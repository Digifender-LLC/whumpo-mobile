package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreReminderNotice
import com.whumpo.core.api.CoreReminderState
import com.whumpo.core.api.CoreWorkTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun CoreTaskReminderContent(
    dueAt: String,
    state: CoreReminderState?,
    verified: Boolean,
    canArm: Boolean,
    busy: Boolean,
    pending: String?,
    notice: String?,
    onArm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onCheck: () -> Unit,
) {
    Text("Personal deadline reminder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text("Due ${reminderTime(dueAt)}")
    val status = notice ?: when {
        busy -> "Checking reminder…"
        pending != null -> "The last reminder change hasn’t been confirmed."
        state == CoreReminderState.Armed -> "Reminder scheduled."
        state == CoreReminderState.Delivered -> "Reminder delivered in Task reminders."
        state == CoreReminderState.Canceled -> "Reminder canceled."
        verified && !canArm -> "The deadline has passed."
        else -> null
    }
    if (status != null) Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    when {
        pending != null -> {
            ReminderButton(pending, onRetry, !busy)
            ReminderButton("Check reminder status", onCheck, !busy)
        }
        !verified || notice != null -> ReminderButton("Check reminder status", onCheck, !busy)
        state == CoreReminderState.Armed -> ReminderButton("Cancel reminder", onCancel, !busy)
        state != CoreReminderState.Delivered && canArm -> ReminderButton("Remind me at the due time", onArm, !busy)
    }
}

@Composable
fun CoreReminderInboxContent(
    organizationName: String,
    items: List<CoreReminderNotice>?,
    hasMore: Boolean,
    task: CoreWorkTask?,
    busy: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (CoreReminderNotice) -> Unit,
    onRead: (CoreReminderNotice) -> Unit,
) {
    key(task?.id) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (task == null) "Back to task notifications" else "Back to task reminders")
            }
            Text(organizationName, style = MaterialTheme.typography.labelLarge)
            Text(if (task == null) "Task reminders" else "Work item",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (notice != null || busy) {
                Text(notice ?: "Checking task reminders…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (task != null) {
                Text(task.title, style = MaterialTheme.typography.titleLarge)
                Text(task.status.label)
                task.description?.let { Text(it) }
                task.dueAt?.let { Text("Due ${reminderTime(it)}") }
            } else {
                ReminderButton("Refresh reminders", onRefresh, !busy)
                if (items?.isEmpty() == true) Text("No task reminders.")
                items?.forEach { item ->
                    key(item.id) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Task deadline reminder", style = MaterialTheme.typography.titleMedium)
                                Text("Due ${reminderTime(item.dueAt)}")
                                ReminderButton("Open reminded task", { onOpen(item) }, !busy)
                                if (item.acknowledgedAt == null) {
                                    ReminderButton("Mark reminder as read", { onRead(item) }, !busy)
                                } else Text("Read")
                            }
                        }
                    }
                }
                if (hasMore) ReminderButton("More reminders", onMore, !busy)
            }
        }
    }
}

@Composable
private fun ReminderButton(label: String, click: () -> Unit, enabled: Boolean) {
    WhumpoQuietButton(onClick = click, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled) { Text(label) }
}

private fun reminderTime(value: String): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
