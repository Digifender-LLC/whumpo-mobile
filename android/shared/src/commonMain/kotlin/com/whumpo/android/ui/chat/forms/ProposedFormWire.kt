package com.whumpo.android.ui.chat.forms

import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.Continuation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** An `interaction.read` reply as the form screen sees it. Anything outside the admitted subset is [UpdateRequired]. */
sealed interface FormRead {
    data object UpdateRequired : FormRead

    data class Form(
        val interactionId: String,
        val definitionDigest: String,
        val definition: FormDefinition,
        val state: FormState,
        val answerRevision: Int,
        val draft: Map<String, Answer>?,
        val replacementInteractionId: String?,
        val submission: Submission?,
    ) : FormRead
}

enum class FormState(val wire: String) { Open("open"), Submitted("submitted"), Canceled("canceled"), Expired("expired"), Superseded("superseded") }

/** Submitted answers and the server's continuation status, retained only for the signed-in account. */
data class Submission(val id: String, val answers: Map<String, Answer>, val continuation: Continuation?)

/**
 * Decodes the native forms `interaction.read` adopted in docs/mobile-api-v1.md (first reviewed as
 * docs/mobile-api-forms-proposal.md).
 * It fails closed: an unknown or malformed member, state, kind, type, binding or presentation, a non-empty `cards`
 * or an answer that does not fit its field yields [FormRead.UpdateRequired], so nothing renders and nothing is sent.
 * Extra read and envelope members are server metadata and are ignored. The digest is opaque and echoed unchanged.
 */
class ProposedFormWire(private val zones: ZoneTime) {
    private class Malformed : Exception()

    private val definitionKeys = setOf("version", "kind", "template", "title", "summary", "presentation", "fields", "cards", "submitBinding", "continuationBinding", "purpose")
    private val requiredDefinitionKeys = definitionKeys - "purpose"
    private val fieldKeys = setOf("id", "type", "label", "help", "required", "binding", "constraints", "default", "when", "group", "source", "questionId")
    private val requiredFieldKeys = setOf("id", "type", "label", "help", "required", "binding", "constraints")
    private val continuationBindings = setOf("agent.task-continue@1", "site.content-prepare@1")
    private val fieldId = Regex("""[a-z][a-z0-9-]{0,39}""")

    fun decode(read: JsonObject): FormRead = try {
        parse(read)
    } catch (_: Malformed) {
        FormRead.UpdateRequired
    }

    private fun fail(): Nothing = throw Malformed()
    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: fail()
    private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: fail()
    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
    private fun JsonElement?.int(): Int = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fail()
    private fun JsonElement?.num(): Double = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: fail()
    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail()
    private fun JsonElement?.uuid(): String = str().takeIf(::isUuid) ?: fail()
    private fun JsonElement?.isNull() = this == null || this is JsonNull
    private fun check(ok: Boolean) { if (!ok) fail() }

    private fun parse(read: JsonObject): FormRead.Form {
        check(read["version"].int() == 1)
        val env = read["envelope"].obj()
        check(env["version"].int() == 1)
        val id = env["id"].uuid()
        env["expiresAt"].str()
        val digest = env["definitionDigest"].str().also { check(it.isNotEmpty()) }
        val definition = definition(env["definition"].obj())
        val state = FormState.entries.firstOrNull { it.wire == read["state"].str() } ?: fail()
        val revision = read["answerRevision"].int().also { check(it >= 0) }
        val draft = read["draft"].let { if (it.isNull()) null else answers(definition, it.obj()) }
        check("replacementInteractionId" in read && "draft" in read && "submission" in read)
        val replacement = read["replacementInteractionId"].let { if (it.isNull()) null else it.uuid() }
        val submission = read["submission"].let { if (it.isNull()) null else submission(definition, it.obj()) }
        check((state == FormState.Submitted) == (submission != null))
        return FormRead.Form(id, digest, definition, state, revision, draft, replacement, submission)
    }

    private fun definition(d: JsonObject): FormDefinition {
        check(d.keys.all { it in definitionKeys } && d.keys.containsAll(requiredDefinitionKeys))
        check(d["version"].int() == 1 && d["kind"].str() == "form")
        d["template"].str()
        if ("purpose" in d) d["purpose"].str()
        check(d["presentation"].str() in setOf("inline", "panel", "dialog"))
        check(d["cards"].arr().isEmpty())
        check(d["submitBinding"].str() == "interaction.answers@1")
        d["continuationBinding"].let { if (!it.isNull()) check(it.str() in continuationBindings) }
        val title = d["title"].str().also { check(it.length <= 100) }
        val summary = d["summary"].str().also { check(it.length <= 400) }
        val raw = d["fields"].arr().also { check(it.size in 1..12) }
        val fields = mutableListOf<FormField>()
        for (f in raw) fields += field(f.obj(), fields)
        return FormDefinition(title, summary, fields, d["continuationBinding"].let { if (it.isNull()) null else it.str() })
    }

