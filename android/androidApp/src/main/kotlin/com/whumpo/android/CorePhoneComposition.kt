package com.whumpo.android

import android.content.Context
import com.whumpo.android.wear.CoreWearTodayPublisher
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreClientSettings
import com.whumpo.core.auth.CoreAccountSecurityController
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreWorkspaceAuthorityLoss
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        authorityLossJob.cancel()
        accounts.clear()
        api.close()
    }
}
