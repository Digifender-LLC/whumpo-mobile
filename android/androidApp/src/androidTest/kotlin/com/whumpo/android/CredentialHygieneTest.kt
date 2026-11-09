package com.whumpo.android

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.core.account.AccountScope
import com.whumpo.core.account.StateSlot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * Credentials never reach the device log, a plain file or the backup system. The bearer is random per run, so finding it
 * anywhere is unambiguous. Emulator evidence only: not a physical device, a real sign-in or a cloud backup.
 */
@RunWith(AndroidJUnit4::class)
class CredentialHygieneTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val token = buildString { val r = SecureRandom(); repeat(64) { append("0123456789abcdef"[r.nextInt(16)]) } }
    private val label = "h***@hygiene-${token.takeLast(8)}.example"

    @Before
    fun setUp() = Fixtures.reset()

    @After
    fun tearDown() = Fixtures.reset()

    /** Files under the app's data directory whose bytes contain any of [needles] in plain text. */
    private fun plainTextHits(vararg needles: String): List<String> {
        val dataDir = File(Fixtures.app.applicationInfo.dataDir)
        val patterns = needles.map { it.toByteArray() }
        return dataDir.walkTopDown().filter { it.isFile && it.canRead() }.filter { file ->
            val bytes = file.readBytes()
            patterns.any { p -> bytes.indexOf(p) >= 0 }
        }.map { it.relativeTo(dataDir).path }.toList()
    }

    private fun ByteArray.indexOf(p: ByteArray): Int {
        if (p.isEmpty() || p.size > size) return -1
        outer@ for (i in 0..size - p.size) {
            for (j in p.indices) if (this[i + j] != p[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun aSignedInSessionNeverReachesTheDeviceLogOrAPlainFile() {
        // The loopback stand-in speaks cleartext HTTP on 127.0.0.1, which only the local flavor allows.
        assumeTrue(BuildConfig.WHUMPO_ENVIRONMENT == "local")
        val sessions = Fixtures.container.sessions
        val worker = Fixtures.signIn(label, token)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav-projects").fetchSemanticsNodes().isNotEmpty() }
            listOf("projects", "activity", "settings", "chat").forEach {
                compose.onNodeWithTag("nav-$it").performClick()
                compose.waitForIdle()
            }
            fun requests() = synchronized(worker.requests) { worker.requests.toList() }
            // The bearer really went through the HTTP stack, so a logging interceptor or error path would have seen it.
            compose.waitUntil(10_000) { requests().any { it.headers["authorization"] == "Bearer $token" } }
            assertTrue(requests().none { "cookie" in it.headers })
            assertEquals("plain-text copies of the bearer or account label", emptyList<String>(), plainTextHits(token, label))
        }
        runBlocking { sessions.clear() }
        assertNull(runBlocking { sessions.bearerToken() })
        assertFalse(WhumpoApplication.accountStateDirectory(Fixtures.app).exists())

        val log = DeviceShell.run("logcat -d -b all")
        assertTrue("logcat returned nothing to search", log.length > 1_000)
        assertFalse("the bearer token is in the device log", log.contains(token))
        assertFalse("the account label is in the device log", log.contains(label))
        assertEquals(emptyList<String>(), plainTextHits(token, label))
    }

    @Test
    fun theBackupManagerRefusesThePackageWhileProtectedStateExists() {
        val owner = AccountScope.of(label)
        val store = Fixtures.container.accountState
        store.activate(owner)
        store.write(owner, StateSlot.CHAT_DRAFT, "backup marker $token")

        val wasEnabled = DeviceShell.run("bmgr enabled").contains("currently enabled")
        val transports = DeviceShell.run("bmgr list transports")
        val previous = transports.lines().firstOrNull { it.trim().startsWith("*") }?.trim()?.removePrefix("*")?.trim()
        val local = listOf(
            "com.android.localtransport/.LocalTransport",
            "android/com.android.internal.backup.LocalTransport",
        ).firstOrNull { transports.lines().any { line -> line.trim().removePrefix("*").trim() == it } }
        assumeTrue("the image has no local backup transport", local != null)
        try {
            DeviceShell.run("bmgr transport $local")
            val selected = DeviceShell.run("bmgr list transports").lines()
                .singleOrNull { it.trim().startsWith("*") }?.trim()?.removePrefix("*")?.trim()
            assertEquals("only the built-in local transport may run", local, selected)
            DeviceShell.run("bmgr enable true")
            assertTrue(DeviceShell.run("bmgr enabled").contains("currently enabled"))
            val pkg = Fixtures.app.packageName
            val out = DeviceShell.run("bmgr backupnow $pkg")
            assertTrue(out, out.contains("Package $pkg with result: Backup is not allowed"))
        } finally {
            if (!wasEnabled) DeviceShell.run("bmgr enable false")
            if (previous != null && previous != local) DeviceShell.run("bmgr transport $previous")
        }
    }
}
