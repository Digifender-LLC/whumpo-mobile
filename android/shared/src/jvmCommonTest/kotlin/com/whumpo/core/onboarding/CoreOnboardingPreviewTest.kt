package com.whumpo.core.onboarding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreOnboardingPreviewTest {
    private fun CoreOnboardingFlow.snapshot() = state.value

    private fun CoreOnboardingFlow.completeSecurity(method: CoreOnboardingSecurityMethod = CoreOnboardingSecurityMethod.Passkeys) {
        assertTrue(selectSecurityMethod(method))
        while (!snapshot().securitySetupComplete) {
            val task = assertNotNull(snapshot().security.nextTask)
            assertTrue(openSecuritySheet(task))
            assertTrue(confirmSecurityTask(task))
        }
    }

    private fun paidAndRegistered(plan: CoreOnboardingPlan) = CoreOnboardingFlow().apply {
        assertTrue(start())
        assertTrue(selectPlan(plan))
        assertTrue(openPaymentSheet())
        assertTrue(confirmPayment())
        completeSecurity()
    }

    private fun CoreOnboardingFlow.answerAll(intent: CoreOnboardingTeamIntent = CoreOnboardingTeamIntent.JustMe) {
        assertTrue(answer("  Avery Example  "))
        assertTrue(answer("Example Studio"))
        assertTrue(answer("Synthetic bookkeeping studio"))
        assertTrue(chooseTeamIntent(intent))
        assertTrue(answer("Plan this week's priorities"))
    }

    private fun readyForReview(plan: CoreOnboardingPlan = CoreOnboardingPlan.Solo) = paidAndRegistered(plan).apply {
        answerAll()
        assertTrue(proceedFromCompletedStep())
        assertEquals(CoreOnboardingStep.Review, snapshot().step)
    }

    @Test
    fun prerequisitesCannotBeSkippedOrReorderedByInvalidActions() {
        val flow = CoreOnboardingFlow()
        val initial = flow.snapshot()
        assertFalse(flow.selectPlan(CoreOnboardingPlan.Solo))
        assertFalse(flow.openPaymentSheet())
        assertFalse(flow.confirmPayment())
        assertFalse(flow.openSecuritySheet(CoreOnboardingSecurityTask.FirstPasskey))
        assertFalse(flow.answer("Avery"))
        assertFalse(flow.proceedFromCompletedStep())
        assertNull(flow.createOrganization())
        assertEquals(initial, flow.snapshot())

        assertTrue(flow.start())
        assertEquals(CoreOnboardingStep.Plan, flow.snapshot().step)
        assertFalse(flow.start())
        assertTrue(flow.selectPlan(CoreOnboardingPlan.Growth))
        assertEquals(CoreOnboardingStep.Payment, flow.snapshot().step)
        assertFalse(flow.confirmPayment(), "Confirmation requires the open demo sheet")
        assertFalse(flow.proceedFromCompletedStep(), "Unpaid payment step cannot be skipped")
        assertFalse(flow.openSecuritySheet(CoreOnboardingSecurityTask.FirstPasskey))
        assertTrue(flow.openPaymentSheet())
        assertFalse(flow.openPaymentSheet())
        assertTrue(flow.confirmPayment())
        assertEquals(CoreOnboardingStep.Security, flow.snapshot().step)
        assertFalse(flow.answer("Avery"), "Conversation requires completed security setup")
        assertFalse(flow.confirmSecurityTask(CoreOnboardingSecurityTask.FirstPasskey))
        assertFalse(flow.openSecuritySheet(CoreOnboardingSecurityTask.FirstPasskey))
        flow.completeSecurity()
        assertEquals(CoreOnboardingStep.Conversation, flow.snapshot().step)
        assertFalse(flow.proceedFromCompletedStep(), "Unanswered conversation cannot be skipped")
        assertNull(flow.createOrganization())
    }

    @Test
    fun paymentAndBothSecurityMethodsSupportCancellationFailureAndRetry() {
        for (method in CoreOnboardingSecurityMethod.entries) {
            val flow = CoreOnboardingFlow()
            flow.start()
            flow.selectPlan(CoreOnboardingPlan.Solo)
            assertTrue(flow.openPaymentSheet())
            assertTrue(flow.cancelPayment())
            assertEquals(CoreOnboardingSheet.Cancelled, flow.snapshot().payment)
            assertFalse(flow.snapshot().paymentConfirmed)
            assertFalse(flow.confirmPayment())
            assertTrue(flow.openPaymentSheet())
            assertTrue(flow.failPayment())
            assertFalse(flow.confirmPayment())
            assertTrue(flow.openPaymentSheet())
            assertTrue(flow.confirmPayment())
            assertFalse(flow.confirmPayment())
            assertTrue(flow.selectSecurityMethod(method))
            while (!flow.snapshot().securitySetupComplete) {
                val task = assertNotNull(flow.snapshot().security.nextTask)
                assertFalse(flow.confirmSecurityTask(task))
                assertTrue(flow.openSecuritySheet(task))
                assertTrue(flow.back())
                assertEquals(CoreOnboardingSheet.Cancelled, flow.snapshot().security.sheet)
                assertEquals(CoreOnboardingStep.Security, flow.snapshot().step)
                assertTrue(flow.openSecuritySheet(task))
                assertTrue(flow.failSecurityTask(task))
                assertFalse(flow.confirmSecurityTask(task))
                assertTrue(flow.cancelSecurityTask(task))
                assertTrue(flow.openSecuritySheet(task))
                assertTrue(flow.confirmSecurityTask(task))
                assertFalse(flow.confirmSecurityTask(task), "Repeated confirmation cannot complete the next task")
            }
            assertEquals(CoreOnboardingStep.Conversation, flow.snapshot().step)
        }
    }

    @Test
    fun partialSecurityAndUnsavedRecoveryCannotBypassSetup() {
        for (method in CoreOnboardingSecurityMethod.entries) {
            val flow = CoreOnboardingFlow()
            flow.start(); flow.selectPlan(CoreOnboardingPlan.Solo)
            assertFalse(flow.selectSecurityMethod(method), "Payment is still required")
            flow.openPaymentSheet(); flow.confirmPayment()
            assertTrue(flow.selectSecurityMethod(method))
            val tasks = if (method == CoreOnboardingSecurityMethod.Passkeys) {
                listOf(CoreOnboardingSecurityTask.FirstPasskey, CoreOnboardingSecurityTask.BackupPasskey)
            } else listOf(CoreOnboardingSecurityTask.Password, CoreOnboardingSecurityTask.Authenticator)
            for (task in tasks) {
                assertFalse(flow.openSecuritySheet(CoreOnboardingSecurityTask.Recovery))
                assertFalse(flow.confirmSecurityTask(CoreOnboardingSecurityTask.Recovery))
                assertFalse(flow.answer("Must not be accepted"))
                assertFalse(flow.proceedFromCompletedStep())
                assertNull(flow.createOrganization())
                flow.openSecuritySheet(task); flow.confirmSecurityTask(task)
                assertFalse(flow.snapshot().securitySetupComplete)
            }
            assertTrue(flow.snapshot().security.credentialsComplete)
            assertFalse(flow.snapshot().security.recoverySaved)
            assertFalse(flow.proceedFromCompletedStep())
            assertNull(flow.createOrganization())
            flow.openSecuritySheet(CoreOnboardingSecurityTask.Recovery)
            assertTrue(flow.confirmSecurityTask(CoreOnboardingSecurityTask.Recovery))
            assertTrue(flow.snapshot().securitySetupComplete)
            if (method == CoreOnboardingSecurityMethod.PasswordAuthenticator) {
                assertFalse(flow.snapshot().security.firstPasskey)
                assertFalse(flow.snapshot().security.backupPasskey)
            }
        }
    }

    @Test
    fun changingMethodClearsSecurityButKeepsAnswersAndPayment() {
        val flow = readyForReview()
        val before = flow.snapshot()
        assertTrue(flow.editSecurity())
        assertTrue(flow.selectSecurityMethod(CoreOnboardingSecurityMethod.PasswordAuthenticator))
        assertEquals(CoreOnboardingSecurity(method = CoreOnboardingSecurityMethod.PasswordAuthenticator), flow.snapshot().security)
        assertEquals(before.answers, flow.snapshot().answers)
        assertEquals(before.paidPlan, flow.snapshot().paidPlan)
        assertFalse(flow.snapshot().canCreate)
        assertNull(flow.createOrganization())
        assertFalse(flow.openSecuritySheet(CoreOnboardingSecurityTask.FirstPasskey))
        flow.openSecuritySheet(CoreOnboardingSecurityTask.Password)
        assertTrue(flow.confirmSecurityTask(CoreOnboardingSecurityTask.Password))
        flow.openSecuritySheet(CoreOnboardingSecurityTask.Authenticator)
        assertTrue(flow.selectSecurityMethod(CoreOnboardingSecurityMethod.Passkeys))
        assertFalse(flow.confirmSecurityTask(CoreOnboardingSecurityTask.Authenticator), "An old method's callback cannot complete a new setup")
        assertEquals(CoreOnboardingSecurity(method = CoreOnboardingSecurityMethod.Passkeys), flow.snapshot().security)
        assertTrue(flow.selectSecurityMethod(CoreOnboardingSecurityMethod.PasswordAuthenticator))
        for (task in listOf(CoreOnboardingSecurityTask.Password, CoreOnboardingSecurityTask.Authenticator, CoreOnboardingSecurityTask.Recovery)) {
            assertTrue(flow.openSecuritySheet(task)); assertTrue(flow.confirmSecurityTask(task))
        }
        assertEquals(CoreOnboardingStep.Review, flow.snapshot().step)
        assertTrue(flow.snapshot().canCreate)
        val security = flow.snapshot().security
        flow.editPlan(); flow.selectPlan(CoreOnboardingPlan.Growth)
        assertEquals(security, flow.snapshot().security, "Changing plans keeps completed account security")
        assertNull(flow.createOrganization(), "New plan still needs payment")
        flow.openPaymentSheet(); flow.confirmPayment()
        assertEquals(CoreOnboardingSecurityMethod.PasswordAuthenticator, assertNotNull(flow.createOrganization()).securityMethod)
    }

    @Test
    fun resetClearsPartialSetupForBothMethods() {
        for (method in CoreOnboardingSecurityMethod.entries) {
            val flow = CoreOnboardingFlow()
            flow.start(); flow.selectPlan(CoreOnboardingPlan.Business)
            flow.openPaymentSheet(); flow.confirmPayment(); flow.selectSecurityMethod(method)
            val task = assertNotNull(flow.snapshot().security.nextTask)
            flow.openSecuritySheet(task); flow.confirmSecurityTask(task)
            flow.reset()
            assertEquals(CoreOnboardingSnapshot(), flow.snapshot())
            assertFalse(flow.confirmSecurityTask(task))
        }
    }

    @Test
    fun invalidAnswersAreReportedWithoutTruncationOrAdvancing() {
        val flow = paidAndRegistered(CoreOnboardingPlan.Solo)
        assertFalse(flow.chooseTeamIntent(CoreOnboardingTeamIntent.JustMe), "Team intent is not the current question")
        assertTrue(flow.answer("   "))
        assertEquals(CoreOnboardingInputError(CoreOnboardingQuestion.OwnerName, "   ", CoreOnboardingInputProblem.Empty), flow.snapshot().inputError)
        val long = "a".repeat(61)
        assertTrue(flow.answer(long))
        assertEquals(CoreOnboardingInputError(CoreOnboardingQuestion.OwnerName, long, CoreOnboardingInputProblem.TooLong), flow.snapshot().inputError)
        assertNull(flow.snapshot().answers.ownerName)
        assertEquals(CoreOnboardingQuestion.OwnerName, flow.snapshot().currentQuestion)
        assertTrue(flow.answer("  " + "a".repeat(60) + "  "))
        assertEquals("a".repeat(60), flow.snapshot().answers.ownerName)
        assertNull(flow.snapshot().inputError)

        assertTrue(flow.answer("o".repeat(81)))
        assertEquals(CoreOnboardingInputProblem.TooLong, flow.snapshot().inputError?.problem)
        assertTrue(flow.answer("o".repeat(80)))
        assertTrue(flow.answer("d".repeat(281)))
        assertEquals(CoreOnboardingQuestion.BusinessDescription, flow.snapshot().currentQuestion)
        assertTrue(flow.answer("d".repeat(280)))
        assertFalse(flow.answer("Team"), "The team question takes a choice, not text")
        assertTrue(flow.chooseTeamIntent(CoreOnboardingTeamIntent.JustMe))
        assertTrue(flow.answer("p".repeat(161)))
        assertNull(flow.snapshot().answers.firstPriority)
        assertTrue(flow.answer("p".repeat(160)))
        assertTrue(flow.snapshot().answers.complete)
    }

    @Test
    fun teamIntentOnSinglePersonPlanCanKeepJustMe() {
        val flow = paidAndRegistered(CoreOnboardingPlan.Growth)
        flow.answer("Avery")
        flow.answer("Example Studio")
        flow.answer("Synthetic studio")
        assertTrue(flow.chooseTeamIntent(CoreOnboardingTeamIntent.Team))
        assertEquals(CoreOnboardingTeamConflict(CoreOnboardingPlan.Growth), flow.snapshot().conflict)
        assertNull(flow.snapshot().answers.teamIntent)
        assertFalse(flow.answer("Blocked while the conflict is open"))
        assertFalse(flow.switchToTeamPlan(CoreOnboardingPlan.Solo), "A single-person plan cannot resolve a team conflict")
        assertTrue(flow.keepJustMe())
        val kept = flow.snapshot()
        assertNull(kept.conflict)
        assertEquals(CoreOnboardingTeamIntent.JustMe, kept.answers.teamIntent)
        assertEquals(CoreOnboardingPlan.Growth, kept.plan)
        assertTrue(kept.paymentConfirmed)
        assertEquals(CoreOnboardingStep.Conversation, kept.step)
        assertEquals(CoreOnboardingQuestion.FirstPriority, kept.currentQuestion)
    }

    @Test
    fun switchingToTeamPlanRequiresRepaymentAndPreservesAnswers() {
        val flow = paidAndRegistered(CoreOnboardingPlan.Solo)
        flow.answer("Avery")
        flow.answer("Example Studio")
        flow.answer("Synthetic studio")
        flow.chooseTeamIntent(CoreOnboardingTeamIntent.Team)
        assertTrue(flow.switchToTeamPlan(CoreOnboardingPlan.Business))
        val switched = flow.snapshot()
        assertEquals(CoreOnboardingStep.Payment, switched.step)
        assertEquals(CoreOnboardingPlan.Business, switched.plan)
        assertFalse(switched.paymentConfirmed)
        assertTrue(switched.repaymentRequired)
        assertTrue(switched.securitySetupComplete, "Security setup belongs to the person, not the plan")
        assertEquals(CoreOnboardingTeamIntent.Team, switched.answers.teamIntent)
        assertEquals("Example Studio", switched.answers.organizationName)
        assertFalse(flow.proceedFromCompletedStep())
        assertTrue(flow.openPaymentSheet())
        assertTrue(flow.confirmPayment())
        assertFalse(flow.snapshot().repaymentRequired)
        assertEquals(CoreOnboardingStep.Conversation, flow.snapshot().step)
        assertEquals(CoreOnboardingQuestion.FirstPriority, flow.snapshot().currentQuestion)
        assertTrue(flow.answer("Organize customer follow-ups"))
        assertTrue(flow.proceedFromCompletedStep())
        assertEquals(CoreOnboardingPlan.Business, assertNotNull(flow.createOrganization()).plan)
    }

    @Test
    fun reviewEditsValidateAndRetainPreviousAnswers() {
        val flow = readyForReview()
        assertEquals("Avery Example", flow.snapshot().answers.ownerName)
        assertFalse(flow.submitEdit("No edit open"))
        assertTrue(flow.startEdit(CoreOnboardingQuestion.OrganizationName))
        assertFalse(flow.snapshot().canCreate)
        assertNull(flow.createOrganization(), "Creation waits for the open edit")
        val long = "x".repeat(81)
        assertTrue(flow.submitEdit(long))
        assertEquals(long, flow.snapshot().inputError?.rejected)
        assertEquals("Example Studio", flow.snapshot().answers.organizationName)
        assertEquals(CoreOnboardingQuestion.OrganizationName, flow.snapshot().editing)
        assertTrue(flow.submitEdit(" Renamed Studio "))
        assertEquals("Renamed Studio", flow.snapshot().answers.organizationName)
        assertNull(flow.snapshot().editing)

        assertTrue(flow.startEdit(CoreOnboardingQuestion.FirstPriority))
        assertTrue(flow.back(), "Back cancels the edit before leaving review")
        assertNull(flow.snapshot().editing)
        assertEquals(CoreOnboardingStep.Review, flow.snapshot().step)
        assertTrue(flow.snapshot().canCreate)
    }

    @Test
    fun reviewTeamEditAndPlanEditUseTheSameConflictAndRepaymentRules() {
        val flow = readyForReview(CoreOnboardingPlan.Solo)
        assertTrue(flow.startEdit(CoreOnboardingQuestion.TeamIntent))
        assertTrue(flow.chooseTeamIntent(CoreOnboardingTeamIntent.Team))
        assertNotNull(flow.snapshot().conflict)
        assertNull(flow.createOrganization())
        assertTrue(flow.keepJustMe())
        assertEquals(CoreOnboardingStep.Review, flow.snapshot().step)
        assertTrue(flow.snapshot().paymentConfirmed)

        assertTrue(flow.editPlan())
        assertEquals(CoreOnboardingStep.Plan, flow.snapshot().step)
        assertTrue(flow.selectPlan(CoreOnboardingPlan.Solo))
        assertEquals(CoreOnboardingStep.Review, flow.snapshot().step, "Keeping the paid plan returns to review")
        assertTrue(flow.snapshot().paymentConfirmed)

        assertTrue(flow.editPlan())
        assertTrue(flow.selectPlan(CoreOnboardingPlan.BusinessPlus))
        val changed = flow.snapshot()
        assertEquals(CoreOnboardingStep.Payment, changed.step)
        assertTrue(changed.repaymentRequired)
        assertEquals("Avery Example", changed.answers.ownerName)
        assertTrue(flow.openPaymentSheet())
        assertTrue(flow.confirmPayment())
        assertEquals(CoreOnboardingStep.Review, flow.snapshot().step)

        assertTrue(flow.startEdit(CoreOnboardingQuestion.TeamIntent))
        assertTrue(flow.chooseTeamIntent(CoreOnboardingTeamIntent.Team))
        assertNull(flow.snapshot().conflict, "Team plans allow a team")
        assertTrue(flow.editPlan())
        assertTrue(flow.selectPlan(CoreOnboardingPlan.Growth))
        assertEquals(CoreOnboardingTeamConflict(CoreOnboardingPlan.Growth), flow.snapshot().conflict)
        assertEquals(CoreOnboardingPlan.BusinessPlus, flow.snapshot().plan, "The conflicting plan is not applied yet")
        assertTrue(flow.keepJustMe())
        val solo = flow.snapshot()
        assertEquals(CoreOnboardingPlan.Growth, solo.plan)
        assertEquals(CoreOnboardingTeamIntent.JustMe, solo.answers.teamIntent)
        assertEquals(CoreOnboardingStep.Payment, solo.step)
        assertNull(flow.createOrganization())
    }

    @Test
    fun backNeverSkipsForwardAndExitsOnlyAtTheEnds() {
        val flow = readyForReview()
        assertTrue(flow.back())
        assertEquals(CoreOnboardingStep.Conversation, flow.snapshot().step)
        assertNull(flow.createOrganization())
        assertTrue(flow.back())
        assertTrue(flow.back())
        assertTrue(flow.back())
        assertTrue(flow.back())
        assertEquals(CoreOnboardingStep.Welcome, flow.snapshot().step)
        assertFalse(flow.back(), "The caller exits from welcome")
        assertTrue(flow.start())
        assertEquals(CoreOnboardingStep.Review, flow.snapshot().step, "Forward resumes at the first unmet step")
    }

    @Test
    fun creationHappensExactlyOnceEvenWhenRaced() {
        val flow = readyForReview()
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val results = (1..32).map { pool.submit<CoreOnboardingOrganization?> { gate.await(); flow.createOrganization() } }
        gate.countDown()
        val created = results.mapNotNull { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, created.size)
        assertEquals(
            CoreOnboardingOrganization(
                ownerName = "Avery Example",
                name = "Example Studio",
                businessDescription = "Synthetic bookkeeping studio",
                teamIntent = CoreOnboardingTeamIntent.JustMe,
                plan = CoreOnboardingPlan.Solo,
                firstGoalDraft = "Plan this week's priorities",
                securityMethod = CoreOnboardingSecurityMethod.Passkeys,
            ),
            created.single(),
        )
        assertEquals(CoreOnboardingStep.Workspace, flow.snapshot().step)
        assertNull(flow.createOrganization())
        assertFalse(flow.back(), "The workspace exits instead of reopening review")
        assertFalse(flow.startEdit(CoreOnboardingQuestion.OwnerName))
        assertFalse(flow.editPlan())
    }

    @Test
    fun resetDiscardsEverythingIncludingTheOrganization() {
        val flow = readyForReview()
        assertNotNull(flow.createOrganization())
        flow.reset()
        assertEquals(CoreOnboardingSnapshot(), flow.snapshot())
        assertNull(flow.createOrganization())
        assertFalse(flow.snapshot().securitySetupComplete)
    }

    @Test
    fun validationTrimsAndRejectsWithoutTruncating() {
        assertEquals("Avery" to null, CoreOnboardingQuestion.OwnerName.validate("  Avery\n"))
        val (value, error) = CoreOnboardingQuestion.FirstPriority.validate("g".repeat(161))
        assertNull(value)
        assertEquals("g".repeat(161), error?.rejected)
        assertEquals(CoreOnboardingInputProblem.TooLong, error?.problem)
    }
}
