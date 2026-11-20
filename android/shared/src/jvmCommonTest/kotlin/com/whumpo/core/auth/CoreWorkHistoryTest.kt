package com.whumpo.core.auth

import com.whumpo.core.api.*
import kotlin.test.*

class CoreWorkHistoryTest {
    @Test
    fun recordedSourceRetainsOnlyTheExactUserBodyAndRejectsAssistantSubstitution() {
        val origin = CoreRecordedTaskSource("task-1", "event-1", "conversation-1", "source-1")
        fun message(id: String, sequence: Int, role: String, body: String) = """{"id":"$id","sequence":$sequence,"userId":"user-1","role":"$role","content":"$body","createdAt":"2026-11-19T12:00:00Z"}"""
        val page = """{"items":[${message("unrelated-1", 1, "user", "Unrelated private message")},${message("source-1", 2, "user", "Exact recorded source")},${message("assistant-1", 3, "assistant", "Unverified generated summary") }]}"""
        val result = decodeWorkSource(page, origin, null)
        assertEquals("Exact recorded source", result.content)
        assertNull(result.next)
        assertFalse(result.toString().contains("Unrelated private"))
        assertFalse(result.toString().contains("Unverified generated"))
        assertFailsWith<CoreProtocolException> { decodeWorkSource(page.replace("\"source-1\",\"sequence\":2,\"userId\":\"user-1\",\"role\":\"user\"", "\"source-1\",\"sequence\":2,\"userId\":\"user-1\",\"role\":\"assistant\""), origin, null) }
        assertFailsWith<CoreProtocolException> { decodeWorkSource(page.replace("\"sequence\":3", "\"sequence\":2"), origin, null) }
    }
}
