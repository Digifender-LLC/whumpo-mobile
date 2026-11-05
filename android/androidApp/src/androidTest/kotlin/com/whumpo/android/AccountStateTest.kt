package com.whumpo.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.security.KeystoreSecretBox
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.account.StateKind
import com.whumpo.core.account.StateSlot
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Keystore-protected, account-scoped state on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class AccountStateTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val store get() = Fixtures.container.accountState
    private val stateDir get() = WhumpoApplication.accountStateDirectory(Fixtures.app)

    @Before
    fun setUp() = Fixtures.reset()

    @After
    fun tearDown() = Fixtures.reset()

    @Test
    fun keystoreBoxRoundTripsAndBindsAssociatedData() {
        val box = KeystoreSecretBox("whumpo.instrumented-test.v1")
        try {
            val sealed = box.seal("fixture plaintext".toByteArray(), "aad-1".toByteArray())
            assertArrayEquals("fixture plaintext".toByteArray(), box.open(sealed, "aad-1".toByteArray()))
            assertThrows(Exception::class.java) { box.open(sealed, "aad-2".toByteArray()) }

            box.destroyKey()
            assertThrows(Exception::class.java) { box.open(sealed, "aad-1".toByteArray()) }
        } finally {
            box.destroyKey()
        }
    }

    @Test
    fun stateLivesInNoBackupStorageAndIsEncrypted() {
        assertTrue(stateDir.canonicalPath.startsWith(Fixtures.app.noBackupFilesDir.canonicalPath))
        store.activate(Fixtures.ownerA)
        store.write(Fixtures.ownerA, StateSlot.CHAT_DRAFT, "plaintext-marker-for-test")

        val onDisk = stateDir.walkTopDown().filter { it.isFile }
            .joinToString("|") { it.name + String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(onDisk.contains("plaintext-marker-for-test"))
        assertFalse(onDisk.contains("acct_fixture_instrumented_a"))
    }

    @Test
    fun accountChangeAndSignOutEraseState() {
        val pending = StateSlot(StateKind.PENDING_OPERATION, "chat-turn")
        val session = StateSlot(StateKind.SESSION, "native")
        store.activate(Fixtures.ownerA)
        store.write(Fixtures.ownerA, StateSlot.CHAT_DRAFT, "draft A")
        store.write(Fixtures.ownerA, pending, "{\"requestId\":\"req_fixture\"}")
        store.write(Fixtures.ownerA, session, "synthetic session A")

        store.activate(Fixtures.ownerB)
        assertNull(store.read(Fixtures.ownerB, StateSlot.CHAT_DRAFT))
        assertNull(store.read(Fixtures.ownerB, pending))
        assertNull(store.read(Fixtures.ownerB, session))
        assertFalse(File(stateDir, Fixtures.ownerA.storageKey).exists())
        assertFalse(store.write(Fixtures.ownerA, session, "late session A"))

        store.write(Fixtures.ownerB, StateSlot.CHAT_DRAFT, "draft B")
        store.write(Fixtures.ownerB, pending, "{\"requestId\":\"req_fixture_b\"}")
        store.write(Fixtures.ownerB, session, "synthetic session B")
        store.signOut()
        assertNull(store.activeAccount.value)
        assertFalse(stateDir.exists())
    }

    @Test
    fun draftSurvivesActivityRecreationAndProcessRestart() {
        store.activate(Fixtures.ownerA)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("chat-composer").performTextInput("Add a booking page")
        // The ViewModel saves after a short debounce on the real clock.
        compose.waitUntil(5_000) { store.read(Fixtures.ownerA, StateSlot.CHAT_DRAFT) == "Add a booking page" }

        scenario.recreate()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("chat-composer") and hasText("Add a booking page"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // A fresh store with the same directory and Keystore alias models a new process.
        val restarted = AccountStateStore(stateDir, KeystoreSecretBox(WhumpoApplication.ACCOUNT_STATE_KEY_ALIAS))
        restarted.restore()
        assertEquals(Fixtures.ownerA, restarted.activeAccount.value)
        assertEquals("Add a booking page", restarted.read(Fixtures.ownerA, StateSlot.CHAT_DRAFT))
        scenario.close()
    }

    @Test
    fun signOutFromSettingsClearsDraftsAndReturnsToWelcome() {
        store.activate(Fixtures.ownerA)
        store.write(Fixtures.ownerA, StateSlot.CHAT_DRAFT, "erase me")
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-sign-out").performScrollTo().performClick()
        compose.onNodeWithTag("settings-sign-out-confirm").performClick()
        compose.waitUntil(5_000) { store.activeAccount.value == null }
        assertFalse(stateDir.exists())

        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth-sign-in").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth-sign-in").assertIsDisplayed()
        scenario.close()
    }
}
