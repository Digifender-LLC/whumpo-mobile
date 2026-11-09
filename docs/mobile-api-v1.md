# Native `/api/mobile/v1` contract

**Imported contract snapshot, not a live-service availability statement.** The
[synthetic fixtures](../tests/fixtures/mobile-v1/) describe wire shapes and rules,
not runtime acceptance. Historical implementation notes and milestone identifiers
below refer to the source snapshot in [migration notes](migration.md). Backend
source links and private design documents are intentionally not included here.
Internal planning sections have been omitted; operation tables and wire shapes
are retained. No live FCM activation, device acceptance or production readiness
is established by this document.

Whumpo is current-version-only ([policy](current-version-policy.md)): one contract version, no old DTOs, no compatibility adapter, no browser downgrade. Anything outside this document is not a v1 operation. A change to a frozen shape requires a reviewed amendment here, fixtures, and a minimum-build bump.

## Principles

1. **A thin transport over existing domain handlers.** Each operation reuses the handler named in the [operation table](#operations); `new` marks the only genuinely new adapters. There is no proxy into `/api/*`, no raw Chat command string and no caller-supplied URL. Existing handlers keep ownership, quota, review and feature-admission checks.
2. **Native authority is a bearer session, nothing else.** A package name, user agent, `Whumpo-Client` header or absent `Origin` grants nothing. Cookie sessions never cross: a Whumpo cookie on a mobile route is refused, and a bearer is refused on every non-mobile route. The browser's `Origin` check on `/api/*` is unchanged.
3. **Server-issued typed metadata is the only source of approval.** The client enables a control only when the server sent it for a current review and the client declared the capability. Text, push arrival and model prose never create approval.
4. **Reads are cheap and authoritative; writes are keyed.** Every durable write carries a client-generated ID. A lost reply is resolved by reading, never by guessing or blind resend.
5. **Strict admission.** Missing or unsupported versions, fields, controls, field types or bindings return `client_update_required` or a closed validation error, never a reduced experience.

## Transport

- Base `https://whumpo.com/api/mobile/v1` (HTTPS only; `local` mode may use the documented loopback origin). Responses are `application/json; charset=utf-8` with `cache-control: no-store`, `x-content-type-options: nosniff` and `referrer-policy: no-referrer`, except photo bytes (`image/jpeg`, `private, no-store`).
- JSON requests are UTF-8, at most 16 KiB (interaction routes 32 KiB, matching [WH-105](chat-interactions.md)). The WebAuthn `credential` object inside `session/login/verify` and `session/reauth/verify` is further limited to 8 KiB of serialized JSON (`413 payload_too_large`); the Android client enforces the same bound. Unknown or missing keys are rejected. Photo upload is the only binary request: `image/jpeg`, at most 1 MiB.
- Rate limits reuse the platform limiters (per client address) plus a per-session limit; exceeding returns `429 rate_limited` with `retryAfterSeconds`.
- Every success body carries `"version": 1`. Dates are ISO-8601 UTC. IDs are lowercase UUIDs. Server cursors are opaque.

### Required headers

| Header | Value | Rule |
| --- | --- | --- |
| `Whumpo-Client` | Compact ASCII JSON, at most 4096 bytes: `{"contract":1,"build":<versionCode>,"platform":"android","controls":[...],"interaction":{"versions":[1],"fieldTypes":[...],"bindings":[...]}}` | Admission and capability declaration only. `contract` must equal `1`, `build` must be at least the server's `minimumBuild`, `interaction.versions` must be exactly `[1]`. Any other value is `426 client_update_required`; malformed or unknown keys are `400`. Present on every request, including anonymous ones. |
| `Authorization` | `Bearer <64 lowercase hex>` | Required on every non-anonymous route, refused on anonymous ones. |
| `Cookie` | none | Presence is `400 invalid_request`. |

`GET /config` is the only route a below-minimum build may still call to learn `minimumBuild`; a wrong `contract` is refused there too.

Declared `controls` are exactly the [admitted capabilities](#native-review-controls) the build implements. Declared `interaction` capabilities must be a subset of the native admission set (below) and must equal what the build renders. The server then issues `canApprove:true` only for a control whose capability is declared, and `interaction/read` rejects any definition needing something undeclared.

## Auth and session model

**Implemented for identity and session transport (WH-083 M1: `config`, `session/login/*`, `session/reauth/*`, `GET session`, `session/logout`; the handoff operations `session/handoff/start` and `session/handoff/exchange` are WH-083 M3, with the browser's `POST /api/auth/identity/v1/handoff/complete` and the `/app/return` page; the push group is WH-086 M5, below; every later group remains planned). WH-084 M2 adds the thin slice `GET /bootstrap`, `GET /chat`, `GET /chat/turns/{turnId}`, `POST /chat/messages`, `GET /reviews` and `POST /reviews/confirm`, each a bearer wrapper over the web path (`admitWorkspace`, `workspaceBindings`, the existing handler, `assertWorkspace`); the rest of the table stays planned.** Schema batch 3d (WH-083) admits `client:'android'` sessions (proof `passkey` or `handoff`), android challenges and `identity_handoffs`, and `resolveIdentity` binds a token to its own client, and the M1/M3 routes use them; before it only `client:'web'` sessions and WebAuthn challenges were accepted** ([authority](../src/workspace-identity/authority.ts), [ceremonies](../src/workspace-identity/ceremonies.ts)). WH-083 adds a distinct `android` client kind in the same identity tables and resolver, not a second authority model.

- **Issue.** A native session exists only after (a) a Credential Manager passkey assertion for an `ready` account, or (b) a PKCE-bound handoff exchange for an account whose browser enrollment/recovery completed. Native never receives an enrollment or recovery grant. `session/login/verify` for an `enrolling` or `recovering` account issues nothing and returns `403 identity_incomplete` with `next:"browser"`.
- **Token.** 256-bit random, stored only as a hash, bound to the account, authority epoch, policy version 1, `client:'android'`, authenticating credential and **actual** passkey verification time. It travels only in `Authorization`. It is held in Android Keystore-protected app-private storage, excluded from backups, logs, URLs, model context and WebViews, and never copied into a browser cookie or query string.
- **Origin binding for passkeys.** The challenge records `client:'android'` and the approved package signing identity (`android:apk-key-hash:...`) from server configuration; RP ID stays `whumpo.com`. Verification requires user verification, the stored credential and counter, and the exact bound identity. Debug identities never acquire release authority. Digital Asset Links for the approved identity are served by `whumpo.com`.
- **Lifetime.** Seven-day absolute expiry; no refresh endpoint and no silent renewal. An expired or revoked token is `401 proof_required` with `proof:"signin"`; the owner signs in again. Challenges last five minutes and are single-use; invalid verification consumes them.
- **Freshness.** Sensitive controls need a passkey verified within ten minutes. `session/reauth/*` runs a new assertion and moves `freshUntil`; it never extends `expiresAt`. A handoff exchange and the recovery-commit proof create **no** freshness. The `fresh_passkey` form of `proof_required` tells the app to reauthenticate and retry with the same `requestId`.
- **Revocation.** Logout, sign-out-everywhere, credential removal, ordinary kit rotation and recovery delete the session or bump the account authority epoch in the same guarded statements as the web contract ([identity](workspace-identity.md#authority-and-persistent-state)). Each request resolves current authority; each write repeats the epoch/stage/expiry predicate inside its committing statement, so a request admitted before revocation fails its later commit. A revoked app clears local private state on the first `401`, and a retained retry waits for the owner to authenticate again. Push registration is withdrawn server-side at the same moment.
- **Gates.** Mobile routes require `stage:'ready'` (`403 identity_incomplete` otherwise) and a completed welcome (`409 onboarding_required`) on the mobile workspace routes that read or write Chat and workspace data; `GET /bootstrap`, `GET /session` and `POST /session/logout` are exempt. These are contract requirements, not proof of live backend acceptance.
- **Handoff.** `session/handoff/start` records `purpose`, `state`, an S256 `codeChallenge` and the only allowed return URI `https://whumpo.com/app/return` (a verified App Link; no query, fragment, port, custom scheme or other host). The browser returns to the trusted identity flow at `browserPath`; only after the account is `ready` (two verified passkeys, saved kit, completed recovery where applicable) does the browser issue a one-time code (sixty seconds; the handoff itself five minutes). **Return parameters (M3).** The browser lands on `https://whumpo.com/app/return?code=<64 lowercase hex>&state=<the state as sent>`: exactly the query parameters `code` and `state`, once each, nothing else. `POST /api/auth/identity/v1/handoff/complete` (exact Origin, ordinary cookie session, a web passkey proven within ten minutes, two active passkeys; a recovery or enrollment grant is `identity_incomplete`) binds the handoff to the account and the source session and answers `{version:1,returnUrl}`, which the page opens; a redirect status would be refused by the identity client. `/app/return` is a static, `no-store`, `Referrer-Policy: no-referrer` page that never echoes the query. `session/handoff/exchange` denies every failure as `401 proof_required` (`proof:"signin"`); a wrong `state` or `codeVerifier` also burns the code, and the response carries `freshUntil:null`. The code is sent to the app only through the verified App Link, never a session, key or provider credential. `session/handoff/exchange` requires the matching `handoffId`, `state` and the app-held verifier, rechecks stage, epoch and the source browser session **at consumption**, and mints a native session that expires no later than that source session and carries its original proof time. Replay, wrong state or verifier, wrong redirect, expiry, source revocation or a changed epoch deny with no session. Authenticated browser completion is a **WH-083/WH-115** deliverable (a web-side return route outside `/api/mobile`); the native shell does not wait for it to build against fixtures.
- **Handoff limits (M3, known).** (1) **Source link (schema batch 4).** Every session carries a stable `lineage_id` (a new sign-in starts its own; web and native reauthentication delete and re-insert the row with a new id and copy it forward). A native session minted by handoff records the web session's lineage as `source_lineage_id`, and the web logout batch deletes every native session whose source lineage is the signing-out session's lineage, so web sign-out ends it and its push device. Web reauthentication and native reauthentication keep the link; unrelated sessions, including other native sessions of the same account, are untouched. A web session evicted by the ten-session cap or expiring on its own does not run the logout batch: the minted native session then ends at its own expiry, which is never later than the source's. Passkey removal, recovery, a security-review commit and an epoch change still revoke it. (2) `session/handoff/start` writes a row that lives 300 seconds in a 2000-row table, so it has its own budget: three units of the `AUTH_LIMITER` per minute on a `handoff:` key, and an IPv6 client is keyed by its /64 prefix for every anonymous mobile route. (3) The Worker's platform observability (sampled) may record request URLs; `/app/return?code=...` carries a one-time code that is useless without the app's verifier and expires in 60 seconds, and `/workspace/native/{token}` carries a browser token that is useless without a fresh passkey and the app's verifier. The Worker adds no URL logging of its own, and no handoff secret is ever logged or echoed by Whumpo code.
- **Credentials and recovery stay in the browser.** Adding or removing passkeys, recovery-kit handling, lost-key recovery, signup, invitation, eligibility and Turnstile open in the system browser; the app starts a handoff and waits for the return.
- **Custom Tab preview** uses the browser session. The app never injects its bearer into a browser. A signed-out browser shows normal sign-in and returns to the allowlisted path. Paths come from the server and must match the preview allowlist (below); the app composes the URL with its constant host.

## Operations

All paths are relative to `/api/mobile/v1`. Fixtures: [operations.json](../tests/fixtures/mobile-v1/operations.json) has one valid pair per row. "Idempotency" is the key that makes a retry safe.

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

**M4 (implemented).** Projects, versions, activity and the eight interaction aliases run the web statements and WH-105/106 handler unchanged behind the M2 workspace gate. List cursors are account- and endpoint-bound offsets over the web's 100-row bound. Interaction errors keep their WH-105 `code` and HTTP status inside `{version,error}` except `client_update_required` (426), `not_found` and `request_conflict`, which use the mobile codes. Interaction answers and continuations have no web recent-authentication requirement, so none is added: the review a continuation prepares still needs a fresh passkey at `reviews/confirm`. A stale `expectedChatRevision` on a content-brief `continue` settles the continuation as `failed`/`changed` (the web outcome); the model-continuation path maps a conversation change to `409 stale_revision`.

- **Field types:** `text`, `long-text`, `choice`, `multi-choice`, `boolean`, `number`, `date`, `datetime`, `resource`. `attachment` waits for WH-085.
- **Bindings:** `answer.value@1`, `interaction.answers@1`, `owned.projects@1`, `site.content@1`, `agent.task-continue@1`, `site.content-prepare@1`. Photo, display, view, connection, comparison and preview bindings are excluded.
- A definition needing anything else returns `426 client_update_required`. There is no simplified form, text projection or browser downgrade. `canApprove` is always false for generic forms.

### Photos (WH-085)

| Operation | Auth | Request / response | Reuses | Idempotency |
| --- | --- | --- | --- | --- |
| `GET /projects/{projectId}/photos` | bearer | `items[photo{id,name,width,height,size,status,safetyStatus}]` | `listPhotos` | none |
| `PUT /projects/{projectId}/photos/{uploadId}` | bearer | JPEG body ≤ 1 MiB, `X-Photo-Name` (URL-encoded, 1–100 characters) → `photo` | `uploadPhoto` | `uploadId`; same ID with different bytes, name or project is `409 request_conflict` |
| `GET /projects/{projectId}/photos/{uploadId}` | bearer | `photo`; the upload readback | `readPhotoUpload` | none |
| `GET /photos/{photoId}` | bearer | JPEG bytes for approved, owned media only | `readPhoto` | none |

**Server (implemented).** The four routes run the web `files.ts` path unchanged behind the workspace gate, so scanning, JPEG validation, metadata stripping, the 1 MiB bound and the web quotas still apply and the client is never trusted. A native upload is always a direct upload (no Chat handoff). Status `404 not_found` means nothing committed, so the same bytes may be resent with the same ID; a stored-but-unconfirmed upload reads back as `status:"pending"` and completes by resending. Errors: over 1 MiB `413 payload_too_large`, a non-`image/jpeg` type `415 unsupported_media_type`, bytes that are not an accepted baseline JPEG `400 invalid_request`, a reused ID with other bytes, name or project `409 request_conflict`, and a web quota or storage allowance `503 unavailable` with the web's plain message. Bytes for held, blocked or foreign media are `404 not_found`; success carries the web route's headers unchanged.

Preparation follows the [photo contract](photos.md): at most 10 MiB input, orientation-correct resize within 1600 x 1600, baseline JPEG within 1 MiB; the exact prepared bytes, name and ID persist for retry. `status:"ready"` with `safetyStatus:"pending"` or `"blocked"` is never rendered as usable media. The [safety gates](photos.md) are unchanged; Android receives no bucket credential.

### Push (WH-086)

| Operation | Auth | Request / response | Reuses | Idempotency |
| --- | --- | --- | --- | --- |
| `PUT /devices/current` | bearer | `{version,fcmRegistrationToken,notificationsEnabled}` → `deviceId`, `preferences` | **new**: owner/device registration | natural (one registration per session) |
| `DELETE /devices/current` | bearer | → `{removed:true}` | **new**: withdrawal | natural |
| `GET /events/{eventId}` | bearer | `event{kind,destination,state}`; `destination` is `null` and `state:"unavailable"` for a deleted, expired or foreign target | **new**: readback from committed receipts | none |

**Implemented (WH-086, schema batch 4).** `PUT /devices/current`, `DELETE /devices/current` and `GET /events/{eventId}` run behind the M2/M4 workspace gate. A device is one `push_devices` row per native session lineage (so a native reauthentication keeps it); the token is stored sealed under the connection vault (context `whumpo:push:token:<account>:<lineage>`) and by SHA-256, and a token has one owner. Until the owner sets `PUSH_NOTIFICATION_PROVIDER=fcm` registration answers `503 unavailable` and stores nothing. `GET /events/{eventId}` answers `{event:{id,kind,destination,state}}` from `push_events` (an opaque id, a kind and a project/review id; no content): `destination` is `{type:'review',id}` or `{type:'project',id}` while the project exists and the event is under its (at most seven day) lifetime, otherwise `null` with `state:"unavailable"`; another owner's id and an unknown id are the same `404`. Nothing in the Worker sends yet: the FCM HTTP v1 sender ([`src/push-fcm.ts`](../src/push-fcm.ts), tested with a fake transport and a synthetic key) is behind the same activation variable and has no dispatcher lane. The FCM data payload is exactly `{"v":"1","eventId":<uuid>,"kind":"review_pending"|"operation_completed"|"attention_required"}`. It carries no project name, message text, source, photo or credential; lock-screen copy is generic. A registration token is a delivery address, not authentication, and is bound to the current session; logout, revocation and account closure remove it. Receiving or opening a notification only triggers an authenticated read; it never sends, approves or retries anything.

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

Every error is `{version:1,error:{code,message,...}}` with the fixed status below; messages are plain and carry no stack, SQL, provider text or internal identifiers ([fixtures](../tests/fixtures/mobile-v1/errors.json)).

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

In the **private source repository only**, `node tests/mobile-v1-contract.mjs`
validates the registry, fixtures and reference semantics against backend sources.
That script and its prototype dependencies are not included in this client repo;
do not run that command here or treat public client tests as its replacement.
Client-side checks are documented in [build notes](android-build.md). Neither
those checks nor the private offline contract test establishes runtime, device,
provider or customer acceptance.
