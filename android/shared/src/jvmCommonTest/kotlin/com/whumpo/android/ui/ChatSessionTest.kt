package com.whumpo.android.ui

import com.whumpo.android.ui.chat.ChatSession
import com.whumpo.android.ui.chat.ChatState
import com.whumpo.android.ui.chat.HapticKind
import com.whumpo.android.ui.chat.Phase
import com.whumpo.android.ui.chat.ReauthHook
import com.whumpo.android.ui.chat.ReviewStep
import com.whumpo.android.ui.chat.SendStatus
import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.MobileApiClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private typealias Scripted = suspend MockRequestHandleScope.() -> HttpResponseData

/** ChatSession against the WP4 client on a MockEngine that serves the frozen fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {
    /** One scripted server: records every request and answers from the lambdas the test sets. */
    private class Server {
        val posts = mutableListOf<Pair<String, JsonObject>>()
        var gets = 0
        var chatBody = ChatFixtures.body("chat.read")
        var chatBodies = ArrayDeque<String>()
        var onSend: () -> Unit = {}
        var sendReply: Scripted = { reply(200, ChatFixtures.body("chat.send")) }
        var confirmReplies = ArrayDeque<Scripted>()
        var turnReply: Scripted = { reply(ChatFixtures.error("not_found")) }

        suspend fun handle(scope: MockRequestHandleScope, request: HttpRequestData): HttpResponseData {
            val path = request.url.encodedPath.removePrefix("/api/mobile/v1")
            return with(scope) {
                when {
                    request.method == HttpMethod.Get && path == "/chat" -> {
                        gets += 1
                        reply(200, chatBodies.removeFirstOrNull() ?: chatBody)
                    }
                    request.method == HttpMethod.Get && path.startsWith("/chat/turns/") -> turnReply(scope)
                    request.method == HttpMethod.Post && path == "/chat/messages" -> {
                        posts += path to body(request)
                        onSend()
                        sendReply(scope)
                    }
                    request.method == HttpMethod.Post && path == "/reviews/confirm" -> {
                        posts += path to body(request)
                        confirmReplies.removeFirst()(scope)
                    }
                    else -> reply(500, "{}")
                }
            }
        }

        private suspend fun body(request: HttpRequestData): JsonObject =
            Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

        fun count(path: String) = posts.count { it.first == path }
    }

    private fun TestScope.session(
        server: Server,
        info: ClientInfo = ChatFixtures.client("current"),
        reauth: ReauthHook = ReauthHook { true },
        committed: () -> Unit = {},
    ): ChatSession {
        val ids = ArrayDeque(ChatFixtures.SEND_IDS + "66666666-6666-4666-8666-000000000001")
        val client: MobileApiClient = testClient(info) { request -> server.handle(this, request) }
        val scope: CoroutineScope = backgroundScope
        return ChatSession(scope, client, info, reauth, onSendCommitted = committed, newId = { ids.removeFirst() })
    }

    private suspend fun TestScope.loaded(server: Server, info: ClientInfo = ChatFixtures.client("current"), reauth: ReauthHook = ReauthHook { true }, committed: () -> Unit = {}): ChatSession {
        val s = session(server, info, reauth, committed)
        s.load()
        s.awaitIdle()
        return s
    }

    @Test
    fun loadsTheConversationFromTheFixture() = runTest {
        val s = loaded(Server())
        val state = s.state.value
        assertEquals(Phase.Ready, state.phase)
        assertEquals(3, state.turns.size)
        assertEquals(4, state.revision)
        assertNotNull(state.turns[0].interaction)
        assertFalse(state.typing)
    }

    @Test
    fun duplicateTapSendsOnce() = runTest {
        val server = Server()
        var committed = 0
        val s = loaded(server, committed = { committed += 1 })
        s.send("Add a ceramic coating service for $300.")
        s.send("Add a ceramic coating service for $300.")
        s.awaitIdle()
        assertEquals(1, server.count("/chat/messages"))
        val sent = server.posts.single().second
        assertEquals(ChatFixtures.SEND_IDS[0], sent["requestId"]!!.jsonPrimitive.content)
        assertEquals(4, sent["expectedRevision"]!!.jsonPrimitive.int)
        assertEquals(1, committed)
        assertNull(s.state.value.outbound)
    }

    @Test
    fun lostReplyIsReadBackAndNeverResentUntilTheOwnerAsks() = runTest {
        val server = Server()
        var committed = 0
        val s = loaded(server, committed = { committed += 1 })
        server.sendReply = { throw IOException("connection reset after the request left") }

        s.send("Add a booking page")
        s.awaitIdle()
        // The reply was lost; a read by request ID says nothing was committed. One POST so far.
        assertEquals(1, server.count("/chat/messages"))
        assertEquals(SendStatus.NotCommitted, s.state.value.outbound?.status)
        assertEquals(0, committed)

        // The owner presses "Send again": same ID, same text, same revision.
        server.sendReply = { reply(200, ChatFixtures.body("chat.send")) }
        s.sendAgain()
        s.awaitIdle()
        assertEquals(2, server.count("/chat/messages"))
        assertEquals(server.posts[0].second, server.posts[1].second)
        assertEquals(1, committed)
        assertNull(s.state.value.outbound)
    }

    @Test
    fun lostReplyThatDidCommitIsFoundByReadbackWithoutASecondSend() = runTest {
        val server = Server()
        var committed = 0
        val s = loaded(server, committed = { committed += 1 })
        server.sendReply = { throw IOException("reply lost") }
        server.turnReply = { reply(200, ChatFixtures.body("chat.turn")) }

        s.send("Publish it.")
        s.awaitIdle()
        assertEquals(1, server.count("/chat/messages"))
        assertNull(s.state.value.outbound)
        assertEquals(1, committed)
    }

    @Test
    fun readbackThatAlsoFailsStaysUnknownAndNeverSends() = runTest {
        val server = Server()
        val s = loaded(server)
        server.sendReply = { throw IOException("offline") }
        server.turnReply = { throw IOException("still offline") }

        s.send("Hello")
        s.awaitIdle()
        assertEquals(SendStatus.Unknown, s.state.value.outbound?.status)
        assertTrue(s.state.value.offline)
        s.checkSend()
        s.awaitIdle()
        assertEquals(1, server.count("/chat/messages"))
    }

    @Test
    fun staleRevisionKeepsTheDraftAndRefreshes() = runTest {
        val server = Server()
        var committed = 0
        val s = loaded(server, committed = { committed += 1 })
        val getsBefore = server.gets
        server.sendReply = { reply(ChatFixtures.error("stale_revision")) }

        s.send("Hello")
        s.awaitIdle()
        assertEquals(0, committed)
        assertNull(s.state.value.outbound)
        assertTrue(s.state.value.notice.orEmpty().contains("conversation changed"))
        assertEquals(HapticKind.Reject, s.state.value.haptic?.kind)
        assertEquals(getsBefore + 1, server.gets)
    }

    @Test
    fun aPendingReplyShowsTypingAndAnnouncesWhenItArrives() = runTest {
        val server = Server()
        val done = ChatFixtures.body("chat.read")
        val last = done.lastIndexOf("\"status\":\"completed\"")
        val pending = done.substring(0, last) + "\"status\":\"pending\"" + done.substring(last + "\"status\":\"completed\"".length)
        // The first read finds the reply pending; the next poll finds it complete.
        server.chatBodies = ArrayDeque(listOf(pending.replace("\"assistantText\":\"Review the publication below.\"", "\"assistantText\":null"), done))
        val s = session(server)
        val seen = mutableListOf<ChatState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.state.collect { seen += it } }
        s.load()
        s.awaitIdle()
        assertTrue(seen.any { it.typing && it.replyPending })
        assertFalse(s.state.value.typing)
        assertTrue(s.state.value.announcement.startsWith("Whumpo replied:"))
    }

    // ---- Review and confirm ----------------------------------------------------------------------------------------

    private fun Server.confirmWith(vararg replies: Scripted) {
        replies.forEach { confirmReplies.addLast(it) }
    }

    private val ok: Scripted =
        { reply(200, ChatFixtures.body("review.confirm")) }

    @Test
    fun confirmUsesTheCurrentConversationRevisionEvenWhenTheReviewTurnIsOlder() = runTest {
        // Found on the emulator against the real Worker: after any later turn the review turn's own revision is stale.
        val server = Server().apply {
            chatBody = Json.parseToJsonElement(chatBody).jsonObject.let { JsonObject(it + ("revision" to JsonPrimitive(9))) }.toString()
            confirmWith(ok)
        }
        val s = loaded(server)
        s.openReview(ChatFixtures.SITE_TURN)
        s.confirmReview()
        s.awaitIdle()
        assertEquals(9, server.posts.single().second["expectedRevision"]!!.jsonPrimitive.int)
    }

    @Test
    fun confirmShowsAReceiptAndSendsTheSheetsRevision() = runTest {
        val server = Server().apply { confirmWith(ok) }
        val s = loaded(server)
        s.openReview(ChatFixtures.SITE_TURN)
        assertEquals(ReviewStep.Ready, s.state.value.review?.step)

        s.confirmReview()
        s.confirmReview() // a repeat tap while the first is running is ignored
        s.awaitIdle()
        assertEquals(1, server.count("/reviews/confirm"))
        val body = server.posts.single().second
        assertEquals(4, body["expectedRevision"]!!.jsonPrimitive.int)
        assertEquals("site", body["control"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        val state = s.state.value
        assertEquals(ReviewStep.Done, state.review?.step)
        assertEquals("Published. Your site is live.", state.review?.message)
        assertEquals(HapticKind.Confirm, state.haptic?.kind)
        assertEquals("Published. Your site is live.", state.announcement)
    }

    @Test
    fun proofRequiredGoesThroughTheReauthHookThenRetriesWithTheSameRequestId() = runTest {
        var reauths = 0
        val server = Server().apply { confirmWith({ reply(ChatFixtures.error("proof_required_fresh")) }, ok) }
        val s = loaded(server, reauth = { reauths += 1; true })
        s.openReview(ChatFixtures.SITE_TURN)
        s.confirmReview()
        s.awaitIdle()
        assertEquals(1, reauths)
        assertEquals(2, server.count("/reviews/confirm"))
        assertEquals(server.posts[0].second, server.posts[1].second)
        assertEquals(ReviewStep.Done, s.state.value.review?.step)
    }

    @Test
    fun aFailedPasskeyChangesNothingAndOffersTryAgain() = runTest {
        val server = Server().apply { confirmWith({ reply(ChatFixtures.error("proof_required_fresh")) }, ok) }
        val s = loaded(server, reauth = { false })
        s.openReview(ChatFixtures.SITE_TURN)
        s.confirmReview()
        s.awaitIdle()
        assertEquals(1, server.count("/reviews/confirm"))
        val flow = s.state.value.review
        assertEquals(ReviewStep.Failed, flow?.step)
        assertTrue(flow?.message.orEmpty().contains("Nothing was changed"))
        assertTrue(flow?.canConfirm == true)
        assertEquals(HapticKind.Reject, s.state.value.haptic?.kind)
    }

    @Test
    fun aStaleReviewDisablesConfirm() = runTest {
        val server = Server().apply { confirmWith({ reply(ChatFixtures.error("stale_revision")) }) }
        val s = loaded(server)
        s.openReview(ChatFixtures.SITE_TURN)
        s.confirmReview()
        s.awaitIdle()
        val flow = s.state.value.review
        assertEquals(ReviewStep.NoLongerCurrent, flow?.step)
        assertFalse(flow?.canConfirm == true)
        s.confirmReview()
        s.awaitIdle()
        assertEquals(1, server.count("/reviews/confirm"))
    }

    @Test
    fun anUnknownControlIsUpdateRequiredAndNeverSent() = runTest {
        val server = Server()
        val s = loaded(server, info = ChatFixtures.client("withoutSiteControls"))
        s.openReview(ChatFixtures.SITE_TURN)
        assertEquals(ReviewStep.UpdateRequired, s.state.value.review?.step)
        s.confirmReview()
        s.awaitIdle()
        assertEquals(0, server.count("/reviews/confirm"))
    }

    @Test
    fun aLostConfirmReplyIsCheckedByReadingAndNeverResent() = runTest {
        val server = Server().apply { confirmWith({ throw IOException("reply lost") }) }
        val s = loaded(server)
        s.openReview(ChatFixtures.SITE_TURN)
        s.confirmReview()
        s.awaitIdle()
        // The conversation still shows the review as pending, so nothing is confirmed; one POST only.
        assertEquals(1, server.count("/reviews/confirm"))
        val flow = s.state.value.review
        assertEquals(ReviewStep.Failed, flow?.step)
        assertTrue(flow?.message.orEmpty().contains("Didn't hear back"))
    }

    @Test
    fun resetForgetsEverything() = runTest {
        val s = loaded(Server())
        s.reset()
        assertEquals(Phase.Loading, s.state.value.phase)
        assertTrue(s.state.value.turns.isEmpty())
    }
}
