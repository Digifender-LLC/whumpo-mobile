package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class CoreInboxCategory { Approval, ConfirmedResult }
enum class CoreInboxPresentation { Eligible, CategoryDisabled, QuietHours }

@ConsistentCopyVisibility
data class CoreInboxItem internal constructor(
    val id: String,
    val category: CoreInboxCategory,
    val observedAt: String,
    val acknowledgedAt: String?,
    val presentation: CoreInboxPresentation,
)

sealed interface CoreInboxDetail {
    data class Approval(val operation: String, val expiresAt: String, val needsSignIn: Boolean) : CoreInboxDetail
    data class ConfirmedResult(val verifiedAt: String, val taskCount: Long) : CoreInboxDetail
}

internal const val CORE_INBOX_PAGE_SIZE = 10

internal data class CoreInboxWirePage(val items: List<CoreInboxItem>, val nextCursor: String?)

internal enum class CoreInboxApprovalStatus { Actionable, ReauthenticationRequired, Expired, Revoked, Decided, Unavailable }

internal sealed interface CoreInboxResolution {
    val id: String
    val effectId: String
    val runId: String

    data class Approval(
        override val id: String,
        override val effectId: String,
        override val runId: String,
        val approvalId: String,
        val status: CoreInboxApprovalStatus,
    ) : CoreInboxResolution

    data class Confirmed(
        override val id: String,
        override val effectId: String,
        override val runId: String,
        val receiptId: String,
    ) : CoreInboxResolution
}

internal fun decodeInboxPage(body: String): CoreInboxWirePage = inboxResponse(body) { row ->
    row.closed("asOf", "windowStart", "policyVersion", "items", *row.optional("nextCursor"))
    val asOf = Instant.parse(row.field("asOf").inboxDate())
    val start = Instant.parse(row.field("windowStart").inboxDate())
    checkDiscovery(start <= asOf)
    row.field("policyVersion").revision()
    val ids = mutableSetOf<String>()
    val raw = row.field("items") as? JsonArray ?: throw CoreProtocolException()
    checkDiscovery(raw.size <= CORE_INBOX_PAGE_SIZE)
    val items = raw.mapNotNull { value ->
        val item = value.objectValue().closed("notificationId", "category", "severity", "observedAt", "acknowledgedAt", "presentation")
        val id = item.field("notificationId").inboxId()
        checkDiscovery(ids.add(id) && item.field("severity").text(6) == "normal")
        val observedAt = item.field("observedAt").inboxDate()
        checkDiscovery(Instant.parse(observedAt) in start..asOf)
        val acknowledgedAt = item.field("acknowledgedAt").unlessNull { it.inboxDate() }
        val presentation = when (item.field("presentation").text(17)) {
            "eligible" -> CoreInboxPresentation.Eligible
            "category_disabled" -> CoreInboxPresentation.CategoryDisabled
            "quiet_hours" -> CoreInboxPresentation.QuietHours
            else -> throw CoreProtocolException()
        }
        // This task inbox never requests briefing notices. Skip a recognized briefing row.
        when (val category = item.field("category").text(17)) {
            "briefing_ready" -> null
            else -> CoreInboxItem(id, inboxCategory(category), observedAt, acknowledgedAt, presentation)
        }
    }
    CoreInboxWirePage(items, row["nextCursor"]?.text(64)?.also { checkDiscovery(coreDigest.matches(it)) })
}

internal fun decodeInboxResolution(body: String, item: CoreInboxItem): CoreInboxResolution = inboxResponse(body) { row ->
    row.closed("notificationId", "category", "status", "source")
    val id = row.field("notificationId").inboxId()
    val category = inboxCategory(row.field("category").text(17))
    checkDiscovery(id == item.id && category == item.category)
    val source = row.field("source").objectValue()
    val effect = source.field("effectId").inboxId()
    val run = source.field("runId").inboxId()
    when (category) {
        CoreInboxCategory.Approval -> {
            source.closed("kind", "approvalId", "effectId", "runId")
            checkDiscovery(source.field("kind").text(8) == "approval")
            val status = when (row.field("status").text(25)) {
                "actionable" -> CoreInboxApprovalStatus.Actionable
                "reauthentication_required" -> CoreInboxApprovalStatus.ReauthenticationRequired
                "expired" -> CoreInboxApprovalStatus.Expired
                "revoked" -> CoreInboxApprovalStatus.Revoked
                "decided" -> CoreInboxApprovalStatus.Decided
                "unavailable" -> CoreInboxApprovalStatus.Unavailable
                else -> throw CoreProtocolException()
            }
            CoreInboxResolution.Approval(id, effect, run, source.field("approvalId").inboxId(), status)
        }
        CoreInboxCategory.ConfirmedResult -> {
            source.closed("kind", "receiptId", "effectId", "runId")
            checkDiscovery(row.field("status").text(8) == "verified" && source.field("kind").text(14) == "effect_receipt")
            CoreInboxResolution.Confirmed(id, effect, run, source.field("receiptId").inboxId())
        }
    }
}

