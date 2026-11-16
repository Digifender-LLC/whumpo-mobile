# Offline conversational onboarding preview

The `localDebug` launcher offers **Explore setup preview** below its passkey
sign-in. The native Compose walkthrough covers plan selection, demo checkout,
demo account security, business questions, editable review, and a starter plan
in the preview workspace. The harness also offers **Account security and sign-in** for a separately
configured local service.

## Open and leave

Build and install the local debug APK as described in the
[Android build notes](android-build.md). Open Whumpo and choose
**Explore setup preview**, **Try onboarding**, then **Start**. Deployed builds
keep their existing sign-in and invite actions. The preview activity is
non-exported.

The journey works offline. **Exit preview** clears the answers and returns to
the harness. Android Back closes an open sheet, conflict or edit first. In
security, it then returns from the task to method choice, and from method choice
to checkout. At welcome or in the workspace, Back exits the walkthrough.
Activity recreation and process death clear the preview as well.

## What is simulated

The entry screen, demo checkout and credential sheets, and preview-workspace
header identify the simulated journey.
Each sheet supports confirm, cancellation, simulated failure and retry. No
payment method or credential is collected. The total is **$0.00 demo**; each
plan says **Plan price to be confirmed**. There are no actual price, quota or
seat promises.

Account security shows method choice alone before enrollment, then one unfinished
credential task at a time. Both choices provide the same preview workspace access:

- **Recommended: two passkeys** simulates separate first and distinct backup
  registrations, followed by a simulated recovery-kit save and a separate acknowledgement. A synced copy
  is not a second distinct credential. Either passkey suffices for ordinary
  sign-in; this preview cannot verify physical independence.
- **Use a password and authenticator instead** simulates password setup and
  authenticator-app TOTP verification, followed by a simulated save of ten
  one-time recovery codes and a separate acknowledgement. This path never requires a passkey.

Every setup step supports cancellation, failure and retry. Password and TOTP
steps have no fields for real secrets. Recovery panels contain only **NOT A REAL
RECOVERY KEY** or **NOT REAL RECOVERY CODES** placeholders: no usable-looking
secrets, QR codes or downloads exist. The save action closes the demo sheet and
shows a separate acknowledgement screen. Security remains incomplete until that
acknowledgement; neither action claims that a real recovery file was saved.

Review reopens method choice. Selecting the same method resumes its unfinished
task or completed summary, retaining progress. Selecting a different method discards all
previous security confirmations and requires the replacement setup in full,
while preserving organization answers and payment. Plan changes retain completed
account security. Review and Account show the selected method.

- Solo and Growth serve one person across devices. Business and Business Plus
  permit a team. Billing belongs to the organization; the account belongs
  to the person, who may later join multiple organizations.
- The guide asks one question at a time. It starts with the person's name,
  workspace name, business description, team intent and first priority. The
  description and priority can wait. Optional questions cover stage, location,
  customers and offers, registration and declared entity type, websites and
  domain access, help, and capacity. Choose **Not sure**, **Skip for now** or
  **Skip remaining details** to leave decisions open.
- The United States registration branch asks only for EIN status. Website and
  domain fields accept a public address and a bare hostname. The preview never
  opens the address. Review can reopen earlier answers. A changed country,
  registration, website or domain answer reopens the dependent questions.
- Team intent on a single-person plan requires an explicit team-plan change or
  **Keep just me**. Changing the plan invalidates the previous demo payment and
  requires confirmation again, while retaining answers and the person's demo
  security setup. Review edits obey the same rules.
- Creation requires complete valid answers, no open edit/conflict, the selected
  plan's demo payment and completed security setup, including recovery
  acknowledgement. Repeated or racing create actions produce only one in-memory
  organization.

Review presents starter actions with reasons. It lets the person edit titles
and select a next action whose prerequisites are clear. An existing site can
lead to a migration review before a domain connection. A US EIN guidance draft
can follow a registration review when the person plans to form an entity.
The preview creates local drafts for review. It performs no purchase, filing
or connection.

Today shows the chosen next action. Work shows the complete starter plan and
its prerequisites. Account shows the person, organization, plan, team intent and
security method. Reviews and the website start empty.

## Ownership and verification

`CoreOnboardingFlow` owns guarded immutable snapshots in shared Kotlin. The
Compose route remembers one flow, collects its state with the screen lifecycle,
and passes snapshots and callbacks to rendering. It resets on exit/disposal.
The preview never enters the authenticated session controller and calls no
network, inference, payment, credential or storage API.

