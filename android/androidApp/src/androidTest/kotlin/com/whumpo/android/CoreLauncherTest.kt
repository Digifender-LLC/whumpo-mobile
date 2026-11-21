package com.whumpo.android

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whumpo.android.core.CoreLauncher
import com.whumpo.android.core.NativeAccountPasskeys
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreClientSettings
import com.whumpo.core.api.coreApiClient
import com.whumpo.core.auth.CoreOrganizationSessionController
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLauncherTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = Fixtures.app as FixtureApplication

    @Before
    fun setUp() = Fixtures.reset()

    @After
    fun tearDown() {
        app.coreLauncherEngine = null
        app.coreApiOriginOverride = null
        app.coreRpIdOverride = null
        Fixtures.reset()
    }

    @Test
    fun defaultLauncherKeepsLegacyEntry() {
        launch().use {
            awaitLegacyEntry()
            if (BuildConfig.WHUMPO_ENVIRONMENT == "local") {
                click("Explore setup preview")
                awaitText("Account security and sign-in")
                compose.onNodeWithText("Review your next step").assertDoesNotExist()
                pressBack()
                awaitLegacyEntry()
            }
        }
    }

    @Test
    fun syntheticLauncherVerifiesReadinessAndRevokesTheExactSession() {
        val fixture = CoreLauncherFixture()
        app.coreLauncherEngine = fixture::engine
        try {
            launch().use {
                if (BuildConfig.WHUMPO_ENVIRONMENT == "local") {
                    awaitLegacyEntry()
                    assertTrue(fixture.requests.isEmpty())
                    return
                }
                awaitText("Open Core sign-in")
                assertTrue(fixture.requests.isEmpty())
                click("Open Core sign-in")
                compose.onNodeWithText("Sign in with a passkey").assertIsNotEnabled()
                compose.onNodeWithText("Use a reserved invitation").assertDoesNotExist()
                click("Sign in with password and authenticator")
                input("Username", CoreLauncherFixture.USERNAME)
                input("Password", CoreLauncherFixture.PASSWORD)
                click("Continue")
                awaitText("Enter your authenticator code")
                assertEquals(listOf("POST /v1/account-security/password/login/start"), fixture.requests)
                input("Authenticator code", CoreLauncherFixture.TOTP)
                click("Sign in")
                awaitText("Choose an organization")
                compose.onNodeWithText("Organization").assertDoesNotExist()
                click("Load organizations")
                assertTrue(fixture.requests.none { it.startsWith("GET /v1/organization") })
                click("fixture-org · viewer")
                awaitText("This organization has not finished setup. Continue in the web workspace.")
                assertTrue("GET /v1/organization" !in fixture.requests)
                compose.onNodeWithText("Open Today").assertDoesNotExist()
                compose.onNodeWithText("Open Work").assertDoesNotExist()
                assertTrue(fixture.requests.none { it.endsWith("/briefing/today") || it.endsWith("/work/tasks") })

                fixture.readiness = CoreLauncherFixture.Readiness.Foreign
                click("Check again")
                awaitText("Could not verify this organization.")
                assertTrue("GET /v1/organization" !in fixture.requests)
                compose.onNodeWithText("Open Today").assertDoesNotExist()
                compose.onNodeWithText("Open Work").assertDoesNotExist()
                assertTrue(fixture.requests.none { it.endsWith("/briefing/today") || it.endsWith("/work/tasks") })

                fixture.readiness = CoreLauncherFixture.Readiness.Ready
                click("Check again")
                awaitText("Fixture organization")
                compose.onNodeWithText("Role: viewer").assertIsDisplayed()
                awaitText("Organization access is active.")
                val backgroundTodayReads = fixture.requests.count { it == "GET /v1/briefing/today" }
                assertTrue("only the session-scoped background Today read is allowed: ${fixture.requests}", backgroundTodayReads <= 1)
                assertTrue("Work waits for explicit user navigation", fixture.requests.none { it.endsWith("/work/tasks") })
                val todayBaseline = fixture.requests.count { it == "GET /v1/briefing/today" }
                fixture.todayResponsePhase = CoreLauncherFixture.TodayResponsePhase.HoldStale
                click("Open Today")
                awaitText("Loading Today…")
                compose.waitUntil(10_000) {
                    fixture.staleTodayRequested.isCompleted &&
                        fixture.requests.count { it == "GET /v1/briefing/today" } > todayBaseline
                }
                assertTrue(
                    "the manual Today action must start a request beyond the ready-background baseline",
                    fixture.requests.count { it == "GET /v1/briefing/today" } > todayBaseline,
                )
                pressBack()
                awaitText("Fixture organization")
                fixture.todayResponsePhase = CoreLauncherFixture.TodayResponsePhase.Fresh
                click("Open Today")
                awaitText("Needs attention")
                awaitText("Review synthetic checklist")
                fixture.releaseStaleToday.complete(Unit)
                compose.waitUntil(10_000) { fixture.staleTodayReleased.isCompleted }
                compose.waitForIdle()
                compose.onNodeWithText("Obsolete briefing").assertDoesNotExist()
                click("Back to organization")

                fixture.denyWork = true
                click("Open Work")
                awaitText("Work is unavailable. Refresh to try again.")
                compose.onNodeWithText("Current synthetic task").assertDoesNotExist()
                fixture.denyWork = false
                click("Refresh Work list")
                awaitText("Current synthetic task")
                compose.onNodeWithText("Create task").assertDoesNotExist()
                click("Back to organization")
                fixture.emptyWork = true
                click("Open Work")
                awaitText("No matching tasks.")
                pressBack()
                awaitText("Fixture organization")
                click("Sign out of Core")
                awaitText("Open Core sign-in")
                compose.onNodeWithText("Fixture organization").assertDoesNotExist()
                compose.waitUntil(10_000) { "DELETE /v1/auth/session" in fixture.requests }
                assertTrue("POST /v1/account-security/sign-out" !in fixture.requests)
                fixture.releaseRevocation.complete(Unit)
                compose.waitUntil(10_000) { "POST /v1/account-security/sign-out" in fixture.requests }
                val expectedManualRequests = listOf(
                        "POST /v1/account-security/password/login/start",
                        "POST /v1/account-security/password/login/verify",
                        "GET /v1/account-security/organizations",
                        "POST /v1/account-security/session",
                        "GET /v1/auth/session",
                        "GET /v1/organization",
                        "GET /v1/organization/entitlements",
                        "GET /v1/work/tasks",
                        "GET /v1/work/tasks",
                        "GET /v1/work/tasks",
                        "DELETE /v1/auth/session",
                        "POST /v1/account-security/sign-out",
                )
                assertTrue(
                    "two explicit Today openings plus at most one scoped background read",
                    fixture.requests.count { it == "GET /v1/briefing/today" } in 2..3,
                )
                assertTrue(
                    "three UI readiness checks plus at most two process-owned checks (initial and one retry)",
                    fixture.requests.count { it == "GET /v1/organization/readiness" } in 3..5,
                )
                assertEquals(
                    expectedManualRequests,
                    fixture.requests.filterNot {
                        it == "GET /v1/briefing/today" || it == "GET /v1/organization/readiness" ||
                            it in setOf("GET /v1/workspaces", "GET /v1/projects", "GET /v1/customers")
                    },
                )
                for (path in listOf("workspaces", "projects", "customers")) assertEquals(2, fixture.requests.count { it == "GET /v1/$path" })
            }
        } finally {
            fixture.releaseRevocation.complete(Unit)
            fixture.releaseStaleToday.complete(Unit)
        }
    }

    @Test
    fun explicitCoreConfigSelectsCoreOutsideTheSyntheticEnvironmentGate() {
        val fixture = CoreLauncherFixture()
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        try {
            launch().use {
                awaitText("Open Core sign-in")
                assertTrue(fixture.requests.isEmpty())
                val composition = checkNotNull(app.corePhoneComposition())
                it.recreate()
                awaitText("Open Core sign-in")
                assertSame(composition, app.corePhoneComposition())
            }
        } finally {
            app.clearCorePhoneCompositionForTest()
            app.coreLauncherEngine = null
            app.coreApiOriginOverride = null
            app.coreRpIdOverride = null
        }
    }

    @Test
    fun coreShareOnNewIntentIsReviewedOnceAndClearedWhenAuthorityChanges() {
        val fixture = CoreLauncherFixture()
        fixture.organizationRole = CoreRole.owner
        fixture.readiness = CoreLauncherFixture.Readiness.Ready
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        try {
            launch().use { scenario ->
                click("Open Core sign-in")
                click("Sign in with password and authenticator")
                input("Username", CoreLauncherFixture.USERNAME)
                input("Password", CoreLauncherFixture.PASSWORD)
                click("Continue")
                awaitText("Enter your authenticator code")
                input("Authenticator code", CoreLauncherFixture.TOTP)
                click("Sign in")
                click("Load organizations")
                click("fixture-org · owner")
                awaitText("Fixture organization")

                val requestsBeforeReceipt = fixture.requests.toList()
                val incoming = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Review these notes before creating anything")
                scenario.onActivity { it.startActivity(incoming) }
                awaitText("Review shared text")
                awaitText("Review these notes before creating anything")
                assertEquals("receiving text does not make API requests", requestsBeforeReceipt, fixture.requests.toList())

                click("Use in draft")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Review these notes before creating anything").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().isEmpty()
                }
                assertEquals("using the text fills a local draft without an API request", requestsBeforeReceipt, fixture.requests.toList())

                scenario.onActivity { it.startActivity(incoming) }
                compose.waitForIdle()
                compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                assertEquals(requestsBeforeReceipt, fixture.requests.toList())

                Fixtures.onMain { checkNotNull(app.corePhoneComposition()).accounts.clear() }
                awaitText("Open Core sign-in")
                compose.onAllNodesWithText("Review these notes before creating anything").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            }
        } finally {
            app.clearCorePhoneCompositionForTest()
            app.coreLauncherEngine = null
            app.coreApiOriginOverride = null
            app.coreRpIdOverride = null
        }
    }

    @Test
    fun coreShareColdActivityCreateUsesTheExistingReadySession() {
        val fixture = CoreLauncherFixture()
        fixture.organizationRole = CoreRole.owner
        fixture.readiness = CoreLauncherFixture.Readiness.Ready
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        var home: ActivityScenario<MainActivity>? = null
        try {
            val initialHome = launch()
            home = initialHome
            click("Open Core sign-in")
            click("Sign in with password and authenticator")
            input("Username", CoreLauncherFixture.USERNAME)
            input("Password", CoreLauncherFixture.PASSWORD)
            click("Continue")
            awaitText("Enter your authenticator code")
            input("Authenticator code", CoreLauncherFixture.TOTP)
            click("Sign in")
            click("Load organizations")
            click("fixture-org · owner")
            awaitText("Fixture organization")
            val composition = checkNotNull(app.corePhoneComposition())
            val current = composition.organizations.state.value
            initialHome.close()
            home = null

            val beforeShareLaunch = fixture.requests.size
            val incoming = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A note from another app")
            launch(incoming).use {
                assertSame(current, composition.organizations.state.value)
                awaitText("Review shared text")
                awaitText("A note from another app")
                assertTrue(fixture.requests.drop(beforeShareLaunch).none {
                    it.startsWith("POST ") || it.startsWith("PUT ") || it.startsWith("DELETE ") ||
                        it.startsWith("GET /v1/work/")
                })
                click("Use in draft")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("A note from another app").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().isEmpty()
                }
            }
        } finally {
            home?.close()
            app.clearCorePhoneCompositionForTest()
            app.coreLauncherEngine = null
            app.coreApiOriginOverride = null
            app.coreRpIdOverride = null
        }
    }

    @Test
    fun coreShareOnColdSignedOutLaunchIsDroppedInsteadOfQueued() {
        val fixture = CoreLauncherFixture()
        fixture.organizationRole = CoreRole.owner
        fixture.readiness = CoreLauncherFixture.Readiness.Unready
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        try {
            val incoming = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Do not queue this while signed out")
            launch(incoming).use {
                awaitText("Open Core sign-in")
                compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                assertTrue(fixture.requests.isEmpty())

                click("Open Core sign-in")
                click("Sign in with password and authenticator")
                input("Username", CoreLauncherFixture.USERNAME)
                input("Password", CoreLauncherFixture.PASSWORD)
                click("Continue")
                awaitText("Enter your authenticator code")
                input("Authenticator code", CoreLauncherFixture.TOTP)
                click("Sign in")
                click("Load organizations")
                click("fixture-org · owner")
                awaitText("This organization has not finished setup. Continue in the web workspace.")
                val unreadyShare = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Do not queue this while organization is unready")
                it.onActivity { activity -> activity.startActivity(unreadyShare) }
                compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }

                fixture.readiness = CoreLauncherFixture.Readiness.Ready
                click("Check again")
                awaitText("Fixture organization")
                compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                compose.onAllNodesWithText("Do not queue this while signed out").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                compose.onAllNodesWithText("Do not queue this while organization is unready").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            }
        } finally {
            app.clearCorePhoneCompositionForTest()
            app.coreLauncherEngine = null
            app.coreApiOriginOverride = null
            app.coreRpIdOverride = null
        }
    }

    @Test
    fun syntheticLauncherSwitchesOnlyVerifiedMembershipsAndDiscardsOldScope() {
        val fixture = CoreLauncherFixture(multipleOrganizations = true)
        val localClient = if (BuildConfig.WHUMPO_ENVIRONMENT == "local") {
            // Exercise the same mounted launcher with a synthetic engine without enabling the default local entry.
            coreApiClient(CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test")), fixture.engine())
        } else null
        val localComposition = localClient?.let {
            CorePhoneComposition(
                CoreClientSettings.allowlisted(
                    "https://core.example.test",
                    "core.example.test",
                    setOf("https://core.example.test"),
                ),
                it,
                app,
            )
        }
        app.coreLauncherEngine = fixture::engine
        fixture.readiness = CoreLauncherFixture.Readiness.Ready
        try {
            launch().use { scenario ->
                if (localClient != null) scenario.onActivity { activity ->
                    val passkeys = NativeAccountPasskeys(app, app.container.activities)
                    activity.setContent { CoreLauncher(checkNotNull(localComposition), passkeys) }
                }
                click("Open Core sign-in")
                click("Sign in with password and authenticator")
                input("Username", CoreLauncherFixture.USERNAME)
                input("Password", CoreLauncherFixture.PASSWORD)
                click("Continue")
                awaitText("Enter your authenticator code")
                input("Authenticator code", CoreLauncherFixture.TOTP)
                click("Sign in")
                click("Load organizations")
                click("fixture-org · viewer")
                compose.waitUntil(10_000) { fixture.oldEntitlementRequested.isCompleted }

                click("Switch organization")
                awaitText("Switch to other-org · operator")
                compose.onNodeWithText("Switch to revoked-org · viewer").assertDoesNotExist()
                compose.onNodeWithText("Switch to fixture-org · viewer").assertDoesNotExist()
                val target = compose.onNodeWithText("Switch to other-org · operator").performScrollTo()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                target.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
                awaitText("Switching organization…")
                compose.onNodeWithText("Fixture organization").assertDoesNotExist()
                compose.onNodeWithText("Other organization").assertDoesNotExist()
                fixture.releaseSwitch.complete(Unit)

                awaitText("Other organization")
                compose.onNodeWithText("Role: operator").assertIsDisplayed()
                awaitText("Organization access has expired.")
                fixture.releaseOldEntitlement.complete(Unit)
                compose.waitUntil(10_000) { fixture.oldEntitlementReleased.isCompleted }
                compose.waitForIdle()
                compose.onNodeWithText("Organization access is active.").assertDoesNotExist()
                compose.onNodeWithText("Fixture organization").assertDoesNotExist()

                click("Switch organization")
                fixture.denySwitch = true
                click("Switch to fixture-org · viewer")
                awaitText("Could not verify the organization switch. Sign in again.")
                awaitText("Open Core sign-in")
                compose.waitUntil(10_000) { app.corePhoneComposition()?.accounts?.state?.value == null }
                compose.onNodeWithText("Other organization").assertDoesNotExist()
                compose.onNodeWithText("Organization access has expired.").assertDoesNotExist()
                assertEquals(2, fixture.requests.count { it == "POST /v1/auth/session/switch" })
                assertEquals(2, fixture.requests.count { it == "GET /v1/auth/session" })
                assertEquals(1, fixture.requests.count { it == "POST /v1/account-security/session" })
            }
        } finally {
            fixture.releaseSwitch.complete(Unit)
            fixture.releaseOldEntitlement.complete(Unit)
            localComposition?.close()
        }
    }

    @Test
    fun syntheticLauncherDiscardsReadsWhenWorkspaceSessionIsRevoked() {
        if (BuildConfig.WHUMPO_ENVIRONMENT == "local") return
        val fixture = CoreLauncherFixture()
        fixture.readiness = CoreLauncherFixture.Readiness.Ready
        fixture.revokeOnWork = true
        app.coreLauncherEngine = fixture::engine
        launch().use {
            click("Open Core sign-in")
            click("Sign in with password and authenticator")
            input("Username", CoreLauncherFixture.USERNAME)
            input("Password", CoreLauncherFixture.PASSWORD)
            click("Continue")
            awaitText("Enter your authenticator code")
            input("Authenticator code", CoreLauncherFixture.TOTP)
            click("Sign in")
            click("Load organizations")
            click("fixture-org · viewer")
            awaitText("Fixture organization")
            click("Open Work")
            awaitText("Open Core sign-in")
            compose.waitUntil(10_000) { app.corePhoneComposition()?.accounts?.state?.value == null }
            assertEquals(CoreOrganizationSessionController.State.SignedOut,
                app.corePhoneComposition()?.organizations?.state?.value)
            compose.onNodeWithText("Fixture organization").assertDoesNotExist()
            compose.onNodeWithText("Work is unavailable. Refresh to try again.").assertDoesNotExist()
            compose.onNodeWithText("Current synthetic task").assertDoesNotExist()
            assertEquals(1, fixture.requests.count { it == "GET /v1/work/tasks" })
        }
    }

    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

    private fun launch(intent: Intent) = ActivityScenario.launch<MainActivity>(intent)

    private fun awaitLegacyEntry() {
        awaitText("Welcome to Whumpo.")
        compose.onNodeWithTag("auth-sign-in").assertIsDisplayed()
        compose.onNodeWithText("Open Core sign-in").assertDoesNotExist()
    }

    private fun awaitText(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(label: String) {
        awaitText(label)
        compose.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun input(label: String, value: String) {
        compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextInput(value)
    }
}

private class CoreLauncherFixture(private val multipleOrganizations: Boolean = false) {
    enum class Readiness { Unready, Foreign, Ready }
    enum class TodayResponsePhase { Fresh, HoldStale }

    val requests = CopyOnWriteArrayList<String>()
    val releaseRevocation = CompletableDeferred<Unit>()
    val releaseSwitch = CompletableDeferred<Unit>()
    val oldEntitlementRequested = CompletableDeferred<Unit>()
    val releaseOldEntitlement = CompletableDeferred<Unit>()
    val oldEntitlementReleased = CompletableDeferred<Unit>()
    val staleTodayRequested = CompletableDeferred<Unit>()
    val releaseStaleToday = CompletableDeferred<Unit>()
    val staleTodayReleased = CompletableDeferred<Unit>()
    @Volatile var revokeOnWork = false
    @Volatile var denyWork = false
    @Volatile var emptyWork = false
    @Volatile var denySwitch = false
    @Volatile var readiness = Readiness.Unready
    @Volatile var todayResponsePhase = TodayResponsePhase.Fresh
    @Volatile var organizationRole = CoreRole.viewer
    private val deadline = System.currentTimeMillis() + 300_000
    private val grant = "ag1." + "g".repeat(42) + "A"
    private val token = "t".repeat(42) + "A"
    private val switchedToken = "v".repeat(42) + "A"

    fun engine() = MockEngine { request ->
        assertEquals("https", request.url.protocol.name)
        assertEquals("core.example.test", request.url.host)
        assertEquals(443, request.url.port)
        val route = "${request.method.value} ${request.url.encodedPath}"
        if (route == "GET /v1/auth/organizations") assertEquals("25", request.url.parameters["limit"])
        else if (route == "GET /v1/work/tasks") {
            assertEquals("50", request.url.parameters["limit"])
            assertEquals(mapOf("limit" to "50", "due" to "all", "customer" to "all", "assignee" to "all"), request.url.parameters.entries().associate { it.key to it.value.single() })
        } else if (route in setOf("GET /v1/workspaces", "GET /v1/projects")) assertEquals("20", request.url.parameters["limit"])
        else if (route == "GET /v1/customers") { assertEquals("20", request.url.parameters["limit"]); assertEquals("all", request.url.parameters["state"]) }
        else assertTrue(request.url.parameters.isEmpty())
        val authorization = request.headers[HttpHeaders.Authorization]
        val switched = authorization == "Bearer $switchedToken"
        val organization = if (switched) "other-org" else "fixture-org"
        if (route != "DELETE /v1/auth/session") requests += route
        val body = when (route) {
            "POST /v1/account-security/password/login/start" -> {
                assertNull(authorization)
                val values = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals(USERNAME, values.getValue("username").jsonPrimitive.content)
                assertEquals(PASSWORD, values.getValue("password").jsonPrimitive.content)
                """{"challengeId":"fixture-challenge","expiresAt":$deadline}"""
            }
            "POST /v1/account-security/password/login/verify" -> {
                assertNull(authorization)
                val values = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals(setOf("version", "challengeId", "totp"), values.keys)
                assertEquals("fixture-challenge", values.getValue("challengeId").jsonPrimitive.content)
                assertEquals(TOTP, values.getValue("totp").jsonPrimitive.content)
                grantBody()
            }
            "GET /v1/account-security/organizations" -> {
                assertEquals("AccountGrant $grant", authorization)
                """{"items":[{"orgId":"fixture-org","role":"${organizationRole.name.lowercase()}"}],"nextCursor":null}"""
            }
            "POST /v1/account-security/session" -> {
                assertEquals("AccountGrant $grant", authorization)
                val values = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals("fixture-org", values.getValue("orgId").jsonPrimitive.content)
                """{"token":"$token","expiresAt":$deadline}"""
            }
            "GET /v1/auth/session" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                val session = if (switched) "other-session" else "fixture-session"
                val role = if (switched) "operator" else organizationRole.name.lowercase()
                """{"sessionId":"$session","orgId":"$organization","userId":"fixture-account","role":"$role","expiresAt":$deadline}"""
            }
            "GET /v1/auth/organizations" -> {
                assertTrue(multipleOrganizations)
                val expected = if (requests.count { it == route } == 1) token else switchedToken
                assertEquals("Bearer $expected", authorization)
                """{"items":[{"orgId":"fixture-org","role":"${organizationRole.name.lowercase()}","active":${!switched}},
                    {"orgId":"other-org","role":"operator","active":$switched}],"nextCursor":null}"""
            }
            "POST /v1/auth/session/switch" -> {
                assertTrue(multipleOrganizations)
                val values = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals(setOf("orgId"), values.keys)
                if (denySwitch) {
                    assertEquals("Bearer $switchedToken", authorization)
                    assertEquals("fixture-org", values.getValue("orgId").jsonPrimitive.content)
                    return@MockEngine respond("{}", HttpStatusCode.NotFound)
                }
                assertEquals("Bearer $token", authorization)
                assertEquals("other-org", values.getValue("orgId").jsonPrimitive.content)
                releaseSwitch.await()
                """{"token":"$switchedToken","expiresAt":$deadline}"""
            }
            "GET /v1/organization/readiness" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                val projectId = if (switched) "other-project" else "fixture-project"
                val foreignOrgId = if (switched) "fixture-org" else "other-org"
                when (readiness) {
                    Readiness.Unready -> """{"orgId":"$organization","ready":false}"""
                    Readiness.Foreign -> """{"orgId":"$foreignOrgId","ready":true,"projectId":"$projectId"}"""
                    Readiness.Ready -> """{"orgId":"$organization","ready":true,"projectId":"$projectId"}"""
                }
            }
            "GET /v1/organization" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                val name = if (switched) "Other organization" else "Fixture organization"
                """{"orgId":"$organization","displayName":"$name","version":1}"""
            }
            "GET /v1/organization/entitlements" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                if (multipleOrganizations && !switched) {
                    oldEntitlementRequested.complete(Unit)
                    withContext(NonCancellable) {
                        releaseOldEntitlement.await()
                        oldEntitlementReleased.complete(Unit)
                    }
                }
                val status = if (switched) "expired" else "active"
                """{"orgId":"$organization","status":"$status","membershipMode":"business_team","humanSeatLimit":5,
                    "activeHumanSeats":2,"reservedHumanSeats":0,"availableHumanSeats":0,"teamAdmissionAvailable":false,
                    "siteBuildAvailable":false,"revision":1,"effectiveFrom":1,"expiresAt":$deadline}"""
            }
            "GET /v1/briefing/today" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                assertEquals("Today cannot be read before this organization is ready", Readiness.Ready, readiness)
                val todayTaskId = if (switched) "other-task" else "fixture-task"
                val todayProjectId = if (switched) "other-project" else "fixture-project"
                val title = if (todayResponsePhase == TodayResponsePhase.HoldStale) {
                    staleTodayRequested.complete(Unit)
                    withContext(NonCancellable) {
                        releaseStaleToday.await()
                        staleTodayReleased.complete(Unit)
                    }
                    "Obsolete briefing"
                } else "Review synthetic checklist"
                val emptySection = """{"items":[],"truncated":false}"""
                """{"generatedAt":"2026-11-14T12:00:00.000Z","dueBefore":"2026-11-15T12:00:00.000Z",
                    "verifiedSince":"2026-11-13T12:00:00.000Z","empty":false,"sections":{"approvals":$emptySection,
                    "deadlines":$emptySection,"verified":$emptySection,"blocked":{"items":[{"provenance":"source_record",
                    "observedAt":"2026-11-14T10:00:00.000Z","source":{"kind":"task","taskId":"$todayTaskId",
                    "projectId":"$todayProjectId","version":1},"title":"$title","status":"blocked","dueAt":null}],"truncated":false}}}"""
            }
            "GET /v1/workspaces", "GET /v1/projects", "GET /v1/customers" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                """{"items":[]}"""
            }
            "GET /v1/work/tasks" -> {
                assertEquals(if (switched) "Bearer $switchedToken" else "Bearer $token", authorization)
                if (revokeOnWork) return@MockEngine respond("{}", HttpStatusCode.Unauthorized)
                if (denyWork) return@MockEngine respond("{}", HttpStatusCode.Forbidden)
                val total = if (emptyWork) 0 else 1
                val taskId = if (switched) "other-task" else "fixture-task"
                val projectId = if (switched) "other-project" else "fixture-project"
                val workspaceId = if (switched) "other-workspace" else "fixture-workspace"
                val items = if (emptyWork) "" else """{"id":"$taskId","projectId":"$projectId","workspaceId":"$workspaceId","title":"Current synthetic task","description":null,"parentTaskId":null,"dueAt":null,"status":"ready","version":1}"""
                """{"items":[$items],"counts":{"total":$total,"overdue":0,"byStatus":{"backlog":0,"ready":$total,"in_progress":0,"blocked":0,"needs_approval":0,"done":0,"canceled":0}},"asOf":"2026-11-19T12:00:00Z"}"""
            }
            "DELETE /v1/auth/session" -> {
                // Session cleanup is scoped to the captured old authority even after the UI switches.
                assertEquals("Bearer $token", authorization)
                requests += route
                releaseRevocation.await()
                return@MockEngine respond("", HttpStatusCode.NoContent)
            }
            "POST /v1/account-security/sign-out" -> {
                assertEquals("AccountGrant $grant", authorization)
                "{}"
            }
            else -> error("Unexpected synthetic route $route")
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun grantBody() = """{
        "authority":{"kind":"account_grant","token":"$grant","purpose":"account","expiresAt":$deadline},
        "state":{"version":1,"accountId":"fixture-account","candidateId":null,"purpose":"account","phase":"ready",
        "generation":1,"inventoryRevision":1,"attemptId":null,"attemptRevision":null,"intentRevision":null,"methods":[],
        "recovery":{"kind":"none","generation":0,"remainingCodes":null,"offer":null},
        "approvedMethod":null,"freshUntil":null,"expiresAt":$deadline,"workspaceAccess":"available","setupProfile":null}
    }"""

    companion object {
        const val USERNAME = "fixture-user"
        const val PASSWORD = "fixture-password"
        const val TOTP = "123456"
    }
}
