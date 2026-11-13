package com.whumpo.android.ui

import com.whumpo.android.ui.chat.ChatViewModel
import com.whumpo.android.ui.chat.ReauthHook
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormPane
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture
import com.whumpo.android.ui.chat.forms.SendOutcome
import com.whumpo.android.ui.chat.forms.edit
import com.whumpo.core.account.AccountScope
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.data.AccountRepository
import com.whumpo.core.data.ChatDraftRepository
import io.ktor.http.HttpMethod
import io.ktor.client.engine.mock.MockEngine
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The identity source changes before the existing account collector gets its next event. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatFormAccountTest {
    private val ownerA = AccountScope.of("acct_fixture_a")
    private val ownerB = AccountScope.of("acct_fixture_b")

    private class DelayedAccount(initial: AccountScope) : AccountRepository {
        var current: AccountScope? = initial
        val deliverChange = CompletableDeferred<Unit>()
        private val updates = MutableSharedFlow<AccountScope?>(extraBufferCapacity = 1)
        override val activeAccount = flow {
            emit(current)
            updates.collect { owner -> deliverChange.await(); emit(owner) }
        }
        fun change(owner: AccountScope?) { current = owner; check(updates.tryEmit(owner)) }
        override suspend fun signOut() = change(null)
    }

    private fun read(state: String?): JsonObject {
        val original = ProposedFormsFixture.read(if (state == null) "emptyDraft" else "submittedWithContinuation")
        val fields = original["envelope"]!!.jsonObject["definition"]!!.jsonObject["fields"]!!.jsonArray
        return if (state == null) original.edit("envelope.definition.fields", JsonArray(listOf(fields.first())))
        else original.edit("submission.continuation.state", JsonPrimitive(state))
    }

    private fun checkBlocked(state: String? = null, recovery: SendOutcome? = null, action: (FormActions) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val account = DelayedAccount(ownerA)
            val drafts = object : ChatDraftRepository {
                override suspend fun loadDraft(owner: AccountScope) = ""
                override suspend fun saveDraft(owner: AccountScope, text: String) = true
            }
            val snapshot = read(state)
            val interactionId = snapshot["envelope"]!!.jsonObject["id"]!!.toString().trim('"')
            val calls = CopyOnWriteArrayList<String>()
            val engine = MockEngine.create {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    if (path.endsWith("/chat")) reply(200, ChatFixtures.body("chat.read"))
                    else {
                        calls += "${request.method.value} $path"
                        if (request.method == HttpMethod.Post && path.endsWith("/interactions/read")) reply(200, snapshot.toString())
                        else if (path.endsWith("/interactions/receipt")) reply(404, """{"version":1,"error":{"code":"not_found","message":"Not found."}}""")
                        else throw IOException("Synthetic lost reply")
                    }
                }
            }
            MobileApiClient(engine, ChatFixtures.BASE, ChatFixtures.client("current"), TestSessions()).use { client ->
                val vm = ChatViewModel(drafts, account, client, ChatFixtures.client("current"), ReauthHook { false })
                advanceUntilIdle()
                vm.onOpenForm(interactionId)
                advanceUntilIdle()
                assertTrue(vm.screen.value.form is FormPane.Shown)
                val retainedActions = vm.formActions
                if (recovery != null) {
                    retainedActions.onAnswer("short-text", Answer.Text("A's retained answer"))
                    retainedActions.onSaveDraft()
                    advanceUntilIdle()
                    if (recovery == SendOutcome.NotReceived) {
                        retainedActions.onCheckAgain()
                        advanceUntilIdle()
                    }
                    assertEquals(recovery, (vm.screen.value.form as FormPane.Shown).screen.send.attempt?.outcome)
                }
                val before = calls.toList()
                account.change(ownerB)
                // The pane still belongs to A while the identity source already reports B.
                assertEquals(ownerA, vm.state.value.owner)
                action(retainedActions)
                advanceUntilIdle()
                assertEquals("No retained A request may be dispatched after the identity changed", before, calls.toList())

                account.deliverChange.complete(Unit)
                advanceUntilIdle()
                vm.onOpenForm(interactionId)
                advanceUntilIdle()
                if (state == null) {
                    retainedActions.onAnswer("short-text", Answer.Text("A's stale callback"))
                    advanceUntilIdle()
                    assertEquals(emptyMap<String, Answer>(), (vm.screen.value.form as FormPane.Shown).screen.answers)
                    vm.formActions.onAnswer("short-text", Answer.Text("B's answer"))
                    advanceUntilIdle()
                    assertEquals(Answer.Text("B's answer"), (vm.screen.value.form as FormPane.Shown).screen.answers["short-text"])
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun accountChangeBlocksRetainedDraftSave() = checkBlocked {
        it.onAnswer("short-text", Answer.Text("A's answer"))
        it.onSaveDraft()
    }

    @Test fun accountChangeBlocksRetainedSubmit() = checkBlocked {
        it.onAnswer("short-text", Answer.Text("A's answer"))
        it.onSubmit()
    }

    @Test fun accountChangeBlocksRetainedCancel() = checkBlocked { it.onCancel() }

    @Test fun accountChangeBlocksRetainedReceiptCheck() = checkBlocked(recovery = SendOutcome.Unknown) { it.onCheckAgain() }

    @Test fun accountChangeBlocksRetainedFrozenResend() = checkBlocked(recovery = SendOutcome.NotReceived) { it.onSendAgain() }

    @Test fun accountChangeBlocksRetainedReadAndRecoveryCallbacks() = checkBlocked {
        it.onReload()
        it.onCheckAgain()
        it.onSendAgain()
        it.onLoadMoreResources("project")
    }

    @Test fun accountChangeBlocksRetainedContinue() = checkBlocked("not-started") { it.onContinue() }
    @Test fun accountChangeBlocksRetainedStop() = checkBlocked("not-started") { it.onStopContinuation() }
    @Test fun accountChangeBlocksRetainedReconcile() = checkBlocked("unknown") { it.onCheckContinuation() }
    @Test fun accountChangeBlocksRetainedPendingStatusRead() = checkBlocked("pending") { it.onCheckContinuation() }
}
