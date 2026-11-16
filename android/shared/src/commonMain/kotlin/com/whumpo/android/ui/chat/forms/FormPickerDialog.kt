package com.whumpo.android.ui.chat.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun FormPickerDialog(
    modifier: Modifier,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    FormPickerDialogWindow(onDismissRequest) {
        FormMemoryOnlyContent {
            Surface(
                modifier.widthIn(max = 360.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column {
                    // Reserve space for the actions before measuring the scrollable picker.
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        content()
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}

@Composable
internal expect fun FormPickerDialogWindow(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
)
