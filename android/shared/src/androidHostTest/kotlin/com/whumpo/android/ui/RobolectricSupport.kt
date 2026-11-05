package com.whumpo.android.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.whumpo.android.ui.theme.WhumpoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Activity whose content survives recreation, for rotation tests. */
class ShellTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // No action bar: the app draws edge to edge and the goldens must not show a system title.
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        setContent { content() }
    }

    companion object {
        var content: @Composable () -> Unit = {}
    }
}

/** Widths from the UX spec: compact phone, medium (foldable open or small tablet), expanded tablet or desktop. */
internal enum class Screen(val label: String, val widthDp: Int, val heightDp: Int) {
    Compact("compact", 360, 720),
    Medium("medium", 700, 900),
    Expanded("expanded", 1000, 700),
}

/**
 * Play listing sizes, in dp at a given density: phone 360x640 at 3x is 1080x1920; the 7" tablet 600x960 at 2x is
 * 1200x1920; the 10" tablet 800x1280 at 2x is 1600x2560. Used only by the store-listing captures.
 */
internal enum class StoreDevice(val label: String, val widthDp: Int, val heightDp: Int, val density: String) {
    Phone("phone", 360, 640, "xxhdpi"),
    Tablet7("tablet7", 600, 960, "xhdpi"),
    Tablet10("tablet10", 800, 1280, "xhdpi"),
}

/** Robolectric host-test base: launches [ShellTestActivity] at a given window size, in a given theme. */
abstract class RobolectricUiTest {
    @get:Rule
    val compose: ComposeTestRule = createEmptyComposeRule()

    @Before
    fun registerActivity() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context.packageName, ShellTestActivity::class.java.name))
    }

    internal fun launch(
        screen: Screen,
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ): ActivityScenario<ShellTestActivity> {
        // Only width and height are set here; density stays at the Robolectric default (xhdpi in the qualifier).
        return launchAt("w${screen.widthDp}dp-h${screen.heightDp}dp-xhdpi", dark, fontScale, content)
    }

    private fun launchAt(
        qualifiers: String,
        dark: Boolean,
        fontScale: Float,
        content: @Composable () -> Unit,
    ): ActivityScenario<ShellTestActivity> {
        RuntimeEnvironment.setQualifiers(qualifiers)
        ShellTestActivity.content = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WhumpoTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        return ActivityScenario.launch(ShellTestActivity::class.java).also { compose.waitForIdle() }
    }

    /** Records or verifies one golden, depending on the `screenshots.record` Gradle property. */
    internal fun capture(
        name: String,
        screen: Screen,
        dark: Boolean,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val theme = if (dark) "dark" else "light"
        val scale = if (fontScale == 1f) "" else "-font${fontScale.toInt()}"
        launch(screen, dark, fontScale, content).use {
            compose.onRoot().captureRoboImage("src/androidHostTest/screenshots/$name-${screen.label}-$theme$scale.png")
        }
    }

    /** Records or verifies one Play listing image under docs/android/store-listing/screenshots (path is relative to :shared). */
    internal fun captureStore(name: String, device: StoreDevice, dark: Boolean, content: @Composable () -> Unit) {
        val theme = if (dark) "dark" else "light"
        launchAt("w${device.widthDp}dp-h${device.heightDp}dp-${device.density}", dark, 1f, content).use {
            compose.onRoot().captureRoboImage("../../docs/android/store-listing/screenshots/${device.label}/$name-$theme.png")
        }
    }
}
