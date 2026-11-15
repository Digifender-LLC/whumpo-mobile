# Offline conversational onboarding preview

The `localDebug` core harness includes **Try onboarding** while signed out. It
opens a native Compose walkthrough: welcome, plan, demo checkout, demo account security,
scripted conversation, editable review, and a personalized offline workspace.
The existing connected sign-in, organizations, Today, Work and recovery flows
remain available through the harness.

## Open and leave

Build and install the local debug APK using the toolchain in
[Android build notes](android-build.md). The existing harness is non-exported.
On a disposable userdebug emulator that supports `adb root`, select its serial
explicitly and launch the harness:

```sh
adb -s emulator-5580 root
adb -s emulator-5580 shell am start -n com.whumpo.android.local/com.whumpo.android.core.CorePreviewActivity
```

Use your emulator's actual serial. A regular non-root shell cannot launch this
non-exported Activity; `run-as ... am start` also fails on API 36 because the
shell package does not match the application UID. This command is for a
disposable emulator, not a physical-device setup step.

Choose **Try onboarding**, then **Start**. No backend or sign-in is needed for
this journey. **Exit preview** returns to the signed-out harness and clears all
preview data. Android Back closes a sheet, conflict or edit first, then goes to
the preceding step; Back at welcome or in the workspace exits. Activity
recreation and process death also discard the preview. There is no saved state
or persistence.

## What is simulated

The persistent **Onboarding preview** label and explicitly labelled demo bottom
sheets distinguish this walkthrough from real payment and credential operations.
Each sheet supports confirm, cancellation, simulated failure and retry. No
payment method or credential is collected. The total is **$0.00 demo**; each
plan says **Plan price to be confirmed**. There are no actual price, quota or
seat promises.

Account security offers two explicit choices before enrollment:

- **Recommended: two passkeys** simulates separate first and distinct backup
  registrations, followed by a saved-recovery-kit acknowledgement. A synced copy
  is not a second distinct credential. Either passkey suffices for ordinary
  sign-in; this preview cannot verify physical independence.
- **Use a password and authenticator instead** simulates password setup and
  authenticator-app TOTP verification, followed by a saved-one-time-recovery-code
  acknowledgement. This path never requires a passkey.

Every setup step supports cancellation, failure and retry. Password and TOTP
steps have no fields for real secrets. Recovery panels contain only **NOT A REAL
RECOVERY KEY** or **NOT REAL RECOVERY CODES** placeholders: no usable-looking
secrets, QR codes or downloads exist. Saving is a deliberate demo acknowledgement,
not a claim that a real recovery file was saved.

Review can reopen account security. Selecting a different method discards all
previous security confirmations and requires the replacement setup in full,
while preserving organization answers and payment. Plan changes retain completed
account security. Review and Account show the selected method.

- Solo and Growth serve one person across devices. Business and Business Plus
  permit a team. Billing belongs to the organization; the account belongs
  to the person, who may later join multiple organizations.
- The scripted conversation asks for owner name (1–60 trimmed characters),
  organization name (1–80), business description (1–280), just-me/team intent,
  and first priority (1–160). Optional business and priority suggestions remain
  editable. Invalid input stays in the field with an inline error; it is never
  silently truncated.
- Team intent on a single-person plan requires an explicit team-plan change or
  **Keep just me**. Changing the plan invalidates the previous demo payment and
  requires confirmation again, while retaining answers and the person's demo
  security setup. Review edits obey the same rules.
- Creation requires complete valid answers, no open edit/conflict, the selected
  plan's demo payment and completed security setup, including recovery
  acknowledgement. Repeated or racing create actions produce only one in-memory
  organization.

Today greets the entered owner and shows their first priority as a draft. Work
shows that local draft. Account shows the entered organization, description,
owner, plan, team intent and selected security method. Reviews and website start
empty. No members, history, invitations, published website or scheduled work
are invented. Today
contains no mascot artwork.

## Ownership and verification

`CoreOnboardingFlow` owns guarded immutable snapshots in shared Kotlin. The
Compose route remembers one flow, collects its state with the screen lifecycle,
and passes snapshots and callbacks to rendering. It resets on exit/disposal.
The preview never enters the authenticated session controller and calls no
network, inference, payment, credential or storage API.

The UI scopes neutral black/white surfaces and the existing gold token to the
preview, retaining existing typography, controls and semantic error colors. It
uses labelled controls and scrolling content. The conversation composer shares
its scrolling surface with the transcript and applies IME insets, including when text is enlarged.

From `android/`, run the focused state-machine tests and native build checks:

```sh
./gradlew :shared:jvmTest --tests 'com.whumpo.core.onboarding.CoreOnboardingPreviewTest'
./gradlew :shared:jvmTest :shared:compileKotlinJvm :androidApp:assembleLocalDebug :androidApp:lintLocalDebug :androidApp:testLocalDebugUnitTest
```

The domain tests cover invalid ordering, cancellation/failure/retry, input
limits, retained answers, plan re-payment, team conflicts, review edits,
exactly-once creation and reset. Security cases cover both methods, partial setup,
unsaved recovery, stale/repeated confirmations, method changes, retained payment
and answers, and plan changes after security setup. App unit tasks reporting
`NO-SOURCE` are not UI coverage. Check the full credential-free gate in
[Android verification](android-verification.md) before pushing.

For a manual emulator or device check, complete both security methods, cancel
and retry each setup step, switch methods from review and verify the old setup
is cleared, choose team on Solo and re-confirm a Business plan, edit answers
and plans from review, inspect all three workspace tabs, and exit/re-enter to
verify erasure. Repeat with the IME visible, compact dimensions, enlarged text,
light/dark themes and TalkBack. Compilation and domain tests alone do not
qualify those UI behaviors. Real payment, passkey enrollment, connected backend,
release signing and physical-device acceptance remain separate work.

## Local integration evidence — November 15, 2026

The integrated client passed 423 shared JVM tests and 524 shared Android host tests, with zero failures/errors/skips. Both debug variants and their AndroidTest APKs assembled, both lint tasks passed, and both app unit tasks reported `NO-SOURCE`. Policy/source checks and whitespace checks passed. Native API 36 manual walkthroughs completed both security choices through organization creation, Account and exit, including cancellation, failure/retry and method switching.

The new security screens have not been qualified with TalkBack, API 28, physical devices or enlarged text. Automated instrumentation was not run for this increment. These are local preview results, not real authentication, recovery, billing or release acceptance.
