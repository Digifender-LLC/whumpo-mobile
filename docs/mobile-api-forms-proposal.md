# Native forms wire snapshot (synthetic)

The v1 fixtures use the `interaction.read` and `interaction.resources` shapes
in Section A. The other sections give client rules and synthetic cases for
these shapes. The server does not accept the example `template:"example@1"`
or the placeholder digests. Implementation, deployment, compatibility,
persistence, and live acceptance require separate evidence.

The fixture is [`forms.json`](../tests/fixtures/proposed/forms.json). For the
authoritative v1 contract, use `docs/mobile-api-v1.md` and
`tests/fixtures/mobile-v1/`. If this document differs, use those sources.

## A. Proposed amendment to frozen v1 shapes (normative, adopted into the v1 fixtures)

The adopted response objects replaced the earlier `interaction.read` and
`interaction.resources` responses in `tests/fixtures/mobile-v1/operations.json`.
These are the earlier frozen v1 shapes.

`interaction.read` response body:

```json
{
  "version": 1,
  "state": "open",
  "answerRevision": 0
}
```

`interaction.resources` response body:

```json
{
  "version": 1,
  "items": [
    {
      "id": "11111111-1111-4111-8111-000000000001",
      "name": "Example Detailing"
    }
  ],
  "next": null
}
```

The exact adopted read response object, also present as `reads.open` in `forms.json`, is:

```json
{
  "version": 1,
  "envelope": {
    "version": 1,
    "id": "66666666-6666-4666-8666-000000000001",
    "expiresAt": "2030-01-08T00:00:00.000Z",
    "definition": {
      "version": 1,
      "kind": "form",
      "template": "example@1",
      "title": "Example form",
      "summary": "Synthetic example values only.",
      "presentation": "inline",
      "fields": [
        {
          "id": "short-text",
          "type": "text",
          "label": "Text example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "maxLength": 120
          },
          "default": {
            "value": "Synthetic default",
            "provenance": "user"
          }
        },
        {
          "id": "long-text",
          "type": "long-text",
          "label": "Long text example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "maxLength": 500
          },
          "group": 1
        },
        {
          "id": "choice",
          "type": "choice",
          "label": "Choice example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "options": [
              {
                "id": "option-a",
                "label": "Option A"
              },
              {
                "id": "option-b",
                "label": "Option B"
              }
            ]
          },
          "default": {
            "value": "option-a",
            "provenance": "suggested"
          }
        },
        {
          "id": "multi-choice",
          "type": "multi-choice",
          "label": "Multiple choice example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "options": [
              {
                "id": "option-a",
                "label": "Option A"
              },
              {
                "id": "option-b",
                "label": "Option B"
              },
              {
                "id": "option-c",
                "label": "Option C"
              }
            ]
          }
        },
        {
          "id": "include-details",
          "type": "boolean",
          "label": "Include details",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {}
        },
        {
          "id": "details",
          "type": "text",
          "label": "Conditional text example",
          "help": "",
          "required": false,
          "binding": "answer.value@1",
          "constraints": {
            "maxLength": 80
          },
          "when": {
            "fieldId": "include-details",
            "equals": true
          }
        },
        {
          "id": "quantity",
          "type": "number",
          "label": "Number example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "min": 0,
            "max": 100,
            "step": 5
          }
        },
        {
          "id": "date",
          "type": "date",
          "label": "Date example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {}
        },
        {
          "id": "datetime",
          "type": "datetime",
          "label": "Date and time example",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "zone": "UTC"
          }
        },
        {
          "id": "project",
          "type": "resource",
          "label": "One project",
          "help": "",
          "required": true,
          "binding": "answer.value@1",
          "constraints": {
            "collection": "projects",
            "multiple": false,
            "maxItems": 1
          }
        },
        {
          "id": "projects",
          "type": "resource",
          "label": "Several projects",
          "help": "",
          "required": false,
          "binding": "answer.value@1",
          "constraints": {
            "collection": "projects",
            "multiple": true,
            "maxItems": 3
          }
        }
      ],
      "cards": [],
      "submitBinding": "interaction.answers@1",
      "continuationBinding": null
    },
    "definitionDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  },
  "state": "open",
  "answerRevision": 0,
  "draft": null,
  "replacementInteractionId": null,
  "submission": null
}
```

