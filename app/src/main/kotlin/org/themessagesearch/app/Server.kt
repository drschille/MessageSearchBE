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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.*
import org.themessagesearch.app.di.ServiceRegistry
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*
import java.util.Base64
import java.util.UUID

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
        get("/openapi") { call.respond(services.openApiSpec.toJsonElement()) }

        authenticate("auth-jwt") {
            documentRoutes(services.documentRepo)
            collaborationRoutes(services.collaborationRepo)
            searchRoutes(services.searchService, appConfig.search)
            answerRoutes(services.answerService, appConfig.search)
            ingestRoutes(services.backfillService)
        }
    }
}

private fun Route.documentRoutes(docRepo: DocumentRepository) {
    get("/v1/documents") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val languageCode = call.request.queryParameters["language_code"]
        val title = call.request.queryParameters["title"]
        val result = docRepo.listDocuments(limit, offset, languageCode, title)
        call.respond(result)
    }
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

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> {
        val content = this.entries
            .filter { it.key is String }
            .associate { (key, value) -> key as String to value.toJsonElement() }
        JsonObject(content)
    }
    is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
    is Array<*> -> JsonArray(this.map { it.toJsonElement() })
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    else -> JsonPrimitive(this.toString())
}

private fun Route.collaborationRoutes(collabRepo: CollaborationRepository) {
    post("/v1/documents/{documentId}/collab/updates") {
        val documentParam = call.parameters["documentId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing document id"))
        val documentId = runCatching { DocumentId(documentParam) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val principal = call.principal<JWTPrincipal>()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token"))
        val userId = principal.payload.subject
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing user id"))
        val req = call.receive<CollaborationUpdateRequest>()
        val paragraphId = runCatching { ParagraphId(req.paragraphId) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid paragraph id"))
        }
        val clientId = runCatching { UUID.fromString(req.clientId) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid client id"))
        }
        val payload = runCatching { Base64.getDecoder().decode(req.update) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid update payload"))
        }
        val update = CollaborationUpdate(
            documentId = documentId,
            paragraphId = paragraphId,
            clientId = clientId.toString(),
            userId = userId,
            languageCode = req.languageCode,
            seq = req.seq,
            payload = payload
        )
        val result = collabRepo.appendUpdate(update)
        call.respond(CollaborationUpdateAckResponse(result.accepted, result.latestUpdateId))
    }

    get("/v1/documents/{documentId}/collab/updates") {
        val documentParam = call.parameters["documentId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing document id"))
        val documentId = runCatching { DocumentId(documentParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val languageCode = call.request.queryParameters["languageCode"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing languageCode"))
        val paragraphId = call.request.queryParameters["paragraphId"]?.let {
            runCatching { ParagraphId(it) }.getOrElse {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid paragraph id"))
            }
        }
        val afterId = call.request.queryParameters["afterId"]?.let {
            it.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid afterId"))
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val updates = collabRepo.listUpdates(documentId, paragraphId, languageCode, afterId, limit)
        val resp = updates.map {
            CollaborationUpdateItem(
                id = it.id ?: error("Missing id on collab update"),
                paragraphId = it.paragraphId.value,
                clientId = it.clientId,
                seq = it.seq,
                languageCode = it.languageCode,
                update = Base64.getEncoder().encodeToString(it.payload),
                createdAt = it.createdAt ?: error("Missing createdAt on collab update")
            )
        }
        call.respond(CollaborationUpdatesResponse(resp))
    }

    get("/v1/documents/{documentId}/collab/snapshot") {
        val documentParam = call.parameters["documentId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing document id"))
        val documentId = runCatching { DocumentId(documentParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val languageCode = call.request.queryParameters["languageCode"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing languageCode"))
        val snapshot = collabRepo.getSnapshot(documentId, languageCode)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "snapshot not found"))
        call.respond(
            CollaborationSnapshotResponse(
                snapshotVersion = snapshot.snapshotVersion,
                payload = Base64.getEncoder().encodeToString(snapshot.payload),
                createdAt = snapshot.createdAt ?: error("Missing createdAt on collab snapshot")
            )
        )
    }
}
