package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkLink
import com.whumpo.core.api.CoreWorkWebOrigin
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoreWorkLinkTest {
    private val apiOrigin = CoreOrigin.allowlisted("https://api.example.test", setOf("https://api.example.test"))
    private val web = assertNotNull(CoreWorkWebOrigin.parse("https://work.example.test"))
    private val firstEvent = "a".repeat(32)
    private val laterEvent = "b".repeat(32)
    private val firstToken = "A".repeat(43)
    private val replacementToken = "E".repeat(43)
    private val firstBookmark = "/v1/tasks/task-1/history?cursor=$firstEvent&limit=1"
    private val earliest = "/v1/tasks/task-1/history?limit=1"
    private val detail = "/v1/tasks/task-1"
    private val date = "2026-11-16T11:00:00.000Z"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun link(event: String = firstEvent, path: String = "/") =
        assertNotNull(CoreWorkLink.parse("https://work.example.test$path#work/task/v1/task-1/$event", web))

    private fun event(id: String, version: Int, operation: String, snapshot: String = "") =
        """{"items":[{"id":"$id","taskVersion":$version,"operation":"$operation","createdAt":"$date"$snapshot}]}"""

    private fun task(id: String = "task-1", version: Int = 3) =
        """{"id":"$id","projectId":"new-project","title":"Updated task","description":"","parentTaskId":null,
            "dueAt":null,"status":"in_progress","version":$version,"assigneeAgentId":null,"assignmentVersion":2}"""

    private data class Response(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

    private inner class Fixture(
        private val response: suspend (String) -> Response,
    ) {
        val requests = ConcurrentLinkedQueue<String>()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("api.example.test", request.url.host)
            assertNull(request.headers[HttpHeaders.Origin])
            val token = request.headers[HttpHeaders.Authorization]
            val path = request.url.encodedPath
            if (path == "/v1/auth/session") {
                assertTrue(token == "Bearer $firstToken" || token == "Bearer $replacementToken")
                val id = if (token == "Bearer $firstToken") "session-1" else "session-2"
                val org = if (token == "Bearer $firstToken") "selected-org" else "other-org"
                respond("""{"sessionId":"$id","orgId":"$org","userId":"actor-1","role":"viewer","expiresAt":9000}""",
                    headers = json)
            } else {
                assertEquals("Bearer $firstToken", token)
                val target = request.url.encodedPathAndQuery
                requests.add(target)
                val result = response(target)
                respond(result.body, result.status, json)
            }
        }

        suspend fun use(block: suspend (CoreOrganizationSessionController, CoreOrganizationSessionController.State.Active) -> Unit) {
            CoreApiClient(engine, apiOrigin).use { api ->
                val sessions = CoreOrganizationSessionController(api) { 1 }
                val active = sessions.establish(firstToken)
                block(sessions, active)
            }
        }
    }

    @Test
    fun permitsOnlyTheConfiguredHttpsWorkPathsAndBoundedOpaqueVersion() {
        link()
        val alias = link(path = "/work-link")
        assertEquals("task-1", alias.taskId)
        assertEquals(firstEvent, alias.creationEventId)
        assertNotNull(CoreWorkLink.parse("https://work.example.test:443/#work/task/v1/a_~.9/$firstEvent", web))
        val alternate = assertNotNull(CoreWorkWebOrigin.parse("https://work.example.test:8443"))
        assertNotNull(CoreWorkLink.parse("https://work.example.test:8443/#work/task/v1/task-1/$firstEvent", alternate))
        assertNotNull(CoreWorkLink.parse("https://work.example.test:8443/work-link#work/task/v1/task-1/$firstEvent", alternate))
        assertNull(CoreWorkLink.parse("https://work.example.test/#work/task/v1/task-1/$firstEvent", alternate))
        assertNull(CoreWorkLink.parse("https://work.example.test/work-link#work/task/v1/task-1/$firstEvent", alternate))
        listOf(
            "https://work.example.test/#work/task/v2/task-1/$firstEvent",
            "https://work.example.test/work-link/#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/work-link.html#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/%77ork-link#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/work%2Dlink#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/work-link%2F#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/./work-link#work/task/v1/task-1/$firstEvent",
            "https://work.example.test//work-link#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/Work-Link#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/work-link?#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/work-link?x=1#work/task/v1/task-1/$firstEvent",
            "https://work.example.test#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/?x=1#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/#work/task/v1/task%2F1/$firstEvent",
            "https://work.example.test/#work/task/v1/task-1/${firstEvent.uppercase()}",
            "https://work.example.test/#work/task/v1/${"t".repeat(129)}/$firstEvent",
            "https://work.example.test.attacker.invalid/#work/task/v1/task-1/$firstEvent",
            "https://actor@work.example.test/#work/task/v1/task-1/$firstEvent",
            "http://work.example.test/#work/task/v1/task-1/$firstEvent",
            "https://work.example.test/#work/task/v1/task-1/$firstEvent\n",
        ).forEach { assertNull(CoreWorkLink.parse(it, web), it) }
        listOf("", "https://work.example.test/", "https://work.example.test/work-link", "https://work.example.test:65536",
            "https://host-.example.test")
            .forEach { assertNull(CoreWorkWebOrigin.parse(it)) }
    }

    @Test
    fun completeAddressBoundIncludesTheLongestLiteralAlias() {
        val host = listOf(63, 63, 63, 61).joinToString(".") { "a".repeat(it) }
        val authority = "https://$host:65535"
        val origin = assertNotNull(CoreWorkWebOrigin.parse(authority))
        val taskId = "t".repeat(128)
        val fragment = "#work/task/v1/$taskId/$firstEvent"
        val root = "$authority/$fragment"
        val alias = "$authority/work-link$fragment"
        assertEquals(443, root.length)
        assertEquals(CoreWorkLink.maxLength, alias.length)
        assertEquals(taskId, assertNotNull(CoreWorkLink.parse(root, origin)).taskId)
        assertEquals(taskId, assertNotNull(CoreWorkLink.parse(alias, origin)).taskId)
        assertNull(CoreWorkLink.parse(alias + "a", origin))
    }

    @Test
    fun matchesBookmarkAndCreationBeforeReadingTheExactCurrentTask() = runTest {
        for ((path, bookmark, creation) in listOf(
            Triple("/", """{"items":[]}""", event(firstEvent, 1, "tasks.create@1")),
            Triple("/work-link", event(laterEvent, 2, "tasks.move@1", ""","snapshot":{"id":"task-1","version":2}"""),
                event(firstEvent, 1, "tasks.create_selected@1", ""","snapshot":{"id":"task-1","version":1}""")),
        )) {
            val fixture = Fixture { path ->
                Response(when (path) {
                    firstBookmark -> bookmark
                    earliest -> creation
                    detail -> task()
                    else -> error("Unexpected request")
                })
            }
            fixture.use { sessions, active ->
                val current = sessions.linkedWorkTask(active, link(path = path)) { true }
                assertEquals("new-project", current.projectId)
                assertEquals("Updated task", current.title)
                assertEquals("", current.description)
                assertEquals(CoreTaskStatus.InProgress, current.status)
                assertEquals(listOf(firstBookmark, earliest, detail), fixture.requests.toList())
            }
        }
    }

    @Test
    fun wrongScopeAndARealLaterBookmarkCannotReadTaskContents() = runTest {
        val foreign = Fixture { Response("{}", HttpStatusCode.NotFound) }
        foreign.use { sessions, active ->
            assertFailsWith<CoreHttpException> { sessions.linkedWorkTask(active, link()) { true } }
            assertEquals(listOf(firstBookmark), foreign.requests.toList())
        }
        val later = Fixture { path ->
            Response(if (path == earliest) event(firstEvent, 1, "tasks.create@1") else """{"items":[]}""")
        }
        later.use { sessions, active ->
            assertFailsWith<CoreProtocolException> { sessions.linkedWorkTask(active, link(laterEvent)) { true } }
            assertEquals(listOf("/v1/tasks/task-1/history?cursor=$laterEvent&limit=1", earliest), later.requests.toList())
        }
    }

    @Test
    fun corruptProofAndStaleOrMalformedCurrentRecordsAreRejected() = runTest {
        for (badCreation in listOf(
            event(firstEvent, 2, "tasks.create@1"),
            event(firstEvent, 1, "tasks.update@1"),
            event(firstEvent, 1, "tasks.create@1", ""","snapshot":{"id":"other-task","version":1}"""),
            """{"items":[],"nextCursor":"$firstEvent"}""",
        )) {
            val fixture = Fixture { path -> Response(if (path == firstBookmark) """{"items":[]}""" else badCreation) }
            fixture.use { sessions, active ->
                assertFailsWith<CoreProtocolException> { sessions.linkedWorkTask(active, link()) { true } }
                assertEquals(listOf(firstBookmark, earliest), fixture.requests.toList())
            }
        }
        for (badTask in listOf(task(id = "other-task"), task(version = 2), task().replace("\"dueAt\":null", "\"dueAt\":\"invalid\""))) {
            val fixture = Fixture { path ->
                Response(when (path) {
                    firstBookmark -> event(laterEvent, 3, "tasks.update@1")
                    earliest -> event(firstEvent, 1, "tasks.create@1")
                    detail -> badTask
                    else -> error("Unexpected request")
                })
            }
            fixture.use { sessions, active ->
                assertFailsWith<CoreProtocolException> { sessions.linkedWorkTask(active, link()) { true } }
                assertEquals(listOf(firstBookmark, earliest, detail), fixture.requests.toList())
            }
        }
    }

    @Test
    fun lateDenialFromAClosedViewLeavesTheSameSessionAndStopsFollowOnReads() = runTest {
        val visible = AtomicBoolean(true)
        val fixture = Fixture {
            visible.set(false)
            Response("{}", HttpStatusCode.Unauthorized)
        }
        fixture.use { sessions, active ->
            assertFailsWith<CancellationException> { sessions.linkedWorkTask(active, link(), visible::get) }
            assertSame(active, sessions.state.value)
            assertEquals(listOf(firstBookmark), fixture.requests.toList())
        }
    }

    @Test
    fun replacementDuringCreationReadPreventsTheOldViewFromReadingTaskDetail() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture { path ->
            if (path == firstBookmark) Response("""{"items":[]}""") else {
                waiting.complete(Unit)
                release.await()
                Response(event(firstEvent, 1, "tasks.create@1"))
            }
        }
        fixture.use { sessions, active ->
            val reading = launch {
                assertFailsWith<CancellationException> { sessions.linkedWorkTask(active, link()) { true } }
            }
            waiting.await()
            val replacement = sessions.establish(replacementToken)
            release.complete(Unit)
            reading.join()
            assertSame(replacement, sessions.state.value)
            assertEquals(listOf(firstBookmark, earliest), fixture.requests.toList())
        }
    }
}
