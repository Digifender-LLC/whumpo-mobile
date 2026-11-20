package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

class CoreRecordedTaskSource internal constructor(
    val taskId: String, val eventId: String, val conversationId: String, val messageId: String,
)
data class CoreWorkHistoryChange(
    val id: String, val version: Long, val label: String, val createdAt: String,
    val snapshot: CoreWorkTask?, val source: String?, val origin: CoreRecordedTaskSource?,
)
@ConsistentCopyVisibility
data class CoreWorkHistoryBookmark internal constructor(val id: String, val version: Long)
data class CoreWorkHistoryPage(val changes: List<CoreWorkHistoryChange>, val next: CoreWorkHistoryBookmark?)
@ConsistentCopyVisibility
data class CoreWorkSourceBookmark internal constructor(val id: String, val sequence: Long)
data class CoreWorkSourceResult(val content: String?, val createdAt: String?, val next: CoreWorkSourceBookmark?)

private val historyLabels = mapOf("tasks.create@1" to "Task created", "tasks.create_selected@1" to "Task created from a selection",
    "tasks.update@1" to "Task updated", "tasks.move@1" to "Task moved", "tasks.transition@1" to "Status changed")
private val historySourceLabels = mapOf("human" to "Member", "agent" to "Agent run", "automation" to "Automation")
private val sourceDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?Z")

internal fun decodeWorkHistory(body: String, taskId: String, after: CoreWorkHistoryBookmark?): CoreWorkHistoryPage = try {
    val root = Json.parseToJsonElement(body).queryObject()
    root.queryClosed(setOf("items"), setOf("nextCursor"))
    val rows = root.queryField("items") as? JsonArray ?: throw CoreProtocolException()
    if (rows.size > 4) throw CoreProtocolException()
    var version = after?.version ?: 0
    val ids = mutableSetOf<String>().also { if (after != null) it.add(after.id) }
    val changes = rows.map { value ->
        val row = value.queryObject()
        row.queryClosed(setOf("id", "taskVersion", "operation", "createdAt"),
            setOf("snapshot", "source", "actorId", "provenance", "assignment", "automation"))
        val id = row.queryField("id").queryString()
        val revision = (row.queryField("taskVersion") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw CoreProtocolException()
        if (!workLinkEventId.matches(id) || !ids.add(id) || revision !in (version + 1)..CORE_MAX_VERSION) throw CoreProtocolException()
        version = revision
        val operation = row.queryField("operation").queryString()
        val label = historyLabels[operation] ?: throw CoreProtocolException()
        val at = sourceTimestamp(row.queryField("createdAt").queryString())
        val snapshot = row["snapshot"]?.let { decodeLinkedWorkTask(it.toString(), taskId) }
        if (snapshot == null) {
            if (row.keys.any { it in setOf("source", "actorId", "provenance", "assignment", "automation") }) throw CoreProtocolException()
            CoreWorkHistoryChange(id, revision, label, at, null, null, null)
        } else {
            if (snapshot.version != revision) throw CoreProtocolException()
            row.queryField("actorId").queryId()
            val source = historySourceLabels[row.queryField("source").queryString()] ?: throw CoreProtocolException()
            // These recognized context objects are not rendered or retained by this reader.
            row["assignment"]?.queryObject(); row["automation"]?.queryObject()
            val origin = row["provenance"]?.queryObject()?.let { proof ->
                proof.queryClosed(setOf("proposalId", "itemId", "conversationId", "sourceMessageId"))
                proof.queryField("proposalId").queryId(); proof.queryField("itemId").queryId()
                if (operation != "tasks.create_selected@1" || revision != 1L) throw CoreProtocolException()
                CoreRecordedTaskSource(taskId, id, proof.queryField("conversationId").queryId(), proof.queryField("sourceMessageId").queryId())
            }
            CoreWorkHistoryChange(id, revision,
                if (operation == "tasks.transition@1" && snapshot.status == CoreTaskStatus.Done) "Completion recorded" else label,
                at, snapshot, source, origin)
        }
    }
    val last = changes.lastOrNull()
    val next = root["nextCursor"]?.queryString()?.let {
        if (changes.size != 4 || it != last?.id) throw CoreProtocolException()
        CoreWorkHistoryBookmark(it, last.version)
    }
    CoreWorkHistoryPage(changes, next)
} catch (_: IllegalArgumentException) { throw CoreProtocolException() }

/** Validates the page but retains only the exact recorded user body and scalar continuation. */
internal fun decodeWorkSource(body: String, origin: CoreRecordedTaskSource, after: CoreWorkSourceBookmark?): CoreWorkSourceResult = try {
    val root = Json.parseToJsonElement(body).queryObject()
    root.queryClosed(setOf("items"), setOf("nextCursor"))
    val rows = root.queryField("items") as? JsonArray ?: throw CoreProtocolException()
    if (rows.size > 5) throw CoreProtocolException()
    val ids = mutableSetOf<String>().also { after?.let { value -> it.add(value.id) } }
    var sequence = after?.sequence ?: 0
    var lastId: String? = null
    var content: String? = null
    var createdAt: String? = null
    for (value in rows) {
        val row = value.queryObject()
        val base = setOf("id", "sequence", "userId", "content", "role", "createdAt")
        row.queryClosed(base, setOf("runId", "receiptIds", "personalLayout", "delegation", "delegationSource"))
        val id = row.queryField("id").queryId()
        val nextSequence = (row.queryField("sequence") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw CoreProtocolException()
        if (!ids.add(id) || nextSequence !in (sequence + 1)..CORE_MAX_VERSION) throw CoreProtocolException()
        sequence = nextSequence; lastId = id
        row.queryField("userId").queryId()
        val role = row.queryField("role").queryString()
        if (role !in setOf("user", "assistant") || role == "user" && row.keys != base) throw CoreProtocolException()
        val text = row.queryField("content").workText(8192, allowEmpty = true)
        if (text.encodeToByteArray().size > 8192) throw CoreProtocolException()
        val at = sourceTimestamp(row.queryField("createdAt").queryString())
        if (id == origin.messageId) {
            if (role != "user") throw CoreProtocolException()
            content = text; createdAt = at
        }
    }
    val cursor = root["nextCursor"]?.queryId()
    if (cursor != null && (rows.size != 5 || cursor != lastId)) throw CoreProtocolException()
    CoreWorkSourceResult(content, createdAt, if (content == null && cursor != null) CoreWorkSourceBookmark(cursor, sequence) else null)
} catch (_: IllegalArgumentException) { throw CoreProtocolException() }

private fun sourceTimestamp(value: String): String {
    if (!sourceDate.matches(value)) throw CoreProtocolException()
    Instant.parse(value)
    return value
}
