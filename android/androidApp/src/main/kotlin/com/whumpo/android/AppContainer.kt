package com.whumpo.android

import android.app.Application
import com.whumpo.android.appearance.PrefsThemeStore
import com.whumpo.android.auth.ActivityTracker
import com.whumpo.android.auth.CredentialManagerPasskeys
import com.whumpo.android.auth.CustomTabsBrowser
import com.whumpo.android.ui.chat.ReauthHook
import com.whumpo.android.ui.common.AppLinks
import com.whumpo.android.ui.photos.AndroidPhotoPreparer
import com.whumpo.android.ui.photos.AndroidPhotoPreviewer
import com.whumpo.android.ui.share.PhotoPreviewer
import com.whumpo.android.ui.photos.PhotoPreparer
import com.whumpo.android.ui.photos.UploadStore
import com.whumpo.core.data.StoredUploadStore
import com.whumpo.android.ui.share.ShareInbox
import com.whumpo.android.ui.shell.DeepLink
import com.whumpo.android.ui.theme.ThemeStore
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.okHttpEngine
import com.whumpo.core.auth.AuthController
import com.whumpo.core.auth.PasskeyAuthenticator
import com.whumpo.core.auth.PasskeyResult
import com.whumpo.core.auth.ReauthResult
import com.whumpo.core.auth.SessionAccountRepository
import com.whumpo.core.auth.SessionManager
import com.whumpo.core.data.AccountRepository
import com.whumpo.core.data.ChatDraftRepository
import com.whumpo.core.data.StoredChatDraftRepository
import com.whumpo.core.environment.WhumpoEnvironment
import com.whumpo.core.push.DeviceNotifications
import com.whumpo.core.push.NoOpPushMessaging
import com.whumpo.core.push.PushMessaging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The newest deep link not yet shown. The shell only exists for a signed-in account, so the link waits behind the sign-in gate. */
class DeepLinkInbox {
    private val link = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = link.asStateFlow()

    fun offer(next: DeepLink) {
        link.value = next
    }

    fun consume(shown: DeepLink) {
        link.compareAndSet(shown, null)
    }
}

/**
 * Delegates to Credential Manager. The only other implementation is a test double installed in-process by instrumented
 * tests (WorkerJourneyTest); nothing outside the app process can set it, and the server verifies every assertion as usual.
 */
class SwitchablePasskeys(private val real: PasskeyAuthenticator) : PasskeyAuthenticator {
    @Volatile var override: PasskeyAuthenticator? = null

    override suspend fun getAssertion(requestJson: String): PasskeyResult = (override ?: real).getAssertion(requestJson)
}

/** Manual dependency graph for the shell; one instance per process. */
class AppContainer(
    val environment: WhumpoEnvironment,
    val accountState: AccountStateStore,
    io: CoroutineDispatcher,
    app: Application,
    scope: CoroutineScope,
) {
    // WP5: the native session, the v1 client and sign-in. WP7 reuses `mobileApi`.
    val sessions = SessionManager(accountState, io)
    val clientInfo: ClientInfo = ClientInfo.current(BuildConfig.VERSION_CODE)
    val mobileApi = MobileApiClient(okHttpEngine(), environment.mobileApiBase, clientInfo, sessions)
    val activities = ActivityTracker()

    /** Custom Tabs: the hand-off pages and previews. It opens the browser's own session and never carries the bearer. */
    val browser = CustomTabsBrowser(app, activities)

    /** A verified App Link waiting for the owner to be signed in. In memory only: it is gone if the process dies. */
    val deepLinks = DeepLinkInbox()
    val shares = ShareInbox()

    /** Credential Manager, unless an emulator test installs a software authenticator through [SwitchablePasskeys.override]. */
    val passkeys = SwitchablePasskeys(CredentialManagerPasskeys(app, activities))
    init {
        // A pending share belongs to the account it arrived for: sign-out or another account drops it (see ShareInbox).
        scope.launch { shares.clearWhenAccountChanges(accountState.activeAccount) }
    }

    val auth = AuthController(
        api = mobileApi,
        sessions = sessions,
        passkeys = passkeys,
        browser = browser,
        origin = environment.origin,
        scope = scope,
    )

    // Chat calls this after `proof_required` with `fresh_passkey`, then retries with the same request ID.
    val reauth: ReauthHook = ReauthHook { auth.reauthenticate() == ReauthResult.Fresh }

    val account: AccountRepository = SessionAccountRepository(accountState, auth)
    val chatDrafts: ChatDraftRepository = StoredChatDraftRepository(accountState, io)

    /** No Firebase in this build; see docs/android-notifications.md for the owner steps that swap in FCM. */
    val push: PushMessaging = NoOpPushMessaging
    val notifications = DeviceNotifications(mobileApi, push)

    val themes: ThemeStore = PrefsThemeStore(app)
    val photoPreparer: PhotoPreparer = AndroidPhotoPreparer(app.contentResolver)
    val previewer: PhotoPreviewer = AndroidPhotoPreviewer(app.contentResolver)
    val uploads: UploadStore = StoredUploadStore(accountState, io)
    val links = AppLinks(environment.origin)
}
