package com.whumpo.android.core

import android.os.Bundle
import android.view.WindowManager
import com.whumpo.android.BuildConfig
import com.whumpo.core.auth.CoreAccountSecurityController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.auth.ActivityTracker
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.coreApiClient
import com.whumpo.android.ui.domains.SiteDomainActions
import com.whumpo.android.ui.domains.SiteDomainContent
import com.whumpo.android.ui.domains.rememberDomainTime
import com.whumpo.core.auth.CoreSiteDomainController
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreOwnershipController
import com.whumpo.core.auth.CorePartnerController
import com.whumpo.core.auth.CoreConnectedOnboardingController
import com.whumpo.core.auth.CoreConnectedSetupState
import com.whumpo.core.auth.CoreBusinessDiscoveryController
import com.whumpo.core.auth.CoreDiscoveryHandoffController
import com.whumpo.core.auth.DiscoveryHandoffState
import com.whumpo.android.ui.onboarding.BusinessDiscoveryContent
import com.whumpo.android.ui.onboarding.ConnectedSetupContent
import com.whumpo.android.ui.onboarding.DiscoveryHandoffContent
import com.whumpo.android.ui.onboarding.DiscoveryHandoffInventory
import com.whumpo.android.ui.ownership.CoreOwnershipSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Debug harness only. The caller must set up the authorized local Worker and qualified RP. */
class CorePreviewActivity : ComponentActivity() {
    private lateinit var api: CoreApiClient
    private lateinit var controller: CoreOrganizationSessionController
    private lateinit var account: CoreAccountSecurityController
    private val activities = ActivityTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val origin = BuildConfig.WHUMPO_LOCAL_CORE_ORIGIN
        val rpId = BuildConfig.WHUMPO_LOCAL_CORE_RP.takeIf(String::isNotBlank)
        api = coreApiClient(CoreOrigin.allowlisted(origin, setOfNotNull(rpId?.let { "https://$it" }), debugLoopback = true))
        controller = CoreOrganizationSessionController(api)
        account = CoreAccountSecurityController(api, controller, rpId)
        val passkeys = NativeAccountPasskeys(applicationContext, activities)
        setContent {
            WhumpoEntryTheme {
                Surface {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CorePreview(controller, account, passkeys, passkeysConfigured = rpId != null)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activities.started(this)
    }

    override fun onStop() {
        activities.stopped(this)
        super.onStop()
    }

    override fun onDestroy() {
        account.clear()
        api.close()
        super.onDestroy()
    }
}

private data class OrganizationPreview(
    val profile: CoreOrganizationProfile,
    val coverage: CoreOrganizationEntitlements,
)

@Composable
internal fun CorePreview(
    controller: CoreOrganizationSessionController,
    account: CoreAccountSecurityController,
    passkeys: NativeAccountPasskeys,
    passkeysConfigured: Boolean,
) {
    val state by controller.state.collectAsState()
    val active = state as? CoreOrganizationSessionController.State.Active
    val partner = remember(controller, active) { active?.let { CorePartnerController(controller, it) } }
    DisposableEffect(partner) {
        onDispose { partner?.retire() }
    }
    val setup = remember(controller, active) { active?.let { CoreConnectedOnboardingController(controller, it) } }
    DisposableEffect(setup) { onDispose { setup?.retire() } }
    val discovery = remember(controller, active) {
        active?.takeIf { it.session.role == CoreRole.owner }?.let { CoreBusinessDiscoveryController(controller, it) }
    }
    DisposableEffect(discovery) { onDispose { discovery?.retire() } }
    val handoff = remember(controller, active, discovery) {
        if (active != null && discovery != null) CoreDiscoveryHandoffController(controller, active, discovery) else null
    }
    DisposableEffect(handoff) { onDispose { handoff?.retire() } }
    val setupState = key(active?.epoch) {
        setup?.state?.collectAsState()?.value ?: CoreConnectedSetupState()
    }
    val ready = setupState.canOpenWorkspace && setupState.readiness?.orgId == active?.session?.orgId
    val ownership = remember(controller, active) { active?.let { CoreOwnershipController(controller, it) } }
    val actions = rememberCoroutineScope()
    var message by remember(active?.epoch) { mutableStateOf<String?>(null) }
    var memberships by remember(active?.epoch) { mutableStateOf<CoreMembershipPage?>(null) }
    var page by remember(active?.epoch) { mutableStateOf<OrganizationPreview?>(null) }
    val domains = remember(controller, active) {
        active?.takeIf { it.session.role == CoreRole.owner }?.let { CoreSiteDomainController(controller, it) }
    }
    DisposableEffect(domains) { onDispose { domains?.retire() } }
    var showDomains by remember(active?.epoch) { mutableStateOf(false) }
    var showWork by remember(active?.epoch) { mutableStateOf(false) }
    var discoveryWorkTask by remember(active?.epoch) { mutableStateOf<CoreWorkTask?>(null) }
    var showToday by remember(active?.epoch) { mutableStateOf(false) }
    var showPartner by remember(active?.epoch) { mutableStateOf(false) }
    var showDiscovery by remember(active?.epoch) { mutableStateOf(false) }
    var showHandoff by remember(active?.epoch) { mutableStateOf(false) }
    var showPartnerSample by remember(state) { mutableStateOf(false) }
    var showOnboarding by remember(state) { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    val accountState by account.state.collectAsState()
    var cursor by remember(active?.epoch) { mutableStateOf<String?>(null) }
    LaunchedEffect(active?.epoch) {
        if (active != null) {
            while (true) {
                val remaining = active.session.expiresAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    controller.expireIfCurrent(active.epoch)
                    break
                }
                delay(minOf(remaining, 60_000))
            }
        }
    }
    LaunchedEffect(setup) { setup?.refresh() }
    LaunchedEffect(active?.epoch, cursor) {
        if (active != null) {
            try {
                val choices = controller.organizations(cursor)
                controller.commitIfCurrent(active.epoch) { memberships = choices }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                controller.commitIfCurrent(active.epoch) { message = "Could not load organizations." }
            }
        }
    }
    LaunchedEffect(active?.epoch, ready) {
        if (active != null && ready) {
            try {
                val snapshot = OrganizationPreview(controller.profile(), controller.entitlements())
                controller.commitIfCurrent(active.epoch) { page = snapshot }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                controller.commitIfCurrent(active.epoch) { message = "Could not load organization details." }
            }
        }
    }
    val choose: (String) -> Unit = { org ->
        message = null
        actions.launch {
            try {
                controller.switchOrganization(org)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "Could not switch organizations. Sign in again."
            }
        }
    }
    val signOut: () -> Unit = {
        message = null
        actions.launch {
            try {
                account.leave()
                showSecurity = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                account.clear()
                showSecurity = false
            }
        }
    }
    val freshSignIn: () -> Unit = {
        account.clear()
        showSecurity = true
    }
    val openDiscovery: (() -> Unit)? = if (discovery == null) null else { { showDiscovery = true } }
    if (active != null && ready && handoff != null && showDiscovery && showHandoff) {
        key(active.epoch) {
            val handoffState by handoff.state.collectAsState()
            BackHandler { showHandoff = false }
            DiscoveryHandoffContent(
                state = handoffState,
                onSubmit = { review -> actions.launch { handoff.submit(review) } },
                onChooseWorkspace = { choice -> actions.launch { handoff.chooseWorkspace(choice) } },
                onMoreWorkspaces = { actions.launch { handoff.moreWorkspaces() } },
                onChooseProject = { choice -> actions.launch { handoff.chooseProject(choice) } },
                onMoreProjects = { actions.launch { handoff.moreProjects() } },
                onInspect = { id -> actions.launch { handoff.inspect(id) } },
                onReviewApproval = { id -> actions.launch { handoff.reviewApproval(id) } },
                onDecision = { approval, choice -> actions.launch { handoff.decide(approval, choice) } },
                onRetry = { actions.launch { handoff.retry() } },
                onOpenWork = { task ->
                    controller.commitIfCurrent(active.epoch) {
                        if (handoff.state.value.verifiedTask == task && !handoff.state.value.pending && !handoff.state.value.busy) {
                            discoveryWorkTask = task
                            showHandoff = false
                            showDiscovery = false
                            showWork = true
                        }
                    }
                },
                onSignIn = freshSignIn,
                onBack = { showHandoff = false },
            )
        }
        return
    }
    if (active != null && discovery != null && showDiscovery && (ready || setupState.canEdit)) {
        key(active.epoch) {
            val discoveryState by discovery.state.collectAsState()
            val handoffState = handoff?.state?.collectAsState()?.value ?: DiscoveryHandoffState()
            val reviewedRevision = discoveryState.snapshot?.revision
            LaunchedEffect(discovery, handoff, ready) {
                discovery.refresh()
                if (ready) handoff?.refreshInventory()
            }
            BackHandler { showDiscovery = false }
            BusinessDiscoveryContent(
                state = discoveryState,
                startWithReview = ready,
                onCommand = { command ->
                    reviewedRevision?.let { revision ->
                        actions.launch { discovery.submit(command, revision) }
                    }
                },
                onRefresh = { actions.launch { discovery.refresh() } },
                onRetry = { actions.launch { discovery.retry() } },
                onSignIn = freshSignIn,
                onLater = { showDiscovery = false },
                modifier = Modifier.fillMaxSize(),
                reviewForWork = if (ready && handoff != null && !handoffState.busy) { action, revision ->
                    showHandoff = true
                    actions.launch { handoff.beginReview(action, revision) }
                } else null,
                workHandoffs = {
                    if (ready && handoff != null) {
                        DiscoveryHandoffInventory(handoffState,
                            onInspect = { id ->
                                showHandoff = true
                                actions.launch { handoff.inspect(id) }
                            }, onResume = { showHandoff = true })
                    }
                },
            )
        }
        return
    }
    if (active != null && setup != null && !ready) {
        key(active.epoch) {
            ConnectedSetupContent(
                state = setupState,
                isOwner = setup.isOwner,
                onAnswer = { question, answer -> actions.launch { setup.answer(question, answer) } },
                onEdit = setup::editQuestion,
                onSkipRemaining = { actions.launch { setup.skipRemaining() } },
                onCreate = { actions.launch { setup.createWorkspace() } },
                onRetry = { actions.launch { setup.retry() } },
                onRefresh = { actions.launch { setup.refresh() } },
                onSignIn = freshSignIn,
                organizationActions = {
                    OrganizationActions(memberships, choose, { cursor = it }, signOut)
                    message?.let { Text(it) }
                },
                modifier = Modifier.fillMaxSize(),
                onDiscovery = openDiscovery,
            )
        }
        return
    }
    if (state == CoreOrganizationSessionController.State.SignedOut && showOnboarding) {
        CoreOnboardingPreview { showOnboarding = false }
        return
    }
    if (state == CoreOrganizationSessionController.State.SignedOut && showPartnerSample) {
        LocalPartnerSample { showPartnerSample = false }
        return
    }
    if (active == null && showSecurity) {
        AccountSecurityScreen(account, accountState, passkeys, passkeysConfigured) { showSecurity = false }
        return
    }
    if (active != null && ready && domains != null && showDomains) {
        key(active.epoch) {
            val domainState by domains.state.collectAsState()
            val closeDomain: () -> Unit = {
                if (domainState.claim == null) showDomains = false else domains.dismissClaim()
            }
            LaunchedEffect(domains) { domains.refresh() }
            BackHandler(onBack = closeDomain)
            SiteDomainContent(domainState, rememberDomainTime(domainState.claims), SiteDomainActions(
                refresh = { actions.launch { domains.refresh() } },
                chooseDifferentProject = { actions.launch { domains.chooseDifferentProject() } },
                chooseWorkspace = { choice -> actions.launch { domains.chooseWorkspace(choice) } },
                moreWorkspaces = { actions.launch { domains.moreWorkspaces() } },
                chooseProject = { choice -> actions.launch { domains.chooseProject(choice) } },
                moreProjects = { actions.launch { domains.moreProjects() } },
                inspect = { claim -> actions.launch { domains.inspect(claim) } },
                prepare = { hostname -> actions.launch { domains.prepare(hostname) } },
                check = { claim -> actions.launch { domains.check(claim) } },
                renew = { claim -> actions.launch { domains.renew(claim) } },
                retry = { actions.launch { domains.retry() } },
                signIn = freshSignIn,
                back = closeDomain,
            ))
        }
        return
    }
    if (active != null && ready && showWork) {
        key(active.epoch) {
            CoreWorkPreview(
                controller, active, page?.profile?.displayName ?: active.session.orgId,
                canCreateTask = active.session.role != CoreRole.viewer,
                canRecoverApplication = active.session.role == CoreRole.owner,
                initialTask = discoveryWorkTask,
            ) {
                showWork = false
                discoveryWorkTask = null
            }
        }
        return
    }
    if (active != null && ready && partner != null && showPartner) {
        key(active.epoch) {
            CorePartnerPreview(
                partner,
                page?.profile?.displayName ?: active.session.orgId,
                signInAgain = {
                    account.clear()
                    showSecurity = true
                },
                close = { showPartner = false },
            )
        }
        return
    }
    if (active != null && ready && showToday) {
        key(active.epoch) {
            CoreTodayPreview(controller, active.epoch, page?.profile?.displayName ?: active.session.orgId) {
                showToday = false
            }
        }
        return
    }
    CorePreviewContent(state, page, memberships, message,
        ownership = {
            if (active != null && ownership != null) {
                key(active.epoch) {
                    CoreOwnershipSection(ownership, active.session.userId, active.session.role) {
                        account.clear()
                        showSecurity = true
                    }
                }
            }
        },
        openToday = { showToday = true },
        openWork = { showWork = true },
        openPartner = { showPartner = true },
        openDiscovery = openDiscovery,
        openDomains = if (active != null && ready && domains != null) ({ showDomains = true }) else null,
        tryPartner = { showPartnerSample = true },
        tryOnboarding = { showOnboarding = true },
        signIn = { showSecurity = true },
        choose = choose,
        next = { cursor = it },
        signOut = signOut,
    )
}

@Composable
private fun CorePreviewContent(
    state: CoreOrganizationSessionController.State,
    page: OrganizationPreview?,
    memberships: CoreMembershipPage?,
    message: String?,
    ownership: @Composable () -> Unit,
    openToday: () -> Unit,
    openWork: () -> Unit,
    openPartner: () -> Unit,
    openDiscovery: (() -> Unit)?,
    openDomains: (() -> Unit)?,
    tryPartner: () -> Unit,
    tryOnboarding: () -> Unit,
    signIn: () -> Unit,
    choose: (String) -> Unit,
    next: (String?) -> Unit,
    signOut: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            CoreOrganizationSessionController.State.SignedOut -> {
                Text("Whumpo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Explore setup", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("Walk through setting up an account and planning your first steps.")
                Text("This offline preview uses no account or payment. It clears when you exit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                WhumpoPrimaryButton(onClick = tryOnboarding, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Try onboarding")
                }
                WhumpoQuietButton(onClick = tryPartner, modifier = Modifier.heightIn(min = 48.dp)) { Text("View offline Partner sample") }
                WhumpoQuietButton(onClick = signIn, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Account security and sign-in")
                }
            }
            CoreOrganizationSessionController.State.Transitioning -> Text("Checking session…")
            is CoreOrganizationSessionController.State.Active -> {
                Text(page?.profile?.displayName ?: "Organization", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("Role: ${state.session.role}")
                if (page == null) {
                    Text("Loading organization…")
                } else {
                    Text("Coverage: ${page.coverage.status}")
                    Text("Seats available: ${page.coverage.availableHumanSeats}")
                    Text(if (page.coverage.siteBuildAvailable) "Site building available" else "Site building unavailable")
                }
                ownership()
                Button(onClick = openToday) { Text("Open Today") }
                WhumpoQuietButton(onClick = openPartner, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Partner") }
                Button(onClick = openWork) { Text("Open Work") }
                if (openDiscovery != null) {
                    WhumpoQuietButton(onClick = openDiscovery, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Open business discovery")
                    }
                }
                if (openDomains != null) {
                    WhumpoQuietButton(onClick = openDomains, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Domain ownership")
                    }
                }
                OrganizationActions(memberships, choose, next, signOut)
            }
        }
        message?.let { Text(it) }
    }
}


@Composable
private fun OrganizationActions(
    memberships: CoreMembershipPage?,
    choose: (String) -> Unit,
    next: (String?) -> Unit,
    signOut: () -> Unit,
) {
    memberships?.items?.forEach { membership ->
        Button(enabled = !membership.active, onClick = { choose(membership.orgId) }) {
            Text("${membership.orgId} · ${membership.role}")
        }
    }
    memberships?.nextCursor?.let { cursor -> Button(onClick = { next(cursor) }) { Text("More organizations") } }
    if (memberships != null) Button(onClick = { next(null) }) { Text("First page") }
    Button(onClick = signOut) { Text("Sign out locally") }
}
