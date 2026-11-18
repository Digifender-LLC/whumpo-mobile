# Building the client

Use JDK 21. Install Android SDK platform 37. Accept its terms.
Set `ANDROID_HOME` or set `sdk.dir` in an untracked `android/local.properties`.
The wrapper specifies Gradle 9.9.0. `android/gradle/libs.versions.toml`
specifies AGP 9.5.1,
Kotlin 2.4.21 and Compose Multiplatform 1.12.2. Do not upgrade them independently.

The public build uses Temurin 21.0.13+9, Android platform `android-37.0`, and
build-tools 36.0.0 and 37.0.0. CI always downloads command-line tools 19.0. It compares the checksum in
`android/scripts/ci-install-sdk.sh` before it replaces the selected tools tree.
Build mode omits the emulator package and system images. `bash android/scripts/test-ci-install-sdk.sh`
does a test of the installer with mock commands and without network access.
Jobs do not share Gradle dependency caches.

Run these commands from `android/`:

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJvm
./gradlew assembleLocalDebug assembleDeployedDebug assembleLocalDebugAndroidTest assembleDeployedDebugAndroidTest lintLocalDebug lintDeployedDebug :shared:testAndroidHostTest testLocalDebugUnitTest testDeployedDebugUnitTest
```

The Android workflow does the tasks from both commands in one build gate.
The workflow validates the Gradle wrapper first. If an app unit task reports
`NO-SOURCE`, record that result separately from test coverage. `localDebug` uses
`http://127.0.0.1:8787` and requires your own compatible backend; `deployedDebug`
targets `https://whumpo.com` and requires separately authorized service access.
Neither variant includes privileged provider credentials.

### Work document photo

In connected `localDebug` Work, open a task and select **Attach private file**. **Capture document photo** opens the device camera for one photo. **Choose file** remains available if the camera is unavailable or the capture is canceled.

A captured JPEG up to 4 MiB appears as **Document photo.jpg** with the task and file size. Check those details before choosing **Save and attach**. A changed task or session discards the result. Capture itself does not save or attach a Work file.

The client gives the camera app a temporary grant to one app-private output file. It removes this output after return or when leaving the attachment screen, and discards any interrupted output when the provider starts in a new app process. The existing file picker, review, and upload controls apply. The camera action does not scan text or crop the photo.

### Host and instrumentation tests

The shared module contains JVM and Android host tests. Roborazzi compares committed reference screenshots with synthetic data. Public
CI compares the images and does not record or update them. If you intend to
record the reference screenshots again, enter this command from `android/`.

```sh
./gradlew :shared:testAndroidHostTest -Pscreenshots.record=true --rerun-tasks
```

Examine the changed images before you commit. Do not record new images to hide
errors.

Instrumentation requires a disposable compatible emulator. For either supported
API, `bash android/scripts/ci-instrumented.sh 28` or
`bash android/scripts/ci-instrumented.sh 36` runs only
`connectedLocalDebugAndroidTest` and `connectedDeployedDebugAndroidTest`, excluding
`WorkerJourneyTest`. Both API levels use this same client-only suite. The inherited `android/scripts/test-worker-journey.sh` requires private
website files and dependencies. Keep that Worker test in the private website
repository. The integration requires that test separately from public client tests.

The Android workflow uses isolated `ubuntu-24.04` hosted runners, a disposable
x86_64 Google APIs emulator at API 28 or 36, no secrets and no provider calls.
It uploads synthetic JUnit/build/Roborazzi reports and the allowlisted ANR,
device-state and tree-diagnostic outputs for seven days. A separate one-day artifact
contains only the two local debug APKs and their build manifest. The size limit
is 64 MiB. See [APK evidence](android-verification.md). It does not upload raw
logcat or workspace/cache directories. `Mobile policy` covers shell regressions
and source-contract tests. `Secret scan` examines all commits and tags in Git history. Its report contains only rule,
commit, hashed path, and line metadata.

Required check names are `Mobile policy`, `Secret scan`,
`Assemble, lint and unit tests`, `Instrumented tests (API 28)` and
`Instrumented tests (API 36)`. Examine GitHub separately for check results and branch protection. See
[CI verification](android-verification.md) and the
[merge policy](agent-merge-policy.md).

Release configuration reads `WHUMPO_UPLOAD_STORE_FILE`, `WHUMPO_UPLOAD_STORE_PASSWORD`,
`WHUMPO_UPLOAD_KEY_ALIAS` and `WHUMPO_UPLOAD_KEY_PASSWORD` only from the environment.
Do not provide these to public contribution jobs. If signing configuration is incomplete, a release build uses the debug identity
and a `-debugsigned` suffix. Official releases require complete signing configuration. This repository contains no release publishing job.

### Synthetic Core launcher

The standard launcher still opens the legacy mobile shell. For local
development only, assemble `deployedDebug` with the exact reserved test origin:

```sh
./gradlew assembleDeployedDebug assembleDeployedDebugAndroidTest \
  -Pwhumpo.syntheticCoreEntryOrigin=https://core.example.test
```

The opt-in opens an existing-account Core entry through `MainActivity`.
It uses a separate HTTPS client without redirects. The device test supplies an
in-process transport for that origin. Use synthetic accounts only. The build
property has no effect on the local preview or on release builds.

