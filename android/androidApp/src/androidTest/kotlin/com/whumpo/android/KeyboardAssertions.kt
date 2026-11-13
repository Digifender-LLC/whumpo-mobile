package com.whumpo.android

import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import java.util.concurrent.ConcurrentLinkedQueue
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Shared test assertion: three stable rendered frames, after IME motion, keep [targetTag] above the keyboard. */
internal fun assertAboveKeyboard(
    compose: ComposeTestRule,
    scenario: ActivityScenario<MainActivity>,
    inputTag: String,
    targetTag: String,
) {
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
    compose.onNodeWithTag(inputTag).performClick()

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
    Log.i("KeyboardAssertions", "keyboardShown=$shown animationObserved=$animationObserved")
    if (Build.VERSION.SDK_INT == 28) assumeTrue("Soft keyboard did not appear on this device", shown)
    assertTrue("Soft keyboard must appear on API ${Build.VERSION.SDK_INT}", shown)

    data class ComposerDraw(
        val nodeId: Int,
        val layoutInfo: LayoutInfo,
        val bounds: Rect,
        val frame: KeyboardGeometryStability.Frame,
    )
    fun Rect.validComposerBounds(): Boolean = !isEmpty && bottom > 0f &&
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    data class OverlapObservation(val composerBottom: Float, val keyboardTop: Int) {
        val message: String
            get() = "$targetTag bottom $composerBottom must be above keyboard top $keyboardTop"
    }
    var overlap: OverlapObservation? = null
    val samples = ConcurrentLinkedQueue<ComposerDraw>()
    val stability = KeyboardGeometryStability()
    var composerNode: SemanticsNode? = null
    var lastDraw: ComposerDraw? = null
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
            val bounds = node.boundsInWindow
            val frame = KeyboardGeometryStability.Frame(
                lastVsyncTimeNanos,
                if (node.layoutInfo.isAttached && bounds.validComposerBounds()) bounds.bottom else 0f,
                root.height - bottom,
                bottom,
                root.height,
                insets?.isVisible(WindowInsetsCompat.Type.ime()) == true,
                imeAnimations.isNotEmpty(),
                animationGeneration,
            )
            samples.add(ComposerDraw(node.id, node.layoutInfo, bounds, frame))
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
                val draw = samples.poll() ?: break
                val previous = lastDraw
                if (draw.frame.timeNanos > (previous?.frame?.timeNanos ?: -1L)) {
                    if (previous != null && (draw.nodeId != previous.nodeId ||
                            draw.layoutInfo !== previous.layoutInfo || draw.bounds != previous.bounds)) {
                        stability.reset()
                    }
                    lastDraw = draw
                    stable = stability.observe(draw.frame)
                }
            }
            // Resolve the current semantics node, rather than trust a cached node
            // whose detached coordinator can return Rect.Zero. Any replacement or
            // geometry change requires new drawn evidence before it can pass.
            val fresh = compose.onNodeWithTag(targetTag).fetchSemanticsNode()
            scenario.onActivity {
                val bounds = fresh.boundsInWindow
                val insets = ViewCompat.getRootWindowInsets(root)
                val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val draw = lastDraw
                val matchesDraw = draw != null && fresh.layoutInfo.isAttached &&
                    bounds.validComposerBounds() && fresh.id == draw.nodeId &&
                    fresh.layoutInfo === draw.layoutInfo && bounds == draw.bounds &&
                    root.height == draw.frame.windowHeight && bottom == draw.frame.imeBottom &&
                    insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    imeAnimations.isEmpty() && draw.frame.animationGeneration == animationGeneration
                stable = stable && matchesDraw
                if (stable) {
                    // Record the exact fresh-node observation here, but assert on
                    // the test thread so a failure is reported as a JUnit assertion.
                    // Stability ends the wait even when this observation is covered.
                    overlap = OverlapObservation(bounds.bottom, root.height - bottom)
                } else {
                    if (!matchesDraw) {
                        stability.reset()
                        samples.clear()
                    }
                    composerNode = fresh
                    root.postInvalidateOnAnimation()
                }
            }
            stable
        }
        val observed = checkNotNull(overlap)
        Log.i("KeyboardAssertions", observed.message)
        assertTrue(observed.message, observed.composerBottom <= observed.keyboardTop + 1f)
    } finally {
        scenario.onActivity {
            root.viewTreeObserver.removeOnDrawListener(drawListener)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        Log.i(
            "KeyboardAssertions",
            "animationObserved=$animationObserved stableRenderedFrames=${stability.stableFrames} " +
                "composerBottom=${lastDraw?.frame?.composerBottom} keyboardTop=${lastDraw?.frame?.keyboardTop}",
        )
    }
}
