package com.whumpo.android.ui.chat.forms

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog

@Composable
internal actual fun FormPickerDialogWindow(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, content = content)
}
