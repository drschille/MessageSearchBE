package org.themessagesearch.infra.db.repo

import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.WorkflowRepository
import java.time.ZoneOffset
import java.util.UUID

private object DocumentReviewsTable : UUIDTable("document_reviews", "review_id") {
    val documentId = uuid("document_id")
    val status = text("status")
    val summary = text("summary")
    val reviewers = text("reviewers")
    val createdBy = uuid("created_by")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

private object ReviewCommentsTable : UUIDTable("review_comments", "comment_id") {
    val reviewId = uuid("review_id")
    val authorId = uuid("author_id")
    val body = text("body")
    val createdAt = timestampWithTimeZone("created_at")
}

class ExposedWorkflowRepository : WorkflowRepository {
    private val json = Json { encodeDefaults = true }

    override suspend fun getDocument(documentId: DocumentId): WorkflowDocument? = transaction {
        val row = DocumentsTable.select { DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }
            .limit(1)
            .firstOrNull()
            ?: return@transaction null
        WorkflowDocument(
            documentId = documentId,
            title = row[DocumentsTable.title],
            body = row[DocumentsTable.body],
            languageCode = row[DocumentsTable.languageCode],
            version = row[DocumentsTable.version],
            state = row[DocumentsTable.workflowState].toWorkflowState(),
            snapshotId = row[DocumentsTable.snapshotId]?.let { SnapshotId(it.toString()) }
        )
    }

