package com.whumpo.core.auth

import com.whumpo.core.api.AccountSecurityState
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreEntitlementStatus
import com.whumpo.core.api.CoreInvitationIssue
import com.whumpo.core.api.CoreInvitationPage
import com.whumpo.core.api.CoreInvitationStatus
import com.whumpo.core.api.CoreMembershipMode
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CorePersonInvitation
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.RecoveryKind
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityAuthority
import com.whumpo.core.api.SecurityGrant
import com.whumpo.core.api.SecurityInvitationPreview
import com.whumpo.core.api.SecurityPhase
import com.whumpo.core.api.SecurityProof
import com.whumpo.core.api.SecurityPurpose
import com.whumpo.core.api.SecurityRecovery
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreOwnerInvitationsTest {
    private val createdAt = "2026-11-17T00:00:00.000Z"
    private val expiresAt = "2026-11-17T12:00:00.000Z"
    private val start = Instant.parse(createdAt).toEpochMilli()
    private val original = CorePersonInvitation("invite-1", "org-1", "user-1", CoreRole.viewer, 1,
        createdAt, expiresAt, CoreInvitationStatus.pending)
    private val code = "wi2." + "A".repeat(43)
    private val bearer = "A".repeat(43)
    private val grantToken = "ag1." + "B".repeat(42) + "A"
    private data class Write(val path: String, val key: String?, val body: String)

    private inner class Fixture(role: CoreRole = CoreRole.owner) : AutoCloseable {
        var now = start + 1
        var identity = CoreSession("session-1", "org-1", "user-1", role, start + 100_000)
        var reply = original
        var preview = SecurityInvitationPreview(original.invitationId, original.orgId, "Example", original.role, expiresAt)
        var available = 2L
        var issueLost = false
        var revokeLost = false
        var unknownIssueProperty = false
        var page = CoreInvitationPage(listOf(original), null)
        var previewHold: suspend () -> Unit = {}
        var historyRequests = 0
        var previewRequests = 0
        val writes = mutableListOf<Write>()
        private val responseHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        private val grant = SecurityGrant(
            SecurityAuthority("account_grant", grantToken, SecurityPurpose.Account, start + 90_000),
            AccountSecurityState(1, "user-1", null, SecurityPurpose.Account, SecurityPhase.Ready, 1, 1,
                null, null, null, emptyList(), SecurityRecovery(RecoveryKind.None, 0, null, null),
                SecurityProof.PasswordTotp, start + 10_000, start + 90_000, SecurityAccess.Available, null),
        )

        private val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val auth = request.headers[HttpHeaders.Authorization]
            fun workspace() = assertEquals("Bearer $bearer", auth)
            fun account() = assertEquals("AccountGrant $grantToken", auth)
            var status = HttpStatusCode.OK
            val body = when (path) {
                "/v1/auth/session" -> {
                    workspace()
                    Json.encodeToString(identity)
                }
                "/v1/organization/entitlements" -> {
                    workspace()
                    Json.encodeToString(CoreOrganizationEntitlements(identity.orgId, CoreEntitlementStatus.active,
                        CoreMembershipMode.business_team, 4, 1, 1, available, available > 0, true, 1, 1, null))
                }
                "/v1/account-security/exchange" -> {
                    workspace()
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("{\"version\":1}", request.body.toByteArray().decodeToString())
                    Json.encodeToString(grant)
                }
                "/v1/account-security/invitations/list" -> {
                    account()
                    historyRequests++
                    Json.encodeToString(page)
                }
                "/v1/account-security/invitations/issue" -> {
                    account()
                    val first = writes.none { it.path == path }
                    writes += Write(path, request.headers["Idempotency-Key"], request.body.toByteArray().decodeToString())
                    if (issueLost) {
                        issueLost = false
                        status = HttpStatusCode.ServiceUnavailable
                    }
                    val result = Json.encodeToString(CoreInvitationIssue(reply, code.takeIf { first }))
                    if (unknownIssueProperty) result.dropLast(1) + ",\"unexpected\":true}" else result
                }
                "/v1/account-security/invitations/preview" -> {
                    assertNull(auth)
                    assertEquals("{\"version\":1,\"invitationCode\":\"$code\"}", request.body.toByteArray().decodeToString())
                    previewRequests++
                    previewHold()
                    Json.encodeToString(preview)
                }
                "/v1/account-security/invitations/revoke" -> {
                    account()
                    writes += Write(path, request.headers["Idempotency-Key"], request.body.toByteArray().decodeToString())
                    if (revokeLost) {
                        revokeLost = false
                        status = HttpStatusCode.ServiceUnavailable
                    }
                    Json.encodeToString(original.copy(version = 2, status = CoreInvitationStatus.revoked, revokedAt = createdAt))
                }
                else -> error("Unexpected invitation route")
            }
            respond(body, status, responseHeaders)
        }
        private val origin = "https://work.example.test"
        private val client = CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin)))
        val sessions = CoreOrganizationSessionController(client) { now }
        suspend fun controller() = CoreOwnerInvitationsController(sessions, sessions.establish(bearer), now = { now })
        override fun close() = client.close()
    }

    @Test
    fun freshIssueUsesExactPreviewWithoutLoadingHistoryAndCopiesOnlyExplicitly() = runTest {
        Fixture().use { fixture ->
            val control = fixture.controller()
            control.issue(CoreRole.viewer)
            assertEquals(0, fixture.historyRequests)
            assertEquals(1, fixture.previewRequests)
            assertEquals("{\"version\":1,\"orgId\":\"org-1\",\"role\":\"viewer\"}", fixture.writes.single().body)
            assertNotNull(fixture.writes.single().key)
            assertEquals(start + 10_000, control.state.value.codeExpiresAt)
            assertFalse(control.state.value.toString().contains(code))
            val copied = mutableListOf<String>()
            control.copyCode(copied::add)
            control.copyCode(copied::add)
            assertEquals(listOf(code), copied)
            assertNull(control.state.value.codeExpiresAt)
        }
    }

    @Test
    fun uncertainIssueRetriesByteIdenticallyAndReplayDoesNotDisclose() = runTest {
        Fixture().use { fixture ->
            val control = fixture.controller()
            fixture.issueLost = true
            control.issue(CoreRole.viewer)
            assertTrue(control.state.value.canRetry)
            assertNull(control.state.value.codeExpiresAt)
            fixture.available = 0
            control.issue(CoreRole.operator)
            control.retry()
            assertEquals(2, fixture.writes.size)
            assertEquals(fixture.writes.first(), fixture.writes.last())
            assertEquals(0, fixture.previewRequests)
            assertEquals(CoreInvitationNotice.IssuedWithoutCode, control.state.value.notice)
            assertFalse(control.state.value.canRetry)
        }
    }

    @Test
    fun malformedIssueOrForeignPreviewNeverExposesCode() = runTest {
        Fixture().use { fixture ->
            val control = fixture.controller()
            fixture.unknownIssueProperty = true
            control.issue(CoreRole.viewer)
            assertTrue(control.state.value.canRetry)
            assertEquals(0, fixture.previewRequests)
            assertNull(control.state.value.codeExpiresAt)
        }
        Fixture().use { fixture ->
            val control = fixture.controller()
            fixture.preview = fixture.preview.copy(orgId = "other-org")
            control.issue(CoreRole.viewer)
            assertEquals(1, fixture.previewRequests)
            assertNull(control.state.value.codeExpiresAt)
            assertTrue(control.state.value.invitations.isEmpty())
        }
    }

    @Test
    fun lateAuthorityChangeDuringPreviewHidesCodeAndCurrentData() = runTest {
        Fixture().use { fixture ->
            val control = fixture.controller()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            fixture.previewHold = { started.complete(Unit); finish.await() }
            val issue = launch { control.issue(CoreRole.viewer) }
            started.await()
            fixture.identity = fixture.identity.copy(role = CoreRole.viewer)
            finish.complete(Unit)
            issue.join()
            assertNull(control.state.value.codeExpiresAt)
            assertTrue(control.state.value.invitations.isEmpty())
            assertNull(control.state.value.seats)
        }
    }

    @Test
    fun codeExpiresAndRevocationRepeatsTheOriginalReviewedVersionAndKey() = runTest {
        Fixture().use { fixture ->
            val control = fixture.controller()
            control.issue(CoreRole.viewer)
            fixture.now = start + 10_000
            control.copyCode { error("Expired code copied") }
            assertNull(control.state.value.codeExpiresAt)
            control.loadHistory()
            fixture.revokeLost = true
            control.revoke(original.copy(version = 2))
            assertEquals(1, fixture.writes.size)
            control.revoke(original)
            assertTrue(control.state.value.canRetry)
            control.retry()
            assertEquals(fixture.writes[1], fixture.writes[2])
            assertEquals("{\"version\":1,\"orgId\":\"org-1\",\"invitationId\":\"invite-1\",\"expectedVersion\":1}",
                fixture.writes.last().body)
            assertEquals(CoreInvitationStatus.revoked, control.state.value.invitations.single().status)
        }
    }

    @Test
    fun ownerWithNoSeatAndViewerNeverSendWrites() = runTest {
        Fixture().use { fixture ->
            fixture.available = 0
            val control = fixture.controller()
            control.issue(CoreRole.viewer)
            assertEquals(CoreInvitationNotice.ReviewChanged, control.state.value.notice)
            assertTrue(fixture.writes.isEmpty())
        }
        Fixture(CoreRole.viewer).use { fixture ->
            fixture.controller().issue(CoreRole.viewer)
            assertTrue(fixture.writes.isEmpty())
        }
    }
}
