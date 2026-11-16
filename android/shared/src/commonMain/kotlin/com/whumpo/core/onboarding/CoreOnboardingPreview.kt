package com.whumpo.core.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Offline onboarding preview. Nothing here calls a network, payment, credential, inference or storage API:
 * payment and account security are local demo confirmations, and the created organization lives only in memory.
 * The account belongs to the person; the plan and its billing belong to the organization.
 */
enum class CoreOnboardingPlan(val label: String, val allowsTeam: Boolean) {
    Solo("Solo", allowsTeam = false),
    Growth("Growth", allowsTeam = false),
    Business("Business", allowsTeam = true),
    BusinessPlus("Business Plus", allowsTeam = true),
}

enum class CoreOnboardingTeamIntent { JustMe, Team }

enum class CoreOnboardingStep { Welcome, Plan, Payment, Security, Conversation, Review, Workspace }

/** Demo sheet lifecycle. [Open] and [Failed] are visible; [Cancelled] is closed with a notice. */
enum class CoreOnboardingSheet { Idle, Open, Failed, Cancelled }

/** Neither choice collects or creates actual credentials. */
enum class CoreOnboardingSecurityMethod(val label: String) {
    Passkeys("Two passkeys and recovery kit"),
    PasswordAuthenticator("Password, authenticator and recovery codes"),
}

enum class CoreOnboardingSecurityTask { FirstPasskey, BackupPasskey, Password, Authenticator, Recovery }

data class CoreOnboardingSecurity(
    val method: CoreOnboardingSecurityMethod? = null,
    val firstPasskey: Boolean = false,
    val backupPasskey: Boolean = false,
    val password: Boolean = false,
    val authenticator: Boolean = false,
    val recoverySaved: Boolean = false,
    val recoveryAcknowledged: Boolean = false,
    val sheet: CoreOnboardingSheet = CoreOnboardingSheet.Idle,
) {
    val credentialsComplete: Boolean get() = when (method) {
        CoreOnboardingSecurityMethod.Passkeys -> firstPasskey && backupPasskey
        CoreOnboardingSecurityMethod.PasswordAuthenticator -> password && authenticator
        null -> false
    }
    val complete: Boolean get() = credentialsComplete && recoverySaved && recoveryAcknowledged
    val nextTask: CoreOnboardingSecurityTask? get() = when (method) {
        CoreOnboardingSecurityMethod.Passkeys -> when {
            !firstPasskey -> CoreOnboardingSecurityTask.FirstPasskey
            !backupPasskey -> CoreOnboardingSecurityTask.BackupPasskey
            !recoverySaved -> CoreOnboardingSecurityTask.Recovery
            else -> null
        }
        CoreOnboardingSecurityMethod.PasswordAuthenticator -> when {
            !password -> CoreOnboardingSecurityTask.Password
            !authenticator -> CoreOnboardingSecurityTask.Authenticator
            !recoverySaved -> CoreOnboardingSecurityTask.Recovery
            else -> null
        }
        null -> null
    }
}

/** Conversation order. [maxLength] is null for the non-text team choice. */
enum class CoreOnboardingQuestion(val maxLength: Int?) {
    OwnerName(60),
    OrganizationName(80),
    BusinessDescription(280),
    TeamIntent(null),
    FirstPriority(160);

    val optional: Boolean get() = this == BusinessDescription || this == FirstPriority
}

enum class CoreOnboardingInputProblem { Empty, TooLong }

/** [rejected] is the submitted text, kept so the composer can show it unchanged for correction. */
data class CoreOnboardingInputError(
    val question: CoreOnboardingQuestion,
    val rejected: String,
    val problem: CoreOnboardingInputProblem,
)

