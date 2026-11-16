package com.whumpo.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class CoreRole { owner, admin, operator, viewer }

@Serializable
data class CoreSession(val sessionId: String, val orgId: String, val userId: String, val role: CoreRole, val expiresAt: Long)

@Serializable
internal class CoreSessionToken(val token: String, val expiresAt: Long)

@Serializable
internal data class CoreAuthenticationOptions(val challengeId: String, val options: JsonObject)

@Serializable
internal data class CoreAuthenticationVerify(val challengeId: String, val response: JsonObject)

@Serializable
internal data class CoreSwitch(val orgId: String)

@Serializable
data class CoreMembership(val orgId: String, val role: CoreRole, val active: Boolean)

@Serializable
data class CoreMembershipPage(val items: List<CoreMembership>, val nextCursor: String? = null)

@Serializable
data class CoreOrganizationProfile(val orgId: String, val displayName: String?, val version: Long)

@Serializable
internal data class CoreProfilePatch(val displayName: String)

@Serializable
enum class CoreEntitlementStatus { unconfigured, scheduled, active, expired, suspended }

@Serializable
enum class CoreMembershipMode { single_user, business_team }

@Serializable
data class CoreOrganizationEntitlements(
    val orgId: String,
    val status: CoreEntitlementStatus,
    val membershipMode: CoreMembershipMode?,
    val humanSeatLimit: Long?,
    val activeHumanSeats: Long,
    val reservedHumanSeats: Long,
    val availableHumanSeats: Long,
    val teamAdmissionAvailable: Boolean,
    val siteBuildAvailable: Boolean,
    val revision: Long?,
    val effectiveFrom: Long?,
    val expiresAt: Long?,
)

open class CoreHttpException(val status: Int) : Exception("Core request failed (HTTP $status)")
class CoreProtocolException : Exception("Core returned an invalid session or organization response")

internal val coreIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
internal val coreBearer = Regex("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
