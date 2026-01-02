package org.themessagesearch.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class DocumentWorkflowState {
    @SerialName("draft")
    DRAFT,
    @SerialName("in_review")
    IN_REVIEW,
    @SerialName("published")
    PUBLISHED,
    @SerialName("archived")
    ARCHIVED
}

@Serializable
enum class ReviewStatus {
    @SerialName("in_review")
    IN_REVIEW,
    @SerialName("changes_requested")
    CHANGES_REQUESTED,
    @SerialName("approved")
    APPROVED
}

@Serializable
data class ReviewId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = ReviewId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
data class ReviewRequest(
    val reviewId: ReviewId,
    val documentId: DocumentId,
    val summary: String,
    val reviewers: List<UserId>,
    val status: ReviewStatus,
    val createdBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class ReviewCommentId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = ReviewCommentId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
data class ReviewComment(
    val commentId: ReviewCommentId,
    val reviewId: ReviewId,
    val authorId: UserId,
    val body: String,
    val createdAt: Instant
)

@Serializable
data class ReviewSubmitRequest(
    val summary: String,
    val reviewers: List<String>
)

@Serializable
data class ReviewDecisionRequest(
    val reason: String? = null,
    val diffSummary: String? = null
)

@Serializable
data class PublishRequest(
    val force: Boolean = false,
    val reason: String? = null,
    val diffSummary: String? = null
)

@Serializable
data class RevertRequest(
    val snapshotId: String,
    val reason: String? = null
)

@Serializable
data class ArchiveRequest(val reason: String)

@Serializable
data class ReviewCommentRequest(val body: String)

@Serializable
data class ReviewResponse(
    val reviewId: String,
    val documentId: String,
    val status: ReviewStatus,
    val createdAt: Instant
)

@Serializable
data class WorkflowTransitionResponse(
    val documentId: String,
    val state: DocumentWorkflowState,
    val version: Long,
    val snapshotId: String? = null,
    val auditId: String? = null
)

@Serializable
data class ReviewCommentResponse(
    val commentId: String,
    val reviewId: String,
    val createdAt: Instant
)

data class WorkflowDocument(
    val documentId: DocumentId,
    val title: String,
    val body: String,
    val languageCode: String,
    val version: Long,
    val state: DocumentWorkflowState,
    val snapshotId: SnapshotId?
)

data class WorkflowTransitionResult(
    val documentId: DocumentId,
    val state: DocumentWorkflowState,
    val version: Long,
    val snapshotId: SnapshotId? = null,
    val auditId: AuditId? = null
)

fun ReviewRequest.toResponse(): ReviewResponse = ReviewResponse(
    reviewId = reviewId.value,
    documentId = documentId.value,
    status = status,
    createdAt = createdAt
)

fun WorkflowTransitionResult.toResponse(): WorkflowTransitionResponse = WorkflowTransitionResponse(
    documentId = documentId.value,
    state = state,
    version = version,
    snapshotId = snapshotId?.value,
    auditId = auditId?.value
)

fun ReviewComment.toResponse(): ReviewCommentResponse = ReviewCommentResponse(
    commentId = commentId.value,
    reviewId = reviewId.value,
    createdAt = createdAt
)
