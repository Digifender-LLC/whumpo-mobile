package com.whumpo.core.account

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Runs on every target (Android host and JVM) to prove commonMain compiles and tests there.
class StateSlotTest {
    @Test
    fun acceptsLowercaseSlugNames() {
        assertTrue(StateSlot(StateKind.DRAFT, "chat-1").name == "chat-1")
    }

    @Test
    fun rejectsUnsafeNames() {
        assertFailsWith<IllegalArgumentException> { StateSlot(StateKind.SESSION, "../x") }
    }
}
