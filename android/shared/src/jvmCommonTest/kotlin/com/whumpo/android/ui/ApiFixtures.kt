package com.whumpo.android.ui

import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.SessionProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The frozen contract fixtures (tests/fixtures/mobile-v1), served through a Ktor MockEngine. The same files the server contract test reads. */
class ApiFixtures {
    /** Every request the client made, in order. */
    val requests: MutableList<HttpRequestData> = CopyOnWriteArrayList()

    /** Bodies of those requests, read as they arrived. */
    val bodies: MutableList<ByteArray> = CopyOnWriteArrayList()

    /** The session the client authenticates with. Set `token = null` to model a signed-out device. */
    val sessions = FakeSessions(TOKEN)

    private val cases = json.parseToJsonElement(file("operations.json").readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    private val routes: Map<String, JsonObject> = cases.associateBy { "${methodOf(it.str("operation"))} ${template(it.str("path"))}" }

    /** Replaces the fixture reply for one route, for example `"GET /activity"`. Return null to fall back to the fixture. */
    var override: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null }

    val client: MobileApiClient = MobileApiClient(
        MockEngine { request ->
            requests += request
            bodies += request.body.toByteArray()
            override(request) ?: fixtureReply(request)
        },
        BASE,
        ClientInfo.current(build = 42, controls = emptyList()),
        sessions,
    )

    private fun MockRequestHandleScope.fixtureReply(request: HttpRequestData): HttpResponseData {
        val case = routes["${request.method.value} ${template(request.url.encodedPath)}"]
            ?: return reply(500, """{"version":1,"error":{"code":"unavailable","message":"no fixture for ${request.method.value} ${request.url.encodedPath}"}}""")
        val response = case["response"]!!.jsonObject
        return reply(response["status"]!!.jsonPrimitive.content.toInt(), response["body"]!!.toString())
    }

    /** `/projects/{id}/photos` for every UUID, so tests can use fresh IDs. */
    private fun template(path: String) = path.removePrefix("/api/mobile/v1").replace(UUID, "{id}")

    private fun methodOf(operation: String) = when (operation) {
        "photo.upload", "device.register" -> "PUT"
        "device.remove" -> "DELETE"
        "session.logout", "chat.send", "review.confirm", "session.loginOptions", "session.loginVerify", "session.handoffStart",
        "session.handoffExchange", "session.reauthOptions", "session.reauthVerify",
        -> "POST"
        else -> if (operation.startsWith("interaction.")) "POST" else "GET"
    }

    companion object {
        const val BASE = "https://whumpo.com/api/mobile/v1"
        const val TOKEN = "0000000000000000000000000000000000000000000000000000000000000001"
        const val PROJECT = "11111111-1111-4111-8111-000000000001"

        /** The fixtures were written at 12:00 UTC on 2026-10-04; tests run five minutes later. */
        val NOW: Instant = Instant.parse("2026-10-04T12:05:00Z")

        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val json = Json

        private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

        private fun file(name: String): File {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "tests/fixtures/mobile-v1/$name").isFile) dir = dir.parentFile
            return File(checkNotNull(dir) { "tests/fixtures/mobile-v1/$name not found above ${File("").absolutePath}" }, "tests/fixtures/mobile-v1/$name")
        }

        fun MockRequestHandleScope.reply(status: Int, body: String): HttpResponseData = respond(
            body, HttpStatusCode.fromValue(status),
            headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"),
        )
    }
}

class FakeSessions(var token: String?) : SessionProvider {
    var rejected = 0

    override suspend fun bearerToken() = token

    override suspend fun onSessionRejected(rejectedToken: String) {
        if (token == rejectedToken) rejected++
    }
}
