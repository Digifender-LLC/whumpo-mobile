package com.whumpo.android

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreClientSettings
import com.whumpo.core.auth.CoreAccountSecurityController
import com.whumpo.core.auth.CoreOrganizationSessionController

/** Process-owned Core auth/session controllers shared by the launcher and connected clients. */
internal class CorePhoneComposition(
    val settings: CoreClientSettings,
    private val api: CoreApiClient,
) : AutoCloseable {
    val organizations = CoreOrganizationSessionController(api)
    val accounts = CoreAccountSecurityController(api, organizations, settings.rpId)

    /** Drop local authority before releasing the HTTP client; no credential is persisted here. */
    override fun close() {
        accounts.clear()
        api.close()
    }
}
