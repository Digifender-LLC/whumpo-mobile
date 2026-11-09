package com.whumpo.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormPaneView
import com.whumpo.android.ui.chat.forms.ZoneTime
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
    formActions: FormActions? = null,
    zones: ZoneTime? = null,
) {
    val flow = screen.chat.review
    val form = screen.form
    val host = formActions?.let { a -> object : FormActions by a { override fun onUpdate() = onUpdateApp() } }
    Box(modifier) {
        AdaptivePanes(
            width = width,
            list = { ChatPane(screen, actions, animated = animated, onOpenPreview = onOpenPreview, onUpdateApp = onUpdateApp, onHelp = onHelp) },
            detail = {
                if (form != null && host != null && zones != null) {
                    FormPaneView(form, zones, host)
                } else if (flow != null) {
                    ReviewPane(flow, actions, onOpenPreview)
                } else {
                    DetailPlaceholder(hint = "Reviews and forms open here, next to your conversation.")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Compact widths: the form takes the full height over the conversation.
        if (form != null && host != null && zones != null && width != WidthClass.Expanded) FormPaneView(form, zones, host, Modifier.fillMaxSize())
    }
    if (flow != null && width != WidthClass.Expanded) ReviewBottomSheet(flow, actions, onOpenPreview)
}
