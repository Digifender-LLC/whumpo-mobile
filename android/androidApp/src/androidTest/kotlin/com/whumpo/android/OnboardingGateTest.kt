package com.whumpo.android

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.android.ui.WhumpoApp
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.account.StateSlot
import com.whumpo.core.auth.AuthProblem
import com.whumpo.core.auth.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real session storage, controller and Compose gate, with synthetic API replies in both flavors. */
@RunWith(AndroidJUnit4::class)
class OnboardingGateTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restored: AppContainer? = null

    @Before
    fun setUp() = Fixtures.reset()

    @After
    fun tearDown() {
        FakeWorker.active?.bootstrapHold?.release()
        restored?.let { graph ->
            Fixtures.onMain { graph.auth.cancelHandoff() }
            runBlocking { graph.sessions.clear() }
        }
        scope.cancel()
        restored?.mobileApi?.close()
        Fixtures.reset()
    }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Fresh stores/controllers model process restoration; Activity recreation alone does not restore a session. */
    private fun showRestoredApp(): AppContainer {
        val app = Fixtures.app
        val graph = AppContainer(
            environment = Fixtures.container.environment,
            accountState = AccountStateStore(
                WhumpoApplication.accountStateDirectory(app),
                KeystoreSecretBox(WhumpoApplication.ACCOUNT_STATE_KEY_ALIAS),
            ),
            io = Dispatchers.IO,
            app = app,
            scope = scope,
            apiEngine = fixtureApiEngine(),
        )
        restored = graph
        compose.setContent { WhumpoTheme { WhumpoApp(graph) } }
        Fixtures.onMain { graph.auth.start() }
        return graph
    }

    @Test
    fun restoredSessionWaitsForBootstrapAndCompletedSetupBeforeShowingTheShell() {
        val worker = Fixtures.api().apply { onboardingComplete = false }
        val hold = FakeWorker.BootstrapHold()
        worker.bootstrapHold = hold
        Fixtures.establishSession()
        val graph = showRestoredApp()
        try {
            hold.awaitRequest()
            compose.waitUntil(10_000) { present("auth-check-setup") }
            compose.onNodeWithTag("auth-check-setup").assertIsNotEnabled()
            compose.onNodeWithTag("nav-chat").assertDoesNotExist()
            assertEquals(Fixtures.ownerA, graph.accountState.activeAccount.value)
        } finally {
            hold.release()
            worker.bootstrapHold = null
        }

        compose.waitUntil(10_000) { graph.auth.state.value == AuthState.FinishSetup(canCheck = true, canOpen = true) }
        compose.onNodeWithTag("nav-chat").assertDoesNotExist()
        worker.onboardingComplete = true
        compose.onNodeWithTag("auth-check-setup").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { present("nav-chat") }
        assertEquals(AuthState.Ready, graph.auth.state.value)
        val checks = worker.requestsSnapshot().filter { it.path.endsWith("/bootstrap") }
        assertEquals(2, checks.size)
        assertTrue(checks.all { it.headers["authorization"] == "Bearer ${"0".repeat(63)}1" })
    }

    @Test
    fun restoredBootstrapFailureKeepsTheShellClosedUntilAnExplicitSuccessfulRetry() {
        val worker = Fixtures.api().apply { bootstrapUnavailable = true }
        Fixtures.establishSession()
        val graph = showRestoredApp()
        compose.waitUntil(10_000) {
            graph.auth.state.value == AuthState.FinishSetup(problem = AuthProblem.ServiceUnavailable, canCheck = true)
        }
        compose.onNodeWithTag("nav-chat").assertDoesNotExist()
        compose.onNodeWithTag("auth-check-setup").assertIsEnabled()
        assertEquals(1, worker.requestsSnapshot().count { it.path.endsWith("/bootstrap") })

        worker.bootstrapUnavailable = false
        compose.onNodeWithTag("auth-check-setup").performClick()
        compose.waitUntil(10_000) { present("nav-chat") }
        assertEquals(AuthState.Ready, graph.auth.state.value)
    }

    @Test
    fun anotherAccountCannotReuseThePreviousAccountsSuccessfulBootstrap() {
        val worker = Fixtures.signIn()
        Fixtures.container.accountState.write(Fixtures.ownerA, StateSlot.CHAT_DRAFT, "account A draft")
        compose.setContent { WhumpoTheme { WhumpoApp(Fixtures.container) } }
        compose.waitUntil(10_000) { present("nav-chat") }

        val tokenB = "0".repeat(63) + "2"
        worker.onboardingComplete = false
        Fixtures.establishSession(Fixtures.LABEL_B, tokenB)
        compose.waitUntil(10_000) { present("auth-check-setup") && !present("nav-chat") }
        assertNull(Fixtures.container.accountState.read(Fixtures.ownerB, StateSlot.CHAT_DRAFT))
        compose.onNodeWithTag("auth-check-setup").performClick()
        Fixtures.awaitAuth { it == AuthState.FinishSetup(canCheck = true, canOpen = true) }
        compose.onNodeWithTag("nav-chat").assertDoesNotExist()
        assertEquals("Bearer $tokenB", worker.requestsSnapshot().last { it.path.endsWith("/bootstrap") }.headers["authorization"])

        worker.onboardingComplete = true
        compose.onNodeWithTag("auth-check-setup").performClick()
        compose.waitUntil(10_000) { present("nav-chat") }
        assertEquals(Fixtures.ownerB, Fixtures.container.accountState.activeAccount.value)
    }
}
