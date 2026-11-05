package com.whumpo.android.ui

import com.whumpo.core.account.AccountScope
import com.whumpo.core.data.AccountRepository
import com.whumpo.core.data.ChatDraftRepository
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAccountRepository(initial: AccountScope? = null) : AccountRepository {
    override val activeAccount = MutableStateFlow(initial)
    var signOutCalls = 0

    override suspend fun signOut() {
        signOutCalls += 1
        activeAccount.value = null
    }
}

/** In-memory drafts keyed by account, honouring the "only the active owner" rule. */
class FakeChatDraftRepository(private val account: FakeAccountRepository) : ChatDraftRepository {
    val stored = mutableMapOf<AccountScope, String>()
    var saves = 0

    override suspend fun loadDraft(owner: AccountScope): String? =
        if (account.activeAccount.value == owner) stored[owner].orEmpty() else null

    override suspend fun saveDraft(owner: AccountScope, text: String): Boolean {
        if (account.activeAccount.value != owner) return false
        saves += 1
        stored[owner] = text
        return true
    }
}
