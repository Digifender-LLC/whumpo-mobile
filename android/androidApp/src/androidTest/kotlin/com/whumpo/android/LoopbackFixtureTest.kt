package com.whumpo.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class LoopbackFixtureTest {
    @Test
    fun closingTheFixtureDisconnectsAnIncompleteRequestAndReleasesItsPort() {
        val worker = FakeWorker()
        try {
            Socket(InetAddress.getByName("127.0.0.1"), 8787).use { client ->
                client.soTimeout = 5_000
                val deadline = System.nanoTime() + 5_000_000_000L
                while (worker.clientCount == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertEquals("fixture accepted the client waiting to send its HTTP headers", 1, worker.clientCount)
                worker.close()
                assertEquals("fixture must close accepted sockets too", -1, client.getInputStream().read())
                FakeWorker().close()
            }
        } finally {
            worker.close()
        }
    }
}
