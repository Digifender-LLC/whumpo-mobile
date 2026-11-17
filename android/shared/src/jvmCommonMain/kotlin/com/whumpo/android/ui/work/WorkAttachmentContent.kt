package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.auth.CoreAttachmentNotice
import com.whumpo.core.auth.CoreWorkAttachmentState

@Composable
fun WorkAttachmentContent(
    taskTitle: String,
    state: CoreWorkAttachmentState,
    picking: Boolean,
    onChoose: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Attach private file", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        if (state.notice != CoreAttachmentNotice.DestinationChanged) Text("Task: $taskTitle")
        state.fileName?.let { Text(it) }
        state.byteCount?.let { Text("$it bytes") }
        val status = when {
            state.attached -> "Attached to task."
            state.saved -> "Saved private file. Attachment not yet verified."
            state.busy -> "Saving private file…"
            picking -> "Opening file…"
            else -> null
        }
        status?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        val notice = when (state.notice) {
            CoreAttachmentNotice.InvalidFile -> "Choose a PDF, JPEG, PNG or plain text file up to 4 MiB."
            CoreAttachmentNotice.CouldNotConfirm -> "The request could not be confirmed. Retry the original request."
            CoreAttachmentNotice.DestinationChanged -> "Task access changed. Reopen the task to attach a file."
            CoreAttachmentNotice.ReservationExpired -> "This upload expired. Choose the file again."
            null -> null
        }
        notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        when {
            state.attached || state.notice == CoreAttachmentNotice.DestinationChanged -> Unit
            state.canRetry -> WhumpoPrimaryButton(onClick = onRetry, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry original request") }
            state.fileName != null -> {
                Text("This file will be private to your organization.")
                WhumpoPrimaryButton(onClick = onConfirm, enabled = !state.busy && !picking,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Save and attach") }
            }
            else -> WhumpoPrimaryButton(onClick = onChoose, enabled = !state.busy && !picking,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose file") }
        }
        WhumpoQuietButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.attached) "Done" else "Back to task")
        }
    }
}
