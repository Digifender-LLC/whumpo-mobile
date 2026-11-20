package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Criteria are optional; a customer directory is never required to read Work. */
data class CoreWorkFilters(
    val workspaceId: String? = null,
    val projectId: String? = null,
    val status: CoreTaskStatus? = null,
    val due: CoreWorkDue = CoreWorkDue.All,
    val customer: CoreWorkCustomer = CoreWorkCustomer.All,
    val customerId: String? = null,
    val assignee: CoreWorkAssignee = CoreWorkAssignee.All,
    val assigneeId: String? = null,
)
enum class CoreWorkDue(val wire: String) { All("all"), Overdue("overdue"), Unscheduled("unscheduled") }
enum class CoreWorkCustomer(val wire: String) { All("all"), Associated("associated"), Unassociated("unassociated") }
enum class CoreWorkAssignee(val wire: String) { All("all"), Assigned("assigned"), Unassigned("unassigned") }

data class CoreWorkQueryItem(val task: CoreWorkTask, val workspaceId: String, val assigneeId: String?)
data class CoreWorkCounts(val total: Int, val overdue: Int, val byStatus: Map<CoreTaskStatus, Int>)
data class CoreWorkQueryPage(
    val items: List<CoreWorkQueryItem>, val counts: CoreWorkCounts, val asOf: String, val nextCursor: String?,
)

internal val workQueryStatuses = linkedMapOf(
    "backlog" to CoreTaskStatus.Backlog, "ready" to CoreTaskStatus.Ready,
    "in_progress" to CoreTaskStatus.InProgress, "blocked" to CoreTaskStatus.Blocked,
    "needs_approval" to CoreTaskStatus.NeedsApproval, "done" to CoreTaskStatus.Done,
    "canceled" to CoreTaskStatus.Canceled,
)
internal const val CORE_WORK_QUERY_PAGE_SIZE = 50

internal fun workQueryPath(filters: CoreWorkFilters, cursor: String? = null): String {
    val values = linkedMapOf("limit" to CORE_WORK_QUERY_PAGE_SIZE.toString())
    for ((name, value) in listOf("workspaceId" to filters.workspaceId, "projectId" to filters.projectId,
        "customerId" to filters.customerId, "assigneeAgentId" to filters.assigneeId)) {
        if (value != null) { require(coreIdentifier.matches(value)); values[name] = value }
    }
    require(filters.customer != CoreWorkCustomer.Unassociated || filters.customerId == null)
    require(filters.assignee != CoreWorkAssignee.Unassigned || filters.assigneeId == null)
    filters.status?.let { status -> values["status"] = workQueryStatuses.entries.single { it.value == status }.key }
    values["due"] = filters.due.wire
    values["customer"] = filters.customer.wire
    values["assignee"] = filters.assignee.wire
    cursor?.let { require(coreDigest.matches(it)); values["cursor"] = it }
    // Every dynamic value has the closed unreserved identifier/cursor grammar above.
    return "/v1/work/tasks?" + values.entries.joinToString("&") { "${it.key}=${it.value}" }
}

internal fun decodeWorkQuery(body: String): CoreWorkQueryPage = try {
    val root = Json.parseToJsonElement(body).queryObject()
    root.queryClosed(setOf("items", "counts", "asOf"), setOf("nextCursor"))
    val counts = root.queryField("counts").queryObject().also {
        it.queryClosed(setOf("total", "overdue", "byStatus"))
    }
    val total = counts.queryField("total").queryCount()
    val overdue = counts.queryField("overdue").queryCount()
    val statuses = counts.queryField("byStatus").queryObject().also { it.queryClosed(workQueryStatuses.keys) }
    val grouped = workQueryStatuses.map { (name, status) -> status to statuses.queryField(name).queryCount() }.toMap()
    if (overdue > total || grouped.values.sum() != total) throw CoreProtocolException()
    val asOf = root.queryField("asOf").queryString().also {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?Z").matches(it)) throw CoreProtocolException()
        Instant.parse(it)
    }
    val values = root.queryField("items") as? JsonArray ?: throw CoreProtocolException()
    if (values.size > CORE_WORK_QUERY_PAGE_SIZE || values.size > total) throw CoreProtocolException()
    val items = values.map { value ->
        val row = value.queryObject()
        val id = row.queryField("id").queryId()
        val workspace = row.queryField("workspaceId").queryId()
        val taskBody = JsonObject(row.filterKeys { it != "workspaceId" }).toString()
        val task = decodeLinkedWorkTask(taskBody, id)
        val assignee = row["assigneeAgentId"]?.takeUnless { it == JsonNull }?.queryId()
        CoreWorkQueryItem(task, workspace, assignee)
    }
    if (items.map { it.task.id }.distinct().size != items.size ||
        items.groupingBy { it.task.status }.eachCount().any { (status, count) -> count > grouped.getValue(status) }) throw CoreProtocolException()
    val cursor = root["nextCursor"]?.queryString()?.also {
        if (!coreDigest.matches(it) || items.isEmpty()) throw CoreProtocolException()
    }
    CoreWorkQueryPage(items, CoreWorkCounts(total, overdue, grouped), asOf, cursor)
} catch (_: IllegalArgumentException) { throw CoreProtocolException() }

internal fun workQueryOverdue(task: CoreWorkTask, asOf: String): Boolean = task.archivedAt == null &&
    task.status !in setOf(CoreTaskStatus.Done, CoreTaskStatus.Canceled) &&
    task.dueAt?.let { Instant.parse(it) < Instant.parse(asOf) } == true

internal fun verifyWorkQueryCriteria(page: CoreWorkQueryPage, filters: CoreWorkFilters) {
    if (page.items.any { item ->
        val task = item.task
        (filters.projectId != null && task.projectId != filters.projectId) ||
            (filters.status != null && task.status != filters.status) ||
            (filters.assigneeId != null && item.assigneeId != filters.assigneeId) ||
            (filters.assignee == CoreWorkAssignee.Assigned && item.assigneeId == null) ||
            (filters.assignee == CoreWorkAssignee.Unassigned && item.assigneeId != null) ||
            (filters.due == CoreWorkDue.Unscheduled && task.dueAt != null) ||
            (filters.due == CoreWorkDue.Overdue && !workQueryOverdue(task, page.asOf))
    }) throw CoreProtocolException()
    // Workspace scopes include descendants; customer membership is a server projection, not a task field.
}

internal fun JsonElement.queryObject() = this as? JsonObject ?: throw CoreProtocolException()
internal fun JsonObject.queryField(key: String) = this[key] ?: throw CoreProtocolException()
internal fun JsonObject.queryClosed(required: Set<String>, optional: Set<String> = emptySet()) {
    if (!keys.containsAll(required) || keys.any { it !in required && it !in optional }) throw CoreProtocolException()
}
internal fun JsonElement.queryString(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw CoreProtocolException()
internal fun JsonElement.queryId() = queryString().takeIf(coreIdentifier::matches) ?: throw CoreProtocolException()
internal fun JsonElement.queryCount(): Int = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    ?.takeIf { it in 0..10_000 } ?: throw CoreProtocolException()
