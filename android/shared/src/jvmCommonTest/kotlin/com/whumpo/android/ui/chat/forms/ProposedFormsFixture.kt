package com.whumpo.android.ui.chat.forms

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** tests/fixtures/proposed/forms.json: the synthetic native forms proposal, not the adopted v1 contract. */
object ProposedFormsFixture {
    private const val PATH = "tests/fixtures/proposed/forms.json"

    val root: JsonObject = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, PATH).isFile) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir) { "$PATH not found" }, PATH).readText()).jsonObject
    }

    fun group(name: String): JsonObject = root.getValue(name).jsonObject

    fun read(name: String): JsonObject = group("reads").getValue(name).jsonObject

    val wire = ProposedFormWire(JavaZoneTime)

    fun form(name: String = "open"): FormRead.Form = wire.decode(read(name)) as FormRead.Form
}

/** A copy with the member at [path] (object keys or array indexes) replaced, or removed when [value] is null. */
fun JsonElement.edit(path: List<String>, value: JsonElement?): JsonElement {
    if (path.isEmpty()) return value ?: JsonNull
    val head = path.first()
    val rest = path.drop(1)
    return when (this) {
        is JsonObject -> if (rest.isEmpty() && value == null) JsonObject(this - head)
        else JsonObject(this + (head to (this[head] ?: JsonNull).edit(rest, value)))
        is JsonArray -> JsonArray(mapIndexed { i, e -> if (i == head.toInt()) e.edit(rest, value) else e })
        else -> error("no member $head")
    }
}

fun JsonObject.edit(path: String, value: JsonElement?): JsonObject = edit(path.split("."), value).jsonObject
