package com.whumpo.android.ui.chat.forms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One `interaction.resources` page. [nextBefore] is opaque and is echoed unchanged; [JsonNull] means no more pages. */
data class ResourcePage(val items: List<FormOption>, val nextBefore: JsonElement)

/**
 * Decodes an adopted resources page, or returns null when it is malformed or belongs to another form, digest or field
 * (a stale or misrouted reply is never merged). Photos are deferred, so `photo` must be null.
 */
fun decodeResourcePage(page: JsonObject, interactionId: String, definitionDigest: String, fieldId: String): ResourcePage? {
    val keys = setOf("version", "interactionId", "definitionDigest", "fieldId", "collection", "items", "nextBefore")
    if (page.keys != keys) return null
    fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    if ((page["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != 1) return null
    if (str(page["interactionId"]) != interactionId || str(page["definitionDigest"]) != definitionDigest) return null
    if (str(page["fieldId"]) != fieldId || str(page["collection"]) != "projects") return null
    val items = (page["items"] as? JsonArray ?: return null).map { item ->
        val o = item as? JsonObject ?: return null
        if (o.keys != setOf("id", "name", "photo") || o["photo"] != JsonNull) return null
        val id = str(o["id"])?.takeIf(::isUuid) ?: return null
        FormOption(id, str(o["name"]) ?: return null)
    }
    if (items.map { it.id }.toSet().size != items.size) return null
    return ResourcePage(items, page.getValue("nextBefore"))
}

/** Loaded pages for one resource field of one form; [names] keeps load order. */
data class ResourceField(
    val names: Map<String, String> = emptyMap(),
    /** The cursor for the next page: null before the first load, [JsonNull] when exhausted. */
    val next: JsonElement? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val canLoadMore: Boolean get() = !loading && next != JsonNull
}
