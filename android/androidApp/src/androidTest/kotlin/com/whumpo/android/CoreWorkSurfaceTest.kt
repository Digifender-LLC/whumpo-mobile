package com.whumpo.android

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.core.CoreWorkSurface
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.core.api.coreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.auth.CoreOrganizationSessionController
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class CoreWorkSurfaceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun setUp() = Fixtures.reset()
    @After fun tearDown() = Fixtures.reset()

    @Test fun definiteConflictAllowsANewAssertionOnlyAfterRefreshingTheVersion() = journey(ambiguous = false)
    @Test fun ambiguousOutcomeKeepsItsOriginalAssertionAcrossARefreshedVersion() = journey(ambiguous = true)

    @Test fun nonOwnerWithAVisibleInProgressTaskCannotOfferCompletion() = journey(ambiguous = false, role = "operator")

    private fun journey(ambiguous: Boolean, role: String = "owner") {
        val writes = CopyOnWriteArrayList<Write>()
        val reads = AtomicInteger()
        val digest = "a".repeat(64)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when (path) {
                "/v1/auth/session" -> """{"sessionId":"session-1","orgId":"org-1","userId":"user-1","role":"$role","expiresAt":9000}"""
                "/v1/workspaces", "/v1/projects", "/v1/customers", "/v1/agents/management" -> """{"items":[]}"""
                "/v1/work/tasks" -> {
                    assertEquals("50", request.url.parameters["limit"])
                    val version = if (reads.incrementAndGet() == 1) 1 else 2
                    """{"items":[{"id":"task-1","projectId":"project-1","workspaceId":"workspace-1","title":"Finish checklist","description":null,"parentTaskId":null,"dueAt":null,"status":"in_progress","version":$version}],"counts":{"total":1,"overdue":0,"byStatus":{"backlog":0,"ready":0,"in_progress":1,"blocked":0,"needs_approval":0,"done":0,"canceled":0}},"asOf":"2026-11-19T12:00:00Z"}"""
                }
                "/v1/tasks/task-1/transitions" -> {
                    val write = Write(checkNotNull(request.headers["Idempotency-Key"]), checkNotNull(request.headers[HttpHeaders.IfMatch]), request.body.toByteArray().decodeToString())
                    writes += write
                    if (writes.size == 1 || !ambiguous && write.version == "\"1\"") {
                        val status = if (ambiguous) HttpStatusCode.InternalServerError else HttpStatusCode.Conflict
                        return@MockEngine respond("""{"error":{"code":"${if (ambiguous) "INTERNAL_ERROR" else "VERSION_CONFLICT"}","message":"Task change unavailable","requestId":"request-1","retryable":false}}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    return@MockEngine respond("""{"runId":"run-1","effectId":"effect-1","status":"queued"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/v1/effects/effect-1" -> """{"id":"effect-1","runId":"run-1","state":"queued","version":1,"payloadHash":"$digest","policyRevision":1}"""
                else -> error("Unexpected synthetic Work route")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = coreApiClient(CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test")), engine)
        val sessions = CoreOrganizationSessionController(api, now = { 1000 })
        val active = runBlocking { sessions.establish("A".repeat(43)) }
        try {
            ActivityScenario.launch<MainActivity>(Intent(Fixtures.app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { WhumpoEntryTheme { CoreWorkSurface(sessions, active, onBack = {}) } } }
                awaitText("Finish checklist")
                if (role != "owner") {
                    compose.onNodeWithText("Complete task").assertDoesNotExist()
                    compose.onNodeWithText("Specific assignee: Any matching assignee").assertDoesNotExist()
                    assertEquals(0, writes.size)
                    assertSame(active, sessions.state.value)
                    return@use
                }
                click("Complete task")
                awaitText(if (ambiguous) "The outcome is unconfirmed. Retry the same request or check its verified result." else "This task changed. Refresh Work before trying again.")
                assertEquals(1, writes.size)
                click("Back to Work")
                if (!ambiguous) {
                    click("Complete task")
                    compose.waitUntil(10_000) { writes.size == 2 }
                    awaitText("This task changed. Refresh Work before trying again.")
                    assertEquals(2, writes.size)
                    assertEquals(writes[0], writes[1])
                    click("Back to Work")
                }
                click("Refresh Work list")
                compose.waitUntil(10_000) { reads.get() >= 2 && compose.onAllNodes(androidx.compose.ui.test.hasText("Loading Work…")).fetchSemanticsNodes().isEmpty() }
                awaitText("Finish checklist")
                click("Complete task")
                awaitText("Completion is queued.")
                assertEquals(if (ambiguous) 2 else 3, writes.size)
                if (ambiguous) assertEquals(writes.first(), writes.last()) else {
                    assertNotEquals(writes.first().key, writes.last().key)
                    assertEquals("\"2\"", writes.last().version)
                    assertEquals(writes.first().body, writes.last().body)
                }
                compose.onNodeWithText("Task completion is verified at version 2.").assertDoesNotExist()
                assertSame(active, sessions.state.value)
            }
        } finally { sessions.clear(); api.close() }
    }
    private fun awaitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private data class Write(val key: String, val version: String, val body: String)
}
