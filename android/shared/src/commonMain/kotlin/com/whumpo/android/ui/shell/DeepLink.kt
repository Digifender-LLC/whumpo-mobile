package com.whumpo.android.ui.shell

/** A verified App Link into the shell (docs/android/app-ux.md, "Deep links"). Only supported destinations exist. */
sealed interface DeepLink {
    val destination: TopDestination

    data class Project(val id: String) : DeepLink {
        override val destination get() = TopDestination.Projects
    }

    /** Back to Chat, e.g. after text was shared into its draft. Never produced by a URL. */
    data object Chat : DeepLink {
        override val destination get() = TopDestination.Chat
    }

    data class ActivityEvent(val eventId: String) : DeepLink {
        override val destination get() = TopDestination.Activity
    }

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * Parses `https://whumpo.com/app/projects/{id}` and `/app/activity/{eventId}`. Exact host, no port, no fragment, no
         * extra path, and the ID must be a lowercase UUID as in the mobile API; anything else is null. A query string is ignored.
         */
        fun parse(url: String, origin: String = "https://whumpo.com"): DeepLink? {
            if ('#' in url || !url.startsWith("$origin/app/")) return null
            val path = url.removePrefix("$origin/app/").substringBefore('?')
            val (kind, id) = path.split('/').takeIf { it.size == 2 } ?: return null
            if (!UUID.matches(id)) return null
            return when (kind) {
                "projects" -> Project(id)
                "activity" -> ActivityEvent(id)
                else -> null
            }
        }
    }
}
