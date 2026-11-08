package com.whumpo.core.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The fresh-passkey requirement each review kind must carry, checked against tests/fixtures/mobile-v1/reviews.json. */
class ReviewContractTest {
    private val json = Json

    private val reviews: JsonObject = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tests/fixtures/mobile-v1/reviews.json").isFile) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "tests/fixtures/mobile-v1/reviews.json not found" }, "tests/fixtures/mobile-v1/reviews.json")
        json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun cases(key: JsonObject) = key["cases"]!!.jsonArray.map { it.jsonObject }

    private fun decode(review: JsonObject) = json.decodeFromJsonElement(Review.serializer(), review)

    private fun withFreshness(review: JsonObject, fresh: Boolean) =
        JsonObject(review + ("requiresFreshPasskey" to JsonPrimitive(fresh)))

    @Test
    fun approvableReviewsRequireAFreshPasskeyExactlyForSpecificationAndAppRestore() {
        val approvable = cases(reviews).filter { it["review"]!!.jsonObject["canApprove"]!!.jsonPrimitive.boolean }
        val kinds = approvable.map { it["review"]!!.jsonObject["kind"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("specification", "site", "app-restore"), kinds)
        for (case in approvable) {
            val review = decode(case["review"]!!.jsonObject)
            assertEquals(review.kind == "specification" || review.kind == "app-restore", review.requiresFreshPasskey, case["name"].toString())
        }
    }

    @Test
    fun freshAppRestoreIsEnabledOnlyForABuildThatDeclaredIt() {
        val restore = cases(reviews).first { it["name"]!!.jsonPrimitive.content == "app restore, current client" }["review"]!!.jsonObject
        val review = decode(restore)
        assertTrue(review.requiresFreshPasskey)
        assertEquals(ReviewPresentation(true, null), review.present(ClientInfo.current(42)))
        assertEquals(
            ReviewPresentation(false, "client_update_required"),
            review.present(ClientInfo.current(42, ClientInfo.ALL_CONTROLS - ControlCapability.APP_RESTORE)),
        )
    }

    @Test
    fun mismatchedFreshnessIsRefused() {
        val invalid = cases(reviews["invalid"]!!.jsonObject)
        assertEquals(setOf("app-restore", "site", "application"), invalid.map { it["review"]!!.jsonObject["kind"]!!.jsonPrimitive.content }.toSet())
        for (case in invalid) {
            val failure = assertFailsWith<IllegalArgumentException>(case["name"].toString()) { decode(case["review"]!!.jsonObject) }
            assertTrue("freshness requirement does not match review kind" in failure.message.orEmpty(), case["name"].toString())
        }
        val specification = cases(reviews).first { it["name"]!!.jsonPrimitive.content == "specification apply, current client" }["review"]!!.jsonObject
        assertFailsWith<IllegalArgumentException> { decode(withFreshness(specification, false)) }
    }

    @Test
    fun freshnessIsNotCheckedOnReviewsThatCannotBeApproved() {
        val expired = cases(reviews).first { it["name"]!!.jsonPrimitive.content == "expired review" }["review"]!!.jsonObject
        for (fresh in listOf(true, false)) {
            val review = decode(withFreshness(expired, fresh))
            assertEquals(ReviewPresentation(false, "expired"), review.present(ClientInfo.current(42)))
        }
    }
}
