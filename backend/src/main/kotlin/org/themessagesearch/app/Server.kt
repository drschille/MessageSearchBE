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
            val safeMessage = redactSensitive(cause.message ?: "internal error")
            val safePath = call.request.path()
            val safeMethod = call.request.httpMethod.value
            this@ktorModule.environment.log.error("Unhandled exception $safeMethod $safePath: $safeMessage")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal error"))
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
            snapshotRoutes(services.snapshotRepo)
            auditRoutes(services.auditRepo)
            workflowRoutes(services.workflowRepo, services.webhookNotifier)
            searchRoutes(services.searchService, appConfig.search)
            answerRoutes(services.answerService, appConfig.search)
            ingestRoutes(services.backfillService)
            userRoutes(services.userRepo)
        }
    }
}

private data class AuthContext(val userId: UserId, val roles: List<UserRole>)

private suspend fun ApplicationCall.requireAuthContext(): AuthContext? {
    val principal = principal<JWTPrincipal>()
        ?: return respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid or missing token")).let { null }
    val subject = principal.payload.subject
        ?: return respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing user id")).let { null }
    val userId = runCatching { UserId(subject) }.getOrElse {
        return respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid user id")).let { null }
    }
    val roleClaim = principal.payload.getClaim("roles")
    val roleStrings = runCatching { roleClaim.asList(String::class.java) }.getOrNull()
        ?: return respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing roles")).let { null }
    if (roleStrings.isEmpty()) {
        return respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing roles")).let { null }
    }
    val parsed = roleStrings.mapNotNull { UserRole.fromString(it) }
    if (parsed.size != roleStrings.size) {
        return respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid roles")).let { null }
    }
    return AuthContext(userId, parsed.distinct())
}

private suspend fun ApplicationCall.requireAnyRole(auth: AuthContext, vararg required: UserRole): Boolean {
    if (auth.roles.contains(UserRole.ADMIN)) return true
    if (required.isEmpty()) return true
    if (required.any { auth.roles.contains(it) }) return true
    respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
    return false
}

private suspend fun ApplicationCall.requireIfMatchVersion(): Long? {
    val raw = request.headers[HttpHeaders.IfMatch] ?: return respond(
        HttpStatusCode.PreconditionRequired,
        mapOf("error" to "missing if-match header")
    ).let { null }
    val cleaned = raw.trim().trim('"')
    val parsed = cleaned.toLongOrNull()
        ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid if-match version")).let { null }
    return parsed
}

private fun AuthContext.hasRole(role: UserRole): Boolean =
    roles.contains(UserRole.ADMIN) || roles.contains(role)

private fun redactSensitive(message: String): String {
    var sanitized = message
    val patterns = listOf(
        Regex("(?i)(authorization|bearer)\\s+[^\\s,]+"),
        Regex("(?i)(api[_-]?key|jwt|token|secret)\\s*[:=]\\s*[^\\s,]+"),
        Regex("(?i)(\"query\"\\s*:\\s*\")([^\"]+)(\")")
    )
    patterns.forEach { pattern ->
        sanitized = sanitized.replace(pattern) { match ->
            when {
                match.groupValues.size >= 4 -> "${match.groupValues[1]}[redacted]${match.groupValues[3]}"
                else -> "[redacted]"
            }
        }
    }
    return sanitized
}

