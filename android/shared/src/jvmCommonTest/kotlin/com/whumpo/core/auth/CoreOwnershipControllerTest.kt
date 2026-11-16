package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTransferStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreOwnershipControllerTest {
    private val base = "https://core.example.test"
    private val ownership = "/v1/organization/ownership"
    private val firstToken = "A".repeat(43)
    private val nextToken = "E".repeat(43)
    private val now = 1_767_225_610_000L
    private val expires = now + 60_000

    private fun session(actor: String, role: String, orgId: String) =
        """{"sessionId":"session-$orgId","orgId":"$orgId","userId":"$actor","role":"$role","expiresAt":$expires}"""

    private fun designation(
        owner: String? = "owner-1",
        version: Long? = 7,
        offer: String = "null",
        provenance: String = "transfer",
    ): String {
        val status = if (owner == null) "undesignated" else "designated"
        val primary = owner?.let { "\"$it\"" }
        val source = owner?.let { "\"$provenance\"" }
        return """{"orgId":"team","status":"$status","primaryOwnerUserId":$primary,"version":$version,"provenance":$source,"pendingTransfer":$offer}"""
    }

    private fun offer(
        target: String = "owner-2",
        status: String = "pending",
        version: Int = 1,
        extra: String = "",
    ) = """{"transferId":"offer-1","orgId":"team","sourceUserId":"owner-1","targetUserId":"$target","designationVersion":7,"targetMembershipVersion":2,"version":$version,"status":"$status","createdAt":"2026-01-01T00:00:00.000Z","expiresAt":"2026-01-01T01:00:00.000Z"$extra}"""

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))

    private suspend fun fixture(
        actor: String = "owner-1",
        role: String = "owner",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        test: suspend (CoreOrganizationSessionController, CoreOrganizationSessionController.State.Active, CoreOwnershipController) -> Unit,
    ) {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(actor, role,
                    if (request.headers[HttpHeaders.Authorization] == "Bearer $firstToken") "team" else "next-team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$nextToken","expiresAt":$expires}""")
                else -> {
                    assertEquals(base, "${request.url.protocol.name}://${request.url.host}")
                    handler(request)
                }
            }
        }
        CoreApiClient(engine, CoreOrigin.allowlisted(base, setOf(base))).use { api ->
            val sessions = CoreOrganizationSessionController(api) { now }
            val active = sessions.establish(firstToken)
            test(sessions, active, CoreOwnershipController(sessions, active) { now })
        }
    }

    @Test
    fun rejectsClosedSchemaViolationsAndForeignOrUnscopedDetails() = runTest {
        val valid = designation(offer = offer())
        val malformed = listOf(
            valid.replace("\"version\":7", "\"version\":\"7\""),
            valid.replace("\"version\":7", "\"version\":7.0"),
            valid.replace("\"version\":7", "\"version\":9007199254740992"),
            valid.replace("\"version\":7,", ""),
            valid.replace("\"version\":7", "\"version\":7,\"unknown\":true"),
            valid.replace("\"version\":1", "\"version\":1,\"unknown\":\"field\""),
            valid.replace("\"version\":1", "\"version\":1,\"acceptedAt\":null"),
            valid.replace("owner-2", "../owner-2"),
            valid.replace("owner-2", "owner-1"),
            valid.replace("offer-1", "offer/1"),
            valid.replace("team", "foreign-tenant"),
            valid.replace("designationVersion\":7", "designationVersion\":8"),
            valid.replace("2026-01-01T00:00:00.000Z", "2026-02-30T00:00:00.000Z"),
            valid.replace("2026-01-01T00:00:00.000Z", "2026-01-01T00:00:60.000Z"),
            valid.replace("2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00Z"),
            valid.replace("2026-01-01T01:00:00.000Z", "2026-01-03T01:00:00.000Z"),
            valid.replace("pending\"", "expired\""),
            valid.replace("provenance\":\"transfer", "provenance\":\"onboarding"),
            " ".repeat(65_536) + valid,
        )
        var reply = valid
        var contentType = ContentType.Application.Json.toString()
        fixture(handler = { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(ownership, request.url.encodedPath)
            assertEquals("Bearer $firstToken", request.headers[HttpHeaders.Authorization])
            assertTrue(request.url.parameters.isEmpty())
            respond(reply, headers = headersOf(HttpHeaders.ContentType, contentType))
        }) { sessions, active, _ ->
            assertEquals("owner-2", sessions.ownership(active).pendingTransfer?.targetUserId)
            for (body in malformed) {
                reply = body
                assertFailsWith<CoreProtocolException>(body.take(600)) { sessions.ownership(active) }
            }
            reply = valid
            contentType = ContentType.Text.Plain.toString()
            assertFailsWith<CoreProtocolException> { sessions.ownership(active) }
        }
        fixture(role = "viewer", handler = { json(valid) }) { sessions, active, controller ->
            assertFailsWith<CoreProtocolException> { sessions.ownership(active) }
            controller.refresh()
            assertNull(controller.state.value.snapshot)
            assertFalse(controller.canReview(CoreOwnershipAction.Claim))
        }
    }

    @Test
    fun replaysOnlyTheReviewedRequestAcrossRefreshAndRetainsOnRetryRefusal() = runTest {
        var current = designation()
        val requests = mutableListOf<SentRequest>()
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                ownership -> json(current)
                "/v1/memberships" -> {
                    assertEquals("100", request.url.parameters["limit"])
                    assertEquals(setOf("limit"), request.url.parameters.names())
                    json("""{"items":[{"userId":"owner-1","role":"owner","version":1},{"userId":"owner-2","role":"owner","version":2},{"userId":"owner-3","role":"owner","version":3,"revokedAt":"legacy"},{"userId":"owner-4","role":"operator","version":4}]}""")
                }
                "$ownership/transfers" -> {
                    requests += sent(request)
                    when (requests.size) {
                        1 -> respond("{}", HttpStatusCode.ServiceUnavailable)
                        2 -> respond("{}", HttpStatusCode.Conflict)
                        3 -> respond("{}", HttpStatusCode.Forbidden)
                        else -> json(offer())
                    }
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.refresh()
            controller.loadCandidates()
            val target = assertNotNull(controller.state.value.candidates).single()
            assertEquals("owner-2", target.userId)
            controller.review(CoreOwnershipAction.Offer, target)
            assertEquals(7L, controller.state.value.review?.designation?.version)
            controller.submit()
            assertTrue(controller.state.value.retry)
            assertEquals(1, requests.size)
            current = designation(owner = "owner-3", version = 8)
            controller.refresh()
            controller.submit()
            assertEquals(1, requests.size)
            controller.retry()
            assertTrue(controller.state.value.retry)
            controller.retry()
            assertTrue(controller.state.value.retry)
            assertEquals(CoreOwnershipNotice.Unconfirmed, controller.state.value.notice)
            controller.retry()
            assertFalse(controller.state.value.retry)
            assertEquals(CoreOwnershipNotice.Confirmed, controller.state.value.notice)
            assertEquals("owner-3", controller.state.value.snapshot?.designation?.primaryOwnerUserId)
            assertEquals(4, requests.size)
            assertEquals(1, requests.distinct().size)
            val request = requests.first()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("\"7\"", request.version)
            assertEquals("""{"targetUserId":"owner-2","targetMembershipVersion":2}""", request.body)
            assertNotNull(request.key)
        }
    }

    @Test
    fun refreshAndConflictRequireNewConsent() = runTest {
        var current = designation()
        val requests = mutableListOf<SentRequest>()
        val members = """{"items":[{"userId":"owner-1","role":"owner","version":1},{"userId":"owner-2","role":"owner","version":2}]}"""
        fixture(handler = { request ->
            when (request.url.encodedPath) {
                ownership -> json(current)
                "/v1/memberships" -> json(members)
                "$ownership/transfers" -> {
                    requests += sent(request)
                    respond("", HttpStatusCode.Conflict)
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.refresh()
            controller.loadCandidates()
            controller.review(CoreOwnershipAction.Offer, controller.state.value.candidates?.single())
            current = designation(version = 8)
            controller.refresh()
            assertNull(controller.state.value.review)
            assertEquals(CoreOwnershipNotice.ReviewChanged, controller.state.value.notice)
            controller.submit()
            assertTrue(requests.isEmpty())
            controller.loadCandidates()
            val candidate = controller.state.value.candidates?.single()
            controller.review(CoreOwnershipAction.Offer, candidate)
            controller.submit()
            assertEquals("\"8\"", requests.single().version)
            assertEquals(CoreOwnershipNotice.ReviewChanged, controller.state.value.notice)
            assertFalse(controller.state.value.retry)
            controller.submit()
            assertEquals(1, requests.size)
            controller.loadCandidates()
            controller.review(CoreOwnershipAction.Offer, candidate)
            controller.submit()
            assertNotEquals(requests[0].key, requests[1].key)
        }
    }

    @Test
    fun claimAndAcceptUseDistinctReceiptsAndExactPreconditions() = runTest {
        var view = designation(owner = null, version = null)
        val requests = mutableListOf<SentRequest>()
        fixture(actor = "owner-2", handler = { request ->
            when (request.url.encodedPath) {
                ownership -> json(view)
                "$ownership/claim" -> {
                    requests += sent(request)
                    view = designation(owner = "owner-2", version = 1, provenance = "owner_claim")
                    json(view)
                }
                "$ownership/transfers/offer-1" -> json(offer(status = "accepted", version = 2,
                    extra = ""","acceptedAt":"2026-01-01T00:00:10.000Z""""))
                "$ownership/transfers/offer-1/accept" -> {
                    requests += sent(request)
                    view = designation(owner = "owner-3", version = 9)
                    json(designation(owner = "owner-2", version = 8))
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.refresh()
            controller.review(CoreOwnershipAction.Claim)
            controller.submit()
            assertEquals("owner-2", controller.state.value.snapshot?.designation?.primaryOwnerUserId)
            assertNull(requests.single().version)
            assertEquals("{}", requests.single().body)
            view = designation(offer = offer())
            controller.refresh()
            controller.review(CoreOwnershipAction.Accept)
            controller.submit()
            assertEquals("\"1\"", requests.last().version)
            assertEquals("{}", requests.last().body)
            assertEquals("owner-3", controller.state.value.snapshot?.designation?.primaryOwnerUserId)
            assertEquals(CoreTransferStatus.accepted, controller.state.value.snapshot?.transfer?.status)
            assertFalse(controller.canReview(CoreOwnershipAction.Accept))
            assertFalse(controller.canReview(CoreOwnershipAction.Cancel))
            controller.refresh()
            assertNull(controller.state.value.snapshot?.transfer)
        }
    }

    @Test
    fun partyCanCancelInvalidatedTransferAndRejectsMismatchedReceipt() = runTest {
        var pending = true
        val requests = mutableListOf<SentRequest>()
        fixture(actor = "owner-2", handler = { request ->
            when (request.url.encodedPath) {
                ownership -> json(designation(offer = if (pending) offer() else "null"))
                "$ownership/transfers/offer-1" -> {
                    val body = if (requests.isEmpty()) offer(status = "invalidated")
                        else offer(status = "cancelled", version = 2, extra = ""","cancelledAt":"2026-01-01T00:00:10.000Z"""")
                    json(body)
                }
                "$ownership/transfers/offer-1/cancel" -> {
                    requests += sent(request)
                    json(offer(target = if (requests.size == 1) "owner-3" else "owner-2", status = "cancelled", version = 2,
                        extra = ""","cancelledAt":"2026-01-01T00:00:10.000Z""""))
                }
                else -> error("Unexpected route")
            }
        }) { _, _, controller ->
            controller.refresh()
            controller.review(CoreOwnershipAction.Accept)
            pending = false
            controller.refresh()
            assertNull(controller.state.value.review)
            assertFalse(controller.canReview(CoreOwnershipAction.Accept))
            controller.review(CoreOwnershipAction.Cancel)
            controller.submit()
            assertTrue(controller.state.value.retry)
            controller.retry()
            assertFalse(controller.state.value.retry)
            assertEquals(1, requests.distinct().size)
            assertEquals("{}", requests.first().body)
            assertNull(requests.first().version)
            assertEquals(CoreTransferStatus.cancelled, controller.state.value.snapshot?.transfer?.status)
            assertFalse(controller.canReview(CoreOwnershipAction.Accept))
            assertFalse(controller.canReview(CoreOwnershipAction.Cancel))
        }
    }

    @Test
    fun membershipPagingRequiresFullOrderedPagesIncludingActiveCaller() = runTest {
        val first = (1..100).joinToString(",") { index ->
            """{"userId":"member-${index.toString().padStart(3, '0')}","role":"viewer","version":1}"""
        }
        var second = """{"items":[{"userId":"owner-1","role":"owner","version":1},{"userId":"owner-2","role":"owner","version":2}]}"""
        val cursors = mutableListOf<String?>()
        fixture(handler = { request ->
            assertEquals("/v1/memberships", request.url.encodedPath)
            val cursor = request.url.parameters["cursor"]
            cursors += cursor
            json(if (cursor == null) """{"items":[$first],"nextCursor":"member-100"}""" else second)
        }) { sessions, active, _ ->
            assertEquals(listOf("owner-2"), sessions.ownershipCandidates(active).map { it.userId })
            assertEquals(listOf(null, "member-100"), cursors)
            second = """{"items":[{"userId":"member-100","role":"owner","version":1}]}"""
            assertFailsWith<CoreProtocolException> { sessions.ownershipCandidates(active) }
            second = """{"items":[{"userId":"owner-2","role":"owner","version":2}]}"""
            assertFailsWith<CoreProtocolException> { sessions.ownershipCandidates(active) }
            second = """{"items":[{"userId":"owner-1","role":"owner","version":1}],"nextCursor":"owner-1"}"""
            assertFailsWith<CoreProtocolException> { sessions.ownershipCandidates(active) }
        }
    }

    @Test
    fun delayedUnauthorizedFromOldScopeCannotClearReplacement() = runTest {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(handler = {
            arrived.complete(Unit)
            release.await()
            respond("", HttpStatusCode.Unauthorized)
        }) { sessions, active, controller ->
            val old = async { assertFailsWith<CancellationException> { controller.refresh() } }
            arrived.await()
            val next = sessions.switchOrganization("next-team")
            release.complete(Unit)
            old.await()
            assertEquals(next, sessions.state.value)
            assertNull(controller.state.value.snapshot)
            assertFailsWith<CancellationException> { sessions.ownership(active) }
        }
    }

    private data class SentRequest(val method: HttpMethod, val version: String?, val key: String?, val body: String)

    private suspend fun sent(request: HttpRequestData): SentRequest {
        assertTrue(request.url.parameters.isEmpty())
        assertEquals("application/json", request.body.contentType?.toString())
        return SentRequest(request.method, request.headers[HttpHeaders.IfMatch],
            request.headers["Idempotency-Key"], request.body.toByteArray().decodeToString())
    }
}
