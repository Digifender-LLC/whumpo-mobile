package com.whumpo.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
enum class CoreInvitationStatus { pending, accepted, revoked, expired, invalidated }

@Serializable
data class CorePersonInvitation(
    val invitationId: String,
    val orgId: String,
    val issuerUserId: String,
    val role: CoreRole,
    val version: Int,
    val createdAt: String,
    val expiresAt: String,
    val status: CoreInvitationStatus,
    val acceptedAt: String? = null,
    val acceptedUserId: String? = null,
    val revokedAt: String? = null,
)

@Serializable
internal class CoreInvitationPage(val items: List<CorePersonInvitation>, val nextCursor: String?)

@Serializable
internal class CoreInvitationIssue(val invitation: CorePersonInvitation, val invitationCode: String? = null)

internal val newPersonInvitationCode = Regex("wi2[.][A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")

internal fun isInvitationRole(role: CoreRole) = role == CoreRole.operator || role == CoreRole.viewer

internal inline fun <reified T> decodeInvitation(raw: String): T = try {
    val value = Json.parseToJsonElement(raw)
    verifyInvitationJson(value)
    Json.decodeFromJsonElement(value)
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}

internal fun verifyInvitationJson(value: kotlinx.serialization.json.JsonElement) {
    when (value) {
        is kotlinx.serialization.json.JsonArray -> value.forEach(::verifyInvitationJson)
        is JsonObject -> value.forEach { (key, field) ->
            when (key) {
                "version" -> require(field is JsonPrimitive && !field.isString)
                "acceptedAt", "acceptedUserId", "revokedAt", "invitationCode" ->
                    require(field is JsonPrimitive && field != JsonNull && field.isString)
                else -> verifyInvitationJson(field)
            }
        }
        else -> Unit
    }
}
