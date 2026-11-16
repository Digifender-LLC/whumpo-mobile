package com.whumpo.core.onboarding

enum class CoreDiscoveryQuestion(val label: String, val prompt: String, vararg choices: String) {
    Stage("Business stage", "Where are you with this business?", "Exploring an idea", "Getting ready to launch", "Already trading", "Growing or changing"),
    Country("Country", "Where is the business based?", "United States", "Another country"),
    CountryName("Country name", "Which country is it based in?"),
    Region("Region", "Which state, province or region should we use when planning local work?"),
    Customers("Customers", "Who are you trying to help or reach?"),
    Offer("Offer", "What do you sell or provide?"),
    Delivery("Delivery", "How do customers get what you offer?", "In person", "Online", "Both"),
    Registration("Registration", "How far along is business registration?", "Already registered", "In progress", "Not registered"),
    Entity("Declared structure", "How do you describe the business structure?", "Individual or sole proprietor", "Partnership", "Company", "LLC", "Another structure"),
    Formation("Formation plans", "Are you considering forming a separate legal entity?", "Considering it", "Keeping the current structure"),
    Website("Website", "What do you have for a website today?", "Have a site", "Building a site", "No site"),
    WebsiteUrl("Public website", "What's the public address of your website?"),
    WebsiteIntent("Website goal", "What would you like to do with the existing site?", "Keep it", "Improve it", "Replace it"),
    Domain("Domain", "Do you have a domain name to use?", "I manage one", "Someone else manages it", "I need one"),
    DomainAccess("Domain access", "Can you access the domain settings?", "I can access settings", "Someone else has access", "I do not have access"),
    Hostname("Hostname", "What's the domain name? Enter just the hostname."),
    DomainHelp("Domain help", "What would you like help with?", "Find a domain name", "Plan a connection", "No domain work for now"),
    Help("Requested help", "Where would help from Whumpo be most useful first?", "Website and leads", "Business registration", "Day-to-day operations", "Deciding what to do next"),
    Ein("US EIN status", "Do you have a US employer identification number?", "Have one", "Would like guidance", "Do not need one now"),
    Blocker("Blocker", "What's making your first priority hard to act on?"),
    Capacity("Time available", "How much time can you set aside for this each week?", "A few hours", "A day", "More than a day"),
    Budget("Budget", "What budget approach feels comfortable?", "Use what I already have", "Compare options first", "Set a budget later"),
    Tools("Tools", "Which tools are already part of your work, such as email, scheduling or bookkeeping?");

    val options: List<String> = choices.toList()
}

sealed interface CoreDiscoveryAnswer {
    data class Declared(val value: String) : CoreDiscoveryAnswer
    data object Unknown : CoreDiscoveryAnswer
    data object Deferred : CoreDiscoveryAnswer

    fun summary(): String = when (this) {
        is Declared -> value
        Unknown -> "Not sure yet"
        Deferred -> "Left for later"
    }
}

data class CoreBusinessDiscovery(val answers: Map<CoreDiscoveryQuestion, CoreDiscoveryAnswer> = emptyMap()) {
    fun declared(question: CoreDiscoveryQuestion): String? = (answers[question] as? CoreDiscoveryAnswer.Declared)?.value

    fun questions(): List<CoreDiscoveryQuestion> = CoreDiscoveryQuestion.entries.filter { question ->
        when (question) {
            CoreDiscoveryQuestion.CountryName -> declared(CoreDiscoveryQuestion.Country) == "Another country"
            CoreDiscoveryQuestion.Region -> declared(CoreDiscoveryQuestion.Country) == "United States" ||
                declared(CoreDiscoveryQuestion.CountryName) != null
            CoreDiscoveryQuestion.Entity -> declared(CoreDiscoveryQuestion.Registration) in setOf("Already registered", "In progress")
            CoreDiscoveryQuestion.Formation -> declared(CoreDiscoveryQuestion.Registration) == "Not registered"
            CoreDiscoveryQuestion.WebsiteUrl, CoreDiscoveryQuestion.WebsiteIntent ->
                declared(CoreDiscoveryQuestion.Website) == "Have a site"
            CoreDiscoveryQuestion.Hostname, CoreDiscoveryQuestion.DomainAccess -> declared(CoreDiscoveryQuestion.Domain) in setOf("I manage one", "Someone else manages it")
            CoreDiscoveryQuestion.Ein -> declared(CoreDiscoveryQuestion.Country) == "United States" &&
                (declared(CoreDiscoveryQuestion.Registration) in setOf("Already registered", "In progress") ||
                    declared(CoreDiscoveryQuestion.Formation) == "Considering it" ||
                    declared(CoreDiscoveryQuestion.Help) == "Business registration")
            else -> true
        }
    }

