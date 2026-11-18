package com.whumpo.core.auth

import com.whumpo.core.api.CoreRole

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
    fun pendingHeadingFocusIsOneShotAndRechecksScopeAfterScroll() = runTest {
        var current = true
        var scrolls = 0
        var focuses = 0
        val valid = CorePendingApprovalsFocus { current }
        valid.reveal({ scrolls++ }) { focuses++ }
        valid.reveal({ scrolls++ }) { focuses++ }
        assertEquals(1, scrolls)
        assertEquals(1, focuses)

        val held = CorePendingApprovalsFocus { current }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            held.reveal({
                entered.complete(Unit)
                release.await()
            }) { focuses++ }
        }
        entered.await()
        current = false
        release.complete(Unit)
        pending.await()
        current = true
        held.reveal({ scrolls++ }) { focuses++ }
        assertEquals(1, scrolls)
        assertEquals(1, focuses)
    }

    @Test
    fun draftAndApprovalActionsAdmitOnlyExplicitNavigationForCurrentRoles() = runTest {
        val draft = "com.whumpo.android.local.shortcut.NEW_TASK_DRAFT"
        val approvals = "com.whumpo.android.local.shortcut.PENDING_APPROVALS"
        assertNull(CoreStaticShortcutRequest.receive(draft, 4, 7, 1))
        assertNull(CoreStaticShortcutRequest.receive(approvals, 4, 7, 1))
        assertNull(CoreStaticShortcutRequest.receive(draft, null, 7, 1, CoreRole.owner))
        assertNull(CoreStaticShortcutRequest.receive(approvals, null, 7, 1, CoreRole.owner))
        assertNull(CoreStaticShortcutRequest.receive(approvals + "/approval-a", 4, 7, 1, CoreRole.owner))
        var readinessReads = 0
        for (role in CoreRole.entries) {
            val draftRequest = CoreStaticShortcutRequest.receive(draft, 4, 7, 1, role)
            if (role == CoreRole.viewer) assertNull(draftRequest)
            else assertEquals(CoreStaticShortcut.NewTaskDraft, assertNotNull(draftRequest).destination)
            val request = assertNotNull(CoreStaticShortcutRequest.receive(approvals, 4, 7, 1, role))
            assertEquals(CoreStaticShortcut.PendingApprovals, request.destination)
            assertTrue(request.observeForeground(true, true, 7))
            assertTrue(request.verify({ 4 }, { 7 }, { 1 }) { readinessReads++; true })
            assertFalse(request.verify({ 4 }, { 7 }, { 1 }) { error("A shortcut replayed its readiness read") })
        }
        assertEquals(CoreRole.entries.size, readinessReads)
    }

    @Test
    fun admitsOnlyExactDestinationAlreadyReadyAndConsumesOnce() = runTest {
        assertNull(CoreStaticShortcutRequest.receive(null, 4, 7, 1))
        assertNull(CoreStaticShortcutRequest.receive(today + "/org-a", 4, 7, 1))
        assertNull(CoreStaticShortcutRequest.receive(today, null, 7, 1))
        val request = assertNotNull(CoreStaticShortcutRequest.receive(today, 4, 7, 1))
        assertEquals(CoreStaticShortcut.Today, request.destination)
        assertTrue(request.observeForeground(true, true, 7))
        var reads = 0
        assertTrue(request.verify({ 4 }, { 7 }, { 1 }) { reads++; true })
        assertFalse(request.verify({ 4 }, { 7 }, { 1 }) { reads++; true })
        assertEquals(1, reads)
    }

    @Test
    fun temporaryUnfocusedResumptionDoesNotConsumeTheRequestBeforeGenuineFocus() = runTest {
        val request = assertNotNull(CoreStaticShortcutRequest.receive(today, 4, 7, 1))
        var reads = 0
        assertTrue(request.observeForeground(false, false, 7))
        assertTrue(request.observeForeground(true, false, 8))
        assertFalse(request.verify({ 4 }, { 8 }, { 1 }) { reads++; true })
        assertTrue(request.observeForeground(false, false, 9))
        assertTrue(request.observeForeground(true, false, 10))
        assertTrue(request.observeForeground(true, true, 11))
        assertTrue(request.verify({ 4 }, { 11 }, { 1 }) { reads++; true })
        assertFalse(request.verify({ 4 }, { 11 }, { 1 }) { reads++; true })
        assertEquals(1, reads)
    }

    @Test
    fun focusLossDuringReadInvalidatesEvenIfTheSameWindowRegainsFocus() = runTest {
        val request = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, 7, 1))
        assertTrue(request.observeForeground(true, true, 7))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async {
            request.verify({ 4 }, { 9 }, { 1 }) {
                entered.complete(Unit)
                release.await()
                true
            }
        }
        entered.await()
        assertFalse(request.observeForeground(true, false, 8))
        assertFalse(request.observeForeground(true, true, 9))
        release.complete(Unit)
        assertFalse(pending.await())
        assertFalse(request.verify({ 4 }, { 9 }, { 1 }) { error("Focus loss replayed a shortcut") })

        val expired = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, 7, 1))
        assertFalse(expired.observeForeground(false, false, 5_007))
        assertFalse(expired.observeForeground(true, true, 5_008))
        assertFalse(expired.verify({ 4 }, { 5_008 }, { 1 }) { error("Expired request ran on first focus") })
    }

    @Test
    fun lateReadyReadDoesNotReplaceAChoiceOrOwnershipReviewInTheSameEpoch() = runTest {
        val epoch = 4L
        for (changesRoute in listOf(true, false)) {
            val request = assertNotNull(CoreStaticShortcutRequest.receive(today, epoch, 7, 1))
            assertTrue(request.observeForeground(true, true, 7))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var hubRoute = true
            var ownershipReview = false
            var lifetime = 1L
            val pending = async {
                request.verify({ epoch.takeIf { hubRoute && !ownershipReview } }, { 7 }, { lifetime }) {
                    entered.complete(Unit)
                    release.await()
                    true
                }
            }
            entered.await()
            if (changesRoute) hubRoute = false else ownershipReview = true
            assertFalse(hubRoute && !ownershipReview)
            lifetime++
            // Both competing surfaces are gone by the time the server replies.
            hubRoute = true
            ownershipReview = false
            release.complete(Unit)
            assertFalse(pending.await())
            assertFalse(request.verify({ epoch }, { 7 }, { 2 }) { error("A stale request replayed after the hub returned") })
        }
    }

    @Test
    fun lateReadCannotOpenAcrossScopeExpiryOrLossOfReadyForeground() = runTest {
        suspend fun verifyAfter(change: () -> Unit, epoch: () -> Long?, time: () -> Long) {
            val request = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, 7, 1))
            assertEquals(CoreStaticShortcut.Work, request.destination)
            assertTrue(request.observeForeground(true, true, 7))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = async {
                request.verify(epoch, time, { 1 }) {
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
        val alreadyExpired = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, 7, 1))
        assertFalse(alreadyExpired.verify({ epoch }, { time }, { 1 }) { error("Expired shortcut read the workspace") })
        val notReady = assertNotNull(CoreStaticShortcutRequest.receive(work, 4, time, 1))
        assertTrue(notReady.observeForeground(true, true, time))
        assertFalse(notReady.verify({ epoch }, { time }, { 1 }) { false })
    }
}
