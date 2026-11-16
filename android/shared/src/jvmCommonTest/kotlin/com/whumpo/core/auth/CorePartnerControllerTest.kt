package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.PartnerDecision
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CorePartnerControllerTest {
    private val origin = "https://partner.example.test"
    private val firstToken = "A".repeat(43)
    private val nextToken = "E".repeat(43)
    private val instant = "2027-01-02T03:04:05.000Z"
    private val digest = "a".repeat(64)

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))

    private fun MockRequestHandleScope.failure(status: Int, code: String) =
        json("""{"error":{"code":"$code","message":"Synthetic rejection","requestId":"req-1","retryable":false}}""",
            HttpStatusCode.fromValue(status))

    private fun conversation(id: String) = """{"id":"$id","agentId":"agent-1","kind":"partner"}"""
    private fun directory(vararg ids: String) = ids.joinToString(prefix = """{"items":[""", postfix = "]}") { conversation(it) }
    private fun topic(id: String, closed: Boolean = false, title: String = "Planning") =
        """{"id":"$id","agentId":"agent-1","agentKind":"partner","conversationKind":"temporary","title":${JsonPrimitive(title)},"pinned":false,"version":${if (closed) 2 else 1},"state":"${if (closed) "closed" else "open"}","retention":"retained","createdAt":"$instant","updatedAt":"$instant"${if (closed) ",\"closedAt\":\"$instant\"" else ""}}"""

    private fun id(sequence: Int) = "msg-${sequence.toString().padStart(4, '0')}"
    private fun message(sequence: Int, content: String = "Message $sequence") =
        """{"id":"${id(sequence)}","userId":"user-1","sequence":$sequence,"role":"user","content":${JsonPrimitive(content)},"createdAt":"$instant"}"""

    private fun page(messages: List<String>, cursor: String? = null) =
        messages.joinToString(prefix = """{"items":[""", postfix = "]" + (cursor?.let { ",\"nextCursor\":\"$it\"" } ?: "") + "}")

    private fun run(status: String = "waiting_approval", steps: Boolean = true, wrongConversation: Boolean = false) =
        """{"id":"run-1","conversationId":"${if (wrongConversation) "other" else "a"}","sourceMessageId":"${id(1)}","status":"$status","planner":"local-task-fixture@1","deadlineAt":2000000000000,"modelCalls":1,"toolCalls":${if (steps) 1 else 0},"steps":[${if (steps) """{"index":1,"tool":"tasks.create@1","effectId":"effect-1","approvalId":"approval-1"}""" else ""}]}"""

    private fun approval(status: String = "pending", effect: String = "effect-1", runId: String = "effect-run-1") =
        """{"id":"approval-1","effectId":"$effect","runId":"$runId","status":"$status","payloadHash":"$digest","policyRevision":2,"requesterId":"user-1","proposal":{"operation":"tasks.create@1","payload":{"projectId":"project-1","title":"Test task","parentTaskId":null,"description":null,"dueAt":null}},"expiresAt":"2099-01-01T00:00:00.000Z","version":${if (status == "pending") 1 else 2}}"""

    private fun effect(state: String = "confirmed", runId: String = "effect-run-1") =
        """{"id":"effect-1","runId":"$runId","state":"$state","version":2,"payloadHash":"$digest","policyRevision":2,"approvalId":"approval-1"${if (state == "confirmed") ",\"taskId\":\"task-1\"" else ""}}"""

    private fun receipt(org: String = "org-1", id: String = "receipt-1", runId: String = "effect-run-1") =
        """{"items":[{"schemaVersion":1,"receiptId":"$id","effectId":"effect-1","orgId":"$org","runId":"$runId","outcome":"confirmed","verificationMethod":"internal_read_after_write","redactedSummary":"Task saved","verifiedAt":"$instant","createdAt":"$instant","taskEvidence":{"taskId":"task-1","taskVersion":1,"taskDigest":"$digest"}}]}"""

    private data class Post(val key: String, val body: String)

    private suspend fun post(request: HttpRequestData) = Post(
        checkNotNull(request.headers["Idempotency-Key"]),
        request.body.toByteArray().decodeToString(),
    )

    private suspend fun fixture(
        role: String = "owner",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        use: suspend (CoreOrganizationSessionController, CoreOrganizationSessionController.State.Active, CorePartnerController) -> Unit,
    ) {
        val engine = MockEngine { request ->
            val initial = request.headers[HttpHeaders.Authorization] == "Bearer $firstToken"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> json(
                    """{"sessionId":"session-${if (initial) 1 else 2}","orgId":"org-${if (initial) 1 else 2}","userId":"user-1","role":"$role","expiresAt":60001}""",
                )
                "/v1/auth/session/switch" -> json("""{"token":"$nextToken","expiresAt":60001}""")
                else -> handler(request)
            }
        }
        CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { 1L }
            val active = sessions.establish(firstToken)
            use(sessions, active, CorePartnerController(sessions, active))
        }
    }

    private suspend fun CorePartnerController.openFirst() {
        directory(PartnerDirectory.Active)
        open(state.value.conversations.first())
        refreshConversation()
    }

    @Test
    fun rejectsInvalidClosedModelsSizesAndCursors() = runTest {
        var response = directory("a", "b")
        fixture(handler = { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("20", request.url.parameters["limit"])
            assertEquals(setOf("limit"), request.url.parameters.names())
            json(response)
        }) { sessions, active, _ ->
            val protocol = CorePartnerProtocol(sessions, active)
            assertEquals(2, protocol.active(null).items.size)
            for (invalid in listOf(
                directory("b", "a"), directory("a", "a"), directory("a", "../b"),
                response.replace("\"partner\"", "\"unknown\""),
                response.replace("\"a\"", "12"),
                response.replace("\"b\"", "\"b\",\"unknown\":true"),
                response.dropLast(1) + ",\"nextCursor\":null}",
                response.dropLast(1) + ",\"nextCursor\":\"b\"}",
                " ".repeat(65_536) + response,
            )) {
                response = invalid
                assertFailsWith<CoreProtocolException> { protocol.active(null) }
            }
        }
    }

    @Test
    fun messageRetryRetainsExactBytesThroughLaterRejectionsAndConfirmsReadOnlyAfterClose() = runTest {
        val writes = mutableListOf<Post>()
        val contents = "Keep this exact  œ\n"
        val item = message(1, contents)
        var closed = false
        var canonical = false
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                "/v1/conversations" -> json(directory("a"))
                "/v1/conversations/a/metadata" -> json(topic("a", closed))
                "/v1/conversations/a/messages" -> if (request.method == HttpMethod.Post) {
                    writes += post(request)
                    when (writes.size) {
                        1 -> failure(503, "UPSTREAM_UNAVAILABLE")
                        2 -> failure(403, "FORBIDDEN")
                        3 -> failure(409, "VERSION_CONFLICT")
                        4 -> failure(423, "POLICY_DISABLED")
                        5 -> failure(429, "RATE_LIMITED")
                        else -> json("""{"message":$item,"replayed":true}""")
                    }
                } else {
                    assertEquals("5", request.url.parameters["limit"])
                    json(page(if (canonical && request.url.parameters["cursor"] == null) listOf(item) else emptyList()))
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.openFirst()
            controller.editMessage(contents)
            repeat(5) {
                controller.saveMessage()
                assertTrue(controller.state.value.savePending)
                assertFalse(controller.state.value.saveAcknowledged)
                controller.editMessage("Different text")
                assertEquals(contents, controller.state.value.messageDraft)
            }
            closed = true
            controller.saveMessage()
            assertEquals(5, writes.size)
            assertEquals(PartnerNotice.ClosedSaveUnresolved, controller.state.value.notice)
            closed = false
            controller.saveMessage()
            assertTrue(controller.state.value.saveAcknowledged)
            assertEquals(6, writes.size)
            assertEquals(1, writes.distinct().size)
            val write = writes.first()
            val body = Json.parseToJsonElement(write.body).jsonObject
            assertEquals(JsonPrimitive(write.key), body["clientOperationId"])
            assertEquals(JsonPrimitive(contents), body["content"])
            canonical = true
            closed = true
            controller.refreshConversation()
            assertEquals(6, writes.size)
            assertFalse(controller.state.value.savePending)
            assertEquals(contents, controller.state.value.savedForRun?.content)
            assertTrue(controller.state.value.topic?.closed == true)
        }
    }

    @Test
    fun canonicalConfirmationResumesBeyondVisibleWindowWithoutASecondPost() = runTest {
        var wrote = false
        var visibleLast = 5
        val cursors = mutableListOf<String?>()
        val contents = "Saved offscreen"
        fixture(handler = { request ->
            val cursor = request.url.parameters["cursor"]
            when (request.url.encodedPath) {
                "/v1/conversations" -> json(directory("a"))
                "/v1/conversations/a/metadata" -> json(topic("a"))
                "/v1/conversations/a/messages" -> if (request.method == HttpMethod.Post) {
                    assertFalse(wrote)
                    wrote = true
                    visibleLast = 300
                    json("""{"message":${message(111, contents)},"replayed":false}""", HttpStatusCode.Created)
                } else {
                    cursors += cursor
                    val after = cursor?.substringAfter("-")?.toInt() ?: 0
                    val end = minOf(after + 5, visibleLast)
                    json(page((after + 1..end).map { if (it == 111) message(it, contents) else message(it) },
                        id(end).takeIf { end < visibleLast }))
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.openFirst()
            controller.editMessage(contents)
            controller.saveMessage()
            assertTrue(controller.state.value.saveAcknowledged)
            assertEquals(id(100), cursors.last())
            repeat(3) { controller.moreMessages() }
            assertEquals(100, controller.state.value.messages.size)
            assertTrue(controller.state.value.messages.none { it.id == id(111) })
            val before = cursors.size
            controller.saveMessage()
            assertEquals(id(105), cursors[before])
            assertEquals(id(111), controller.state.value.savedForRun?.id)
            assertFalse(controller.state.value.savePending)
        }
    }

    @Test
    fun ownerCreateFreezesTitleAgentAndKeyAndViewerCannotWrite() = runTest {
        val writes = mutableListOf<Post>()
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                "/v1/agents/management" ->
                    json("""{"items":[{"id":"agent-1","kind":"partner","name":"Partner","enabled":true,"version":1}]}""")
                "/v1/temporary-conversations" -> {
                    writes += post(request)
                    when (writes.size) {
                        1 -> failure(503, "UPSTREAM_UNAVAILABLE")
                        2 -> failure(429, "CONVERSATION_LIMIT")
                        else -> json(topic("a", title = "Café"), HttpStatusCode.Created)
                    }
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.agents()
            controller.create(controller.state.value.agents.single(), " Cafe\u0301 ")
            assertTrue(controller.state.value.creationPending)
            controller.create(null, "Unrelated title")
            assertTrue(controller.state.value.creationPending)
            controller.create(null, "")
            assertEquals(3, writes.size)
            assertEquals(1, writes.distinct().size)
            assertEquals(JsonPrimitive("Café"), Json.parseToJsonElement(writes.first().body).jsonObject["title"])
            assertFalse(controller.state.value.creationPending)
            assertEquals("a", controller.state.value.conversation?.id)
        }
        fixture(role = "viewer", handler = { error("Viewer write attempted") }) { _, _, controller ->
            controller.agents()
            controller.create(null, "")
            assertFalse(controller.canCreate)
            assertFalse(controller.canWrite)
        }
    }

    @Test
    fun explicitRunPolicyDisabledDoesNotResendMessageAndAmbiguousStartReplaysExactRequest() = runTest {
        val posted = mutableListOf<Post>()
        var disabled = true
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                "/v1/conversations" -> json(directory("a"))
                "/v1/conversations/a/metadata" -> json(topic("a"))
                "/v1/conversations/a/messages" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    json(page(listOf(message(1))))
                }
                "/v1/workspaces" -> json("""{"items":[{"id":"workspace-1","name":"Workspace"}]}""")
                "/v1/projects" -> {
                    assertEquals("workspace-1", request.url.parameters["workspaceId"])
                    json("""{"items":[{"id":"project-1","name":"Project","workspaceId":"workspace-1"}]}""")
                }
                "/v1/conversations/a/runs" -> {
                    posted += post(request)
                    when {
                        disabled || posted.size == 3 -> failure(423, "POLICY_DISABLED")
                        posted.size == 2 -> failure(503, "UPSTREAM_UNAVAILABLE")
                        posted.size == 4 -> json(run(wrongConversation = true), HttpStatusCode.Accepted)
                        else -> json(run(), HttpStatusCode.Accepted)
                    }
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.openFirst()
            assertTrue(posted.isEmpty())
            val source = controller.state.value.messages.single()
            val workspace = controller.workspaces().items.single()
            val project = controller.projects(workspace.id).items.single()
            controller.startRun(source, project, "One task")
            assertEquals(PartnerNotice.RunDisabled, controller.state.value.notice)
            assertFalse(controller.state.value.runPending)
            assertNull(controller.state.value.run)
            disabled = false
            controller.startRun(source, project, "One task")
            repeat(2) {
                controller.startRun(null, null, "Other task")
                assertTrue(controller.state.value.runPending)
                assertNull(controller.state.value.run)
            }
            controller.startRun(null, null, "")
            assertEquals(1, posted.drop(1).distinct().size)
            assertEquals(JsonPrimitive(source.id), Json.parseToJsonElement(posted.last().body).jsonObject["sourceMessageId"])
            assertEquals("run-1", controller.state.value.run?.id)
        }
    }

    @Test
    fun approvalAndExactDecisionAreSeparateFromReceiptAndFreshTaskProof() = runTest {
        var approval = approval(effect = "another-effect")
        var effect = effect("queued")
        var receipt = receipt("foreign")
        val writes = mutableListOf<Post>()
        var taskReads = 0
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                "/v1/conversations" -> json(directory("a"))
                "/v1/conversations/a/metadata" -> json(topic("a"))
                "/v1/conversations/a/messages" -> json(page(listOf(
                    """{"id":"outcome","userId":"user-1","sequence":2,"role":"assistant","content":"Outcome","createdAt":"$instant","runId":"run-1","receiptIds":["receipt-1"]}""",
                )))
                "/v1/agent-runs/run-1" -> json(run())
                "/v1/approvals/approval-1" -> json(approval)
                "/v1/approvals/approval-1/decision" -> {
                    writes += post(request)
                    when (writes.size) {
                        1 -> failure(503, "UPSTREAM_UNAVAILABLE")
                        2 -> failure(409, "VERSION_CONFLICT")
                        else -> json("""{"approvalId":"approval-1","effectId":"effect-1","runId":"effect-run-1","status":"approved","version":2}""")
                    }
                }
                "/v1/effects/effect-1" -> json(effect)
                "/v1/effects/effect-1/receipts" -> json(receipt)
                "/v1/tasks/task-1" -> {
                    taskReads++
                    json("""{"id":"task-1","projectId":"project-1","title":"Current task","description":null,"parentTaskId":null,"dueAt":null,"status":"ready","version":2}""")
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.openFirst()
            controller.openRun(controller.state.value.messages.single())
            val step = assertNotNull(controller.state.value.run).steps.single()
            controller.review(step)
            assertNull(controller.state.value.approval)
            approval = approval()
            controller.review(step)
            assertEquals("approval-1", controller.state.value.approval?.id)
            assertEquals(0, writes.size)
            controller.decide(PartnerDecision.approve)
            controller.decide(PartnerDecision.reject)
            assertTrue(controller.state.value.decisionPending)
            controller.decide(null)
            assertEquals(1, writes.distinct().size)
            val body = Json.parseToJsonElement(writes.last().body).jsonObject
            assertEquals(JsonPrimitive("approve"), body["decision"])
            assertEquals(JsonPrimitive(digest), body["payloadHash"])
            assertEquals(JsonPrimitive(writes.last().key), body["clientOperationId"])
            assertEquals(PartnerNotice.DecisionRecorded, controller.state.value.notice)
            assertNull(controller.state.value.proof)
            approval = approval("approved")
            controller.verifyTask(step)
            assertIs<PartnerTaskProof.Queued>(controller.state.value.proof)
            effect = effect()
            controller.verifyTask(step)
            assertNull(controller.state.value.proof)
            assertEquals(0, taskReads)
            receipt = receipt(runId = "run-1")
            controller.verifyTask(step)
            assertNull(controller.state.value.proof)
            assertEquals(0, taskReads)
            receipt = receipt()
            effect = effect(runId = "run-1")
            controller.verifyTask(step)
            assertNull(controller.state.value.proof)
            assertEquals(0, taskReads)
            effect = effect()
            controller.verifyTask(step)
            val proof = assertIs<PartnerTaskProof.Verified>(controller.state.value.proof)
            assertEquals("receipt-1", proof.receiptId)
            assertEquals(2L, proof.task.version)
            assertEquals(1, taskReads)
        }
    }

    @Test
    fun oldConversationSuccessOrUnauthorizedCannotChangeNewConversation() = runTest {
        for (denied in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture(handler = { request ->
                when (request.url.encodedPath) {
                    "/v1/conversations" -> json(directory("a", "b"))
                    "/v1/conversations/a/metadata" -> json(topic("a"))
                    "/v1/conversations/a/messages" -> {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        if (denied) failure(401, "SESSION_REVOKED") else json(page(listOf(message(2))))
                    }
                    "/v1/conversations/b/metadata" -> json(topic("b"))
                    "/v1/conversations/b/messages" -> {
                        assertNull(request.url.parameters["cursor"])
                        json(page(listOf(message(10))))
                    }
                    else -> error("Unexpected route")
                }
            }) { sessions, active, controller ->
                controller.directory(PartnerDirectory.Active)
                val (first, second) = controller.state.value.conversations
                controller.open(first)
                val pending = launch { controller.refreshConversation() }
                entered.await()
                controller.closeConversation()
                controller.open(second)
                controller.refreshConversation()
                release.complete(Unit)
                pending.join()
                assertEquals(active, sessions.state.value)
                assertEquals("b", controller.state.value.topic?.id)
                assertEquals(listOf(id(10)), controller.state.value.messages.map { it.id })
                assertFalse(controller.state.value.busy)
            }
        }
    }

    @Test
    fun organizationSwitchDropsDraftsAndFencesLateSuccessOrUnauthorized() = runTest {
        for (denied in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var started = false
            fixture(handler = { request ->
                when (request.url.encodedPath) {
                    "/v1/conversations" -> json(directory("a"))
                    "/v1/conversations/a/metadata" -> json(topic("a"))
                    "/v1/conversations/a/messages" ->
                        if (started && request.headers[HttpHeaders.Authorization] == "Bearer $firstToken") {
                            entered.complete(Unit)
                            release.await()
                            if (denied) failure(401, "SESSION_REVOKED") else json(page(listOf(message(2))))
                        } else json(page(emptyList()))
                    else -> error("Unexpected route")
                }
            }) { sessions, _, previous ->
                previous.openFirst()
                previous.editMessage("First organization draft")
                previous.closeConversation()
                previous.open(previous.state.value.conversations.single())
                assertEquals("First organization draft", previous.state.value.messageDraft)
                started = true
                val pending = launch { previous.refreshConversation() }
                entered.await()
                val active = sessions.switchOrganization("org-2")
                val next = CorePartnerController(sessions, active)
                next.openFirst()
                release.complete(Unit)
                pending.join()
                assertEquals(active, sessions.state.value)
                assertEquals("", next.state.value.messageDraft)
                assertTrue(next.state.value.messages.isEmpty())
                previous.retire()
                assertEquals(PartnerState(), previous.state.value)
            }
        }
    }

    @Test
    fun pollingStopsOnCancellationAndRemainsBounded() = runTest {
        var reads = 0
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                "/v1/conversations" -> json(directory("a"))
                "/v1/conversations/a/metadata" -> json(topic("a"))
                "/v1/conversations/a/messages" -> json(page(listOf(
                    """{"id":"outcome","userId":"user-1","sequence":2,"role":"assistant","content":"Outcome","createdAt":"$instant","runId":"run-1","receiptIds":[]}""",
                )))
                "/v1/agent-runs/run-1" -> {
                    reads++
                    json(run("queued", steps = false))
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.openFirst()
            controller.openRun(controller.state.value.messages.single())
            val canceled = launch { controller.pollRun("a", "run-1") }
            runCurrent()
            advanceTimeBy(2_499)
            canceled.cancelAndJoin()
            assertEquals(1, reads)
            controller.pollRun("a", "run-1")
            assertEquals(13, reads)
        }
    }
}
