package com.whumpo.android.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.common.ageLabel
import com.whumpo.android.ui.state.ErrorCard
import com.whumpo.android.ui.state.OfflineBanner
import com.whumpo.android.ui.state.Skeleton
import com.whumpo.android.ui.state.UpdateRequiredScreen
import com.whumpo.android.ui.state.WhumpoIcons
import com.whumpo.android.ui.state.WhumpoMascot
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.core.api.ChatTurn
import com.whumpo.core.api.PreviewRef
import kotlin.time.Clock
import kotlin.time.Instant

/** Everything the Chat screen can ask for. The default bodies do nothing so previews and tests can pass [NoChatActions]. */
interface ChatActions {
    fun onDraftChanged(text: String) {}
    fun onSend() {}
    fun onRetry() {}
    fun onCheckSend() {}
    fun onSendAgain() {}
    fun onDiscardUnsent() {}
    fun onOpenReview(turnId: String) {}
    fun onConfirmReview() {}
    fun onDismissReview() {}
}

object NoChatActions : ChatActions

/** The draft state (account-scoped, protected) joined with the conversation state. */
data class ChatScreenState(
    val signedIn: Boolean = false,
    val draft: String = "",
    val composerEnabled: Boolean = false,
    val chat: ChatState = ChatState(),
    /** For relative times. The ViewModel refreshes it with every state change; tests pin it. */
    val now: Instant = Clock.System.now(),
) {
    val canSend: Boolean
        get() = composerEnabled && draft.isNotBlank() && draft.trim().length <= ChatSession.MAX_MESSAGE_CHARS &&
            !chat.offline && chat.outbound == null && !chat.replyPending && chat.phase is Phase.Ready
}

/** Starter prompts only fill the draft. The owner still reads and sends it. */
val StarterPrompts = listOf(
    "Update the opening hours on my website",
    "Add a new service with a price",
    "Show me what changed this week",
)

private const val EMPTY_PROMPT = "What can I help with today?"

/**
 * The conversation pane: title, state components, transcript, composer, and the polite live region.
 * The review sheet is separate ([ReviewBottomSheet] on compact and medium widths, [ReviewPane] beside this on expanded).
 */
@Composable
fun ChatPane(
    screen: ChatScreenState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    onOpenPreview: (PreviewRef) -> Unit = {},
    onUpdateApp: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val chat = screen.chat
    HapticCueEffect(chat.haptic)
    Column(modifier.fillMaxSize().imePadding()) {
        Text(
            "Chat",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() },
        )
        if (chat.offline) OfflineBanner("You're offline. You can read, but sending waits until you're back.")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val phase = chat.phase
            when {
                !screen.signedIn -> SignedOutNotice()
                phase is Phase.Loading -> Skeleton("Loading your conversation", Modifier.padding(horizontal = 20.dp), animated = animated)
                phase is Phase.Failed -> ErrorCard(phase.what, phase.safe, actions::onRetry, Modifier.padding(horizontal = 20.dp))
                phase is Phase.UpdateRequired -> UpdateRequiredScreen(onUpdate = onUpdateApp, onHelp = onHelp)
                chat.turns.isEmpty() && chat.outbound == null -> EmptyConversation(actions)
                else -> Transcript(chat, screen.now, actions, animated, onOpenPreview)
            }
        }
        chat.notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("chat-notice").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // The composer stays while the conversation is unavailable: the draft is protected and is never lost to an outage.
        if (screen.signedIn) Composer(screen, actions)
        // Invisible polite live region: new replies and review outcomes are read out once. It only exists while it has words,
        // because an empty one fails the accessibility check for speakable text.
        if (chat.announcement.isNotEmpty()) {
            Box(
                Modifier.size(1.dp).testTag("chat-announcement").semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = chat.announcement
                },
            )
        }
    }
}

@Composable
private fun HapticCueEffect(cue: HapticCue?) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(cue?.seq) {
        when (cue?.kind) {
            HapticKind.Confirm -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            HapticKind.Reject -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
    }
}

