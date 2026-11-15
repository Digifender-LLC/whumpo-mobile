package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTaskOutcome
import com.whumpo.core.api.CoreTaskStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertNull
import kotlin.test.assertIs

class CoreTaskOutcomeTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val source = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val project = CoreProjectChoice("project-1", "workspace-1", "Website")
    private val queued = """{"runId":"run-1","effectId":"effect-1","status":"queued"}"""
    private val task = """{"id":"task-9","projectId":"project-2","title":"Café","description":null,""" +
        """"parentTaskId":null,"dueAt":null,"status":"ready","version":3}"""

    private fun session(org: String) =
        """{"sessionId":"session-$org","orgId":"$org","userId":"user-1","role":"owner","expiresAt":9000}"""

    private fun effect(state: String, taskId: String? = null, id: String = "effect-1", runId: String = "run-1") =
        """{"id":"$id","runId":"$runId","state":"$state","taskId":${taskId?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun reportsEachEffectStateAndReadsCanonicalTaskOnlyWhenConfirmed() = runTest {
        var effectResponse = effect("queued")
        var taskResponse = task
        var effectStatus = HttpStatusCode.OK
        val paths = mutableListOf<String>()
        val keys = mutableListOf<String?>()
        val engine = MockEngine { request ->
            assertEquals("Bearer $source", request.headers["Authorization"])
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/tasks" -> {
                    keys += request.headers["Idempotency-Key"]
                    if (keys.size == 1) respond("", HttpStatusCode.GatewayTimeout)
                    else respond(queued, HttpStatusCode.Accepted)
                }
                "/v1/effects/effect-1" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    respond(effectResponse, effectStatus)
                }
                "/v1/tasks/task-9" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    respond(taskResponse)
                }
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            val request = controller.prepareTaskCreation(active.epoch, project, "Café", "")
            assertFailsWith<IllegalStateException> { controller.taskOutcome(request) }
            assertEquals(504, assertFailsWith<CoreHttpException> { controller.createTask(request) }.status)
            assertFailsWith<IllegalStateException> { controller.taskOutcome(request) }
            controller.createTask(request)
            assertEquals(CoreTaskOutcome.Queued, controller.taskOutcome(request))
            effectResponse = effect("waiting_approval")
            assertEquals(CoreTaskOutcome.WaitingApproval, controller.taskOutcome(request))
            effectStatus = HttpStatusCode.ServiceUnavailable
            assertEquals(503, assertFailsWith<CoreHttpException> { controller.taskOutcome(request) }.status)
            effectStatus = HttpStatusCode.OK
            effectResponse = effect("failed")
            assertEquals(CoreTaskOutcome.Failed, controller.taskOutcome(request))
            effectResponse = effect("canceled")
            assertEquals(CoreTaskOutcome.Canceled, controller.taskOutcome(request))
            assertFalse("/v1/tasks/task-9" in paths)
            effectResponse = effect("confirmed", "task-9")
            // The canonical task may have moved away from the project used at creation.
            val confirmed = assertIs<CoreTaskOutcome.Confirmed>(controller.taskOutcome(request))
            assertEquals("task-9", confirmed.task.id)
            assertEquals("project-2", confirmed.task.projectId)
            assertEquals(CoreTaskStatus.Ready, confirmed.task.status)
            // Open task must tolerate another move after the status response.
            taskResponse = task.replace("project-2", "project-3")
            assertEquals("project-3", controller.canonicalTask(confirmed.task.id).projectId)
            taskResponse = task.replace("task-9", "task-8")
            assertFailsWith<CoreProtocolException> { controller.canonicalTask(confirmed.task.id) }
            assertEquals(2, keys.size)
            assertEquals(keys[0], keys[1])
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun rejectsMismatchedOrInvalidEffectsAndTasks() = runTest {
        var effectResponse = effect("queued")
        var taskResponse = task
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/tasks" -> respond(queued, HttpStatusCode.Accepted)
                "/v1/effects/effect-1" -> respond(effectResponse)
                "/v1/tasks/task-9" -> respond(taskResponse)
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            val request = controller.prepareTaskCreation(active.epoch, project, "Task", "")
            controller.createTask(request)
            listOf(
                effect("queued", id = "effect-2"),
                effect("queued", runId = "run-2"),
                effect("queued", "task-9"),
                effect("failed", "task-9"),
                effect("confirmed"),
                effect("confirmed", "bad/task"),
            ).forEach { invalid ->
                effectResponse = invalid
                assertFailsWith<CoreProtocolException>(invalid) { controller.taskOutcome(request) }
            }
            effectResponse = """{"id":"effect-1","runId":"run-1","state":"applied","taskId":null}"""
            assertFailsWith<IllegalArgumentException> { controller.taskOutcome(request) }
            effectResponse = effect("confirmed", "task-9")
            taskResponse = task.replace("\"task-9\"", "\"task-8\"")
            assertFailsWith<CoreProtocolException> { controller.taskOutcome(request) }
            taskResponse = task.replace("\"version\":3", "\"version\":0")
            assertFailsWith<CoreProtocolException> { controller.taskOutcome(request) }
            taskResponse = task
            assertIs<CoreTaskOutcome.Confirmed>(controller.taskOutcome(request))
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun replayedAcceptanceMustNameTheSameEffectAndRun() = runTest {
        var response = queued
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/tasks" -> respond(response, HttpStatusCode.Accepted)
                "/v1/effects/effect-1" -> respond(effect("queued"))
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            val request = controller.prepareTaskCreation(active.epoch, project, "Task", "")
            controller.createTask(request)
            response = queued.replace("effect-1", "effect-2")
            assertFailsWith<CoreProtocolException> { controller.createTask(request) }
            response = queued.replace("run-1", "run-2")
            assertFailsWith<CoreProtocolException> { controller.createTask(request) }
            assertEquals(CoreTaskOutcome.Queued, controller.taskOutcome(request))
        }
    }

    @Test
    fun lateAcceptanceCannotBeCachedAfterOrganizationSwitch() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/tasks" -> {
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
            val request = controller.prepareTaskCreation(old.epoch, project, "Task", "")
            val pending = async { assertFailsWith<CancellationException> { controller.createTask(request) } }
            started.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertNull(request.acceptance)
            assertEquals(current, controller.state.value)
        }
    }

    @Test
    fun oldOrganizationEffectResponseIsIgnoredAndCannotBeRetriedInNewSession() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/tasks" -> respond(queued, HttpStatusCode.Accepted)
                "/v1/effects/effect-1" -> {
                    check(old)
                    effects++
                    started.complete(Unit)
                    release.await()
                    respond(effect("confirmed", "task-9"))
                }
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val request = controller.prepareTaskCreation(old.epoch, project, "Task", "")
            controller.createTask(request)
            val pending = async { assertFailsWith<CancellationException> { controller.taskOutcome(request) } }
            started.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            assertFailsWith<CancellationException> { controller.taskOutcome(request) }
            assertEquals(1, effects)
            assertEquals(current, controller.state.value)
        }
    }

    @Test
    fun oldOrganizationTaskReadAndLate401CannotClearReplacementSession() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/tasks" -> respond(queued, HttpStatusCode.Accepted)
                "/v1/effects/effect-1" -> respond(effect("confirmed", "task-9"))
                "/v1/tasks/task-9" -> {
                    check(old)
                    started.complete(Unit)
                    release.await()
                    respond("", HttpStatusCode.Unauthorized)
                }
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val request = controller.prepareTaskCreation(old.epoch, project, "Task", "")
            controller.createTask(request)
            val pending = async { assertFailsWith<CancellationException> { controller.taskOutcome(request) } }
            started.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            assertEquals(current, controller.state.value)
            assertEquals("team", current.session.orgId)
        }
    }
}
