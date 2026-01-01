package org.themessagesearch.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

data class CollaborationUpdate(
    val id: Long? = null,
    val documentId: DocumentId,
    val paragraphId: ParagraphId,
    val clientId: String,
    val userId: String,
    val languageCode: String,
    val seq: Long,
    val payload: ByteArray,
    val createdAt: Instant? = null
)

data class CollaborationSnapshot(
    val documentId: DocumentId,
    val languageCode: String,
    val snapshotVersion: Long,
    val payload: ByteArray,
    val createdAt: Instant? = null
)

data class CollaborationParagraph(
    val paragraphId: ParagraphId,
    val documentId: DocumentId,
    val languageCode: String,
    val position: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CollaborationSession(
    val documentId: DocumentId,
    val clientId: String,
    val userId: String,
    val connectedAt: Instant,
    val lastSeenAt: Instant
)

@Serializable
data class CollaborationUpdateRequest(
    val clientId: String,
    val paragraphId: String,
    val languageCode: String,
    val seq: Long,
    val update: String
)

@Serializable
data class CollaborationUpdateAckResponse(
    val accepted: Boolean,
    val latestUpdateId: Long
)

@Serializable
data class CollaborationUpdateItem(
    val id: Long,
    val paragraphId: String,
    val clientId: String,
    val seq: Long,
    val languageCode: String,
    val update: String,
    val createdAt: Instant
)

@Serializable
data class CollaborationUpdatesResponse(
    val updates: List<CollaborationUpdateItem>
)

@Serializable
data class CollaborationSnapshotResponse(
    val snapshotVersion: Long,
    val payload: String,
    val createdAt: Instant
)
