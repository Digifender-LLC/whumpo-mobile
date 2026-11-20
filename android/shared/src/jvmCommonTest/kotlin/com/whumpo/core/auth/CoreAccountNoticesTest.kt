package com.whumpo.core.auth

import com.whumpo.core.api.*
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CoreAccountNoticesTest {
    private val origin = CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test"))
    private val token = "ag1." + "E".repeat(43)
    private val passkeys = object : AccountPasskeys {
        override suspend fun register(options: String): String? = error("No enrollment")
        override suspend fun assert(options: String) = """{"id":"synthetic"}"""
    }
    private fun grant(account: String = "account-1") = """{"authority":{"kind":"account_grant","token":"$token","purpose":"account","expiresAt":9000},"state":{"version":1,"accountId":"$account","candidateId":null,"purpose":"account","phase":"ready","generation":2,"inventoryRevision":1,"attemptId":null,"attemptRevision":null,"intentRevision":null,"methods":[],"recovery":{"kind":"none","generation":0,"remainingCodes":null,"offer":null},"approvedMethod":null,"freshUntil":null,"expiresAt":9000,"workspaceAccess":"none","setupProfile":null}}"""
    private fun notice(id: String = "notice:1", delivery: String = "sent") = """{"id":"$id","kind":"sign_in","createdAt":1,"delivery":"$delivery"}"""
    private fun page(rows: String = notice(), cursor: String? = null) = """{"items":[$rows],"nextCursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""
    private suspend fun MockRequestHandleScope.login(path: String, account: String = "account-1") = when (path) {
        "/v1/account-security/passkey/login/options" -> respond("""{"challengeId":"login:1","options":{"challenge":"synthetic","rpId":"core.example.test","userVerification":"required"}}""")
        "/v1/account-security/passkey/login/verify" -> respond(grant(account))
        else -> error("Unexpected synthetic route")
    }

    @Test fun zeroOrgAccountGrantPagesDedupesAndLeavesWorkspaceSignedOut() = runTest {
        var count = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/notices")) {
                assertEquals("AccountGrant $token", request.headers["Authorization"])
                assertNull(request.headers["Idempotency-Key"])
                count++
                if (count == 1) respond(page(cursor = "cursor:1")) else {
                    assertEquals("cursor:1", request.url.parameters["cursor"])
                    respond(page(notice() + "," + notice("notice:2", "unknown")))
                }
            } else login(request.url.encodedPath)
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, "core.example.test") { 1 }
            controller.loginPasskey(passkeys)
            assertFailsWith<IllegalStateException> { controller.organizations() }
            controller.loadNotices(); controller.loadNotices(more = true)
            assertEquals(listOf("notice:1", "notice:2"), controller.notices.value!!.items.map { it.id })
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
            controller.hideNotices(); assertNull(controller.notices.value)
        }
    }

    @Test fun malformedClosedPagesClearPreviouslyVisibleMetadata() = runTest {
        val invalid = listOf(
            page().dropLast(1) + ",\"extra\":true}",
            page(notice().dropLast(1) + ",\"secret\":\"unexpected\"}"),
            page(notice(delivery = "delivered")), page(notice() + "," + notice()),
            page().replace("\"createdAt\":1", "\"createdAt\":\"1\""),
            page("", "cursor:1"), page(notice(), "bad cursor"),
        )
        for (raw in invalid) {
            var next = page()
            val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) respond(next) else login(request.url.encodedPath) }
            CoreApiClient(engine, origin).use { api ->
                val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { 1 }, "core.example.test") { 1 }
                controller.loginPasskey(passkeys); controller.loadNotices(); assertNotNull(controller.notices.value)
                next = raw
                assertFailsWith<CoreProtocolException> { controller.loadNotices() }; assertNull(controller.notices.value)
            }
        }
    }

    @Test fun heldResponseAfterHideCannotRestoreNoticesAndCanRetry() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var held = true
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/notices")) {
                if (held) { entered.complete(Unit); release.await() }; respond(page())
            } else login(request.url.encodedPath)
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { 1 }, "core.example.test") { 1 }
            controller.loginPasskey(passkeys)
            val read = async { runCatching { controller.loadNotices() } }; entered.await()
            controller.hideNotices(); release.complete(Unit)
            assertIs<CancellationException>(read.await().exceptionOrNull()); assertNull(controller.notices.value)
            held = false; controller.loadNotices(); assertNotNull(controller.notices.value)
        }
    }

    @Test fun heldReadAfterExpiryClearsAccountAndMetadata() = runTest {
        var clock = 1L
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) {
            entered.complete(Unit); release.await(); respond(page())
        } else login(request.url.encodedPath) }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { clock }, "core.example.test") { clock }
            controller.loginPasskey(passkeys)
            val read = async { runCatching { controller.loadNotices() } }; entered.await()
            clock = 9000; release.complete(Unit)
            assertIs<CancellationException>(read.await().exceptionOrNull()); assertNull(controller.notices.value); assertNull(controller.state.value)
        }
    }

    @Test fun endedReadCannotClearLaterAccountAndCurrent401ClearsAuthority() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var hold = true; var account = "account-1"
        val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) {
            if (hold) { entered.complete(Unit); release.await() }
            respond("{}", HttpStatusCode.Unauthorized)
        } else login(request.url.encodedPath, account) }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { 1 }, "core.example.test") { 1 }
            controller.loginPasskey(passkeys)
            val read = async { runCatching { controller.loadNotices() } }; entered.await(); controller.clear(); release.complete(Unit)
            read.await(); account = "account-2"; controller.loginPasskey(passkeys)
            assertEquals("account-2", controller.state.value?.accountId)
            hold = false
            assertFailsWith<CoreHttpException> { controller.loadNotices() }; assertNull(controller.state.value)
        }
    }

    @Test fun cancelledCurrentReadJoinsAndDoesNotRestoreMetadata() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var held = true
        val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) {
            if (held) { entered.complete(Unit); release.await() }; respond(page())
        } else login(request.url.encodedPath) }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { 1 }, "core.example.test") { 1 }
            controller.loginPasskey(passkeys)
            val read = launch { controller.loadNotices() }; entered.await(); read.cancel(); release.complete(Unit); read.join()
            assertNull(controller.notices.value); assertNotNull(controller.state.value)
            held = false; controller.loadNotices(); assertNotNull(controller.notices.value)
        }
    }
    @Test fun cursorLoopAndChangedDuplicateClearAccumulatedPage() = runTest {
        for (changedDuplicate in listOf(false, true)) {
            var more = false
            val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) {
                if (!more) respond(page(cursor = "cursor:1"))
                else if (changedDuplicate) respond(page(notice(delivery = "unknown")))
                else respond(page(notice("notice:2"), "cursor:1"))
            } else login(request.url.encodedPath) }
            CoreApiClient(engine, origin).use { api ->
                val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { 1 }, "core.example.test") { 1 }
                controller.loginPasskey(passkeys); controller.loadNotices(); more = true
                assertFailsWith<CoreProtocolException> { controller.loadNotices(more = true) }; assertNull(controller.notices.value)
                assertNotNull(controller.state.value)
            }
        }
    }

    @Test fun twentyContinuationBudgetStopsRequestsAndPreReadExpiryClearsPrivateState() = runTest {
        var clock = 1L; var calls = 0
        val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) {
            calls++; respond(page(notice("notice:$calls"), "cursor:$calls"))
        } else login(request.url.encodedPath) }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreAccountSecurityController(api, CoreOrganizationSessionController(api) { clock }, "core.example.test") { clock }
            controller.loginPasskey(passkeys); controller.loadNotices()
            repeat(20) { controller.loadNotices(more = true) }
            assertEquals(21, calls)
            assertFailsWith<CoreProtocolException> { controller.loadNotices(more = true) }
            assertEquals(21, calls); assertNull(controller.notices.value)
            controller.loadNotices(); assertNotNull(controller.notices.value)
            clock = 9000; controller.expireAccountIfNeeded()
            assertNull(controller.notices.value); assertNull(controller.state.value)
        }
    }

    @Test fun workspaceLossClearHidesVisibleNoticesBeforeHeldResponseJoins() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0; var account = "account-1"
        val engine = MockEngine { request -> if (request.url.encodedPath.endsWith("/notices")) {
            calls++
            if (calls == 2) { entered.complete(Unit); release.await() }
            respond(page(cursor = if (calls == 1) "cursor:1" else null))
        } else login(request.url.encodedPath, account) }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, "core.example.test") { 1 }
            controller.loginPasskey(passkeys); controller.loadNotices(); assertNotNull(controller.notices.value)
            val read = async { runCatching { controller.loadNotices(more = true) } }; entered.await()
            assertNotNull(controller.notices.value)
            // The launcher uses this synchronous boundary on workspace authority loss.
            controller.clear()
            assertNull(controller.notices.value); assertNull(controller.state.value)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
            release.complete(Unit)
            assertIs<CancellationException>(read.await().exceptionOrNull())
            assertNull(controller.notices.value)
            account = "account-2"; controller.loginPasskey(passkeys)
            assertEquals("account-2", controller.state.value?.accountId)
            controller.loadNotices(); assertNotNull(controller.notices.value)
        }
    }

}