data class CoreOnboardingAnswers(
    val ownerName: String? = null,
    val organizationName: String? = null,
    val businessDescription: String? = null,
    val teamIntent: CoreOnboardingTeamIntent? = null,
    val firstPriority: String? = null,
    val deferred: Set<CoreOnboardingQuestion> = emptySet(),
) {
    fun text(question: CoreOnboardingQuestion): String? = when (question) {
        CoreOnboardingQuestion.OwnerName -> ownerName
        CoreOnboardingQuestion.OrganizationName -> organizationName
        CoreOnboardingQuestion.BusinessDescription -> businessDescription
        CoreOnboardingQuestion.TeamIntent -> null
        CoreOnboardingQuestion.FirstPriority -> firstPriority
    }

    fun isAnswered(question: CoreOnboardingQuestion): Boolean =
        if (question == CoreOnboardingQuestion.TeamIntent) teamIntent != null else text(question) != null || question in deferred

    val firstUnanswered: CoreOnboardingQuestion? get() = CoreOnboardingQuestion.entries.firstOrNull { !isAnswered(it) }
    val complete: Boolean get() = firstUnanswered == null

    internal fun withText(question: CoreOnboardingQuestion, value: String) = when (question) {
        CoreOnboardingQuestion.OwnerName -> copy(ownerName = value)
        CoreOnboardingQuestion.OrganizationName -> copy(organizationName = value)
        CoreOnboardingQuestion.BusinessDescription -> copy(businessDescription = value, deferred = deferred - question)
        CoreOnboardingQuestion.TeamIntent -> throw IllegalArgumentException("Team intent is not a text answer.")
        CoreOnboardingQuestion.FirstPriority -> copy(firstPriority = value, deferred = deferred - question)
    }
}

/** Raised when the person wants a team on a single-person plan; [singlePersonPlan] is kept by "Keep just me". */
data class CoreOnboardingTeamConflict(val singlePersonPlan: CoreOnboardingPlan)

/** The in-memory result. [firstGoalDraft] is a local draft only; nothing is invited, published or saved. */
data class CoreOnboardingOrganization(
    val ownerName: String,
    val name: String,
    val businessDescription: String?,
    val teamIntent: CoreOnboardingTeamIntent,
    val plan: CoreOnboardingPlan,
    val firstGoalDraft: String?,
    val securityMethod: CoreOnboardingSecurityMethod,
    val starterActions: List<CoreStarterAction>,
    val nextAction: CoreStarterKind,
    val websiteStatus: String? = null,
    val websiteAddress: String? = null,
)

data class CoreOnboardingSnapshot(
    val step: CoreOnboardingStep = CoreOnboardingStep.Welcome,
    val plan: CoreOnboardingPlan? = null,
    val paidPlan: CoreOnboardingPlan? = null,
    val payment: CoreOnboardingSheet = CoreOnboardingSheet.Idle,
    /** True after a plan change discarded an earlier demo payment confirmation. */
    val repaymentRequired: Boolean = false,
    val security: CoreOnboardingSecurity = CoreOnboardingSecurity(),
    /** Choice is separate from the focused task; returning to it retains the selected method's progress. */
    val securityChoiceVisible: Boolean = true,
    val answers: CoreOnboardingAnswers = CoreOnboardingAnswers(),
    val conflict: CoreOnboardingTeamConflict? = null,
    val editing: CoreOnboardingQuestion? = null,
    val inputError: CoreOnboardingInputError? = null,
    val organization: CoreOnboardingOrganization? = null,
    val discovery: CoreBusinessDiscovery = CoreBusinessDiscovery(),
    val discoveryEdit: CoreDiscoveryQuestion? = null,
    val starterChoice: CoreStarterKind? = null,
    val starterTitles: Map<CoreStarterKind, String> = emptyMap(),
) {
    val securitySetupComplete: Boolean get() = security.complete
    val paymentConfirmed: Boolean get() = plan != null && paidPlan == plan
    val currentQuestion: CoreOnboardingQuestion? get() = answers.firstUnanswered
    val starterActions: List<CoreStarterAction>
        get() {
            val priority = answers.firstPriority
            val candidates = discovery.starterActions().map { action ->
                val contextual = if (action.kind == CoreStarterKind.PlanPriority && priority != null) {
                    action.copy(title = priority, reason = "Start with the priority you chose during setup.")
                } else action
                starterTitles[action.kind]?.let { contextual.copy(title = it) } ?: contextual
            }
            return if (priority == null) candidates else candidates.sortedBy { it.kind != CoreStarterKind.PlanPriority }
        }
    val nextAction: CoreStarterKind get() = starterActions.filter { it.requires.isEmpty() }
        .let { ready -> ready.firstOrNull { it.kind == starterChoice } ?: ready.first() }.kind
    val teamPlanConflict: Boolean
        get() = answers.teamIntent == CoreOnboardingTeamIntent.Team && plan?.allowsTeam == false
    val canCreate: Boolean
        get() = step == CoreOnboardingStep.Review && organization == null && editing == null && conflict == null &&
            paymentConfirmed && securitySetupComplete && answers.complete && discovery.next == null && discoveryEdit == null && !teamPlanConflict
}

