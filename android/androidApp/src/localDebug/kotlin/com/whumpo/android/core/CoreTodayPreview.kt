package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.core.api.CoreTodayDecisionAccess
import com.whumpo.core.api.CoreTodayDueState
import com.whumpo.core.api.CoreTodaySection
import com.whumpo.core.api.CoreTodaySiteActivity
import com.whumpo.core.api.CoreTodaySiteKind
import com.whumpo.core.api.CoreTodayTaskOperation
import com.whumpo.core.api.CoreTodayTaskSource
import com.whumpo.core.api.CoreWebsiteOperation
import com.whumpo.core.auth.CoreOrganizationSessionController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class TodayTask(val today: CoreOrganizationSessionController.Today, val source: CoreTodayTaskSource)
private data class TodayReceipt(val today: CoreOrganizationSessionController.Today, val activity: CoreTodaySiteActivity)

/** Mounted under the session epoch key, so an organization change or sign-out discards all of this state. */
@Composable
internal fun CoreTodayPreview(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    organizationName: String,
    close: () -> Unit,
) {
    var task by remember { mutableStateOf<TodayTask?>(null) }
    var receipt by remember { mutableStateOf<TodayReceipt?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val goBack: () -> Unit = {
        when {
            task != null -> task = null
            receipt != null -> receipt = null
            else -> close()
        }
    }
    BackHandler(onBack = goBack)
    key(task, receipt, refresh) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val openTask = task
            val openReceipt = receipt
            TextButton(onClick = goBack) {
                Text(if (openTask != null || openReceipt != null) "Back to Today" else "Back to organization")
            }
            Text(organizationName, style = MaterialTheme.typography.labelLarge)
            when {
                openTask != null -> WorkRead(controller, epoch, { controller.todayTask(openTask.today, openTask.source) }) { detail ->
                    Text(detail.title, style = MaterialTheme.typography.headlineMedium)
                    Text(detail.status.label, style = MaterialTheme.typography.titleMedium)
                    detail.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    detail.dueAt?.let { Text("Due ${readable(it)}") }
                    if (detail.archivedAt != null) Text("Archived")
                }
                openReceipt != null -> {
                    Text("Website receipt", style = MaterialTheme.typography.headlineMedium)
                    WorkRead(controller, epoch, { controller.todayWebsiteReceipt(openReceipt.today, openReceipt.activity) }) { item ->
                        if (item == null) {
                            Text("This receipt is no longer listed in the website history.")
                        } else {
                            TodayCard {
                                Text(
                                    if (item.operation == CoreWebsiteOperation.Publish) "Website published" else "Website withdrawn",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("Version ${item.receipt.targetVersion}")
                                Text("Confirmed ${readable(item.receipt.confirmedAt)}")
                                item.receipt.pageCount?.let { Text("$it pages") }
                            }
                        }
                    }
                }
                else -> {
                    Text("Today", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    OutlinedButton(onClick = { refresh += 1 }) { Text("Refresh") }
                    WorkRead(controller, epoch, { controller.today() }) { today ->
                        TodayBriefing(
                            today,
                            openTask = { source -> controller.commitIfCurrent(epoch) { task = TodayTask(today, source) } },
                            openReceipt = { activity -> controller.commitIfCurrent(epoch) { receipt = TodayReceipt(today, activity) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayBriefing(
    today: CoreOrganizationSessionController.Today,
    openTask: (CoreTodayTaskSource) -> Unit,
    openReceipt: (CoreTodaySiteActivity) -> Unit,
) {
    val briefing = today.briefing
    val sections = briefing.sections
    Text("Updated ${readable(briefing.generatedAt)}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Deadlines through ${readable(briefing.dueBefore)}. Results since ${readable(briefing.verifiedSince)}.",
        style = MaterialTheme.typography.bodySmall,
    )
    val hasOmittedItems = listOf(sections.blocked, sections.deadlines, sections.approvals, sections.verified).any { it.truncated } ||
        sections.siteActivity?.truncated == true
    if (briefing.empty && !hasOmittedItems) {
        TodayCard {
            Text("You’re caught up", style = MaterialTheme.typography.titleMedium)
            Text("There are no due tasks or decisions waiting in this briefing.")
        }
        return
    }
    TodaySection("Blocked priorities", sections.blocked, "Nothing is blocked.") { item ->
        TodayCard(onClick = { openTask(item.source) }) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(item.status.label)
            item.dueAt?.let { Text("Due ${readable(it)}") }
        }
    }
    TodaySection("Deadlines", sections.deadlines, "No deadlines in this window.") { item ->
        TodayCard(onClick = { openTask(item.source) }) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(
                when (item.dueState) {
                    CoreTodayDueState.Overdue -> "Overdue · was due ${readable(item.dueAt)}"
                    CoreTodayDueState.Upcoming -> "Due ${readable(item.dueAt)}"
                },
                color = if (item.dueState == CoreTodayDueState.Overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(item.status.label, style = MaterialTheme.typography.bodySmall)
        }
    }
    TodaySection("Pending decisions", sections.approvals, "No decisions are waiting.") { item ->
        TodayCard {
            Text("Approval waiting for a decision", style = MaterialTheme.typography.titleMedium)
            Text("Pending review · not completed")
            Text("Expires ${readable(item.expiresAt)}")
            Text(decisionLabel(item.decisionAccess), style = MaterialTheme.typography.bodySmall)
        }
    }
    TodaySection("Verified task results", sections.verified, "No confirmed task results in this window.") { item ->
        TodayCard {
            Text(operationLabel(item.operation), style = MaterialTheme.typography.titleMedium)
            Text("Confirmed task receipt · ${readable(item.observedAt)}")
        }
    }
    val site = sections.siteActivity
    if (site == null) {
        Text("Website activity", style = MaterialTheme.typography.titleLarge)
        Text("No website activity in this window.")
    } else {
        TodaySection("Website activity", site, "") { item -> SiteActivity(item, openReceipt) }
    }
}

@Composable
private fun SiteActivity(item: CoreTodaySiteActivity, openReceipt: (CoreTodaySiteActivity) -> Unit) {
    val source = item.source
    if (source.kind.review) {
        TodayCard {
            Text(
                if (source.kind == CoreTodaySiteKind.PublicationReview) "Website publication awaiting review"
                else "Website withdrawal awaiting review",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (source.kind == CoreTodaySiteKind.PublicationReview) "Pending review · not published"
                else "Pending review · not withdrawn",
            )
            item.createdAt?.let { Text("Requested ${readable(it)}") }
            item.decisionAccess?.let { Text(decisionLabel(it), style = MaterialTheme.typography.bodySmall) }
        }
    } else {
        TodayCard(onClick = { openReceipt(item) }) {
            Text(
                if (source.kind == CoreTodaySiteKind.PublicationReceipt) "Website published" else "Website withdrawn",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Confirmed website receipt · version ${source.targetVersion}")
            Text(readable(item.observedAt), style = MaterialTheme.typography.bodySmall)
            Text("View receipt", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun <T> TodaySection(
    title: String,
    section: CoreTodaySection<T>,
    emptyText: String,
    row: @Composable (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (section.items.isEmpty() && !section.truncated) Text(emptyText)
    section.items.forEach { row(it) }
    if (section.truncated) {
        Text(
            if (section.items.isEmpty()) "Items exist but could not be included in this briefing."
            else "Showing the first ${section.items.size}. More items exist.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TodayCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
    if (onClick == null) Card(Modifier.fillMaxWidth(), content = body)
    else Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = body)
}

private fun decisionLabel(access: CoreTodayDecisionAccess) = when (access) {
    CoreTodayDecisionAccess.Available -> "Decisions are not available in this preview."
    CoreTodayDecisionAccess.ReauthenticationRequired ->
        "Deciding requires signing in again. Decisions are not available in this preview."
}

private fun operationLabel(operation: CoreTodayTaskOperation) = when (operation) {
    CoreTodayTaskOperation.Create -> "Task created"
    CoreTodayTaskOperation.Update -> "Task updated"
    CoreTodayTaskOperation.Transition -> "Task status changed"
    CoreTodayTaskOperation.Move -> "Task moved"
    CoreTodayTaskOperation.CreateSelected -> "Selected tasks created"
}

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun readable(value: String): String =
    runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(timeFormat) }.getOrDefault(value)
