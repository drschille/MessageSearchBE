package org.themessagesearch.infra.db.repo

import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.AuditRepository
import org.themessagesearch.core.ports.SnapshotRepository
import java.time.ZoneOffset
import java.util.UUID

object SnapshotsTable : UUIDTable("snapshots") {
    val documentId = uuid("document_id")
    val version = long("version")
    val state = text("state")
    val title = text("title")
    val body = text("body")
    val languageCode = text("language_code")
    val createdAt = timestampWithTimeZone("created_at")
    val createdBy = uuid("created_by")
    val sourceDraftId = uuid("source_draft_id").nullable()
    val sourceRevision = text("source_revision").nullable()
}

object DocumentAuditsTable : UUIDTable("document_audits", "audit_id") {
    val documentId = uuid("document_id")
    val actorId = uuid("actor_id")
    val action = text("action")
    val reason = text("reason").nullable()
    val fromState = text("from_state").nullable()
    val toState = text("to_state").nullable()
    val snapshotId = uuid("snapshot_id").nullable()
    val diffSummary = text("diff_summary").nullable()
    val requestId = text("request_id").nullable()
    val ipFingerprint = text("ip_fingerprint").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

class ExposedSnapshotRepository : SnapshotRepository {
    override suspend fun create(snapshot: Snapshot): Snapshot = transaction {
        SnapshotsTable.insert {
            it[id] = UUID.fromString(snapshot.snapshotId.value)
            it[documentId] = UUID.fromString(snapshot.documentId.value)
            it[version] = snapshot.version
            it[state] = snapshot.state.dbValue()
            it[title] = snapshot.title
            it[body] = snapshot.body
            it[languageCode] = snapshot.languageCode
            it[createdAt] = snapshot.createdAt.toJavaInstant().atOffset(ZoneOffset.UTC)
            it[createdBy] = UUID.fromString(snapshot.createdBy.value)
            it[sourceDraftId] = snapshot.sourceDraftId?.let { UUID.fromString(it) }
            it[sourceRevision] = snapshot.sourceRevision
        }
        snapshot
    }

    override suspend fun list(documentId: DocumentId, limit: Int, cursor: String?): SnapshotListResult = transaction {
        val parsedCursor = cursor?.let { parseCursor(it) }
        val base = SnapshotsTable.select { SnapshotsTable.documentId eq UUID.fromString(documentId.value) }
        if (parsedCursor != null) {
            val cursorEntity = EntityID(parsedCursor.snapshotId, SnapshotsTable)
            base.andWhere {
                (SnapshotsTable.createdAt less parsedCursor.createdAt) or
                    (SnapshotsTable.createdAt eq parsedCursor.createdAt and (SnapshotsTable.id less cursorEntity))
            }
        }
        val rows = base
            .orderBy(SnapshotsTable.createdAt to SortOrder.DESC, SnapshotsTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val hasNext = rows.size > limit
        val page = rows.take(limit)
        val items = page.map { it.toSnapshot() }
        val nextCursor = if (hasNext) {
            val last = page.last()
            formatCursor(last[SnapshotsTable.createdAt].toInstant().toKotlinInstant(), last[SnapshotsTable.id].value)
        } else {
            null
        }
        SnapshotListResult(items, nextCursor)
    }

    override suspend fun findById(documentId: DocumentId, snapshotId: SnapshotId): Snapshot? = transaction {
        val row = SnapshotsTable.select {
            (SnapshotsTable.documentId eq UUID.fromString(documentId.value)) and
                (SnapshotsTable.id eq EntityID(UUID.fromString(snapshotId.value), SnapshotsTable))
        }.limit(1).firstOrNull() ?: return@transaction null
        row.toSnapshot()
    }

    private fun ResultRow.toSnapshot(): Snapshot = Snapshot(
        snapshotId = SnapshotId(this[SnapshotsTable.id].value.toString()),
        documentId = DocumentId(this[SnapshotsTable.documentId].toString()),
        version = this[SnapshotsTable.version],
        state = this[SnapshotsTable.state].toSnapshotState(),
        title = this[SnapshotsTable.title],
        body = this[SnapshotsTable.body],
        languageCode = this[SnapshotsTable.languageCode],
        createdAt = this[SnapshotsTable.createdAt].toInstant().toKotlinInstant(),
        createdBy = UserId(this[SnapshotsTable.createdBy].toString()),
        sourceDraftId = this[SnapshotsTable.sourceDraftId]?.toString(),
        sourceRevision = this[SnapshotsTable.sourceRevision]
    )

    private fun parseCursor(cursor: String): ParsedCursor {
        val parts = cursor.split('|', limit = 2)
        require(parts.size == 2) { "invalid cursor" }
        val instant = Instant.parse(parts[0])
        val id = UUID.fromString(parts[1])
        return ParsedCursor(instant.toJavaInstant().atOffset(ZoneOffset.UTC), id)
    }

    private fun formatCursor(createdAt: Instant, snapshotId: UUID): String =
        "${createdAt.toString()}|${snapshotId}"

    private data class ParsedCursor(val createdAt: java.time.OffsetDateTime, val snapshotId: UUID)
}

class ExposedAuditRepository : AuditRepository {
    override suspend fun create(event: DocumentAuditEvent): DocumentAuditEvent = transaction {
        DocumentAuditsTable.insert {
            it[id] = UUID.fromString(event.auditId.value)
            it[documentId] = UUID.fromString(event.documentId.value)
            it[actorId] = UUID.fromString(event.actorId.value)
            it[action] = event.action.dbValue()
            it[reason] = event.reason
            it[fromState] = event.fromState?.dbValue()
            it[toState] = event.toState?.dbValue()
            it[snapshotId] = event.snapshotId?.let { id -> UUID.fromString(id.value) }
            it[diffSummary] = event.diffSummary
            it[requestId] = event.requestId
            it[ipFingerprint] = event.ipFingerprint
            it[createdAt] = event.createdAt.toJavaInstant().atOffset(ZoneOffset.UTC)
        }
        event
    }

