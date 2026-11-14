# Core organization adapter

The shared Android and JVM module includes an opt-in transport and session controller for the Whumpo core contract. The adapter reads and rotates the current bearer session, lists memberships, switches organization, and reads organization profile and entitlements. Profile rename sends the caller's version and idempotency key. The adapter accepts additive response fields while requiring the fields it uses.

Contract provenance is the `backend.openapi.json` snapshot at SHA-256 `f6a092fd4f301dbeee297920fadae63815a9fef37a5627e4711f18a73c45340c`. The subset covers `/v1/auth/session`, `/v1/auth/session/rotate`, `/v1/auth/organizations`, `/v1/auth/session/switch`, `/v1/organization`, and `/v1/organization/entitlements`. The client declares `siteBuildAvailable` as a required entitlement field alongside `teamAdmissionAvailable`.

A platform integration creates a `CoreOrigin` from its explicit HTTPS allowlist and acquires a bearer through its secure sign-in flow. Debug integrations can opt in to an explicit IPv4 loopback origin. The adapter verifies the bearer before exposing an active session and validates the replacement identity, organization, and expiration on switch. A switch invalidates the old epoch before the request and installs the replacement token and verified session together. An ambiguous switch or rejected replacement requires fresh authentication. The HTTP client disables redirects and connection retries.

Keep organization data and jobs under the active state's `CoreAccountScope` and epoch. The scope hashes the configured environment origin, verified user ID, and organization ID into a separate storage namespace. Clear displayed organization data and cancel its jobs when the epoch changes. Before publishing any deferred result, use `commitIfCurrent` with the epoch captured when starting the operation. That callback must be short, synchronous, and limited to local state commits.

This slice has an in-memory integration boundary. Credential Manager and App Link association, sealed core session storage, Compose organization switching, and real Worker qualification belong to later integration. The existing app flavors retain their existing mobile contract.

Local verification on Temurin 21.0.13+9 with the Gradle 9.9.0 wrapper compiled the JVM and Android main targets. The JVM suite passed 380 tests, including six tests for the core adapter. The mobile policy negative fixtures passed. No APK, device or live-origin checks ran.
