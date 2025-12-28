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
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*

fun main() {
    val config = ConfigLoader.load()
    val services = ServiceRegistry.init(config)
    embeddedServer(Netty, port = 8080) { ktorModule(config, services) }.start(wait = true)
}

fun Application.ktorModule(appConfig: AppConfig, services: ServiceRegistry.Registry) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; prettyPrint = false }) }
    install(CallLogging) {
        filter { call -> call.request.path() != "/health" && call.request.path() != "/metrics" }
    }
    install(StatusPages) {
        exception<Throwable> { call: ApplicationCall, cause: Throwable ->
            // TODO: integrate structured logging
            this@ktorModule.environment.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
        }
    }

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
        val document = docRepo.create(req)
        call.respond(HttpStatusCode.Created, document.toResponse())
    }
    get("/v1/documents/{id}") {
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val documentId = runCatching { DocumentId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val snapshotParam = call.request.queryParameters["snapshot_id"]
        val snapshot = snapshotParam?.let {
            runCatching { SnapshotId(it) }.getOrElse {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid snapshot_id"))
            }
        }
        val languageCode = call.request.queryParameters["language_code"]
        val doc = docRepo.findById(documentId, snapshot, languageCode) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(doc.toResponse())
    }
}

private fun Route.searchRoutes(searchService: HybridSearchService, searchConfig: SearchConfig) {
    post("/v1/search") {
        val req = call.receive<SearchRequest>()
        val limit = (req.limit ?: 10).coerceIn(1, 100)
        val offset = maxOf(req.offset ?: 0, 0)
        val weights = req.weights ?: searchConfig.weights
        val results = searchService.search(req.query, limit, offset, weights, req.languageCode)
        call.respond(results)
    }
}

private fun Route.answerRoutes(answerService: AnswerService, searchConfig: SearchConfig) {
    post("/v1/answer") {
        val req = call.receive<AnswerRequest>()
        val limit = (req.limit ?: 5).coerceIn(1, 25)
        val resp = answerService.answer(req.query, limit, searchConfig.weights, req.languageCode)
        call.respond(resp)
    }
}

private fun Route.ingestRoutes(backfill: EmbeddingBackfillService) {
    post("/v1/ingest/embed") {
        val req = call.receive<EmbedBackfillRequest>()
        val batchSize = (req.batchSize ?: 50).coerceIn(1, 500)
        val cursor = req.paragraphCursor?.let {
            runCatching { ParagraphId(it) }.getOrElse {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid paragraph cursor"))
            }
        }
        val result = backfill.backfill(batchSize, cursor, req.languageCode)
        call.respond(result)
    }
}
