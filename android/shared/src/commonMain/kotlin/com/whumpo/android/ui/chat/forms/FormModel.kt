package com.whumpo.android.ui.chat.forms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A native form as the app renders it (docs/android/chat-forms.md). Built only by [ProposedFormWire] from a decoded
 * definition; nothing here reads the network or guesses a field.
 */
data class FormDefinition(
    val title: String,
    val summary: String,
    val fields: List<FormField>,
    val continuationBinding: String? = null,
)

data class FormOption(val id: String, val label: String)

sealed interface FieldKind {
    data class Text(val maxLength: Int, val long: Boolean) : FieldKind
    data class Choice(val options: List<FormOption>) : FieldKind
    data class MultiChoice(val options: List<FormOption>) : FieldKind
    data object YesNo : FieldKind
    data class Number(val min: Double, val max: Double, val step: Double) : FieldKind
    data object Date : FieldKind
    data class DateTime(val zone: String) : FieldKind
    data class Resource(val multiple: Boolean, val maxItems: Int) : FieldKind
}

/** Shows a field only while an earlier choice or yes/no field equals [equals]. */
data class ShowWhen(val fieldId: String, val equals: Answer)

data class FormField(
    val id: String,
    val label: String,
    val help: String,
    val required: Boolean,
    val kind: FieldKind,
    val default: Answer? = null,
    val suggested: Boolean = false,
    val showWhen: ShowWhen? = null,
    val group: Int = 0,
)

/** One field's answer. Dates and times are their wire strings; a number keeps the text as typed. */
sealed interface Answer {
    data class Text(val value: String) : Answer
    /** Incomplete wall-clock input stays in the memory-owned answers; it is never a wire value. */
    data class DateTimeDraft(val date: String, val time: String) : Answer
    data class Flag(val value: Boolean) : Answer
    data class Number(val text: String) : Answer
    data class One(val id: String) : Answer
    data class Many(val ids: List<String>) : Answer
}

const val NAME_NOT_LOADED = "Name not loaded"

fun FormDefinition.initialAnswers(saved: Map<String, Answer>?): Map<String, Answer> =
    saved ?: fields.mapNotNull { f -> f.default?.let { f.id to it } }.toMap()

/** A field is shown when it has no condition, or its controlling field is shown and equals the condition. */
fun FormDefinition.visibleFields(answers: Map<String, Answer>): List<FormField> {
    val shown = mutableSetOf<String>()
    return fields.filter { f ->
        val w = f.showWhen
        val visible = w == null || (w.fieldId in shown && answers[w.fieldId] == w.equals)
        if (visible) shown += f.id
        visible
    }
}

internal fun Answer.isEmpty(): Boolean = when (this) {
    is Answer.Text -> value.isEmpty()
    is Answer.DateTimeDraft -> date.isEmpty() && time.isEmpty()
    is Answer.Number -> text.isBlank()
    is Answer.Many -> ids.isEmpty()
    is Answer.Flag, is Answer.One -> false
}

/**
 * Client-side checks, mirroring the server's so the owner sees problems before sending; the server stays
 * authoritative. A draft may leave required fields empty. Returns field ID to message, in display order.
 */
fun FormDefinition.validate(answers: Map<String, Answer>, draft: Boolean): Map<String, String> {
    val problems = linkedMapOf<String, String>()
    for (f in visibleFields(answers)) {
        val a = answers[f.id]
        val p = if (a == null || a.isEmpty()) {
            if (f.required && !draft) requiredMessage(f.kind) else null
        } else {
            answerProblem(f.kind, a)
        }
        if (p != null) problems[f.id] = p
    }
    return problems
}

private fun requiredMessage(kind: FieldKind) = when (kind) {
    is FieldKind.Choice, is FieldKind.YesNo -> "Choose an answer."
    is FieldKind.MultiChoice -> "Choose at least one."
    is FieldKind.Resource -> "Choose a project."
    else -> "Enter an answer."
}

/** Why a present answer is not valid for [kind], or null. Shared with the decoder, which fails closed on any. */
internal fun answerProblem(kind: FieldKind, a: Answer): String? {
    val mismatch = "This answer can't be used."
    return when (kind) {
        is FieldKind.Text -> if (a !is Answer.Text) mismatch
        else if (a.value.length > kind.maxLength) "Use at most ${kind.maxLength} characters." else null
        is FieldKind.Choice -> if (a !is Answer.One || kind.options.none { it.id == a.id }) mismatch else null
        is FieldKind.MultiChoice -> if (a !is Answer.Many || a.ids.toSet().size != a.ids.size ||
            !a.ids.all { id -> kind.options.any { it.id == id } }
        ) mismatch else null
        is FieldKind.YesNo -> if (a !is Answer.Flag) mismatch else null
        is FieldKind.Number -> if (a !is Answer.Number) mismatch else numberProblem(kind, a.text)
        is FieldKind.Date -> if (a !is Answer.Text || !isIsoDate(a.value)) "Enter a date as YYYY-MM-DD." else null
        is FieldKind.DateTime -> if (a !is Answer.Text || !isUtcInstant(a.value)) "Enter a date and time." else null
        is FieldKind.Resource -> when {
            kind.multiple && a is Answer.Many ->
                if (a.ids.size > kind.maxItems) "Choose at most ${kind.maxItems}."
                else if (a.ids.toSet().size != a.ids.size || !a.ids.all(::isUuid)) mismatch else null
            !kind.multiple && a is Answer.One -> if (!isUuid(a.id)) mismatch else null
            else -> mismatch
        }
    }
}

