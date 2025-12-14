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
import org.themessagesearch.core.model.Document
import org.themessagesearch.core.model.DocumentCreateRequest
import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.core.model.SnapshotId
import org.themessagesearch.core.ports.DocumentRepository
import org.themessagesearch.core.ports.EmbeddingRepository
import java.time.ZoneOffset
import java.util.UUID

// Documents table using DAO API for cleaner mapping.
object DocumentsTable : UUIDTable("documents") {
    val title = text("title")
    val body = text("body")
    val version = long("version")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    // tsvector column is generated in DB migration; not mapped here.
}

// Embeddings table (pgvector); we don't define vec column (unsupported natively in Exposed yet), use raw SQL.
object DocEmbeddingsTable : Table("doc_embeddings") {
    val docId = uuid("doc_id")
    override val primaryKey = PrimaryKey(docId)
}

class ExposedDocumentRepository : DocumentRepository {
    override suspend fun create(request: DocumentCreateRequest): Document = transaction {
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val id = DocumentsTable.insertAndGetId {
            it[title] = request.title
            it[body] = request.body
            it[version] = 1
            it[createdAt] = offsetNow
            it[updatedAt] = offsetNow
        }
        DocumentsTable.select { DocumentsTable.id eq id }
            .limit(1)
            .first()
            .toDocument()
    }

    override suspend fun findById(id: DocumentId, snapshotId: SnapshotId?): Document? = transaction {
        // Snapshot support is forthcoming; ignore snapshotId for now.
        DocumentsTable
            .select { DocumentsTable.id eq EntityID(UUID.fromString(id.value), DocumentsTable) }
            .limit(1)
            .firstOrNull()
            ?.toDocument()
    }

    override suspend fun fetchByIds(ids: Collection<DocumentId>): Map<DocumentId, Document> = transaction {
        if (ids.isEmpty()) return@transaction emptyMap()
        val uuidList = ids.map { UUID.fromString(it.value) }
        DocumentsTable
            .select { DocumentsTable.id inList uuidList }
            .associate { row ->
                val rowId = DocumentId(row[DocumentsTable.id].value.toString())
                rowId to row.toDocument()
            }
    }

    override suspend fun listIdsMissingEmbedding(limit: Int, cursor: DocumentId?): List<DocumentId> = transaction {
        val query = (DocumentsTable leftJoin DocEmbeddingsTable)
            .slice(DocumentsTable.id)
            .select { DocEmbeddingsTable.docId.isNull() }
        cursor?.let {
            val cursorUuid = UUID.fromString(it.value)
            val entity = EntityID(cursorUuid, DocumentsTable)
            query.andWhere { DocumentsTable.id greater entity }
        }
        query.orderBy(DocumentsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { DocumentId(it[DocumentsTable.id].value.toString()) }
    }

    private fun ResultRow.toDocument(): Document = Document(
        id = DocumentId(this[DocumentsTable.id].value.toString()),
        title = this[DocumentsTable.title],
        body = this[DocumentsTable.body],
        version = this[DocumentsTable.version],
        snapshotId = null,
        createdAt = this[DocumentsTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = this[DocumentsTable.updatedAt].toInstant().toKotlinInstant()
    )
}

class ExposedEmbeddingRepository : EmbeddingRepository {
    private val dim = 1536
    private fun requireDim(vector: FloatArray) = require(vector.size == dim) { "Vector length ${vector.size} != $dim" }

    override suspend fun upsertEmbedding(docId: DocumentId, vector: FloatArray) {
        requireDim(vector)
        transaction { exec(singleUpsertSql(docId, vector)) }
    }

    override suspend fun batchUpsertEmbeddings(vectors: Map<DocumentId, FloatArray>) {
        if (vectors.isEmpty()) return
        vectors.values.forEach { requireDim(it) }
        transaction {
            // Use a batch of VALUES clauses to minimize round trips.
            val valuesClauses = buildString {
                vectors.entries.forEachIndexed { idx, (docId, vec) ->
                    if (idx > 0) append(',')
                    append("('" + docId.value + "'::uuid, '" + vec.joinToString(prefix = "[", postfix = "]") { it.toString() } + "'::vector)")
                }
            }
            val sql = """
                INSERT INTO doc_embeddings(doc_id, vec) VALUES $valuesClauses
                ON CONFLICT (doc_id) DO UPDATE SET vec = EXCLUDED.vec
            """.trimIndent()
            exec(sql)
        }
    }

    override suspend fun hasEmbedding(docId: DocumentId): Boolean = transaction {
        !DocEmbeddingsTable.select { DocEmbeddingsTable.docId eq UUID.fromString(docId.value) }.empty()
    }

    private fun singleUpsertSql(docId: DocumentId, vector: FloatArray): String {
        val literal = vector.joinToString(prefix = "[", postfix = "]") { it.toString() }
        return """
            INSERT INTO doc_embeddings(doc_id, vec)
            VALUES ('${docId.value}'::uuid, '$literal'::vector)
            ON CONFLICT (doc_id) DO UPDATE SET vec = EXCLUDED.vec
        """.trimIndent()
    }
}
