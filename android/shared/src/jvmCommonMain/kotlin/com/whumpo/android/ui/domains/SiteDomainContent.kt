package com.whumpo.android.ui.domains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.common.absoluteUtc
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreDomainClaim
import com.whumpo.core.api.CoreDomainStatus
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.auth.CoreSiteDomainState
import com.whumpo.core.auth.DomainNotice
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun rememberDomainTime(
    claims: List<CoreDomainClaim>,
    clock: () -> Instant = { Clock.System.now() },
): Instant {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(claims) {
        now = maxOf(now, clock())
        val boundaries = claims.flatMap { listOfNotNull(it.expiresAt, it.evidenceValidUntil) }.distinct()
        while (true) {
            val next = boundaries.filter { it > now }.minOrNull() ?: break
            delay((next - now).inWholeMilliseconds.coerceIn(1, 60_000))
            now = maxOf(now, clock())
        }
    }
    return now
}

data class SiteDomainActions(
    val refresh: () -> Unit,
    val chooseDifferentProject: () -> Unit,
    val chooseWorkspace: (CoreWorkspaceChoice) -> Unit,
    val moreWorkspaces: () -> Unit,
    val chooseProject: (CoreProjectChoice) -> Unit,
    val moreProjects: () -> Unit,
    val inspect: (CoreDomainClaim) -> Unit,
    val prepare: (String) -> Unit,
    val check: (CoreDomainClaim) -> Unit,
    val renew: (CoreDomainClaim) -> Unit,
    val retry: () -> Unit,
    val signIn: () -> Unit,
    val back: () -> Unit,
)

@Composable
fun SiteDomainContent(
    state: CoreSiteDomainState,
    now: Instant,
    actions: SiteDomainActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DomainAction(if (state.claim == null) "Back to organization" else "Back to domain claims", actions.back)
            Text("Domain ownership", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            state.notice?.let { notice ->
                Text(notice.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.busy) {
                Text("Checking domain claims…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                CircularProgressIndicator()
            }
            if (state.notice == DomainNotice.Denied) {
                DomainAction("Sign in again", actions.signIn, enabled = !state.busy)
            }
            when {
                state.pending -> {
                    val label = if (state.readbackOnly) "Read saved claim" else "Retry original request"
                    DomainAction(label, actions.retry, enabled = !state.busy, primary = true)
                }
                state.claim != null -> DomainClaimContent(state.claim, now, !state.busy, actions)
                state.project != null -> DomainClaimsContent(state, now, actions)
                else -> DomainProjectContent(state, actions)
            }
        }
    }
}

@Composable
private fun DomainProjectContent(state: CoreSiteDomainState, actions: SiteDomainActions) {
    val workspaces = state.workspaceChoices
    if (workspaces == null) {
        if (!state.busy) DomainAction("Load domain claims", actions.refresh)
        return
    }
    Text("Choose a workspace", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    if (workspaces.items.isEmpty()) Text("No workspaces are available.")
    workspaces.items.forEach { workspace ->
        DomainAction(workspace.name, { actions.chooseWorkspace(workspace) }, enabled = !state.busy)
    }
    if (workspaces.nextCursor != null) DomainAction("More workspaces", actions.moreWorkspaces, enabled = !state.busy)
    val projects = state.projectChoices ?: return
    Text("Choose a project in ${state.workspace?.name.orEmpty()}", style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    if (projects.items.isEmpty()) Text("No projects are available in this workspace.")
    projects.items.forEach { project ->
        DomainAction(project.name, { actions.chooseProject(project) }, enabled = !state.busy)
    }
    if (projects.nextCursor != null) DomainAction("More projects", actions.moreProjects, enabled = !state.busy)
}

@Composable
private fun DomainClaimsContent(state: CoreSiteDomainState, now: Instant, actions: SiteDomainActions) {
    val project = checkNotNull(state.project)
    var hostname by remember(project.id) { mutableStateOf("") }
    val enabled = !state.busy
    Text(project.name ?: "Workspace project", style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    if (!project.fixed) DomainAction("Choose another project", actions.chooseDifferentProject, enabled)
    Text("Prove ownership of a domain you already manage using a TXT record.")
    if (state.claims.size < 5) {
        OutlinedTextField(
            value = hostname,
            onValueChange = { if (it.length <= 245) hostname = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Domain name") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            supportingText = { Text("Enter a hostname like example.com. Use Punycode for international names.") },
        )
        DomainAction("Get TXT instructions", { actions.prepare(hostname) }, enabled && hostname.isNotBlank(), primary = true)
    }
    if (state.claims.isEmpty()) Text("No domain claims yet.")
    state.claims.forEach { claim ->
        DomainAction(claim.hostname, { actions.inspect(claim) }, enabled)
        Text(statusLabel(claim, now), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    DomainAction("Refresh domain claims", actions.refresh, enabled)
}

@Composable
private fun DomainClaimContent(claim: CoreDomainClaim, now: Instant, enabled: Boolean, actions: SiteDomainActions) {
    val status = claim.statusAt(now)
    Text(claim.hostname, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(statusLabel(claim, now), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (status == CoreDomainStatus.OwnershipVerified) {
        Text("Ownership evidence valid until ${absoluteUtc(checkNotNull(claim.evidenceValidUntil))}.")
    }
    if (status == CoreDomainStatus.Expired) {
        Text("Get a new TXT challenge and replace the previous value at your DNS provider.")
        DomainAction("Renew TXT challenge", { actions.renew(claim) }, enabled, primary = true)
    } else {
        Text("At your DNS provider, add this TXT record.")
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Record name", style = MaterialTheme.typography.labelLarge)
                Text(claim.record.name)
                Text("Record value", style = MaterialTheme.typography.labelLarge)
                Text(claim.record.value)
            }
        }
        Text("TXT challenge expires ${absoluteUtc(claim.expiresAt)}.")
        DomainAction("Check TXT record", { actions.check(claim) }, enabled, primary = true)
    }
    DomainAction("Refresh this claim", { actions.inspect(claim) }, enabled)
}

@Composable
private fun DomainAction(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) WhumpoPrimaryButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    else WhumpoQuietButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
}

private fun statusLabel(claim: CoreDomainClaim, now: Instant) = when (claim.statusAt(now)) {
    CoreDomainStatus.AwaitingDns -> if (claim.status == CoreDomainStatus.OwnershipVerified) {
        "Ownership evidence expired. Check the TXT record again."
    } else "Awaiting DNS"
    CoreDomainStatus.OwnershipVerified -> "Ownership verified"
    CoreDomainStatus.ProofMissing -> "TXT proof missing"
    CoreDomainStatus.ResolverUnknown -> "DNS could not be checked. Try again."
    CoreDomainStatus.Expired -> "TXT challenge expired"
}

private val DomainNotice.message: String get() = when (this) {
    DomainNotice.Unavailable -> "Could not load the domain claim. Try again."
    DomainNotice.Denied -> "Domain claim access is unavailable. Check your project access or sign in again."
    DomainNotice.InvalidHostname -> "Enter a valid public hostname without a URL or wildcard."
    DomainNotice.Changed -> "The domain claim changed. Review the current details."
    DomainNotice.Unconfirmed -> "The domain request may have been saved."
    DomainNotice.CheckSaved -> "Read the current claim to see what was saved."
}
