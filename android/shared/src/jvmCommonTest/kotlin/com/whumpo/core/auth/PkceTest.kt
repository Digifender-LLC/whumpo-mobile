package com.whumpo.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun challengeMatchesTheRfc7636Vector() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun verifierAndStateFitTheContractRules() {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        assertTrue(Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier))
        assertTrue(Regex("[A-Za-z0-9_-]{16,128}").matches(state))
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(Pkce.challenge(verifier)))
        assertNotEquals(verifier, Pkce.newVerifier())
        assertNotEquals(state, Pkce.newState())
    }
}
