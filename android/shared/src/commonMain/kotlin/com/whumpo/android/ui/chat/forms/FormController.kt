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
    private val newId: () -> String = ::newRequestId,
) : FormActions {
    private val _pane = MutableStateFlow<FormPane?>(null)
    val pane: StateFlow<FormPane?> = _pane.asStateFlow()

    private var generation = 0
    private var submitter: FormSubmitter? = null
    private var watch: Job? = null
    private var pages = mapOf<String, ResourceField>()
    private var appliedProblem: String? = null
    private var visitId: String? = null
    private var keptAnswers: KeptAnswers? = null
    private var seenReread: FormRead? = null
    private val kept = mutableMapOf<String, Kept>()

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
        val retained = kept.remove(interactionId)
        val sub = retained?.submitter ?: FormSubmitter(api, wire, scope, newId)
        submitter = sub
        visitId = interactionId
        keptAnswers = retained?.answers
        // A reread from before the form was closed is not applied again.
        seenReread = sub.state.value.reread
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
            if (read == null) {
                _pane.value = FormPane.Failed(interactionId)
                return@launch
            }
            if (read is FormRead.Form && read.interactionId != interactionId) {
                _pane.value = FormPane.Shown(interactionId, FormScreenState(FormRead.UpdateRequired))
                return@launch
            }
            val (answers, review) = (read as? FormRead.Form)?.let(::starting) ?: (emptyMap<String, Answer>() to null)
            show(interactionId, read, answers, review)
            if (read is FormRead.Form) loadFirstPages(read)
        }
    }

    /** Dismisses the form, keeping an unresolved send and unsent answers for a reopen. */
    fun close() {
        generation += 1
        watch?.cancel()
        watch = null
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
        if (k.read.definitionDigest == form.definitionDigest && revision == form.answerRevision) return k.answers to k.review
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

    private fun show(interactionId: String, read: FormRead, answers: Map<String, Answer>, review: FormReview?) {
        val send = submitter?.state?.value ?: FormSendState()
        _pane.value = FormPane.Shown(interactionId, decorate(FormScreenState(read, answers, send = send, review = review)))
    }

    private fun decorate(screen: FormScreenState) = screen.copy(
        resourceNames = pages.values.fold(emptyMap()) { all, field -> all + field.names },
        moreResources = pages.filterValues { it.canLoadMore }.keys,
    )

    private fun onSend(send: FormSendState) {
        val current = shown() ?: return
        settleSaves(current.interactionId, send)
        var screen = current.screen.copy(send = send)
        val reread = send.reread
        val form = screen.read as? FormRead.Form
        var newDigest: FormRead.Form? = null
        if (reread != null && reread !== seenReread && !send.unresolved && reread != screen.read) {
            seenReread = reread
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
        newDigest?.let(::loadFirstPages)
    }

    private fun editable(): Pair<FormPane.Shown, FormRead.Form>? {
        val current = shown() ?: return null
        val form = current.screen.read as? FormRead.Form ?: return null
        if (form.state != FormState.Open || form.submission != null || current.screen.send.unresolved) return null
        return current to form
    }

    override fun onAnswer(fieldId: String, answer: Answer?) {
        val (current, _) = editable() ?: return
        val answers = if (answer == null) current.screen.answers - fieldId else current.screen.answers + (fieldId to answer)
        _pane.value = current.copy(screen = current.screen.copy(answers = answers, errors = current.screen.errors - fieldId))
    }

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