/**
 * Validates one text answer. Surrounding whitespace is trimmed; over-long input is rejected, never truncated.
 * Returns the trimmed value or the error describing the unchanged input.
 */
fun CoreOnboardingQuestion.validate(raw: String): Pair<String?, CoreOnboardingInputError?> {
    val max = requireNotNull(maxLength) { "$this is not a text question." }
    val value = raw.trim()
    return when {
        value.isEmpty() -> null to CoreOnboardingInputError(this, raw, CoreOnboardingInputProblem.Empty)
        value.length > max -> null to CoreOnboardingInputError(this, raw, CoreOnboardingInputProblem.TooLong)
        else -> value to null
    }
}

/** Optional starting points for free-text answers; the person can still edit them before sending. */
object CoreOnboardingQuickChoices {
    val business = listOf("Local service business", "Retail shop", "Professional practice", "Online store")
    val firstPriority = listOf("Plan this week's priorities", "Organize customer follow-ups", "Get the website started")
}

/**
 * Single owner of the preview state. Every transition is a pure function applied with compare-and-set, so a
 * repeated or racing action is either applied exactly once or ignored. Actions invalid for the current
 * snapshot return false and change nothing.
 */
class CoreOnboardingFlow {
    private val mutable = MutableStateFlow(CoreOnboardingSnapshot())
    val state: StateFlow<CoreOnboardingSnapshot> = mutable.asStateFlow()

    fun start(): Boolean = transition { if (step == CoreOnboardingStep.Welcome) proceed() else null }

    /** Valid on the plan step. Choosing a single-person plan while a team is intended raises a conflict. */
    fun selectPlan(choice: CoreOnboardingPlan): Boolean = transition {
        when {
            step != CoreOnboardingStep.Plan || conflict != null -> null
            !choice.allowsTeam && answers.teamIntent == CoreOnboardingTeamIntent.Team ->
                copy(conflict = CoreOnboardingTeamConflict(choice))
            else -> withPlan(choice).proceed()
        }
    }

    /** Continue past an already satisfied step (for example after navigating back). */
    fun proceedFromCompletedStep(): Boolean = transition {
        val satisfied = when (step) {
            CoreOnboardingStep.Payment -> paymentConfirmed
            CoreOnboardingStep.Security -> securitySetupComplete && !securityChoiceVisible
            CoreOnboardingStep.Conversation -> answers.complete && discovery.next == null
            else -> false
        }
        if (satisfied && conflict == null) proceed() else null
    }

    fun openPaymentSheet(): Boolean = transition {
        if (step == CoreOnboardingStep.Payment && !paymentConfirmed && payment != CoreOnboardingSheet.Open) {
            copy(payment = CoreOnboardingSheet.Open)
        } else null
    }

    fun confirmPayment(): Boolean = transition {
        if (step == CoreOnboardingStep.Payment && payment == CoreOnboardingSheet.Open) {
            copy(paidPlan = plan, payment = CoreOnboardingSheet.Idle, repaymentRequired = false).proceed()
        } else null
    }

