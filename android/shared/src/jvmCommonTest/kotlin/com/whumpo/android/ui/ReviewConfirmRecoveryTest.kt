package com.whumpo.android.ui

import com.whumpo.android.ui.chat.ChatSession
import com.whumpo.android.ui.chat.HapticKind
import com.whumpo.android.ui.chat.Phase
import com.whumpo.android.ui.chat.ReauthHook
import com.whumpo.android.ui.chat.ReviewStep
import com.whumpo.core.api.MobileApiClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private typealias RecoveryReply = suspend MockRequestHandleScope.() -> HttpResponseData

/** Synthetic contract readbacks only; no backend, provider, device or persistence claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewConfirmRecoveryTest {
    private class Server {
        val posts = CopyOnWriteArrayList<JsonObject>()
        val receiptIds = CopyOnWriteArrayList<String>()
        var chatBody = ChatFixtures.body("chat.read")
        var chatError: Pair<Int, String>? = null
        var confirmReply: RecoveryReply = { throw IOException("Synthetic lost confirmation reply") }
        var receiptReply: RecoveryReply = { reply(ChatFixtures.error("not_found")) }

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData = with(scope) {
            val path = request.url.encodedPath.removePrefix("/api/mobile/v1")
            when {
                request.method == HttpMethod.Get && path == "/chat" ->
                    chatError?.let { reply(it) } ?: reply(200, chatBody)
                request.method == HttpMethod.Get && path.startsWith("/chat/turns/") -> {
                    receiptIds += path.substringAfterLast('/')
                    receiptReply(scope)
                }
                request.method == HttpMethod.Post && path == "/reviews/confirm" -> {
                    posts += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    confirmReply(scope)
                }
                else -> error("Unexpected synthetic request: ${request.method.value} $path")
            }
        }
    }

    private class Fixture(scope: CoroutineScope, maxPolls: Int = 0, newId: () -> String = { REQUEST }) : AutoCloseable {
        val server = Server()
        private val info = ChatFixtures.client("current")
        private val client: MobileApiClient = testClient(info) { request -> server.handle(this, request) }
        val session = ChatSession(scope, client, info, ReauthHook { true }, newId = newId, pollDelayMs = 1, maxPolls = maxPolls)

        suspend fun loadAndOpen() {
            session.load()
            session.awaitIdle()
            session.openReview(ChatFixtures.SITE_TURN)
        }

        suspend fun confirm() {
            session.confirmReview()
            session.awaitIdle()
        }

        override fun close() {
            session.reset()
            client.close()
        }
    }

    @Test
    fun aDismissedOffPageNotFoundAttemptRemainsReachableForAnExplicitIdenticalRetry() = runTest {
        val ids = ArrayDeque(listOf(REQUEST, NEXT_REQUEST))
        Fixture(backgroundScope, newId = { ids.removeFirst() }).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { throw IOException("Synthetic receipt outage") }
            f.confirm()
            assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
            val original = f.server.posts.single()
            f.session.dismissReview()
            f.server.chatBody = page(revision = 90, empty = true)
            f.server.receiptReply = { reply(ChatFixtures.error("not_found")) }
            f.session.refreshNow()
            f.session.awaitIdle()
            assertNull(f.session.state.value.review)
            assertTrue(f.session.state.value.turns.isEmpty())
            assertEquals(1, f.session.state.value.reviewChecks.size)
            assertEquals(REQUEST, f.session.state.value.reviewChecks.single().requestId)
            assertEquals(ReviewStep.Failed, f.session.state.value.reviewChecks.single().step)
            assertTrue(f.session.state.value.reviewChecks.single().canConfirm)
            assertFalse(f.session.state.value.reviewChecks.single().canCheckOutcome)
            val reads = f.server.receiptIds.size

            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            val reopened = f.session.state.value.review!!
            assertEquals(REQUEST, reopened.requestId)
            assertEquals(4, reopened.revision)
            assertTrue(reopened.canConfirm)
            assertEquals(reads, f.server.receiptIds.size)
            assertEquals(1, f.server.posts.size)
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            assertEquals(REQUEST, f.session.state.value.review?.requestId)
            assertEquals(1, ids.size)
            f.server.confirmReply = { reply(200, acknowledgment("completed")) }
            f.confirm()
            assertEquals(2, f.server.posts.size)
            assertEquals(original, f.server.posts.last())
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertTrue(f.session.state.value.reviewChecks.isEmpty())
        }
    }

    @Test
    fun aRetainedOffPageRetryLosesPermissionWhenTheReviewReturnsWithdrawn() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 90, empty = true)
            f.confirm()
            assertTrue(f.session.state.value.reviewChecks.single().canConfirm)
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            assertEquals(REQUEST, f.session.state.value.review?.requestId)
            f.server.chatBody = page(revision = 100, applied = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            assertTrue(f.session.state.value.reviewChecks.isEmpty())
            f.session.confirmReview()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.confirmReview()
            f.session.awaitIdle()
            assertEquals(1, f.server.posts.size)
            assertFalse(f.session.state.value.review?.canConfirm == true)
        }
    }

    @Test
    fun committedOutcomesAndResetDoNotLeaveAnOffPageRetryEntry() = runTest {
        for (status in listOf("failed", "completed")) {
            Fixture(backgroundScope).use { f ->
                f.loadAndOpen()
                f.server.chatBody = page(revision = 90, empty = true)
                f.server.receiptReply = { reply(200, receipt(status)) }
                f.confirm()
                assertTrue(f.session.state.value.reviewChecks.isEmpty())
                assertFalse(f.session.state.value.review?.canConfirm == true)
                f.session.dismissReview()
                f.session.openReview(ChatFixtures.SITE_TURN)
                f.session.confirmReview()
                f.session.awaitIdle()
                assertNull(f.session.state.value.review)
                assertEquals(1, f.server.posts.size)
            }
        }
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 90, empty = true)
            f.confirm()
            assertTrue(f.session.state.value.reviewChecks.single().canConfirm)
            f.session.reset()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.confirmReview()
            f.session.awaitIdle()
            assertTrue(f.session.state.value.reviewChecks.isEmpty())
            assertNull(f.session.state.value.review)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun aDismissedUncertainRequestCanReopenAfterItsTurnLeavesThePage() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { throw IOException("Synthetic receipt outage") }
            f.confirm()
            assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
            f.session.dismissReview()
            f.server.chatBody = page(revision = 90, empty = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.server.receiptReply = { reply(200, receipt("completed")) }
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertEquals(REQUEST, f.session.state.value.review?.requestId)
            assertEquals(listOf(REQUEST, REQUEST, REQUEST), f.server.receiptIds)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun anExplicitReadOnlyCheckSettlesTheRetainedRequestAfterTheReviewIsApplied() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            val original = f.session.state.value.review!!
            f.server.receiptReply = { throw IOException("Synthetic receipt outage") }
            f.confirm()
            assertEquals(REQUEST, f.session.state.value.reviewChecks.single().requestId)
            assertTrue(f.session.state.value.review?.canCheckOutcome == true)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            f.server.chatBody = page(revision = 20, applied = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            val reopened = f.session.state.value.review!!
            assertEquals(original.requestId, reopened.requestId)
            assertEquals(original.revision, reopened.revision)
            assertEquals(original.review.control, reopened.review.control)
            assertEquals(ReviewStep.Uncertain, reopened.step)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)

            f.server.receiptReply = { reply(200, receipt("completed")) }
            f.session.checkReview(REQUEST)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertTrue(f.session.state.value.reviewChecks.isEmpty())
            assertTrue(f.server.receiptIds.all { it == original.requestId })
            assertEquals(1, f.server.posts.size)
            val reads = f.server.receiptIds.size
            f.session.checkReview(REQUEST)
            f.session.awaitIdle()
            assertEquals(reads, f.server.receiptIds.size)
        }
    }

    @Test
    fun failedExplicitChecksRetainTheSameReadOnlyActionWithoutAnotherPost() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { throw IOException("Synthetic receipt outage") }
            f.confirm()
            val replies: List<RecoveryReply> = listOf(
                { reply(ChatFixtures.error("unavailable")) },
                { reply(200, "{}") },
                { reply(200, receipt("uncertain")) },
                { reply(200, receipt("pending")) },
            )
            for (response in replies) {
                f.server.receiptReply = response
                f.session.checkReview(REQUEST)
                f.session.awaitIdle()
                assertTrue(f.session.state.value.review?.canCheckOutcome == true)
                assertFalse(f.session.state.value.review?.canConfirm == true)
                assertEquals(REQUEST, f.session.state.value.reviewChecks.single().requestId)
                f.session.confirmReview()
                assertEquals(1, f.server.posts.size)
            }
            assertEquals(List(5) { REQUEST }, f.server.receiptIds)
        }
    }

    @Test
    fun retainedChecksCanReopenWhenTheCurrentTurnNoLongerHasReviewMetadata() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { throw IOException("Synthetic receipt outage") }
            f.confirm()
            f.session.dismissReview()
            f.server.chatBody = page(revision = 20, missingReview = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            assertNull(f.session.state.value.turns.first { it.id == ChatFixtures.SITE_TURN }.review)
            assertEquals(REQUEST, f.session.state.value.reviewChecks.single().requestId)
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
            assertEquals(REQUEST, f.session.state.value.review?.requestId)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            f.server.receiptReply = { reply(200, receipt("completed")) }
            f.session.checkReview(REQUEST)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun resetDropsRetainedChecksAndRejectsAnOldReadOnlyCallbackAndLateResponse() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { throw IOException("Synthetic receipt outage") }
            f.confirm()
            f.session.checkReview(NEXT_REQUEST)
            f.session.awaitIdle()
            assertEquals(listOf(REQUEST), f.server.receiptIds)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.server.receiptReply = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await(); reply(200, receipt("completed")) }
            }
            f.session.checkReview(REQUEST)
            entered.await()
            val previousWork = backgroundScope.coroutineContext[Job]!!.children.single()
            f.session.reset()
            release.complete(Unit)
            previousWork.join()
            f.session.checkReview(REQUEST)
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            assertEquals(Phase.Loading, f.session.state.value.phase)
            assertTrue(f.session.state.value.reviewChecks.isEmpty())
            assertNull(f.session.state.value.review)
            assertEquals("", f.session.state.value.announcement)
            assertEquals(listOf(REQUEST, REQUEST), f.server.receiptIds)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun completedRequestOutsideTheNewestPageIsRecoveredWithoutAnotherPost() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 90, empty = true)
            f.server.receiptReply = { reply(200, receipt("completed")) }
            f.confirm()
            assertEquals(listOf(REQUEST), f.server.receiptIds)
            assertEquals(1, f.server.posts.size)
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertEquals("Published. Your site is live.", f.session.state.value.review?.message)
            assertEquals(90, f.session.state.value.revision)
            assertTrue(f.session.state.value.turns.isEmpty())
            f.session.refreshNow()
            f.session.awaitIdle()
            assertTrue(f.session.state.value.turns.isEmpty())
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun aStaleRevisionRejectionCanBeReviewedAgainWithAFreshImmutableRequest() = runTest {
        val ids = ArrayDeque(listOf(REQUEST, NEXT_REQUEST))
        Fixture(backgroundScope, newId = { ids.removeFirst() }).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 20)
            f.server.confirmReply = { reply(ChatFixtures.error("stale_revision")) }
            f.confirm()
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertEquals(1, f.server.posts.size)
            val rejected = f.server.posts.single()
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)

            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(ReviewStep.Ready, f.session.state.value.review?.step)
            assertEquals(NEXT_REQUEST, f.session.state.value.review?.requestId)
            assertEquals(20, f.session.state.value.review?.revision)
            assertEquals(1, f.server.posts.size)
            f.server.confirmReply = { reply(200, acknowledgment("completed", id = NEXT_REQUEST)) }
            f.confirm()
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertEquals(2, f.server.posts.size)
            assertEquals(REQUEST, rejected["requestId"]!!.jsonPrimitive.content)
            assertEquals(4, rejected["expectedRevision"]!!.jsonPrimitive.int)
            assertEquals(NEXT_REQUEST, f.server.posts.last()["requestId"]!!.jsonPrimitive.content)
            assertEquals(20, f.server.posts.last()["expectedRevision"]!!.jsonPrimitive.int)
            assertEquals(rejected["control"], f.server.posts.last()["control"])
        }
    }

    @Test
    fun aStaleRejectionNeedsASuccessfulNewerAuthorizedPageBeforeANewAttempt() = runTest {
        val ids = ArrayDeque(listOf(REQUEST, NEXT_REQUEST))
        Fixture(backgroundScope, newId = { ids.removeFirst() }).use { f ->
            f.loadAndOpen()
            // A page fetched before the rejection must not authorize a replacement attempt.
            f.server.chatBody = page(revision = 20)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.server.chatError = ChatFixtures.error("unavailable")
            f.server.confirmReply = { reply(ChatFixtures.error("stale_revision")) }
            f.confirm()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertEquals(REQUEST, f.session.state.value.review?.requestId)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)

            f.server.chatError = null
            f.server.chatBody = page(revision = 30, applied = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            assertEquals(REQUEST, f.session.state.value.review?.requestId)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)

            f.server.chatBody = page(revision = 40)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(ReviewStep.Ready, f.session.state.value.review?.step)
            assertEquals(NEXT_REQUEST, f.session.state.value.review?.requestId)
            assertEquals(40, f.session.state.value.review?.revision)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun pendingRepliesPollTheSpecificRequestOutsideTheNewestPage() = runTest {
        val replies: List<RecoveryReply> = listOf(
            { throw IOException("Synthetic lost confirmation reply") },
            { reply(200, "{}") },
            { reply(200, acknowledgment("pending")) },
        )
        for (response in replies) {
            Fixture(backgroundScope, maxPolls = 2).use { f ->
                f.loadAndOpen()
                f.server.chatBody = page(revision = 90, empty = true)
                f.server.confirmReply = response
                f.server.receiptReply = { reply(200, receipt(if (f.server.receiptIds.size == 1) "pending" else "completed")) }
                f.confirm()
                assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
                assertEquals(listOf(REQUEST, REQUEST), f.server.receiptIds)
                assertEquals(90, f.session.state.value.revision)
                assertTrue(f.session.state.value.turns.isEmpty())
                assertEquals(1, f.server.posts.size)
                f.session.confirmReview()
                assertEquals(1, f.server.posts.size)
            }
        }
    }

    @Test
    fun pendingReceiptPollingIsBoundedAndNeverPostsAgain() = runTest {
        Fixture(backgroundScope, maxPolls = 2).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 90, empty = true)
            f.server.receiptReply = { reply(200, receipt("pending")) }
            f.confirm()
            assertEquals(ReviewStep.Pending, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            assertEquals(listOf(REQUEST, REQUEST, REQUEST), f.server.receiptIds)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun pendingReceiptsCanCompleteEvenWhileTheTranscriptIsUnavailable() = runTest {
        Fixture(backgroundScope, maxPolls = 2).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 90, empty = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.server.chatError = ChatFixtures.error("unavailable")
            f.server.receiptReply = { reply(200, receipt(if (f.server.receiptIds.size == 1) "pending" else "completed")) }
            f.confirm()
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertTrue(f.session.state.value.phase is Phase.Failed)
            assertEquals(listOf(REQUEST, REQUEST), f.server.receiptIds)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun anAppliedReviewOnThePageDoesNotResolveThisPendingRequest() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { reply(200, receipt("pending")) }
            f.confirm()
            f.server.chatBody = page(revision = 20, applied = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            assertEquals(ReviewStep.Pending, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Pending, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            assertFalse(f.session.state.value.haptic?.kind == HapticKind.Confirm)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
            assertTrue(f.server.receiptIds.all { it == REQUEST })
        }
    }

    @Test
    fun uncertainAndUnfamiliarStatusesStayUncertainWithoutReposting() = runTest {
        for (status in listOf("uncertain", "unfamiliar")) {
            Fixture(backgroundScope).use { f ->
                f.loadAndOpen()
                f.server.receiptReply = { reply(200, receipt(status)) }
                f.confirm()
                f.session.refreshNow()
                f.session.awaitIdle()
                f.session.dismissReview()
                f.session.openReview(ChatFixtures.SITE_TURN)
                f.session.awaitIdle()
                val flow = f.session.state.value.review
                assertEquals(ReviewStep.Uncertain, flow?.step)
                assertTrue(flow?.message.orEmpty().contains("unknown"))
                assertFalse(flow?.message.orEmpty().contains("Nothing was changed"))
                assertFalse(flow?.canConfirm == true)
                f.session.confirmReview()
                assertEquals(1, f.server.posts.size)
            }
        }
    }

    @Test
    fun failedReceiptDoesNotAuthorizeRepeatingACommittedRequest() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { reply(200, receipt("failed")) }
            f.confirm()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(ReviewStep.Failed, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.retryAllowed == true)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun onlyRequestSpecificNotFoundAllowsAnExplicitIdenticalRetryAfterReopening() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.confirm()
            assertEquals(ReviewStep.Failed, f.session.state.value.review?.step)
            assertTrue(f.session.state.value.review?.canConfirm == true)
            f.server.chatBody = page(revision = 20)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(4, f.session.state.value.review?.revision)
            assertEquals(1, f.server.posts.size)
            f.server.confirmReply = { reply(200, acknowledgment("completed")) }
            f.confirm()
            assertEquals(2, f.server.posts.size)
            assertEquals(f.server.posts.first(), f.server.posts.last())
            assertEquals(4, f.server.posts.last()["expectedRevision"]!!.jsonPrimitive.int)
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
        }
    }

    @Test
    fun aWithdrawnReviewDoesNotRegainApprovalAfterANotFoundReadback() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.confirm()
            f.server.chatBody = page(revision = 20, applied = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun aNotFoundReadbackDoesNotOverrideANewerWithdrawnReviewWhileTheSheetIsOpen() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { reply(200, receipt("pending")) }
            f.confirm()
            f.server.chatBody = page(revision = 20, applied = true)
            f.server.receiptReply = { reply(ChatFixtures.error("not_found")) }
            f.session.refreshNow()
            f.session.awaitIdle()
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            f.session.confirmReview()
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun refreshingAReadySheetWithWithdrawnMetadataPreventsItsFirstPost() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.chatBody = page(revision = 20, applied = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.session.confirmReview()
            assertEquals(ReviewStep.NoLongerCurrent, f.session.state.value.review?.step)
            assertTrue(f.server.posts.isEmpty())
        }
    }

    @Test
    fun aReceiptForAnotherIdCannotBecomeASuccessReceipt() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { reply(200, receipt("completed", id = ChatFixtures.SITE_TURN)) }
            f.confirm()
            assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            assertFalse(f.session.state.value.haptic?.kind == HapticKind.Confirm)
            assertEquals(listOf(REQUEST), f.server.receiptIds)
        }
    }

    @Test
    fun failedOrMalformedReadbacksNeverClaimNothingChangedOrEnableAPost() = runTest {
        val replies: List<RecoveryReply> = listOf(
            { throw IOException("Synthetic readback outage") },
            { reply(ChatFixtures.error("unavailable")) },
            { reply(200, "{}") },
        )
        for (response in replies) {
            Fixture(backgroundScope).use { f ->
                f.loadAndOpen()
                f.server.receiptReply = response
                f.confirm()
                f.server.chatError = ChatFixtures.error("unavailable")
                f.session.refreshNow()
                f.session.awaitIdle()
                assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
                assertFalse(f.session.state.value.review?.canConfirm == true)
                assertFalse(f.session.state.value.review?.message.orEmpty().contains("Nothing was changed"))
                assertFalse(f.session.state.value.notice.orEmpty().contains("Nothing was changed"))
                f.session.confirmReview()
                assertEquals(1, f.server.posts.size)
            }
        }
    }

    @Test
    fun anEmptyTranscriptAndFailedRefreshDoNotSettleAnUnresolvedConfirmation() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { reply(200, receipt("uncertain")) }
            f.confirm()
            f.server.chatBody = page(revision = 20, empty = true)
            f.session.refreshNow()
            f.session.awaitIdle()
            f.server.chatError = ChatFixtures.error("unavailable")
            f.server.receiptReply = { throw IOException("Synthetic readback outage") }
            f.session.refreshNow()
            f.session.awaitIdle()
            val state = f.session.state.value
            assertTrue(state.turns.isEmpty())
            assertFalse((state.phase as Phase.Failed).safe.contains("Nothing was changed"))
            assertEquals(ReviewStep.Uncertain, state.review?.step)
            assertFalse(state.review?.canConfirm == true)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun malformedPostAcknowledgmentsRequireReadbackBeforeAnyRetry() = runTest {
        for ((status, step) in listOf("completed" to ReviewStep.Done, "pending" to ReviewStep.Pending, "failed" to ReviewStep.Failed)) {
            Fixture(backgroundScope).use { f ->
                f.loadAndOpen()
                f.server.chatBody = page(revision = 90, empty = true)
                f.server.confirmReply = { reply(200, "{}") }
                f.server.receiptReply = { reply(200, receipt(status)) }
                f.confirm()
                assertEquals(listOf(REQUEST), f.server.receiptIds)
                assertEquals(step, f.session.state.value.review?.step)
                assertEquals(90, f.session.state.value.revision)
                assertFalse(f.session.state.value.review?.canConfirm == true)
                f.session.confirmReview()
                assertEquals(1, f.server.posts.size)
            }
        }
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.confirmReply = { reply(200, "not-json") }
            f.server.receiptReply = { reply(ChatFixtures.error("unavailable")) }
            f.confirm()
            assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
            assertFalse(f.session.state.value.review?.canConfirm == true)
            assertFalse(f.session.state.value.review?.message.orEmpty().contains("Nothing was changed"))
            assertEquals(listOf(REQUEST), f.server.receiptIds)
        }
    }

    @Test
    fun pendingAndUncertainAcknowledgmentsDoNotClaimDownstreamSuccess() = runTest {
        for ((status, step) in listOf("pending" to ReviewStep.Pending, "uncertain" to ReviewStep.Uncertain)) {
            Fixture(backgroundScope).use { f ->
                f.loadAndOpen()
                f.server.confirmReply = { reply(200, acknowledgment(status)) }
                f.server.receiptReply = { reply(200, receipt(status)) }
                f.confirm()
                assertEquals(step, f.session.state.value.review?.step)
                assertFalse(f.session.state.value.review?.canConfirm == true)
                assertFalse(f.session.state.value.announcement.contains("Your site is live"))
                assertEquals(1, f.server.posts.size)
            }
        }
    }

    @Test
    fun rejectedOrMismatchedAcknowledgmentNeedsTheOriginalRequestsReadback() = runTest {
        for (ack in listOf(acknowledgment("completed", accepted = false), acknowledgment("completed", id = ChatFixtures.SITE_TURN))) {
            Fixture(backgroundScope).use { f ->
                f.loadAndOpen()
                f.server.confirmReply = { reply(200, ack) }
                f.server.receiptReply = { reply(200, receipt("uncertain")) }
                f.confirm()
                assertEquals(listOf(REQUEST), f.server.receiptIds)
                assertEquals(ReviewStep.Uncertain, f.session.state.value.review?.step)
                assertEquals(1, f.server.posts.size)
            }
        }
    }

    @Test
    fun reopeningAnInFlightConfirmationCannotPostAgain() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.server.confirmReply = { entered.complete(Unit); release.await(); throw IOException("Synthetic lost reply") }
            f.server.receiptReply = { reply(200, receipt("pending")) }
            f.session.confirmReview()
            entered.await()
            assertEquals(1, f.server.posts.size)
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            assertEquals(ReviewStep.Confirming, f.session.state.value.review?.step)
            f.session.confirmReview()
            release.complete(Unit)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Pending, f.session.state.value.review?.step)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun concurrentRefreshAndReopenShareOneReadback() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            f.server.receiptReply = { throw IOException("Synthetic read outage") }
            f.confirm()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.server.receiptReply = { entered.complete(Unit); release.await(); reply(200, receipt("completed")) }
            f.session.refreshNow()
            entered.await()
            f.session.dismissReview()
            f.session.openReview(ChatFixtures.SITE_TURN)
            f.session.checkReview(REQUEST)
            f.session.confirmReview()
            runCurrent()
            assertEquals(listOf(REQUEST, REQUEST), f.server.receiptIds)
            release.complete(Unit)
            f.session.awaitIdle()
            assertEquals(ReviewStep.Done, f.session.state.value.review?.step)
            assertEquals(1, f.server.posts.size)
        }
    }

    @Test
    fun resetRejectsALateNonCancellableReadback() = runTest {
        Fixture(backgroundScope).use { f ->
            f.loadAndOpen()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            f.server.receiptReply = {
                entered.complete(Unit)
                withContext(NonCancellable) {
                    release.await()
                    reply(200, receipt("completed")).also { returned.complete(Unit) }
                }
            }
            f.session.confirmReview()
            entered.await()
            assertEquals(listOf(REQUEST), f.server.receiptIds)
            val previousWork = backgroundScope.coroutineContext[Job]!!.children.single()
            f.session.reset()
            release.complete(Unit)
            returned.await()
            previousWork.join()
            assertEquals(Phase.Loading, f.session.state.value.phase)
            assertNull(f.session.state.value.review)
            assertTrue(f.session.state.value.reviewChecks.isEmpty())
            assertEquals("", f.session.state.value.announcement)
            assertEquals(1, f.server.posts.size)
        }
    }

    companion object {
        private const val REQUEST = "66666666-6666-4666-8666-000000000001"
        private const val NEXT_REQUEST = "66666666-6666-4666-8666-000000000002"

        private fun receipt(status: String, id: String = REQUEST): String {
            val envelope = Json.parseToJsonElement(ChatFixtures.body("chat.turn")).jsonObject
            val turn = envelope["turn"]!!.jsonObject
            return JsonObject(envelope + ("turn" to JsonObject(turn + mapOf("id" to JsonPrimitive(id), "status" to JsonPrimitive(status))))).toString()
        }

        private fun acknowledgment(status: String, accepted: Boolean = true, id: String = REQUEST): String {
            val ack = Json.parseToJsonElement(ChatFixtures.body("review.confirm")).jsonObject
            return JsonObject(ack + mapOf("requestId" to JsonPrimitive(id), "turnStatus" to JsonPrimitive(status), "accepted" to JsonPrimitive(accepted))).toString()
        }

        private fun page(revision: Int, empty: Boolean = false, applied: Boolean = false, missingReview: Boolean = false): String {
            val page = Json.parseToJsonElement(ChatFixtures.body("chat.read")).jsonObject
            val items = if (empty) emptyList() else page["items"]!!.jsonArray.map { item ->
                val turn = item.jsonObject
                if (missingReview && turn["id"]!!.jsonPrimitive.content == ChatFixtures.SITE_TURN) {
                    JsonObject(turn + ("review" to JsonNull))
                } else if (applied && turn["id"]!!.jsonPrimitive.content == ChatFixtures.SITE_TURN) {
                    val review = turn["review"]!!.jsonObject
                    JsonObject(turn + ("review" to JsonObject(review + mapOf(
                        "state" to JsonPrimitive("applied"), "canApprove" to JsonPrimitive(false), "control" to JsonNull,
                    ))))
                } else item
            }
            return JsonObject(page + mapOf("revision" to JsonPrimitive(revision), "items" to JsonArray(items))).toString()
        }
    }
}