private fun Route.documentRoutes(docRepo: DocumentRepository) {
    get("/v1/documents") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.READER)) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val languageCode = call.request.queryParameters["language_code"]
        val title = call.request.queryParameters["title"]
        val restrictToPublished = !auth.hasRole(UserRole.EDITOR) &&
            !auth.hasRole(UserRole.REVIEWER) &&
            !auth.hasRole(UserRole.ADMIN)
        val state = if (restrictToPublished) DocumentWorkflowState.PUBLISHED else null
        val result = docRepo.listDocuments(limit, offset, languageCode, title, state)
        call.respond(result)
    }
    post("/v1/documents") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.EDITOR)) return@post
        val req = call.receive<DocumentCreateRequest>()
        val document = docRepo.create(req, auth.userId)
        call.respond(HttpStatusCode.Created, document.toResponse())
    }
    post("/v1/documents:batch") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.EDITOR)) return@post
        val req = call.receive<DocumentCreateBatchRequest>()
        if (req.documents.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "documents must not be empty"))
        }
        val maxBatchSize = 100
        if (req.documents.size > maxBatchSize) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "batch size ${req.documents.size} exceeds max $maxBatchSize")
            )
        }
        val results = req.documents.mapIndexed { index, documentRequest ->
            runCatching { docRepo.create(documentRequest, auth.userId) }
                .fold(
                    onSuccess = { document ->
                        DocumentCreateBatchResult(index = index, document = document.toResponse())
                    },
                    onFailure = { error ->
                        DocumentCreateBatchResult(
                            index = index,
                            error = error.message ?: "invalid payload"
                        )
                    }
                )
        }
        val created = results.count { it.document != null }
        val response = DocumentCreateBatchResponse(
            created = created,
            failed = results.size - created,
            results = results
        )
        call.respond(response)
    }
    get("/v1/documents/{id}") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.READER)) return@get
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
        val restrictToPublished = !auth.hasRole(UserRole.EDITOR) &&
            !auth.hasRole(UserRole.REVIEWER) &&
            !auth.hasRole(UserRole.ADMIN)
        if (restrictToPublished && doc.workflowState != DocumentWorkflowState.PUBLISHED) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        call.respond(doc.toResponse())
    }
}

private fun Route.snapshotRoutes(snapshotRepo: SnapshotRepository) {
    get("/v1/documents/{id}/snapshots") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.READER)) return@get
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val documentId = runCatching { DocumentId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val cursor = call.request.queryParameters["cursor"]
        val result = snapshotRepo.list(documentId, limit, cursor)
        call.respond(result.toResponse())
    }
    get("/v1/documents/{id}/snapshots/{snapshotId}") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.READER)) return@get
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val documentId = runCatching { DocumentId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val snapshotParam = call.parameters["snapshotId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val snapshotId = runCatching { SnapshotId(snapshotParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid snapshot id"))
        }
        val snapshot = snapshotRepo.findById(documentId, snapshotId)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "snapshot not found"))
        call.respond(snapshot.toResponse())
    }
}

private fun Route.auditRoutes(auditRepo: AuditRepository) {
    get("/v1/documents/{id}/audits") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.REVIEWER)) return@get
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val documentId = runCatching { DocumentId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val cursor = call.request.queryParameters["cursor"]
        val result = auditRepo.list(documentId, limit, cursor)
        call.respond(result.toResponse())
    }
    get("/v1/documents/{id}/audits/{auditId}") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.REVIEWER)) return@get
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val documentId = runCatching { DocumentId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val auditParam = call.parameters["auditId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val auditId = runCatching { AuditId(auditParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid audit id"))
        }
        val audit = auditRepo.findById(documentId, auditId)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "audit not found"))
        call.respond(audit.toResponse())
    }
}