    val next: CoreDiscoveryQuestion? get() = questions().firstOrNull { it !in answers }

    fun prompt(question: CoreDiscoveryQuestion): String =
        if (question == CoreDiscoveryQuestion.Offer && declared(CoreDiscoveryQuestion.Stage) == "Exploring an idea") {
            "What are you considering offering?"
        } else question.prompt

    fun answer(question: CoreDiscoveryQuestion, answer: CoreDiscoveryAnswer): CoreBusinessDiscovery? {
        if (question !in questions()) return null
        val normalized = if (answer is CoreDiscoveryAnswer.Declared) {
            val value = answer.value.trim()
            if (!question.accepts(value)) return null
            CoreDiscoveryAnswer.Declared(value)
        } else answer
        if (answers[question] == normalized) return this
        val dependent = when (question) {
            CoreDiscoveryQuestion.Country -> setOf(CoreDiscoveryQuestion.CountryName, CoreDiscoveryQuestion.Region,
                CoreDiscoveryQuestion.Registration, CoreDiscoveryQuestion.Entity, CoreDiscoveryQuestion.Formation, CoreDiscoveryQuestion.Ein)
            CoreDiscoveryQuestion.CountryName -> setOf(CoreDiscoveryQuestion.Region, CoreDiscoveryQuestion.Registration,
                CoreDiscoveryQuestion.Entity, CoreDiscoveryQuestion.Formation, CoreDiscoveryQuestion.Ein)
            CoreDiscoveryQuestion.Registration -> setOf(CoreDiscoveryQuestion.Entity, CoreDiscoveryQuestion.Formation, CoreDiscoveryQuestion.Ein)
            CoreDiscoveryQuestion.Website -> setOf(CoreDiscoveryQuestion.WebsiteUrl, CoreDiscoveryQuestion.WebsiteIntent)
            CoreDiscoveryQuestion.Domain -> setOf(CoreDiscoveryQuestion.Hostname, CoreDiscoveryQuestion.DomainAccess, CoreDiscoveryQuestion.DomainHelp)
            else -> emptySet()
        }
        val changed = copy(answers = (answers - dependent) + (question to normalized))
        return changed.copy(answers = changed.answers.filterKeys { it in changed.questions() })
    }

    fun deferRemaining(): CoreBusinessDiscovery = copy(
        answers = answers + questions().filterNot(answers::containsKey).associateWith { CoreDiscoveryAnswer.Deferred },
    )
}

private val publicHost = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+")
private val publicAddress = Regex("https?://([^/:?#]+)(?:/[^?#\\s]*)?", RegexOption.IGNORE_CASE)
private val sensitiveNumber = Regex("(?<!\\d)(?:\\d{2}-\\d{7}|\\d{3}-\\d{2}-\\d{4}|\\d{9,19})(?!\\d)")

internal fun safeDiscoveryText(value: String, limit: Int): Boolean =
    value.isNotBlank() && value.length <= limit && value.none(Char::isISOControl) && !sensitiveNumber.containsMatchIn(value)

private fun CoreDiscoveryQuestion.accepts(value: String): Boolean = when {
    options.isNotEmpty() -> value in options
    this == CoreDiscoveryQuestion.Hostname -> safeDiscoveryText(value, 253) && publicHost.matches(value)
    this == CoreDiscoveryQuestion.WebsiteUrl -> {
        val host = publicAddress.matchEntire(value)?.groupValues?.get(1)
        safeDiscoveryText(value, 280) && host != null && publicHost.matches(host)
    }
    else -> safeDiscoveryText(value, 160)
}

enum class CoreStarterKind { ClarifyOffer, PlanPriority, ReviewSite, SiteBrief, DomainAccess, DomainOptions, DomainMigration, DomainConnection, Registration, EinGuidance, ReviewTools }
data class CoreStarterAction(
    val kind: CoreStarterKind,
    val title: String,
    val reason: String,
    val requires: Set<CoreStarterKind> = emptySet(),
)

