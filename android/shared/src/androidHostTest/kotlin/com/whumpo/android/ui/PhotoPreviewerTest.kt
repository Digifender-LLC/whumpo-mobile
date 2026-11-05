package com.whumpo.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.whumpo.android.ui.photos.AndroidPhotoPreviewer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The share preview on real bitmap code: a small thumbnail from a good photo, a plain null from anything else. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoPreviewerTest {
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(200, 80, 40))
        return ByteArrayOutputStream().also { bitmap.compress(format, 95, it) }.toByteArray()
    }

    private fun previewer(bytes: ByteArray?, timeoutMs: Long = 4_000) =
        AndroidPhotoPreviewer({ bytes?.let { ByteArrayInputStream(it) } }, timeoutMs)

    private val uri = "content://media/external/images/media/1"

    @Test
    fun aGoodPhotoBecomesAThumbnailWithinTheRequestedSize() = runBlocking {
        val thumb = previewer(image(3_000, 2_000)).thumbnail(uri, 480)
        assertNotNull(thumb)
        assertTrue("longer side fits 480: ${thumb!!.width}x${thumb.height}", maxOf(thumb.width, thumb.height) <= 480)
        assertTrue(thumb.width > thumb.height)
    }

    @Test
    fun pngAndWebpAreAcceptedToo() = runBlocking {
        assertNotNull(previewer(image(200, 100, Bitmap.CompressFormat.PNG)).thumbnail(uri, 480))
        assertNotNull(previewer(image(200, 100, Bitmap.CompressFormat.WEBP_LOSSY)).thumbnail(uri, 480))
    }

    @Test
    fun corruptTruncatedWrongTypeAndEmptyFilesFallBackToNull() = runBlocking {
        assertNull(previewer(ByteArray(2_000) { (it * 31).toByte() }).thumbnail(uri, 480))
        assertNull(previewer(image(300, 200).copyOf(40)).thumbnail(uri, 480))
        assertNull("GIF is not an accepted type", previewer("GIF89a".toByteArray() + ByteArray(100)).thumbnail(uri, 480))
        assertNull(previewer(ByteArray(0)).thumbnail(uri, 480))
        assertNull("the provider has nothing", previewer(null).thumbnail(uri, 480))
    }

    @Test
    fun aFileOverTheTenMebibyteCapIsRefusedLikeTheUpload() = runBlocking {
        val jpeg = image(100, 100)
        val big = jpeg + ByteArray(10 * 1024 * 1024) // a valid JPEG header followed by padding past the cap
        assertNull(previewer(big).thumbnail(uri, 480))
    }

    @Test
    fun onlyContentUrisAreRead() = runBlocking {
        var opened = 0
        val p = AndroidPhotoPreviewer({ opened++; ByteArrayInputStream(image(50, 50)) })
        assertNull(p.thumbnail("file:///data/local/tmp/x.jpg", 480))
        assertNull(p.thumbnail("https://example.test/x.jpg", 480))
        assertEquals("a non-content handle is never opened", 0, opened)
    }

    @Test
    fun aProviderThatThrowsOrHangsCannotFreezeTheCaller() = runBlocking {
        assertNull(AndroidPhotoPreviewer({ throw SecurityException("grant revoked") }).thumbnail(uri, 480))

        val block = CountDownLatch(1)
        val hanging = object : InputStream() {
            override fun read(): Int {
                block.await() // a provider that never answers
                return -1
            }
        }
        val started = System.nanoTime()
        val result = AndroidPhotoPreviewer({ hanging }, timeoutMs = 300).thumbnail(uri, 480)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        block.countDown()
        assertNull(result)
        assertTrue("returned in about the timeout, not when the provider answered: $tookMs ms", tookMs < 3_000)
    }

    @Test
    fun theDecodeWritesNothingToDisk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun files() = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir).flatMap { d -> d.walkTopDown().filter { it.isFile }.map { it.path }.toList() }
        val before = files()
        previewer(image(1_000, 800)).thumbnail(uri, 480)
        assertEquals(before, files())
    }
}
