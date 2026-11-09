package com.whumpo.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.core.view.allViews
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * The platform accessibility tree, which is what TalkBack reads, rather than Compose semantics: the four destination
 * focus stops have tab roles, names and selection state, each screen has its title as a heading, nothing actionable is unlabelled,
 * and moving between tabs uses the accessibility click action TalkBack sends on a double tap. This proves what TalkBack
 * is given, not what it speaks: speech on a device is still manual evidence (docs/android-build.md).
 */
@RunWith(AndroidJUnit4::class)
class PlatformAccessibilityTreeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val testName = TestName()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val titles = listOf("Chat", "Projects", "Activity", "Settings")
    private var previousServiceFlags: Int? = null
    private var accessibilityRoots = emptyList<RootForTest>()

    @Before
    fun signedIn() {
        val info = automation.serviceInfo
        previousServiceFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        Fixtures.reset()
        Fixtures.signIn()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav-chat").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            accessibilityRoots = compose.activity.window.decorView.allViews.filterIsInstance<RootForTest>().toList()
            assertTrue("Compose accessibility roots exist", accessibilityRoots.isNotEmpty())
            // UiAutomation suppresses the enabled-service list that Compose uses to enable events.
            accessibilityRoots.forEach { it.forceAccessibilityForTesting(true) }
        }
    }

    @After
    fun signedOut() {
        try {
            Fixtures.reset()
        } finally {
            try {
                compose.runOnUiThread { accessibilityRoots.forEach { it.forceAccessibilityForTesting(false) } }
            } finally {
                previousServiceFlags?.let { flags ->
                    automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                }
            }
        }
    }

    private fun appNodes(): List<AccessibilityNodeInfo> {
        val pkg = Fixtures.app.packageName
        val root = automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg }
            ?: automation.rootInActiveWindow?.takeIf { it.packageName == pkg }
            ?: return emptyList()
        // Depth-first, in child order, without making a claim about TalkBack's traversal order.
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        return all
    }

    private fun AccessibilityNodeInfo.spoken(): String =
        listOfNotNull(text, contentDescription, hintText, AccessibilityNodeInfoCompat.wrap(this).stateDescription)
            .joinToString(" ").trim()

    /**
     * Labels within a focus stop's subtree. Compose exposes the label and role as non-focusable descendants of the tab.
     * This checks their presence, not TalkBack's precise announcement or traversal algorithm.
     */
    private fun AccessibilityNodeInfo.announcement(): String {
        val parts = mutableListOf(spoken())
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            if (!child.isFocusStop()) parts += child.announcement()
        }
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun AccessibilityNodeInfo.isFocusStop() = isClickable || isLongClickable || isEditable || isScreenReaderFocusable

    private fun AccessibilityNodeInfo.role(): String? = AccessibilityNodeInfoCompat.wrap(this).roleDescription?.toString()

    private fun describe(n: AccessibilityNodeInfo): String {
        var depth = 0
        var p = n.parent
        while (p != null) { depth++; p = p.parent }
        val flags = listOfNotNull("click".takeIf { n.isClickable }, "sel".takeIf { n.isSelected }, "srf".takeIf { n.isScreenReaderFocusable }, "head".takeIf { n.isHeading })
        return "\n" + " ".repeat(depth) + "${n.className?.toString()?.substringAfterLast('.')}|${n.role()}|${n.spoken()}|$flags"
    }

    /** A role within the focus stop, without crossing into another independently focusable control. */
    private fun AccessibilityNodeInfo.stopRole(): String? {
        role()?.let { return it }
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            if (!child.isFocusStop()) child.stopRole()?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.isTab() = isFocusStop() && stopRole() == "Tab"

    private fun tabs() = appNodes().filter { it.isTab() }

    private fun withDiagnostics(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            // Capture before @After signs out. Do not log node text, credentials or account state.
            runCatching {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val power = context.getSystemService(android.os.PowerManager::class.java)
                val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                fun root(node: AccessibilityNodeInfo?) = node?.let {
                    "package=${it.packageName}, class=${it.className}, children=${it.childCount}"
                } ?: "null"
                android.util.Log.e("WhumpoTreeDiagnostics",
                    "test=${testName.methodName}, target=${context.packageName}, interactive=${power?.isInteractive}, locked=${keyguard?.isKeyguardLocked}, " +
                        "serviceFlags=${automation.serviceInfo.flags}, activeRoot=${root(automation.rootInActiveWindow)}")
                automation.windows.forEach { window ->
                    val node = window.root
                    val systemRoot = node?.takeIf {
                        window.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.packageName?.toString() == "android"
                    }
                    fun hasSystemControl(id: String) = runCatching {
                        systemRoot?.findAccessibilityNodeInfosByViewId(id)?.isNotEmpty()
                    }.getOrNull()
                    android.util.Log.e("WhumpoTreeDiagnostics",
                        "window=${window.id}, type=${window.type}, active=${window.isActive}, focused=${window.isFocused}, root=${root(node)}, " +
                            "anrControls=${hasSystemControl("android:id/aerr_wait")}, crashControls=${hasSystemControl("android:id/aerr_app_info")}")
                }
            }.onFailure {
                runCatching { android.util.Log.e("WhumpoTreeDiagnostics", "Capture unavailable: ${it.javaClass.name}") }
            }
            throw failure
        }
    }

    private fun waitForTree(what: String, condition: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        var nodes = emptyList<AccessibilityNodeInfo>()
        runCatching { compose.waitUntil(10_000) { nodes = appNodes(); condition(nodes) } }
        assertTrue("$what; tree: ${nodes.map { describe(it) }}", condition(nodes))
        return nodes
    }

    @Test
    fun interactiveWindowsIncludeTheApp() = withDiagnostics {
        assertTrue("interactive window retrieval is enabled",
            automation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        compose.waitUntil(10_000) {
            automation.windows.any { it.root?.packageName == Fixtures.app.packageName }
        }
    }

    @Test
    fun navigationEmitsPlatformSubtreeUpdates() = withDiagnostics {
        compose.waitForIdle()
        automation.waitForIdle(500, 5_000)
        val projects = waitForTree("one Projects tab") { nodes ->
            nodes.count { it.isTab() && it.announcement().contains("Projects") } == 1
        }.first { it.isTab() && it.announcement().contains("Projects") }
        assertTrue("Projects starts unselected", !projects.isSelected)
        var eventCount = 0
        var appEventCount = 0
        var appEventTypes = 0
        var appContentChanges = 0
        try {
            automation.executeAndWaitForEvent(
                {
                    assertTrue("Projects accepts the accessibility click", projects.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    compose.waitForIdle()
                },
                { event ->
                    eventCount++
                    if (event.packageName == Fixtures.app.packageName) {
                        appEventCount++
                        appEventTypes = appEventTypes or event.eventType
                        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                            appContentChanges = appContentChanges or event.contentChangeTypes
                        }
                    }
                    event.packageName == Fixtures.app.packageName &&
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0
                },
                10_000,
            ).recycle()
        } catch (_: TimeoutException) {
            // UiAutomation's timeout message contains an event dump; keep it out of test reports.
            runCatching {
                android.util.Log.e("WhumpoTreeDiagnostics",
                    "test=${testName.methodName}, eventCount=$eventCount, appEventCount=$appEventCount, " +
                        "appEventTypes=$appEventTypes, appContentChanges=$appContentChanges")
            }
            throw AssertionError("No app subtree update after Projects accessibility click")
        }
        waitForTree("Projects is the only selected tab after the platform update") { nodes ->
            val tabs = nodes.filter { it.isTab() }
            tabs.size == titles.size &&
                tabs.singleOrNull { it.isSelected }?.announcement()?.contains("Projects") == true
        }
    }

    @Test
    fun destinationsAreNamedSelectableTabsReachedByTheAccessibilityClickAction() = withDiagnostics {
        titles.forEach { title ->
            val tab = waitForTree("one tab named $title") { nodes ->
                nodes.count { it.isTab() && it.announcement().contains(title) } == 1
            }.first { it.isTab() && it.announcement().contains(title) }
            assertTrue("$title tab is visible", tab.isVisibleToUser)
            // Compose offers no click on the tab that is already selected.
            if (!tab.isSelected) {
                assertTrue("$title tab offers a click", tab.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                assertTrue("$title tab click was accepted", tab.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }

            waitForTree("$title is the only selected tab") { nodes ->
                val t = nodes.filter { it.isTab() }
                t.size == titles.size && t.singleOrNull { it.isSelected }?.announcement()?.contains(title) == true
            }
            waitForTree("$title is announced as a heading") { nodes ->
                nodes.any { it.isHeading && it.text?.toString() == title }
            }
        }
        assertEquals(titles, tabs().map { tab -> titles.first { tab.announcement().contains(it) } })
    }

    @Test
    fun everyActionableNodeHasSomethingToSay() = withDiagnostics {
        titles.forEach { title ->
            tabs().first { it.announcement().contains(title) }.takeIf { !it.isSelected }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val nodes = waitForTree("$title heading") { all -> all.any { it.isHeading && it.text?.toString() == title } }
            val silent = nodes.filter { (it.isClickable || it.isLongClickable || it.isEditable) && it.isVisibleToUser }
                .filter { it.announcement().isEmpty() }
            assertEquals("unlabelled actionable nodes on $title", emptyList<String>(), silent.map { "${it.className} ${it.viewIdResourceName}" })
        }
    }

    @Test
    fun theComposerIsAnEditableFieldNamedMessage() = withDiagnostics {
        val nodes = waitForTree("an editable composer") { all -> all.any { it.isEditable } }
        val composer = nodes.single { it.isEditable }
        assertTrue("composer says Message: '${composer.announcement()}'", composer.announcement().contains("Message"))
        assertTrue(composer.isFocusable)
    }
}
