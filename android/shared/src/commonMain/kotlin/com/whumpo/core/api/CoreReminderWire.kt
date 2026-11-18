package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@ConsistentCopyVisibility
data class CoreReminderTarget internal constructor(
    val taskId: String,
    val projectId: String,
    val taskVersion: Long,
    val dueAt: String,
) {
    companion object {
        fun fromTask(task: CoreWorkTask): CoreReminderTarget? {
            val due = task.dueAt ?: return null
            if (task.archivedAt != null || task.status == CoreTaskStatus.Done || task.status == CoreTaskStatus.Canceled ||
                !coreIdentifier.matches(task.id) || !coreIdentifier.matches(task.projectId) ||
                task.version !in 1..CORE_MAX_VERSION || !isReminderTime(due)
            ) return null
            return CoreReminderTarget(task.id, task.projectId, task.version, due)
        }
    }
}

enum class CoreReminderState { Armed, Delivered, Canceled, Invalidated }

@ConsistentCopyVisibility
data class CoreTaskReminder internal constructor(
    val id: String,
    val target: CoreReminderTarget,
    val state: CoreReminderState,
)

@ConsistentCopyVisibility
data class CoreReminderNotice internal constructor(
    val id: String,
    val dueAt: String,
    val observedAt: String,
    val acknowledgedAt: String?,
)

internal data class CoreReminderWirePage(val items: List<CoreReminderNotice>, val before: String?)

private val canonicalReminderTime = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")

private fun isReminderTime(value: String): Boolean = canonicalReminderTime.matches(value) &&
    runCatching {
        val canonical = Instant.parse(value).toString()
        value == canonical || (canonical.length == 20 && value == canonical.dropLast(1) + ".000Z")
    }.getOrDefault(false)

private fun JsonElement.reminderId() = text(128).also { checkDiscovery(coreIdentifier.matches(it)) }
private fun JsonElement.reminderTime() = text(24).also { checkDiscovery(isReminderTime(it)) }

private fun JsonObject.target() = CoreReminderTarget(
    field("taskId").reminderId(), field("projectId").reminderId(),
    field("taskVersion").revision(), field("dueAt").reminderTime(),
).also { checkDiscovery(it.taskVersion in 1..CORE_MAX_VERSION) }

private fun JsonElement.reminder(): CoreTaskReminder {
    val row = objectValue().closed("id", "taskId", "projectId", "taskVersion", "dueAt", "state")
    val state = when (row.field("state").text(16)) {
        "armed" -> CoreReminderState.Armed
        "delivered" -> CoreReminderState.Delivered
        "canceled" -> CoreReminderState.Canceled
        "invalidated" -> CoreReminderState.Invalidated
        else -> throw CoreProtocolException()
    }
    return CoreTaskReminder(row.field("id").reminderId(), row.target(), state)
}

internal fun decodeReminderStatus(body: String, wanted: CoreReminderTarget): CoreTaskReminder? = reminderResponse(body) { root ->
    val record = root.closed("reminder").field("reminder").unlessNull { it.reminder() }
    if (record == null || record.target == wanted) record else {
        checkDiscovery(record.target.taskId == wanted.taskId &&
            (record.state == CoreReminderState.Canceled || record.state == CoreReminderState.Invalidated))
        null
    }
}

internal fun decodeTaskReminder(body: String): CoreTaskReminder = reminderResponse(body) { it.reminder() }

internal fun decodeReminderPage(body: String): CoreReminderWirePage = reminderResponse(body) { root ->
    val before = root["nextCursor"]?.reminderId()
    if (before == null) root.closed("items") else root.closed("items", "nextCursor")
    val items = root.field("items").uniqueList(25) { element ->
        val item = element.objectValue()
        val read = item["acknowledgedAt"]?.reminderTime()
        if (read == null) item.closed("id", "dueAt", "observedAt")
        else item.closed("id", "dueAt", "observedAt", "acknowledgedAt")
        val due = item.field("dueAt").reminderTime()
        val observed = item.field("observedAt").reminderTime()
        checkDiscovery(observed >= due && (read == null || read >= observed))
        CoreReminderNotice(item.field("id").reminderId(), due, observed, read)
    }
    checkDiscovery(items.map { it.id }.distinct().size == items.size &&
        (before == null || (items.size == 25 && before == items.last().id)))
    CoreReminderWirePage(items, before)
}

internal fun decodeReminderResolution(body: String, notice: CoreReminderNotice): CoreReminderTarget = reminderResponse(body) { root ->
    root.closed("id", "taskId", "projectId", "taskVersion", "dueAt")
    checkDiscovery(root.field("id").reminderId() == notice.id)
    root.target().also { checkDiscovery(it.dueAt == notice.dueAt) }
}

internal fun decodeReminderAcknowledgement(body: String, notice: CoreReminderNotice): String = reminderResponse(body) { root ->
    root.closed("id", "acknowledgedAt")
    checkDiscovery(root.field("id").reminderId() == notice.id)
    root.field("acknowledgedAt").reminderTime().also { checkDiscovery(it >= notice.observedAt) }
}

private inline fun <T> reminderResponse(body: String, read: (JsonObject) -> T): T = try {
    read(Json.parseToJsonElement(body).objectValue())
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}
