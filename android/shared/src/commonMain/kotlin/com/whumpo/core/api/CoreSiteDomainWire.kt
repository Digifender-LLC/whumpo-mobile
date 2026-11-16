package com.whumpo.core.api

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CoreDomainStatus(val code: String) {
    AwaitingDns("awaiting_dns"),
    OwnershipVerified("ownership_verified"),
    ProofMissing("proof_missing"),
    ResolverUnknown("resolver_unknown"),
    Expired("expired"),
}

data class CoreDomainTxt(val name: String, val value: String)

data class CoreDomainClaim(
    val id: String,
    val projectId: String,
    val hostname: String,
    val generation: Long,
    val status: CoreDomainStatus,
    val record: CoreDomainTxt,
    val expiresAt: Instant,
    val checkedAt: Instant?,
    val evidenceValidUntil: Instant?,
) {
    fun statusAt(now: Instant): CoreDomainStatus = when {
        expiresAt <= now -> CoreDomainStatus.Expired
        status == CoreDomainStatus.OwnershipVerified && evidenceValidUntil?.let { it > now } != true ->
            CoreDomainStatus.AwaitingDns
        else -> status
    }

    internal fun follows(previous: CoreDomainClaim): Boolean =
        id == previous.id && projectId == previous.projectId && hostname == previous.hostname &&
            (generation > previous.generation || generation == previous.generation &&
                record == previous.record && expiresAt == previous.expiresAt &&
                (previous.checkedAt == null || checkedAt?.let { it >= previous.checkedAt } == true))
}

internal enum class CoreDomainOperationKind(val segment: String) {
    Prepare(""), Check("check"), Renew("renew"),
}

internal sealed interface CoreDomainCommand {
    val kind: CoreDomainOperationKind
    val expectedGeneration: Long
    val expectedHostname: String
    val claim: CoreDomainClaim?
    fun encode(): String

    data class Prepare(val hostname: String) : CoreDomainCommand {
        override val kind = CoreDomainOperationKind.Prepare
        override val expectedGeneration = 1L
        override val expectedHostname get() = hostname
        override val claim = null
        override fun encode() = buildJsonObject { put("hostname", hostname) }.toString()
    }

    data class Existing(override val kind: CoreDomainOperationKind, override val claim: CoreDomainClaim) : CoreDomainCommand {
        init {
            require(kind != CoreDomainOperationKind.Prepare)
            require(kind != CoreDomainOperationKind.Renew || claim.generation < CORE_MAX_VERSION)
        }
        override val expectedGeneration = claim.generation + if (kind == CoreDomainOperationKind.Renew) 1 else 0
        override val expectedHostname get() = claim.hostname
        override fun encode() = buildJsonObject { put("generation", claim.generation) }.toString()
    }
}

internal val coreDomainClaimId = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
private val domainAscii = Regex("[A-Za-z0-9.-]{3,245}")
private val domainHostname = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val domainProof = Regex("whumpo-verification=[0-9a-f]{64}")
private val domainObservations = setOf(CoreDomainStatus.OwnershipVerified, CoreDomainStatus.ProofMissing, CoreDomainStatus.ResolverUnknown)

fun normalizeCoreDomainHostname(input: String): String? = input.trim().takeIf(domainAscii::matches)?.lowercase()
    ?.takeIf { it.matches(domainHostname) && it.substringAfterLast('.').any { char -> char in 'a'..'z' } }

internal fun decodeDomainClaims(text: String, org: String, project: String): List<CoreDomainClaim> {
    val row = Json.parseToJsonElement(text).objectValue().closed("schemaVersion", "orgId", "claims")
    row.verifyDomainScope(org)
    return row.field("claims").uniqueList(5) { decodeDomainClaim(it, project) }.also { items ->
        requireDomain(items.map { it.id }.toSet().size == items.size && items.map { it.hostname }.toSet().size == items.size)
    }
}

internal fun decodeDomainDetail(text: String, org: String, project: String, id: String): CoreDomainClaim {
    val row = Json.parseToJsonElement(text).objectValue().closed("schemaVersion", "orgId", "claim")
    row.verifyDomainScope(org)
    return decodeDomainClaim(row.field("claim"), project).also { requireDomain(it.id == id) }
}

internal fun decodeDomainOperation(text: String, org: String, project: String, command: CoreDomainCommand): CoreDomainClaim {
    val row = Json.parseToJsonElement(text).objectValue().closed("schemaVersion", "orgId", "operationId",
        "operationGeneration", "observation", "claim")
    row.verifyDomainScope(org)
    requireDomain(coreDomainClaimId.matches(row.field("operationId").text(36)))
    val generation = row.field("operationGeneration").revision()
    val observation = row.field("observation").unlessNull(::domainStatus)
    val claim = decodeDomainClaim(row.field("claim"), project)
    requireDomain(generation == command.expectedGeneration && claim.generation >= generation &&
        claim.hostname == command.expectedHostname && command.claim?.let(claim::follows) != false)
    requireDomain(if (command.kind == CoreDomainOperationKind.Check) observation in domainObservations else observation == null)
    return claim
}

private fun JsonObject.verifyDomainScope(org: String) {
    requireDomain(field("schemaVersion") == JsonPrimitive(1) && coreIdentifier.matches(org) && field("orgId").text(128) == org)
}

private fun decodeDomainClaim(element: JsonElement, project: String): CoreDomainClaim {
    val row = element.objectValue().closed("id", "projectId", "hostname", "generation", "status", "record",
        "expiresAt", "checkedAt", "evidenceValidUntil")
    val id = row.field("id").text(36)
    val hostname = row.field("hostname").text(245)
    val record = row.field("record").objectValue().closed("type", "name", "value")
    val name = record.field("name").text(253)
    val value = record.field("value").text(84)
    requireDomain(coreIdentifier.matches(project) && row.field("projectId").text(128) == project &&
        coreDomainClaimId.matches(id) && domainHostname.matches(hostname) &&
        record.field("type") == JsonPrimitive("TXT") && name == "_whumpo.$hostname" && domainProof.matches(value))
    val status = domainStatus(row.field("status"))
    val expires = domainInstant(row.field("expiresAt"))
    val checked = row.field("checkedAt").unlessNull(::domainInstant)
    val evidence = row.field("evidenceValidUntil").unlessNull(::domainInstant)
    requireDomain(if (status == CoreDomainStatus.OwnershipVerified) {
        checked != null && evidence != null && evidence > checked && evidence <= expires
    } else evidence == null)
    return CoreDomainClaim(id, project, hostname, row.field("generation").revision(), status,
        CoreDomainTxt(name, value), expires, checked, evidence)
}

private fun domainStatus(value: JsonElement): CoreDomainStatus =
    CoreDomainStatus.entries.firstOrNull { it.code == value.text(32) } ?: throw CoreProtocolException()

private fun domainInstant(value: JsonElement): Instant = try {
    Instant.parse(value.text(35))
} catch (_: IllegalArgumentException) {
    throw CoreProtocolException()
}

internal fun requireDomain(condition: Boolean) {
    if (!condition) throw CoreProtocolException()
}
