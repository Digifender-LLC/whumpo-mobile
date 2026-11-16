package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreRecoveryPoint
import com.whumpo.core.api.CoreRestoreResult
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreOrganizationSessionController.RecoveryAction
import com.whumpo.core.auth.CoreOrganizationSessionController.RecoveryAttempt
import com.whumpo.core.auth.CoreOrganizationSessionController.RecoveryOutcome
import com.whumpo.core.auth.CoreOrganizationSessionController.RecoverySnapshot
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CoreApplicationRecoveryPreview(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    organizationName: String,
    close: () -> Unit,
) {
    var workspace by remember { mutableStateOf<CoreWorkspaceChoice?>(null) }
    var project by remember { mutableStateOf<CoreProjectChoice?>(null) }
    var workspaceCursor by remember { mutableStateOf<String?>(null) }
    var projectCursor by remember { mutableStateOf<String?>(null) }
    val choice = project
    val group = workspace
    val back: () -> Unit = {
        when {
            project != null -> project = null
            workspace != null -> workspace = null
            else -> close()
        }
    }
    BackHandler(onBack = back)
    key(choice?.id ?: group?.id) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = back) { Text(if (choice != null) "Back to projects" else if (group != null) "Back to workspaces" else "Back to Work") }
            Text(organizationName, style = MaterialTheme.typography.labelLarge)
            Text("Application recovery", style = MaterialTheme.typography.headlineMedium)
            when {
                choice != null -> RecoveryProjectPanel(controller, epoch, choice)
                group != null -> key(projectCursor) {
                    Text("Choose a project")
                    WorkRead(controller, epoch, { controller.projectChoices(group.id, projectCursor) }) { page ->
                        if (page.items.isEmpty()) Text("No projects on this page.")
                        page.items.forEach { item -> OutlinedButton(onClick = { project = item }) { Text(item.name) } }
                        page.nextCursor?.let { next -> TextButton(onClick = { projectCursor = next }) { Text("More projects") } }
                        if (projectCursor != null) TextButton(onClick = { projectCursor = null }) { Text("First page") }
                    }
                }
                else -> key(workspaceCursor) {
                    Text("Choose a workspace")
                    WorkRead(controller, epoch, { controller.workspaceChoices(workspaceCursor) }) { page ->
                        if (page.items.isEmpty()) Text("No workspaces on this page.")
                        page.items.forEach { item -> OutlinedButton(onClick = {
                            workspace = item
                            projectCursor = null
                        }) { Text(item.name) } }
                        page.nextCursor?.let { next -> TextButton(onClick = { workspaceCursor = next }) { Text("More workspaces") } }
                        if (workspaceCursor != null) TextButton(onClick = { workspaceCursor = null }) { Text("First page") }
                    }
                }
            }
        }
    }
}

private data class RecoveryViewState(
    val snapshot: RecoverySnapshot? = null,
    val removal: CoreRecoveryPoint? = null,
    val review: CoreRestoreResult? = null,
    val literal: String = "",
    val pending: RecoveryAttempt? = null,
    val busy: Boolean = false,
    val rejected: Boolean = false,
    val expired: Boolean = false,
    val message: String? = null,
)

