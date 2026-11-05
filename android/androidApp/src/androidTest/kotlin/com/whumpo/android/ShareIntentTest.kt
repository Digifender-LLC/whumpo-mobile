package com.whumpo.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WH-085 on the emulator: the system share sheet's intents (ACTION_SEND) reach the app, and nothing is uploaded or sent
 * until the owner confirms (the photo is read on the phone for the preview, in memory only). The server is [FakeWorker] on device loopback; this is synthetic emulator evidence, not
 * a physical device and not a safety-scan or provider result.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var worker: FakeWorker
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun signedInWithAFakeWorker() {
        // The loopback stand-in speaks cleartext HTTP on 127.0.0.1, which only the local flavor's network config allows.
        assumeTrue(BuildConfig.WHUMPO_ENVIRONMENT == "local")
        Fixtures.reset()
        worker = FakeWorker()
        // Account state plus an in-memory bearer, as a finished sign-in would leave them. Synthetic values only.
        Fixtures.container.sessions.begin("0".repeat(63) + "1")
        Fixtures.container.accountState.activate(Fixtures.ownerA)
    }

    @After
    fun cleanUp() {
        scenario?.close()
        if (!::worker.isInitialized) return
        if (::worker.isInitialized) worker.close()
        Fixtures.reset()
    }

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent.setClassName(Fixtures.app.packageName, MainActivity::class.java.name))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun sharedText(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    /** A synthetic picture (two colour bands), so a rendered preview is visibly the photo and not a placeholder. */
    private fun syntheticJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(Color.rgb(20, 120, 200))
            drawRect(0f, 120f, 320f, 240f, android.graphics.Paint().apply { color = Color.rgb(240, 180, 40) })
        }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** A real `content://` URI from [TestShareProvider] in the test APK. MediaStore needs a storage permission to insert on API 28. */
    private fun sharedPhoto(bytes: ByteArray = syntheticJpeg()): Intent {
        val uri = TestShareProvider.uriFor(InstrumentationRegistry.getInstrumentation().context.packageName, bytes)
        return Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
    }

    /** Emulator screenshots for the PR (app external files, pulled by adb); a failure to capture never fails a test. */
    private fun shot(name: String) {
        try {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = java.io.File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
            val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            Thread.sleep(400)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bmp ->
                java.io.File(dir, "$name-${if (night) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun waitFor(timeout: Long = 10_000, condition: () -> Boolean) = compose.waitUntil(timeout, condition)

    @Test
    fun sharedTextOnlyReachesTheDraftAfterConfirmAndIsNeverSent() {
        launch(sharedText("Please add opening hours https://example.test/hours"))
        compose.onNodeWithTag("share-text").assertIsDisplayed()
        shot("share-text")
        // Before confirming: nothing was posted, and the link was not fetched.
        assertTrue(worker.requests.none { it.method != "GET" })
        assertTrue(worker.requests.none { "example.test" in it.path })

        compose.onNodeWithTag("share-confirm").performClick()
        waitFor { compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty() }
        waitFor { compose.onAllNodesWithText("Please add opening hours", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
        Thread.sleep(500)
        assertTrue("the draft is not sent", worker.requests.none { it.method != "GET" })
    }

    @Test
    fun cancellingAShareSendsAndUploadsNothing() {
        launch(sharedPhoto())
        compose.onNodeWithTag("share-cancel").performClick()
        waitFor { compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty() }
        Thread.sleep(500)
        assertTrue(worker.uploads().isEmpty())
        assertTrue(runBlocking { Fixtures.container.uploads.load() }.isEmpty())
    }

    @Test
    fun aSharedPhotoUploadsOnlyAfterAProjectIsChosenAndConfirmed() {
        launch(sharedPhoto())
        waitFor { compose.onAllNodesWithTag("share-project-${FakeWorker.PROJECT_ID}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-confirm").assertIsNotEnabled()
        shot("share-photo-choose")
        assertTrue(worker.uploads().isEmpty())

        compose.onNodeWithTag("share-project-${FakeWorker.PROJECT_ID}").performClick()
        compose.onNodeWithTag("share-confirm").assertIsEnabled()
        shot("share-photo-chosen")
        Thread.sleep(500)
        assertTrue("choosing a project is not submitting", worker.uploads().isEmpty())

        compose.onNodeWithTag("share-confirm").performClick()
        waitFor { worker.uploads().isNotEmpty() }
        val put = worker.uploads().single()
        assertTrue(put.headers["x-photo-name"]!!.startsWith("synthetic-share"))
        // A prepared baseline JPEG with no EXIF/APPn segment.
        assertEquals(0xFF.toByte(), put.body[0])
        assertEquals(0xD8.toByte(), put.body[1])
        assertFalse(String(put.body, Charsets.ISO_8859_1).contains("Exif"))
        assertTrue(put.body.size <= 1_048_576)
    }

    @Test
    fun aLostReplyKeepsTheExactBytesAndRetriesTheSameRequestUntilLogoutErasesThem() {
        worker.dropUploads = true
        launch(sharedPhoto())
        waitFor { compose.onAllNodesWithTag("share-project-${FakeWorker.PROJECT_ID}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-project-${FakeWorker.PROJECT_ID}").performClick()
        compose.onNodeWithTag("share-confirm").performClick()

        // The reply is lost; the status read finds nothing; the owner sees a retry that "won't be added twice".
        waitFor { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        worker.dropUploads = false
        val first = worker.uploads().first()
        val kept = runBlocking { Fixtures.container.uploads.load() }.single()
        assertArrayEquals(first.body, kept.jpeg)
        assertTrue(first.path.endsWith("/photos/${kept.id}"))

        compose.onAllNodesWithText("Try again")[0].performScrollTo().performClick()
        waitFor { worker.uploads().size > 1 && runBlocking { Fixtures.container.uploads.load() }.isEmpty() }
        val second = worker.uploads().last()
        worker.uploads().forEach { assertArrayEquals(first.body, it.body); assertEquals(first.path, it.path) }
        assertEquals(first.path, second.path)
        assertEquals(first.headers["x-photo-name"], second.headers["x-photo-name"])
        assertArrayEquals(first.body, second.body)

        // And a fresh interrupted upload does not survive sign-out.
        runBlocking {
            Fixtures.container.uploads.save(com.whumpo.android.ui.photos.PendingUpload("00000000-0000-4000-8000-0000000000aa", FakeWorker.PROJECT_ID, "x.jpg", first.body), Fixtures.ownerA)
        }
        Fixtures.container.accountState.signOut()
        assertTrue(runBlocking { Fixtures.container.uploads.load() }.isEmpty())
    }

    @Test
    fun unsupportedSharesAreIgnored() {
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_TEXT, "x")
                .setClassName(Fixtures.app.packageName, MainActivity::class.java.name),
        )
        Thread.sleep(1_000)
        assertTrue(compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty())
        // A file:// handle is refused as well.
        scenario!!.onActivity {
            it.handleShare(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/local/tmp/x.jpg")))
        }
        Thread.sleep(500)
        assertTrue(compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty())
        assertTrue(worker.requests.none { it.method != "GET" })
    }

    // ---- the photo preview: read on this phone, in memory, nothing leaves before Add ----

    private fun appFiles(): Map<String, Pair<Long, Long>> {
        val ctx = Fixtures.app
        val roots = listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir, ctx.codeCacheDir)
        return roots.flatMap { r -> r.walkTopDown().filter { it.isFile }.map { it.path to (it.length() to it.lastModified()) }.toList() }.toMap()
    }

    @Test
    fun thePreviewShowsThePhotoAndWritesNothingAndSendsNothingBeforeAdd() {
        val intent = sharedPhoto()
        val before = appFiles()
        launch(intent)
        waitFor { compose.onAllNodesWithTag("share-preview-image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-preview-image").assertIsDisplayed()
        compose.onNodeWithTag("share-preview-image").assert(androidx.compose.ui.test.hasContentDescription("Preview of the photo you're sharing."))
        Shots.take("share-preview")
        Thread.sleep(500)
        assertEquals("the preview wrote no file, cache or log", before, appFiles())
        assertTrue("nothing but reads went to the server", worker.requests.none { it.method != "GET" })
        assertTrue(worker.uploads().isEmpty())
        assertTrue("nothing was kept for recovery", runBlocking { Fixtures.container.uploads.load() }.isEmpty())
    }

    @Test
    fun aCorruptFileGivesAPlainFallbackAndTheDialogStillWorks() {
        launch(sharedPhoto(ByteArray(2_000) { (it * 31).toByte() }))
        waitFor { compose.onAllNodesWithTag("share-preview-unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Preview unavailable. You can still add the photo.").assertIsDisplayed()
        Shots.take("share-preview-unavailable")
        waitFor { compose.onAllNodesWithTag("share-project-${FakeWorker.PROJECT_ID}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-project-${FakeWorker.PROJECT_ID}").performClick()
        compose.onNodeWithTag("share-confirm").assertIsEnabled()
        assertTrue(worker.uploads().isEmpty())
    }

    @Test
    fun theThumbnailIsDroppedOnDismissAndOnAccountChange() {
        launch(sharedPhoto())
        waitFor { Fixtures.container.shares.preview.value is com.whumpo.android.ui.share.PreviewState.Ready }
        compose.onNodeWithTag("share-cancel").performClick()
        waitFor { compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty() }
        assertEquals(com.whumpo.android.ui.share.PreviewState.Idle, Fixtures.container.shares.preview.value)
        assertEquals(null, Fixtures.container.shares.pending.value)

        scenario!!.onActivity { it.handleShare(sharedPhoto().also { i -> i.setPackage(null) }) }
        waitFor { Fixtures.container.shares.preview.value is com.whumpo.android.ui.share.PreviewState.Ready }
        Fixtures.container.accountState.activate(Fixtures.ownerB)
        waitFor { Fixtures.container.shares.pending.value == null }
        assertEquals(com.whumpo.android.ui.share.PreviewState.Idle, Fixtures.container.shares.preview.value)

        scenario!!.onActivity { it.handleShare(sharedText("another")) }
        Fixtures.container.accountState.signOut()
        waitFor { Fixtures.container.shares.pending.value == null }
        assertEquals(com.whumpo.android.ui.share.PreviewState.Idle, Fixtures.container.shares.preview.value)
    }

    // ---- recreation: a share is answered once ----

    @Test
    fun rotationDoesNotReplayAShareAndAddDoesNotUploadTwice() {
        launch(sharedPhoto())
        waitFor { compose.onAllNodesWithTag("share-project-${FakeWorker.PROJECT_ID}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("share-project-${FakeWorker.PROJECT_ID}").performClick()
        compose.onNodeWithTag("share-confirm").performClick()
        waitFor { worker.uploads().isNotEmpty() }
        waitFor { compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty() }

        scenario!!.recreate()
        Thread.sleep(1_500)
        assertTrue("the dialog does not come back", compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty())
        assertEquals("one upload, not two", 1, worker.uploads().map { it.path }.distinct().size)
    }

    @Test
    fun cancelSurvivesRotationAndTheShareIsNotUndone() {
        launch(sharedText("Please do not come back"))
        compose.onNodeWithTag("share-cancel").performClick()
        waitFor { compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty() }
        scenario!!.recreate()
        Thread.sleep(1_500)
        assertTrue(compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aShareStillOpenSurvivesRotationWithoutReplayingItsAnswer() {
        launch(sharedText("Keep me open"))
        scenario!!.recreate()
        Thread.sleep(1_000)
        // The dialog was never answered, so it is still the one pending item (not a second copy).
        assertEquals(com.whumpo.android.ui.share.SharedItem.Text("Keep me open"), Fixtures.container.shares.pending.value)
    }

    @Test
    fun aFreshLauncherIntentDoesNotReplayAnOldShareFromHistory() {
        scenario = ActivityScenario.launch(
            sharedText("From Recents").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                .setClassName(Fixtures.app.packageName, MainActivity::class.java.name),
        )
        Thread.sleep(1_500)
        assertEquals(null, Fixtures.container.shares.pending.value)
        assertTrue(compose.onAllNodesWithTag("share-dialog").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aHostileExtraNeverCrashesTheApp() {
        scenario = ActivityScenario.launch(
            Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, "not a uri")
                .setClassName(Fixtures.app.packageName, MainActivity::class.java.name),
        )
        Thread.sleep(1_000)
        assertEquals(null, Fixtures.container.shares.pending.value)
        scenario!!.onActivity {
            it.handleShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, android.text.SpannableString("Styled text")))
        }
        assertEquals(com.whumpo.android.ui.share.SharedItem.Text("Styled text"), Fixtures.container.shares.pending.value)
    }
}
