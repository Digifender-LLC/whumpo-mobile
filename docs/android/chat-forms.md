# Native chat forms

Chat uses native forms under the adopted [mobile API v1](../mobile-api-v1.md)
contract and its fixtures in `tests/fixtures/mobile-v1/`. The model, decoder,
send recovery, resource paging, controller, and Compose screen are in
`android/shared/src/commonMain/kotlin/com/whumpo/android/ui/chat/forms/`. The
tests use synthetic examples in `tests/fixtures/proposed/forms.json`. Checks
cover JVM, host UI, screenshots, and API 28 and 36 emulators. Live Worker
compatibility, physical-device and TalkBack use, signed releases, and customer
acceptance require separate evidence.

## Goal

Replace the chat placeholder ("Answering forms in the app is coming soon") with a
native form for definitions inside the admitted subset in
[mobile API v1](../mobile-api-v1.md): field types `text`, `long-text`, `choice`,
`multi-choice`, `boolean`, `number`, `date`, `datetime`, `resource`. A
definition outside that subset shows the update-required state. There is no
simplified form, text projection or browser downgrade.

## Placement

- **Chat card.** Each turn with an interaction shows a card under the reply:
  a known form title, a one-line status (`Needs your answers`, `Draft saved`,
  `Sent`, `No longer open`) and one action (`Answer`, `Continue`, `View`). The
  compact Chat reference has no title or draft flag: a generic title is shown
  until an explicit form read supplies metadata, retained only in account memory.
  The card is one accessible node with a merged description.
- **Form surface.** `Answer` opens the form full height on compact widths
  (a dialog-style screen with a top bar: close, title, step-free scroll) and
  as the right-hand pane on expanded widths, matching the review sheet layout.
  Predictable back: back/close keeps typed answers on the device and sends
  nothing. On compact widths, the covered conversation leaves keyboard traversal
  and the accessibility tree. Closing restores the conversation position and
  draft without replaying an old confirmation haptic.
- Existing theme only: `WhumpoTheme` colors and typography, Material 3 inputs,
  `WhumpoPrimaryButton`/`WhumpoSecondaryButton`/`WhumpoQuietButton`, state
  components for loading, error, offline and update-required.

## Fields

| Type | Control | Notes |
| --- | --- | --- |
| `text` | Single-line outlined text field | Visible label above value, helper text, counter when a length limit exists |
| `long-text` | Multi-line text field, grows to a bounded height then scrolls | Enter inserts a newline; the keyboard action does not submit |
| `choice` | Radio group (≤ 6 options) or a searchable list | Whole row is the 48dp target; `selectableGroup` semantics |
| `multi-choice` | Checkbox group | Selected count is announced; limits shown as helper text |
| `boolean` | Switch row with the label as the row text | `toggleable` row; state read as on/off |
| `number` | Text field with numeric keyboard | Locale-aware entry; range and step in helper text |
| `date` | Read-only field opening the Material date picker | The admitted contract permits years 1–9999 and has no additional date bounds |
| `datetime` | Date picker then time picker | Shows the time zone the answer is sent in |
| `resource` | Picker over `interactions/resources` pages | Search, paging with `before`, empty and error states |

At large text sizes, date and time pickers use their Material input modes so
calendar and clock labels stay readable. Both require an explicit confirmation.

## Accessibility and input

- Each field has a label that stays visible. Placeholders give examples only.
- The app shows "Required" text for each required field.
- The app shows an error under its field and links it to that field. The
  accessibility announcement uses the polite setting. After a failed submit, focus moves to the first
  invalid field and a short summary at the top lists the problems.
- The keyboard action moves to the next field. The action on the last field
  closes the keyboard. The submit bar stays above the keyboard (`imePadding`), and the
  focused field is scrolled into view.
- At 200% text size, labels and errors wrap without truncation. The submit
  bar can scroll with the content to keep fields visible.
- Targets are at least 48dp. Light and dark use theme roles only.

## Validation

The client applies the limits in the form definition. It does this when a
field loses focus and when the user submits the form. It does not examine
every keystroke in untouched fields. The server remains authoritative. The app
shows each server error at the named field. If the field ID is unknown, the
app shows the error at form level.

Number entry retains the finite JSON number format. Range bounds are exact and
inclusive. The canonical decimal distance to the nearest grid point must be
strictly less than `step * 1e-7`, without widening it at large magnitudes. The
client uses the closest shortest decimal matching Node's `JSON.stringify`,
including subnormals; a deterministic Node-generated corpus checks JVM parity.
Typed digits that would be lost
by the wire conversion show a precision error. Date/time input that would convert
outside UTC years 1–9999 remains editable and invalid. Submitted reads missing
required visible answers fail closed.

## Submission and uncertain sends

Uses the existing `interactions/mutate`, `receipt`, `continue`, `stop` and
`reconcile` aliases.

- The app creates one `requestId` per submit attempt. It uses the same ID for
  each retry of that attempt, as it does for chat messages.
- While a send is in progress, the app disables the button and shows progress.
- If `receipt` returns the new `answerRevision`, the card shows "Sent".
  The app keeps the receipt and read-only answers visible until Close or Back.
  This keeps Continue, Stop, or Check available when the form requires it.
- If the reply is lost, the app shows the uncertain status and offers
  Check again. When the owner taps it, the app reads `interactions/receipt`
  for the same `requestId`. The app does not send again automatically.
  If the receipt shows a recorded mutation, the app accepts the result.
  If the receipt shows no commit, `Send again` uses the same ID and answers.
  Otherwise, `Check again` stays available.
