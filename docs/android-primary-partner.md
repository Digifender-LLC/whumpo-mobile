# Primary Partner conversation access

A conversation returned by the active directory can have no managed-topic metadata. The Android controller now supports that routing-only conversation without inventing a topic, title, version or lifecycle state.

Missing metadata alone grants no access. The controller rechecks the bounded active directory for the same conversation, agent and kind, then reads authenticated canonical history before enabling the composer. A known managed topic cannot fall back after its metadata disappears. Metadata refusal, a missing or changed route, and history refusal do not enable chat. Actual message writes retain the current session, role and server-side authorization checks.

The same controller path supports saved messages and runs in a proven routing-only conversation. Closed managed conversations remain read-only. An ambiguous save retains its exact request bytes and idempotency key across later write rejections; a rejected write is not treated as proof that an earlier request did not commit. Read-access loss clears the scoped conversation state. Conversation changes and session epochs still cancel or refuse stale work.

Focused local verification covers 19 controller tests, including the original primary-history failure, exact retry reconciliation, viewer/read-denial cases and managed-topic behavior, plus four headless composer surface tests. This is not device, provider, release or service-interoperability acceptance. The full credential-free repository checks and current-revision hosted checks remain required before integration.
