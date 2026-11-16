package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRestoreResult
import com.whumpo.core.auth.CoreOrganizationSessionController.RecoveryAction
import com.whumpo.core.auth.CoreOrganizationSessionController.RecoveryOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class CoreApplicationRecoveryTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val token = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val project = CoreProjectChoice("project-1", "workspace-1", "Operations")
    private val point = "12345678-1234-1234-1234-123456789001"
    private val program = "12345678-1234-1234-1234-123456789002"
    private val review = "12345678-1234-1234-1234-123456789003"
    private val hash = "a".repeat(64)
    private val pointsPath = "/v1/application-recovery-points"
    private val reviewPath = "/v1/application-restore-reviews"
    private val disabled = """{"projectId":"project-1","revision":4,"enabled":false,"runtimeEligible":false,"programId":null,"testId":null,"accessId":null,"disableReviewId":null}"""
    private fun session(org: String = "org-1") =
        """{"sessionId":"session-$org","orgId":"$org","userId":"user-1","role":"owner","expiresAt":9000}"""
    private fun point(removed: Boolean = false, count: Int = 500, scope: String = project.id) =
        """{"id":"$point","projectId":"$scope","programId":"$program","applicationRevision":4,"createdAt":1234,"retainedDocuments":$count,"removed":$removed}"""
    private fun waiting() =
        """{"id":"$review","pointId":"$point","projectId":"project-1","programId":"$program","expectedApplicationRevision":4,"documents":500,"expiresAt":8000,"state":"waiting_approval","payloadHash":"$hash"}"""
    private fun completed(receiptHash: String = hash) =
        """{"reviewId":"$review","state":"compacted","outcome":"applied","payloadHash":"$receiptHash","receipt":{"reviewId":"$review","restoredDocuments":2,"confirmedAt":3000}}"""

    @Test
    fun disabledRecoveryUsesExactBodiesAndReplaysRemovedCapture() = runTest {
        val confirmRequests = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            assertNull(request.headers["Origin"])
            assertEquals("Bearer $token", request.headers["Authorization"])
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            if (request.method == HttpMethod.Post) assertNotNull(request.headers["Idempotency-Key"])
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session())
                "/v1/application-state" -> {
                    assertEquals("project-1", request.url.parameters["projectId"])
                    respond(disabled)
                }
                pointsPath -> if (request.method == HttpMethod.Get) {
                    assertEquals("project-1", request.url.parameters["projectId"])
                    respond("""{"points":[${point()}]}""")
                } else {
                    assertTrue(request.url.parameters.isEmpty())
                    assertEquals(Json.parseToJsonElement("""{"projectId":"project-1","expectedApplicationRevision":4}"""),
                        Json.parseToJsonElement(assertNotNull(body)))
                    respond(point(removed = true))
                }
                "$pointsPath/$point/remove" -> {
                    assertEquals("""{"confirmation":"REMOVE"}""", body)
                    respond("""{"id":"$point","removed":true}""")
                }
                reviewPath -> {
                    assertEquals(Json.parseToJsonElement("""{"projectId":"project-1","expectedApplicationRevision":4,"pointId":"$point"}"""),
                        Json.parseToJsonElement(assertNotNull(body)))
                    respond(waiting())
                }
                "$reviewPath/$review" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertNull(request.headers["Idempotency-Key"])
                    respond(completed())
                }
                "$reviewPath/$review/confirm" -> {
                    assertEquals(Json.parseToJsonElement("""{"payloadHash":"$hash","confirmation":"RESTORE"}"""),
                        Json.parseToJsonElement(assertNotNull(body)))
                    confirmRequests += assertNotNull(request.headers["Idempotency-Key"]) to body
                    if (confirmRequests.size == 1) respond("", HttpStatusCode.GatewayTimeout) else respond(completed())
                }
                else -> error("Unexpected recovery route")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(token)
            val scope = controller.recoveryProject(active.epoch, project)
            val saved = controller.recovery(scope)
            assertNull(saved.state.programId)
            assertTrue(saved.state.canRestore)
            val retained = saved.points.single()
            assertEquals(500, retained.retainedDocuments)
            suspend fun write(action: RecoveryAction) = controller.writeRecovery(controller.prepareRecoveryAttempt(scope, action))
            assertTrue(assertIs<RecoveryOutcome.Captured>(write(RecoveryAction.Capture(4))).point.removed)
            assertEquals(RecoveryOutcome.Removed, write(RecoveryAction.Remove(retained, "REMOVE")))
            val r = assertIs<CoreRestoreResult.Review>(
                assertIs<RecoveryOutcome.Reviewed>(write(RecoveryAction.Prepare(retained, 4))).result)
            val attempt = controller.prepareRecoveryAttempt(scope, RecoveryAction.Confirm(r, "RESTORE"))
            assertEquals(504, assertFailsWith<CoreHttpException> { controller.writeRecovery(attempt) }.status)
            assertEquals(2, controller.readApplicationRestore(scope, r).receipt?.restoredDocuments)
            assertIs<RecoveryOutcome.Reviewed>(controller.writeRecovery(attempt))
            assertEquals(confirmRequests[0], confirmRequests[1])
        }
    }

    @Test
    fun closedMetadataAndOldOrganizationRepliesCannotLeakOrClearNewSession() = runTest {
        var response = """{"points":[${point(count = 501)}]}"""
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delayReply = false
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (request.headers["Authorization"] == "Bearer $token") "org-1" else "org-2"))
                "/v1/application-state" -> respond(disabled)
                pointsPath -> if (delayReply) {
                    started.complete(Unit)
                    release.await()
                    respond("", HttpStatusCode.Unauthorized)
                } else respond(response)
                else -> error("Unexpected recovery route")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(token)
            val scope = controller.recoveryProject(active.epoch, project)
            assertFailsWith<CoreProtocolException> { controller.recovery(scope) }
            response = """{"points":[${point(scope = "project-2")}]}"""
            assertFailsWith<CoreProtocolException> { controller.recovery(scope) }
            response = """{"points":[],"visitorValues":["private"]}"""
            assertFailsWith<SerializationException> { controller.recovery(scope) }

            delayReply = true
            val late = async { controller.recovery(scope) }
            started.await()
            val current = controller.establish(replacement)
            release.complete(Unit)
            assertFailsWith<CancellationException> { late.await() }
            assertEquals(current, controller.state.value)
            assertFailsWith<CancellationException> { controller.recovery(scope) }
        }
    }
}
