package com.whumpo.android

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        Fixtures.reset()
    }

    @Test
    fun defaultLauncherKeepsLegacyEntry() {
        launch().use {
            awaitLegacyEntry()
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

                fixture.readiness = CoreLauncherFixture.Readiness.Foreign
                click("Check again")
                awaitText("Could not verify this organization.")
                assertTrue("GET /v1/organization" !in fixture.requests)

                fixture.readiness = CoreLauncherFixture.Readiness.Ready
                click("Check again")
                awaitText("Fixture organization")
                compose.onNodeWithText("Role: viewer").assertIsDisplayed()
                click("Sign out of Core")
                awaitText("Open Core sign-in")
                compose.onNodeWithText("Fixture organization").assertDoesNotExist()
                compose.waitUntil(10_000) { "DELETE /v1/auth/session" in fixture.requests }
                assertTrue("POST /v1/account-security/sign-out" !in fixture.requests)
                fixture.releaseRevocation.complete(Unit)
                compose.waitUntil(10_000) { "POST /v1/account-security/sign-out" in fixture.requests }
                assertEquals(
                    listOf(
                        "POST /v1/account-security/password/login/start",
                        "POST /v1/account-security/password/login/verify",
                        "GET /v1/account-security/organizations",
                        "POST /v1/account-security/session",
                        "GET /v1/auth/session",
                        "GET /v1/organization/readiness",
                        "GET /v1/organization/readiness",
                        "GET /v1/organization/readiness",
                        "GET /v1/organization",
                        "DELETE /v1/auth/session",
                        "POST /v1/account-security/sign-out",
                    ),
                    fixture.requests,
                )
            }
        } finally {
            fixture.releaseRevocation.complete(Unit)
        }
    }

    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))

    private fun awaitLegacyEntry() {
        awaitText("Welcome back to Whumpo.")
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

private class CoreLauncherFixture {
    enum class Readiness { Unready, Foreign, Ready }

    val requests = CopyOnWriteArrayList<String>()
    val releaseRevocation = CompletableDeferred<Unit>()
    @Volatile var readiness = Readiness.Unready
    private val deadline = System.currentTimeMillis() + 300_000
    private val grant = "ag1." + "g".repeat(42) + "A"
    private val token = "t".repeat(42) + "A"

    fun engine() = MockEngine { request ->
        assertEquals("https", request.url.protocol.name)
        assertEquals("core.example.test", request.url.host)
        assertEquals(443, request.url.port)
        assertTrue(request.url.parameters.isEmpty())
        val route = "${request.method.value} ${request.url.encodedPath}"
        val authorization = request.headers[HttpHeaders.Authorization]
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
                """{"items":[{"orgId":"fixture-org","role":"viewer"}],"nextCursor":null}"""
            }
            "POST /v1/account-security/session" -> {
                assertEquals("AccountGrant $grant", authorization)
                val values = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                assertEquals("fixture-org", values.getValue("orgId").jsonPrimitive.content)
                """{"token":"$token","expiresAt":$deadline}"""
            }
            "GET /v1/auth/session" -> {
                assertEquals("Bearer $token", authorization)
                """{"sessionId":"fixture-session","orgId":"fixture-org","userId":"fixture-account","role":"viewer","expiresAt":$deadline}"""
            }
            "GET /v1/organization/readiness" -> {
                assertEquals("Bearer $token", authorization)
                when (readiness) {
                    Readiness.Unready -> """{"orgId":"fixture-org","ready":false}"""
                    Readiness.Foreign -> """{"orgId":"other-org","ready":true,"projectId":"fixture-project"}"""
                    Readiness.Ready -> """{"orgId":"fixture-org","ready":true,"projectId":"fixture-project"}"""
                }
            }
            "GET /v1/organization" -> {
                assertEquals("Bearer $token", authorization)
                """{"orgId":"fixture-org","displayName":"Fixture organization","version":1}"""
            }
            "DELETE /v1/auth/session" -> {
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
