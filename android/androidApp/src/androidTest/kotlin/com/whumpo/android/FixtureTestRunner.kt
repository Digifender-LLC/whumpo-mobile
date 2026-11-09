package com.whumpo.android

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.environment.WhumpoEnvironment
import com.whumpo.core.api.okHttpEngine
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Installed only in the test APK; deployed instrumented tests cannot reach the public API. */
class FixtureTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, FixtureApplication::class.java.name, context)
}

class FixtureApplication : WhumpoApplication() {
    override fun createContainer(): AppContainer {
        return AppContainer(
            environment = WhumpoEnvironment.from(BuildConfig.WHUMPO_ENVIRONMENT, BuildConfig.WHUMPO_ORIGIN),
            accountState = AccountStateStore(accountStateDirectory(this), KeystoreSecretBox(ACCOUNT_STATE_KEY_ALIAS)),
            io = Dispatchers.IO,
            app = this,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            apiEngine = fixtureApiEngine(),
        )
    }
}

/** Always synthetic: real HTTP to loopback locally, an in-process responder for the deployed flavor. */
internal fun fixtureApiEngine(): HttpClientEngine {
    // Preserve real HTTP credential-hygiene evidence and the opt-in, separately seeded local Worker journey.
    if (BuildConfig.WHUMPO_ENVIRONMENT == "local") return okHttpEngine()
    return MockEngine { request ->
        val worker = FakeWorker.active
        val (status, body) = if (worker == null) {
            "503 Service Unavailable" to """{"version":1,"error":{"code":"unavailable","message":"No synthetic fixture is running."}}"""
        } else {
            val bytes = request.body.toByteArray()
            withContext(Dispatchers.IO) {
                worker.exchange(
                    request.method.value,
                    request.url.encodedPath,
                    request.headers.entries().associate { (key, value) -> key.lowercase() to value.joinToString(",") },
                    bytes,
                )
            }
        }
        respond(body, HttpStatusCode.fromValue(status.substringBefore(' ').toInt()),
            headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"))
    }
}