    fun failPayment(): Boolean = transition {
        if (step == CoreOnboardingStep.Payment && payment == CoreOnboardingSheet.Open) copy(payment = CoreOnboardingSheet.Failed) else null
    }

    fun cancelPayment(): Boolean = transition {
        if (step == CoreOnboardingStep.Payment && payment.visible) copy(payment = CoreOnboardingSheet.Cancelled) else null
    }

    /** A new choice deliberately discards all earlier security confirmations, but keeps payment and answers. */
    fun selectSecurityMethod(method: CoreOnboardingSecurityMethod): Boolean = transition {
        if (step != CoreOnboardingStep.Security || !paymentConfirmed || !securityChoiceVisible) null
        else copy(
            security = if (method == security.method) security else CoreOnboardingSecurity(method = method),
            securityChoiceVisible = false,
        )
    }

    fun editSecurity(): Boolean = transition {
        if (step == CoreOnboardingStep.Review && editing == null && discoveryEdit == null && conflict == null) {
            copy(step = CoreOnboardingStep.Security, securityChoiceVisible = true, inputError = null)
        } else null
    }

    fun openSecuritySheet(task: CoreOnboardingSecurityTask): Boolean = transition {
        if (step == CoreOnboardingStep.Security && !securityChoiceVisible && paymentConfirmed && security.nextTask == task &&
            security.sheet != CoreOnboardingSheet.Open
        ) copy(security = security.copy(sheet = CoreOnboardingSheet.Open)) else null
    }

    /** The expected task prevents a repeated confirmation from completing the next credential. */
    fun confirmSecurityTask(task: CoreOnboardingSecurityTask): Boolean = transition {
        if (step != CoreOnboardingStep.Security || securityChoiceVisible || !paymentConfirmed || security.nextTask != task ||
            security.sheet != CoreOnboardingSheet.Open
        ) null else {
            val confirmed = when (task) {
                CoreOnboardingSecurityTask.FirstPasskey -> security.copy(firstPasskey = true)
                CoreOnboardingSecurityTask.BackupPasskey -> security.copy(backupPasskey = true)
                CoreOnboardingSecurityTask.Password -> security.copy(password = true)
                CoreOnboardingSecurityTask.Authenticator -> security.copy(authenticator = true)
                CoreOnboardingSecurityTask.Recovery -> security.copy(recoverySaved = true)
            }.copy(sheet = CoreOnboardingSheet.Idle)
            copy(security = confirmed)
        }
    }

    /** Saving and acknowledging are separate demo actions; neither creates a recovery file. */
    fun acknowledgeRecovery(method: CoreOnboardingSecurityMethod): Boolean = transition {
        if (step != CoreOnboardingStep.Security || securityChoiceVisible || !paymentConfirmed ||
            security.method != method || !security.credentialsComplete || !security.recoverySaved ||
            security.recoveryAcknowledged || security.sheet != CoreOnboardingSheet.Idle
        ) null else copy(security = security.copy(recoveryAcknowledged = true)).proceed()
    }

    fun failSecurityTask(task: CoreOnboardingSecurityTask): Boolean = transition {
        if (step == CoreOnboardingStep.Security && !securityChoiceVisible && security.nextTask == task && security.sheet == CoreOnboardingSheet.Open) {
            copy(security = security.copy(sheet = CoreOnboardingSheet.Failed))
        } else null
    }

    fun cancelSecurityTask(task: CoreOnboardingSecurityTask): Boolean = transition {
        if (step == CoreOnboardingStep.Security && !securityChoiceVisible && security.nextTask == task && security.sheet.visible) {
            copy(security = security.copy(sheet = CoreOnboardingSheet.Cancelled))
        } else null
    }

    /** Answers the current text question in the conversation. Invalid input keeps the previous state plus an error. */
    fun answer(raw: String): Boolean = mutable.value.currentQuestion?.let { answer(it, raw) } ?: false

