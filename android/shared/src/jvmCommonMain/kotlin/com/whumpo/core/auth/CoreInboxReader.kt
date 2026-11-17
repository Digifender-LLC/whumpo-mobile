package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreInboxApprovalStatus
import com.whumpo.core.api.CoreInboxCategory
import com.whumpo.core.api.CoreInboxDetail
import com.whumpo.core.api.CoreInboxItem
import com.whumpo.core.api.CoreInboxResolution
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class CoreInboxPage internal constructor(
    internal val session: CoreOrganizationSessionController.State.Active,
    private val continuation: String?,
    val items: List<CoreInboxItem>,
) {
    val hasMore: Boolean get() = continuation != null
    internal fun nextFor(active: CoreOrganizationSessionController.State.Active): String {
        if (session !== active) throw CancellationException("Inbox scope changed")
        return continuation ?: throw CoreProtocolException()
    }

    internal fun withAcknowledgment(item: CoreInboxItem, at: String): CoreInboxPage =
        CoreInboxPage(session, continuation, items.map { if (it === item) it.copy(acknowledgedAt = at) else it })

    internal fun requireItem(active: CoreOrganizationSessionController.State.Active, item: CoreInboxItem) {
        if (session !== active) throw CancellationException("Inbox scope changed")
        if (items.none { it === item } ||
            (item.category == CoreInboxCategory.Approval && active.session.role != CoreRole.owner)
        ) throw CoreProtocolException()
    }
}

suspend fun CoreOrganizationSessionController.inboxPage(
    active: CoreOrganizationSessionController.State.Active,
    after: CoreInboxPage? = null,
    whileCurrent: () -> Boolean,
): CoreInboxPage {
    val cursor = after?.nextFor(active)
    val wire = requestInbox(active, whileCurrent) { client, token -> client.inbox(token, cursor) }
    if ((cursor != null && wire.nextCursor == cursor) ||
        (active.session.role != CoreRole.owner && wire.items.any { it.category == CoreInboxCategory.Approval })
    ) throw CoreProtocolException()
    return CoreInboxPage(active, wire.nextCursor, wire.items)
}

suspend fun CoreOrganizationSessionController.openInboxItem(
    active: CoreOrganizationSessionController.State.Active,
    page: CoreInboxPage,
    item: CoreInboxItem,
    whileCurrent: () -> Boolean,
): CoreInboxDetail {
    page.requireItem(active, item)
    val source = requestInbox(active, whileCurrent) { client, token -> client.resolveInboxItem(token, item) }
    return when (source) {
        is CoreInboxResolution.Approval -> {
            when (source.status) {
                CoreInboxApprovalStatus.Actionable, CoreInboxApprovalStatus.ReauthenticationRequired -> Unit
                else -> throw CoreProtocolException()
            }
            val detail = requestInbox(active, whileCurrent) { client, token -> client.inboxApproval(token, source) }
            // Approval detail is organization-scoped. Recheck the notification's narrower owner audience and status.
            val current = requestInbox(active, whileCurrent) { client, token -> client.resolveInboxItem(token, item) }
            if (current != source) throw CoreProtocolException()
            detail
        }
        is CoreInboxResolution.Confirmed -> requestInbox(active, whileCurrent) { client, token ->
            client.inboxReceipt(token, source, active.session.orgId)
        }
    }
}

suspend fun CoreOrganizationSessionController.acknowledgeInboxItem(
    active: CoreOrganizationSessionController.State.Active,
    page: CoreInboxPage,
    item: CoreInboxItem,
    whileCurrent: () -> Boolean,
): CoreInboxPage {
    page.requireItem(active, item)
    val acknowledgedAt = requestInbox(active, whileCurrent) { client, token -> client.acknowledgeInboxItem(token, item) }
    return page.withAcknowledgment(item, acknowledgedAt)
}

private suspend fun <T> CoreOrganizationSessionController.requestInbox(
    active: CoreOrganizationSessionController.State.Active,
    whileCurrent: () -> Boolean,
    request: suspend (CoreApiClient, String) -> T,
): T = requestForActiveSession(active) { client, token ->
    requireInboxCurrent(whileCurrent)
    try {
        request(client, token).also { requireInboxCurrent(whileCurrent) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        requireInboxCurrent(whileCurrent)
        throw error
    }
}

private suspend fun requireInboxCurrent(whileCurrent: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    if (!whileCurrent()) throw CancellationException("Inbox closed")
}
