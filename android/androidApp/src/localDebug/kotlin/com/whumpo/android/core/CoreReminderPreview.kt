package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whumpo.android.ui.work.CoreReminderInboxContent
import com.whumpo.android.ui.work.CoreTaskReminderContent
import com.whumpo.core.api.CoreReminderTarget
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreReminderAttempt
import com.whumpo.core.auth.CoreReminderChangedException
import com.whumpo.core.auth.CoreReminderPage
import com.whumpo.core.auth.CoreReminderStatus
import com.whumpo.core.auth.CoreTaskCalendarReview
import com.whumpo.core.auth.markReminderRead
import com.whumpo.core.auth.openReminder
import com.whumpo.core.auth.reminderPage
import com.whumpo.core.auth.reminderStatus
import com.whumpo.core.auth.submitReminder
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun CoreWorkDeadlineActions(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    task: CoreWorkTask,
    foreground: Boolean,
    generation: Long,
    reminderAttempts: MutableMap<String, CoreReminderAttempt>,
    whileCurrent: () -> Boolean,
) {
    if (!foreground) return
    CoreReminderTarget.fromTask(task)?.let { target ->
        key(generation, target) {
            CoreTaskReminderPreview(sessions, active, target, reminderAttempts[task.id],
                setPending = { pending ->
                    if (pending == null) reminderAttempts.remove(task.id)
                    else reminderAttempts[task.id] = pending
                },
                whileCurrent = whileCurrent,
            )
        }
    }
    CoreTaskCalendarReview.from(task)?.let { review ->
        key(generation, review) { CoreWorkTaskCalendar(sessions, active.epoch, review, whileCurrent) }
    }
}

@Composable
private fun CoreTaskReminderPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    target: CoreReminderTarget,
    pending: CoreReminderAttempt?,
    setPending: (CoreReminderAttempt?) -> Unit,
    whileCurrent: () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val latestCurrent by rememberUpdatedState(whileCurrent)
    val retain by rememberUpdatedState(setPending)
    val attemptInScope by rememberUpdatedState(pending)
    var status by remember { mutableStateOf<CoreReminderStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }

    fun current() = latestCurrent() && sessions.commitIfCurrent(active.epoch) {}

    suspend fun update(attempt: CoreReminderAttempt? = null) {
        try {
            if (attempt != null) {
                sessions.submitReminder(active, attempt, ::current)
                if (!current()) return
                retain(null)
            }
            val fresh = sessions.reminderStatus(active, target, ::current)
            if (!current()) return
            if (attemptInScope?.let { it.target != target || fresh.settles(it) } == true) retain(null)
            status = fresh
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (current()) {
                status = null
                if (error is CoreReminderChangedException) {
                    retain(null)
                    issue = "This Work item changed. Return to Work and refresh it."
                } else issue = "The reminder could not be verified. Check again."
            }
        } finally {
            if (current()) busy = false
        }
    }

    LaunchedEffect(active, target) {
        if (current()) {
            busy = true
            update()
        }
    }

    fun submit(attempt: CoreReminderAttempt? = null) {
        if (busy || !current()) return
        if (attempt != null) retain(attempt)
        issue = null
        busy = true
        scope.launch { update(attempt) }
    }

    val canArm = Instant.parse(target.dueAt).isAfter(Instant.now())
    val retry = pending?.takeIf { it.target == target }
    CoreTaskReminderContent(
        target.dueAt, status?.reminder?.state, verified = status != null, canArm, busy,
        pending = when (retry) {
            is CoreReminderAttempt.Arm -> "Retry scheduling reminder"
            is CoreReminderAttempt.Cancel -> "Retry canceling reminder"
            null -> null
        },
        notice = issue,
        onArm = { if (retry == null && Instant.parse(target.dueAt).isAfter(Instant.now())) status?.armAttempt()?.let(::submit) },
        onCancel = { if (retry == null) status?.cancelAttempt()?.let(::submit) },
        onRetry = { retry?.let(::submit) },
        onCheck = { submit() },
    )
}

@Composable
internal fun CoreReminderInboxPreview(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    organizationName: String,
    close: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var generation by remember(active) { mutableLongStateOf(0L) }
    var pending by remember(active) { mutableStateOf<Job?>(null) }
    var page by remember(active) { mutableStateOf<CoreReminderPage?>(null) }
    var task by remember(active) { mutableStateOf<CoreWorkTask?>(null) }
    var busy by remember(active) { mutableStateOf(false) }
    var notice by remember(active) { mutableStateOf<String?>(null) }

    fun current() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        sessions.commitIfCurrent(active.epoch) {}

    fun clear() {
        generation++
        pending?.cancel()
        pending = null
        page = null
        task = null
        busy = false
        notice = null
    }

    fun <T> submit(
        reload: Boolean = false,
        read: suspend (() -> Boolean) -> T,
        apply: (T) -> Unit,
    ) {
        if (busy || !current()) return
        val request = ++generation
        fun admitted() = generation == request && current()
        notice = null
        busy = true
        pending = scope.launch {
            try {
                val result = read(::admitted)
                if (admitted()) apply(result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (admitted()) {
                    if (reload) page = null
                    notice = "The task reminder could not be verified. Refresh to check again."
                }
            } finally {
                if (admitted()) busy = false
            }
        }
    }

    fun load(after: CoreReminderPage? = null) {
        submit(reload = true, read = { sessions.reminderPage(active, after, it) }, apply = { page = it })
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
        if (task != null) task = null else {
            clear()
            close()
        }
    }

    BackHandler(onBack = ::back)
    CoreReminderInboxContent(
        organizationName, page?.items, page?.hasMore == true, task, busy, notice,
        onBack = ::back,
        onRefresh = { load() },
        onMore = { page?.let(::load) },
        onOpen = { item ->
            page?.let { selected ->
                submit(read = { sessions.openReminder(active, selected, item, it) }, apply = { task = it })
            }
        },
        onRead = { item ->
            page?.let { selected ->
                submit(read = { sessions.markReminderRead(active, selected, item, it) }, apply = { page = it })
            }
        },
    )
}
