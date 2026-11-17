package com.whumpo.android.core

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.auth.ActivityTracker
import com.whumpo.android.security.KeystoreSecretBox
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
import com.whumpo.core.api.CoreWorkWebOrigin
import com.whumpo.core.api.coreApiClient
import com.whumpo.core.account.CoreOfflineWorkStore
import com.whumpo.android.ui.domains.SiteDomainActions
import com.whumpo.android.ui.domains.SiteDomainContent
import com.whumpo.android.ui.domains.SiteHostnameActions
import com.whumpo.android.ui.domains.SiteHostnameContent
import com.whumpo.android.ui.domains.rememberDomainTime
import com.whumpo.core.auth.CoreSiteDomainController
import com.whumpo.core.auth.CoreSiteHostnameController
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreOfflineWorkController
import com.whumpo.core.auth.CoreOwnershipController
import com.whumpo.core.auth.CorePartnerController
import com.whumpo.core.auth.CoreConnectedOnboardingController
import com.whumpo.core.auth.CoreConnectedSetupState
import com.whumpo.core.auth.CoreBusinessDiscoveryController
import com.whumpo.core.auth.CoreDiscoveryHandoffController
import com.whumpo.core.auth.DiscoveryHandoffState
import com.whumpo.android.ui.onboarding.BusinessDiscoveryContent
import com.whumpo.android.ui.onboarding.ConnectedSetupContent
import com.whumpo.android.ui.onboarding.ConnectedSetupNextStepContent
import com.whumpo.android.ui.onboarding.DiscoveryHandoffContent
import com.whumpo.android.ui.onboarding.DiscoveryHandoffInventory
import com.whumpo.android.ui.ownership.CoreOwnershipSection
import com.whumpo.android.ui.work.OfflineWorkActions
import com.whumpo.android.ui.work.OfflineWorkContent
import java.io.File
import kotlinx.coroutines.delay
import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.auth.CoreAdaptiveHomeController
import com.whumpo.core.auth.CoreAdaptiveProfileCache
import com.whumpo.core.auth.CoreStaticShortcut
import com.whumpo.core.auth.CoreStaticShortcutRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Debug harness only. The caller must set up the authorized local Worker and qualified RP. */
class CorePreviewActivity : ComponentActivity() {
    private lateinit var api: CoreApiClient
    private lateinit var controller: CoreOrganizationSessionController
    private lateinit var account: CoreAccountSecurityController
    private val activities = ActivityTracker()
    private var adaptiveForeground by mutableStateOf(false)
    private var adaptiveGeneration by mutableStateOf(0L)
    private var adaptiveCacheFailure by mutableStateOf(false)
    private var shortcutReadyEpoch: () -> Long? = { null }
    private var shortcut by mutableStateOf<CoreStaticShortcutRequest?>(null)
    private var shortcutLifetime = 0L
    private var shortcutWindowFocused by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val origin = BuildConfig.WHUMPO_LOCAL_CORE_ORIGIN
        val rpId = BuildConfig.WHUMPO_LOCAL_CORE_RP.takeIf(String::isNotBlank)
        api = coreApiClient(CoreOrigin.allowlisted(origin, setOfNotNull(rpId?.let { "https://$it" }), debugLoopback = true))
        val adaptiveCache = CoreAdaptiveProfileCache(File(noBackupFilesDir, "core-adaptive-home"))
        controller = CoreOrganizationSessionController(api, onAuthorityLost = { expired ->
            if (!adaptiveCache.eraseActor(api.origin, expired.userId)) adaptiveCacheFailure = true
        })
        account = CoreAccountSecurityController(api, controller, rpId)
        val offlineWork = CoreOfflineWorkStore(File(noBackupFilesDir, "core-offline-work")) {
            KeystoreSecretBox("whumpo.core.offline-work.v1")
        }
        val passkeys = NativeAccountPasskeys(applicationContext, activities)
        val workWebOrigin = CoreWorkWebOrigin.parse(BuildConfig.WHUMPO_LOCAL_WORK_WEB_ORIGIN)
        setContent {
            WhumpoEntryTheme {
                Surface {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        CorePreview(controller, account, passkeys, offlineWork, workWebOrigin, passkeysConfigured = rpId != null,
                            adaptiveForeground = adaptiveForeground, adaptiveGeneration = adaptiveGeneration,
                            isForegroundGeneration = { adaptiveForeground && it == adaptiveGeneration },
                            adaptiveCache = adaptiveCache, cacheFailure = { adaptiveCacheFailure = true },
                            eraseCacheActor = { if (!adaptiveCache.eraseActor(api.origin, it)) adaptiveCacheFailure = true },
                            showCacheFailure = adaptiveCacheFailure, shortcut = shortcut,
                            shortcutForeground = adaptiveForeground && shortcutWindowFocused,
                            isShortcutForeground = { adaptiveForeground && hasWindowFocus() },
                            publishShortcutReady = { shortcutReadyEpoch = it },
                            shortcutLifetime = { shortcutLifetime }, interruptShortcut = ::interruptShortcut,
                            finishShortcut = { if (shortcut === it) shortcut = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val current = (controller.state.value as? CoreOrganizationSessionController.State.Active)?.epoch
        val ready = shortcutReadyEpoch()?.takeIf { it == current && controller.commitIfCurrent(it) {} }
        interruptShortcut()
        shortcut = CoreStaticShortcutRequest.receive(intent.action, ready, SystemClock.elapsedRealtime(), shortcutLifetime)
        updateShortcutForeground()
    }

    private fun updateShortcutForeground(hasFocus: Boolean = hasWindowFocus()) {
        shortcutWindowFocused = hasFocus
        if (shortcut?.observeForeground(adaptiveForeground, shortcutWindowFocused, SystemClock.elapsedRealtime()) == false) {
            interruptShortcut()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateShortcutForeground(hasFocus)
    }

    private fun interruptShortcut() {
        shortcutLifetime++
        shortcut = null
    }

    override fun onStart() {
        super.onStart()
        activities.started(this)
    }

    override fun onResume() {
        super.onResume()
        adaptiveForeground = true
        updateShortcutForeground()
    }

    override fun onPause() {
        adaptiveForeground = false
        adaptiveGeneration++
        updateShortcutForeground()
        super.onPause()
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
    offlineWork: CoreOfflineWorkStore,
    workWebOrigin: CoreWorkWebOrigin?,
    passkeysConfigured: Boolean,
    adaptiveForeground: Boolean,
    adaptiveGeneration: Long,
    isForegroundGeneration: (Long) -> Boolean,
    adaptiveCache: CoreAdaptiveProfileCache,
    cacheFailure: () -> Unit,
    eraseCacheActor: (String) -> Unit,
    showCacheFailure: Boolean,
    shortcut: CoreStaticShortcutRequest?,
    shortcutForeground: Boolean,
    isShortcutForeground: () -> Boolean,
    publishShortcutReady: (() -> Long?) -> Unit,
    shortcutLifetime: () -> Long,
    interruptShortcut: () -> Unit,
    finishShortcut: (CoreStaticShortcutRequest) -> Unit,
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
    var ownershipSignInReview by remember(active?.epoch) { mutableStateOf(false) }
    val actions = rememberCoroutineScope()
    var message by remember(active?.epoch) { mutableStateOf<String?>(null) }
    var memberships by remember(active?.epoch) { mutableStateOf<CoreMembershipPage?>(null) }
    var page by remember(active?.epoch) { mutableStateOf<OrganizationPreview?>(null) }
    val domains = remember(controller, active) {
        active?.takeIf { it.session.role == CoreRole.owner }?.let { CoreSiteDomainController(controller, it) }
    }
    DisposableEffect(domains) { onDispose { domains?.retire() } }
    var showDomains by remember(active?.epoch) { mutableStateOf(false) }
    var showHostname by remember(active?.epoch) { mutableStateOf(false) }
    var restoreHostnameFocus by remember(active?.epoch) { mutableStateOf(false) }
    var showWork by remember(active?.epoch) { mutableStateOf(false) }
    var showInvitations by remember(active?.epoch) { mutableStateOf(false) }
    var showOfflineWork by remember(active?.epoch) { mutableStateOf(false) }
    var discoveryWorkTask by remember(active?.epoch) { mutableStateOf<CoreWorkTask?>(null) }
    var showToday by remember(active?.epoch) { mutableStateOf(false) }
    var shortcutToday by remember(active?.epoch) { mutableStateOf(false) }
    var showPartner by remember(active?.epoch) { mutableStateOf(false) }
    var showDiscovery by remember(active?.epoch) { mutableStateOf(false) }
    var nextStepChosen by remember(active?.epoch) { mutableStateOf(false) }
    var showHandoff by remember(active?.epoch) { mutableStateOf(false) }
    var showPartnerSample by remember(state) { mutableStateOf(false) }
    var showOnboarding by remember(state) { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    val securityPresented = active == null && showSecurity
    val focus = remember(controller, active, ready) {
        active?.takeIf { ready }?.let {
            CoreAdaptiveHomeController(controller, it, cache = adaptiveCache, onCacheFailure = cacheFailure)
        }
    }
    fun shortcutHubEpoch(): Long? {
        val session = active ?: return null
        val liveSetup = setup?.state?.value ?: return null
        val ownershipState = ownership?.state?.value
        if (!liveSetup.canOpenWorkspace || liveSetup.readiness?.orgId != session.session.orgId ||
            page == null || liveSetup.busy || (!nextStepChosen && liveSetup.nextStepProject(session.session.orgId) != null) ||
            ownershipSignInReview || ownershipState?.review != null || ownershipState?.busy == true ||
            showWork || showToday || showOfflineWork || showPartner || showInvitations || showDiscovery ||
            showHandoff || showDomains || showHostname) return null
        return session.epoch
    }
    SideEffect { publishShortcutReady(::shortcutHubEpoch) }
    LaunchedEffect(shortcut, shortcutForeground, adaptiveGeneration, active?.epoch) {
        val request = shortcut ?: return@LaunchedEffect
        if (!shortcutForeground) return@LaunchedEffect
        try {
            val session = active ?: return@LaunchedEffect
            fun currentEpoch(): Long? = session.epoch.takeIf {
                shortcutHubEpoch() == it && isShortcutForeground() &&
                    isForegroundGeneration(adaptiveGeneration) && controller.commitIfCurrent(it) {}
            }
            val admissionLifetime = shortcutLifetime()
            if (!request.verify(::currentEpoch, SystemClock::elapsedRealtime, shortcutLifetime) {
                    controller.organizationWhenReady(session) != null
                }) return@LaunchedEffect
            controller.commitIfCurrent(session.epoch) {
                if (currentEpoch() == null || shortcutLifetime() != admissionLifetime) return@commitIfCurrent
                when (request.destination) {
                    CoreStaticShortcut.Today -> { shortcutToday = true; showToday = true }
                    CoreStaticShortcut.Work -> { showWork = true }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed current readiness read leaves ordinary workspace navigation in place.
        } finally {
            finishShortcut(request)
        }
    }
    val focusForeground = focus != null && adaptiveForeground && !securityPresented && !(showToday && shortcutToday)
    var primaryAvailability by remember(focus, adaptiveGeneration) { mutableStateOf(emptySet<CoreAdaptiveCategory>()) }
    var todayAvailability by remember(focus, adaptiveGeneration) { mutableStateOf(emptySet<CoreAdaptiveCategory>()) }
    var requestedFocus by remember(focus, adaptiveGeneration) { mutableStateOf<CoreAdaptiveCategory?>(null) }
    DisposableEffect(focus, focusForeground, adaptiveGeneration) {
        focus?.foreground(focusForeground) { isForegroundGeneration(adaptiveGeneration) }
        onDispose { focus?.foreground(false) }
    }
    LaunchedEffect(focus, focusForeground, adaptiveGeneration) {
        if (!focusForeground) requestedFocus = null
        if (focus != null && focusForeground) {
            while (true) {
                delay(300_000)
                if (showToday && isForegroundGeneration(adaptiveGeneration)) focus.refresh()
            }
        }
    }
    fun destinationRead(category: CoreAdaptiveCategory, readable: Boolean) {
        if (!focusForeground || !isForegroundGeneration(adaptiveGeneration) || active == null) return
        controller.commitIfCurrent(active.epoch) {
            primaryAvailability = if (readable) primaryAvailability + category else primaryAvailability - category
            focus.availability(primaryAvailability + todayAvailability)
            if (requestedFocus == category) {
                requestedFocus = null
                if (readable) actions.launch { if (isForegroundGeneration(adaptiveGeneration)) focus.open(category) }
            }
        }
    }
    val accountState by account.state.collectAsState()
    var cursor by remember(active?.epoch) { mutableStateOf<String?>(null) }
    LaunchedEffect(active?.epoch) {
        if (active != null) {
            controller.commitIfCurrent(active.epoch) { showSecurity = false }
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
    // Keep the organization and Work refresh effects outside the offline pane.
    if (active != null && ready && active.session.role != CoreRole.viewer && showOfflineWork) {
        key(active.epoch) {
            CoreOfflineWorkPreview(controller, active, offlineWork,
                openTask = { task ->
                    controller.commitIfCurrent(active.epoch) {
                        discoveryWorkTask = task
                        showOfflineWork = false
                    }
                },
                signOut = {
                    account.clear()
                    eraseCacheActor(active.session.userId)
                    showSecurity = false
                },
                close = { showOfflineWork = false },
            )
        }
        return
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
        interruptShortcut()
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
        interruptShortcut()
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
            } finally {
                if (controller.state.value is CoreOrganizationSessionController.State.SignedOut) {
                    active?.session?.userId?.let(eraseCacheActor)
                }
            }
        }
    }
    val freshSignIn: () -> Unit = {
        interruptShortcut()
        account.clear()
        showSecurity = true
    }
    val openDiscovery: (() -> Unit)? = if (discovery == null) null else { { interruptShortcut(); showDiscovery = true } }
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
    val nextStepProject = active?.let { setupState.nextStepProject(it.session.orgId) }
    if (active != null && setup != null && discovery != null && nextStepProject != null && !nextStepChosen) {
        fun continueSetup(review: Boolean) {
            controller.commitIfCurrent(active.epoch) {
                if (!nextStepChosen && setup.state.value.nextStepProject(active.session.orgId) == nextStepProject) {
                    nextStepChosen = true
                    showDiscovery = review
                }
            }
        }
        BackHandler { continueSetup(review = false) }
        ConnectedSetupNextStepContent(
            onReview = { continueSetup(review = true) },
            onContinue = { continueSetup(review = false) },
            modifier = Modifier.fillMaxSize(),
        )
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
    if (securityPresented) {
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
    if (active != null && ready && active.session.role == CoreRole.owner && showHostname) {
        key(active.epoch) {
            CoreHostnamePreview(controller, active, freshSignIn) {
                showHostname = false
                restoreHostnameFocus = true
            }
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
                openOfflineWork = { showOfflineWork = true },
                workWebOrigin = workWebOrigin,
                signOut = signOut,
                onReadable = { destinationRead(CoreAdaptiveCategory.Work, it) },
                calendarForeground = adaptiveForeground,
                calendarGeneration = adaptiveGeneration,
                isCalendarForeground = { isForegroundGeneration(adaptiveGeneration) },
            ) {
                requestedFocus = null
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
                close = { requestedFocus = null; showPartner = false },
                onReadable = { destinationRead(CoreAdaptiveCategory.Partner, it) },
            )
        }
        return
    }
    if (active != null && ready && showToday) {
        key(active.epoch) {
            LaunchedEffect(focus, focusForeground, adaptiveGeneration) {
                if (focus != null && focusForeground) {
                    suspend fun readable(read: suspend () -> Unit): Boolean = try {
                        read()
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                    val destinations = buildSet {
                        if (readable { controller.work() }) add(CoreAdaptiveCategory.Work)
                        if (partner != null && readable { partner.readableDirectory() }) add(CoreAdaptiveCategory.Partner)
                    }
                    if (!isForegroundGeneration(adaptiveGeneration)) return@LaunchedEffect
                    controller.commitIfCurrent(active.epoch) {
                        primaryAvailability = destinations
                        focus.availability(primaryAvailability + todayAvailability)
                    }
                    focus.enterToday()
                }
            }
            DisposableEffect(focus) { onDispose { focus?.leaveToday() } }
            CoreTodayPreview(controller, active, page?.profile?.displayName ?: active.session.orgId,
                focus = focus.takeIf { focusForeground },
                readGeneration = if (focusForeground) adaptiveGeneration else -1L,
                canCreditOpen = { focusForeground && isForegroundGeneration(adaptiveGeneration) },
                availability = { destinations ->
                    if (focusForeground && isForegroundGeneration(adaptiveGeneration)) controller.commitIfCurrent(active.epoch) {
                        todayAvailability = destinations
                        focus.availability(primaryAvailability + destinations)
                    }
                },
                openFocus = { category ->
                    controller.commitIfCurrent(active.epoch) {
                        if (focusForeground && isForegroundGeneration(adaptiveGeneration) && category in primaryAvailability) {
                            requestedFocus = category
                            showToday = false
                            if (category == CoreAdaptiveCategory.Work) showWork = true
                            if (category == CoreAdaptiveCategory.Partner) showPartner = true
                        }
                    }
                },
            ) { showToday = false }
        }
        return
    }
    if (active?.session?.role == CoreRole.owner && ready && showInvitations) {
        key(active.epoch) {
            CoreOwnerInvitationsPreview(controller, active) { showInvitations = false }
        }
        return
    }
    CorePreviewContent(state, page, memberships, message ?: "Focus cache could not be cleared on this device.".takeIf { showCacheFailure },
        ownership = {
            if (active != null && ownership != null) {
                key(active.epoch) {
                    CoreOwnershipSection(ownership, active.session.userId, active.session.role,
                        onSignInReview = { ownershipSignInReview = it },
                        onReviewOpened = interruptShortcut) {
                        account.clear()
                        showSecurity = true
                    }
                }
            }
        },
        openToday = { interruptShortcut(); shortcutToday = false; showToday = true },
        openWork = { interruptShortcut(); requestedFocus = CoreAdaptiveCategory.Work; showWork = true },
        openInvitations = if (active?.session?.role == CoreRole.owner && ready) ({
            interruptShortcut()
            showInvitations = true
        }) else null,
        openPartner = { interruptShortcut(); requestedFocus = CoreAdaptiveCategory.Partner; showPartner = true },
        openDiscovery = openDiscovery,
        openDomains = if (active != null && ready && domains != null) ({ interruptShortcut(); showDomains = true }) else null,
        openHostname = if (active?.session?.role == CoreRole.owner && ready) ({
            restoreHostnameFocus = false
            interruptShortcut()
            showHostname = true
        }) else null,
        restoreHostnameFocus = restoreHostnameFocus,
        tryPartner = { showPartnerSample = true },
        tryOnboarding = { showOnboarding = true },
        signIn = { interruptShortcut(); showSecurity = true },
        choose = choose,
        next = { cursor = it },
        signOut = signOut,
    )
}

@Composable
private fun CoreOfflineWorkPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    store: CoreOfflineWorkStore,
    openTask: (CoreWorkTask) -> Unit,
    signOut: () -> Unit,
    close: () -> Unit,
) {
    val controller = remember(sessions, active, store) { CoreOfflineWorkController(sessions, active, store) }
    DisposableEffect(controller) { onDispose { controller.retire() } }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val back: () -> Unit = {
        if (state.connected) {
            controller.offline()
            scope.launch { controller.load() }
        } else {
            close()
        }
    }
    LaunchedEffect(controller) { controller.load() }
    BackHandler(onBack = back)
    OfflineWorkContent(state, OfflineWorkActions(
        save = { title, description -> scope.launch { controller.save(title, description) } },
        discard = { scope.launch { controller.discard() } },
        reconnect = { scope.launch { controller.reconnect() } },
        chooseWorkspace = { scope.launch { controller.chooseWorkspace(it) } },
        changeWorkspace = controller::changeWorkspace,
        moreWorkspaces = { scope.launch { controller.moreWorkspaces() } },
        chooseProject = { scope.launch { controller.chooseProject(it) } },
        moreProjects = { scope.launch { controller.moreProjects() } },
        submit = { scope.launch { controller.submit(it) } },
        cancelReview = controller::cancelReview,
        checkTask = { scope.launch { controller.checkTask() } },
        openTask = openTask,
        back = back,
        signOut = {
            controller.retire()
            signOut()
        },
    ))
}

@Composable
private fun CoreHostnamePreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    signIn: () -> Unit,
    close: () -> Unit,
) {
    val controller = remember(sessions, active) { CoreSiteHostnameController(sessions, active) }
    DisposableEffect(controller) { onDispose { controller.retire() } }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val closeScreen = {
        if (state.review == null) close() else controller.cancelReview()
    }
    val now = rememberDomainTime(state.claims.orEmpty())
    val deadline = state.review?.expiresAt ?: state.pendingUntil
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(controller, deadline) {
        if (deadline != null) {
            delay((deadline - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(1))
            controller.expireReview()
        }
    }
    BackHandler(onBack = closeScreen)
    SiteHostnameContent(state, now, SiteHostnameActions(
        refresh = { scope.launch { controller.refresh() } },
        chooseDifferentProject = { scope.launch { controller.chooseDifferentProject() } },
        chooseWorkspace = { scope.launch { controller.chooseWorkspace(it) } },
        moreWorkspaces = { scope.launch { controller.moreWorkspaces() } },
        chooseProject = { scope.launch { controller.chooseProject(it) } },
        moreProjects = { scope.launch { controller.moreProjects() } },
        loadClaims = { scope.launch { controller.loadClaims() } },
        reviewClaim = { scope.launch { controller.reviewClaim(it) } },
        reviewDisable = controller::reviewDisable,
        confirm = { scope.launch { controller.confirm(it) } },
        cancelReview = controller::cancelReview,
        retry = { scope.launch { controller.retry() } },
        checkStatus = { scope.launch { controller.checkStatus() } },
        signIn = signIn,
        back = closeScreen,
    ))
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
    openInvitations: (() -> Unit)?,
    openPartner: () -> Unit,
    openDiscovery: (() -> Unit)?,
    openDomains: (() -> Unit)?,
    openHostname: (() -> Unit)?,
    restoreHostnameFocus: Boolean,
    tryPartner: () -> Unit,
    tryOnboarding: () -> Unit,
    signIn: () -> Unit,
    choose: (String) -> Unit,
    next: (String?) -> Unit,
    signOut: () -> Unit,
) {
    val hostnameFocus = remember { FocusRequester() }
    LaunchedEffect(openHostname != null, restoreHostnameFocus) {
        if (openHostname != null && restoreHostnameFocus) hostnameFocus.requestFocus()
    }
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
                if (openInvitations != null) {
                    WhumpoQuietButton(onClick = openInvitations, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Manage team invitations")
                    }
                }
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
                if (openHostname != null) {
                    WhumpoQuietButton(onClick = openHostname,
                        modifier = Modifier.focusRequester(hostnameFocus).heightIn(min = 48.dp)) {
                        Text("Website hostname")
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
