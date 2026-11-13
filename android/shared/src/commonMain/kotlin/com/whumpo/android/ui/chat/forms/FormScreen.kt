package com.whumpo.android.ui.chat.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.TimeInput as MaterialTimeInput
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import com.whumpo.android.ui.common.readable
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.android.ui.theme.WhumpoTheme

private val MinTarget = 48.dp

interface FormActions {
    fun onAnswer(fieldId: String, answer: Answer?)
    fun onSubmit()
    fun onSaveDraft()
    fun onCheckAgain()
    fun onSendAgain()
    fun onReload()
    fun onLoadMoreResources(fieldId: String)
    /** Review choice: replace the owner's answers with the newer saved ones. Sends nothing. */
    fun onUseNewerAnswers()
    /** Review choice: keep the owner's answers. Sends nothing. */
    fun onKeepMyAnswers()
    fun onClose()
    fun onUpdate()
    fun onCancel()
    fun onFieldBlur(fieldId: String)
    fun onContinue()
    fun onCheckContinuation()
    fun onStopContinuation()
    fun onOpenContinuationResult()
}

/** Everything the form screen shows. The caller owns answers, runs [FormDefinition.validate] and loads resources. */
data class FormScreenState(
    val read: FormRead,
    val answers: Map<String, Answer> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    /** Incremented when Send or Save draft finds problems: focus moves to the first one. */
    val focusFirstError: Int = 0,
    /** Loaded project names by ID, for display and for choosing. */
    val resourceNames: Map<String, String> = emptyMap(),
    /** Resource fields with another page to load. */
    val moreResources: Set<String> = emptySet(),
    val send: FormSendState = FormSendState(),
    /** Newer saved answers held back for the owner's choice; Send and Save draft wait for it. */
    val review: FormReview? = null,
    /** Explicit next-step state; unknown outcomes never offer another Continue. */
    val continuation: FormContinuationState? = null,
    val resourceLoading: Set<String> = emptySet(),
    val resourceFailures: Set<String> = emptySet(),
)

/**
 * What a reread changed under the owner's edits. [server] is the newer saved draft, if any; [differs] lists fields
 * where it differs from the owner's answers; [dropped] labels answers whose field was removed or changed type, which
 * were taken out so they are never sent.
 */
data class FormReview(
    val server: Map<String, Answer>?,
    val differs: Set<String>,
    val dropped: List<String>,
)

/**
 * The native form (docs/android/chat-forms.md): full height on compact widths, or inside the right pane on wide
 * ones. Actions stay pinned above the keyboard, except at large text where they follow the last field so nothing is
 * covered. Opened from the chat card through [FormController].
 */
@Composable
fun FormScreen(state: FormScreenState, zones: ZoneTime, actions: FormActions, modifier: Modifier = Modifier) {
    // Material picker/input components use rememberSaveable internally. This subtree deliberately has no
    // saved-state registry: the controller is the sole, memory-only owner of every form answer.
    FormMemoryOnlyContent { FormScreenContent(state, zones, actions, modifier) }
}

/** Reapplied inside each Dialog because Android creates a separate composition root there. */
@Composable
internal fun FormMemoryOnlyContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null, content = content)
}

@Composable
private fun FormScreenContent(state: FormScreenState, zones: ZoneTime, actions: FormActions, modifier: Modifier) {
    val read = state.read
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding().testTag("form-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.readable().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (read as? FormRead.Form)?.definition?.title ?: "Form",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            WhumpoQuietButton(actions::onClose, Modifier.heightIn(min = MinTarget).testTag("form-close")) { Text("Close") }
        }
        when (read) {
            FormRead.UpdateRequired -> UpdateRequiredBody(actions)
            is FormRead.Form -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("form-body"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.readable().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FormBody(read, state, zones, actions)
                    }
                    if (largeText) ActionBar(read, state, actions)
                }
                if (!largeText) ActionBar(read, state, actions)
            }
        }
    }
}