Account entry and the preview use warm light and dark surfaces, native system
type, and the existing Material controls. The conversation shows the latest
answer and a single prompt. The composer scrolls and applies system bar and
keyboard insets.

From `android/`, run the focused state-machine tests and native build checks:

```sh
./gradlew :shared:jvmTest --tests 'com.whumpo.core.onboarding.*'
./gradlew :shared:jvmTest :shared:compileKotlinJvm :androidApp:assembleLocalDebug :androidApp:lintLocalDebug :androidApp:testLocalDebugUnitTest
```

The domain tests cover invalid ordering, cancellation/failure/retry, input
limits, retained answers, plan re-payment, team conflicts, review edits,
exactly-once creation and reset. Security cases cover both methods, partial setup,
unsaved and unacknowledged recovery, hidden-task actions, sheet/task/choice Back,
same-method resume, stale/repeated confirmations, method changes, retained payment
and answers, and plan changes after security setup. App unit tasks reporting
`NO-SOURCE` are not UI coverage. Check the full credential-free gate in
[Android verification](android-verification.md) before pushing.

For a manual emulator or device check, verify that choices disappear after selection
and only the unfinished task is shown. Back must dismiss a sheet before returning
to choices; reselecting the same method must resume its progress. Verify that
simulated recovery save does not advance until the separate acknowledgement.
Complete both security methods, cancel
and retry each setup step, switch methods from review and verify the old setup
is cleared, choose team on Solo and re-confirm a Business plan, edit answers
and plans from review, inspect all three workspace tabs, and exit/re-enter to
verify erasure. Repeat with the IME visible, compact dimensions, enlarged text,
light/dark themes and TalkBack. Compilation and domain tests alone do not
qualify those UI behaviors. Real payment, passkey enrollment, connected backend,
release signing and physical-device acceptance remain separate work.

## Connected account security in localDebug

Open **Explore setup preview**, then **Account security and sign-in**. The same
harness still offers **Try onboarding** for the offline business and checkout
preview above. The connected route has its own core origin, separate from the
legacy mobile API. By default it uses `http://127.0.0.1:5173` on the device. For
a compatible test service, configure the public endpoint and relying-party host
with Gradle properties.

```sh
./gradlew assembleLocalDebug -Pwhumpo.localCoreOrigin=https://core.example.test \
  -Pwhumpo.localCoreRp=core.example.test
```

For HTTPS, the endpoint host and configured RP must match exactly. An
explicitly configured RP can also be used with the existing debug-only
loopback transport. Passkeys require native Credential Manager registration and
assertion support, Digital Asset Links for the installed build's package and
signing certificate, and backend verification of the Android WebAuthn origin.
Use a configured test service for those checks. The default loopback build
leaves real passkeys disabled until an RP is supplied. Deployed builds retain
their existing account entry.

Enroll with a person invitation or an operator-issued bootstrap token entered at
runtime. The app previews an invitation with the service, shows the organization
and role, and submits consent only after acceptance. **Use a reserved invitation**
starts setup for an identity and membership assigned by the operator. Enter the
reserved token and choose a sign-in profile. Save the continuation key to a
private location with the Android document picker before starting setup. Keep
the key and the original invitation token until you finish. The key file contains
only the continuation key, and Android gives the chosen document provider access
to that file. After an interruption, open **Resume reserved setup** and enter
the original token and saved key to continue the same reservation with its
original sign-in profile. **Retry last step** checks an unconfirmed request in
the current session. **Pause reserved setup** returns to account entry and
leaves the reservation available for continuation while it is valid.
Membership and role come from the service after setup. An enrollment grant stages
the selected sign-in method. Passkey enrollment adds two distinct credential
records and a recovery kit. The password path stages a password and verifies an
authenticator code before preparing ten recovery codes. The app uses the
service's verified factor state to distinguish a prepared setup key from a
working authenticator.

Save recovery material with the Android document picker, then acknowledge the
successful write in the app. Choose a trusted private destination. The chosen
document provider receives the text of the kit or codes. The app holds the
material in memory through the write, masks typed secrets, and blocks
screenshots of this activity. Its account bearer, recovery material, pending
proofs and enrollment continuation stay in memory and clear when this
activity is destroyed. The service gives one-time material only to the
original prepare response. If that response is lost, retry to reconcile the
receipt, then prepare fresh material. If enrollment completion committed but
its acknowledgement was lost, use normal sign-in with the newly enrolled
method. The app has no enrollment continuation after activity destruction.
Contact the local operator if interrupted enrollment requires new admission.

