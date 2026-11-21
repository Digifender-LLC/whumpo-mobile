package com.whumpo.core.wear

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.auth.CoreOrganizationSessionController
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoreWearTodayPublicationWorkerTest {
    private val source = "A".repeat(43)
    private val originUrl = "https://core.example.test"
    private val origin = CoreOrigin.allowlisted(originUrl, setOf(originUrl))

    @Test
    fun failedInitialTombstoneDoesNotKillActorBeforeFreshCoreSnapshot() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(sessionJson())
                "/v1/briefing/today" -> respond(todayJson())
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val organizations = CoreOrganizationSessionController(api) { 1 }
            val published = mutableListOf<CoreWearTodaySnapshot>()
            var firstUnavailable = true
            val worker = CoreWearTodayPublicationWorker(
                organizations = organizations,
                processScope = backgroundScope,
                nextGeneration = { published.size.toLong() + 1L },
                publish = { snapshot, _ ->
                    if (!snapshot.available && firstUnavailable) {
                        firstUnavailable = false
                        throw CancellationException("simulated canceled Data Layer task")
                    }
                    published += snapshot
                },
                nowMillis = { Instant.parse("2026-11-21T12:00:00.000Z").toEpochMilli() },
                readSnapshot = { _, generation, publishedAt ->
                    CoreWearTodaySnapshot(
                        generation = generation,
                        generatedAtMillis = publishedAt,
                        publishedAtMillis = publishedAt,
                        expiresAtMillis = publishedAt + CoreWearTodaySnapshot.MAX_AGE_MILLIS,
                        available = true,
                        approvals = 2,
                    )
                },
            )
            try {
                runCurrent()
                assertTrue(firstUnavailable.not(), "initial tombstone was not attempted")
                organizations.establish(source)
                worker.refreshToday()
                runCurrent()
                assertEquals(listOf("/v1/auth/session"), paths)
                assertTrue(published.any { it.available }, "actor did not recover to an available Core snapshot: $published")
            } finally {
                worker.close()
            }
        }
    }

    @Test
    fun lateCoreResponseAfterSignOutCannotPublishCountsAndTombstoneFollows() = runTest {
        val todayStarted = CompletableDeferred<Unit>()
        val releaseToday = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(sessionJson())
                "/v1/briefing/today" -> {
                    todayStarted.complete(Unit)
                    releaseToday.await()
                    respond(todayJson())
                }
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val organizations = CoreOrganizationSessionController(api) { 1 }
            organizations.establish(source)
            val published = mutableListOf<CoreWearTodaySnapshot>()
            val worker = CoreWearTodayPublicationWorker(
                organizations = organizations,
                processScope = backgroundScope,
                nextGeneration = { published.size.toLong() + 1L },
                publish = { snapshot, _ -> published += snapshot },
                nowMillis = { Instant.parse("2026-11-21T12:00:00.000Z").toEpochMilli() },
            )
            try {
                runCurrent()
                todayStarted.await()
                organizations.clear()
                releaseToday.complete(Unit)
                runCurrent()

                assertFalse(published.any { it.available }, "a late response published after sign-out")
                assertTrue(published.any { !it.available }, "sign-out did not publish an unavailable snapshot")
                assertEquals(CoreOrganizationSessionController.State.SignedOut, organizations.state.value)
            } finally {
                worker.close()
            }
        }
    }

    private fun sessionJson() =
        """{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}"""

    private fun todayJson() =
        """{"generatedAt":"2026-11-21T12:00:00.000Z","dueBefore":"2026-11-22T12:00:00.000Z","verifiedSince":"2026-11-20T12:00:00.000Z","empty":true,"sections":{"approvals":{"items":[],"truncated":false},"blocked":{"items":[],"truncated":false},"deadlines":{"items":[],"truncated":false},"verified":{"items":[],"truncated":false}}}"""
}
