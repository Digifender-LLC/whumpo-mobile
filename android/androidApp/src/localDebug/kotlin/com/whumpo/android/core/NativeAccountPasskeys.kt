package com.whumpo.android.core

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import com.whumpo.android.auth.ActivityTracker
import com.whumpo.android.auth.CredentialManagerPasskeys
import com.whumpo.core.auth.AccountPasskeys
import com.whumpo.core.auth.PasskeyResult

internal class NativeAccountPasskeys(context: Context, private val activities: ActivityTracker) : AccountPasskeys {
    private val manager = CredentialManager.create(context)
    private val authentication = CredentialManagerPasskeys(context, activities)

    override suspend fun register(options: String): String? {
        val host = checkNotNull(activities.current)
        return try {
            val result = manager.createCredential(host, CreatePublicKeyCredentialRequest(options))
            checkNotNull(result as? CreatePublicKeyCredentialResponse).registrationResponseJson
        } catch (_: CreateCredentialCancellationException) {
            null
        }
    }

    override suspend fun assert(options: String): String? = when (val result = authentication.getAssertion(options)) {
        is PasskeyResult.Assertion -> result.json
        PasskeyResult.Cancelled -> null
        PasskeyResult.NoCredential, PasskeyResult.Unavailable -> error("Passkey unavailable")
    }
}