@Composable
private fun SignedOutNotice() {
    Card(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("unavailable-sign_in_required"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            "Sign in to chat with Whumpo. Your unsent message stays on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun EmptyConversation(actions: ChatActions) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp).testTag("chat-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        WhumpoMascot(size = 96.dp)
        Text(EMPTY_PROMPT, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        StarterPrompts.forEachIndexed { index, prompt ->
            WhumpoSecondaryButton(
                onClick = { actions.onDraftChanged(prompt) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-starter-$index"),
            ) { Text(prompt) }
        }
    }
}

@Composable
private fun Transcript(chat: ChatState, now: Instant, actions: ChatActions, animated: Boolean, onOpenPreview: (PreviewRef) -> Unit) {
    val listState = rememberLazyListState()
    val extras = (if (chat.outbound != null) 1 else 0) + (if (chat.typing) 1 else 0)
    val count = chat.turns.size + extras
    LaunchedEffect(count) { if (count > 0) listState.scrollToItem(count - 1) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("chat-transcript"),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(chat.turns, key = { it.id }) { turn -> TurnItem(turn, now, actions, onOpenPreview) }
        chat.outbound?.let { out -> item(key = "outbound") { OutboundItem(out, actions) } }
        if (chat.typing) item(key = "typing") { TypingIndicator(animated) }
    }
}

@Composable
private fun TurnItem(turn: ChatTurn, now: Instant, actions: ChatActions, onOpenPreview: (PreviewRef) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bubble(turn.userText, mine = true)
        // Relative like Projects and Activity; TalkBack reads the absolute UTC time too.
        val sent = ageLabel(turn.createdAt, now)
        Text(
            sent.text,
            style = MaterialTheme.typography.bodySmall,
            color = WhumpoTheme.colors.inkMuted,
            modifier = Modifier.align(Alignment.End).testTag("turn-time-${turn.id}").clearAndSetSemantics { contentDescription = sent.description },
        )
        turn.assistantText?.let { Bubble(it, mine = false) }
        when (turn.status) {
            "failed" -> Note("Whumpo couldn't reply to this. Nothing was changed.")
            "uncertain" -> {
                Note("We can't tell yet whether this finished.")
                WhumpoQuietButton(actions::onRetry, Modifier.heightIn(min = 48.dp)) { Text("Check again") }
            }
        }
        turn.review?.let { ReviewCard(turn, actions, onOpenPreview) }
        turn.interaction?.let { InteractionCard(it.state) }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = WhumpoTheme.colors.inkMuted, modifier = Modifier.padding(start = 36.dp))
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            WhumpoMascot(size = 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (mine) scheme.onPrimaryContainer else scheme.onSurface,
            modifier = Modifier.weight(1f, fill = false).semantics { stateDescription = if (mine) "You" else "Whumpo" },
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/** A message the owner sent that has no confirmed turn yet. */
@Composable
private fun OutboundItem(out: Outbound, actions: ChatActions) {
    Column(Modifier.fillMaxWidth().testTag("chat-outbound"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Bubble(out.content, mine = true)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            when (out.status) {
                SendStatus.Sending -> Note("Sending...")
                SendStatus.Unknown -> {
                    Note("Didn't hear back yet.")
                    WhumpoQuietButton(actions::onCheckSend, Modifier.heightIn(min = 48.dp).testTag("outbound-check")) { Text("Check again") }
                }
                SendStatus.NotCommitted -> {
                    Note("Not sent. Nothing was saved.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WhumpoSecondaryButton(actions::onSendAgain, Modifier.heightIn(min = 48.dp).testTag("outbound-resend")) { Text("Send again") }
                        WhumpoQuietButton(actions::onDiscardUnsent, Modifier.heightIn(min = 48.dp)) { Text("Discard") }
                    }
                }
            }
        }
    }
}

/** Three dots (opacity only) and the words. The words are the live region, so reduced motion loses nothing. */
@Composable
fun TypingIndicator(animated: Boolean, modifier: Modifier = Modifier) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.testTag("chat-typing").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WhumpoMascot(size = 28.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val transition = if (animated) rememberInfiniteTransition(label = "typing") else null
            repeat(3) { index ->
                val alpha = transition?.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 150), RepeatMode.Reverse),
                    label = "typing-dot-$index",
                )?.value ?: 0.6f
                Box(Modifier.size(8.dp).alpha(alpha).background(dot, CircleShape))
            }
        }
        Text("Whumpo is working on it.", style = MaterialTheme.typography.bodyMedium, color = WhumpoTheme.colors.inkMuted)
    }
}

@Composable
private fun Composer(screen: ChatScreenState, actions: ChatActions) {
    val hint = when {
        screen.chat.phase !is Phase.Ready -> "Your draft is saved on this device. Sending starts when Whumpo is reachable."
        screen.chat.offline -> "You're offline. Reconnect to send."
        screen.draft.trim().length > ChatSession.MAX_MESSAGE_CHARS -> "Messages can be up to ${ChatSession.MAX_MESSAGE_CHARS} characters."
        screen.chat.replyPending -> "Whumpo is replying. You can send again in a moment."
        screen.draft.isBlank() -> "Write a message to send it."
        else -> null
    }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = screen.draft,
                    onValueChange = actions::onDraftChanged,
                    enabled = screen.composerEnabled && screen.chat.outbound == null,
                    label = { Text("Message") },
                    maxLines = 5,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat-composer")
                        .onPreviewKeyEvent { event ->
                            val send = event.type == KeyEventType.KeyDown && event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed)
                            if (send && screen.canSend) actions.onSend()
                            send
                        },
                )
                WhumpoPrimaryButton(actions::onSend, Modifier.heightIn(min = 48.dp).testTag("chat-send"), enabled = screen.canSend) { Text("Send") }
            }
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp).testTag("chat-hint"))
            }
        }
    }
}

/** An interaction whose payload is still raw JSON (WH-105). Nothing from it is rendered or sent. */
@Composable
fun InteractionCard(state: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().padding(start = 36.dp).testTag("interaction-generic"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(WhumpoIcons.Chat, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Whumpo has a form for you", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Answering forms in the app is coming soon. Finish this one on the Whumpo website. Status: $state.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
