package com.whumpo.android

import android.content.Context
import com.whumpo.android.wear.CoreWearTodayPublisher
import android.os.SystemClock
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.core.account.CoreOfflineWorkStore
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreClientSettings
import com.whumpo.core.auth.CoreAccountSecurityController
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreSharedDraftItem
import com.whumpo.core.auth.CoreSharedDraftReview
import com.whumpo.core.auth.CoreSharedDraftRoute
import com.whumpo.core.auth.CoreWorkspaceAuthorityLoss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-owned Core auth/session controllers shared by the launcher and connected clients. */
internal class CorePhoneComposition(
    val settings: CoreClientSettings,
    private val api: CoreApiClient,
    context: Context,
    processScope: CoroutineScope? = null,
) : AutoCloseable {
    private val authorityLossJob = SupervisorJob(processScope?.coroutineContext?.get(Job))
    private val authorityLossScope = CoroutineScope(
        (processScope?.coroutineContext ?: Dispatchers.Main.immediate) + authorityLossJob,
    )
    val organizations = CoreOrganizationSessionController(api)
    val accounts = CoreAccountSecurityController(
        api,
        organizations,
        settings.rpId,
        onWorkspaceAuthorityLost = ::queueWorkspaceAuthorityLoss,
    )
    private val wearTodayPublisher = CoreWearTodayPublisher(context, organizations, authorityLossScope)

    /** Re-reads only while this process still owns a verified active Core workspace session. */
    fun refreshWearToday() = wearTodayPublisher.refreshToday()

    val offlineWorkStore = CoreOfflineWorkStore(java.io.File(context.noBackupFilesDir, "core-offline-work")) {
        KeystoreSecretBox("whumpo.core.offline-work.v1")
    }

    internal data class SharedDraftOffer(
        val review: CoreSharedDraftReview,
        val active: CoreOrganizationSessionController.State.Active,
        val accepted: Boolean = false,
    )

    private val sharedDraftRoute = CoreSharedDraftRoute()
    private val mutableSharedDraft = MutableStateFlow<SharedDraftOffer?>(null)
    val sharedDraft = mutableSharedDraft.asStateFlow()
    private var shareReadyEpoch: Long? = null
    private var shareResumed = false
    private var shareWindowFocused = false

    /** Readiness is published only after this exact active organization has passed its readiness check. */
    fun publishShareReadiness(epoch: Long?, ready: Boolean) {
        val active = organizations.state.value as? CoreOrganizationSessionController.State.Active
        shareReadyEpoch = epoch?.takeIf { ready && active?.epoch == it }
        val offer = mutableSharedDraft.value
        if (offer != null && (active !== offer.active || shareReadyEpoch != offer.active.epoch)) {
            dismissSharedDraft(offer.review)
        }
    }

    /** This transient handoff is never persisted or queued for a later sign-in/readiness result. */
    fun offerSharedDraft(item: CoreSharedDraftItem): Boolean {
        val active = organizations.state.value as? CoreOrganizationSessionController.State.Active ?: return false
        val readyEpoch = shareReadyEpoch?.takeIf { it == active.epoch } ?: return false
        if (mutableSharedDraft.value != null) return false
        val now = SystemClock.elapsedRealtime()
        val review = sharedDraftRoute.receive(item, readyEpoch, active.session.role, now) ?: return false
        review.observeForeground(shareResumed, shareWindowFocused, now)
        mutableSharedDraft.value = SharedDraftOffer(review, active)
        return true
    }

    fun canAcceptSharedDraft(offer: SharedDraftOffer): Boolean {
        val current = mutableSharedDraft.value ?: return false
        return current.review === offer.review && !current.accepted && current.active === offer.active &&
            organizations.state.value === offer.active && shareReadyEpoch == offer.active.epoch &&
            offer.review.canReview(offer.active.epoch, SystemClock.elapsedRealtime())
    }

    /** Returns text for the in-memory editor exactly once; callers persist only after a separate user save action. */
    fun acceptSharedDraft(offer: SharedDraftOffer): String? {
        if (!canAcceptSharedDraft(offer)) return null
        var text: String? = null
        val committed = organizations.commitIfCurrent(offer.active.epoch) {
            if (mutableSharedDraft.value?.review === offer.review && !offer.accepted) {
                text = offer.review.accept(offer.active.epoch, SystemClock.elapsedRealtime())
                if (text != null) mutableSharedDraft.value = offer.copy(accepted = true)
            }
        }
        return text.takeIf { committed }
    }

    fun dismissSharedDraft(review: CoreSharedDraftReview) {
        if (mutableSharedDraft.value?.review !== review) return
        review.cancel()
        mutableSharedDraft.value = null
        sharedDraftRoute.interrupt()
    }

    fun observeShareForeground(resumed: Boolean, focused: Boolean) {
        shareResumed = resumed
        shareWindowFocused = focused
        val offer = mutableSharedDraft.value ?: return
        if (offer.accepted) return
        if (!offer.review.observeForeground(resumed, focused, SystemClock.elapsedRealtime())) {
            dismissSharedDraft(offer.review)
        }
    }

    fun discardStaleSharedDraft() {
        val active = organizations.state.value as? CoreOrganizationSessionController.State.Active
        if (active == null || shareReadyEpoch != active.epoch) shareReadyEpoch = null
        val offer = mutableSharedDraft.value ?: return
        if (active !== offer.active) dismissSharedDraft(offer.review)
    }

    private fun queueWorkspaceAuthorityLoss(expected: CoreWorkspaceAuthorityLoss) {
        // Main (not Main.immediate) keeps account locking outside the workspace controller's critical section.
        authorityLossScope.launch(Dispatchers.Main) {
            if (organizations.state.value == CoreOrganizationSessionController.State.SignedOut) {
                accounts.clearIfWorkspaceAuthorityCurrent(expected)
            }
        }
    }

    /** Drop local authority before releasing the HTTP client; no credential is persisted here. */
    override fun close() {
        wearTodayPublisher.close()
        mutableSharedDraft.value?.review?.cancel()
        mutableSharedDraft.value = null
        sharedDraftRoute.interrupt()
        authorityLossJob.cancel()
        accounts.clear()
        api.close()
    }
}
