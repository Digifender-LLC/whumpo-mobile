package com.whumpo.android.ui.chat.forms

import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalNumbersTest {
    @Test
    fun plainNumbersMatchTheIndependentEcmaScriptCorpus() {
        val path = "tests/fixtures/numbers/canonical-decimals.json"
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, path).isFile) dir = dir.parentFile
        val rows = Json.parseToJsonElement(File(checkNotNull(dir), path).readText()).jsonArray
        for (row in rows) {
            val bits = row.jsonObject.getValue("bits").jsonPrimitive.content
            val decimal = row.jsonObject.getValue("decimal").jsonPrimitive.content
            val value = Double.fromBits(bits.toULong(16).toLong())
            assertEquals(BigDecimal(decimal).stripTrailingZeros().toPlainString(), plain(value), bits)
        }
    }

    @Test
    fun gridDistanceUsesAStrictOneTenMillionthOfTheStep() {
        val kind = FieldKind.Number(0.0, 1.0, 1.0)
        for (value in listOf("0", "1", "0.00000009999999", "0.99999990000001")) {
            assertEquals(null, decimalNumberProblem(value, kind), value)
        }
        for (value in listOf("0.0000001", "0.0000001000001", "0.9999999", "0.9999998999999")) {
            assertEquals(DecimalNumberProblem.Step, decimalNumberProblem(value, kind), value)
        }
        for (value in listOf("0.300000001", "0.299999999", "0.30000000000000004")) {
            assertEquals(null, decimalNumberProblem(value, FieldKind.Number(0.1, 1.0, 0.2)), value)
        }
    }

    @Test
    fun subnormalGridUsesCanonicalNumberDecimalsWithoutLosingTypedPrecision() {
        val step = Double.MIN_VALUE
        val kind = FieldKind.Number(0.0, 1.0, step)
        for (value in listOf("5e-324", "1e-323", "1.5e-323", "2e-323")) {
            assertEquals(null, decimalNumberProblem(BigDecimal(value).toPlainString(), kind), value)
        }
        assertEquals(DecimalNumberProblem.Precision,
            decimalNumberProblem(BigDecimal("4.9e-324").toPlainString(), kind))
    }
}
