package org.themessagesearch.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AuditId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = AuditId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
enum class SnapshotState {
    @SerialName("published")
    PUBLISHED,
    @SerialName("archived")
    ARCHIVED
}

@Serializable
data class Snapshot(
    val snapshotId: SnapshotId,
    val documentId: DocumentId,
    val version: Long,
    val state: SnapshotState,
    val title: String,
    val body: String,
    val languageCode: String,
    val createdAt: Instant,
    val createdBy: UserId,
    val sourceDraftId: String? = null,
    val sourceRevision: String? = null
)

@Serializable
data class SnapshotResponse(
    val snapshotId: String,
    val documentId: String,
    val version: Long,
    val state: SnapshotState,
    val title: String,
    val body: String,
    val languageCode: String,
    val createdAt: Instant,
    val createdBy: String,
    val sourceDraftId: String? = null,
    val sourceRevision: String? = null
)

@Serializable
data class SnapshotListItem(
    val snapshotId: String,
    val version: Long,
    val state: SnapshotState,
    val languageCode: String,
    val createdAt: Instant,
    val createdBy: String
)

@Serializable
data class SnapshotListResponse(
    val items: List<SnapshotListItem>,
    val nextCursor: String? = null
)

data class SnapshotListResult(
    val items: List<Snapshot>,
    val nextCursor: String? = null
)

@Serializable
enum class DocumentAuditAction {
    @SerialName("draft.created")
    DRAFT_CREATED,
    @SerialName("draft.updated")
    DRAFT_UPDATED,
    @SerialName("review.submitted")
    REVIEW_SUBMITTED,
    @SerialName("review.approved")
    REVIEW_APPROVED,
    @SerialName("review.rejected")
    REVIEW_REJECTED,
    @SerialName("publish")
    PUBLISH,
    @SerialName("archive")
    ARCHIVE,
    @SerialName("unarchive")
    UNARCHIVE,
    @SerialName("revert")
    REVERT,
    @SerialName("force_publish")
    FORCE_PUBLISH
}

@Serializable
data class DocumentAuditEvent(
    val auditId: AuditId,
    val documentId: DocumentId,
    val actorId: UserId,
    val action: DocumentAuditAction,
    val reason: String? = null,
    val fromState: DocumentWorkflowState? = null,
    val toState: DocumentWorkflowState? = null,
    val snapshotId: SnapshotId? = null,
    val diffSummary: String? = null,
    val requestId: String? = null,
    val ipFingerprint: String? = null,
    val createdAt: Instant
)

@Serializable
data class DocumentAuditListResponse(
    val items: List<DocumentAuditEventResponse>,
    val nextCursor: String? = null
)

@Serializable
data class DocumentAuditEventResponse(
    val auditId: String,
    val documentId: String,
    val actorId: String,
    val action: DocumentAuditAction,
    val reason: String? = null,
    val fromState: DocumentWorkflowState? = null,
    val toState: DocumentWorkflowState? = null,
    val snapshotId: String? = null,
    val diffSummary: String? = null,
    val requestId: String? = null,
    val createdAt: Instant
)

data class DocumentAuditListResult(
    val items: List<DocumentAuditEvent>,
    val nextCursor: String? = null
)

fun Snapshot.toListItem(): SnapshotListItem = SnapshotListItem(
    snapshotId = snapshotId.value,
    version = version,
    state = state,
    languageCode = languageCode,
    createdAt = createdAt,
    createdBy = createdBy.value
)

fun Snapshot.toResponse(): SnapshotResponse = SnapshotResponse(
    snapshotId = snapshotId.value,
    documentId = documentId.value,
    version = version,
    state = state,
    title = title,
    body = body,
    languageCode = languageCode,
    createdAt = createdAt,
    createdBy = createdBy.value,
    sourceDraftId = sourceDraftId,
    sourceRevision = sourceRevision
)

fun SnapshotListResult.toResponse(): SnapshotListResponse = SnapshotListResponse(
    items = items.map { it.toListItem() },
    nextCursor = nextCursor
)

fun DocumentAuditEvent.toResponse(): DocumentAuditEventResponse = DocumentAuditEventResponse(
    auditId = auditId.value,
    documentId = documentId.value,
    actorId = actorId.value,
    action = action,
    reason = reason,
    fromState = fromState,
    toState = toState,
    snapshotId = snapshotId?.value,
    diffSummary = diffSummary,
    requestId = requestId,
    createdAt = createdAt
)

fun DocumentAuditListResult.toResponse(): DocumentAuditListResponse = DocumentAuditListResponse(
    items = items.map { it.toResponse() },
    nextCursor = nextCursor
)
