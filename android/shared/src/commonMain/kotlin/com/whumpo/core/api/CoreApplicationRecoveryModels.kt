package com.whumpo.core.api

import kotlinx.serialization.Serializable

internal val recoveryId = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")

@Serializable
data class CoreApplicationRecoveryState(
    val projectId: String,
    val revision: Long,
    val enabled: Boolean,
    val runtimeEligible: Boolean,
    val programId: String?,
    val testId: String?,
    val accessId: String?,
    val disableReviewId: String?,
) {
    val canCapture: Boolean get() = revision > 0
    val canRestore: Boolean get() = canCapture && !enabled
}

@Serializable
data class CoreRecoveryPoint(
    val id: String,
    val projectId: String,
    val programId: String,
    val applicationRevision: Long,
    val createdAt: Long,
    val retainedDocuments: Int,
    val removed: Boolean,
)

@Serializable
internal data class CoreRecoveryPoints(val points: List<CoreRecoveryPoint>)

@Serializable
internal data class CoreRecoveryRemoval(val id: String, val removed: Boolean)

@Serializable
data class CoreRestoreReceipt(val reviewId: String, val restoredDocuments: Int, val confirmedAt: Long)

sealed interface CoreRestoreResult {
    val payloadHash: String
    val receipt: CoreRestoreReceipt?

    @Serializable
    data class Review(
        val id: String,
        val projectId: String,
        val pointId: String,
        val programId: String,
        val expectedApplicationRevision: Long,
        val documents: Int,
        val expiresAt: Long,
        val state: String,
        override val payloadHash: String,
        override val receipt: CoreRestoreReceipt? = null,
    ) : CoreRestoreResult

    @Serializable
    data class Compacted(
        val reviewId: String,
        val state: String,
        val outcome: String,
        override val payloadHash: String,
        override val receipt: CoreRestoreReceipt? = null,
    ) : CoreRestoreResult
}

internal fun verifyRecoveryPoint(point: CoreRecoveryPoint, projectId: String) {
    if (!recoveryId.matches(point.id) || point.projectId != projectId || !recoveryId.matches(point.programId) ||
        point.applicationRevision !in 1..CORE_MAX_VERSION || point.createdAt !in 1..CORE_MAX_VERSION ||
        point.retainedDocuments !in 0..500) throw CoreProtocolException()
}

internal fun verifyRestore(result: CoreRestoreResult) {
    val reviewId: String
    val applied: Boolean
    val maximum: Int
    when (result) {
        is CoreRestoreResult.Review -> {
            if (!coreIdentifier.matches(result.projectId) || !recoveryId.matches(result.pointId) ||
                !recoveryId.matches(result.programId) || result.expectedApplicationRevision !in 1..CORE_MAX_VERSION ||
                result.documents !in 0..500 || result.expiresAt !in 1..CORE_MAX_VERSION ||
                result.state !in setOf("waiting_approval", "applied")) throw CoreProtocolException()
            reviewId = result.id
            applied = result.state == "applied"
            maximum = minOf(result.documents, 100)
        }
        is CoreRestoreResult.Compacted -> {
            if (result.state != "compacted" || result.outcome !in setOf("applied", "expired")) throw CoreProtocolException()
            reviewId = result.reviewId
            applied = result.outcome == "applied"
            maximum = 100
        }
    }
    val receipt = result.receipt
    if (!recoveryId.matches(reviewId) || !coreDigest.matches(result.payloadHash) || applied != (receipt != null) ||
        (receipt != null && (receipt.reviewId != reviewId || receipt.restoredDocuments !in 0..maximum ||
            receipt.confirmedAt !in 1..CORE_MAX_VERSION))) throw CoreProtocolException()
}
