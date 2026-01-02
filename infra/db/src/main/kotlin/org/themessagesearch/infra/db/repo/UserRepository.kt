package org.themessagesearch.infra.db.repo

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import org.themessagesearch.core.model.*
import org.themessagesearch.core.ports.UserRepository
import java.time.ZoneOffset
import java.util.*

private object UsersTable : UUIDTable("users") {
    val email = text("email").nullable()
    val displayName = text("display_name").nullable()
    val status = text("status")
    val passwordHash = text("password_hash").nullable()
    val passwordUpdatedAt = timestampWithTimeZone("password_updated_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

private object UserRolesTable : Table("user_roles") {
    val userId = reference("user_id", UsersTable)
    val role = text("role")
    val assignedBy = reference("assigned_by", UsersTable)
    val assignedAt = timestampWithTimeZone("assigned_at")
}

private object UserAuditsTable : UUIDTable("user_audits", "audit_id") {
    val actorId = reference("actor_id", UsersTable)
    val targetUserId = reference("target_user_id", UsersTable)
    val action = text("action")
    val reason = text("reason").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

class ExposedUserRepository : UserRepository {
    override suspend fun findById(id: UserId): UserProfile? = transaction {
        val row = UsersTable.selectAll().where { UsersTable.id eq EntityID(UUID.fromString(id.value), UsersTable) }
            .limit(1)
            .firstOrNull()
            ?: return@transaction null
        val roles = loadCurrentRoles(listOf(row[UsersTable.id].value))[row[UsersTable.id].value].orEmpty()
        row.toUser(roles)
    }

    override suspend fun findByEmail(email: String): UserProfile? = transaction {
        val row = UsersTable.selectAll().where { UsersTable.email eq email }
            .limit(1)
            .firstOrNull()
            ?: return@transaction null
        val roles = loadCurrentRoles(listOf(row[UsersTable.id].value))[row[UsersTable.id].value].orEmpty()
        row.toUser(roles)
    }

    override suspend fun findAuthByEmail(email: String): UserAuthRecord? = transaction {
        val row = UsersTable.selectAll().where { UsersTable.email eq email }
            .limit(1)
            .firstOrNull()
            ?: return@transaction null
        val passwordHash = row[UsersTable.passwordHash] ?: return@transaction null
        val roles = loadCurrentRoles(listOf(row[UsersTable.id].value))[row[UsersTable.id].value].orEmpty()
        UserAuthRecord(row.toUser(roles), passwordHash)
    }

    override suspend fun findOrCreateFromAuth(
        id: UserId,
        roles: List<UserRole>,
        email: String?,
        displayName: String?
    ): UserProfile = transaction {
        val existing = UsersTable.selectAll().where { UsersTable.id eq EntityID(UUID.fromString(id.value), UsersTable) }
            .limit(1)
            .firstOrNull()
        if (existing != null) {
            val currentRoles = loadCurrentRoles(listOf(existing[UsersTable.id].value))[existing[UsersTable.id].value].orEmpty()
            return@transaction existing.toUser(currentRoles)
        }
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val entityId = UsersTable.insertAndGetId {
            it[UsersTable.id] = UUID.fromString(id.value)
            it[UsersTable.email] = email
            it[UsersTable.displayName] = displayName
            it[UsersTable.status] = UserStatus.ACTIVE.dbValue()
            it[UsersTable.passwordHash] = null
            it[UsersTable.passwordUpdatedAt] = null
            it[UsersTable.createdAt] = offsetNow
            it[UsersTable.updatedAt] = offsetNow
        }
        if (roles.isNotEmpty()) {
            insertRoleBatch(entityId, entityId, offsetNow, roles)
        }
        insertAudit(
            auditId = UUID.randomUUID(),
            actorId = entityId,
            targetId = entityId,
            action = UserAuditAction.USER_CREATED
        )
        val profile = UserProfile(
            id = UserId(entityId.value.toString()),
            email = email,
            displayName = displayName,
            roles = roles,
            status = UserStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
        profile
    }

    override suspend fun listUsers(limit: Int, cursor: String?, includeDeleted: Boolean): UserListResult = transaction {
        val parsedCursor = cursor?.let { parseCursor(it) }
        val base = UsersTable.selectAll()
        if (!includeDeleted) {
            base.andWhere { UsersTable.status neq UserStatus.DELETED.dbValue() }
        }
        if (parsedCursor != null) {
            val cursorEntity = EntityID(parsedCursor.userId, UsersTable)
            base.andWhere {
                (UsersTable.createdAt greater parsedCursor.createdAt) or
                    (UsersTable.createdAt eq parsedCursor.createdAt and (UsersTable.id greater cursorEntity))
            }
        }
        val rows = base
            .orderBy(UsersTable.createdAt to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
            .limit(limit + 1)
            .toList()
        val hasNext = rows.size > limit
        val page = rows.take(limit)
        val userIds = page.map { it[UsersTable.id].value }
        val rolesByUser = loadCurrentRoles(userIds)
        val items = page.map { row ->
            val roles = rolesByUser[row[UsersTable.id].value].orEmpty()
            row.toUser(roles)
        }
        val nextCursor = if (hasNext) {
            val last = page.last()
            formatCursor(last[UsersTable.createdAt].toInstant().toKotlinInstant(), last[UsersTable.id].value)
        } else {
            null
        }
        UserListResult(items, nextCursor)
    }

    override suspend fun createUser(request: UserCreateRequest, actorId: UserId): UserProfile? = transaction {
        if (request.email != null) {
            val existing = UsersTable.selectAll().where { UsersTable.email eq request.email }
                .limit(1)
                .firstOrNull()
            if (existing != null) return@transaction null
        }
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val entityId = UsersTable.insertAndGetId {
            it[UsersTable.id] = UUID.randomUUID()
            it[UsersTable.email] = request.email
            it[UsersTable.displayName] = request.displayName
            it[UsersTable.status] = UserStatus.ACTIVE.dbValue()
            it[UsersTable.passwordHash] = null
            it[UsersTable.passwordUpdatedAt] = null
            it[UsersTable.createdAt] = offsetNow
            it[UsersTable.updatedAt] = offsetNow
        }
        val actorEntity = EntityID(UUID.fromString(actorId.value), UsersTable)
        if (request.roles.isNotEmpty()) {
            insertRoleBatch(entityId, actorEntity, offsetNow, request.roles)
        }
        insertAudit(
            auditId = UUID.randomUUID(),
            actorId = actorEntity,
            targetId = entityId,
            action = UserAuditAction.USER_CREATED
        )
        UserProfile(
            id = UserId(entityId.value.toString()),
            email = request.email,
            displayName = request.displayName,
            roles = request.roles,
            status = UserStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
    }

    override suspend fun createUserWithPassword(
        request: UserRegisterRequest,
        passwordHash: String
    ): UserProfile? = transaction {
        val existing = UsersTable.selectAll().where { UsersTable.email eq request.email }
            .limit(1)
            .firstOrNull()
        if (existing != null) return@transaction null
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val entityId = UsersTable.insertAndGetId {
            it[UsersTable.id] = UUID.randomUUID()
            it[UsersTable.email] = request.email
            it[UsersTable.displayName] = request.displayName
            it[UsersTable.status] = UserStatus.ACTIVE.dbValue()
            it[UsersTable.passwordHash] = passwordHash
            it[UsersTable.passwordUpdatedAt] = offsetNow
            it[UsersTable.createdAt] = offsetNow
            it[UsersTable.updatedAt] = offsetNow
        }
        val role = UserRole.READER
        insertRoleBatch(entityId, entityId, offsetNow, listOf(role))
        insertAudit(
            auditId = UUID.randomUUID(),
            actorId = entityId,
            targetId = entityId,
            action = UserAuditAction.USER_CREATED
        )
        UserProfile(
            id = UserId(entityId.value.toString()),
            email = request.email,
            displayName = request.displayName,
            roles = listOf(role),
            status = UserStatus.ACTIVE,
            createdAt = now,
            updatedAt = now
        )
    }

    override suspend fun setPassword(userId: UserId, passwordHash: String): UserProfile? = transaction {
        val userEntity = EntityID(UUID.fromString(userId.value), UsersTable)
        val row = UsersTable.selectAll().where { UsersTable.id eq userEntity }.limit(1).firstOrNull()
            ?: return@transaction null
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        UsersTable.update({ UsersTable.id eq userEntity }) {
            it[UsersTable.passwordHash] = passwordHash
            it[UsersTable.passwordUpdatedAt] = offsetNow
            it[UsersTable.updatedAt] = offsetNow
        }
        val roles = loadCurrentRoles(listOf(userEntity.value))[userEntity.value].orEmpty()
        row.copy(
            status = row[UsersTable.status].toUserStatus(),
            updatedAt = now,
            roles = roles
        )
    }

    override suspend fun replaceRoles(
        userId: UserId,
        roles: List<UserRole>,
        actorId: UserId,
        reason: String
    ): UserProfile? = transaction {
        val userEntity = EntityID(UUID.fromString(userId.value), UsersTable)
        val row = UsersTable.selectAll().where { UsersTable.id eq userEntity }.limit(1).firstOrNull()
            ?: return@transaction null
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        val actorEntity = EntityID(UUID.fromString(actorId.value), UsersTable)
        insertRoleBatch(userEntity, actorEntity, offsetNow, roles)
        insertAudit(
            auditId = UUID.randomUUID(),
            actorId = actorEntity,
            targetId = userEntity,
            action = UserAuditAction.ROLES_REPLACED,
            reason = reason
        )
        val updated = row.toUser(roles)
        updated
    }

    override suspend fun updateStatus(
        userId: UserId,
        status: UserStatus,
        actorId: UserId,
        reason: String
    ): UserProfile? = transaction {
        val userEntity = EntityID(UUID.fromString(userId.value), UsersTable)
        val row = UsersTable.selectAll().where { UsersTable.id eq userEntity }.limit(1).firstOrNull()
            ?: return@transaction null
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        UsersTable.update({ UsersTable.id eq userEntity }) {
            it[UsersTable.status] = status.dbValue()
            it[UsersTable.updatedAt] = offsetNow
        }
        val actorEntity = EntityID(UUID.fromString(actorId.value), UsersTable)
        insertAudit(
            auditId = UUID.randomUUID(),
            actorId = actorEntity,
            targetId = userEntity,
            action = UserAuditAction.STATUS_CHANGED,
            reason = reason
        )
        val roles = loadCurrentRoles(listOf(userEntity.value))[userEntity.value].orEmpty()
        row.copy(
            status = status,
            updatedAt = now,
            roles = roles
        )
    }

    override suspend fun deleteUser(userId: UserId, actorId: UserId, reason: String): UserProfile? = transaction {
        val userEntity = EntityID(UUID.fromString(userId.value), UsersTable)
        val row = UsersTable.selectAll().where { UsersTable.id eq userEntity }.limit(1).firstOrNull()
            ?: return@transaction null
        val now = Clock.System.now()
        val offsetNow = now.toJavaInstant().atOffset(ZoneOffset.UTC)
        UsersTable.update({ UsersTable.id eq userEntity }) {
            it[UsersTable.status] = UserStatus.DELETED.dbValue()
            it[UsersTable.updatedAt] = offsetNow
        }
        val actorEntity = EntityID(UUID.fromString(actorId.value), UsersTable)
        insertAudit(
            auditId = UUID.randomUUID(),
            actorId = actorEntity,
            targetId = userEntity,
            action = UserAuditAction.USER_DELETED,
            reason = reason
        )
        val roles = loadCurrentRoles(listOf(userEntity.value))[userEntity.value].orEmpty()
        row.copy(
            status = UserStatus.DELETED,
            updatedAt = now,
            roles = roles
        )
    }

    override suspend fun listAudits(userId: UserId, limit: Int, cursor: String?): UserAuditListResult = transaction {
        val parsedCursor = cursor?.let { parseCursor(it) }
        val targetEntity = EntityID(UUID.fromString(userId.value), UsersTable)
        val base = UserAuditsTable.selectAll().where { UserAuditsTable.targetUserId eq targetEntity }
        if (parsedCursor != null) {
            val cursorEntity = EntityID(parsedCursor.userId, UserAuditsTable)
            base.andWhere {
                (UserAuditsTable.createdAt less parsedCursor.createdAt) or
                    (UserAuditsTable.createdAt eq parsedCursor.createdAt and (UserAuditsTable.id less cursorEntity))
            }
        }
        val rows = base
            .orderBy(UserAuditsTable.createdAt to SortOrder.DESC, UserAuditsTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .toList()
        val hasNext = rows.size > limit
        val page = rows.take(limit)
        val items = page.map { it.toAuditEvent() }
        val nextCursor = if (hasNext) {
            val last = page.last()
            formatCursor(last[UserAuditsTable.createdAt].toInstant().toKotlinInstant(), last[UserAuditsTable.id].value)
        } else {
            null
        }
        UserAuditListResult(items, nextCursor)
    }

    private fun insertRoleBatch(
        userEntity: EntityID<UUID>,
        actorEntity: EntityID<UUID>,
        assignedAt: java.time.OffsetDateTime,
        roles: List<UserRole>
    ) {
        roles.distinct().forEach { role ->
            UserRolesTable.insert {
                it[userId] = userEntity
                it[UserRolesTable.role] = role.dbValue()
                it[assignedBy] = actorEntity
                it[UserRolesTable.assignedAt] = assignedAt
            }
        }
    }

    private fun insertAudit(
        auditId: UUID,
        actorId: EntityID<UUID>,
        targetId: EntityID<UUID>,
        action: UserAuditAction,
        reason: String? = null
    ) {
        UserAuditsTable.insert {
            it[UserAuditsTable.id] = auditId
            it[UserAuditsTable.actorId] = actorId
            it[UserAuditsTable.targetUserId] = targetId
            it[UserAuditsTable.action] = action.dbValue()
            it[UserAuditsTable.reason] = reason
            it[UserAuditsTable.createdAt] = Clock.System.now().toJavaInstant().atOffset(ZoneOffset.UTC)
        }
    }

    private fun loadCurrentRoles(userIds: List<UUID>): Map<UUID, List<UserRole>> {
        if (userIds.isEmpty()) return emptyMap()
        val rows = UserRolesTable
            .selectAll().where { UserRolesTable.userId inList userIds.map { EntityID(it, UsersTable) } }
            .orderBy(UserRolesTable.userId to SortOrder.ASC, UserRolesTable.assignedAt to SortOrder.DESC)
        val rolesByUser = mutableMapOf<UUID, MutableList<UserRole>>()
        val latestByUser = mutableMapOf<UUID, java.time.OffsetDateTime>()
        rows.forEach { row ->
            val userId = row[UserRolesTable.userId].value
            val assignedAt = row[UserRolesTable.assignedAt]
            val currentLatest = latestByUser[userId]
            if (currentLatest == null) {
                latestByUser[userId] = assignedAt
                rolesByUser[userId] = mutableListOf(row[UserRolesTable.role].toUserRole())
            } else if (assignedAt == currentLatest) {
                rolesByUser.getValue(userId).add(row[UserRolesTable.role].toUserRole())
            }
        }
        return rolesByUser
    }

    private fun ResultRow.toUser(roles: List<UserRole>): UserProfile = UserProfile(
        id = UserId(this[UsersTable.id].value.toString()),
        email = this[UsersTable.email],
        displayName = this[UsersTable.displayName],
        roles = roles,
        status = this[UsersTable.status].toUserStatus(),
        createdAt = this[UsersTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = this[UsersTable.updatedAt].toInstant().toKotlinInstant()
    )

    private fun ResultRow.copy(
        status: UserStatus,
        updatedAt: Instant,
        roles: List<UserRole>
    ): UserProfile = UserProfile(
        id = UserId(this[UsersTable.id].value.toString()),
        email = this[UsersTable.email],
        displayName = this[UsersTable.displayName],
        roles = roles,
        status = status,
        createdAt = this[UsersTable.createdAt].toInstant().toKotlinInstant(),
        updatedAt = updatedAt
    )

    private fun ResultRow.toAuditEvent(): UserAuditEvent = UserAuditEvent(
        auditId = this[UserAuditsTable.id].value.toString(),
        actorId = this[UserAuditsTable.actorId].value.toString(),
        targetUserId = this[UserAuditsTable.targetUserId].value.toString(),
        action = this[UserAuditsTable.action].toUserAuditAction(),
        reason = this[UserAuditsTable.reason],
        createdAt = this[UserAuditsTable.createdAt].toInstant().toKotlinInstant()
    )

    private fun parseCursor(cursor: String): ParsedCursor {
        val parts = cursor.split('|', limit = 2)
        require(parts.size == 2) { "invalid cursor" }
        val instant = Instant.parse(parts[0])
        val id = UUID.fromString(parts[1])
        return ParsedCursor(instant.toJavaInstant().atOffset(ZoneOffset.UTC), id)
    }

    private fun formatCursor(createdAt: Instant, userId: UUID): String =
        "$createdAt|${userId}"

    private data class ParsedCursor(val createdAt: java.time.OffsetDateTime, val userId: UUID)
}

private fun String.toUserRole(): UserRole =
    UserRole.fromString(this) ?: error("Unknown role $this")

private fun String.toUserAuditAction(): UserAuditAction = when (this) {
    "user.created" -> UserAuditAction.USER_CREATED
    "roles.replaced" -> UserAuditAction.ROLES_REPLACED
    "status.changed" -> UserAuditAction.STATUS_CHANGED
    "user.deleted" -> UserAuditAction.USER_DELETED
    else -> error("Unknown audit action $this")
}

private fun UserRole.dbValue(): String = when (this) {
    UserRole.READER -> "reader"
    UserRole.EDITOR -> "editor"
    UserRole.REVIEWER -> "reviewer"
    UserRole.ADMIN -> "admin"
}

private fun UserAuditAction.dbValue(): String = when (this) {
    UserAuditAction.USER_CREATED -> "user.created"
    UserAuditAction.ROLES_REPLACED -> "roles.replaced"
    UserAuditAction.STATUS_CHANGED -> "status.changed"
    UserAuditAction.USER_DELETED -> "user.deleted"
}

private fun String.toUserStatus(): UserStatus = when (this) {
    "active" -> UserStatus.ACTIVE
    "disabled" -> UserStatus.DISABLED
    "deleted" -> UserStatus.DELETED
    else -> error("Unknown status $this")
}

private fun UserStatus.dbValue(): String = when (this) {
    UserStatus.ACTIVE -> "active"
    UserStatus.DISABLED -> "disabled"
    UserStatus.DELETED -> "deleted"
}
