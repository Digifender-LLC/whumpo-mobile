package com.whumpo.core.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccountStateStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ownerA = AccountScope.of("acct_fixture_a")
    private val ownerB = AccountScope.of("acct_fixture_b")
    private val pending = StateSlot(StateKind.PENDING_OPERATION, "chat-turn")

    private fun newStore(box: SecretBox = FakeSecretBox()) =
        AccountStateStore(File(temp.root, "account-state"), box)

    @Test
    fun writesAreReadableOnlyWhileTheAccountIsActive() {
        val store = newStore()
        assertFalse(store.write(ownerA, StateSlot.CHAT_DRAFT, "hi"))

        store.activate(ownerA)
        assertTrue(store.write(ownerA, StateSlot.CHAT_DRAFT, "Add a booking page"))
        assertEquals("Add a booking page", store.read(ownerA, StateSlot.CHAT_DRAFT))
        assertNull(store.read(ownerA, pending))
        assertNull(store.read(ownerB, StateSlot.CHAT_DRAFT))
    }

    @Test
    fun plaintextAndAccountIdNeverReachDisk() {
        val store = newStore()
        store.activate(ownerA)
        store.write(ownerA, StateSlot.CHAT_DRAFT, "secret-draft-marker")

        val bytes = File(temp.root, "account-state").walkTopDown()
            .filter { it.isFile }
            .map { it.readBytes().toString(Charsets.ISO_8859_1) + it.name }
            .joinToString("|")
        assertFalse(bytes.contains("secret-draft-marker"))
        assertFalse(bytes.contains("acct_fixture_a"))
    }

    @Test
    fun switchingAccountsErasesThePreviousAccountsState() {
        val store = newStore()
        store.activate(ownerA)
        store.write(ownerA, StateSlot.CHAT_DRAFT, "owner A draft")
        store.write(ownerA, pending, "{\"requestId\":\"req_fixture\"}")

        store.activate(ownerB)
        assertNull(store.read(ownerB, StateSlot.CHAT_DRAFT))
        assertNull(store.read(ownerA, StateSlot.CHAT_DRAFT))
        assertFalse(File(File(temp.root, "account-state"), ownerA.storageKey).exists())
        assertFalse("late writes for a previous account are refused", store.write(ownerA, pending, "late"))

        store.activate(ownerA)
        assertNull(store.read(ownerA, StateSlot.CHAT_DRAFT))
    }

    @Test
    fun reactivatingTheSameAccountKeepsItsState() {
        val store = newStore()
        store.activate(ownerA)
        store.write(ownerA, StateSlot.CHAT_DRAFT, "keep me")
        store.activate(ownerA)
        assertEquals("keep me", store.read(ownerA, StateSlot.CHAT_DRAFT))
    }

    @Test
    fun signOutErasesEverythingAndDestroysTheKey() {
        val box = FakeSecretBox()
        val store = newStore(box)
        store.activate(ownerA)
        store.write(ownerA, StateSlot(StateKind.SESSION, "native"), "fixture-not-a-token")

        store.signOut()
        assertNull(store.activeAccount.value)
        assertEquals(1, box.destroyCount)
        assertFalse(File(temp.root, "account-state").exists())
        assertNull(store.read(ownerA, StateSlot(StateKind.SESSION, "native")))
    }

    @Test
    fun restoreRecoversTheActiveAccountAfterProcessDeath() {
        val box = FakeSecretBox()
        newStore(box).apply {
            activate(ownerA)
            write(ownerA, StateSlot.CHAT_DRAFT, "survives process death")
        }

        val restarted = newStore(box)
        restarted.restore()
        assertEquals(ownerA, restarted.activeAccount.value)
        assertEquals("survives process death", restarted.read(ownerA, StateSlot.CHAT_DRAFT))
    }

    @Test
    fun restoreWithoutTheOriginalKeyErasesInsteadOfTrusting() {
        newStore(FakeSecretBox()).apply {
            activate(ownerA)
            write(ownerA, StateSlot.CHAT_DRAFT, "copied to another device")
        }

        val elsewhere = newStore(FakeSecretBox())
        elsewhere.restore()
        assertNull(elsewhere.activeAccount.value)
        assertFalse(File(temp.root, "account-state").exists())
    }

    @Test
    fun recordsAreBoundToTheirSlot() {
        val store = newStore()
        store.activate(ownerA)
        store.write(ownerA, StateSlot.CHAT_DRAFT, "draft body")
        val dir = File(File(temp.root, "account-state"), ownerA.storageKey)
        File(dir, "draft-chat.bin").copyTo(File(dir, "pending-chat-turn.bin"))

        assertNull(store.read(ownerA, pending))
        assertFalse(File(dir, "pending-chat-turn.bin").exists())
        assertEquals("draft body", store.read(ownerA, StateSlot.CHAT_DRAFT))
    }

    @Test
    fun slotNamesAreBounded() {
        listOf("", "Upper", "../escape", "x".repeat(65)).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { StateSlot(StateKind.DRAFT, name) }
        }
        assertTrue(StateSlot(StateKind.DRAFT, "form-42").name.isNotEmpty())
    }
}
