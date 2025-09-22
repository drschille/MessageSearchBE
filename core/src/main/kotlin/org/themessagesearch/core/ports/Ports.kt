package org.themessagesearch.core.ports

import org.themessagesearch.core.model.*

interface DocumentRepository {
    suspend fun insert(document: Document)
    suspend fun findById(id: DocumentId): Document?
    suspend fun listIdsMissingEmbedding(limit: Int): List<DocumentId>
}

interface EmbeddingRepository {
    suspend fun upsertEmbedding(docId: DocumentId, vector: FloatArray)
    suspend fun batchUpsertEmbeddings(vectors: Map<DocumentId, FloatArray>)
    suspend fun hasEmbedding(docId: DocumentId): Boolean
}

interface HybridSearchService {
    suspend fun search(query: String, limit: Int?, weights: HybridWeights): List<SearchResultItem>
}

interface EmbeddingClient {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

interface ChatClient {
    suspend fun generate(prompt: String, context: List<String>): String
}

interface AnswerService {
    suspend fun answer(query: String, topK: Int, weights: HybridWeights): AnswerResponse
}

interface EmbeddingBackfillService {
    suspend fun backfill(batchSize: Int): Int
}
