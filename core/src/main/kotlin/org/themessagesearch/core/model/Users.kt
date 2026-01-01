package org.themessagesearch.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class UserId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "Invalid UUID: $value" } }
    companion object { fun random() = UserId(UUID.randomUUID().toString()) }
    override fun toString(): String = value
}

@Serializable
enum class UserRole {
    @SerialName("reader")
    READER,
    @SerialName("editor")
    EDITOR,
    @SerialName("reviewer")
    REVIEWER,
    @SerialName("admin")
    ADMIN;

    companion object {
        fun fromString(value: String): UserRole? = when (value.lowercase()) {
            "reader" -> READER
            "editor" -> EDITOR
            "reviewer" -> REVIEWER
            "admin" -> ADMIN
            else -> null
        }
    }
}

@Serializable
enum class UserStatus {
    @SerialName("active")
    ACTIVE,
    @SerialName("disabled")
    DISABLED
}

@Serializable
data class UserProfile(
    val id: UserId,
    val email: String?,
    val displayName: String?,
    val roles: List<UserRole>,
    val status: UserStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Serializable
data class UserProfileResponse(
    val id: String,
    val email: String?,
    val displayName: String?,
    val roles: List<UserRole>,
    val status: UserStatus
)

@Serializable
data class UserCreateRequest(
    val email: String? = null,
    val displayName: String? = null,
    val roles: List<UserRole> = emptyList()
)

@Serializable
data class UserUpdateRolesRequest(val roles: List<UserRole>, val reason: String)

@Serializable
data class UserUpdateStatusRequest(val status: UserStatus, val reason: String)

@Serializable
data class UserListResponse(
    val items: List<UserProfileResponse>,
    val nextCursor: String? = null
)

data class UserListResult(
    val items: List<UserProfile>,
    val nextCursor: String? = null
)

@Serializable
enum class UserAuditAction {
    @SerialName("user.created")
    USER_CREATED,
    @SerialName("roles.replaced")
    ROLES_REPLACED,
    @SerialName("status.changed")
    STATUS_CHANGED
}

@Serializable
data class UserAuditEvent(
    val auditId: String,
    val actorId: String,
    val targetUserId: String,
    val action: UserAuditAction,
    val reason: String? = null,
    val createdAt: Instant
)

@Serializable
data class UserAuditListResponse(
    val items: List<UserAuditEvent>,
    val nextCursor: String? = null
)

data class UserAuditListResult(
    val items: List<UserAuditEvent>,
    val nextCursor: String? = null
)

fun UserProfile.toResponse(): UserProfileResponse = UserProfileResponse(
    id = id.value,
    email = email,
    displayName = displayName,
    roles = roles,
    status = status
)