The exact adopted resources page object, also present as the first entry in `resources.pages` in `forms.json`, is:

```json
{
  "version": 1,
  "interactionId": "66666666-6666-4666-8666-000000000001",
  "definitionDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "fieldId": "project",
  "collection": "projects",
  "items": [
    {
      "id": "11111111-1111-4111-8111-000000000001",
      "name": "Synthetic project one",
      "photo": null
    },
    {
      "id": "11111111-1111-4111-8111-000000000002",
      "name": "Synthetic project two",
      "photo": null
    }
  ],
  "nextBefore": {
    "id": "11111111-1111-4111-8111-000000000002",
    "name": "Synthetic project two"
  }
}
```

The adopted `POST /interactions/read` response is `{version:1,envelope:{version:1,id,expiresAt,definition,definitionDigest},state,answerRevision,draft,replacementInteractionId,submission}`. The client ignores other read or
envelope members. It does not show them or send them back. The
`definitionDigest` is opaque. The client sends it back unchanged and does not
calculate it.

`state` is `open`, `submitted`, `canceled`, `expired`, or `superseded`.
Other values require an update. The client uses `state` as authoritative.
A future `expiresAt` does not open a form in a non-open state. Under the
proposal, the server resolves an `open` response whose expiry elapsed. The
client never infers the state from expiry.

`draft` is `null` if there is no saved draft. If a saved draft exists,
`draft` is an answers object. An empty saved draft is `{}`.
`replacementInteractionId` is `null` or the UUID of the replacement form.
A `superseded` form with a null replacement is terminal and closed.

`submission` is `null` or `{id,answers,continuation}`. Its `continuation` is
`null` or uses the public `Continuation` shape `{id,state,resultTurnId,reason}`.
The read response includes submitted answers.

The `definition` shape is
`{version:1,kind,template,title,summary,presentation,fields,cards,submitBinding,continuationBinding,purpose?}`.
A native form requires `kind:"form"` and `cards:[]`. Other kinds and non-empty
card arrays require an update. `template` and optional `purpose` are opaque
labels. `presentation` is `inline`, `panel`, or `dialog`. If `presentation`
has a different value, show the update screen without fields and do not send
a form mutation. The title limit is 100 UTF-16 code units. The summary limit
is 400 UTF-16 code units. A definition contains 1–12 fields in display order.

Each field has shape
`{id,type,label,help,required,binding:"answer.value@1",constraints,default?,when?,group?,source?,questionId?}`.
The field ID matches `^[a-z][a-z0-9-]{0,39}$`. The label contains 1–80 UTF-16
code units. Help text contains 0–240 UTF-16 code units and can be empty. Labels
and help use plain text. `source` and `questionId` are opaque.

An optional `default` has shape `{value,provenance}`. Allowed provenance
values are `saved`, `user`, and `suggested`. The client fills the answer with
the default. It shows a suggested value as a suggestion. The optional `when`
has shape `{fieldId,equals}`. The client shows the field only while the earlier
choice or boolean field equals `equals`. It omits answers for hidden fields.
The optional `group` is an integer from 0 through 2. The client uses it only
to group fields on the screen.

The adopted read shape and its example include `default`, `when`, and
`group`. The native client fills defaults, shows suggestions, and shows fields
when their conditions apply.

