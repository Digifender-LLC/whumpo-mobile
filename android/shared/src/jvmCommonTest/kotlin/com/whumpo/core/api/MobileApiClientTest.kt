package com.whumpo.core.api

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs the client against the frozen contract fixtures in tests/fixtures/mobile-v1. These are the same files the
 * server-side contract test (tests/mobile-v1-contract.mjs) reads, so the client and the contract cannot drift apart.
 */
class MobileApiClientTest {
    private val json = Json { encodeDefaults = true }

    private fun fixture(name: String): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/mobile-v1/$name").isFile) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "tests/fixtures/mobile-v1/$name not found above ${File("").absolutePath}" }, "tests/fixtures/mobile-v1/$name")
        return json.parseToJsonElement(file.readText()).jsonObject
    }

    private val operations = fixture("operations.json")["cases"]!!.jsonArray.map { it.jsonObject }
    private val errorCases = fixture("errors.json")["cases"]!!.jsonObject
    private val clientFixture = fixture("client.json")
    private val current = clientInfo("current")
    private val base = "https://whumpo.com/api/mobile/v1"
    private val token = operations.first { it["operation"]!!.jsonPrimitive.content == "session.read" }
        .let { it["request"]!!.jsonObject["headers"]!!.jsonObject["authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ") }

    private fun clientInfo(name: String, source: JsonObject = clientFixture): ClientInfo {
        val o = source[name]!!.jsonObject
        return ClientInfo(
            build = o["build"]!!.jsonPrimitive.int,
            controls = o["controls"]!!.jsonArray.map { it.jsonPrimitive.content },
            interaction = json.decodeFromJsonElement(InteractionCapabilities.serializer(), o["interaction"]!!),
        )
    }

    private class FakeSessions(var token: String?) : SessionProvider {
        var rejected = 0
        override suspend fun bearerToken() = token
        override suspend fun onSessionRejected() {
            rejected++
        }
    }

    private fun client(
        sessions: SessionProvider = FakeSessions(token),
        info: ClientInfo = current,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = MobileApiClient(MockEngine(handler), base, info, sessions)

    private fun MockRequestHandleScope.reply(status: Int, body: String, vararg headers: Pair<String, String>) = respond(
        body, HttpStatusCode.fromValue(status),
        headersOf(*(listOf(HttpHeaders.ContentType to "application/json; charset=utf-8") + headers).map { it.first to listOf(it.second) }.toTypedArray()),
    )

    // ---- Every endpoint, against its fixture pair --------------------------------------------------------------------------

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.num(key: String) = this[key]!!.jsonPrimitive.int
    private inline fun <reified T> enc(value: T): JsonElement = json.encodeToJsonElement(value)

    private val expectedMethod = mapOf("photo.upload" to HttpMethod.Put, "device.register" to HttpMethod.Put, "device.remove" to HttpMethod.Delete)
    private val getOperations = setOf(
        "config.read", "session.read", "bootstrap.read", "chat.read", "chat.turn", "review.list", "project.list", "project.read",
        "project.versions", "activity.list", "photo.list", "photo.status", "photo.bytes", "event.read",
    )
    private val anonymous = setOf("config.read", "session.loginOptions", "session.loginVerify", "session.handoffStart", "session.handoffExchange")
    private val jpeg = ByteArray(204_800) { (it % 251).toByte() }

    /** Calls the operation under test with the request the fixture describes and returns the typed result as JSON. */
    private suspend fun call(c: MobileApiClient, op: String, case: JsonObject): JsonElement {
        val req = case["request"]!!.jsonObject
        val body = req["body"]?.jsonObject
        val query = req["query"]?.jsonObject
        val last = case["path"]!!.jsonPrimitive.content.substringAfterLast('/')
        val projectId = case["path"]!!.jsonPrimitive.content.substringAfter("/projects/", "").substringBefore('/')
        return when (op) {
            "config.read" -> enc(c.getConfig())
            "session.loginOptions" -> enc(c.loginOptions())
            "session.loginVerify" -> enc(c.loginVerify(body!!.str("challengeId"), body["credential"]!!.jsonObject))
            "session.handoffStart" -> enc(
                c.handoffStart(HandoffStartRequest(body!!.str("purpose"), body.str("state"), body.str("codeChallenge"), body.str("redirectUri"))),
            )
            "session.handoffExchange" -> enc(
                c.handoffExchange(HandoffExchangeRequest(body!!.str("handoffId"), body.str("code"), body.str("state"), body.str("codeVerifier"))),
            )
            "session.reauthOptions" -> enc(c.reauthOptions())
            "session.reauthVerify" -> enc(c.reauthVerify(body!!.str("challengeId"), body["credential"]!!.jsonObject))
            "session.read" -> enc(c.getSession())
            "session.logout" -> enc(c.logout())
            "bootstrap.read" -> enc(c.bootstrap())
            "chat.read" -> enc(c.getChat(limit = query!!.num("limit")))
            "chat.turn" -> enc(c.getChatTurn(last))
            "chat.send" -> enc(c.sendMessage(body!!.str("requestId"), body.num("expectedRevision"), body.str("content")))
            "review.list" -> enc(c.listReviews())
            "review.confirm" -> enc(
                c.confirmReview(
                    body!!.str("requestId"), body.num("expectedRevision"),
                    json.decodeFromJsonElement(ReviewControlSerializer, body["control"]!!),
                ),
            )
            "interaction.list" -> c.interactionList(body!!["projectId"]!!.jsonPrimitive.content, body.num("limit"), body["before"])
            "interaction.read" -> c.interactionRead(body!!.str("interactionId"))
            "interaction.resources" -> c.interactionResources(
                body!!.str("interactionId"), body.str("definitionDigest"), body.str("fieldId"), body.num("limit"), body["before"],
            )
            "interaction.mutate" -> enc(
                c.interactionMutate(
                    InteractionMutateRequest(
                        body!!.str("requestId"), body.str("interactionId"), body.str("definitionDigest"), body.num("expectedAnswerRevision"),
                        body.str("operation"), body["answers"]?.jsonObject,
                    ),
                ),
            )
            "interaction.receipt" -> enc(c.interactionReceipt(body!!.str("requestId")))
            "interaction.continue" -> enc(c.interactionContinue(body!!.str("submissionId"), body.num("expectedChatRevision")))
            "interaction.stop" -> enc(c.interactionStop(body!!.str("submissionId")))
            "interaction.reconcile" -> enc(c.interactionReconcile(body!!.str("submissionId")))
            "project.list" -> enc(c.listProjects(limit = query!!.num("limit")))
            "project.read" -> enc(c.getProject(last))
            "project.versions" -> enc(c.listVersions(projectId, query!!.num("limit")))
            "activity.list" -> enc(c.listActivity(limit = query!!.num("limit")))
            "photo.list" -> enc(c.listPhotos(projectId))
            "photo.upload" -> enc(c.uploadPhoto(projectId, last, "van.jpg", jpeg))
            "photo.status" -> enc(c.getPhotoUpload(projectId, last))
            "photo.bytes" -> JsonPrimitive(c.getPhotoBytes(last).contentHashCode())
            "device.register" -> enc(c.registerDevice(body!!.str("fcmRegistrationToken"), body["notificationsEnabled"]!!.jsonPrimitive.boolean))
            "device.remove" -> enc(c.removeDevice())
            "event.read" -> enc(c.getEvent(last))
            else -> error("no client call for $op")
        }
    }

    @Test
    fun everyFixtureOperationIsCalledAndRoundTrips() = runTest {
        val seen = mutableSetOf<String>()
        for (case in operations) {
            val op = case.str("operation")
            val req = case["request"]!!.jsonObject
            val response = case["response"]!!.jsonObject
            val sessions = FakeSessions(token)
            var requests = 0
            val c = client(sessions) { r ->
                requests++
                val expectedPath = case.str("path")
                assertEquals(expectedPath, r.url.encodedPath, op)
                assertEquals("whumpo.com", r.url.host, op)
                assertEquals(expectedMethod[op] ?: if (op in getOperations) HttpMethod.Get else HttpMethod.Post, r.method, op)
                // Headers: client capability matches the fixture byte-for-meaning, bearer only on authenticated routes, never a cookie.
                assertEquals(
                    json.parseToJsonElement(req["headers"]!!.jsonObject.str("whumpo-client")),
                    json.parseToJsonElement(r.headers["Whumpo-Client"]!!), op,
                )
                assertNull(r.headers[HttpHeaders.Cookie], op)
                if (op in anonymous) assertNull(r.headers[HttpHeaders.Authorization], op)
                else assertEquals(req["headers"]!!.jsonObject.str("authorization"), r.headers[HttpHeaders.Authorization], op)
                // Query and body equal the fixture request exactly (no extra, missing or renamed keys).
                val expectedQuery = req["query"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                assertEquals(expectedQuery, r.url.parameters.entries().associate { it.key to it.value.single() }, op)
                val sent = r.body.toByteArray()
                val binary = req["binary"]?.jsonObject
                if (binary != null) {
                    assertEquals(binary.str("contentType"), r.body.contentType.toString(), op)
                    assertEquals(binary.num("bytes"), sent.size, op)
                    assertEquals(req["headers"]!!.jsonObject.str("x-photo-name"), r.headers["X-Photo-Name"], op)
                    assertTrue(sent.contentEquals(jpeg), op)
                } else if (req["body"] != null) {
                    assertEquals(req["body"], json.parseToJsonElement(sent.decodeToString()), op)
                } else {
                    assertEquals(0, sent.size, op)
                }
                val binaryResponse = response["binary"]?.jsonObject
                if (binaryResponse != null) {
                    respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, binaryResponse.str("contentType")))
                } else {
                    reply(response.num("status"), response["body"].toString())
                }
            }
            val result = call(c, op, case)
            assertEquals(1, requests, "$op sent exactly one request")
            assertEquals(0, sessions.rejected, op)
            if (response["binary"] != null) assertEquals(JsonPrimitive(jpeg.contentHashCode()), result, op)
            else assertEquals(response["body"], result, op)
            seen += op
            c.close()
        }
        // One fixture row per operation in the contract registry (34); every one was called.
        assertEquals(operations.map { it.str("operation") }.toSet(), seen)
        assertEquals(34, seen.size)
    }

    // ---- Error codes ----------------------------------------------------------------------------------------------------------

    private suspend fun errorFor(name: String, headers: Array<Pair<String, String>> = emptyArray(), sessions: SessionProvider = FakeSessions(token)): MobileApiException {
        val case = errorCases[name]!!.jsonObject
        val c = client(sessions) { reply(case.num("status"), case["body"].toString(), *headers) }
        return assertFailsWith<MobileApiException> { c.getSession() }.also { c.close() }
    }

    @Test
    fun everyContractErrorCodeMapsToItsType() = runTest {
        val covered = mutableSetOf<String>()
        suspend fun check(name: String): MobileApiException {
            val e = errorFor(name)
            covered += e.code
            assertEquals(errorCases[name]!!.jsonObject["body"]!!.jsonObject["error"]!!.jsonObject.str("code"), e.code, name)
            return e
        }
        assertEquals(426, errorCases.getValue("client_update_required").jsonObject.num("status"))
        check("client_update_required").let {
            assertIs<MobileApiException.ClientUpdateRequired>(it)
            assertEquals(40, it.minimumBuild)
            assertEquals(1, it.contract)
        }
        assertEquals(MobileApiException.ProofRequired.Proof.SIGN_IN, assertIs<MobileApiException.ProofRequired>(check("proof_required_signin")).proof)
        assertEquals(MobileApiException.ProofRequired.Proof.FRESH_PASSKEY, assertIs<MobileApiException.ProofRequired>(check("proof_required_fresh")).proof)
        assertIs<MobileApiException.IdentityIncomplete>(check("identity_incomplete"))
        assertIs<MobileApiException.OnboardingRequired>(check("onboarding_required"))
        assertIs<MobileApiException.IdentityChanged>(check("identity_changed"))
        assertIs<MobileApiException.RequestConflict>(check("request_conflict"))
        assertEquals(6, assertIs<MobileApiException.StaleRevision>(check("stale_revision")).currentRevision)
        assertIs<MobileApiException.ChatBusy>(check("chat_busy"))
        assertIs<MobileApiException.ReviewUnavailable>(check("review_unavailable"))
        assertIs<MobileApiException.NotFound>(check("not_found"))
        for (name in listOf("invalid_request", "invalid_cursor", "unsupported_control")) {
            assertIs<MobileApiException.ValidationFailed>(check(name))
        }
        assertIs<MobileApiException.PayloadTooLarge>(check("payload_too_large"))
        assertIs<MobileApiException.UnsupportedMediaType>(check("unsupported_media_type"))
        assertEquals(60, assertIs<MobileApiException.RateLimited>(check("rate_limited")).retryAfterSeconds)
        assertIs<MobileApiException.Unavailable>(check("unavailable"))

        // The contract lists unknown_field and missing_field too; they have no fixture, so build their bodies here.
        for (code in listOf("unknown_field", "missing_field")) {
            val c = client { reply(400, """{"version":1,"error":{"code":"$code","message":"Check the request and try again."}}""") }
            val e = assertFailsWith<MobileApiException.ValidationFailed> { c.getSession() }
            assertEquals(code, e.code)
            covered += code
        }
        val documented = setOf(
            "client_update_required", "proof_required", "identity_incomplete", "onboarding_required", "identity_changed", "invalid_request", "unknown_field",
            "missing_field", "invalid_cursor", "unsupported_control", "not_found", "request_conflict", "stale_revision", "chat_busy", "review_unavailable",
            "payload_too_large", "unsupported_media_type", "rate_limited", "unavailable",
        )
        assertEquals(documented, covered)
        // Every code the fixtures carry is one the client maps.
        val fixtureCodes = errorCases.values.map { it.jsonObject["body"]!!.jsonObject["error"]!!.jsonObject.str("code") }.toSet()
        assertEquals(fixtureCodes, fixtureCodes.intersect(documented))
    }

    @Test
    fun rejectedSessionNotifiesTheProviderOnlyOnAuthenticatedRoutes() = runTest {
        for ((name, expected) in listOf("proof_required_signin" to 1, "identity_changed" to 1, "proof_required_fresh" to 0, "not_found" to 0, "rate_limited" to 0)) {
            val sessions = FakeSessions(token)
            errorFor(name, sessions = sessions)
            assertEquals(expected, sessions.rejected, name)
        }
        // A wrong passkey on the anonymous sign-in route is a generic proof_required, not a revoked session.
        val sessions = FakeSessions(null)
        val c = client(sessions) { reply(401, errorCases.getValue("proof_required_signin").jsonObject["body"].toString()) }
        assertFailsWith<MobileApiException.ProofRequired> { c.loginVerify("cccccccc-cccc-4ccc-8ccc-000000000001", buildJsonObject { put("id", "AAAA") }) }
        assertEquals(0, sessions.rejected)
    }

    @Test
    fun rateLimitFallsBackToTheRetryAfterHeader() = runTest {
        val c = client { reply(429, """{"version":1,"error":{"code":"rate_limited","message":"Slow down."}}""", "Retry-After" to "30") }
        assertEquals(30, assertFailsWith<MobileApiException.RateLimited> { c.getSession() }.retryAfterSeconds)
    }

    @Test
    fun nonContractFailuresAreUnrecognizedNotGuessed() = runTest {
        val html = client { respond("<html>Bad gateway</html>", HttpStatusCode.BadGateway) }
        assertEquals(502, assertFailsWith<MobileApiException.Unrecognized> { html.getSession() }.status)
        val redirect = client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://example.com/")) }
        assertEquals(302, assertFailsWith<MobileApiException.Unrecognized> { redirect.getSession() }.status)
        val unknownCode = client { reply(409, """{"version":1,"error":{"code":"brand_new","message":"x"}}""") }
        assertEquals("brand_new", assertFailsWith<MobileApiException.Unrecognized> { unknownCode.getSession() }.code)
    }

    // ---- Responses are strict ------------------------------------------------------------------------------------------------

    private fun mutated(op: String, mutate: (JsonElement) -> JsonElement): String =
        mutate(operations.first { it.str("operation") == op }["response"]!!.jsonObject["body"]!!).toString()

    @Test
    fun rejectionFixturesForResponsesAreRefused() = runTest {
        val rejections = fixture("rejections.json")["cases"]!!.jsonArray.map { it.jsonObject }.filter { it["responseOf"] != null }
        assertEquals(5, rejections.size)
        for (r in rejections) {
            val op = r.str("responseOf")
            val body = mutated(op) { b ->
                val o = b.jsonObject.toMutableMap()
                fun firstItem(f: (MutableMap<String, JsonElement>) -> Unit): JsonElement {
                    val items = o["items"]!!.jsonArray.toMutableList()
                    val item = items[0].jsonObject.toMutableMap().also(f)
                    items[0] = JsonObject(item)
                    o["items"] = kotlinx.serialization.json.JsonArray(items)
                    return JsonObject(o)
                }
                when (r.str("mutate")) {
                    "previewPath" -> firstItem { it["preview"] = buildJsonObject { put("path", "/api/files/11111111-1111-4111-8111-111111111111") } }
                    "approvableWithoutControl" -> firstItem { it["control"] = JsonNull }
                    "unknownReviewKind" -> firstItem { it["kind"] = JsonPrimitive("dns") }
                    "approvableExpired" -> firstItem { it["state"] = JsonPrimitive("expired") }
                    "extraField" -> JsonObject(o.toMutableMap().also { m ->
                        m["project"] = JsonObject(o["project"]!!.jsonObject + ("ownerUserId" to JsonPrimitive("x")))
                    })
                    else -> error(r.str("mutate"))
                }
            }
            val c = client { reply(200, body) }
            assertFailsWith<MobileApiException.InvalidResponse>(r.str("name")) {
                when (op) {
                    "project.versions" -> c.listVersions("11111111-1111-4111-8111-000000000001")
                    "review.list" -> c.listReviews()
                    "project.read" -> c.getProject("11111111-1111-4111-8111-000000000001")
                    else -> error(op)
                }
            }
        }
    }

    @Test
    fun wrongVersionAndMissingKeysAreInvalidResponses() = runTest {
        val wrongVersion = client { reply(200, mutated("session.read") { JsonObject(it.jsonObject + ("version" to JsonPrimitive(2))) }) }
        assertFailsWith<MobileApiException.InvalidResponse> { wrongVersion.getSession() }
        val missing = client { reply(200, mutated("session.read") { JsonObject(it.jsonObject - "account") }) }
        assertFailsWith<MobileApiException.InvalidResponse> { missing.getSession() }
        val notJson = client { reply(200, "nope") }
        assertFailsWith<MobileApiException.InvalidResponse> { notJson.getSession() }
        val passthroughVersion = client { reply(200, """{"version":2,"items":[]}""") }
        assertFailsWith<MobileApiException.InvalidResponse> { passthroughVersion.interactionList(null) }
        val notJpeg = client { respond("x", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }
        assertFailsWith<MobileApiException.InvalidResponse> { notJpeg.getPhotoBytes("99999999-9999-4999-8999-000000000001") }
    }

    // ---- Reviews: server metadata is the only source of approval ---------------------------------------------------------------

    @Test
    fun reviewPresentationFollowsTheFixtures() {
        val fx = fixture("reviews.json")
        for (case in fx["cases"]!!.jsonArray.map { it.jsonObject }) {
            val review = json.decodeFromJsonElement(Review.serializer(), case["review"]!!)
            val p = review.present(clientInfo(case.str("client"), fx["clients"]!!.jsonObject))
            assertEquals(case["enabled"]!!.jsonPrimitive.boolean, p.enabled, case.str("name"))
            if (case["reason"] != null) assertEquals(case.str("reason"), p.reason, case.str("name"))
        }
    }

    @Test
    fun reviewControlsRoundTripAndOthersAreRefused() {
        val id = "33333333-3333-4333-8333-000000000001"
        val controls = listOf(
            SpecificationApply(id, "a".repeat(64), 3), ConfirmSite(id), ConfirmApplication(id),
            AppRestore("11111111-1111-4111-8111-000000000001", id),
        )
        for (control in controls) {
            val wire = json.encodeToJsonElement(ReviewControlSerializer, control)
            assertEquals(control, json.decodeFromJsonElement(ReviewControlSerializer, wire))
        }
        assertEquals(
            buildJsonObject { put("type", "appRestore"); put("projectId", "11111111-1111-4111-8111-000000000001"); put("reviewId", id); put("confirmation", "RESTORE") },
            json.encodeToJsonElement(ReviewControlSerializer, controls[3]),
        )
        for (bad in listOf("""{"type":"unpublish"}""", """{"type":"confirm","kind":"backup","reviewId":"$id"}""")) {
            assertFailsWith<IllegalArgumentException> { json.decodeFromString(ReviewControlSerializer, bad) }
        }
    }

    @Test
    fun aBuildNeverSendsAControlItDidNotDeclare() = runTest {
        val c = client(info = clientInfo("withoutApplicationControls")) { error("must not send") }
        assertFailsWith<IllegalArgumentException> {
            c.confirmReview(newRequestId(), 5, ConfirmApplication("33333333-3333-4333-8333-000000000001"))
        }
    }

    // ---- Request IDs, replay and lost replies ---------------------------------------------------------------------------------

    @Test
    fun generatedRequestIdsAreLowercaseUuids() {
        val a = newRequestId()
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(a))
        assertTrue(a != newRequestId())
    }

    @Test
    fun aLostWriteReplyIsReportedAsUnknownAndNeverResent() = runTest {
        var sends = 0
        val c = client { sends++; throw java.io.IOException("connection reset") }
        val e = assertFailsWith<MobileApiException.Transport> { c.sendMessage(newRequestId(), 4, "hello") }
        assertTrue(e.outcomeUnknown)
        assertEquals(1, sends)
        val read = assertFailsWith<MobileApiException.Transport> { c.getChat() }
        assertFalse(read.outcomeUnknown)
    }

    @Test
    fun resolveSendReadsTheTurnAndTreatsNotFoundAsNothingCommitted() = runTest {
        val turn = operations.first { it.str("operation") == "chat.turn" }
        val id = turn.str("path").substringAfterLast('/')
        val found = client { r ->
            assertEquals("$base/chat/turns/$id", r.url.toString().substringBefore('?'))
            reply(200, turn["response"]!!.jsonObject["body"].toString())
        }
        assertEquals(id, assertNotNull(found.resolveSend(id)).id)
        val missing = client { reply(404, errorCases.getValue("not_found").jsonObject["body"].toString()) }
        assertNull(missing.resolveSend(id))
    }

    @Test
    fun sameRequestIdAndPayloadAreSentUnchangedOnAnExplicitRetry() = runTest {
        val id = newRequestId()
        val bodies = mutableListOf<String>()
        val c = client { r ->
            bodies += r.body.toByteArray().decodeToString()
            if (bodies.size == 1) throw java.io.IOException("timeout")
            reply(200, """{"version":1,"requestId":"$id","accepted":true,"revision":5,"turnStatus":"pending"}""")
        }
        assertFailsWith<MobileApiException.Transport> { c.sendMessage(id, 4, "Add a ceramic coating service for \$300.") }
        assertEquals(id, c.sendMessage(id, 4, "Add a ceramic coating service for \$300.").requestId)
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1])
    }

    @Test
    fun freshPasskeyProofIsSurfacedForTheCallerToReauthenticateThenRetryWithTheSameId() = runTest {
        val id = newRequestId()
        var calls = 0
        val c = client { r ->
            calls++
            if (calls == 1) reply(401, errorCases.getValue("proof_required_fresh").jsonObject["body"].toString())
            else reply(200, """{"version":1,"requestId":"$id","accepted":true,"revision":6,"turnStatus":"completed"}""")
        }
        val control = SpecificationApply("33333333-3333-4333-8333-000000000001", "a".repeat(64), 3)
        val e = assertFailsWith<MobileApiException.ProofRequired> { c.confirmReview(id, 5, control) }
        assertEquals(MobileApiException.ProofRequired.Proof.FRESH_PASSKEY, e.proof)
        assertEquals(1, calls)
        assertEquals("completed", c.confirmReview(id, 5, control).turnStatus)
    }

    // ---- identity_changed after the client's own reauth ---------------------------------------------------------------------

    private val reauthBody get() = operations.first { it.str("operation") == "session.reauthVerify" }["response"]!!.jsonObject["body"].toString()
    private val identityChanged get() = errorCases.getValue("identity_changed").jsonObject
    private val credential = buildJsonObject { put("id", "AAAA") }
    private val challenge = "cccccccc-cccc-4ccc-8ccc-000000000001"
    private val sendId = "dddddddd-dddd-4ddd-8ddd-000000000001"

    /** Reauth verify succeeds; the first [failures] other requests fail identity_changed, later ones succeed. Records request bodies. */
    private class Race(val failures: Int) {
        val bodies = mutableListOf<String>()
    }

    private fun raceClient(race: Race, sessions: SessionProvider, time: TestTimeSource) =
        MobileApiClient(
            MockEngine { r ->
                if (r.url.encodedPath.endsWith("/session/reauth/verify")) {
                    reply(200, reauthBody)
                } else {
                    race.bodies += r.body.toByteArray().decodeToString()
                    if (race.bodies.size <= race.failures) {
                        reply(identityChanged.num("status"), identityChanged["body"].toString())
                    } else {
                        reply(200, """{"version":1,"requestId":"$sendId","accepted":true,"revision":6,"turnStatus":"completed"}""")
                    }
                }
            },
            base, current, sessions, time,
        )

    @Test
    fun identityChangedIsRetriedOnceWithTheSameRequestIdAfterTheClientsOwnReauth() = runTest {
        val sessions = FakeSessions(token)
        val time = TestTimeSource()
        val race = Race(failures = 1)
        val c = raceClient(race, sessions, time)
        c.reauthVerify(challenge, credential)
        time += 3.seconds
        assertEquals("completed", c.sendMessage(sendId, 5, "hello").turnStatus)
        assertEquals(2, race.bodies.size)
        assertEquals(race.bodies[0], race.bodies[1])
        assertTrue(race.bodies[1].contains(sendId))
        assertEquals(0, sessions.rejected)
    }

    @Test
    fun identityChangedIsNotRetriedWithoutARecentReauth() = runTest {
        for (reauthFirst in listOf(false, true)) {
            val sessions = FakeSessions(token)
            val time = TestTimeSource()
            val race = Race(failures = 5)
            val c = raceClient(race, sessions, time)
            if (reauthFirst) {
                c.reauthVerify(challenge, credential)
                time += 11.seconds
            }
            assertFailsWith<MobileApiException.IdentityChanged> { c.sendMessage(sendId, 5, "hello") }
            assertEquals(1, race.bodies.size, "reauthFirst=$reauthFirst")
            assertEquals(1, sessions.rejected)
        }
    }

    @Test
    fun overloadNeverRejectsTheSession() = runTest {
        // An anonymous call refused as identity_changed has no session to reject; a retryable 503 on a signed-in call keeps it too.
        val changed = FakeSessions(token)
        val anonymous = MobileApiClient(MockEngine { reply(identityChanged.num("status"), identityChanged["body"].toString()) }, base, current, changed, TestTimeSource())
        assertFailsWith<MobileApiException.IdentityChanged> { anonymous.loginOptions() }
        assertEquals(0, changed.rejected)
        val busy = errorCases.getValue("unavailable").jsonObject
        val sessions = FakeSessions(token)
        val signedIn = MobileApiClient(MockEngine { reply(busy.num("status"), busy["body"].toString()) }, base, current, sessions, TestTimeSource())
        assertFailsWith<MobileApiException.Unavailable> { signedIn.reauthOptions() }
        assertEquals(0, sessions.rejected)
    }

    @Test
    fun onlyOneRetryEverHappens() = runTest {
        val sessions = FakeSessions(token)
        val race = Race(failures = 5)
        val c = raceClient(race, sessions, TestTimeSource())
        c.reauthVerify(challenge, credential)
        assertFailsWith<MobileApiException.IdentityChanged> { c.sendMessage(sendId, 5, "hello") }
        assertEquals(2, race.bodies.size)
        assertEquals(race.bodies[0], race.bodies[1])
        assertEquals(1, sessions.rejected)
    }

    @Test
    fun otherCodesAreNeverRetriedAfterAReauth() = runTest {
        for (name in listOf("proof_required_signin", "proof_required_fresh", "stale_revision")) {
            var calls = 0
            val case = errorCases.getValue(name).jsonObject
            val c = MobileApiClient(
                MockEngine { r ->
                    if (r.url.encodedPath.endsWith("/session/reauth/verify")) {
                        reply(200, reauthBody)
                    } else {
                        calls++
                        reply(case.num("status"), case["body"].toString())
                    }
                },
                base, current, FakeSessions(token), TestTimeSource(),
            )
            c.reauthVerify(challenge, credential)
            assertFailsWith<MobileApiException> { c.sendMessage(sendId, 5, "hello") }
            assertEquals(1, calls, name)
        }
    }

    // ---- Session provider ---------------------------------------------------------------------------------------------------

    @Test
    fun signedOutCallsFailLocallyWithoutANetworkRequest() = runTest {
        for (bad in listOf(null, "short", token.dropLast(1) + "G")) {
            val c = client(FakeSessions(bad)) { error("must not send") }
            val e = assertFailsWith<MobileApiException.ProofRequired> { c.getSession() }
            assertEquals(MobileApiException.ProofRequired.Proof.SIGN_IN, e.proof)
        }
        // Anonymous routes never need a token.
        val c = client(FakeSessions(null)) { reply(200, operations.first { it.str("operation") == "config.read" }["response"]!!.jsonObject["body"].toString()) }
        assertEquals(40, c.getConfig().minimumBuild)
    }

    // ---- Client-side admission, from the request rejection fixtures -----------------------------------------------------------

    private fun rejection(name: String) = fixture("rejections.json")["cases"]!!.jsonArray.map { it.jsonObject }.first { it.str("name") == name }["request"]!!.jsonObject

    @Test
    fun requestsTheServerWouldRefuseAreNotSent() = runTest {
        val c = client { error("must not send") }
        for (name in listOf("handoff redirect off the verified host", "handoff redirect with a query string", "handoff custom-scheme redirect", "handoff short state")) {
            val b = rejection(name)["body"]!!.jsonObject
            assertFailsWith<IllegalArgumentException>(name) {
                c.handoffStart(HandoffStartRequest(b.str("purpose"), b.str("state"), b.str("codeChallenge"), b.str("redirectUri")))
            }
        }
        val x = rejection("exchange verifier too short")["body"]!!.jsonObject
        assertFailsWith<IllegalArgumentException> { c.handoffExchange(HandoffExchangeRequest(x.str("handoffId"), x.str("code"), x.str("state"), x.str("codeVerifier"))) }
        assertFailsWith<IllegalArgumentException> { c.sendMessage(newRequestId(), 4, rejection("chat message over 1000 characters")["body"]!!.jsonObject.str("content")) }
        assertFailsWith<IllegalArgumentException> { c.sendMessage(newRequestId(), 4, "") }
        assertFailsWith<IllegalArgumentException> { c.getChat(limit = rejection("chat limit above 50")["query"]!!.jsonObject.num("limit")) }
        assertFailsWith<IllegalArgumentException> { c.registerDevice(rejection("device token with whitespace")["body"]!!.jsonObject.str("fcmRegistrationToken"), true) }
        val m = rejection("interaction cancel carrying answers")["body"]!!.jsonObject
        assertFailsWith<IllegalArgumentException> {
            c.interactionMutate(
                InteractionMutateRequest(m.str("requestId"), m.str("interactionId"), m.str("definitionDigest"), m.num("expectedAnswerRevision"), m.str("operation"), m["answers"]!!.jsonObject),
            )
        }
        assertFailsWith<IllegalArgumentException> { c.getProject("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { c.uploadPhoto("11111111-1111-4111-8111-000000000001", "99999999-9999-4999-8999-000000000001", "a.jpg", ByteArray(1_048_577)) }
    }

    @Test
    fun anAnswerlessSubmitIsRefusedAndACancelOmitsTheAnswersKey() = runTest {
        val sent = mutableListOf<String>()
        val c = client { r ->
            sent += r.body.toByteArray().decodeToString()
            reply(200, """{"version":1,"requestId":"55555555-5555-4555-8555-000000000003","interactionId":"66666666-6666-4666-8666-000000000001","operation":"cancel","answerRevision":2}""")
        }
        val digest = "b".repeat(64)
        assertFailsWith<IllegalArgumentException> {
            c.interactionMutate(InteractionMutateRequest("55555555-5555-4555-8555-000000000003", "66666666-6666-4666-8666-000000000001", digest, 1, "submit"))
        }
        c.interactionMutate(InteractionMutateRequest("55555555-5555-4555-8555-000000000003", "66666666-6666-4666-8666-000000000001", digest, 1, "cancel"))
        assertFalse(json.parseToJsonElement(sent.single()).jsonObject.containsKey("answers"))
    }

    // ---- Push payload -------------------------------------------------------------------------------------------------------

    @Test
    fun pushDataIsParsedStrictlyFromTheFixtures() {
        val fx = fixture("push.json")
        fun data(o: JsonObject) = o.mapValues { it.value.jsonPrimitive.content }
        for (v in fx["valid"]!!.jsonArray) assertNotNull(PushMessage.fromData(data(v.jsonObject)), v.toString())
        for (bad in fx["invalid"]!!.jsonArray.map { it.jsonObject }) assertNull(PushMessage.fromData(data(bad["message"]!!.jsonObject)), bad.str("name"))
        assertNull(PushMessage.fromData(emptyMap()))
    }

    // ---- Client header ------------------------------------------------------------------------------------------------------

    @Test
    fun theClientHeaderMatchesTheFixtureForEveryClient() {
        for (name in listOf("current", "withoutApplicationControls")) {
            assertEquals(clientFixture[name], json.encodeToJsonElement(ClientInfo.serializer(), clientInfo(name)), name)
        }
        assertEquals(clientFixture["current"], json.encodeToJsonElement(ClientInfo.serializer(), ClientInfo.current(build = 42)))
        assertEquals(40, clientFixture["minimumBuild"]!!.jsonPrimitive.int)
    }
}
