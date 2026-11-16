package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class SecurityProfile { @SerialName("passkeys") Passkeys, @SerialName("password_totp") PasswordTotp }

@Serializable
enum class SecurityPurpose { @SerialName("account") Account, @SerialName("enrollment") Enrollment, @SerialName("recovery") Recovery }

@Serializable
enum class SecurityPhase { @SerialName("legacy") Legacy, @SerialName("enrolling") Enrolling, @SerialName("ready") Ready, @SerialName("recovering") Recovering }

@Serializable
enum class SecurityAccess { @SerialName("restricted") Restricted, @SerialName("available") Available, @SerialName("none") None }

@Serializable
enum class SecurityMethodKind { @SerialName("passkey") Passkey, @SerialName("password") Password, @SerialName("totp") Totp }

@Serializable
enum class SecurityMethodStatus { @SerialName("active") Active, @SerialName("staged") Staged }

@Serializable
enum class SecurityProof { @SerialName("passkey_uv") Passkey, @SerialName("password_totp") PasswordTotp }

@Serializable
enum class RecoveryKind { @SerialName("none") None, @SerialName("kit") Kit, @SerialName("codes") Codes }

@Serializable
data class SecurityMethod(val id: String, val kind: SecurityMethodKind, val state: SecurityMethodStatus, val verified: Boolean, val version: Long, val label: String?, val createdAt: Long)

@Serializable
data class RecoveryOffer(val id: String, val kind: RecoveryKind, val generation: Long, val saved: Boolean, val expiresAt: Long)

@Serializable
data class SecurityRecovery(val kind: RecoveryKind, val generation: Long, val remainingCodes: Int?, val offer: RecoveryOffer?)

@Serializable
data class AccountSecurityState(
    val version: Int,
    val accountId: String?,
    val candidateId: String?,
    val purpose: SecurityPurpose,
    val phase: SecurityPhase,
    val generation: Long,
    val inventoryRevision: Long,
    val attemptId: String?,
    val attemptRevision: Long?,
    val intentRevision: Long?,
    val methods: List<SecurityMethod>,
    val recovery: SecurityRecovery,
    val approvedMethod: SecurityProof?,
    val freshUntil: Long?,
    val expiresAt: Long,
    val workspaceAccess: SecurityAccess,
    val setupProfile: SecurityProfile?,
)

@Serializable
internal class SecurityAuthority(val kind: String, val token: String, val purpose: SecurityPurpose, val expiresAt: Long)

@Serializable
internal class SecurityGrant(val authority: SecurityAuthority, val state: AccountSecurityState)

@Serializable
internal class SecurityStart(val authority: SecurityAuthority?, val state: AccountSecurityState?)

@Serializable
internal class SecurityChallenge(val challengeId: String, val options: JsonObject)

@Serializable
internal class SecurityFinalChallenge(val challengeId: String, val method: SecurityProof, val options: JsonObject?)

@Serializable
internal class SecurityPasswordChallenge(val challengeId: String, val expiresAt: Long)

@Serializable
internal class SecurityReceipt(val factorId: String? = null, val offerId: String? = null, val recoveryGeneration: Long)

@Serializable
internal class SecurityTotpPreparation(val receipt: SecurityReceipt, val secret: String?, val otpauthUri: String?)

@Serializable
internal class SecurityRecoveryPreparation(val receipt: SecurityReceipt, val document: String?)

@Serializable
data class SecurityInvitationPreview(
    val invitationId: String,
    val orgId: String,
    val organizationName: String?,
    val role: CoreRole,
    val expiresAt: String,
)

@Serializable
internal data class SecurityInvitationAcceptance(
    val invitationId: String,
    val orgId: String,
    val membership: SecurityInvitationMembership,
    val acceptedAt: String,
)

@Serializable
internal data class SecurityInvitationMembership(val userId: String, val role: CoreRole, val version: Int)

@Serializable
data class SecurityMembership(val orgId: String, val role: CoreRole)

@Serializable
data class SecurityMembershipPage(val items: List<SecurityMembership>, val nextCursor: String?)

internal val accountGrantBearer = Regex("ag1[.][A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
internal val securityOpaqueId = Regex("[A-Za-z0-9._:-]{1,128}")
internal val personInvitationCode = Regex("wi[12][.][A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")

sealed interface SecurityAdmission {
    class Bootstrap(val token: String) : SecurityAdmission
    class PersonInvitation(val code: String, val invitationId: String, val orgId: String, val role: CoreRole) : SecurityAdmission
}

internal fun SecurityAdmission.wire(): JsonObject = buildJsonObject {
    when (val admission = this@wire) {
        is SecurityAdmission.Bootstrap -> {
            require(coreBearer.matches(admission.token))
            put("kind", "bootstrap")
            put("token", admission.token)
        }
        is SecurityAdmission.PersonInvitation -> {
            require(personInvitationCode.matches(admission.code))
            put("kind", "person_invitation")
            put("code", admission.code)
            put("consent", admission.consent())
        }
    }
}

internal fun SecurityAdmission.PersonInvitation.consent(): JsonObject = buildJsonObject {
    require(coreIdentifier.matches(invitationId) && coreIdentifier.matches(orgId))
    require(role == CoreRole.operator || role == CoreRole.viewer)
    put("invitationId", invitationId)
    put("orgId", orgId)
    put("role", role.name)
}

internal val accountSecurityJson = Json { ignoreUnknownKeys = true }
