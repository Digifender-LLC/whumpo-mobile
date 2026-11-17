package com.whumpo.core.auth

import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.api.CoreAdaptiveProfile
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreAdaptiveHomeTest {
    private val work = CoreAdaptiveCategory.Work
    private val partner = CoreAdaptiveCategory.Partner
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val origin = "https://work.example.test"
    private val token = "A".repeat(43)
    private val generation = "10000000-0000-4000-8000-000000000001"
    private val now = Instant.parse("2026-01-02T12:00:00Z").toEpochMilli()

    @Test
    fun explicitOpenIsConsumedOnceAndCannotBeRecreditedOnForegroundRevalidation() {
        val tapped = CoreAdaptiveOpenIntent(4)
        assertTrue(tapped.consume(4))
        assertFalse(tapped.consume(4))
        assertFalse(tapped.consume(5))
        val lateRead = CoreAdaptiveOpenIntent(4)
        assertFalse(lateRead.consume(5))
        val freshTap = CoreAdaptiveOpenIntent(5)
        assertTrue(freshTap.consume(5))
    }

    @Test
    fun learnedOrderWaitsForNextVisitAndUnavailableDestinationLeavesItsSlot() = runTest {
        var revision = 1
        var visits = 0
        CoreApiClient(MockEngine { request ->
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            val response = when (request.url.encodedPath) {
                "/v1/auth/session" -> session
                "/v1/adaptive-home/profile" -> profile(revision)
                "/v1/adaptive-home/visit" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(generation, Json.parseToJsonElement(request.body.toByteArray().decodeToString())
                        .jsonObject.getValue("generation").jsonPrimitive.content)
                    visits++
                    revision = 2
                    profile(revision)
                }
                else -> error("Unexpected fixture route")
            }
            respond(response, headers = headers)
        }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { now }
            val home = CoreAdaptiveHomeController(sessions, sessions.establish(token)) { 0L }
            home.foreground(true)
            home.availability(setOf(work, partner))
            home.enterToday()
            home.refresh()
            assertEquals(listOf(work, partner), home.state.value.shortcuts)
            assertEquals(1, visits)
            home.availability(setOf(work))
            assertEquals(listOf(work, null), home.state.value.shortcuts)
            home.availability(setOf(work, partner))
            home.leaveToday()
            home.enterToday()
            assertEquals(listOf(partner, work), home.state.value.shortcuts)
            assertEquals(2, visits)
        }
    }

    @Test
    fun heldProfileCannotInstallAfterForegroundLossAndReadsDoNotRegisterUsage() = runTest {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        CoreApiClient(MockEngine { request ->
            val route = request.url.encodedPath
            calls += route
            val response = when (route) {
                "/v1/auth/session" -> session
                "/v1/adaptive-home/profile" -> {
                    arrived.complete(Unit)
                    release.await()
                    profile(1)
                }
                else -> error("Unexpected fixture route")
            }
            respond(response, headers = headers)
        }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { now }
            val home = CoreAdaptiveHomeController(sessions, sessions.establish(token)) { 0L }
            home.foreground(true)
            val request = launch { home.enterToday() }
            arrived.await()
            home.foreground(false)
            release.complete(Unit)
            request.join()
            assertEquals(null, home.state.value.review)
            assertTrue(home.state.value.shortcuts.isEmpty())
            assertEquals(listOf("/v1/auth/session", "/v1/adaptive-home/profile"), calls)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun coldProjectionOnlyFollowsCurrentReadabilityAndExpiresWhileCanonicalGetIsHeld() = runTest {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val directory = Files.createTempDirectory("adaptive-projection").toFile()
        try {
            CoreApiClient(MockEngine { request ->
                val route = request.url.encodedPath
                calls += route
                assertEquals(HttpMethod.Get, request.method)
                respond(when (route) {
                    "/v1/auth/session" -> session
                    "/v1/adaptive-home/profile" -> {
                        arrived.complete(Unit)
                        release.await()
                        profile(2)
                    }
                    else -> error("A cached projection must not issue a write")
                }, headers = headers)
            }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
                val sessions = CoreOrganizationSessionController(client) { now }
                val active = sessions.establish(token)
                val cache = CoreAdaptiveProfileCache(directory) { now }
                val learned = Json.decodeFromString<CoreAdaptiveProfile>(profile(2)).copy(
                    serverTime = Instant.ofEpochMilli(now - 86_399_000L).toString(),
                )
                cache.save(client.origin, active.session, learned)
                val expire = CompletableDeferred<Unit>()
                var elapsed = 0L
                val home = CoreAdaptiveHomeController(sessions, active, cache = cache, waitForProjectionExpiry = { wait ->
                    assertEquals(1_000L, wait)
                    expire.await()
                }) { elapsed }
                home.foreground(true)
                home.availability(setOf(work, partner))
                home.foreground(false)
                home.foreground(true)
                home.availability(setOf(work))
                val held = launch { home.enterToday() }
                arrived.await()
                assertEquals(listOf(work), home.state.value.provisional)
                assertEquals(null, home.state.value.review)
                home.open(work)
                home.availability(setOf(partner))
                assertEquals(listOf(partner), home.state.value.provisional)
                elapsed = 1_000
                expire.complete(Unit)
                runCurrent()
                assertEquals(emptyList(), home.state.value.provisional)
                assertEquals(null, home.state.value.review)
                home.foreground(false)
                sessions.clear()
                release.complete(Unit)
                held.join()
                assertEquals(null, home.state.value.review)
                assertEquals(listOf("/v1/auth/session", "/v1/adaptive-home/profile"), calls)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preferencesUseExactPutAndResetKeepsLearningOffAfterReadback() = runTest {
        var revision = 1
        var generationId = generation
        var pins = "[]"
        var learning = true
        val controls = mutableListOf<String>()
        fun settingsProfile() = """{"schemaVersion":1,"algorithmVersion":1,"orgId":"org-1","userId":"user-1",
            "generation":"$generationId","revision":$revision,"preferenceRevision":$revision,
            "serverTime":"2026-01-02T12:00:00.000Z","learningEnabled":$learning,"pins":$pins,
            "learnedOrder":["work","partner","website_activity","verified_results"],
            "evidence":"cold_start","evaluatedDay":null}"""
        CoreApiClient(MockEngine { request ->
            val route = request.url.encodedPath
            val response = when (route) {
                "/v1/auth/session" -> session
                "/v1/adaptive-home/profile", "/v1/adaptive-home/visit" -> settingsProfile()
                "/v1/adaptive-home/preferences", "/v1/adaptive-home/reset" -> {
                    val preferences = route.endsWith("/preferences")
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val command = body.getValue("commandId").jsonPrimitive.content
                    assertEquals(command, request.headers["Idempotency-Key"])
                    assertEquals(generationId, body.getValue("generation").jsonPrimitive.content)
                    assertEquals(revision.toString(), body.getValue("expectedPreferenceRevision").jsonPrimitive.content)
                    assertEquals(if (preferences) HttpMethod.Put else HttpMethod.Post, request.method)
                    controls += command
                    if (preferences) {
                        assertEquals(false.toString(), body.getValue("learningEnabled").jsonPrimitive.content)
                        assertEquals("""["partner"]""", body.getValue("pins").toString())
                        pins = """["partner"]"""
                        learning = false
                    } else {
                        assertEquals(setOf("generation", "expectedPreferenceRevision", "commandId", "expiresAt"), body.keys)
                        pins = "[]"
                    }
                    revision++
                    generationId = generation.dropLast(1) + revision
                    """{"commandId":"$command","committedGeneration":"$generationId",
                        "committedRevision":$revision,"applied":true,"readbackRequired":true}"""
                }
                else -> error("Unexpected fixture route")
            }
            respond(response, headers = headers)
        }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { now }
            val home = CoreAdaptiveHomeController(sessions, sessions.establish(token)) { 0L }
            home.foreground(true)
            home.availability(setOf(work, partner))
            home.enterToday()
            home.preferences(checkNotNull(home.state.value.review), false, listOf(partner))
            assertFalse(home.state.value.learningEnabled)
            assertEquals(listOf(partner), home.state.value.pins)
            home.reset(checkNotNull(home.state.value.review))
            assertFalse(home.state.value.learningEnabled)
            assertTrue(home.state.value.pins.isEmpty())
            assertEquals(2, controls.distinct().size)
        }
    }

    @Test
    fun foreignGenerationVisitCannotInstallEvenAtHigherRevision() = runTest {
        var canonicalRevision = 1
        val foreign = generation.dropLast(1) + "2"
        CoreApiClient(MockEngine { request ->
            val response = when (request.url.encodedPath) {
                "/v1/auth/session" -> session
                "/v1/adaptive-home/profile" -> profile(canonicalRevision, if (canonicalRevision == 1) generation else foreign)
                "/v1/adaptive-home/visit" -> profile(100, foreign, preferenceRevision = 7)
                else -> error("Unexpected fixture route")
            }
            respond(response, headers = headers)
        }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { now }
            val home = CoreAdaptiveHomeController(sessions, sessions.establish(token)) { 0L }
            home.foreground(true)
            home.enterToday()
            assertEquals(generation, home.state.value.review?.generation)
            assertEquals(1L, home.state.value.review?.preferenceRevision)
            assertEquals(CoreAdaptiveNotice.Unavailable, home.state.value.notice)
            canonicalRevision = 2
            home.refresh()
            assertEquals(foreign, home.state.value.review?.generation)
            assertEquals(7L, home.state.value.review?.preferenceRevision)
        }
    }

    @Test
    fun stalePreferencesAndResetDoNotRebaseAcrossSameScopeCanonicalRefresh() = runTest {
        var revision = 1
        var remoteGeneration = generation
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        CoreApiClient(MockEngine { request ->
            val route = request.url.encodedPath
            calls += route
            val response = when (route) {
                "/v1/auth/session" -> session
                "/v1/adaptive-home/profile" -> {
                    if (revision == 2) {
                        arrived.complete(Unit)
                        release.await()
                    }
                    profile(revision, remoteGeneration, revision)
                }
                "/v1/adaptive-home/visit" -> profile(revision, remoteGeneration, revision)
                else -> error("Stale local editor attempted a write")
            }
            respond(response, headers = headers)
        }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { now }
            val home = CoreAdaptiveHomeController(sessions, sessions.establish(token)) { 0L }
            home.foreground(true)
            home.enterToday()
            val initial = checkNotNull(home.state.value.review)
            revision = 2
            val refresh = launch { home.refresh() }
            arrived.await()
            val deferredSave = launch { home.preferences(initial, true, listOf(work)) }
            release.complete(Unit)
            refresh.join()
            deferredSave.join()
            assertEquals(CoreAdaptiveNotice.Changed, home.state.value.notice)
            val second = checkNotNull(home.state.value.review)
            assertEquals(2L, second.preferenceRevision)
            remoteGeneration = generation.dropLast(1) + "2"
            revision = 3
            home.refresh()
            home.reset(second)
            assertEquals(CoreAdaptiveNotice.Changed, home.state.value.notice)
            assertEquals(remoteGeneration, home.state.value.review?.generation)
            assertTrue(calls.none { it.endsWith("/preferences") || it.endsWith("/reset") })
        }
    }

    @Test
    fun newlyEstablishedOrganizationVisitReadsAndVisitsWithoutLearningAnOpen() = runTest {
        val calls = mutableListOf<String>()
        CoreApiClient(MockEngine { request ->
            val route = request.url.encodedPath
            calls += route
            respond(if (route == "/v1/auth/session") session else profile(1), headers = headers)
        }, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { now }
            val home = CoreAdaptiveHomeController(sessions, sessions.establish(token)) { 0L }
            home.availability(setOf(work, partner))
            home.foreground(true)
            home.enterToday()
            assertEquals(listOf(work, partner), home.state.value.shortcuts)
            home.foreground(false)
            home.open(work)
            assertEquals(
                listOf("/v1/auth/session", "/v1/adaptive-home/profile", "/v1/adaptive-home/visit"),
                calls,
            )
        }
    }

    private val session get() = Json.encodeToString(CoreSession("session-1", "org-1", "user-1", CoreRole.viewer, now + 60_000))

    private fun profile(revision: Int, generationId: String = generation, preferenceRevision: Int = 1): String {
        val order = if (revision == 1) """["work","partner","website_activity","verified_results"]"""
            else """["partner","work","website_activity","verified_results"]"""
        val evidence = if (revision == 1) """ "cold_start","evaluatedDay":null """
            else """ "learned","evaluatedDay":"2026-01-02" """
        return """{"schemaVersion":1,"algorithmVersion":1,"orgId":"org-1","userId":"user-1",
        "generation":"$generationId","revision":$revision,"preferenceRevision":$preferenceRevision,"serverTime":"2026-01-02T12:00:00.000Z",
        "learningEnabled":true,"pins":[],"learnedOrder":$order,"evidence":$evidence}"""
    }
}