@Composable
private fun RecoveryProjectPanel(controller: CoreOrganizationSessionController, epoch: Long, choice: CoreProjectChoice) {
    val project = remember(epoch, choice.id) { controller.recoveryProject(epoch, choice) }
    val jobs = rememberCoroutineScope()
    var state by remember { mutableStateOf(RecoveryViewState()) }
    fun publish(next: RecoveryViewState) { controller.commitIfCurrent(epoch) { state = next } }
    fun install(result: CoreRestoreResult) {
        val waiting = result is CoreRestoreResult.Review && result.state == "waiting_approval"
        publish(state.copy(review = result, pending = if (waiting) state.pending else null, literal = ""))
    }
    suspend fun refresh() {
        val current = controller.recovery(project)
        publish(state.copy(snapshot = current))
    }
    fun run(work: suspend () -> Unit) {
        if (state.busy) return
        publish(state.copy(busy = true, message = null))
        jobs.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val next = when ((error as? CoreHttpException)?.status) {
                    403 -> RecoveryViewState(message = "Owner access and a recent passkey sign-in are required. Sign in again and select this organization.")
                    404 -> RecoveryViewState(message = "This project or review is no longer available.")
                    409 -> state.copy(snapshot = null, removal = null, pending = null, rejected = true, literal = "",
                        message = "The application or recovery point changed. Refresh before preparing another restore.")
                    423, 429 -> state.copy(pending = null, message = "Recovery is temporarily unavailable.")
                    else -> state.copy(message = "The recovery result could not be verified.")
                }
                publish(next)
            } finally {
                publish(state.copy(busy = false))
            }
        }
    }
    fun submit(attempt: RecoveryAttempt) = run {
        publish(state.copy(pending = attempt))
        val action = attempt.action
        if (action is RecoveryAction.Confirm) {
            val current = controller.readApplicationRestore(project, action.review)
            install(current)
            if (current !is CoreRestoreResult.Review || current.state != "waiting_approval") return@run
            if (state.rejected || current.expiresAt <= System.currentTimeMillis()) {
                publish(state.copy(pending = null, expired = true))
                return@run
            }
        }
        when (val result = controller.writeRecovery(attempt)) {
            is RecoveryOutcome.Captured -> publish(state.copy(message =
                if (result.point.removed) "This point was captured and later removed." else "Recovery point captured."))
            RecoveryOutcome.Removed -> publish(state.copy(removal = null, message = "Recovery point removed."))
            is RecoveryOutcome.Reviewed -> {
                publish(state.copy(rejected = false, expired = false))
                install(result.result)
            }
        }
        publish(state.copy(pending = null, literal = ""))
        refresh()
    }
    LaunchedEffect(project) { run { refresh() } }
    val review = state.review as? CoreRestoreResult.Review
    LaunchedEffect(review?.id, review?.expiresAt) {
        if (review?.state == "waiting_approval") {
            delay((review.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            publish(state.copy(expired = true))
        }
    }
    RecoveryContent(choice.name, state,
        refresh = { run { refresh() } },
        changeLiteral = { publish(state.copy(literal = it.take(7))) },
        selectRemoval = { publish(state.copy(removal = it, literal = "")) },
        act = { action -> submit(controller.prepareRecoveryAttempt(project, action)) },
        retry = { state.pending?.let(::submit) },
        readReview = { run { review?.let { install(controller.readApplicationRestore(project, it)) } } },
    )
}

@Composable
private fun RecoveryContent(
    projectName: String,
    view: RecoveryViewState,
    refresh: () -> Unit,
    changeLiteral: (String) -> Unit,
    selectRemoval: (CoreRecoveryPoint?) -> Unit,
    act: (RecoveryAction) -> Unit,
    retry: () -> Unit,
    readReview: () -> Unit,
) {
    val snapshot = view.snapshot
    val available = !view.busy && view.pending == null
    Text(projectName, style = MaterialTheme.typography.titleLarge)
    Text("Restoring a recovery point requires a separate review and leaves the application disabled.")
    OutlinedButton(onClick = refresh, enabled = available) { Text("Refresh recovery points") }
    if (snapshot?.state?.canCapture == true) {
        Button(onClick = { act(RecoveryAction.Capture(snapshot.state.revision)) }, enabled = available) {
            Text("Capture recovery point")
        }
    }
    if (snapshot != null && snapshot.points.isEmpty()) Text("No recovery points.")
    snapshot?.points?.forEach { point ->
        Text(DateFormat.getDateTimeInstance().format(Date(point.createdAt)), style = MaterialTheme.typography.titleMedium)
        Text("${point.retainedDocuments} retained documents")
        OutlinedButton(onClick = { act(RecoveryAction.Prepare(point, snapshot.state.revision)) },
            enabled = available && snapshot.state.canRestore) { Text("Review restore") }
        TextButton(onClick = { selectRemoval(point) }, enabled = available) { Text("Remove point") }
    }
    view.removal?.let { point ->
        Text("Type REMOVE to permanently remove this recovery point.")
        OutlinedTextField(view.literal, changeLiteral, label = { Text("Type REMOVE") }, singleLine = true, enabled = available)
        Button(onClick = { act(RecoveryAction.Remove(point, view.literal)) },
            enabled = available && view.literal == "REMOVE") { Text("Confirm removal") }
        TextButton(onClick = { selectRemoval(null) }, enabled = available) { Text("Cancel removal") }
    }
    val result = view.review
    val receipt = result?.receipt
    val review = result as? CoreRestoreResult.Review
    if (receipt != null) Text("Restored ${receipt.restoredDocuments} documents. The application remains disabled.")
    else if (result is CoreRestoreResult.Compacted) Text("The saved review expired without a restore.")
    else if (review != null && view.removal == null) {
        val ready = !view.rejected && !view.expired && review.expiresAt > System.currentTimeMillis() &&
            snapshot?.state?.canRestore == true && snapshot.state.revision == review.expectedApplicationRevision
        if (ready) {
            Text("Restore the reviewed point for ${review.documents} documents.")
            Text("Review expires ${DateFormat.getDateTimeInstance().format(Date(review.expiresAt))}.")
            OutlinedTextField(view.literal, changeLiteral, label = { Text("Type RESTORE") }, singleLine = true, enabled = available)
            Button(onClick = {
                if (review.expiresAt > System.currentTimeMillis()) act(RecoveryAction.Confirm(review, view.literal))
            }, enabled = available && view.literal == "RESTORE") { Text("Confirm restore") }
        } else Text("This review can no longer be confirmed.")
        OutlinedButton(onClick = readReview, enabled = !view.busy &&
            (view.pending == null || view.pending.action is RecoveryAction.Confirm)) { Text("Check review status") }
    }
    if (view.pending != null && !view.busy) {
        Text("The result is unconfirmed. Retry the exact request before creating another.")
        OutlinedButton(onClick = retry) { Text("Retry exact request") }
    }
    view.message?.let { Text(it) }
    if (view.busy) CircularProgressIndicator()
}
