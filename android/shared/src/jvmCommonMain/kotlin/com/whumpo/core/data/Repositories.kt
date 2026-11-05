package com.whumpo.core.data

import com.whumpo.core.account.AccountScope
import kotlinx.coroutines.flow.Flow

/** The unsent Chat draft of one account. Sending arrives with WH-084. */
interface ChatDraftRepository {
    /** [owner]'s stored draft (possibly empty), or null when [owner] is not signed in. */
    suspend fun loadDraft(owner: AccountScope): String?

    /** Stores [text] for [owner]; returns false when [owner] is no longer signed in. */
    suspend fun saveDraft(owner: AccountScope, text: String): Boolean
}

/** Account lifecycle the shell can drive locally. Native sign-in itself is WH-083. */
interface AccountRepository {
    /** The signed-in account, or null. Emits on every sign-in, sign-out or account change. */
    val activeAccount: Flow<AccountScope?>

    suspend fun signOut()
}
