package com.whumpo.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    val paneState = rememberChatPaneState(screen.chat)
    val compactForm = form != null && host != null && zones != null && width != WidthClass.Expanded
    val boundaryFocus = remember { FocusRequester() }
    var formWasOpen by remember { mutableStateOf(false) }
    val focusBoundary = if (width != WidthClass.Expanded) Modifier.focusRequester(boundaryFocus).focusGroup() else Modifier
    Box(modifier.then(focusBoundary)) {
        if (compactForm) {
            // Covered Chat controls must leave both keyboard traversal and the accessibility tree.
            FormPaneView(checkNotNull(form), checkNotNull(zones), checkNotNull(host), Modifier.fillMaxSize())
        } else AdaptivePanes(
            width = width,
            list = { ChatPane(screen, actions, animated = animated, onOpenPreview = onOpenPreview, onUpdateApp = onUpdateApp, onHelp = onHelp, paneState = paneState) },
            detail = {
                if (form != null && host != null && zones != null) {
                    FormPaneView(form, zones, host)
                } else if (flow != null) {
                    ReviewPane(flow, actions, onOpenPreview)
                } else {
                    DetailPlaceholder(hint = "Reviews open here, next to your conversation.")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
    // A completed read replaces Loading/Failed controls; answer edits keep the same pane type.
    LaunchedEffect(compactForm, width, form?.interactionId, form?.let { it::class }) {
        if (width != WidthClass.Expanded && (compactForm || formWasOpen)) boundaryFocus.requestFocus()
        formWasOpen = compactForm
    }
    if (flow != null && width != WidthClass.Expanded && !compactForm) ReviewBottomSheet(flow, actions, onOpenPreview)
}
