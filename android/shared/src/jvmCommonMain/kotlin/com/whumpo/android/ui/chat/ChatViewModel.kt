package com.whumpo.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whumpo.android.ui.chat.forms.ClientFormApi
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormController
import com.whumpo.android.ui.chat.forms.JavaZoneTime
import com.whumpo.android.ui.chat.forms.ProposedFormWire
import com.whumpo.core.account.AccountScope
import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.data.AccountRepository
import com.whumpo.core.data.ChatDraftRepository
import com.whumpo.core.data.StoredChatDraftRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Chat for the signed-in account. The draft belongs to the account: it is loaded from and saved to encrypted
 * account-scoped storage (so it survives process death and rotation), and it disappears from the screen as soon as
 * the account signs out or changes. The conversation itself lives in [ChatSession]; the draft is cleared only once a
 * send is committed, so a lost reply or a stale revision never costs the owner their text.
 */
class ChatViewModel(
    private val drafts: ChatDraftRepository,
    private val account: AccountRepository,
    client: MobileApiClient,
    clientInfo: ClientInfo,
    reauth: ReauthHook,
) : ViewModel(), ChatActions {
    data class UiState(
        val loading: Boolean = true,
        val owner: AccountScope? = null,
        val draft: String = "",
    ) {
        val composerEnabled: Boolean get() = owner != null && !loading
    }

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()

    private val session = ChatSession(viewModelScope, client, clientInfo, reauth, onSendCommitted = { clearDraft() })

    // The open form and its answers are memory-only and dropped on sign-out or account change.
    private val forms = FormController(
        ClientFormApi(client), ProposedFormWire(JavaZoneTime), viewModelScope,
        currentChatRevision = { session.state.value.takeIf { it.phase == Phase.Ready && !it.offline }?.revision },
        onResultRequested = { session.load() },
    )

    // Capture the owner when the screen obtains its callbacks. A callback retained by an old
    // composition cannot send its answers with the new account's token or edit the new account's form.
    val formActions: FormActions
        get() {
            val owner = mutableState.value.owner
            return object : FormActions {
                private fun current(action: () -> Unit) = withCurrentSession(owner, action)
                override fun onAnswer(fieldId: String, answer: Answer?) = current { forms.onAnswer(fieldId, answer) }
                override fun onSubmit() = current { forms.onSubmit() }
                override fun onSaveDraft() = current { forms.onSaveDraft() }
                override fun onCheckAgain() = current { forms.onCheckAgain() }
                override fun onSendAgain() = current { forms.onSendAgain() }
                override fun onReload() = current { forms.onReload() }
                override fun onLoadMoreResources(fieldId: String) = current { forms.onLoadMoreResources(fieldId) }
                override fun onUseNewerAnswers() = current { forms.onUseNewerAnswers() }
                override fun onKeepMyAnswers() = current { forms.onKeepMyAnswers() }
                override fun onClose() = current { forms.onClose() }
                override fun onUpdate() = current { forms.onUpdate() }
                override fun onCancel() = current { forms.onCancel() }
                override fun onFieldBlur(fieldId: String) = current { forms.onFieldBlur(fieldId) }
                override fun onContinue() = current { forms.onContinue() }
                override fun onCheckContinuation() = current { forms.onCheckContinuation() }
                override fun onStopContinuation() = current { forms.onStopContinuation() }
                override fun onOpenContinuationResult() = current { forms.onOpenContinuationResult() }
            }
        }

    /** What the screen draws: the draft state joined with the conversation and the open form. */
    val screen: StateFlow<ChatScreenState> = combine(state, session.state, forms.pane, forms.cards) { draft, chat, form, cards ->
        ChatScreenState(signedIn = draft.owner != null, draft = draft.draft, composerEnabled = draft.composerEnabled, chat = chat, form = form, formCards = cards)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatScreenState())

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            account.activeAccount.collectLatest { owner ->
                saveJob?.cancel()
                session.reset()
                forms.reset()
                if (owner == null) {
                    mutableState.value = UiState(loading = false)
                    return@collectLatest
                }
                mutableState.value = UiState(loading = true, owner = owner)
                val draft = drafts.loadDraft(owner)
                mutableState.value = if (draft == null) {
                    UiState(loading = false)
                } else {
                    UiState(loading = false, owner = owner, draft = draft)
                }
                if (draft != null) session.load()
            }
        }
    }

    override fun onDraftChanged(text: String) {
        val current = mutableState.value
        val owner = current.owner ?: return
        if (current.loading) return
        val bounded = text.take(StoredChatDraftRepository.MAX_DRAFT_CHARS)
        mutableState.value = current.copy(draft = bounded)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            drafts.saveDraft(owner, bounded)
        }
    }

    private fun clearDraft() {
        val current = mutableState.value
        val owner = current.owner ?: return
        mutableState.value = current.copy(draft = "")
        saveJob?.cancel()
        saveJob = viewModelScope.launch { drafts.saveDraft(owner, "") }
    }

    /**
     * "Ask about this project": puts [text] in the draft and never sends it. An unsent draft is kept and the text goes after it.
     * Does nothing while signed out or before the stored draft has loaded.
     */
    fun prefill(text: String) {
        val current = mutableState.value
        if (current.owner == null || current.loading) return
        onDraftChanged(if (current.draft.isBlank()) text else current.draft.trimEnd() + "\n\n" + text)
    }

    override fun onSend() = session.send(mutableState.value.draft)

    override fun onRetry() = session.load()

    override fun onCheckSend() = session.checkSend()

    override fun onSendAgain() = session.sendAgain()

    override fun onDiscardUnsent() = session.discardUnsent()

    override fun onOpenReview(turnId: String) {
        withCurrentSession { session.openReview(turnId) }
    }

    override fun onConfirmReview() = session.confirmReview()

    override fun onCheckReview(requestId: String) {
        withCurrentSession { session.checkReview(requestId) }
    }

    // A stale UI callback can arrive before the account collector has reset the session.
    private fun withCurrentSession(owner: AccountScope? = mutableState.value.owner, action: () -> Unit) {
        if (owner == null) return
        viewModelScope.launch {
            if (account.activeAccount.firstOrNull() == owner && mutableState.value.owner == owner) action()
        }
    }

    override fun onDismissReview() = session.dismissReview()

    override fun onOpenForm(interactionId: String) {
        withCurrentSession { forms.open(interactionId) }
    }

    internal suspend fun awaitIdle() = session.awaitIdle()

    companion object {
        const val SAVE_DEBOUNCE_MS: Long = 250
    }
}
