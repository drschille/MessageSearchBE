package org.themessagesearch.infra.search

import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.*

class AnswerServiceImpl(
    private val search: HybridSearchService,
    private val chat: ChatClient
) : AnswerService {
    override suspend fun answer(query: String, limit: Int, weights: HybridWeights, languageCode: String?): AnswerResponse {
        val searchResponse = search.search(query, limit, offset = 0, weights = weights, languageCode = languageCode)
        val contextBodies = searchResponse.results.map { it.snippet ?: it.title }
        val answer = chat.generate(prompt = query, context = contextBodies)
        val citations = searchResponse.results.map {
            Citation(
                documentId = it.documentId,
                snapshotId = it.snapshotId,
                paragraphId = it.paragraphId,
                languageCode = it.languageCode,
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
    override suspend fun backfill(batchSize: Int, cursor: ParagraphId?, languageCode: String?): BackfillResult {
        val paragraphs = docRepo.listParagraphsMissingEmbedding(batchSize, cursor, languageCode)
        if (paragraphs.isEmpty()) return BackfillResult(processed = 0, nextParagraphCursor = cursor?.value)
        val ordered = paragraphs
        val payloads = mutableMapOf<ParagraphId, FloatArray>()
        val texts = ordered.map { paragraphText(it) }
        val vectors = embedClient.embed(texts)
        ordered.forEachIndexed { idx, paragraph ->
            val vec = vectors.getOrNull(idx) ?: return@forEachIndexed
            payloads[paragraph.id] = vec
        }
        embeddingRepo.batchUpsertParagraphEmbeddings(payloads)
        val nextCursor = ordered.lastOrNull()?.id?.value
        return BackfillResult(processed = ordered.size, nextParagraphCursor = nextCursor)
    }

    private fun paragraphText(paragraph: DocumentParagraph): String {
        val headingPrefix = paragraph.heading?.let { "$it\n" } ?: ""
        return headingPrefix + paragraph.body
    }
}
