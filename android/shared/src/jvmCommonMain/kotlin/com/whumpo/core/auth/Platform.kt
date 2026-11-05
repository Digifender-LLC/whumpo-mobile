package com.whumpo.core.auth

/** Outcome of one Credential Manager passkey request. */
sealed interface PasskeyResult {
    /** [json] is the WebAuthn authentication response JSON, passed to the server unchanged. */
    class Assertion(val json: String) : PasskeyResult {
        override fun toString() = "Assertion(<redacted>)"
    }

    /** The owner dismissed the system sheet. Not an error. */
    data object Cancelled : PasskeyResult

    /** No Whumpo passkey is available on this device. */
    data object NoCredential : PasskeyResult

    /** Credential Manager is missing, unsupported or was interrupted. */
    data object Unavailable : PasskeyResult
}

/** Credential Manager behind an interface (ADR: platform services). The Android implementation lives in `:androidApp`. */
interface PasskeyAuthenticator {
    /** [requestJson] is the server's WebAuthn request options (`options` from the challenge response). */
    suspend fun getAssertion(requestJson: String): PasskeyResult
}

/** Opens a URL in the system browser (a Custom Tab on Android). Returns false when nothing could open it. */
interface BrowserLauncher {
    fun open(url: String): Boolean
}
