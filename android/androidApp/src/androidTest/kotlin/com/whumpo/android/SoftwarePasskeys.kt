package com.whumpo.android

import android.util.Base64
import com.whumpo.core.auth.PasskeyAuthenticator
import com.whumpo.core.auth.PasskeyResult
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What `tests/android-local-worker.mjs` wrote: a disposable account, its passkey and the origin its assertions claim. */
class WorkerSeed(val accountHandle: String, val credentialId: String, val privateKeyPkcs8: String, val origin: String, val reviewId: String, val project: String) {
    companion object {
        /** The seed arrives base64-encoded as the `seed` instrumentation argument; null when no worker was started. */
        fun from(encoded: String?): WorkerSeed? {
            if (encoded.isNullOrBlank()) return null
            val o = Json.parseToJsonElement(String(Base64.decode(encoded, Base64.DEFAULT))).jsonObject
            fun f(k: String) = o[k]!!.jsonPrimitive.content
            return WorkerSeed(f("accountHandle"), f("credentialId"), f("privateKeyPkcs8"), f("origin"), f("reviewId"), f("project"))
        }
    }
}

/**
 * A software authenticator standing in for Credential Manager: a test double, not a passkey provider. It signs the WebAuthn
 * request the server issued with the seeded ES256 key, claiming the Android origin the Worker allowlists. It proves the app's
 * state machine and the server's verification of a correctly formed assertion. It does NOT prove Credential Manager, the
 * app-signature to origin binding, Digital Asset Links, or a real provider; those need a device and the published assetlinks.
 */
class SoftwarePasskeys(private val seed: WorkerSeed) : PasskeyAuthenticator {
    private val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(seed.privateKeyPkcs8, Base64.DEFAULT)))

    // Monotonic across calls and across test runs (the server rejects a counter that does not rise).
    private val counter = AtomicLong((System.currentTimeMillis() / 1000 - EPOCH) * 16)

    var assertions = 0
        private set

    override suspend fun getAssertion(requestJson: String): PasskeyResult {
        val options = Json.parseToJsonElement(requestJson).jsonObject
        val challenge = options["challenge"]!!.jsonPrimitive.content
        val rpId = options["rpId"]!!.jsonPrimitive.content
        val client = buildJsonObject {
            put("type", "webauthn.get")
            put("challenge", challenge)
            put("origin", seed.origin)
            put("crossOrigin", false)
        }.toString().toByteArray()
        val count = counter.incrementAndGet()
        val authData = sha256(rpId.toByteArray()) + byteArrayOf(0x05) + byteArrayOf((count shr 24).toByte(), (count shr 16).toByte(), (count shr 8).toByte(), count.toByte())
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(authData + sha256(client))
            sign()
        }
        assertions++
        return PasskeyResult.Assertion(response(client, authData, signature).toString())
    }

    private fun response(client: ByteArray, authData: ByteArray, signature: ByteArray): JsonObject = buildJsonObject {
        put("id", seed.credentialId)
        put("rawId", seed.credentialId)
        put("type", "public-key")
        put("clientExtensionResults", buildJsonObject {})
        put("response", buildJsonObject {
            put("clientDataJSON", b64(client))
            put("authenticatorData", b64(authData))
            put("signature", b64(signature))
            put("userHandle", seed.accountHandle)
        })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private companion object {
        /** 2026-01-01 UTC in seconds. */
        const val EPOCH = 1_767_225_600L
    }
}
