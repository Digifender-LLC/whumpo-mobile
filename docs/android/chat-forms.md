# Native chat forms

Status: implemented against the adopted contract in
[mobile API v1](../mobile-api-v1.md) and `tests/fixtures/mobile-v1/`, and wired
into the chat. The model, decoder, send recovery, resources paging, controller
and Compose screen live in
`android/shared/src/commonMain/kotlin/com/whumpo/android/ui/chat/forms/`; the
synthetic catalog in `tests/fixtures/proposed/forms.json` drives the tests.
Verification uses synthetic JVM, host UI, screenshot and API 28/36 emulator
checks. These checks do not establish live Worker compatibility, physical-device/
TalkBack, signed-release or customer acceptance.

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
  nothing.
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

- Every field has a persistent visible label; placeholders are examples only.
- Required fields say "Required" in text, not by color or an asterisk alone.
- Errors appear under the field, are tied to it as the field's error semantics
  and are announced politely. After a failed submit, focus moves to the first
  invalid field and a short summary at the top lists the problems.
- IME actions move to the next field; the last field's action closes the
  keyboard. The submit bar stays above the keyboard (`imePadding`), and the
  focused field is scrolled into view.
- Large text (200%) reflows: labels and errors wrap, no truncation, the submit
  bar can scroll with content instead of covering fields.
- Targets are at least 48dp. Light and dark use theme roles only.

## Validation

Client validation mirrors the definition's own constraints for fast feedback
and runs on blur and on submit, never on every keystroke for untouched fields.
The server remains authoritative: its field errors are shown on the named
fields; an unknown field id shows the form-level error.

## Submission and uncertain sends

Uses the existing `interactions/mutate`, `receipt`, `continue`, `stop` and
`reconcile` aliases.

- One `requestId` per submit attempt is created once and reused for every retry
  of that attempt, like chat sends.
- **Sending** disables the button and shows progress.
- **Accepted** (`receipt` with the new `answerRevision`): the card records "Sent"
  and the form keeps the receipt/read-only answers visible until Close or Back.
  This keeps explicit next-step choices reachable instead of hiding a required
  Continue, Stop or Check action behind an automatic dismissal.
- **Lost reply** (outcome unknown): the form explains that arrival is unconfirmed
  and offers Check again, which reads `interactions/receipt` for the same
  `requestId` only on a tap. It never re-sends on its own. If the receipt
  confirms the mutation was recorded, it is treated as accepted; if it shows nothing was committed, `Send again` reuses the same ID
  and answers; otherwise `Check again` stays available.
- **Stale definition or answer revision:** re-read the interaction, keep typed
  values for fields whose id and type are unchanged, mark the rest as changed
  and ask the owner to review before sending. Implemented: a newer saved draft
  is held in a review card ("Use newer answers" or "Keep my answers"), answers
  for removed or retyped fields are taken out and listed, and Send and Save
  draft wait for the choice. Choosing sends nothing; unedited answers simply
  follow the newer read.
- **Continuations:** follow the [published lifecycle](../mobile-api-v1.md#native-form-continuation-lifecycle). Only `not-started` exposes Continue/confirmed Stop;
  `pending` offers an explicit status check, `unknown` reconciles the same submission,
  and succeeded/failed are terminal. Unsupported states expose no mutation.
  A successful result can refresh Chat; prepared changes still need ordinary review.
- **Save draft** uses `save-draft` with the same guarantees. **Cancel form** is
  explicit, confirmed, and sends no answers.
- **Offline or proof required:** answers stay only in account memory; nothing is
  sent until the owner acts again. Sign-out/account change clears all form answers,
  retained requests, continuation state and card metadata. No form answers enter
  SavedInstanceState, including Material picker/input state.

## Update required

A `426 client_update_required` read or a definition outside the admitted subset
shows the existing update-required pattern inside the card and form: "Update
Whumpo to answer this form." with the update action. No field is rendered.

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
  action bar stays above the soft keyboard in the app window.
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
