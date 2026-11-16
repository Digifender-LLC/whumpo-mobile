package com.whumpo.core.auth

import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOwnershipDesignation
import com.whumpo.core.api.CoreOwnershipStatus
import com.whumpo.core.api.CoreOwnershipTransfer
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreTransferStatus
import com.whumpo.core.api.OwnershipRoute
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@ConsistentCopyVisibility
data class CoreOwnershipCandidate internal constructor(val userId: String, val membershipVersion: Long)

enum class CoreOwnershipAction { Claim, Offer, Accept, Cancel }

@ConsistentCopyVisibility
data class CoreOwnershipSnapshot internal constructor(
    val designation: CoreOwnershipDesignation,
    val transfer: CoreOwnershipTransfer?,
)

class CoreOwnershipReview internal constructor(
    val action: CoreOwnershipAction,
    internal val snapshot: CoreOwnershipSnapshot,
    val target: CoreOwnershipCandidate?,
) {
    val designation get() = snapshot.designation
    val transfer get() = snapshot.transfer.takeIf { action == CoreOwnershipAction.Accept || action == CoreOwnershipAction.Cancel }
}

internal class CoreOwnershipAttempt(
    val active: CoreOrganizationSessionController.State.Active,
    val review: CoreOwnershipReview,
    val key: String = UUID.randomUUID().toString(),
) {
    val route = when (review.action) {
        CoreOwnershipAction.Claim -> OwnershipRoute.Claim
        CoreOwnershipAction.Offer -> OwnershipRoute.Offer
        CoreOwnershipAction.Accept -> OwnershipRoute.Accept(checkNotNull(review.transfer).transferId)
        CoreOwnershipAction.Cancel -> OwnershipRoute.Cancel(checkNotNull(review.transfer).transferId)
    }
    val body = review.target?.let { target ->
        buildJsonObject {
            put("targetUserId", target.userId)
            put("targetMembershipVersion", target.membershipVersion)
        }.toString()
    } ?: "{}"
    val version = when (review.action) {
        CoreOwnershipAction.Offer -> review.designation.version
        CoreOwnershipAction.Accept -> review.transfer?.version
        else -> null
    }
}

enum class CoreOwnershipNotice { Unavailable, ReviewChanged, SignInAgain, Forbidden, Rejected, Unconfirmed, Confirmed }

data class CoreOwnershipState(
    val snapshot: CoreOwnershipSnapshot? = null,
    val candidates: List<CoreOwnershipCandidate>? = null,
    val review: CoreOwnershipReview? = null,
    val retry: Boolean = false,
    val busy: Boolean = false,
    val notice: CoreOwnershipNotice? = null,
)

