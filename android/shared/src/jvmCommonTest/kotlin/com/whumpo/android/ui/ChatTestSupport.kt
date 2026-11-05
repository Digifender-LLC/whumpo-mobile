package com.whumpo.android.ui

import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.InteractionCapabilities
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.SessionProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The frozen contract fixtures (tests/fixtures/mobile-v1), the same files the server contract test and WP4 read. */
internal object ChatFixtures {
    const val BASE = "https://whumpo.com/api/mobile/v1"
    const val SITE_TURN = "44444444-4444-4444-8444-000000000003"
    val SEND_IDS = listOf("44444444-4444-4444-8444-000000000003", "55555555-5555-4555-8555-000000000001")

    private val json = Json

    private fun fixture(name: String): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/mobile-v1/$name").isFile) dir = dir.parentFile
        val root = checkNotNull(dir) { "tests/fixtures/mobile-v1/$name not found above ${File("").absolutePath}" }
        return json.parseToJsonElement(File(root, "tests/fixtures/mobile-v1/$name").readText()).jsonObject
    }

    private val operations = fixture("operations.json")["cases"]!!.jsonArray.map { it.jsonObject }
    private val errors = fixture("errors.json")["cases"]!!.jsonObject
    private val clients = fixture("reviews.json")["clients"]!!.jsonObject

    val token: String = operations.first { it["operation"]!!.jsonPrimitive.content == "session.read" }
        .let { it["request"]!!.jsonObject["headers"]!!.jsonObject["authorization"]!!.jsonPrimitive.content.removePrefix("Bearer ") }

    /** The success body of one operation, as the server sends it. */
    fun body(operation: String): String =
        operations.first { it["operation"]!!.jsonPrimitive.content == operation }["response"]!!.jsonObject["body"]!!.toString()

    /** An error case from errors.json: its status and body. */
    fun error(name: String): Pair<Int, String> =
        errors[name]!!.jsonObject.let { it["status"]!!.jsonPrimitive.int to it["body"]!!.toString() }

    /** `current` declares every control; `withoutSiteControls` lacks `site.confirm`. */
    fun client(name: String): ClientInfo {
        val o = clients[name]!!.jsonObject
        return ClientInfo(
            build = o["build"]!!.jsonPrimitive.int,
            controls = o["controls"]!!.jsonArray.map { it.jsonPrimitive.content },
            interaction = json.decodeFromJsonElement(InteractionCapabilities.serializer(), o["interaction"]!!),
        )
    }
}

internal class TestSessions : SessionProvider {
    override suspend fun bearerToken(): String = ChatFixtures.token

    override suspend fun onSessionRejected() = Unit
}

internal fun testClient(
    info: ClientInfo = ChatFixtures.client("current"),
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) = MobileApiClient(MockEngine(handler), ChatFixtures.BASE, info, TestSessions())

internal fun MockRequestHandleScope.reply(status: Int, body: String): HttpResponseData =
    respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))

internal fun MockRequestHandleScope.reply(error: Pair<Int, String>): HttpResponseData = reply(error.first, error.second)
