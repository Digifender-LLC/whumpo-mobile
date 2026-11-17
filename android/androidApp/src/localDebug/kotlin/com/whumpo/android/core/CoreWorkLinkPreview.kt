package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whumpo.android.ui.work.WorkLinkContent
import com.whumpo.android.ui.work.WorkLinkResult
import com.whumpo.core.api.CoreWorkLink
import com.whumpo.core.api.CoreWorkWebOrigin
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.linkedWorkTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun CoreWorkLinkPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    origin: CoreWorkWebOrigin,
    organizationName: String,
    close: () -> Unit,
    signOut: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<WorkLinkResult>(WorkLinkResult.EnterLink) }
    var generation by remember { mutableLongStateOf(0L) }
    var reading by remember { mutableStateOf<Job?>(null) }

    fun clearRead() {
        generation++
        reading?.cancel()
        reading = null
        result = WorkLinkResult.EnterLink
    }

    fun reset() {
        clearRead()
        link = ""
    }

    fun back() {
        reset()
        close()
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) reset()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            reset()
        }
    }
    BackHandler(onBack = ::back)

    WorkLinkContent(organizationName, link, result,
        onLinkChange = {
            clearRead()
            link = it.take(CoreWorkLink.maxLength + 1)
        },
        onOpen = {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !sessions.commitIfCurrent(active.epoch) {} || result == WorkLinkResult.Checking
            ) return@WorkLinkContent
            clearRead()
            val target = CoreWorkLink.parse(link, origin)
            if (target == null) {
                result = WorkLinkResult.Unavailable
                return@WorkLinkContent
            }
            val attempt = generation
            fun current() = generation == attempt && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                sessions.commitIfCurrent(active.epoch) {}
            result = WorkLinkResult.Checking
            reading = scope.launch {
                val outcome = try {
                    WorkLinkResult.Open(sessions.linkedWorkTask(active, target, ::current))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    WorkLinkResult.Unavailable
                }
                if (current()) result = outcome
            }
        },
        onBack = ::back,
        onSignOut = {
            back()
            signOut()
        },
    )
}
