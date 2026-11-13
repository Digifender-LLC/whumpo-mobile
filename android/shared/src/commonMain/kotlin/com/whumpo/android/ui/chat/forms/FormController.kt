package com.whumpo.android.ui.chat.forms

import com.whumpo.core.api.MobileApiException
import com.whumpo.core.api.newRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Instant

/** [FormApi] plus `interaction.resources` paging. */
interface FormPagesApi : FormApi, FormContinuationApi {
    suspend fun resources(interactionId: String, definitionDigest: String, fieldId: String, before: JsonElement?): JsonObject
}

/** What the chat shows for the form that is open, if any. */
sealed interface FormPane {
    val interactionId: String

    data class Loading(override val interactionId: String) : FormPane
    data class Failed(override val interactionId: String) : FormPane
    data class Shown(override val interactionId: String, val screen: FormScreenState) : FormPane
}

/**
 * One open form for the signed-in account (docs/android/chat-forms.md). Everything lives only in memory. [close]
 * only dismisses the form: an unresolved send and the owner's unsent answers are kept for that interaction and
 * come back on reopen, while loaded project names and submitted answers are dropped. [reset] (sign-out or account
 * change) drops everything. Every reply is checked against the visit that asked for it, so a late reply for another
 * account, form, digest or field is ignored.
 */
