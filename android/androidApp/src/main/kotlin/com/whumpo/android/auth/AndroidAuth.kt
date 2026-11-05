package com.whumpo.android.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.whumpo.core.auth.BrowserLauncher
import com.whumpo.core.auth.PasskeyAuthenticator
import com.whumpo.core.auth.PasskeyResult
import java.lang.ref.WeakReference

/** The activity that is on screen, held weakly. Credential Manager and Custom Tabs need one; nothing else may keep it alive. */
class ActivityTracker {
    @Volatile private var ref: WeakReference<Activity>? = null

    val current: Activity? get() = ref?.get()

    fun started(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun stopped(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }
}

/**
 * Credential Manager passkey sign-in (ADR: `PasskeyAuthenticator`). The server's WebAuthn options go in unchanged and the
 * authentication response JSON comes out unchanged. The RP ID is whumpo.com, so this needs the Digital Asset Links file on
 * production (docs/android/assetlinks-setup.md); without it Credential Manager refuses the request.
 *
 * Not testable in CI: real passkeys need a device, a credential provider and the published asset links.
 */
class CredentialManagerPasskeys(context: Context, private val activities: ActivityTracker) : PasskeyAuthenticator {
    private val manager = CredentialManager.create(context)

    override suspend fun getAssertion(requestJson: String): PasskeyResult {
        val host = activities.current ?: return PasskeyResult.Unavailable
        return try {
            val credential = manager.getCredential(host, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))).credential
            if (credential is PublicKeyCredential) PasskeyResult.Assertion(credential.authenticationResponseJson) else PasskeyResult.Unavailable
        } catch (_: GetCredentialCancellationException) {
            PasskeyResult.Cancelled
        } catch (_: NoCredentialException) {
            PasskeyResult.NoCredential
        } catch (_: GetCredentialException) {
            PasskeyResult.Unavailable
        }
    }
}

/** Opens the hand-off and help pages in a Custom Tab (the system browser's own session; the app never injects its bearer). */
class CustomTabsBrowser(private val app: Context, private val activities: ActivityTracker) : BrowserLauncher {
    override fun open(url: String): Boolean {
        val tab = CustomTabsIntent.Builder().build()
        val host = activities.current
        return try {
            if (host == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(host ?: app, url.toUri())
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
