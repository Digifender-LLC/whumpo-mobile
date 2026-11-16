package com.whumpo.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CoreDiscoveryKey(val code: String, val maxLength: Int, vararg options: String) {
    Stage("stage", 0, "idea", "launching", "operating", "growing"),
    Country("country", 0),
    Region("region", 80),
    Customers("customers", 280),
    Offer("offer", 280),
    Delivery("delivery", 0, "online", "in_person", "physical_products", "mixed"),
    Registration("registration", 0, "registered", "in_progress", "not_registered"),
    Formation("formationIntent", 0, "form_entity", "explore", "no_entity_planned"),
    Entity("entityType", 80),
    EmployerIdStatus("ein", 0, "have_ein", "need_ein"),
    Website("website", 0, "existing", "none"),
    WebsiteUrl("websiteURL", 500),
    WebsiteIntent("websiteIntent", 0, "keep", "improve", "replace"),
    Domain("domain", 0, "owned", "managed_by_other", "none"),
    DomainName("domainName", 253),
    DomainAccess("domainAccess", 0, "self", "other", "none"),
    DomainHelp("domainHelp", 0, "find_new", "connect_owned", "keep_as_is", "understand_existing", "none"),
    RegistrationHelp("registrationHelp", 0, "paperwork_checklist", "understand_requirements", "none"),
    Blocker("blocker", 280),
    Time("time", 0, "under_two_hours", "two_to_five_hours", "over_five_hours"),
    Budget("budget", 280),
    Tools("tools", 280);

    val choices: List<String> = options.toList()

    internal fun accepts(value: String): Boolean = when {
        this == Country -> value in coreDiscoveryCountries
        choices.isNotEmpty() -> value in choices
        !value.isDiscoveryText(maxLength) -> false
        this == WebsiteUrl -> discoveryUrl.matches(value)
        this == DomainName -> discoveryHostname.matches(value)
        else -> true
    }
}

// Kept in wire-code order and checked against the exported country snapshot.
val coreDiscoveryCountries: List<String> = """
    AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE
    BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD
    CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM
    DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF
    GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU
    ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN
    KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME
    MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA
    NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM
    PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI
    SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK
    TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI
    VN VU WF WS YE YT ZA ZM ZW
""".trimIndent().split(Regex("\\s+"))

enum class CoreDiscoveryActionId(val code: String) {
    Goal("goal"), Migration("migration"), DomainAccess("domain-access"), Domain("domain"),
    DomainDiscovery("domain-discovery"), WebsiteDiscovery("website-discovery"),
    DiscoverRegistration("discover-registration"), Registration("registration"),
    EmployerId("ein"), Offer("offer"), Tools("tools"),
}

sealed interface CoreDiscoveryValue {
    data class Declared(val value: String) : CoreDiscoveryValue
    data object Unknown : CoreDiscoveryValue
    data object Deferred : CoreDiscoveryValue
}

data class CoreDiscoverySavedAnswer(val value: CoreDiscoveryValue, val revision: Long)

enum class CoreDiscoveryPlanStatus { Missing, Current, Stale }

data class CoreDiscoveryProposal(
    val id: CoreDiscoveryActionId,
    val title: String,
    val reason: String,
    val dependencies: List<CoreDiscoveryActionId>,
    val answerKeys: List<CoreDiscoveryKey>,
    val deferred: Boolean,
    val titleOverride: String?,
)

data class CoreDiscoveryPlan(
    val revision: Long,
    val answerRevision: Long,
    val summary: String,
    val actions: List<CoreDiscoveryProposal>,
    val selectedNext: CoreDiscoveryActionId?,
    val recommendedNext: CoreDiscoveryActionId?,
)

data class CoreDiscoverySnapshot(
    val orgId: String,
    val revision: Long,
    val answerRevision: Long,
    val answers: Map<CoreDiscoveryKey, CoreDiscoverySavedAnswer>,
    val activeQuestions: List<CoreDiscoveryKey>,
    val planStatus: CoreDiscoveryPlanStatus,
    val plan: CoreDiscoveryPlan?,
)

internal data class CoreDiscoveryReceipt(
    val orgId: String,
    val revision: Long,
    val answerRevision: Long,
    val planRevision: Long?,
    val invalidatedKeys: List<CoreDiscoveryKey>,
)

sealed interface CoreDiscoveryCommand {
    data class Answer(val key: CoreDiscoveryKey, val value: CoreDiscoveryValue) : CoreDiscoveryCommand
    data class Clear(val key: CoreDiscoveryKey) : CoreDiscoveryCommand
    data object DeferRemaining : CoreDiscoveryCommand
    data object RefreshPlan : CoreDiscoveryCommand
    data class EditPlan(val planRevision: Long, val edit: CoreDiscoveryEdit) : CoreDiscoveryCommand
}

sealed interface CoreDiscoveryEdit {
    data class Title(val id: CoreDiscoveryActionId, val value: String?) : CoreDiscoveryEdit
    data class Defer(val id: CoreDiscoveryActionId, val value: Boolean) : CoreDiscoveryEdit
    data class Select(val id: CoreDiscoveryActionId?) : CoreDiscoveryEdit
}

