package com.whumpo.core.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WhumpoEnvironmentTest {
    @Test
    fun acceptsLoopbackOriginsForLocalBuilds() {
        listOf("http://127.0.0.1:8787", "http://localhost:8787", "http://[::1]:8787").forEach {
            assertEquals(EnvironmentKind.LOCAL, WhumpoEnvironment.from("local", it).kind)
        }
    }

    @Test
    fun refusesNonLoopbackOrMalformedLocalOrigins() {
        listOf(
            "http://10.0.2.2:8787",
            "http://192.168.1.20:8787",
            "http://0.0.0.0:8787",
            "https://example.trycloudflare.com:443",
            "http://127.0.0.1",
            "http://127.0.0.1:8787/api",
            "http://user:pass@127.0.0.1:8787",
            "http://127.0.0.1:8787?x=1",
            "ftp://127.0.0.1:8787",
        ).forEach { origin ->
            assertThrows(origin, IllegalArgumentException::class.java) {
                WhumpoEnvironment.from("local", origin)
            }
        }
    }

    @Test
    fun testBuildsUseOnlyTheDeployedOrigin() {
        val env = WhumpoEnvironment.from("test", "https://whumpo.com")
        assertEquals("https://whumpo.com/api/mobile/v1", env.mobileApiBase)
        listOf("http://whumpo.com", "https://whumpo.com:8443", "https://whumpo.com/", "http://127.0.0.1:8787")
            .forEach { origin ->
                assertThrows(origin, IllegalArgumentException::class.java) {
                    WhumpoEnvironment.from("test", origin)
                }
            }
    }

    @Test
    fun refusesUnknownEnvironmentKinds() {
        assertThrows(IllegalArgumentException::class.java) {
            WhumpoEnvironment.from("production", "https://whumpo.com")
        }
    }
}
