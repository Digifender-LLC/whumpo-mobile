# Core organization adapter

The shared Android and JVM module includes an opt-in transport and session controller for the Whumpo core contract. The adapter reads and rotates the current bearer session, lists memberships, switches organization, and reads organization profile and entitlements. Profile rename sends the caller's version and idempotency key. The adapter accepts additive response fields while requiring the fields it uses.

Contract provenance is the `backend.openapi.json` snapshot at SHA-256 `f6a092fd4f301dbeee297920fadae63815a9fef37a5627e4711f18a73c45340c`. The subset covers `/v1/auth/session`, `/v1/auth/session/rotate`, `/v1/auth/organizations`, `/v1/auth/session/switch`, `/v1/organization`, and `/v1/organization/entitlements`. The client declares `siteBuildAvailable` as a required entitlement field alongside `teamAdmissionAvailable`.

A platform integration creates a `CoreOrigin` from its explicit HTTPS allowlist and acquires a bearer through its secure sign-in flow. Debug integrations can opt in to an explicit IPv4 loopback origin. The adapter verifies the bearer before exposing an active session and validates the replacement identity, organization, and expiration on switch. A switch invalidates the old epoch before the request and installs the replacement token and verified session together. An ambiguous switch or rejected replacement requires fresh authentication. Switches and rotations preserve the source session’s absolute expiry. Native bearer requests omit the browser Origin header. Native passkey origin verification remains part of sign-in. The HTTP client disables redirects and connection retries and reads bounded JSON plus one byte to detect oversized responses. The default bound is 64 KiB. Work list requests allow 64 KiB per requested task, capped at ten tasks.

Keep organization data and jobs under the active state's `CoreAccountScope` and epoch. The scope hashes the configured environment origin, verified user ID, and organization ID into a separate storage namespace. Clear displayed organization data and cancel its jobs when the epoch changes. Before publishing any deferred result, use `commitIfCurrent` with the epoch captured when starting the operation. That callback must be short, synchronous, and limited to local state commits.

This slice has an in-memory integration boundary. App Link association, sealed core session storage, production Compose integration, and real Worker qualification belong to later integration. The existing app flavors retain their existing mobile contract.

Local verification on Temurin 21.0.13+9 with the Gradle 9.9.0 wrapper compiled the JVM and Android main targets. The JVM suite passed 380 tests, including six tests for the core adapter. The mobile policy negative fixtures passed. No APK, device or live-origin checks ran.

The follow-up offline build compiled JVM and Android main and passed all eight focused adapter tests. The added tests cover rotation expiry mismatch and oversized responses. The switch test also verifies that native requests omit Origin.

## Android passkey preview

The `localDebug` APK includes a non-exported `CorePreviewActivity`. It leaves the default launcher and legacy mobile transport in place. The preview uses the core passkey options and verification routes and calls Android Credential Manager with the returned WebAuthn options. It resolves the resulting server-issued bearer against the session endpoint and holds it in memory while the Activity lives. The screen shows the selected organization and its membership coverage. Changing organizations invalidates the prior screen data and cancels its pending load.

A developer can launch the preview from the app's debug UID after installing the local debug APK and forwarding the local Worker:

```sh
adb reverse tcp:5173 tcp:5173
adb shell run-as com.whumpo.android.local am start -n com.whumpo.android.local/com.whumpo.android.core.CorePreviewActivity
```

The local Worker must be running at port 5173. Native sign-in requires a pre-enrolled test passkey, the relying party's Digital Asset Links association for the exact test app and certificate, and server verification of the signed app origin. The loopback API forwarding alone supplies no relying-party association. A failed platform or server check leaves the signed-out preview visible with a sign-in error. Registration, association provisioning, and real native passkey verification are outside this preview.

Local verification built the local and deployed debug APKs and passed 11 focused JVM tests. The local APK manifest marks the preview non-exported. The deployed debug APK omits it. No device was attached, so launch and passkey interaction on Android remain unverified.

## Work and website receipts

After passkey sign-in, choose **Open Work** in the local preview. Open a task
to see its current detail, then choose **View website receipts** for the task's
project. Work and receipts use paginated reads. The receipts screen shows
published and withdrawn versions, including the page count when the receipt
contains a multipage manifest. A project without a website target has its own
empty state. These screens leave the legacy app and existing release gate in
place.

The Work subset uses `GET /v1/work/tasks`, `GET /v1/tasks/{taskId}`,
`GET /v1/site-targets?projectId=…`, and `GET /v1/site-targets/{id}/history`
from the public contract snapshot with SHA-256
`01f90c2a89ce2709909ee5a548664f54446a1632de579b1aa03df33203677a6d`.
The adapter binds task detail to the selected task and project, and website
receipts to the selected project target and verified organization. The Work
screen drops open detail, cursors, errors, and in-flight results when the
organization session changes.

Owners can choose **Check current publication** on a task project's website
receipts screen in the local debug app. The check reads the current target,
verifies the latest confirmed static publication receipt against that target's
version and active effect, then rereads the target before showing the result.
It reports the confirmed version or the absence of a current publication. The
result describes publication state at the time of the check. Manual TXT
verification remains in its existing Domains screen.

