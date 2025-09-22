package org.themessagesearch.app.di

import org.themessagesearch.app.AppConfig
import org.themessagesearch.core.ports.*
import org.themessagesearch.infra.db.DatabaseFactory
import org.themessagesearch.infra.db.repo.ExposedDocumentRepository
import org.themessagesearch.infra.db.repo.ExposedEmbeddingRepository
import org.themessagesearch.infra.search.HybridSearchServiceImpl
import org.themessagesearch.infra.search.AnswerServiceImpl
import org.themessagesearch.infra.search.EmbeddingBackfillServiceImpl
import org.themessagesearch.infra.ai.StubEmbeddingClient
import org.themessagesearch.infra.ai.StubChatClient

object ServiceRegistry {
    data class Registry(
        val documentRepo: DocumentRepository,
        val embeddingRepo: EmbeddingRepository,
        val searchService: HybridSearchService,
        val answerService: AnswerService,
        val backfillService: EmbeddingBackfillService,
        val openApiSpec: Map<String, Any>
    )

    fun init(cfg: AppConfig): Registry {
        DatabaseFactory.init(cfg.db)

        val embeddingClient = StubEmbeddingClient() // TODO replace with real provider based on cfg.ai.provider
        val chatClient = StubChatClient() // TODO replace with configurable chat provider

        val docRepo = ExposedDocumentRepository()
        val embRepo = ExposedEmbeddingRepository()
        val search = HybridSearchServiceImpl(embeddingClient, candidateK = cfg.search.k)
        val answer = AnswerServiceImpl(search, chatClient)
        val backfill = EmbeddingBackfillServiceImpl(docRepo, embRepo, embeddingClient)

        val openApi = mapOf(
            "openapi" to "3.0.0",
            "info" to mapOf("title" to "MessageSearch API", "version" to "0.1.0"),
            "paths" to mapOf(
                "/v1/documents" to mapOf("post" to mapOf("summary" to "Create document")),
                "/v1/documents/{id}" to mapOf("get" to mapOf("summary" to "Get document")),
                "/v1/search" to mapOf("post" to mapOf("summary" to "Hybrid search")),
                "/v1/answer" to mapOf("post" to mapOf("summary" to "RAG answer")),
                "/v1/ingest/embed" to mapOf("post" to mapOf("summary" to "Backfill embeddings"))
            )
        )

        return Registry(
            documentRepo = docRepo,
            embeddingRepo = embRepo,
            searchService = search,
            answerService = answer,
            backfillService = backfill,
            openApiSpec = openApi
        )
    }
}

