package com.whumpo.android.ui

import androidx.compose.runtime.Composable
import com.whumpo.android.ui.auth.AuthFlow
import com.whumpo.core.auth.AuthProblem
import com.whumpo.core.auth.AuthState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the sign-in screens at 360dp (phone) and 1000dp (tablet or desktop window), light and dark,
 * plus 200% text on the two screens with the most content. The Restoring skeleton is left out: it animates.
 * Record with `-Pscreenshots.record` (the workflow's record_screenshots input); CI verifies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AuthScreenshotTest : RobolectricUiTest() {
    private val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
        "auth-welcome" to { AuthFlow(AuthState.SignedOut(), RecordingActions()) },
        "auth-welcome-busy" to { AuthFlow(AuthState.SignedOut(busy = true), RecordingActions()) },
        "auth-welcome-no-passkey" to { AuthFlow(AuthState.SignedOut(AuthProblem.NoPasskey), RecordingActions()) },
        "auth-welcome-network" to { AuthFlow(AuthState.SignedOut(AuthProblem.Network), RecordingActions()) },
        "auth-welcome-session-ended" to { AuthFlow(AuthState.SignedOut(AuthProblem.SessionEnded), RecordingActions()) },
        "auth-waiting" to { AuthFlow(AuthState.WaitingForBrowser(), RecordingActions()) },
        "auth-exchanging" to { AuthFlow(AuthState.WaitingForBrowser(exchanging = true), RecordingActions()) },
        "auth-finish-setup" to { AuthFlow(AuthState.FinishSetup(canCheck = true), RecordingActions()) },
    )

    @Test
    fun signInScreensOnPhoneAndTabletInLightAndDark() {
        for ((name, scene) in scenes) for (screen in listOf(Screen.Compact, Screen.Expanded)) for (dark in listOf(false, true)) {
            capture(name, screen, dark, content = scene)
        }
    }

    @Test
    fun signInScreensAtLargeText() {
        for ((name, scene) in scenes.filter { it.first in setOf("auth-welcome-no-passkey", "auth-finish-setup") }) {
            capture(name, Screen.Compact, dark = false, fontScale = 2f, content = scene)
        }
    }
}
