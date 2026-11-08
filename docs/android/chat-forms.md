# Native chat forms (design)

Status: design only. Wire parsing and UI code wait for a public, synthetic
snapshot of the interaction definition contract (see Dependencies). Nothing here
changes the client header, admitted field types or bindings.

## Goal

Replace the chat placeholder ("Answering forms in the app is coming soon") with a
native form for definitions inside the admitted subset in
[mobile API v1](../mobile-api-v1.md): field types `text`, `long-text`, `choice`,
`multi-choice`, `boolean`, `number`, `date`, `datetime`, `resource`. A
definition outside that subset shows the update-required state. There is no
simplified form, text projection or browser downgrade.

## Placement

- **Chat card.** Each turn with an interaction shows a card under the reply:
  form title, a one-line status (`Needs your answers`, `Draft saved`,
  `Sent`, `No longer open`) and one action (`Answer`, `Continue`, `View`).
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
| `date` | Read-only field opening the Material date picker | Bounds disable out-of-range days |
| `datetime` | Date picker then time picker | Shows the time zone the answer is sent in |
| `resource` | Picker over `interactions/resources` pages | Search, paging with `before`, empty and error states |

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
- **Accepted** (`receipt` with the new `answerRevision`): the form closes to the
  card, which reads "Sent", and the live region says so once.
- **Lost reply** (outcome unknown): the form says "Didn't hear back yet.
  Checking." and reads `interactions/receipt` for the same `requestId`. It never
  re-sends on its own. If the receipt shows the answer, it is treated as
  accepted; if it shows nothing was committed, `Send again` reuses the same ID
  and answers; otherwise `Check again` stays available.
- **Stale definition or answer revision:** re-read the interaction, keep typed
  values for fields whose id and type are unchanged, mark the rest as changed
  and ask the owner to review before sending.
- **Continuation `unknown`:** resolved with `reconcile`, never by a new
  `continue`.
- **Save draft** uses `save-draft` with the same guarantees. **Cancel form** is
  explicit, confirmed, and sends no answers.
- **Signed out / proof required / offline:** answers stay on the device for this
  session; nothing is sent until the owner acts again.

## Update required

A `426 client_update_required` read or a definition outside the admitted subset
shows the existing update-required pattern inside the card and form: "Update
Whumpo to answer this form." with the update action. No field is rendered.

## Tests and evidence (planned)

- JVM: form state, validation per field type, request-ID reuse, receipt
  readback, stale re-read, reconcile, update-required, using synthetic fixtures.
- Roborazzi goldens: card states and form states (empty, filled, errors,
  sending, lost reply, sent, update-required) at compact and expanded widths,
  light and dark, and 200% font. Baselines are recorded locally and verified
  in CI.
- Instrumented: a separate forms test for keyboard and TalkBack-relevant
  semantics.
- Not covered: the live Worker, physical devices, release builds and customer
  acceptance.

## Dependencies

- A public, synthetic snapshot of the interaction contract: the definition
  object and digest placement, per-type field schema and constraints, answer
  encoding, states and list items, field-level and stale errors, receipt to
  submission linkage, and a definition exercising every admitted type plus a
  426 case. It lands in `docs/mobile-api-v1.md` and `tests/fixtures/mobile-v1/`
  and updates the website's compatibility pin.
- Shared test helpers and the existing keyboard, large-text and accessibility
  instrumented tests are changed by an open onboarding PR; forms code avoids them
  until that PR lands.
