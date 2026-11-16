package com.whumpo.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreDiscoveryCommand
import com.whumpo.core.api.CoreDiscoveryEdit
import com.whumpo.core.api.CoreDiscoveryKey
import com.whumpo.core.api.CoreDiscoveryPlan
import com.whumpo.core.api.CoreDiscoveryPlanStatus
import com.whumpo.core.api.CoreDiscoveryProposal
import com.whumpo.core.api.CoreDiscoveryValue
import com.whumpo.core.api.coreDiscoveryCountries
import com.whumpo.core.auth.CoreConnectedDiscoveryState
import com.whumpo.core.auth.CoreDiscoveryNotice

/** Renders the connected conversation and review proposals from the current server snapshot. */
@Composable
fun BusinessDiscoveryContent(
    state: CoreConnectedDiscoveryState,
    startWithReview: Boolean,
    onCommand: (CoreDiscoveryCommand) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    var reviewing by remember { mutableStateOf(startWithReview) }
    var editing by remember(snapshot?.revision) { mutableStateOf<CoreDiscoveryKey?>(null) }
    val questions = snapshot?.activeQuestions.orEmpty()
    val answers = snapshot?.answers.orEmpty()
    val unanswered = questions.filterNot(answers::containsKey)
    val question = editing ?: unanswered.firstOrNull()
    val showPlan = reviewing || question == null
    val earlier = questions.filter { it != question && it in answers }
    val scroll = rememberLazyListState()
    LaunchedEffect(question, showPlan, snapshot?.revision, state.notice, state.invalidatedKeys) {
        if (snapshot != null) {
            val target = when {
                state.notice != null || state.invalidatedKeys.isNotEmpty() -> 1
                showPlan -> 0
                else -> 2 + earlier.size
            }
            scroll.scrollToItem(target)
        }
    }

    Column(modifier.imePadding().padding(horizontal = 20.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discovery-chat"),
            state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Business discovery", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() })
            }
            item {
                if (snapshot == null && state.notice == null) DiscoveryMessage("Whumpo", "I’m loading your saved business answers.")
                if (state.busy) DiscoveryStatus("Working…")
                state.notice?.let { notice ->
                    DiscoveryStatus(notice.label())
                    if (notice == CoreDiscoveryNotice.SignInAgain) DiscoveryQuiet("Sign in again", !state.busy, onSignIn)
                }
                if (state.invalidatedKeys.isNotEmpty()) {
                    val labels = state.invalidatedKeys.joinToString { it.prompt().label }
                    DiscoveryStatus("Revisit $labels. Your updated answers changed what applies.")
                }
                when {
                    state.pending -> DiscoveryPrimary(
                        if (state.acknowledged) "Check saved discovery" else "Retry original request",
                        !state.busy, onRetry,
                    )
                    !state.busy && (state.requiresRefresh || snapshot == null) ->
                        DiscoveryQuiet("Reload saved discovery", onClick = onRefresh)
                }
            }
            if (snapshot != null && showPlan) {
                item {
                    DiscoveryMessage("Whumpo", "Let’s review your starter plan. These edits update the draft plan.")
                    if (unanswered.isNotEmpty()) DiscoveryQuiet("Continue questions", state.canEdit) {
                        editing = null
                        reviewing = false
                    }
                    if (snapshot.planStatus != CoreDiscoveryPlanStatus.Current) {
                        val stale = snapshot.planStatus == CoreDiscoveryPlanStatus.Stale
                        val prompt = if (stale) "Your goal changed. Refresh the starter plan to review the latest suggestions."
                            else "You can prepare a starter plan from the answers you’ve shared."
                        DiscoveryMessage("Whumpo", prompt)
                        DiscoveryPrimary(if (stale) "Refresh starter plan" else "Prepare starter plan", state.canEdit,
                            onClick = { onCommand(CoreDiscoveryCommand.RefreshPlan) })
                    }
                }
                snapshot.plan?.takeUnless { state.requiresRefresh }?.let { plan ->
                    item {
                        DiscoveryMessage("Whumpo", plan.summary)
                        if (plan.selectedNext != null) DiscoveryQuiet("Clear selected next step", state.canEdit) {
                            onCommand(CoreDiscoveryCommand.EditPlan(plan.revision, CoreDiscoveryEdit.Select(null)))
                        }
                    }
                    items(plan.actions, key = { "proposal-" + it.id.code }) { action ->
                        ProposalMessage(plan, action, state.canEdit) { edit ->
                            onCommand(CoreDiscoveryCommand.EditPlan(plan.revision, edit))
                        }
                    }
                }
                item { Text("Saved answers", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium) }
                items(questions, key = { "review-" + it.code }) { field ->
                    val answer = answers[field]?.value
                    if (answer != null) DiscoveryMessage("You · " + field.prompt().label, field.summary(answer))
                    DiscoveryQuiet((if (answer == null) "Answer " else "Change ") + field.prompt().label, state.canEdit) {
                        editing = field
                        reviewing = false
                    }
                }
            } else if (snapshot != null && question != null) {
                items(earlier, key = { "answered-" + it.code }) { field ->
                    DiscoveryMessage("You · " + field.prompt().label, field.summary(answers.getValue(field).value))
                    DiscoveryQuiet("Change " + field.prompt().label, state.canEdit) { editing = field }
                }
                item(key = "question-" + question.code) {
                    QuestionMessage(question, answers[question]?.value, state.canEdit, onCommand)
                    if (unanswered.isNotEmpty()) DiscoveryQuiet("Defer remaining questions", state.canEdit) {
                        onCommand(CoreDiscoveryCommand.DeferRemaining)
                    }
                    DiscoveryQuiet("Review starter plan") { reviewing = true }
                }
            }
        }
        DiscoveryQuiet("Continue later", onClick = onLater)
    }
}

