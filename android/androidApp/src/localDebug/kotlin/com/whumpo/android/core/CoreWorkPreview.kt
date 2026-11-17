package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreWebsiteHistoryItem
import com.whumpo.core.api.CoreWebsiteOperation
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.CoreWorkWebOrigin
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreTaskCalendarReview
import kotlinx.coroutines.CancellationException

@Composable
internal fun CoreWorkPreview(
    controller: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    organizationName: String,
    canCreateTask: Boolean,
    canRecoverApplication: Boolean,
    initialTask: CoreWorkTask? = null,
    startTaskDraft: Boolean = false,
    openOfflineWork: () -> Unit,
    workWebOrigin: CoreWorkWebOrigin?,
    signOut: () -> Unit,
    onReadable: (Boolean) -> Unit = {},
    calendarForeground: Boolean,
    calendarGeneration: Long,
    isCalendarForeground: () -> Boolean,
    close: () -> Unit,
) {
    val epoch = active.epoch
    var recovering by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(startTaskDraft && canCreateTask && initialTask == null) }
    var receivingLink by remember { mutableStateOf(false) }
    var inbox by remember { mutableStateOf(false) }
    var selected by remember(initialTask) { mutableStateOf(initialTask) }
    var attachmentTask by remember { mutableStateOf<CoreWorkTask?>(null) }
    var openedFromCreation by remember { mutableStateOf(false) }
    var receipts by remember { mutableStateOf(false) }
    var workCursor by remember { mutableStateOf<String?>(null) }
    var historyCursor by remember { mutableStateOf<String?>(null) }
    attachmentTask?.let { target ->
        key(epoch, target.id, target.projectId, target.version) {
            CoreWorkAttachmentPreview(controller, active, target) { attachmentTask = null }
        }
        return
    }
    if (inbox) {
        CoreInboxPreview(controller, active, organizationName) { inbox = false }
        return
    }
    if (receivingLink && workWebOrigin != null) {
        CoreWorkLinkPreview(controller, active, workWebOrigin, organizationName,
            close = { receivingLink = false }, signOut = signOut)
        return
    }
    if (recovering && canRecoverApplication) {
        CoreApplicationRecoveryPreview(controller, epoch, organizationName) { recovering = false }
        return
    }
    if (creating && canCreateTask) {
        CoreTaskCreationPreview(controller, epoch, organizationName,
            openTask = { created ->
                controller.commitIfCurrent(epoch) {
                    selected = created
                    openedFromCreation = true
                    receipts = false
                    historyCursor = null
                    creating = false
                }
            },
            close = { creating = false },
        )
        return
    }
    val task = selected
    val goBack: () -> Unit = {
        when {
            receipts -> {
                receipts = false
                historyCursor = null
            }
            task != null -> selected = null
            else -> close()
        }
    }
    BackHandler(onBack = goBack)
    key(task?.id, receipts, workCursor, historyCursor) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val backLabel = when {
                receipts -> "Back to task"
                task != null -> "Back to Work"
                else -> "Back to organization"
            }
            TextButton(onClick = goBack) { Text(backLabel) }
            Text(organizationName, style = MaterialTheme.typography.labelLarge)
            when {
                task == null -> {
                    Text("Work", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    WhumpoQuietButton(onClick = { inbox = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Task notifications")
                    }
                    if (workWebOrigin != null) {
                        WhumpoQuietButton(onClick = { receivingLink = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Open Work link")
                        }
                    }
                    if (canCreateTask) {
                        WhumpoPrimaryButton(onClick = { creating = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("New task")
                        }
                        WhumpoQuietButton(onClick = openOfflineWork, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Capture offline task")
                        }
                    }
                    WorkRead(controller, epoch, { controller.work(workCursor) },
                        onUnavailable = { onReadable(false) }, onRead = { onReadable(true) }) { page ->
                        if (page.items.isEmpty()) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("No tasks here", style = MaterialTheme.typography.titleMedium)
                                    Text(if (canCreateTask) "Create a task to plan your next step." else "Tasks for this organization will appear here.")
                                }
                            }
                        }
                        page.items.forEach { item ->
                            Card(onClick = { selected = item; openedFromCreation = false }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                    Text(item.status.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        page.nextCursor?.let { next ->
                            Button(onClick = { workCursor = next }) { Text("More tasks") }
                        }
                        if (workCursor != null) OutlinedButton(onClick = { workCursor = null }) { Text("First page") }
                    }
                    if (canRecoverApplication) WhumpoQuietButton(onClick = { recovering = true }) { Text("Application recovery") }
                }
                receipts -> {
                    Text("Website receipts", style = MaterialTheme.typography.headlineMedium)
                    Text("Project ${task.projectId}", style = MaterialTheme.typography.bodyMedium)
                    if (active.session.role == CoreRole.owner) {
                        CurrentPublicationCheck(controller, active, task.projectId)
                    }
                    WorkRead(controller, epoch, { controller.websiteReceipts(task.projectId, historyCursor) }) { page ->
                        when {
                            page == null -> Text("This project has no website target.")
                            page.items.isEmpty() -> Text("No website receipts yet.")
                            else -> page.items.forEach { WebsiteReceipt(it) }
                        }
                        page?.nextCursor?.let { next ->
                            Button(onClick = { historyCursor = next }) { Text("More receipts") }
                        }
                        if (historyCursor != null) {
                            OutlinedButton(onClick = { historyCursor = null }) { Text("Most recent receipts") }
                        }
                    }
                }
                else -> WorkRead(controller, epoch, {
                    if (openedFromCreation) controller.canonicalTask(task.id) else controller.task(task.id, task.projectId)
                }) { detail ->
                    Text(detail.title, style = MaterialTheme.typography.headlineMedium)
                    Text(detail.status.label, style = MaterialTheme.typography.titleMedium)
                    detail.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    detail.dueAt?.let { Text("Due $it") }
                    if (detail.archivedAt != null) Text("Archived")
                    Text("Project ${detail.projectId}", style = MaterialTheme.typography.bodyMedium)
                    if (calendarForeground) {
                        val calendarDraft = CoreTaskCalendarReview.from(detail)
                        if (calendarDraft != null) key(calendarGeneration, calendarDraft) {
                            CoreWorkTaskCalendar(controller, epoch, calendarDraft) {
                                isCalendarForeground() && selected == task && !receipts && attachmentTask == null &&
                                    !inbox && !creating && !recovering && !receivingLink
                            }
                        }
                    }
                    if (active.session.role != CoreRole.viewer && detail.archivedAt == null) {
                        WhumpoQuietButton(onClick = {
                            controller.commitIfCurrent(epoch) { attachmentTask = detail }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Attach private file") }
                    }
                    Button(onClick = { selected = detail; receipts = true }) { Text("View website receipts") }
                }
            }
        }
    }
}

@Composable
private fun CurrentPublicationCheck(
    controller: CoreOrganizationSessionController,
    active: CoreOrganizationSessionController.State.Active,
    projectId: String,
) {
    var attempt by remember(active.epoch, projectId) { mutableStateOf(0) }
    OutlinedButton(
        onClick = { attempt += 1 },
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text("Check current publication")
    }
    if (attempt > 0) {
        key(attempt) {
            WorkRead(controller, active.epoch, { controller.currentPublication(active, projectId) }) { publication ->
                Text(
                    if (publication == null) "No current website publication."
                    else "Confirmed publication, version ${publication.targetVersion}.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun WebsiteReceipt(item: CoreWebsiteHistoryItem) {
    val receipt = item.receipt
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (item.operation == CoreWebsiteOperation.Publish) "Website published" else "Website withdrawn",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Version ${receipt.targetVersion} · ${receipt.confirmedAt}")
            Text("Receipt ${receipt.effectId}", style = MaterialTheme.typography.bodySmall)
            item.buildId?.let { Text("Build $it", style = MaterialTheme.typography.bodySmall) }
            receipt.pageCount?.let { Text("$it pages") }
            receipt.withdrawnEffectId?.let { Text("Withdraws receipt $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private sealed interface WorkReadResult<out T> {
    data object Loading : WorkReadResult<Nothing>
    data class Ready<T>(val value: T) : WorkReadResult<T>
    data class Failed(val message: String) : WorkReadResult<Nothing>
}

@Composable
internal fun <T> WorkRead(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    read: suspend () -> T,
    onUnavailable: () -> Unit = {},
    onRead: (T) -> Unit = {},
    readGeneration: Long = 0L,
    content: @Composable (T) -> Unit,
) {
    var result by remember(epoch, readGeneration) { mutableStateOf<WorkReadResult<T>>(WorkReadResult.Loading) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(epoch, attempt, readGeneration) {
        try {
            val value = read()
            controller.commitIfCurrent(epoch) {
                result = WorkReadResult.Ready(value)
                onRead(value)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = when ((error as? CoreHttpException)?.status) {
                403 -> "Your organization access does not allow this view."
                404 -> "This item is no longer available."
                else -> "Could not load this view."
            }
            controller.commitIfCurrent(epoch) {
                result = WorkReadResult.Failed(message)
                onUnavailable()
            }
        }
    }
    when (val current = result) {
        WorkReadResult.Loading -> CircularProgressIndicator()
        is WorkReadResult.Ready -> content(current.value)
        is WorkReadResult.Failed -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        current.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OutlinedButton(onClick = {
                        result = WorkReadResult.Loading
                        attempt += 1
                    }) { Text("Retry") }
                }
            }
        }
    }
}