The launcher asks the user to choose an organization after verified sign-in.
It reads readiness and a matching organization profile before showing the
organization name and role. Sign-out clears local account and workspace
authority before attempting revocation of the exact workspace session and
account grant. A failed request leaves the device signed out and reports that
service sign-out is unconfirmed.

The test exercises the existing password and authenticator flow. Passkey sign-in
requires the signed Android association and server-side native origin
qualification. A confirmed launch audience, production Core endpoint, and
release acceptance are still required.

### Primary owner controls

In the connected Core preview, open **Show primary owner details** on the Organization screen. The designation shows the stored user ID, version and source. Owners can review a claim when the organization is undesignated. The current primary owner can load eligible owners and review an offer for a specific recipient. The recipient can review acceptance, and the parties can review cancellation.

Confirming sends the reviewed action. If the designation or transfer changes, refresh the details and review the action again. When the service cannot confirm a request, **Retry exact request** sends the same request for the active workspace session. A new session clears the saved retry. Claim, offer and acceptance can require a recent verified sign-in. **Sign in again** opens the existing account sign-in flow for passkeys or password and authenticator code.

The shared JVM suite uses synthetic responses to verify request shape, closed response decoding, pagination, review invalidation, exact replay and session switching. Android host tests also exercise the claim review and retry controls at large text size. See [Android verification](android-verification.md) for the full client gate.

### Core task notifications

From **Today** or **Work** in a ready connected `localDebug` organization,
open **Task notifications**. The list uses the active workspace session.
Owners can open pending task approvals. A confirmed task result displays after
the app matches its verified receipt to the selected notification and workspace.
Opening a notification displays its checked details. Approval decisions take
place outside the inbox.

Use **Acknowledge notification** to mark a row as seen. If the response is lost,
the same acknowledgment can be tried again. The server returns the original
acknowledgment time. **Refresh notifications** starts over from the first page.
An unavailable or expired selection clears the result and offers a fresh read.
Leaving the inbox or changing sessions cancels in-flight work and clears local
inbox state. Returning after the app was backgrounded starts a fresh read.

This preview pulls task notifications on demand.

In a connected `localDebug` Work item with a current deadline, choose
**Remind me at the due time** to set a personal reminder. **Cancel reminder**
cancels the current schedule. If the result is uncertain, **Check reminder status**
reads the current schedule. **Retry scheduling reminder** repeats the original
deadline, revision, and request key in that Work session.

Open **Task notifications → Task reminders** from Today or Work to see
delivered reminders. **Open reminded task** checks the current task against the
reminder before showing details. **Mark reminder as read** is a separate action.
These reminders appear in the app and leave the task and its deadline unchanged.

### Core Partner workspace

From a connected `localDebug` Core organization, choose **Open Partner**.
**Active conversations** lists current conversations. **Conversation history**
includes closed conversations and their retained messages. Owners can choose an
enabled agent and create a temporary conversation. Members with write access can
save messages. Signed-out users can view an offline sample with synthetic data.

The organization needs to finish connected setup before its default project and
Partner are available. The connected Android harness offers workspace setup
and [optional business discovery](core-business-discovery.md). Its signed-out
onboarding sample uses local demo data.

**Save message** writes only the message. Partner confirms an acknowledgment
against the exact canonical message in history. The confirmation reads a separate
cursor even when the saved message is outside the 100 visible messages. After
an ambiguous write, **Retry exact save** repeats the original request. When the
conversation closes before acknowledgment, the save stays unresolved until the
conversation is reopened. An acknowledged save can still be checked against
closed history. Drafts and pending requests are held in memory for the active
session and clear on organization or account change or app restart.

From your saved message, **Request task run** lets you choose a project and review
one task title. The service can disable task execution by policy while the
message stays saved. A pending approval opens the exact task change for review.
Owners can approve or reject supported task creation, update, or transition
proposals. **Check task result** checks the matching effect, immutable receipt
and a fresh task read. A queued run, recorded decision or completed run alone
does not verify a task change.

The public synthetic JVM tests cover scoped pages, exact write retry, closed
history confirmation, an acknowledgment outside the visible window, disabled
run admission, approval and receipt binding, and cancellation across
conversation and organization changes. Compose host tests exercise the
conversation controls at 200% text size and with synthetic IME insets. Physical
devices and connected service acceptance require separate evidence.

### Connected workspace setup

After a connected Core sign-in, the app checks the selected organization's
readiness before it offers Today, Work, or Partner. Existing ready
organizations open directly. Members waiting for setup can refresh, switch
organizations, or sign out. An owner can answer the setup questions in the
conversation. Answers saved by the service appear again on the next sign-in.

The initial questions cover the organization label, owner name, first goal,
time zone, and briefing schedule. All answers are optional. **Skip this
question** leaves the field empty and advances the conversation for the
current session. **Skip remaining questions** saves the profile's skipped
status and keeps answers already saved. On returning to a draft profile,
the app asks about any remaining empty fields.

**Create workspace** submits the provisioning request. The app opens
the workspace after checking the current provisioning record and the
organization's readiness. If the response is uncertain,
**Retry original request** sends the same request in the current session.
**Refresh workspace status** can confirm provisioning through fresh reads.
Signing in again clears pending request bodies and reloads saved progress.

Connected setup uses the existing account sign-in methods. The offline
onboarding preview and its simulated checkout are separate from connected
workspace setup.
