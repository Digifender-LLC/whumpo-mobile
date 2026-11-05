package com.whumpo.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Supplies the native bearer session (WP5 owns sign-in and storage; this client has no auth UI).
 * Tokens travel only in the `Authorization` header.
 */
interface SessionProvider {
    /** The current bearer token (64 lowercase hex), or null when signed out. */
    suspend fun bearerToken(): String?

    /**
     * Called when the server rejected the session (`proof_required` with `signin`, or `identity_changed`) on an
     * authenticated route. The app clears local private state; a retained retry waits for the owner to sign in.
     */
    suspend fun onSessionRejected()
}

/** A client-generated ID for a durable write (lowercase UUID). Generate once per owner action and reuse it for every retry of that action. */
@OptIn(ExperimentalUuidApi::class)
fun newRequestId(): String = Uuid.random().toString()

/**
 * Typed client for `/api/mobile/v1` (docs/mobile-api-v1.md). One method per operation.
 *
 * The client never regenerates IDs and auto-retries only one case: `identity_changed` right after its own reauth (see [exec]). A write takes the caller's request ID; after [MobileApiException.Transport]
 * with `outcomeUnknown`, resolve with a read ([resolveSend], [getChatTurn], [interactionReceipt], [getPhotoUpload]) and retry only
 * as an explicit owner action with the same ID and payload. Cookies and redirects are never used.
 *
 * @param mobileApiBase for example `WhumpoEnvironment.mobileApiBase` (`https://whumpo.com/api/mobile/v1`), no trailing slash.
 */
