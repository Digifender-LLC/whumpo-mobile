package com.whumpo.core.wear

import com.whumpo.core.api.CoreTodayBriefing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Versioned, counts-only phone-to-Wear payload. Never add account, workspace, task, or credential data. */
@Serializable
data class CoreWearTodaySnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val generation: Long,
    /** The Core server's `generatedAt` for available data; publication time for an unavailable tombstone. */
    val generatedAtMillis: Long,
    /** Phone wall-clock at publication, used to reject buffered older writes and recover after reinstall. */
    val publishedAtMillis: Long,
    val expiresAtMillis: Long,
    val available: Boolean,
    val approvals: Int = 0,
    val approvalsTruncated: Boolean = false,
    val blocked: Int = 0,
    val blockedTruncated: Boolean = false,
    val deadlines: Int = 0,
    val deadlinesTruncated: Boolean = false,
) {
    fun validAt(nowMillis: Long): Boolean {
        if (schemaVersion != SCHEMA_VERSION || generation <= 0L || generatedAtMillis <= 0L ||
            publishedAtMillis <= 0L || generatedAtMillis > nowMillis || publishedAtMillis > nowMillis ||
            expiresAtMillis <= nowMillis || expiresAtMillis <= publishedAtMillis ||
            expiresAtMillis - publishedAtMillis > MAX_AGE_MILLIS
        ) return false
        if (available) {
            if (expiresAtMillis <= generatedAtMillis ||
                expiresAtMillis - generatedAtMillis > MAX_AGE_MILLIS
            ) return false
            return validCount(approvals, approvalsTruncated) &&
                validCount(blocked, blockedTruncated) && validCount(deadlines, deadlinesTruncated)
        }
        return approvals == 0 && !approvalsTruncated && blocked == 0 && !blockedTruncated &&
            deadlines == 0 && !deadlinesTruncated && generatedAtMillis == publishedAtMillis
    }

    fun olderThan(other: CoreWearTodaySnapshot): Boolean =
        publishedAtMillis < other.publishedAtMillis ||
            (publishedAtMillis == other.publishedAtMillis && generation <= other.generation)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_AGE_MILLIS = 5 * 60_000L
        const val PHONE_CAPABILITY = "com.whumpo.android.today.phone"
        const val WATCH_CAPABILITY = "com.whumpo.android.today.watch"
        const val DATA_PATH = "/today/counts-v1"

        private val wireJson = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            encodeDefaults = true
        }

        fun fromBriefing(
            briefing: CoreTodayBriefing,
            generation: Long,
            generatedAtMillis: Long,
            publishedAtMillis: Long,
        ): CoreWearTodaySnapshot {
            require(generatedAtMillis > 0L && publishedAtMillis > 0L)
            require(generatedAtMillis <= Long.MAX_VALUE - MAX_AGE_MILLIS)
            require(publishedAtMillis <= Long.MAX_VALUE - MAX_AGE_MILLIS)
            val sections = briefing.sections
            return CoreWearTodaySnapshot(
                generation = generation,
                generatedAtMillis = generatedAtMillis,
                publishedAtMillis = publishedAtMillis,
                expiresAtMillis = minOf(generatedAtMillis + MAX_AGE_MILLIS, publishedAtMillis + MAX_AGE_MILLIS),
                available = true,
                approvals = sections.approvals.items.size,
                approvalsTruncated = sections.approvals.truncated,
                blocked = sections.blocked.items.size,
                blockedTruncated = sections.blocked.truncated,
                deadlines = sections.deadlines.items.size,
                deadlinesTruncated = sections.deadlines.truncated,
            )
        }

        fun encode(snapshot: CoreWearTodaySnapshot): ByteArray =
            wireJson.encodeToString(CoreWearTodaySnapshot.serializer(), snapshot).encodeToByteArray()

        fun decode(bytes: ByteArray): CoreWearTodaySnapshot {
            if (bytes.size !in 1..1024) throw SerializationException("Invalid Wear snapshot size")
            return wireJson.decodeFromString(CoreWearTodaySnapshot.serializer(), bytes.decodeToString())
        }

        private fun validCount(count: Int, truncated: Boolean): Boolean =
            count in 0..10 && (!truncated || count == 10)
    }
}

/** Shared receiver gate; pairing/capability checks are supplied by the native Data Layer adapter. */
fun acceptsCoreWearTodaySnapshot(
    incoming: CoreWearTodaySnapshot,
    current: CoreWearTodaySnapshot?,
    senderIsPhoneCapabilityNode: Boolean,
    nowMillis: Long,
): Boolean = senderIsPhoneCapabilityNode && incoming.validAt(nowMillis) &&
    (current == null || !incoming.olderThan(current))
