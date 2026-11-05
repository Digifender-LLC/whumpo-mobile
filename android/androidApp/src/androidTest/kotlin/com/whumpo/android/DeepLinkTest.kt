package com.whumpo.android

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verified App Links reach the shell, and only behind the sign-in gate. Delivery goes through the same path as `onNewIntent`. */
@RunWith(AndroidJUnit4::class)
class DeepLinkTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val id = "11111111-1111-4111-8111-000000000001"

    @Before
    fun setUp() = Fixtures.reset()

    @After
    fun tearDown() = Fixtures.reset()

    private fun open(url: String) = compose.activityRule.scenario.onActivity {
        it.handleLink(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    private fun signIn() {
        Fixtures.container.accountState.activate(Fixtures.ownerA)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav-chat").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aProjectLinkOpensTheProjectWhenSignedIn() {
        signIn()
        open("https://whumpo.com/app/projects/$id")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("project-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-projects").assertIsSelected()
    }

    @Test
    fun anActivityLinkOpensActivityWhenSignedIn() {
        signIn()
        open("https://whumpo.com/app/activity/$id")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("screen-activity").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-activity").assertIsSelected()
    }

    @Test
    fun aLinkWhileSignedOutWaitsForSignIn() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth-sign-in").fetchSemanticsNodes().isNotEmpty() }
        open("https://whumpo.com/app/projects/$id")
        compose.onAllNodesWithTag("nav-projects").assertCountEquals(0)
        signIn()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("project-detail").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-projects").assertIsSelected()
    }

    @Test
    fun aMalformedLinkChangesNothing() {
        signIn()
        open("https://whumpo.com/app/projects/not-a-project")
        compose.waitForIdle()
        compose.onNodeWithTag("nav-chat").assertIsSelected()
    }
}
