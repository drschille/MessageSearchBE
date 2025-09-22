package org.themessagesearch.app

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.metrics.micrometer.*
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.*
import org.themessagesearch.app.di.ServiceRegistry
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

fun main() {
    val config = ConfigLoader.load()
    val services = ServiceRegistry.init(config)
    embeddedServer(Netty, port = 8080) { ktorModule(config, services) }.start(wait = true)
}

fun Application.ktorModule(appConfig: AppConfig, services: ServiceRegistry.Registry) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; prettyPrint = false }) }

    val jwtCfg = appConfig.jwt
    install(Authentication) {
        jwt("auth-jwt") {
            val algorithm = Algorithm.HMAC256(jwtCfg.secret)
            verifier(
                JWT.require(algorithm)
                    .withIssuer(jwtCfg.issuer)
                    .build()
            )
            validate { credentials ->
                if (credentials.payload.audience.contains(jwtCfg.audience)) JWTPrincipal(credentials.payload) else null
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token")) }
        }
    }

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) { registry = prometheusRegistry }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        get("/metrics") { call.respond(prometheusRegistry.scrape()) }
        get("/openapi") { call.respond(services.openApiSpec) }

        authenticate("auth-jwt") {
            documentRoutes(services.documentRepo)
            searchRoutes(services.searchService, appConfig.search)
            answerRoutes(services.answerService, appConfig.search)
            ingestRoutes(services.backfillService)
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
