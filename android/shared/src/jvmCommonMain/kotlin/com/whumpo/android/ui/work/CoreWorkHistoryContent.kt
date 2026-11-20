package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreRecordedTaskSource
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.auth.CoreWorkHistoryState
import com.whumpo.core.auth.CoreWorkSourceState

@Composable
fun CoreWorkHistoryContent(
    state: CoreWorkHistoryState,
    source: CoreWorkSourceState,
    refresh: () -> Unit,
    more: () -> Unit,
    openSource: (CoreRecordedTaskSource) -> Unit,
    moreSource: () -> Unit,
    closeSource: () -> Unit,
    close: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.busy || source.busy
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Task history", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (state.busy) Text("Loading task history…")
        if (state.unavailable) Text("Task history is unavailable.")
        state.current?.let {
            Text(it.title, style = MaterialTheme.typography.titleLarge)
            Text("Current: ${it.status.label} · version ${it.version}")
            if (it.status == CoreTaskStatus.Blocked) Text("This task is blocked. Review its recorded changes; no separate blocker explanation is recorded here.")
        }
        state.page?.let { page ->
            if (page.changes.isEmpty()) Text("No recorded task changes.")
            page.changes.forEach { change ->
                Text("${change.label} · version ${change.version}", style = MaterialTheme.typography.titleMedium)
                Text(workDueLabel(change.createdAt))
                change.snapshot?.let { task -> Text("${task.status.label} · ${change.source}") }
                change.origin?.let { origin ->
                    WhumpoQuietButton(onClick = { openSource(origin) }, enabled = !busy) { Text("Open original request") }
                }
            }
            if (page.next != null) WhumpoQuietButton(onClick = more, enabled = !busy) { Text("More task history") }
        }
        if (source.busy || source.unavailable || source.result != null) {
            Text("Original task request", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when {
                source.busy -> Text("Opening the recorded request…")
                source.unavailable -> Text("This recorded request is unavailable.")
                source.result?.content != null -> {
                    Text(source.result.content.ifEmpty { "The recorded request is empty." })
                    source.result.createdAt?.let { Text("Submitted ${workDueLabel(it)}") }
                }
                source.result?.next != null -> {
                    Text("The request is on another history page. Only the exact recorded message will be shown.")
                    WhumpoQuietButton(onClick = moreSource, enabled = !busy) { Text("Search next source page") }
                }
                else -> Text("The recorded request was not found in this conversation.")
            }
            WhumpoQuietButton(onClick = closeSource) { Text("Close original request") }
        }
        WhumpoQuietButton(onClick = refresh, enabled = !busy) { Text("Refresh task history") }
        WhumpoQuietButton(onClick = close) { Text("Back to Work") }
    }
}
