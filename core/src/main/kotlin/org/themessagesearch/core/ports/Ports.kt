package org.themessagesearch.core.ports

import org.themessagesearch.core.model.*

interface DocumentRepository {
    suspend fun create(request: DocumentCreateRequest, actorId: UserId? = null): Document
    suspend fun findById(id: DocumentId, snapshotId: SnapshotId? = null, languageCode: String? = null): Document?
    suspend fun fetchByIds(ids: Collection<DocumentId>, languageCode: String? = null): Map<DocumentId, Document>
    suspend fun listParagraphsMissingEmbedding(limit: Int, cursor: ParagraphId? = null, languageCode: String? = null): List<DocumentParagraph>
    suspend fun listDocuments(
        limit: Int,
        offset: Int,
        languageCode: String? = null,
        title: String? = null,
        state: DocumentWorkflowState? = null
    ): DocumentListResponse
}

interface SnapshotRepository {
    suspend fun create(snapshot: Snapshot): Snapshot
    suspend fun list(documentId: DocumentId, limit: Int, cursor: String?): SnapshotListResult
    suspend fun findById(documentId: DocumentId, snapshotId: SnapshotId): Snapshot?
}

interface AuditRepository {
    suspend fun create(event: DocumentAuditEvent): DocumentAuditEvent
    suspend fun list(documentId: DocumentId, limit: Int, cursor: String?): DocumentAuditListResult
    suspend fun findById(documentId: DocumentId, auditId: AuditId): DocumentAuditEvent?
}

interface WorkflowRepository {
    suspend fun getDocument(documentId: DocumentId): WorkflowDocument?
    suspend fun submitForReview(
        documentId: DocumentId,
        expectedVersion: Long,
        summary: String,
        reviewers: List<UserId>,
        actorId: UserId
    ): ReviewRequest?
    suspend fun approveReview(
        documentId: DocumentId,
        reviewId: ReviewId,
        expectedVersion: Long,
        reason: String?,
        diffSummary: String?,
        actorId: UserId
    ): WorkflowTransitionResult?
    suspend fun requestChanges(
        documentId: DocumentId,
        reviewId: ReviewId,
        expectedVersion: Long,
        reason: String?,
        diffSummary: String?,
        actorId: UserId
    ): WorkflowTransitionResult?
    suspend fun publish(
        documentId: DocumentId,
        expectedVersion: Long,
        force: Boolean,
        reason: String?,
        diffSummary: String?,
        actorId: UserId
    ): WorkflowTransitionResult?
    suspend fun archive(
        documentId: DocumentId,
        expectedVersion: Long,
        reason: String?,
        actorId: UserId
    ): WorkflowTransitionResult?
    suspend fun revert(
        documentId: DocumentId,
        expectedVersion: Long,
        snapshotId: SnapshotId,
        reason: String?,
        actorId: UserId
    ): WorkflowTransitionResult?
    suspend fun addReviewComment(
        reviewId: ReviewId,
        authorId: UserId,
        body: String
    ): ReviewComment?
}

interface EmbeddingRepository {
    suspend fun upsertParagraphEmbedding(paragraphId: ParagraphId, vector: FloatArray)
    suspend fun batchUpsertParagraphEmbeddings(vectors: Map<ParagraphId, FloatArray>)
    suspend fun hasParagraphEmbedding(paragraphId: ParagraphId): Boolean
}

interface HybridSearchService {
    suspend fun search(query: String, limit: Int, offset: Int, weights: HybridWeights, languageCode: String? = null): SearchResponse
}

interface EmbeddingClient {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

interface ChatClient {
    suspend fun generate(prompt: String, context: List<String>): String
}

interface AnswerService {
    suspend fun answer(query: String, limit: Int, weights: HybridWeights, languageCode: String? = null): AnswerResponse
}

interface EmbeddingBackfillService {
    suspend fun backfill(batchSize: Int, cursor: ParagraphId?, languageCode: String? = null): BackfillResult
}

interface CollaborationRepository {
    data class AppendResult(val accepted: Boolean, val latestUpdateId: Long)

    suspend fun appendUpdate(update: CollaborationUpdate): AppendResult
    suspend fun listUpdates(
        documentId: DocumentId,
        paragraphId: ParagraphId?,
        languageCode: String,
        afterId: Long?,
        limit: Int
    ): List<CollaborationUpdate>
    suspend fun getSnapshot(documentId: DocumentId, languageCode: String): CollaborationSnapshot?
    suspend fun upsertSnapshot(snapshot: CollaborationSnapshot)
}

interface UserRepository {
    suspend fun findById(id: UserId): UserProfile?
    suspend fun findByEmail(email: String): UserProfile?
    suspend fun findAuthByEmail(email: String): UserAuthRecord?
    suspend fun findOrCreateFromAuth(id: UserId, roles: List<UserRole>, email: String?, displayName: String?): UserProfile
    suspend fun listUsers(limit: Int, cursor: String?, includeDeleted: Boolean = false): UserListResult
    suspend fun createUser(request: UserCreateRequest, actorId: UserId): UserProfile?
    suspend fun createUserWithPassword(request: UserRegisterRequest, passwordHash: String): UserProfile?
    suspend fun replaceRoles(userId: UserId, roles: List<UserRole>, actorId: UserId, reason: String): UserProfile?
    suspend fun setPassword(userId: UserId, passwordHash: String): UserProfile?
    suspend fun updateStatus(userId: UserId, status: UserStatus, actorId: UserId, reason: String): UserProfile?
    suspend fun deleteUser(userId: UserId, actorId: UserId, reason: String): UserProfile?
    suspend fun listAudits(userId: UserId, limit: Int, cursor: String?): UserAuditListResult
}
