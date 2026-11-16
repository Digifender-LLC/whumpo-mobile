# Connected business discovery on Android

Open **Explore setup preview** in the `localDebug` build, then **Account
security and sign-in** against a compatible Core test service. An owner who
has reached the verified workspace setup review can choose **Add business
details**. **Continue later** returns to setup review. **Create workspace**
continues the required readiness flow. After setup, **Open business discovery**
on the organization screen opens review and **Continue questions** resumes
unanswered questions. Today, Work, and Partner are available once the workspace
is ready. Signed-out **Try onboarding** remains an offline demo.

The chat displays the service's applicable questions and saved answers.
**I’m not sure**, **Leave this for later**, and **Clear saved answer** distinguish
unknown, deferred, and unanswered. The country picker saves an alpha-2 code from
the finite supported list. The U.S. EIN question offers status choices only.
When an answer changes what applies, the app refreshes the server snapshot and
shows which answers to revisit. **Defer remaining questions** saves explicit
deferrals for the questions that currently apply. **Continue later** closes the
optional conversation without changing any answer.

Starter plan review shows server proposals, reasons, prerequisites, and any
selected next step. Owners can rename or reset a title, defer or restore a step,
and select or clear a next step. Only eligible steps can be selected. A stale
plan must be refreshed before editing. These controls edit the draft. Work
creation starts from a separate [Work handoff review](core-discovery-work-handoff.md).

## Wire and retry

The client reads `GET /v1/onboarding/discovery` and writes
`POST /v1/onboarding/discovery/commands` through the active organization's
verified owner session. It sends the exact reviewed revision in `If-Match`
and one `Idempotency-Key` per attempt. **Retry original request** preserves the
original body, version, and key after an ambiguous result. After an
acknowledgment, **Check saved discovery** reads the current snapshot without
resending the write. A conflict requires a fresh read before further edits.
Pending requests and answer inputs are held in memory for the active session.
Changing session cancels and fences old requests. On cold reentry, the app
loads current answers from the service.

The [public contract fixtures](../tests/fixtures/business-discovery-v1/) provide
the Draft 2020-12 schema, canonical countries, and synthetic examples. The
original wire files have these SHA-256 hashes.

| File | SHA-256 |
| --- | --- |
| `business-discovery.schema.json` | `57643bf48993b01532a8b919d05334d179cb7e7f16a94c0b5a6c65a8b169e446` |
| `business-discovery-examples.json` | `73cb6f63062a182b9e1c7b2a6a5427582bf544a75f2e273e678e3bfe3329aaf1` |
| `business-discovery-country-codes.json` | `83f0e5bb08e4e9bf5e04600a42fa088a86c5af52414ffe7754a6520e4803b12f` |

## Verification

From `android/`, use synthetic data for the client checks.

```sh
./gradlew :shared:jvmTest --tests '*CoreBusinessDiscoveryConnectionTest'
./gradlew :shared:testAndroidHostTest --tests '*BusinessDiscoveryContentTest'
```

The JVM tests cover typed command fixtures, closed response decoding, organization
scope, exact retries, stale plans, and cold reads. Android host tests cover
compact layout at 200% text, keyboard action and IME inset geometry, light and
dark entry themes, and explicit plan review. Follow the
[full Android gate](android-verification.md) before pushing. A connected Worker
journey and physical-device acceptance have separate checks.
