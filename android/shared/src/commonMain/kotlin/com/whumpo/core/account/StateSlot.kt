package com.whumpo.core.account

/** The kinds of protected per-account state the native app may hold. */
enum class StateKind(val fileTag: String) {
    /** Unsent Chat or form drafts. */
    DRAFT("draft"),

    /** The native bearer session (WH-083). Never logged, exported or backed up. */
    SESSION("session"),

    /** The original payload, revision and request id of a submitted operation. */
    PENDING_OPERATION("pending"),

    /** A prepared photo (exact bytes, name, project and upload ID) kept so an interrupted upload retries the same request. */
    UPLOAD("upload"),
}

/** One named record of a [StateKind], e.g. `StateSlot(DRAFT, "chat")`. */
data class StateSlot(val kind: StateKind, val name: String) {
    init {
        require(NAME_PATTERN.matches(name)) { "State slot names must match ${NAME_PATTERN.pattern}" }
    }

    internal val fileName: String get() = "${kind.fileTag}-$name.bin"

    companion object {
        private val NAME_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")

        fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

        val CHAT_DRAFT = StateSlot(StateKind.DRAFT, "chat")
    }
}
