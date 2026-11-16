package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreDiscoveryActionId
import com.whumpo.core.api.CoreHandoffCommand
import com.whumpo.core.api.CoreHandoffLineage
import com.whumpo.core.api.CoreHandoffState
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.PartnerDecision
import com.whumpo.core.api.decodeHandoff
import com.whumpo.core.api.decodeHandoffInventory
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
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreDiscoveryHandoffTest {
    private val origin = "https://handoff.example.test"
    private val bearer = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val discoveryPath = "/v1/onboarding/discovery"
    private val path = "$discoveryPath/handoffs"
    private val effectPath = "/v1/effects/effect-demo"
    private val approvalPath = "/v1/approvals/approval-demo"
    private val hash = "a".repeat(64)

    @Test
    fun canonicalFixturesDecodeAndUnknownFieldsFailClosed() {
        val cases = examples()
        val command = CoreHandoffCommand("project-demo", 4, 3, 4, CoreDiscoveryActionId.Domain)
        assertEquals(cases.getValue("command"), Json.parseToJsonElement(command.encode()))
        val waiting = decodeHandoff(cases.getValue("waitingApproval").toString())
        val confirmed = decodeHandoff(cases.getValue("confirmed").toString())
        assertEquals(CoreHandoffState.WaitingApproval, waiting.state)
        assertEquals(CoreHandoffState.Confirmed, confirmed.state)
        assertTrue(command.matches(waiting) && command.matches(confirmed))
        assertEquals(listOf(confirmed), decodeHandoffInventory(cases.getValue("inventory").toString(), "org-demo"))
        val value = cases.getValue("confirmed").toString()
        val unexpectedFields = listOf(
            value.dropLast(1) + ",\"unexpected\":null}",
            value.replace("\"taskVersion\":1", "\"taskVersion\":1,\"unknown\":true"),
            value.replace("\"taskId\":\"task-migration\"", "\"taskId\":\"task-migration\",\"unknown\":true"),
            value.replace("\"taskVersion\":1", "\"taskVersion\":\"1\""),
            value.replace("\"state\":\"confirmed\"", "\"state\":\"queued\""),
        )
        unexpectedFields.forEach {
            assertFailsWith<CoreProtocolException> { decodeHandoff(it) }
        }
        assertFailsWith<CoreProtocolException> { decodeHandoffInventory(cases.getValue("inventory").toString(), "other-org") }
        val duplicate = """{"schemaVersion":1,"orgId":"org-demo","handoffs":[$value,$value]}"""
        assertFailsWith<CoreProtocolException> { decodeHandoffInventory(duplicate, "org-demo") }
    }

    @Test
    fun lostAdmissionAndDecisionRetryExactAndWorkNeedsAllThreeProofs() = runTest {
        val writes = mutableListOf<Write>()
        var state = "waiting_approval"
        var receiptValid = false
        var workValid = false
        var canonicalReads = 0
        var detailUnavailable = false
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness("project-demo"))
                discoveryPath -> json(snapshot())
                path -> if (request.method == HttpMethod.Get) json(inventory())
                else {
                    writes += sent(request)
                    if (writes.size == 1) fail(503)
                    else json(handoff())
                }
                "$path/effect-demo" -> {
                    canonicalReads++
                    if (detailUnavailable) {
                        detailUnavailable = false
                        fail(503)
                    } else json(handoff(state = state))
                }
                effectPath -> json(effect(state))
                approvalPath -> json(approval())
                "$approvalPath/decision" -> {
                    writes += sent(request)
                    if (writes.count { it.path == "$approvalPath/decision" } == 1) failPartner(503)
                    else {
                        state = "queued"
                        detailUnavailable = true
                        json("""{"approvalId":"approval-demo","effectId":"effect-demo","runId":"run-demo","status":"approved","version":2}""")
                    }
                }
                "$effectPath/receipts" -> json(receipts(if (receiptValid) "receipt-effect-demo" else "other-receipt"))
                "/v1/tasks/task-domain" -> json(work(if (workValid) "project-demo" else "other-project"))
                else -> error("Unexpected fixture route")
            }
        }) { _, _, controller ->
            controller.beginReview(CoreDiscoveryActionId.Goal, 4)
            val review = assertNotNull(controller.state.value.review)
            assertTrue(review.canSubmit)
            controller.submit(review)
            assertTrue(controller.state.value.pending)
            assertFalse(controller.state.value.acknowledged)
            assertEquals(0, canonicalReads)
            controller.retry()
            assertEquals(writes[0], writes[1])
            assertEquals(CoreHandoffState.WaitingApproval, controller.state.value.detail?.state)
            assertEquals(1, canonicalReads)
            assertNull(controller.state.value.verifiedTask)

            controller.reviewApproval("effect-demo")
            val approval = assertNotNull(controller.state.value.approval)
            controller.decide(approval, PartnerDecision.approve)
            assertTrue(controller.state.value.pending)
            controller.retry()
            assertEquals(writes[2], writes[3])
            val body = Json.parseToJsonElement(writes[2].body).jsonObject
            assertEquals("\"$hash\"", body.getValue("payloadHash").toString())
            assertEquals("1", body.getValue("expectedVersion").toString())
            assertEquals("\"${writes[2].key}\"", body.getValue("clientOperationId").toString())
            assertTrue(controller.state.value.acknowledged)
            controller.retry()
            assertEquals(4, writes.size)
            assertEquals(CoreHandoffState.Queued, controller.state.value.detail?.state)
            assertNull(controller.state.value.verifiedTask)

            state = "confirmed"
            controller.inspect("effect-demo")
            assertNull(controller.state.value.verifiedTask)
            receiptValid = true
            controller.inspect("effect-demo")
            assertNull(controller.state.value.verifiedTask)
            workValid = true
            controller.inspect("effect-demo")
            assertEquals("task-domain", controller.state.value.verifiedTask?.id)
        }
    }

    @Test
    fun legacyProjectAndPrerequisiteRequireCurrentExplicitReview() = runTest {
        var prerequisiteLineage = "superseded"
        var stale = false
        var writes = 0
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(null))
                discoveryPath -> json(if (stale) staleSnapshot() else snapshot(dependent = true))
                path -> if (request.method == HttpMethod.Get) {
                    json(inventory(handoff("confirmed", "migration", id = "effect-prior", lineage = prerequisiteLineage)))
                } else {
                    writes++
                    fail(409)
                }
                "/v1/workspaces" -> json("""{"items":[{"id":"workspace","name":"Studio"}],"nextCursor":null}""")
                "/v1/projects" -> json("""{"items":[{"id":"project-demo","workspaceId":"workspace","name":"Main"}],"nextCursor":null}""")
                else -> error("Unexpected fixture route")
            }
        }) { _, _, controller ->
            controller.beginReview(CoreDiscoveryActionId.Goal, 4)
            assertNull(controller.state.value.review?.projectId)
            assertFalse(assertNotNull(controller.state.value.review).canSubmit)
            val workspace = assertNotNull(controller.state.value.workspaces).items.single()
            controller.chooseWorkspace(workspace)
            val project = assertNotNull(controller.state.value.projects).items.single()
            controller.chooseProject(project)
            assertEquals(listOf(CoreDiscoveryActionId.Migration), controller.state.value.review?.pendingPrerequisites)
            assertFalse(assertNotNull(controller.state.value.review).canSubmit)
            prerequisiteLineage = "current"
            controller.chooseProject(project)
            val reviewed = assertNotNull(controller.state.value.review)
            assertTrue(reviewed.canSubmit)
            stale = true
            controller.submit(reviewed)
            assertEquals(DiscoveryHandoffNotice.Changed, controller.state.value.notice)
            assertNull(controller.state.value.review)
            assertEquals(0, writes)
        }
    }

    @Test
    fun coldInventoryKeepsSupersededHistoryAndDeniedReadHidesDiscoveryDuringRetry() = runTest {
        var inventoryDenied = false
        var detailDenied = false
        val writes = mutableListOf<Write>()
        val old = handoff("confirmed", id = "effect-prior", lineage = "superseded")
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness("project-demo"))
                discoveryPath -> json(snapshot())
                path -> if (request.method == HttpMethod.Post) {
                    writes += sent(request)
                    if (writes.size == 1) fail(403) else json(handoff(previous = "effect-prior"))
                } else if (inventoryDenied) fail(423)
                else json(inventory(old))
                "$path/effect-demo" -> if (detailDenied) fail(403)
                else json(handoff(previous = "effect-prior"))
                else -> error("Unexpected fixture route")
            }
        }) { _, discovery, controller ->
            discovery.refresh()
            controller.refreshInventory()
            val prior = controller.state.value.inventory.single()
            assertEquals(CoreHandoffLineage.Superseded, prior.lineage)
            assertNull(controller.state.value.verifiedTask)
            controller.beginReview(CoreDiscoveryActionId.Goal, 4)
            val reviewed = assertNotNull(controller.state.value.review)
            assertTrue(reviewed.canSubmit)
            detailDenied = true
            controller.submit(reviewed)
            assertFalse(controller.state.value.acknowledged)
            assertTrue(controller.state.value.pending)
            assertNull(discovery.state.value.snapshot)
            controller.retry()
            assertTrue(controller.state.value.acknowledged)
            assertTrue(controller.state.value.pending)
            assertNull(discovery.state.value.snapshot)
            assertNull(controller.state.value.detail)
            assertTrue(controller.state.value.inventory.isEmpty())
            detailDenied = false
            controller.retry()
            assertEquals("effect-prior", controller.state.value.detail?.supersedesHandoffId)
            assertEquals(2, writes.size)
            assertEquals(writes[0], writes[1])
            assertFalse(controller.state.value.pending)
            inventoryDenied = true
            controller.refreshInventory()
            assertTrue(controller.state.value.inventory.isEmpty())
            assertNull(controller.state.value.detail)
        }
    }

    @Test
    fun rejectedApprovalBindingAndEpochSwitchPreventForeignDecision() = runTest {
        var wrongHash = true
        var holdDecision = false
        var decisionWrites = 0
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                path -> json(inventory(handoff()))
                "$path/effect-demo" -> json(handoff())
                effectPath -> json(effect("waiting_approval"))
                approvalPath -> json(approval(if (wrongHash) "b".repeat(64) else hash))
                "$approvalPath/decision" -> {
                    decisionWrites++
                    check(holdDecision)
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                else -> error("Unexpected fixture route")
            }
        }) { sessions, _, controller ->
            controller.inspect("effect-demo")
            controller.reviewApproval("effect-demo")
            assertNull(controller.state.value.approval)
            wrongHash = false
            controller.reviewApproval("effect-demo")
            val reviewed = assertNotNull(controller.state.value.approval)
            holdDecision = true
            val pending = launch { controller.decide(reviewed, PartnerDecision.approve) }
            started.await()
            sessions.switchOrganization("other-org")
            pending.join()
            stopped.await()
            controller.retry()
            controller.decide(reviewed, PartnerDecision.approve)
            assertEquals(1, decisionWrites)
            assertNull(controller.state.value.verifiedTask)
        }
    }

    private suspend fun fixture(
        routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        test: suspend (CoreOrganizationSessionController, CoreBusinessDiscoveryController, CoreDiscoveryHandoffController) -> Unit,
    ) {
        val engine = MockEngine { request ->
            val org = if (request.headers[HttpHeaders.Authorization] == "Bearer $replacement") "other-org" else "org-demo"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> json("""{"sessionId":"session-$org","orgId":"$org","userId":"owner","role":"owner","expiresAt":60001}""")
                "/v1/auth/session/switch" -> json("""{"token":"$replacement","expiresAt":60001}""")
                else -> {
                    assertEquals("handoff.example.test", request.url.host)
                    assertEquals("Bearer $bearer", request.headers[HttpHeaders.Authorization])
                    routes(request)
                }
            }
        }
        CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin))).use { api ->
            val sessions = CoreOrganizationSessionController(api) { 1L }
            val active = sessions.establish(bearer)
            val discovery = CoreBusinessDiscoveryController(sessions, active)
            test(sessions, discovery, CoreDiscoveryHandoffController(sessions, active, discovery))
        }
    }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    private fun MockRequestHandleScope.fail(status: Int) = respond("{}", HttpStatusCode.fromValue(status))
    private fun MockRequestHandleScope.failPartner(status: Int) =
        respond("""{"error":{"code":"SERVICE_UNAVAILABLE","message":"Synthetic unavailable","requestId":"request-id","retryable":true}}""",
            HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))

    private data class Write(val path: String, val body: String, val key: String)
    private suspend fun sent(request: HttpRequestData) = Write(request.url.encodedPath,
        request.body.toByteArray().decodeToString(), checkNotNull(request.headers["Idempotency-Key"]))

    private fun examples(): JsonObject = Json.parseToJsonElement(generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "tests/fixtures/discovery-work-handoff-v1/discovery-work-handoff-examples.json") }
        .first(File::isFile).readText()).jsonObject

    private fun readiness(project: String?) =
        """{"orgId":"org-demo","ready":true${project?.let { ",\"projectId\":\"$it\"" }.orEmpty()}}"""

    private fun inventory(vararg rows: String) =
        """{"schemaVersion":1,"orgId":"org-demo","handoffs":[${rows.joinToString(",")}]}"""

    private fun snapshot(dependent: Boolean = false): String {
        val dependencies = if (dependent) """["migration"]""" else "[]"
        val prior = if (dependent) """,{"id":"migration","title":"Migration","reason":"Review the site","dependencies":[],"answerKeys":[],"requiresReview":true,"deferred":false}""" else ""
        val recommended = if (dependent) "migration" else "goal"
        return """{"schemaVersion":1,"orgId":"org-demo","revision":4,"answerRevision":3,"answers":{},"activeQuestions":["stage"],"planStatus":"current","plan":{"revision":4,"answerRevision":3,"rulesVersion":1,"summary":"Review the plan","actions":[{"id":"goal","title":"First goal","reason":"Your first step","dependencies":$dependencies,"answerKeys":[],"requiresReview":true,"deferred":false}$prior],"selectedNext":null,"recommendedNext":"$recommended"}}"""
    }

    private fun staleSnapshot() =
        """{"schemaVersion":1,"orgId":"org-demo","revision":4,"answerRevision":3,"answers":{},"activeQuestions":["stage"],"planStatus":"stale","plan":null}"""

    private fun handoff(state: String = "waiting_approval", action: String = "goal", id: String = "effect-demo",
        lineage: String = "current", previous: String? = null): String {
        val version = effectVersion(state)
        val task = if (state == "confirmed") """{"taskId":"task-domain","receiptId":"receipt-effect-demo","taskVersion":1}""" else "null"
        val prior = previous?.let { "\"$it\"" } ?: "null"
        return """{"schemaVersion":1,"orgId":"org-demo","handoffId":"$id","projectId":"project-demo","actionId":"$action","discoveryRevision":4,"answerRevision":3,"planRevision":4,"dependencies":[],"state":"$state","approvalId":"approval-demo","effectVersion":$version,"payloadHash":"$hash","task":$task,"lineageStatus":"$lineage","supersedesHandoffId":$prior}"""
    }

    private fun effect(state: String): String {
        val task = if (state == "confirmed") ""","taskId":"task-domain"""" else ""
        return """{"id":"effect-demo","runId":"run-demo","state":"$state","version":${effectVersion(state)},"payloadHash":"$hash","policyRevision":2,"approvalId":"approval-demo"$task}"""
    }

    private fun effectVersion(state: String): Int = when (state) {
        "waiting_approval" -> 1
        "queued" -> 2
        else -> 3
    }

    private fun approval(payloadHash: String = hash) =
        """{"id":"approval-demo","effectId":"effect-demo","runId":"run-demo","status":"pending","payloadHash":"$payloadHash","policyRevision":2,"requesterId":"owner","proposal":{"operation":"tasks.create@1","payload":{"projectId":"project-demo","title":"First goal","parentTaskId":null,"description":null,"dueAt":null}},"expiresAt":"2026-11-26T12:00:00.000Z","version":1}"""

    private fun receipts(id: String) =
        """{"items":[{"schemaVersion":1,"receiptId":"$id","effectId":"effect-demo","orgId":"org-demo","runId":"run-demo","outcome":"confirmed","verificationMethod":"internal_read_after_write","redactedSummary":"Created a task","verifiedAt":"2026-11-16T12:00:00.000Z","createdAt":"2026-11-16T12:00:00.000Z","taskEvidence":{"taskId":"task-domain","taskVersion":1,"taskDigest":"$hash"}}]}"""

    private fun work(project: String) =
        """{"id":"task-domain","projectId":"$project","title":"First goal","description":null,"parentTaskId":null,"dueAt":null,"status":"backlog","version":1}"""
}
