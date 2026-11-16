# Verify domain ownership on Android

From the ready organization screen in the connected Android `localDebug`
harness, an owner can open **Domain ownership**. A newly provisioned workspace
uses its assigned project. For a legacy workspace, choose a workspace and
project before viewing or creating claims.

Enter a hostname you already manage and select **Get TXT instructions**. Use
Punycode for an international name. Add the exact TXT name and value at your
DNS provider. Both values are selectable from the claim screen.

Select **Check TXT record** to request an ownership check. The screen shows
the current claim, including an ownership proof's validity window. When
the DNS evidence expires, check the TXT record again. When the challenge
expires, select **Renew TXT challenge** and replace the previous value at your
DNS provider. Website routing and certificates have a separate setup flow.

If a request is interrupted, **Retry original request** reuses the same body
and key while the originating session is active. If the server rejects an
initial hostname, correct the existing entry. After acknowledgement, a
version conflict, or a validation rejection following an uncertain write,
**Read saved claim** performs a GET. The client checks
the organization, project, claim identity and generation before showing
updated instructions. Denied reads clear the visible claim. A new session
loads current claims without replaying a previous session's request.

The [public v1 schema and examples](../tests/fixtures/site-domains-v1/) describe
the wire contract. From `android/`, run the focused tests before the
[full credential-free gate](android-verification.md).

```sh
./gradlew :shared:jvmTest --tests '*CoreSiteDomainTest'
./gradlew :shared:testAndroidHostTest --tests '*SiteDomainContentTest'
```

The client tests use synthetic HTTP responses. The connected Worker and DNS
resolver behavior have separate qualification.
