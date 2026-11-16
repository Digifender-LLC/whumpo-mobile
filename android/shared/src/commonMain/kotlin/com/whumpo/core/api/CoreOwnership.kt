package com.whumpo.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class CoreOwnershipStatus { designated, undesignated }

@Serializable
enum class CoreOwnershipProvenance {
    onboarding, local_bootstrap, backfill_onboarding_creator, backfill_sole_owner, owner_claim, transfer,
}

@Serializable
enum class CoreTransferStatus { pending, accepted, cancelled, expired, invalidated }

@Serializable
@ConsistentCopyVisibility
data class CoreOwnershipDesignation internal constructor(
    val orgId: String,
    val status: CoreOwnershipStatus,
    val primaryOwnerUserId: String?,
    val version: Long?,
    val provenance: CoreOwnershipProvenance?,
    val pendingTransfer: CoreOwnershipTransfer?,
)

@Serializable
@ConsistentCopyVisibility
data class CoreOwnershipTransfer internal constructor(
    val transferId: String,
    val orgId: String,
    val sourceUserId: String,
    val targetUserId: String,
    val designationVersion: Long,
    val targetMembershipVersion: Long,
    val version: Long,
    val status: CoreTransferStatus,
    val createdAt: String,
    val expiresAt: String,
    val acceptedAt: String? = null,
    val cancelledAt: String? = null,
)

@Serializable
internal data class CoreOwnershipMember(
    val userId: String,
    val role: CoreRole,
    val version: Long,
    val revokedAt: String? = null,
)

@Serializable
internal data class CoreOwnershipMemberPage(
    val items: List<CoreOwnershipMember>,
    val nextCursor: String? = null,
)

internal sealed class OwnershipRoute(val path: String, val write: Boolean = false) {
    data object Designation : OwnershipRoute("/v1/organization/ownership")
    data object Claim : OwnershipRoute("/v1/organization/ownership/claim", true)
    data object Offer : OwnershipRoute("/v1/organization/ownership/transfers", true)
    data object Members : OwnershipRoute("/v1/memberships")
    class Transfer(id: String) : OwnershipRoute(transferPath(id))
    class Accept(id: String) : OwnershipRoute(transferPath(id) + "/accept", true)
    class Cancel(id: String) : OwnershipRoute(transferPath(id) + "/cancel", true)

    private companion object {
        fun transferPath(id: String): String {
            require(coreIdentifier.matches(id))
            return "/v1/organization/ownership/transfers/$id"
        }
    }
}

internal inline fun <reified T> decodeOwnership(body: String): T = try {
    val value = Json.parseToJsonElement(body)
    verifyOwnershipFieldTypes(value)
    Json.decodeFromString<T>(body)
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}

private val ownershipVersionFields = setOf("version", "designationVersion", "targetMembershipVersion")
private val ownershipOptionalFields = setOf("nextCursor", "revokedAt", "acceptedAt", "cancelledAt")
private val ownershipInteger = Regex("[1-9][0-9]{0,15}")

internal fun verifyOwnershipFieldTypes(value: JsonElement, depth: Int = 0) {
    if (depth > 3) throw CoreProtocolException()
    when (value) {
        is JsonObject -> value.forEach { (key, field) ->
            when {
                field == JsonNull && key in ownershipOptionalFields -> throw CoreProtocolException()
                field == JsonNull -> Unit
                key in ownershipVersionFields -> {
                    val number = field as? JsonPrimitive ?: throw CoreProtocolException()
                    if (number.isString || !ownershipInteger.matches(number.content)) throw CoreProtocolException()
                }
                field is JsonPrimitive && !field.isString -> throw CoreProtocolException()
                else -> verifyOwnershipFieldTypes(field, depth + 1)
            }
        }
        is JsonArray -> value.forEach { verifyOwnershipFieldTypes(it, depth + 1) }
        is JsonPrimitive -> Unit
    }
}
