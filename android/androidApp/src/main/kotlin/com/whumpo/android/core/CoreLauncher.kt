package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreEntitlementStatus
import com.whumpo.core.api.CoreMembership
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreTodayBriefing
import com.whumpo.core.api.CoreTodayTaskOperation
import com.whumpo.core.auth.AccountPasskeys
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.android.CorePhoneComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch

@Composable
internal fun CoreLauncher(composition: CorePhoneComposition, passkeys: AccountPasskeys) {
    val sessions = composition.organizations
    val accounts = composition.accounts
    val session by sessions.state.collectAsStateWithLifecycle()
    val account by accounts.state.collectAsStateWithLifecycle()
    var showSignIn by remember(composition) { mutableStateOf(false) }
    var switching by remember(composition) { mutableStateOf(false) }
    var switchUnconfirmed by remember(composition) { mutableStateOf(false) }
    var signOutUnconfirmed by remember(composition) { mutableStateOf(false) }
    val actions = rememberCoroutineScope()
    val active = session as? CoreOrganizationSessionController.State.Active
    LaunchedEffect(active) {
        if (active != null) {
            showSignIn = false
            while (true) {
                val remaining = active.session.expiresAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    if (sessions.state.value === active) {
                        accounts.clear()
                        showSignIn = false
                    }
                    break
                }
                delay(minOf(remaining, 60_000))
            }
        }
    }
    WhumpoEntryTheme {
        Surface {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (active != null) {
                    key(active.epoch) {
                        CoreOrganizationEntry(active, sessions, onSwitch = { membership ->
                            if (!switching && sessions.state.value === active) {
                                switching = true
                                showSignIn = false
                                actions.launch(start = CoroutineStart.UNDISPATCHED) {
                                    try {
                                        sessions.switchOrganization(active, membership)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) {
                                        if (sessions.state.value == CoreOrganizationSessionController.State.SignedOut) {
                                            switchUnconfirmed = true
                                        }
                                    } finally {
                                        switching = false
                                    }
                                }
                            }
                        }, signOut = {
                            showSignIn = false
                            actions.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    accounts.signOutWorkspace()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    if (!showSignIn) signOutUnconfirmed = true
                                }
                            }
                        })
                    }
                } else if (switching) {
                    Text("Switching organization…", Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite })
                } else if (showSignIn) {
                    AccountSecurityScreen(accounts, account, passkeys, passkeysConfigured = false, existingAccountOnly = true) {
                        showSignIn = false
                    }
                } else {
                    CoreSignedOut(signOutUnconfirmed, switchUnconfirmed) {
                        signOutUnconfirmed = false
                        switchUnconfirmed = false
                        showSignIn = true
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreSignedOut(signOutUnconfirmed: Boolean, switchUnconfirmed: Boolean, openSignIn: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Whumpo Core", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text("Synthetic test build. Use only fixture accounts.")
        if (signOutUnconfirmed) {
            Text("Signed out on this device. Service sign-out could not be confirmed.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (switchUnconfirmed) {
            Text("Could not verify the organization switch. Sign in again.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        WhumpoPrimaryButton(onClick = openSignIn) { Text("Open Core sign-in") }
    }
}

private sealed interface OrganizationEntryState {
    data object Loading : OrganizationEntryState
    data object Unready : OrganizationEntryState
    data object Unavailable : OrganizationEntryState
    data class Ready(val profile: CoreOrganizationProfile, val access: CoreEntitlementStatus?) : OrganizationEntryState
}

private sealed interface OrganizationChoices {
    data object Closed : OrganizationChoices
    data object Loading : OrganizationChoices
    data object Unavailable : OrganizationChoices
    data class Loaded(val page: CoreMembershipPage) : OrganizationChoices
}

@Composable
private fun CoreOrganizationEntry(
    active: CoreOrganizationSessionController.State.Active,
    sessions: CoreOrganizationSessionController,
    onSwitch: (CoreMembership) -> Unit,
    signOut: () -> Unit,
) {
    var state by remember { mutableStateOf<OrganizationEntryState>(OrganizationEntryState.Loading) }
    var retry by remember { mutableStateOf(0) }
    var choices by remember { mutableStateOf<OrganizationChoices>(OrganizationChoices.Closed) }
    var read by remember { mutableStateOf<OrganizationReadAttempt?>(null) }
    val actions = rememberCoroutineScope()

    fun loadChoices(cursor: String?) {
        if (choices == OrganizationChoices.Loading) return
        choices = OrganizationChoices.Loading
        actions.launch {
            try {
                val page = sessions.organizations(active, cursor)
                sessions.commitIfCurrent(active.epoch) { choices = OrganizationChoices.Loaded(page) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (sessions.state.value != CoreOrganizationSessionController.State.SignedOut) {
                    sessions.commitIfCurrent(active.epoch) { choices = OrganizationChoices.Unavailable }
                }
            }
        }
    }
    LaunchedEffect(active, retry) {
        state = OrganizationEntryState.Loading
        try {
            val profile = sessions.organizationWhenReady(active)
            val availability = profile?.let {
                try {
                    sessions.entitlements(active).status
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (sessions.state.value == CoreOrganizationSessionController.State.SignedOut) throw error
                    null
                }
            }
            sessions.commitIfCurrent(active.epoch) {
                state = if (profile == null) OrganizationEntryState.Unready else OrganizationEntryState.Ready(profile, availability)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (sessions.state.value != CoreOrganizationSessionController.State.SignedOut) {
                sessions.commitIfCurrent(active.epoch) { state = OrganizationEntryState.Unavailable }
            }
        }
    }
    val attempt = read
    if (attempt != null) {
        key(attempt) {
            if (attempt.page == OrganizationReadPage.Work) {
                CoreWorkSurface(sessions, active, onBack = { read = null })
            } else CoreOrganizationRead(
                active, sessions, current = { read === attempt && sessions.state.value === active },
                onBack = { read = null },
                onRetry = { if (read === attempt) read = OrganizationReadAttempt(attempt.page) },
            )
        }
        return
    }
    CoreOrganizationContent(
        state, active.session.orgId, active.session.role, choices, { retry++ },
        loadChoices = ::loadChoices, closeChoices = { choices = OrganizationChoices.Closed }, onSwitch = onSwitch, signOut = signOut,
        onOpen = { page ->
            if (read == null && state is OrganizationEntryState.Ready && sessions.state.value === active) {
                read = OrganizationReadAttempt(page)
            }
        },
    )
}

@Composable
private fun CoreOrganizationContent(
    state: OrganizationEntryState,
    organizationId: String,
    role: CoreRole,
    choices: OrganizationChoices,
    retry: () -> Unit,
    loadChoices: (String?) -> Unit,
    closeChoices: () -> Unit,
    onSwitch: (CoreMembership) -> Unit,
    signOut: () -> Unit,
    onOpen: (OrganizationReadPage) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Organization", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        when (state) {
            OrganizationEntryState.Loading -> Text("Checking organization setup…",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OrganizationEntryState.Unready -> Text("This organization has not finished setup. Continue in the web workspace.")
            OrganizationEntryState.Unavailable -> Text("Could not verify this organization.",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            is OrganizationEntryState.Ready -> {
                Text(state.profile.displayName ?: organizationId, Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                Text("Role: ${role.name}")
                Text(when (state.access) {
                    CoreEntitlementStatus.active -> "Organization access is active."
                    CoreEntitlementStatus.scheduled -> "Organization access is scheduled."
                    CoreEntitlementStatus.expired -> "Organization access has expired."
                    CoreEntitlementStatus.suspended -> "Organization access is suspended."
                    CoreEntitlementStatus.unconfigured -> "Organization access is unconfigured."
                    null -> "Could not verify organization access."
                }, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                WhumpoPrimaryButton(onClick = { onOpen(OrganizationReadPage.Today) }) { Text("Open Today") }
                WhumpoQuietButton(onClick = { onOpen(OrganizationReadPage.Work) }) { Text("Open Work") }
            }
        }
        if (state == OrganizationEntryState.Unready || state == OrganizationEntryState.Unavailable) {
            WhumpoQuietButton(onClick = retry) { Text("Check again") }
        }
        when (choices) {
            OrganizationChoices.Closed -> WhumpoQuietButton(onClick = { loadChoices(null) }) { Text("Switch organization") }
            OrganizationChoices.Loading -> Text("Checking your organizations…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OrganizationChoices.Unavailable -> {
                Text("Could not verify your organization choices.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                WhumpoQuietButton(onClick = { loadChoices(null) }) { Text("Check organizations again") }
                WhumpoQuietButton(onClick = closeChoices) { Text("Close organization choices") }
            }
            is OrganizationChoices.Loaded -> {
                Text("Choose an organization", Modifier.semantics { heading() })
                val otherOrganizations = choices.page.items.filter { !it.active }
                if (otherOrganizations.isEmpty()) Text("No other organizations on this page.")
                otherOrganizations.forEach { membership ->
                    WhumpoQuietButton(onClick = { onSwitch(membership) }) {
                        Text("Switch to ${membership.orgId} · ${membership.role.name}")
                    }
                }
                choices.page.nextCursor?.let { cursor ->
                    WhumpoQuietButton(onClick = { loadChoices(cursor) }) { Text("More organizations") }
                }
                WhumpoQuietButton(onClick = closeChoices) { Text("Close organization choices") }
            }
        }
        WhumpoQuietButton(onClick = signOut) { Text("Sign out of Core") }
    }
}


private enum class OrganizationReadPage(val title: String, val emptyLabel: String) {
    Today("Today", "Nothing needs your attention today."),
    Work("Work", "No tasks here."),
}

private class OrganizationReadAttempt(val page: OrganizationReadPage)
private data class OrganizationReadGroup(val heading: String, val rows: List<String>, val truncated: Boolean = false)

private sealed interface OrganizationReadState {
    data object Loading : OrganizationReadState
    data object Unavailable : OrganizationReadState
    data class Loaded(val groups: List<OrganizationReadGroup>) : OrganizationReadState
}

@Composable
private fun CoreOrganizationRead(
    active: CoreOrganizationSessionController.State.Active,
    sessions: CoreOrganizationSessionController,
    current: () -> Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var state by remember { mutableStateOf<OrganizationReadState>(OrganizationReadState.Loading) }
    LaunchedEffect(Unit) {
        if (!current()) return@LaunchedEffect
        try {
            val groups = todayGroups(sessions.today().briefing)
            coroutineContext.ensureActive()
            sessions.commitIfCurrent(active.epoch) {
                if (current()) state = OrganizationReadState.Loaded(groups)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            if (sessions.state.value != CoreOrganizationSessionController.State.SignedOut) {
                sessions.commitIfCurrent(active.epoch) {
                    if (current()) state = OrganizationReadState.Unavailable
                }
            }
        }
    }
    BackHandler(onBack = onBack)
    CoreOrganizationReadContent(OrganizationReadPage.Today, state, onBack, onRetry)
}

@Composable
private fun CoreOrganizationReadContent(
    page: OrganizationReadPage,
    state: OrganizationReadState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(page.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        WhumpoQuietButton(onClick = onBack) { Text("Back to organization") }
        when (state) {
            OrganizationReadState.Loading -> Text("Loading ${page.title}…",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OrganizationReadState.Unavailable -> {
                Text("Could not verify ${page.title}.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                WhumpoQuietButton(onClick = onRetry) { Text("Try again") }
            }
            is OrganizationReadState.Loaded -> {
                if (state.groups.isEmpty()) Text(page.emptyLabel)
                state.groups.forEach { group ->
                    Text(group.heading, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    group.rows.forEach { row -> Text(row) }
                    if (group.truncated) Text("Showing the first results.")
                }
            }
        }
    }
}

private fun todayGroups(briefing: CoreTodayBriefing): List<OrganizationReadGroup> {
    val sections = briefing.sections
    val site = sections.siteActivity
    return listOf(
        OrganizationReadGroup("Task decisions", sections.approvals.items.map { "Approval waiting for a decision" }, sections.approvals.truncated),
        OrganizationReadGroup("Needs attention", sections.blocked.items.map { it.title }, sections.blocked.truncated),
        OrganizationReadGroup("Deadlines", sections.deadlines.items.map { it.title }, sections.deadlines.truncated),
        OrganizationReadGroup("Verified results", sections.verified.items.map {
            when (it.operation) {
                CoreTodayTaskOperation.Create -> "Task created"
                CoreTodayTaskOperation.Update, CoreTodayTaskOperation.Transition -> "Task updated"
                CoreTodayTaskOperation.Move -> "Task moved"
                CoreTodayTaskOperation.CreateSelected -> "Selected tasks created"
            }
        }, sections.verified.truncated),
        OrganizationReadGroup("Website activity", site?.items.orEmpty().map {
            if (it.source.kind.review) "Website change awaiting review" else "Website change verified"
        }, site?.truncated == true),
    ).filter { it.rows.isNotEmpty() || it.truncated }
}
