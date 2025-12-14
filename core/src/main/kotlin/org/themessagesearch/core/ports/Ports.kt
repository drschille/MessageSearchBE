package org.themessagesearch.core.ports

import org.themessagesearch.core.model.*

interface DocumentRepository {
    suspend fun create(request: DocumentCreateRequest): Document
    suspend fun findById(id: DocumentId, snapshotId: SnapshotId? = null): Document?
    suspend fun fetchByIds(ids: Collection<DocumentId>): Map<DocumentId, Document>
    suspend fun listIdsMissingEmbedding(limit: Int, cursor: DocumentId? = null): List<DocumentId>
}

interface EmbeddingRepository {
    suspend fun upsertEmbedding(docId: DocumentId, vector: FloatArray)
    suspend fun batchUpsertEmbeddings(vectors: Map<DocumentId, FloatArray>)
    suspend fun hasEmbedding(docId: DocumentId): Boolean
}

interface HybridSearchService {
    suspend fun search(query: String, limit: Int, offset: Int, weights: HybridWeights): SearchResponse
}

interface EmbeddingClient {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

interface ChatClient {
    suspend fun generate(prompt: String, context: List<String>): String
}

interface AnswerService {
    suspend fun answer(query: String, limit: Int, weights: HybridWeights): AnswerResponse
}

interface EmbeddingBackfillService {
    suspend fun backfill(batchSize: Int, cursor: DocumentId?): BackfillResult
}