| Type | Constraints | Answer |
| --- | --- | --- |
| `text` | `{maxLength}` where `maxLength` is 1–200 | A string; an empty string is valid only when the field is optional. |
| `long-text` | `{maxLength}` where `maxLength` is 1–2000 | A string; an empty string is valid only when the field is optional. |
| `choice` | `{options:[{id,label}]}` with 1–12 options and unique IDs | One option ID. |
| `multi-choice` | `{options:[{id,label}]}` with 1–12 options and unique IDs | Unique option IDs; the answer is non-empty when the field is required. |
| `boolean` | `{}` | `true` or `false`; `false` is a complete answer. |
| `number` | `{min,max,step}` with finite bounds within ±1e9 and `step>0` | A number on the step grid starting at `min`. |
| `date` | `{}` | `YYYY-MM-DD` with a year from 1 through 9999. |
| `datetime` | `{zone}` using an IANA time-zone name | A UTC value in `YYYY-MM-DDTHH:MM:SS.sssZ` form. |
| `resource` | `{collection:"projects",multiple,maxItems}`; `maxItems` is 1 for one value or 1–5 for multiple values | A UUID, or a unique UUID array when multiple values are allowed. |

Numeric bounds are exact and inclusive. For finite JSON numbers, use their
closest shortest decimal values. These values match Node's `JSON.stringify`
output, including subnormals. The distance from the answer to the nearest grid point anchored at
`min` must be strictly less than `step * 1e-7`; equality is rejected. This is a
fraction of the step, independent of answer magnitude. The native client also
rejects an entry if its Double wire conversion loses precision. For example, `0.300000001` fits `min=0.1, step=0.2`; with `min=0,
step=1`, `0.00000009999999` fits and `0.0000001` does not. These rules do not
introduce an arbitrary-precision wire format or establish live-service acceptance.

Every field's `binding` is exactly `answer.value@1`. Within the native admission set, `submitBinding` is `interaction.answers@1`; any other value requires an update. `continuationBinding` is `null`, `agent.task-continue@1`, or `site.content-prepare@1`; which combinations a given template allows is server-side and not disclosed.

Proposed: a build that renders a `resource` field with `collection:"projects"` declares `owned.projects@1` among its interaction capabilities; this declaration is not a field binding.

The client omits unanswered fields. Drafts can omit required fields. The
answer size limit is 16 KiB. The mutation body size limit is 32 KiB. Measure
text length in UTF-16 code units.

A field-level `422` response has `{version:1,error:{code,message,fieldId}}` for one field problem. It is passed through from the interaction vocabulary and is outside the closed mobile codes in `tests/fixtures/mobile-v1/errors.json`; its synthetic example is `errors.field422` in the forms fixture. The `code` set is open, and clients do not branch on unknown codes. A `400` response is a form-level shape or validation error. For an interaction-route `409` whose code is none of the mapped codes, the client rereads and classifies the result: a replacement means “form changed,” a terminal state means “no longer open,” and a greater `answerRevision` means “newer answers.” The mapped codes `onboarding_required`, `identity_changed`, `request_conflict`, `stale_revision`, `chat_busy`, and `review_unavailable` keep existing behavior. `request_conflict` means the same request ID was already used with a different payload; the original payload must be reused for a retry.

The native client handles interaction-route 422 responses as field problems, with unknown or absent field IDs shown at form level. It maps known mobile error codes only at their documented status; unknown codes remain unrecognized. Required client checks are: parse a 422 with a known `fieldId`; send unknown or absent `fieldId` to form level; a 422 without `fieldId` is handled at form level without crashing; do not branch on an unknown code; reread after an unmapped interaction-route 409; preserve mapped 409 behavior; every mapped 409 code keeps its existing handling; exercise every update-required case; ignore extra read and envelope metadata; fail closed on extra definition and field members; echo a cursor unchanged; and handle receipt 404.

For `POST /interactions/receipt`, `404 not_found` means no mutation was recorded for that request ID.

