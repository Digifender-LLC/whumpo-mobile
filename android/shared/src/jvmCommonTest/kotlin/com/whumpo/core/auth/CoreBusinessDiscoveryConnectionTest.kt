package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreDiscoveryActionId
import com.whumpo.core.api.CoreDiscoveryCommand
import com.whumpo.core.api.CoreDiscoveryEdit
import com.whumpo.core.api.CoreDiscoveryKey
import com.whumpo.core.api.CoreDiscoveryPlanStatus
import com.whumpo.core.api.CoreDiscoveryValue
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.coreDiscoveryCountries
import com.whumpo.core.api.decodeDiscoveryReceipt
import com.whumpo.core.api.decodeDiscoverySnapshot
import com.whumpo.core.api.encodeDiscovery
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreBusinessDiscoveryConnectionTest {
    private val origin = "https://discovery.example.test"
    private val owner = "A".repeat(43)
    private val otherOwner = "E".repeat(43)
    private val path = "/v1/onboarding/discovery"

    @Test
    fun publishedFixturesMatchTheTypedClientContract() {
        val schema = fixtureJson("business-discovery.schema.json").jsonObject.getValue("\$defs").jsonObject
        val examples = fixtureJson("business-discovery-examples.json").jsonObject
        val countries = fixtureJson("business-discovery-country-codes.json").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(countries, coreDiscoveryCountries)
        assertEquals(countries, schema.getValue("value-country").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(schema.getValue("key").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
            CoreDiscoveryKey.entries.map { it.code })
        assertEquals(schema.getValue("actionId").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
            CoreDiscoveryActionId.entries.map { it.code })
        CoreDiscoveryKey.entries.filter { it.choices.isNotEmpty() }.forEach { key ->
            assertEquals(schema.getValue("value-" + key.code).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }, key.choices)
        }
        val commands = examples.getValue("validCommands").jsonArray
        commands.forEach { fixture ->
            val body = fixture.jsonObject.getValue("body").jsonObject
            assertEquals(body, Json.parseToJsonElement(commandForFixture(body).encodeDiscovery()))
        }
        val responses = examples.getValue("examples").jsonObject
        assertEquals(CoreDiscoveryPlanStatus.Missing, decodeDiscoverySnapshot(responses.getValue("emptySnapshot").toString()).planStatus)
        assertEquals(CoreDiscoveryPlanStatus.Stale, decodeDiscoverySnapshot(responses.getValue("goalChangeSnapshot").toString()).planStatus)
        assertEquals(8L, decodeDiscoveryReceipt(responses.getValue("mutationReceipt").toString()).revision)
    }

    @Test
    fun closedResponseDecodersRejectUnexpectedFieldsAndWrongPrimitiveTypes() {
        val snapshot = snapshot()
        val malformed = listOf(
            snapshot.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            snapshot.replace("\"revision\":1", "\"revision\":true"),
            snapshot.replace("\"answers\":{}", "\"answers\":{\"ein\":{\"status\":\"declared\",\"value\":\"12-3456789\",\"source\":\"owner\",\"revision\":1}}"),
            snapshot.replace("\"answers\":{}", "\"answers\":{\"stage\":{\"status\":\"unknown\",\"value\":\"idea\",\"source\":\"owner\",\"revision\":1}}"),
            snapshot.replace("\"stage\",\"country\"", "\"stage\",\"stage\""),
            snapshot.replace("\"planStatus\":\"missing\"", "\"planStatus\":\"current\""),
            snapshot.dropLast(1) + ",\"unexpected\":null}",
            snapshot(plan = currentPlan.replace("\"requiresReview\":true", "\"requiresReview\":false")),
        )
        malformed.forEach { assertFailsWith<CoreProtocolException> { decodeDiscoverySnapshot(it) } }
        assertFailsWith<CoreProtocolException> { decodeDiscoveryReceipt(receipt().replace("[]", "[\"foreign\"]")) }
        assertEquals(CoreDiscoveryActionId.Goal, decodeDiscoverySnapshot(snapshot(plan = currentPlan)).plan?.recommendedNext)
    }

    @Test
    fun canonicalUnicodeWordBoundariesApplyOnReadAndWrite() = runTest {
        val prose = "Studio ſ123456789"
        fun answer(value: String) = CoreDiscoveryCommand.Answer(CoreDiscoveryKey.Offer, CoreDiscoveryValue.Declared(value))
        fun answers(value: String) =
            """{"offer":{"status":"declared","value":"$value","source":"owner","revision":2}}"""
        assertEquals(CoreDiscoveryValue.Declared(prose),
            decodeDiscoverySnapshot(snapshot(revision = 2, answers = answers(prose))).answers.getValue(CoreDiscoveryKey.Offer).value)
        assertFailsWith<CoreProtocolException> {
            decodeDiscoverySnapshot(snapshot(revision = 2, answers = answers("Studio 123456789")))
        }

        val writes = mutableListOf<Sent>()
        fixture(routes = { request ->
            if (request.method == HttpMethod.Post) {
                writes += sent(request)
                json(receipt(revision = 2))
            } else if (writes.isEmpty()) json(snapshot())
            else json(snapshot(revision = 2, answers = answers(prose)))
        }) { _, controller ->
            controller.refresh()
            controller.submit(answer("Studio 123456789"), 1)
            assertTrue(writes.isEmpty())
            controller.submit(answer(prose), 1)
            assertEquals(answer(prose).encodeDiscovery(), writes.single().body)
            assertEquals(CoreDiscoveryValue.Declared(prose),
                controller.state.value.snapshot?.answers?.get(CoreDiscoveryKey.Offer)?.value)
        }
    }

    @Test
    fun ambiguousWriteReusesExactBytesVersionKeyAndFetchesAfterAcknowledgment() = runTest {
        var committed = false
        var reads = 0
        val sent = mutableListOf<Sent>()
        fixture(routes = { request ->
            if (request.method == HttpMethod.Post) {
                sent += sent(request)
                when (sent.size) {
                    1 -> {
                        committed = true
                        fail(503)
                    }
                    2 -> fail(403)
                    else -> json(receipt(revision = 2, invalidated = "[\"region\"]"))
                }
            } else {
                reads++
                when {
                    !committed -> json(snapshot(answers =
                        """{"country":{"status":"declared","value":"US","source":"owner","revision":1},"region":{"status":"declared","value":"California","source":"owner","revision":1}}"""))
                    reads == 2 -> fail(503)
                    else -> json(snapshot(revision = 2, answers = """{"country":{"status":"declared","value":"TW","source":"owner","revision":2}}"""))
                }
            }
        }) { sessions, controller ->
            controller.refresh()
            assertTrue(controller.state.value.canEdit)
            assertTrue(CoreDiscoveryKey.Region in controller.state.value.snapshot!!.answers)
            controller.submit(CoreDiscoveryCommand.Answer(CoreDiscoveryKey.Country, CoreDiscoveryValue.Declared("tw")), 1)
            assertTrue(controller.state.value.pending)
            assertEquals(CoreDiscoveryNotice.Unconfirmed, controller.state.value.notice)
            controller.retry()
            assertTrue(controller.state.value.pending)
            assertEquals(CoreDiscoveryNotice.SignInAgain, controller.state.value.notice)
            controller.retry()
            assertTrue(controller.state.value.acknowledged)
            assertEquals(1L, controller.state.value.snapshot?.revision)
            controller.retry()
            assertEquals(3, sent.size)
            assertTrue(sent.all { it == sent.first() })
            assertEquals("\"1\"", sent.first().version)
            assertEquals("TW", Json.parseToJsonElement(sent.first().body).jsonObject.getValue("answer").jsonObject.value("value"))
            assertEquals(listOf(CoreDiscoveryKey.Region), controller.state.value.invalidatedKeys)
            assertFalse(controller.state.value.pending)
            assertEquals(2L, controller.state.value.snapshot?.revision)
            assertFalse(CoreDiscoveryKey.Region in controller.state.value.snapshot!!.answers)

            val cold = CoreBusinessDiscoveryController(sessions, sessions.state.value as CoreOrganizationSessionController.State.Active)
            cold.refresh()
            assertEquals(CoreDiscoveryValue.Declared("TW"), cold.state.value.snapshot?.answers?.get(CoreDiscoveryKey.Country)?.value)
        }
    }

    @Test
    fun conflictAndStalePlanRequireFreshReviewAndEinOnlyAcceptsStatuses() = runTest {
        var latest = snapshot(status = "stale")
        val sent = mutableListOf<Sent>()
        fixture(routes = { request ->
            if (request.method == HttpMethod.Get) json(latest)
            else {
                sent += sent(request)
                if (sent.size == 1) fail(409)
                else {
                    latest = snapshot(revision = 2, answerRevision = 1, plan = currentPlan)
                    json(receipt(revision = 2, answerRevision = 1, planRevision = "2"))
                }
            }
        }) { _, controller ->
            controller.refresh()
            assertNull(controller.state.value.snapshot?.plan)
            controller.submit(CoreDiscoveryCommand.EditPlan(1, CoreDiscoveryEdit.Select(CoreDiscoveryActionId.Goal)), 1)
            controller.submit(CoreDiscoveryCommand.Answer(CoreDiscoveryKey.EmployerIdStatus, CoreDiscoveryValue.Declared("12-3456789")), 1)
            assertTrue(sent.isEmpty())
            controller.submit(CoreDiscoveryCommand.RefreshPlan, 1)
            assertEquals(CoreDiscoveryNotice.Changed, controller.state.value.notice)
            assertFalse(controller.state.value.canEdit)
            controller.submit(CoreDiscoveryCommand.RefreshPlan, 1)
            assertEquals(1, sent.size)
            controller.refresh()
            controller.submit(CoreDiscoveryCommand.RefreshPlan, 1)
            assertEquals(2, sent.size)
            assertEquals(CoreDiscoveryPlanStatus.Current, controller.state.value.snapshot?.planStatus)
            controller.submit(CoreDiscoveryCommand.EditPlan(2, CoreDiscoveryEdit.Select(CoreDiscoveryActionId.Goal)), 1)
            assertEquals(CoreDiscoveryNotice.Changed, controller.state.value.notice)
            assertEquals(2, sent.size)
        }
    }

    @Test
    fun activeOrganizationSwitchCancelsInFlightDiscoveryAndFencesOldWrites() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var reads = 0
        fixture(routes = { request ->
            reads++
            if (request.headers[HttpHeaders.Authorization] == "Bearer $otherOwner") json(snapshot(org = "other-org"))
            else {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }) { sessions, controller ->
            val old = launch { controller.refresh() }
            started.await()
            val active = sessions.switchOrganization("other-org")
            old.join()
            stopped.await()
            controller.submit(CoreDiscoveryCommand.RefreshPlan, 1)
            assertEquals(1, reads)
            assertNull(controller.state.value.snapshot)
            val replacement = CoreBusinessDiscoveryController(sessions, active)
            replacement.refresh()
            assertEquals("other-org", replacement.state.value.snapshot?.orgId)
            assertEquals(2, reads)
        }
    }

    @Test
    fun nonOwnerCannotRequestDiscoveryAndWrongOrganizationCannotPublish() = runTest {
        fixture(role = "operator", routes = { error("Non-owner sent a discovery request") }) { _, controller ->
            controller.refresh()
            controller.submit(CoreDiscoveryCommand.RefreshPlan, 1)
            assertNull(controller.state.value.snapshot)
        }
        fixture(routes = { json(snapshot(org = "foreign")) }) { _, controller ->
            controller.refresh()
            assertNull(controller.state.value.snapshot)
            assertEquals(CoreDiscoveryNotice.Unavailable, controller.state.value.notice)
        }
        fixture(routes = { fail(423) }) { _, controller ->
            controller.refresh()
            assertFalse(controller.state.value.canEdit)
            assertEquals(CoreDiscoveryNotice.PolicyUnavailable, controller.state.value.notice)
        }
    }

    private suspend fun fixture(
        role: String = "owner",
        routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        test: suspend (CoreOrganizationSessionController, CoreBusinessDiscoveryController) -> Unit,
    ) {
        val engine = MockEngine { request ->
            val org = if (request.headers[HttpHeaders.Authorization] == "Bearer $otherOwner") "other-org" else "org-1"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> json("""{"sessionId":"session-$org","orgId":"$org","userId":"owner","role":"$role","expiresAt":60001}""")
                "/v1/auth/session/switch" -> json("""{"token":"$otherOwner","expiresAt":60001}""")
                path, "$path/commands" -> {
                    assertEquals("discovery.example.test", request.url.host)
                    assertTrue(request.url.parameters.isEmpty())
                    assertEquals(if (request.method == HttpMethod.Get) path else "$path/commands", request.url.encodedPath)
                    assertTrue(request.headers[HttpHeaders.Authorization] in listOf("Bearer $owner", "Bearer $otherOwner"))
                    routes(request)
                }
                else -> error("Unexpected route")
            }
        }
        CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin))).use { api ->
            val sessions = CoreOrganizationSessionController(api) { 1L }
            test(sessions, CoreBusinessDiscoveryController(sessions, sessions.establish(owner)))
        }
    }

    private data class Sent(val body: String, val version: String?, val key: String?)
    private suspend fun sent(request: HttpRequestData) =
        Sent(request.body.toByteArray().decodeToString(), request.headers[HttpHeaders.IfMatch],
            request.headers["Idempotency-Key"]).also { assertTrue(it.key?.isNotBlank() == true) }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    private fun MockRequestHandleScope.fail(status: Int) = respond("{}", HttpStatusCode.fromValue(status))

    private fun snapshot(
        revision: Int = 1, answerRevision: Int = revision, org: String = "org-1",
        answers: String = "{}", plan: String = "null", status: String = if (plan == "null") "missing" else "current",
    ) = """{"schemaVersion":1,"orgId":"$org","revision":$revision,"answerRevision":$answerRevision,"answers":$answers,"activeQuestions":["stage","country","region","ein","offer"],"planStatus":"$status","plan":$plan}"""

    private fun receipt(revision: Int = 1, answerRevision: Int = revision, planRevision: String = "null", invalidated: String = "[]") =
        """{"schemaVersion":1,"orgId":"org-1","revision":$revision,"answerRevision":$answerRevision,"planRevision":$planRevision,"invalidatedKeys":$invalidated}"""

    private val currentPlan =
        """{"revision":2,"answerRevision":1,"rulesVersion":1,"summary":"Review the next step","actions":[{"id":"goal","title":"Review your goal","reason":"Start with the goal you shared","dependencies":[],"answerKeys":[],"requiresReview":true,"deferred":false}],"selectedNext":null,"recommendedNext":"goal"}"""

    private fun commandForFixture(body: JsonObject): CoreDiscoveryCommand = when (body.value("action")) {
        "answer.set" -> {
            val answer = body.getValue("answer").jsonObject
            CoreDiscoveryCommand.Answer(key(body.value("key")), when (answer.value("status")) {
                "declared" -> CoreDiscoveryValue.Declared(answer.value("value"))
                "unknown" -> CoreDiscoveryValue.Unknown
                "deferred" -> CoreDiscoveryValue.Deferred
                else -> error("Unknown fixture answer")
            })
        }
        "answer.clear" -> CoreDiscoveryCommand.Clear(key(body.value("key")))
        "answers.defer_remaining" -> CoreDiscoveryCommand.DeferRemaining
        "plan.refresh" -> CoreDiscoveryCommand.RefreshPlan
        "plan.edit" -> {
            val edit = body.getValue("edit").jsonObject
            val id = edit["id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let { code ->
                CoreDiscoveryActionId.entries.single { it.code == code }
            }
            CoreDiscoveryCommand.EditPlan(body.getValue("planRevision").jsonPrimitive.long, when (edit.value("kind")) {
                "title" -> CoreDiscoveryEdit.Title(checkNotNull(id), edit.getValue("value").takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                "defer" -> CoreDiscoveryEdit.Defer(checkNotNull(id), edit.getValue("value").jsonPrimitive.boolean)
                "select" -> CoreDiscoveryEdit.Select(id)
                else -> error("Unknown fixture edit")
            })
        }
        else -> error("Unknown fixture command")
    }

    private fun key(code: String) = CoreDiscoveryKey.entries.single { it.code == code }
    private fun JsonObject.value(name: String) = getValue(name).jsonPrimitive.content
    private fun fixtureJson(name: String) = Json.parseToJsonElement(generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "tests/fixtures/business-discovery-v1/$name") }.first(File::isFile).readText())
}
