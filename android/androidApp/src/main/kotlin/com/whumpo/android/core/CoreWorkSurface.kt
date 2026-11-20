package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.work.CoreWorkCompletionContent
import com.whumpo.android.ui.work.CoreWorkHistoryContent
import com.whumpo.android.ui.work.CoreWorkQueryContent
import com.whumpo.core.api.*
import com.whumpo.core.auth.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shared by the real local Core account surface and the native launcher contract surface. */
@Composable
internal fun CoreWorkSurface(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    onBack: () -> Unit,
    openTask: ((CoreWorkTask, String?) -> Unit)? = null,
    onListVisible: (Boolean) -> Unit = {},
    onReadable: (Boolean) -> Unit = {},
    initialFilters: CoreWorkFilters = CoreWorkFilters(),
    saveFilters: (CoreWorkFilters) -> Unit = {},
    interact: () -> Unit = {},
    beforeQueries: @Composable () -> Unit = {},
) {
    var filters by remember(active) { mutableStateOf(initialFilters) }
    var detail by remember(active) { mutableStateOf<CoreWorkTask?>(null) }
    var history by remember(active) { mutableStateOf<CoreWorkTask?>(null) }
    var completion by remember(active) { mutableStateOf<CoreWorkCompletionController?>(null) }
    var showCompletion by remember(active) { mutableStateOf(false) }
    val actions = rememberCoroutineScope()
    fun finish(task: CoreWorkTask) {
        if (active.session.role != CoreRole.owner || !sessions.commitIfCurrent(active.epoch) {}) return
        val old = completion
        val terminal = old?.state?.value?.let {
            it.proof is PartnerTaskProof.Verified || it.proof == PartnerTaskProof.Failed || it.proof == PartnerTaskProof.Canceled ||
                (!it.accepted && it.notice in setOf(CoreWorkCompletionNotice.Changed, CoreWorkCompletionNotice.Rejected))
        } ?: false
        val same = old?.task?.let { it.id == task.id && it.version == task.version } == true
        val selected = if (old != null && (!terminal && old.state.value.started || same)) old else {
            old?.retire()
            CoreWorkCompletionController(sessions, active, task).also { completion = it }
        }
        showCompletion = true
        actions.launch { selected.submit() }
    }
    val goBack = { when { showCompletion -> showCompletion = false; history != null -> history = null; detail != null -> detail = null; else -> onBack() } }
    BackHandler(onBack = goBack)
    DisposableEffect(active) { onDispose { completion?.retire(); onListVisible(false) } }
    SideEffect { onListVisible(detail == null && history == null && !showCompletion) }
    CoreWorkQueries(sessions, active, filters, { filters = it; saveFilters(it) }, interact,
        open = { task, name -> if (openTask != null) openTask(task, name) else detail = task },
        history = { history = it },
        complete = if (active.session.role == CoreRole.owner) ::finish else null,
        onReadable = onReadable,
    ) { state, queryActions ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                showCompletion && completion != null -> CompletionPanel(checkNotNull(completion)) { showCompletion = false }
                history != null -> HistoryPanel(sessions, active, checkNotNull(history)) { history = null }
                detail != null -> WorkTaskPanel(sessions, active, checkNotNull(detail),
                    { history = it }, if (active.session.role == CoreRole.owner) ::finish else null) { detail = null }
                else -> {
                    WhumpoQuietButton(onClick = onBack) { Text("Back to organization") }
                    Text("Work", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    beforeQueries()
                    CoreWorkQueryContent(state, queryActions, canManageAssignees = active.session.role == CoreRole.owner)
                }
            }
        }
    }
}

@Composable
private fun CompletionPanel(model: CoreWorkCompletionController, close: () -> Unit) {
    val state by model.state.collectAsState()
    val actions = rememberCoroutineScope()
    CoreWorkCompletionContent(model.task, state,
        submit = { actions.launch { model.submit() } }, refresh = { actions.launch { model.refresh() } },
        approve = { actions.launch { model.approve() } }, close = close)
}

@Composable
private fun HistoryPanel(sessions: CoreOrganizationSessionController, active: CoreOrganizationSessionController.State.Active,
    task: CoreWorkTask, close: () -> Unit) {
    val model = remember(active, task.id, task.projectId) { CoreWorkHistoryController(sessions, active, task) }
    val state by model.state.collectAsState()
    val source by model.source.collectAsState()
    val actions = rememberCoroutineScope()
    var sourceJob by remember(model) { mutableStateOf<Job?>(null) }
    var origin by remember(model) { mutableStateOf<CoreRecordedTaskSource?>(null) }
    DisposableEffect(model) { onDispose { model.retire() } }
    LaunchedEffect(model) { model.load() }
    fun requestSource(value: CoreRecordedTaskSource, next: CoreWorkSourceBookmark? = null) {
        val previous = sourceJob
        origin = value
        sourceJob = actions.launch { previous?.cancelAndJoin(); model.openSource(value, next) }
    }
    CoreWorkHistoryContent(state, source,
        refresh = { actions.launch { model.load() } }, more = { state.page?.next?.let { actions.launch { model.load(it) } } },
        openSource = { requestSource(it) }, moreSource = { origin?.let { requestSource(it, source.result?.next) } },
        closeSource = { sourceJob?.cancel(); model.closeSource(); origin = null }, close = close)
}

@Composable
private fun WorkTaskPanel(sessions: CoreOrganizationSessionController, active: CoreOrganizationSessionController.State.Active,
    selected: CoreWorkTask, history: (CoreWorkTask) -> Unit, complete: ((CoreWorkTask) -> Unit)?, close: () -> Unit) {
    var task by remember(active, selected.id) { mutableStateOf<CoreWorkTask?>(null) }
    var unavailable by remember(active, selected.id) { mutableStateOf(false) }
    LaunchedEffect(active, selected.id) {
        try {
            val value = withTimeoutOrNull(15_000) { sessions.canonicalTask(active, selected.id) }
            sessions.commitIfCurrent(active.epoch) { task = value; unavailable = value == null }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { sessions.commitIfCurrent(active.epoch) { unavailable = true } }
    }
    Text("Task details", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    when {
        unavailable -> Text("This task is unavailable. Refresh Work to check its current scope.")
        task == null -> Text("Opening the current task…")
        else -> checkNotNull(task).let { value ->
            Text(value.title, style = MaterialTheme.typography.titleLarge)
            Text("${value.status.label} · version ${value.version}")
            value.description?.let { Text(it) }
            WhumpoQuietButton(onClick = { history(value) }) { Text(if (value.status == CoreTaskStatus.Blocked) "View blocked task history" else "Open task history") }
            if (value.status == CoreTaskStatus.InProgress && value.archivedAt == null && complete != null)
                WhumpoQuietButton(onClick = { complete(value) }) { Text("Complete task") }
        }
    }
    WhumpoQuietButton(onClick = close) { Text("Back to Work") }
}