@Composable
private fun UpdateRequiredBody(actions: FormActions) {
    Column(
        Modifier.readable().padding(24.dp).testTag("form-update-required"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Update Whumpo to answer this form.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        WhumpoPrimaryButton(actions::onUpdate, Modifier.heightIn(min = MinTarget).testTag("form-update")) { Text("Open Play Store") }
    }
}

private fun editable(read: FormRead.Form, send: FormSendState): Boolean {
    val a = send.attempt
    val committedSubmit = a != null && a.outcome == SendOutcome.Committed && a.request.operation != "save-draft"
    return read.state == FormState.Open && !send.unresolved && !committedSubmit && a?.updateRequired != true
}

@Composable
private fun FormBody(read: FormRead.Form, state: FormScreenState, zones: ZoneTime, actions: FormActions) {
    val definition = read.definition
    if (definition.summary.isNotEmpty()) {
        Text(definition.summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
    val submitted = read.submission ?: (state.send.reread as? FormRead.Form)?.submission
    when {
        submitted != null -> SubmittedAnswers(definition, submitted.answers, state.resourceNames, zones)
        read.state == FormState.Open -> EditableFields(read, state, zones, actions)
        else -> Text(
            if (read.replacementInteractionId != null) "This form was replaced by a newer one." else "This form is no longer open.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag("form-closed"),
        )
    }
}

@Composable
private fun EditableFields(read: FormRead.Form, state: FormScreenState, zones: ZoneTime, actions: FormActions) {
    val definition = read.definition
    val enabled = editable(read, state.send)
    val requesters = remember(definition) { definition.fields.associate { it.id to FocusRequester() } }
    LaunchedEffect(state.focusFirstError) {
        if (state.focusFirstError > 0) state.errors.keys.firstOrNull()?.let { requesters[it]?.requestFocus() }
    }
    state.review?.let { ReviewCard(definition, it, enabled, actions) }
    if (state.errors.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().testTag("form-error-summary"),
        ) {
            val n = state.errors.size
            Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (n == 1) "Fix 1 answer to continue." else "Fix $n answers to continue.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                definition.visibleFields(state.answers).forEach { field ->
                    state.errors[field.id]?.let { problem -> Text("${field.label}: $problem", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
        }
    }
    val visible = definition.visibleFields(state.answers)
    val lastTextId = visible.lastOrNull { it.kind is FieldKind.Text || it.kind is FieldKind.Number }?.id
    for (field in visible) {
        key(read.interactionId, read.definitionDigest, field.id) { FieldControl(
            field = field,
            answer = state.answers[field.id],
            error = state.errors[field.id],
            enabled = enabled,
            names = state.resourceNames,
            canLoadMore = field.id in state.moreResources,
            resourcesLoading = field.id in state.resourceLoading,
            resourcesFailed = field.id in state.resourceFailures,
            formKey = read.interactionId,
            zones = zones,
            focus = requesters.getValue(field.id),
            actions = actions,
            imeAction = if (field.id == lastTextId) ImeAction.Done else ImeAction.Next,
        ) }
    }
}

@Composable
private fun ReviewCard(definition: FormDefinition, review: FormReview, enabled: Boolean, actions: FormActions) {
    val changed = definition.fields.filter { it.id in review.differs }.map { it.label }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("form-review"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val color = MaterialTheme.colorScheme.onSecondaryContainer
            Text(
                "Review before sending",
                style = MaterialTheme.typography.titleMedium,
                color = color,
                modifier = Modifier.semantics { heading() },
            )
            if (changed.isNotEmpty()) {
                Text("Newer answers were saved for: ${changed.joinToString()}. Yours are shown.", style = MaterialTheme.typography.bodyLarge, color = color)
            }
            if (review.dropped.isNotEmpty()) {
                Text("No longer in this form, so they won't be sent: ${review.dropped.joinToString()}.", style = MaterialTheme.typography.bodyLarge, color = color)
            }
            val buttons = Modifier.fillMaxWidth().heightIn(min = MinTarget)
            if (review.server != null && changed.isNotEmpty()) {
                WhumpoSecondaryButton(actions::onUseNewerAnswers, buttons.testTag("form-review-newer"), enabled = enabled) { Text("Use newer answers") }
            }
            WhumpoSecondaryButton(actions::onKeepMyAnswers, buttons.testTag("form-review-mine"), enabled = enabled) {
                Text(if (changed.isNotEmpty()) "Keep my answers" else "OK")
            }
        }
    }
}

private fun labelOf(field: FormField) = if (field.required) "${field.label} (required)" else field.label

@Composable
private fun Support(error: String?, help: String) {
    if (error != null) {
        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    } else if (help.isNotEmpty()) {
        Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldControl(
    field: FormField,
    answer: Answer?,
    error: String?,
    enabled: Boolean,
    names: Map<String, String>,
    canLoadMore: Boolean,
    resourcesLoading: Boolean,
    resourcesFailed: Boolean,
    formKey: String,
    zones: ZoneTime,
    focus: FocusRequester,
    actions: FormActions,
    imeAction: ImeAction,
) {
    val tag = Modifier.testTag("form-field-${field.id}")
    val help = if (field.suggested && answer == field.default) {
        listOf(field.help, "Suggested answer. Change it if it isn't right.").filter { it.isNotEmpty() }.joinToString(" ")
    } else field.help
    when (val kind = field.kind) {
        is FieldKind.Text -> {
            val value = (answer as? Answer.Text)?.value.orEmpty()
            TextInput(
                label = labelOf(field), value = value, error = error, enabled = enabled, focus = focus, modifier = tag,
                help = listOf(help, "${value.length} of ${kind.maxLength} characters").filter { it.isNotEmpty() }.joinToString(". "),
                singleLine = !kind.long, keyboard = KeyboardOptions(imeAction = if (kind.long) ImeAction.Default else imeAction),
                onBlur = { actions.onFieldBlur(field.id) },
            ) { actions.onAnswer(field.id, if (it.isEmpty()) null else Answer.Text(it)) }
        }
        is FieldKind.Number -> TextInput(
            label = labelOf(field), value = (answer as? Answer.Number)?.text.orEmpty(), error = error, enabled = enabled,
            focus = focus, modifier = tag,
            help = listOf(help, "From ${plain(kind.min)} to ${plain(kind.max)}, in steps of ${plain(kind.step)}. Do not group digits.").filter { it.isNotEmpty() }.joinToString(" "),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
            onBlur = { actions.onFieldBlur(field.id) },
        ) { actions.onAnswer(field.id, if (it.isBlank()) null else Answer.Number(it)) }
        FieldKind.Date -> DateInput(
            labelOf(field), (answer as? Answer.Text)?.value.orEmpty(), error, help, enabled, focus, tag,
            onBlur = { actions.onFieldBlur(field.id) },
        ) { actions.onAnswer(field.id, it.takeIf { value -> value.isNotEmpty() }?.let(Answer::Text)) }
        is FieldKind.DateTime -> DateTimeInput(formKey, field, kind, answer, error, help, enabled, zones, focus, actions)
        is FieldKind.Choice -> Options(
            field, kind.options, error, help, enabled, focus, multiple = false, searchable = kind.options.size > 6,
            onBlur = { actions.onFieldBlur(field.id) },
            selected = { (answer as? Answer.One)?.id == it },
        ) { id -> actions.onAnswer(field.id, Answer.One(id)) }
        FieldKind.YesNo -> BooleanInput(field, (answer as? Answer.Flag)?.value, error, help, enabled, focus, actions)
        is FieldKind.MultiChoice -> {
            val ids = (answer as? Answer.Many)?.ids.orEmpty()
            Options(field, kind.options, error, help, enabled, focus, multiple = true, selected = { it in ids }, searchable = kind.options.size > 6, onBlur = { actions.onFieldBlur(field.id) }) { id ->
                val next = if (id in ids) ids - id else ids + id
                actions.onAnswer(field.id, if (next.isEmpty()) null else Answer.Many(next))
            }
        }
        is FieldKind.Resource -> {
            val chosen = when (answer) {
                is Answer.One -> listOf(answer.id)
                is Answer.Many -> answer.ids
                else -> emptyList()
            }
            // Chosen IDs not in a loaded page stay listed, by "Name not loaded", so they are never silently dropped.
            val options = (names.keys + chosen).distinct().map { FormOption(it, resourceName(it, names)) }
            val full = kind.multiple && chosen.size >= kind.maxItems
            Options(
                field, options, error,
                listOf(help, if (kind.multiple) "Choose up to ${kind.maxItems}. Showing loaded projects only." else "Showing loaded projects only.")
                    .filter { it.isNotEmpty() }.joinToString(" "),
                enabled, focus, multiple = kind.multiple, selected = { it in chosen },
                optionEnabled = { !full || it in chosen },
                searchable = true,
                onBlur = { actions.onFieldBlur(field.id) },
            ) { id ->
                val next = if (!kind.multiple) listOf(id) else if (id in chosen) chosen - id else chosen + id
                actions.onAnswer(
                    field.id,
                    when {
                        next.isEmpty() -> null
                        kind.multiple -> Answer.Many(next)
                        else -> Answer.One(next.single())
                    },
                )
            }
            if (resourcesLoading) Text("Loading projects…", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            if (resourcesFailed) Text("Couldn't load projects. Your selections are kept.", color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            if (canLoadMore) {
                WhumpoQuietButton(
                    { actions.onLoadMoreResources(field.id) },
                    Modifier.heightIn(min = MinTarget).testTag("form-more-${field.id}"),
                    enabled = enabled,
                ) { Text(if (resourcesFailed) "Try loading projects again" else "Load more projects") }
            }
        }
    }
}

@Composable
private fun BooleanInput(field: FormField, answer: Boolean?, problem: String?, help: String, enabled: Boolean, focus: FocusRequester, actions: FormActions) {
    val status = if (answer == null) "Not answered" else if (answer) "Yes" else "No"
    Column(Modifier.fillMaxWidth().testTag("form-field-${field.id}")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTarget).blurAfterFocus { actions.onFieldBlur(field.id) }.focusRequester(focus)
                .toggleable(answer == true, enabled, Role.Switch) { actions.onAnswer(field.id, Answer.Flag(it)) }
                .semantics { stateDescription = status; if (problem != null) error(problem) }
                .testTag("form-boolean-${field.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(labelOf(field), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = answer == true, onCheckedChange = null, enabled = enabled)
        }
        // An untouched off switch is not an implicit answer. False remains a complete, explicit one-tap choice.
        if (answer == null) WhumpoQuietButton(
            { actions.onAnswer(field.id, Answer.Flag(false)) }, Modifier.heightIn(min = MinTarget).testTag("form-boolean-no-${field.id}"), enabled = enabled,
        ) { Text("Use No") }
        Support(problem, help)
    }
}

@Composable
private fun TextInput(
    label: String,
    value: String,
    error: String?,
    help: String,
    enabled: Boolean,
    focus: FocusRequester,
    modifier: Modifier,
    keyboard: KeyboardOptions,
    singleLine: Boolean = true,
    onBlur: () -> Unit = {},
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        isError = error != null,
        supportingText = if (error != null || help.isNotEmpty()) ({ Support(error, help) }) else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        keyboardOptions = keyboard,
        modifier = modifier.fillMaxWidth().blurAfterFocus(onBlur).focusRequester(focus).semantics { if (error != null) error(error) },
    )
}

/** Validation follows an actual visit to a focus target, never initial composition. */
@Composable
private fun Modifier.blurAfterFocus(onBlur: () -> Unit): Modifier {
    var hadFocus by remember { mutableStateOf(false) }
    return onFocusChanged {
        if (hadFocus && !it.hasFocus) onBlur()
        hadFocus = it.hasFocus
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateInput(
    label: String,
    date: String,
    problem: String?,
    help: String,
    enabled: Boolean,
    focus: FocusRequester,
    modifier: Modifier,
    onBlur: () -> Unit,
    onSelected: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        WhumpoSecondaryButton(
            { picking = true },
            Modifier.fillMaxWidth().heightIn(min = MinTarget).blurAfterFocus(onBlur).focusRequester(focus)
                .semantics { if (problem != null) error(problem) },
            enabled = enabled,
        ) { Text(date.ifEmpty { "Choose date" }) }
        if (date.isNotEmpty()) WhumpoQuietButton(
            { onSelected(""); onBlur() }, Modifier.heightIn(min = MinTarget), enabled = enabled,
        ) { Text("Clear date") }
        Support(problem, help)
    }
    if (picking && enabled) {
        val initial = date.takeIf(::isIsoDate)?.let { Instant.parse("${it}T00:00:00Z").toEpochMilliseconds() }
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial, yearRange = 1..9999, initialDisplayMode = if (largeText) DisplayMode.Input else DisplayMode.Picker)
        DatePickerDialog(
            modifier = Modifier.testTag("form-date-dialog"),
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { onSelected(Instant.fromEpochMilliseconds(it).toString().substringBefore('T')) }
                        picking = false
                        onBlur()
                    },
                    enabled = picker.selectedDateMillis != null,
                    modifier = Modifier.testTag("form-date-confirm"),
                ) { Text("Use date") }
            },
            dismissButton = { TextButton({ picking = false }, Modifier.testTag("form-date-dismiss")) { Text("Keep previous date") } },
        ) {
            FormMemoryOnlyContent {
                DatePicker(picker, title = { Text(label, Modifier.padding(24.dp)) }, showModeToggle = !largeText, modifier = Modifier.testTag("form-date-picker"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInput(
    time: String,
    enabled: Boolean,
    problem: String?,
    modifier: Modifier,
    onBlur: () -> Unit,
    onSelected: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Time", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        WhumpoSecondaryButton(
            { picking = true },
            Modifier.fillMaxWidth().heightIn(min = MinTarget).blurAfterFocus(onBlur).semantics { if (problem != null) error(problem) },
            enabled = enabled,
        ) { Text(time.ifEmpty { "Choose time" }) }
        if (time.isNotEmpty()) WhumpoQuietButton(
            { onSelected(""); onBlur() }, Modifier.heightIn(min = MinTarget), enabled = enabled,
        ) { Text("Clear time") }
    }
    if (picking && enabled) {
        val pieces = time.split(':')
        val picker = rememberTimePickerState(
            initialHour = pieces.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 12,
            initialMinute = pieces.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            modifier = Modifier.testTag("form-time-dialog"),
            onDismissRequest = { picking = false },
            title = { Text("Choose time") },
            text = {
                FormMemoryOnlyContent {
                    if (LocalDensity.current.fontScale >= 1.5f) MaterialTimeInput(picker, Modifier.testTag("form-time-picker"))
                    else TimePicker(picker, Modifier.testTag("form-time-picker"))
                }
            },
            confirmButton = { TextButton({
                onSelected("${picker.hour.toString().padStart(2, '0')}:${picker.minute.toString().padStart(2, '0')}")
                picking = false
                onBlur()
            }, Modifier.testTag("form-time-confirm")) { Text("Use time") } },
            dismissButton = { TextButton({ picking = false }, Modifier.testTag("form-time-dismiss")) { Text("Keep previous time") } },
        )
    }
}

@Composable
private fun DateTimeInput(
    formKey: String,
    field: FormField,
    kind: FieldKind.DateTime,
    answer: Answer?,
    error: String?,
    help: String,
    enabled: Boolean,
    zones: ZoneTime,
    focus: FocusRequester,
    actions: FormActions,
) {
    fun shown(a: Answer?) = (a as? Answer.Text)?.value?.let { zones.fromUtc(it, kind.zone) }
    // The controller owns even incomplete input. No answers enter SavedInstanceState,
    // and closing/reopening or hiding a conditional field cannot erase typed text.
    val pending = answer as? Answer.DateTimeDraft
    val exact = shown(answer)
    val date = pending?.date ?: exact?.date.orEmpty()
    val time = pending?.time ?: exact?.time.orEmpty()
    val local = if (date.isEmpty() && time.isEmpty()) null else zones.toUtc(date, time, kind.zone)
    val offset = (local as? ZoneTime.Local.Exact)?.offset
    val zoneNote = "Time zone: ${kind.zone}" + (offset?.let { " ($it)" } ?: "") + "."
    val localProblem = when (local) {
        ZoneTime.Local.Gap -> "That time doesn't exist in ${kind.zone} because the clocks change. Choose another time."
        ZoneTime.Local.Ambiguous -> "That time happens twice in ${kind.zone} because the clocks change. Choose another time."
        else -> null
    }
    fun update(d: String, t: String) {
        val next = if (d.isEmpty() && t.isEmpty()) null else zones.toUtc(d, t, kind.zone)
        val out = when (next) {
            null -> null
            is ZoneTime.Local.Exact -> Answer.Text(next.utc)
            // Kept as an invalid value so validation reports it instead of silently dropping it.
            else -> Answer.DateTimeDraft(d, t)
        }
        actions.onAnswer(field.id, out)
    }
    Column(Modifier.fillMaxWidth().testTag("form-field-${field.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(labelOf(field), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        DateInput(
            "Date", date, localProblem ?: error, "", enabled, focus, Modifier.testTag("form-field-${field.id}-date"),
            onBlur = { actions.onFieldBlur(field.id) },
        ) { update(it, time) }
        TimeInput(
            time, enabled, localProblem ?: error, Modifier.testTag("form-field-${field.id}-time"),
            onBlur = { actions.onFieldBlur(field.id) },
        ) { update(date, it) }
        Text(zoneNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Support(localProblem ?: error, help)
    }
}

@Composable
private fun Options(
    field: FormField,
    options: List<FormOption>,
    error: String?,
    help: String,
    enabled: Boolean,
    focus: FocusRequester,
    multiple: Boolean,
    selected: (String) -> Boolean,
    optionEnabled: (String) -> Boolean = { true },
    searchable: Boolean = false,
    onBlur: () -> Unit = {},
    onPick: (String) -> Unit,
) {
    var query by remember(field.id) { mutableStateOf("") }
    val filtered = options.filter { it.label.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxWidth().blurAfterFocus(onBlur).selectableGroup().testTag("form-field-${field.id}").semantics { if (error != null) error(error) }) {
        Text(labelOf(field), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
        if (multiple) Text("${options.count { selected(it.id) }} selected", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (searchable) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, enabled = enabled,
                label = { Text(if (field.kind is FieldKind.Resource) "Search loaded projects" else "Search options") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("form-search-${field.id}"),
            )
        }
        if (filtered.isEmpty()) {
            Text(
                if (query.isNotEmpty()) "No matching options." else "No projects loaded yet.", style = MaterialTheme.typography.bodyMedium, color = WhumpoTheme.colors.inkMuted,
                modifier = if (searchable) Modifier else Modifier.focusRequester(focus).focusable(),
            )
        }
        filtered.forEachIndexed { i, option ->
            val on = selected(option.id)
            val usable = enabled && optionEnabled(option.id)
            val row = Modifier.fillMaxWidth().heightIn(min = MinTarget).let { if (i == 0 && !searchable) it.focusRequester(focus) else it }
            Row(
                (if (multiple) row.toggleable(on, usable, Role.Checkbox) { onPick(option.id) }
                else row.selectable(on, usable, Role.RadioButton) { onPick(option.id) }).testTag("form-option-${field.id}-${option.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (multiple) Checkbox(on, onCheckedChange = null, enabled = usable) else RadioButton(on, onClick = null, enabled = usable)
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (option.label == NAME_NOT_LOADED) WhumpoTheme.colors.inkMuted else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Support(error, help)
    }
}

@Composable
private fun SubmittedAnswers(definition: FormDefinition, answers: Map<String, Answer>, names: Map<String, String>, zones: ZoneTime) {
    Column(Modifier.fillMaxWidth().testTag("form-submitted"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your answers", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        for (field in definition.visibleFields(answers)) {
            val a = answers[field.id] ?: continue
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("form-answer-${field.id}")) {
                Text(field.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(display(field.kind, a, names, zones), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

private fun display(kind: FieldKind, a: Answer, names: Map<String, String>, zones: ZoneTime): String = when (a) {
    is Answer.DateTimeDraft -> "${a.date} ${a.time}"
    is Answer.Flag -> if (a.value) "Yes" else "No"
    is Answer.Number -> a.text
    is Answer.Text -> if (kind is FieldKind.DateTime) {
        zones.fromUtc(a.value, kind.zone)?.let { "${it.date} ${it.time} (${kind.zone}, ${it.offset})" } ?: a.value
    } else a.value
    is Answer.One -> optionLabel(kind, a.id, names)
    is Answer.Many -> a.ids.joinToString(", ") { optionLabel(kind, it, names) }
}

private fun optionLabel(kind: FieldKind, id: String, names: Map<String, String>) = when (kind) {
    is FieldKind.Choice -> kind.options.firstOrNull { it.id == id }?.label ?: id
    is FieldKind.MultiChoice -> kind.options.firstOrNull { it.id == id }?.label ?: id
    else -> resourceName(id, names)
}

/** The one live region: outcome text plus the latest status or tap result, each announced once. */
@Composable
private fun LiveStatus(outcome: String?, notice: Notice?) {
    var lastId by rememberSaveable { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(notice?.takeIf { it.id == lastId }?.text) }
    LaunchedEffect(notice?.id) {
        if (notice == null) {
            shown = null
        } else if (notice.id == lastId) {
            shown = notice.text
        } else {
            // Clear for a frame so a repeated text (two failed reloads) is a change a screen reader announces.
            shown = null
            withFrameNanos { }
            shown = notice.text
            lastId = notice.id
        }
    }
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("form-status"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        outcome?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        shown?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("form-notice")) }
    }
}

@Composable
private fun ActionBar(read: FormRead.Form, state: FormScreenState, actions: FormActions) {
    val send = state.send
    val attempt = send.attempt
    val cancel = attempt?.request?.operation == "cancel"
    var confirmingCancel by remember(read.interactionId) { mutableStateOf(false) }
    val outcome = when (attempt?.outcome) {
        SendOutcome.Sending -> if (cancel) "Canceling the form…" else "Sending your answers…"
        SendOutcome.Unknown -> if (cancel) "We couldn't confirm the form was canceled. Check before trying again." else "We couldn't confirm your answers arrived. Check before sending again."
        SendOutcome.Checking -> if (cancel) "Checking whether the form was canceled…" else "Checking whether your answers arrived…"
        SendOutcome.NotReceived -> if (cancel) "The cancellation didn't arrive. You can try the same cancellation again." else "Your answers didn't arrive. Send them again; nothing changes."
        SendOutcome.Rejected -> attempt.problem
        SendOutcome.Committed, null -> null
    }
    val committedSubmit = attempt != null && attempt.outcome == SendOutcome.Committed && attempt.request.operation != "save-draft"
    val open = read.state == FormState.Open
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("form-actions")) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.readable().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveStatus(outcome, send.announcement)
            val buttons = Modifier.fillMaxWidth().heightIn(min = MinTarget)
            when {
                attempt?.updateRequired == true ->
                    WhumpoPrimaryButton(actions::onUpdate, buttons.testTag("form-update")) { Text("Open Play Store") }
                attempt?.outcome == SendOutcome.Unknown || attempt?.outcome == SendOutcome.Checking ->
                    WhumpoPrimaryButton(actions::onCheckAgain, buttons.testTag("form-check"), enabled = attempt.outcome == SendOutcome.Unknown) { Text("Check again") }
                attempt?.outcome == SendOutcome.NotReceived ->
                    WhumpoPrimaryButton(actions::onSendAgain, buttons.testTag("form-send-again")) { Text(if (cancel) "Try canceling again" else "Send again") }
                committedSubmit -> if (send.followUpFailed) {
                    WhumpoQuietButton(actions::onReload, buttons.testTag("form-reload"), enabled = !send.reading) { Text("Try loading again") }
                }
                open -> {
                    val ready = send.canStart && state.review == null
                    WhumpoPrimaryButton(actions::onSubmit, buttons.testTag("form-send"), enabled = ready) {
                        Text(if (attempt?.outcome == SendOutcome.Sending) "Sending…" else "Send")
                    }
                    WhumpoSecondaryButton(actions::onSaveDraft, buttons.testTag("form-save"), enabled = ready) { Text("Save draft") }
                    WhumpoQuietButton({ confirmingCancel = true }, buttons.testTag("form-cancel"), enabled = send.canStart) { Text("Cancel form") }
                    val savedDraft = attempt != null && attempt.outcome == SendOutcome.Committed && attempt.request.operation == "save-draft"
                    val stale = attempt?.outcome == SendOutcome.Rejected
                    if (savedDraft || stale) {
                        WhumpoQuietButton(actions::onReload, buttons.testTag("form-reload"), enabled = !send.reading) {
                            Text(if (savedDraft) "Reload saved draft" else "Reload form")
                        }
                    }
                }
            }
            if (!send.unresolved) state.continuation?.let { ContinuationControls(it, actions) }
        }
        }
    }
    if (confirmingCancel && open && send.canStart) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text("Cancel this form?") },
            text = { Text("This closes the form without submitting your answers. Closing the screen instead keeps your unsent answers for this session.") },
            confirmButton = { TextButton({ confirmingCancel = false; actions.onCancel() }, Modifier.testTag("form-cancel-confirm")) { Text("Cancel form") } },
            dismissButton = { TextButton({ confirmingCancel = false }, Modifier.testTag("form-cancel-dismiss")) { Text("Keep form") } },
        )
    }
}

@Composable
private fun ContinuationControls(state: FormContinuationState, actions: FormActions) {
    var confirmingStop by remember { mutableStateOf(false) }
    val text = when (state.phase) {
        FormContinuationPhase.NotStarted -> "Your answers are saved. Continue when you're ready for the next step."
        FormContinuationPhase.Pending -> "The next step is in progress. Check its status when you're ready."
        FormContinuationPhase.Succeeded -> "The next step finished."
        FormContinuationPhase.Unknown -> "We couldn't confirm the next step's outcome. Check it before doing anything else."
        FormContinuationPhase.Failed -> "The next step did not finish. Your submitted answers are still saved."
        FormContinuationPhase.Unavailable -> "Update Whumpo to view this next step. Your submitted answers are saved."
    }
    Column(Modifier.fillMaxWidth().testTag("form-continuation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val buttons = Modifier.fillMaxWidth().heightIn(min = MinTarget)
        when (state.phase) {
            FormContinuationPhase.NotStarted -> {
                WhumpoPrimaryButton(actions::onContinue, buttons.testTag("form-continue"), enabled = state.canContinue) { Text("Continue") }
                WhumpoQuietButton({ confirmingStop = true }, buttons.testTag("form-stop"), enabled = state.canStop) { Text("Stop next step") }
            }
            FormContinuationPhase.Pending, FormContinuationPhase.Unknown ->
                WhumpoPrimaryButton(actions::onCheckContinuation, buttons.testTag("form-continuation-check"), enabled = state.canCheck) { Text(if (state.busy) "Checking…" else "Check next step") }
            FormContinuationPhase.Succeeded -> if (state.resultTurnId != null) {
                WhumpoPrimaryButton(actions::onOpenContinuationResult, buttons.testTag("form-continuation-result")) { Text("Return to Chat") }
            }
            FormContinuationPhase.Unavailable ->
                WhumpoQuietButton(actions::onUpdate, buttons.testTag("form-continuation-update")) { Text("Open Play Store") }
            FormContinuationPhase.Failed -> Unit
        }
    }
    if (confirmingStop && state.canStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("Stop before the next step?") },
            text = { Text("Your submitted answers stay saved. This stops the next step from starting.") },
            confirmButton = { TextButton({ confirmingStop = false; actions.onStopContinuation() }, Modifier.testTag("form-stop-confirm")) { Text("Stop next step") } },
            dismissButton = { TextButton({ confirmingStop = false }, Modifier.testTag("form-stop-dismiss")) { Text("Keep next step") } },
        )
    }
}