The final credential proof moves enrollment to a regular account grant.
Sign-in also issues an account grant, using either a single existing passkey or
password plus TOTP. A signed-in member can also review and accept a person
invitation. The account keeps its existing sign-in method and returns to
organization selection after acceptance. The organization picker requires an explicit choice and
verifies the returned workspace session against the account and selected
organization before opening Today or Work.

**Recover an account** takes the locator and kit key or one unused code from a
saved recovery file. Review which sign-in methods to remove or retain, choose a
completion profile, and save and acknowledge the replacement recovery file.
**Secure account** applies the reviewed removals and activates the replacement
material. Finish any missing credentials and complete the independent final
proof before choosing an organization. A retained passkey can count toward the
two required passkeys, with at least one newly verified passkey required.
Password and TOTP recovery can reuse retained verified factors. The final
password and authenticator proof requires a fresh code.

**Retry last step** reuses the exact submitted request after an ambiguous
response. If the service secured the account and the previous grant has stopped
working, **Resume with replacement material** starts a restricted session using
the saved replacement file. The app reads the current review and checks its
attempt and revision against the service snapshot before offering completion.
If final proof was sent but completion remains unconfirmed, sign in with the
resulting credential to check access. Closing the activity clears the local
recovery session. The saved file provides the recovery entry point.

```sh
./gradlew :shared:jvmTest --tests 'com.whumpo.core.auth.CoreAccountSecurityControllerTest'
./gradlew :shared:jvmTest --tests 'com.whumpo.core.auth.CoreAccountRecoveryTest'
```

The synthetic tests cover both enrollment profiles, public invitation consent,
the verified TOTP distinction, replay after a lost response, restricted grant
boundaries, explicit scope handoff, ordinary login, RP mismatch and credential
prompt cancellation by epoch. Recovery and reservation fixtures cover the
restricted request shapes, retained-factor rules, redacted review readback,
exact retries after malformed or missing responses, and rejected late proof.
Provider calls, physical devices, Digital Asset Links, external document
storage and native accessibility require separate end-to-end evidence.

## Earlier security-choice integration evidence — November 15, 2026

The integrated client passed 423 shared JVM tests and 524 shared Android host tests, with zero failures/errors/skips. Both debug variants and their AndroidTest APKs assembled, both lint tasks passed, and both app unit tasks reported `NO-SOURCE`. Policy/source checks and whitespace checks passed. Native API 36 manual walkthroughs completed both security choices through organization creation, Account and exit, including cancellation, failure/retry and method switching.

The new security screens have not been qualified with TalkBack, API 28, physical devices or enlarged text. Automated instrumentation was not run for this increment. These are local preview results, not real authentication, recovery, billing or release acceptance.

## Focused security-step integration verification — November 15, 2026

The focused-step candidate passed all 15 onboarding state-machine tests, 424
shared JVM tests and 525 shared Android host tests, with zero failures, errors
or skips. `:androidApp:assembleLocalDebug` and `:androidApp:lintLocalDebug`
passed. `:androidApp:testLocalDebugUnitTest` reported `NO-SOURCE`. Host tests
verified the existing committed screenshot baselines; no baselines were updated.
The additional host command is `./gradlew :shared:testAndroidHostTest`.

A fresh disposable API 36 ARM64 emulator (1080 × 2400, density 420, font scale
1.0) completed both methods through review, creation, method-specific Account
summary and exit. Manual synthetic checks covered choice-only presentation,
one unfinished task at a time, system Back dismissing a sheet without leaving
its task, task Back to choice, choice Back to the retained checkout, same-method
resume, cancellation and simulated failure/retry, separate recovery save and
acknowledgement, and changing methods from review. The review text before and
after replacement security setup retained the same payment and organization
answers. Exiting and reopening returned to welcome. Captured choice, task,
recovery acknowledgement and Account screens were inspected locally.

This is bounded local preview evidence. No new instrumented test was added:
the existing full instrumentation inventory is shared by both flavors, while
this screen is local-debug-only. The full two-flavor/API instrumentation gate
was not run for this increment. TalkBack, API 28, enlarged text, physical devices,
real authentication/recovery/payment and signed-release acceptance remain
unqualified. There are no theme, typography, animation or production-auth changes.

Integration review verified the source and captured-evidence hashes, inspected the
choice, task and recovery-acknowledgement screenshots, and independently reran
all 424 JVM and 525 Android host tests against the integrated files. Local debug
assemble and lint passed again; the app unit task remained `NO-SOURCE`. This
review adds no new emulator or assistive-technology coverage to the scope above.
