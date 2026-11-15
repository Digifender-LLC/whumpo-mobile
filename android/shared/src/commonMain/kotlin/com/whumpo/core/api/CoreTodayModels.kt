package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded wire envelope for at most 50 compact source-linked briefing rows. */
internal const val CORE_TODAY_MAX_BYTES = 256 * 1_024
internal const val CORE_TODAY_SECTION_LIMIT = 10
internal val coreTimestamp = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")

@Serializable
data class CoreTodayBriefing(
    val generatedAt: String,
    val dueBefore: String,
    val verifiedSince: String,
    val empty: Boolean,
    val sections: CoreTodaySections,
)

@Serializable
data class CoreTodaySections(
    val approvals: CoreTodaySection<CoreTodayApproval>,
    val blocked: CoreTodaySection<CoreTodayBlocked>,
    val deadlines: CoreTodaySection<CoreTodayDeadline>,
    val verified: CoreTodaySection<CoreTodayVerified>,
    /** Absent when the window has no website activity; a present section is never empty. */
    val siteActivity: CoreTodaySection<CoreTodaySiteActivity>? = null,
)

@Serializable
data class CoreTodaySection<T>(val items: List<T>, val truncated: Boolean)

@Serializable
data class CoreTodayTaskSource(val kind: String, val taskId: String, val projectId: String, val version: Long)

@Serializable
data class CoreTodayBlocked(
    val provenance: String,
    val observedAt: String,
    val source: CoreTodayTaskSource,
    val title: String,
    val status: CoreTaskStatus,
    val dueAt: String?,
)

@Serializable
enum class CoreTodayDueState {
    @SerialName("overdue") Overdue,
    @SerialName("upcoming") Upcoming,
}

@Serializable
data class CoreTodayDeadline(
    val provenance: String,
    val observedAt: String,
    val source: CoreTodayTaskSource,
    val title: String,
    val status: CoreTaskStatus,
    val dueAt: String,
    val dueState: CoreTodayDueState,
)

@Serializable
enum class CoreTodayDecisionAccess {
    @SerialName("available") Available,
    @SerialName("reauthentication_required") ReauthenticationRequired,
}

@Serializable
data class CoreTodayApprovalSource(
    val kind: String,
    val approvalId: String,
    val effectId: String,
    val runId: String,
    val version: Long,
)

/** A pending decision. It is not a result and never implies the effect ran. */
@Serializable
data class CoreTodayApproval(
    val provenance: String,
    val observedAt: String,
    val source: CoreTodayApprovalSource,
    val expiresAt: String,
    val decisionAccess: CoreTodayDecisionAccess,
)

@Serializable
data class CoreTodayReceiptSource(val kind: String, val effectId: String, val receiptId: String, val runId: String)

@Serializable
enum class CoreTodayTaskOperation {
    @SerialName("tasks.create@1") Create,
    @SerialName("tasks.update@1") Update,
    @SerialName("tasks.transition@1") Transition,
    @SerialName("tasks.move@1") Move,
    @SerialName("tasks.create_selected@1") CreateSelected,
}

/** A confirmed task-effect receipt. Website proof is reported separately in site activity. */
@Serializable
data class CoreTodayVerified(
    val provenance: String,
    val observedAt: String,
    val source: CoreTodayReceiptSource,
    val operation: CoreTodayTaskOperation,
)

@Serializable
enum class CoreTodaySiteKind(val operation: CoreWebsiteOperation, val review: Boolean) {
    @SerialName("site_publication_receipt") PublicationReceipt(CoreWebsiteOperation.Publish, false),
    @SerialName("site_withdrawal_receipt") WithdrawalReceipt(CoreWebsiteOperation.Withdraw, false),
    @SerialName("site_publication_review") PublicationReview(CoreWebsiteOperation.Publish, true),
    @SerialName("site_withdrawal_review") WithdrawalReview(CoreWebsiteOperation.Withdraw, true),
}

@Serializable
data class CoreTodaySiteSource(
    val kind: CoreTodaySiteKind,
    val operation: CoreWebsiteOperation,
    val effectId: String,
    val targetId: String,
    val projectId: String,
    val taskId: String,
    val buildId: String,
    val targetVersion: Long,
    val artifactDigest: String,
)

/** A website receipt or a website change still awaiting review. A review is never a published result. */
@Serializable
data class CoreTodaySiteActivity(
    val provenance: String,
    val observedAt: String,
    val source: CoreTodaySiteSource,
    // Raw so that an absent field and an explicit null remain distinguishable.
    @SerialName("createdAt") internal val createdAtField: JsonElement = absentField,
    val decisionAccess: CoreTodayDecisionAccess? = null,
) {
    val createdAt: String? get() = (createdAtField as? JsonPrimitive)?.takeIf { it.isString }?.content
    internal val createdAtPresent: Boolean get() = createdAtField !== absentField
    internal val createdAtValid: Boolean
        get() = !createdAtPresent || createdAtField == JsonNull || createdAt?.let(coreTimestamp::matches) == true
}

private val absentField: JsonElement = JsonObject(emptyMap())
