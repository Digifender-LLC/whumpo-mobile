package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val workHostLabel = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
private val workWebAuthority = Regex("($workHostLabel(?:\\.$workHostLabel)+)(?::([1-9][0-9]{0,4}))?")
private val workLinkFragment = Regex("work/task/v1/([A-Za-z0-9][A-Za-z0-9._~-]{0,127})/([0-9a-f]{32})")
internal val workLinkEventId = Regex("[0-9a-f]{32}")
private val workLinkDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?Z")
private val workCreationOperations = setOf("tasks.create@1", "tasks.create_selected@1")
private val workHistoryOperations = workCreationOperations + setOf("tasks.move@1", "tasks.update@1", "tasks.transition@1")

class CoreWorkWebOrigin private constructor(private val authority: String) {
    companion object {
        fun parse(value: String): CoreWorkWebOrigin? = parseAuthority(value)?.let(::CoreWorkWebOrigin)
    }

    internal fun accepts(value: String): Boolean = parseAuthority(value) == authority
}

class CoreWorkLink private constructor(internal val taskId: String, internal val creationEventId: String) {
    companion object {
        const val maxLength = 452

        fun parse(value: String, origin: CoreWorkWebOrigin): CoreWorkLink? {
            if (value.length > maxLength) return null
            val pathStart = value.indexOf('/', startIndex = "https://".length)
            val fragmentStart = value.indexOf('#')
            if (pathStart < 0 || fragmentStart <= pathStart || !origin.accepts(value.substring(0, pathStart))) return null
            val path = value.substring(pathStart, fragmentStart)
            if (path != "/" && path != "/work-link") return null
            val fragment = workLinkFragment.matchEntire(value.substring(fragmentStart + 1)) ?: return null
            return CoreWorkLink(fragment.groupValues[1], fragment.groupValues[2])
        }
    }
}

private fun parseAuthority(value: String): String? {
    if (!value.startsWith("https://") || value.length > 267) return null
    val parts = workWebAuthority.matchEntire(value.removePrefix("https://")) ?: return null
    val host = parts.groupValues[1]
    if (host.length > 253) return null
    val portText = parts.groupValues[2]
    val port = if (portText.isEmpty()) 443 else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return if (port == 443) host else "$host:$port"
}

internal data class CoreWorkLinkEvent(val id: String, val version: Long, val operation: String) {
    val isCreation: Boolean get() = version == 1L && operation in workCreationOperations
}

internal fun decodeWorkLinkHistory(body: String, taskId: String): CoreWorkLinkEvent? = workLinkResponse(body) { page ->
    val cursor = page["nextCursor"]?.workEventId()
    if (cursor == null) page.closed("items") else page.closed("items", "nextCursor")
    val entry = page.field("items").uniqueList(1) { value ->
        val row = value.objectValue()
        // Only these fields belong to the link proof. History can carry other metadata for separate features.
        val id = row.field("id").workEventId()
        val version = row.field("taskVersion").revision()
        val operation = row.field("operation").text(32)
        row.field("createdAt").workDate()
        if (operation !in workHistoryOperations) throw CoreProtocolException()
        row["snapshot"]?.objectValue()?.let { snapshot ->
            if (snapshot.field("id").workId() != taskId || snapshot.field("version").revision() != version) {
                throw CoreProtocolException()
            }
        }
        CoreWorkLinkEvent(id, version, operation)
    }.singleOrNull()
    if (cursor != null && cursor != entry?.id) throw CoreProtocolException()
    entry
}

internal fun decodeLinkedWorkTask(body: String, taskId: String): CoreWorkTask = workLinkResponse(body) { row ->
    val required = setOf("id", "projectId", "title", "description", "dueAt", "status", "version", "parentTaskId")
    val optional = setOf("archivedAt", "assigneeAgentId", "assignmentVersion")
    if (!row.keys.containsAll(required) || row.keys.any { it !in required && it !in optional }) throw CoreProtocolException()
    val id = row.field("id").workId()
    val version = row.field("version").revision()
    if (id != taskId || ("assigneeAgentId" in row) != ("assignmentVersion" in row)) throw CoreProtocolException()
    row["assignmentVersion"]?.let {
        if (it.revision() > version) throw CoreProtocolException()
        row.field("assigneeAgentId").unlessNull { value -> value.workId() }
    }
    val statusField = row.field("status")
    statusField.text(32)
    val status = Json.decodeFromJsonElement(CoreTaskStatus.serializer(), statusField)
    CoreWorkTask(
        id = id,
        projectId = row.field("projectId").workId(),
        title = row.field("title").workText(200, allowEmpty = false),
        description = row.field("description").unlessNull { it.workText(10_000, allowEmpty = true) },
        parentTaskId = row.field("parentTaskId").unlessNull { it.workId() },
        dueAt = row.field("dueAt").unlessNull { it.workDate() },
        status = status,
        version = version,
        archivedAt = row["archivedAt"]?.workDate(),
    )
}

private fun JsonElement.workId() = text(128).takeIf(coreIdentifier::matches) ?: throw CoreProtocolException()
private fun JsonElement.workEventId() = text(32).takeIf(workLinkEventId::matches) ?: throw CoreProtocolException()

private fun JsonElement.workDate(): String = text(24).also {
    if (!workLinkDate.matches(it)) throw CoreProtocolException()
    Instant.parse(it)
}

private fun JsonElement.workText(limit: Int, allowEmpty: Boolean): String {
    val value = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw CoreProtocolException()
    if (value.isEmpty() && allowEmpty) return value
    if (value.isEmpty() || value.length > limit * 2 || value.count { !it.isLowSurrogate() } > limit ||
        value.indices.any { index ->
            val char = value[index]
            char == '\u0000' ||
                (char.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() != true) ||
                (char.isLowSurrogate() && value.getOrNull(index - 1)?.isHighSurrogate() != true)
        }
    ) throw CoreProtocolException()
    return value
}

private inline fun <T> workLinkResponse(body: String, decode: (JsonObject) -> T): T = try {
    decode(Json.parseToJsonElement(body).objectValue())
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}
