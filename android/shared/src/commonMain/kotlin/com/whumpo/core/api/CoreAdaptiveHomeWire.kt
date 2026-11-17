package com.whumpo.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CoreAdaptiveCategory {
    @SerialName("work") Work,
    @SerialName("partner") Partner,
    @SerialName("website_activity") WebsiteActivity,
    @SerialName("verified_results") VerifiedResults,
}

@Serializable
internal data class CoreAdaptiveProfile(
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val orgId: String,
    val userId: String,
    val generation: String,
    val revision: Long,
    val preferenceRevision: Long,
    val serverTime: String,
    val learningEnabled: Boolean,
    val pins: List<CoreAdaptiveCategory>,
    val learnedOrder: List<CoreAdaptiveCategory>,
    val evidence: String,
    val evaluatedDay: String?,
) {
    fun shortcuts(available: Set<CoreAdaptiveCategory>): List<CoreAdaptiveCategory> =
        (pins + if (learningEnabled) learnedOrder else CoreAdaptiveCategory.entries)
            .distinct().filter(available::contains).take(2)
}

@Serializable internal data class CoreAdaptiveVisit(val generation: String)
@Serializable internal data class CoreAdaptiveRegistration(val generation: String, val commandId: String, val expiresAt: String)
@Serializable internal data class CoreAdaptiveControl(
    val generation: String,
    val expectedPreferenceRevision: Long,
    val commandId: String,
    val expiresAt: String,
    val learningEnabled: Boolean? = null,
    val pins: List<CoreAdaptiveCategory>? = null,
)
@Serializable internal data class CoreAdaptiveReceipt(
    val commandId: String,
    val committedGeneration: String,
    val committedRevision: Long,
    val applied: Boolean,
    val readbackRequired: Boolean,
)
@Serializable internal data class CoreAdaptiveWriter(
    val profile: CoreAdaptiveProfile, val writerId: String, val acceptedSequence: Long,
)
@Serializable internal data class CoreAdaptiveBucket(val day: String, val category: CoreAdaptiveCategory, val count: Int)
@Serializable internal data class CoreAdaptiveContribution(
    val schemaVersion: Int, val generation: String, val writerId: String,
    val sequence: Long, val buckets: List<CoreAdaptiveBucket>,
)
@Serializable internal data class CoreAdaptiveContributionResult(
    val profile: CoreAdaptiveProfile, val acceptedSequence: Long, val applied: Boolean,
)

internal val coreAdaptiveUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal const val coreAdaptiveMaxInteger = 9_007_199_254_740_991L
