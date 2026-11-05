package com.whumpo.core.auth

import com.whumpo.core.account.AccountScope
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.account.FakeSecretBox
import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.MobileApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sign-in state machine against a fake Credential Manager wrapper, a fake browser and the WP4 client on Ktor's
 * `MockEngine`. This proves the app's logic. It does not prove real passkeys: those need a device, a credential provider
 * and the published asset links (docs/android/assetlinks-setup.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthControllerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val token = "0".repeat(63) + "1"
    private val code = "0".repeat(63) + "2"
    private val handoffId = "dddddddd-dddd-4ddd-8ddd-000000000001"
    private val challengeId = "cccccccc-cccc-4ccc-8ccc-000000000001"
    private val times = """"expiresAt":"2026-10-11T12:00:00.000Z","freshUntil":"2026-10-04T12:30:00.000Z""""
    private val browserPath = "/workspace/native/hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh"

    private fun err(code: String, extra: String = "") = """{"version":1,"error":{"code":"$code","message":"Plain words."$extra}}"""
    private val ok = mapOf(
        "/session/login/options" to """{"version":1,"challengeId":"$challengeId","expiresAt":"2026-10-04T12:30:00.000Z","options":{"rpId":"whumpo.com","challenge":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","userVerification":"required","allowCredentials":[]}}""",
        "/session/login/verify" to """{"version":1,"session":{"token":"$token",$times}}""",
        "/session" to """{"version":1,"session":{"expiresAt":"2026-10-11T12:00:00.000Z","freshUntil":null},"account":{"label":"o***@example.test"}}""",
        "/bootstrap" to """{"version":1,"onboarding":{"complete":true,"browserPath":null},"chat":{"revision":4,"mode":"ai"},"projectCount":1,"pendingReviewCount":0}""",
        "/session/handoff/start" to """{"version":1,"handoffId":"$handoffId","browserPath":"$browserPath","expiresAt":"2026-10-04T12:05:00.000Z"}""",
        "/session/handoff/exchange" to """{"version":1,"session":{"token":"$token","expiresAt":"2026-10-11T12:00:00.000Z","freshUntil":null}}""",
        "/session/reauth/options" to """{"version":1,"challengeId":"$challengeId","expiresAt":"2026-10-04T12:30:00.000Z","options":{"rpId":"whumpo.com","challenge":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","userVerification":"required"}}""",
        "/session/reauth/verify" to """{"version":1,"session":{$times}}""",
        "/session/logout" to """{"version":1,"signedOut":true}""",
    )

    private class Call(val path: String, val auth: String?, val body: String)

    private class FakePasskeys(var next: PasskeyResult = PasskeyResult.Assertion(CREDENTIAL)) : PasskeyAuthenticator {
        val requests = CopyOnWriteArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getAssertion(requestJson: String): PasskeyResult {
            requests += requestJson
            gate?.await()
            return next
        }
    }

    private class FakeBrowser : BrowserLauncher {
        val opened = CopyOnWriteArrayList<String>()
        var works = true

        override fun open(url: String): Boolean {
            if (works) opened += url
            return works
        }
    }

    private inner class Rig(private val scope: TestScope) {
        var now: Instant = Instant.parse("2026-10-04T12:00:00Z")
        val calls = CopyOnWriteArrayList<Call>()

        /** path -> replacement status and body; `null` makes the request fail like a dropped connection. */
        val overrides = mutableMapOf<String, Pair<Int, String>?>()
        val box = FakeSecretBox()
        val dir = File(temp.root, "account-state")
        val store = AccountStateStore(dir, box)
        val sessions = SessionManager(store, UnconfinedTestDispatcher(scope.testScheduler)) { now }
        val passkeys = FakePasskeys()
        val browser = FakeBrowser()
        val api = MobileApiClient(
            MockEngine { request ->
                val path = request.url.encodedPath.removePrefix("/api/mobile/v1")
                calls += Call(path, request.headers[HttpHeaders.Authorization], request.body.toByteArray().decodeToString())
                val override = if (overrides.containsKey(path)) overrides[path] ?: throw IOException("connection dropped") else null
                val (status, body) = override ?: (200 to checkNotNull(ok[path]) { "unexpected $path" })
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
            },
            "https://whumpo.com/api/mobile/v1",
            ClientInfo.current(1, controls = emptyList()),
            sessions,
        )
        val auth = AuthController(api, sessions, passkeys, browser, "https://whumpo.com", scope.backgroundScope)

        suspend fun started(): Rig {
            sessions.restore()
            auth.state.first { it != AuthState.Restoring }
            return this
        }

        /** Lets queued work run, then waits a moment of real time so a request that should not happen has a chance to. */
        suspend fun settle() {
            scope.runCurrent()
            withContext(Dispatchers.Default) { delay(100) }
            scope.runCurrent()
        }

        suspend fun await(expected: AuthState) = auth.state.first { it == expected }
        fun paths() = calls.map { it.path }
        fun body(path: String) = Json.parseToJsonElement(calls.last { it.path == path }.body).jsonObject
        fun onDisk() = dir.walkTopDown().filter { it.isFile }.joinToString("|") { it.name + String(it.readBytes(), Charsets.ISO_8859_1) }
    }

    private fun test(block: suspend Rig.() -> Unit) = runTest { Rig(this).started().block() }

    // ---- Passkey sign-in -------------------------------------------------------------------------------------------

    @Test
    fun startsSignedOutWithNoStoredSession() = test {
        assertEquals(AuthState.SignedOut(), auth.state.value)
    }

    @Test
    fun passkeySignInStoresTheSessionSealedAndOpensTheApp() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)

        assertEquals(listOf("/session/login/options", "/session/login/verify", "/session", "/bootstrap"), paths())
        // The options go to Credential Manager unchanged; the assertion goes to the server unchanged.
        assertEquals("whumpo.com", Json.parseToJsonElement(passkeys.requests.single()).jsonObject["rpId"]!!.jsonPrimitive.content)
        assertEquals(challengeId, body("/session/login/verify")["challengeId"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(CREDENTIAL), body("/session/login/verify")["credential"])
        // Anonymous until the token exists, then bearer only.
        assertNull(calls.first { it.path == "/session/login/verify" }.auth)
        assertEquals("Bearer $token", calls.first { it.path == "/session" }.auth)
        // Sealed on disk: neither the token nor the account label is readable.
        assertFalse(onDisk().contains(token))
        assertFalse(onDisk().contains("example.test"))
    }

    @Test
    fun theSessionSurvivesARestart() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)

        val restarted = SessionManager(AccountStateStore(dir, box), UnconfinedTestDispatcher(), { now })
        restarted.restore()
        assertEquals(SessionManager.State.SignedIn, restarted.state.first())
        assertEquals(token, restarted.bearerToken())
    }

    @Test
    fun anExpiredStoredSessionIsErasedOnRestart() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)

        now = Instant.parse("2026-10-11T12:00:00Z")
        val restarted = SessionManager(AccountStateStore(dir, box), UnconfinedTestDispatcher(), { now })
        restarted.restore()
        assertEquals(SessionManager.State.SignedOut(ended = true), restarted.state.first())
        assertNull(restarted.bearerToken())
        assertFalse(dir.exists())
    }

    @Test
    fun dismissingTheSystemSheetIsNotAnError() = test {
        passkeys.next = PasskeyResult.Cancelled
        auth.signInWithPasskey()
        settle()
        assertEquals(AuthState.SignedOut(), auth.state.first { it != AuthState.SignedOut(busy = true) })
        assertEquals(listOf("/session/login/options"), paths())
    }

    @Test
    fun noPasskeyOnThisPhoneOffersAnotherWay() = test {
        passkeys.next = PasskeyResult.NoCredential
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.NoPasskey))
    }

    @Test
    fun anUnavailableCredentialManagerIsReported() = test {
        passkeys.next = PasskeyResult.Unavailable
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.PasskeyUnavailable))
    }

    @Test
    fun networkFailureShowsAProblemAndStoresNothing() = test {
        overrides["/session/login/options"] = null
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.Network))
        assertEquals(0, passkeys.requests.size)
        assertFalse(dir.exists())
    }

    @Test
    fun aRefusedAssertionShowsRejectedAndStoresNothing() = test {
        overrides["/session/login/verify"] = 401 to err("proof_required", ""","proof":"signin"""")
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.Rejected))
        assertFalse(dir.exists())
    }

    @Test
    fun rateLimitAndOutageAreNamed() = test {
        overrides["/session/login/options"] = 429 to err("rate_limited", ""","retryAfterSeconds":60""")
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.RateLimited))

        overrides["/session/login/options"] = 503 to err("unavailable")
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.ServiceUnavailable))
    }

    @Test
    fun anOutOfDateAppIsBlocked() = test {
        overrides["/session/login/options"] = 426 to err("client_update_required", ""","minimumBuild":40,"contract":1""")
        auth.signInWithPasskey()
        await(AuthState.UpdateRequired)
    }

    @Test
    fun aSecondTapWhileSigningInIsIgnored() = test {
        passkeys.gate = CompletableDeferred()
        auth.signInWithPasskey()
        await(AuthState.SignedOut(busy = true))
        auth.signInWithPasskey()
        auth.signInWithBrowser()
        passkeys.gate!!.complete(Unit)
        await(AuthState.Ready)
        assertEquals(1, paths().count { it == "/session/login/options" })
        assertEquals(0, paths().count { it == "/session/handoff/start" })
    }

    // ---- Account not finished, welcome gate -------------------------------------------------------------------------

    @Test
    fun anIncompleteAccountIsSentToTheBrowserAndGetsNoSession() = test {
        overrides["/session/login/verify"] = 403 to err("identity_incomplete", ""","next":"browser"""")
        auth.signInWithPasskey()
        await(AuthState.FinishSetup())
        assertFalse(dir.exists())

        auth.openSetup()
        await(AuthState.WaitingForBrowser())
        assertEquals("signin", body("/session/handoff/start")["purpose"]!!.jsonPrimitive.content)
        assertEquals(listOf("https://whumpo.com$browserPath"), browser.opened.toList())
    }

    @Test
    fun anUnfinishedWelcomeHoldsTheAppUntilTheServerSaysItIsDone() = test {
        val unfinished = """{"version":1,"onboarding":{"complete":false,"browserPath":"/welcome"},"chat":null,"projectCount":0,"pendingReviewCount":0}"""
        overrides["/bootstrap"] = 200 to unfinished
        auth.signInWithPasskey()
        await(AuthState.FinishSetup(canCheck = true))

        auth.openSetup()
        settle()
        assertEquals(listOf("https://whumpo.com/welcome"), browser.opened.toList())

        auth.checkSetupAgain()
        await(AuthState.FinishSetup(canCheck = true))
        overrides.remove("/bootstrap")
        auth.checkSetupAgain()
        await(AuthState.Ready)
    }

    @Test
    fun aServerPathThatCouldLeaveTheHostIsNeverOpened() = test {
        overrides["/session/handoff/start"] = 200 to """{"version":1,"handoffId":"$handoffId","browserPath":"//evil.example/x","expiresAt":"2026-10-04T12:05:00.000Z"}"""
        auth.signInWithBrowser()
        await(AuthState.SignedOut(AuthProblem.HandoffFailed))
        assertTrue(browser.opened.isEmpty())
    }

    // ---- Browser hand-off -----------------------------------------------------------------------------------------------

    private fun Rig.returnLink() = "https://whumpo.com/app/return?code=$code&state=${body("/session/handoff/start")["state"]!!.jsonPrimitive.content}"

    @Test
    fun handoffUsesPkceAndExchangesTheOneTimeCode() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        val start = body("/session/handoff/start")
        assertEquals("S256", start["codeChallengeMethod"]!!.jsonPrimitive.content)
        assertEquals("https://whumpo.com/app/return", start["redirectUri"]!!.jsonPrimitive.content)
        assertEquals("signin", start["purpose"]!!.jsonPrimitive.content)
        assertEquals(listOf("https://whumpo.com$browserPath"), browser.opened.toList())

        auth.onReturn(returnLink())
        await(AuthState.Ready)
        val exchange = body("/session/handoff/exchange")
        assertEquals(handoffId, exchange["handoffId"]!!.jsonPrimitive.content)
        assertEquals(code, exchange["code"]!!.jsonPrimitive.content)
        assertEquals(start["state"], exchange["state"])
        // The verifier the app held hashes to the challenge it sent.
        assertEquals(start["codeChallenge"]!!.jsonPrimitive.content, Pkce.challenge(exchange["codeVerifier"]!!.jsonPrimitive.content))
        assertFalse(onDisk().contains(code))
    }

    @Test
    fun theInviteStartsASignupHandoff() = test {
        auth.startInvite()
        await(AuthState.WaitingForBrowser())
        assertEquals("signup", body("/session/handoff/start")["purpose"]!!.jsonPrimitive.content)
    }

    @Test
    fun aReturnWithTheWrongStateIsIgnoredAndTheHandoffKeepsWaiting() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        auth.onReturn("https://whumpo.com/app/return?code=$code&state=${"x".repeat(43)}")
        settle()
        assertEquals(AuthState.WaitingForBrowser(), auth.state.value)
        assertFalse(paths().contains("/session/handoff/exchange"))
        // The real return still completes afterwards: the stray link did not burn the pending hand-off.
        auth.onReturn(returnLink())
        await(AuthState.Ready)
        assertTrue(paths().contains("/session/handoff/exchange"))
    }

    @Test
    fun onlyAStrictCodeAndStateAreExchanged() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        val state = body("/session/handoff/start")["state"]!!.jsonPrimitive.content
        val base = "https://whumpo.com/app/return"
        // Malformed or repeated pairs are ignored; they never reach the exchange.
        auth.onReturn("$base?code=$code&state=$state&code=$code")
        auth.onReturn("$base?code=$code&state=$state&junk")
        settle()
        assertEquals(AuthState.WaitingForBrowser(), auth.state.value)
        // An extra name is refused and spends the hand-off.
        auth.onReturn("$base?code=$code&state=$state&extra=1")
        await(AuthState.SignedOut(AuthProblem.HandoffFailed))
        assertFalse(paths().contains("/session/handoff/exchange"))
    }

    @Test
    fun anUppercaseCodeIsRefused() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        auth.onReturn("https://whumpo.com/app/return?code=${"A".repeat(64)}&state=${body("/session/handoff/start")["state"]!!.jsonPrimitive.content}")
        await(AuthState.SignedOut(AuthProblem.HandoffFailed))
        assertFalse(paths().contains("/session/handoff/exchange"))
    }

    @Test
    fun aShortCodeIsRefused() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        auth.onReturn("https://whumpo.com/app/return?code=${code.dropLast(1)}&state=${body("/session/handoff/start")["state"]!!.jsonPrimitive.content}")
        await(AuthState.SignedOut(AuthProblem.HandoffFailed))
        assertFalse(paths().contains("/session/handoff/exchange"))
    }

    @Test
    fun anOversizedCredentialIsRefusedLocally() = test {
        passkeys.next = PasskeyResult.Assertion("""{"id":"${"A".repeat(8 * 1024)}"}""")
        auth.signInWithPasskey()
        await(AuthState.SignedOut(AuthProblem.CredentialTooLarge))
        assertFalse(paths().contains("/session/login/verify"))
    }

    @Test
    fun aCredentialAtTheLimitIsSent() = test {
        val pad = 8 * 1024 - CREDENTIAL.toByteArray().size
        passkeys.next = PasskeyResult.Assertion(CREDENTIAL.replace("\"id\":\"AAAA\"", "\"id\":\"AAAA${"A".repeat(pad)}\""))
        auth.signInWithPasskey()
        await(AuthState.Ready)
        assertTrue(paths().contains("/session/login/verify"))
    }

    @Test
    fun aReplayedLinkDoesNothing() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        val link = returnLink()
        auth.onReturn(link)
        await(AuthState.Ready)
        auth.onReturn(link)
        settle()
        assertEquals(1, paths().count { it == "/session/handoff/exchange" })
    }

    @Test
    fun linksThatAreNotTheReturnLinkAreIgnored() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        auth.onReturn("https://evil.example/app/return?code=$code&state=x")
        auth.onReturn("https://whumpo.com/app/return/more?code=$code&state=x")
        auth.onReturn("https://whumpo.com/app/return?code=$code#frag")
        auth.onReturn("whumpo://app/return?code=$code")
        settle()
        assertEquals(AuthState.WaitingForBrowser(), auth.state.value)
        assertFalse(paths().contains("/session/handoff/exchange"))
    }

    @Test
    fun aReturnWithNoHandoffInFlightIsIgnored() = test {
        auth.onReturn("https://whumpo.com/app/return?code=$code&state=${"x".repeat(43)}")
        settle()
        assertEquals(AuthState.SignedOut(), auth.state.value)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aFailedExchangeNeedsAFreshHandoff() = test {
        overrides["/session/handoff/exchange"] = 401 to err("proof_required", ""","proof":"signin"""")
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        auth.onReturn(returnLink())
        await(AuthState.SignedOut(AuthProblem.HandoffFailed))
        assertFalse(dir.exists())
    }

    @Test
    fun cancellingTheHandoffReturnsToWelcome() = test {
        auth.signInWithBrowser()
        await(AuthState.WaitingForBrowser())
        auth.cancelHandoff()
        await(AuthState.SignedOut())
        auth.onReturn(returnLink())
        settle()
        assertFalse(paths().contains("/session/handoff/exchange"))
    }

    @Test
    fun noBrowserIsReported() = test {
        browser.works = false
        auth.signInWithBrowser()
        await(AuthState.SignedOut(AuthProblem.NoBrowser))
    }

    // ---- Revocation, expiry, sign-out -----------------------------------------------------------------------------------

    @Test
    fun aRejectedSessionClearsEverythingAndSaysWhy() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)
        store.write(store.activeAccount.value!!, com.whumpo.core.account.StateSlot.CHAT_DRAFT, "draft")

        overrides["/session"] = 401 to err("proof_required", ""","proof":"signin"""")
        runCatching { api.getSession() }
        await(AuthState.SignedOut(AuthProblem.SessionEnded))
        assertFalse(dir.exists())
        assertTrue(box.destroyCount > 0)
    }

    @Test
    fun aLocallyExpiredSessionEndsWithoutCallingTheServer() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)
        val before = calls.size

        now = Instant.parse("2026-10-11T12:00:01Z")
        runCatching { api.getSession() }
        await(AuthState.SignedOut(AuthProblem.SessionEnded))
        assertEquals(before, calls.size)
    }

    @Test
    fun signOutTellsTheServerThenErasesEverything() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)

        // Real time: the 5 s logout bound would otherwise fire instantly under the test scheduler.
        withContext(Dispatchers.Default) { auth.signOutNow() }
        assertEquals("Bearer $token", calls.last { it.path == "/session/logout" }.auth)
        await(AuthState.SignedOut())
        assertFalse(dir.exists())
        assertTrue(box.destroyCount > 0)
    }

    @Test
    fun signOutErasesLocalStateEvenWhenTheServerCannotBeReached() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)
        overrides["/session/logout"] = null

        // Real time: the 5 s logout bound would otherwise fire instantly under the test scheduler.
        withContext(Dispatchers.Default) { auth.signOutNow() }
        await(AuthState.SignedOut())
        assertFalse(dir.exists())
    }

    @Test
    fun anAccountWithNoStoredSessionIsNotASignIn() = test {
        store.activate(AccountScope.of("o***@example.test"))
        assertNotNull(store.activeAccount.value)
        sessions.restore()
        await(AuthState.SignedOut(AuthProblem.SessionEnded))
        assertFalse(dir.exists())
    }

    // ---- Fresh-passkey reauthentication --------------------------------------------------------------------------------

    @Test
    fun reauthenticationMovesFreshUntilAndSendsTheSameAssertion() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)
        now = Instant.parse("2026-10-04T12:45:00Z")
        assertFalse(auth.isFresh)

        // 12:30 is in the past now, so use a later window from the server.
        overrides["/session/reauth/verify"] = 200 to """{"version":1,"session":{"expiresAt":"2026-10-11T12:00:00.000Z","freshUntil":"2026-10-04T12:55:00.000Z"}}"""
        assertEquals(ReauthResult.Fresh, auth.reauthenticate())
        assertTrue(auth.isFresh)
        assertEquals("Bearer $token", calls.last { it.path == "/session/reauth/options" }.auth)
        assertEquals(Json.parseToJsonElement(CREDENTIAL), body("/session/reauth/verify")["credential"])
        // The moved window is stored, not just remembered.
        val restarted = SessionManager(AccountStateStore(dir, box), UnconfinedTestDispatcher(), { now })
        restarted.restore()
        assertTrue(restarted.isFresh)
    }

    @Test
    fun reauthenticationCancelledOrFailed() = test {
        auth.signInWithPasskey()
        await(AuthState.Ready)

        passkeys.next = PasskeyResult.Cancelled
        assertEquals(ReauthResult.Cancelled, auth.reauthenticate())
        passkeys.next = PasskeyResult.NoCredential
        assertEquals(ReauthResult.Failed(AuthProblem.NoPasskey), auth.reauthenticate())
        passkeys.next = PasskeyResult.Assertion(CREDENTIAL)
        overrides["/session/reauth/verify"] = 429 to err("rate_limited", ""","retryAfterSeconds":60""")
        assertEquals(ReauthResult.Failed(AuthProblem.RateLimited), auth.reauthenticate())
        overrides["/session/reauth/options"] = 426 to err("client_update_required")
        assertEquals(ReauthResult.UpdateRequired, auth.reauthenticate())
    }

    private companion object {
        const val CREDENTIAL =
            """{"id":"AAAA","rawId":"AAAA","type":"public-key","response":{"clientDataJSON":"AAAA","authenticatorData":"AAAA","signature":"AAAA","userHandle":"AAAA"}}"""
    }
}
