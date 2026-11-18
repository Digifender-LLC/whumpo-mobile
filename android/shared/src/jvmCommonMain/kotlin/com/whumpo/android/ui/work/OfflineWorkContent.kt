package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreTaskOutcome
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.auth.CoreOfflineWorkState
import com.whumpo.core.auth.CoreSharedDraftItem
import com.whumpo.core.auth.OfflineWorkNotice
import com.whumpo.core.auth.OfflineWorkReview

data class OfflineWorkActions(
    val save: (String, String) -> Unit,
    val discard: () -> Unit,
    val reconnect: () -> Unit,
    val chooseWorkspace: (CoreWorkspaceChoice) -> Unit,
    val changeWorkspace: () -> Unit,
    val moreWorkspaces: () -> Unit,
    val chooseProject: (CoreProjectChoice) -> Unit,
    val moreProjects: () -> Unit,
    val submit: (OfflineWorkReview) -> Unit,
    val cancelReview: () -> Unit,
    val checkTask: () -> Unit,
    val openTask: (CoreWorkTask) -> Unit,
    val back: () -> Unit,
    val signOut: () -> Unit,
)

@Composable
fun OfflineWorkContent(
    state: CoreOfflineWorkState,
    actions: OfflineWorkActions,
    sharedItem: CoreSharedDraftItem? = null,
    shareOrganization: String? = null,
    useSharedItem: () -> String? = { null },
    dismissShare: () -> Unit = {},
) {
    var title by remember(state.title) { mutableStateOf(state.title) }
    var description by remember(state.description) { mutableStateOf(state.description) }
    val matchesSaved = state.saved && title == state.title && description == state.description
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkDraftButton(if (state.connected) "Back to offline draft" else "Back to Work", actions.back)
        Text("Task draft", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        state.notice?.let { WorkDraftStatus(it.message) }
        when {
            state.accessLost -> {
                Text("Sign in again to reopen a draft for this organization.")
            }
            state.busy && !state.loaded -> WorkDraftStatus("Opening saved draft…")
            sharedItem != null && state.loaded -> {
                val available = !state.connected && !state.saved && !state.frozen && !state.busy &&
                    state.notice == null && title.isEmpty() && description.isEmpty()
                if (available) {
                    Text(if (sharedItem.isLink) "Review shared link" else "Review shared text",
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    shareOrganization?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    Text(sharedItem.text)
                    Text("Use it as the draft description. You can edit it before saving on this device.")
                    WorkDraftButton("Use in draft", {
                        if (title.isEmpty() && description.isEmpty()) useSharedItem()?.let { description = it }
                    }, primary = true)
                } else {
                    WorkDraftStatus("The current draft can't receive this item.")
                }
                WorkDraftButton("Dismiss shared item", dismissShare)
            }
            !state.connected -> {
                if (state.frozen) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    if (state.description.isNotEmpty()) Text(state.description)
                    Text("The original request is saved on this device.")
                    WorkDraftButton("Reconnect to review", actions.reconnect, enabled = !state.busy, primary = true)
                } else if (state.loaded && state.notice != OfflineWorkNotice.Unreadable) {
                    OutlinedTextField(title, { if (it.length <= 400) title = it }, enabled = !state.busy,
                        singleLine = true, label = { Text("Task title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { if (it.length <= 20_000) description = it }, enabled = !state.busy,
                        minLines = 3, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                    WorkDraftButton("Save on this device", { actions.save(title, description) },
                        enabled = !state.busy && title.isNotBlank() && !matchesSaved, primary = !matchesSaved)
                    if (state.saved) {
                        WorkDraftButton("Reconnect to review", actions.reconnect, enabled = !state.busy && matchesSaved, primary = true)
                        WorkDraftButton("Discard saved draft", {
                            title = ""
                            description = ""
                            actions.discard()
                        }, enabled = !state.busy)
                    }
                }
            }
            state.busy -> WorkDraftStatus("Checking Work…")
            state.review != null -> {
                val review = state.review
                Text(if (review.retry) "Review original task request" else "Review task",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(review.projectName)
                Text(review.title)
                if (review.description.isNotEmpty()) Text(review.description)
                WorkDraftButton(if (review.retry) "Retry original request" else "Create reviewed task",
                    { actions.submit(review) }, primary = true)
                WorkDraftButton("Cancel review", actions.cancelReview)
            }
            state.outcome != null -> WorkDraftOutcome(state.outcome, actions)
            else -> WorkDraftProjects(state, actions)
        }
        WorkDraftButton("Sign out", actions.signOut)
    }
}

@Composable
private fun WorkDraftProjects(state: CoreOfflineWorkState, actions: OfflineWorkActions) {
    if (state.workspace == null) {
        Text("Choose a workspace", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        val workspaces = state.workspaces ?: return
        if (workspaces.items.isEmpty()) Text("No workspaces are available.")
        workspaces.items.forEach { choice -> WorkDraftButton(choice.name, { actions.chooseWorkspace(choice) }) }
        if (workspaces.nextCursor != null) WorkDraftButton("More workspaces", actions.moreWorkspaces)
    } else {
        Text("Choose a project in ${state.workspace.name}", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        WorkDraftButton("Change workspace", actions.changeWorkspace)
        val projects = state.projects ?: return
        if (projects.items.isEmpty()) Text("No projects are available.")
        projects.items.forEach { choice -> WorkDraftButton(choice.name, { actions.chooseProject(choice) }) }
        if (projects.nextCursor != null) WorkDraftButton("More projects", actions.moreProjects)
    }
}

@Composable
private fun WorkDraftOutcome(outcome: CoreTaskOutcome, actions: OfflineWorkActions) {
    val label = when (outcome) {
        CoreTaskOutcome.Queued -> "Task creation queued."
        CoreTaskOutcome.WaitingApproval -> "Task awaiting approval."
        CoreTaskOutcome.Failed -> "Task creation failed. The draft is still saved."
        CoreTaskOutcome.Canceled -> "Task creation was canceled. The draft is still saved."
        is CoreTaskOutcome.Confirmed -> "Task created."
    }
    WorkDraftStatus(label)
    if (outcome is CoreTaskOutcome.Confirmed) {
        WorkDraftButton("Open task", { actions.openTask(outcome.task) }, primary = true)
    } else {
        WorkDraftButton("Check task status", actions.checkTask, primary = true)
    }
}

@Composable
private fun WorkDraftButton(label: String, action: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val size = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) WhumpoPrimaryButton(onClick = action, enabled = enabled, modifier = size) { Text(label) }
    else WhumpoQuietButton(onClick = action, enabled = enabled, modifier = size) { Text(label) }
}

@Composable
private fun WorkDraftStatus(message: String) =
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

private val OfflineWorkNotice.message get() = when (this) {
    OfflineWorkNotice.Saved -> "Saved on this device."
    OfflineWorkNotice.Unreadable -> "The saved draft could not be opened."
    OfflineWorkNotice.StorageUnavailable -> "Could not update the saved draft."
    OfflineWorkNotice.NetworkUnavailable -> "Could not connect. Your draft is still saved."
    OfflineWorkNotice.AccessDenied -> "This session cannot access the draft's Work project."
    OfflineWorkNotice.ProjectUnavailable -> "The saved project is no longer available."
    OfflineWorkNotice.Changed -> "Check the task details or project and review again."
    OfflineWorkNotice.Unconfirmed -> "Work did not confirm the task. Review the original request when connected."
    OfflineWorkNotice.Unavailable -> "Could not verify Work. Your draft is still saved."
}
