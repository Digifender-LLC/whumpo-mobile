package com.whumpo.android.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.whumpo.android.ui.work.CoreWorkQueryActions
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreWorkDirectory
import com.whumpo.core.api.CoreWorkFilters
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreWorkQueryController
import com.whumpo.core.auth.CoreWorkQueryState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App wiring owns authenticated effects; the shared content only receives immutable state/callbacks. */
@Composable
internal fun CoreWorkQueries(
    sessions: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    initialFilters: CoreWorkFilters,
    savedFilters: (CoreWorkFilters) -> Unit,
    interact: () -> Unit,
    open: (CoreWorkTask, String?) -> Unit,
    history: (CoreWorkTask) -> Unit,
    complete: ((CoreWorkTask) -> Unit)?,
    onReadable: (Boolean) -> Unit,
    content: @Composable (CoreWorkQueryState, CoreWorkQueryActions) -> Unit,
) {
    val model = remember(active) { CoreWorkQueryController(sessions, active) }
    val state by model.state.collectAsState()
    val actions = rememberCoroutineScope()
    DisposableEffect(model) { onDispose { model.retire() } }
    LaunchedEffect(model) {
        try {
            coroutineScope {
                launch { model.loadChoices(CoreWorkDirectory.Workspace) }
                launch { model.loadChoices(CoreWorkDirectory.Customer) }
                if (active.session.role == CoreRole.owner) launch { model.loadChoices(CoreWorkDirectory.Assignee) }
                model.apply(initialFilters)
                awaitCancellation()
            }
        } finally {
            model.retire()
            withContext(NonCancellable) { model.close() }
        }
    }
    LaunchedEffect(model, state.draft.workspaceId) { model.loadChoices(CoreWorkDirectory.Project) }
    LaunchedEffect(state.page, state.notice) { onReadable(state.page != null) }
    content(state, CoreWorkQueryActions(
        edit = { interact(); model.editFilters(it) },
        apply = { interact(); val filters = state.draft; savedFilters(filters); actions.launch { model.apply(filters) } },
        refresh = { interact(); actions.launch { model.refresh() } },
        more = { interact(); actions.launch { model.more() } },
        choices = { kind, more -> interact(); actions.launch { model.loadChoices(kind, more) } },
        open = { task ->
            interact()
            open(task, state.choices[CoreWorkDirectory.Project]?.items?.find { it.id == task.projectId }?.name)
        },
        history = { interact(); history(it) },
        complete = complete?.let { action -> { task -> interact(); action(task) } },
    ))
}
