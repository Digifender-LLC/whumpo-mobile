package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

enum class CoreWorkDirectory { Workspace, Project, Customer, Assignee }
data class CoreWorkChoice(val id: String, val name: String, val workspaceId: String? = null)
data class CoreWorkChoices(val items: List<CoreWorkChoice>, val nextCursor: String?)

/** Retains only display labels/identities; customer contact fields never enter screen state. */
internal fun decodeWorkDirectory(body: String, kind: CoreWorkDirectory, previous: String?, workspace: String?): CoreWorkChoices = try {
    val page = Json.parseToJsonElement(body).queryObject()
    page.queryClosed(setOf("items"), setOf("nextCursor"))
    val rows = page.queryField("items") as? JsonArray ?: throw CoreProtocolException()
    if (rows.size > 20) throw CoreProtocolException()
    val items = rows.map { entry ->
        val row = entry.queryObject()
        val id = row.queryField("id").queryId()
        row.queryField("version").directoryRevision()
        when (kind) {
            CoreWorkDirectory.Workspace -> {
                row.queryClosed(setOf("id", "name", "version"), setOf("parentWorkspaceId"))
                row["parentWorkspaceId"]?.queryId()
                CoreWorkChoice(id, row.queryField("name").directoryText(200, 800))
            }
            CoreWorkDirectory.Project -> {
                row.queryClosed(setOf("id", "workspaceId", "name", "version"))
                val parent = row.queryField("workspaceId").queryId()
                if (workspace != null && parent != workspace) throw CoreProtocolException()
                val name = row.queryField("name").takeUnless { it == JsonNull }?.directoryText(200, 800)
                CoreWorkChoice(id, name ?: "Project name unavailable", parent)
            }
            CoreWorkDirectory.Assignee -> {
                row.queryClosed(setOf("id", "kind", "name", "enabled", "version"))
                val type = row.queryField("kind").queryString()
                if (type !in setOf("partner", "specialist")) throw CoreProtocolException()
                val enabled = (row.queryField("enabled") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: throw CoreProtocolException()
                val name = row.queryField("name").takeUnless { it == JsonNull }?.directoryText(200, 800)
                    ?: if (type == "partner") "Partner" else "Unnamed specialist"
                CoreWorkChoice(id, name + if (enabled) "" else " (disabled)")
            }
            CoreWorkDirectory.Customer -> customerChoice(row, id)
        }
    }
    if (items.distinctBy { it.id }.size != items.size) throw CoreProtocolException()
    val next = page["nextCursor"]?.queryId()
    if (next != null && (items.isEmpty() || next == previous)) throw CoreProtocolException()
    CoreWorkChoices(items, next)
} catch (_: IllegalArgumentException) { throw CoreProtocolException() }

private fun customerChoice(row: JsonObject, id: String): CoreWorkChoice {
    row.queryClosed(setOf("id", "version", "kind", "state", "createdAt", "updatedAt", "archivedAt",
        "businessId", "displayName", "email", "phone", "notes", "stage", "contactPermission"))
    if (row.queryField("kind").queryString() != "customer" ||
        row.queryField("state").queryString() !in setOf("active", "archived") ||
        row.queryField("stage").queryString() !in setOf("prospect", "active", "inactive") ||
        row.queryField("contactPermission").queryString() !in setOf("not_recorded", "permitted", "declined")) throw CoreProtocolException()
    for (key in listOf("createdAt", "updatedAt")) Instant.parse(row.queryField(key).queryString())
    row.queryField("archivedAt").takeUnless { it == JsonNull }?.let { Instant.parse(it.queryString()) }
    row.queryField("businessId").takeUnless { it == JsonNull }?.queryId()
    row.queryField("email").takeUnless { it == JsonNull }?.queryString()?.let {
        if (it.length > 254 || !Regex("^[\\x21-\\x7e]+@[^@]+\\.[^@]+$").matches(it)) throw CoreProtocolException()
    }
    row.queryField("phone").takeUnless { it == JsonNull }?.queryString()?.let {
        if (!Regex("\\+[1-9][0-9]{7,14}").matches(it)) throw CoreProtocolException()
    }
    row.queryField("notes").takeUnless { it == JsonNull }?.directoryText(2000, 8192, notes = true)
    val name = row.queryField("displayName").directoryText(120, 512)
    return CoreWorkChoice(id, name + if (row.queryField("state").queryString() == "archived") " (archived)" else "")
}

private fun JsonElement.directoryRevision(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    ?.takeIf { it in 1..CORE_MAX_VERSION } ?: throw CoreProtocolException()
private fun JsonElement.directoryText(characters: Int, bytes: Int, notes: Boolean = false): String {
    val value = queryString()
    if (value.isEmpty() || value != value.trim() || value.count { !it.isLowSurrogate() } > characters ||
        value.encodeToByteArray().size > bytes || value.indices.any { index ->
            val c = value[index]
            (c.code in 0..31 || c.code in 127..159) && !(notes && c in "\t\n") ||
                c.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() != true ||
                c.isLowSurrogate() && value.getOrNull(index - 1)?.isHighSurrogate() != true
        }) throw CoreProtocolException()
    return value
}
