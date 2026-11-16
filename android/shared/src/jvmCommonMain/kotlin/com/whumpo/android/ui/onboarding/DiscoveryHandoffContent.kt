package com.whumpo.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.partner.PartnerApprovalDetails
import com.whumpo.android.ui.partner.PartnerButton
import com.whumpo.android.ui.partner.PartnerDecisionButtons
import com.whumpo.android.ui.partner.PartnerStatus
import com.whumpo.core.api.CoreDiscoveryActionId
import com.whumpo.core.api.CoreDiscoveryHandoff
import com.whumpo.core.api.CoreHandoffLineage
import com.whumpo.core.api.CoreHandoffState
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.api.PartnerApproval
import com.whumpo.core.api.PartnerDecision
import com.whumpo.core.auth.DiscoveryHandoffNotice
import com.whumpo.core.auth.DiscoveryHandoffReview
import com.whumpo.core.auth.DiscoveryHandoffState

@Composable
fun DiscoveryHandoffInventory(
    state: DiscoveryHandoffState,
    onInspect: (String) -> Unit,
    onResume: () -> Unit,
) {
    if (state.pending) PartnerButton("Resume Work handoff", !state.busy, onClick = onResume)
    if (state.inventory.isNotEmpty()) {
        Text("Work handoffs", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        state.inventory.forEach { handoff ->
            PartnerButton(handoff.actionId.label() + " · " + handoff.label(), !state.busy && !state.pending) {
                onInspect(handoff.id)
            }
        }
    }
}

@Composable
fun DiscoveryHandoffContent(
    state: DiscoveryHandoffState,
    onSubmit: (DiscoveryHandoffReview) -> Unit,
    onChooseWorkspace: (CoreWorkspaceChoice) -> Unit,
    onMoreWorkspaces: () -> Unit,
    onChooseProject: (CoreProjectChoice) -> Unit,
    onMoreProjects: () -> Unit,
    onInspect: (String) -> Unit,
    onReviewApproval: (String) -> Unit,
    onDecision: (PartnerApproval, PartnerDecision) -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (CoreWorkTask) -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.busy && !state.pending
    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PartnerButton("Back to discovery", onClick = onBack)
        Text("Review a Work handoff", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        if (state.busy) PartnerStatus("Checking the current handoff…")
        state.notice?.let { PartnerStatus(it.label()) }
        if (state.notice == DiscoveryHandoffNotice.SignInAgain) {
            PartnerButton("Sign in again", !state.busy, onClick = onSignIn)
        }
        if (state.pending) {
            PartnerButton(if (state.acknowledged) "Check saved handoff" else "Retry original request",
                !state.busy, primary = true, onClick = onRetry)
        }
        state.review?.let { review ->
            Text(review.title, style = MaterialTheme.typography.titleLarge)
            Text(review.reason)
            Text("This requests one undated, unassigned Work item for review.")
            val existing = review.existing
            if (existing != null) {
                Text(existing.label())
                PartnerButton("Inspect existing handoff", enabled) { onInspect(existing.id) }
            } else {
                review.fixedProjectId?.let { Text("Workspace project: $it") }
                review.project?.let { Text("Selected project: " + it.name) }
                state.workspaces?.let { page ->
                    Text("Choose a workspace", style = MaterialTheme.typography.titleMedium)
                    page.items.forEach { item ->
                        PartnerButton("Workspace: " + item.name, enabled) { onChooseWorkspace(item) }
                    }
                    if (page.nextCursor != null) PartnerButton("More workspaces", enabled, onClick = onMoreWorkspaces)
                }
                state.projects?.let { page ->
                    Text("Choose a project", style = MaterialTheme.typography.titleMedium)
                    if (page.items.isEmpty()) Text("This workspace has no available projects.")
                    page.items.forEach { item ->
                        PartnerButton("Project: " + item.name, enabled) { onChooseProject(item) }
                    }
                    if (page.nextCursor != null) PartnerButton("More projects", enabled, onClick = onMoreProjects)
                }
                if (review.pendingPrerequisites.isNotEmpty()) {
                    Text("Required in this project: " + review.pendingPrerequisites.joinToString { it.label() })
                }
                if (review.dependencies.isNotEmpty()) {
                    Text("Verified prerequisites: " + review.dependencies.joinToString { it.actionId.label() })
                }
                PartnerButton("Request one Work item", enabled && review.canSubmit, primary = true) { onSubmit(review) }
            }
        }
        state.detail?.let { handoff ->
            Text(handoff.actionId.label(), style = MaterialTheme.typography.titleLarge)
            Text(handoff.label(), modifier = Modifier.semantics { heading() })
            Text("Project " + handoff.projectId)
            handoff.supersedesHandoffId?.let { previous ->
                PartnerButton("Inspect previous handoff", enabled) { onInspect(previous) }
            }
            when (handoff.state) {
                CoreHandoffState.WaitingApproval -> {
                    val approval = state.approval
                    if (approval != null) {
                        PartnerApprovalDetails(approval)
                        PartnerDecisionButtons(enabled) { onDecision(approval, it) }
                    } else if (handoff.lineage == CoreHandoffLineage.Current) {
                        PartnerButton("Review exact approval", enabled) { onReviewApproval(handoff.id) }
                    }
                }
                CoreHandoffState.Confirmed -> {
                    val task = state.verifiedTask
                    if (task == null) Text("Check the handoff to verify its Work item.")
                    else PartnerButton("Open verified Work item", enabled, primary = true) { onOpenWork(task) }
                }
                CoreHandoffState.Queued, CoreHandoffState.Failed, CoreHandoffState.Canceled -> Unit
            }
            if (!state.pending) PartnerButton("Check handoff status", enabled) { onInspect(handoff.id) }
        }
    }
}

private fun CoreDiscoveryHandoff.label(): String {
    val status = when (state) {
        CoreHandoffState.WaitingApproval -> "Waiting for approval"
        CoreHandoffState.Queued -> "Queued"
        CoreHandoffState.Confirmed -> "Handoff confirmed"
        CoreHandoffState.Failed -> "Handoff failed"
        CoreHandoffState.Canceled -> "Handoff canceled"
    }
    return if (lineage == CoreHandoffLineage.Current) status else "$status · Superseded"
}

private fun CoreDiscoveryActionId.label(): String = when (this) {
    CoreDiscoveryActionId.Goal -> "First goal"
    CoreDiscoveryActionId.Migration -> "Website migration"
    CoreDiscoveryActionId.DomainAccess -> "Domain access"
    CoreDiscoveryActionId.Domain -> "Domain work"
    CoreDiscoveryActionId.DomainDiscovery -> "Domain options"
    CoreDiscoveryActionId.WebsiteDiscovery -> "Website review"
    CoreDiscoveryActionId.DiscoverRegistration -> "Registration options"
    CoreDiscoveryActionId.Registration -> "Registration review"
    CoreDiscoveryActionId.EmployerId -> "EIN guidance"
    CoreDiscoveryActionId.Offer -> "Offer review"
    CoreDiscoveryActionId.Tools -> "Tool review"
}

private fun DiscoveryHandoffNotice.label(): String = when (this) {
    DiscoveryHandoffNotice.Unavailable -> "I couldn’t check this handoff. Try again."
    DiscoveryHandoffNotice.Changed -> "The saved plan or handoff changed. Go back and review it again."
    DiscoveryHandoffNotice.Denied -> "Work handoffs are unavailable for this organization."
    DiscoveryHandoffNotice.SignInAgain -> "Sign in again to verify owner access."
    DiscoveryHandoffNotice.Unconfirmed -> "I couldn’t confirm the request. Retry sends the original request."
    DiscoveryHandoffNotice.CheckSaved -> "The service acknowledged the request. Check the saved handoff to confirm its status."
}
