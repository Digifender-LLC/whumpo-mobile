package com.whumpo.core.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Each contract code maps only at its fixed status (tests/fixtures/mobile-v1/errors.json); any other status is unrecognized. */
class ErrorStatusTest {
    private val cases = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/mobile-v1/errors.json").isFile) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "tests/fixtures/mobile-v1/errors.json not found" }, "tests/fixtures/mobile-v1/errors.json")
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonObject
    }

    @Test
    fun everyCodeMapsAtItsFixedStatusOnly() {
        assertEquals(18, cases.size)
        for ((name, c) in cases) {
            val case = c.jsonObject
            val status = case.getValue("status").jsonPrimitive.int
            val body = case.getValue("body").toString()
            val code = case.getValue("body").jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
            val mapped = MobileApiException.fromResponse(status, body)
            assertEquals(code, mapped.code, name)
            assertEquals(false, mapped is MobileApiException.Unrecognized, name)
            for (wrong in listOf(200, 422, 500, if (status == 409) 400 else 409).filter { it != status }) {
                val e = assertIs<MobileApiException.Unrecognized>(MobileApiException.fromResponse(wrong, body), "$name at $wrong")
                assertEquals(wrong, e.status, "$name at $wrong")
            }
        }
    }
}