internal fun decodeInboxApproval(body: String, source: CoreInboxResolution.Approval): CoreInboxDetail.Approval =
    inboxResponse(body) { row ->
        row.closed("id", "effectId", "runId", "status", "payloadHash", "policyRevision", "requesterId",
            "proposal", "expiresAt", "version", *row.optional("errorCode"))
        checkDiscovery(row.field("id").inboxId() == source.approvalId &&
            row.field("effectId").inboxId() == source.effectId && row.field("runId").inboxId() == source.runId &&
            row.field("status").text(8) == "pending" && row.field("version").revision() == 1L && "errorCode" !in row)
        row.field("requesterId").inboxId()
        row.field("payloadHash").inboxDigest()
        row.field("policyRevision").inboxInteger(0L..CORE_MAX_VERSION)
        // Only the known operation is projected. The command payload is never presented or used to act.
        val operation = when (row.field("proposal").objectValue().field("operation").text(24)) {
            "tasks.create@1" -> "Create task"
            "tasks.update@1" -> "Update task"
            "tasks.transition@1" -> "Change task status"
            "tasks.move@1" -> "Move task"
            "tasks.create_selected@1" -> "Create selected tasks"
            else -> throw CoreProtocolException()
        }
        CoreInboxDetail.Approval(operation, row.field("expiresAt").inboxDate(),
            source.status == CoreInboxApprovalStatus.ReauthenticationRequired)
    }

internal fun decodeInboxReceipt(body: String, source: CoreInboxResolution.Confirmed, orgId: String): CoreInboxDetail.ConfirmedResult =
    inboxResponse(body) { root ->
        val items = root.closed("items").field("items") as? JsonArray ?: throw CoreProtocolException()
        val row = items.singleOrNull()?.objectValue() ?: throw CoreProtocolException()
        val common = arrayOf("schemaVersion", "receiptId", "effectId", "orgId", "runId", "outcome", "verificationMethod",
            "redactedSummary", "verifiedAt", "createdAt")
        checkDiscovery(row.field("schemaVersion").revision() == 1L && row.field("receiptId").inboxId() == source.receiptId &&
            row.field("effectId").inboxId() == source.effectId && row.field("runId").inboxId() == source.runId &&
            row.field("orgId").inboxId() == orgId && row.field("outcome").text(9) == "confirmed" &&
            row.field("verificationMethod").text(25) == "internal_read_after_write")
        row.field("redactedSummary").text(2000)
        val verifiedAt = row.field("verifiedAt").inboxDate()
        row.field("createdAt").inboxDate()
        val count = if ("batch" in row) {
            row.closed(*common, "batch")
            val batch = row.field("batch").objectValue().closed("proposalId", "memberCount", "manifestDigest")
            batch.field("proposalId").inboxId()
            batch.field("manifestDigest").inboxDigest()
            batch.field("memberCount").inboxInteger(1L..10L)
        } else {
            row.closed(*common, "taskEvidence", *row.optional("automation", "move", "completion"))
            val evidence = row.field("taskEvidence").objectValue().closed("taskId", "taskVersion", "taskDigest")
            evidence.field("taskId").inboxId()
            evidence.field("taskDigest").inboxDigest()
            val version = evidence.field("taskVersion").revision()
            row["completion"]?.objectValue()?.closed("kind", "actorId")?.let {
                checkDiscovery(it.field("kind").text(15) == "human_assertion")
                it.field("actorId").inboxId()
            }
            row["automation"]?.objectValue()?.closed("automationId", "automationVersion", "occurrenceId",
                "scheduledAt", "localDate", "localTime", "resolvedLocalTime", "timeZone", "dst")?.let {
                checkDiscovery(version == 1L && "completion" !in row && "move" !in row)
                it.field("automationId").inboxId()
                it.field("automationVersion").revision()
                it.field("occurrenceId").inboxId()
                it.field("scheduledAt").inboxDate()
                checkDiscovery(localDate.matches(it.field("localDate").text(10)) &&
                    localTime.matches(it.field("localTime").text(5)) && localTime.matches(it.field("resolvedLocalTime").text(5)) &&
                    it.field("dst").text(15) in setOf("exact", "gap_shifted", "overlap_earlier"))
                it.field("timeZone").text(128)
            }
            row["move"]?.objectValue()?.closed("memberCount", "manifestDigest")?.let {
                it.field("manifestDigest").inboxDigest()
                it.field("memberCount").inboxInteger(1L..100L)
            } ?: 1L
        }
        CoreInboxDetail.ConfirmedResult(verifiedAt, count)
    }

internal fun decodeInboxAck(body: String, item: CoreInboxItem): String = inboxResponse(body) { row ->
    row.closed("notificationId", "acknowledgedAt")
    val id = row.field("notificationId").inboxId()
    val at = row.field("acknowledgedAt").inboxDate()
    checkDiscovery(id == item.id && (item.acknowledgedAt == null || at == item.acknowledgedAt))
    at
}

private fun inboxCategory(value: String) = when (value) {
    "approval_required" -> CoreInboxCategory.Approval
    "effect_confirmed" -> CoreInboxCategory.ConfirmedResult
    else -> throw CoreProtocolException()
}

private fun JsonObject.optional(vararg names: String) = names.filter { it in this }.toTypedArray()
private fun JsonElement.inboxId() = text(128).also { checkDiscovery(coreIdentifier.matches(it)) }
private fun JsonElement.inboxDigest() = text(64).also { checkDiscovery(coreDigest.matches(it)) }
private fun JsonElement.inboxDate(): String = text(24).also {
    checkDiscovery(inboxDate.matches(it))
    Instant.parse(it)
}
private fun JsonElement.inboxInteger(range: LongRange) = (this as? JsonPrimitive)?.takeUnless { it.isString }
    ?.content?.takeIf(inboxInteger::matches)?.toLongOrNull()?.takeIf { it in range } ?: throw CoreProtocolException()

private val inboxInteger = Regex("0|[1-9][0-9]{0,15}")
private val inboxDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z")
private val localDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val localTime = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")

private inline fun <T> inboxResponse(body: String, decode: (JsonObject) -> T): T = try {
    decode(Json.parseToJsonElement(body).objectValue())
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}
