package com.whumpo.android.ui

import com.whumpo.android.ui.chat.ChatViewModel
import com.whumpo.android.ui.chat.Phase
import com.whumpo.android.ui.chat.ReauthHook
import com.whumpo.android.ui.chat.ReviewStep
import com.whumpo.core.account.AccountScope
import io.ktor.http.HttpMethod
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val ownerA = AccountScope.of("acct_fixture_a")
    private val ownerB = AccountScope.of("acct_fixture_b")
    private lateinit var main: TestDispatcher

    @Before
    fun setUp() {
        main = StandardTestDispatcher()
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun signedOutComposerIsHiddenAndIgnoresInput() = runTest(main) {
        val account = FakeAccountRepository()
        val drafts = FakeChatDraftRepository(account)
        val vm = ChatViewModel(drafts, account, signedOutClient(), ChatFixtures.client("current"), ReauthHook { false })
        advanceUntilIdle()

        assertNull(vm.state.value.owner)
        assertFalse(vm.state.value.composerEnabled)
        vm.onDraftChanged("typed while signed out")
        advanceUntilIdle()
        assertEquals("", vm.state.value.draft)
        assertEquals(0, drafts.saves)
    }

    @Test
    fun restoresAndDebouncesTheAccountDraft() = runTest(main) {
        val account = FakeAccountRepository(ownerA)
        val drafts = FakeChatDraftRepository(account).apply { stored[ownerA] = "saved earlier" }
        val vm = ChatViewModel(drafts, account, signedOutClient(), ChatFixtures.client("current"), ReauthHook { false })
        advanceUntilIdle()
        assertEquals("saved earlier", vm.state.value.draft)
        assertTrue(vm.state.value.composerEnabled)

        vm.onDraftChanged("a")
        vm.onDraftChanged("ab")
        vm.onDraftChanged("abc")
        advanceTimeBy(ChatViewModel.SAVE_DEBOUNCE_MS + 1)
        assertEquals(1, drafts.saves)
        assertEquals("abc", drafts.stored[ownerA])
    }

    @Test
    fun signOutClearsTheVisibleDraftAndDropsPendingSaves() = runTest(main) {
        val account = FakeAccountRepository(ownerA)
        val drafts = FakeChatDraftRepository(account)
        val vm = ChatViewModel(drafts, account, signedOutClient(), ChatFixtures.client("current"), ReauthHook { false })
        advanceUntilIdle()

        vm.onDraftChanged("not yet saved")
        account.signOut()
        advanceUntilIdle()
        assertEquals("", vm.state.value.draft)
        assertNull(vm.state.value.owner)
        assertEquals(0, drafts.saves)
    }

    @Test
    fun accountChangeNeverCarriesADraftAcross() = runTest(main) {
        val account = FakeAccountRepository(ownerA)
        val drafts = FakeChatDraftRepository(account)
        val vm = ChatViewModel(drafts, account, signedOutClient(), ChatFixtures.client("current"), ReauthHook { false })
        advanceUntilIdle()

        vm.onDraftChanged("owner A only")
        account.activeAccount.value = ownerB
        advanceUntilIdle()
        assertEquals(ownerB, vm.state.value.owner)
        assertEquals("", vm.state.value.draft)
        assertNull(drafts.stored[ownerB])
        assertNull(drafts.stored[ownerA])
    }

    private fun signedOutClient() = testClient { reply(ChatFixtures.error("proof_required_signin")) }

    /** A server that serves the fixture conversation and answers a send with [send]. */
    private fun chatClient(send: Pair<Int, String>) = testClient { request ->
        when {
            request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/chat") -> reply(200, ChatFixtures.body("chat.read"))
            request.method == HttpMethod.Post -> reply(send)
            else -> reply(500, "{}")
        }
    }

    private suspend fun TestScope.settle(vm: ChatViewModel) {
        advanceUntilIdle()
        vm.awaitIdle()
        advanceUntilIdle()
    }

    private fun viewModelWith(client: com.whumpo.core.api.MobileApiClient, draft: String): Triple<ChatViewModel, FakeChatDraftRepository, FakeAccountRepository> {
        val account = FakeAccountRepository(ownerA)
        val drafts = FakeChatDraftRepository(account).apply { stored[ownerA] = draft }
        return Triple(ChatViewModel(drafts, account, client, ChatFixtures.client("current"), ReauthHook { false }), drafts, account)
    }

    @Test
    fun theDraftIsClearedOnlyOnceTheSendIsCommitted() = runTest(main) {
        val (vm, drafts, _) = viewModelWith(chatClient(200 to ChatFixtures.body("chat.send")), "Publish it.")
        settle(vm)
        assertEquals(Phase.Ready, vm.screen.value.chat.phase)
        assertEquals("Publish it.", vm.screen.value.draft)

        vm.onSend()
        settle(vm)
        assertEquals("", vm.screen.value.draft)
        assertEquals("", drafts.stored[ownerA])
    }

    @Test
    fun aRejectedSendKeepsTheDraft() = runTest(main) {
        val (vm, drafts, _) = viewModelWith(chatClient(ChatFixtures.error("stale_revision")), "Publish it.")
        settle(vm)

        vm.onSend()
        settle(vm)
        assertEquals("Publish it.", vm.screen.value.draft)
        assertEquals("Publish it.", drafts.stored[ownerA])
        assertTrue(vm.screen.value.chat.notice != null)
    }

    @Test
    fun signOutClearsTheConversationToo() = runTest(main) {
        val (vm, _, account) = viewModelWith(chatClient(200 to ChatFixtures.body("chat.send")), "")
        settle(vm)
        assertEquals(3, vm.screen.value.chat.turns.size)

        account.signOut()
        settle(vm)
        assertTrue(vm.screen.value.chat.turns.isEmpty())
        assertFalse(vm.screen.value.signedIn)
    }

    @Test
    fun prefillFillsTheDraftWithoutSendingAndKeepsAnUnsentOne() = runTest(main) {
        val account = FakeAccountRepository(ownerA)
        val drafts = FakeChatDraftRepository(account)
        val vm = ChatViewModel(drafts, account, signedOutClient(), ChatFixtures.client("current"), ReauthHook { false })
        advanceUntilIdle()

        vm.prefill("About my project \"Cafe\". ")
        assertEquals("About my project \"Cafe\". ", vm.state.value.draft)
        vm.prefill("Second.")
        assertEquals("About my project \"Cafe\".\n\nSecond.", vm.state.value.draft)
        advanceTimeBy(ChatViewModel.SAVE_DEBOUNCE_MS + 1)
        assertEquals(vm.state.value.draft, drafts.stored[ownerA])
    }

    @Test
    fun aReviewCheckActionReadsTheOriginalRequestAndSettlesWithoutAnotherMutation() = runTest(main) {
        val server = ReviewCheckServer()
        server.client().use { client ->
            val (vm, _, _) = viewModelWith(client, "")
            settle(vm)
            vm.onOpenReview(ChatFixtures.SITE_TURN)
            settle(vm)
            val requestId = vm.screen.value.chat.review!!.requestId
            vm.onConfirmReview()
            settle(vm)
            assertEquals(ReviewStep.Uncertain, vm.screen.value.chat.review?.step)
            assertEquals(requestId, vm.screen.value.chat.reviewChecks.single().requestId)
            server.complete = true
            vm.onCheckReview(requestId)
            settle(vm)
            assertEquals(ReviewStep.Done, vm.screen.value.chat.review?.step)
            assertTrue(vm.screen.value.chat.reviewChecks.isEmpty())
            assertEquals(listOf(requestId, requestId), server.reads)
            assertEquals(1, server.posts.size)
        }
    }

    @Test
    fun accountChangeAndSignOutInvalidateRetainedReviewCheckCallbacks() = runTest(main) {
        val server = ReviewCheckServer()
        server.client().use { client ->
            val (vm, _, account) = viewModelWith(client, "")
            settle(vm)
            vm.onOpenReview(ChatFixtures.SITE_TURN)
            settle(vm)
            val requestId = vm.screen.value.chat.review!!.requestId
            vm.onConfirmReview()
            settle(vm)
            assertEquals(requestId, vm.screen.value.chat.reviewChecks.single().requestId)
            account.activeAccount.value = ownerB
            vm.onCheckReview(requestId)
            vm.onOpenReview(ChatFixtures.SITE_TURN)
            settle(vm)
            vm.onCheckReview(requestId)
            settle(vm)
            assertTrue(vm.screen.value.chat.reviewChecks.isEmpty())
            assertNull(vm.screen.value.chat.review)
            assertEquals(listOf(requestId), server.reads)
            account.signOut()
            settle(vm)
            vm.onCheckReview(requestId)
            settle(vm)
            assertFalse(vm.screen.value.signedIn)
            assertTrue(vm.screen.value.chat.reviewChecks.isEmpty())
            assertEquals(listOf(requestId), server.reads)
            assertEquals(1, server.posts.size)
        }
    }

    private class ReviewCheckServer {
        val reads = CopyOnWriteArrayList<String>()
        val posts = CopyOnWriteArrayList<String>()
        var complete = false

        fun client() = testClient(ChatFixtures.client("current")) { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path.endsWith("/chat") -> reply(200, ChatFixtures.body("chat.read"))
                request.method == HttpMethod.Post && path.endsWith("/reviews/confirm") -> {
                    posts += path
                    throw IOException("Synthetic lost confirmation reply")
                }
                request.method == HttpMethod.Get && path.contains("/chat/turns/") -> {
                    val id = path.substringAfterLast('/')
                    reads += id
                    if (!complete) throw IOException("Synthetic receipt outage")
                    val envelope = Json.parseToJsonElement(ChatFixtures.body("chat.turn")).jsonObject
                    val turn = envelope["turn"]!!.jsonObject
                    reply(200, JsonObject(envelope + ("turn" to JsonObject(turn + mapOf("id" to JsonPrimitive(id), "status" to JsonPrimitive("completed"))))).toString())
                }
                else -> error("Unexpected synthetic request")
            }
        }
    }

    @Test
    fun prefillIsIgnoredWhileSignedOut() = runTest(main) {
        val account = FakeAccountRepository()
        val vm = ChatViewModel(FakeChatDraftRepository(account), account, signedOutClient(), ChatFixtures.client("current"), ReauthHook { false })
        advanceUntilIdle()
        vm.prefill("hello")
        assertEquals("", vm.state.value.draft)
    }
}
