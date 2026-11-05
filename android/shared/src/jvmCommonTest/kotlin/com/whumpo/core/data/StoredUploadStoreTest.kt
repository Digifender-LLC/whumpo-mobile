package com.whumpo.core.data

import com.whumpo.android.ui.photos.PendingUpload
import com.whumpo.core.account.AccountScope
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.account.FakeSecretBox
import com.whumpo.core.account.SecretBox
import java.io.File
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** WH-085: prepared uploads survive a restart, belong to one account and never reach disk in the clear. */
class StoredUploadStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ownerA = AccountScope.of("acct_fixture_a")
    private val ownerB = AccountScope.of("acct_fixture_b")
    private val box = FakeSecretBox()
    private val jpeg = ByteArray(4_096) { (it * 7).toByte() }
    private val project = "11111111-2222-4333-8444-555555555555"

    private fun accountStore(b: SecretBox = box) = AccountStateStore(File(temp.root, "account-state"), b)

    private fun upload(n: Int) = PendingUpload("00000000-0000-4000-8000-00000000000$n", project, "van$n.jpg", jpeg)

    @Test
    fun theSameBytesNameAndIdComeBackAfterARestart() = runBlocking {
        val first = accountStore().apply { activate(ownerA) }
        StoredUploadStore(first, Dispatchers.Unconfined).save(upload(1), ownerA)

        val restarted = accountStore().apply { restore() }
        val back = StoredUploadStore(restarted, Dispatchers.Unconfined).load().single()
        assertEquals("00000000-0000-4000-8000-000000000001", back.id)
        assertEquals(project, back.projectId)
        assertEquals("van1.jpg", back.name)
        assertArrayEquals(jpeg, back.jpeg)
    }

    @Test
    fun signOutAndAccountChangeEraseThePhotoBytes() = runBlocking {
        val account = accountStore().apply { activate(ownerA) }
        val store = StoredUploadStore(account, Dispatchers.Unconfined)
        store.save(upload(1), ownerA)

        account.activate(ownerB)
        assertTrue(store.load().isEmpty())
        account.activate(ownerA)
        assertTrue("another account's upload must not return", store.load().isEmpty())

        store.save(upload(2), ownerA)
        account.signOut()
        assertTrue(store.load().isEmpty())
        assertFalse(File(temp.root, "account-state").exists())
    }

    @Test
    fun aSaveForAnAccountThatIsNoLongerSignedInIsRefused() = runBlocking {
        val account = accountStore().apply { activate(ownerB) }
        val store = StoredUploadStore(account, Dispatchers.Unconfined)
        assertFalse("A's bytes must not land in B's slot", store.save(upload(1), ownerA))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun bytesAreEncryptedAtRestAndNothingIsSavedSignedOut() = runBlocking {
        val account = accountStore()
        val store = StoredUploadStore(account, Dispatchers.Unconfined)
        assertFalse(store.save(upload(1), null))

        account.activate(ownerA)
        store.save(PendingUpload("00000000-0000-4000-8000-000000000009", project, "marker-name.jpg", jpeg), ownerA)
        val disk = File(temp.root, "account-state").walkTopDown().filter { it.isFile }
            .joinToString("|") { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(disk.contains("marker-name"))
    }

    @Test
    fun removeBoundsAndMalformedIds() = runBlocking {
        val account = accountStore().apply { activate(ownerA) }
        val store = StoredUploadStore(account, Dispatchers.Unconfined)
        for (n in 1..StoredUploadStore.MAX_PENDING) assertTrue(store.save(upload(n), ownerA))
        assertFalse("the sixth is refused", store.save(upload(StoredUploadStore.MAX_PENDING + 1), ownerA))
        assertTrue("re-saving an existing id is allowed", store.save(upload(1), ownerA))
        assertFalse(store.save(PendingUpload("../escape", project, "x.jpg", jpeg), ownerA))
        assertFalse(store.save(PendingUpload("00000000-0000-4000-8000-00000000000a", project, "x.jpg", ByteArray(StoredUploadStore.MAX_BYTES + 1)), ownerA))

        store.remove(upload(1).id)
        assertEquals(StoredUploadStore.MAX_PENDING - 1, store.load().size)
    }

    @Test
    fun aStuckUploadExpiresAndFreesItsSlot() = runBlocking {
        var now = 1_000_000L
        val account = accountStore().apply { activate(ownerA) }
        val store = StoredUploadStore(account, Dispatchers.Unconfined) { now }
        for (n in 1..StoredUploadStore.MAX_PENDING) assertTrue(store.save(upload(n), ownerA))
        assertFalse("full: a new upload is not kept", store.save(upload(6), ownerA))

        now += StoredUploadStore.MAX_AGE_MILLIS - 1
        assertEquals("still inside the week", StoredUploadStore.MAX_PENDING, store.load().size)
        now += 2
        assertTrue("a week later they are gone", store.load().isEmpty())
        assertTrue("and the slots are free again", store.save(upload(6), ownerA))
    }

    @Test
    fun aKeystoreFailureMeansNotKeptNeverACrash() = runBlocking {
        val failing = object : SecretBox by box {
            override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                if (plaintext.size > 1_000) throw GeneralSecurityException("Keystore unavailable") else box.seal(plaintext, associatedData)
        }
        val account = accountStore(failing).apply { activate(ownerA) }
        val store = StoredUploadStore(account, Dispatchers.Unconfined)
        assertFalse(store.save(upload(1), ownerA))
        assertTrue(store.load().isEmpty())
    }
}
