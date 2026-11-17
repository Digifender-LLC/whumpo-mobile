package com.whumpo.android.core

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whumpo.android.ui.ownership.CoreOwnerInvitationsActions
import com.whumpo.android.ui.ownership.CoreOwnerInvitationsContent
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreOwnerInvitationsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CoreOwnerInvitationsPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val controller = remember(sessions, active, lifecycle) {
        CoreOwnerInvitationsController(sessions, active, canInteract = {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        })
    }
    val state by controller.state.collectAsState()
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> {
                    foreground = false
                    controller.clearCode()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.retire()
        }
    }
    LaunchedEffect(controller, foreground) { if (foreground) controller.refreshSeats() }
    LaunchedEffect(controller, state.codeExpiresAt) {
        state.codeExpiresAt?.let {
            delay((it - System.currentTimeMillis()).coerceAtLeast(0))
            controller.clearCode()
        }
    }
    val leave = {
        controller.retire()
        close()
    }
    BackHandler(onBack = leave)
    CoreOwnerInvitationsContent(state, CoreOwnerInvitationsActions(
        refreshSeats = { scope.launch { controller.refreshSeats() } },
        history = { more -> scope.launch { controller.loadHistory(more) } },
        issue = { role -> scope.launch { controller.issue(role) } },
        revoke = { invitation -> scope.launch { controller.revoke(invitation) } },
        retry = { scope.launch { controller.retry() } },
        copy = {
            scope.launch {
                controller.copyCode { code ->
                    val clip = ClipData.newPlainText("Invitation code", code)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }
            }
        },
        clearCode = controller::clearCode,
        close = leave,
    ))
}