internal fun CoreDiscoveryCommand.encodeDiscovery(): String = buildJsonObject {
    put("schemaVersion", 1)
    when (val command = this@encodeDiscovery) {
        is CoreDiscoveryCommand.Answer -> {
            put("action", "answer.set")
            put("key", command.key.code)
            put("answer", buildJsonObject {
                when (val answer = command.value) {
                    is CoreDiscoveryValue.Declared -> {
                        put("status", "declared")
                        put("value", answer.value)
                    }
                    CoreDiscoveryValue.Unknown -> put("status", "unknown")
                    CoreDiscoveryValue.Deferred -> put("status", "deferred")
                }
            })
        }
        is CoreDiscoveryCommand.Clear -> {
            put("action", "answer.clear")
            put("key", command.key.code)
        }
        CoreDiscoveryCommand.DeferRemaining -> put("action", "answers.defer_remaining")
        CoreDiscoveryCommand.RefreshPlan -> put("action", "plan.refresh")
        is CoreDiscoveryCommand.EditPlan -> {
            put("action", "plan.edit")
            put("planRevision", command.planRevision)
            put("edit", buildJsonObject {
                when (val edit = command.edit) {
                    is CoreDiscoveryEdit.Title -> {
                        put("kind", "title")
                        put("id", edit.id.code)
                        put("value", edit.value?.let(::JsonPrimitive) ?: JsonNull)
                    }
                    is CoreDiscoveryEdit.Defer -> {
                        put("kind", "defer")
                        put("id", edit.id.code)
                        put("value", edit.value)
                    }
                    is CoreDiscoveryEdit.Select -> {
                        put("kind", "select")
                        put("id", edit.id?.code?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
            })
        }
    }
}.toString()

internal fun decodeDiscoverySnapshot(body: String): CoreDiscoverySnapshot {
    val value = discoveryRoot(body).closed("schemaVersion", "orgId", "revision", "answerRevision",
        "answers", "activeQuestions", "planStatus", "plan")
    val revision = value.field("revision").revision()
    val answerRevision = value.field("answerRevision").revision()
    val status = when (value.field("planStatus").text(7)) {
        "missing" -> CoreDiscoveryPlanStatus.Missing
        "current" -> CoreDiscoveryPlanStatus.Current
        "stale" -> CoreDiscoveryPlanStatus.Stale
        else -> badDiscovery()
    }
    val answers = value.field("answers").objectValue().map { (code, element) ->
        val key = discoveryKey(code)
        key to element.savedAnswer(key)
    }.toMap()
    val questions = value.field("activeQuestions").uniqueList(CoreDiscoveryKey.entries.size) { discoveryKey(it.text(32)) }
    val plan = value.field("plan").unlessNull { it.plan() }
    checkDiscovery(answerRevision <= revision && answers.keys.all { it in questions } &&
        answers.values.all { it.revision <= answerRevision })
    checkDiscovery((status == CoreDiscoveryPlanStatus.Current) == (plan != null))
    plan?.let { checkDiscovery(it.answerRevision == answerRevision) }
    return CoreDiscoverySnapshot(value.field("orgId").text(128), revision, answerRevision, answers,
        questions, status, plan)
}

internal fun decodeDiscoveryReceipt(body: String): CoreDiscoveryReceipt {
    val value = discoveryRoot(body).closed("schemaVersion", "orgId", "revision", "answerRevision",
        "planRevision", "invalidatedKeys")
    val revision = value.field("revision").revision()
    val answerRevision = value.field("answerRevision").revision()
    checkDiscovery(answerRevision <= revision)
    return CoreDiscoveryReceipt(value.field("orgId").text(128), revision, answerRevision,
        value.field("planRevision").unlessNull { it.revision() },
        value.field("invalidatedKeys").uniqueList(CoreDiscoveryKey.entries.size) { discoveryKey(it.text(32)) })
}

internal fun discoveryRoot(raw: String): JsonObject {
    val root = try {
        Json.parseToJsonElement(raw).objectValue()
    } catch (_: IllegalArgumentException) {
        badDiscovery()
    }
    checkDiscovery(root.field("schemaVersion") == JsonPrimitive(1))
    return root
}

private fun JsonElement.savedAnswer(key: CoreDiscoveryKey): CoreDiscoverySavedAnswer {
    val value = objectValue()
    val answer = when (value.field("status").text(8)) {
        "declared" -> {
            value.closed("status", "value", "source", "revision")
            val declared = value.field("value").text(500)
            checkDiscovery(key.accepts(declared))
            CoreDiscoveryValue.Declared(declared)
        }
        "unknown" -> CoreDiscoveryValue.Unknown
        "deferred" -> CoreDiscoveryValue.Deferred
        else -> badDiscovery()
    }
    if (answer !is CoreDiscoveryValue.Declared) value.closed("status", "source", "revision")
    checkDiscovery(value.field("source") == JsonPrimitive("owner"))
    return CoreDiscoverySavedAnswer(answer, value.field("revision").revision())
}

private fun JsonElement.plan(): CoreDiscoveryPlan {
    val value = objectValue().closed("revision", "answerRevision", "rulesVersion", "summary", "actions",
        "selectedNext", "recommendedNext")
    checkDiscovery(value.field("rulesVersion") == JsonPrimitive(1))
    val actions = value.field("actions").uniqueList(CoreDiscoveryActionId.entries.size) { it.proposal() }
    val byId = actions.associateBy { it.id }
    checkDiscovery(byId.size == actions.size &&
        actions.all { it.id !in it.dependencies && it.dependencies.all(byId::containsKey) })
    fun readNext(key: String) = value.field(key).unlessNull { element ->
        val id = actionId(element)
        val action = byId[id] ?: badDiscovery()
        checkDiscovery(!action.deferred && action.dependencies.isEmpty())
        id
    }
    return CoreDiscoveryPlan(value.field("revision").revision(), value.field("answerRevision").revision(),
        value.field("summary").text(1600), actions, readNext("selectedNext"), readNext("recommendedNext"))
}

private fun JsonElement.proposal(): CoreDiscoveryProposal {
    val value = objectValue()
    val required = setOf("id", "title", "reason", "dependencies", "answerKeys", "requiresReview", "deferred")
    checkDiscovery(value.keys == required || value.keys == required + "titleOverride")
    checkDiscovery(value.field("requiresReview") == JsonPrimitive(true))
    return CoreDiscoveryProposal(actionId(value.field("id")), value.field("title").text(200),
        value.field("reason").text(500), value.field("dependencies").uniqueList(10, ::actionId),
        value.field("answerKeys").uniqueList(CoreDiscoveryKey.entries.size) { discoveryKey(it.text(32)) },
        value.field("deferred").boolean(), value["titleOverride"]?.text(200)?.also { checkDiscovery(it.isDiscoveryText(200)) })
}

private fun discoveryKey(code: String) = CoreDiscoveryKey.entries.firstOrNull { it.code == code } ?: badDiscovery()
internal fun actionId(value: JsonElement) = CoreDiscoveryActionId.entries.firstOrNull { it.code == value.text(32) } ?: badDiscovery()
internal fun JsonElement.objectValue() = this as? JsonObject ?: badDiscovery()
internal fun JsonObject.field(name: String) = this[name] ?: badDiscovery()
internal fun JsonObject.closed(vararg names: String): JsonObject = apply { checkDiscovery(keys == names.toSet()) }
internal fun <T> JsonElement.unlessNull(read: (JsonElement) -> T): T? = if (this == JsonNull) null else read(this)
internal fun JsonElement.text(maxLength: Int): String = (this as? JsonPrimitive)
    ?.takeIf { it.isString && it.content.hasDiscoveryLength(maxLength) }?.content ?: badDiscovery()
private fun JsonElement.boolean(): Boolean = when (this) {
    JsonPrimitive(true) -> true
    JsonPrimitive(false) -> false
    else -> badDiscovery()
}
internal fun JsonElement.revision(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }
    ?.content?.takeIf(discoveryRevision::matches)?.toLongOrNull()?.takeIf { it in 1..CORE_MAX_VERSION }
    ?: badDiscovery()
internal fun <T> JsonElement.uniqueList(maxSize: Int, read: (JsonElement) -> T): List<T> {
    val array = this as? JsonArray ?: badDiscovery()
    checkDiscovery(array.size <= maxSize)
    return array.map(read).also { checkDiscovery(it.toSet().size == it.size) }
}

private fun String.hasDiscoveryLength(maxLength: Int): Boolean =
    isNotEmpty() && length <= maxLength * 2 && count { !it.isLowSurrogate() } in 1..maxLength

internal fun String.isDiscoveryText(maxLength: Int): Boolean =
    hasDiscoveryLength(maxLength) && isNotBlank() && none(Char::isISOControl) &&
        !discoverySensitiveNumber.containsMatchIn(this) && !discoveryPrivateField.containsMatchIn(this) &&
        !discoveryMarkup.containsMatchIn(this)

// The wire policy's Unicode case-insensitive word boundary includes long s and Kelvin sign.
private val discoveryWord = "A-Za-z0-9_\u017F\u212A"
private val discoverySensitiveNumber =
    Regex("(?<![$discoveryWord])(?:[0-9]{2}-[0-9]{7}|[0-9]{3}-[0-9]{2}-[0-9]{4}|[0-9]{9}|[0-9]{13,19})(?![$discoveryWord])")
private val discoveryPrivateField = Regex("(?:password|access[_ ]?token|secret|routing[_ ]?number)\\s*[:=]", RegexOption.IGNORE_CASE)
private val discoveryMarkup = Regex("</?[A-Za-z][^>]*>")
private val discoveryHostname = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
private val discoveryUrl = Regex("https?://[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/[^?#\\\\]*)?")
private val discoveryRevision = Regex("[1-9][0-9]{0,15}")
internal fun checkDiscovery(condition: Boolean) { if (!condition) badDiscovery() }
private fun badDiscovery(): Nothing = throw CoreProtocolException()
