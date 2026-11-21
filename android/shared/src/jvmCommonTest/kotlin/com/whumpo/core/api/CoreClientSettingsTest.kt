package com.whumpo.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreClientSettingsTest {
    @Test
    fun keepsExplicitApiOriginSeparateFromSharedRpId() {
        val settings = CoreClientSettings.allowlisted(
            origin = "https://api.example.test",
            rpId = "example.test",
            allowedHttpsOrigins = setOf("https://api.example.test"),
        )

        assertEquals("https://api.example.test", settings.origin.value)
        assertEquals("example.test", settings.rpId)
    }

    @Test
    fun rejectsAnEndpointOutsideTheExactAllowlist() {
        assertFailsWith<IllegalArgumentException> {
            CoreClientSettings.allowlisted(
                origin = "https://other.example.test",
                rpId = "example.test",
                allowedHttpsOrigins = setOf("https://api.example.test"),
            )
        }
    }

    @Test
    fun rejectsNonCanonicalRpIds() {
        val maxLengthRpId = listOf(63, 63, 63, 61).joinToString(".") { "a".repeat(it) }
        val tooLongRpId = listOf(63, 63, 63, 62).joinToString(".") { "a".repeat(it) }
        assertEquals(253, maxLengthRpId.length)
        assertEquals(254, tooLongRpId.length)
        assertEquals(maxLengthRpId, CoreClientSettings.allowlisted(
            "https://api.example.test",
            maxLengthRpId,
            setOf("https://api.example.test"),
        ).rpId)

        for (rpId in listOf("Example.test", "https://example.test", "example.test/", "127.0.0.1", tooLongRpId)) {
            assertFailsWith<IllegalArgumentException> {
                CoreClientSettings.allowlisted(
                    origin = "https://api.example.test",
                    rpId = rpId,
                    allowedHttpsOrigins = setOf("https://api.example.test"),
                )
            }
        }
    }

    @Test
    fun rejectsNonCanonicalOrigins() {
        for (origin in listOf("http://api.example.test", "https://api.example.test/", "https://API.example.test")) {
            assertFailsWith<IllegalArgumentException> {
                CoreClientSettings.allowlisted(origin, "example.test", setOf(origin))
            }
        }
    }
}
