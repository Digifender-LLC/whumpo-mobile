package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTaskCommandStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CoreTaskCreationTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val source = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val project = CoreProjectChoice("project-1", "workspace-1", "Website")
    private val queued = """{"runId":"run-1","effectId":"effect-1","status":"queued"}"""
    private val waiting = """{"runId":"run-1","effectId":"effect-1","status":"waiting_approval","approvalId":"approval-1"}"""

    private fun session(org: String) =
        """{"sessionId":"session-$org","orgId":"$org","userId":"user-1","role":"owner","expiresAt":9000}"""

    @Test
    fun workspaceAndProjectReadsDriveExactSubmissionAndUncertainRetry() = runTest {
        val posts = mutableListOf<Pair<String?, String>>()
        val engine = MockEngine { request ->
            assertEquals("Bearer $source", request.headers["Authorization"])
            assertNull(request.headers["Origin"])
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/workspaces" -> {
                    assertEquals(setOf("limit"), request.url.parameters.names())
                    respond("""{"items":[{"id":"workspace-1","name":"Studio","version":1}]}""")
                }
                "/v1/projects" -> {
                    assertEquals("workspace-1", request.url.parameters["workspaceId"])
                    respond("""{"items":[{"id":"project-1","name":"Website","workspaceId":"workspace-1","version":1}]}""")
                }
                "/v1/tasks" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
                    posts += request.headers["Idempotency-Key"] to request.body.toByteArray().decodeToString()
                    if (posts.size == 1) respond("", HttpStatusCode.ServiceUnavailable)
                    else respond(waiting, HttpStatusCode.Accepted)
                }
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            val workspace = controller.workspaceChoices().items.single()
            val authorizedProject = controller.projectChoices(workspace.id).items.single()
            val request = controller.prepareTaskCreation(active.epoch, authorizedProject, "  Cafe\u0301  ", " Scope ")
            assertEquals(503, assertFailsWith<CoreHttpException> { controller.createTask(request) }.status)
            val acceptance = controller.createTask(request)
            assertEquals(CoreTaskCommandStatus.WaitingApproval, acceptance.status)
            assertEquals("approval-1", acceptance.approvalId)
            assertEquals(posts[0], posts[1])
            assertEquals("""{"projectId":"project-1","title":"Café","description":"Scope"}""", posts[0].second)
            controller.createTask(controller.prepareTaskCreation(active.epoch, authorizedProject, "Changed", ""))
            assertNotEquals(checkNotNull(posts[0].first), checkNotNull(posts[2].first))
            assertEquals("""{"projectId":"project-1","title":"Changed"}""", posts[2].second)
        }
    }

    @Test
    fun validatesSelectionAndAcceptanceAndPreservesTypedRejection() = runTest {
        var response = queued
        var status = HttpStatusCode.OK
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/projects" -> respond("""{"items":[{"id":"project-1","workspaceId":"wrong-workspace","name":"Foreign"}]}""")
                "/v1/tasks" -> respond(response, status)
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            assertFailsWith<CoreProtocolException> { controller.projectChoices("workspace-1") }
            val request = controller.prepareTaskCreation(active.epoch, project, "Create task", "")
            assertFailsWith<CoreProtocolException> { controller.createTask(request) }
            status = HttpStatusCode.Accepted
            response = """{"runId":"run-1","effectId":"effect-1","status":"waiting_approval"}"""
            assertFailsWith<CoreProtocolException> { controller.createTask(request) }
            response = queued
            assertEquals(CoreTaskCommandStatus.Queued, controller.createTask(request).status)
            status = HttpStatusCode.Forbidden
            assertEquals(403, assertFailsWith<CoreHttpException> { controller.createTask(request) }.status)
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun oldOrganizationAcceptanceCannotCommitOrReplayWithReplacementToken() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var posts = 0
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/tasks" -> {
                    check(old)
                    posts++
                    started.complete(Unit)
                    release.await()
                    respond(queued, HttpStatusCode.Accepted)
                }
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val request = controller.prepareTaskCreation(old.epoch, project, "Create task", "")
            val pending = async { assertFailsWith<CancellationException> { controller.createTask(request) } }
            started.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            assertFailsWith<CancellationException> { controller.createTask(request) }
            assertEquals(1, posts)
            assertEquals(current, controller.state.value)
        }
    }
}
