package com.whumpo.core.auth

import com.whumpo.core.account.CoreAccountScope
import com.whumpo.core.account.CoreOfflineWorkStore
import com.whumpo.core.account.FakeSecretBox
import com.whumpo.core.account.OfflineWorkDraft
import com.whumpo.core.account.OfflineWorkStorageException
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreTaskOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class CoreOfflineWorkTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val source = "A".repeat(43)
    private val reopened = "E".repeat(43)
    private val title = "A private task draft"
    private val description = "A synthetic detail"
    private val bytes = """{"projectId":"project-1","title":"$title","description":"$description"}"""
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val digest = "a".repeat(64)
    private val time = "2026-11-16T10:00:00.000Z"
    private val receipt = """
        {"items":[{"schemaVersion":1,"receiptId":"receipt-1","effectId":"effect-1","orgId":"org-1","runId":"run-1",
         "outcome":"confirmed","verificationMethod":"internal_read_after_write","redactedSummary":"Created a task",
         "verifiedAt":"$time","createdAt":"$time","taskEvidence":{"taskId":"task-1","taskVersion":1,"taskDigest":"$digest"}}]}
    """.trimIndent()
    private val task = """
        {"id":"task-1","projectId":"project-1","title":"$title","description":"$description",
         "parentTaskId":null,"dueAt":null,"status":"ready","version":1}
    """.trimIndent()

    private data class Write(val key: String, val body: String)
    private enum class Delivery { DroppedRequest, DroppedReply, Delivered }

    private inner class Fixture {
        var role = "operator"
        var projectAvailable = true
        var delivery = Delivery.Delivered
        var recorded: Write? = null
        var receiptBody = receipt
        var taskBody = task
        var beforeEffect: suspend () -> Unit = {}
        val calls = mutableListOf<String>()
        val writes = mutableListOf<Write>()

        val engine = MockEngine { request ->
            val authorization = request.headers[HttpHeaders.Authorization]
            assertNull(request.headers[HttpHeaders.Origin])
            val session = when (authorization) {
                "Bearer $source" -> "session-1"
                "Bearer $reopened" -> "session-2"
                else -> error("Unexpected authority")
            }
            val path = request.url.encodedPath
            calls += path
            when (path) {
                "/v1/auth/session" -> response(
                    """{"sessionId":"$session","orgId":"org-1","userId":"actor-1","role":"$role","expiresAt":9000}""",
                )
                "/v1/organization/readiness" -> response("""{"orgId":"org-1","ready":true,"projectId":"project-1"}""")
                "/v1/workspaces" -> response("""{"items":[{"id":"workspace-1","name":"Workspace"}]}""")
                "/v1/projects" -> {
                    assertEquals("workspace-1", request.url.parameters["workspaceId"])
                    if (projectAvailable) response("""{"items":[{"id":"project-1","workspaceId":"workspace-1","name":"Project"}]}""")
                    else response("""{"items":[]}""")
                }
                "/v1/tasks" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val write = Write(assertNotNull(request.headers["Idempotency-Key"]), request.body.toByteArray().decodeToString())
                    writes += write
                    if (delivery == Delivery.DroppedRequest) throw IOException("Fixture request unavailable")
                    recorded?.let { assertEquals(it, write) }
                    recorded = write
                    if (delivery == Delivery.DroppedReply) throw IOException("Fixture reply unavailable")
                    response("""{"runId":"run-1","effectId":"effect-1","status":"queued"}""", 202)
                }
                "/v1/effects/effect-1" -> {
                    beforeEffect()
                    response("""{"id":"effect-1","runId":"run-1","state":"confirmed","taskId":"task-1"}""")
                }
                "/v1/effects/effect-1/receipts" -> response(receiptBody)
                "/v1/tasks/task-1" -> response(taskBody)
                else -> error("Unexpected fixture path")
            }
        }

        private fun MockRequestHandleScope.response(body: String, status: Int = 200): HttpResponseData =
            respond(body, HttpStatusCode.fromValue(status), json)
    }

    private fun TestScope.controller(sessions: CoreOrganizationSessionController,
        active: CoreOrganizationSessionController.State.Active, store: CoreOfflineWorkStore,
    ) = CoreOfflineWorkController(sessions, active, store, StandardTestDispatcher(testScheduler))

    private suspend fun CoreOfflineWorkController.reviewSaved(): OfflineWorkReview {
        reconnect()
        chooseWorkspace(assertNotNull(state.value.workspaces).items.single())
        chooseProject(assertNotNull(state.value.projects).items.single())
        return assertNotNull(state.value.review)
    }

    private suspend fun TestScope.retryAcrossReopen(loss: Delivery) {
        val fixture = Fixture()
        val directory = createTempDirectory().toFile()
        val box = FakeSecretBox()
        try {
            CoreApiClient(fixture.engine, origin).use { api ->
                val sessions = CoreOrganizationSessionController(api) { 1 }
                val active = sessions.establish(source)
                val store = CoreOfflineWorkStore(directory) { box }
                val first = controller(sessions, active, store)
                first.load()
                first.save(title, description)
                assertEquals(listOf("/v1/auth/session"), fixture.calls)
                val review = first.reviewSaved()
                assertFalse(review.retry)
                assertTrue(fixture.writes.isEmpty())
                fixture.delivery = loss
                first.submit(review)
                assertFalse(first.state.value.connected)
                assertEquals(OfflineWorkNotice.Unconfirmed, first.state.value.notice)
                val original = fixture.writes.single()
                assertEquals(bytes, original.body)
                assertEquals(original.key, store.read(active.scope)?.pending?.key)
                val sealed = directory.listFiles().orEmpty().single().readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(sealed.contains(title) || sealed.contains(original.key))
                if (loss == Delivery.DroppedReply) assertEquals(original, fixture.recorded) else assertNull(fixture.recorded)
                first.retire()
                sessions.clear()

                val replacement = sessions.establish(reopened)
                val reopen = CoreOfflineWorkStore(directory) { box }
                val second = controller(sessions, replacement, reopen)
                val beforeLoad = fixture.calls.size
                second.load()
                assertTrue(second.state.value.frozen)
                assertEquals(beforeLoad, fixture.calls.size)
                fixture.projectAvailable = false
                second.reconnect()
                assertEquals(OfflineWorkNotice.ProjectUnavailable, second.state.value.notice)
                assertNull(second.state.value.review)
                assertEquals(original.key, reopen.read(replacement.scope)?.pending?.key)
                assertEquals(1, fixture.writes.size)
                fixture.projectAvailable = true
                second.reconnect()
                val retry = assertNotNull(second.state.value.review)
                assertTrue(retry.retry)
                assertEquals(1, fixture.writes.size)
                fixture.delivery = Delivery.Delivered
                second.submit(retry)
                assertEquals(listOf(original, original), fixture.writes)
                assertEquals(CoreTaskOutcome.Queued, second.state.value.outcome)
                assertEquals("effect-1", reopen.read(replacement.scope)?.pending?.acceptance?.effectId)

                fixture.receiptBody = receipt.replace("org-1", "other-org")
                second.checkTask()
                assertTrue(second.state.value.frozen)
                assertFalse(fixture.calls.contains("/v1/tasks/task-1"))
                fixture.receiptBody = receipt
                fixture.taskBody = task.replace("project-1", "other-project")
                second.reconnect()
                second.checkTask()
                assertNotNull(reopen.read(replacement.scope))
                fixture.taskBody = task
                second.reconnect()
                second.checkTask()
                val result = second.state.value.outcome as CoreTaskOutcome.Confirmed
                assertEquals("task-1", result.task.id)
                assertNull(reopen.read(replacement.scope))
                assertEquals(2, fixture.writes.size)
                second.retire()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun aRequestLostBeforeAcceptanceReopensWithTheExactKeyAndBytes() = runTest {
        retryAcrossReopen(Delivery.DroppedRequest)
    }

    @Test fun aCommittedReplyLostAfterAcceptanceReopensWithoutAnotherLogicalWrite() = runTest {
        retryAcrossReopen(Delivery.DroppedReply)
    }

    @Test
    fun leavingConnectedRecoveryCancelsFollowOnReadsAndRoleLossHidesTheDraft() = runTest {
        val fixture = Fixture()
        val directory = createTempDirectory().toFile()
        val box = FakeSecretBox()
        try {
            CoreApiClient(fixture.engine, origin).use { api ->
                val sessions = CoreOrganizationSessionController(api) { 1 }
                val active = sessions.establish(source)
                val store = CoreOfflineWorkStore(directory) { box }
                val model = controller(sessions, active, store)
                model.load()
                model.save(title, description)
                model.submit(model.reviewSaved())
                val read = CompletableDeferred<Unit>()
                fixture.beforeEffect = { read.complete(Unit); awaitCancellation() }
                val pending = launch { model.checkTask() }
                read.await()
                model.offline()
                pending.join()
                assertTrue(pending.isCancelled)
                assertFalse(model.state.value.connected)
                assertFalse(fixture.calls.contains("/v1/effects/effect-1/receipts"))
                assertNotNull(store.read(active.scope))
                val offlineCalls = fixture.calls.size
                model.checkTask()
                assertEquals(offlineCalls, fixture.calls.size)
                fixture.role = "viewer"
                model.reconnect()
                assertTrue(model.state.value.accessLost)
                assertEquals("", model.state.value.title)
                val deniedCalls = fixture.calls.size
                model.load()
                model.reconnect()
                assertEquals(deniedCalls, fixture.calls.size)
                assertNotNull(store.read(active.scope))
                sessions.clear()
                model.retire()
                assertFalse(model.state.value.loaded)

                fixture.role = "operator"
                val replacement = sessions.establish(reopened)
                val reopenedModel = controller(sessions, replacement, store)
                reopenedModel.load()
                reopenedModel.reconnect()
                val secondRead = CompletableDeferred<Unit>()
                fixture.beforeEffect = { secondRead.complete(Unit); awaitCancellation() }
                val awaiting = launch { reopenedModel.checkTask() }
                secondRead.await()
                sessions.clear()
                awaiting.join()
                assertTrue(awaiting.isCancelled)
                assertFalse(fixture.calls.contains("/v1/effects/effect-1/receipts"))
                assertNotNull(store.read(replacement.scope))
                reopenedModel.retire()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun swappedScopesAndLostKeysCannotOpenTheSealedSlot() {
        val directory = createTempDirectory().toFile()
        try {
            val box = FakeSecretBox()
            val store = CoreOfflineWorkStore(directory) { box }
            val scope = CoreAccountScope.of(origin, "actor-1", "org-1")
            val other = CoreAccountScope.of(origin, "actor-1", "org-2")
            val alternate = CoreOrigin.allowlisted("https://other.example.test", setOf("https://other.example.test"))
            val draft = OfflineWorkDraft(title, description)
            assertTrue(store.replace(scope, null, draft))
            assertNull(store.read(CoreAccountScope.of(origin, "actor-2", "org-1")))
            assertNull(store.read(CoreAccountScope.of(alternate, "actor-1", "org-1")))
            assertNull(store.read(other))
            File(directory, "${scope.storageKey}.bin").copyTo(File(directory, "${other.storageKey}.bin"))
            assertFailsWith<OfflineWorkStorageException> { store.read(other) }
            assertEquals(draft, store.read(scope))
            val reopen = CoreOfflineWorkStore(directory) { box }
            assertFalse(reopen.replace(scope, null, draft.copy(title = "An unrelated task")))
            val original = File(directory, "${scope.storageKey}.bin").readBytes()
            box.destroyKey()
            assertFailsWith<OfflineWorkStorageException> { store.read(scope) }
            assertFailsWith<OfflineWorkStorageException> { store.remove(scope, draft) }
            assertFailsWith<OfflineWorkStorageException> { store.replace(scope, null, draft.copy(title = "An unrelated task")) }
            assertContentEquals(original, File(directory, "${scope.storageKey}.bin").readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }
}
