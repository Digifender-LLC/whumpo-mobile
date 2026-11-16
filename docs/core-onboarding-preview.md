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
preview data. Android Back closes a sheet, conflict or edit first. Within security,
Back from a focused task returns to method choice; Back from method choice returns
to checkout. Dismissing a sheet does not also leave its task. Elsewhere Back goes to
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

Account security shows method choice alone before enrollment, then one unfinished
credential task at a time. Both choices provide the same preview workspace access:

- **Recommended: two passkeys** simulates separate first and distinct backup
  registrations, followed by a simulated recovery-kit save and a separate acknowledgement. A synced copy
  is not a second distinct credential. Either passkey suffices for ordinary
  sign-in; this preview cannot verify physical independence.
- **Use a password and authenticator instead** simulates password setup and
  authenticator-app TOTP verification, followed by a simulated one-time-recovery-code
  save and a separate acknowledgement. This path never requires a passkey.

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
