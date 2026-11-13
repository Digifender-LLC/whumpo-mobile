package com.whumpo.android.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Composition-owned position; the conversation and draft remain in [ChatScreenState]. */
class ChatPaneState internal constructor(internal val list: LazyListState)

/** Keep position and one-shot effects mounted while a compact form replaces the conversation UI. */
@Composable
fun rememberChatPaneState(chat: ChatState): ChatPaneState {
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(chat.haptic?.seq) {
        when (chat.haptic?.kind) {
            HapticKind.Confirm -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            HapticKind.Reject -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
    }
    val missingChecks = chat.reviewChecks.count { check -> chat.turns.none { it.id == check.turnId } }
    val failure = chat.transcriptFailure ?: (chat.phase as? Phase.Failed)
    val count = chat.turns.size + missingChecks + (if (failure != null) 1 else 0) +
        (if (chat.outbound != null) 1 else 0) + (if (chat.typing) 1 else 0)
    LaunchedEffect(count, failure) { if (failure == null && count > 0) list.scrollToItem(count - 1) }
    // A new read failure exposes its retry; later list changes retain that position.
    LaunchedEffect(failure) { if (failure != null) list.scrollToItem(0) }
    return remember(list) { ChatPaneState(list) }
}