    fun answer(question: CoreOnboardingQuestion, raw: String): Boolean = transition {
        if (step != CoreOnboardingStep.Conversation || conflict != null || question != currentQuestion || question.maxLength == null) {
            null
        } else submitText(question, raw)
    }

    fun deferCoreQuestion(): Boolean = transition {
        val question = when (step) {
            CoreOnboardingStep.Conversation -> currentQuestion
            CoreOnboardingStep.Review -> editing
            else -> null
        }
        if (conflict != null || question?.optional != true) return@transition null
        if (question in answers.deferred) return@transition copy(editing = null, inputError = null)
        val kept = when (question) {
            CoreOnboardingQuestion.BusinessDescription -> answers.copy(businessDescription = null)
            CoreOnboardingQuestion.FirstPriority -> answers.copy(firstPriority = null)
            else -> answers
        }
        copy(answers = kept.copy(deferred = kept.deferred + question), editing = null, inputError = null)
            .refinePriorityAction(question)
    }

    /** Answers the team question in the conversation, or the team edit in review. */
    fun chooseTeamIntent(intent: CoreOnboardingTeamIntent): Boolean = transition {
        val asking = conflict == null && (
            (step == CoreOnboardingStep.Conversation && currentQuestion == CoreOnboardingQuestion.TeamIntent) ||
                (step == CoreOnboardingStep.Review && editing == CoreOnboardingQuestion.TeamIntent)
            )
        val current = plan
        when {
            !asking || current == null -> null
            intent == CoreOnboardingTeamIntent.Team && !current.allowsTeam -> copy(conflict = CoreOnboardingTeamConflict(current))
            else -> copy(answers = answers.copy(teamIntent = intent), editing = null, inputError = null)
        }
    }

    /** Resolves a conflict by staying on the single-person plan and recording "just me". */
    fun keepJustMe(): Boolean = transition {
        val pending = conflict ?: return@transition null
        copy(answers = answers.copy(teamIntent = CoreOnboardingTeamIntent.JustMe), conflict = null, editing = null)
            .withPlan(pending.singlePersonPlan).resumeAfterConflict()
    }

    /** Resolves a conflict by moving to [teamPlan]; the earlier demo payment no longer applies. */
    fun switchToTeamPlan(teamPlan: CoreOnboardingPlan): Boolean = transition {
        if (conflict == null || !teamPlan.allowsTeam) null
        else copy(answers = answers.copy(teamIntent = CoreOnboardingTeamIntent.Team), conflict = null, editing = null)
            .withPlan(teamPlan).resumeAfterConflict()
    }

    /** From review, reopen the plan step. Choosing the same plan returns to review without re-payment. */
    fun editPlan(): Boolean = transition {
        if (step == CoreOnboardingStep.Review && editing == null && discoveryEdit == null && conflict == null) {
            copy(step = CoreOnboardingStep.Plan, inputError = null)
        } else null
    }

    fun startEdit(question: CoreOnboardingQuestion): Boolean = transition {
        if (step == CoreOnboardingStep.Review && editing == null && discoveryEdit == null && conflict == null) copy(editing = question, inputError = null) else null
    }

    fun submitEdit(raw: String): Boolean = transition {
        val question = editing
        if (step != CoreOnboardingStep.Review || conflict != null || question == null || question.maxLength == null) null
        else submitText(question, raw)
    }

    fun deferDiscovery(): Boolean = transition {
        if (step != CoreOnboardingStep.Conversation || !answers.complete || conflict != null || discovery.next == null) null
        else copy(discovery = discovery.deferRemaining())
    }

    fun startDiscoveryEdit(question: CoreDiscoveryQuestion): Boolean = transition {
        if (step != CoreOnboardingStep.Review || editing != null || discoveryEdit != null ||
            conflict != null || question !in discovery.questions()) null
        else copy(discoveryEdit = question)
    }

