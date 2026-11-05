package com.whumpo.android

import android.app.Application
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.environment.WhumpoEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class WhumpoApplication : Application() {
    lateinit var container: AppContainer
        private set

    // Main.immediate: the sign-in controller touches the UI thread only through the browser and Credential Manager.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val store = AccountStateStore(accountStateDirectory(this), KeystoreSecretBox(ACCOUNT_STATE_KEY_ALIAS))
        container = AppContainer(
            environment = WhumpoEnvironment.from(BuildConfig.WHUMPO_ENVIRONMENT, BuildConfig.WHUMPO_ORIGIN),
            accountState = store,
            io = Dispatchers.IO,
            app = this,
            scope = appScope,
        )
        container.auth.start()
    }

    companion object {
        const val ACCOUNT_STATE_KEY_ALIAS = "whumpo.account-state.v1"

        /** App-private and never backed up (Context.getNoBackupFilesDir). */
        fun accountStateDirectory(app: Application): File = File(app.noBackupFilesDir, "account-state")
    }
}
