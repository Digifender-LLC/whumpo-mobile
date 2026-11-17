package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreInboxDetail
import com.whumpo.core.api.CoreInboxPresentation
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class CoreInboxTest {
    private val origin = CoreOrigin.allowlisted("https://api.example.test", setOf("https://api.example.test"))
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val firstToken = "A".repeat(43)
    private val nextToken = "E".repeat(43)
    private val cursor = "c".repeat(64)
    private val at = "2026-11-16T11:00:00.000Z"
    private val start = "2026-10-17T11:00:00.000Z"
    private val ackAt = "2026-11-16T11:05:00.000Z"
    private val digest = "a".repeat(64)
    private val list = "/v1/notifications?limit=10"
    private val more = "/v1/notifications?cursor=$cursor&limit=10"
    private val resolve = "/v1/notifications/notice-1/resolve"
    private val approval = "/v1/approvals/approval-1"
    private val receipts = "/v1/effects/effect-1/receipts"
    private val ack = "/v1/notifications/notice-1/acknowledge"

    private fun item(category: String = "approval_required", id: String = "notice-1", acknowledged: String = "null") =
        """{"notificationId":"$id","category":"$category","severity":"normal","observedAt":"$at",
            "acknowledgedAt":$acknowledged,"presentation":"quiet_hours"}"""

    private fun page(items: String = item(), cursor: String? = null) =
        """{"asOf":"$at","windowStart":"$start","policyVersion":1,"items":[$items]${cursor?.let { ""","nextCursor":"$it"""" } ?: ""}}"""

    private fun approvalResolution(status: String = "actionable") =
        """{"notificationId":"notice-1","category":"approval_required","status":"$status",
            "source":{"kind":"approval","approvalId":"approval-1","effectId":"effect-1","runId":"run-1"}}"""

    private fun receiptResolution() =
        """{"notificationId":"notice-1","category":"effect_confirmed","status":"verified",
            "source":{"kind":"effect_receipt","receiptId":"receipt-1","effectId":"effect-1","runId":"run-1"}}"""

    private fun canonicalApproval() =
        """{"id":"approval-1","effectId":"effect-1","runId":"run-1","status":"pending",
            "payloadHash":"$digest","policyRevision":1,"requesterId":"actor-1",
            "proposal":{"operation":"tasks.create@1","payload":{"title":"Synthetic task"}},"expiresAt":"$ackAt","version":1}"""

    private fun canonicalReceipt(evidence: String = """"taskEvidence":{"taskId":"task-1","taskVersion":1,"taskDigest":"$digest"}""") =
        """{"items":[{"schemaVersion":1,"receiptId":"receipt-1","effectId":"effect-1","runId":"run-1","orgId":"selected-org",
            "outcome":"confirmed","verificationMethod":"internal_read_after_write","redactedSummary":"Synthetic result",
            "verifiedAt":"$at","createdAt":"$at",$evidence}]}"""

    private data class Reply(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)
    private data class Request(val target: String, val body: String? = null)

    private inner class Fixture(private val role: String = "owner", private val response: suspend (String) -> Reply) {
        val requests = ConcurrentLinkedQueue<Request>()
        val targets get() = requests.map(Request::target)
        val engine = MockEngine { request ->
            assertEquals("api.example.test", request.url.host)
            assertNull(request.headers[HttpHeaders.Origin])
            assertNull(request.headers["Idempotency-Key"])
            val token = request.headers[HttpHeaders.Authorization]
            val target = request.url.encodedPathAndQuery
            if (target == "/v1/auth/session") {
                val original = token == "Bearer $firstToken"
                val id = if (original) "session-1" else "session-2"
                val org = if (original) "selected-org" else "other-org"
                respond("""{"sessionId":"$id","orgId":"$org","userId":"actor-1","role":"$role","expiresAt":9000}""",
                    headers = json)
            } else {
                assertEquals("Bearer $firstToken", token)
                if (target == ack) {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("application/json", request.body.contentType.toString())
                    requests.add(Request(target, request.body.toByteArray().decodeToString()))
                } else {
                    assertEquals(HttpMethod.Get, request.method)
                    requests.add(Request(target))
                }
                val result = response(target)
                respond(result.body, result.status, json)
            }
        }

        suspend fun use(block: suspend (CoreOrganizationSessionController, CoreOrganizationSessionController.State.Active) -> Unit) {
            CoreApiClient(engine, origin).use { api ->
                val sessions = CoreOrganizationSessionController(api) { 1 }
                block(sessions, sessions.establish(firstToken))
            }
        }
    }

    @Test
    fun approvalNeedsSameCanonicalPendingIdentityAndFinalOwnerAudienceWithoutAcknowledging() = runTest {
        for (status in listOf("actionable", "reauthentication_required")) {
            val fixture = Fixture {
                Reply(when (it) {
                    list -> page()
                    resolve -> approvalResolution(status)
                    approval -> canonicalApproval()
                    else -> error("Unexpected request")
                })
            }
            fixture.use { sessions, active ->
                val page = sessions.inboxPage(active) { true }
                val row = page.items.single()
                assertEquals(CoreInboxPresentation.QuietHours, row.presentation)
                assertNull(row.acknowledgedAt)
                val detail = assertIs<CoreInboxDetail.Approval>(sessions.openInboxItem(active, page, row) { true })
                assertEquals("Create task", detail.operation)
                assertEquals(ackAt, detail.expiresAt)
                assertEquals(status == "reauthentication_required", detail.needsSignIn)
                assertEquals(listOf(list, resolve, approval, resolve), fixture.targets)
            }
        }
    }

    @Test
    fun revokedChangedAndMismatchedApprovalNeverProduceDetail() = runTest {
        var resolveReads = 0
        val changed = Fixture {
            Reply(when (it) {
                list -> page()
                resolve -> approvalResolution(if (++resolveReads == 1) "actionable" else "revoked")
                approval -> canonicalApproval()
                else -> error("Unexpected request")
            })
        }
        changed.use { sessions, active ->
            val page = sessions.inboxPage(active) { true }
            assertFailsWith<CoreProtocolException> { sessions.openInboxItem(active, page, page.items.single()) { true } }
            assertEquals(listOf(list, resolve, approval, resolve), changed.targets)
        }
        for ((initial, proof, expected) in listOf(
            Triple(approvalResolution("expired"), canonicalApproval(), listOf(list, resolve)),
            Triple(approvalResolution().replace("notice-1", "notice-2"), canonicalApproval(), listOf(list, resolve)),
            Triple(approvalResolution(), canonicalApproval().replace("run-1", "run-2"), listOf(list, resolve, approval)),
            Triple(approvalResolution(), canonicalApproval().replace("pending", "approved"), listOf(list, resolve, approval)),
        )) {
            val fixture = Fixture {
                Reply(when (it) {
                    list -> page()
                    resolve -> initial
                    approval -> proof
                    else -> error("Unexpected request")
                })
            }
            fixture.use { sessions, active ->
                val page = sessions.inboxPage(active) { true }
                assertFailsWith<CoreProtocolException> { sessions.openInboxItem(active, page, page.items.single()) { true } }
                assertEquals(expected, fixture.targets)
            }
        }
    }

    @Test
    fun scopedSingleAndBatchReceiptsRequireExactVerifiedTuple() = runTest {
        val batch = canonicalReceipt(""""batch":{"proposalId":"proposal-1","memberCount":3,"manifestDigest":"$digest"}""")
        val receipt = canonicalReceipt()
        val mismatches = listOf(
            receipt.replace("receipt-1", "receipt-2"), receipt.replace("effect-1", "effect-2"),
            receipt.replace("run-1", "run-2"), receipt.replace("selected-org", "other-org"),
            receipt.replace("confirmed", "queued"), receipt.replace("internal_read_after_write", "unverified"),
            receipt.replace("taskDigest", "unrecognizedField"), """{"items":[]}""",
        )
        for ((body, count) in listOf(receipt to 1L, batch to 3L) + mismatches.map { it to null }) {
            val fixture = Fixture("viewer") {
                Reply(when (it) {
                    list -> page(item("effect_confirmed"))
                    resolve -> receiptResolution()
                    receipts -> body
                    else -> error("Unexpected request")
                })
            }
            fixture.use { sessions, active ->
                val page = sessions.inboxPage(active) { true }
                if (count == null) {
                    assertFailsWith<CoreProtocolException> { sessions.openInboxItem(active, page, page.items.single()) { true } }
                } else {
                    assertEquals(CoreInboxDetail.ConfirmedResult(at, count),
                        sessions.openInboxItem(active, page, page.items.single()) { true })
                }
                assertEquals(listOf(list, resolve, receipts), fixture.targets)
            }
        }
    }

    @Test
    fun lostAcknowledgmentRetriesSameRequestAndReturnsOnlyOriginalServerTimestamp() = runTest {
        var writes = 0
        val acknowledgment = """{"notificationId":"notice-1","acknowledgedAt":"$ackAt"}"""
        val fixture = Fixture {
            when (it) {
                list -> Reply(page())
                ack -> when (++writes) {
                    1 -> Reply("{}", HttpStatusCode.ServiceUnavailable)
                    2, 3 -> Reply(acknowledgment)
                    else -> Reply(acknowledgment.replace(ackAt, "2026-11-16T11:06:00.000Z"))
                }
                else -> error("Acknowledgment must be independent of resolution")
            }
        }
        fixture.use { sessions, active ->
            val first = sessions.inboxPage(active) { true }
            val item = first.items.single()
            assertFailsWith<CoreHttpException> { sessions.acknowledgeInboxItem(active, first, item) { true } }
            assertNull(item.acknowledgedAt)
            val checked = sessions.acknowledgeInboxItem(active, first, item) { true }
            assertEquals(ackAt, checked.items.single().acknowledgedAt)
            val replay = sessions.acknowledgeInboxItem(active, checked, checked.items.single()) { true }
            assertEquals(ackAt, replay.items.single().acknowledgedAt)
            assertFailsWith<CoreProtocolException> {
                sessions.acknowledgeInboxItem(active, replay, replay.items.single()) { true }
            }
            assertEquals(listOf(Request(list)) + List(4) { Request(ack, "{}") }, fixture.requests.toList())
        }
    }

    @Test
    fun pageRejectsBadRoleFieldsDuplicateIdsAndBadCursorButCanRestartAExpiredPage() = runTest {
        val checked = page(item("effect_confirmed"))
        val oversized = page((1..11).joinToString(",") { item(id = "notice-$it") })
        for ((body, role) in listOf(
            page() to "viewer",
            page("${item()},${item(id = "notice-1", category = "effect_confirmed")}") to "owner",
            checked.replace("policyVersion", "unexpected") to "viewer",
            checked.replace(at, "2026-02-31T11:00:00.000Z") to "viewer",
            page(item("unknown_category")) to "owner",
            page(cursor = "malformed") to "owner",
            oversized to "owner",
        )) {
            val fixture = Fixture(role) { Reply(body) }
            fixture.use { sessions, active ->
                assertFailsWith<CoreProtocolException> { sessions.inboxPage(active) { true } }
                assertEquals(listOf(list), fixture.targets)
            }
        }
        val fixture = Fixture {
            when (it) {
                list -> Reply(page("${item()},${item("briefing_ready", "briefing-1")},${item("briefing_ready", "briefing-2")}", cursor))
                more -> Reply("{}", HttpStatusCode.Conflict)
                else -> error("Unexpected request")
            }
        }
        fixture.use { sessions, active ->
            val first = sessions.inboxPage(active) { true }
            assertEquals(1, first.items.size)
            assertFailsWith<CoreHttpException> { sessions.inboxPage(active, first) { true } }
            assertEquals(first.items, sessions.inboxPage(active) { true }.items)
            assertEquals(listOf(list, more, list), fixture.targets)
        }
    }

    @Test
    fun deniedSourceStopsFollowOnsAndClosingBeforeLate401KeepsCurrentSession() = runTest {
        val visible = AtomicBoolean(true)
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            visible.set(true)
            val fixture = Fixture {
                if (it == list) Reply(page()) else {
                    if (status == HttpStatusCode.Unauthorized) visible.set(false)
                    Reply("{}", status)
                }
            }
            fixture.use { sessions, active ->
                val page = sessions.inboxPage(active) { true }
                if (status == HttpStatusCode.Unauthorized) {
                    assertFailsWith<CancellationException> { sessions.openInboxItem(active, page, page.items.single(), visible::get) }
                } else {
                    assertFailsWith<CoreHttpException> { sessions.openInboxItem(active, page, page.items.single(), visible::get) }
                }
                assertSame(active, sessions.state.value)
                assertEquals(listOf(list, resolve), fixture.targets)
            }
        }
    }

    @Test
    fun sessionReplacementDuringResolveFencesCanonicalReadAndOldPageAcknowledgment() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture {
            if (it == list) Reply(page(cursor = cursor)) else {
                waiting.complete(Unit)
                release.await()
                Reply(approvalResolution())
            }
        }
        fixture.use { sessions, active ->
            val page = sessions.inboxPage(active) { true }
            val row = page.items.single()
            assertFailsWith<CoreProtocolException> { sessions.openInboxItem(active, page, row.copy()) { true } }
            val opening = launch {
                assertFailsWith<CancellationException> { sessions.openInboxItem(active, page, row) { true } }
            }
            waiting.await()
            val replacement = sessions.establish(nextToken)
            release.complete(Unit)
            opening.join()
            assertFailsWith<CancellationException> { sessions.acknowledgeInboxItem(replacement, page, row) { true } }
            assertFailsWith<CancellationException> { sessions.inboxPage(replacement, page) { true } }
            assertEquals(listOf(list, resolve), fixture.targets)
        }
    }

    @Test
    fun closingDuringAcknowledgeDiscardsLateSuccessAndDoesNotReplayAutomatically() = runTest {
        val visible = AtomicBoolean(true)
        val fixture = Fixture {
            if (it == list) Reply(page()) else {
                visible.set(false)
                Reply("""{"notificationId":"notice-1","acknowledgedAt":"$ackAt"}""")
            }
        }
        fixture.use { sessions, active ->
            val page = sessions.inboxPage(active) { true }
            val row = page.items.single()
            assertFailsWith<CancellationException> { sessions.acknowledgeInboxItem(active, page, row, visible::get) }
            assertNull(row.acknowledgedAt)
            assertEquals(listOf(Request(list), Request(ack, "{}")), fixture.requests.toList())
        }
    }
}
