package com.whumpo.core.auth

import com.whumpo.core.api.CORE_TODAY_MAX_BYTES
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreTodayDecisionAccess
import com.whumpo.core.api.CoreTodayDueState
import com.whumpo.core.api.CoreTodaySiteKind
import com.whumpo.core.api.CoreTodayTaskOperation
import com.whumpo.core.api.CoreWebsiteOperation
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTodayTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val source = "A".repeat(43)
    private val replacement = "E".repeat(43)
    private val digest = "a".repeat(64)
    private val observed = "2026-11-14T10:00:00.000Z"

    private fun session(org: String) =
        """{"sessionId":"session-$org","orgId":"$org","userId":"user-1","role":"owner","expiresAt":9000}"""

    private fun blocked(task: String = "task-1", title: String = "Fix checkout") =
        """{"provenance":"source_record","observedAt":"$observed","source":{"kind":"task","taskId":"$task",""" +
            """"projectId":"project-1","version":2},"title":"$title","status":"blocked","dueAt":null}"""

    private fun deadline(task: String = "task-2", status: String = "in_progress") =
        """{"provenance":"source_record","observedAt":"$observed","source":{"kind":"task","taskId":"$task",""" +
            """"projectId":"project-1","version":5},"title":"Send proposal","status":"$status",""" +
            """"dueAt":"2026-11-14T08:00:00.000Z","dueState":"overdue"}"""

    private fun approval(effect: String = "effect-9") =
        """{"provenance":"source_record","observedAt":"$observed","source":{"kind":"approval","approvalId":"approval-1",""" +
            """"effectId":"$effect","runId":"run-9","version":1},"expiresAt":"2026-11-15T10:00:00.000Z",""" +
            """"decisionAccess":"reauthentication_required"}"""

    private fun verified(effect: String = "effect-3") =
        """{"provenance":"source_record","observedAt":"$observed","source":{"kind":"effect_receipt",""" +
            """"effectId":"$effect","receiptId":"receipt-3","runId":"run-3"},"operation":"tasks.transition@1"}"""

    private fun siteReceipt(version: Long = 4, effect: String = "effect-4", artifact: String = digest) =
        """{"provenance":"source_record","observedAt":"$observed","source":{"kind":"site_publication_receipt",""" +
            """"operation":"sites.publish@1","effectId":"$effect","targetId":"site-1","projectId":"project-1",""" +
            """"taskId":"task-1","buildId":"build-4","targetVersion":$version,"artifactDigest":"$artifact"}}"""

    private val siteReview =
        """{"provenance":"source_record","observedAt":"$observed","createdAt":null,"source":{"kind":"site_withdrawal_review",""" +
            """"operation":"sites.withdraw@1","effectId":"effect-5","targetId":"site-1","projectId":"project-1",""" +
            """"taskId":"task-1","buildId":"build-4","targetVersion":4,"artifactDigest":"$digest"},"decisionAccess":"available"}"""

    private fun section(vararg items: String, truncated: Boolean = false) =
        """{"items":[${items.joinToString(",")}],"truncated":$truncated}"""

    private fun briefing(
        approvals: String = section(),
        blocked: String = section(),
        deadlines: String = section(),
        verified: String = section(),
        site: String? = null,
        empty: Boolean = false,
        generatedAt: String = "2026-11-14T12:00:00.000Z",
    ) = """{"generatedAt":"$generatedAt","dueBefore":"2026-11-15T12:00:00.000Z","verifiedSince":"2026-11-13T12:00:00.000Z",""" +
        """"empty":$empty,"sections":{"approvals":$approvals,"blocked":$blocked,"deadlines":$deadlines,""" +
        """"verified":$verified${site?.let { ""","siteActivity":$it""" } ?: ""}}}"""

    private val full = briefing(
        approvals = section(approval()),
        blocked = section(blocked(), truncated = true),
        deadlines = section(deadline()),
        verified = section(verified()),
        site = section(siteReceipt(), siteReview),
    )

    private fun historyPublish(version: Long = 4, effect: String = "effect-4", build: String = "build-4", org: String = "solo") =
        """{"operation":"sites.publish@1","buildId":"$build","receipt":{"orgId":"$org","targetId":"site-1",""" +
            """"effectId":"$effect","targetVersion":$version,"artifactDigest":"$digest","artifactBytes":123,""" +
            """"confirmedAt":"2026-11-14T09:00:00Z"}}"""

    private fun task(id: String = "task-1", project: String = "project-1", version: Int = 2) =
        """{"id":"$id","projectId":"$project","title":"Fix checkout","description":null,"parentTaskId":null,""" +
            """"dueAt":null,"status":"blocked","version":$version}"""

    @Test
    fun readsValidEmptyAndTruncatedBriefingWithoutQuery() = runTest {
        var body = full
        val engine = MockEngine { request ->
            assertEquals("Bearer $source", request.headers["Authorization"])
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/briefing/today" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertTrue(request.url.parameters.isEmpty())
                    respond(body)
                }
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            val today = controller.today()
            assertEquals(active.epoch, today.epoch)
            val sections = today.briefing.sections
            assertFalse(today.briefing.empty)
            assertTrue(sections.blocked.truncated)
            assertFalse(sections.deadlines.truncated)
            assertEquals(CoreTaskStatus.Blocked, sections.blocked.items.single().status)
            assertEquals(CoreTodayDueState.Overdue, sections.deadlines.items.single().dueState)
            assertEquals(CoreTodayDecisionAccess.ReauthenticationRequired, sections.approvals.items.single().decisionAccess)
            assertEquals(CoreTodayTaskOperation.Transition, sections.verified.items.single().operation)
            val site = checkNotNull(sections.siteActivity).items
            assertEquals(listOf(CoreTodaySiteKind.PublicationReceipt, CoreTodaySiteKind.WithdrawalReview), site.map { it.source.kind })
            assertNull(site[0].decisionAccess)
            assertNull(site[1].createdAt)
            assertEquals(CoreTodayDecisionAccess.Available, site[1].decisionAccess)

            body = briefing(empty = true).dropLast(1) + ""","futureField":{"ignored":true}}"""
            val empty = controller.today().briefing
            assertTrue(empty.empty)
            assertNull(empty.sections.siteActivity)
            assertTrue(empty.sections.blocked.items.isEmpty())
            body = briefing(blocked = section(truncated = true), empty = true)
            assertTrue(controller.today().briefing.sections.blocked.truncated)
            body = briefing(blocked = section(blocked("task-1")), deadlines = section(deadline("task-1")))
            assertEquals(1, controller.today().briefing.sections.deadlines.items.size)
            body = briefing(approvals = section(approval("effect-3")), verified = section(verified("effect-3")))
            assertEquals(1, controller.today().briefing.sections.verified.items.size)
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun rejectsInconsistentOrMalformedBriefingRows() = runTest {
        var body = full
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/briefing/today" -> respond(body)
                else -> error("Unexpected path")
            }
        }
        val eleven = (1..11).map { blocked("task-$it") }.toTypedArray()
        val protocolFailures = listOf(
            briefing(blocked = section(blocked()), empty = true),
            briefing(empty = false),
            briefing(blocked = section(truncated = true)),
            briefing(site = section(), empty = true),
            briefing(blocked = section(*eleven)),
            briefing(blocked = section(blocked(), blocked())),
            briefing(blocked = section(blocked()), generatedAt = "2026-11-14T11:00:00.000Z"),
            briefing(blocked = section(blocked().replace("\"status\":\"blocked\"", "\"status\":\"ready\""))),
            briefing(deadlines = section(deadline(status = "done"))),
            briefing(blocked = section(blocked().replace("source_record", "summary"))),
            briefing(blocked = section(blocked().replace("\"kind\":\"task\"", "\"kind\":\"approval\""))),
            briefing(blocked = section(blocked("bad/task"))),
            briefing(blocked = section(blocked().replace("\"version\":2", "\"version\":0"))),
            briefing(blocked = section(blocked(title = "é".repeat(201)))),
            briefing(blocked = section(blocked().replace(observed, "2026-11-14T10:00:00Z"))),
            briefing(blocked = section(blocked().replace(observed, "2026-13-14T10:00:00.000Z"))),
            briefing(blocked = section(blocked()), generatedAt = "2026-11-16T00:00:00.000Z"),
            briefing(approvals = section(approval().replace("\"kind\":\"approval\"", "\"kind\":\"task\""))),
            briefing(verified = section(verified(), verified())),
            briefing(site = section(siteReceipt().replace("sites.publish@1", "sites.withdraw@1"))),
            briefing(site = section(siteReceipt().dropLast(1) + ""","decisionAccess":"available"}""")),
            briefing(site = section(siteReceipt().dropLast(1) + ""","createdAt":null}""")),
            briefing(site = section(siteReview.replace("\"createdAt\":null,", ""))),
            briefing(site = section(siteReview.replace(""","decisionAccess":"available"""", ""))),
            briefing(site = section(siteReview.replace("\"createdAt\":null", "\"createdAt\":\"yesterday\""))),
            briefing(site = section(siteReceipt(version = 1))),
            briefing(site = section(siteReceipt(artifact = "A".repeat(64)))),
            briefing(site = section(siteReceipt(), siteReceipt(version = 3))),
        )
        val decodeFailures = listOf(
            briefing(blocked = section(blocked().replace(""","dueAt":null""", ""))),
            briefing(verified = section(verified().replace("tasks.transition@1", "tasks.delete@1"))),
            briefing(site = section(siteReceipt().replace("site_publication_receipt", "site_preview"))),
            briefing().replace(""","verified":{"items":[],"truncated":false}""", ""),
        )
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            protocolFailures.forEach { invalid ->
                body = invalid
                assertFailsWith<CoreProtocolException>(invalid) { controller.today() }
            }
            decodeFailures.forEach { invalid ->
                body = invalid
                assertFailsWith<IllegalArgumentException>(invalid) { controller.today() }
            }
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun briefingReadIsBoundedAndDetectsOneByteOverflow() = runTest {
        val valid = briefing(empty = true)
        var body = valid
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/briefing/today" -> respond(body)
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            assertEquals(256 * 1_024, CORE_TODAY_MAX_BYTES)
            body = valid + " ".repeat(CORE_TODAY_MAX_BYTES - valid.length)
            assertTrue(controller.today().briefing.empty)
            body += " "
            assertFailsWith<CoreProtocolException> { controller.today() }
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun opensPriorityByExactCanonicalTaskAcrossProjectMoves() = runTest {
        var taskResponse = task(project = "project-7", version = 3)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/briefing/today" -> respond(full)
                "/v1/tasks/task-1", "/v1/tasks/task-2" -> {
                    assertTrue(request.url.parameters.isEmpty())
                    respond(taskResponse)
                }
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            controller.establish(source)
            val today = controller.today()
            val priority = today.briefing.sections.blocked.items.single().source
            val moved = controller.todayTask(today, priority)
            assertEquals("task-1", moved.id)
            assertEquals("project-7", moved.projectId)
            taskResponse = task(version = 1)
            assertFailsWith<CoreProtocolException> { controller.todayTask(today, priority) }
            taskResponse = task(id = "task-8")
            assertFailsWith<CoreProtocolException> { controller.todayTask(today, priority) }
            assertFailsWith<IllegalArgumentException> { controller.todayTask(today, priority.copy()) }
            taskResponse = task(id = "task-2", version = 5)
            assertEquals("task-2", controller.todayTask(today, today.briefing.sections.deadlines.items.single().source).id)
        }
    }

    @Test
    fun opensOnlyTheExactWebsiteReceiptFromHistory() = runTest {
        var history = """{"items":[${historyPublish()}],"nextCursor":"4"}"""
        val engine = MockEngine { request ->
            val query = request.url.parameters
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/briefing/today" -> respond(full)
                "/v1/site-targets" -> {
                    assertEquals("project-1", query["projectId"])
                    respond("""{"id":"site-1","orgId":"solo","projectId":"project-1","slug":"synthetic","version":5,"activeEffectId":null}""")
                }
                "/v1/site-targets/site-1/history" -> {
                    assertEquals("1", query["limit"])
                    assertEquals("5", query["before"])
                    respond(history)
                }
                else -> error("Unexpected path ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val active = controller.establish(source)
            val today = controller.today()
            val (receipt, review) = checkNotNull(today.briefing.sections.siteActivity).items
            val item = checkNotNull(controller.todayWebsiteReceipt(today, receipt))
            assertEquals(CoreWebsiteOperation.Publish, item.operation)
            assertEquals("effect-4", item.receipt.effectId)
            assertEquals(4, item.receipt.targetVersion)
            listOf(
                historyPublish(effect = "effect-other"),
                historyPublish(build = "build-other"),
                historyPublish(org = "foreign-org"),
                historyPublish().replace(digest, "b".repeat(64)),
            ).forEach { mismatch ->
                history = """{"items":[$mismatch]}"""
                assertFailsWith<CoreProtocolException>(mismatch) { controller.todayWebsiteReceipt(today, receipt) }
            }
            history = """{"items":[${historyPublish(version = 3, effect = "effect-3")}],"nextCursor":"3"}"""
            assertNull(controller.todayWebsiteReceipt(today, receipt))
            history = """{"items":[]}"""
            assertNull(controller.todayWebsiteReceipt(today, receipt))
            assertFailsWith<IllegalArgumentException> { controller.todayWebsiteReceipt(today, review) }
            assertFailsWith<IllegalArgumentException> { controller.todayWebsiteReceipt(today, receipt.copy()) }
            assertEquals(active, controller.state.value)
        }
    }

    @Test
    fun lateBriefingAfterSwitchIsDroppedAndOldBriefingCannotNavigate() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldReads = 0
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/briefing/today" -> if (old) {
                    if (++oldReads == 2) {
                        started.complete(Unit)
                        release.await()
                    }
                    respond(full)
                } else respond(briefing(empty = true))
                else -> error("An old briefing must never reach ${request.url.encodedPath}")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val oldToday = controller.today()
            val pending = async { assertFailsWith<CancellationException> { controller.today() } }
            started.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            val sections = oldToday.briefing.sections
            assertFailsWith<CancellationException> { controller.todayTask(oldToday, sections.blocked.items.single().source) }
            assertFailsWith<CancellationException> {
                controller.todayWebsiteReceipt(oldToday, checkNotNull(sections.siteActivity).items.first())
            }
            assertEquals(current, controller.state.value)
            val fresh = controller.today()
            assertEquals(current.epoch, fresh.epoch)
            assertTrue(fresh.briefing.empty)
        }
    }

    @Test
    fun late401FromOldOrganizationCannotClearReplacementSession() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond("""{"token":"$replacement","expiresAt":9000}""")
                "/v1/briefing/today" -> if (old) {
                    started.complete(Unit)
                    release.await()
                    respond("", HttpStatusCode.Unauthorized)
                } else respond("", HttpStatusCode.Unauthorized)
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val pending = async { assertFailsWith<CancellationException> { controller.today() } }
            started.await()
            val current = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            assertEquals(current, controller.state.value)
            assertEquals("team", current.session.orgId)
            assertEquals(401, assertFailsWith<CoreHttpException> { controller.today() }.status)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, controller.state.value)
        }
    }
}
