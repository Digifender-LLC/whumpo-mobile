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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.home.CoreSourceHistoryContent
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun CoreSourceHistoryPreview(
    controller: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    current: () -> Boolean,
    close: () -> Unit,
) {
    var workspace by remember { mutableStateOf<CoreWorkspaceChoice?>(null) }
    var project by remember { mutableStateOf<CoreProjectChoice?>(null) }
    var workspaceCursor by remember { mutableStateOf<String?>(null) }
    var projectCursor by remember { mutableStateOf<String?>(null) }
    val group = workspace
    val choice = project
    val back: () -> Unit = {
        when {
            choice != null -> project = null
            group != null -> workspace = null
            else -> close()
        }
    }
    BackHandler(onBack = back)
    key(choice?.id, group?.id) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = back) {
                Text(if (choice != null) "Choose another project" else if (group != null) "Back to workspaces" else "Back to organization")
            }
            Text("Project source history", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            when {
                choice != null -> {
                    Text(choice.name, style = MaterialTheme.typography.titleMedium)
                    SourceHistoryPages(controller, active, choice, current = {
                        current() && project == choice
                    })
                }
                group != null -> key(projectCursor) {
                    Text("Choose a project")
                    WorkRead(controller, active.epoch, { controller.projectChoices(group.id, projectCursor, expected = active) }) { page ->
                        if (page.items.isEmpty()) Text("No projects available.")
                        page.items.forEach { item ->
                            WhumpoQuietButton({
                                if (current()) controller.commitIfCurrent(active.epoch) { project = item }
                            }) { Text(item.name) }
                        }
                        page.nextCursor?.let { next -> TextButton(onClick = { projectCursor = next }) { Text("More projects") } }
                        if (projectCursor != null) TextButton(onClick = { projectCursor = null }) { Text("First projects") }
                    }
                }
                else -> key(workspaceCursor) {
                    Text("Choose a workspace")
                    WorkRead(controller, active.epoch, { controller.workspaceChoices(workspaceCursor, expected = active) }) { page ->
                        if (page.items.isEmpty()) Text("No workspaces available.")
                        page.items.forEach { item ->
                            WhumpoQuietButton({
                                if (current()) controller.commitIfCurrent(active.epoch) {
                                    projectCursor = null
                                    workspace = item
                                }
                            }) { Text(item.name) }
                        }
                        page.nextCursor?.let { next -> TextButton(onClick = { workspaceCursor = next }) { Text("More workspaces") } }
                        if (workspaceCursor != null) TextButton(onClick = { workspaceCursor = null }) { Text("First workspaces") }
                    }
                }
            }
        }
    }
}

private class HistoryRead(val prior: CoreOrganizationSessionController.SourceHistoryPage? = null)

@Composable
private fun SourceHistoryPages(
    controller: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    project: CoreProjectChoice,
    current: () -> Boolean,
) {
    var request by remember { mutableStateOf(HistoryRead()) }
    var response by remember(request) { mutableStateOf<CoreOrganizationSessionController.SourceHistoryPage?>(null) }
    var loading by remember(request) { mutableStateOf(true) }
    var notice by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        val exact = request
        if (!current()) return@LaunchedEffect
        try {
            val page = controller.sourceHistory(active, project, exact.prior)
            currentCoroutineContext().ensureActive()
            if (current() && request === exact) controller.commitIfCurrent(active.epoch) { response = page }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (current() && request === exact) controller.commitIfCurrent(active.epoch) {
                notice = when ((error as? CoreHttpException)?.status) {
                    409 -> "Source history changed. Load the latest versions."
                    403, 404, 423 -> "Source history is unavailable."
                    else -> "Source history could not be loaded."
                }
            }
        } finally {
            if (current() && request === exact) controller.commitIfCurrent(active.epoch) { loading = false }
        }
    }

    val page = response
    CoreSourceHistoryContent(page?.entries, page?.hasOlderVersions == true, loading, notice, onOlder = {
        if (!loading && current() && page?.hasOlderVersions == true) request = HistoryRead(page)
    }, onLatest = {
        if (!loading && current()) request = HistoryRead()
    })
}
