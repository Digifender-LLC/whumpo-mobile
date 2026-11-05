package com.whumpo.core.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Wire models for the frozen /api/mobile/v1 contract (docs/mobile-api-v1.md). Field names are the wire names.
// Responses are decoded strictly: an unknown or missing key fails as InvalidResponse, as the contract's closed schemas do.
// Dates stay ISO-8601 strings and IDs stay lowercase UUID strings; this module does no calendar or UUID parsing.

/** Every success body carries `"version": 1`. */
interface Versioned {
    val version: Int
}

const val CONTRACT_VERSION: Int = 1

/** Control capabilities a build declares in the `Whumpo-Client` header. */
object ControlCapability {
    const val SPECIFICATION_APPLY = "specification.apply"
    const val SITE_CONFIRM = "site.confirm"
    const val APPLICATION_CONFIRM = "application.confirm"
    const val APP_RESTORE = "app.restore"
}

// ---- Client header -------------------------------------------------------------------------------------------------

@Serializable
data class InteractionCapabilities(
    val versions: List<Int>,
    val fieldTypes: List<String>,
    val bindings: List<String>,
)

/** The `Whumpo-Client` header value. It declares capability and grants nothing. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientInfo(
    val build: Int,
    val controls: List<String>,
    val interaction: InteractionCapabilities,
) {
    @EncodeDefault
    val contract: Int = CONTRACT_VERSION

    @EncodeDefault
    val platform: String = "android"

    companion object {
        val NATIVE_FIELD_TYPES = listOf("text", "long-text", "choice", "multi-choice", "boolean", "number", "date", "datetime", "resource")
        val NATIVE_BINDINGS = listOf(
            "answer.value@1", "interaction.answers@1", "owned.projects@1",
            "site.content@1", "agent.task-continue@1", "site.content-prepare@1",
        )
        val ALL_CONTROLS = listOf(
            ControlCapability.SPECIFICATION_APPLY, ControlCapability.SITE_CONFIRM,
            ControlCapability.APPLICATION_CONFIRM, ControlCapability.APP_RESTORE,
        )

        /** What this build implements. Declare a control only once its UI exists: the server enables only what is declared. */
        fun current(build: Int, controls: List<String> = ALL_CONTROLS) =
            ClientInfo(build, controls, InteractionCapabilities(listOf(1), NATIVE_FIELD_TYPES, NATIVE_BINDINGS))
    }
}

// ---- Review controls (exact ChatControl shapes; nothing else is admitted) -----------------------------------------------

