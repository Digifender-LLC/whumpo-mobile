package com.whumpo.core.wear

/** Single-writer generation counter. Production calls it from the serialized Data Layer publisher actor. */
class CoreWearGenerationCounter(
    private val read: () -> Long,
    private val persist: (Long) -> Boolean,
) {
    fun next(): Long {
        val previous = read()
        require(previous >= 0L) { "Invalid persisted Wear generation" }
        val next = Math.addExact(previous, 1L)
        check(persist(next)) { "Unable to persist Wear generation" }
        return next
    }
}
