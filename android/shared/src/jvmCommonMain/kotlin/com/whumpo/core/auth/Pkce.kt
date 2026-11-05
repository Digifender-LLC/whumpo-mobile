package com.whumpo.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) S256 values for the browser hand-off. The verifier never leaves the app except in the exchange call. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun token(): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))

    /** 43 unreserved characters (32 random bytes). */
    fun newVerifier(): String = token()

    /** 43 base64url characters; the hand-off `state` rule is 16..128. */
    fun newState(): String = token()

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
