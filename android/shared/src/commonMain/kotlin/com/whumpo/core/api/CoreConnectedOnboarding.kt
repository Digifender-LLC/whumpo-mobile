package com.whumpo.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CoreOrganizationReadiness(val orgId: String, val ready: Boolean, val projectId: String? = null)

@Serializable
enum class CoreOnboardingStage { reserved, credential_registered, verified, ready }

@Serializable
enum class CoreOnboardingProfileStatus { draft, skipped }

@Serializable
enum class CoreBriefingCadence { daily, weekly, none }

@Serializable
data class CoreOnboardingProfile(
    val version: Long,
    val status: CoreOnboardingProfileStatus,
    val goal: String?,
    val ownerName: String?,
    val organizationLabel: String?,
    val timeZone: String?,
    val briefingCadence: CoreBriefingCadence?,
)

@Serializable
internal data class CoreOnboardingSummary(
    val orgId: String,
    val stage: CoreOnboardingStage,
    val profile: CoreOnboardingProfile,
    val workspaceId: String? = null,
    val projectId: String? = null,
    val agentId: String? = null,
    val conversationId: String? = null,
)

private val onboardingNullableFields = setOf("goal", "ownerName", "organizationLabel", "timeZone", "briefingCadence")
private val onboardingInteger = Regex("[1-9][0-9]{0,15}")

internal inline fun <reified T> decodeConnectedOnboarding(body: String): T = try {
    verifyOnboardingTypes(Json.parseToJsonElement(body))
    Json.decodeFromString(body)
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}

internal fun verifyOnboardingTypes(node: JsonElement, depth: Int = 0) {
    if (depth > 2 || node !is JsonObject) throw CoreProtocolException()
    node.forEach { (key, value) ->
        val valid = when {
            value == JsonNull -> key in onboardingNullableFields
            value is JsonArray -> false
            value is JsonObject -> key == "profile" && depth == 0
            value is JsonPrimitive -> when (key) {
                "version" -> !value.isString && onboardingInteger.matches(value.content)
                "ready" -> value == JsonPrimitive(true) || value == JsonPrimitive(false)
                else -> value.isString
            }
            else -> false
        }
        if (!valid) throw CoreProtocolException()
        if (value is JsonObject) verifyOnboardingTypes(value, depth + 1)
    }
}
