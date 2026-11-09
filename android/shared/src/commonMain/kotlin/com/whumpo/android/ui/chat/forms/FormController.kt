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
interface FormPagesApi : FormApi {
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

    /** Unsent answers, reused only while the form is still open at the same digest and answer revision. */
    private class KeptAnswers(val answers: Map<String, Answer>, val digest: String, val revision: Int)

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
            show(interactionId, read, (read as? FormRead.Form)?.let(::startingAnswers).orEmpty())
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
    }

    private fun keep(interactionId: String, sub: FormSubmitter) {
        val send = sub.state.value
        val screen = shown()?.screen
        val form = screen?.read as? FormRead.Form
        val answers = when {
            screen == null -> keptAnswers
            send.attempt?.outcome == SendOutcome.Committed -> null
            form == null || form.state != FormState.Open || form.submission != null -> null
            else -> KeptAnswers(screen.answers, form.definitionDigest, form.answerRevision)
        }
        if (!send.unresolved) sub.reset()
        if (send.unresolved || answers != null) kept[interactionId] = Kept(sub.takeIf { send.unresolved }, answers)
    }

    /** Kept unsent answers when they still fit this read; otherwise the saved draft, or the definition's defaults. */
    private fun startingAnswers(form: FormRead.Form): Map<String, Answer> {
        val fits = keptAnswers?.takeIf {
            it.digest == form.definitionDigest && it.revision == form.answerRevision &&
                form.state == FormState.Open && form.submission == null
        }
        keptAnswers = null
        return fits?.answers ?: form.definition.initialAnswers(form.draft)
    }

    private fun shown(): FormPane.Shown? = _pane.value as? FormPane.Shown

    private fun show(interactionId: String, read: FormRead, answers: Map<String, Answer>) {
        _pane.value = FormPane.Shown(interactionId, decorate(FormScreenState(read, answers, send = submitter?.state?.value ?: FormSendState())))
    }

    private fun decorate(screen: FormScreenState) = screen.copy(
        resourceNames = pages.values.fold(emptyMap()) { all, field -> all + field.names },
        moreResources = pages.filterValues { it.canLoadMore }.keys,
    )

    private fun onSend(send: FormSendState) {
        val current = shown() ?: return
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
                    screen = screen.copy(read = reread, answers = reread.draft ?: screen.answers, errors = emptyMap())
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

    override fun onSubmit() = mutate("submit", draft = false)

    override fun onSaveDraft() = mutate("save-draft", draft = true)

    private fun mutate(operation: String, draft: Boolean) {
        val (current, form) = editable() ?: return
        val screen = current.screen
        val errors = form.definition.validate(screen.answers, draft)
        if (errors.isNotEmpty()) {
            _pane.value = current.copy(screen = screen.copy(errors = errors, focusFirstError = screen.focusFirstError + 1))
            return
        }
        _pane.value = current.copy(screen = screen.copy(errors = emptyMap()))
        submitter?.start(form, operation, form.definition.encodeAnswers(screen.answers))
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