    private fun field(f: JsonObject, earlier: List<FormField>): FormField {
        check(f.keys.all { it in fieldKeys } && f.keys.containsAll(requiredFieldKeys))
        val id = f["id"].str().also { x -> check(fieldId.matches(x) && earlier.none { it.id == x }) }
        val label = f["label"].str().also { check(it.length in 1..80) }
        val help = f["help"].str().also { check(it.length <= 240) }
        val required = f["required"].bool()
        check(f["binding"].str() == "answer.value@1")
        val kind = kind(f["type"].str(), f["constraints"].obj())
        val group = if ("group" in f) f["group"].int().also { check(it in 0..2) } else 0
        var default: Answer? = null
        var suggested = false
        if ("default" in f) {
            val d = f["default"].obj()
            check(d.keys == setOf("value", "provenance"))
            val provenance = d["provenance"].str().also { check(it in setOf("saved", "user", "suggested")) }
            default = answer(kind, d["value"])
            suggested = provenance == "suggested"
        }
        val showWhen = if ("when" in f) {
            val w = f["when"].obj()
            check(w.keys == setOf("fieldId", "equals"))
            val target = earlier.firstOrNull { it.id == w["fieldId"].str() } ?: fail()
            check(target.kind is FieldKind.Choice || target.kind is FieldKind.YesNo)
            ShowWhen(target.id, answer(target.kind, w["equals"]))
        } else null
        return FormField(id, label, help, required, kind, default, suggested, showWhen, group)
    }

    private fun kind(type: String, c: JsonObject): FieldKind = when (type) {
        "text", "long-text" -> {
            check(c.keys == setOf("maxLength"))
            val long = type == "long-text"
            FieldKind.Text(c["maxLength"].int().also { check(it in 1..(if (long) 2000 else 200)) }, long)
        }
        "choice", "multi-choice" -> {
            check(c.keys == setOf("options"))
            val options = c["options"].arr().map { o ->
                val obj = o.obj()
                check(obj.keys == setOf("id", "label"))
                FormOption(obj["id"].str().also { check(it.isNotEmpty()) }, obj["label"].str().also { check(it.isNotEmpty()) })
            }
            check(options.size in 1..12 && options.map { it.id }.toSet().size == options.size)
            if (type == "choice") FieldKind.Choice(options) else FieldKind.MultiChoice(options)
        }
        "boolean" -> FieldKind.YesNo.also { check(c.isEmpty()) }
        "number" -> {
            check(c.keys == setOf("min", "max", "step"))
            val min = c["min"].num()
            val max = c["max"].num()
            val step = c["step"].num()
            check(min >= -1e9 && max <= 1e9 && min <= max && step > 0)
            FieldKind.Number(min, max, step)
        }
        "date" -> FieldKind.Date.also { check(c.isEmpty()) }
        "datetime" -> {
            check(c.keys == setOf("zone"))
            FieldKind.DateTime(c["zone"].str().also { check(zones.isKnownZone(it)) })
        }
        "resource" -> {
            check(c.keys == setOf("collection", "multiple", "maxItems"))
            check(c["collection"].str() == "projects")
            val multiple = c["multiple"].bool()
            val max = c["maxItems"].int()
            check(if (multiple) max in 1..5 else max == 1)
            FieldKind.Resource(multiple, max)
        }
        else -> fail()
    }

    /** One wire value for [kind]; it must also pass the field's own checks. */
    private fun answer(kind: FieldKind, v: JsonElement?): Answer {
        val a = when (kind) {
            is FieldKind.Text, FieldKind.Date, is FieldKind.DateTime -> Answer.Text(v.str())
            FieldKind.YesNo -> Answer.Flag(v.bool())
            is FieldKind.Number -> Answer.Number(plain(v.num()))
            is FieldKind.Choice -> Answer.One(v.str())
            is FieldKind.MultiChoice -> Answer.Many(v.arr().map { it.str() })
            is FieldKind.Resource -> if (kind.multiple) Answer.Many(v.arr().map { it.str() }) else Answer.One(v.str())
        }
        check(answerProblem(kind, a) == null)
        return a
    }

    /** Draft or submitted answers: every key names a field, every value fits it, and hidden fields carry nothing. */
    private fun answers(definition: FormDefinition, o: JsonObject): Map<String, Answer> {
        val out = o.mapValues { (k, v) -> answer((definition.fields.firstOrNull { it.id == k } ?: fail()).kind, v) }
        val shown = definition.visibleFields(out).map { it.id }.toSet()
        check(out.keys.all { it in shown })
        return out
    }

    private fun submission(definition: FormDefinition, s: JsonObject): Submission {
        check(s.keys == setOf("id", "answers", "continuation"))
        val continuation = s["continuation"].let { value ->
            if (value.isNull()) null else value.obj().let { c ->
                check(c.keys == setOf("id", "state", "resultTurnId", "reason"))
                Continuation(
                    c["id"].uuid(), c["state"].str(),
                    c["resultTurnId"].let { if (it.isNull()) null else it.uuid() },
                    c["reason"].let { if (it.isNull()) null else it.str() },
                )
            }
        }
        return Submission(s["id"].uuid(), answers(definition, s["answers"].obj()), continuation)
    }

    /** Checks a mutation's `answers` against the definition exactly as a reply's draft would be. */
    fun answersFit(definition: FormDefinition, answers: JsonObject, draft: Boolean): Boolean = try {
        val decoded = answers(definition, answers)
        definition.validate(decoded, draft).isEmpty()
    } catch (_: Malformed) {
        false
    }
}

/** The immutable mutation for [operation] on [form]: built once, then reused unchanged for every retry. */
fun FormRead.Form.mutation(requestId: String, operation: String, answers: JsonObject?) = InteractionMutateRequest(
    requestId = requestId,
    interactionId = interactionId,
    definitionDigest = definitionDigest,
    expectedAnswerRevision = answerRevision,
    operation = operation,
    answers = if (operation == "cancel") null else answers,
)
