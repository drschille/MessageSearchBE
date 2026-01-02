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
    val workflowState = text("workflow_state")
    val languageCode = text("language_code")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val snapshotId = uuid("snapshot_id").nullable()
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
    override suspend fun create(request: DocumentCreateRequest, actorId: UserId?): Document = transaction {
        require(request.paragraphs.isNotEmpty()) { "Document requires at least one paragraph" }
        val normalizedParagraphs = request.paragraphs.sortedBy { it.position }
        require(normalizedParagraphs.all { it.languageCode == request.languageCode }) {
            "Paragraph languages must match document language ${request.languageCode}"
        }
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val aggregateBody = normalizedParagraphs.joinToString("\n\n") { it.body }
        val snapshotId = if (request.publish) SnapshotId.random() else null
        val workflowState = if (request.publish) DocumentWorkflowState.PUBLISHED else DocumentWorkflowState.DRAFT
        val id = DocumentsTable.insertAndGetId {
            it[title] = request.title
            it[body] = aggregateBody
            it[version] = 1
            it[DocumentsTable.workflowState] = workflowState.dbValue()
            it[languageCode] = request.languageCode
            it[createdAt] = offsetNow
            it[updatedAt] = offsetNow
            it[DocumentsTable.snapshotId] = null
        }
        val auditAction = if (request.publish) "publish" else "draft.created"
        val auditToState = if (request.publish) DocumentWorkflowState.PUBLISHED.dbValue() else DocumentWorkflowState.DRAFT.dbValue()
        DocumentAuditsTable.insert {
            it[id] = UUID.randomUUID()
            it[documentId] = id.value
            it[actorId] = UUID.fromString(actorId?.value ?: ZERO_UUID)
            it[action] = auditAction
            it[reason] = null
            it[fromState] = null
            it[toState] = auditToState
            it[DocumentAuditsTable.snapshotId] = snapshotId?.let { sid -> UUID.fromString(sid.value) }
            it[diffSummary] = null
            it[requestId] = null
            it[ipFingerprint] = null
            it[createdAt] = offsetNow
        }
        if (request.publish && snapshotId != null) {
            val createdBy = actorId?.value ?: ZERO_UUID
            SnapshotsTable.insert {
                it[SnapshotsTable.id] = UUID.fromString(snapshotId.value)
                it[SnapshotsTable.documentId] = id.value
                it[SnapshotsTable.version] = 1
                it[SnapshotsTable.state] = "published"
                it[SnapshotsTable.title] = request.title
                it[SnapshotsTable.body] = aggregateBody
                it[SnapshotsTable.languageCode] = request.languageCode
                it[SnapshotsTable.createdAt] = offsetNow
                it[SnapshotsTable.createdBy] = UUID.fromString(createdBy)
                it[SnapshotsTable.sourceDraftId] = null
                it[SnapshotsTable.sourceRevision] = null
            }
            DocumentsTable.update({ DocumentsTable.id eq id }) {
                it[DocumentsTable.snapshotId] = UUID.fromString(snapshotId.value)
            }
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
        if (snapshotId != null) {
            val snapshotRow = SnapshotsTable.select {
                (SnapshotsTable.documentId eq UUID.fromString(id.value)) and
                    (SnapshotsTable.id eq EntityID(UUID.fromString(snapshotId.value), SnapshotsTable))
            }.limit(1).firstOrNull() ?: return@transaction null
            if (languageCode != null && snapshotRow[SnapshotsTable.languageCode] != languageCode) {
                return@transaction null
            }
            return@transaction snapshotRow.toSnapshotDocument()
        }
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
                row.toParagraphRow()
            }
    }

    override suspend fun listDocuments(
        limit: Int,
        offset: Int,
        languageCode: String?,
        title: String?,
        state: DocumentWorkflowState?
    ): DocumentListResponse = transaction {
        val countQuery = DocumentsTable.selectAll()
        languageCode?.let { lang ->
            countQuery.andWhere { DocumentsTable.languageCode eq lang }
        }
        title?.let { t ->
            countQuery.andWhere { DocumentsTable.title eq t }
        }
        state?.let { desired ->
            countQuery.andWhere { DocumentsTable.workflowState eq desired.dbValue() }
        }
        val total = countQuery.count()
        val listQuery = DocumentsTable.selectAll()
        languageCode?.let { lang ->
            listQuery.andWhere { DocumentsTable.languageCode eq lang }
        }
        title?.let { t ->
            listQuery.andWhere { DocumentsTable.title eq t }
        }
        state?.let { desired ->
            listQuery.andWhere { DocumentsTable.workflowState eq desired.dbValue() }
        }
        val items = listQuery
            .orderBy(DocumentsTable.updatedAt to SortOrder.DESC)
            .limit(limit, offset.toLong())
            .map { row ->
                DocumentListItem(
                    id = row[DocumentsTable.id].value.toString(),
                    title = row[DocumentsTable.title],
                    workflowState = row[DocumentsTable.workflowState].toWorkflowState(),
                    languageCode = row[DocumentsTable.languageCode],
                    createdAt = row[DocumentsTable.createdAt].toInstant().toKotlinInstant(),
                    updatedAt = row[DocumentsTable.updatedAt].toInstant().toKotlinInstant()
                )
            }
        DocumentListResponse(total = total, limit = limit, offset = offset, items = items)
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
            .mapValues { (_, rows) -> rows.map { it.toParagraphRow() } }
    }

    private fun ResultRow.toDocument(paragraphs: List<DocumentParagraph>): Document = Document(
        id = DocumentId(this[DocumentsTable.id].value.toString()),
        title = this[DocumentsTable.title],
        body = this[DocumentsTable.body],
        version = this[DocumentsTable.version],
        workflowState = this[DocumentsTable.workflowState].toWorkflowState(),
        languageCode = this[DocumentsTable.languageCode],
        paragraphs = paragraphs,
        snapshotId = this[DocumentsTable.snapshotId]?.let { SnapshotId(it.toString()) },
        createdAt = this[DocumentsTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = this[DocumentsTable.updatedAt].toInstant().toKotlinInstant()
    )

}

