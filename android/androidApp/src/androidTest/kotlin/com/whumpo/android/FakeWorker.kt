package com.whumpo.android

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A synthetic stand-in for `/api/mobile/v1` on device loopback (127.0.0.1:8787, the `local` flavor's origin), serving the
 * shared mobile-v1 fixture shapes for one project. It exists to count and inspect what the app sends: it is not the Worker
 * and proves nothing about server behavior (the node suites and WorkerJourneyTest cover that).
 */
class FakeWorker(private val port: Int = 8787) : AutoCloseable {
    class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    val requests: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    /** While true, every photo PUT is read and then dropped without a reply (a lost reply; OkHttp may retry it once itself). */
    @Volatile var dropUploads = false

    // SO_REUSEADDR: the next test rebinds the port while the last one's connections are still in TIME_WAIT.
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
    }

    @Volatile private var open = true
    private val clients = mutableSetOf<Socket>()
    internal val clientCount: Int get() = synchronized(clients) { clients.size }

    private val acceptor = thread(isDaemon = true, name = "fake-worker") {
        while (open) {
            val socket = try { server.accept() } catch (_: Exception) { return@thread }
            synchronized(clients) {
                if (!open) {
                    socket.close()
                    return@thread
                }
                socket.reuseAddress = true
                clients += socket
            }
            thread(isDaemon = true) {
                // A client that gave up (the app was torn down mid-request) must not take the test process down with it.
                try { serve(socket) } catch (_: java.io.IOException) { }
                finally { synchronized(clients) { clients -= socket } }
            }
        }
    }

    fun uploads() = synchronized(requests) { requests.filter { it.method == "PUT" && "/photos/" in it.path } }

    private fun serve(socket: Socket) {
      socket.use {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val (method, target) = lines.first().split(' ')
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val path = target.substringBefore('?')
        requests += Seen(method, path, headers, body)

        if (method == "PUT" && "/photos/" in path && dropUploads) {
            return
        }
        val (status, json) = route(method, path)
        val bytes = json.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
      }
    }

    private fun route(method: String, path: String): Pair<String, String> {
        val base = "/api/mobile/v1"
        val rest = path.removePrefix(base)
        return when {
            method == "GET" && rest == "/projects" -> "200 OK" to """{"version":1,"items":[$PROJECT],"nextCursor":null}"""
            method == "GET" && rest == "/projects/$PROJECT_ID" -> "200 OK" to """{"version":1,"project":$PROJECT}"""
            method == "GET" && rest == "/projects/$PROJECT_ID/versions" -> "200 OK" to """{"version":1,"items":[]}"""
            method == "GET" && rest == "/projects/$PROJECT_ID/photos" -> "200 OK" to """{"version":1,"items":[]}"""
            method == "PUT" && rest.startsWith("/projects/$PROJECT_ID/photos/") ->
                "200 OK" to """{"version":1,"photo":{"id":"${rest.substringAfterLast('/')}","name":"synthetic-share.jpg","width":64,"height":48,"size":1000,"status":"ready","safetyStatus":"pending"}}"""
            else -> "404 Not Found" to """{"version":1,"error":{"code":"not_found","message":"This item is unavailable."}}"""
        }
    }

    override fun close() {
        val remaining = synchronized(clients) {
            open = false
            clients.toList()
        }
        server.close()
        remaining.forEach { it.close() }
        acceptor.join(5_000)
        check(!acceptor.isAlive) { "Loopback fixture did not stop accepting connections" }
    }

    companion object {
        const val PROJECT_ID = "11111111-1111-4111-8111-000000000001"
        private const val PROJECT = """{"id":"$PROJECT_ID","name":"Example Detailing","purpose":"Mobile detailing in a synthetic city","status":"draft","siteLive":false,"backendLive":false,"createdAt":"2026-10-04T12:00:00.000Z","updatedAt":"2026-10-04T12:00:00.000Z"}"""
    }
}