/** Pure, review-only suggestions. The preview performs no purchase, connection, registration or filing. */
fun CoreBusinessDiscovery.starterActions(): List<CoreStarterAction> {
    fun value(question: CoreDiscoveryQuestion) = declared(question)
    val website = value(CoreDiscoveryQuestion.Website)
    val existingSite = website == "Have a site"
    val domainHelp = value(CoreDiscoveryQuestion.DomainHelp)
    val help = value(CoreDiscoveryQuestion.Help)
    val websiteActions = buildList {
        if (existingSite && (help == "Website and leads" || value(CoreDiscoveryQuestion.WebsiteIntent) in setOf("Improve it", "Replace it"))) {
            add(CoreStarterAction(CoreStarterKind.ReviewSite, "Review the existing website", "Start from the site you already have and identify what should improve."))
        } else if (website == null && help == "Website and leads") {
            add(CoreStarterAction(CoreStarterKind.ReviewSite, "Check your website starting point", "Decide whether to improve an existing site or start with a new brief."))
        } else if (!existingSite && help == "Website and leads") {
            add(CoreStarterAction(CoreStarterKind.SiteBrief, "Draft a website brief", "Outline the offer, audience and action visitors should take."))
        }
        if (domainHelp in setOf("Find a domain name", "Plan a connection")) {
            val needsDomain = value(CoreDiscoveryQuestion.Domain) == "I need one" || domainHelp == "Find a domain name"
            val prerequisites = mutableSetOf<CoreStarterKind>()
            if (value(CoreDiscoveryQuestion.DomainAccess) != "I can access settings" && !needsDomain) {
                add(CoreStarterAction(CoreStarterKind.DomainAccess, "Check who manages the domain", "Confirm access before proposing any changes."))
                prerequisites += CoreStarterKind.DomainAccess
            }
            if (needsDomain) {
                add(CoreStarterAction(CoreStarterKind.DomainOptions, "Shortlist domain names", "Compare names for review. Availability and purchase are separate steps."))
                prerequisites += CoreStarterKind.DomainOptions
            }
            if (existingSite) {
                add(CoreStarterAction(CoreStarterKind.DomainMigration, "Review the site and email migration", "Check DNS, existing email and redirects before connecting a different domain."))
                prerequisites += CoreStarterKind.DomainMigration
            }
            add(CoreStarterAction(CoreStarterKind.DomainConnection, "Draft a domain connection checklist", "Review the records and access a connection would require.", prerequisites.toSet()))
        }
    }
    val registrationActions = buildList {
        val einHelp = value(CoreDiscoveryQuestion.Country) == "United States" && value(CoreDiscoveryQuestion.Ein) == "Would like guidance"
        val formation = value(CoreDiscoveryQuestion.Formation) == "Considering it" || value(CoreDiscoveryQuestion.Registration) == "In progress"
        val prerequisite = einHelp && formation
        if (help == "Business registration" || prerequisite) {
            add(CoreStarterAction(CoreStarterKind.Registration, "Review local registration requirements", "Confirm your location and chosen structure before preparing any paperwork."))
        }
        if (einHelp) add(CoreStarterAction(CoreStarterKind.EinGuidance, "Review official US EIN guidance", "Check which steps apply. Enter no tax number in the preview.",
            if (prerequisite) setOf(CoreStarterKind.Registration) else emptySet()))
    }
    val priority = CoreStarterAction(CoreStarterKind.PlanPriority, "Choose your first priority", "Decide which step would help you most.")
    val exploring = value(CoreDiscoveryQuestion.Stage) == "Exploring an idea" && value(CoreDiscoveryQuestion.Offer) == null
    return buildList {
        if (help == "Business registration") addAll(registrationActions)
        if (help == "Website and leads") addAll(websiteActions)
        if (help == "Day-to-day operations") add(CoreStarterAction(CoreStarterKind.ReviewTools, "Review the tools you use", "Identify one useful improvement to how you work today."))
        if (exploring) add(CoreStarterAction(CoreStarterKind.ClarifyOffer, "Outline your offer and audience", "Start with what you could offer and who would benefit."))
        add(priority)
        if (help != "Website and leads") addAll(websiteActions)
        if (help != "Business registration") addAll(registrationActions)
    }
}