In a ready owner workspace, **Website hostname** reads the selected project's
current attachment in the local debug app. Reserved, Eligible at last check,
Reproof required, and Detaching describe the recorded attachment state. The
screen presents the hostname as text.

Attach or resume reviews match a current website publication and a currently
verified domain. A review shows the hostname and website version. A disable
review binds the held attachment and remains available when a TXT proof or
publication is unavailable. Disabling closes local serving and keeps the
reservation until removal is confirmed.

After an uncertain response, **Retry original request** uses the same request
details in the original session. **Check current status** reads the latest
attachment. Confirmed responses, conflicts, and replacement sessions recover
through status reads. Project and session changes clear the screen's
in-memory review and pending write.

From **Work**, choose **New task**, a workspace, and a project. The preview reads
available choices from `GET /v1/workspaces` and `GET /v1/projects?workspaceId=…`,
then submits the title and optional description to `POST /v1/tasks`. A valid
acceptance shows whether the command is queued or awaiting approval. An
acceptance does not mean the task exists. **Check task status** reads
`GET /v1/effects/{effectId}` for the exact accepted effect and run in the
session that submitted it. The screen reports queued, awaiting approval,
failed or canceled. Only a confirmed effect names a task. The preview then
reads that task with `GET /v1/tasks/{taskId}` and shows the canonical task,
which may have moved to another project since creation. **Open task** reads
current detail by that exact task ID, so a later move within the organization
also remains readable. Website receipts use the displayed task's current
project. The adapter rejects
an effect or task with a different ID, run or task binding. A replayed
acceptance must name the same effect and run. Checking status never resends
the task request.

If acceptance is uncertain, **Retry same request** resends the frozen draft with
its original idempotency key. Leaving the form discards this in-memory retry
state. Check Work for the task before creating it again. A new draft uses a new
key. Changing organizations clears the form and prevents an old response from
updating the new organization's screen.

## Task outcome qualification

The task outcome increment passed 400 shared JVM tests and 501 Android host
tests with no failures or skips. Both debug variants and their instrumentation
APKs built, and both lint tasks passed. App unit tasks reported `NO-SOURCE`.
The local APK keeps the preview non-exported; the deployed APK omits it.

Synthetic tests cover all effect outcomes, exact effect/run/task bindings,
uncertain submission with the original key, replay identity, canonical task
moves, old-organization responses and delayed unauthorized responses. A late
acceptance cannot update a request handle after its session changes. The
existing response limits and session lifetime checks remain in effect.

These are local build and transport tests. No device interaction, instrumented
test execution, live Worker sign-in or signed-release qualification ran for
this increment.

## Today

After passkey sign-in, choose **Open Today** in the local preview. The screen
reads `GET /v1/briefing/today` with no query parameters and shows blocked
priorities, deadlines, pending decisions, verified task results and website
activity for the server's rolling window. **Refresh** reads the briefing again.
Truncated sections say that more items exist. An empty briefing, an empty
section and absent website activity each have their own label.

Source kinds stay distinct. A pending decision or a website change awaiting
review is labeled as pending and never as completed. Verified task results
are task-effect receipts; website receipts appear only under website activity.
The preview does not offer native approval decisions.

Selecting a blocked priority or deadline reads that exact task ID through
`GET /v1/tasks/{taskId}`, so a task that has since moved projects stays
readable. The adapter rejects a different task ID or a version older than the
briefing row. Selecting a website receipt reads the project's site target and
then its history at the receipt's exact version through the exclusive `before`
cursor with a limit of one. The history entry must match the target, effect,
operation, artifact digest and, for publications, build. A version the history
no longer lists has its own message. Reviews have no receipt to open.

The briefing response schema has no organization field. The adapter binds each
briefing to the session epoch and scope that read it, discards a response that
arrives after a switch or sign-out, and refuses navigation from a briefing of an
earlier session. A late unauthorized response from the old session does not
clear the replacement session. Validation covers the exact 24-hour UTC windows, section
limits and truncation, the `empty` flag, provenance, source kinds, identifiers,
versions, timestamps, website kind and operation pairing, review-only fields,
and duplicate rows within each section. Cross-section source reuse is allowed.
An empty truncated section remains visible as omitted items, not an all-clear.
The client reads at most 256 KiB of JSON plus one overflow byte for the compact
briefing rows; the 64 KiB default for other routes is unchanged. The server
proof-processing budget is separate from the response envelope.

`CoreTodayTest` covers these cases with synthetic MockEngine payloads. Integrated
verification passed 407 JVM tests and 508 Android host tests, with no failures
or skips. Both debug variants and instrumentation APKs built; both lint tasks
passed. App unit tasks reported `NO-SOURCE`. The local preview remains
non-exported and absent from the deployed variant. These are local transport
and build checks; no device interaction, instrumented test execution, live
Worker sign-in or signed-release qualification ran for this increment.
