package org.themessagesearch.infra.db.repo

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.themessagesearch.core.model.Document
import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.core.ports.DocumentRepository
import org.themessagesearch.core.ports.EmbeddingRepository

object DocumentsTable : Table("documents") {
    val id = uuid("id")
    val title = text("title")
    val body = text("body")
    // tsv column is generated, no need to declare (would require custom expression)
    override val primaryKey = PrimaryKey(id)
}

object DocEmbeddingsTable : Table("doc_embeddings") {
    val docId = uuid("doc_id")
    // vector column not represented (pgvector); we store raw FloatArray through SQL parameter in repository implementation
    override val primaryKey = PrimaryKey(docId)
}

class ExposedDocumentRepository : DocumentRepository {
    override suspend fun insert(document: Document) {
        transaction {
            DocumentsTable.insert {
                it[id] = java.util.UUID.fromString(document.id.value)
                it[title] = document.title
                it[body] = document.body
            }
        }
    }

    override suspend fun findById(id: DocumentId): Document? = transaction {
        DocumentsTable.select { DocumentsTable.id eq java.util.UUID.fromString(id.value) }
            .limit(1).firstOrNull()?.let {
                Document(
                    id = id,
                    title = it[DocumentsTable.title],
                    body = it[DocumentsTable.body]
                )
            }
    }

    override suspend fun listIdsMissingEmbedding(limit: Int): List<DocumentId> = transaction {
        // Left join to find documents without embedding
        (DocumentsTable leftJoin DocEmbeddingsTable)
            .slice(DocumentsTable.id)
            .select { DocEmbeddingsTable.docId.isNull() }
            .limit(limit)
            .map { DocumentId(it[DocumentsTable.id].toString()) }
    }
}

class ExposedEmbeddingRepository : EmbeddingRepository {
    override suspend fun upsertEmbedding(docId: DocumentId, vector: FloatArray) {
        transaction {
            val conn = TransactionManager.current().connection.jdbcConnection
            val vectorLiteral = vector.joinToString(prefix = "[", postfix = "]") { it.toString() }
            conn.prepareStatement(
                "INSERT INTO doc_embeddings(doc_id, vec) VALUES (?::uuid, ?::vector) ON CONFLICT (doc_id) DO UPDATE SET vec = EXCLUDED.vec"
            ).use { ps ->
                ps.setString(1, docId.value)
                ps.setString(2, vectorLiteral)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun hasEmbedding(docId: DocumentId): Boolean = transaction {
        DocEmbeddingsTable.select { DocEmbeddingsTable.docId eq java.util.UUID.fromString(docId.value) }
            .empty().not()
    }
}
