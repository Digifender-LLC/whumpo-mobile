package com.whumpo.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect as AndroidRect
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Dialogs wrap their content: compare actual window screen coordinates, not activity/decor heights. */
internal fun assertDialogConfirmAboveKeyboard(compose: ComposeTestRule, input: SemanticsNodeInteraction, targetTag: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val serviceInfo = automation.serviceInfo
    val originalFlags = serviceInfo.flags
    serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    automation.serviceInfo = serviceInfo
    var root: View? = null
    val animations = mutableSetOf<WindowInsetsAnimationCompat>()
    var animationGeneration = 0
    var node: SemanticsNode? = null
    var currentIme: AndroidRect? = null
    var frameTime = 0L
    data class Draw(val id: Int, val layout: LayoutInfo, val bounds: Rect, val originY: Int, val frame: KeyboardGeometryStability.Frame)
    val draws = ConcurrentLinkedQueue<Draw>()
    val stability = KeyboardGeometryStability()
    var lastDraw: Draw? = null
    val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameTime = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    val drawListener = ViewTreeObserver.OnDrawListener {
        val target = node
        val ime = currentIme
        val window = root
        if (target != null && ime != null && window != null && frameTime > 0) {
            val origin = IntArray(2).also(window::getLocationOnScreen)
            val bounds = target.boundsInWindow
            val bottom = if (target.layoutInfo.isAttached && !bounds.isEmpty) bounds.bottom + origin[1] else 0f
            draws.add(Draw(target.id, target.layoutInfo, bounds, origin[1], KeyboardGeometryStability.Frame(
                frameTime, bottom, ime.top, ime.height(), ime.bottom, !ime.isEmpty, animations.isNotEmpty(), animationGeneration,
            )))
        }
    }
    fun keyboard(): AndroidRect? = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { window -> AndroidRect().also(window::getBoundsInScreen).takeUnless { it.isEmpty } }
    try {
        onView(isRoot()).inRoot(isDialog()).check { view, missing ->
            if (missing != null) throw missing
            root = checkNotNull(view).rootView
            assertEquals("Dialog must use actual 200% system text", 2f, checkNotNull(root).resources.configuration.fontScale, 0.01f)
        }
        val window = checkNotNull(root)
        instrumentation.runOnMainSync {
            ViewCompat.setWindowInsetsAnimationCallback(window, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) { animationGeneration++; animations.add(animation) }
                }
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: MutableList<WindowInsetsAnimationCompat>) = insets
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) { animationGeneration++; animations.remove(animation) }
                }
            })
            window.viewTreeObserver.addOnDrawListener(drawListener)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        input.performClick()
        // No new API28 exemption: this test proves the actual IME on every inventoried device.
        compose.waitUntil(10_000) { keyboard() != null }
        compose.onNodeWithTag(targetTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        var confirmed: KeyboardGeometryStability.Frame? = null
        compose.waitUntil(10_000) {
            var stable = false
            while (true) {
                val draw = draws.poll() ?: break
                val previous = lastDraw
                if (previous != null && (draw.id != previous.id || draw.layout !== previous.layout || draw.bounds != previous.bounds || draw.originY != previous.originY)) stability.reset()
                lastDraw = draw
                stable = stability.observe(draw.frame)
            }
            val fresh = compose.onNodeWithTag(targetTag).fetchSemanticsNode()
            val ime = keyboard()
            instrumentation.runOnMainSync {
                val origin = IntArray(2).also(window::getLocationOnScreen)
                val draw = lastDraw
                val matches = draw != null && ime != null && fresh.layoutInfo.isAttached && !fresh.boundsInWindow.isEmpty &&
                    fresh.id == draw.id && fresh.layoutInfo === draw.layout && fresh.boundsInWindow == draw.bounds && origin[1] == draw.originY &&
                    ime.top == draw.frame.keyboardTop && ime.height() == draw.frame.imeBottom && ime.bottom == draw.frame.windowHeight &&
                    animations.isEmpty() && animationGeneration == draw.frame.animationGeneration
                stable = stable && matches
                if (stable) confirmed = draw!!.frame
                else {
                    if (!matches) { stability.reset(); draws.clear() }
                    node = fresh
                    currentIme = ime
                    window.postInvalidateOnAnimation()
                }
            }
            stable
        }
        val observed = checkNotNull(confirmed)
        Log.i("DialogKeyboardAssertions", "$targetTag bottom=${observed.composerBottom} keyboardTop=${observed.keyboardTop} stableRenderedFrames=${stability.stableFrames}")
        assertTrue("$targetTag must be above the actual keyboard", observed.composerBottom <= observed.keyboardTop + 1f)
    } finally {
        instrumentation.runOnMainSync {
            root?.let { window ->
                window.viewTreeObserver.removeOnDrawListener(drawListener)
                ViewCompat.setWindowInsetsAnimationCallback(window, null)
            }
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        serviceInfo.flags = originalFlags
        automation.serviceInfo = serviceInfo
    }
}
