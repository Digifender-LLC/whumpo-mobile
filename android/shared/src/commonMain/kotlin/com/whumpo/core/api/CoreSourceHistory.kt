package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

private const val maxVersion = 9_007_199_254_740_991L
internal val sourceHistoryCursor = Regex("[1-9][0-9]{0,15}\\.[1-9][0-9]{0,15}\\.[a-f0-9]{64}")
private val commitHash = Regex("[a-f0-9]{40}")
private val sourceDigest = Regex("[a-f0-9]{64}")

@Serializable
enum class CoreSourceHistoryOrigin {
    @SerialName("legacy") Legacy,
    @SerialName("source-save") Save,
    @SerialName("composition") Composition,
    @SerialName("whole-restore") Restore,
    @SerialName("slot-restore") SlotRestore,
}

data class CoreSourceHistoryVersion(
    val version: Long,
    val confirmedAt: Long,
    val origin: CoreSourceHistoryOrigin,
)

@Serializable
private data class HistoryRow(
    val version: Long,
    val confirmedAt: Long,
    val origin: CoreSourceHistoryOrigin,
    val commit: String,
    val contentDigest: String,
    val provenance: String,
    val selectedSlot: String?,
) {
    fun projection(): CoreSourceHistoryVersion {
        if (version !in 1..maxVersion || confirmedAt !in 0..8_640_000_000_000_000L ||
            !commitHash.matches(commit) || !sourceDigest.matches(contentDigest) ||
            provenance != if (origin == CoreSourceHistoryOrigin.Legacy) "git-only" else "source-bound"
        ) throw CoreProtocolException()
        if (origin == CoreSourceHistoryOrigin.SlotRestore) {
            if (selectedSlot != "site" && selectedSlot != "application") throw CoreProtocolException()
        } else if (selectedSlot != null) throw CoreProtocolException()
        return CoreSourceHistoryVersion(version, confirmedAt, origin)
    }
}

@Serializable
private data class HistoryWire(
    val projectId: String,
    val headVersion: Long,
    val entries: List<HistoryRow>,
    val nextCursor: String?,
)

internal data class SourceHistoryResult(
    val headVersion: Long,
    val entries: List<CoreSourceHistoryVersion>,
    val nextCursor: String?,
)

internal fun decodeSourceHistory(body: String, projectId: String, prior: SourceHistoryResult?): SourceHistoryResult {
    val wire = try {
        val objectValue = Json.parseToJsonElement(body) as? JsonObject ?: throw CoreProtocolException()
        objectValue.requireIntegers("headVersion")
        objectValue.requireStrings("projectId")
        objectValue.requireNullableString("nextCursor")
        val rows = objectValue["entries"] as? JsonArray ?: throw CoreProtocolException()
        if (rows.size > 20) throw CoreProtocolException()
        for (element in rows) {
            val row = element as? JsonObject ?: throw CoreProtocolException()
            row.requireIntegers("version", "confirmedAt")
            row.requireStrings("origin", "commit", "contentDigest", "provenance")
            row.requireNullableString("selectedSlot")
        }
        Json.decodeFromJsonElement<HistoryWire>(objectValue)
    } catch (_: SerializationException) {
        throw CoreProtocolException()
    }
    val entries = wire.entries
    if (wire.projectId != projectId || wire.headVersion !in 0..maxVersion || entries.size > 20 ||
        (wire.headVersion == 0L) != entries.isEmpty() ||
        entries.zipWithNext().any { (newer, older) -> newer.version <= older.version } ||
        wire.nextCursor?.let { !sourceHistoryCursor.matches(it) || entries.size != 20 || entries.last().version == 1L } == true
    ) throw CoreProtocolException()
    if (prior == null) {
        if (entries.firstOrNull()?.version?.let { it != wire.headVersion } == true) throw CoreProtocolException()
    } else if (wire.headVersion != prior.headVersion || entries.isEmpty() || entries.first().version >= prior.entries.last().version) {
        throw CoreProtocolException()
    }
    return SourceHistoryResult(wire.headVersion, entries.map { it.projection() }, wire.nextCursor)
}


private fun JsonObject.requireIntegers(vararg keys: String) {
    if (keys.any { key ->
        val value = this[key] as? JsonPrimitive
        value == null || value.isString || value.content.toLongOrNull() == null
    }) throw CoreProtocolException()
}

private fun JsonObject.requireStrings(vararg keys: String) {
    if (keys.any { (this[it] as? JsonPrimitive)?.isString != true }) throw CoreProtocolException()
}

private fun JsonObject.requireNullableString(key: String) {
    if (this[key] != JsonNull) requireStrings(key)
}
