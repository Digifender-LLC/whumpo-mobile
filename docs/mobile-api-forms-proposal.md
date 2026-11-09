# Native forms wire snapshot (synthetic)

Section A's `interaction.read` and `interaction.resources` shapes are adopted into the v1 fixtures; the remaining sections are the client rules and synthetic case catalog for them. The fixture values are synthetic; `template:"example@1"` and placeholder digests are not server-valid as-is. Nothing here claims implementation, deployment, compatibility, persistence, or live acceptance.

The fixture is [`forms.json`](../tests/fixtures/proposed/forms.json). `docs/mobile-api-v1.md` and `tests/fixtures/mobile-v1/` remain the authoritative v1 contract; where this document differs from them, they win.

## A. Proposed amendment to frozen v1 shapes (normative, adopted into the v1 fixtures)

The read and resources response objects below **replaced**, rather than added to, the earlier `interaction.read` response and `interaction.resources` response in `tests/fixtures/mobile-v1/operations.json`. The earlier frozen v1 shapes were:

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

The adopted `POST /interactions/read` response is `{version:1,envelope:{version:1,id,expiresAt,definition,definitionDigest},state,answerRevision,draft,replacementInteractionId,submission}`. Additional read or envelope members are server metadata that the client ignores and does not echo or display. `definitionDigest` is opaque: the client echoes it unchanged and never computes it.

`state` is `open`, `submitted`, `canceled`, `expired`, or `superseded`; any other value requires an update. `state` is authoritative over `expiresAt`: a future expiry does not make a non-open state open. Proposed: if an `open` response has an elapsed expiry, the server resolves it; the client never infers a state from expiry. `draft` is `null` when no saved draft exists or an answers object when one exists; `{}` is a saved empty draft. `replacementInteractionId` is `null` or the UUID of the form that replaced this one. A `superseded` state with a null replacement is terminal and no longer open.

`submission` is `null` or `{id,answers,continuation}`. Its `continuation` is `null` or uses the existing public `Continuation` shape `{id,state,resultTurnId,reason}`. Submitted answers are included in the read response.

The `definition` shape is `{version:1,kind,template,title,summary,presentation,fields,cards,submitBinding,continuationBinding,purpose?}`. A native form requires `kind:"form"` and `cards:[]`; any other kind or a non-empty cards array requires an update. `template` and optional `purpose` are opaque labels. `presentation` is `inline`, `panel`, or `dialog`; any other `presentation` requires an update, and the client must fail closed: it does not render the form and performs no form mutation. The title is at most 100 UTF-16 code units, the summary is at most 400 UTF-16 code units, and a definition contains 1–12 fields in display order.

Every field has shape `{id,type,label,help,required,binding:"answer.value@1",constraints,default?,when?,group?,source?,questionId?}`. The field ID matches `^[a-z][a-z0-9-]{0,39}$`; the label is 1–80 UTF-16 code units, help is 0–240 UTF-16 code units and may be empty, and labels and help are plain text. `source` and `questionId` are opaque.

An optional `default` has shape `{value,provenance}`, with allowed provenance values `saved`, `user`, and `suggested`. It prefills an answer, and a suggested value is shown as a suggestion. The optional `when` has shape `{fieldId,equals}`; it shows a field only while the earlier choice or boolean field equals `equals`, and answers for fields that are not shown are omitted. The optional `group` is an integer from 0 through 2 and is for visual grouping only. `default`, `when` and `group` are part of the adopted read shape and appear in its example; client prefill, suggestion display and conditional display are pending client work.

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

Every field's `binding` is exactly `answer.value@1`. Within the native admission set, `submitBinding` is `interaction.answers@1`; any other value requires an update. `continuationBinding` is `null`, `agent.task-continue@1`, or `site.content-prepare@1`; which combinations a given template allows is server-side and not disclosed.

Proposed: a build that renders a `resource` field with `collection:"projects"` declares `owned.projects@1` among its interaction capabilities; this declaration is not a field binding.

Unanswered fields are omitted, and drafts may omit required fields. Answers are limited to 16 KiB and a mutation body is limited to 32 KiB. Lengths are measured in UTF-16 code units.

