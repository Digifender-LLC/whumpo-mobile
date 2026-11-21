package com.whumpo.core.wear

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.auth.CoreOrganizationSessionController
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
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
    fun unreadyAndForeignOrganizationNeverReadToday() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        var readinessReads = 0
        val firstRead = CompletableDeferred<Unit>()
        val secondRead = CompletableDeferred<Unit>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/organization/readiness" -> {
                        readinessReads++
                        if (readinessReads == 1) {
                            firstRead.complete(Unit)
                            respond(readinessJson(ready = false), headers = jsonHeaders)
                        } else {
                            secondRead.complete(Unit)
                            respond(readinessJson(ready = true, orgId = "foreign-org"), headers = jsonHeaders)
                        }
                    }
                    "/v1/briefing/today" -> error("Today must not be read for unready or foreign authority")
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val organizations = CoreOrganizationSessionController(api) { 1 }
            organizations.establish(source)
            val worker = CoreWearTodayPublicationWorker(
                organizations = organizations,
                processScope = backgroundScope,
                nextGeneration = { 1L },
                publish = { _, _ -> },
            )
            try {
                runCurrent()
                withTimeout(5_000) { firstRead.await() }
                assertEquals(1, readinessReads)
                assertFalse("/v1/briefing/today" in paths)
                advanceTimeBy(60_000)
                runCurrent()
                withTimeout(5_000) { secondRead.await() }
                assertEquals(2, readinessReads)
                assertFalse("/v1/briefing/today" in paths)
            } finally {
                worker.close()
            }
        }
    }

    @Test
    fun laterReadyOrganizationRecoversThroughBoundedRetryAndReadsToday() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        var ready = false
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/organization/readiness" -> respond(readinessJson(ready), headers = jsonHeaders)
                    "/v1/briefing/today" -> respond(todayJson())
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
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
                nowMillis = { Instant.parse("2026-11-21T12:00:30.000Z").toEpochMilli() },
            )
            try {
                runCurrent()
                assertFalse("/v1/briefing/today" in paths)
                assertFalse(published.any { it.available })

                ready = true
                advanceTimeBy(60_000)
                runCurrent()

                assertEquals(1, paths.count { it == "/v1/briefing/today" })
                assertTrue(published.any { it.available })
            } finally {
                worker.close()
            }
        }
    }

    @Test
    fun exactReadyActiveSessionChecksReadinessBeforeToday() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/organization/readiness" -> respond(readinessJson(ready = true), headers = jsonHeaders)
                    "/v1/briefing/today" -> respond(todayJson())
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
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
                nowMillis = { Instant.parse("2026-11-21T12:00:30.000Z").toEpochMilli() },
            )
            try {
                runCurrent()
                assertEquals(
                    listOf("/v1/auth/session", "/v1/organization/readiness", "/v1/briefing/today"),
                    paths,
                )
                assertTrue(published.any { it.available })
            } finally {
                worker.close()
            }
        }
    }

    @Test
    fun scopeChangeDuringReadinessCannotContinueToToday() = runTest {
        val readinessStarted = CompletableDeferred<Unit>()
        val releaseReadiness = CompletableDeferred<Unit>()
        val readinessResponseReturned = CompletableDeferred<Unit>()
        val paths = CopyOnWriteArrayList<String>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/organization/readiness" -> {
                        readinessStarted.complete(Unit)
                        releaseReadiness.await()
                        val response = respond(readinessJson(ready = true), headers = jsonHeaders)
                        readinessResponseReturned.complete(Unit)
                        response
                    }
                    "/v1/briefing/today" -> error("A stale session must not continue to Today")
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
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
            )
            try {
                runCurrent()
                withTimeout(5_000) { readinessStarted.await() }
                organizations.clear()
                releaseReadiness.complete(Unit)
                runCurrent()
                withTimeout(5_000) { readinessResponseReturned.await() }
                assertFalse("/v1/briefing/today" in paths)
                assertFalse(published.any { it.available })
            } finally {
                releaseReadiness.complete(Unit)
                worker.close()
            }
        }
    }

    @Test
    fun scopeChangeDuringTodayCannotPublishTheLateBriefing() = runTest {
        val todayStarted = CompletableDeferred<Unit>()
        val releaseToday = CompletableDeferred<Unit>()
        val todayResponseReturned = CompletableDeferred<Unit>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/organization/readiness" -> respond(readinessJson(ready = true), headers = jsonHeaders)
                    "/v1/briefing/today" -> {
                        todayStarted.complete(Unit)
                        releaseToday.await()
                        val response = respond(todayJson())
                        todayResponseReturned.complete(Unit)
                        response
                    }
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
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
                nowMillis = { Instant.parse("2026-11-21T12:00:30.000Z").toEpochMilli() },
            )
            try {
                runCurrent()
                withTimeout(5_000) { todayStarted.await() }
                organizations.clear()
                releaseToday.complete(Unit)
                runCurrent()
                withTimeout(5_000) { todayResponseReturned.await() }
                assertFalse(published.any { it.available })
                assertTrue(published.any { !it.available })
            } finally {
                releaseToday.complete(Unit)
                worker.close()
            }
        }
    }

    @Test
    fun failedInitialTombstoneDoesNotKillActorBeforeFreshCoreSnapshot() = runTest {
        val paths = CopyOnWriteArrayList<String>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/briefing/today" -> respond(todayJson())
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
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
        val readinessStarted = CompletableDeferred<Unit>()
        val readinessResponseReturned = CompletableDeferred<Unit>()
        val todayStarted = CompletableDeferred<Unit>()
        val releaseToday = CompletableDeferred<Unit>()
        val todayResponseReturned = CompletableDeferred<Unit>()
        val signedOutTombstonePublished = CompletableDeferred<Unit>()
        val paths = CopyOnWriteArrayList<String>()
        val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond(sessionJson())
                    "/v1/organization/readiness" -> {
                        readinessStarted.complete(Unit)
                        val response = respond(readinessJson(ready = true), headers = jsonHeaders)
                        readinessResponseReturned.complete(Unit)
                        response
                    }
                    "/v1/briefing/today" -> {
                        todayStarted.complete(Unit)
                        releaseToday.await()
                        val response = respond(todayJson())
                        todayResponseReturned.complete(Unit)
                        response
                    }
                    else -> error("Unexpected path: ${request.url.encodedPath}")
                }
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
                publish = { snapshot, _ ->
                    published += snapshot
                    if (!snapshot.available && published.count { !it.available } >= 2) {
                        signedOutTombstonePublished.complete(Unit)
                    }
                },
                nowMillis = { Instant.parse("2026-11-21T12:00:00.000Z").toEpochMilli() },
            )
            try {
                runCurrent()
                withTimeout(5_000) { readinessStarted.await() }
                withTimeout(5_000) { readinessResponseReturned.await() }
                withTimeout(5_000) { todayStarted.await() }
                assertEquals(
                    listOf("/v1/auth/session", "/v1/organization/readiness", "/v1/briefing/today"),
                    paths,
                )
                organizations.clear()
                releaseToday.complete(Unit)
                runCurrent()
                withTimeout(5_000) { todayResponseReturned.await() }
                withTimeout(5_000) { signedOutTombstonePublished.await() }

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

    private fun readinessJson(ready: Boolean, orgId: String = "org-1") =
        """{"orgId":"$orgId","ready":$ready}"""

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun todayJson() =
        """{"generatedAt":"2026-11-21T12:00:00.000Z","dueBefore":"2026-11-22T12:00:00.000Z","verifiedSince":"2026-11-20T12:00:00.000Z","empty":true,"sections":{"approvals":{"items":[],"truncated":false},"blocked":{"items":[],"truncated":false},"deadlines":{"items":[],"truncated":false},"verified":{"items":[],"truncated":false}}}"""
}
