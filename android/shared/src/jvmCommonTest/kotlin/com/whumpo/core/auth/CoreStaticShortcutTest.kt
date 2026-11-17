package com.whumpo.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreStaticShortcutTest {
    private val today = "com.whumpo.android.local.shortcut.TODAY"
    private val work = "com.whumpo.android.local.shortcut.WORK"

    @Test
    fun admitsOnlyExactDestinationAlreadyReadyAndConsumesOnce() = runTest {
        assertNull(CoreStaticShortcutRequest.receive(null, 4, 7))
        assertNull(CoreStaticShortcutRequest.receive(today + "/org-a", 4, 7))
        assertNull(CoreStaticShortcutRequest.receive(today, null, 7))
        val request = assertNotNull(CoreStaticShortcutRequest.receive(today, 4, 7))
        assertEquals(CoreStaticShortcut.Today, request.destination)
        var reads = 0
        assertTrue(request.verify({ 4 }, { 7 }) { reads++; true })
        assertFalse(request.verify({ 4 }, { 7 }) { reads++; true })
        assertEquals(1, reads)
    }

    @Test
    fun lateReadyReadDoesNotReplaceAChoiceOrOwnershipReviewInTheSameEpoch() = runTest {
        val epoch = 4L
        for (changesRoute in listOf(true, false)) {
            val request = assertNotNull(CoreStaticShortcutRequest.receive(today, epoch, 7))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var hubRoute = true
            var ownershipReview = false
            val pending = async {
                request.verify({ epoch.takeIf { hubRoute && !ownershipReview } }, { 7 }) {
                    entered.complete(Unit)
                    release.await()
                    true
                }
            }
            entered.await()
            if (changesRoute) hubRoute = false else ownershipReview = true
            release.complete(Unit)
            assertFalse(pending.await())
            assertFalse(request.verify({ epoch }, { 7 }) { error("A stale request replayed after the hub returned") })
        }
    }

    @Test
    fun lateReadCannotOpenAcrossScopeExpiryOrLossOfReadyForeground() = runTest {
        suspend fun verifyAfter(change: () -> Unit, epoch: () -> Long?, time: () -> Long) {
            val request = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, 7))
            assertEquals(CoreStaticShortcut.Work, request.destination)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = async {
                request.verify(epoch, time) {
                    entered.complete(Unit)
                    release.await()
                    true
                }
            }
            entered.await()
            change()
            release.complete(Unit)
            assertFalse(pending.await())
        }
        var epoch: Long? = 4
        var time = 7L
        verifyAfter({ epoch = 5 }, { epoch }, { time })
        epoch = 4
        verifyAfter({ epoch = null }, { epoch }, { time })
        epoch = 4
        verifyAfter({ time += 5_000 }, { epoch }, { time })
        val alreadyExpired = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, 7))
        assertFalse(alreadyExpired.verify({ epoch }, { time }) { error("Expired shortcut read the workspace") })
        val notReady = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, time))
        assertFalse(notReady.verify({ epoch }, { time }) { false })
    }
}
