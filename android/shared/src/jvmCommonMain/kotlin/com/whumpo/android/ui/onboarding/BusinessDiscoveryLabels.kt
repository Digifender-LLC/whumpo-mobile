package com.whumpo.android.ui.onboarding

import com.whumpo.core.api.CoreDiscoveryKey
import com.whumpo.core.api.CoreDiscoveryValue
import com.whumpo.core.auth.CoreDiscoveryNotice
import java.util.Locale

internal data class DiscoveryPrompt(val label: String, val question: String)

internal fun CoreDiscoveryKey.prompt(): DiscoveryPrompt = when (this) {
    CoreDiscoveryKey.Stage -> DiscoveryPrompt("Business stage", "Where are you with this business?")
    CoreDiscoveryKey.Country -> DiscoveryPrompt("Country", "Where is your business based?")
    CoreDiscoveryKey.Region -> DiscoveryPrompt("Region", "Which state, province, or region is relevant to your business?")
    CoreDiscoveryKey.Customers -> DiscoveryPrompt("Customers", "Who would you like to reach or help?")
    CoreDiscoveryKey.Offer -> DiscoveryPrompt("Offer", "What do you provide or plan to offer?")
    CoreDiscoveryKey.Delivery -> DiscoveryPrompt("Delivery", "How do customers receive what you offer?")
    CoreDiscoveryKey.Registration -> DiscoveryPrompt("Registration", "How far along is your business registration?")
    CoreDiscoveryKey.Formation -> DiscoveryPrompt("Formation plans", "Are you considering forming a separate legal entity?")
    CoreDiscoveryKey.Entity -> DiscoveryPrompt("Business structure", "How do you describe the business structure?")
    CoreDiscoveryKey.EmployerIdStatus -> DiscoveryPrompt("U.S. EIN status", "Do you have a U.S. employer identification number? Choose a status.")
    CoreDiscoveryKey.Website -> DiscoveryPrompt("Website", "Do you have a website today?")
    CoreDiscoveryKey.WebsiteUrl -> DiscoveryPrompt("Public website URL", "What is your public website address? Use a link without sign-in details.")
    CoreDiscoveryKey.WebsiteIntent -> DiscoveryPrompt("Website goal", "What would you like to do with the existing website?")
    CoreDiscoveryKey.Domain -> DiscoveryPrompt("Domain", "Do you already have a domain name?")
    CoreDiscoveryKey.DomainName -> DiscoveryPrompt("Domain name", "What is the domain name? Enter the hostname.")
    CoreDiscoveryKey.DomainAccess -> DiscoveryPrompt("Domain access", "Who can access your domain settings?")
    CoreDiscoveryKey.DomainHelp -> DiscoveryPrompt("Domain help", "What domain help would you like?")
    CoreDiscoveryKey.RegistrationHelp -> DiscoveryPrompt("Registration help", "What help would you like with registration?")
    CoreDiscoveryKey.Blocker -> DiscoveryPrompt("Blocker", "What is making it hard to move forward?")
    CoreDiscoveryKey.Time -> DiscoveryPrompt("Time available", "How much time can you set aside each week?")
    CoreDiscoveryKey.Budget -> DiscoveryPrompt("Budget", "What budget approach feels comfortable?")
    CoreDiscoveryKey.Tools -> DiscoveryPrompt("Tools", "What tools do you already use?")
}

internal fun CoreDiscoveryKey.summary(answer: CoreDiscoveryValue): String = when (answer) {
    is CoreDiscoveryValue.Declared -> when {
        this == CoreDiscoveryKey.Country -> discoveryCountryName(answer.value)
        choices.isNotEmpty() -> choiceLabel(answer.value)
        else -> answer.value
    }
    CoreDiscoveryValue.Unknown -> "Not sure yet"
    CoreDiscoveryValue.Deferred -> "Left for later"
}

internal fun discoveryCountryName(code: String): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()).ifBlank { code }

internal fun CoreDiscoveryKey.choiceLabel(code: String): String = when (code) {
    "idea" -> "Exploring an idea"
    "launching" -> "Getting ready to launch"
    "operating" -> "Already operating"
    "growing" -> "Growing or changing"
    "form_entity" -> "Plan to form an entity"
    "explore" -> "Exploring options"
    "no_entity_planned" -> "No entity planned"
    "have_ein" -> "I have an EIN"
    "need_ein" -> "I need EIN guidance"
    "existing" -> "I have a website"
    "owned" -> "I own a domain"
    "managed_by_other" -> "Someone else manages it"
    "self" -> "I can access settings"
    "other" -> "Someone else has access"
    "find_new" -> "Find a new domain"
    "connect_owned" -> "Plan a connection"
    "keep_as_is" -> "Keep it as it is"
    "understand_existing" -> "Understand the current setup"
    "paperwork_checklist" -> "Prepare a paperwork checklist"
    "none" -> when (this) {
        CoreDiscoveryKey.Website -> "I don’t have a website"
        CoreDiscoveryKey.Domain -> "I don’t have a domain"
        CoreDiscoveryKey.DomainAccess -> "No settings access"
        else -> "No help for now"
    }
    "under_two_hours" -> "Under two hours"
    "two_to_five_hours" -> "Two to five hours"
    "over_five_hours" -> "Over five hours"
    else -> code.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

internal fun CoreDiscoveryNotice.label(): String = when (this) {
    CoreDiscoveryNotice.Unavailable -> "I couldn’t load your business discovery. Try reloading saved discovery."
    CoreDiscoveryNotice.PolicyUnavailable -> "Business discovery is unavailable for this organization."
    CoreDiscoveryNotice.InvalidAnswer -> "Check this answer and try again."
    CoreDiscoveryNotice.Unconfirmed -> "I couldn’t confirm the save. Retry sends your original request."
    CoreDiscoveryNotice.ConfirmationPending -> "The service acknowledged your request. Check your saved discovery to confirm it."
    CoreDiscoveryNotice.Changed -> "Your saved discovery changed. Reload it before trying the edit again."
    CoreDiscoveryNotice.SignInAgain -> "This request requires a recent owner sign-in."
    CoreDiscoveryNotice.Rejected -> "The service couldn’t accept this change. Reload your saved discovery to review it."
}
