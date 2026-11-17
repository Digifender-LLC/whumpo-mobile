package com.whumpo.android.core

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
import androidx.compose.runtime.DisposableEffect
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
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreEntitlementStatus
import com.whumpo.core.api.CoreMembership
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreRole
import com.whumpo.core.auth.AccountPasskeys
import com.whumpo.core.auth.CoreAccountSecurityController
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CoreLauncher(api: CoreApiClient, passkeys: AccountPasskeys) {
    val sessions = remember(api) { CoreOrganizationSessionController(api) }
    val accounts = remember(api, sessions) {
        CoreAccountSecurityController(api, sessions, api.origin.value.removePrefix("https://"))
    }
    val session by sessions.state.collectAsStateWithLifecycle()
    val account by accounts.state.collectAsStateWithLifecycle()
    var showSignIn by remember(api) { mutableStateOf(false) }
    var switching by remember(api) { mutableStateOf(false) }
    var switchUnconfirmed by remember(api) { mutableStateOf(false) }
    var signOutUnconfirmed by remember(api) { mutableStateOf(false) }
    val actions = rememberCoroutineScope()
    DisposableEffect(accounts) {
        onDispose { accounts.clear() }
    }
    val active = session as? CoreOrganizationSessionController.State.Active
    LaunchedEffect(active) {
        if (active != null) {
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
                        CoreOrganizationEntry(active, sessions, onSessionEnded = {
                            accounts.clear()
                            showSignIn = false
                        }, onSwitch = { membership ->
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
                                            accounts.clear()
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
    onSessionEnded: () -> Unit,
    onSwitch: (CoreMembership) -> Unit,
    signOut: () -> Unit,
) {
    var state by remember { mutableStateOf<OrganizationEntryState>(OrganizationEntryState.Loading) }
    var retry by remember { mutableStateOf(0) }
    var choices by remember { mutableStateOf<OrganizationChoices>(OrganizationChoices.Closed) }
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
                if (sessions.state.value == CoreOrganizationSessionController.State.SignedOut) onSessionEnded()
                else sessions.commitIfCurrent(active.epoch) { choices = OrganizationChoices.Unavailable }
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
            if (sessions.state.value == CoreOrganizationSessionController.State.SignedOut) {
                onSessionEnded()
            } else {
                sessions.commitIfCurrent(active.epoch) { state = OrganizationEntryState.Unavailable }
            }
        }
    }
    CoreOrganizationContent(
        state, active.session.orgId, active.session.role, choices, { retry++ },
        loadChoices = ::loadChoices, closeChoices = { choices = OrganizationChoices.Closed }, onSwitch = onSwitch, signOut = signOut,
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
