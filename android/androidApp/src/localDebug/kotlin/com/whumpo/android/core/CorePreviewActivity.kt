package com.whumpo.android.core

import android.os.Bundle
import android.view.WindowManager
import com.whumpo.android.BuildConfig
import com.whumpo.core.auth.CoreAccountSecurityController
import androidx.activity.ComponentActivity
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
import com.whumpo.core.api.coreApiClient
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreOwnershipController
import com.whumpo.core.auth.CorePartnerController
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
    val memberships: CoreMembershipPage,
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
    val ownership = remember(controller, active) { active?.let { CoreOwnershipController(controller, it) } }
    val actions = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var page by remember(active?.epoch) { mutableStateOf<OrganizationPreview?>(null) }
    var showWork by remember(active?.epoch) { mutableStateOf(false) }
    var showToday by remember(active?.epoch) { mutableStateOf(false) }
    var showPartner by remember(active?.epoch) { mutableStateOf(false) }
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
    LaunchedEffect(active?.epoch, cursor) {
        if (active != null) {
            try {
                val snapshot = OrganizationPreview(controller.organizations(cursor), controller.profile(), controller.entitlements())
                controller.commitIfCurrent(active.epoch) { page = snapshot }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                controller.commitIfCurrent(active.epoch) { message = "Could not load organizations." }
            }
        }
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
    if (active != null && showWork) {
        key(active.epoch) {
            CoreWorkPreview(
                controller, active.epoch, page?.profile?.displayName ?: active.session.orgId,
                canCreateTask = active.session.role != CoreRole.viewer,
                canRecoverApplication = active.session.role == CoreRole.owner,
            ) {
                showWork = false
            }
        }
        return
    }
    if (active != null && partner != null && showPartner) {
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
    if (active != null && showToday) {
        key(active.epoch) {
            CoreTodayPreview(controller, active.epoch, page?.profile?.displayName ?: active.session.orgId) {
                showToday = false
            }
        }
        return
    }
    CorePreviewContent(state, page, message,
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
        tryPartner = { showPartnerSample = true },
        tryOnboarding = { showOnboarding = true },
        signIn = { showSecurity = true },
        choose = { org ->
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
        },
        next = { cursor = it },
        signOut = {
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
        },
    )
}

@Composable
private fun CorePreviewContent(
    state: CoreOrganizationSessionController.State,
    page: OrganizationPreview?,
    message: String?,
    ownership: @Composable () -> Unit,
    openToday: () -> Unit,
    openWork: () -> Unit,
    openPartner: () -> Unit,
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
                    page.memberships.items.forEach { membership ->
                        Button(enabled = !membership.active, onClick = { choose(membership.orgId) }) {
                            Text("${membership.orgId} · ${membership.role}")
                        }
                    }
                    page.memberships.nextCursor?.let { Button(onClick = { next(it) }) { Text("More organizations") } }
                    Button(onClick = { next(null) }) { Text("First page") }
                }
                ownership()
                Button(onClick = openToday) { Text("Open Today") }
                WhumpoQuietButton(onClick = openPartner, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Partner") }
                Button(onClick = openWork) { Text("Open Work") }
                Button(onClick = signOut) { Text("Sign out locally") }
            }
        }
        message?.let { Text(it) }
    }
}