- If the definition or answer revision is stale, the app reads the interaction
  again. It keeps typed values only for fields with the same ID and type. It
  lists fields that the new definition removes or changes to a different type.
  For a newer saved draft, the review card offers "Use newer answers" and
  "Keep my answers". Send and Save draft stay disabled until the owner selects
  an option. The option sends nothing. Unedited answers use the newer read.
  A late reopen read cannot replace answers that the app already confirmed at
  a newer revision. The app keeps a submit follow-up that arrives during pane
  loading.
- The [published lifecycle](../mobile-api-v1.md#native-form-continuation-lifecycle)
  specifies the continuation controls. Only `not-started` offers Continue or
  confirmed Stop. In `pending`, the owner can request a status check. In
  `unknown`, the app reconciles the same submission. The succeeded and
  failed states are terminal. Unsupported states permit no mutations.
  A successful result can refresh Chat. Prepared changes still require the
  ordinary review.
- Save draft uses `save-draft` with the same guarantees. Cancel form requires
  explicit confirmation and sends no answers.
- If the app is offline or proof is necessary, answers stay in account memory.
  The app sends nothing until the owner acts again. Sign-out or account change
  clears answers, retained requests, continuation state, and card metadata.
  SavedInstanceState contains no answers, including in Material picker or
  input state.

## Update required

If the read returns `426 client_update_required` or an unsupported definition,
the card and form show "Update Whumpo to answer this form." with the update
action. The app shows no fields.

## Tests and evidence

- JVM (`jvmCommonTest/.../chat/forms/`): every proposed read decodes with its
  state; every update-required case and 39 malformed variants fail closed;
  validation per field type; hidden and empty answers are never sent;
  mutations match the fixture requests; the seven invalid mutations are
  refused; daylight-saving gaps and repeats are never guessed; one request per
  attempt, receipt check only on tap, Send again reuses the same request
  object, one automatic read per confirmed submit, separate announcements for
  separate saves, and an account change drops pending work and late replies,
  including calls still queued. Only errors refused before any write (closed
  validation, sign-in, onboarding, size, media, stale revision, not found,
  update required) allow a new request ID; `unavailable`, `rate_limited`, a
  code at the wrong status and anything unrecognized stay receipt-gated.
- Host UI (`androidHostTest/.../FormScreenTest.kt`): update required shows no
  fields and no Get help; required labels; 48dp targets; first-problem focus;
  Sent is status text; an unconfirmed send offers only Check again, and not
  received only Send again; actions stay reachable at 200% text and on wide
  screens; submitted answers are read-only with "Name not loaded".
- Controller (`jvmCommonTest/.../FormControllerTest.kt`): opening reads once
  and writes nothing; the opaque `nextBefore` is echoed unchanged; a page for
  another form, digest or field is ignored and can be retried; late pages and
  reads after close or account change are ignored, and a queued read never
  starts; a failed read offers only Try loading again; an unknown form fails
  closed with nothing sent; a 422 `fieldId` shows at a known field and at form
  level otherwise; an unresolved send blocks edits and new writes; no saved
  draft prefills the definition's defaults, an empty saved draft stays empty
  and a saved draft wins; a reread with a new digest loads fresh first pages
  once and ignores the old page; a read for another interaction is never
  shown; closing while sending or unknown keeps the frozen request, receipt
  gate and unsent answers for reopen, also across another form, and an
  account change drops them; a newer saved draft is staged for review rather
  than overwriting edits, both choices send nothing, and removed or retyped
  fields are taken out while changed constraints still validate; edits made
  after a successful save survive close and reopen at the saved revision, while
  a committed submit keeps no answers. Edits are measured against the answers
  of the latest save confirmed committed (even when its reply came late), never
  an unconfirmed one, so returning to an older value after a save is still an
  edit and a newer read is staged for review.
- API (`MobileApiClientTest`): 422 field problems only on interaction routes;
  retries send the frozen request bytes unchanged.
- Device (`androidTest/.../FormDeviceTest.kt`, API 28 and 36 emulators in CI):
  typed input sends nothing until Send; every field and action is reachable at
  200% text, with 48dp actions and Accessibility Test Framework checks; the
  action bar stays above the soft keyboard in the app window. Date/time popup
  confirmations stay above the actual IME at 200% system text. Compact form
  coverage inspects the platform accessibility tree and dispatches Tab keys;
  it does not establish manual TalkBack navigation or announcement quality.
- Form and card screenshot baselines cover compact/wide, light/dark, large text,
  validation and continuation states. Physical TalkBack, live Worker, physical
  devices, signed releases and customer acceptance remain separate.

## Wiring

- `ChatViewModel` owns one `FormController` over `ClientFormApi`
  (`MobileApiClient`) and resets it with the chat on sign-out or account change.
- The chat card's Open form button reads the form; it opens in the detail pane
  on wide screens and full height on compact ones, where Back closes it.
- Close and Back only dismiss the form. An unresolved send and unsent answers
  stay in memory for that interaction until a conclusive outcome or an account
  change; reopening reads the form again and never sends.
- `FormSubmitter` sends frozen request bytes (`MobileApiClient.freezeMutation`);
  an unmapped interaction 409 stays receipt-gated and triggers one reread to
  explain replacement, a closed form or newer answers.
- The `Whumpo-Client` header already declares all nine field types; they match
  what the screen renders.