    override suspend fun list(documentId: DocumentId, limit: Int, cursor: String?): DocumentAuditListResult = transaction {
        val parsedCursor = cursor?.let { parseCursor(it) }
        val base = DocumentAuditsTable.select { DocumentAuditsTable.documentId eq UUID.fromString(documentId.value) }
        if (parsedCursor != null) {
            val cursorEntity = EntityID(parsedCursor.auditId, DocumentAuditsTable)
            base.andWhere {
                (DocumentAuditsTable.createdAt less parsedCursor.createdAt) or
                    (DocumentAuditsTable.createdAt eq parsedCursor.createdAt and (DocumentAuditsTable.id less cursorEntity))
            }
        }
        val rows = base
            .orderBy(DocumentAuditsTable.createdAt to SortOrder.DESC, DocumentAuditsTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val hasNext = rows.size > limit
        val page = rows.take(limit)
        val items = page.map { it.toAuditEvent() }
        val nextCursor = if (hasNext) {
            val last = page.last()
            formatCursor(last[DocumentAuditsTable.createdAt].toInstant().toKotlinInstant(), last[DocumentAuditsTable.id].value)
        } else {
            null
        }
        DocumentAuditListResult(items, nextCursor)
    }

    override suspend fun findById(documentId: DocumentId, auditId: AuditId): DocumentAuditEvent? = transaction {
        val row = DocumentAuditsTable.select {
            (DocumentAuditsTable.documentId eq UUID.fromString(documentId.value)) and
                (DocumentAuditsTable.id eq EntityID(UUID.fromString(auditId.value), DocumentAuditsTable))
        }.limit(1).firstOrNull() ?: return@transaction null
        row.toAuditEvent()
    }

    private fun ResultRow.toAuditEvent(): DocumentAuditEvent = DocumentAuditEvent(
        auditId = AuditId(this[DocumentAuditsTable.id].value.toString()),
        documentId = DocumentId(this[DocumentAuditsTable.documentId].toString()),
        actorId = UserId(this[DocumentAuditsTable.actorId].toString()),
        action = this[DocumentAuditsTable.action].toDocumentAuditAction(),
        reason = this[DocumentAuditsTable.reason],
        fromState = this[DocumentAuditsTable.fromState]?.toSnapshotState(),
        toState = this[DocumentAuditsTable.toState]?.toSnapshotState(),
        snapshotId = this[DocumentAuditsTable.snapshotId]?.let { SnapshotId(it.toString()) },
        diffSummary = this[DocumentAuditsTable.diffSummary],
        requestId = this[DocumentAuditsTable.requestId],
        ipFingerprint = this[DocumentAuditsTable.ipFingerprint],
        createdAt = this[DocumentAuditsTable.createdAt].toInstant().toKotlinInstant()
    )

    private fun parseCursor(cursor: String): ParsedCursor {
        val parts = cursor.split('|', limit = 2)
        require(parts.size == 2) { "invalid cursor" }
        val instant = Instant.parse(parts[0])
        val id = UUID.fromString(parts[1])
        return ParsedCursor(instant.toJavaInstant().atOffset(ZoneOffset.UTC), id)
    }

    private fun formatCursor(createdAt: Instant, auditId: UUID): String =
        "${createdAt.toString()}|${auditId}"

    private data class ParsedCursor(val createdAt: java.time.OffsetDateTime, val auditId: UUID)
}

private fun String.toSnapshotState(): SnapshotState = when (this) {
    "published" -> SnapshotState.PUBLISHED
    "archived" -> SnapshotState.ARCHIVED
    else -> error("Unknown snapshot state $this")
}

private fun SnapshotState.dbValue(): String = when (this) {
    SnapshotState.PUBLISHED -> "published"
    SnapshotState.ARCHIVED -> "archived"
}

private fun String.toDocumentAuditAction(): DocumentAuditAction = when (this) {
    "draft.created" -> DocumentAuditAction.DRAFT_CREATED
    "draft.updated" -> DocumentAuditAction.DRAFT_UPDATED
    "review.submitted" -> DocumentAuditAction.REVIEW_SUBMITTED
    "review.approved" -> DocumentAuditAction.REVIEW_APPROVED
    "review.rejected" -> DocumentAuditAction.REVIEW_REJECTED
    "publish" -> DocumentAuditAction.PUBLISH
    "archive" -> DocumentAuditAction.ARCHIVE
    "unarchive" -> DocumentAuditAction.UNARCHIVE
    "revert" -> DocumentAuditAction.REVERT
    "force_publish" -> DocumentAuditAction.FORCE_PUBLISH
    else -> error("Unknown audit action $this")
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
