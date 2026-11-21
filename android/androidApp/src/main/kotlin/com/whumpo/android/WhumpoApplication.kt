package com.whumpo.android

import android.app.Application
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.api.CoreClientSettings
import com.whumpo.core.api.coreApiClient
import io.ktor.client.engine.HttpClientEngine
import com.whumpo.core.environment.WhumpoEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

open class WhumpoApplication : Application() {
    lateinit var container: AppContainer
        private set
    private var coreComposition: CorePhoneComposition? = null

    // Main.immediate: the sign-in controller touches the UI thread only through the browser and Credential Manager.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        if (configuredCoreSettings() == null) container.auth.start()
    }

    protected open fun syntheticCoreEndpoint(): String = BuildConfig.WHUMPO_SYNTHETIC_CORE_ORIGIN

    protected open fun syntheticCoreEngine(): HttpClientEngine? = null

    protected open fun configuredCoreApiOrigin(): String = BuildConfig.WHUMPO_CORE_API_ORIGIN

    protected open fun configuredCoreRpId(): String = BuildConfig.WHUMPO_CORE_RP_ID

    private fun configuredCoreSettings(): CoreClientSettings? {
        val apiOrigin = configuredCoreApiOrigin()
        val rpId = configuredCoreRpId()
        if (apiOrigin.isEmpty() && rpId.isEmpty()) {
            if (!BuildConfig.DEBUG || BuildConfig.WHUMPO_ENVIRONMENT != "test") return null
            val synthetic = syntheticCoreEndpoint()
            if (synthetic.isEmpty()) return null
            return CoreClientSettings.allowlisted(
                synthetic,
                rpId = "core.example.test",
                allowedHttpsOrigins = setOf("https://core.example.test"),
            )
        }
        require(apiOrigin.isNotEmpty() && rpId.isNotEmpty()) {
            "Core API origin and shared WebAuthn RP ID must be configured together"
        }
        return CoreClientSettings.allowlisted(apiOrigin, rpId, setOf(apiOrigin))
    }

    @Synchronized
    internal fun corePhoneComposition(): CorePhoneComposition? {
        coreComposition?.let { return it }
        val settings = configuredCoreSettings() ?: return null
        val engine = syntheticCoreEngine()
        val api = if (engine == null) coreApiClient(settings.origin) else coreApiClient(settings.origin, engine)
        return CorePhoneComposition(settings, api).also { coreComposition = it }
    }

    @Synchronized
    internal fun clearCorePhoneCompositionForTest() {
        coreComposition?.close()
        coreComposition = null
    }

    /** The instrumented test application supplies a synthetic transport before any session is restored. */
    protected open fun createContainer(): AppContainer {
        val store = AccountStateStore(accountStateDirectory(this), KeystoreSecretBox(ACCOUNT_STATE_KEY_ALIAS))
        return AppContainer(
            environment = WhumpoEnvironment.from(BuildConfig.WHUMPO_ENVIRONMENT, BuildConfig.WHUMPO_ORIGIN),
            accountState = store,
            io = Dispatchers.IO,
            app = this,
            scope = appScope,
        )
    }

    companion object {
        const val ACCOUNT_STATE_KEY_ALIAS = "whumpo.account-state.v1"

        /** App-private and never backed up (Context.getNoBackupFilesDir). */
        fun accountStateDirectory(app: Application): File = File(app.noBackupFilesDir, "account-state")
    }
}