/** Keeps review and retry state for one verified workspace session. */
class CoreOwnershipController(
    private val session: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(CoreOwnershipState())
    val state = mutableState.asStateFlow()
    private var attempt: CoreOwnershipAttempt? = null
    private var previouslySeenOffer: CoreOwnershipTransfer? = null

    private val actor get() = active.session.userId

    fun canReview(action: CoreOwnershipAction, target: CoreOwnershipCandidate? = null): Boolean {
        val current = state.value
        val snapshot = current.snapshot ?: return false
        return !current.busy && !current.retry && eligible(action, snapshot, target)
    }

    private fun eligible(action: CoreOwnershipAction, snapshot: CoreOwnershipSnapshot, target: CoreOwnershipCandidate?): Boolean {
        val designation = snapshot.designation
        val transfer = snapshot.transfer
        val owner = active.session.role == CoreRole.owner
        val usableTransfer = transfer != null && transfer.version == 1L && ownershipTime(transfer.expiresAt) > now()
        return when (action) {
            CoreOwnershipAction.Claim -> owner && designation.status == CoreOwnershipStatus.undesignated
            CoreOwnershipAction.Offer -> owner && designation.primaryOwnerUserId == actor &&
                designation.pendingTransfer == null && target != null && target in state.value.candidates.orEmpty()
            CoreOwnershipAction.Accept -> owner && usableTransfer && transfer.status == CoreTransferStatus.pending &&
                transfer.targetUserId == actor && transfer == designation.pendingTransfer
            CoreOwnershipAction.Cancel -> usableTransfer &&
                transfer.status in setOf(CoreTransferStatus.pending, CoreTransferStatus.invalidated) &&
                actor in listOf(transfer.sourceUserId, transfer.targetUserId)
        }
    }

    fun review(action: CoreOwnershipAction, target: CoreOwnershipCandidate? = null) = update { current ->
        if (canReview(action, target)) {
            current.copy(review = CoreOwnershipReview(action, checkNotNull(current.snapshot), target), notice = null)
        } else current
    }

    fun dismissReview() = update { if (it.busy) it else it.copy(review = null) }

    suspend fun refresh() {
        if (!start()) return
        try {
            readCurrent()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            update { it.copy(snapshot = null, review = null, candidates = null, notice = CoreOwnershipNotice.Unavailable) }
        } finally {
            update { it.copy(busy = false) }
        }
    }

    suspend fun loadCandidates() {
        if (!start {
                val designation = it.snapshot?.designation
                !it.retry && designation?.primaryOwnerUserId == actor && designation.pendingTransfer == null
            }) return
        try {
            val candidates = session.ownershipCandidates(active)
            update { current ->
                val target = current.review?.target
                val changed = target != null && target !in candidates
                current.copy(candidates = candidates, review = current.review.takeUnless { changed },
                    notice = CoreOwnershipNotice.ReviewChanged.takeIf { changed })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            update { it.copy(candidates = null, review = null, notice = CoreOwnershipNotice.Unavailable) }
        } finally {
            update { it.copy(busy = false) }
        }
    }

    suspend fun submit() {
        var prepared: CoreOwnershipAttempt? = null
        update { current ->
            val review = current.review
            if (current.busy || current.retry || review == null) return@update current
            if (current.snapshot != review.snapshot || !eligible(review.action, review.snapshot, review.target)) {
                return@update current.copy(review = null, notice = CoreOwnershipNotice.ReviewChanged)
            }
            prepared = CoreOwnershipAttempt(active, review)
            attempt = prepared
            current.copy(busy = true, review = null, notice = null)
        }
        prepared?.let { send(it, retry = false) }
    }

    suspend fun retry() {
        if (!start { it.retry }) return
        send(checkNotNull(attempt), retry = true)
    }

    private suspend fun send(sent: CoreOwnershipAttempt, retry: Boolean) {
        try {
            session.writeOwnership(sent)
            update {
                attempt = null
                it.copy(retry = false, snapshot = null, candidates = null, notice = CoreOwnershipNotice.Confirmed)
            }
            try {
                readCurrent(keepNotice = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The verified write receipt is sufficient to confirm the request. Refresh can be retried separately.
            }
        } catch (error: CancellationException) {
            retainIfPending()
            throw error
        } catch (error: CoreHttpException) {
            if (retry || error.status !in 400..499) {
                retainIfPending()
            } else {
                val notice = when {
                    error.status == 409 -> CoreOwnershipNotice.ReviewChanged
                    error.status == 403 && sent.review.action != CoreOwnershipAction.Cancel -> CoreOwnershipNotice.SignInAgain
                    error.status == 403 -> CoreOwnershipNotice.Forbidden
                    else -> CoreOwnershipNotice.Rejected
                }
                update {
                    attempt = null
                    it.copy(retry = false, snapshot = null, candidates = null, notice = notice)
                }
                if (error.status == 409) {
                    try {
                        readCurrent(keepNotice = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The prior review remains cleared when the current state cannot be loaded.
                    }
                }
            }
        } catch (_: Exception) {
            retainIfPending()
        } finally {
            update { it.copy(busy = false) }
        }
    }

    private fun retainIfPending() = update {
        if (attempt == null) it else it.copy(retry = true, notice = CoreOwnershipNotice.Unconfirmed)
    }

    private suspend fun readCurrent(keepNotice: Boolean = false) {
        val designation = session.ownership(active)
        val known = previouslySeenOffer
        val transfer = designation.pendingTransfer ?: known?.let {
            try {
                session.ownershipTransfer(active, it)
            } catch (error: CoreHttpException) {
                if (error.status != 404 && error.status != 403) throw error
                null
            }
        }
        val next = CoreOwnershipSnapshot(designation, transfer)
        update { current ->
            previouslySeenOffer = transfer?.takeIf {
                it.version == 1L && actor in listOf(it.sourceUserId, it.targetUserId)
            }
            val changed = current.review?.snapshot?.let { it != next } == true
            current.copy(
                snapshot = next,
                candidates = current.candidates.takeIf { current.snapshot == next },
                review = current.review.takeUnless { changed },
                notice = when {
                    changed -> CoreOwnershipNotice.ReviewChanged
                    current.retry -> CoreOwnershipNotice.Unconfirmed
                    keepNotice -> current.notice
                    else -> null
                },
            )
        }
    }

    private fun start(allowed: (CoreOwnershipState) -> Boolean = { true }): Boolean {
        var started = false
        update {
            if (it.busy || !allowed(it)) it else {
                started = true
                it.copy(busy = true)
            }
        }
        return started
    }

    private fun update(transform: (CoreOwnershipState) -> CoreOwnershipState) {
        session.commitIfCurrent(active.epoch) {
            if (session.state.value === active) mutableState.value = transform(mutableState.value)
        }
    }
}
