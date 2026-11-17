package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreWorkLink
import com.whumpo.core.api.CoreWorkTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

suspend fun CoreOrganizationSessionController.linkedWorkTask(
    active: CoreOrganizationSessionController.State.Active,
    link: CoreWorkLink,
    whileCurrent: () -> Boolean,
): CoreWorkTask {
    val bookmark = readWorkLink(active, whileCurrent) { client, token ->
        client.workLinkHistory(token, link.taskId, link.creationEventId)
    }
    if (bookmark != null && (bookmark.id == link.creationEventId || bookmark.version <= 1)) throw CoreProtocolException()

    val creation = readWorkLink(active, whileCurrent) { client, token ->
        client.workLinkHistory(token, link.taskId, cursor = null)
    }
    if (creation == null || !creation.isCreation || creation.id != link.creationEventId) throw CoreProtocolException()

    return readWorkLink(active, whileCurrent) { client, token ->
        client.linkedWorkTask(token, link.taskId)
    }.also { current ->
        if (current.version < (bookmark?.version ?: creation.version)) throw CoreProtocolException()
    }
}

private suspend fun <T> CoreOrganizationSessionController.readWorkLink(
    active: CoreOrganizationSessionController.State.Active,
    whileCurrent: () -> Boolean,
    read: suspend (CoreApiClient, String) -> T,
): T = requestForActiveSession(active) { client, token ->
    requireCurrentWorkLink(whileCurrent)
    try {
        read(client, token).also { requireCurrentWorkLink(whileCurrent) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // A closed view's late denial cannot mutate the session that was left open.
        requireCurrentWorkLink(whileCurrent)
        throw error
    }
}

private suspend fun requireCurrentWorkLink(whileCurrent: () -> Boolean) {
    currentCoroutineContext().ensureActive()
    if (!whileCurrent()) throw CancellationException("Work link closed")
}