    fun answerDiscovery(question: CoreDiscoveryQuestion, answer: CoreDiscoveryAnswer): Boolean = transition {
        val editing = step == CoreOnboardingStep.Review && discoveryEdit == question
        if (!editing && (step != CoreOnboardingStep.Conversation || !answers.complete || discovery.next != question || conflict != null)) {
            return@transition null
        }
        val next = discovery.answer(question, answer) ?: return@transition null
        if (next == discovery) return@transition copy(discoveryEdit = null)
        val previous = discovery.starterActions().associateBy { it.kind }
        val compatible = next.starterActions().filter { it == previous[it.kind] && it.kind !in question.affectedActions() }
            .map { it.kind }.toSet()
        copy(
            discovery = next,
            discoveryEdit = null,
            step = if (editing && next.next != null) CoreOnboardingStep.Conversation else step,
            starterChoice = starterChoice?.takeIf(compatible::contains),
            starterTitles = starterTitles.filterKeys(compatible::contains),
        )
    }

    fun selectNextAction(kind: CoreStarterKind): Boolean = transition {
        if (step != CoreOnboardingStep.Review || editing != null || discoveryEdit != null || conflict != null ||
            starterActions.none { it.kind == kind && it.requires.isEmpty() }) null
        else copy(starterChoice = kind)
    }

    fun editStarterTitle(kind: CoreStarterKind, raw: String): Boolean = transition {
        val title = raw.trim()
        if (step != CoreOnboardingStep.Review || discoveryEdit != null || editing != null || conflict != null ||
            starterActions.none { it.kind == kind } || !safeDiscoveryText(title, 160)) null
        else copy(starterTitles = starterTitles + (kind to title))
    }

    fun cancelEdit(): Boolean = transition {
        if (editing != null || discoveryEdit != null) copy(editing = null, discoveryEdit = null, inputError = null) else null
    }

    /**
     * Creates the single in-memory organization. Returns it only to the one call that performed the
     * transition; later or racing calls, and calls with unmet prerequisites, return null.
     */
    fun createOrganization(): CoreOnboardingOrganization? {
        while (true) {
            val current = mutable.value
            if (!current.canCreate) return null
            val answers = current.answers
            val organization = CoreOnboardingOrganization(
                ownerName = answers.ownerName!!,
                name = answers.organizationName!!,
                businessDescription = answers.businessDescription,
                teamIntent = answers.teamIntent!!,
                plan = current.plan!!,
                firstGoalDraft = answers.firstPriority,
                securityMethod = current.security.method!!,
                starterActions = current.starterActions,
                nextAction = current.nextAction,
                websiteStatus = current.discovery.declared(CoreDiscoveryQuestion.Website),
                websiteAddress = current.discovery.declared(CoreDiscoveryQuestion.WebsiteUrl),
            )
            if (mutable.compareAndSet(current, current.copy(step = CoreOnboardingStep.Workspace, organization = organization))) {
                return organization
            }
        }
    }

    /**
     * Steps back one level: a pending conflict, edit or open sheet closes first, then the previous step.
     * Returns false at the welcome and workspace steps, where the caller exits the preview.
     */
    fun back(): Boolean = transition {
        when {
            conflict != null -> copy(conflict = null)
            editing != null || discoveryEdit != null -> copy(editing = null, discoveryEdit = null, inputError = null)
            step == CoreOnboardingStep.Payment && payment.visible -> copy(payment = CoreOnboardingSheet.Cancelled)
            step == CoreOnboardingStep.Security && security.sheet.visible ->
                copy(security = security.copy(sheet = CoreOnboardingSheet.Cancelled))
            step == CoreOnboardingStep.Security && !securityChoiceVisible -> copy(securityChoiceVisible = true)
            step == CoreOnboardingStep.Welcome || step == CoreOnboardingStep.Workspace -> null
            else -> copy(step = CoreOnboardingStep.entries[step.ordinal - 1], securityChoiceVisible = true, inputError = null)
        }
    }

    /** Discards everything, including a created organization. */
    fun reset() {
        mutable.value = CoreOnboardingSnapshot()
    }

