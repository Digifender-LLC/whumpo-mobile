package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.*
import com.whumpo.core.auth.CoreWorkChoiceState
import com.whumpo.core.auth.CoreWorkQueryNotice
import com.whumpo.core.auth.CoreWorkQueryState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class CoreWorkQueryActions(
    val edit: (CoreWorkFilters) -> Unit,
    val apply: () -> Unit,
    val refresh: () -> Unit,
    val more: () -> Unit,
    val choices: (CoreWorkDirectory, Boolean) -> Unit,
    val open: (CoreWorkTask) -> Unit,
    val history: (CoreWorkTask) -> Unit,
    val complete: ((CoreWorkTask) -> Unit)? = null,
)

/** Plain rendering: no session, controller, network client or coroutine lifetime is required. */
@Composable
fun CoreWorkQueryContent(
    state: CoreWorkQueryState,
    actions: CoreWorkQueryActions,
    canManageAssignees: Boolean,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkChoice("Workspace", draft.workspaceId, "All workspaces", state.choices[CoreWorkDirectory.Workspace],
            state.busy, { actions.edit(draft.copy(workspaceId = it, projectId = null)) },
            { actions.choices(CoreWorkDirectory.Workspace, it) })
        WorkChoice("Project", draft.projectId, "All projects", state.choices[CoreWorkDirectory.Project],
            state.busy, { actions.edit(draft.copy(projectId = it)) }, { actions.choices(CoreWorkDirectory.Project, it) })
        WorkFilter("Status", draft.status?.label ?: "All statuses", listOf(null to "All statuses") +
            CoreTaskStatus.entries.map { it to it.label }, state.busy) { actions.edit(draft.copy(status = it)) }
        WorkFilter("Due", when (draft.due) {
            CoreWorkDue.All -> "All due dates"; CoreWorkDue.Overdue -> "Overdue"; CoreWorkDue.Unscheduled -> "No due date"
        }, listOf(CoreWorkDue.All to "All due dates", CoreWorkDue.Overdue to "Overdue", CoreWorkDue.Unscheduled to "No due date"),
            state.busy) { actions.edit(draft.copy(due = it)) }
        WorkFilter("Customer", when (draft.customer) {
            CoreWorkCustomer.All -> "Any customer"; CoreWorkCustomer.Associated -> "Associated"; CoreWorkCustomer.Unassociated -> "No customer"
        }, listOf(CoreWorkCustomer.All to "Any customer", CoreWorkCustomer.Associated to "Associated", CoreWorkCustomer.Unassociated to "No customer"),
            state.busy) { actions.edit(draft.copy(customer = it, customerId = null)) }
        if (draft.customer != CoreWorkCustomer.Unassociated) WorkChoice("Specific customer", draft.customerId,
            "Any matching customer", state.choices[CoreWorkDirectory.Customer], state.busy,
            { actions.edit(draft.copy(customerId = it)) }, { actions.choices(CoreWorkDirectory.Customer, it) })
        WorkFilter("Assignee", when (draft.assignee) {
            CoreWorkAssignee.All -> "Any assignee"; CoreWorkAssignee.Assigned -> "Assigned"; CoreWorkAssignee.Unassigned -> "Unassigned"
        }, listOf(CoreWorkAssignee.All to "Any assignee", CoreWorkAssignee.Assigned to "Assigned", CoreWorkAssignee.Unassigned to "Unassigned"),
            state.busy) { actions.edit(draft.copy(assignee = it, assigneeId = null)) }
        if (canManageAssignees && draft.assignee != CoreWorkAssignee.Unassigned) WorkChoice("Specific assignee", draft.assigneeId,
            "Any matching assignee", state.choices[CoreWorkDirectory.Assignee], state.busy,
            { actions.edit(draft.copy(assigneeId = it)) }, { actions.choices(CoreWorkDirectory.Assignee, it) })
        WhumpoPrimaryButton(onClick = actions.apply, enabled = !state.busy) { Text("Apply Work filters") }
        WhumpoQuietButton(onClick = actions.refresh, enabled = !state.busy) { Text("Refresh Work list") }
        if (draft != state.filters) Text("Apply filters to update the task list.")
        if (state.busy) Text("Loading Work…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.notice?.let { notice ->
            Text(if (notice == CoreWorkQueryNotice.RestartRequired) "This task list expired. Refresh to start a new list."
                else "Work is unavailable. Refresh to try again.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        state.page?.let { page ->
            Text("${page.counts.total} matching tasks · ${page.counts.overdue} overdue")
            Text("Checked ${workDueLabel(page.asOf)}", style = MaterialTheme.typography.bodySmall)
            if (state.items.isEmpty()) Text("No matching tasks.")
            CoreTaskStatus.entries.forEach { status ->
                val items = state.items.filter { it.task.status == status }
                if (items.isNotEmpty()) {
                    Text("${status.label}: ${items.size} shown of ${page.counts.byStatus.getValue(status)}",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    items.forEach { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                WhumpoQuietButton(onClick = { actions.open(item.task) }, enabled = !state.busy) { Text(item.task.title) }
                                state.choices[CoreWorkDirectory.Project]?.items?.find { it.id == item.task.projectId }?.let { Text(it.name) }
                                Text(item.task.dueAt?.let { "Due ${workDueLabel(it)}" } ?: "No due date")
                                if (workQueryOverdue(item.task, page.asOf)) Text("Overdue", color = MaterialTheme.colorScheme.error)
                                WhumpoQuietButton(onClick = { actions.history(item.task) }, enabled = !state.busy) {
                                    Text(if (status == CoreTaskStatus.Blocked) "View blocked task history" else "Open task history")
                                }
                                if (status == CoreTaskStatus.InProgress && item.task.archivedAt == null) actions.complete?.let { complete ->
                                    WhumpoPrimaryButton(onClick = { complete(item.task) }, enabled = !state.busy) { Text("Complete task") }
                                }
                            }
                        }
                    }
                }
            }
            if (page.nextCursor != null) WhumpoQuietButton(onClick = actions.more, enabled = !state.busy) { Text("More matching tasks") }
        }
    }
}

@Composable
private fun WorkChoice(label: String, selected: String?, any: String, page: CoreWorkChoiceState?, disabled: Boolean,
    select: (String?) -> Unit, load: (Boolean) -> Unit) {
    WorkFilter(label, if (selected == null) any else page?.items?.find { it.id == selected }?.name ?: "Selection unavailable",
        listOf(null to any) + page?.items.orEmpty().map { it.id to it.name }, disabled || page?.busy == true, select)
    if (page?.unavailable == true) Text("$label choices are unavailable. You can still use the other filters.")
    if (page?.nextCursor != null) WhumpoQuietButton(onClick = { load(true) }, enabled = !disabled && !page.busy) { Text("More $label choices") }
    if (page?.unavailable == true) WhumpoQuietButton(onClick = { load(false) }, enabled = !disabled) { Text("Refresh $label choices") }
}

@Composable
private fun <T> WorkFilter(label: String, value: String, options: List<Pair<T, String>>, disabled: Boolean, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        WhumpoQuietButton(onClick = { expanded = true }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("$label: $value") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (choice, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; select(choice) }) }
        }
    }
}

internal fun workDueLabel(value: String, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(Instant.parse(value).atZone(zone))
