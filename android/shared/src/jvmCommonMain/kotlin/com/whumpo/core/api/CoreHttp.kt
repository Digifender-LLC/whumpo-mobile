package com.whumpo.core.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/** The replacement session endpoint revokes the original bearer, so transport replay is unsafe. */
fun coreApiClient(origin: CoreOrigin): CoreApiClient = CoreApiClient(
    OkHttp.create {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    },
    origin,
)

fun coreApiClient(origin: CoreOrigin, engine: HttpClientEngine): CoreApiClient = CoreApiClient(engine, origin)
