package org.themessagesearch.app.di

import org.themessagesearch.app.AppConfig
import org.themessagesearch.core.ports.*
import org.themessagesearch.infra.db.DatabaseFactory
import org.themessagesearch.infra.db.repo.ExposedCollaborationRepository
import org.themessagesearch.infra.db.repo.ExposedDocumentRepository
import org.themessagesearch.infra.db.repo.ExposedEmbeddingRepository
import org.themessagesearch.infra.db.repo.ExposedUserRepository
import org.themessagesearch.infra.search.HybridSearchServiceImpl
import org.themessagesearch.infra.search.AnswerServiceImpl
import org.themessagesearch.infra.search.EmbeddingBackfillServiceImpl
import org.themessagesearch.infra.ai.StubEmbeddingClient
import org.themessagesearch.infra.ai.StubChatClient

object ServiceRegistry {
    data class Registry(
        val documentRepo: DocumentRepository,
        val embeddingRepo: EmbeddingRepository,
        val collaborationRepo: CollaborationRepository,
        val userRepo: UserRepository,
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
        val collabRepo = ExposedCollaborationRepository()
        val userRepo = ExposedUserRepository()
        val search = HybridSearchServiceImpl(embeddingClient, candidateK = cfg.search.k)
        val answer = AnswerServiceImpl(search, chatClient)
        val backfill = EmbeddingBackfillServiceImpl(docRepo, embRepo, embeddingClient)

        val openApi = mapOf(
            "openapi" to "3.0.0",
            "info" to mapOf("title" to "MessageSearch API", "version" to "0.1.0"),
            "paths" to mapOf(
                "/v1/documents" to mapOf(
                    "post" to mapOf(
                        "summary" to "Create document",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/DocumentCreateRequest"),
                                    "example" to mapOf(
                                        "title" to "Sample title",
                                        "languageCode" to "en-US",
                                        "paragraphs" to listOf(
                                            mapOf("position" to 0, "heading" to "Intro", "body" to "First paragraph.", "languageCode" to "en-US"),
                                            mapOf("position" to 1, "body" to "Second paragraph.", "languageCode" to "en-US")
                                        ),
                                        "publish" to true
                                    )
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "201" to mapOf(
                                "description" to "Created",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/DocumentResponse"),
                                        "example" to mapOf(
                                            "id" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                            "title" to "Sample title",
                                            "body" to "First paragraph.\n\nSecond paragraph.",
                                            "version" to 1,
                                            "snapshotId" to "9c2f2b2d-5aef-4b36-9e64-0a7e3c6af9cb",
                                            "languageCode" to "en-US",
                                            "paragraphs" to listOf(
                                                mapOf(
                                                    "id" to "4b4f3a6b-3e20-4f17-8b6c-6a1f7b4b84ef",
                                                    "documentId" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                    "position" to 0,
                                                    "heading" to "Intro",
                                                    "body" to "First paragraph.",
                                                    "languageCode" to "en-US",
                                                    "createdAt" to "2024-01-01T00:00:00Z",
                                                    "updatedAt" to "2024-01-01T00:00:00Z"
                                                ),
                                                mapOf(
                                                    "id" to "c7a4a0e5-9f2e-4d1b-8b78-3d9b9d935f0d",
                                                    "documentId" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                    "position" to 1,
                                                    "heading" to null,
                                                    "body" to "Second paragraph.",
                                                    "languageCode" to "en-US",
                                                    "createdAt" to "2024-01-01T00:00:00Z",
                                                    "updatedAt" to "2024-01-01T00:00:00Z"
                                                )
                                            ),
                                            "createdAt" to "2024-01-01T00:00:00Z",
                                            "updatedAt" to "2024-01-01T00:00:00Z"
                                        )
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid payload")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid or missing token")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "forbidden")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/documents:batch" to mapOf(
                    "post" to mapOf(
                        "summary" to "Batch create documents",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/DocumentCreateBatchRequest"),
                                    "example" to mapOf(
                                        "documents" to listOf(
                                            mapOf(
                                                "title" to "Sample title",
                                                "languageCode" to "en-US",
                                                "paragraphs" to listOf(
                                                    mapOf("position" to 0, "body" to "First paragraph.", "languageCode" to "en-US")
                                                ),
                                                "publish" to true
                                            ),
                                            mapOf(
                                                "title" to "Second title",
                                                "languageCode" to "en-US",
                                                "paragraphs" to listOf(
                                                    mapOf("position" to 0, "body" to "Another paragraph.", "languageCode" to "en-US")
                                                ),
                                                "publish" to true
                                            )
                                        )
                                    )
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "Batch results",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/DocumentCreateBatchResponse"),
                                        "example" to mapOf(
                                            "created" to 2,
                                            "failed" to 0,
                                            "results" to listOf(
                                                mapOf(
                                                    "index" to 0,
                                                    "document" to mapOf(
                                                        "id" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                        "title" to "Sample title",
                                                        "body" to "First paragraph.",
                                                        "version" to 1,
                                                        "snapshotId" to "9c2f2b2d-5aef-4b36-9e64-0a7e3c6af9cb",
                                                        "languageCode" to "en-US",
                                                        "paragraphs" to listOf(
                                                            mapOf(
                                                                "id" to "4b4f3a6b-3e20-4f17-8b6c-6a1f7b4b84ef",
                                                                "documentId" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                                "position" to 0,
                                                                "heading" to null,
                                                                "body" to "First paragraph.",
                                                                "languageCode" to "en-US",
                                                                "createdAt" to "2024-01-01T00:00:00Z",
                                                                "updatedAt" to "2024-01-01T00:00:00Z"
                                                            )
                                                        ),
                                                        "createdAt" to "2024-01-01T00:00:00Z",
                                                        "updatedAt" to "2024-01-01T00:00:00Z"
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "batch size 120 exceeds max 100")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid or missing token")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "forbidden")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/documents/{id}" to mapOf(
                    "get" to mapOf(
                        "summary" to "Get document",
                        "parameters" to listOf(
                            mapOf("name" to "language_code", "in" to "query", "schema" to mapOf("type" to "string")),
                            mapOf("name" to "snapshot_id", "in" to "query", "schema" to mapOf("type" to "string"))
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/DocumentResponse"),
                                        "example" to mapOf(
                                            "id" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                            "title" to "Sample title",
                                            "body" to "First paragraph.\n\nSecond paragraph.",
                                            "version" to 1,
                                            "snapshotId" to "9c2f2b2d-5aef-4b36-9e64-0a7e3c6af9cb",
                                            "languageCode" to "en-US",
                                            "paragraphs" to listOf(
                                                mapOf(
                                                    "id" to "4b4f3a6b-3e20-4f17-8b6c-6a1f7b4b84ef",
                                                    "documentId" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                    "position" to 0,
                                                    "heading" to "Intro",
                                                    "body" to "First paragraph.",
                                                    "languageCode" to "en-US",
                                                    "createdAt" to "2024-01-01T00:00:00Z",
                                                    "updatedAt" to "2024-01-01T00:00:00Z"
                                                )
                                            ),
                                            "createdAt" to "2024-01-01T00:00:00Z",
                                            "updatedAt" to "2024-01-01T00:00:00Z"
                                        )
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid document id")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid or missing token")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "forbidden")
                                    )
                                )
                            ),
                            "404" to mapOf(
                                "description" to "Not Found",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "document not found")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/search" to mapOf(
                    "post" to mapOf(
                        "summary" to "Hybrid search",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/SearchRequest"),
                                    "example" to mapOf(
                                        "query" to "policy update",
                                        "limit" to 10,
                                        "offset" to 0,
                                        "languageCode" to "en-US",
                                        "weights" to mapOf("text" to 0.35, "vector" to 0.65)
                                    )
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/SearchResponse"),
                                        "example" to mapOf(
                                            "total" to 1,
                                            "limit" to 10,
                                            "offset" to 0,
                                            "results" to listOf(
                                                mapOf(
                                                    "documentId" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                    "paragraphId" to "4b4f3a6b-3e20-4f17-8b6c-6a1f7b4b84ef",
                                                    "snapshotId" to "9c2f2b2d-5aef-4b36-9e64-0a7e3c6af9cb",
                                                    "languageCode" to "en-US",
                                                    "title" to "Sample title",
                                                    "snippet" to "First paragraph.",
                                                    "textScore" to 0.42,
                                                    "vectorScore" to 0.78,
                                                    "finalScore" to 0.63
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid search request")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid or missing token")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "forbidden")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/answer" to mapOf(
                    "post" to mapOf(
                        "summary" to "RAG answer",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/AnswerRequest"),
                                    "example" to mapOf(
                                        "query" to "What changed in the policy?",
                                        "limit" to 5,
                                        "languageCode" to "en-US"
                                    )
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/AnswerResponse"),
                                        "example" to mapOf(
                                            "answer" to "The policy now requires a two-step review process.",
                                            "citations" to listOf(
                                                mapOf(
                                                    "documentId" to "2b6b7e5e-7fe9-47b1-8fa2-31a3f2ad2f5c",
                                                    "paragraphId" to "4b4f3a6b-3e20-4f17-8b6c-6a1f7b4b84ef",
                                                    "snapshotId" to "9c2f2b2d-5aef-4b36-9e64-0a7e3c6af9cb",
                                                    "languageCode" to "en-US",
                                                    "score" to 0.63,
                                                    "excerpt" to "First paragraph."
                                                )
                                            ),
                                            "tokensUsed" to mapOf("prompt" to 120, "completion" to 24)
                                        )
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid answer request")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid or missing token")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "forbidden")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/ingest/embed" to mapOf(
                    "post" to mapOf(
                        "summary" to "Backfill paragraph embeddings",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/EmbedBackfillRequest"),
                                    "example" to mapOf(
                                        "batchSize" to 100,
                                        "paragraphCursor" to null,
                                        "languageCode" to "en-US"
                                    )
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/BackfillResult"),
                                        "example" to mapOf(
                                            "processed" to 100,
                                            "nextParagraphCursor" to "c7a4a0e5-9f2e-4d1b-8b78-3d9b9d935f0d"
                                        )
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid paragraph cursor")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "invalid or missing token")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse"),
                                        "example" to mapOf("error" to "forbidden")
                                    )
                                )
                            )
                        )
                    )
                )
                ,
                "/v1/users/me" to mapOf(
                    "get" to mapOf(
                        "summary" to "Get current user profile",
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserProfileResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/users" to mapOf(
                    "get" to mapOf(
                        "summary" to "List users",
                        "parameters" to listOf(
                            mapOf("name" to "limit", "in" to "query", "schema" to mapOf("type" to "integer")),
                            mapOf("name" to "cursor", "in" to "query", "schema" to mapOf("type" to "string"))
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserListResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    ),
                    "post" to mapOf(
                        "summary" to "Create user",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/UserCreateRequest")
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "201" to mapOf(
                                "description" to "Created",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserProfileResponse")
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "409" to mapOf(
                                "description" to "Conflict",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/users/{id}" to mapOf(
                    "get" to mapOf(
                        "summary" to "Get user profile",
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserProfileResponse")
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "404" to mapOf(
                                "description" to "Not Found",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/users/{id}/roles" to mapOf(
                    "patch" to mapOf(
                        "summary" to "Replace user roles",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/UserUpdateRolesRequest")
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserProfileResponse")
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "404" to mapOf(
                                "description" to "Not Found",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/users/{id}/status" to mapOf(
                    "patch" to mapOf(
                        "summary" to "Update user status",
                        "requestBody" to mapOf(
                            "content" to mapOf(
                                "application/json" to mapOf(
                                    "schema" to mapOf("\$ref" to "#/components/schemas/UserUpdateStatusRequest")
                                )
                            )
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserProfileResponse")
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "404" to mapOf(
                                "description" to "Not Found",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    )
                ),
                "/v1/users/{id}/audits" to mapOf(
                    "get" to mapOf(
                        "summary" to "List user audits",
                        "parameters" to listOf(
                            mapOf("name" to "limit", "in" to "query", "schema" to mapOf("type" to "integer")),
                            mapOf("name" to "cursor", "in" to "query", "schema" to mapOf("type" to "string"))
                        ),
                        "responses" to mapOf(
                            "200" to mapOf(
                                "description" to "OK",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/UserAuditListResponse")
                                    )
                                )
                            ),
                            "400" to mapOf(
                                "description" to "Bad Request",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "401" to mapOf(
                                "description" to "Unauthorized",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            ),
                            "403" to mapOf(
                                "description" to "Forbidden",
                                "content" to mapOf(
                                    "application/json" to mapOf(
                                        "schema" to mapOf("\$ref" to "#/components/schemas/ErrorResponse")
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            "components" to mapOf(
                "schemas" to mapOf(
                    "DocumentParagraph" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "string", "format" to "uuid"),
                            "documentId" to mapOf("type" to "string", "format" to "uuid"),
                            "position" to mapOf("type" to "integer"),
                            "heading" to mapOf("type" to "string", "nullable" to true),
                            "body" to mapOf("type" to "string"),
                            "languageCode" to mapOf("type" to "string"),
                            "createdAt" to mapOf("type" to "string", "format" to "date-time"),
                            "updatedAt" to mapOf("type" to "string", "format" to "date-time")
                        ),
                        "required" to listOf("id", "documentId", "position", "body", "languageCode", "createdAt", "updatedAt")
                    ),
                    "DocumentParagraphInput" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "position" to mapOf("type" to "integer"),
                            "heading" to mapOf("type" to "string", "nullable" to true),
                            "body" to mapOf("type" to "string"),
                            "languageCode" to mapOf("type" to "string")
                        ),
                        "required" to listOf("position", "body", "languageCode")
                    ),
                    "DocumentCreateRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "title" to mapOf("type" to "string"),
                            "languageCode" to mapOf("type" to "string"),
                            "paragraphs" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/DocumentParagraphInput")
                            ),
                            "publish" to mapOf("type" to "boolean")
                        ),
                        "required" to listOf("title", "languageCode", "paragraphs")
                    ),
                    "DocumentCreateBatchRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "documents" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/DocumentCreateRequest")
                            )
                        ),
                        "required" to listOf("documents")
                    ),
                    "DocumentResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "string", "format" to "uuid"),
                            "title" to mapOf("type" to "string"),
                            "body" to mapOf("type" to "string"),
                            "version" to mapOf("type" to "integer"),
                            "snapshotId" to mapOf("type" to "string", "format" to "uuid", "nullable" to true),
                            "languageCode" to mapOf("type" to "string"),
                            "paragraphs" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/DocumentParagraph")
                            ),
                            "createdAt" to mapOf("type" to "string", "format" to "date-time"),
                            "updatedAt" to mapOf("type" to "string", "format" to "date-time")
                        ),
                        "required" to listOf("id", "title", "body", "version", "languageCode", "paragraphs", "createdAt", "updatedAt")
                    ),
                    "DocumentCreateBatchResult" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "index" to mapOf("type" to "integer"),
                            "document" to mapOf("\$ref" to "#/components/schemas/DocumentResponse", "nullable" to true),
                            "error" to mapOf("type" to "string", "nullable" to true)
                        ),
                        "required" to listOf("index")
                    ),
                    "DocumentCreateBatchResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "created" to mapOf("type" to "integer"),
                            "failed" to mapOf("type" to "integer"),
                            "results" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/DocumentCreateBatchResult")
                            )
                        ),
                        "required" to listOf("created", "failed", "results")
                    ),
                    "SearchResultItem" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "documentId" to mapOf("type" to "string", "format" to "uuid"),
                            "paragraphId" to mapOf("type" to "string", "format" to "uuid"),
                            "snapshotId" to mapOf("type" to "string", "format" to "uuid", "nullable" to true),
                            "languageCode" to mapOf("type" to "string"),
                            "title" to mapOf("type" to "string"),
                            "snippet" to mapOf("type" to "string", "nullable" to true),
                            "textScore" to mapOf("type" to "number"),
                            "vectorScore" to mapOf("type" to "number"),
                            "finalScore" to mapOf("type" to "number")
                        ),
                        "required" to listOf(
                            "documentId",
                            "paragraphId",
                            "languageCode",
                            "title",
                            "textScore",
                            "vectorScore",
                            "finalScore"
                        )
                    ),
                    "SearchRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf("type" to "string"),
                            "limit" to mapOf("type" to "integer"),
                            "offset" to mapOf("type" to "integer"),
                            "languageCode" to mapOf("type" to "string", "nullable" to true),
                            "weights" to mapOf("\$ref" to "#/components/schemas/HybridWeights")
                        ),
                        "required" to listOf("query")
                    ),
                    "HybridWeights" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "text" to mapOf("type" to "number"),
                            "vector" to mapOf("type" to "number")
                        ),
                        "required" to listOf("text", "vector")
                    ),
                    "SearchResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "total" to mapOf("type" to "integer"),
                            "limit" to mapOf("type" to "integer"),
                            "offset" to mapOf("type" to "integer"),
                            "results" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/SearchResultItem")
                            )
                        ),
                        "required" to listOf("total", "limit", "offset", "results")
                    ),
                    "Citation" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "documentId" to mapOf("type" to "string", "format" to "uuid"),
                            "paragraphId" to mapOf("type" to "string", "format" to "uuid"),
                            "snapshotId" to mapOf("type" to "string", "format" to "uuid", "nullable" to true),
                            "languageCode" to mapOf("type" to "string"),
                            "score" to mapOf("type" to "number"),
                            "excerpt" to mapOf("type" to "string", "nullable" to true)
                        ),
                        "required" to listOf("documentId", "paragraphId", "languageCode", "score")
                    ),
                    "TokensUsed" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "prompt" to mapOf("type" to "integer"),
                            "completion" to mapOf("type" to "integer")
                        ),
                        "required" to listOf("prompt", "completion")
                    ),
                    "AnswerRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf("type" to "string"),
                            "limit" to mapOf("type" to "integer"),
                            "languageCode" to mapOf("type" to "string", "nullable" to true)
                        ),
                        "required" to listOf("query")
                    ),
                    "AnswerResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "answer" to mapOf("type" to "string"),
                            "citations" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/Citation")
                            ),
                            "tokensUsed" to mapOf("\$ref" to "#/components/schemas/TokensUsed")
                        ),
                        "required" to listOf("answer", "citations")
                    ),
                    "EmbedBackfillRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "batchSize" to mapOf("type" to "integer"),
                            "paragraphCursor" to mapOf("type" to "string", "format" to "uuid", "nullable" to true),
                            "languageCode" to mapOf("type" to "string", "nullable" to true)
                        )
                    ),
                    "BackfillResult" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "processed" to mapOf("type" to "integer"),
                            "nextParagraphCursor" to mapOf("type" to "string", "format" to "uuid", "nullable" to true)
                        ),
                        "required" to listOf("processed")
                    ),
                    "UserProfileResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "id" to mapOf("type" to "string", "format" to "uuid"),
                            "email" to mapOf("type" to "string", "nullable" to true),
                            "displayName" to mapOf("type" to "string", "nullable" to true),
                            "roles" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string")
                            ),
                            "status" to mapOf("type" to "string")
                        ),
                        "required" to listOf("id", "roles", "status")
                    ),
                    "UserCreateRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "email" to mapOf("type" to "string", "nullable" to true),
                            "displayName" to mapOf("type" to "string", "nullable" to true),
                            "roles" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string")
                            )
                        ),
                        "required" to listOf("roles")
                    ),
                    "UserUpdateRolesRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "roles" to mapOf(
                                "type" to "array",
                                "items" to mapOf("type" to "string")
                            ),
                            "reason" to mapOf("type" to "string")
                        ),
                        "required" to listOf("roles", "reason")
                    ),
                    "UserUpdateStatusRequest" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "status" to mapOf("type" to "string"),
                            "reason" to mapOf("type" to "string")
                        ),
                        "required" to listOf("status", "reason")
                    ),
                    "UserListResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "items" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/UserProfileResponse")
                            ),
                            "nextCursor" to mapOf("type" to "string", "nullable" to true)
                        ),
                        "required" to listOf("items")
                    ),
                    "UserAuditEvent" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "auditId" to mapOf("type" to "string", "format" to "uuid"),
                            "actorId" to mapOf("type" to "string", "format" to "uuid"),
                            "targetUserId" to mapOf("type" to "string", "format" to "uuid"),
                            "action" to mapOf("type" to "string"),
                            "reason" to mapOf("type" to "string", "nullable" to true),
                            "createdAt" to mapOf("type" to "string", "format" to "date-time")
                        ),
                        "required" to listOf("auditId", "actorId", "targetUserId", "action", "createdAt")
                    ),
                    "UserAuditListResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "items" to mapOf(
                                "type" to "array",
                                "items" to mapOf("\$ref" to "#/components/schemas/UserAuditEvent")
                            ),
                            "nextCursor" to mapOf("type" to "string", "nullable" to true)
                        ),
                        "required" to listOf("items")
                    ),
                    "ErrorResponse" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "error" to mapOf("type" to "string")
                        ),
                        "required" to listOf("error")
                    )
                )
            )
        )

        return Registry(
            documentRepo = docRepo,
            embeddingRepo = embRepo,
            collaborationRepo = collabRepo,
            userRepo = userRepo,
            searchService = search,
            answerService = answer,
            backfillService = backfill,
            openApiSpec = openApi
        )
    }
}