private fun Route.workflowRoutes(workflowRepo: WorkflowRepository, webhookNotifier: WebhookNotifier) {
    post("/v1/documents/{id}/review") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.EDITOR)) return@post
        val expectedVersion = call.requireIfMatchVersion() ?: return@post
        val idParam = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val documentId = runCatching { DocumentId(idParam) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        if (workflowRepo.getDocument(documentId) == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
        }
        val req = call.receive<ReviewSubmitRequest>()
        if (req.reviewers.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reviewers required"))
        }
        val reviewers = req.reviewers.mapNotNull { reviewerId ->
            runCatching { UserId(reviewerId) }.getOrNull()
        }
        if (reviewers.size != req.reviewers.size) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid reviewer ids"))
        }
        val review = workflowRepo.submitForReview(documentId, expectedVersion, req.summary, reviewers, auth.userId)
            ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "version/state conflict"))
        webhookNotifier.notifyReviewSubmitted(
            documentId = documentId,
            reviewId = review.reviewId,
            summary = req.summary,
            actorId = auth.userId
        )
        call.respond(HttpStatusCode.Accepted, review.toResponse())
    }
    post("/v1/documents/{id}/reviews/{reviewId}/approve") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.REVIEWER)) return@post
        val expectedVersion = call.requireIfMatchVersion() ?: return@post
        val documentId = call.parameters["id"]?.let { runCatching { DocumentId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        val reviewId = call.parameters["reviewId"]?.let { runCatching { ReviewId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid review id"))
        if (workflowRepo.getDocument(documentId) == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
        }
        val req = call.receive<ReviewDecisionRequest>()
        val result = workflowRepo.approveReview(
            documentId,
            reviewId,
            expectedVersion,
            req.reason,
            req.diffSummary,
            auth.userId
        ) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "version/state conflict"))
        webhookNotifier.notifyDocumentPublished(
            documentId = documentId,
            snapshotId = result.snapshotId,
            summary = req.diffSummary,
            actorId = auth.userId
        )
        call.respond(result.toResponse())
    }
    post("/v1/documents/{id}/reviews/{reviewId}/request-changes") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.REVIEWER)) return@post
        val expectedVersion = call.requireIfMatchVersion() ?: return@post
        val documentId = call.parameters["id"]?.let { runCatching { DocumentId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        val reviewId = call.parameters["reviewId"]?.let { runCatching { ReviewId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid review id"))
        if (workflowRepo.getDocument(documentId) == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
        }
        val req = call.receive<ReviewDecisionRequest>()
        val result = workflowRepo.requestChanges(
            documentId,
            reviewId,
            expectedVersion,
            req.reason,
            req.diffSummary,
            auth.userId
        ) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "version/state conflict"))
        call.respond(result.toResponse())
    }
    post("/v1/documents/{id}/publish") {
        val auth = call.requireAuthContext() ?: return@post
        val req = call.receive<PublishRequest>()
        if (req.force) {
            if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@post
            if (req.reason.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason required for force publish"))
            }
        } else {
            if (!call.requireAnyRole(auth, UserRole.REVIEWER)) return@post
            if (req.reason.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason required for publish"))
            }
        }
        val expectedVersion = call.requireIfMatchVersion() ?: return@post
        val documentId = call.parameters["id"]?.let { runCatching { DocumentId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        if (workflowRepo.getDocument(documentId) == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
        }
        val result = workflowRepo.publish(
            documentId,
            expectedVersion,
            req.force,
            req.reason,
            req.diffSummary,
            auth.userId
        ) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "version/state conflict"))
        webhookNotifier.notifyDocumentPublished(
            documentId = documentId,
            snapshotId = result.snapshotId,
            summary = req.diffSummary,
            actorId = auth.userId
        )
        call.respond(result.toResponse())
    }
    post("/v1/documents/{id}/revert") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.EDITOR)) return@post
        val expectedVersion = call.requireIfMatchVersion() ?: return@post
        val documentId = call.parameters["id"]?.let { runCatching { DocumentId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        if (workflowRepo.getDocument(documentId) == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
        }
        val req = call.receive<RevertRequest>()
        val snapshotId = runCatching { SnapshotId(req.snapshotId) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid snapshot id"))
        }
        val result = workflowRepo.revert(
            documentId,
            expectedVersion,
            snapshotId,
            req.reason,
            auth.userId
        ) ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "version/state conflict"))
        call.respond(result.toResponse())
    }
    post("/v1/documents/{id}/archive") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@post
        val expectedVersion = call.requireIfMatchVersion() ?: return@post
        val documentId = call.parameters["id"]?.let { runCatching { DocumentId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        if (workflowRepo.getDocument(documentId) == null) {
            return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "document not found"))
        }
        val req = call.receive<ArchiveRequest>()
        if (req.reason.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason required"))
        }
        val result = workflowRepo.archive(documentId, expectedVersion, req.reason, auth.userId)
            ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "version/state conflict"))
        call.respond(result.toResponse())
    }
    post("/v1/documents/{id}/reviews/{reviewId}/comments") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.EDITOR)) return@post
        val reviewId = call.parameters["reviewId"]?.let { runCatching { ReviewId(it) }.getOrNull() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid review id"))
        val req = call.receive<ReviewCommentRequest>()
        if (req.body.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "comment body required"))
        }
        val comment = workflowRepo.addReviewComment(reviewId, auth.userId, req.body)
            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "review not found"))
        call.respond(HttpStatusCode.Created, comment.toResponse())
    }
}

private fun Route.searchRoutes(searchService: HybridSearchService, searchConfig: SearchConfig) {
    post("/v1/search") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.READER)) return@post
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
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.READER)) return@post
        val req = call.receive<AnswerRequest>()
        val limit = (req.limit ?: 5).coerceIn(1, 25)
        val resp = answerService.answer(req.query, limit, searchConfig.weights, req.languageCode)
        call.respond(resp)
    }
}

