package com.whumpo.android

import android.content.Intent
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.whumpo.android.core.CoreLauncher
import com.whumpo.android.core.CoreScopedSharedTask
import com.whumpo.android.core.NativeAccountPasskeys
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreClientSettings
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
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
import org.junit.Assert.assertNotSame
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
        fixture.todayResponsePhase = CoreLauncherFixture.TodayResponsePhase.HoldPublisher
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        try {
            val hostIntent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            launchTracked(hostIntent).use { tracked ->
                val originalActivity = tracked.awaitFirstResumed()
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
                awaitText("Organization access is active.")
                compose.waitUntil(10_000) { fixture.publisherTodayRequested.isCompleted }

                val requestsBeforeReceipt = fixture.requests.toList()
                val incoming = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Review these notes before creating anything")
                sendExternalShare(incoming)
                awaitText("Review shared text")
                awaitText("Review these notes before creating anything")
                assertScrubbedShareBaseIntent(originalActivity)
                assertSame("singleTask delivery stays on the existing Activity instance", originalActivity,
                    tracked.currentStartedActivity())
                assertEquals("singleTask delivery creates no replacement Activity", 1, tracked.createdCount())
                assertEquals("receiving text does not make API requests", requestsBeforeReceipt, fixture.requests.toList())

                click("Use in draft")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Review these notes before creating anything").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().isEmpty()
                }
                assertEquals("using the text fills a local draft without an API request", requestsBeforeReceipt, fixture.requests.toList())

                sendExternalShare(incoming)
                compose.waitForIdle()
                compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                assertEquals(requestsBeforeReceipt, fixture.requests.toList())

                Fixtures.onMain { checkNotNull(app.corePhoneComposition()).accounts.clear() }
                awaitText("Open Core sign-in")
                compose.onAllNodesWithText("Review these notes before creating anything").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
            }
        } finally {
            fixture.releasePublisherToday.complete(Unit)
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
        fixture.accountId = "share-save-${System.nanoTime().toString(36)}"
        fixture.readiness = CoreLauncherFixture.Readiness.Ready
        fixture.todayResponsePhase = CoreLauncherFixture.TodayResponsePhase.HoldPublisher
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        var filesBeforeTest: Set<String>? = null
        try {
            // Establish a genuine current account/org session before the first Activity, keeping this ACTION_SEND
            // Activity launch cold while the process-owned publisher is independently held at its Today read.
            val composition = establishReadyCoreComposition()
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) { fixture.publisherTodayRequested.await() }
            }
            val current = composition.organizations.state.value
            assertEquals("the ready Core session is bound to the account grant", fixture.accountId,
                (current as CoreOrganizationSessionController.State.Active).session.userId)
            assertNoLiveMainActivity()

            val beforeShareLaunch = fixture.requests.size
            val draftDirectory = java.io.File(app.noBackupFilesDir, "core-offline-work")
            val draftFilesBefore = draftDirectory.listFiles()?.map { it.name }?.toSet().orEmpty()
            filesBeforeTest = draftFilesBefore
            val incoming = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A note from another app")
            launchTracked(incoming).use { tracked ->
                val launchedActivity = tracked.awaitFirstResumed()
                assertSame(current, composition.organizations.state.value)
                assertSame("cold ACTION_SEND is displayed by its tracked target instance", launchedActivity,
                    tracked.currentStartedActivity())
                assertEquals("cold ACTION_SEND creates one new Activity", 1, tracked.createdCount())
                awaitText("Review shared text")
                awaitText("A note from another app")
                assertScrubbedShareBaseIntent(launchedActivity)
                compose.waitForIdle()
                assertEquals(fixture.requests.take(beforeShareLaunch), fixture.requests.toList())
                click("Use in draft")
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("A note from another app").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithText("Review shared text").fetchSemanticsNodes().isEmpty()
                }
                assertEquals("Use keeps the text in memory until Save", draftFilesBefore,
                    draftDirectory.listFiles()?.map { it.name }?.toSet().orEmpty())
                assertEquals("Use makes no API request", fixture.requests.take(beforeShareLaunch), fixture.requests.toList())
                input("Task title", "Shared notes follow-up")
                click("Save on this device")
                awaitText("Saved on this device.")
                val newFiles = draftDirectory.listFiles()?.filter { it.name !in draftFilesBefore }.orEmpty()
                assertEquals("explicit Save creates exactly one encrypted offline draft", 1, newFiles.size)
                assertEquals("Save remains local", fixture.requests.take(beforeShareLaunch), fixture.requests.toList())
                newFiles.single().delete()
                if (draftDirectory.listFiles().isNullOrEmpty()) draftDirectory.delete()
            }
        } finally {
            fixture.releasePublisherToday.complete(Unit)
            filesBeforeTest?.let { before ->
                val draftDirectory = java.io.File(app.noBackupFilesDir, "core-offline-work")
                draftDirectory.listFiles()?.filterNot { it.name in before }?.forEach { it.delete() }
                if (draftDirectory.listFiles().isNullOrEmpty()) draftDirectory.delete()
            }
            app.clearCorePhoneCompositionForTest()
            app.coreLauncherEngine = null
            app.coreApiOriginOverride = null
            app.coreRpIdOverride = null
        }
    }

    @Test
    fun coreShareReviewExpiresAndPauseDiscardsTheTransientText() {
        val fixture = CoreLauncherFixture()
        fixture.organizationRole = CoreRole.owner
        fixture.readiness = CoreLauncherFixture.Readiness.Ready
        fixture.todayResponsePhase = CoreLauncherFixture.TodayResponsePhase.HoldPublisher
        app.coreApiOriginOverride = "https://api.core.example.test"
        app.coreRpIdOverride = "core.example.test"
        app.coreLauncherEngine = fixture::engine
        try {
            val hostIntent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            launchTracked(hostIntent).use { tracked ->
                val activity = tracked.awaitFirstResumed()
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
                awaitText("Organization access is active.")
                compose.waitUntil(10_000) { fixture.publisherTodayRequested.isCompleted }
                compose.waitForIdle()
                val composition = checkNotNull(app.corePhoneComposition())
                val requestsBeforeShare = fixture.requests.toList()
                val draftDirectory = java.io.File(app.noBackupFilesDir, "core-offline-work")
                val draftFilesBefore = draftDirectory.listFiles()?.map { it.name }?.toSet().orEmpty()

                val expiring = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Expires from this transient review")
                sendExternalShare(expiring)
                awaitText("Expires from this transient review")
                compose.mainClock.advanceTimeBy(60_100)
                compose.waitForIdle()
                compose.onAllNodesWithText("Expires from this transient review").fetchSemanticsNodes().let {
                    assertTrue("expired raw share text is removed from the production surface", it.isEmpty())
                }
                assertNull(composition.sharedDraft.value)
                assertEquals("expiry is local", requestsBeforeShare, fixture.requests.toList())

                val pauseCount = tracked.eventCount(activity, ActivityEvent.PAUSED)
                pressHomeFromActivity()
                tracked.awaitEvent(activity, ActivityEvent.PAUSED, pauseCount)
                val arrivingWhilePaused = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Review after this activity resumes")
                val resumeCount = tracked.eventCount(activity, ActivityEvent.RESUMED)
                sendExternalShare(arrivingWhilePaused)
                compose.waitUntil(10_000) { composition.sharedDraft.value != null }
                tracked.awaitEvent(activity, ActivityEvent.RESUMED, resumeCount)
                assertSame("background ACTION_SEND resumes the same singleTask Activity", activity,
                    tracked.currentStartedActivity())
                awaitText("Review after this activity resumes")
                click("Dismiss shared item")
                compose.waitUntil(10_000) { composition.sharedDraft.value == null }
                assertEquals("resuming and reviewing is local", requestsBeforeShare, fixture.requests.toList())

                val paused = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Pause removes this transient review")
                sendExternalShare(paused)
                awaitText("Pause removes this transient review")
                val staleOffer = checkNotNull(composition.sharedDraft.value)
                val stalePauseCount = tracked.eventCount(activity, ActivityEvent.PAUSED)
                pressHomeFromActivity()
                tracked.awaitEvent(activity, ActivityEvent.PAUSED, stalePauseCount)
                compose.waitUntil(10_000) { composition.sharedDraft.value == null }
                compose.onAllNodesWithText("Pause removes this transient review").fetchSemanticsNodes().let {
                    assertTrue("onPause clears the unaccepted share", it.isEmpty())
                }
                var staleUseResult: String? = "not-invoked"
                Fixtures.onMain { staleUseResult = composition.acceptSharedDraft(staleOffer) }
                assertNull("a stale Use callback cannot accept an offer after onPause", staleUseResult)
                assertEquals("pause does not persist the shared text", draftFilesBefore,
                    draftDirectory.listFiles()?.map { it.name }?.toSet().orEmpty())
                assertEquals("pause is local", requestsBeforeShare, fixture.requests.toList())
            }
        } finally {
            fixture.releasePublisherToday.complete(Unit)
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
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Do not queue this while signed out")
            assertNoLiveMainActivity()
            Fixtures.onMain { assertNull("cold share starts before any app Activity", app.container.activities.current) }
            launchTracked(incoming).use { tracked ->
                val activity = tracked.awaitFirstResumed()
                assertEquals("signed-out cold ACTION_SEND creates one target Activity", 1, tracked.createdCount())
                awaitText("Open Core sign-in")
                assertScrubbedShareBaseIntent(activity)
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
                sendExternalShare(unreadyShare)
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
        val fixture = CoreLauncherFixture(
            multipleOrganizations = true,
            expectedHost = if (BuildConfig.WHUMPO_ENVIRONMENT == "local") "core.example.test" else "api.core.example.test",
        )
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
                val composition = localComposition ?: checkNotNull(app.corePhoneComposition())
                val activeA = composition.organizations.state.value as CoreOrganizationSessionController.State.Active
                val aTask = CoreWorkTask(
                    id = "fixture-task-a", projectId = "fixture-project", title = "A scope task", description = null,
                    parentTaskId = null, dueAt = null, status = CoreTaskStatus.Blocked, version = 1,
                )
                val aSelection = CoreScopedSharedTask(activeA, aTask)
                assertSame(aTask, aSelection.forActive(activeA))

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
                val activeB = composition.organizations.state.value as CoreOrganizationSessionController.State.Active
                assertNotSame("switch creates a distinct current authority", activeA, activeB)
                assertNull("a selection from A cannot become the route target in B", aSelection.forActive(activeB))
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

    /**
     * Starts the real Activity without ActivityScenario's intent-filter-based lifecycle observer. MainActivity
     * deliberately scrubs the original ACTION_SEND filter identity after consuming its untrusted payload.
     */
    private fun launchTracked(intent: Intent): MainActivityLifecycleProbe {
        val probe = MainActivityLifecycleProbe(app)
        try {
            InstrumentationRegistry.getInstrumentation().context.startActivity(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            probe.awaitFirstResumed()
            return probe
        } catch (failure: Throwable) {
            probe.close()
            throw failure
        }
    }

    /** Start from the instrumentation APK so ACTION_SEND follows the same task/launchMode path as another app. */
    private fun sendExternalShare(intent: Intent) {
        InstrumentationRegistry.getInstrumentation().context.startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun pressHomeFromActivity() {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_HOME")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun assertNoLiveMainActivity() {
        val nonDestroyedStages = listOf(
            Stage.PRE_ON_CREATE,
            Stage.CREATED,
            Stage.STARTED,
            Stage.RESUMED,
            Stage.PAUSED,
            Stage.STOPPED,
            Stage.RESTARTED,
        )
        var liveActivities: List<MainActivity> = emptyList()
        Fixtures.onMain {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            liveActivities = nonDestroyedStages.flatMap(monitor::getActivitiesInStage).filterIsInstance<MainActivity>()
        }
        assertTrue("cold share begins without a live MainActivity", liveActivities.isEmpty())
    }

    private fun assertScrubbedShareBaseIntent(activity: MainActivity) {
        val base = activity.intent
        assertNull("consumed share action is removed from the base intent", base.action)
        assertNull("consumed share data is removed from the base intent", base.data)
        assertNull("consumed share type is removed from the base intent", base.type)
        assertTrue("consumed share categories are removed from the base intent", base.categories.isNullOrEmpty())
        assertNull("consumed share clip data is removed from the base intent", base.clipData)
        assertTrue("consumed share extras are removed from the base intent", base.extras?.isEmpty != false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertNull("consumed share identifier is removed from the base intent", base.identifier)
        }
        assertEquals(ComponentName(app, MainActivity::class.java), base.component)
    }

    private enum class ActivityEvent { CREATED, STARTED, RESUMED, PAUSED, STOPPED, DESTROYED }

    /** Tracks exact Activity objects; unlike ActivityScenario it does not compare a scrubbed Intent to launch filter. */
    private class MainActivityLifecycleProbe(private val application: Application) :
        Application.ActivityLifecycleCallbacks, AutoCloseable {
        private val lock = java.lang.Object()
        private val history = java.util.IdentityHashMap<MainActivity, MutableList<ActivityEvent>>()
        private val created = mutableListOf<MainActivity>()

        init {
            application.registerActivityLifecycleCallbacks(this)
        }

        fun awaitFirstResumed(timeoutMillis: Long = 10_000): MainActivity = await(timeoutMillis) {
            created.firstOrNull { activity -> history[activity]?.contains(ActivityEvent.RESUMED) == true }
        }

        fun createdCount(): Int = synchronized(lock) { created.size }

        fun currentStartedActivity(): MainActivity? = synchronized(lock) {
            created.lastOrNull { activity -> history[activity]?.lastOrNull() in setOf(ActivityEvent.STARTED, ActivityEvent.RESUMED) }
        }

        fun eventCount(activity: MainActivity, event: ActivityEvent): Int = synchronized(lock) {
            history[activity]?.count { it == event } ?: 0
        }

        fun awaitEvent(activity: MainActivity, event: ActivityEvent, afterCount: Int, timeoutMillis: Long = 10_000) {
            await(timeoutMillis) {
                val count = history[activity]?.count { it == event } ?: 0
                activity.takeIf { count > afterCount }
            }
        }

        private fun <T : Any> await(timeoutMillis: Long, value: () -> T?): T {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            synchronized(lock) {
                while (true) {
                    value()?.let { return it }
                    val remainingNanos = deadline - System.nanoTime()
                    if (remainingNanos <= 0) throw AssertionError("Timed out waiting for tracked MainActivity lifecycle")
                    val millis = (remainingNanos / 1_000_000).coerceAtLeast(1)
                    lock.wait(millis)
                }
            }
        }

        private fun recordCreated(activity: Activity) {
            if (activity !is MainActivity) return
            synchronized(lock) {
                if (activity !in history) {
                    created += activity
                    history[activity] = mutableListOf()
                }
                history.getValue(activity) += ActivityEvent.CREATED
                lock.notifyAll()
            }
        }

        private fun record(activity: Activity, event: ActivityEvent) {
            if (activity !is MainActivity) return
            synchronized(lock) {
                val events = history[activity] ?: return
                events += event
                lock.notifyAll()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = recordCreated(activity)
        override fun onActivityStarted(activity: Activity) = record(activity, ActivityEvent.STARTED)
        override fun onActivityResumed(activity: Activity) = record(activity, ActivityEvent.RESUMED)
        override fun onActivityPaused(activity: Activity) = record(activity, ActivityEvent.PAUSED)
        override fun onActivityStopped(activity: Activity) = record(activity, ActivityEvent.STOPPED)
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = record(activity, ActivityEvent.DESTROYED)

        override fun close() {
            try {
                val active = synchronized(lock) {
                    created.filter { history[it]?.lastOrNull() != ActivityEvent.DESTROYED }
                }
                if (active.isNotEmpty()) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        active.forEach { activity -> if (!activity.isFinishing) activity.finish() }
                    }
                    val deadline = System.nanoTime() + 10_000_000_000L
                    synchronized(lock) {
                        while (active.any { history[it]?.lastOrNull() != ActivityEvent.DESTROYED }) {
                            val remainingNanos = deadline - System.nanoTime()
                            if (remainingNanos <= 0) {
                                throw AssertionError("Timed out waiting for all probe-owned MainActivity instances to be destroyed")
                            }
                            lock.wait((remainingNanos / 1_000_000).coerceAtLeast(1))
                        }
                    }
                }
            } finally {
                application.unregisterActivityLifecycleCallbacks(this)
            }
        }
    }

    private fun establishReadyCoreComposition(): CorePhoneComposition {
        var created: CorePhoneComposition? = null
        Fixtures.onMain { created = app.corePhoneComposition() }
        val composition = checkNotNull(created)
        val active = kotlinx.coroutines.runBlocking {
            composition.accounts.loginPassword(CoreLauncherFixture.USERNAME, CoreLauncherFixture.PASSWORD)
            composition.accounts.loginTotp(CoreLauncherFixture.TOTP)
            val membership = composition.accounts.organizations().items.single()
            composition.accounts.chooseOrganization(membership)
            composition.organizations.state.value as CoreOrganizationSessionController.State.Active
        }
        val ready = kotlinx.coroutines.runBlocking { composition.organizations.organizationWhenReady(active) != null }
        assertTrue("the existing fixture session passed the production readiness check", ready)
        Fixtures.onMain { composition.publishShareReadiness(active.epoch, ready) }
        return composition
    }

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

private class CoreLauncherFixture(
    private val multipleOrganizations: Boolean = false,
    private val expectedHost: String = "api.core.example.test",
) {
    enum class Readiness { Unready, Foreign, Ready }
    enum class TodayResponsePhase { Fresh, HoldStale, HoldPublisher }

    val requests = CopyOnWriteArrayList<String>()
    val releaseRevocation = CompletableDeferred<Unit>()
    val releaseSwitch = CompletableDeferred<Unit>()
    val oldEntitlementRequested = CompletableDeferred<Unit>()
    val releaseOldEntitlement = CompletableDeferred<Unit>()
    val oldEntitlementReleased = CompletableDeferred<Unit>()
    val staleTodayRequested = CompletableDeferred<Unit>()
    val releaseStaleToday = CompletableDeferred<Unit>()
    val staleTodayReleased = CompletableDeferred<Unit>()
    val publisherTodayRequested = CompletableDeferred<Unit>()
    val releasePublisherToday = CompletableDeferred<Unit>()
    val publisherTodayReleased = CompletableDeferred<Unit>()
    @Volatile var revokeOnWork = false
    @Volatile var denyWork = false
    @Volatile var emptyWork = false
    @Volatile var denySwitch = false
    @Volatile var readiness = Readiness.Unready
    @Volatile var todayResponsePhase = TodayResponsePhase.Fresh
    @Volatile var organizationRole = CoreRole.viewer
    @Volatile var accountId = "fixture-account"
    private val deadline = System.currentTimeMillis() + 300_000
    private val grant = "ag1." + "g".repeat(42) + "A"
    private val token = "t".repeat(42) + "A"
    private val switchedToken = "v".repeat(42) + "A"

    fun engine() = MockEngine { request ->
        assertEquals("https", request.url.protocol.name)
        assertEquals(expectedHost, request.url.host)
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
                """{"sessionId":"$session","orgId":"$organization","userId":"$accountId","role":"$role","expiresAt":$deadline}"""
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
                if (todayResponsePhase == TodayResponsePhase.HoldPublisher) {
                    publisherTodayRequested.complete(Unit)
                    withContext(NonCancellable) {
                        releasePublisherToday.await()
                        publisherTodayReleased.complete(Unit)
                    }
                }
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
        "state":{"version":1,"accountId":"$accountId","candidateId":null,"purpose":"account","phase":"ready",
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
