package com.whumpo.android.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whumpo.android.ui.shell.AdaptivePanes
import com.whumpo.android.ui.shell.DetailPlaceholder
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.core.api.PreviewRef

/**
 * Chat for one window width: the conversation, and the review beside it on expanded widths (the transcript stays visible)
 * or as a modal bottom sheet below that.
 */
@Composable
fun ChatLayout(
    screen: ChatScreenState,
    width: WidthClass,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    onOpenPreview: (PreviewRef) -> Unit = {},
    onUpdateApp: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val flow = screen.chat.review
    AdaptivePanes(
        width = width,
        list = { ChatPane(screen, actions, animated = animated, onOpenPreview = onOpenPreview, onUpdateApp = onUpdateApp, onHelp = onHelp) },
        detail = {
            if (flow != null) {
                ReviewPane(flow, actions, onOpenPreview)
            } else {
                DetailPlaceholder(hint = "Reviews open here, next to your conversation.")
            }
        },
        modifier = modifier,
    )
    if (flow != null && width != WidthClass.Expanded) ReviewBottomSheet(flow, actions, onOpenPreview)
}
