package com.whumpo.core.wear

import com.whumpo.core.account.CoreAccountScope
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoreWearTodayTransportConcurrencyTest {
    private val now = 1_800_000_000_000L

    @Test
    fun acceptedAvailableAndLaterTombstoneInOneBatchAreBothAppliedInOrder() {
        var stored: ByteArray? = null
        val storage = store({ stored?.copyOf() }, { stored = it.copyOf(); true })
        val changed = storage.applyBatch(
            listOf(
                update("phone", available(4)),
                update("phone", unavailable(5)),
            ),
            setOf("phone"),
            now,
        )

        assertTrue(changed)
        assertFalse(requireNotNull(stored).let { CoreWearTodaySnapshot.decode(it) }.available)
        assertEquals(5L, CoreWearTodaySnapshot.decode(requireNotNull(stored)).generation)
        assertNull(storage.read(now))
    }

    @Test
    fun readCompareCommitIsSerializedAcrossDifferentStorageInstances() {
        val stored = AtomicReference<ByteArray?>(null)
        val oldWriteEntered = CountDownLatch(1)
        val releaseOldWrite = CountDownLatch(1)
        val first = CoreWearTodaySnapshotStore(
            loadEncoded = { stored.get()?.copyOf() },
            saveEncoded = { bytes ->
                oldWriteEntered.countDown()
                check(releaseOldWrite.await(2, TimeUnit.SECONDS))
                stored.set(bytes.copyOf())
                true
            },
        )
        val second = store({ stored.get()?.copyOf() }, { stored.set(it.copyOf()); true })
        val oldFailure = AtomicReference<Throwable?>(null)
        val newFailure = AtomicReference<Throwable?>(null)
        val old = Thread {
            try {
                first.apply("phone", setOf("phone"), CoreWearTodaySnapshot.encode(available(7)), now)
            } catch (error: Throwable) {
                oldFailure.set(error)
            }
        }
        val tombstone = Thread {
            try {
                second.apply("phone", setOf("phone"), CoreWearTodaySnapshot.encode(unavailable(8)), now)
            } catch (error: Throwable) {
                newFailure.set(error)
            }
        }

        old.start()
        assertTrue(oldWriteEntered.await(2, TimeUnit.SECONDS), "older write did not reach its held commit")
        tombstone.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (tombstone.state != Thread.State.BLOCKED && tombstone.isAlive && System.nanoTime() < deadline) {
            Thread.yield()
        }
        val serializedBehindOlderCommit = tombstone.state == Thread.State.BLOCKED
        releaseOldWrite.countDown()
        old.join(2_000)
        tombstone.join(2_000)

        assertTrue(serializedBehindOlderCommit, "a second storage instance entered while the older commit was held")
        assertFalse(old.isAlive || tombstone.isAlive, "storage operations did not finish")
        assertNull(oldFailure.get())
        assertNull(newFailure.get())
        val final = CoreWearTodaySnapshot.decode(requireNotNull(stored.get()))
        assertEquals(8L, final.generation)
        assertFalse(final.available)
    }

    @Test
    fun authorityChangedDuringTransportPreflightPreventsStaleWrite() = runTest {
        val preflightStarted = CompletableDeferred<Unit>()
        val releasePreflight = CompletableDeferred<Unit>()
        var current = true
        var writes = 0
        val expected = CoreOrganizationSessionController.State.Active(
            epoch = 9,
            session = CoreSession("session", "org", "user", CoreRole.owner, 9_000),
            scope = CoreAccountScope.of(CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test")), "user", "org"),
        )
        val writer = CoreWearTodaySnapshotWriter(
            beforeWrite = {
                preflightStarted.complete(Unit)
                releasePreflight.await()
            },
            isCurrent = { it === expected && current },
            write = { writes++ },
        )
        val publication = async { writer.publish(available(10), expected) }
        preflightStarted.await()
        current = false
        releasePreflight.complete(Unit)

        assertFalse(publication.await())
        assertEquals(0, writes)
    }

    @Test
    fun authorityChangedWhileWriterWasQueuedCannotDispatchStaleSnapshot() = runTest {
        val dispatcher = ManualCoroutineDispatcher()
        var current = true
        var writes = 0
        val expected = CoreOrganizationSessionController.State.Active(
            epoch = 10,
            session = CoreSession("session", "org", "user", CoreRole.owner, 9_000),
            scope = CoreAccountScope.of(CoreOrigin.allowlisted("https://core.example.test", setOf("https://core.example.test")), "user", "org"),
        )
        val writer = CoreWearTodaySnapshotWriter(
            beforeWrite = {},
            isCurrent = { it === expected && current },
            write = { writes++ },
            writeDispatcher = dispatcher,
        )
        val publication = async { writer.publish(available(11), expected) }
        runCurrent()
        assertEquals(1, dispatcher.pendingCount)
        current = false
        dispatcher.runNext()
        runCurrent()

        assertFalse(publication.await())
        assertEquals(0, writes)
    }

    private fun store(load: () -> ByteArray?, save: (ByteArray) -> Boolean) =
        CoreWearTodaySnapshotStore(load, save)

    private fun update(node: String, snapshot: CoreWearTodaySnapshot) =
        CoreWearTodaySnapshotStore.Update(node, CoreWearTodaySnapshot.encode(snapshot))

    private fun available(generation: Long) = CoreWearTodaySnapshot(
        generation = generation,
        generatedAtMillis = now - 1_000,
        publishedAtMillis = now - 500,
        expiresAtMillis = now + 4 * 60_000,
        available = true,
        approvals = 3,
    )

    private fun unavailable(generation: Long) = CoreWearTodaySnapshot(
        generation = generation,
        generatedAtMillis = now - 100,
        publishedAtMillis = now - 100,
        expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS - 100,
        available = false,
    )

    private class ManualCoroutineDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        val pendingCount: Int get() = queued.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runNext() {
            require(queued.isNotEmpty())
            queued.removeFirst().run()
        }
    }
}