@Composable
private fun QuestionMessage(
    question: CoreDiscoveryKey,
    initial: CoreDiscoveryValue?,
    editable: Boolean,
    onCommand: (CoreDiscoveryCommand) -> Unit,
) {
    val prompt = question.prompt()
    fun submit(value: CoreDiscoveryValue) = onCommand(CoreDiscoveryCommand.Answer(question, value))
    DiscoveryMessage("Whumpo", prompt.question)
    if (initial != null) DiscoveryMessage("You · " + prompt.label, question.summary(initial))
    when {
        question == CoreDiscoveryKey.Country -> CountryPicker(initial, editable) { submit(CoreDiscoveryValue.Declared(it)) }
        question.choices.isNotEmpty() -> question.choices.forEach { value ->
            DiscoveryQuiet(question.choiceLabel(value), editable) { submit(CoreDiscoveryValue.Declared(value)) }
        }
        else -> DiscoveryEditor(prompt.label, "Save answer", (initial as? CoreDiscoveryValue.Declared)?.value.orEmpty(),
            question.maxLength * 2, editable,
            keyboardType = if (question == CoreDiscoveryKey.WebsiteUrl) KeyboardType.Uri else KeyboardType.Text) {
            submit(CoreDiscoveryValue.Declared(it))
        }
    }
    DiscoveryQuiet("I’m not sure", editable) { submit(CoreDiscoveryValue.Unknown) }
    DiscoveryQuiet("Leave this for later", editable) { submit(CoreDiscoveryValue.Deferred) }
    if (initial != null) DiscoveryQuiet("Clear saved answer", editable) { onCommand(CoreDiscoveryCommand.Clear(question)) }
}

