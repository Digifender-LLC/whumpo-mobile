package com.whumpo.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CoreHostnameStatus(internal val wire: String) {
    Reserved("reserved"),
    ServingEligible("serving_eligible"),
    ReproofRequired("reproof_required"),
    Detaching("detaching"),
}

data class CoreHostnameAttachment(
    val id: String,
    val hostname: String,
    val generation: Long,
    val revision: Long,
    val status: CoreHostnameStatus,
)

internal data class CoreHostnamePublication(
    val targetId: String,
    val targetVersion: Long,
    val effectId: String,
    val artifactDigest: String,
    val pageManifestDigest: String?,
)

internal data class CoreHostnameApproval(val witness: JsonObject, val digest: String) {
    fun encode(base: CoreHostnameAttachment?) = buildJsonObject {
        put("witness", witness)
        put("reviewDigest", digest)
        if (base != null) put("expectedRevision", base.revision)
    }.toString()
}

internal data class CoreHostnameReviewResponse(val current: CoreHostnameAttachment?, val approval: CoreHostnameApproval)

internal data class CoreHostnameReceipt(
    val routeId: String,
    val hostname: String,
    val generation: Long,
    val revision: Long,
) {
    fun verifyReadback(current: CoreHostnameAttachment?) {
        if (current?.id == routeId && (current.hostname != hostname ||
            current.generation < generation || current.revision < revision)
        ) throw CoreProtocolException()
    }
}

internal sealed interface CoreHostnameCommand {
    val suffix: String
    val base: CoreHostnameAttachment?
    val hostname: String
    val body: String

    class Attach(override val hostname: String, approval: CoreHostnameApproval) : CoreHostnameCommand {
        override val suffix = "/attach"
        override val base = null
        override val body = approval.encode(null)
    }

    class Resume(override val base: CoreHostnameAttachment, approval: CoreHostnameApproval) : CoreHostnameCommand {
        override val suffix = "/${base.id}/resume"
        override val hostname = base.hostname
        override val body = approval.encode(base)
    }

    class Disable(override val base: CoreHostnameAttachment) : CoreHostnameCommand {
        override val suffix = "/${base.id}/disable"
        override val hostname = base.hostname
        override val body = buildJsonObject { put("expectedRevision", base.revision) }.toString()
    }
}

internal fun decodeHostnameStatus(body: String, orgId: String, projectId: String): CoreHostnameAttachment? =
    hostnameResponse(body, orgId, projectId) { _, current -> current }

internal fun decodeHostnameReview(
    body: String,
    orgId: String,
    projectId: String,
    claim: CoreDomainClaim,
    publication: CoreHostnamePublication,
): CoreHostnameReviewResponse = hostnameResponse(body, orgId, projectId, "review") { root, current ->
    val review = root.field("review").objectValue().closed("witness", "reviewDigest")
    val witness = review.field("witness").objectValue().closed(
        "hostname", "claimId", "claimGeneration", "checkId", "targetId", "targetVersion", "effectId",
        "artifactDigest", "pageManifestDigest",
    )
    witness.field("checkId").hostnameIdentifier()
    val seenPublication = CoreHostnamePublication(
        witness.field("targetId").hostnameIdentifier(), witness.field("targetVersion").revision(),
        witness.field("effectId").hostnameIdentifier(), witness.field("artifactDigest").hostnameDigest(),
        witness.field("pageManifestDigest").unlessNull { it.hostnameDigest() },
    )
    if (witness.field("hostname").canonicalHostname() != claim.hostname ||
        witness.field("claimId").hostnameIdentifier() != claim.id ||
        witness.field("claimGeneration").revision() != claim.generation || seenPublication != publication
    ) throw CoreProtocolException()
    CoreHostnameReviewResponse(current, CoreHostnameApproval(witness, review.field("reviewDigest").hostnameDigest()))
}

internal fun decodeHostnameReceipt(
    body: String,
    orgId: String,
    projectId: String,
    command: CoreHostnameCommand,
): CoreHostnameReceipt = hostnameResponse(body, orgId, projectId, "receipt") { root, current ->
    val row = root.field("receipt").objectValue().closed("id", "routeId", "generation", "revision", "phase")
    row.field("id").hostnameIdentifier()
    val id = row.field("routeId").hostnameIdentifier()
    val generation = row.field("generation").revision()
    val revision = row.field("revision").revision()
    val base = command.base
    val phase = if (command is CoreHostnameCommand.Disable) CoreHostnameStatus.Detaching else CoreHostnameStatus.Reserved
    if (row.field("phase") != JsonPrimitive(phase.wire) || generation != (base?.generation ?: 0) + 1 ||
        revision != (base?.revision ?: 0) + 1 || (base != null && id != base.id)
    ) throw CoreProtocolException()
    CoreHostnameReceipt(id, command.hostname, generation, revision).also { it.verifyReadback(current) }
}

private inline fun <T> hostnameResponse(
    body: String,
    orgId: String,
    projectId: String,
    vararg fields: String,
    read: (JsonObject, CoreHostnameAttachment?) -> T,
): T = try {
    val row = Json.parseToJsonElement(body).objectValue().closed("schemaVersion", "orgId", "projectId", "attachment", *fields)
    if (row.field("schemaVersion") != JsonPrimitive(1) || row.field("orgId").hostnameIdentifier() != orgId ||
        row.field("projectId").hostnameIdentifier() != projectId
    ) throw CoreProtocolException()
    read(row, row.field("attachment").unlessNull(::hostnameAttachment))
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}

private fun hostnameAttachment(value: JsonElement): CoreHostnameAttachment {
    val row = value.objectValue().closed("id", "hostname", "generation", "revision", "status")
    val generation = row.field("generation").revision()
    val revision = row.field("revision").revision()
    if (generation > revision) throw CoreProtocolException()
    val status = CoreHostnameStatus.entries.firstOrNull { row.field("status") == JsonPrimitive(it.wire) }
        ?: throw CoreProtocolException()
    return CoreHostnameAttachment(row.field("id").hostnameIdentifier(), row.field("hostname").canonicalHostname(),
        generation, revision, status)
}

private fun JsonElement.hostnameIdentifier() =
    text(128).takeIf(coreIdentifier::matches) ?: throw CoreProtocolException()

private fun JsonElement.hostnameDigest() =
    text(64).takeIf(coreDigest::matches) ?: throw CoreProtocolException()

private fun JsonElement.canonicalHostname() =
    text(245).takeIf { normalizeCoreDomainHostname(it) == it } ?: throw CoreProtocolException()