private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

private fun ResultRow.toSnapshotDocument(): Document {
    val docId = UUID.fromString(this[SnapshotsTable.documentId].toString())
    val paragraphs = DocumentParagraphsTable
        .select { DocumentParagraphsTable.documentId eq EntityID(docId, DocumentsTable) }
        .orderBy(DocumentParagraphsTable.position to SortOrder.ASC)
        .map { it.toParagraphRow() }
    val snapshotState = this[SnapshotsTable.state]
    return Document(
        id = DocumentId(this[SnapshotsTable.documentId].toString()),
        title = this[SnapshotsTable.title],
        body = this[SnapshotsTable.body],
        version = this[SnapshotsTable.version],
        workflowState = if (snapshotState == "archived") DocumentWorkflowState.ARCHIVED else DocumentWorkflowState.PUBLISHED,
        languageCode = this[SnapshotsTable.languageCode],
        paragraphs = paragraphs,
        snapshotId = SnapshotId(this[SnapshotsTable.id].value.toString()),
        createdAt = this[SnapshotsTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = this[SnapshotsTable.createdAt].toInstant().toKotlinInstant()
    )
}

private fun ResultRow.toParagraphRow(): DocumentParagraph = DocumentParagraph(
    id = ParagraphId(this[DocumentParagraphsTable.id].value.toString()),
    documentId = DocumentId(this[DocumentParagraphsTable.documentId].value.toString()),
    position = this[DocumentParagraphsTable.position],
    heading = this[DocumentParagraphsTable.heading],
    body = this[DocumentParagraphsTable.body],
    languageCode = this[DocumentParagraphsTable.languageCode],
    createdAt = this[DocumentParagraphsTable.createdAt].toInstant().toKotlinInstant(),
    updatedAt = this[DocumentParagraphsTable.updatedAt].toInstant().toKotlinInstant()
)

private fun DocumentWorkflowState.dbValue(): String = when (this) {
    DocumentWorkflowState.DRAFT -> "draft"
    DocumentWorkflowState.IN_REVIEW -> "in_review"
    DocumentWorkflowState.PUBLISHED -> "published"
    DocumentWorkflowState.ARCHIVED -> "archived"
}

private fun String.toWorkflowState(): DocumentWorkflowState = when (this) {
    "draft" -> DocumentWorkflowState.DRAFT
    "in_review" -> DocumentWorkflowState.IN_REVIEW
    "published" -> DocumentWorkflowState.PUBLISHED
    "archived" -> DocumentWorkflowState.ARCHIVED
    else -> error("Unknown workflow state $this")
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
