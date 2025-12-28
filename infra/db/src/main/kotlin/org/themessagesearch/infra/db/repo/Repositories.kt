package org.themessagesearch.infra.db.repo

import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.DocumentRepository
import org.themessagesearch.core.ports.EmbeddingRepository
import java.time.ZoneOffset
import java.util.UUID

// Documents table using DAO API for cleaner mapping.
object DocumentsTable : UUIDTable("documents") {
    val title = text("title")
    val body = text("body")
    val version = long("version")
    val languageCode = text("language_code")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    // tsvector column is generated in DB migration; not mapped here.
}

object DocumentParagraphsTable : UUIDTable("document_paragraphs") {
    val documentId = reference("document_id", DocumentsTable)
    val languageCode = text("language_code")
    val position = integer("position")
    val heading = text("heading").nullable()
    val body = text("body")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

// Embeddings table (pgvector); use raw SQL for vector column operations.
object ParagraphEmbeddingsTable : Table("paragraph_embeddings") {
    val paragraphId = uuid("paragraph_id")
    override val primaryKey = PrimaryKey(paragraphId)
}

class ExposedDocumentRepository : DocumentRepository {
    override suspend fun create(request: DocumentCreateRequest): Document = transaction {
        require(request.paragraphs.isNotEmpty()) { "Document requires at least one paragraph" }
        val normalizedParagraphs = request.paragraphs.sortedBy { it.position }
        require(normalizedParagraphs.all { it.languageCode == request.languageCode }) {
            "Paragraph languages must match document language ${request.languageCode}"
        }
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val aggregateBody = normalizedParagraphs.joinToString("\n\n") { it.body }
        val id = DocumentsTable.insertAndGetId {
            it[title] = request.title
            it[body] = aggregateBody
            it[version] = 1
            it[languageCode] = request.languageCode
            it[createdAt] = offsetNow
            it[updatedAt] = offsetNow
        }
        normalizedParagraphs.forEach { paragraph ->
            DocumentParagraphsTable.insert {
                it[documentId] = id
                it[languageCode] = paragraph.languageCode
                it[position] = paragraph.position
                it[heading] = paragraph.heading
                it[body] = paragraph.body
                it[createdAt] = offsetNow
                it[updatedAt] = offsetNow
            }
        }
        loadDocument(id.value, request.languageCode)
            ?: error("Failed to load document ${id.value}")
    }

    override suspend fun findById(id: DocumentId, snapshotId: SnapshotId?, languageCode: String?): Document? = transaction {
        // Snapshot support is forthcoming; ignore snapshotId for now.
        loadDocument(UUID.fromString(id.value), languageCode)
    }

    override suspend fun fetchByIds(ids: Collection<DocumentId>, languageCode: String?): Map<DocumentId, Document> = transaction {
        if (ids.isEmpty()) return@transaction emptyMap()
        val uuidList = ids.map { UUID.fromString(it.value) }
        val rows = DocumentsTable.select { DocumentsTable.id inList uuidList }.toList()
        val paragraphsByDoc = loadParagraphs(uuidList, languageCode)
        rows.mapNotNull { row ->
            val docLanguage = row[DocumentsTable.languageCode]
            if (languageCode != null && docLanguage != languageCode) return@mapNotNull null
            val docId = row[DocumentsTable.id].value
            val paragraphs = paragraphsByDoc[docId].orEmpty()
            val document = row.toDocument(paragraphs)
            DocumentId(docId.toString()) to document
        }.toMap()
    }

    override suspend fun listParagraphsMissingEmbedding(limit: Int, cursor: ParagraphId?, languageCode: String?): List<DocumentParagraph> = transaction {
        val base = (DocumentParagraphsTable leftJoin ParagraphEmbeddingsTable)
            .select { ParagraphEmbeddingsTable.paragraphId.isNull() }
        languageCode?.let { lang ->
            base.andWhere { DocumentParagraphsTable.languageCode eq lang }
        }
        cursor?.let {
            val cursorUuid = UUID.fromString(it.value)
            val entity = EntityID(cursorUuid, DocumentParagraphsTable)
            base.andWhere { DocumentParagraphsTable.id greater entity }
        }
        base.orderBy(DocumentParagraphsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                row.toParagraph()
            }
    }

