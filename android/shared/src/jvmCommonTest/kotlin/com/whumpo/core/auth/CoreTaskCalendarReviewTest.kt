package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTaskCalendarReviewTest {
    private val due = "2026-11-18T14:30:00.000Z"
    private val task = CoreWorkTask("task-a", "project-a", "Review deadline", "private description", null, due,
        CoreTaskStatus.Ready, 6L)

    @Test
    fun closedOrNoncanonicalTasksCannotBeReviewed() {
        for (invalid in listOf(
            task.copy(dueAt = null), task.copy(dueAt = "2026-02-30T14:30:00.000Z"),
            task.copy(dueAt = "2026-11-18T14:30:00Z"), task.copy(dueAt = "2026-11-18T14:30:00.000+00:00"),
            task.copy(archivedAt = due), task.copy(status = CoreTaskStatus.Done), task.copy(status = CoreTaskStatus.Canceled),
        )) assertNull(CoreTaskCalendarReview.from(invalid))
        val accepted = assertNotNull(CoreTaskCalendarReview.from(task))
        assertEquals(Instant.parse(due).toEpochMilli(), accepted.startMillis)
        assertEquals(1_800_000L, accepted.endMillis - accepted.startMillis)
        assertEquals(task.title, accepted.title)
    }

    @Test
    fun reviewAndConfirmEachReadExactTaskAndLateSelectionCannotUseAValidReply() = runTest {
        val bearer = "A".repeat(43)
        val host = "https://calendar.example"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val routes = mutableListOf<String>()
        val contentType = headersOf(HttpHeaders.ContentType, "application/json")
        var response = task
        var hold = false
        CoreApiClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("Bearer $bearer", request.headers[HttpHeaders.Authorization])
            val path = request.url.encodedPath
            routes += path
            val body = when (path) {
                "/v1/auth/session" -> Json.encodeToString(CoreSession("session-a", "org-a", "actor-a", CoreRole.viewer, 60_000L))
                "/v1/tasks/task-a" -> {
                    if (hold) {
                        entered.complete(Unit)
                        release.await()
                    }
                    Json.encodeToString(response)
                }
                else -> error("Unexpected review route")
            }
            respond(body, headers = contentType)
        }, CoreOrigin.allowlisted(host, setOf(host))).use { api ->
            val controller = CoreOrganizationSessionController(api) { 0L }
            val active = controller.establish(bearer)
            var selected = true
            val current = { selected && controller.commitIfCurrent(active.epoch) {} }
            val reviewed = assertNotNull(CoreTaskCalendarReview.from(task))
            assertTrue(reviewed.verify(current, controller::task))
            for (changed in listOf(task.copy(title = "Different"), task.copy(dueAt = "2026-11-18T15:00:00.000Z"),
                task.copy(version = 7L), task.copy(status = CoreTaskStatus.InProgress))) {
                response = changed
                assertFalse(reviewed.verify(current, controller::task))
            }
            response = task
            hold = true
            val late = async { reviewed.verify(current, controller::task) }
            entered.await()
            selected = false
            release.complete(Unit)
            assertFalse(late.await())
            assertFalse(reviewed.verify(current, controller::task))
            assertEquals(listOf("/v1/auth/session") + List(6) { "/v1/tasks/task-a" }, routes)
        }
    }
}
