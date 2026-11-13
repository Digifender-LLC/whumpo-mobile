package com.whumpo.android

import android.os.ParcelFileDescriptor
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.node.RootForTest
import androidx.core.view.allViews
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FieldKind
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormDefinition
import com.whumpo.android.ui.chat.forms.FormField
import com.whumpo.android.ui.chat.forms.FormRead
import com.whumpo.android.ui.chat.forms.FormScreen
import com.whumpo.android.ui.chat.forms.FormScreenState
import com.whumpo.android.ui.chat.forms.FormState
import com.whumpo.android.ui.chat.forms.JavaZoneTime
import com.whumpo.android.ui.chat.ChatActions
import com.whumpo.android.ui.chat.ChatLayout
import com.whumpo.android.ui.chat.ChatScreenState
import com.whumpo.android.ui.chat.ChatState
import com.whumpo.android.ui.chat.Phase
import com.whumpo.android.ui.chat.forms.FormPane
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.core.api.ChatTurn
import com.whumpo.core.api.InteractionRef
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native form on a device: typed input, 200% text, accessibility checks and the soft keyboard. Synthetic and
 * backend-free; semantics and ATF checks are not physical TalkBack acceptance.
 */
@RunWith(AndroidJUnit4::class)
class FormDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    /** Hosts the form in [MainActivity] so the window keeps the app's edge-to-edge and adjustResize setup. */
    @Before
    fun setUp() {
        Fixtures.reset()
        Fixtures.signIn()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        Fixtures.reset()
    }

    private class Actions : FormActions {
        var answers by mutableStateOf(emptyMap<String, Answer>())
        val calls = mutableListOf<String>()
        override fun onAnswer(fieldId: String, answer: Answer?) {
            answers = if (answer == null) answers - fieldId else answers + (fieldId to answer)
        }
        override fun onSubmit() { calls += "submit" }
        override fun onSaveDraft() { calls += "save" }
        override fun onCheckAgain() { calls += "check" }
        override fun onSendAgain() { calls += "send-again" }
        override fun onReload() { calls += "reload" }
        override fun onLoadMoreResources(fieldId: String) { calls += "more:$fieldId" }
        override fun onUseNewerAnswers() { calls += "use-newer" }
        override fun onKeepMyAnswers() { calls += "keep-mine" }
        override fun onClose() { calls += "close" }
        override fun onUpdate() { calls += "update" }
        override fun onCancel() { calls += "cancel" }
        override fun onFieldBlur(fieldId: String) { calls += "blur:$fieldId" }
        override fun onContinue() { calls += "continue" }
        override fun onCheckContinuation() { calls += "continuation-check" }
        override fun onStopContinuation() { calls += "continuation-stop" }
        override fun onOpenContinuationResult() { calls += "continuation-result" }
    }

    private val fieldIds = listOf("name", "notes", "ready")
    private val form = FormRead.Form(
        interactionId = "66666666-6666-4666-8666-0000000000d1",
        definitionDigest = "d".repeat(64),
        definition = FormDefinition(
            "Synthetic form",
            "Synthetic summary",
            listOf(
                FormField("name", "Synthetic name", "Synthetic help", true, FieldKind.Text(80, long = false)),
                FormField("notes", "Synthetic notes", "", false, FieldKind.Text(500, long = true)),
                FormField("ready", "Synthetic yes or no", "", false, FieldKind.YesNo),
            ),
        ),
        state = FormState.Open,
        answerRevision = 0,
        draft = null,
        replacementInteractionId = null,
        submission = null,
    )

    private fun show(fontScale: Float = 1f, displayedForm: FormRead.Form = form, initialAnswers: Map<String, Answer> = emptyMap()): Actions {
        val actions = Actions().apply { answers = initialAnswers }
        scenario.onActivity { activity ->
            activity.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    WhumpoTheme { FormScreen(FormScreenState(displayedForm, actions.answers), JavaZoneTime, actions) }
                }
            }
        }
        compose.waitForIdle()
        return actions
    }

    @Test
    fun typedAnswersSendNothingUntilSend() {
        val actions = show()
        compose.onNodeWithTag("form-field-name").performTextInput("Synthetic answer")
        compose.waitForIdle()
        assertEquals(Answer.Text("Synthetic answer"), actions.answers["name"])
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithTag("form-send").performClick()
        assertEquals(listOf("submit"), actions.calls.filterNot { it.startsWith("blur:") })
    }

    @Test
    fun compactFormRemovesCoveredChatFromPlatformTreeAndKeyboardTraversal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        var roots = emptyList<RootForTest>()
        val transcript = "Covered synthetic transcript"
        val draft = "Protected synthetic chat draft"
        val turn = ChatTurn("55555555-5555-4555-8555-0000000000d1", 1, "completed", "guided",
            "2030-01-01T00:00:00.000Z", transcript, null, null, InteractionRef(form.interactionId, "open"))
        var state by mutableStateOf(ChatScreenState(signedIn = true, draft = draft, composerEnabled = true,
            chat = ChatState(phase = Phase.Ready, turns = listOf(turn)), now = Instant.parse("2030-01-01T00:01:00Z")))
        val actions = Actions()
        val formActions = object : FormActions by actions {
            override fun onClose() { state = state.copy(form = null) }
        }
        val chatActions = object : ChatActions {
            override fun onOpenForm(interactionId: String) {
                state = state.copy(form = FormPane.Loading(form.interactionId))
            }
        }
        fun treeText(): List<String> {
            val root = automation.windows.mapNotNull { it.root }.firstOrNull { it.packageName == Fixtures.app.packageName }
                ?: automation.rootInActiveWindow?.takeIf { it.packageName == Fixtures.app.packageName }
                ?: return emptyList()
            val text = mutableListOf<String>()
            fun visit(node: AccessibilityNodeInfo) {
                text += listOfNotNull(node.text, node.contentDescription, node.hintText).joinToString(" ")
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            visit(root)
            return text
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            scenario.onActivity { activity ->
                activity.setContent { WhumpoTheme { ChatLayout(state, WidthClass.Compact, chatActions, animated = false, formActions = formActions, zones = JavaZoneTime) } }
            }
            compose.waitForIdle()
            scenario.onActivity { activity ->
                roots = activity.window.decorView.allViews.filterIsInstance<RootForTest>().toList()
                assertTrue("Compose accessibility roots exist", roots.isNotEmpty())
                roots.forEach { it.forceAccessibilityForTesting(true) }
            }
            compose.waitUntil(10_000) { treeText().any { transcript in it } && treeText().any { draft in it } }
            compose.onNodeWithTag("interaction-open").performClick()
            // Exercise the first real Tab after a touch-opened loading pane.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
            compose.onNodeWithTag("form-close").assertIsFocused()
            compose.runOnIdle { state = state.copy(form = FormPane.Shown(form.interactionId, FormScreenState(form))) }
            compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("form-screen")))
            compose.onNodeWithTag("chat-composer", useUnmergedTree = true).assertDoesNotExist()
            compose.waitUntil(10_000) {
                val tree = treeText()
                tree.any { "Synthetic form" in it } && tree.none { transcript in it || draft in it }
            }
            repeat(2) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                compose.waitForIdle()
                compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("form-screen")))
            }
            compose.onNodeWithTag("form-close").performClick()
            compose.onNodeWithTag("form-screen", useUnmergedTree = true).assertDoesNotExist()
            val returned = compose.onNode(isFocused()).assertIsDisplayed()
                .assert(hasTestTag("interaction-open") or hasTestTag("chat-composer"))
            val returnedId = returned.fetchSemanticsNode().id
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
            val next = compose.onNode(isFocused()).assertIsDisplayed()
                .assert(hasTestTag("interaction-open") or hasTestTag("chat-composer") or hasTestTag("chat-send"))
            assertNotEquals(returnedId, next.fetchSemanticsNode().id)
            compose.waitUntil(10_000) { treeText().any { transcript in it } && treeText().any { draft in it } }
            assertEquals(draft, state.draft)
            assertTrue(actions.calls.none { it == "submit" })
        } finally {
            try {
                scenario.onActivity { roots.forEach { it.forceAccessibilityForTesting(false) } }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            }
        }
    }

    @Test
    fun everyFieldAndActionIsReachableAtDoubleTextAndPassesAccessibilityChecks() {
        compose.enableAccessibilityChecks()
        show(fontScale = 2f)
        compose.onRoot().tryPerformAccessibilityChecks()
        fieldIds.forEach { compose.onNodeWithTag("form-field-$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("form-send").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("form-save").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("form-cancel").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun theActionBarStaysAboveTheSoftKeyboard() {
        show()
        assertAboveKeyboard(compose, scenario, "form-field-name", "form-actions")
    }

    @Test
    fun dateAndTimePickersRequireConfirmationWithoutSubmitting() {
        val dates = form.copy(definition = form.definition.copy(fields = listOf(
            FormField("date", "Date", "", true, FieldKind.Date),
            FormField("datetime", "Date and time", "", true, FieldKind.DateTime("UTC")),
        )))
        val initial = mapOf("date" to Answer.Text("2031-05-06"), "datetime" to Answer.Text("2032-04-08T10:15:00.000Z"))
        val actions = show(displayedForm = dates, initialAnswers = initial)
        compose.onNodeWithText("2031-05-06").performClick()
        compose.onNodeWithTag("form-date-picker").assertExists()
        assertEquals(initial, actions.answers)
        compose.onNodeWithTag("form-date-dismiss").performClick()
        assertEquals(initial, actions.answers)
        compose.onNodeWithText("10:15").performScrollTo().performClick()
        compose.onNodeWithTag("form-time-picker").assertExists()
        assertEquals(initial, actions.answers)
        compose.onNodeWithTag("form-time-confirm").performClick()
        assertEquals(initial, actions.answers)
        assertTrue(actions.calls.filterNot { it.startsWith("blur:") }.isEmpty())
    }

    @Test
    fun dateAndTimeInputConfirmAboveKeyboardAtActualDoubleText() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        val original = shell("settings get system font_scale")
        val restore = original.toFloatOrNull()?.let { "settings put system font_scale $it" } ?: "settings delete system font_scale"
        try {
            scenario.close()
            shell("settings put system font_scale 2.0")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) {
                var scale = 0f
                scenario.onActivity { scale = it.resources.configuration.fontScale }
                kotlin.math.abs(scale - 2f) < 0.01f
            }
            val dates = form.copy(definition = form.definition.copy(fields = listOf(
                FormField("datetime", "Date and time", "", true, FieldKind.DateTime("UTC")),
            )))
            val initial = mapOf("datetime" to Answer.Text("2031-05-06T07:08:00.000Z"))
            val actions = show(fontScale = 2f, displayedForm = dates, initialAnswers = initial)
            compose.onNodeWithText("2031-05-06").performScrollTo().performClick()
            assertDialogConfirmAboveKeyboard(compose, compose.onNode(hasSetTextAction()), "form-date-confirm")
            compose.onNodeWithTag("form-date-confirm").performClick()
            assertEquals(initial, actions.answers)
            compose.onNodeWithText("07:08").performScrollTo().performClick()
            assertDialogConfirmAboveKeyboard(compose, compose.onAllNodes(hasSetTextAction())[0], "form-time-confirm")
            compose.onNodeWithTag("form-time-confirm").performClick()
            assertEquals(initial, actions.answers)
            assertTrue(actions.calls.filterNot { it.startsWith("blur:") }.isEmpty())
        } finally {
            try { scenario.close() } finally {
                shell(restore)
                val restoredScale = original.toFloatOrNull() ?: 1f
                compose.waitUntil(10_000) {
                    kotlin.math.abs(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale - restoredScale) < 0.01f
                }
            }
        }
    }

}
