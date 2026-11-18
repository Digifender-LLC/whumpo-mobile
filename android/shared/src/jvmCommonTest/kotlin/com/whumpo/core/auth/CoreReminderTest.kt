package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreReminderState
import com.whumpo.core.api.CoreReminderTarget
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreReminderTest {
    private val due = "2026-12-01T11:22:33.119Z"
    private val observed = "2026-12-01T11:24:00.000Z"
    private val ackTime = "2026-12-01T11:25:00.000Z"
    private val taskPath = "/v1/tasks/task-1"
    private val statusPath = "$taskPath/reminder"
    private val cancelPath = "/v1/task-reminders/notice-1/cancel"
    private val inbox = "/v1/notifications/task-reminders"
    private val resolve = "$inbox/notice-1/resolve"
    private val ack = "$inbox/notice-1/acknowledge"
    private val target = CoreReminderTarget.fromTask(CoreWorkTask(
        "task-1", "project-1", "Review the quote", null, null, due, CoreTaskStatus.Ready, 4,
    ))!!

    private fun task(project: String = "project-1", version: Int = 4, status: String = "ready") =
        """{"id":"task-1","projectId":"$project","title":"Review the quote","description":null,"parentTaskId":null,"status":"$status","version":$version,"dueAt":"$due"}"""

    private fun reminder(state: String, project: String = "project-1", id: String = "notice-1") =
        """{"id":"$id","taskId":"task-1","projectId":"$project","taskVersion":4,"dueAt":"$due","state":"$state"}"""

    private fun status(state: String, project: String = "project-1") =
        """{"reminder":${reminder(state, project)}}"""

    private fun notice(id: String = "notice-1", deadline: String = due) =
        """{"id":"$id","dueAt":"$deadline","observedAt":"$observed"}"""

    private fun resolved(project: String = "project-1", id: String = "notice-1") =
        """{"id":"$id","taskId":"task-1","projectId":"$project","taskVersion":4,"dueAt":"$due"}"""

    private data class Call(val path: String, val method: HttpMethod, val body: String?, val key: String?)
    private data class Reply(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

    private inner class Fixture(respondTo: suspend (Call) -> Reply) {
        val calls = mutableListOf<Call>()
        private val first = "A".repeat(43)
        private val alternate = "E".repeat(43)
        private val origin = CoreOrigin.allowlisted("https://api.example.test", setOf("https://api.example.test"))
        private val json = headersOf(HttpHeaders.ContentType, "application/json")
        private val engine = MockEngine { request ->
            val path = request.url.encodedPathAndQuery
            assertEquals("api.example.test", request.url.host)
            val bearer = request.headers[HttpHeaders.Authorization]
            if (path == "/v1/auth/session") {
                val second = bearer == "Bearer $alternate"
                val org = if (second) "org-other" else "org-current"
                val session = if (second) "session-2" else "session-1"
                respond("""{"sessionId":"$session","orgId":"$org","userId":"member","role":"viewer","expiresAt":9000}""", headers = json)
            } else {
                assertEquals("Bearer $first", bearer)
                val body = if (request.method == HttpMethod.Post) request.body.toByteArray().decodeToString() else null
                val call = Call(path, request.method, body, request.headers["Idempotency-Key"])
                calls += call
                val reply = respondTo(call)
                respond(reply.body, reply.status, json)
            }
        }

        suspend fun use(block: suspend (CoreOrganizationSessionController, CoreOrganizationSessionController.State.Active) -> Unit) {
            CoreApiClient(engine, origin).use { api ->
                val session = CoreOrganizationSessionController(api) { 1 }
                block(session, session.establish(first))
            }
        }

        suspend fun switch(session: CoreOrganizationSessionController) = session.establish(alternate)
    }

    @Test fun replaysTheSameArmAndReadsAnUncertainCancelWithoutChangingTheTask() = runTest {
        var arms = 0
        var cancels = 0
        val fixture = Fixture {
            when {
                it.path == taskPath -> Reply(task())
                it.path == cancelPath -> {
                    cancels++
                    Reply("{}", HttpStatusCode.ServiceUnavailable)
                }
                it.path == statusPath && it.method == HttpMethod.Post ->
                    if (++arms == 1) Reply("{}", HttpStatusCode.ServiceUnavailable) else Reply(reminder("armed"))
                it.path == statusPath && cancels == 1 -> Reply(status("canceled"))
                it.path == statusPath && arms == 2 -> Reply(status("armed"))
                it.path == statusPath -> Reply("""{"reminder":null}""")
                else -> error("Unexpected request")
            }
        }
        fixture.use { sessions, active ->
            val initial = sessions.reminderStatus(active, target) { true }
            val attempt = assertNotNull(initial.armAttempt())
            assertFailsWith<CoreHttpException> { sessions.submitReminder(active, attempt) { true } }
            sessions.submitReminder(active, attempt) { true }
            val armed = sessions.reminderStatus(active, target) { true }
            assertTrue(armed.settles(attempt))
            val cancel = assertNotNull(armed.cancelAttempt())
            assertFailsWith<CoreHttpException> { sessions.submitReminder(active, cancel) { true } }
            val canceled = sessions.reminderStatus(active, target) { true }
            assertTrue(canceled.settles(cancel))
            assertEquals(CoreReminderState.Canceled, canceled.reminder?.state)
            assertNotEquals(attempt.key, assertNotNull(canceled.armAttempt()).key)
        }
        val posts = fixture.calls.filter { it.method == HttpMethod.Post }
        assertEquals(listOf(statusPath, statusPath, cancelPath), posts.map(Call::path))
        assertNotNull(posts.first().key)
        assertEquals(posts.first(), posts[1])
        assertEquals("""{"taskVersion":4,"dueAt":"$due"}""", posts.first().body)
        assertEquals("{}", posts.last().body)
        assertNull(posts.last().key)
    }

    @Test fun resolvesTheExactCurrentWorkItemBeforeSeparateReadAcknowledgment() = runTest {
        val fixture = Fixture {
            Reply(when (it.path) {
                inbox -> """{"items":[${notice()}]}"""
                resolve -> resolved()
                taskPath -> task()
                ack -> """{"id":"notice-1","acknowledgedAt":"$ackTime"}"""
                else -> error("Unexpected request")
            })
        }
        fixture.use { sessions, active ->
            val page = sessions.reminderPage(active) { true }
            val item = page.items.single()
            assertNull(item.acknowledgedAt)
            val result = sessions.openReminder(active, page, item) { true }
            assertEquals(target, CoreReminderTarget.fromTask(result))
            assertEquals(listOf(inbox, resolve, taskPath), fixture.calls.map(Call::path))
            assertTrue(fixture.calls.all { it.method == HttpMethod.Get })
            val marked = sessions.markReminderRead(active, page, item) { true }
            assertEquals(ackTime, marked.items.single().acknowledgedAt)
            assertNull(item.acknowledgedAt)
            assertEquals(Call(ack, HttpMethod.Post, "{}", null), fixture.calls.last())
        }
    }

    @Test fun refusesChangedTaskAndStopsWhenTheNoticeClosesOrChangesAccount() = runTest {
        var current = true
        var workReads = 0
        val fixture = Fixture {
            Reply(when (it.path) {
                taskPath -> task(version = if (++workReads == 1) 4 else 5)
                statusPath -> """{"reminder":null}"""
                inbox -> """{"items":[${notice()}]}"""
                resolve -> { current = false; resolved() }
                else -> error("Unexpected request")
            })
        }
        fixture.use { sessions, active ->
            val attempt = assertNotNull(sessions.reminderStatus(active, target) { true }.armAttempt())
            assertFailsWith<CoreReminderChangedException> { sessions.submitReminder(active, attempt) { true } }
            val page = sessions.reminderPage(active) { true }
            val item = page.items.single()
            assertFailsWith<CancellationException> { sessions.openReminder(active, page, item) { current } }
            val alternate = fixture.switch(sessions)
            assertFailsWith<CancellationException> { sessions.markReminderRead(alternate, page, item) { true } }
            assertFailsWith<CancellationException> { sessions.submitReminder(alternate, attempt) { true } }
            assertTrue(fixture.calls.all { it.method == HttpMethod.Get })
            assertEquals(listOf(taskPath, statusPath, taskPath, inbox, resolve), fixture.calls.map(Call::path))
        }
    }

    @Test fun refusesMalformedPageAndMismatchedResolutionAndReportsOnlyTheSelectedStatus() = runTest {
        var reads = 0
        var statusReads = 0
        val fixture = Fixture {
            Reply(when (it.path) {
                inbox ->
                    if (++reads == 1) """{"items":[${notice(deadline = "2026-12-01T11:22:60.000Z")}]}"""
                    else """{"items":[${notice()}]}"""
                resolve -> resolved(project = "project-other")
                taskPath -> task()
                statusPath -> if (++statusReads == 1) status("canceled", "project-other") else status("armed", "project-other")
                else -> error("Unexpected request")
            })
        }
        fixture.use { sessions, active ->
            assertFailsWith<CoreProtocolException> { sessions.reminderPage(active) { true } }
            val page = sessions.reminderPage(active) { true }
            assertFailsWith<CoreReminderChangedException> { sessions.openReminder(active, page, page.items.single()) { true } }
            assertNull(sessions.reminderStatus(active, target) { true }.reminder)
            assertFailsWith<CoreProtocolException> { sessions.reminderStatus(active, target) { true } }
            assertTrue(fixture.calls.all { it.method == HttpMethod.Get })
        }
    }

    @Test fun reminderPagingUsesOnlyTheCurrentBoundAnchor() = runTest {
        val names = (1..25).map { "rem-$it" }
        val fixture = Fixture {
            Reply(when (it.path) {
                inbox -> """{"items":[${names.joinToString(",") { id -> notice(id) }}],"nextCursor":"rem-25"}"""
                "$inbox?before=rem-25" -> """{"items":[${notice("rem-26")}]}"""
                else -> error("Unexpected request")
            })
        }
        fixture.use { sessions, active ->
            val older = sessions.reminderPage(active, sessions.reminderPage(active) { true }) { true }
            assertEquals(names + "rem-26", older.items.map { it.id })
            assertEquals(listOf(inbox, "$inbox?before=rem-25"), fixture.calls.map(Call::path))
        }
    }
}
