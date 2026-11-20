package com.whumpo.core.auth

import com.whumpo.core.api.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CoreWorkQueryTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val token = "A".repeat(43)
    private val cursor = "a".repeat(64)
    private fun task(id: String = "task-1") = """{"id":"$id","projectId":"project-1","workspaceId":"workspace-1","parentTaskId":null,"title":"Follow up","description":null,"dueAt":null,"status":"ready","version":1}"""
    private fun page(items: String = task(), total: Int = 1) = """{"items":[$items],"counts":{"total":$total,"overdue":0,"byStatus":{"backlog":0,"ready":$total,"in_progress":0,"blocked":0,"needs_approval":0,"done":0,"canceled":0}},"asOf":"2026-11-19T12:00:00.000Z"}"""

    @Test
    fun queryBindsAllEffectiveCriteriaAndRetainedCursorWithoutCookies() = runTest {
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertEquals("/v1/work/tasks", request.url.encodedPath)
            assertEquals(mapOf("limit" to "50", "workspaceId" to "workspace-1", "projectId" to "project-1",
                "status" to "ready", "due" to "overdue", "customer" to "associated", "customerId" to "customer-1",
                "assignee" to "assigned", "assigneeAgentId" to "agent-1", "cursor" to cursor),
                request.url.parameters.entries().associate { it.key to it.value.single() })
            respond(page().replace("\"dueAt\":null", "\"dueAt\":\"2026-11-18T12:00:00Z\"")
                .replace("\"overdue\":0", "\"overdue\":1")
                .replace("\"version\":1", "\"version\":1,\"assigneeAgentId\":\"agent-1\",\"assignmentVersion\":1"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        } }, origin)
        try {
            val result = api.workQuery(token, CoreWorkFilters(workspaceId = "workspace-1", projectId = "project-1",
                status = CoreTaskStatus.Ready, due = CoreWorkDue.Overdue, customer = CoreWorkCustomer.Associated,
                customerId = "customer-1", assignee = CoreWorkAssignee.Assigned, assigneeId = "agent-1"), cursor)
            assertEquals(1, result.counts.total)
            assertEquals("workspace-1", result.items.single().workspaceId)
        } finally { api.close() }
    }

    @Test
    fun closedQueryRejectsCoercionUnknownFieldsCountsDuplicatesAndOversizedPages() {
        val invalid = listOf(
            page().replace("\"total\":1", "\"total\":\"1\""),
            page().replace("\"overdue\":0", "\"overdue\":2"),
            page().replace("\"ready\":1", "\"ready\":0"),
            page().replace("\"workspace-1\"", "[\"workspace-1\"]"),
            page().dropLast(1) + ",\"privateField\":true}",
            page(task() + "," + task(), 2),
            page((1..51).joinToString(",") { task("task-$it") }, 51),
            page().replace("2026-11-19T12:00:00.000Z", "2026-11-19T13:00:00+01:00"),
        )
        invalid.forEach { assertFailsWith<CoreProtocolException> { decodeWorkQuery(it) } }
        val valid = decodeWorkQuery(page((1..50).joinToString(",") { task("task-$it") }, 50))
        assertEquals(50, valid.items.size)
        assertEquals(50, valid.counts.byStatus[CoreTaskStatus.Ready])
    }

    @Test
    fun releaseUsesExactOwnedCursorAndRefusesInvalidCriteriaBeforeSending() = runTest {
        var calls = 0
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            calls++
            assertEquals("DELETE", request.method.value)
            assertEquals(setOf("cursor"), request.url.parameters.names())
            assertEquals(cursor, request.url.parameters["cursor"])
            respond("", HttpStatusCode.NoContent)
        } }, origin)
        try {
            api.releaseWorkQuery(token, cursor)
            assertEquals(1, calls)
            assertFailsWith<IllegalArgumentException> { api.releaseWorkQuery(token, "not-a-cursor") }
            assertFailsWith<IllegalArgumentException> {
                api.workQuery(token, CoreWorkFilters(customer = CoreWorkCustomer.Unassociated, customerId = "customer-1"), null)
            }
            assertEquals(1, calls)
        } finally { api.close() }
    }
    @Test
    fun filtersReleaseOwnedFirstCursorAcrossPaginationBeforeTheThirdSnapshot() = runTest {
        val live = mutableSetOf<String>()
        var next = 0
        var releases = 0
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond("""{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}""")
                "/v1/work/tasks" -> {
                    val requested = request.url.parameters["cursor"]
                    if (request.method.value == "DELETE") {
                        assertTrue(live.remove(requested)); releases++
                        respond("", HttpStatusCode.NoContent)
                    } else {
                        val body = if (requested != null) {
                            assertTrue(requested in live)
                            page(task("task-51") + "," + task("task-52"), 52)
                        } else {
                            assertTrue(live.size < 2, "The original two-live-snapshot cap remains enforced")
                            val created = (++next).toString(16).padStart(64, '0'); live += created
                            page((1..50).joinToString(",") { task("task-$it") }, 52).dropLast(1) + ",\"nextCursor\":\"$created\"}"
                        }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> error("Unexpected public contract route")
            }
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreWorkQueryController(sessions, sessions.establish(token))
            controller.apply(CoreWorkFilters())
            controller.more()
            assertEquals(52, controller.state.value.items.size)
            assertEquals(0, releases, "Reading the last page must not discard the owned release cursor")
            repeat(3) { controller.apply(CoreWorkFilters(status = CoreTaskStatus.Ready)) }
            assertEquals(3, releases)
            assertEquals(1, live.size)
            controller.close()
            assertTrue(live.isEmpty())
            assertTrue(controller.state.value.items.isEmpty())
        } finally { api.close() }
    }

    @Test
    fun reenteredViewWaitsForAbandonedSnapshotReleaseBeforeCreatingAnother() = runTest {
        val entered = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        var reads = 0
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond("""{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}""")
                "/v1/work/tasks" -> if (request.method.value == "DELETE") {
                    entered.complete(Unit); allowRelease.await(); respond("", HttpStatusCode.NoContent)
                } else {
                    reads++
                    respond(page((1..50).joinToString(",") { task("task-$it") }, 52).dropLast(1) + ",\"nextCursor\":\"$cursor\"}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected public contract route")
            }
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api) { 1 }
            val active = sessions.establish(token)
            val prior = CoreWorkQueryController(sessions, active)
            prior.apply(CoreWorkFilters()); prior.retire()
            assertTrue(prior.state.value.items.isEmpty())
            val next = CoreWorkQueryController(sessions, active)
            val loading = async { next.apply(CoreWorkFilters()) }
            entered.await()
            assertEquals(1, reads, "A reentered view must await old release acknowledgement")
            assertTrue(next.state.value.items.isEmpty())
            allowRelease.complete(Unit); loading.await()
            assertEquals(2, reads)
            next.close(); prior.close()
        } finally { api.close() }
    }

    @Test
    fun cancelledInitialReadObtainsAndReleasesItsLateCursorWithoutPublishingPrivateRows() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var released = false
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond("""{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}""")
                "/v1/work/tasks" -> if (request.method.value == "DELETE") {
                    assertEquals(cursor, request.url.parameters["cursor"])
                    released = true; respond("", HttpStatusCode.NoContent)
                } else {
                    entered.complete(Unit); release.await()
                    respond(page((1..50).joinToString(",") { task("task-$it") }, 52).dropLast(1) + ",\"nextCursor\":\"$cursor\"}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected public contract route")
            }
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreWorkQueryController(sessions, sessions.establish(token))
            val loading = async { controller.apply(CoreWorkFilters()) }
            entered.await(); loading.cancel(); release.complete(Unit); loading.join()
            assertTrue(released)
            assertTrue(controller.state.value.items.isEmpty())
            controller.close()
        } finally { api.close() }
    }

    @Test
    fun latePageAcrossAccountAndRoleChangeClearsStateAndReleasesOnlyTheOldCursor() = runTest {
        lateScopeChange(denyOldCleanup = false)
    }

    @Test
    fun deniedOldActorCleanupCannotSignOutTheReplacementViewer() = runTest {
        lateScopeChange(denyOldCleanup = true)
    }

    private suspend fun TestScope.lateScopeChange(denyOldCleanup: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val replacement = "E".repeat(43)
        val releaseCalls = mutableListOf<String>()
        val lateCursor = "b".repeat(64)
        var reads = 0
        val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> if (request.headers[HttpHeaders.Authorization] == "Bearer $token")
                    respond("""{"sessionId":"session-old","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}""")
                    else respond("""{"sessionId":"session-new","orgId":"org-2","userId":"user-2","role":"viewer","expiresAt":9000}""")
                "/v1/work/tasks" -> if (request.method.value == "DELETE") {
                    assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
                    val released = requireNotNull(request.url.parameters["cursor"]); releaseCalls += released
                    if (denyOldCleanup && released == lateCursor) respond("", HttpStatusCode.Unauthorized)
                    else respond("", HttpStatusCode.NoContent)
                } else {
                    reads++
                    val next = if (reads == 1) cursor else lateCursor
                    if (reads == 2) { entered.complete(Unit); release.await() }
                    respond(page((1..50).joinToString(",") { task("task-$it") }, 52).dropLast(1) +
                        ",\"nextCursor\":\"$next\"}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("Unexpected public contract route")
            }
        } }, origin)
        try {
            val sessions = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreWorkQueryController(sessions, sessions.establish(token))
            controller.apply(CoreWorkFilters())
            assertEquals(50, controller.state.value.items.size)
            val pending = async { assertFailsWith<CancellationException> { controller.refresh() } }
            entered.await()
            val next = sessions.establish(replacement)
            controller.retire()
            assertTrue(controller.state.value.items.isEmpty())
            assertNull(controller.state.value.page)
            release.complete(Unit); pending.await()
            assertEquals(listOf(cursor, lateCursor), releaseCalls)
            assertEquals(next, sessions.state.value)
            assertEquals(CoreRole.viewer, next.session.role)
            assertTrue(controller.state.value.items.isEmpty())
            controller.close()
        } finally { api.close() }
    }

    @Test
    fun continuedPagesNeverMergeChangedEvaluationTimeOrCounts() = runTest {
        for (alterTime in listOf(true, false)) {
            val api = CoreApiClient(MockEngine.create { dispatcher = StandardTestDispatcher(testScheduler); addHandler { request ->
                when (request.url.encodedPath) {
                    "/v1/auth/session" -> respond("""{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"owner","expiresAt":9000}""")
                    "/v1/work/tasks" -> if (request.method.value == "DELETE") respond("", HttpStatusCode.NoContent)
                    else {
                        val body = if (request.url.parameters["cursor"] == null)
                            page((1..50).joinToString(",") { task("task-$it") }, 52).dropLast(1) + ",\"nextCursor\":\"$cursor\"}"
                        else if (alterTime) page(task("task-51") + "," + task("task-52"), 52)
                            .replace("2026-11-19T12:00:00.000Z", "2026-11-19T12:01:00.000Z")
                        else page(task("task-51") + "," + task("task-52"), 53)
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    else -> error("Unexpected public contract route")
                }
            } }, origin)
            try {
                val sessions = CoreOrganizationSessionController(api) { 1 }
                val controller = CoreWorkQueryController(sessions, sessions.establish(token))
                controller.apply(CoreWorkFilters()); controller.more()
                assertTrue(controller.state.value.items.isEmpty())
                assertNull(controller.state.value.page)
                assertEquals(CoreWorkQueryNotice.Unavailable, controller.state.value.notice)
                controller.close()
            } finally { api.close() }
        }
    }

}