private val NumberText = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?""")

private fun numberProblem(kind: FieldKind.Number, text: String): String? {
    val t = text.trim()
    val v = if (NumberText.matches(t)) t.toDoubleOrNull() else null
    val range = "Enter a number from ${plain(kind.min)} to ${plain(kind.max)}."
    if (v == null || !v.isFinite() || v < kind.min || v > kind.max) return range
    val steps = (v - kind.min) / kind.step
    val r = kotlin.math.round(steps)
    if (kotlin.math.abs(steps - r) > 1e-9 * maxOf(1.0, kotlin.math.abs(steps))) {
        return "Use steps of ${plain(kind.step)}, starting at ${plain(kind.min)}."
    }
    return null
}

/** [d] as plain decimal text, never exponent notation: `1.23456785E7` is `12345678.5`, `100.0` is `100`. */
internal fun plain(d: Double): String = plainDecimal(d.toString()) ?: d.toString()

private val DecimalLiteral = Regex("""(-?)([0-9]+)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]{1,3}))?""")

/** Expands a decimal literal such as `5.0E-4` into plain text (`0.0005`), dropping trailing zeros; null if not one. */
internal fun plainDecimal(literal: String): String? {
    val m = DecimalLiteral.matchEntire(literal) ?: return null
    val (sign, whole, fraction, exponent) = m.destructured
    val digits = whole + fraction
    val point = whole.length + (if (exponent.isEmpty()) 0 else exponent.toInt())
    val padded = when {
        point <= 0 -> "0".repeat(1 - point) + digits
        point > digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits
    }
    val at = maxOf(point, 1)
    val integer = padded.substring(0, at).trimStart('0').ifEmpty { "0" }
    val decimals = padded.substring(at).trimEnd('0')
    val text = if (decimals.isEmpty()) integer else "$integer.$decimals"
    return if (text == "0") text else sign + text
}

private val IsoDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private val UtcInstant = Regex("""(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})\.\d{3}Z""")
private val Uuid = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

internal fun isUuid(s: String) = Uuid.matches(s)

internal fun isIsoDate(s: String): Boolean {
    val m = IsoDate.matchEntire(s) ?: return false
    val (y, mo, d) = m.destructured.toList().map { it.toInt() }
    if (y < 1 || mo !in 1..12 || d < 1) return false
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val days = when (mo) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    return d <= days
}

internal fun isUtcInstant(s: String): Boolean {
    val m = UtcInstant.matchEntire(s) ?: return false
    val (date, h, mi, sec) = m.destructured
    return isIsoDate(date) && h.toInt() < 24 && mi.toInt() < 60 && sec.toInt() < 60
}

/** The `answers` object for a mutation: shown, non-empty answers only. Call after [validate] reports nothing. */
/** The answers [encodeAnswers] would send: visible and not empty. */
fun FormDefinition.sendableAnswers(answers: Map<String, Answer>): Map<String, Answer> =
    visibleFields(answers).mapNotNull { f -> answers[f.id]?.takeUnless { it.isEmpty() }?.let { f.id to it } }.toMap()

fun FormDefinition.encodeAnswers(answers: Map<String, Answer>): JsonObject = JsonObject(
    visibleFields(answers).mapNotNull { f ->
        val a = answers[f.id]?.takeUnless { it.isEmpty() } ?: return@mapNotNull null
        f.id to encode(a)
    }.toMap(),
)

private fun encode(a: Answer): JsonElement = when (a) {
    is Answer.Text -> JsonPrimitive(a.value)
    is Answer.DateTimeDraft -> throw IllegalArgumentException("Incomplete date and time cannot be sent")
    is Answer.Flag -> JsonPrimitive(a.value)
    is Answer.One -> JsonPrimitive(a.id)
    is Answer.Many -> JsonArray(a.ids.map { JsonPrimitive(it) })
    is Answer.Number -> {
        val t = a.text.trim()
        t.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(t.toDouble())
    }
}

/** A resource's display name from loaded pages, or [NAME_NOT_LOADED]. IDs are never shown in place of names. */
fun resourceName(id: String, loaded: Map<String, String>): String = loaded[id]?.takeIf { it.isNotBlank() } ?: NAME_NOT_LOADED
