package com.whumpo.android.ui.domains

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreDomainClaim
import com.whumpo.core.api.CoreDomainStatus
import com.whumpo.core.api.CoreHostnameStatus
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.auth.CoreSiteHostnameState
import com.whumpo.core.auth.HostnameAction
import com.whumpo.core.auth.HostnameNotice
import com.whumpo.core.auth.HostnamePending
import com.whumpo.core.auth.HostnameReview
import kotlin.time.Instant

data class SiteHostnameActions(
    val refresh: () -> Unit,
    val chooseDifferentProject: () -> Unit,
    val chooseWorkspace: (CoreWorkspaceChoice) -> Unit,
    val moreWorkspaces: () -> Unit,
    val chooseProject: (CoreProjectChoice) -> Unit,
    val moreProjects: () -> Unit,
    val loadClaims: () -> Unit,
    val reviewClaim: (CoreDomainClaim) -> Unit,
    val reviewDisable: () -> Unit,
    val confirm: (HostnameReview) -> Unit,
    val cancelReview: () -> Unit,
    val retry: () -> Unit,
    val checkStatus: () -> Unit,
    val signIn: () -> Unit,
    val back: () -> Unit,
)

@Composable
fun SiteHostnameContent(
    state: CoreSiteHostnameState,
    now: Instant,
    actions: SiteHostnameActions,
    modifier: Modifier = Modifier,
) {
    val titleFocus = remember { FocusRequester() }
    val reviewFocus = remember { FocusRequester() }
    val disableFocus = remember { FocusRequester() }
    var restoreDisable by remember(state.project?.id) { mutableStateOf(false) }
    var hadReview by remember(state.project?.id) { mutableStateOf(false) }
    val currentReview = state.review
    val canDisable = state.loaded && state.attachment?.status?.let { it != CoreHostnameStatus.Detaching } == true
    LaunchedEffect(currentReview, state.pending, state.busy) {
        when {
            currentReview != null && !hadReview -> {
                hadReview = true
                reviewFocus.requestFocus()
            }
            currentReview == null && hadReview && !state.busy -> {
                hadReview = false
                val restored = restoreDisable && canDisable && state.pending == null && disableFocus.requestFocus()
                if (!restored) titleFocus.requestFocus()
                restoreDisable = false
            }
            state.pending != null && state.busy -> titleFocus.requestFocus()
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HostnameButton(if (currentReview == null) "Back to organization" else "Back to hostname", actions.back)
            Text("Website hostname", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() })
            state.project?.name?.let { Text(it) }
            state.notice?.let { HostnameStatusText(it.message) }
            if (state.busy) HostnameStatusText("Checking hostname status…")
            if (state.notice == HostnameNotice.Denied) {
                HostnameButton("Sign in again", actions.signIn, enabled = !state.busy)
            }
            when {
                state.pending != null -> {
                    if (state.pending == HostnamePending.Retry) {
                        HostnameButton("Retry original request", actions.retry, enabled = !state.busy, primary = true)
                    }
                    HostnameButton("Check current status", actions.checkStatus, enabled = !state.busy,
                        primary = state.pending == HostnamePending.CheckStatus)
                }
                currentReview != null -> HostnameReviewContent(currentReview, reviewFocus, now, actions, !state.busy) {
                    restoreDisable = currentReview.action == HostnameAction.Disable
                    actions.cancelReview()
                }
                state.project == null -> HostnameProjectContent(state, actions)
                else -> {
                    if (state.loaded) {
                        val attachment = state.attachment
                        if (attachment == null) Text("No hostname attachment for this project.")
                        else {
                            Text(attachment.hostname, style = MaterialTheme.typography.titleMedium)
                            Text(attachment.status.label)
                        }
                        if (!state.policyUnavailable && (attachment == null || attachment.status == CoreHostnameStatus.ReproofRequired)) {
                            HostnameButton(if (state.claims == null) "Load verified domains" else "Refresh verified domains",
                                actions.loadClaims, enabled = !state.busy)
                            state.claims.orEmpty().filter {
                                it.statusAt(now) == CoreDomainStatus.OwnershipVerified &&
                                    (attachment == null || attachment.hostname == it.hostname)
                            }.forEach { claim ->
                                HostnameButton("Review ${claim.hostname}", { actions.reviewClaim(claim) }, enabled = !state.busy)
                            }
                        }
                        if (canDisable) {
                            HostnameButton("Review disabling hostname", actions.reviewDisable, enabled = !state.busy,
                                modifier = Modifier.focusRequester(disableFocus))
                        }
                    }
                    HostnameButton("Refresh hostname status", actions.refresh, enabled = !state.busy)
                    if (!state.project.fixed) {
                        HostnameButton("Choose a different project", actions.chooseDifferentProject, enabled = !state.busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostnameProjectContent(state: CoreSiteHostnameState, actions: SiteHostnameActions) {
    val workspaces = state.workspaces
    if (workspaces == null) {
        if (!state.busy) HostnameButton("Load hostname status", actions.refresh)
        return
    }
    Text("Choose a workspace", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    if (workspaces.items.isEmpty()) Text("No workspaces are available.")
    workspaces.items.forEach { workspace ->
        HostnameButton(workspace.name, { actions.chooseWorkspace(workspace) }, enabled = !state.busy)
    }
    if (workspaces.nextCursor != null) HostnameButton("More workspaces", actions.moreWorkspaces, enabled = !state.busy)
    val projects = state.projects ?: return
    Text("Choose a project in ${state.workspace?.name.orEmpty()}", style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    if (projects.items.isEmpty()) Text("No projects are available in this workspace.")
    projects.items.forEach { project ->
        HostnameButton(project.name, { actions.chooseProject(project) }, enabled = !state.busy)
    }
    if (projects.nextCursor != null) HostnameButton("More projects", actions.moreProjects, enabled = !state.busy)
}

@Composable
private fun HostnameReviewContent(
    review: HostnameReview,
    focus: FocusRequester,
    now: Instant,
    actions: SiteHostnameActions,
    enabled: Boolean,
    cancel: () -> Unit,
) {
    val (label, heading) = when (review.action) {
        HostnameAction.Attach -> "Attach hostname" to "Review hostname attachment"
        HostnameAction.Resume -> "Resume hostname" to "Review resuming hostname"
        HostnameAction.Disable -> "Disable hostname" to "Review disabling hostname"
    }
    Text(heading, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.focusRequester(focus).focusable().semantics { heading() })
    Text(review.hostname)
    review.publicationVersion?.let { Text("Website publication version $it") }
    if (review.action == HostnameAction.Disable) {
        Text("Close local serving. The hostname stays reserved until removal is confirmed.")
    }
    HostnameButton(label, { actions.confirm(review) }, enabled = enabled && review.expiresAt?.let { it > now } != false,
        primary = true)
    HostnameButton("Cancel review", cancel, enabled = enabled)
}

@Composable
private fun HostnameStatusText(message: String) {
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun HostnameButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val size = modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) WhumpoPrimaryButton(onClick = onClick, enabled = enabled, modifier = size) { Text(label) }
    else WhumpoQuietButton(onClick = onClick, enabled = enabled, modifier = size) { Text(label) }
}

private val CoreHostnameStatus.label get() = when (this) {
    CoreHostnameStatus.Reserved -> "Reserved"
    CoreHostnameStatus.ServingEligible -> "Eligible at last check"
    CoreHostnameStatus.ReproofRequired -> "Reproof required"
    CoreHostnameStatus.Detaching -> "Detaching"
}

private val HostnameNotice.message get() = when (this) {
    HostnameNotice.MissingPublication -> "Confirm a website publication before attaching a hostname."
    HostnameNotice.MissingProof -> "Check a TXT record in Domain ownership before reviewing an attachment."
    HostnameNotice.Changed -> "The hostname details changed. Check the current status and review again."
    HostnameNotice.PolicyUnavailable -> "Hostname attachment changes are unavailable. You can still check status or disable an attachment."
    HostnameNotice.Denied -> "Hostname details are unavailable to this session."
    HostnameNotice.Unavailable -> "Could not verify the hostname details. Refresh to try again."
    HostnameNotice.Unconfirmed -> "Could not confirm the request. You can retry it with the same details."
    HostnameNotice.CheckStatus -> "Check the current hostname status before making another change."
    HostnameNotice.Current -> "Current hostname status loaded."
    HostnameNotice.Recorded -> "Change recorded. Current hostname status loaded."
}
