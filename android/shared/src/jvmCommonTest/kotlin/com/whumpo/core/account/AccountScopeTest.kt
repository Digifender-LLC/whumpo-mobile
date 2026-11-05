package com.whumpo.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountScopeTest {
    @Test
    fun storageKeyIsStableOpaqueAndDistinct() {
        val a = AccountScope.of("acct_owner_one")
        assertEquals(a.storageKey, AccountScope.of("acct_owner_one").storageKey)
        assertNotEquals(a.storageKey, AccountScope.of("acct_owner_two").storageKey)
        assertEquals(32, a.storageKey.length)
        assertFalse(a.storageKey.contains("owner"))
    }

    @Test
    fun toStringNeverRevealsTheAccount() {
        assertEquals("AccountScope(<redacted>)", AccountScope.of("acct_owner_one").toString())
    }

    @Test
    fun rejectsMalformedAccountIds() {
        listOf("", "   ", "a b", "a\nb", "x".repeat(129)).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { AccountScope.of(id) }
        }
    }
}
