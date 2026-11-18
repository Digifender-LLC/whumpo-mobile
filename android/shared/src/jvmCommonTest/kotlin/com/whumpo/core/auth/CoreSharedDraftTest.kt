package com.whumpo.core.auth

import com.whumpo.core.api.CoreRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreSharedDraftTest {
    private val item = checkNotNull(CoreSharedDraftItem.parse("Synthetic follow-up"))

    @Test
    fun acceptsOneBoundedPlainTextOrHttpsLinkWithoutChangingContentIntoMetadata() {
        val text = assertNotNull(CoreSharedDraftItem.parse(StringBuilder("Notes: follow up\nReview the brief")))
        assertEquals("Notes: follow up\nReview the brief", text.text)
        assertFalse(text.isLink)
        val link = assertNotNull(CoreSharedDraftItem.parse("https://example.test/notes?id=12#here"))
        assertTrue(link.isLink)
        assertEquals("https://example.test/notes?id=12#here", link.text)
        assertNotNull(CoreSharedDraftItem.parse("x".repeat(4_000)))

        listOf("", " \n ", "a\u0000b", "a\u001bb", "\uD800", "x".repeat(4_001),
            "https:brief", "https:///notes", "https://user:secret@example.test", "https://example.test:65536",
            "http://example.test", "content://provider/item", "file:///documents/one", "javascript:alert(0)",
        ).forEach { assertNull(CoreSharedDraftItem.parse(it)) }
    }

    @Test
    fun signedOutUnreadyAndViewerDeliveriesCannotBeReboundLater() {
        assertNull(CoreSharedDraftRoute().receive(item, null, CoreRole.owner, 100))
        assertNull(CoreSharedDraftRoute().receive(item, 3, null, 100))
        assertNull(CoreSharedDraftRoute().receive(item, 3, CoreRole.viewer, 100))
    }

    @Test
    fun stoppedWarmDeliveryWaitsForRealFocusAndAcceptsOnceInExactEpoch() {
        val request = assertNotNull(CoreSharedDraftRoute().receive(item, 3, CoreRole.operator, 100))
        assertTrue(request.observeForeground(false, false, 100))
        assertTrue(request.observeForeground(true, false, 110))
        assertFalse(request.canReview(3, 110))
        assertTrue(request.observeForeground(false, false, 120))
        assertTrue(request.observeForeground(true, true, 130))
        assertFalse(request.canReview(4, 130))
        assertTrue(request.canReview(3, 130))
        assertEquals(item.text, request.accept(3, 130))
        assertNull(request.accept(3, 130))
    }

    @Test
    fun lostFocusAndExpiredDeliveryCannotBeReopened() {
        val interrupted = assertNotNull(CoreSharedDraftRoute().receive(item, 3, CoreRole.owner, 100))
        assertTrue(interrupted.observeForeground(true, true, 101))
        assertFalse(interrupted.observeForeground(true, false, 102))
        assertFalse(interrupted.observeForeground(true, true, 103))
        assertNull(interrupted.accept(3, 103))

        val dismissed = assertNotNull(CoreSharedDraftRoute().receive(item, 3, CoreRole.owner, 100))
        assertTrue(dismissed.observeForeground(true, true, 101))
        dismissed.cancel()
        assertNull(dismissed.accept(3, 102))

        val expired = assertNotNull(CoreSharedDraftRoute().receive(item, 3, CoreRole.admin, 100))
        assertTrue(expired.observeForeground(true, true, 100))
        assertTrue(expired.canReview(3, 60_099))
        assertFalse(expired.canReview(3, 60_100))
        assertNull(expired.accept(3, 60_100))
    }
    @Test
    fun leavingThenReturningToTheSameRouteCannotReopenADispatchedOffer() {
        val route = CoreSharedDraftRoute()
        val request = assertNotNull(route.receive(item, 3, CoreRole.owner, 100))
        assertTrue(request.matches(3))
        route.interrupt() // A Work subroute opens.
        route.interrupt() // The Work list becomes ready again.
        assertFalse(request.matches(3))
        assertFalse(request.observeForeground(true, true, 103))
        assertNull(request.accept(3, 103))

        val later = assertNotNull(route.receive(item, 3, CoreRole.owner, 104))
        assertTrue(later.observeForeground(true, true, 105))
        assertEquals(item.text, later.accept(3, 105))
    }

    @Test
    fun ownershipInteractionBeforeUseInvalidatesEvenIfReviewImmediatelyCloses() {
        val route = CoreSharedDraftRoute()
        val request = assertNotNull(route.receive(item, 3, CoreRole.owner, 100))
        assertTrue(request.observeForeground(true, true, 101))
        assertTrue(request.canReview(3, 101))
        route.interrupt() // The synchronous ownership action precedes its state publication.
        assertFalse(request.matches(3))
        assertFalse(request.canReview(3, 102))
        assertNull(request.accept(3, 102))
    }

}
