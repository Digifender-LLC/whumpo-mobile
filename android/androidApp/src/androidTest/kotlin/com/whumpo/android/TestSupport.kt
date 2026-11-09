package com.whumpo.android

import androidx.test.core.app.ApplicationProvider
import com.whumpo.core.account.AccountScope
import com.whumpo.core.auth.AuthState
import com.whumpo.core.auth.SessionManager
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Synthetic, secret-free fixture accounts. Never real account identifiers or tokens. */
object Fixtures {
    const val LABEL_A = "acct_fixture_instrumented_a"
    const val LABEL_B = "acct_fixture_instrumented_b"
    val ownerA: AccountScope = AccountScope.of(LABEL_A)
    val ownerB: AccountScope = AccountScope.of(LABEL_B)
    private var worker: FakeWorker? = null

    val app: WhumpoApplication get() = ApplicationProvider.getApplicationContext()

    val container: AppContainer get() = app.container

    /** Leaves the device signed out with no account state and no key. */
    fun reset() {
        // Start-up restores the stored session asynchronously; let it finish so it cannot race the fixture.
        runBlocking { container.sessions.state.first { it !is SessionManager.State.Restoring } }
        onMain { container.auth.cancelHandoff() }
        runBlocking { container.sessions.clear() }
        awaitAuth { it is AuthState.SignedOut }
        worker?.close()
        worker = null
    }

    /** A loopback fixture for local builds; the deployed test engine uses its same synthetic responses in process. */
    fun api(): FakeWorker = worker ?: FakeWorker().also { worker = it }

    /** Stores a real, sealed synthetic session; this alone must never open the shell. */
    fun establishSession(label: String = LABEL_A, token: String = "0".repeat(63) + "1") {
        container.sessions.begin(token)
        runBlocking { container.sessions.establish(label, Instant.now().plusSeconds(3_600).toString(), null) }
    }

    /** Exercises the production onboarding check instead of bypassing AuthGate with an active account. */
    fun signIn(label: String = LABEL_A, token: String = "0".repeat(63) + "1"): FakeWorker {
        val fixture = api()
        establishSession(label, token)
        onMain { container.auth.checkSetupAgain() }
        awaitAuth { it == AuthState.Ready }
        return fixture
    }

    fun awaitAuth(predicate: (AuthState) -> Boolean): AuthState = runBlocking {
        withTimeout(10_000) { container.auth.state.first(predicate) }
    }

    fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }
}

/** Emulator screenshots for the PR (app external files, pulled by adb); a failure to capture never fails a test. */
object Shots {
    fun take(name: String) {
        try {
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val dir = java.io.File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
            val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            Thread.sleep(400)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bmp ->
                java.io.File(dir, "$name-${if (night) "dark" else "light"}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (_: Exception) {
        }
    }
}

/** Runs a command as the shell user (the same user `adb shell` uses) and returns its output once it exits. */
object DeviceShell {
    fun run(command: String): String {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
