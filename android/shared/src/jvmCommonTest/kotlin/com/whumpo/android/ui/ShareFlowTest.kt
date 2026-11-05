package com.whumpo.android.ui

import com.whumpo.android.ui.share.ShareFlow
import com.whumpo.android.ui.share.ShareInbox
import com.whumpo.android.ui.share.SharedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WH-085: a share does nothing until the owner confirms, and a pending share never outlives its account. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShareFlowTest {
    private val added = mutableListOf<String>()
    private val drafted = mutableListOf<String>()

    private fun flow(item: SharedItem) = ShareFlow(item, { added += it }, { drafted += it })

    @Test
    fun aPhotoNeedsAChosenProjectAndNothingHappensBeforeConfirm() {
        val f = flow(SharedItem.Photo("content://media/1"))
        assertFalse(f.canConfirm)
        assertFalse("confirm with no project does nothing", f.confirm())
        assertTrue(added.isEmpty())
        f.choose("project-1")
        assertTrue("choosing is not submitting", added.isEmpty())
        assertTrue(f.canConfirm)
        assertTrue(f.confirm())
        assertEquals(listOf("project-1"), added)
    }

    @Test
    fun aDoubleConfirmAddsOnce() {
        val f = flow(SharedItem.Photo("content://media/1")).apply { choose("p") }
        f.confirm()
        assertFalse(f.confirm())
        assertEquals(1, added.size)
    }

    @Test
    fun textGoesToTheDraftOnlyOnConfirmAndNeverToAProject() {
        val f = flow(SharedItem.Text("opening hours https://example.test"))
        assertTrue(drafted.isEmpty())
        assertTrue(f.confirm())
        assertEquals(listOf("opening hours https://example.test"), drafted)
        assertTrue(added.isEmpty())
    }

    @Test
    fun aPendingShareIsDroppedOnSignOutOrAnotherAccountButKeptAcrossSignIn() = runTest(UnconfinedTestDispatcher()) {
        val inbox = ShareInbox()
        val account = MutableStateFlow<Any?>(null)
        val job = launch { inbox.clearWhenAccountChanges(account) }

        inbox.offer(SharedItem.Text("arrived signed out"))
        account.value = "a"
        assertEquals("a share can arrive before sign-in", SharedItem.Text("arrived signed out"), inbox.pending.value)

        account.value = "b"
        assertNull("another account never sees A's share", inbox.pending.value)

        inbox.offer(SharedItem.Photo("content://media/1"))
        account.value = null
        assertNull("sign-out drops it", inbox.pending.value)
        job.cancel()
    }

    // ---- the preview lives and dies with the pending item ----

    private class FakePreviewer(val gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null, val result: () -> androidx.compose.ui.graphics.ImageBitmap?) :
        com.whumpo.android.ui.share.PhotoPreviewer {
        var calls = 0
        override suspend fun thumbnail(source: String, maxSidePx: Int): androidx.compose.ui.graphics.ImageBitmap? {
            calls++
            gate?.await()
            return result()
        }
    }

    // ImageBitmap(w, h) needs Skia's native library, which the JVM unit-test host does not load: a stub does the job.
    private fun tiny(): androidx.compose.ui.graphics.ImageBitmap = object : androidx.compose.ui.graphics.ImageBitmap {
        override val width = 2
        override val height = 2
        override val colorSpace get() = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
        override val hasAlpha = false
        override val config = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
        override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
        override fun prepareToDraw() = Unit
    }

    @Test
    fun aGoodPhotoBecomesAThumbnailAndABadOneBecomesUnavailable() = runTest(UnconfinedTestDispatcher()) {
        val inbox = ShareInbox()
        inbox.offer(SharedItem.Photo("content://media/1"))
        inbox.loadPreview(FakePreviewer { tiny() }, 480)
        assertTrue(inbox.preview.value is com.whumpo.android.ui.share.PreviewState.Ready)

        inbox.offer(SharedItem.Photo("content://media/2"))
        inbox.loadPreview(FakePreviewer { null }, 480)
        assertEquals(com.whumpo.android.ui.share.PreviewState.Unavailable, inbox.preview.value)

        inbox.offer(SharedItem.Photo("content://media/3"))
        inbox.loadPreview(FakePreviewer { throw SecurityException("grant revoked") }, 480)
        assertEquals("a throwing provider is a fallback, not a crash", com.whumpo.android.ui.share.PreviewState.Unavailable, inbox.preview.value)
    }

    @Test
    fun textNeverAsksForAPreview() = runTest(UnconfinedTestDispatcher()) {
        val inbox = ShareInbox()
        inbox.offer(SharedItem.Text("hello"))
        val previewer = FakePreviewer { tiny() }
        inbox.loadPreview(previewer, 480)
        assertEquals(0, previewer.calls)
        assertEquals(com.whumpo.android.ui.share.PreviewState.Idle, inbox.preview.value)
    }

    @Test
    fun clearDropsTheThumbnailAndALateResultNeverComesBack() = runTest(UnconfinedTestDispatcher()) {
        val inbox = ShareInbox()
        inbox.offer(SharedItem.Photo("content://media/1"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch { inbox.loadPreview(FakePreviewer(gate) { tiny() }, 480) }
        assertEquals(com.whumpo.android.ui.share.PreviewState.Loading, inbox.preview.value)

        inbox.clear() // dismiss, sign-out or another account
        gate.complete(Unit)
        job.join()
        assertNull(inbox.pending.value)
        assertEquals("a slow file cannot put the thumbnail back", com.whumpo.android.ui.share.PreviewState.Idle, inbox.preview.value)

        inbox.offer(SharedItem.Photo("content://media/2"))
        inbox.loadPreview(FakePreviewer { tiny() }, 480)
        assertTrue(inbox.preview.value is com.whumpo.android.ui.share.PreviewState.Ready)
        inbox.clear()
        assertEquals(com.whumpo.android.ui.share.PreviewState.Idle, inbox.preview.value)
    }

    @Test
    fun theThumbnailIsDroppedWhenTheAccountChanges() = runTest(UnconfinedTestDispatcher()) {
        val inbox = ShareInbox()
        val account = MutableStateFlow<Any?>("a")
        val job = launch { inbox.clearWhenAccountChanges(account) }
        inbox.offer(SharedItem.Photo("content://media/1"))
        inbox.loadPreview(FakePreviewer { tiny() }, 480)
        account.value = "b"
        assertEquals(com.whumpo.android.ui.share.PreviewState.Idle, inbox.preview.value)
        job.cancel()
    }
}
