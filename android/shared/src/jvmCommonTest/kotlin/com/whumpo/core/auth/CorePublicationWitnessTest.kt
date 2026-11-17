package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CorePublicationWitnessTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val bearer = "A".repeat(43)
    private val artifactHash = "a".repeat(64)
    private val manifestHash = "b".repeat(64)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val target = """
        {"id":"site-1","orgId":"org-1","projectId":"project-1",
         "slug":"site-00000000-0000-0000-0000-000000000001","version":3,"activeEffectId":"effect-1"}
    """.trimIndent()
    private val manifest = ""","pageManifestDigest":"$manifestHash","pageCount":2"""
    private val item = """
        {"operation":"sites.publish@1","buildId":"build-1","receipt":{
         "orgId":"org-1","effectId":"effect-1","targetId":"site-1","targetVersion":3,
         "artifactDigest":"$artifactHash","artifactBytes":512,"confirmedAt":"2026-01-01T00:00:00.000Z"$manifest}}
    """.trimIndent()

    private fun session(role: String = "owner") =
        """{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"$role","expiresAt":9000}"""

    @Test
    fun checksExactPublicationAndRereadsTheTargetForBothManifestShapes() = runTest {
        var receipt = item
        val reads = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("Bearer $bearer", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Origin])
            val path = request.url.encodedPath
            if (path != "/v1/auth/session") reads += path
            when (path) {
                "/v1/auth/session" -> respond(session())
                "/v1/site-targets" -> {
                    assertEquals(setOf("projectId"), request.url.parameters.names())
                    assertEquals("project-1", request.url.parameters["projectId"])
                    respond(target, headers = json)
                }
                "/v1/site-targets/site-1/history" -> {
                    assertEquals(setOf("limit"), request.url.parameters.names())
                    assertEquals("1", request.url.parameters["limit"])
                    respond("""{"items":[$receipt],"nextCursor":"3"}""", headers = json)
                }
                else -> error("Unexpected request")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(bearer)
            val result = checkNotNull(controller.currentPublication(active, "project-1"))
            assertEquals(active.epoch, result.epoch)
            assertEquals("project-1", result.projectId)
            assertEquals("site-1", result.targetId)
            assertEquals(3L, result.targetVersion)
            assertEquals("effect-1", result.effectId)
            assertEquals(artifactHash, result.artifactDigest)
            assertEquals(manifestHash, result.pageManifestDigest)
            assertEquals(2, result.pageCount)
            assertEquals(listOf("/v1/site-targets", "/v1/site-targets/site-1/history", "/v1/site-targets"), reads)

            receipt = item.replace(manifest, "")
            val singlePage = checkNotNull(controller.currentPublication(active, "project-1"))
            assertEquals(artifactHash, singlePage.artifactDigest)
            assertNull(singlePage.pageManifestDigest)
            assertNull(singlePage.pageCount)
        }
    }

    @Test
    fun noTargetOrInactiveTargetNeedsNoHistoryAndNonOwnerCannotStartTheRead() = runTest {
        var role = "owner"
        var targetStatus = HttpStatusCode.NotFound
        var targetReads = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(role))
                "/v1/site-targets" -> {
                    targetReads++
                    respond(target.replace("\"effect-1\"", "null"), targetStatus, json)
                }
                else -> error("Unexpected history request")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val owner = controller.establish(bearer)
            assertNull(controller.currentPublication(owner, "project-1"))
            targetStatus = HttpStatusCode.OK
            assertNull(controller.currentPublication(owner, "project-1"))
            role = "viewer"
            val viewer = controller.establish(bearer)
            assertEquals(403, assertFailsWith<CoreHttpException> { controller.currentPublication(viewer, "project-1") }.status)
            assertFailsWith<CancellationException> { controller.currentPublication(owner, "project-1") }
            assertEquals(2, targetReads)
        }
    }

    @Test
    fun rejectsNoncanonicalTargetHistoryAndAChangedCurrentTarget() = runTest {
        var firstTarget = target
        var finalTarget = target
        var history = ""
        var historyHeaders = json
        var reads = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session())
                "/v1/site-targets" -> respond(if (++reads == 1) firstTarget else finalTarget, headers = json)
                "/v1/site-targets/site-1/history" -> respond(history, headers = historyHeaders)
                else -> error("Unexpected request")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(bearer)
            for (invalid in listOf(
                target.replace("project-1", "project-2"),
                target.replace("org-1", "org-2"),
                target.replace(""""version":3""", """"version":"3""""),
            )) {
                firstTarget = invalid
                reads = 0
                assertFailsWith<CoreProtocolException> { controller.currentPublication(active, "project-1") }
                assertEquals(1, reads)
            }
            firstTarget = target
            for (invalid in listOf(
                """{"items":[]}""",
                """{"items":[$item,$item]}""",
                """{"items":[$item],"nextCursor":"2"}""",
                """{"items":[$item],"extra":true}""",
            ) + listOf(
                item.replace("sites.publish@1", "sites.withdraw@1"),
                item.replace("org-1", "org-2"),
                item.replace("site-1", "site-2"),
                item.replace("effect-1", "effect-2"),
                item.replace(""""targetVersion":3""", """"targetVersion":2"""),
                item.replace(artifactHash, "X".repeat(64)),
                item.replace(manifestHash, "X".repeat(64)),
                item.replace(""","pageCount":2""", ""),
                item.replace(""""pageCount":2""", """"pageCount":"2""""),
                item.replace("2026-01-01", "2026-02-30"),
            ).map { """{"items":[$it]}""" }) {
                history = invalid
                reads = 0
                assertFailsWith<CoreProtocolException> { controller.currentPublication(active, "project-1") }
                assertEquals(1, reads)
            }
            history = """{"items":[$item]}"""
            reads = 0
            historyHeaders = headersOf(HttpHeaders.ContentType, "text/plain")
            assertFailsWith<CoreProtocolException> { controller.currentPublication(active, "project-1") }
            assertEquals(1, reads)
            historyHeaders = json
            finalTarget = target.replace(""""version":3""", """"version":4""").replace("\"effect-1\"", "null")
            reads = 0
            assertFailsWith<CoreProtocolException> { controller.currentPublication(active, "project-1") }
            assertEquals(2, reads)
        }
    }

    @Test
    fun aReplacedSessionSuppressesEveryRemainingReadAndTheWitness() = runTest {
        for (pausedRead in 1..3) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val engine = MockEngine { request ->
                if (request.url.encodedPath == "/v1/auth/session") {
                    respond(session())
                } else {
                    if (++reads == pausedRead) {
                        started.complete(Unit)
                        release.await()
                    }
                    when (request.url.encodedPath) {
                        "/v1/site-targets" -> respond(target, headers = json)
                        "/v1/site-targets/site-1/history" -> respond("""{"items":[$item]}""", headers = json)
                        else -> error("Unexpected request")
                    }
                }
            }
            CoreApiClient(engine, origin).use { api ->
                val controller = CoreOrganizationSessionController(api) { 1 }
                val original = controller.establish(bearer)
                val result = async {
                    assertFailsWith<CancellationException> { controller.currentPublication(original, "project-1") }
                }
                started.await()
                controller.establish("E".repeat(43))
                release.complete(Unit)
                result.await()
                assertEquals(pausedRead, reads)
            }
        }
    }
}
