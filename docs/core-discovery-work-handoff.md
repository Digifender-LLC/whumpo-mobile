# Review one discovery action for Work

In the connected Android `localDebug` harness, open business discovery from a
ready organization as its owner. Choose **Review … for Work** on one current
starter-plan proposal. A suggested next step or a discovery plan edit does not
request a Work item.

The review reads the current plan and handoff inventory. A newly provisioned
organization uses its workspace project. In an established organization, choose
a workspace and project explicitly. Required steps must have confirmed handoffs
in the chosen project and still match the current plan. Refresh the starter
plan from discovery if the goal changed. Then open a new review.

**Request one Work item** submits only the reviewed action and revisions. A
waiting handoff provides **Review exact approval** and the existing approval
details and decision controls. Queued handoffs stay queued until you check
their status. **Open verified Work item** appears after a confirmed handoff,
its immutable receipt and a fresh task read agree. This opens the Work task
directly.

The Work handoff list loads on reentry. Older context can appear as superseded,
and a replacement links to its previous handoff. Pending request bodies and
idempotency keys stay in memory for the active session. **Retry original
request** submits those exact bytes. After acknowledgement, **Check saved
handoff** reads current status. Changing organization or session cancels and
fences old requests. Denied reads clear previously displayed private
discovery and handoff details.

## Wire contract and verification

The [public v1 schema and synthetic examples](../tests/fixtures/discovery-work-handoff-v1/)
cover a versioned command, handoff detail and bounded inventory. The transport
uses the active organization session. Approval and receipt verification use
the existing Work effect routes.

From `android/`, run the focused tests before the
[full credential-free gate](android-verification.md):

```sh
./gradlew :shared:jvmTest --tests '*CoreDiscoveryHandoffTest'
./gradlew :shared:testAndroidHostTest --tests '*DiscoveryHandoffContentTest'
```

The focused tests cover closed decoding, exact write and decision replay,
stale reviews, prerequisite and project selection, denied reads, session
changes, and independent confirmation checks. Compose host tests cover
explicit selection, large-text review, and status and task-link controls.
The connected Worker journey and release checks have separate evidence.