    private inline fun transition(change: CoreOnboardingSnapshot.() -> CoreOnboardingSnapshot?): Boolean {
        while (true) {
            val current = mutable.value
            val next = current.change() ?: return false
            if (next == current) return false
            if (mutable.compareAndSet(current, next)) return true
        }
    }
}

private val CoreOnboardingSheet.visible get() = this == CoreOnboardingSheet.Open || this == CoreOnboardingSheet.Failed

/** The first unmet prerequisite; forward navigation can never skip one. */
private fun CoreOnboardingSnapshot.proceed() = copy(
    step = when {
        plan == null -> CoreOnboardingStep.Plan
        !paymentConfirmed -> CoreOnboardingStep.Payment
        !securitySetupComplete -> CoreOnboardingStep.Security
        !answers.complete || discovery.next != null -> CoreOnboardingStep.Conversation
        else -> CoreOnboardingStep.Review
    },
    inputError = null,
)

private fun CoreOnboardingSnapshot.withPlan(choice: CoreOnboardingPlan) = if (choice == plan) this else copy(
    plan = choice,
    paidPlan = null,
    payment = CoreOnboardingSheet.Idle,
    repaymentRequired = repaymentRequired || paidPlan != null,
)

/** Mid-conversation the person keeps answering; elsewhere the first unmet prerequisite applies. */
private fun CoreOnboardingSnapshot.resumeAfterConflict() =
    if (step == CoreOnboardingStep.Conversation && paymentConfirmed) this else proceed()

private fun CoreOnboardingSnapshot.submitText(question: CoreOnboardingQuestion, raw: String): CoreOnboardingSnapshot {
    val (value, error) = question.validate(raw)
    return if (value == null) copy(inputError = error)
    else copy(answers = answers.withText(question, value), editing = null, inputError = null)
        .let { if (answers.text(question) == value) it else it.refinePriorityAction(question) }
}

private fun CoreOnboardingSnapshot.refinePriorityAction(question: CoreOnboardingQuestion): CoreOnboardingSnapshot =
    if (question != CoreOnboardingQuestion.FirstPriority) this else copy(
        starterChoice = starterChoice?.takeUnless { it == CoreStarterKind.PlanPriority },
        starterTitles = starterTitles - CoreStarterKind.PlanPriority,
    )

private fun CoreDiscoveryQuestion.affectedActions(): Set<CoreStarterKind> = when (this) {
    CoreDiscoveryQuestion.Country, CoreDiscoveryQuestion.CountryName, CoreDiscoveryQuestion.Region,
    CoreDiscoveryQuestion.Registration, CoreDiscoveryQuestion.Entity, CoreDiscoveryQuestion.Formation,
    CoreDiscoveryQuestion.Ein -> setOf(CoreStarterKind.Registration, CoreStarterKind.EinGuidance)
    CoreDiscoveryQuestion.Website, CoreDiscoveryQuestion.WebsiteUrl, CoreDiscoveryQuestion.WebsiteIntent ->
        setOf(CoreStarterKind.ReviewSite, CoreStarterKind.SiteBrief, CoreStarterKind.DomainMigration, CoreStarterKind.DomainConnection)
    CoreDiscoveryQuestion.Domain, CoreDiscoveryQuestion.Hostname, CoreDiscoveryQuestion.DomainHelp, CoreDiscoveryQuestion.DomainAccess ->
        setOf(CoreStarterKind.DomainAccess, CoreStarterKind.DomainOptions, CoreStarterKind.DomainMigration, CoreStarterKind.DomainConnection)
    CoreDiscoveryQuestion.Customers, CoreDiscoveryQuestion.Offer, CoreDiscoveryQuestion.Delivery ->
        setOf(CoreStarterKind.ClarifyOffer, CoreStarterKind.SiteBrief)
    CoreDiscoveryQuestion.Tools -> setOf(CoreStarterKind.ReviewTools)
    else -> emptySet()
}