class MobileApiClient(
    engine: HttpClientEngine,
    private val mobileApiBase: String,
    private val client: ClientInfo,
    private val sessions: SessionProvider,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {
    // Set when this client's own reauthVerify succeeded; lets exec tell a reauth race from a genuinely changed identity.
    private var lastReauth: TimeMark? = null

    private val http = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
    }
    private val clientHeader = wire.encodeToString(ClientInfo.serializer(), client)

    override fun close() = http.close()

    // ---- Identity and session ---------------------------------------------------------------------------------------

    suspend fun getConfig(): ConfigResponse = get(ConfigResponse.serializer(), "/config", auth = false)

    suspend fun loginOptions(): PasskeyOptionsResponse =
        post(PasskeyOptionsResponse.serializer(), "/session/login/options", VersionOnly.serializer(), VersionOnly(), auth = false)

    suspend fun loginVerify(challengeId: String, credential: JsonObject): SessionResponse =
        post(
            SessionResponse.serializer(), "/session/login/verify",
            PasskeyVerifyRequest.serializer(), PasskeyVerifyRequest(uuid(challengeId), credential), auth = false,
        )

    suspend fun handoffStart(request: HandoffStartRequest): HandoffStartResponse {
        require(request.purpose in setOf("signin", "signup", "recovery")) { "unknown handoff purpose" }
        require(request.redirectUri == BROWSER_RETURN) { "redirectUri must be $BROWSER_RETURN" }
        require(PKCE_CHALLENGE.matches(request.codeChallenge)) { "codeChallenge must be 43 base64url characters (S256)" }
        require(STATE.matches(request.state)) { "state must be 16..128 base64url characters" }
        return post(HandoffStartResponse.serializer(), "/session/handoff/start", HandoffStartRequest.serializer(), request, auth = false)
    }

    suspend fun handoffExchange(request: HandoffExchangeRequest): SessionResponse {
        uuid(request.handoffId)
        require(Regex("[0-9a-f]{64}").matches(request.code)) { "code must be 64 lowercase hex" }
        require(STATE.matches(request.state)) { "state must be 16..128 base64url characters" }
        require(Regex("[A-Za-z0-9._~-]{43,128}").matches(request.codeVerifier)) { "codeVerifier must be 43..128 unreserved characters" }
        return post(SessionResponse.serializer(), "/session/handoff/exchange", HandoffExchangeRequest.serializer(), request, auth = false)
    }

    suspend fun reauthOptions(): PasskeyOptionsResponse =
        post(PasskeyOptionsResponse.serializer(), "/session/reauth/options", VersionOnly.serializer(), VersionOnly())

    suspend fun reauthVerify(challengeId: String, credential: JsonObject): ReauthResponse =
        post(ReauthResponse.serializer(), "/session/reauth/verify", PasskeyVerifyRequest.serializer(), PasskeyVerifyRequest(uuid(challengeId), credential))
            .also { lastReauth = timeSource.markNow() }

    suspend fun getSession(): SessionReadResponse = get(SessionReadResponse.serializer(), "/session")

    /** On a rejected session the [SessionProvider] is notified before this throws, so the app can clear local state either way. */
    suspend fun logout(): LogoutResponse = post(LogoutResponse.serializer(), "/session/logout", VersionOnly.serializer(), VersionOnly())

    // ---- Workspace, Chat and reviews --------------------------------------------------------------------------------

    suspend fun bootstrap(): BootstrapResponse = get(BootstrapResponse.serializer(), "/bootstrap")

    suspend fun getChat(limit: Int = 25, cursor: String? = null, query: String? = null): ChatPage {
        require(limit in 1..50) { "limit must be 1..50" }
        query?.let { require(it.length <= 100) { "q is at most 100 characters" } }
        return get(ChatPage.serializer(), "/chat", params = listOf("limit" to limit.toString(), "cursor" to cursor, "q" to query))
    }

    suspend fun getChatTurn(turnId: String): ChatTurnResponse = get(ChatTurnResponse.serializer(), "/chat/turns/${uuid(turnId)}")

    /** [requestId] is the turn ID. Reuse it, with the same content, for any retry of this send. */
    suspend fun sendMessage(requestId: String, expectedRevision: Int, content: String): WriteAccepted {
        require(content.length in 1..1000) { "content must be 1..1000 characters" }
        return post(
            WriteAccepted.serializer(), "/chat/messages", SendMessageRequest.serializer(),
            SendMessageRequest(uuid(requestId), expectedRevision, content), write = true,
        )
    }

    /** Resolves a lost [sendMessage] reply: the committed turn, or null when nothing committed (`404 not_found`). */
    suspend fun resolveSend(requestId: String): ChatTurn? =
        try {
            getChatTurn(requestId).turn
        } catch (_: MobileApiException.NotFound) {
            null
        }

    suspend fun listReviews(): ReviewList = get(ReviewList.serializer(), "/reviews")

    /**
     * Sends a server-issued control. A `fresh_passkey` [MobileApiException.ProofRequired] means: reauthenticate
     * ([reauthOptions]/[reauthVerify]), then call again with the same [requestId].
     */
    suspend fun confirmReview(requestId: String, expectedRevision: Int, control: ReviewControl): WriteAccepted {
        require(control.capability in client.controls) { "this build did not declare ${control.capability}" }
        return post(
            WriteAccepted.serializer(), "/reviews/confirm", ConfirmReviewRequest.serializer(),
            ConfirmReviewRequest(uuid(requestId), expectedRevision, control), write = true,
        )
    }

    // ---- Interactions (WH-105 payloads pass through as JSON objects) ----------------------------------------------------

    suspend fun interactionList(projectId: String?, limit: Int = 20, before: JsonElement? = null): JsonObject {
        require(limit in 1..20) { "limit must be 1..20" }
        return postObject(
            "/interactions/list", InteractionListRequest.serializer(),
            InteractionListRequest(projectId?.let(::uuid), limit, before),
        )
    }

    /** The request's `client` is always the declared header capability; the server refuses any other. */
    suspend fun interactionRead(interactionId: String): JsonObject =
        postObject("/interactions/read", InteractionReadRequest.serializer(), InteractionReadRequest(uuid(interactionId), client.interaction))

    suspend fun interactionResources(
        interactionId: String,
        definitionDigest: String,
        fieldId: String,
        limit: Int = 20,
        before: JsonElement? = null,
    ): JsonObject {
        require(limit in 1..20) { "limit must be 1..20" }
        require(DIGEST.matches(definitionDigest)) { "definitionDigest must be 64 lowercase hex" }
        return postObject(
            "/interactions/resources", InteractionResourcesRequest.serializer(),
            InteractionResourcesRequest(uuid(interactionId), definitionDigest, fieldId, limit, before),
        )
    }

    suspend fun interactionMutate(request: InteractionMutateRequest): InteractionReceipt {
        require(request.operation in setOf("save-draft", "submit", "cancel")) { "unknown operation" }
        require((request.operation == "cancel") == (request.answers == null)) { "cancel carries no answers; save-draft and submit require them" }
        require(DIGEST.matches(request.definitionDigest)) { "definitionDigest must be 64 lowercase hex" }
        uuid(request.requestId)
        uuid(request.interactionId)
        return post(InteractionReceipt.serializer(), "/interactions/mutate", InteractionMutateRequest.serializer(), request, write = true)
    }

    /** Readback for a lost [interactionMutate] reply. */
    suspend fun interactionReceipt(requestId: String): InteractionReceipt =
        post(InteractionReceipt.serializer(), "/interactions/receipt", InteractionReceiptRequest.serializer(), InteractionReceiptRequest(uuid(requestId)))

    suspend fun interactionContinue(submissionId: String, expectedChatRevision: Int): ContinuationResponse =
        post(
            ContinuationResponse.serializer(), "/interactions/continue", ContinueRequest.serializer(),
            ContinueRequest(uuid(submissionId), expectedChatRevision), write = true,
        )

    suspend fun interactionStop(submissionId: String): ContinuationResponse =
        post(ContinuationResponse.serializer(), "/interactions/stop", SubmissionRequest.serializer(), SubmissionRequest(uuid(submissionId)), write = true)

    /** Resolves a continuation in state `unknown`. */
    suspend fun interactionReconcile(submissionId: String): ContinuationResponse =
        post(ContinuationResponse.serializer(), "/interactions/reconcile", SubmissionRequest.serializer(), SubmissionRequest(uuid(submissionId)), write = true)

    // ---- Projects, versions, activity -------------------------------------------------------------------------------

    suspend fun listProjects(limit: Int = 25, cursor: String? = null): ProjectPage {
        require(limit in 1..50) { "limit must be 1..50" }
        return get(ProjectPage.serializer(), "/projects", params = listOf("limit" to limit.toString(), "cursor" to cursor))
    }

    suspend fun getProject(projectId: String): ProjectResponse = get(ProjectResponse.serializer(), "/projects/${uuid(projectId)}")

    suspend fun listVersions(projectId: String, limit: Int = 25): VersionList {
        require(limit in 1..25) { "limit must be 1..25" }
        return get(VersionList.serializer(), "/projects/${uuid(projectId)}/versions", params = listOf("limit" to limit.toString()))
    }

    suspend fun listActivity(limit: Int = 25, cursor: String? = null): ActivityPage {
        require(limit in 1..50) { "limit must be 1..50" }
        return get(ActivityPage.serializer(), "/activity", params = listOf("limit" to limit.toString(), "cursor" to cursor))
    }

    // ---- Photos -----------------------------------------------------------------------------------------------------

    suspend fun listPhotos(projectId: String): PhotoList = get(PhotoList.serializer(), "/projects/${uuid(projectId)}/photos")

    /** [uploadId] keys the upload; the same ID with different bytes, name or project is `409 request_conflict`. [jpeg] is the prepared JPEG, at most 1 MiB. */
    suspend fun uploadPhoto(projectId: String, uploadId: String, name: String, jpeg: ByteArray): PhotoResponse {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_PHOTO_BYTES) { "photo must be a prepared JPEG of at most 1 MiB" }
        require(name.length in 1..100) { "photo name must be 1..100 characters" }
        return send(
            PhotoResponse.serializer(), HttpMethod.Put, "/projects/${uuid(projectId)}/photos/${uuid(uploadId)}",
            body = ByteArrayContent(jpeg, ContentType.Image.JPEG), extraHeaders = listOf("X-Photo-Name" to name.encodeURLParameter()), write = true,
        )
    }

    /** Readback for a lost [uploadPhoto] reply. */
    suspend fun getPhotoUpload(projectId: String, uploadId: String): PhotoResponse =
        get(PhotoResponse.serializer(), "/projects/${uuid(projectId)}/photos/${uuid(uploadId)}")

    /** JPEG bytes for approved, owned media. Do not render a photo whose [Photo.usable] is false. */
    suspend fun getPhotoBytes(photoId: String): ByteArray {
        val r = exec(HttpMethod.Get, "/photos/${uuid(photoId)}", auth = true)
        if (!r.contentType.startsWith("image/jpeg")) throw MobileApiException.InvalidResponse("photo reply is not image/jpeg")
        return r.bytes
    }

    // ---- Push -------------------------------------------------------------------------------------------------------

    suspend fun registerDevice(fcmRegistrationToken: String, notificationsEnabled: Boolean): DeviceResponse {
        require(FCM_TOKEN.matches(fcmRegistrationToken)) { "invalid registration token" }
        return send(
            DeviceResponse.serializer(), HttpMethod.Put, "/devices/current",
            body = encodeBody(DeviceRegisterRequest.serializer(), DeviceRegisterRequest(fcmRegistrationToken, notificationsEnabled)), write = true,
        )
    }

    suspend fun removeDevice(): DeviceRemoved = send(DeviceRemoved.serializer(), HttpMethod.Delete, "/devices/current", write = true)

    /** Opening a notification only triggers this authenticated read; it never sends, approves or retries anything. */
    suspend fun getEvent(eventId: String): EventResponse = get(EventResponse.serializer(), "/events/${uuid(eventId)}")

    // ---- Transport --------------------------------------------------------------------------------------------------

    private class Raw(val bytes: ByteArray, val contentType: String)

    private suspend fun <T : Versioned> get(
        serializer: KSerializer<T>,
        path: String,
        auth: Boolean = true,
        params: List<Pair<String, String?>> = emptyList(),
    ): T = send(serializer, HttpMethod.Get, path, auth = auth, params = params)

    private suspend fun <B, T : Versioned> post(
        serializer: KSerializer<T>,
        path: String,
        bodySerializer: KSerializer<B>,
        body: B,
        auth: Boolean = true,
        write: Boolean = false,
    ): T = send(serializer, HttpMethod.Post, path, auth = auth, body = encodeBody(bodySerializer, body), write = write)

    private suspend fun <B> postObject(path: String, bodySerializer: KSerializer<B>, body: B): JsonObject {
        val text = exec(HttpMethod.Post, path, auth = true, body = encodeBody(bodySerializer, body)).bytes.decodeToString()
        return decode(JsonObject.serializer(), text).also {
            if (it["version"]?.jsonPrimitive?.int != CONTRACT_VERSION) throw MobileApiException.InvalidResponse("unsupported response version")
        }
    }

    private suspend fun <T : Versioned> send(
        serializer: KSerializer<T>,
        method: HttpMethod,
        path: String,
        auth: Boolean = true,
        params: List<Pair<String, String?>> = emptyList(),
        body: io.ktor.http.content.OutgoingContent? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        write: Boolean = false,
    ): T {
        val text = exec(method, path, auth, params, body, extraHeaders, write).bytes.decodeToString()
        val value = decode(serializer, text)
        if (value.version != CONTRACT_VERSION) throw MobileApiException.InvalidResponse("unsupported response version ${value.version}")
        return value
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        try {
            wire.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw MobileApiException.InvalidResponse("response does not match the v1 contract", e)
        } catch (e: IllegalArgumentException) {
            throw MobileApiException.InvalidResponse("response does not match the v1 contract", e)
        }

    private fun <B> encodeBody(serializer: KSerializer<B>, body: B) =
        TextContent(wire.encodeToString(serializer, body), JSON_UTF8)

    /**
     * A reauth replaces the session row (same token, new authority), so a request that resolved its identity just before the
     * commit fails with `identity_changed`. After this client's own reauth (within [REAUTH_RACE_WINDOW]) that is repeated
     * exactly once, with the identical body and request ID; any other `identity_changed` is a rejected session.
     */
    private suspend fun exec(
        method: HttpMethod,
        path: String,
        auth: Boolean,
        params: List<Pair<String, String?>> = emptyList(),
        body: io.ktor.http.content.OutgoingContent? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        write: Boolean = false,
    ): Raw {
        val first = try {
            return execOnce(method, path, auth, params, body, extraHeaders, write)
        } catch (e: MobileApiException.IdentityChanged) {
            e
        }
        val reauth = lastReauth
        // An anonymous call carries no session, so a refusal of it (a full challenge table, say) says nothing about the stored one.
        if (!auth) throw first
        if (reauth == null || reauth.elapsedNow() > REAUTH_RACE_WINDOW) return reject(first)
        return try {
            execOnce(method, path, auth, params, body, extraHeaders, write)
        } catch (e: MobileApiException.IdentityChanged) {
            reject(e)
        }
    }

    private suspend fun reject(e: MobileApiException.IdentityChanged): Nothing {
        sessions.onSessionRejected()
        throw e
    }

    private suspend fun execOnce(
        method: HttpMethod,
        path: String,
        auth: Boolean,
        params: List<Pair<String, String?>> = emptyList(),
        body: io.ktor.http.content.OutgoingContent? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        write: Boolean = false,
    ): Raw {
        val token = if (auth) {
            sessions.bearerToken()?.takeIf { BEARER.matches(it) }
                ?: throw MobileApiException.ProofRequired("Sign in to continue.", MobileApiException.ProofRequired.Proof.SIGN_IN)
        } else {
            null
        }
        val response = try {
            http.request(mobileApiBase + path) {
                this.method = method
                header(CLIENT_HEADER, clientHeader)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                extraHeaders.forEach { (k, v) -> header(k, v) }
                params.forEach { (k, v) -> if (v != null) parameter(k, v) }
                if (body != null) setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MobileApiException.Transport(e, outcomeUnknown = write)
        }
        val bytes = try {
            response.bodyAsBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MobileApiException.Transport(e, outcomeUnknown = write)
        }
        if (response.status.value != 200) {
            val error = MobileApiException.fromResponse(
                response.status.value, bytes.decodeToString(), response.headers[HttpHeaders.RetryAfter],
            )
            // identity_changed is reported by [exec], which may retry it first.
            if (auth && error is MobileApiException.ProofRequired && error.proof == MobileApiException.ProofRequired.Proof.SIGN_IN) {
                sessions.onSessionRejected()
            }
            throw error
        }
        return Raw(bytes, response.headers[HttpHeaders.ContentType].orEmpty())
    }

    private fun uuid(id: String): String {
        require(UUID.matches(id)) { "expected a lowercase UUID" }
        return id
    }

    private companion object {
        val REAUTH_RACE_WINDOW = 10.seconds
        const val CLIENT_HEADER = "Whumpo-Client"
        val JSON_UTF8 = ContentType.parse("application/json; charset=utf-8")
        const val BROWSER_RETURN = "https://whumpo.com/app/return"
        const val MAX_PHOTO_BYTES = 1_048_576
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val BEARER = Regex("[0-9a-f]{64}")
        val DIGEST = Regex("[0-9a-f]{64}")
        val PKCE_CHALLENGE = Regex("[A-Za-z0-9_-]{43}")
        val STATE = Regex("[A-Za-z0-9_-]{16,128}")
        val FCM_TOKEN = Regex("[A-Za-z0-9_:.-]{16,4096}")

        // Strict decode (unknown keys fail); encodeDefaults so `version` and the constant discriminators are always sent.
        val wire = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
