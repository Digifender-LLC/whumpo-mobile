package com.whumpo.android

import android.app.Application
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOrigin
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

    // Main.immediate: the sign-in controller touches the UI thread only through the browser and Credential Manager.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        if (coreLauncherOrigin() == null) container.auth.start()
    }

    protected open fun syntheticCoreEndpoint(): String = BuildConfig.WHUMPO_SYNTHETIC_CORE_ORIGIN

    protected open fun syntheticCoreEngine(): HttpClientEngine? = null

    private fun coreLauncherOrigin(): CoreOrigin? {
        if (!BuildConfig.DEBUG || BuildConfig.WHUMPO_ENVIRONMENT != "test") return null
        val configured = syntheticCoreEndpoint()
        if (configured.isEmpty()) return null
        return CoreOrigin.allowlisted(configured, setOf("https://core.example.test"))
    }

    internal fun coreLauncherClient(): CoreApiClient? = coreLauncherOrigin()?.let { origin ->
        syntheticCoreEngine()?.let { coreApiClient(origin, it) } ?: coreApiClient(origin)
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
