# Agent merge policy

This policy applies to public repository `Digifender-LLC/whumpo-mobile`
(GitHub repository ID `1446445212`).

## Authorized scope

The owner authorizes automatic merging of same-repository, non-fork, non-draft
code PRs that meet this policy. Reviews are advisory. Do not add an Autoapprover
requirement, a human approval quota, or a sensitive-path exclusion. Keep fork PRs
outside automatic merge. Do not bypass a required check. Keep repository
settings changes, app deployment, and app release outside this authority.

The exact required checks are:

1. `Mobile policy` (`CI` workflow, job `mobile-policy`)
2. `Secret scan` (`CI` workflow, job `secret-scan`)
3. `Assemble, lint and unit tests` (`Android` workflow, job `build`)
4. `Instrumented tests (API 28)` (`Android` workflow, `instrumented` matrix)
5. `Instrumented tests (API 36)` (`Android` workflow, `instrumented` matrix)

Use current results that show all five checks passed. Associate the results
with the workflow run `head_sha` and its PR head and base SHAs. A
`pull_request` workflow tests the synthetic merge commit that GitHub makes
from the head and base. Make sure that all review threads are resolved.
A superseded result, a workflow definition, or a missing status cannot show a
current pass.

## Trusted controller requirements

The trusted-main merge controller source is in `tools/merge-controller.mjs` and
`.github/workflows/merge-controller.yml`. The controller requires a separate reviewed bootstrap under the existing
authority. Until bootstrap, use the external hourly coordination path. If CI-event automation or hosted Actions is unavailable,
report the actual infrastructure status; do not retry an unchanged billing or
runner refusal, alter settings, or add a public-PR runner exception.

Controller bootstrap requires a saved ruleset with the five exact check names
bound as required checks to GitHub Actions app ID `15368`, and successful runs
for all five from that source. This technical condition uses existing authority. This change leaves ruleset
settings as they were.

The controller must:

- Run from trusted `main` and be the sole merge writer; public PR workflows keep
  `contents: read` and cannot merge.
- Only process same-repository, non-fork, non-draft code PRs. It must not apply
  special sensitive-path exclusions.
- Require the five named checks above, associated with each run's current
  `head_sha` and attached PR head/base; require all review threads resolved.
  Because `pull_request` jobs test the synthetic merge commit, compare the run
  metadata and current PR state rather than assuming the checkout is the raw
  head tree.
- Read PR, head/base, checks, threads and protection evidence; immediately before
  merge, read them again and compare. Any head/base/status/thread race aborts the
  merge. A later legitimate CI event or the existing hourly inventory can
  reevaluate; the controller does not add a scheduler or retry API writes.
- Use protections that require the five checks with GitHub Actions app ID
  `15368` as their required status-check source, zero human approvals, no
  deletion, no non-fast-forward updates and strict current-base checks. This source binding is not a merge
  actor identity.
- Never use a bypass guard or native queued auto-merge. Preserve one writer per
  branch and cap retries at three; future Caps/Fusion orchestration must preserve
  those limits.

## Execution and bootstrap

Completion of `CI`, `Android` or `Mobile review signal` can trigger automatic
reconciliation. The echo job signals review, review-comment, and PR readiness metadata events.
It uses no checkout, permissions, or credentials. CI results provide separate
evidence. Thread resolution alone has no documented Actions
trigger; the existing hourly stewardship handles that gap through explicit,
revision-bound dispatch below. The signal is not a required check or scheduler.
The workflow checks repository name, immutable ID and `refs/heads/main`, then
checks out the trusted event SHA with credential persistence disabled. The workflow does not install repository dependencies. It does not build PR
code or download artifacts.
The short-lived workflow token is available to pinned trusted-job actions,
including checkout, and to the controller process. `persist-credentials: false`
prevents checkout from persisting credentials in Git configuration; it does not
prevent job-level token access. The token has repository contents write for the
exact-head squash endpoint, with pull-requests/actions/checks read. Public PR
execution keeps read-only permissions.
If a workflow event comes from a fork, the controller ignores it before it
reads PR evidence.

Workflow-run `head_sha` and attached PR head/base are the evidence association;
normal PR checks test GitHub's synthetic merge, not a claim that the raw head
tree alone was executed. The latest run/attempt of each mapped workflow must have
exactly its expected successful jobs from Actions app `15368`. A newer failed or
pending run cannot be replaced with older green evidence. All five names must
remain bound in effective rules, with resolved conversations and zero approval
quotas. Additional unmapped required checks fail closed pending a reviewed mapping.

The controller reads the repository, PR, base, files, rules, threads, and CI
twice. If the evidence changes, the controller stops. Before it sends one
SHA-conditional squash request, it examines the head and base again. Strict server-side current-base protections close
the remaining API race window; the controller does not have a bypass path.
Missing API fields/permissions, pagination limits, unavailable CI or an unknown
merge response stop processing without a mutation retry. A successful merge stops
the event batch because the base has changed. A workflow run started from older
main refuses to act after main moves.

Default manual `workflow_dispatch` is read-only and accepts one PR number.
If its token cannot read repository merge settings, the controller stops.
Permission defaults do not supply this evidence. Controller/readiness jobs are not required
checks. No native auto-merge, approvals, settings mutations or new credentials
are used. Review descriptions remain advisory; unresolved threads block.

The existing hourly stewardship can select `mode=reconcile` explicitly.
Specify the observed `head_sha` and `base_sha`, one PR number, and `ref=main`.
That selects the same trusted-main reconcile job, not a separate merge writer;
missing, malformed or stale revisions fail closed, and every normal readiness
guard is still required. Dispatch requests use only an already-authorized native
capability; if it is unavailable, report the limitation rather than adding a
credential, scheduler, webhook or direct merge path. This is distinct from the
default read-only readiness job. Do not retry an unchanged hosted refusal.

After the reviewed workflow is installed, the dispatch interface is:

```sh
gh workflow run merge-controller.yml --repo Digifender-LLC/whumpo-mobile --ref main \
  -f mode=reconcile -f pull_number="$PR" -f head_sha="$HEAD" -f base_sha="$BASE"
```

Enter `node tests/merge-controller.mjs` for synthetic boundary, race, and API
tests. `Mobile policy` also includes these tests. The tests do not send a
real merge request. Source tests and a
ruleset readback do not establish installed workflow behavior. Before initial
bootstrap, independently verify current rules, all exact-revision checks and
resolved threads; install only the reviewed source from a passing PR. This
does not grant release/signing/deployment, provider or source-cutover authority.
Rollback is a reviewed revert of this controller while retaining the five CI
checks and the existing hourly inventory. Website integration ownership is
unchanged; any later cutover requires separate review and a rollback plan.