A field-level `422` response has `{version:1,error:{code,message,fieldId}}` for one field problem. It is passed through from the interaction vocabulary and is outside the closed mobile codes in `tests/fixtures/mobile-v1/errors.json`; its synthetic example is `errors.field422` in the forms fixture. The `code` set is open, and clients do not branch on unknown codes. A `400` response is a form-level shape or validation error. For an interaction-route `409` whose code is none of the mapped codes, the client rereads and classifies the result: a replacement means “form changed,” a terminal state means “no longer open,” and a greater `answerRevision` means “newer answers.” The mapped codes `onboarding_required`, `identity_changed`, `request_conflict`, `stale_revision`, `chat_busy`, and `review_unavailable` keep existing behavior. `request_conflict` means the same request ID was already used with a different payload; the original payload must be reused for a retry.

Client 422 handling is **not implemented in the current client**: `MobileApiException.fromResponse` has no `fieldId`, has no 422 handling, and maps unknown codes to `Unrecognized`. Client work is separately owned and pending. Required client tests are: parse a 422 with a known `fieldId`; send unknown or absent `fieldId` to form level; a 422 without `fieldId` is handled at form level without crashing; do not branch on an unknown code; reread after an unmapped interaction-route 409; preserve mapped 409 behavior; every mapped 409 code keeps its existing handling; exercise every update-required case; ignore extra read and envelope metadata; fail closed on extra definition and field members; echo a cursor unchanged; and handle receipt 404.

For `POST /interactions/receipt`, `404 not_found` means no mutation was recorded for that request ID.

The adopted resources page response is `{version:1,interactionId,definitionDigest,fieldId,collection:"projects",items:[{id,name,photo:null}],nextBefore}`. `nextBefore` is an opaque JSON value or `null` when exhausted. The client echoes it unchanged as the next request's `before` and never inspects or constructs it. The example cursor's internal structure is not contractual.

The minimum-build plan uses only these verified facts: `GET /config` returns `minimumBuild`; a build below it receives `426 client_update_required`; and `versionCode` is the base plus the CI run number. The proposed `minimumBuild` is raised to the `versionCode` of the first released build implementing the amended read/resources parsing, and is set only when that release exists. The adopted shapes need no server change. The paired website fixture copy lands with this amendment and the client change follows separately; nothing here is deployed, and no numeric build threshold is proposed. The `minimumBuild` value `40` in `errors.clientUpdateRequired426` is a synthetic example, not a proposed threshold.

The fixture contains no example of `continuationBinding:"site.content-prepare@1"`, of the optional `source` member, or of `saved` provenance; those documented values are not covered by the fixture.

## B. Existing capability (public at 4f05dc0)

The public mobile API snapshot documents these build-level interaction bindings: `answer.value@1`, `interaction.answers@1`, `owned.projects@1`, `site.content@1`, `agent.task-continue@1`, and `site.content-prepare@1`. This build-level list does not imply that each field can use any of those bindings.

## C. Client UX recommendations (non-normative)

Fail closed to an update-required screen with no fields or mutations for any unsupported field type or definition kind, any unknown member in a definition or field, state, binding, or `presentation`, and whenever `cards` is non-empty. A malformed definition or read also fails closed.

Show submitted answers read-only to their authorized owner and keep them in memory only for that screen. When a 422 `fieldId` names a field in the current definition, show the problem on that field; otherwise show it at form level. Label a resource ID whose name is not present in a loaded page as “Name not loaded.”

Use explicit taps for retries and reads; do not poll. After an uncertain mutation outcome, gate retry on the receipt check. If the owner explicitly retries, reuse the original request ID and exact request body; never create a new ID automatically.

This forms contract adds no fresh-passkey requirement. Review confirmation keeps its own existing fresh-passkey requirement.

Photo resources are deferred under WH-085. The digest remains opaque and is never computed by the client.

Public references: `mobile-api-v1.md` lines 12, 25, 33–39, 85–104, 150, 153–166; `Models.kt` lines 403–410; `tests/fixtures/mobile-v1/operations.json` interaction response bodies.
