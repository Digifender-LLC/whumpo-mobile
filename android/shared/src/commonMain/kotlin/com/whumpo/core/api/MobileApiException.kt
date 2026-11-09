package com.whumpo.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every contract error code (docs/mobile-api-v1.md, Errors) maps to one subtype. The client never retries on its own:
 * a retry is an explicit owner action that reuses the original payload and request ID.
 */
sealed class MobileApiException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 426. Unsupported contract, build, version, control, field type or binding. No downgrade, no approval. */
    class ClientUpdateRequired(message: String, val minimumBuild: Int?, val contract: Int?) :
        MobileApiException("client_update_required", message)

    /** 401. [Proof.SIGN_IN]: missing, expired or revoked session. [Proof.FRESH_PASSKEY]: reauthenticate, then retry with the same request ID. */
    class ProofRequired(message: String, val proof: Proof) : MobileApiException("proof_required", message) {
        enum class Proof { SIGN_IN, FRESH_PASSKEY }
    }

    /** 403. Account is enrolling or recovering; finish in the browser. */
    class IdentityIncomplete(message: String) : MobileApiException("identity_incomplete", message)

    /** 409. Welcome is not complete; open the browser. */
    class OnboardingRequired(message: String) : MobileApiException("onboarding_required", message)

    /** 409. Authority changed under the action; sign in and review again. */
    class IdentityChanged(message: String) : MobileApiException("identity_changed", message)

    /** 400. `invalid_request`, `unknown_field`, `missing_field`, `invalid_cursor` or `unsupported_control` (see [code]). */
    class ValidationFailed(code: String, message: String) : MobileApiException(code, message)

    /** 404. Missing, expired or foreign; deliberately indistinguishable. After a lost write it means nothing committed. */
    class NotFound(message: String) : MobileApiException("not_found", message)

    /** 409. The request ID was already used with a different payload. */
    class RequestConflict(message: String) : MobileApiException("request_conflict", message)

    /** 409. The conversation moved on; refresh to [currentRevision] before offering a new action. */
    class StaleRevision(message: String, val currentRevision: Int?) : MobileApiException("stale_revision", message)

    /** 409. A prior reply is still pending. */
    class ChatBusy(message: String) : MobileApiException("chat_busy", message)

    /** 409. The review is stale or expired. */
    class ReviewUnavailable(message: String) : MobileApiException("review_unavailable", message)

    /** 413. */
    class PayloadTooLarge(message: String) : MobileApiException("payload_too_large", message)

    /** 415. */
    class UnsupportedMediaType(message: String) : MobileApiException("unsupported_media_type", message)

    /** 429. Wait [retryAfterSeconds] (from the body, else the `Retry-After` header) before an explicit retry. */
    class RateLimited(message: String, val retryAfterSeconds: Int?) : MobileApiException("rate_limited", message)

    /** 503. Platform or provider unavailable. */
    class Unavailable(message: String) : MobileApiException("unavailable", message)

    /**
     * 422 from an interaction route: one field problem from the open WH-105 vocabulary. Show it at [fieldId] when that
     * names a field of the current form, otherwise at form level. Never branch on [code].
     */
    class FieldProblem(code: String, message: String, val fieldId: String?) : MobileApiException(code, message)

    /** A non-success reply that is not a contract error body (gateway page, redirect, unknown code). */
    class Unrecognized(val status: Int, code: String?) :
        MobileApiException(code ?: "unrecognized", "Unexpected response (HTTP $status).")

    /** A success reply that does not match the frozen shape or version. */
    class InvalidResponse(message: String, cause: Throwable? = null) : MobileApiException("invalid_response", message, cause)

    /**
     * No usable reply. When [outcomeUnknown] is true a write may have committed: resolve it by reading
     * (for example `getChatTurn(requestId)`), never by generating a new ID or resending blindly.
     */
    class Transport(cause: Throwable, val outcomeUnknown: Boolean) :
        MobileApiException("transport", "The request could not be completed.", cause)

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        /** The fixed status of each code (docs/mobile-api-v1.md, Errors). A code at any other status is not a contract error. */
        private val statusOf = mapOf(
            "client_update_required" to 426,
            "proof_required" to 401,
            "identity_incomplete" to 403,
            "onboarding_required" to 409,
            "identity_changed" to 409,
            "invalid_request" to 400,
            "unknown_field" to 400,
            "missing_field" to 400,
            "invalid_cursor" to 400,
            "unsupported_control" to 400,
            "not_found" to 404,
            "request_conflict" to 409,
            "stale_revision" to 409,
            "chat_busy" to 409,
            "review_unavailable" to 409,
            "payload_too_large" to 413,
            "unsupported_media_type" to 415,
            "rate_limited" to 429,
            "unavailable" to 503,
        )

        @Serializable
        private class Envelope(val version: Int, val error: Body)

        @Serializable
        private class Body(
            val code: String,
            val message: String,
            val minimumBuild: Int? = null,
            val contract: Int? = null,
            val proof: String? = null,
            val retryAfterSeconds: Int? = null,
            val currentRevision: Int? = null,
            val fieldId: String? = null,
        )

        /**
         * Maps an HTTP failure to its contract error. [retryAfterHeader] is only a fallback for rate limits. Only
         * interaction routes ([fieldProblems]) can return a `422` [FieldProblem]; elsewhere a 422 is [Unrecognized].
         */
        fun fromResponse(status: Int, body: String, retryAfterHeader: String? = null, fieldProblems: Boolean = false): MobileApiException {
            val b = try {
                lenient.decodeFromString(Envelope.serializer(), body).takeIf { it.version == CONTRACT_VERSION }?.error
            } catch (_: Exception) {
                null
            } ?: return Unrecognized(status, null)
            // 422 codes are an open set outside the closed mobile codes, which keep their own statuses.
            if (fieldProblems && status == 422 && b.code !in statusOf) return FieldProblem(b.code, b.message, b.fieldId)
            if (statusOf[b.code] != status) return Unrecognized(status, b.code)
            val m = b.message
            return when (b.code) {
                "client_update_required" -> ClientUpdateRequired(m, b.minimumBuild, b.contract)
                "proof_required" -> ProofRequired(
                    m,
                    if (b.proof == "fresh_passkey") ProofRequired.Proof.FRESH_PASSKEY else ProofRequired.Proof.SIGN_IN,
                )
                "identity_incomplete" -> IdentityIncomplete(m)
                "onboarding_required" -> OnboardingRequired(m)
                "identity_changed" -> IdentityChanged(m)
                "invalid_request", "unknown_field", "missing_field", "invalid_cursor", "unsupported_control" -> ValidationFailed(b.code, m)
                "not_found" -> NotFound(m)
                "request_conflict" -> RequestConflict(m)
                "stale_revision" -> StaleRevision(m, b.currentRevision)
                "chat_busy" -> ChatBusy(m)
                "review_unavailable" -> ReviewUnavailable(m)
                "payload_too_large" -> PayloadTooLarge(m)
                "unsupported_media_type" -> UnsupportedMediaType(m)
                "rate_limited" -> RateLimited(m, b.retryAfterSeconds ?: retryAfterHeader?.trim()?.toIntOrNull())
                "unavailable" -> Unavailable(m)
                else -> Unrecognized(status, b.code)
            }
        }
    }
}