class FormController(
    private val api: FormPagesApi,
    private val wire: ProposedFormWire,
    private val scope: CoroutineScope,
    private val currentChatRevision: () -> Int? = { null },
    private val onResultRequested: (String) -> Unit = {},
    private val now: () -> Instant = { Clock.System.now() },
    private val newId: () -> String = ::newRequestId,
) : FormActions {
    private val _pane = MutableStateFlow<FormPane?>(null)
    val pane: StateFlow<FormPane?> = _pane.asStateFlow()
    private val mutableCards = MutableStateFlow<Map<String, FormCardSummary>>(emptyMap())
    val cards: StateFlow<Map<String, FormCardSummary>> = mutableCards.asStateFlow()

    private var generation = 0
    private var submitter: FormSubmitter? = null
    private var watch: Job? = null
    private var pages = mapOf<String, ResourceField>()
    private var appliedProblem: String? = null
    private var visitId: String? = null
    private var keptAnswers: KeptAnswers? = null
    private var seenReread: FormRead? = null
    private var readPendingAtOpen = false
    private val kept = mutableMapOf<String, Kept>()
    private val continuations = mutableMapOf<String, FormContinuation>()
    private var activeContinuation: FormContinuation? = null
    private var continuationWatch: Job? = null
    private var reloadContinuationRevisions: Map<String, Int>? = null

    /** Unsent answers, the read they were made against and their [baseline] at [revision], reconciled on reopen. */
    private class KeptAnswers(
        val answers: Map<String, Answer>,
        val read: FormRead.Form,
        val review: FormReview?,
        val baseline: Map<String, Answer>,
        val revision: Int,
    )

    /**
     * Save-draft answers per interaction: [pending] is the request ID and answers of the latest save sent; [base] is
     * the answers of the latest save confirmed committed, at [revision]. Only a committed save moves [base].
     */
    private data class Saved(val pending: Pair<String, Map<String, Answer>>?, val base: Map<String, Answer>?, val revision: Int)

    private val saves = mutableMapOf<String, Saved>()

    /** What a closed form keeps until [reset]: its unresolved send and its unsent answers. */
    private class Kept(val submitter: FormSubmitter?, val answers: KeptAnswers?)

    /** Opens [interactionId] for a new visit and reads it once. */
    fun open(interactionId: String) {
        close()
        val g = generation
        val continuationRevisions = continuations.mapValues { it.value.revision }
        val retained = kept.remove(interactionId)
        val sub = retained?.submitter ?: FormSubmitter(api, wire, scope, newId)
        submitter = sub
        visitId = interactionId
        keptAnswers = retained?.answers
        // A reread from before the form was closed is not applied again.
        seenReread = sub.state.value.reread
        readPendingAtOpen = sub.state.value.reading
        _pane.value = FormPane.Loading(interactionId)
        watch = scope.launch { sub.state.collect { if (g == generation) onSend(it) } }
        scope.launch {
            if (g != generation) return@launch
            val read = try {
                wire.decode(api.read(interactionId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: MobileApiException.ClientUpdateRequired) {
                FormRead.UpdateRequired
            } catch (_: Exception) {
                null
            }
            if (g != generation) return@launch
            // A submit may settle and finish its follow-up while this pane is still loading.
            val send = sub.state.value
            val latest = send.reread?.takeIf {
                !readPendingAtOpen && !send.unresolved && it !== seenReread && !olderRead(it, read)
            } ?: read
            if (latest == null) {
                _pane.value = FormPane.Failed(interactionId)
                return@launch
            }
            if (latest is FormRead.Form && latest.interactionId != interactionId) {
                _pane.value = FormPane.Shown(interactionId, FormScreenState(FormRead.UpdateRequired))
                return@launch
            }
            if (!send.unresolved) seenReread = send.reread
            val (answers, review) = (latest as? FormRead.Form)?.let(::starting) ?: (emptyMap<String, Answer>() to null)
            show(interactionId, latest, answers, review, continuationRevisions)
            if (latest is FormRead.Form) loadFirstPages(latest)
        }
    }

    /** Dismisses the form, keeping an unresolved send and unsent answers for a reopen. */
    fun close() {
        generation += 1
        watch?.cancel()
        watch = null
        continuationWatch?.cancel()
        continuationWatch = null
        activeContinuation = null
        reloadContinuationRevisions = null
        readPendingAtOpen = false
        val id = visitId
        val sub = submitter
        if (id != null && sub != null) keep(id, sub)
        visitId = null
        keptAnswers = null
        submitter = null
        pages = emptyMap()
        appliedProblem = null
        _pane.value = null
    }

    /** Sign-out or account change: drops every kept send and answer and ignores replies still in flight. */
    fun reset() {
        close()
        kept.values.forEach { it.submitter?.reset() }
        kept.clear()
        saves.clear()
        continuations.values.forEach { it.reset() }
        continuations.clear()
        mutableCards.value = emptyMap()
    }

    private fun keep(interactionId: String, sub: FormSubmitter) {
        val send = sub.state.value
        val screen = shown()?.screen
        val form = screen?.read as? FormRead.Form
        settleSaves(interactionId, send)
        val attempt = send.attempt
        // A committed save leaves the form open, so later edits are kept; a committed submit or cancel keeps nothing.
        val terminal = attempt?.outcome == SendOutcome.Committed && attempt.request.operation != "save-draft"
        if (terminal) saves.remove(interactionId)
        val answers = when {
            screen == null -> keptAnswers
            terminal -> null
            form == null || form.state != FormState.Open || form.submission != null -> null
            else -> {
                val (baseline, revision) = baseline(form)
                KeptAnswers(screen.answers, form, screen.review, baseline, revision)
            }
        }
        if (!send.unresolved) sub.reset()
        if (send.unresolved || answers != null) kept[interactionId] = Kept(sub.takeIf { send.unresolved }, answers)
    }

    /** Kept unsent answers reconciled with this read; otherwise the saved draft, or the definition's defaults. */
    private fun starting(form: FormRead.Form): Pair<Map<String, Answer>, FormReview?> {
        val k = keptAnswers
        keptAnswers = null
        settleSaves(form.interactionId, submitter?.state?.value)
        if (k == null || form.state != FormState.Open || form.submission != null) return form.definition.initialAnswers(form.draft) to null
        // A save committed while the form was closed moves the baseline too.
        val s = saves[form.interactionId]?.takeIf { it.base != null && it.revision > k.revision }
        val baseline = s?.base ?: k.baseline
        val revision = s?.revision ?: k.revision
        if (k.read.definitionDigest == form.definitionDigest && revision >= form.answerRevision) return k.answers to k.review
        return reconcile(k.read.definition, baseline, k.answers, form)
    }

    /** The answers edits are measured against: the latest committed save when it is current, else the read's own. */
    private fun baseline(form: FormRead.Form): Pair<Map<String, Answer>, Int> {
        val s = saves[form.interactionId]
        val base = s?.base
        if (s != null && base != null && s.revision >= form.answerRevision) return base to s.revision
        return form.definition.initialAnswers(form.draft) to form.answerRevision
    }

    /** Moves the saved baseline to a pending save's own answers once its outcome is committed; a refusal drops it. */
    private fun settleSaves(interactionId: String, send: FormSendState?) {
        val s = saves[interactionId] ?: return
        val pending = s.pending ?: return
        val attempt = send?.attempt?.takeIf { it.request.requestId == pending.first } ?: return
        when (attempt.outcome) {
            SendOutcome.Committed -> saves[interactionId] = Saved(null, pending.second, attempt.receipt?.answerRevision ?: s.revision)
            SendOutcome.Rejected -> saves[interactionId] = s.copy(pending = null)
            else -> Unit
        }
    }

    /**
     * A newer read never overwrites the owner's edits. Answers equal to [baseline] (unedited) follow [newer]. Otherwise answers whose field
     * keeps its id and type stay (Send still validates them against the new constraints), answers for removed or
     * retyped fields are taken out, and a differing newer draft waits in [FormReview] for the owner's choice.
     */
    private fun reconcile(
        old: FormDefinition,
        baseline: Map<String, Answer>,
        local: Map<String, Answer>,
        newer: FormRead.Form,
    ): Pair<Map<String, Answer>, FormReview?> {
        val fresh = newer.definition.initialAnswers(newer.draft)
        if (local == baseline) return fresh to null
        val now = newer.definition.fields.associateBy { it.id }
        val before = old.fields.associateBy { it.id }
        val kept = local.filter { (id, _) ->
            val o = before[id]
            val n = now[id]
            o != null && n != null && o.kind.shape() == n.kind.shape()
        }
        val dropped = old.fields.filter { it.id in local && it.id !in kept }.map { it.label }
        val server = newer.draft
        val differs = if (server == null) {
            emptySet()
        } else {
            val mine = newer.definition.sendableAnswers(kept)
            val theirs = newer.definition.sendableAnswers(server)
            (mine.keys + theirs.keys).filterTo(linkedSetOf()) { mine[it] != theirs[it] }
        }
        val review = if (differs.isEmpty() && dropped.isEmpty()) null else FormReview(server, differs, dropped)
        return kept to review
    }

    private fun FieldKind.shape(): Any = when (this) {
        is FieldKind.Text -> "text:$long"
        is FieldKind.Resource -> "resource:$multiple"
        else -> this::class
    }

    private fun shown(): FormPane.Shown? = _pane.value as? FormPane.Shown

    private fun olderRead(candidate: FormRead?, current: FormRead?) =
        candidate is FormRead.Form && current is FormRead.Form && candidate.answerRevision < current.answerRevision

    private fun show(interactionId: String, read: FormRead, answers: Map<String, Answer>, review: FormReview?, continuationRevisions: Map<String, Int>) {
        val send = submitter?.state?.value ?: FormSendState()
        _pane.value = FormPane.Shown(interactionId, decorate(FormScreenState(read, answers, send = send, review = review)))
        if (read is FormRead.Form) recordCard(read, send)
        bindContinuation(read, continuationRevisions)
    }

    private fun decorate(screen: FormScreenState) = screen.copy(
        resourceNames = pages.values.fold(emptyMap()) { all, field -> all + field.names },
        moreResources = pages.filterValues { it.canLoadMore }.keys,
        resourceLoading = pages.filterValues { it.loading }.keys,
        resourceFailures = pages.filterValues { it.failed }.keys,
    )

    private fun onSend(send: FormSendState) {
        // This read began before the new visit's initial read; its late reply cannot replace that newer snapshot.
        if (readPendingAtOpen && !send.reading) {
            seenReread = send.reread
            readPendingAtOpen = false
        }
        val current = shown() ?: return
        settleSaves(current.interactionId, send)
        var screen = current.screen.copy(send = send)
        val reread = send.reread
        val form = screen.read as? FormRead.Form
        var newDigest: FormRead.Form? = null
        val newRead = reread != null && reread !== seenReread && !send.unresolved
        if (newRead) seenReread = reread
        val freshRead = newRead && !olderRead(reread, form)
        if (freshRead) {
            when {
                reread is FormRead.UpdateRequired -> screen = FormScreenState(FormRead.UpdateRequired, send = send)
                reread is FormRead.Form && reread.interactionId == current.interactionId -> {
                    if (form != null && reread.definitionDigest != form.definitionDigest) {
                        pages = emptyMap()
                        newDigest = reread
                    }
                    val open = reread.state == FormState.Open && reread.submission == null
                    val (answers, review) = if (form != null && open) {
                        reconcile(form.definition, baseline(form).first, screen.answers, reread)
                    } else {
                        (reread.draft ?: screen.answers) to null
                    }
                    screen = screen.copy(read = reread, answers = answers, errors = emptyMap(), review = review)
                }
            }
        }
        val attempt = send.attempt
        if (attempt != null && attempt.outcome == SendOutcome.Rejected && attempt.request.requestId != appliedProblem) {
            appliedProblem = attempt.request.requestId
            val fields = (screen.read as? FormRead.Form)?.definition?.visibleFields(screen.answers)?.map { it.id }.orEmpty()
            val fieldId = attempt.fieldId
            if (fieldId != null && fieldId in fields && attempt.problem != null) {
                screen = screen.copy(errors = screen.errors + (fieldId to attempt.problem), focusFirstError = screen.focusFirstError + 1)
            }
        }
        _pane.value = current.copy(screen = decorate(screen))
        (screen.read as? FormRead.Form)?.let { recordCard(it, send) }
        if (attempt?.outcome == SendOutcome.Committed && attempt.request.operation == "cancel" && form != null) {
            _pane.value = current.copy(screen = decorate(screen.copy(
                read = form.copy(state = FormState.Canceled, draft = null), answers = emptyMap(), errors = emptyMap(), review = null,
            )))
        }
        if (freshRead || attempt?.outcome == SendOutcome.Committed && attempt.request.operation == "cancel") {
            bindContinuation(shown()?.screen?.read ?: return, reloadContinuationRevisions.orEmpty())
            reloadContinuationRevisions = null
        }
        newDigest?.let(::loadFirstPages)
    }

    private fun bindContinuation(read: FormRead, observedRevisions: Map<String, Int>) {
        val form = read as? FormRead.Form
        val submission = form?.submission
        val continuation = submission?.continuation
        if (form == null || submission == null || continuation == null || form.definition.continuationBinding == null) {
            continuationWatch?.cancel()
            continuationWatch = null
            activeContinuation = null
            shown()?.let { _pane.value = it.copy(screen = it.screen.copy(continuation = null)) }
            return
        }
        val existing = continuations[submission.id]
        val flow = continuations.getOrPut(submission.id) {
            FormContinuation(api, wire, scope, form.interactionId, submission.id, continuation, currentChatRevision) {
                val latest = shown()?.screen?.read as? FormRead.Form
                latest?.submission?.id == submission.id && latest.replacementInteractionId == null &&
                    latest.expiresAt?.let { it > now() } == true
            }
        }
        // Only this new authoritative read may replace the cached status. An action
        // that started or settled after the read began makes its reply obsolete.
        if (existing != null && observedRevisions[submission.id] == flow.revision) flow.observe(continuation)
        if (flow === activeContinuation) return
        continuationWatch?.cancel()
        activeContinuation = flow
        val g = generation
        continuationWatch = scope.launch {
            flow.state.collect { continuation ->
                if (g == generation) {
                    val current = shown()
                    if ((current?.screen?.read as? FormRead.Form)?.submission?.id == submission.id) {
                        _pane.value = current.copy(screen = current.screen.copy(continuation = continuation))
                    }
                }
            }
        }
    }

    private fun recordCard(form: FormRead.Form, send: FormSendState) {
        val committed = send.attempt?.takeIf { it.outcome == SendOutcome.Committed }
        val state = when (committed?.request?.operation) {
            "submit" -> "submitted"
            "cancel" -> "canceled"
            else -> form.state.wire
        }
        mutableCards.update { it + (form.interactionId to FormCardSummary(
            form.definition.title, state, state == "open" && (form.draft != null || committed?.request?.operation == "save-draft"),
        )) }
    }

    override fun onOpenContinuationResult() {
        val state = activeContinuation?.state?.value ?: return
        val turnId = state.resultTurnId?.takeIf(::isUuid) ?: return
        if (state.phase != FormContinuationPhase.Succeeded || state.busy) return
        close()
        onResultRequested(turnId)
    }

    private fun editable(): Pair<FormPane.Shown, FormRead.Form>? {
        val current = shown() ?: return null
        val form = current.screen.read as? FormRead.Form ?: return null
        if (form.state != FormState.Open || form.submission != null || current.screen.send.unresolved) return null
        return current to form
    }

    override fun onAnswer(fieldId: String, answer: Answer?) {
        val (current, form) = editable() ?: return
        val answers = if (answer == null) current.screen.answers - fieldId else current.screen.answers + (fieldId to answer)
        val visible = form.definition.visibleFields(answers).map { it.id }.toSet()
        _pane.value = current.copy(screen = current.screen.copy(
            answers = answers, errors = (current.screen.errors - fieldId).filterKeys { it in visible },
        ))
    }

    override fun onFieldBlur(fieldId: String) {
        val (current, form) = editable() ?: return
        val error = form.definition.validate(current.screen.answers, draft = false)[fieldId]
        _pane.value = current.copy(screen = current.screen.copy(
            errors = if (error == null) current.screen.errors - fieldId else current.screen.errors + (fieldId to error),
        ))
    }

    /** Called only by the confirmed cancel control; invalid/partial answers are intentionally absent. */
    override fun onCancel() {
        val (_, form) = editable() ?: return
        submitter?.start(form, "cancel", null)
    }

    override fun onContinue() { if (submitter?.state?.value?.reading != true) activeContinuation?.continueNow() }
    override fun onCheckContinuation() { if (submitter?.state?.value?.reading != true) activeContinuation?.check() }
    override fun onStopContinuation() { if (submitter?.state?.value?.reading != true) activeContinuation?.stop() }

    override fun onUseNewerAnswers() {
        val (current, form) = editable() ?: return
        val server = current.screen.review?.server ?: return
        val answers = form.definition.initialAnswers(server)
        _pane.value = current.copy(screen = current.screen.copy(answers = answers, review = null, errors = emptyMap()))
    }

    override fun onKeepMyAnswers() {
        val (current, _) = editable() ?: return
        _pane.value = current.copy(screen = current.screen.copy(review = null))
    }

    override fun onSubmit() = mutate("submit", draft = false)

    override fun onSaveDraft() = mutate("save-draft", draft = true)

    private fun mutate(operation: String, draft: Boolean) {
        val (current, form) = editable() ?: return
        val screen = current.screen
        if (screen.review != null) return
        val errors = form.definition.validate(screen.answers, draft)
        if (errors.isNotEmpty()) {
            _pane.value = current.copy(screen = screen.copy(errors = errors, focusFirstError = screen.focusFirstError + 1))
            return
        }
        _pane.value = current.copy(screen = screen.copy(errors = emptyMap()))
        val sub = submitter ?: return
        settleSaves(form.interactionId, sub.state.value)
        if (sub.start(form, operation, form.definition.encodeAnswers(screen.answers)) && draft) {
            val requestId = sub.state.value.attempt?.request?.requestId ?: return
            val s = saves[form.interactionId]
            saves[form.interactionId] = Saved(requestId to screen.answers, s?.base, s?.revision ?: 0)
            settleSaves(form.interactionId, sub.state.value)
        }
    }

    override fun onCheckAgain() {
        submitter?.checkAgain()
    }

    override fun onSendAgain() {
        submitter?.sendAgain()
    }

    override fun onReload() {
        if (activeContinuation?.state?.value?.busy == true) return
        reloadContinuationRevisions = continuations.mapValues { it.value.revision }
        when (val pane = _pane.value) {
            is FormPane.Failed -> open(pane.interactionId)
            is FormPane.Shown -> submitter?.reload(pane.interactionId)
            else -> Unit
        }
    }

    override fun onLoadMoreResources(fieldId: String) {
        val form = shown()?.screen?.read as? FormRead.Form ?: return
        loadPage(form, fieldId)
    }

    override fun onClose() = close()

    /** The host opens the store listing; nothing here. */
    override fun onUpdate() = Unit

    private fun loadFirstPages(form: FormRead.Form) {
        form.definition.fields.filter { it.kind is FieldKind.Resource }.forEach { loadPage(form, it.id) }
    }

    private fun loadPage(form: FormRead.Form, fieldId: String) {
        if (form.definition.fields.none { it.id == fieldId && it.kind is FieldKind.Resource }) return
        val field = pages[fieldId] ?: ResourceField()
        if (!field.canLoadMore) return
        val g = generation
        val before = field.next
        setPage(fieldId, field.copy(loading = true, failed = false))
        scope.launch {
            if (g != generation) return@launch
            val page = try {
                decodeResourcePage(api.resources(form.interactionId, form.definitionDigest, fieldId, before), form.interactionId, form.definitionDigest, fieldId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val now = shown()?.screen?.read as? FormRead.Form
            if (g != generation || now?.interactionId != form.interactionId || now.definitionDigest != form.definitionDigest) return@launch
            val latest = pages[fieldId] ?: return@launch
            if (latest.next != before) return@launch
            setPage(
                fieldId,
                if (page == null) {
                    latest.copy(loading = false, failed = true)
                } else {
                    ResourceField(latest.names + page.items.associate { it.id to it.label }, page.nextBefore)
                },
            )
        }
    }

    private fun setPage(fieldId: String, field: ResourceField) {
        pages = pages + (fieldId to field)
        _pane.update { pane -> if (pane is FormPane.Shown) pane.copy(screen = decorate(pane.screen)) else pane }
    }
}
