package org.themessagesearch.infra.db.repo

import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.themessagesearch.core.model.CollaborationSnapshot
import org.themessagesearch.core.model.CollaborationUpdate
import org.themessagesearch.core.model.DocumentId
import org.themessagesearch.core.model.ParagraphId
import org.themessagesearch.core.ports.CollaborationRepository
import java.time.ZoneOffset
import java.util.UUID

object CollabParagraphsTable : Table("collab_paragraphs") {
    val paragraphId = uuid("paragraph_id")
    val documentId = uuid("document_id")
    val languageCode = text("language_code")
    val position = integer("position")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(paragraphId)
}

object CollabUpdatesTable : LongIdTable("collab_updates") {
    val documentId = uuid("document_id")
    val paragraphId = uuid("paragraph_id")
    val clientId = uuid("client_id")
    val userId = text("user_id")
    val languageCode = text("language_code")
    val seq = long("seq")
    val payload = binary("payload")
    val createdAt = timestampWithTimeZone("created_at")

    init {
        uniqueIndex(documentId, paragraphId, clientId, seq)
        index("idx_collab_updates_document_paragraph_id", false, documentId, paragraphId, id)
        index("idx_collab_updates_document_created_at", false, documentId, createdAt)
    }
}

object CollabSnapshotsTable : Table("collab_snapshots") {
    val documentId = uuid("document_id")
    val languageCode = text("language_code")
    val snapshotVersion = long("snapshot_version")
    val payload = binary("payload")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(documentId, languageCode)
}

class ExposedCollaborationRepository : CollaborationRepository {
    override suspend fun appendUpdate(update: CollaborationUpdate): CollaborationRepository.AppendResult = transaction {
        val now = update.createdAt ?: Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val insert = CollabUpdatesTable.insertIgnore {
            it[documentId] = UUID.fromString(update.documentId.value)
            it[paragraphId] = UUID.fromString(update.paragraphId.value)
            it[clientId] = UUID.fromString(update.clientId)
            it[userId] = update.userId
            it[languageCode] = update.languageCode
            it[seq] = update.seq
            it[payload] = update.payload
            it[createdAt] = offsetNow
        }
        val insertedId = insert.resultedValues?.singleOrNull()?.get(CollabUpdatesTable.id)?.value
        val existingId = insertedId ?: CollabUpdatesTable
            .slice(CollabUpdatesTable.id)
            .selectAll().where {
                (CollabUpdatesTable.documentId eq UUID.fromString(update.documentId.value)) and
                        (CollabUpdatesTable.paragraphId eq UUID.fromString(update.paragraphId.value)) and
                        (CollabUpdatesTable.clientId eq UUID.fromString(update.clientId)) and
                        (CollabUpdatesTable.seq eq update.seq)
            }
            .limit(1)
            .firstOrNull()
            ?.get(CollabUpdatesTable.id)
            ?.value
            ?: error("Unable to resolve collab update id for ${update.documentId.value}")
        CollaborationRepository.AppendResult(insertedId != null, existingId)
    }

    override suspend fun listUpdates(
        documentId: DocumentId,
        paragraphId: ParagraphId?,
        languageCode: String,
        afterId: Long?,
        limit: Int
    ): List<CollaborationUpdate> = transaction {
        val base = CollabUpdatesTable.selectAll().where {
            (CollabUpdatesTable.documentId eq UUID.fromString(documentId.value)) and
                    (CollabUpdatesTable.languageCode eq languageCode)
        }
        paragraphId?.let {
            base.andWhere { CollabUpdatesTable.paragraphId eq UUID.fromString(it.value) }
        }
        afterId?.let {
            base.andWhere { CollabUpdatesTable.id greater EntityID(it, CollabUpdatesTable) }
        }
        base.orderBy(CollabUpdatesTable.id to SortOrder.ASC)
            .limit(limit)
            .map { row -> row.toCollaborationUpdate() }
    }

    override suspend fun getSnapshot(documentId: DocumentId, languageCode: String): CollaborationSnapshot? = transaction {
        CollabSnapshotsTable
            .selectAll().where {
                (CollabSnapshotsTable.documentId eq UUID.fromString(documentId.value)) and
                        (CollabSnapshotsTable.languageCode eq languageCode)
            }
            .limit(1)
            .firstOrNull()
            ?.toCollaborationSnapshot()
    }

    override suspend fun upsertSnapshot(snapshot: CollaborationSnapshot): Unit = transaction {
        val now = snapshot.createdAt ?: Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val existing = CollabSnapshotsTable
            .selectAll().where {
                (CollabSnapshotsTable.documentId eq UUID.fromString(snapshot.documentId.value)) and
                        (CollabSnapshotsTable.languageCode eq snapshot.languageCode)
            }
            .limit(1)
            .firstOrNull()
        if (existing == null) {
            CollabSnapshotsTable.insert {
                it[documentId] = UUID.fromString(snapshot.documentId.value)
                it[languageCode] = snapshot.languageCode
                it[snapshotVersion] = snapshot.snapshotVersion
                it[payload] = snapshot.payload
                it[createdAt] = offsetNow
            }
        } else {
            CollabSnapshotsTable.update({
                (CollabSnapshotsTable.documentId eq UUID.fromString(snapshot.documentId.value)) and
                    (CollabSnapshotsTable.languageCode eq snapshot.languageCode)
            }) {
                it[snapshotVersion] = snapshot.snapshotVersion
                it[payload] = snapshot.payload
                it[createdAt] = offsetNow
            }
        }
    }

    private fun ResultRow.toCollaborationUpdate(): CollaborationUpdate = CollaborationUpdate(
        id = this[CollabUpdatesTable.id].value,
        documentId = DocumentId(this[CollabUpdatesTable.documentId].toString()),
        paragraphId = ParagraphId(this[CollabUpdatesTable.paragraphId].toString()),
        clientId = this[CollabUpdatesTable.clientId].toString(),
        userId = this[CollabUpdatesTable.userId],
        languageCode = this[CollabUpdatesTable.languageCode],
        seq = this[CollabUpdatesTable.seq],
        payload = this[CollabUpdatesTable.payload],
        createdAt = this[CollabUpdatesTable.createdAt].toInstant().toKotlinInstant()
    )

    private fun ResultRow.toCollaborationSnapshot(): CollaborationSnapshot = CollaborationSnapshot(
        documentId = DocumentId(this[CollabSnapshotsTable.documentId].toString()),
        languageCode = this[CollabSnapshotsTable.languageCode],
        snapshotVersion = this[CollabSnapshotsTable.snapshotVersion],
        payload = this[CollabSnapshotsTable.payload],
        createdAt = this[CollabSnapshotsTable.createdAt].toInstant().toKotlinInstant()
    )
}
