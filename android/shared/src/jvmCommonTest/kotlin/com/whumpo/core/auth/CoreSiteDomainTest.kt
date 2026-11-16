package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreDomainCommand
import com.whumpo.core.api.CoreDomainOperationKind
import com.whumpo.core.api.CoreDomainStatus
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.decodeDomainClaims
import com.whumpo.core.api.decodeDomainDetail
import com.whumpo.core.api.decodeDomainOperation
import com.whumpo.core.api.normalizeCoreDomainHostname
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CoreSiteDomainTest {
    private val origin = "https://domains.example.test"
    private val bearer = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val org = "synthetic-org"
    private val project = "synthetic-project"
    private val hostname = "synthetic-example.com"
    private val path = "/v1/projects/$project/domain-claims"
    private val claimId = "12345678-1234-4234-8234-123456789abc"
    private val detailPath = "$path/$claimId"
    private val expiry = "2026-11-23T10:00:00.000Z"
    private val start = Instant.parse("2026-11-16T10:00:00.000Z")
    private val examples by lazy {
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "tests/fixtures/site-domains-v1/site-domains-examples.json") }.first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private val awaiting get() = examples.getValue("detail").jsonObject.getValue("claim").jsonObject
    private val proof get() = examples.getValue("operation").jsonObject.getValue("claim").jsonObject

    @Test
    fun canonicalExamplesAndCloseEveryWireShape() {
        val claim = decodeDomainDetail(examples.getValue("detail").toString(), org, project, claimId)
        val normalized = assertNotNull(normalizeCoreDomainHostname(" Synthetic-Example.COM "))
        assertEquals(hostname, normalized)
        assertEquals(JsonPrimitive(hostname), Json.parseToJsonElement(CoreDomainCommand.Prepare(normalized).encode()).jsonObject["hostname"])
        assertEquals(examples.getValue("generationRequest"),
            Json.parseToJsonElement(CoreDomainCommand.Existing(CoreDomainOperationKind.Check, claim).encode()))
        listOf("https://example.com", "*.example.com", "localhost", "127.0.0.1", "K.example.com", "a-.-b.test").forEach {
            assertNull(normalizeCoreDomainHostname(it))
        }
        assertEquals(listOf(claim), decodeDomainClaims(examples.getValue("list").toString(), org, project))
        val confirmed = decodeDomainOperation(examples.getValue("operation").toString(), org, project,
            CoreDomainCommand.Existing(CoreDomainOperationKind.Check, claim))
        assertEquals(CoreDomainStatus.OwnershipVerified, confirmed.statusAt(start))
        assertEquals(CoreDomainStatus.AwaitingDns, confirmed.statusAt(assertNotNull(confirmed.evidenceValidUntil)))
        assertEquals(CoreDomainStatus.Expired, confirmed.statusAt(confirmed.expiresAt))

        val detail = examples.getValue("detail").toString()
        listOf(
            detail.dropLast(1) + ",\"extra\":null}",
            detail.replace("\"type\":\"TXT\"", "\"type\":\"TXT\",\"extra\":true"),
            detail.replace("\"generation\":1", "\"generation\":\"1\""),
            detail.replace("_whumpo.$hostname", "_whumpo.other.example"),
            detail.replace("awaiting_dns", "connected"),
            detail.replace(expiry, "tomorrow"),
            detail.replace(org, "foreign-org"),
            detail.replace(project, "foreign-project"),
            detail.replace(claimId, "00000000-0000-4000-8000-000000000005"),
        ).forEach { invalid -> assertFailsWith<CoreProtocolException> { decodeDomainDetail(invalid, org, project, claimId) } }
        assertFailsWith<CoreProtocolException> { decodeDomainClaims(list(awaiting, awaiting), org, project) }
        assertFailsWith<CoreProtocolException> {
            decodeDomainOperation(operation(awaiting, 1, "proof_missing"), org, project, CoreDomainCommand.Prepare(hostname))
        }
        assertFailsWith<CoreProtocolException> {
            decodeDomainOperation(operation(proof.changed("evidenceValidUntil", JsonPrimitive("2026-11-24T00:00:00Z")),
                1, "ownership_verified"), org, project, CoreDomainCommand.Existing(CoreDomainOperationKind.Check, claim))
        }
    }

    @Test
    fun lostPrepareKeepsExactBytesAndAcknowledgedReplyNeedsMatchingReadback() = runTest {
        val writes = mutableListOf<Write>()
        var checks = 0
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(project))
                path -> if (request.method == HttpMethod.Get) json(list()) else {
                    writes += request.write()
                    if (writes.size == 1) fail(503) else json(operation(awaiting, 1))
                }
                detailPath -> {
                    checks++
                    if (checks == 1) json(detail(awaiting.changed("hostname", JsonPrimitive("other.example"))
                        .changed("record", awaiting.getValue("record").jsonObject.changed("name", JsonPrimitive("_whumpo.other.example")))))
                    else json(detail(awaiting))
                }
                else -> error("Unexpected fixture request")
            }
        }) { _, controller ->
            controller.refresh()
            assertTrue(assertNotNull(controller.state.value.project).fixed)
            controller.prepare("Synthetic-Example.COM")
            assertTrue(controller.state.value.pending)
            assertFalse(controller.state.value.readbackOnly)
            assertEquals(0, checks)
            controller.refresh()
            assertEquals(1, writes.size)
            controller.retry()
            assertEquals(writes.first(), writes[1])
            assertEquals(Write(path, """{"hostname":"$hostname"}""", writes.first().key), writes.first())
            assertTrue(controller.state.value.readbackOnly)
            assertNull(controller.state.value.claim)
            controller.retry()
            assertEquals(2, writes.size)
            assertEquals(hostname, controller.state.value.claim?.hostname)
            assertFalse(controller.state.value.pending)
        }
    }

    @Test
    fun acknowledgedRenewRejectsOlderGenerationAlteredTxtAndCheckedAtRegression() = runTest {
        val writes = mutableListOf<Write>()
        val renewed = proof.changed("generation", JsonPrimitive(2))
            .changed("record", proof.getValue("record").jsonObject.changed("value", JsonPrimitive("whumpo-verification=" + "2".repeat(64))))
            .changed("expiresAt", JsonPrimitive("2026-11-30T10:00:00Z"))
            .changed("checkedAt", JsonPrimitive("2026-11-23T10:01:00Z"))
            .changed("evidenceValidUntil", JsonPrimitive("2026-11-24T10:01:00Z"))
        var read = 0
        fixture(now = Instant.parse("2026-11-23T10:01:00Z"), routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(project))
                path -> json(list(awaiting))
                detailPath -> json(detail(when (++read) {
                    1, 2 -> awaiting
                    3 -> renewed.changed("record", awaiting.getValue("record"))
                    4 -> renewed.changed("expiresAt", JsonPrimitive("2026-11-29T10:00:00Z"))
                    5 -> renewed.changed("checkedAt", JsonPrimitive("2026-11-23T10:00:59Z"))
                    else -> renewed.changed("generation", JsonPrimitive(3))
                }))
                "$detailPath/renew" -> {
                    writes += request.write()
                    json(operation(renewed, 2))
                }
                else -> error("Unexpected fixture request")
            }
        }) { _, controller ->
            controller.refresh()
            controller.inspect(controller.state.value.claims.single())
            val old = assertNotNull(controller.state.value.claim)
            controller.check(old)
            assertTrue(writes.isEmpty())
            controller.renew(old)
            repeat(4) { index ->
                assertTrue(controller.state.value.pending)
                assertEquals(DomainNotice.CheckSaved, controller.state.value.notice)
                assertNull(controller.state.value.claim)
                assertEquals(1, writes.size)
                controller.retry()
                assertEquals(index + 3, read)
            }
            assertEquals("""{"generation":1}""", writes.single().body)
            assertEquals(3L, controller.state.value.claim?.generation)
            assertFalse(controller.state.value.pending)
        }
    }

    @Test
    fun deniedReadErasesInstructionsButUncertainRetryRetainsOriginalOperation() = runTest {
        val writes = mutableListOf<Write>()
        var readDenied = false
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(project))
                path -> json(list(awaiting))
                detailPath -> if (readDenied) fail(423) else json(detail(awaiting))
                "$detailPath/check" -> {
                    writes += request.write()
                    if (writes.size == 1) fail(403) else json(operation(awaiting, 1, "resolver_unknown"))
                }
                else -> error("Unexpected fixture request")
            }
        }) { _, controller ->
            controller.refresh()
            controller.inspect(controller.state.value.claims.single())
            readDenied = true
            controller.check(assertNotNull(controller.state.value.claim))
            assertEquals(DomainNotice.Denied, controller.state.value.notice)
            assertNull(controller.state.value.project)
            assertNull(controller.state.value.claim)
            assertTrue(controller.state.value.claims.isEmpty())
            assertTrue(controller.state.value.pending)
            assertFalse(controller.state.value.readbackOnly)
            controller.retry()
            assertEquals(writes[0], writes[1])
            assertEquals(DomainNotice.Denied, controller.state.value.notice)
            assertTrue(controller.state.value.readbackOnly)
            readDenied = false
            controller.retry()
            assertEquals(2, writes.size)
            assertEquals(hostname, controller.state.value.claim?.hostname)
        }
    }

    @Test
    fun legacyOwnerSelectsExplicitProjectAndConflictOnlyRereadsClaims() = runTest {
        var lists = 0
        var writes = 0
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness())
                "/v1/workspaces" -> json("""{"items":[{"id":"workspace","name":"Studio"}],"nextCursor":null}""")
                "/v1/projects" -> json("""{"items":[{"id":"$project","name":"Site","workspaceId":"workspace"}],"nextCursor":null}""")
                path -> if (request.method == HttpMethod.Get) {
                    lists++
                    if (lists == 2) fail(503) else json(list(awaiting))
                } else {
                    writes++
                    fail(409)
                }
                else -> error("Unexpected fixture request")
            }
        }) { _, controller ->
            controller.refresh()
            assertNull(controller.state.value.project)
            assertEquals(0, lists)
            controller.chooseWorkspace(assertNotNull(controller.state.value.workspaceChoices).items.single())
            assertEquals(0, lists)
            controller.chooseProject(assertNotNull(controller.state.value.projectChoices).items.single())
            assertEquals("Site", controller.state.value.project?.name)
            controller.prepare(hostname)
            assertTrue(controller.state.value.readbackOnly)
            controller.retry()
            assertEquals(3, lists)
            assertEquals(1, writes)
            assertEquals(DomainNotice.Changed, controller.state.value.notice)
            assertEquals(hostname, controller.state.value.claims.single().hostname)
        }
    }

    @Test
    fun domainOperationCancelsAndCannotReplayAcrossOrganizationOrForViewer() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var writes = 0
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(project))
                path -> if (request.method == HttpMethod.Get) json(list()) else {
                    writes++
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                else -> error("Unexpected fixture request")
            }
        }) { sessions, controller ->
            controller.refresh()
            val operation = launch { controller.prepare(hostname) }
            started.await()
            val viewer = sessions.switchOrganization("other-org")
            operation.join()
            stopped.await()
            controller.retry()
            CoreSiteDomainController(sessions, viewer).refresh()
            assertEquals(1, writes)
            assertNull(controller.state.value.claim)
        }
    }

    private fun readiness(projectId: String? = null) = buildJsonObject {
        put("orgId", org)
        put("ready", true)
        projectId?.let { put("projectId", it) }
    }.toString()

    private fun detail(body: JsonObject) = """{"schemaVersion":1,"orgId":"$org","claim":$body}"""
    private fun list(vararg body: JsonObject) =
        """{"schemaVersion":1,"orgId":"$org","claims":[${body.joinToString(",")}]}"""
    private fun operation(body: JsonObject, generation: Long, observation: String? = null) =
        """{"schemaVersion":1,"orgId":"$org","operationId":"12345678-1234-4234-8234-987654321abc","operationGeneration":$generation,"observation":${observation?.let { "\"$it\"" } ?: "null"},"claim":$body}"""

    private fun JsonObject.changed(field: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(toMutableMap().apply { put(field, value) })

    private suspend fun fixture(
        now: Instant = start,
        routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        test: suspend (CoreOrganizationSessionController, CoreSiteDomainController) -> Unit,
    ) {
        val engine = MockEngine { request ->
            val other = request.headers[HttpHeaders.Authorization] == "Bearer $replacement"
            val userOrg = if (other) "other-org" else org
            val role = if (other) "viewer" else "owner"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> json("""{"sessionId":"session-$userOrg","orgId":"$userOrg","userId":"owner","role":"$role","expiresAt":60001}""")
                "/v1/auth/session/switch" -> json("""{"token":"$replacement","expiresAt":60001}""")
                else -> {
                    assertEquals("domains.example.test", request.url.host)
                    assertEquals("Bearer $bearer", request.headers[HttpHeaders.Authorization])
                    assertTrue(request.url.parameters.names().let { it.isEmpty() || request.url.encodedPath == "/v1/projects" ||
                        request.url.encodedPath == "/v1/workspaces" })
                    routes(request)
                }
            }
        }
        CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin))).use { api ->
            val sessions = CoreOrganizationSessionController(api) { 1L }
            test(sessions, CoreSiteDomainController(sessions, sessions.establish(bearer)) { now })
        }
    }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.fail(status: Int) = respond("{}", HttpStatusCode.fromValue(status))

    private data class Write(val path: String, val body: String, val key: String)
    private suspend fun HttpRequestData.write() = Write(url.encodedPath,
        body.toByteArray().decodeToString(), checkNotNull(headers["Idempotency-Key"]))
}