    private fun Transaction.loadDocument(id: UUID, languageCode: String?): Document? {
        val row = DocumentsTable
            .select { DocumentsTable.id eq EntityID(id, DocumentsTable) }
            .limit(1)
            .firstOrNull()
            ?: return null
        if (languageCode != null && row[DocumentsTable.languageCode] != languageCode) return null
        val paragraphs = loadParagraphs(listOf(id), languageCode)[id].orEmpty()
        return row.toDocument(paragraphs)
    }

    private fun Transaction.loadParagraphs(
        documentIds: Collection<UUID>,
        languageCode: String?
    ): Map<UUID, List<DocumentParagraph>> {
        if (documentIds.isEmpty()) return emptyMap()
        val query = DocumentParagraphsTable
            .select { DocumentParagraphsTable.documentId inList documentIds.map { EntityID(it, DocumentsTable) } }
        languageCode?.let { lang ->
            query.andWhere { DocumentParagraphsTable.languageCode eq lang }
        }
        return query
            .orderBy(DocumentParagraphsTable.position to SortOrder.ASC)
            .groupBy { it[DocumentParagraphsTable.documentId].value }
            .mapValues { (_, rows) -> rows.map { it.toParagraph() } }
    }

    private fun ResultRow.toDocument(paragraphs: List<DocumentParagraph>): Document = Document(
        id = DocumentId(this[DocumentsTable.id].value.toString()),
        title = this[DocumentsTable.title],
        body = this[DocumentsTable.body],
        version = this[DocumentsTable.version],
        languageCode = this[DocumentsTable.languageCode],
        paragraphs = paragraphs,
        snapshotId = null,
        createdAt = this[DocumentsTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = this[DocumentsTable.updatedAt].toInstant().toKotlinInstant()
    )

    private fun ResultRow.toParagraph(): DocumentParagraph = DocumentParagraph(
        id = ParagraphId(this[DocumentParagraphsTable.id].value.toString()),
        documentId = DocumentId(this[DocumentParagraphsTable.documentId].value.toString()),
        position = this[DocumentParagraphsTable.position],
        heading = this[DocumentParagraphsTable.heading],
        body = this[DocumentParagraphsTable.body],
        languageCode = this[DocumentParagraphsTable.languageCode],
        createdAt = this[DocumentParagraphsTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = this[DocumentParagraphsTable.updatedAt].toInstant().toKotlinInstant()
    )
}

class ExposedEmbeddingRepository : EmbeddingRepository {
    private val dim = 1536
    private fun requireDim(vector: FloatArray) = require(vector.size == dim) { "Vector length ${vector.size} != $dim" }

    override suspend fun upsertParagraphEmbedding(paragraphId: ParagraphId, vector: FloatArray) {
        requireDim(vector)
        transaction { exec(singleUpsertSql(paragraphId, vector)) }
    }

    override suspend fun batchUpsertParagraphEmbeddings(vectors: Map<ParagraphId, FloatArray>) {
        if (vectors.isEmpty()) return
        vectors.values.forEach { requireDim(it) }
        transaction {
            val valuesClauses = buildString {
                vectors.entries.forEachIndexed { idx, (paragraphId, vec) ->
                    if (idx > 0) append(',')
                    append("('" + paragraphId.value + "'::uuid, '" + vec.joinToString(prefix = "[", postfix = "]") { it.toString() } + "'::vector)")
                }
            }
            val sql = """
                INSERT INTO paragraph_embeddings(paragraph_id, vec) VALUES $valuesClauses
                ON CONFLICT (paragraph_id) DO UPDATE SET vec = EXCLUDED.vec
            """.trimIndent()
            exec(sql)
        }
    }

    override suspend fun hasParagraphEmbedding(paragraphId: ParagraphId): Boolean = transaction {
        !ParagraphEmbeddingsTable.select { ParagraphEmbeddingsTable.paragraphId eq UUID.fromString(paragraphId.value) }.empty()
    }

    private fun singleUpsertSql(paragraphId: ParagraphId, vector: FloatArray): String {
        val literal = vector.joinToString(prefix = "[", postfix = "]") { it.toString() }
        return """
            INSERT INTO paragraph_embeddings(paragraph_id, vec)
            VALUES ('${paragraphId.value}'::uuid, '$literal'::vector)
            ON CONFLICT (paragraph_id) DO UPDATE SET vec = EXCLUDED.vec
        """.trimIndent()
    }
}
