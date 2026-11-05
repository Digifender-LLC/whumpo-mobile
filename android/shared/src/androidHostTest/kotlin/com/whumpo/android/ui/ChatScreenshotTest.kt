package com.whumpo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.chat.ChatLayout
import com.whumpo.android.ui.chat.ChatPane
import com.whumpo.android.ui.chat.ChatScreenState
import com.whumpo.android.ui.chat.ChatState
import com.whumpo.android.ui.chat.NoChatActions
import com.whumpo.android.ui.chat.Outbound
import com.whumpo.android.ui.chat.Phase
import com.whumpo.android.ui.chat.ReviewFlow
import com.whumpo.android.ui.chat.ReviewSheetContent
import com.whumpo.android.ui.chat.ReviewStep
import com.whumpo.android.ui.chat.SendStatus
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.core.api.ChatPage
import com.whumpo.core.api.ChatTurn
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The conversation built from the frozen WP4 fixtures, shared by the golden and behaviour tests. */
internal object ChatScenes {
    private val page = Json.decodeFromString(ChatPage.serializer(), ChatFixtures.body("chat.read"))
    val turns: List<ChatTurn> = page.items
    private val specTurn = turns[1]
    val siteTurn = turns[2]

    fun screen(chat: ChatState, draft: String = ""): ChatScreenState =
        ChatScreenState(signedIn = true, draft = draft, composerEnabled = true, chat = chat, now = Instant.parse("2026-10-04T12:05:00Z"))

    val ready = ChatState(phase = Phase.Ready, turns = turns, revision = page.revision)

    fun flow(step: ReviewStep, message: String? = null, spec: Boolean = false): ReviewFlow {
        val turn = if (spec) specTurn else siteTurn
        return ReviewFlow(turn.id, turn.revision, checkNotNull(turn.review), "55555555-5555-4555-8555-000000000009", step, message)
    }
}

/**
 * Roborazzi goldens for Chat and the review sheet at 360dp (phone) and 1000dp (tablet), light and dark.
 * Animations are off so the images are deterministic. Record with `-Pscreenshots.record`; CI verifies.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChatScreenshotTest : RobolectricUiTest() {
    private val widths = listOf(Screen.Compact to WidthClass.Compact, Screen.Expanded to WidthClass.Expanded)

    private fun chat(state: ChatScreenState, width: WidthClass): @Composable () -> Unit = {
        ChatLayout(state, width, NoChatActions, animated = false)
    }

    private fun recordAll(name: String, scene: (WidthClass) -> @Composable () -> Unit) {
        for ((screen, width) in widths) for (dark in listOf(false, true)) capture(name, screen, dark, content = scene(width))
    }

    @Test
    fun chatStates() {
        val s = ChatScenes
        recordAll("chat-loading") { w -> chat(s.screen(ChatState(phase = Phase.Loading)), w) }
        recordAll("chat-empty") { w -> chat(s.screen(ChatState(phase = Phase.Ready)), w) }
        recordAll("chat-conversation") { w -> chat(s.screen(s.ready, draft = "Add a ceramic coating service for $300."), w) }
        recordAll("chat-typing") { w ->
            val pending = s.turns.last().copy(status = "pending", assistantText = null, review = null)
            chat(s.screen(s.ready.copy(turns = s.turns.dropLast(1) + pending)), w)
        }
        recordAll("chat-lost-reply") { w ->
            val lost = Outbound("55555555-5555-4555-8555-000000000001", "Add a booking page", 4, SendStatus.Unknown)
            chat(s.screen(s.ready.copy(outbound = lost)), w)
        }
        recordAll("chat-error") { w ->
            chat(s.screen(ChatState(phase = Phase.Failed("Couldn't reach Whumpo.", "Nothing was changed."))), w)
        }
        recordAll("chat-offline") { w -> chat(s.screen(s.ready.copy(offline = true)), w) }
    }

    /** On a phone the sheet is modal and lives in its own window, so the golden draws its content over a scrim. */
    @Composable
    private fun SheetOverChat(state: ChatScreenState, width: WidthClass) {
        val flow = checkNotNull(state.chat.review)
        if (width == WidthClass.Expanded) {
            ChatLayout(state, width, NoChatActions, animated = false)
        } else {
            Box(Modifier.fillMaxSize()) {
                ChatPane(state.copy(chat = state.chat.copy(review = null)), NoChatActions, animated = false)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) { ReviewSheetContent(flow, NoChatActions, {}) }
                }
            }
        }
    }

    @Test
    fun reviewSheet() {
        val s = ChatScenes
        fun sheet(name: String, flow: ReviewFlow) =
            recordAll(name) { w -> { SheetOverChat(s.screen(s.ready.copy(review = flow)), w) } }
        sheet("review-ready", s.flow(ReviewStep.Ready, spec = true))
        sheet("review-receipt", s.flow(ReviewStep.Done, "Published. Your site is live."))
        sheet("review-stale", s.flow(ReviewStep.NoLongerCurrent, "This review is no longer current. Nothing was changed."))
    }

    /** Play listing image 1: the end of the conversation with a draft; phones show one turn so no card is cut at the top. */
    @Test
    fun storeListing() {
        val s = ChatScenes
        for (device in StoreDevice.entries) for (dark in listOf(false, true)) {
            val width = WidthClass.of(device.widthDp.dp)
            val turns = s.turns.takeLast(if (device == StoreDevice.Phone) 1 else 2)
            captureStore("1-chat", device, dark) {
                ChatLayout(s.screen(s.ready.copy(turns = turns), draft = "Add a ceramic coating service for $300."), width, NoChatActions, animated = false)
            }
        }
    }
}