private fun Route.ingestRoutes(backfill: EmbeddingBackfillService) {
    post("/v1/ingest/embed") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@post
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

private fun Route.userRoutes(userRepo: UserRepository) {
    get("/v1/users/me") {
        val auth = call.requireAuthContext() ?: return@get
        val profile = userRepo.findOrCreateFromAuth(auth.userId, auth.roles.toList(), email = null, displayName = null)
        val response = profile.toResponse().copy(roles = auth.roles.toList())
        call.respond(response)
    }

    get("/v1/users") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@get
        userRepo.findOrCreateFromAuth(auth.userId, auth.roles.toList(), email = null, displayName = null)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val cursor = call.request.queryParameters["cursor"]
        val result = runCatching { userRepo.listUsers(limit, cursor) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid cursor"))
        }
        call.respond(UserListResponse(result.items.map { it.toResponse() }, result.nextCursor))
    }

    post("/v1/users") {
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@post
        userRepo.findOrCreateFromAuth(auth.userId, auth.roles.toList(), email = null, displayName = null)
        val req = call.receive<UserCreateRequest>()
        if (req.roles.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "roles must include at least one role"))
        }
        val created = userRepo.createUser(req, auth.userId)
            ?: return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "user already exists"))
        call.respond(HttpStatusCode.Created, created.toResponse())
    }

    get("/v1/users/{id}") {
        val auth = call.requireAuthContext() ?: return@get
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val userId = runCatching { UserId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid user id"))
        }
        if (auth.userId != userId && !auth.hasRole(UserRole.ADMIN)) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
        }
        val user = userRepo.findById(userId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val response = if (auth.userId == userId) user.toResponse().copy(roles = auth.roles.toList()) else user.toResponse()
        call.respond(response)
    }

    patch("/v1/users/{id}/roles") {
        val auth = call.requireAuthContext() ?: return@patch
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@patch
        userRepo.findOrCreateFromAuth(auth.userId, auth.roles.toList(), email = null, displayName = null)
        val idParam = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
        val userId = runCatching { UserId(idParam) }.getOrElse {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid user id"))
        }
        val req = call.receive<UserUpdateRolesRequest>()
        if (req.roles.isEmpty()) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "roles must include at least one role"))
        }
        if (req.reason.isBlank()) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason is required"))
        }
        val updated = userRepo.replaceRoles(userId, req.roles, auth.userId, req.reason)
            ?: return@patch call.respond(HttpStatusCode.NotFound)
        call.respond(updated.toResponse())
    }

    patch("/v1/users/{id}/status") {
        val auth = call.requireAuthContext() ?: return@patch
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@patch
        userRepo.findOrCreateFromAuth(auth.userId, auth.roles.toList(), email = null, displayName = null)
        val idParam = call.parameters["id"] ?: return@patch call.respond(HttpStatusCode.BadRequest)
        val userId = runCatching { UserId(idParam) }.getOrElse {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid user id"))
        }
        val req = call.receive<UserUpdateStatusRequest>()
        if (req.reason.isBlank()) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason is required"))
        }
        val updated = userRepo.updateStatus(userId, req.status, auth.userId, req.reason)
            ?: return@patch call.respond(HttpStatusCode.NotFound)
        call.respond(updated.toResponse())
    }

    get("/v1/users/{id}/audits") {
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.ADMIN)) return@get
        userRepo.findOrCreateFromAuth(auth.userId, auth.roles.toList(), email = null, displayName = null)
        val idParam = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val userId = runCatching { UserId(idParam) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid user id"))
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val cursor = call.request.queryParameters["cursor"]
        val result = runCatching { userRepo.listAudits(userId, limit, cursor) }.getOrElse {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid cursor"))
        }
        call.respond(UserAuditListResponse(result.items, result.nextCursor))
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
        val auth = call.requireAuthContext() ?: return@post
        if (!call.requireAnyRole(auth, UserRole.EDITOR, UserRole.REVIEWER)) return@post
        val documentParam = call.parameters["documentId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing document id"))
        val documentId = runCatching { DocumentId(documentParam) }.getOrElse {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document id"))
        }
        val userId = auth.userId.value
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
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.EDITOR, UserRole.REVIEWER)) return@get
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
        val auth = call.requireAuthContext() ?: return@get
        if (!call.requireAnyRole(auth, UserRole.EDITOR, UserRole.REVIEWER)) return@get
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
