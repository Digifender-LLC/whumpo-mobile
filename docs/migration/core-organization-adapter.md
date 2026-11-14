# Core organization adapter

The shared Android and JVM module includes an opt-in transport and session controller for the Whumpo core contract. The adapter reads and rotates the current bearer session, lists memberships, switches organization, and reads organization profile and entitlements. Profile rename sends the caller's version and idempotency key. The adapter accepts additive response fields while requiring the fields it uses.

Contract provenance is the `backend.openapi.json` snapshot at SHA-256 `f6a092fd4f301dbeee297920fadae63815a9fef37a5627e4711f18a73c45340c`. The subset covers `/v1/auth/session`, `/v1/auth/session/rotate`, `/v1/auth/organizations`, `/v1/auth/session/switch`, `/v1/organization`, and `/v1/organization/entitlements`. The client declares `siteBuildAvailable` as a required entitlement field alongside `teamAdmissionAvailable`.

A platform integration creates a `CoreOrigin` from its explicit HTTPS allowlist and acquires a bearer through its secure sign-in flow. Debug integrations can opt in to an explicit IPv4 loopback origin. The adapter verifies the bearer before exposing an active session and validates the replacement identity, organization, and expiration on switch. A switch invalidates the old epoch before the request and installs the replacement token and verified session together. An ambiguous switch or rejected replacement requires fresh authentication. Switches and rotations preserve the source session’s absolute expiry. Native bearer requests omit the browser Origin header. Native passkey origin verification remains part of sign-in. The HTTP client disables redirects and connection retries and reads at most 64 KiB of JSON plus one byte to detect oversized responses.

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
