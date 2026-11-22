package com.whumpo.core.wear

import com.whumpo.core.api.CoreTodayApproval
import com.whumpo.core.api.CoreTodayApprovalSource
import com.whumpo.core.api.CoreTodayBriefing
import com.whumpo.core.api.CoreTodayDecisionAccess
import com.whumpo.core.api.CoreTodaySection
import com.whumpo.core.api.CoreTodaySections
import com.whumpo.core.api.CoreTodayVerified
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreWearTodaySnapshotTest {
    private val now = 1_800_000_000_000L

    @Test
    fun wireFormatContainsOnlyBoundedCountsAndFreshnessFields() {
        val snapshot = available(generation = 4L)
        val json = CoreWearTodaySnapshot.encode(snapshot).decodeToString()
        val fields = Json.parseToJsonElement(json).jsonObject.keys

        assertEquals(
            setOf(
                "schemaVersion", "generation", "generatedAtMillis", "publishedAtMillis", "expiresAtMillis",
                "available", "approvals", "approvalsTruncated", "blocked", "blockedTruncated",
                "deadlines", "deadlinesTruncated",
            ),
            fields,
        )
        assertEquals(snapshot, CoreWearTodaySnapshot.decode(json.encodeToByteArray()))
    }

    @Test
    fun verifiedBriefingProjectionCarriesOnlySectionCountsAndTruncation() {
        val approval = CoreTodayApproval(
            provenance = "verified",
            observedAt = "2026-11-21T12:00:00.000Z",
            source = CoreTodayApprovalSource("approval", "approval-secret-id", "effect-id", "run-id", 1),
            expiresAt = "2026-11-21T13:00:00.000Z",
            decisionAccess = CoreTodayDecisionAccess.Available,
        )
        val briefing = CoreTodayBriefing(
            generatedAt = "2026-11-21T12:00:00.000Z",
            dueBefore = "2026-11-22T12:00:00.000Z",
            verifiedSince = "2026-11-20T12:00:00.000Z",
            empty = false,
            sections = CoreTodaySections(
                approvals = CoreTodaySection(
                    List(10) { index ->
                        approval.copy(source = approval.source.copy(approvalId = "approval-secret-$index"))
                    },
                    true,
                ),
                blocked = CoreTodaySection(emptyList(), false),
                deadlines = CoreTodaySection(emptyList(), false),
                verified = CoreTodaySection(emptyList<CoreTodayVerified>(), false),
            ),
        )

        val snapshot = CoreWearTodaySnapshot.fromBriefing(
            briefing,
            generation = 7,
            generatedAtMillis = now - 1_000,
            publishedAtMillis = now - 500,
        )
        val wire = CoreWearTodaySnapshot.encode(snapshot).decodeToString()

        assertEquals(10, snapshot.approvals)
        assertTrue(snapshot.approvalsTruncated)
        assertEquals(0, snapshot.blocked)
        assertEquals(0, snapshot.deadlines)
        assertTrue(snapshot.validAt(now))
        assertFalse(wire.contains("approval-secret-id"))
        assertFalse(wire.contains("approval-secret-9"))
        assertFalse(wire.contains("effect-id"))
        assertFalse(wire.contains("run-id"))
    }

    @Test
    fun validationRejectsExpiredFutureMalformedAndUnknownWireData() {
        assertTrue(available(generation = 2L).validAt(now))
        assertFalse(available(generation = 2L).copy(expiresAtMillis = now).validAt(now))
        assertFalse(available(generation = 2L).copy(publishedAtMillis = now + 1).validAt(now))
        assertFalse(available(generation = 2L).copy(approvals = 2, approvalsTruncated = true).validAt(now))
        assertFalse(available(generation = 2L).copy(expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS + 1).validAt(now))
        assertFalse(unavailable(generation = 3L).copy(blocked = 1).validAt(now))
        assertThrows(SerializationException::class.java) {
            CoreWearTodaySnapshot.decode("${CoreWearTodaySnapshot.encode(available(1L)).decodeToString().dropLast(1)},\"taskId\":\"secret\"}".encodeToByteArray())
        }
    }

    @Test
    fun sourceGenerationSurvivesPublisherRecreationAndCommitFailureDoesNotAdvance() {
        var stored = 8L
        val firstProcess = CoreWearGenerationCounter({ stored }, { stored = it; true })
        assertEquals(9L, firstProcess.next())

        val nextProcess = CoreWearGenerationCounter({ stored }, { stored = it; true })
        assertEquals(10L, nextProcess.next())
        assertThrows(IllegalStateException::class.java) {
            CoreWearGenerationCounter({ stored }, { false }).next()
        }
        assertEquals(10L, stored)
    }

    @Test
    fun bufferedOldSnapshotsAndTombstonesCannotReplaceNewerPublication() {
        val current = available(generation = 12L)
        val olderFresh = available(generation = 11L).copy(publishedAtMillis = now - 501, generatedAtMillis = now - 1_001)
        val olderTombstone = unavailable(generation = 13L).copy(publishedAtMillis = now - 1_001, generatedAtMillis = now - 1_001)

        assertTrue(olderFresh.olderThan(current))
        assertTrue(olderTombstone.olderThan(current))
        assertFalse(acceptsCoreWearTodaySnapshot(olderFresh, current, true, now))
        assertFalse(acceptsCoreWearTodaySnapshot(olderTombstone, current, true, now))
        assertFalse(acceptsCoreWearTodaySnapshot(available(13L), current, false, now))
        assertTrue(acceptsCoreWearTodaySnapshot(available(13L), current, true, now))
        assertFalse(current.olderThan(olderFresh))
    }

    @Test
    fun expiredSnapshotFallsBackAndAcceptsFreshRecoveryWithoutAnotherTileRequest() {
        val expired = available(generation = 20L).copy(expiresAtMillis = now)
        val recovered = available(generation = 21L).copy(
            generatedAtMillis = now - 100,
            publishedAtMillis = now - 50,
            expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS - 100,
        )

        assertFalse(expired.validAt(now))
        assertFalse(acceptsCoreWearTodaySnapshot(expired, null, senderIsPhoneCapabilityNode = true, nowMillis = now))
        assertTrue(recovered.validAt(now))
        assertTrue(
            acceptsCoreWearTodaySnapshot(
                recovered,
                expired,
                senderIsPhoneCapabilityNode = true,
                nowMillis = now,
            ),
        )
        assertFalse(
            acceptsCoreWearTodaySnapshot(
                expired,
                recovered,
                senderIsPhoneCapabilityNode = true,
                nowMillis = now,
            ),
        )
    }

    private fun available(generation: Long) = CoreWearTodaySnapshot(
        generation = generation,
        generatedAtMillis = now - 1_000,
        publishedAtMillis = now - 500,
        expiresAtMillis = now + 4 * 60_000,
        available = true,
        approvals = 10,
        approvalsTruncated = true,
        blocked = 2,
        deadlines = 0,
    )

    private fun unavailable(generation: Long) = CoreWearTodaySnapshot(
        generation = generation,
        generatedAtMillis = now - 100,
        publishedAtMillis = now - 100,
        expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS - 100,
        available = false,
    )
}
