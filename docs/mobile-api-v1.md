# Native `/api/mobile/v1` contract

This imported contract snapshot describes the mobile API at the recorded source
revision. The [synthetic fixtures](../tests/fixtures/mobile-v1/) give examples of
wire shapes and rules. The historical implementation notes and milestone IDs
refer to the snapshot in [migration notes](migration.md). The public copy keeps
the operation tables and wire shapes and omits internal planning and private
design documents. Live service availability, FCM activation, device acceptance,
and production readiness require separate evidence.

Whumpo supports one current contract version under the
[current-version policy](current-version-policy.md). It excludes old DTOs,
compatibility adapters, and browser downgrades. This document lists all v1
operations. To change a frozen wire shape, review an amendment to this document.
Update the fixtures. Increase the minimum build.

## Principles

1. **A thin transport over existing domain handlers.** Each operation reuses the handler named in the [operation table](#operations); `new` marks the only genuinely new adapters. There is no proxy into `/api/*`, no raw Chat command string and no caller-supplied URL. Existing handlers keep ownership, quota, review and feature-admission checks.
2. **Native authority is a bearer session, nothing else.** A package name, user agent, `Whumpo-Client` header or absent `Origin` grants nothing. Cookie sessions never cross: a Whumpo cookie on a mobile route is refused, and a bearer is refused on every non-mobile route. The browser's `Origin` check on `/api/*` is unchanged.
3. **Server-issued typed metadata is the only source of approval.** The client enables a control only when the server sent it for a current review and the client declared the capability. Text, push arrival and model prose never create approval.
4. **Reads are cheap and authoritative; writes are keyed.** Every durable write carries a client-generated ID. If a reply is lost, read the result. Do not guess or send the write again without a read.
5. **Strict admission.** Missing or unsupported versions, fields, controls, field types or bindings return `client_update_required` or a closed validation error, never a reduced experience.

## Transport

- Use `https://whumpo.com/api/mobile/v1` as the base URL. Use HTTPS only. In
  `local` mode, you can use the documented loopback origin. The server uses
  `application/json; charset=utf-8` with `cache-control: no-store`,
  `x-content-type-options: nosniff`, and `referrer-policy: no-referrer`. Photo
  byte responses instead use `image/jpeg` and `private, no-store`.
- JSON requests are UTF-8, at most 16 KiB (interaction routes 32 KiB, matching [WH-105](chat-interactions.md)). The WebAuthn `credential` object inside `session/login/verify` and `session/reauth/verify` is further limited to 8 KiB of serialized JSON (`413 payload_too_large`); the Android client enforces the same bound. Unknown or missing keys are rejected. Photo upload is the only binary request: `image/jpeg`, at most 1 MiB.
- Rate limits reuse the platform limiters (per client address) plus a per-session limit; exceeding returns `429 rate_limited` with `retryAfterSeconds`.
- Every success body carries `"version": 1`. Dates are ISO-8601 UTC. IDs are lowercase UUIDs. Server cursors are opaque.

### Required headers

| Header | Value | Rule |
| --- | --- | --- |
| `Whumpo-Client` | Compact ASCII JSON, at most 4096 bytes: `{"contract":1,"build":<versionCode>,"platform":"android","controls":[...],"interaction":{"versions":[1],"fieldTypes":[...],"bindings":[...]}}` | Admission and capability declaration only. `contract` must equal `1`, `build` must be at least the server's `minimumBuild`, `interaction.versions` must be exactly `[1]`. Any other value is `426 client_update_required`; malformed or unknown keys are `400`. Present on every request, including anonymous ones. |
| `Authorization` | `Bearer <64 lowercase hex>` | Required on every non-anonymous route, refused on anonymous ones. |
| `Cookie` | none | Presence is `400 invalid_request`. |

If the build is less than `minimumBuild`, it can call only `GET /config`.
This route gives the value of `minimumBuild`. The server also refuses an
incorrect `contract` on this route.

Declared `controls` are exactly the [admitted capabilities](#native-review-controls) the build implements. Declared `interaction` capabilities must be a subset of the native admission set (below) and must equal what the build renders. The server then issues `canApprove:true` only for a control whose capability is declared, and `interaction/read` rejects any definition needing something undeclared.

## Auth and session model

WH-083 M1 implements identity and session transport. M1 covers `config`,
`session/login/*`, `session/reauth/*`, `GET session`, and `session/logout`.
WH-083 M3 covers `session/handoff/start`, `session/handoff/exchange`, the
browser's `POST /api/auth/identity/v1/handoff/complete`, and `/app/return`.
The push group is WH-086 M5.

WH-084 M2 adds `GET /bootstrap`, `GET /chat`, `GET /chat/turns/{turnId}`,
`POST /chat/messages`, `GET /reviews`, and `POST /reviews/confirm`. Each route
uses bearer authentication over the existing web path. The path uses
`admitWorkspace`, `workspaceBindings`, the existing handler, and
`assertWorkspace`. The historical M2 note classified the remaining entries as
planned. See the implementation notes for later milestones in Operations.

Schema batch 3d (WH-083) accepts `client:'android'` sessions with a `passkey`
or `handoff` proof. It also accepts Android challenges and
`identity_handoffs`. The M1 and M3 routes use them. `resolveIdentity` binds
a token to its client. Before this schema batch, the system accepted only
`client:'web'` sessions and WebAuthn challenges. WH-083 adds the `android`
client to the same identity tables and resolver. The historical source references
are [authority](../src/workspace-identity/authority.ts) and
[ceremonies](../src/workspace-identity/ceremonies.ts).

### Session issue and storage

The server issues a native session after a Credential Manager passkey
assertion for a `ready` account or a PKCE-bound handoff exchange only. The handoff
requires completed browser enrollment or recovery. The native app does not
receive an enrollment or recovery grant. If the account is `enrolling` or
`recovering`, `session/login/verify` returns `403 identity_incomplete` with
`next:"browser"` and issues no session.

The server generates a random 256-bit token and stores only its hash. It binds
the token to the account, authority epoch, policy version 1, `client:'android'`,
authenticating credential, and actual passkey verification time. The app
sends the token only in `Authorization`. Android Keystore protects the
app-private storage that holds the token. The app excludes the token from
backups, logs, URLs, model context, WebViews, browser cookies, and query strings.

### Passkey origin, lifetime, and freshness

The challenge records `client:'android'` and the server-configured package
signing identity (`android:apk-key-hash:...`). The RP ID remains `whumpo.com`.
The server requires user verification, the stored credential and counter, and
the exact bound identity. Debug identities cannot acquire release authority.
`whumpo.com` serves Digital Asset Links for the approved identity.

A session expires after seven days and has no refresh endpoint or silent
renewal. If a token expires or the server revokes it, the server returns
`401 proof_required` with `proof:"signin"`. The owner signs in again.
A challenge lasts five minutes and permits a single use. An invalid
verification consumes it.

A sensitive control requires a passkey that the server verified in the last
ten minutes. `session/reauth/*` does a new assertion and updates
`freshUntil`. It does not extend `expiresAt`. A handoff exchange and a
recovery-commit proof do not set freshness. If the server returns the
`fresh_passkey` form of `proof_required`, the app reauthenticates and uses the
same `requestId` for the retry.

### Gates

Logout, sign-out-everywhere, credential removal, ordinary kit rotation, and
recovery delete the session or increase the account authority epoch. They use
the same guarded statements as the
[identity contract](workspace-identity.md#authority-and-persistent-state).
Each request resolves current authority. Each write examines the epoch, stage,
and expiry again in the committing statement. If revocation occurs after
admission, the later commit fails.

If the app receives the first `401` for a revoked session, it clears local
private state. A retained retry waits for the owner to authenticate again.
The server withdraws push registration when it revokes the session.

Mobile routes require `stage:'ready'`. Otherwise, the server returns
`403 identity_incomplete`. The mobile routes that read or write Chat and
workspace data require completed welcome. Otherwise, the server returns
`409 onboarding_required`. `GET /bootstrap`, `GET /session`, and
`POST /session/logout` are exempt. Live backend acceptance requires separate
evidence.

### Handoff and return parameters (M3)

`session/handoff/start` records `purpose`, `state`, and an S256
`codeChallenge`. The only permitted return URI is the verified App Link
`https://whumpo.com/app/return`. The URI has no query, fragment, port, custom
scheme, or other host. The browser returns to the trusted identity flow at
`browserPath`. An account becomes `ready` with two verified passkeys, a saved
kit, and completed recovery if applicable. When the account is `ready`, the
browser issues a one-time code. The code lasts 60 seconds. The handoff lasts
five minutes.

The browser opens
`https://whumpo.com/app/return?code=<64 lowercase hex>&state=<the state as sent>`.
The query has only `code` and `state`, each once.
`POST /api/auth/identity/v1/handoff/complete` requires the exact Origin, an
ordinary cookie session, and two active passkeys. It also requires a web passkey
that the server verified in the last ten minutes. A recovery or enrollment
grant returns `identity_incomplete`. The route binds the handoff to the account
and source session. It returns `{version:1,returnUrl}`, and the page opens that
URL. The identity client refuses a redirect status.

`/app/return` is a static page with `no-store` and
`Referrer-Policy: no-referrer`. It does not echo the query. The verified App
Link sends only the one-time code and state to the app. It carries no session,
key, or provider credential.

If `session/handoff/exchange` fails, it returns `401 proof_required` with
`proof:"signin"`. An incorrect `state` or `codeVerifier` also consumes the
code. The exchange response has `freshUntil:null`.
`session/handoff/exchange` requires the
matching `handoffId`, `state`, and the verifier that the app holds. When the
server consumes the code, it examines the stage, epoch, and source browser
session again. The server issues a native session with the original proof time.
The native session expires no later than the source session.

Replay, incorrect state or verifier, incorrect redirect, expiry, source
revocation, or an epoch change prevents session issue. WH-083/WH-115 covers
authenticated browser completion. The web return route is outside
`/api/mobile`. The native shell can build against fixtures before that route
is available.

### Known handoff limits

With schema batch 4, each session has a stable `lineage_id`. A new sign-in
starts a new lineage. During web or native reauthentication, the server
replaces the row and copies its lineage to the new row. A native session from a
handoff records the web lineage in `source_lineage_id`. Web logout deletes all
native sessions with that source lineage and ends their push registration.
Reauthentication keeps this link. Web logout does not affect unrelated
sessions, including other native sessions of the same account.

A web session that expires or that the ten-session cap evicts does not go
through the logout batch. The native session then ends at its own expiry,
which is no later than the source expiry. Passkey removal, recovery, a
security-review commit, and a change to the epoch still revoke the session.

`session/handoff/start` writes a row that lasts 300 seconds in a 2,000-row
table. The route has a separate budget of three `AUTH_LIMITER` units per
minute on a `handoff:` key. Every anonymous mobile route uses the IPv6 /64
prefix for an IPv6 client.

The Worker platform's observability can sample request URLs.
`/app/return?code=...` carries a one-time code. The code requires the app
verifier and expires in 60 seconds. `/workspace/native/{token}` carries a
browser token. The token requires a fresh passkey and the app verifier.
The Worker adds no URL logging. Whumpo code does not log or echo handoff secrets.

### Browser operations

The app opens the system browser for passkey changes, recovery-kit handling,
lost-key recovery, signup, invitation, eligibility, and Turnstile. The app
starts the handoff and waits for the return.

Custom Tab previews use the browser session. The app does not send its bearer
token to the browser. If the browser is signed out, it shows the normal sign-in
flow and returns to the permitted path. The server supplies the path. The app
uses the path only if it matches the preview allowlist. The app supplies its
constant host.

## Operations

All paths are relative to `/api/mobile/v1`.
[operations.json](../tests/fixtures/mobile-v1/operations.json) gives one valid
request and response pair per row. "Idempotency" identifies the key that
makes a retry safe.

### Identity and session (WH-083)

| Operation | Auth | Request / response | Reuses | Idempotency |
| --- | --- | --- | --- | --- |
| `GET /config` | none | `minimumBuild`, `rpId:"whumpo.com"`, `browserReturn` | **new**: minimum-build and RP configuration | none |
| `POST /session/login/options` | none | `{version}` → `challengeId`, `expiresAt`, WebAuthn `options` | `loginOptions` | none (mints one challenge) |
| `POST /session/login/verify` | none | `{version,challengeId,credential}` → `session{token,expiresAt,freshUntil}` | `loginVerify` | challenge is single use |
| `POST /session/handoff/start` | none | `{version,purpose,state,codeChallenge,codeChallengeMethod:"S256",redirectUri}` → `handoffId`, `browserPath`, `expiresAt` | **new**: native handoff over the shared identity flow | none |
| `POST /session/handoff/exchange` | none | `{version,handoffId,code,state,codeVerifier}` → `session` | **new**: one-time code exchange | code is single use |
| `POST /session/reauth/options` | bearer | challenge for the current account | `reauthenticationOptions` | none |
| `POST /session/reauth/verify` | bearer | `{version,challengeId,credential}` → `session{expiresAt,freshUntil}` | `reauthenticationVerify` | challenge is single use |
| `GET /session` | bearer | `session{expiresAt,freshUntil}`, `account{label}` (masked address, never the raw email) | `resolveIdentity` | none |
| `POST /session/logout` | bearer | `{version}` → `{signedOut:true}`; the app clears local state even on `401` | identity `logout` | natural |

### Workspace, Chat and reviews (WH-084)

| Operation | Auth | Request / response | Reuses | Idempotency |
| --- | --- | --- | --- | --- |
| `GET /bootstrap` | bearer | `onboarding{complete,browserPath}`, `chat{revision,mode}`, `projectCount`, `pendingReviewCount`; the first call after sign-in | `onboardingComplete`, `readChat` | none |
| `GET /chat` | bearer | `?limit=1..50&cursor&q` → `revision`, `mode`, `items[turn]` oldest-first, `nextCursor` for older turns | `readChat`, `chatHistory` | none |
| `GET /chat/turns/{turnId}` | bearer | `{version,turn}`; the readback after a lost reply | `readSavedChatTurn` | none |
| `POST /chat/messages` | bearer | `{version,requestId,expectedRevision,content(1..1000)}` → `accepted`, `revision`, `turnStatus` | `sendConversation` | `requestId` is the turn ID |
| `GET /reviews` | bearer | up to five pending exact reviews with server-issued control metadata | `readWorkspaceReviews` | none |
| `POST /reviews/confirm` | bearer | `{version,requestId,expectedRevision,control}` → as `chat/messages` | `sendChatControl`, `chatControl` | `requestId` |
| `POST /interactions/list` | bearer | WH-105 payload | interaction `list` | none |
| `POST /interactions/read` | bearer | `{version,interactionId,client}`; `client` must equal the header capability | interaction `read` + `requireCurrentClient` | none |
| `POST /interactions/resources` | bearer | WH-105 payload | interaction `resources` | none |
| `POST /interactions/mutate` | bearer | WH-105 mutation: `save-draft`, `submit`, `cancel` | interaction `mutate` | `requestId` |
| `POST /interactions/receipt` | bearer | `{version,requestId}` readback | interaction `receipt` | none |
| `POST /interactions/continue` | bearer | `{version,submissionId,expectedChatRevision}` | interaction `continue` | `submissionId` |
| `POST /interactions/stop` | bearer | `{version,submissionId}` | interaction `stop` | `submissionId` |
| `POST /interactions/reconcile` | bearer | `{version,submissionId}`; resolves an unknown continuation | interaction `reconcile` | `submissionId` |
| `GET /projects` | bearer | `?limit&cursor` → `items[project]` | `project_service_status` read | none |
| `GET /projects/{projectId}` | bearer | `{project}`; foreign IDs are `404 not_found` | same + `readProjectDetails` | none |
| `GET /projects/{projectId}/versions` | bearer | `?limit<=25` → versions with allowlisted preview paths | `site_versions` read | none |
| `GET /activity` | bearer | `?limit&cursor` → owned operation records | `activity_events` read | none |

The interaction operations are thin authenticated aliases for the frozen [WH-105/WH-106 payloads](chat-interactions.md#wire-objects-and-digest) and return their existing error vocabulary wrapped in the mobile error envelope. The native admission set is bounded:

M4 implements projects, versions, activity, and the eight interaction aliases
behind the M2 workspace gate. These routes use the existing web statements
and the WH-105/106 handler. List cursors are offsets over the web limit of 100
rows. They are specific to the account and endpoint.

Interaction errors keep the WH-105 `code` and HTTP status in `{version,error}`.
The exceptions are `client_update_required` (426), `not_found`, and
`request_conflict`. They use the mobile codes. Interaction answers and
continuations have no recent-authentication requirement on the web or in the
client. If the continuation prepares a review, `reviews/confirm` still requires
a fresh passkey. If `expectedChatRevision` is stale on a content-brief
`continue`, the continuation becomes `failed` with reason `changed`, as on
the web. If the conversation changes on the model-continuation path, the
server returns `409 stale_revision`.

- **Field types:** `text`, `long-text`, `choice`, `multi-choice`, `boolean`, `number`, `date`, `datetime`, `resource`. `attachment` waits for WH-085.
- **Bindings:** `answer.value@1`, `interaction.answers@1`, `owned.projects@1`, `site.content@1`, `agent.task-continue@1`, `site.content-prepare@1`. Photo, display, view, connection, comparison and preview bindings are excluded.
- A definition needing anything else returns `426 client_update_required`. There is no simplified form, text projection or browser downgrade. `canApprove` is always false for generic forms.

The fixtures use the adopted form read and resource shapes. The
`interaction.read` operation returns
`{version,envelope:{version,id,expiresAt,definition,definitionDigest},state,answerRevision,draft,replacementInteractionId,submission}`.
The `interaction.resources` operation returns
`{version,interactionId,definitionDigest,fieldId,collection:"projects",items:[{id,name,photo:null}],nextBefore}`.
The app treats `nextBefore` as opaque and sends it unchanged as the next
`before`. The server does not accept the example `template:"example@1"` or
the placeholder digests. See [the forms snapshot](mobile-api-forms-proposal.md)
for client rules and synthetic cases.

The client implements typed parsing, the native form UI, and field errors for
interaction-route `422` responses. Live compatibility requires separate
evidence. Photo resources remain deferred. The authorized owner can read
submitted answers but cannot change them.

### Native form continuation lifecycle

The continuation object keeps the shape `{id,state,resultTurnId,reason}`. In a
submitted read, `submission.continuation` is non-null exactly when
`definition.continuationBinding` is non-null. If the continuation is null,
there is no follow-up and Continue stays disabled.

`continuation.id` equals the enclosing `submission.id`. An action response
must match that ID and the requested `submissionId`. A non-null `resultTurnId`
is a UUID. The reason contains only plain status text.

| State | Native controls |
| --- | --- |
| `not-started` | Explicit Continue sends the same submission ID with the currently observed Chat revision; it requires a current, unexpired, unreplaced form. Confirmed Stop may stop only this never-started work, including after expiry; the server rechecks ownership and eligibility. |
| `pending` | The next step is running. Explicit Check reads its current form/status or reconciles the existing submission. No Continue or Stop is offered. |
| `unknown` | Explicit Check reconciles the same submission ID. Never issue another Continue for an unknown outcome. |
| `succeeded` | Show the terminal result. A valid non-null result turn can be opened/refreshed in Chat; a null result remains terminal without navigation. Never rerun; prepared actions still require their ordinary review. |
| `failed` | Terminal, including `stopped`, `changed` and `unavailable` reasons. No retry-as-new authority. |
| Any future state | Read-only/update-required. No continuation mutation. |

If a continue, stop, or reconcile reply is lost, the app keeps the original
submission and its uncertain status. The app requires an explicit read or
reconciliation. It does not dispatch a request automatically or create a new
submission. If a fresh server read shows `not-started`, the app can enable a
later explicit action with the current Chat revision. A failed read, a read
that returns after a newer action, or a status-only UI update cannot clear
uncertainty. The server still checks current identity, ownership, expiry,
replacement, and feature eligibility.

### Photos (WH-085)

| Operation | Auth | Request / response | Reuses | Idempotency |
| --- | --- | --- | --- | --- |
| `GET /projects/{projectId}/photos` | bearer | `items[photo{id,name,width,height,size,status,safetyStatus}]` | `listPhotos` | none |
| `PUT /projects/{projectId}/photos/{uploadId}` | bearer | JPEG body ≤ 1 MiB, `X-Photo-Name` (URL-encoded, 1–100 characters) → `photo` | `uploadPhoto` | `uploadId`; same ID with different bytes, name or project is `409 request_conflict` |
| `GET /projects/{projectId}/photos/{uploadId}` | bearer | `photo`; the upload readback | `readPhotoUpload` | none |
| `GET /photos/{photoId}` | bearer | JPEG bytes for approved, owned media only | `readPhoto` | none |

The server implements the four routes behind the workspace gate. They use
the existing web `files.ts` path. The server applies scanning, JPEG validation,
metadata removal, the 1 MiB limit, and web quotas to client uploads. Native
uploads go directly to the server and do not use Chat handoff.

If readback returns `404 not_found`, the upload did not commit. The client
can send the same bytes again with the same ID. If storage succeeded before
confirmation, readback returns `status:"pending"`. Send the same upload
again to complete it.

| Condition | Error |
| --- | --- |
| Body larger than 1 MiB | `413 payload_too_large` |
| Media type other than `image/jpeg` | `415 unsupported_media_type` |
| Bytes outside the accepted baseline JPEG format | `400 invalid_request` |
| Same ID with other bytes, name, or project | `409 request_conflict` |
| Web quota or storage allowance | `503 unavailable` with the web's plain message |
| Held, blocked, or foreign media | `404 not_found` |

A successful byte response uses the same headers as the web route. The
[photo contract](photos.md) permits up to 10 MiB of input. It requires the
correct orientation, dimensions within 1600 x 1600, and baseline JPEG within
1 MiB. Keep the exact prepared bytes, name, and ID for a retry. If
`status:"ready"` occurs with `safetyStatus:"pending"` or `"blocked"`, the client
does not show the photo as usable. The same [safety gates](photos.md) apply.
Android does not receive a bucket credential.

### Push (WH-086)

| Operation | Auth | Request / response | Reuses | Idempotency |
| --- | --- | --- | --- | --- |
| `PUT /devices/current` | bearer | `{version,fcmRegistrationToken,notificationsEnabled}` → `deviceId`, `preferences` | **new**: owner/device registration | natural (one registration per session) |
| `DELETE /devices/current` | bearer | → `{removed:true}` | **new**: withdrawal | natural |
| `GET /events/{eventId}` | bearer | `event{kind,destination,state}`; `destination` is `null` and `state:"unavailable"` for a deleted, expired or foreign target | **new**: readback from committed receipts | none |

WH-086 implements `PUT /devices/current`, `DELETE /devices/current`, and
`GET /events/{eventId}` behind the M2/M4 workspace gate in schema batch 4.
The server keeps one `push_devices` row per native session lineage. Native
reauthentication keeps the lineage and device. The server stores the token
sealed under the connection vault with context
`whumpo:push:token:<account>:<lineage>`. It also stores a SHA-256 hash. Each
token has one owner. Until the owner sets `PUSH_NOTIFICATION_PROVIDER=fcm`,
registration returns `503 unavailable` and stores nothing.

`GET /events/{eventId}` returns `{event:{id,kind,destination,state}}` from
`push_events`. The event uses an opaque ID, a kind, and a project or review
ID. It carries no content. While the project exists and the event is within
its lifetime, `destination` is `{type:'review',id}` or `{type:'project',id}`.
The event lifetime is seven days or less. Otherwise, `destination` is
`null` with `state:"unavailable"`. The response is the same `404` for
another owner's ID and an unknown ID.

At this milestone, the Worker does not send notifications. The FCM HTTP v1
sender uses the same activation variable and has no dispatcher. Tests of
[`src/push-fcm.ts`](../src/push-fcm.ts) use a fake transport and a synthetic
key. The exact FCM data payload is
`{"v":"1","eventId":<uuid>,"kind":"review_pending"|"operation_completed"|"attention_required"}`.
It omits project names, message text, source, photos, and credentials. The
lock screen shows generic text.

A registration token gives a delivery address. It does not authenticate the
owner. The server binds it to the current session. Logout, revocation, and
account closure remove it. When the app receives or opens a notification,
it does only an authenticated read. It does not send, approve, or retry a
request.

## Native review controls

Four controls are admitted. Each is the **exact** `ChatControl` shape from [`src/chat-controls.ts`](../src/chat-controls.ts); the server revalidates it through `chatControl`, so a native client cannot reach any other control or a raw command string.

| Capability | Wire control | Fresh passkey | Journey |
| --- | --- | --- | --- |
| `specification.apply` | `{type:"specificationApply",reviewId,proposalDigest,expectedRevision}` | required | Save the exact private website version prepared from a content brief |
| `site.confirm` | `{type:"confirm",kind:"site",reviewId}` | no | Publish the reviewed website version |
| `application.confirm` | `{type:"confirm",kind:"application",reviewId}` | no | Activate the reviewed bounded application change |
| `app.restore` | `{type:"appRestore",projectId,reviewId,confirmation:"RESTORE"}` | required | Restore reviewed application data |

A review sent to the app is `{kind,reviewId,project,state,expiresAt,canApprove,control,requiresFreshPasskey,disclosure{title,lines},preview,unavailableReason}`. Invariants (checked offline): `canApprove` implies `state:"pending"`, a `control` whose type matches `kind` and `reviewId`, and no `unavailableReason`; otherwise `control` is `null`. A review whose capability the build did not declare arrives with `canApprove:false` and `unavailableReason:"client_update_required"`; an unknown `kind` makes the client show update-required with no enabled control. Expired, stale, foreign or withdrawn reviews expose no control. The [disclosure](../tests/fixtures/mobile-v1/reviews.json) lines are server-written exact-effect text; the app shows them before confirmation and does not synthesize them.

Preview `path` values are only `/api/site-versions/{id}/preview`, `/api/specification-reviews/{id}/preview|diff` and `/api/publication-reviews/{id}/diff`, opened in an authenticated Custom Tab. Any other path is rejected by the client.

Everything else stays web-only: provider-grant revocation, DNS/domain proof, GitHub backup, CMS, backend/application publication reviews beyond the above, version/photo deletion, unpublish and take-down shortcuts, and review-detail/shortcut controls. A native request naming them returns `400 unsupported_control`.

## Pagination, revisions and idempotency

- **Lists** use `limit` (1–50 default 25; versions 1–25) and an opaque `cursor`; responses carry `nextCursor` or `null`. Cursors are bound to the account and endpoint; a foreign or malformed cursor is `400 invalid_cursor`. Chat pages are oldest-first within a page, with `nextCursor` reaching older turns. Today's bounded sources (100 projects/activity, 25 versions) are paged by the adapter.
- **Chat revision** is the monotonic workspace revision. Writes send `expectedRevision`; a mismatch is `409 stale_revision` with `currentRevision` and the app refreshes before offering a new action. A pending prior reply is `409 chat_busy`.
- **Same `requestId`, same canonical payload** reads the committed result (no second effect). **Same ID, different payload** is `409 request_conflict`. A reply lost in flight is resolved with `GET /chat/turns/{requestId}` (`404 not_found` means nothing committed), `interactions/receipt` or the photo status route, never by regenerating an ID. The app does not auto-resend on reconnect, launch, notification or process restart; a retry is an explicit owner action that reuses the original payload.
- A write result reports acceptance, not downstream success. `turnStatus:"pending"` and `"uncertain"` are shown honestly and resolved by reads.

## Errors

Every error has the shape `{version:1,error:{code,message,...}}` and the fixed
status in the table. Plain error messages omit stack traces, SQL, provider
text, and internal identifiers. See the
[error fixtures](../tests/fixtures/mobile-v1/errors.json).

| Code | Status | Meaning and client action |
| --- | --- | --- |
| `client_update_required` | 426 | Unsupported contract, build, version, control, field type or binding. Body has `minimumBuild`/`contract` and `next:"update_app"`. No downgrade and no approval. |
| `proof_required` | 401 | Missing, expired, revoked or non-android session (`proof:"signin"`), or a stale passkey for a sensitive control (`proof:"fresh_passkey"`). |
| `identity_incomplete` | 403 | Account is enrolling or recovering. Open the browser; no native session exists. |
| `onboarding_required` | 409 | Welcome is not complete. Open the browser; reads and writes are refused. |
| `identity_changed` | 409 | Epoch or credential state changed under an action. Sign in and review again. |
| `invalid_request`, `unknown_field`, `missing_field`, `invalid_cursor`, `unsupported_control` | 400 | Closed validation failures. |
| `not_found` | 404 | Missing, expired or foreign resource; deliberately indistinguishable. |
| `request_conflict`, `stale_revision`, `chat_busy`, `review_unavailable` | 409 | Replay with changed payload, stale conversation, a pending reply, or a stale/expired review. |
| `payload_too_large` | 413 | Over the route bound. |
| `unsupported_media_type` | 415 | Not a prepared JPEG. |
| WH-105 field codes (open set) | 422 | Interaction-route field validation, passed through from the WH-105 vocabulary; it is outside the closed mobile codes, so `errors.json` has no case for it. The error adds `fieldId` when one field is at fault. Show it at that field, or at form level when `fieldId` is absent or unknown; never branch on the code. The synthetic example is `errors.field422` in the [forms catalog](../tests/fixtures/proposed/forms.json). Handled by the native form field/form-level error presentation. |
| `rate_limited` | 429 | Wait `retryAfterSeconds`. |
| `unavailable` | 503 | Platform or provider unavailable; authentication and help stay reachable. |

The web identity code `client_upgrade_required` (409) belongs to `/api/auth/identity/v1`; mobile uses only the codes above.

## Journey ownership and evidence

| Journey | Native | Browser | Authoritative contract | Failure and readback | Evidence required |
| --- | --- | --- | --- | --- | --- |
| Returning sign-in | Credential Manager passkey, token storage | none | `session/login/*` over `loginOptions/loginVerify` | wrong or replayed challenge: generic `proof_required`; `GET /session` | Physical device, approved signing identity, replay/expiry cases |
| New or recovering owner | Handoff start/return/exchange | Signup, two passkeys, saved kit, restricted recovery | WH-113 shared identity; `session/handoff/*` | Incomplete flow never exchanges; resumes in the browser | PKCE/state/redirect mismatch, replay, revocation during handoff, one-key and incomplete-recovery denial, process death |
| First workspace entry | Detect via `bootstrap` | Welcome/onboarding | `/api/onboarding`, `onboarding_required` | Refresh `bootstrap` after return | Gate holds on the gated routes (see Gates); two-owner isolation |
| Chat request and history | Compose, list, status | none | `chat/*` over `sendConversation`, `readChat`, `chatHistory` | Same-ID readback; honest pending/uncertain | Duplicate taps, lost reply, stale revision, inference unavailable |
| Answer a maintained form | Render the admitted subset | none | `interactions/*` | `receipt`, `reconcile`; unsupported definition is update-required | Field/binding admission, stale digest, unknown field |
| Review and approve | Disclosure, control, fresh-passkey reauth | Preview in Custom Tab | `reviews/*`, four controls | Stale/foreign/withdrawn reviews expose no control; same-ID replay | Per-control exact/stale/concurrent tests, no client-created approval |
| Projects, versions, activity | Lists and details | Website preview | `projects/*`, `activity` | `404` for foreign IDs | Two-owner isolation, pagination |
| Photos and sharing | Picker, preparation, explicit upload | none | `projects/{id}/photos/*` | Status route; same-ID byte conflict | Size/format/URI cases, held and blocked media, no automatic send or publish from a share |
| Push and preferences | Permission, token, display | none | `devices/current`, `events/{id}` | Open reads current state; unavailable destinations are safe | Token rotation, revocation, duplicate/delayed events, no private payload |
| Sign-out and revocation | Local purge, `session/logout` | Security, recovery, sign-out everywhere | WH-113 revocation semantics | `401` clears local state; retained retries wait | Revocation mid-request, account switch |
| Security, recovery, support, billing, providers, take-down | Open the browser | Everything | Existing web contracts | n/a | Remains reachable with Chat or inference down |

WH-048 remains a prerequisite for **Android customer admission** only. It does not depend on Android, and Android does not delay the web beta.

## Explicitly outside v1

Cookie or `Origin`-based mobile access; bearer access to non-mobile routes; a generic proxy or command strings; password, recovery-code or email-link sign-in; passkey add/remove, recovery-kit and recovery flows, signup and invitation; onboarding answers; provider connections, DNS/domains and billing/checkout; backups, exports, CMS/WordPress and the other publication reviews; unpublish, deletion and take-down shortcuts; custom/temporary views, interaction cards, dashboard layout writes and attachment fields until WH-085; full offline sync or cached workspace snapshots; iOS, customer-generated Android apps and public Play distribution (WH-088). Real-money payments stay disabled and CMS testing stays owner-deferred.

## Verification

In the private source repository, `node tests/mobile-v1-contract.mjs` compares
the registry, fixtures, and reference behavior with the backend source. Keep
that command and its prototype dependencies in the private repository. See
the [build notes](android-build.md) for public client checks. The integration
requires the private contract test separately. Runtime, device, provider, and
customer acceptance each require separate evidence.
