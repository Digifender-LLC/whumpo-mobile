package com.whumpo.android.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whumpo.android.auth.ActivityTracker
import com.whumpo.android.auth.CredentialManagerPasskeys
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.coreApiClient
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.PasskeyAuthenticator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Debug harness only. The caller must set up the authorized local Worker and qualified RP. */
class CorePreviewActivity : ComponentActivity() {
    private lateinit var api: CoreApiClient
    private lateinit var controller: CoreOrganizationSessionController
    private val activities = ActivityTracker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = coreApiClient(CoreOrigin.allowlisted("http://127.0.0.1:5173", emptySet(), debugLoopback = true))
        controller = CoreOrganizationSessionController(api)
        val passkeys = CredentialManagerPasskeys(applicationContext, activities)
        setContent {
            WhumpoTheme {
                Surface { CorePreview(controller, passkeys) }
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
        controller.clear()
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
internal fun CorePreview(controller: CoreOrganizationSessionController, passkeys: PasskeyAuthenticator) {
    val state by controller.state.collectAsState()
    val active = state as? CoreOrganizationSessionController.State.Active
    val actions = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var page by remember(active?.epoch) { mutableStateOf<OrganizationPreview?>(null) }
    var showWork by remember(active?.epoch) { mutableStateOf(false) }
    var showToday by remember(active?.epoch) { mutableStateOf(false) }
    var cursor by remember(active?.epoch) { mutableStateOf<String?>(null) }
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
    if (active != null && showWork) {
        key(active.epoch) {
            CoreWorkPreview(
                controller, active.epoch, page?.profile?.displayName ?: active.session.orgId,
                canCreateTask = active.session.role != CoreRole.viewer,
            ) {
                showWork = false
            }
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
        openToday = { showToday = true },
        openWork = { showWork = true },
        signIn = {
            message = null
            actions.launch {
                try {
                    message = when (controller.signIn(passkeys)) {
                        CoreOrganizationSessionController.SignInResult.SignedIn,
                        CoreOrganizationSessionController.SignInResult.Cancelled -> null
                        CoreOrganizationSessionController.SignInResult.NoCredential -> "Enroll a test passkey for this relying party first."
                        CoreOrganizationSessionController.SignInResult.Unavailable -> "Sign-in unavailable on this device. Check the passkey provider and app association."
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    message = "Sign-in unavailable. Check the local service and Android passkey origin."
                }
            }
        },
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
        signOut = { controller.clear(); message = null },
    )
}

@Composable
private fun CorePreviewContent(
    state: CoreOrganizationSessionController.State,
    page: OrganizationPreview?,
    message: String?,
    openToday: () -> Unit,
    openWork: () -> Unit,
    signIn: () -> Unit,
    choose: (String) -> Unit,
    next: (String?) -> Unit,
    signOut: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Whumpo organizations")
        when (state) {
            CoreOrganizationSessionController.State.SignedOut -> Button(onClick = signIn) { Text("Sign in with a passkey") }
            CoreOrganizationSessionController.State.Transitioning -> Text("Checking session…")
            is CoreOrganizationSessionController.State.Active -> {
                Text(page?.profile?.displayName ?: state.session.orgId)
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
                Button(onClick = openToday) { Text("Open Today") }
                Button(onClick = openWork) { Text("Open Work") }
                Button(onClick = signOut) { Text("Sign out locally") }
            }
        }
        message?.let { Text(it) }
    }
}