    override suspend fun submitForReview(
        documentId: DocumentId,
        expectedVersion: Long,
        summary: String,
        reviewers: List<UserId>,
        actorId: UserId
    ): ReviewRequest? = transaction {
        val now = Clock.System.now()
        val updated = DocumentsTable.update({
            (DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable)) and
                (DocumentsTable.version eq expectedVersion) and
                (DocumentsTable.workflowState eq DocumentWorkflowState.DRAFT.dbValue())
        }) {
            it[DocumentsTable.workflowState] = DocumentWorkflowState.IN_REVIEW.dbValue()
            it[DocumentsTable.version] = expectedVersion + 1
            it[DocumentsTable.updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        if (updated == 0) return@transaction null
        val reviewId = ReviewId.random()
        val encodedReviewers = json.encodeToString(ListSerializer(String.serializer()), reviewers.map { it.value })
        DocumentReviewsTable.insert {
            it[id] = UUID.fromString(reviewId.value)
            it[DocumentReviewsTable.documentId] = UUID.fromString(documentId.value)
            it[status] = ReviewStatus.IN_REVIEW.dbValue()
            it[DocumentReviewsTable.summary] = summary
            it[DocumentReviewsTable.reviewers] = encodedReviewers
            it[createdBy] = UUID.fromString(actorId.value)
            it[createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
            it[updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        val auditId = AuditId.random()
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(auditId.value)
            it[DocumentAuditsTable.documentId] = UUID.fromString(documentId.value)
            it[actorId] = UUID.fromString(actorId.value)
            it[action] = DocumentAuditAction.REVIEW_SUBMITTED.dbValue()
            it[reason] = summary
            it[fromState] = DocumentWorkflowState.DRAFT.dbValue()
            it[toState] = DocumentWorkflowState.IN_REVIEW.dbValue()
            it[DocumentAuditsTable.snapshotId] = null
            it[diffSummary] = null
            it[requestId] = null
            it[ipFingerprint] = null
            it[DocumentAuditsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        ReviewRequest(
            reviewId = reviewId,
            documentId = documentId,
            summary = summary,
            reviewers = reviewers,
            status = ReviewStatus.IN_REVIEW,
            createdBy = actorId,
            createdAt = now,
            updatedAt = now
        )
    }

    override suspend fun approveReview(
        documentId: DocumentId,
        reviewId: ReviewId,
        expectedVersion: Long,
        reason: String?,
        diffSummary: String?,
        actorId: UserId
    ): WorkflowTransitionResult? = transaction {
        val now = Clock.System.now()
        val reviewRow = DocumentReviewsTable.select {
            (DocumentReviewsTable.id eq EntityID(UUID.fromString(reviewId.value), DocumentReviewsTable)) and
                (DocumentReviewsTable.documentId eq UUID.fromString(documentId.value))
        }.limit(1).firstOrNull() ?: return@transaction null
        if (reviewRow[DocumentReviewsTable.status] != ReviewStatus.IN_REVIEW.dbValue()) return@transaction null
        val priorState = DocumentWorkflowState.IN_REVIEW
        val updated = DocumentsTable.update({
            (DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable)) and
                (DocumentsTable.version eq expectedVersion) and
                (DocumentsTable.workflowState eq DocumentWorkflowState.IN_REVIEW.dbValue())
        }) {
            it[DocumentsTable.workflowState] = DocumentWorkflowState.PUBLISHED.dbValue()
            it[DocumentsTable.version] = expectedVersion + 1
            it[DocumentsTable.updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        if (updated == 0) return@transaction null
        DocumentReviewsTable.update({
            DocumentReviewsTable.id eq EntityID(UUID.fromString(reviewId.value), DocumentReviewsTable)
        }) {
            it[status] = ReviewStatus.APPROVED.dbValue()
            it[updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        val snapshotId = SnapshotId.random()
        val docRow = DocumentsTable.select { DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }
            .limit(1).first()
        SnapshotsTable.insert {
            it[id] = UUID.fromString(snapshotId.value)
            it[documentId] = UUID.fromString(documentId.value)
            it[version] = expectedVersion + 1
            it[state] = SnapshotState.PUBLISHED.dbValue()
            it[title] = docRow[DocumentsTable.title]
            it[body] = docRow[DocumentsTable.body]
            it[languageCode] = docRow[DocumentsTable.languageCode]
            it[createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
            it[createdBy] = UUID.fromString(actorId.value)
            it[sourceDraftId] = null
            it[sourceRevision] = null
        }
        DocumentsTable.update({ DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }) {
            it[snapshotId] = UUID.fromString(snapshotId.value)
        }
        val auditId = AuditId.random()
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(auditId.value)
            it[DocumentAuditsTable.documentId] = UUID.fromString(documentId.value)
            it[actorId] = UUID.fromString(actorId.value)
            it[action] = DocumentAuditAction.REVIEW_APPROVED.dbValue()
            it[DocumentAuditsTable.reason] = reason
            it[fromState] = priorState.dbValue()
            it[toState] = DocumentWorkflowState.PUBLISHED.dbValue()
            it[DocumentAuditsTable.snapshotId] = UUID.fromString(snapshotId.value)
            it[DocumentAuditsTable.diffSummary] = diffSummary
            it[requestId] = null
            it[ipFingerprint] = null
            it[DocumentAuditsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        WorkflowTransitionResult(
            documentId = documentId,
            state = DocumentWorkflowState.PUBLISHED,
            version = expectedVersion + 1,
            snapshotId = snapshotId,
            auditId = auditId
        )
    }

    override suspend fun requestChanges(
        documentId: DocumentId,
        reviewId: ReviewId,
        expectedVersion: Long,
        reason: String?,
        diffSummary: String?,
        actorId: UserId
    ): WorkflowTransitionResult? = transaction {
        val now = Clock.System.now()
        val reviewRow = DocumentReviewsTable.select {
            (DocumentReviewsTable.id eq EntityID(UUID.fromString(reviewId.value), DocumentReviewsTable)) and
                (DocumentReviewsTable.documentId eq UUID.fromString(documentId.value))
        }.limit(1).firstOrNull() ?: return@transaction null
        if (reviewRow[DocumentReviewsTable.status] != ReviewStatus.IN_REVIEW.dbValue()) return@transaction null
        val priorState = DocumentWorkflowState.IN_REVIEW
        val updated = DocumentsTable.update({
            (DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable)) and
                (DocumentsTable.version eq expectedVersion) and
                (DocumentsTable.workflowState eq DocumentWorkflowState.IN_REVIEW.dbValue())
        }) {
            it[DocumentsTable.workflowState] = DocumentWorkflowState.DRAFT.dbValue()
            it[DocumentsTable.version] = expectedVersion + 1
            it[DocumentsTable.updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        if (updated == 0) return@transaction null
        DocumentReviewsTable.update({
            DocumentReviewsTable.id eq EntityID(UUID.fromString(reviewId.value), DocumentReviewsTable)
        }) {
            it[status] = ReviewStatus.CHANGES_REQUESTED.dbValue()
            it[updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        val auditId = AuditId.random()
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(auditId.value)
            it[DocumentAuditsTable.documentId] = UUID.fromString(documentId.value)
            it[actorId] = UUID.fromString(actorId.value)
            it[action] = DocumentAuditAction.REVIEW_REJECTED.dbValue()
            it[DocumentAuditsTable.reason] = reason
            it[fromState] = priorState.dbValue()
            it[toState] = DocumentWorkflowState.DRAFT.dbValue()
            it[DocumentAuditsTable.snapshotId] = null
            it[DocumentAuditsTable.diffSummary] = diffSummary
            it[requestId] = null
            it[ipFingerprint] = null
            it[DocumentAuditsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        WorkflowTransitionResult(
            documentId = documentId,
            state = DocumentWorkflowState.DRAFT,
            version = expectedVersion + 1,
            snapshotId = null,
            auditId = auditId
        )
    }

    override suspend fun publish(
        documentId: DocumentId,
        expectedVersion: Long,
        force: Boolean,
        reason: String?,
        diffSummary: String?,
        actorId: UserId
    ): WorkflowTransitionResult? = transaction {
        val now = Clock.System.now()
        val priorRow = DocumentsTable.select { DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }
            .limit(1)
            .firstOrNull() ?: return@transaction null
        val priorState = priorRow[DocumentsTable.workflowState].toWorkflowState()
        val expectedState = if (force) {
            DocumentsTable.workflowState inList listOf(
                DocumentWorkflowState.DRAFT.dbValue(),
                DocumentWorkflowState.IN_REVIEW.dbValue()
            )
        } else {
            DocumentsTable.workflowState eq DocumentWorkflowState.IN_REVIEW.dbValue()
        }
        val updated = DocumentsTable.update({
            (DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable)) and
                (DocumentsTable.version eq expectedVersion) and
                expectedState
        }) {
            it[DocumentsTable.workflowState] = DocumentWorkflowState.PUBLISHED.dbValue()
            it[DocumentsTable.version] = expectedVersion + 1
            it[DocumentsTable.updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        if (updated == 0) return@transaction null
        val snapshotId = SnapshotId.random()
        val docRow = DocumentsTable.select { DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }
            .limit(1).first()
        SnapshotsTable.insert {
            it[id] = UUID.fromString(snapshotId.value)
            it[documentId] = UUID.fromString(documentId.value)
            it[version] = expectedVersion + 1
            it[state] = SnapshotState.PUBLISHED.dbValue()
            it[title] = docRow[DocumentsTable.title]
            it[body] = docRow[DocumentsTable.body]
            it[languageCode] = docRow[DocumentsTable.languageCode]
            it[createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
            it[createdBy] = UUID.fromString(actorId.value)
            it[sourceDraftId] = null
            it[sourceRevision] = null
        }
        DocumentsTable.update({ DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }) {
            it[snapshotId] = UUID.fromString(snapshotId.value)
        }
        val auditId = AuditId.random()
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(auditId.value)
            it[DocumentAuditsTable.documentId] = UUID.fromString(documentId.value)
            it[actorId] = UUID.fromString(actorId.value)
            it[action] = if (force) DocumentAuditAction.FORCE_PUBLISH.dbValue() else DocumentAuditAction.PUBLISH.dbValue()
            it[DocumentAuditsTable.reason] = reason
            it[fromState] = priorState.dbValue()
            it[toState] = DocumentWorkflowState.PUBLISHED.dbValue()
            it[DocumentAuditsTable.snapshotId] = UUID.fromString(snapshotId.value)
            it[DocumentAuditsTable.diffSummary] = diffSummary
            it[requestId] = null
            it[ipFingerprint] = null
            it[DocumentAuditsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        WorkflowTransitionResult(
            documentId = documentId,
            state = DocumentWorkflowState.PUBLISHED,
            version = expectedVersion + 1,
            snapshotId = snapshotId,
            auditId = auditId
        )
    }

    override suspend fun archive(
        documentId: DocumentId,
        expectedVersion: Long,
        reason: String?,
        actorId: UserId
    ): WorkflowTransitionResult? = transaction {
        val now = Clock.System.now()
        val updated = DocumentsTable.update({
            (DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable)) and
                (DocumentsTable.version eq expectedVersion) and
                (DocumentsTable.workflowState eq DocumentWorkflowState.PUBLISHED.dbValue())
        }) {
            it[DocumentsTable.workflowState] = DocumentWorkflowState.ARCHIVED.dbValue()
            it[DocumentsTable.version] = expectedVersion + 1
            it[DocumentsTable.updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        if (updated == 0) return@transaction null
        val snapshotId = SnapshotId.random()
        val docRow = DocumentsTable.select { DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }
            .limit(1).first()
        SnapshotsTable.insert {
            it[id] = UUID.fromString(snapshotId.value)
            it[documentId] = UUID.fromString(documentId.value)
            it[version] = expectedVersion + 1
            it[state] = SnapshotState.ARCHIVED.dbValue()
            it[title] = docRow[DocumentsTable.title]
            it[body] = docRow[DocumentsTable.body]
            it[languageCode] = docRow[DocumentsTable.languageCode]
            it[createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
            it[createdBy] = UUID.fromString(actorId.value)
            it[sourceDraftId] = null
            it[sourceRevision] = null
        }
        DocumentsTable.update({ DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }) {
            it[snapshotId] = UUID.fromString(snapshotId.value)
        }
        val auditId = AuditId.random()
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(auditId.value)
            it[DocumentAuditsTable.documentId] = UUID.fromString(documentId.value)
            it[actorId] = UUID.fromString(actorId.value)
            it[action] = DocumentAuditAction.ARCHIVE.dbValue()
            it[DocumentAuditsTable.reason] = reason
            it[fromState] = DocumentWorkflowState.PUBLISHED.dbValue()
            it[toState] = DocumentWorkflowState.ARCHIVED.dbValue()
            it[DocumentAuditsTable.snapshotId] = UUID.fromString(snapshotId.value)
            it[DocumentAuditsTable.diffSummary] = null
            it[requestId] = null
            it[ipFingerprint] = null
            it[DocumentAuditsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        WorkflowTransitionResult(
            documentId = documentId,
            state = DocumentWorkflowState.ARCHIVED,
            version = expectedVersion + 1,
            snapshotId = snapshotId,
            auditId = auditId
        )
    }

    override suspend fun revert(
        documentId: DocumentId,
        expectedVersion: Long,
        snapshotId: SnapshotId,
        reason: String?,
        actorId: UserId
    ): WorkflowTransitionResult? = transaction {
        val now = Clock.System.now()
        val snapshotRow = SnapshotsTable.select {
            (SnapshotsTable.documentId eq UUID.fromString(documentId.value)) and
                (SnapshotsTable.id eq EntityID(UUID.fromString(snapshotId.value), SnapshotsTable))
        }.limit(1).firstOrNull() ?: return@transaction null
        val priorRow = DocumentsTable.select { DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable) }
            .limit(1)
            .firstOrNull() ?: return@transaction null
        val priorState = priorRow[DocumentsTable.workflowState].toWorkflowState()
        val updated = DocumentsTable.update({
            (DocumentsTable.id eq EntityID(UUID.fromString(documentId.value), DocumentsTable)) and
                (DocumentsTable.version eq expectedVersion)
        }) {
            it[DocumentsTable.workflowState] = DocumentWorkflowState.DRAFT.dbValue()
            it[DocumentsTable.version] = expectedVersion + 1
            it[DocumentsTable.title] = snapshotRow[SnapshotsTable.title]
            it[DocumentsTable.body] = snapshotRow[SnapshotsTable.body]
            it[DocumentsTable.languageCode] = snapshotRow[SnapshotsTable.languageCode]
            it[DocumentsTable.updatedAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        if (updated == 0) return@transaction null
        val auditId = AuditId.random()
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(auditId.value)
            it[DocumentAuditsTable.documentId] = UUID.fromString(documentId.value)
            it[actorId] = UUID.fromString(actorId.value)
            it[action] = DocumentAuditAction.REVERT.dbValue()
            it[DocumentAuditsTable.reason] = reason
            it[fromState] = priorState.dbValue()
            it[toState] = DocumentWorkflowState.DRAFT.dbValue()
            it[DocumentAuditsTable.snapshotId] = UUID.fromString(snapshotId.value)
            it[DocumentAuditsTable.diffSummary] = null
            it[requestId] = null
            it[ipFingerprint] = null
            it[DocumentAuditsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        WorkflowTransitionResult(
            documentId = documentId,
            state = DocumentWorkflowState.DRAFT,
            version = expectedVersion + 1,
            snapshotId = snapshotId,
            auditId = auditId
        )
    }

    override suspend fun addReviewComment(reviewId: ReviewId, authorId: UserId, body: String): ReviewComment? = transaction {
        val existing = DocumentReviewsTable.select {
            DocumentReviewsTable.id eq EntityID(UUID.fromString(reviewId.value), DocumentReviewsTable)
        }.limit(1).firstOrNull() ?: return@transaction null
        val now = Clock.System.now()
        val commentId = ReviewCommentId.random()
        ReviewCommentsTable.insert {
            it[id] = UUID.fromString(commentId.value)
            it[ReviewCommentsTable.reviewId] = UUID.fromString(reviewId.value)
            it[ReviewCommentsTable.authorId] = UUID.fromString(authorId.value)
            it[ReviewCommentsTable.body] = body
            it[ReviewCommentsTable.createdAt] = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        ReviewComment(
            commentId = commentId,
            reviewId = ReviewId(existing[DocumentReviewsTable.id].value.toString()),
            authorId = authorId,
            body = body,
            createdAt = now
        )
    }
}

private fun ReviewStatus.dbValue(): String = when (this) {
    ReviewStatus.IN_REVIEW -> "in_review"
    ReviewStatus.CHANGES_REQUESTED -> "changes_requested"
    ReviewStatus.APPROVED -> "approved"
}

private fun DocumentWorkflowState.dbValue(): String = when (this) {
    DocumentWorkflowState.DRAFT -> "draft"
    DocumentWorkflowState.IN_REVIEW -> "in_review"
    DocumentWorkflowState.PUBLISHED -> "published"
    DocumentWorkflowState.ARCHIVED -> "archived"
}

private fun String.toWorkflowState(): DocumentWorkflowState = when (this) {
    "draft" -> DocumentWorkflowState.DRAFT
    "in_review" -> DocumentWorkflowState.IN_REVIEW
    "published" -> DocumentWorkflowState.PUBLISHED
    "archived" -> DocumentWorkflowState.ARCHIVED
    else -> error("Unknown workflow state $this")
}

private fun DocumentAuditAction.dbValue(): String = when (this) {
    DocumentAuditAction.DRAFT_CREATED -> "draft.created"
    DocumentAuditAction.DRAFT_UPDATED -> "draft.updated"
    DocumentAuditAction.REVIEW_SUBMITTED -> "review.submitted"
    DocumentAuditAction.REVIEW_APPROVED -> "review.approved"
    DocumentAuditAction.REVIEW_REJECTED -> "review.rejected"
    DocumentAuditAction.PUBLISH -> "publish"
    DocumentAuditAction.ARCHIVE -> "archive"
    DocumentAuditAction.UNARCHIVE -> "unarchive"
    DocumentAuditAction.REVERT -> "revert"
    DocumentAuditAction.FORCE_PUBLISH -> "force_publish"
}

private fun SnapshotState.dbValue(): String = when (this) {
    SnapshotState.PUBLISHED -> "published"
    SnapshotState.ARCHIVED -> "archived"
}