@Composable
private fun CountryPicker(initial: CoreDiscoveryValue?, editable: Boolean, onCountry: (String) -> Unit) {
    val countries = remember { coreDiscoveryCountries.map { it to discoveryCountryName(it) }.sortedBy { it.second } }
    var query by remember(initial) { mutableStateOf((initial as? CoreDiscoveryValue.Declared)?.value.orEmpty()) }
    val search = query.trim()
    val shown = countries.filter { (code, name) -> code.startsWith(search, ignoreCase = true) || name.contains(search, ignoreCase = true) }
        .sortedBy { (code, _) -> !code.equals(search, ignoreCase = true) }
    OutlinedTextField(query, { if (it.length <= 80) query = it }, enabled = editable,
        label = { Text("Search countries by name or code") }, modifier = Modifier.fillMaxWidth().testTag("discovery-country"),
        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
    if (shown.isEmpty()) Text("No matching countries.")
    shown.take(6).forEach { (code, name) -> DiscoveryQuiet("$name ($code)", editable) { onCountry(code) } }
    if (shown.size > 6) Text("Enter more of the name or code to narrow the list.")
}

@Composable
private fun ProposalMessage(
    plan: CoreDiscoveryPlan,
    action: CoreDiscoveryProposal,
    editable: Boolean,
    onEdit: (CoreDiscoveryEdit) -> Unit,
) {
    var renaming by remember(action.titleOverride) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DiscoveryMessage("Whumpo · Proposed step", action.titleOverride ?: action.title)
        Text(action.reason)
        if (action.answerKeys.isNotEmpty()) Text("Based on " + action.answerKeys.joinToString { it.prompt().label })
        if (action.dependencies.isNotEmpty()) {
            Text("First review " + action.dependencies.joinToString { id ->
                val dependency = plan.actions.first { it.id == id }
                dependency.titleOverride ?: dependency.title
            })
        }
        when {
            action.deferred -> Text("Deferred")
            action.id == plan.selectedNext -> Text("Selected for later review")
            action.id == plan.recommendedNext -> Text("Suggested next")
        }
        if (renaming) {
            DiscoveryEditor("Proposed step title", "Save title", action.titleOverride ?: action.title, 400, editable) {
                onEdit(CoreDiscoveryEdit.Title(action.id, it))
            }
            DiscoveryQuiet("Close title editor") { renaming = false }
        } else {
            DiscoveryQuiet("Rename step", editable) { renaming = true }
        }
        if (action.titleOverride != null) DiscoveryQuiet("Reset title", editable) {
            onEdit(CoreDiscoveryEdit.Title(action.id, null))
        }
        DiscoveryQuiet(if (action.deferred) "Restore step" else "Defer step", editable) {
            onEdit(CoreDiscoveryEdit.Defer(action.id, !action.deferred))
        }
        if (!action.deferred && action.dependencies.isEmpty() && action.id != plan.selectedNext) {
            DiscoveryQuiet("Select as next step", editable) { onEdit(CoreDiscoveryEdit.Select(action.id)) }
        }
    }
}

@Composable
private fun DiscoveryEditor(
    label: String,
    button: String,
    initial: String,
    limit: Int,
    editable: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val focus = LocalFocusManager.current
    val save = {
        if (editable && value.isNotBlank()) {
            focus.clearFocus()
            onSave(value)
        }
    }
    OutlinedTextField(value, { if (it.length <= limit) value = it }, enabled = editable,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth().testTag("discovery-editor"),
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }))
    DiscoveryPrimary(button, editable && value.isNotBlank(), save, "discovery-save")
}

@Composable
private fun DiscoveryMessage(speaker: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(speaker, style = MaterialTheme.typography.labelLarge)
            Text(body)
        }
    }
}

@Composable
private fun DiscoveryStatus(text: String) = Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })

@Composable
private fun DiscoveryPrimary(label: String, enabled: Boolean, onClick: () -> Unit, tag: String = "discovery-primary") {
    WhumpoPrimaryButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), enabled) { Text(label) }
}

@Composable
private fun DiscoveryQuiet(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    WhumpoQuietButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled) { Text(label) }
}
