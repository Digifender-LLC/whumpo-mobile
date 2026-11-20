package com.whumpo.core.auth

import com.whumpo.core.api.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoreWorkCompletionTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val token = "A".repeat(43)
    private val digest = "a".repeat(64)
    private val instant = "2026-11-19T12:00:00Z"
    private val task = CoreWorkTask("task-1", "project-1", "Finish the checklist", null, null, null, CoreTaskStatus.InProgress, 1)

    @Test
    fun deniedFreshnessAndVersionConflictNeverBecomeCompletionOrLogout() = runTest {
        var status = HttpStatusCode.Forbidden
        val keys = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            if (request.url.encodedPath == "/v1/auth/session") respond("""{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}""")
            else {
                assertEquals("/v1/tasks/task-1/transitions", request.url.encodedPath)
                assertEquals("\"1\"", request.headers[HttpHeaders.IfMatch])
                keys += request.headers["Idempotency-Key"]
                bodies += request.body.toByteArray().decodeToString()
                respond("""{"error":{"code":"${if (status == HttpStatusCode.Forbidden) "RECENT_OWNER_PROOF_REQUIRED" else "VERSION_CONFLICT"}","message":"Task change unavailable","requestId":"request-1","retryable":false}}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api, now = { 1000 })
            val active = sessions.establish(token)
            val model = CoreWorkCompletionController(sessions, active, task)
            model.submit()
            assertEquals(CoreWorkCompletionNotice.FreshOwnerRequired, model.state.value.notice)
            assertSame(active, sessions.state.value)
            assertNull(model.state.value.proof)
            status = HttpStatusCode.Conflict
            model.submit()
            assertEquals(CoreWorkCompletionNotice.Changed, model.state.value.notice)
            assertEquals(2, keys.size)
            assertNotNull(keys.first())
            assertEquals(keys.first(), keys.last())
            assertEquals(bodies.first(), bodies.last())
            assertFalse(model.state.value.accepted)
            assertSame(active, sessions.state.value)
        } finally { api.close() }
    }

    @Test
    fun nonOwnerCannotSendACompletionAndCurrentOwnerReceiptMustMatch() = runTest {
        var role = "viewer"
        var actor = "another-user"
        var writes = 0
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            val body = when (request.url.encodedPath) {
                "/v1/auth/session" -> """{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"$role","expiresAt":9000}"""
                "/v1/tasks/task-1/transitions" -> { writes++; """{"runId":"run-1","effectId":"effect-1","status":"queued"}""" }
                "/v1/effects/effect-1" -> """{"id":"effect-1","runId":"run-1","state":"confirmed","version":2,"payloadHash":"$digest","policyRevision":2,"taskId":"task-1"}"""
                "/v1/effects/effect-1/receipts" -> """{"items":[{"schemaVersion":1,"receiptId":"receipt-1","effectId":"effect-1","orgId":"org-1","runId":"run-1","outcome":"confirmed","verificationMethod":"internal_read_after_write","redactedSummary":"Task marked done","verifiedAt":"$instant","createdAt":"$instant","taskEvidence":{"taskId":"task-1","taskVersion":2,"taskDigest":"$digest"},"completion":{"kind":"human_assertion","actorId":"$actor"}}]}"""
                "/v1/tasks/task-1" -> """{"id":"task-1","projectId":"project-1","title":"Finish the checklist","description":null,"parentTaskId":null,"dueAt":null,"status":"done","version":2}"""
                else -> error("Unexpected route")
            }
            respond(body, if (request.url.encodedPath.endsWith("/transitions")) HttpStatusCode.Accepted else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api, now = { 1000 })
            val viewer = sessions.establish(token)
            val denied = CoreWorkCompletionController(sessions, viewer, task)
            denied.submit()
            assertEquals(0, writes)
            assertEquals(CoreWorkCompletionNotice.FreshOwnerRequired, denied.state.value.notice)
            role = "owner"
            val owner = sessions.establish(token)
            val model = CoreWorkCompletionController(sessions, owner, task)
            model.submit()
            assertNull(model.state.value.proof)
            actor = "user-1"
            model.refresh()
            val proof = assertIs<PartnerTaskProof.Verified>(model.state.value.proof)
            assertEquals(2L, proof.verifiedVersion)
            assertEquals(CoreTaskStatus.Done, proof.task.status)
            assertEquals(1, writes)
            model.retire()
            assertNull(model.state.value.proof)
        } finally { api.close() }
    }
    @Test
    fun pendingApprovalIsNotCompletionAndChangedReviewCannotBeDecided() = runTest {
        var version = 1
        var decisions = 0
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            val body = when (request.url.encodedPath) {
                "/v1/auth/session" -> """{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}"""
                "/v1/tasks/task-1/transitions" -> """{"runId":"run-1","effectId":"effect-1","status":"waiting_approval","approvalId":"approval-1"}"""
                "/v1/approvals/approval-1" -> """{"id":"approval-1","effectId":"effect-1","runId":"run-1","status":"pending","payloadHash":"$digest","policyRevision":2,"requesterId":"user-1","proposal":{"operation":"tasks.transition@1","taskId":"task-1","expectedVersion":1,"payload":{"status":"done","reason":"Completed from Work."}},"expiresAt":"2099-01-01T00:00:00Z","version":$version}"""
                "/v1/approvals/approval-1/decision" -> { decisions++; error("Changed review must never be decided") }
                else -> error("Unexpected route")
            }
            respond(body, if (request.url.encodedPath.endsWith("/transitions")) HttpStatusCode.Accepted else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api, now = { 1000 })
            val model = CoreWorkCompletionController(sessions, sessions.establish(token), task)
            model.submit()
            assertEquals(PartnerTaskProof.WaitingApproval, model.state.value.proof)
            assertTrue(model.state.value.accepted)
            assertEquals(1L, model.state.value.approval?.version)
            version = 2
            model.approve()
            assertEquals(0, decisions)
            assertEquals(CoreWorkCompletionNotice.ReviewChanged, model.state.value.notice)
            assertEquals(2L, model.state.value.approval?.version)
            assertEquals(PartnerTaskProof.WaitingApproval, model.state.value.proof)
        } finally { api.close() }
    }

}
