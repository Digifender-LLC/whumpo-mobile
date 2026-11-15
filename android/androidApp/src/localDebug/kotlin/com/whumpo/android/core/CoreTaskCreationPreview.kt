package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreTaskCommandAcceptance
import com.whumpo.core.api.CoreTaskCommandStatus
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CancellationException

private sealed interface SubmissionState {
    data object Editing : SubmissionState
    data class Sending(val request: CoreOrganizationSessionController.TaskCreation) : SubmissionState
    data class Uncertain(val request: CoreOrganizationSessionController.TaskCreation, val message: String) : SubmissionState
    data class Accepted(val response: CoreTaskCommandAcceptance) : SubmissionState
}

@Composable
internal fun CoreTaskCreationPreview(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    organizationName: String,
    close: () -> Unit,
) {
    var selection by remember { mutableStateOf<CoreWorkspaceChoice?>(null) }
    var project by remember { mutableStateOf<CoreProjectChoice?>(null) }
    var workspaceCursor by remember { mutableStateOf<String?>(null) }
    var projectCursor by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var submission by remember { mutableStateOf<SubmissionState>(SubmissionState.Editing) }
    val current = submission
    val canClose = current is SubmissionState.Editing || current is SubmissionState.Accepted
    BackHandler { if (canClose) close() }

    LaunchedEffect(current) {
        if (current is SubmissionState.Sending) {
            try {
                val accepted = controller.createTask(current.request)
                controller.commitIfCurrent(epoch) { submission = SubmissionState.Accepted(accepted) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                controller.commitIfCurrent(epoch) {
                    val status = (error as? CoreHttpException)?.status
                    val rejection = when (status) {
                        400, 422 -> "Check the task details and try again."
                        403 -> "Your organization access does not allow task creation."
                        404 -> "This project is no longer available. Choose another project."
                        else -> null
                    }
                    if (rejection != null) {
                        feedback = rejection
                        submission = SubmissionState.Editing
                    } else {
                        submission = SubmissionState.Uncertain(
                            current.request,
                            if (status == 409) "The task request conflicts with the current state."
                            else "The server did not confirm whether it accepted the task.",
                        )
                    }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canClose) TextButton(onClick = close) { Text("Back to Work") }
        Text(organizationName, style = MaterialTheme.typography.labelLarge)
        Text("New task", style = MaterialTheme.typography.headlineMedium)
        when (current) {
            SubmissionState.Editing -> {
                val workspace = selection
                val chosen = project
                when {
                    workspace == null -> key(workspaceCursor) {
                        Text("Choose a workspace", style = MaterialTheme.typography.titleMedium)
                        WorkRead(controller, epoch, { controller.workspaceChoices(workspaceCursor) }) { page ->
                            if (page.items.isEmpty()) Text("No workspaces available.")
                            page.items.forEach { item ->
                                OutlinedButton(onClick = {
                                    selection = item
                                    projectCursor = null
                                }) { Text(item.name) }
                            }
                            page.nextCursor?.let { next ->
                                TextButton(onClick = { workspaceCursor = next }) { Text("More workspaces") }
                            }
                            if (workspaceCursor != null) {
                                TextButton(onClick = { workspaceCursor = null }) { Text("First page") }
                            }
                        }
                    }
                    chosen == null -> key(workspace.id, projectCursor) {
                        Text("Choose a project", style = MaterialTheme.typography.titleMedium)
                        Text(workspace.name)
                        TextButton(onClick = { selection = null }) { Text("Change workspace") }
                        WorkRead(controller, epoch, { controller.projectChoices(workspace.id, projectCursor) }) { page ->
                            if (page.items.isEmpty()) Text("No projects available in this workspace.")
                            page.items.forEach { item ->
                                OutlinedButton(onClick = { project = item }) { Text(item.name) }
                            }
                            page.nextCursor?.let { next ->
                                TextButton(onClick = { projectCursor = next }) { Text("More projects") }
                            }
                            if (projectCursor != null) {
                                TextButton(onClick = { projectCursor = null }) { Text("First page") }
                            }
                        }
                    }
                    else -> TaskDetails(
                        workspace = workspace.name,
                        project = chosen.name,
                        title = title,
                        description = description,
                        changeProject = { project = null; feedback = null },
                        changeTitle = { title = it; feedback = null },
                        changeDescription = { description = it; feedback = null },
                        submit = {
                            // This callback can fire twice before recomposition.
                            if (submission == SubmissionState.Editing) {
                                controller.commitIfCurrent(epoch) {
                                    try {
                                        val request = controller.prepareTaskCreation(epoch, chosen, title, description)
                                        submission = SubmissionState.Sending(request)
                                    } catch (error: CoreHttpException) {
                                        if (error.status == 403) feedback = "Your organization access does not allow task creation."
                                        else throw error
                                    } catch (_: IllegalArgumentException) {
                                        feedback = "Enter a title of up to 200 characters and a description of up to 10,000."
                                    }
                                }
                            }
                        },
                    )
                }
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            is SubmissionState.Sending -> Text("Submitting task…")
            is SubmissionState.Uncertain -> {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Text("Retry sends the same task request. If you leave, check Work for the task before creating it again.")
                Button(onClick = { submission = SubmissionState.Sending(current.request) }) { Text("Retry same request") }
                TextButton(onClick = close) { Text("Leave without confirmation") }
            }
            is SubmissionState.Accepted -> {
                Text(
                    if (current.response.status == CoreTaskCommandStatus.WaitingApproval) "Task awaiting approval."
                    else "Task creation queued.",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun TaskDetails(
    workspace: String,
    project: String,
    title: String,
    description: String,
    changeProject: () -> Unit,
    changeTitle: (String) -> Unit,
    changeDescription: (String) -> Unit,
    submit: () -> Unit,
) {
    Text("$workspace / $project", style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = changeProject) { Text("Change project") }
    OutlinedTextField(
        value = title,
        onValueChange = changeTitle,
        label = { Text("Task title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = description,
        onValueChange = changeDescription,
        label = { Text("Description (optional)") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = title.isNotEmpty(), onClick = submit) { Text("Create task") }
}
