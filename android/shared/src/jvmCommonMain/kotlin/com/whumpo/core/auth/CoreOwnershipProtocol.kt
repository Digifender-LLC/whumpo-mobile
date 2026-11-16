package com.whumpo.core.auth

import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreOwnershipDesignation
import com.whumpo.core.api.CoreOwnershipProvenance
import com.whumpo.core.api.CoreOwnershipStatus
import com.whumpo.core.api.CoreOwnershipTransfer
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreTransferStatus
import com.whumpo.core.api.coreIdentifier
import com.whumpo.core.api.coreTimestamp
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal fun ownershipTime(value: String): Long {
    if (!coreTimestamp.matches(value) || value.take(4).toInt() < 1970) throw CoreProtocolException()
    try {
        return LocalDateTime.parse(value.dropLast(1)).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: DateTimeParseException) {
        throw CoreProtocolException()
    }
}

internal fun CoreOwnershipTransfer.verify() {
    val created = ownershipTime(createdAt)
    val expires = ownershipTime(expiresAt)
    val closing = when (status) {
        CoreTransferStatus.pending, CoreTransferStatus.expired, CoreTransferStatus.invalidated ->
            version == 1L && acceptedAt == null && cancelledAt == null
        CoreTransferStatus.accepted ->
            version == 2L && cancelledAt == null && acceptedAt?.let { ownershipTime(it) in created until expires } == true
        CoreTransferStatus.cancelled ->
            version == 2L && acceptedAt == null && cancelledAt?.let { ownershipTime(it) in created until expires } == true
    }
    if (!closing || sourceUserId == targetUserId || expires - created !in 1..86_400_000 ||
        designationVersion !in 1..CORE_MAX_VERSION || targetMembershipVersion !in 1..CORE_MAX_VERSION ||
        listOf(transferId, orgId, sourceUserId, targetUserId).any { !coreIdentifier.matches(it) }
    ) throw CoreProtocolException()
}

internal fun CoreOwnershipTransfer.matchesOffer(offer: CoreOwnershipTransfer): Boolean =
    copy(version = offer.version, status = offer.status, acceptedAt = offer.acceptedAt, cancelledAt = offer.cancelledAt) == offer

internal fun CoreOwnershipDesignation.verify() {
    if (!coreIdentifier.matches(orgId)) throw CoreProtocolException()
    when (status) {
        CoreOwnershipStatus.undesignated -> {
            if (primaryOwnerUserId != null || version != null || provenance != null || pendingTransfer != null) {
                throw CoreProtocolException()
            }
        }
        CoreOwnershipStatus.designated -> {
            val minimumVersion = if (provenance == CoreOwnershipProvenance.transfer) 2L else 1L
            val maximumVersion = if (provenance == CoreOwnershipProvenance.transfer) CORE_MAX_VERSION else 1L
            if (primaryOwnerUserId?.let(coreIdentifier::matches) != true ||
                version == null || version !in minimumVersion..maximumVersion || provenance == null
            ) throw CoreProtocolException()
            pendingTransfer?.let { transfer ->
                transfer.verify()
                if (transfer.status != CoreTransferStatus.pending || transfer.orgId != orgId ||
                    transfer.sourceUserId != primaryOwnerUserId || transfer.designationVersion != version
                ) throw CoreProtocolException()
            }
        }
    }
}