@Serializable(with = ReviewControlSerializer::class)
sealed interface ReviewControl {
    val reviewId: String
    val capability: String
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SpecificationApply(
    override val reviewId: String,
    val proposalDigest: String,
    val expectedRevision: Int,
) : ReviewControl {
    @EncodeDefault
    val type: String = "specificationApply"
    override val capability: String get() = ControlCapability.SPECIFICATION_APPLY
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfirmSite(override val reviewId: String) : ReviewControl {
    @EncodeDefault
    val type: String = "confirm"

    @EncodeDefault
    val kind: String = "site"
    override val capability: String get() = ControlCapability.SITE_CONFIRM
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfirmApplication(override val reviewId: String) : ReviewControl {
    @EncodeDefault
    val type: String = "confirm"

    @EncodeDefault
    val kind: String = "application"
    override val capability: String get() = ControlCapability.APPLICATION_CONFIRM
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppRestore(val projectId: String, override val reviewId: String) : ReviewControl {
    @EncodeDefault
    val type: String = "appRestore"

    @EncodeDefault
    val confirmation: String = "RESTORE"
    override val capability: String get() = ControlCapability.APP_RESTORE
}

object ReviewControlSerializer : JsonContentPolymorphicSerializer<ReviewControl>(ReviewControl::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out ReviewControl> {
        val o = element.jsonObject
        return when (o["type"]?.jsonPrimitive?.content) {
            "specificationApply" -> SpecificationApply.serializer()
            "appRestore" -> AppRestore.serializer()
            "confirm" -> when (o["kind"]?.jsonPrimitive?.content) {
                "site" -> ConfirmSite.serializer()
                "application" -> ConfirmApplication.serializer()
                else -> throw IllegalArgumentException("unsupported confirm kind")
            }
            else -> throw IllegalArgumentException("unsupported control type")
        }
    }
}

// ---- Reviews, Chat ---------------------------------------------------------------------------------------------------

@Serializable data class ProjectRef(val id: String, val name: String)

@Serializable data class Disclosure(val title: String, val lines: List<String>)

/** A server-issued preview path. Only the allowlisted shapes are accepted; the app composes the URL with its constant host. */
@Serializable
data class PreviewRef(val path: String) {
    init {
        require(PREVIEW_ALLOWED.matches(path)) { "preview path outside the allowlist" }
    }
}

private const val PREVIEW_ID = "[0-9a-f-]{36}"
private val PREVIEW_ALLOWED =
    Regex("/api/(?:site-versions/$PREVIEW_ID/preview|specification-reviews/$PREVIEW_ID/(?:preview|diff)|publication-reviews/$PREVIEW_ID/diff)")

/** What the app may offer for a review: [enabled] only when the server issued a control this build declared. */
data class ReviewPresentation(val enabled: Boolean, val reason: String?)

@Serializable
data class Review(
    /** `specification`, `site`, `application` or `app-restore`. Kept a string: an unknown kind must reach the app as update-required, not a crash. */
    val kind: String,
    val reviewId: String,
    val project: ProjectRef?,
    /** `pending`, `applied`, `expired`, `superseded` or `unavailable`. */
    val state: String,
    val expiresAt: String,
    val canApprove: Boolean,
    val control: ReviewControl?,
    val requiresFreshPasskey: Boolean,
    val disclosure: Disclosure,
    val preview: PreviewRef?,
    /** `client_update_required`, `expired`, `stale` or `not_current`. */
    val unavailableReason: String?,
) {
    init {
        // The same invariants the contract checks offline: a bad server reply never reaches the UI as an approvable review.
        if (canApprove) {
            require(state == "pending" && unavailableReason == null && control != null && control.reviewId == reviewId) { "invalid approvable review" }
            require(
                when (kind) {
                    "specification" -> control is SpecificationApply
                    "site" -> control is ConfirmSite
                    "application" -> control is ConfirmApplication
                    "app-restore" -> control is AppRestore
                    else -> false
                },
            ) { "control does not match review kind" }
            require(requiresFreshPasskey == (kind == "specification")) { "freshness requirement does not match review kind" }
        } else {
            require(control == null) { "control on a review that cannot be approved" }
        }
    }

    /**
     * Server metadata alone grants approval, and only for a capability this build declared.
     * Text, push arrival and unknown kinds never enable anything.
     */
    fun present(client: ClientInfo): ReviewPresentation = when {
        !canApprove || control == null -> ReviewPresentation(false, unavailableReason ?: state)
        control.capability in client.controls -> ReviewPresentation(true, null)
        else -> ReviewPresentation(false, "client_update_required")
    }
}

@Serializable
data class InteractionRef(val interactionId: String, val state: String)

@Serializable
data class ChatTurn(
    val id: String,
    val revision: Int,
    /** `pending`, `completed`, `failed` or `uncertain`; show honestly and resolve by reading. */
    val status: String,
    /** `guided` or `ai`. */
    val mode: String,
    val createdAt: String,
    val userText: String,
    val assistantText: String?,
    val review: Review?,
    val interaction: InteractionRef?,
)

// ---- Request / response pairs --------------------------------------------------------------------------------------------

@Serializable data class VersionOnly(val version: Int = CONTRACT_VERSION)

@Serializable
data class ConfigResponse(
    override val version: Int,
    val contract: Int,
    val minimumBuild: Int,
    val rpId: String,
    val browserReturn: String,
) : Versioned

@Serializable
data class SessionTokenInfo(val token: String, val expiresAt: String, val freshUntil: String?)

@Serializable
data class SessionTimes(val expiresAt: String, val freshUntil: String?)

@Serializable
data class ReauthTimes(val expiresAt: String, val freshUntil: String)

/** WebAuthn options and credentials are opaque JSON passed to and from Credential Manager (WP5). */
@Serializable
data class PasskeyOptionsResponse(
    override val version: Int,
    val challengeId: String,
    val expiresAt: String,
    val options: JsonObject,
) : Versioned

@Serializable
data class PasskeyVerifyRequest(val challengeId: String, val credential: JsonObject, val version: Int = CONTRACT_VERSION)

@Serializable
data class SessionResponse(override val version: Int, val session: SessionTokenInfo) : Versioned

@Serializable
data class ReauthResponse(override val version: Int, val session: ReauthTimes) : Versioned

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HandoffStartRequest(
    /** `signin`, `signup` or `recovery`. */
    val purpose: String,
    val state: String,
    val codeChallenge: String,
    val redirectUri: String,
    val version: Int = CONTRACT_VERSION,
) {
    @EncodeDefault
    val codeChallengeMethod: String = "S256"
}

@Serializable
data class HandoffStartResponse(
    override val version: Int,
    val handoffId: String,
    val browserPath: String,
    val expiresAt: String,
) : Versioned

@Serializable
data class HandoffExchangeRequest(
    val handoffId: String,
    val code: String,
    val state: String,
    val codeVerifier: String,
    val version: Int = CONTRACT_VERSION,
)

@Serializable data class AccountInfo(val label: String)

@Serializable
data class SessionReadResponse(override val version: Int, val session: SessionTimes, val account: AccountInfo) : Versioned

@Serializable data class LogoutResponse(override val version: Int, val signedOut: Boolean) : Versioned

@Serializable data class OnboardingState(val complete: Boolean, val browserPath: String?)

@Serializable data class ChatState(val revision: Int, val mode: String)

@Serializable
data class BootstrapResponse(
    override val version: Int,
    val onboarding: OnboardingState,
    val chat: ChatState?,
    val projectCount: Int,
    val pendingReviewCount: Int,
) : Versioned

@Serializable
data class ChatPage(
    override val version: Int,
    val revision: Int,
    val mode: String,
    val items: List<ChatTurn>,
    val nextCursor: String?,
) : Versioned

@Serializable data class ChatTurnResponse(override val version: Int, val turn: ChatTurn) : Versioned

@Serializable
data class SendMessageRequest(val requestId: String, val expectedRevision: Int, val content: String, val version: Int = CONTRACT_VERSION)

@Serializable
data class ConfirmReviewRequest(
    val requestId: String,
    val expectedRevision: Int,
    val control: ReviewControl,
    val version: Int = CONTRACT_VERSION,
)

/** Acceptance, not downstream success: `turnStatus` may be `pending` or `uncertain`. */
@Serializable
data class WriteAccepted(
    override val version: Int,
    val requestId: String,
    val accepted: Boolean,
    val revision: Int,
    val turnStatus: String,
) : Versioned

@Serializable
data class ReviewList(override val version: Int, val items: List<Review>, val denied: Int, val unavailable: Int) : Versioned

// ---- Interactions (WH-105 payloads; definition bodies are passed through, the fixtures freeze the envelopes) ----------------

@Serializable
data class InteractionListRequest(val projectId: String?, val limit: Int, val before: JsonElement?, val version: Int = CONTRACT_VERSION)

@Serializable
data class InteractionReadRequest(val interactionId: String, val client: InteractionCapabilities, val version: Int = CONTRACT_VERSION)

@Serializable
data class InteractionResourcesRequest(
    val interactionId: String,
    val definitionDigest: String,
    val fieldId: String,
    val limit: Int,
    val before: JsonElement?,
    val version: Int = CONTRACT_VERSION,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InteractionMutateRequest(
    val requestId: String,
    val interactionId: String,
    val definitionDigest: String,
    val expectedAnswerRevision: Int,
    /** `save-draft`, `submit` or `cancel`. `cancel` carries no answers; the others require them. */
    val operation: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val answers: JsonObject? = null,
    val version: Int = CONTRACT_VERSION,
)

@Serializable
data class InteractionReceiptRequest(val requestId: String, val version: Int = CONTRACT_VERSION)

@Serializable
data class InteractionReceipt(
    override val version: Int,
    val requestId: String,
    val interactionId: String,
    val operation: String,
    val answerRevision: Int,
) : Versioned

@Serializable
data class SubmissionRequest(val submissionId: String, val version: Int = CONTRACT_VERSION)

@Serializable
data class ContinueRequest(val submissionId: String, val expectedChatRevision: Int, val version: Int = CONTRACT_VERSION)

@Serializable
data class Continuation(
    val id: String,
    /** `pending`, `failed`, `unknown`, and the other WH-106 states; resolve `unknown` with reconcile. */
    val state: String,
    val resultTurnId: String?,
    val reason: String?,
)

@Serializable
data class ContinuationResponse(override val version: Int, val submissionId: String, val continuation: Continuation) : Versioned

// ---- Projects, versions, activity ----------------------------------------------------------------------------------------

@Serializable
data class Project(
    val id: String,
    val name: String,
    val purpose: String,
    val status: String,
    val siteLive: Boolean,
    val backendLive: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable data class ProjectPage(override val version: Int, val items: List<Project>, val nextCursor: String?) : Versioned

@Serializable data class ProjectResponse(override val version: Int, val project: Project) : Versioned

@Serializable
data class SiteVersion(val id: String, val componentId: String, val digest: String, val createdAt: String, val preview: PreviewRef)

@Serializable data class VersionList(override val version: Int, val items: List<SiteVersion>) : Versioned

@Serializable
data class ActivityItem(val id: String, val projectId: String?, val action: String, val summary: String, val createdAt: String)

@Serializable data class ActivityPage(override val version: Int, val items: List<ActivityItem>, val nextCursor: String?) : Versioned

// ---- Photos --------------------------------------------------------------------------------------------------------------

@Serializable
data class Photo(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val size: Int,
    /** `pending` or `ready`. */
    val status: String,
    /** `pending`, `approved` or `blocked`. Only `ready` with `approved` is usable media. */
    val safetyStatus: String,
) {
    val usable: Boolean get() = status == "ready" && safetyStatus == "approved"
}

@Serializable data class PhotoList(override val version: Int, val items: List<Photo>) : Versioned

@Serializable data class PhotoResponse(override val version: Int, val photo: Photo) : Versioned

// ---- Push ------------------------------------------------------------------------------------------------------------------

@Serializable
data class DeviceRegisterRequest(val fcmRegistrationToken: String, val notificationsEnabled: Boolean, val version: Int = CONTRACT_VERSION)

@Serializable data class DevicePreferences(val notificationsEnabled: Boolean)

@Serializable
data class DeviceResponse(override val version: Int, val deviceId: String, val preferences: DevicePreferences) : Versioned

@Serializable data class DeviceRemoved(override val version: Int, val removed: Boolean) : Versioned

@Serializable data class EventDestination(val type: String, val id: String)

@Serializable
data class EventInfo(
    val id: String,
    /** `review_pending`, `operation_completed` or `attention_required`. */
    val kind: String,
    val destination: EventDestination?,
    /** `current` or `unavailable` (then `destination` is null). */
    val state: String,
)

@Serializable data class EventResponse(override val version: Int, val event: EventInfo) : Versioned

/** The exact FCM data payload: an opaque event ID and kind only. Open it with an authenticated `getEvent` read. */
@Serializable
data class PushMessage(val v: String, val eventId: String, val kind: String) {
    companion object {
        private val KINDS = setOf("review_pending", "operation_completed", "attention_required")
        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Parses FCM `data` strictly: exactly `v`, `eventId`, `kind`; anything else (extra keys, other versions or kinds) is dropped as null. */
        fun fromData(data: Map<String, String>): PushMessage? {
            if (data.keys != setOf("v", "eventId", "kind")) return null
            val (v, id, kind) = Triple(data.getValue("v"), data.getValue("eventId"), data.getValue("kind"))
            return if (v == "1" && ID.matches(id) && kind in KINDS) PushMessage(v, id, kind) else null
        }
    }
}
