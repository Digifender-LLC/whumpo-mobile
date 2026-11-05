package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.shell.WhumpoShell
import com.whumpo.android.ui.state.EmptyState
import com.whumpo.android.ui.state.ErrorCard
import com.whumpo.android.ui.state.OfflineBanner
import com.whumpo.android.ui.state.Skeleton
import com.whumpo.android.ui.state.UpdateRequiredScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the state components and the shell: every width, light and dark, plus 200% font scale
 * for the components. Animations are off (`animated = false`) so the images are deterministic; that is also
 * the reduced-motion look. Record with `-Pscreenshots.record`; CI verifies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest : RobolectricUiTest() {
    private val scenes: List<Pair<String, @Composable () -> Unit>> = listOf(
        "state-loading" to { Padded { Skeleton("Loading projects", animated = false) } },
        "state-empty" to { EmptyState("No projects yet. Tell Whumpo what you need.", "Go to Chat", {}) },
        "state-error" to { Padded { ErrorCard("Couldn't load your projects.", "Nothing was changed.", {}) } },
        "state-offline" to {
            Column {
                OfflineBanner("You're offline. We'll be ready when you're back.")
                Padded { Skeleton("Loading projects", animated = false, rows = 3) }
            }
        },
        "state-update-required" to { UpdateRequiredScreen(onUpdate = {}, onHelp = {}) },
    )

    @Composable
    private fun Padded(content: @Composable () -> Unit) = Column(Modifier.padding(16.dp)) {
        Text("Projects", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Column(Modifier.padding(top = 16.dp)) { content() }
    }

    @Test
    fun stateComponentsAtEveryWidthInLightAndDark() {
        for ((name, scene) in scenes) for (screen in Screen.entries) for (dark in listOf(false, true)) {
            capture(name, screen, dark, content = scene)
        }
    }

    @Test
    fun stateComponentsAtLargeText() {
        for ((name, scene) in scenes) capture(name, Screen.Compact, dark = false, fontScale = 2f, content = scene)
    }

    @Test
    fun shellAtEveryWidthInLightAndDark() {
        for (screen in Screen.entries) for (dark in listOf(false, true)) {
            capture("shell", screen, dark) { WhumpoShell() }
        }
    }

    @Test
    fun shellOfflineAndLargeText() {
        capture("shell-offline", Screen.Compact, dark = false) { WhumpoShell(offline = true) }
        capture("shell", Screen.Compact, dark = false, fontScale = 2f) { WhumpoShell() }
    }
}
