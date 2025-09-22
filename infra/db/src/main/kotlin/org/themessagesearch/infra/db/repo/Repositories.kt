package org.themessagesearch.infra.db.repo

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.themessagesearch.core.model.Document
import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.core.ports.DocumentRepository
import org.themessagesearch.core.ports.EmbeddingRepository
import java.util.UUID

// Documents table using DAO API for cleaner mapping.
object DocumentsTable : UUIDTable("documents") {
    val title = text("title")
    val body = text("body")
    // tsvector column is generated in DB migration; not mapped here.
}

class DocumentEntity(id: EntityID<UUID>) : UUIDEntity(id) {
    companion object : UUIDEntityClass<DocumentEntity>(DocumentsTable)
    var title by DocumentsTable.title
    var body by DocumentsTable.body
}

// Embeddings table (pgvector); we don't define vec column (unsupported natively in Exposed yet), use raw SQL.
object DocEmbeddingsTable : Table("doc_embeddings") {
    val docId = uuid("doc_id")
    override val primaryKey = PrimaryKey(docId)
}

class ExposedDocumentRepository : DocumentRepository {
    override suspend fun insert(document: Document) {
        transaction {
            DocumentEntity.new(UUID.fromString(document.id.value)) {
                title = document.title
                body = document.body
            }
        }
    }

    override suspend fun findById(id: DocumentId): Document? = transaction {
        DocumentEntity.findById(UUID.fromString(id.value))?.let {
            Document(id = DocumentId(it.id.value.toString()), title = it.title, body = it.body)
        }
    }

    override suspend fun listIdsMissingEmbedding(limit: Int): List<DocumentId> = transaction {
        // LEFT JOIN to find documents without embedding
        (DocumentsTable leftJoin DocEmbeddingsTable)
            .slice(DocumentsTable.id)
            .select { DocEmbeddingsTable.docId.isNull() }
            .limit(limit)
            .map { DocumentId(it[DocumentsTable.id].value.toString()) }
    }
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
