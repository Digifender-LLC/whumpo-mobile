package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.CoreSourceHistoryOrigin
import com.whumpo.core.api.decodeSourceHistory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreSourceHistoryTest {
    private val cursor = "21.2." + "c".repeat(64)
    private val project = CoreProjectChoice("project-a", "workspace-a", "Site")

    @Test
    fun validatesTheClosedPageAndProjectsOnlyDisplayData() {
        val first = decodeSourceHistory(page((21 downTo 2).toList(), 21, cursor), project.id, null)
        assertEquals((21L downTo 2L).toList(), first.entries.map { it.version })
        assertEquals(CoreSourceHistoryOrigin.Save, first.entries.first().origin)
        assertEquals(listOf(1L), decodeSourceHistory(page(listOf(1), 21), project.id, first).entries.map { it.version })
        for (invalid in listOf(
            page(listOf(2, 1), 2).replace("\"headVersion\":2", "\"headVersion\":\"2\""),
            page(listOf(1, 2), 2),
            page(listOf(2, 1), 2).replace("\"selectedSlot\":null", "\"selectedSlot\":\"site\""),
            page(listOf(2, 1), 2).replace("\"projectId\":", "\"unexpected\":1,\"projectId\":"),
            page(listOf(2, 1), 2).replace(project.id, "other"),
            page(listOf(2, 1), 2, cursor),
        )) assertFailsWith<CoreProtocolException> { decodeSourceHistory(invalid, project.id, null) }
        assertFailsWith<CoreProtocolException> { decodeSourceHistory(page(listOf(3), 21), project.id, first) }
        assertFailsWith<CoreProtocolException> { decodeSourceHistory(page(listOf(1), 22), project.id, first) }
    }

    @Test
    fun usesExactGetCursorAndRejectsTheOldSessionOnLateReply() = runTest {
        val bearer = "A".repeat(43)
        val replacement = "E".repeat(43)
        val host = "https://history.example"
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        val cursors = mutableListOf<String?>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var conflict = false
        var hold = false
        CoreApiClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            when (request.url.encodedPath) {
                "/v1/auth/session" -> {
                    val original = request.headers[HttpHeaders.Authorization] == "Bearer $bearer"
                    respond(Json.encodeToString(CoreSession(
                        if (original) "first" else "second", "org-a", "actor-a", CoreRole.viewer, 60_000L,
                    )), headers = headers)
                }
                "/v1/projects/project-a/git/history" -> {
                    assertEquals("Bearer $bearer", request.headers[HttpHeaders.Authorization])
                    assertEquals("20", request.url.parameters["limit"])
                    val after = request.url.parameters["cursor"]
                    cursors += after
                    if (hold) {
                        entered.complete(Unit)
                        release.await()
                    }
                    if (conflict) respond("""{"error":{"code":"VERSION_CONFLICT"}}""", HttpStatusCode.Conflict, headers)
                    else respond(if (after == null) page((21 downTo 2).toList(), 21, cursor) else page(listOf(1), 21), headers = headers)
                }
                else -> error("Unexpected source history request")
            }
        }, CoreOrigin.allowlisted(host, setOf(host))).use { api ->
            val controller = CoreOrganizationSessionController(api) { 0L }
            val active = controller.establish(bearer)
            val first = controller.sourceHistory(active, project)
            assertTrue(first.hasOlderVersions)
            val last = controller.sourceHistory(active, project, first)
            assertFalse(last.hasOlderVersions)
            conflict = true
            assertEquals(409, assertFailsWith<CoreHttpException> { controller.sourceHistory(active, project, first) }.status)
            conflict = false
            assertEquals(first.entries, controller.sourceHistory(active, project).entries)
            hold = true
            val pending = async { controller.sourceHistory(active, project) }
            entered.await()
            val next = controller.establish(replacement)
            release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            assertFailsWith<IllegalArgumentException> { controller.sourceHistory(next, project, first) }
            assertEquals(listOf(null, cursor, cursor, null, null), cursors)
        }
    }

    private fun page(versions: List<Int>, head: Int, next: String? = null): String {
        val rows = versions.joinToString(",") { version ->
            """{"version":$version,"confirmedAt":1,"origin":"source-save","commit":"${"a".repeat(40)}","contentDigest":"${"b".repeat(64)}","provenance":"source-bound","selectedSlot":null}"""
        }
        return """{"projectId":"${project.id}","headVersion":$head,"entries":[$rows],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""
    }
}
