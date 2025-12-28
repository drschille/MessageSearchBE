package org.themessagesearch.infra.search

import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.*

class AnswerServiceImpl(
    private val search: HybridSearchService,
    private val chat: ChatClient
) : AnswerService {
    override suspend fun answer(query: String, limit: Int, weights: HybridWeights): AnswerResponse {
        val searchResponse = search.search(query, limit, offset = 0, weights = weights)
        val contextBodies = searchResponse.results.map { it.snippet ?: it.title }
        val answer = chat.generate(prompt = query, context = contextBodies)
        val citations = searchResponse.results.map {
            Citation(
                documentId = it.id,
                snapshotId = it.snapshotId,
                score = it.finalScore,
                excerpt = it.snippet
            )
        }
        return AnswerResponse(answer = answer, citations = citations)
    }
}

class EmbeddingBackfillServiceImpl(
    private val docRepo: DocumentRepository,
    private val embeddingRepo: EmbeddingRepository,
    private val embedClient: EmbeddingClient
) : EmbeddingBackfillService {
    override suspend fun backfill(batchSize: Int, cursor: DocumentId?): BackfillResult {
        val ids = docRepo.listIdsMissingEmbedding(batchSize, cursor)
        if (ids.isEmpty()) return BackfillResult(processed = 0, nextCursor = null)
        val docsById = docRepo.fetchByIds(ids.toSet())
        val orderedDocs = ids.mapNotNull { id -> docsById[id]?.let { id to it } }
        if (orderedDocs.isEmpty()) return BackfillResult(processed = 0, nextCursor = ids.lastOrNull()?.value)
        val payloads = mutableMapOf<DocumentId, FloatArray>()
        val texts = orderedDocs.map { (_, doc) -> doc.title + "\n" + doc.body }
        val vectors = embedClient.embed(texts)
        orderedDocs.forEachIndexed { idx, (id, _) ->
            val vec = vectors.getOrNull(idx) ?: return@forEachIndexed
            payloads[id] = vec
        }
        embeddingRepo.batchUpsertEmbeddings(payloads)
        val nextCursor = ids.lastOrNull()?.value
        return BackfillResult(processed = orderedDocs.size, nextCursor = nextCursor)
    }
}
