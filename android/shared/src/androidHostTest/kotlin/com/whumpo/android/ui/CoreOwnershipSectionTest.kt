package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.ownership.CoreOwnershipSection
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreOwnershipController
import com.whumpo.core.auth.CoreSharedDraftItem
import com.whumpo.core.auth.CoreSharedDraftReview
import com.whumpo.core.auth.CoreSharedDraftRoute
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreOwnershipSectionTest : RobolectricUiTest() {
    @Test
    fun claimRequiresExplicitReviewAndShowsExactRetryAtLargeText() {
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond("""{"sessionId":"fixture-session","orgId":"fixture-org","userId":"fixture-owner","role":"owner","expiresAt":9000}""")
                "/v1/organization/ownership" -> {
                    reads.incrementAndGet()
                    respond("""{"orgId":"fixture-org","status":"undesignated","primaryOwnerUserId":null,"version":null,"provenance":null,"pendingTransfer":null}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/v1/organization/ownership/claim" -> {
                    writes.incrementAndGet()
                    respond("", HttpStatusCode.ServiceUnavailable)
                }
                else -> error("Unexpected path")
            }
        }
        val origin = "https://core.example.test"
        CoreApiClient(engine, CoreOrigin.allowlisted(origin, setOf(origin))).use { api ->
            val sessions = CoreOrganizationSessionController(api) { 1L }
            val active = runBlocking { sessions.establish("A".repeat(43)) }
            val ownership = CoreOwnershipController(sessions, active) { 1L }
            val reviewOpens = AtomicInteger()
            val shareRoute = CoreSharedDraftRoute()
            val item = checkNotNull(CoreSharedDraftItem.parse("Synthetic task details"))
            var heldShare: CoreSharedDraftReview? = null
            launch(Screen.Compact, dark = true, fontScale = 2f) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CoreOwnershipSection(ownership, "fixture-owner", CoreRole.owner, onReviewOpened = {
                        check(ownership.state.value.review == null)
                        check(heldShare?.matches(active.epoch) == false)
                        reviewOpens.incrementAndGet()
                    }, onInteraction = shareRoute::interrupt, signIn = { error("Unexpected sign-in") })
                }
            }.use {
                compose.onNodeWithText("Primary owner").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                compose.onNodeWithText("Show primary owner details").assertHeightIsAtLeast(48.dp).performClick()
                waitForText("Review primary owner claim")
                assertEquals(1, reads.get())
                assertEquals(0, writes.get())
                compose.runOnIdle {
                    val share = checkNotNull(shareRoute.receive(item, active.epoch, CoreRole.owner, 1L))
                    check(share.observeForeground(true, true, 2L))
                    heldShare = share
                }
                compose.onNodeWithText("Review primary owner claim").performScrollTo().performClick()
                assertEquals(1, reviewOpens.get())
                check(heldShare?.accept(active.epoch, 3L) == null)
                compose.onNodeWithText("New primary owner: fixture-owner (You)").performScrollTo().assertIsDisplayed()
                assertEquals(0, writes.get())
                compose.onNodeWithText("Confirm primary owner claim").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                waitForText("Retry exact request")
                val notice = compose.onNodeWithText("The service may have applied this request.", substring = true)
                notice.performScrollTo().assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
                )
                compose.onNodeWithText("Hide primary owner details").performScrollTo().performClick()
                compose.onNodeWithText("Retry exact request").assertDoesNotExist()
                compose.onNodeWithText("Show primary owner details").performClick()
                waitForText("Retry exact request")
                compose.onNodeWithText("Review primary owner claim").assertDoesNotExist()
                compose.onNodeWithText("Retry exact request").performScrollTo().assertHeightIsAtLeast(48.dp)
                assertEquals(1, writes.get())
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
