package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWebsiteOperation
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreWorkTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val source = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val digest = "a".repeat(64)
    private val task = """
        {"id":"task-1","projectId":"project-1","title":"Prepare website","description":"Check the home page",
         "parentTaskId":null,"dueAt":null,"status":"needs_approval","version":2,"workspaceId":null}
    """.trimIndent()

    private fun session(org: String) =
        """{"sessionId":"session-$org","orgId":"$org","userId":"user-1","role":"owner","expiresAt":9000}"""

    private fun target(org: String = "solo") =
        """{"id":"site-1","orgId":"$org","projectId":"project-1","slug":"synthetic","version":3,"activeEffectId":null}"""

    private fun withdrawal(org: String = "solo") = """
        {"operation":"sites.withdraw@1","receipt":{"orgId":"$org","targetId":"site-1","effectId":"effect-2",
         "withdrawnEffectId":"effect-1","targetVersion":3,"artifactDigest":"$digest","confirmedAt":"2026-01-01T00:00:00Z"}}
    """.trimIndent()

    @Test
    fun readsWorkDetailAndBothReceiptShapesWithTheirExactQueries() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer $source", request.headers["Authorization"])
            assertNull(request.headers["Origin"])
            val query = request.url.parameters
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/work/tasks" -> {
                    assertEquals("10", query["limit"])
                    if (query["cursor"] == null) respond("""{"items":[$task],"nextCursor":"$digest","futureField":true}""")
                    else {
                        assertEquals(digest, query["cursor"])
                        respond("""{"items":[]}""")
                    }
                }
                "/v1/tasks/task-1" -> respond(task)
                "/v1/site-targets" -> {
                    assertEquals(setOf("projectId"), query.names())
                    assertEquals("project-1", query["projectId"])
                    respond(target())
                }
                "/v1/site-targets/site-1/history" -> {
                    assertEquals("10", query["limit"])
                    if (query["before"] == null) respond("""{"items":[${withdrawal()}],"nextCursor":"3"}""")
                    else {
                        assertEquals("3", query["before"])
                        respond("""
                            {"items":[{"operation":"sites.publish@1","buildId":"build-1","receipt":{
                            "orgId":"solo","targetId":"site-1","effectId":"effect-1","targetVersion":2,
                            "artifactDigest":"$digest","artifactBytes":123,"confirmedAt":"2026-01-01T00:00:00Z",
                            "pageManifestDigest":"$digest","pageCount":2}}]}
                        """.trimIndent())
                    }
                }
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            controller.establish(source)
            val work = controller.work()
            assertEquals(CoreTaskStatus.NeedsApproval, work.items.single().status)
            assertTrue(controller.work(work.nextCursor).items.isEmpty())
            assertEquals("Check the home page", controller.task("task-1", "project-1").description)
            val history = checkNotNull(controller.websiteReceipts("project-1"))
            assertEquals(CoreWebsiteOperation.Withdraw, history.items.single().operation)
            val publication = checkNotNull(controller.websiteReceipts("project-1", history.nextCursor)).items.single()
            assertEquals("build-1", publication.buildId)
            assertEquals(2, publication.receipt.pageCount)
        }
    }

    @Test
    fun fullDescriptionsFitTheBoundedWorkPageAndActiveUnauthorizedClearsSession() = runTest {
        val description = "\\u0001".repeat(10_000)
        val first = task.replace("Check the home page", description)
        val second = first.replace("task-1", "task-2")
        var status = HttpStatusCode.OK
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/work/tasks" -> respond("""{"items":[$first,$second]}""", status)
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            controller.establish(source)
            assertEquals(listOf(10_000, 10_000), controller.work(limit = 2).items.map { it.description?.length })
            status = HttpStatusCode.Unauthorized
            assertEquals(401, assertFailsWith<CoreHttpException> { controller.work() }.status)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, controller.state.value)
        }
    }

    @Test
    fun absentTargetsAndUnavailableHistoryAreDistinctAndWrongScopeFailsClosed() = runTest {
        var targetStatus = HttpStatusCode.NotFound
        var history = ""
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/tasks/task-1" -> respond(task.replace("project-1", "foreign-project"))
                "/v1/site-targets" -> respond(target(), targetStatus)
                "/v1/site-targets/site-1/history" ->
                    if (history.isEmpty()) respond("", HttpStatusCode.NotFound) else respond(history)
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            assertNull(controller.websiteReceipts("project-1"))
            targetStatus = HttpStatusCode.OK
            assertEquals(404, assertFailsWith<CoreHttpException> { controller.websiteReceipts("project-1") }.status)
            history = """{"items":[${withdrawal("foreign-org")}]}"""
            assertFailsWith<CoreProtocolException> { controller.websiteReceipts("project-1") }
            assertFailsWith<CoreProtocolException> { controller.task("task-1", "project-1") }
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun organizationSwitchCancelsLateMalformedWorkAndPreventsOldTargetFollowup() = runTest {
        val workStarted = CompletableDeferred<Unit>()
        val targetStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/work/tasks" -> if (old) {
                    workStarted.complete(Unit)
                    release.await()
                    respond("""{"items":"invalid"}""")
                } else respond("""{"items":[]}""")
                "/v1/site-targets" -> {
                    assertTrue(old)
                    targetStarted.complete(Unit)
                    release.await()
                    respond(target())
                }
                else -> error("An old target response must never trigger a history request")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val work = async { assertFailsWith<CancellationException> { controller.work() } }
            val history = async { assertFailsWith<CancellationException> { controller.websiteReceipts("project-1") } }
            workStarted.await()
            targetStarted.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            work.await()
            history.await()
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            assertEquals(current, controller.state.value)
            assertTrue(controller.work().items.isEmpty())
        }
    }
}
