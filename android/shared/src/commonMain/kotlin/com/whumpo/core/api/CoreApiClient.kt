package com.whumpo.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Only exact configured HTTPS origins or explicit debug loopback origins are accepted. */
class CoreOrigin private constructor(val value: String) {
    companion object {
        private val https = Regex("https://[a-z0-9]+(?:[-.][a-z0-9]+)+")
        private val loopback = Regex("http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})")

        fun allowlisted(value: String, allowedHttpsOrigins: Set<String>, debugLoopback: Boolean = false): CoreOrigin {
            val port = loopback.matchEntire(value)?.groupValues?.get(1)?.toInt()
            require((https.matches(value) && value in allowedHttpsOrigins) ||
                (debugLoopback && port != null && port in 1..65535)) { "Core origin is not allowed" }
            return CoreOrigin(value)
        }
    }
}

/** Owns the platform engine and HTTP client. The engine constructor supports local mock tests. */
class CoreApiClient internal constructor(private val engine: HttpClientEngine, val origin: CoreOrigin) : AutoCloseable {
    private val http = HttpClient(engine) { followRedirects = false }
    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun authenticationOptions(): CoreAuthenticationOptions =
        json.decodeFromString(call(null, "/v1/auth/passkeys/authentication/options", HttpMethod.Post, "{}"))

    internal suspend fun authenticate(challengeId: String, response: JsonObject): CoreSessionToken {
        require(coreBearer.matches(challengeId))
        return json.decodeFromString(call(null, "/v1/auth/passkeys/authentication/verify", HttpMethod.Post,
            json.encodeToString(CoreAuthenticationVerify(challengeId, response))))
    }

    internal suspend fun session(token: String): CoreSession =
        json.decodeFromString(call(token, "/v1/auth/session"))

    internal suspend fun switch(token: String, target: String): CoreSessionToken {
        require(coreIdentifier.matches(target))
        return json.decodeFromString(call(token, "/v1/auth/session/switch", HttpMethod.Post, json.encodeToString(CoreSwitch(target))))
    }

    internal suspend fun rotate(token: String): CoreSessionToken =
        json.decodeFromString(call(token, "/v1/auth/session/rotate", HttpMethod.Post, "{}"))

    internal suspend fun organizations(token: String, cursor: String?, limit: Int): CoreMembershipPage {
        require(limit in 1..50 && (cursor == null || coreIdentifier.matches(cursor)))
        return json.decodeFromString(call(token, "/v1/auth/organizations", cursor = cursor, limit = limit))
    }

    internal suspend fun profile(token: String): CoreOrganizationProfile =
        json.decodeFromString(call(token, "/v1/organization"))

    internal suspend fun rename(token: String, name: String, version: Long, key: String): CoreOrganizationProfile {
        require(version in 1..9_007_199_254_740_991L && coreIdentifier.matches(key))
        return json.decodeFromString(call(token, "/v1/organization", HttpMethod.Patch,
            json.encodeToString(CoreProfilePatch(name)), version = version, key = key))
    }

    internal suspend fun entitlements(token: String): CoreOrganizationEntitlements =
        json.decodeFromString(call(token, "/v1/organization/entitlements"))

    internal suspend fun work(token: String, cursor: String?, limit: Int): CoreWorkPage {
        require(limit in 1..10 && (cursor == null || coreDigest.matches(cursor)))
        // A task description can be 10,000 characters, including JSON-escaped characters.
        return json.decodeFromString(call(token, "/v1/work/tasks", cursor = cursor, limit = limit,
            maxResponseBytes = limit * 65_536))
    }

    internal suspend fun task(token: String, taskId: String): CoreWorkTask {
        require(coreIdentifier.matches(taskId))
        return json.decodeFromString(call(token, "/v1/tasks/$taskId"))
    }

    internal suspend fun websiteTarget(token: String, projectId: String): CoreWebsiteTarget {
        require(coreIdentifier.matches(projectId))
        return json.decodeFromString(call(token, "/v1/site-targets", projectId = projectId))
    }

    internal suspend fun websiteHistory(token: String, targetId: String, before: String?, limit: Int): CoreWebsiteHistoryPage {
        require(coreIdentifier.matches(targetId) && limit in 1..50)
        require(before == null || (coreHistoryCursor.matches(before) && before.toLong() <= CORE_MAX_VERSION))
        return json.decodeFromString(call(token, "/v1/site-targets/$targetId/history", before = before, limit = limit))
    }

    internal suspend fun workspaceChoices(token: String, cursor: String?, limit: Int): CoreWorkspaceChoices {
        require(limit in 1..25 && (cursor == null || coreIdentifier.matches(cursor)))
        return json.decodeFromString(call(token, "/v1/workspaces", cursor = cursor, limit = limit))
    }

    internal suspend fun projectChoices(token: String, workspaceId: String, cursor: String?, limit: Int): CoreProjectChoices {
        require(coreIdentifier.matches(workspaceId) && limit in 1..25 && (cursor == null || coreIdentifier.matches(cursor)))
        return json.decodeFromString(call(token, "/v1/projects", workspaceId = workspaceId, cursor = cursor, limit = limit))
    }

    internal suspend fun createTask(token: String, draft: CoreNewTask, requestKey: String): CoreTaskCommandAcceptance {
        require(coreIdentifier.matches(requestKey))
        return json.decodeFromString(call(token, "/v1/tasks", HttpMethod.Post, json.encodeToString(draft),
            key = requestKey, expectedStatus = 202))
    }

    private suspend fun call(
        token: String?,
        path: String,
        verb: HttpMethod = HttpMethod.Get,
        body: String? = null,
        cursor: String? = null,
        limit: Int? = null,
        version: Long? = null,
        key: String? = null,
        projectId: String? = null,
        before: String? = null,
        maxResponseBytes: Int = 65_536,
        workspaceId: String? = null,
        expectedStatus: Int = 200,
    ): String {
        require(token == null || coreBearer.matches(token)) { "Invalid core bearer format" }
        return http.prepareRequest(origin.value + path) {
            method = verb
            if (token != null) header("Authorization", "Bearer $token")
            if (workspaceId != null) parameter("workspaceId", workspaceId)
            if (projectId != null) parameter("projectId", projectId)
            if (before != null) parameter("before", before)
            if (cursor != null) parameter("cursor", cursor)
            if (limit != null) parameter("limit", limit)
            if (version != null) header("If-Match", "\"$version\"")
            if (key != null) header("Idempotency-Key", key)
            if (body != null) {
                header("Content-Type", "application/json")
                setBody(body)
            }
        }.execute { response ->
            if (response.status.value != expectedStatus) {
                if (response.status.value in 200..299) throw CoreProtocolException()
                throw CoreHttpException(response.status.value)
            }
            val bytes = response.bodyAsChannel().readBuffer(maxResponseBytes.toLong() + 1).readByteArray()
            if (bytes.size > maxResponseBytes) throw CoreProtocolException()
            bytes.decodeToString(throwOnInvalidSequence = true)
        }
    }

    override fun close() {
        http.close()
        engine.close()
    }
}
