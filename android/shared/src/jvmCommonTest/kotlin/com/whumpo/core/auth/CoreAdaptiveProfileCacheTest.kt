package com.whumpo.core.auth

import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.api.CoreAdaptiveCategory.Partner
import com.whumpo.core.api.CoreAdaptiveCategory.Work
import com.whumpo.core.api.CoreAdaptiveProfile
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreAdaptiveProfileCacheTest {
    private val sample = 1_800_000_000_000L
    private var now = sample
    private val origin = origin("https://core.example")
    private val otherOrigin = origin("https://other.example")
    private val categories = CoreAdaptiveCategory.entries.toSet()

    @Test
    fun projectionIsScopedReadOnlyAndPersistsNoRawIdentityOrAuthority() = withCache { directory, cache ->
        val session = session()
        cache.saveImmediate(origin, session, profile(session))
        val reopened = CoreAdaptiveProfileCache(directory) { now }
        val projection = assertNotNull(reopened.load(origin, session.copy(sessionId = "new-session")))
        assertEquals(listOf(Partner, Work), projection.shortcuts(categories))
        assertEquals(listOf(Work), projection.shortcuts(setOf(Work)))
        assertNull(reopened.load(otherOrigin, session))
        assertNull(reopened.load(origin, session.copy(userId = "different-account")))
        assertNull(reopened.load(origin, session.copy(orgId = "different-organization")))
        val bytes = directory.resolve("profiles-v1.bin").readBytes()
        assertTrue(bytes.size < 16_384)
        val raw = bytes.decodeToString()
        for (secret in listOf(origin.value, session.userId, session.orgId, session.sessionId, "test-generation", "evidence-text")) {
            assertFalse(secret in raw)
        }
        cache.saveImmediate(origin, session, profile(session.copy(userId = "different-account")))
        assertContentEquals(bytes, directory.resolve("profiles-v1.bin").readBytes())
    }

    @Test
    fun exactAgeSessionExpiryAndClockRollbackFailClosed() = withCache { _, cache ->
        val session = session()
        val started = cache.beginCanonicalRead { 0 }
        now += 86_399_999L
        cache.save(origin, session, profile(session), started) { 86_399_999L }
        assertEquals(1L, assertNotNull(cache.load(origin, session)).remainingMillis)
        now++
        assertNull(cache.load(origin, session))
        now = sample
        assertNull(cache.load(origin, session))
        cache.saveImmediate(origin, session, profile(session))
        assertEquals(7L, assertNotNull(cache.load(origin, session.copy(expiresAt = now + 7))).remainingMillis)
        assertNull(cache.load(origin, session.copy(expiresAt = now)))
        now--
        assertNull(cache.load(origin, session))
        now++
        assertNull(cache.load(origin, session))

    }

    @Test
    fun canonicalServerOffsetDoesNotExtendLeaseAndColdReadDebitsFlight() = withCache { directory, cache ->
        val session = session()
        val started = cache.beginCanonicalRead { 200L }
        now += 250
        cache.save(origin, session, profile(session, server = sample + 1_408), started) { 450L }
        val reopened = CoreAdaptiveProfileCache(directory) { now }
        val projection = assertNotNull(reopened.load(origin, session))
        assertEquals(86_399_750L, projection.remainingMillis)
        val nearerExpiry = session.copy(expiresAt = sample + 2_000)
        assertEquals(342L, assertNotNull(reopened.load(origin, nearerExpiry)).remainingMillis)
        assertEquals(listOf(Partner, Work), projection.shortcuts(categories))
        now = sample + 86_400_000L
        assertNull(reopened.load(origin, session))
        now = sample + 5_000
        assertNull(reopened.load(origin, session))
    }

    @Test
    fun retreatInconsistentFlightAndExpiredSessionCannotSave() = withCache { _, cache ->
        val session = session()
        val started = cache.beginCanonicalRead { 200L }
        now--
        cache.save(origin, session, profile(session, server = sample + 1_408), started) { 201L }
        assertNull(cache.load(origin, session))
        now = sample + 5L
        cache.save(origin, session, profile(session), started) { 210L }
        assertNull(cache.load(origin, session))
        cache.save(origin, session, profile(session), started) { 199L }
        assertNull(cache.load(origin, session))
        cache.save(origin, session.copy(expiresAt = now), profile(session), started) { 205L }
        assertNull(cache.load(origin, session))
    }

    @Test
    fun lruAndErasureAreScopedByOriginAndActor() = withCache { _, cache ->
        val scopes = (0..8).map { session(org = "organization-$it") }
        scopes.take(8).forEach { cache.saveImmediate(origin, it, profile(it)) }
        assertNotNull(cache.load(origin, scopes[0]))
        cache.saveImmediate(origin, scopes[8], profile(scopes[8]))
        assertNull(cache.load(origin, scopes[1]))
        assertNotNull(cache.load(origin, scopes[0]))
        val other = session(user = "other-account")
        cache.saveImmediate(origin, other, profile(other))
        cache.saveImmediate(otherOrigin, scopes[8], profile(scopes[8]))
        assertTrue(cache.eraseScope(origin, scopes[0]))
        assertNull(cache.load(origin, scopes[0]))
        assertNotNull(cache.load(origin, scopes[8]))
        assertTrue(cache.eraseActor(origin, scopes[8].userId))
        assertNull(cache.load(origin, scopes[8]))
        assertNotNull(cache.load(otherOrigin, scopes[8]))
        assertNotNull(cache.load(origin, other))
    }

    @Test
    fun corruptOrOversizedFilesAreDiscardedAndCanBeRecreated() = withCache { directory, cache ->
        val session = session()
        cache.saveImmediate(origin, session, profile(session))
        val file = directory.resolve("profiles-v1.bin")
        val valid = file.readBytes()
        for (corrupt in listOf(valid.copyOf(8), valid + ByteArray(16_385), valid.copyOf().apply { this[12] = 9 })) {
            file.writeBytes(corrupt)
            assertNull(cache.load(origin, session))
            assertFalse(file.exists())
        }
        cache.saveImmediate(origin, session, profile(session))
        assertNotNull(cache.load(origin, session))
    }

    @Test
    fun rollbackAboveSaveTimeInvalidatesEvenAfterReopeningAndAcrossScopes() = withCache { directory, cache ->
        val first = session()
        val other = session(org = "second-organization")
        cache.saveImmediate(origin, first, profile(first))
        cache.saveImmediate(origin, other, profile(other))
        now = sample + 23 * 3_600_000L
        assertEquals(3_600_000L, assertNotNull(cache.load(origin, first)).remainingMillis)
        now = sample + 3_600_000L
        val reopened = CoreAdaptiveProfileCache(directory) { now }
        assertNull(reopened.load(origin, other))
        now = sample + 23 * 3_600_000L
        assertNull(reopened.load(origin, first))
    }

    @Test
    fun olderCanonicalSaveObservesTimeWithoutReplacingOrderOrAllowingRollback() = withCache { directory, cache ->
        val session = session()
        val olderProfile = profile(session, server = sample - 1).copy(pins = listOf(Work))
        cache.saveImmediate(origin, session, profile(session))
        now = sample + 23 * 3_600_000L
        cache.saveImmediate(origin, session, olderProfile)
        val reopened = CoreAdaptiveProfileCache(directory) { now }
        assertEquals(listOf(Partner, Work), assertNotNull(reopened.load(origin, session)).shortcuts(categories))

        now += 1_000
        cache.saveImmediate(origin, session, olderProfile)
        now -= 500
        assertNull(CoreAdaptiveProfileCache(directory) { now }.load(origin, session))
    }

    @Test
    fun undeletableCacheReportsFailureAndDeniesReadsFromAReopenedStoreInProcess() = withCache { directory, cache ->
        val session = session()
        cache.saveImmediate(origin, session, profile(session))
        val file = directory.resolve("profiles-v1.bin")
        val stale = file.readBytes()
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        val obstruction = file.resolve("child").apply { writeText("obstruction") }
        assertFalse(cache.eraseActor(origin, session.userId))
        assertTrue(obstruction.delete())
        assertTrue(file.delete())
        file.writeBytes(stale)
        assertNull(CoreAdaptiveProfileCache(directory) { now }.load(origin, session))
        assertTrue(cache.eraseScope(origin, session))
        assertFalse(file.exists())
    }

    private fun CoreAdaptiveProfileCache.saveImmediate(origin: CoreOrigin, session: CoreSession, profile: CoreAdaptiveProfile) {
        save(origin, session, profile, beginCanonicalRead { 0L }) { 0L }
    }

    private fun origin(value: String) = CoreOrigin.allowlisted(value, setOf(value))

    private fun session(org: String = "test-organization", user: String = "test-account") =
        CoreSession("test-session", org, user, CoreRole.owner, sample + 180_000_000L)

    private fun profile(session: CoreSession, server: Long = sample) = CoreAdaptiveProfile(
        1, 1, session.orgId, session.userId, "test-generation", 1, 1,
        Instant.ofEpochMilli(server).toString(), true, listOf(Partner), CoreAdaptiveCategory.entries,
        "evidence-text", null,
    )

    private fun withCache(test: (File, CoreAdaptiveProfileCache) -> Unit) {
        val directory = Files.createTempDirectory("adaptive-cache-test").toFile()
        try {
            test(directory, CoreAdaptiveProfileCache(directory) { now })
        } finally {
            directory.deleteRecursively()
        }
    }
}
