package com.whumpo.core.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/** The production engine for [MobileApiClient] on Android and JVM desktop. Tests pass a Ktor `MockEngine` instead. */
fun okHttpEngine(): HttpClientEngine = OkHttp.create()