The adopted resources page response is `{version:1,interactionId,definitionDigest,fieldId,collection:"projects",items:[{id,name,photo:null}],nextBefore}`. `nextBefore` is an opaque JSON value or `null` when exhausted. The client sends the value unchanged as `before` in the next request. It does
not examine or make the cursor. The contract does not specify the cursor
structure shown in the example.

The minimum-build plan uses only these verified facts: `GET /config` returns `minimumBuild`; a build below it receives `426 client_update_required`; and `versionCode` is the base plus the CI run number. The proposed `minimumBuild` is raised to the `versionCode` of the first released build implementing the amended read/resources parsing, and is set only when that release exists. The adopted shapes need no server change. The paired website fixture copy lands with this amendment and the client change follows separately; nothing here is deployed, and no numeric build threshold is proposed. The `minimumBuild` value `40` in `errors.clientUpdateRequired426` is a synthetic example, not a proposed threshold.

The fixture contains no example of `continuationBinding:"site.content-prepare@1"`, of the optional `source` member, or of `saved` provenance; those documented values are not covered by the fixture.

## B. Existing capability (public at 4f05dc0)

The public mobile API snapshot documents these build-level interaction bindings: `answer.value@1`, `interaction.answers@1`, `owned.projects@1`, `site.content@1`, `agent.task-continue@1`, and `site.content-prepare@1`. This build-level list does not imply that each field can use any of those bindings.

## C. Client UX recommendations (non-normative)

Fail closed to an update-required screen with no fields or mutations for any unsupported field type or definition kind, any unknown member in a definition or field, state, binding, or `presentation`, and whenever `cards` is non-empty. A malformed definition or read also fails closed.

Show submitted answers read-only to their authorized owner and keep them in memory only for that screen. When a 422 `fieldId` names a field in the current definition, show the problem on that field; otherwise show it at form level. Label a resource ID whose name is not present in a loaded page as “Name not loaded.”

Require a tap for each retry or read. Do not poll. If the mutation outcome
is uncertain, require a receipt check before a retry. If the owner selects
retry, use the original request ID and the exact request body. Do not create
a new ID automatically.

This forms contract adds no fresh-passkey requirement. Review confirmation keeps its own existing fresh-passkey requirement.

Photo resources are deferred under WH-085. The digest remains opaque and is never computed by the client.

The proposal originally cited these source locations. The line numbers refer
to the version it cited.

- `mobile-api-v1.md` lines 12, 25, 33–39, 85–104, 150, 153–166
- `Models.kt` lines 403–410
- `tests/fixtures/mobile-v1/operations.json` interaction response bodies

## D. Adopted continuation clarification

The existing continuation shape is `{id,state,resultTurnId,reason}`. A submitted form has a continuation exactly when its definition has a continuation binding. Null means no followup, never permission to start one. Its ID equals both the enclosing submission ID and the requested `submissionId` in an action response. Non-null result turn IDs must be UUIDs. The earlier proposed pending/failed examples had inconsistent binding/ID values; those wholly synthetic examples are corrected without changing any route or wire shape.

| State | Native behavior |
| --- | --- |
| `not-started` | Continue only on an explicit tap using this submission and the current Chat revision, while the form is unexpired/unreplaced/current. Confirmed Stop only stops never-started work; it remains available after expiry, subject to server ownership checks. |
| `pending` | Already running: explicit status read/reconciliation only; no Continue or Stop. |
| `unknown` | Explicit reconciliation of this submission only; never Continue. |
| `succeeded` | Terminal result, no rerun. A non-null valid result turn can be refreshed/opened in Chat; null has no navigation. Existing action reviews still apply. |
| `failed` | Terminal, including stopped/changed/unavailable. No retry-as-new authority. |
| Future state | Read-only/update-required; no mutation. |

If a reply is lost, keep the submission ID and its uncertain status. Do not
dispatch automatically, poll, or create a new submission. A fresh server
read can establish `not-started` again. Stale reads, failed reads, and
status-only updates cannot establish it. The server still controls identity,
ownership, replacement, expiry, and feature eligibility.
