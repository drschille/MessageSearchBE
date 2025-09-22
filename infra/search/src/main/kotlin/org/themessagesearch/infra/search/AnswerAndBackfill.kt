package org.themessagesearch.infra.search

import org.themessagesearch.core.model.AnswerResponse
import org.themessagesearch.core.model.HybridWeights
import org.themessagesearch.core.ports.*
import org.themessagesearch.core.model.DocumentId

class AnswerServiceImpl(
    private val search: HybridSearchService,
    private val chat: ChatClient
) : AnswerService {
    override suspend fun answer(query: String, topK: Int, weights: HybridWeights): AnswerResponse {
        val results = search.search(query, topK, weights)
        val contextBodies = results.map { it.title } // TODO include snippet of body; need body in search query output or second fetch
        val answer = chat.generate(prompt = query, context = contextBodies)
        return AnswerResponse(answer = answer, citations = results.map { it.id })
    }
}

class EmbeddingBackfillServiceImpl(
    private val docRepo: DocumentRepository,
    private val embeddingRepo: EmbeddingRepository,
    private val embedClient: EmbeddingClient
) : EmbeddingBackfillService {
    override suspend fun backfill(batchSize: Int): Int {
        val ids = docRepo.listIdsMissingEmbedding(batchSize)
        if (ids.isEmpty()) return 0
        // For now fetch each doc individually (could batch)
        var processed = 0
        for (id in ids) {
            // naive; in future fetch docs in batch
            val doc = docRepo.findById(id) ?: continue
            val vec = embedClient.embed(listOf(doc.title + "\n" + doc.body)).first()
            embeddingRepo.upsertEmbedding(DocumentId(doc.id.value), vec)
            processed++
        }
        return processed
    }
}

