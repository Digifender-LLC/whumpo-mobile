package com.whumpo.android

import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsNode
import java.util.concurrent.ConcurrentLinkedQueue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The composer stays above the soft keyboard (edge-to-edge + imePadding). */
@RunWith(AndroidJUnit4::class)
class KeyboardInsetsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        Fixtures.reset()
        Fixtures.signIn()
    }

    @After
    fun tearDown() = Fixtures.reset()

    private fun withKeyboardActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use(block)
    }

    @Test
    fun anAbsentKeyboardClosesTheActivityBeforeSkipping() {
        var activity: MainActivity? = null
        assertThrows(AssumptionViolatedException::class.java) {
            withKeyboardActivity { scenario ->
                scenario.onActivity { activity = it }
                assumeTrue("Synthetic unavailable keyboard", false)
            }
        }
        assertTrue("the skipped scenario destroyed its activity", activity?.isDestroyed == true)
    }

    @Test
    fun composerIsNotCoveredByTheKeyboard() = withKeyboardActivity { scenario ->
        val imeAnimations = mutableSetOf<WindowInsetsAnimationCompat>()
        var animationObserved = false
        var animationGeneration = 0
        scenario.onActivity { activity ->
            ViewCompat.setWindowInsetsAnimationCallback(
                activity.window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            animationGeneration++
                            animationObserved = true
                            imeAnimations.add(animation)
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsetsCompat,
                        runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                    ): WindowInsetsCompat = insets

                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                            animationGeneration++
                            imeAnimations.remove(animation)
                        }
                    }
                },
            )
        }
        compose.onNodeWithTag("chat-composer").performClick()

        val shown = try {
            compose.waitUntil(10_000) {
                var visible = false
                var imeBottom = 0
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(root)
                    visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                }
                visible && imeBottom > 0
            }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
        // API28's explicit conditional skip is incomplete keyboard coverage. API36
        // must show the keyboard and execute this assertion in the required suite.
        Log.i("KeyboardInsetsTest", "keyboardShown=$shown animationObserved=$animationObserved")
        if (Build.VERSION.SDK_INT == 28) assumeTrue("Soft keyboard did not appear on this device", shown)
        assertTrue("Soft keyboard must appear on API ${Build.VERSION.SDK_INT}", shown)

        val samples = ConcurrentLinkedQueue<KeyboardGeometryStability.Frame>()
        val stability = KeyboardGeometryStability()
        var composerNode: SemanticsNode? = null
        var lastFrame: KeyboardGeometryStability.Frame? = null
        lateinit var root: View
        scenario.onActivity { root = it.window.decorView }
        var lastVsyncTimeNanos = 0L
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                lastVsyncTimeNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        val drawListener = ViewTreeObserver.OnDrawListener {
            // onDraw runs in an actual rendered frame. The vsync timestamp prevents
            // multiple draws/polls within one frame from counting as separate evidence.
            val node = composerNode
            if (node != null && lastVsyncTimeNanos > 0) {
                val insets = ViewCompat.getRootWindowInsets(root)
                val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                samples.add(
                    KeyboardGeometryStability.Frame(
                        lastVsyncTimeNanos,
                        node.boundsInWindow.bottom,
                        root.height - bottom,
                        bottom,
                        root.height,
                        insets?.isVisible(WindowInsetsCompat.Type.ime()) == true,
                        imeAnimations.isNotEmpty(),
                        animationGeneration,
                    ),
                )
            }
        }
        scenario.onActivity {
            root.viewTreeObserver.addOnDrawListener(drawListener)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        try {
            compose.waitUntil(10_000) {
                var stable = false
                while (true) {
                    val sample = samples.poll() ?: break
                    if (sample.timeNanos > (lastFrame?.timeNanos ?: -1L)) {
                        lastFrame = sample
                        stable = stability.observe(sample)
                    }
                }
                // A callback after the last draw invalidates that draw's evidence,
                // even if the animation started and ended between polls.
                scenario.onActivity {
                    stable = stable && imeAnimations.isEmpty() &&
                        lastFrame?.animationGeneration == animationGeneration
                }
                if (!stable) {
                    val node = compose.onNodeWithTag("chat-composer").fetchSemanticsNode()
                    scenario.onActivity {
                        composerNode = node
                        it.window.decorView.postInvalidateOnAnimation()
                    }
                }
                stable
            }
            val frame = checkNotNull(lastFrame)
            assertTrue(
                "composer bottom ${frame.composerBottom} must be above keyboard top ${frame.keyboardTop}",
                frame.composerBottom <= frame.keyboardTop + 1f,
            )
        } finally {
            scenario.onActivity {
                root.viewTreeObserver.removeOnDrawListener(drawListener)
                Choreographer.getInstance().removeFrameCallback(frameCallback)
            }
            Log.i(
                "KeyboardInsetsTest",
                "animationObserved=$animationObserved stableRenderedFrames=${stability.stableFrames} " +
                    "composerBottom=${lastFrame?.composerBottom} keyboardTop=${lastFrame?.keyboardTop}",
            )
        }
    }
}
