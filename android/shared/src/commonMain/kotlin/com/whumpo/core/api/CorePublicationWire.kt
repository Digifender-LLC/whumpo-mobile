package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val siteTargetSlug = Regex("site-[0-9a-f-]{36}")

internal fun decodePublicationTarget(body: String, orgId: String, projectId: String): CoreWebsiteTarget =
    decodePublicationResponse(body) { root ->
        val row = root.closed("id", "orgId", "projectId", "slug", "version", "activeEffectId")
        val target = CoreWebsiteTarget(
            id = row.field("id").publicationId(),
            orgId = row.field("orgId").publicationId(),
            projectId = row.field("projectId").publicationId(),
            slug = row.field("slug").text(41),
            version = row.field("version").revision(),
            activeEffectId = row.field("activeEffectId").unlessNull { it.publicationId() },
        )
        if (target.orgId != orgId || target.projectId != projectId || !siteTargetSlug.matches(target.slug) ||
            (target.version == 1L && target.activeEffectId != null)
        ) throw CoreProtocolException()
        target
    }

internal fun decodeCurrentPublicationReceipt(body: String, target: CoreWebsiteTarget): CoreWebsiteReceipt =
    decodePublicationResponse(body) { root ->
        val nextCursor = root["nextCursor"]?.text(16)
        if (nextCursor == null) root.closed("items") else root.closed("items", "nextCursor")
        val receipt = root.field("items").uniqueList(1, ::decodePublicationItem).singleOrNull()
            ?: throw CoreProtocolException()
        if (receipt.orgId != target.orgId || receipt.targetId != target.id ||
            receipt.targetVersion != target.version || receipt.effectId != target.activeEffectId ||
            (nextCursor != null && nextCursor != receipt.targetVersion.toString())
        ) throw CoreProtocolException()
        receipt
    }

private fun decodePublicationItem(value: JsonElement): CoreWebsiteReceipt {
    val item = value.objectValue().closed("operation", "buildId", "receipt")
    if (item.field("operation") != JsonPrimitive("sites.publish@1")) throw CoreProtocolException()
    item.field("buildId").publicationId()
    val receipt = item.field("receipt").objectValue()
    val fields = arrayOf("orgId", "effectId", "targetId", "targetVersion", "artifactDigest", "artifactBytes", "confirmedAt")
    if ("pageManifestDigest" in receipt || "pageCount" in receipt) {
        receipt.closed(*fields, "pageManifestDigest", "pageCount")
    } else {
        receipt.closed(*fields)
    }
    val version = receipt.field("targetVersion").revision()
    val bytes = receipt.field("artifactBytes").revision()
    val pages = receipt["pageCount"]?.revision()
    val confirmedAt = receipt.field("confirmedAt").text(24)
    if (version < 2 || bytes > 32_768 || (pages != null && pages !in 2..4) ||
        !coreTimestamp.matches(confirmedAt)
    ) throw CoreProtocolException()
    Instant.parse(confirmedAt)
    return CoreWebsiteReceipt(
        orgId = receipt.field("orgId").publicationId(),
        effectId = receipt.field("effectId").publicationId(),
        targetId = receipt.field("targetId").publicationId(),
        targetVersion = version,
        artifactDigest = receipt.field("artifactDigest").publicationDigest(),
        confirmedAt = confirmedAt,
        artifactBytes = bytes.toInt(),
        pageManifestDigest = receipt["pageManifestDigest"]?.publicationDigest(),
        pageCount = pages?.toInt(),
    )
}

private fun JsonElement.publicationId(): String =
    text(128).takeIf(coreIdentifier::matches) ?: throw CoreProtocolException()

private fun JsonElement.publicationDigest(): String =
    text(64).takeIf(coreDigest::matches) ?: throw CoreProtocolException()

private inline fun <T> decodePublicationResponse(body: String, decode: (JsonObject) -> T): T = try {
    decode(Json.parseToJsonElement(body).objectValue())
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}
