package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreWebsiteHistoryItem
import com.whumpo.core.api.CoreWebsiteOperation
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CancellationException

@Composable
internal fun CoreWorkPreview(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    organizationName: String,
    close: () -> Unit,
) {
    var selected by remember { mutableStateOf<CoreWorkTask?>(null) }
    var receipts by remember { mutableStateOf(false) }
    var workCursor by remember { mutableStateOf<String?>(null) }
    var historyCursor by remember { mutableStateOf<String?>(null) }
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
                    Text("Work", style = MaterialTheme.typography.headlineMedium)
                    WorkRead(controller, epoch, { controller.work(workCursor) }) { page ->
                        if (page.items.isEmpty()) Text("No tasks on this page.")
                        page.items.forEach { item ->
                            Card(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) {
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
                }
                receipts -> {
                    Text("Website receipts", style = MaterialTheme.typography.headlineMedium)
                    Text("Project ${task.projectId}", style = MaterialTheme.typography.bodyMedium)
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
                else -> WorkRead(controller, epoch, { controller.task(task.id, task.projectId) }) { detail ->
                    Text(detail.title, style = MaterialTheme.typography.headlineMedium)
                    Text(detail.status.label, style = MaterialTheme.typography.titleMedium)
                    detail.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    detail.dueAt?.let { Text("Due $it") }
                    if (detail.archivedAt != null) Text("Archived")
                    Text("Project ${detail.projectId}", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { receipts = true }) { Text("View website receipts") }
                }
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
private fun <T> WorkRead(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    read: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    var result by remember { mutableStateOf<WorkReadResult<T>>(WorkReadResult.Loading) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(epoch, attempt) {
        try {
            val value = read()
            controller.commitIfCurrent(epoch) { result = WorkReadResult.Ready(value) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = when ((error as? CoreHttpException)?.status) {
                403 -> "Your organization access does not allow this view."
                404 -> "This item is no longer available."
                else -> "Could not load this view."
            }
            controller.commitIfCurrent(epoch) { result = WorkReadResult.Failed(message) }
        }
    }
    when (val current = result) {
        WorkReadResult.Loading -> CircularProgressIndicator()
        is WorkReadResult.Ready -> content(current.value)
        is WorkReadResult.Failed -> {
            Text(current.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                result = WorkReadResult.Loading
                attempt += 1
            }) { Text("Retry") }
        }
    }
}
