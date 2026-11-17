package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHostnameStatus
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.decodeHostnameStatus
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CoreSiteHostnameTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val bearer = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val project = "project-1"
    private val org = "org-1"
    private val path = "/v1/projects/$project/hostname-attachment"
    private val domainPath = "/v1/projects/$project/domain-claims"
    private val host = "synthetic-example.com"
    private val claim = "12345678-1234-4234-8234-123456789abc"
    private val digest = "a".repeat(64)
    private val reviewDigest = "b".repeat(64)
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val deadline = Instant.parse("2026-11-16T10:02:00Z")
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun session(role: String = "owner") =
        """{"sessionId":"session-1","orgId":"$org","userId":"user-1","role":"$role","expiresAt":9000}"""

    private fun attachment(status: String, revision: Int = 1, generation: Int = revision) =
        """{"id":"route-1","hostname":"$host","generation":$generation,"revision":$revision,"status":"$status"}"""

    private fun envelope(current: String = "null", tail: String = "") =
        """{"schemaVersion":1,"orgId":"$org","projectId":"$project","attachment":$current$tail}"""

    private fun witness(version: Int = 2) = """
        {"hostname":"$host","claimId":"$claim","claimGeneration":1,"checkId":"check-1",
         "targetId":"target-1","targetVersion":$version,"effectId":"effect-$version","artifactDigest":"$digest","pageManifestDigest":null}
    """.trimIndent()

    private fun review(current: String = "null", version: Int = 2) =
        envelope(current, ""","review":{"witness":${witness(version)},"reviewDigest":"$reviewDigest"}""")

    private fun ack(current: String, event: String = "reserved", generation: Int = 1) = envelope(current,
        ""","receipt":{"id":"receipt-1","routeId":"route-1","generation":$generation,"revision":$generation,"phase":"$event"}""")

    private data class Write(val path: String, val key: String?, val body: String)

    private inner class Fixture {
        var clock = now
        var attachment = "null"
        var publicationVersion = 2
        var role = "owner"
        var available = true
        var policy = true
        var readStatus: suspend () -> Int = { 200 }
        var send: (Write) -> Pair<Int, String> = { 200 to ack(this.attachment) }
        val requests = mutableListOf<String>()
        val writes = mutableListOf<Write>()
        val reviews = mutableListOf<Write>()

        val engine = MockEngine { request ->
            val endpoint = request.url.encodedPath
            requests += endpoint
            assertTrue(request.headers[HttpHeaders.Authorization] in listOf("Bearer $bearer", "Bearer $replacement"))
            assertNull(request.headers[HttpHeaders.Origin])
            when (endpoint) {
                "/v1/auth/session" -> respond(session(role))
                "/v1/organization/readiness" -> json("""{"orgId":"$org","ready":true,"projectId":"$project"}""")
                path -> json(envelope(attachment), readStatus())
                domainPath -> json("""
                    {"schemaVersion":1,"orgId":"$org","claims":[{
                      "id":"$claim","projectId":"$project","hostname":"$host","generation":1,"status":"ownership_verified",
                      "record":{"type":"TXT","name":"_whumpo.$host","value":"whumpo-verification=$digest"},
                      "expiresAt":"2026-11-17T10:00:00Z","checkedAt":"$now","evidenceValidUntil":"$deadline"
                    }]}
                    """.trimIndent())
                "$path/review" -> {
                    reviews += request.write()
                    if (policy) json(review(attachment, publicationVersion)) else json("{}", 423)
                }
                "/v1/site-targets" -> {
                    assertEquals(project, request.url.parameters["projectId"])
                    if (!available) json("{}", 404) else json("""
                        {"id":"target-1","orgId":"$org","projectId":"$project","slug":"site-00000000-0000-0000-0000-000000000001",
                        "version":$publicationVersion,"activeEffectId":"effect-$publicationVersion"}
                        """.trimIndent())
                }
                "/v1/site-targets/target-1/history" -> {
                    assertEquals("1", request.url.parameters["limit"])
                    json("""
                        {"items":[{"operation":"sites.publish@1","buildId":"build-1","receipt":{
                        "orgId":"$org","targetId":"target-1","targetVersion":$publicationVersion,"effectId":"effect-$publicationVersion",
                        "artifactDigest":"$digest","artifactBytes":100,"confirmedAt":"2026-11-16T10:00:00.000Z"}}]}
                        """.trimIndent())
                }
                "$path/attach", "$path/route-1/resume", "$path/route-1/disable" -> {
                    val write = request.write()
                    assertNotNull(write.key)
                    writes += write
                    val (status, response) = send(write)
                    json(response, status)
                }
                else -> error("Unexpected request $endpoint")
            }
        }

        private suspend fun HttpRequestData.write(): Write {
            assertEquals(HttpMethod.Post, method)
            assertTrue(url.parameters.isEmpty())
            return Write(url.encodedPath, headers["Idempotency-Key"], body.toByteArray().decodeToString())
        }

        private fun MockRequestHandleScope.json(body: String, status: Int = 200): HttpResponseData =
            respond(body, HttpStatusCode.fromValue(status), headers)
    }

    private suspend fun exercise(fixture: Fixture, block: suspend (CoreOrganizationSessionController, CoreSiteHostnameController) -> Unit) {
        CoreApiClient(fixture.engine, origin).use { api ->
            val sessions = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreSiteHostnameController(sessions, sessions.establish(bearer)) { fixture.clock }
            try {
                block(sessions, controller)
            } finally {
                controller.retire()
            }
        }
    }

    private suspend fun CoreSiteHostnameController.prepare(): HostnameReview {
        loadClaims()
        reviewClaim(assertNotNull(state.value.claims).single())
        return assertNotNull(state.value.review)
    }

    @Test
    fun exactAttachRetryAndAcknowledgedReadbackUseDifferentActions() = runTest {
        val fixture = Fixture()
        exercise(fixture) { _, controller ->
            controller.refresh()
            assertTrue(controller.state.value.loaded)
            assertNull(controller.state.value.attachment)
            assertEquals(listOf("/v1/auth/session", "/v1/organization/readiness", path), fixture.requests)
            val reviewed = controller.prepare()
            assertEquals(HostnameAction.Attach, reviewed.action)
            assertEquals(2L, reviewed.publicationVersion)
            assertEquals(Write("$path/review", null,
                """{"claimId":"$claim","claimGeneration":1,"targetId":"target-1","targetVersion":2}"""), fixture.reviews.single())
            fixture.send = { 503 to "{}" }
            controller.confirm(reviewed)
            assertEquals(HostnamePending.Retry, controller.state.value.pending)
            fixture.send = {
                fixture.attachment = attachment("serving_eligible", revision = 2, generation = 1)
                fixture.readStatus = { 503 }
                200 to ack(fixture.attachment)
            }
            controller.retry()
            assertEquals(listOf(fixture.writes.first(), fixture.writes.first()), fixture.writes)
            val body = Json.parseToJsonElement(fixture.writes.first().body).jsonObject
            assertEquals(Json.parseToJsonElement(witness()), body["witness"])
            assertEquals(setOf("witness", "reviewDigest"), body.keys)
            assertEquals(HostnamePending.CheckStatus, controller.state.value.pending)
            assertNull(controller.state.value.attachment)
            controller.retry()
            assertEquals(2, fixture.writes.size)
            fixture.readStatus = { 200 }
            controller.checkStatus()
            assertEquals(HostnameNotice.Recorded, controller.state.value.notice)
            assertEquals(CoreHostnameStatus.ServingEligible, controller.state.value.attachment?.status)
            assertEquals(2, fixture.writes.size)
        }
    }

    @Test
    fun disableSurvivesPolicyOffAndTheUnrelatedProofDeadline() = runTest {
        val fixture = Fixture().apply {
            attachment = this@CoreSiteHostnameTest.attachment("reproof_required")
            policy = false
        }
        exercise(fixture) { _, controller ->
            controller.refresh()
            controller.loadClaims()
            controller.reviewClaim(assertNotNull(controller.state.value.claims).single())
            assertTrue(controller.state.value.policyUnavailable)
            assertEquals(CoreHostnameStatus.ReproofRequired, controller.state.value.attachment?.status)
            controller.reviewDisable()
            val disable = assertNotNull(controller.state.value.review)
            assertEquals(HostnameAction.Disable, disable.action)
            fixture.clock = deadline
            fixture.available = false
            controller.expireReview()
            assertEquals(disable, controller.state.value.review)
            val historyReads = fixture.requests.count { it.startsWith("/v1/site-targets") }
            fixture.send = {
                fixture.attachment = attachment("detaching", 2)
                200 to ack(fixture.attachment, "detaching", 2)
            }
            controller.confirm(disable)
            assertEquals("""{"expectedRevision":1}""", fixture.writes.single().body)
            assertEquals("$path/route-1/disable", fixture.writes.single().path)
            assertEquals(historyReads, fixture.requests.count { it.startsWith("/v1/site-targets") })
            assertEquals(CoreHostnameStatus.Detaching, controller.state.value.attachment?.status)
        }
    }

    @Test
    fun stalePublicationCannotResumeAndConflictUsesCanonicalStatus() = runTest {
        val fixture = Fixture().apply { attachment = this@CoreSiteHostnameTest.attachment("reproof_required") }
        exercise(fixture) { _, controller ->
            controller.refresh()
            val first = controller.prepare()
            fixture.publicationVersion = 3
            controller.confirm(first)
            assertTrue(fixture.writes.isEmpty())
            assertEquals(HostnameNotice.Changed, controller.state.value.notice)
            val second = controller.prepare()
            assertEquals(HostnameAction.Resume, second.action)
            fixture.send = {
                fixture.attachment = attachment("detaching", 2)
                409 to "{}"
            }
            controller.confirm(second)
            assertEquals("$path/route-1/resume", fixture.writes.single().path)
            val body = Json.parseToJsonElement(fixture.writes.single().body).jsonObject
            assertEquals(Json.parseToJsonElement(witness(3)), body["witness"])
            assertTrue("expectedRevision" in body)
            assertEquals(HostnameNotice.Changed, controller.state.value.notice)
            assertEquals(CoreHostnameStatus.Detaching, controller.state.value.attachment?.status)
            assertNull(controller.state.value.pending)
        }
    }

    @Test
    fun expiryAndReplacementNeverReplayTheOldAttempt() = runTest {
        val fixture = Fixture()
        exercise(fixture) { sessions, controller ->
            controller.refresh()
            val reviewed = controller.prepare()
            fixture.send = { 503 to "{}" }
            controller.confirm(reviewed)
            fixture.clock = deadline
            controller.expireReview()
            assertEquals(HostnamePending.CheckStatus, controller.state.value.pending)
            controller.retry()
            assertEquals(1, fixture.writes.size)
            val started = CompletableDeferred<Unit>()
            fixture.readStatus = { started.complete(Unit); awaitCancellation() }
            val status = launch { controller.checkStatus() }
            started.await()
            val newActive = sessions.establish(replacement)
            status.join()
            assertTrue(status.isCancelled)
            val requests = fixture.requests.size
            controller.retry()
            controller.checkStatus()
            assertEquals(requests, fixture.requests.size)
            controller.retire()
            assertFalse(controller.state.value.loaded)
            fixture.readStatus = { 200 }
            fixture.attachment = attachment("reserved")
            val next = CoreSiteHostnameController(sessions, newActive)
            next.refresh()
            assertEquals(CoreHostnameStatus.Reserved, next.state.value.attachment?.status)
            assertEquals(1, fixture.writes.size)
            next.retire()
        }
    }

    @Test
    fun statusRejectsForeignFieldsAndNonCanonicalPhaseValues() {
        val good = envelope(attachment("reserved"))
        assertEquals(host, decodeHostnameStatus(good, org, project)?.hostname)
        for (raw in listOf(
            good.replace(org, "other-org"),
            good.replace(project, "other-project"),
            good.replace("reserved", "routed"),
            good.replace(host, host.uppercase()),
            good.replace("\"revision\":1", "\"revision\":\"1\""),
            good.dropLast(1) + ",\"provider\":null}",
        )) assertFailsWith<CoreProtocolException> { decodeHostnameStatus(raw, org, project) }
    }
}
