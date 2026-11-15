package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CoreTaskStatus(val label: String) {
    @SerialName("backlog") Backlog("Backlog"),
    @SerialName("ready") Ready("Ready"),
    @SerialName("in_progress") InProgress("In progress"),
    @SerialName("blocked") Blocked("Blocked"),
    @SerialName("needs_approval") NeedsApproval("Needs approval"),
    @SerialName("done") Done("Done"),
    @SerialName("canceled") Canceled("Canceled"),
}

@Serializable
data class CoreWorkTask(
    val id: String,
    val projectId: String,
    val title: String,
    val description: String?,
    val parentTaskId: String?,
    val dueAt: String?,
    val status: CoreTaskStatus,
    val version: Long,
    val archivedAt: String? = null,
)

@Serializable
data class CoreWorkPage(val items: List<CoreWorkTask>, val nextCursor: String? = null)

@Serializable
internal data class CoreWebsiteTarget(
    val id: String,
    val orgId: String,
    val projectId: String,
    val slug: String,
    val version: Long,
    val activeEffectId: String?,
)

@Serializable
enum class CoreWebsiteOperation {
    @SerialName("sites.publish@1") Publish,
    @SerialName("sites.withdraw@1") Withdraw,
}

@Serializable
data class CoreWebsiteReceipt(
    val orgId: String,
    val effectId: String,
    val targetId: String,
    val targetVersion: Long,
    val artifactDigest: String,
    val confirmedAt: String,
    val artifactBytes: Int? = null,
    val pageManifestDigest: String? = null,
    val pageCount: Int? = null,
    val withdrawnEffectId: String? = null,
)

@Serializable
data class CoreWebsiteHistoryItem(
    val operation: CoreWebsiteOperation,
    val receipt: CoreWebsiteReceipt,
    val buildId: String? = null,
)

@Serializable
data class CoreWebsiteHistoryPage(
    val items: List<CoreWebsiteHistoryItem>,
    val nextCursor: String? = null,
)

internal val coreDigest = Regex("[a-f0-9]{64}")
internal val coreHistoryCursor = Regex("[1-9][0-9]{0,15}")
internal const val CORE_MAX_VERSION = 9_007_199_254_740_991L
