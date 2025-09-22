package org.themessagesearch.app

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.callloging.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.metrics.micrometer.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.*
import org.themessagesearch.infra.db.DatabaseFactory
import org.themessagesearch.app.di.ServiceRegistry

fun main() {
    val config = ConfigLoader.load()
    val registry = ServiceRegistry.init(config)
    embeddedServer(Netty, port = 8080) { ktorModule(config, registry) }.start(wait = true)
}

fun Application.ktorModule(appConfig: AppConfig, registry: ServiceRegistry.Registry) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; prettyPrint = false }) }
    install(CallLogging)

    val jwt = appConfig.jwt
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(io.ktor.util.internal.toByteArray(jwt.secret)) // TODO use proper JWT algorithm & library (e.g., Auth0)
            validate { credentials ->
                if (credentials.payload.audience.contains(jwt.audience)) JWTPrincipal(credentials.payload) else null
            }
        }
    }

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) { registry = prometheusRegistry }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        get("/metrics") { call.respond(prometheusRegistry.scrape()) }
        // Minimal OpenAPI placeholder
        get("/openapi") {
            call.respond(mapOf(
                "openapi" to "3.0.0",
                "info" to mapOf("title" to "MessageSearch API", "version" to "0.1.0"),
                "paths" to mapOf(
                    "/v1/documents" to mapOf("post" to mapOf("summary" to "Create document")),
                    "/v1/documents/{id}" to mapOf("get" to mapOf("summary" to "Get document")),
                    "/v1/search" to mapOf("post" to mapOf("summary" to "Hybrid search")),
                    "/v1/answer" to mapOf("post" to mapOf("summary" to "RAG answer")),
                    "/v1/ingest/embed" to mapOf("post" to mapOf("summary" to "Backfill embeddings"))
                )
            ))
        }
        swaggerUI(path = "swagger", swaggerFile = "openapi")

        authenticate("auth-jwt") {
            documentRoutes(registry.documentRepo)
            searchRoutes(registry.searchService, appConfig.search)
            answerRoutes(registry.answerService, appConfig.search)
            ingestRoutes(registry.backfillService)
        }
    }
}

private fun Route.documentRoutes(docRepo: DocumentRepository) {
    post("/v1/documents") {
        val req = call.receive<DocumentCreateRequest>()
        val doc = Document(DocumentId.random(), req.title, req.body)
        docRepo.insert(doc)
        call.respond(HttpStatusCode.Created, DocumentResponse(doc.id.value, doc.title, doc.body))
    }
    get("/v1/documents/{id}") {
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val doc = docRepo.findById(DocumentId(idParam)) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(DocumentResponse(doc.id.value, doc.title, doc.body))
    }
}

private fun Route.searchRoutes(searchService: HybridSearchService, searchConfig: SearchConfig) {
    post("/v1/search") {
        val req = call.receive<SearchRequest>()
        val limit = req.limit ?: 10
        val results = searchService.search(req.query, limit, searchConfig.weights)
        call.respond(SearchResponse(results))
    }
}

private fun Route.answerRoutes(answerService: AnswerService, searchConfig: SearchConfig) {
    post("/v1/answer") {
        val req = call.receive<AnswerRequest>()
        val resp = answerService.answer(req.query, searchConfig.k, searchConfig.weights)
        call.respond(resp)
    }
}

private fun Route.ingestRoutes(backfill: EmbeddingBackfillService) {
    post("/v1/ingest/embed") {
        val req = call.receive<EmbedBackfillRequest>()
        val processed = backfill.backfill(req.batchSize ?: 50)
        call.respond(mapOf("processed" to processed))
    }
}

