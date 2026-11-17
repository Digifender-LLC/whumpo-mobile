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
import com.whumpo.android.ui.work.CoreInboxContent
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreInboxDetail
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.auth.CoreInboxPage
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.acknowledgeInboxItem
import com.whumpo.core.auth.inboxPage
import com.whumpo.core.auth.openInboxItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun CoreInboxPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    organizationName: String,
    close: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var generation by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<CoreInboxPage?>(null) }
    var detail by remember { mutableStateOf<CoreInboxDetail?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun clear() {
        generation++
        pending?.cancel()
        pending = null
        busy = false
        page = null
        detail = null
        notice = null
    }

    fun isForeground() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        sessions.commitIfCurrent(active.epoch) {}

    fun <T> submit(
        acknowledge: Boolean = false,
        work: suspend (() -> Boolean) -> T,
        apply: (T) -> Unit,
    ) {
        if (busy || !isForeground()) return
        val request = ++generation
        fun current() = generation == request && isForeground()
        notice = null
        busy = true
        pending = scope.launch {
            try {
                val result = work(::current)
                if (current()) apply(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current()) {
                    detail = null
                    val status = (error as? CoreHttpException)?.status
                    val uncertainAck = acknowledge && error !is CoreProtocolException && (status == null || status >= 500)
                    if (!uncertainAck) page = null
                    notice = if (uncertainAck) "Acknowledgment could not be confirmed. Try again."
                    else "Task notifications are unavailable. Refresh to check again."
                }
            } finally {
                if (current()) busy = false
            }
        }
    }

    fun load(after: CoreInboxPage? = null) {
        submit(work = { sessions.inboxPage(active, after, it) }, apply = { page = it })
    }

    DisposableEffect(lifecycle, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> load()
                Lifecycle.Event.ON_PAUSE -> clear()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            clear()
        }
    }

    fun back() {
        if (detail != null) detail = null else {
            clear()
            close()
        }
    }
    BackHandler(onBack = ::back)
    CoreInboxContent(
        organizationName, page?.items, page?.hasMore == true, detail, busy, notice,
        onBack = ::back,
        onRefresh = { load() },
        onMore = { page?.let(::load) },
        onOpen = { item ->
            page?.let { current ->
                submit(work = { sessions.openInboxItem(active, current, item, it) }, apply = { detail = it })
            }
        },
        onAcknowledge = { item ->
            page?.let { current ->
                submit(acknowledge = true, work = { sessions.acknowledgeInboxItem(active, current, item, it) }) {
                    page = it
                    notice = "Notification acknowledged."
                }
            }
        },
    )
}
