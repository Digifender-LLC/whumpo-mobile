# Agent merge policy

This policy applies to public repository `Digifender-LLC/whumpo-mobile`
(GitHub repository ID `1446445212`).

## Authorized scope

The owner authorizes automatic merging of same-repository, non-fork, non-draft
code PRs when the required policy is satisfied. Reviews are advisory; do not add
an Autoapprover requirement, human approval quota or sensitive-path exclusion.
This authority does not permit merging a fork PR, bypassing a required check,
changing repository settings in a code packet, or deploying/releasing the app.

The exact required checks are:

1. `Mobile policy` (`CI` workflow, job `mobile-policy`)
2. `Secret scan` (`CI` workflow, job `secret-scan`)
3. `Assemble, lint and unit tests` (`Android` workflow, job `build`)
4. `Instrumented tests (API 28)` (`Android` workflow, `instrumented` matrix)
5. `Instrumented tests (API 36)` (`Android` workflow, `instrumented` matrix)

All five must have successful, current evidence for the PR's exact head SHA and
current base SHA. Confirm every review thread is resolved. Do not accept a
success from a superseded commit, infer success from a workflow definition, or
turn a missing status into a pass.

## Trusted controller requirements

The trusted-main merge controller source is in `tools/merge-controller.mjs` and
`.github/workflows/merge-controller.yml`. Its separate, reviewed bootstrap is a
technical limitation, not a new owner-approval loop. Until bootstrap, external hourly coordination
path is the fallback. If CI-event automation or hosted Actions is unavailable,
report the actual infrastructure status; do not retry an unchanged billing or
runner refusal, alter settings, or add a public-PR runner exception.

Bootstrap is blocked until a repository ruleset is saved with the five exact
check names bound as required checks to GitHub Actions app ID `15368`, and all
five have successful runs from that source. This is a technical readiness
condition, not a new owner-permission loop; this packet does not edit ruleset
settings.

The controller must:

- Run from trusted `main` and be the sole merge writer; public PR workflows keep
  `contents: read` and cannot merge.
- Only process same-repository, non-fork, non-draft code PRs. It must not apply
  special sensitive-path exclusions.
- Require the five named checks above, all attached to the current head, with
  the current base SHA recorded; require all review threads resolved.
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
reconciliation. The signal is a permission-free, no-checkout echo job on review,
thread-resolution and PR readiness metadata events. It carries no credentials,
does not count as CI evidence and prevents a resolved thread being stranded after
CI already completed. It is not a required check or a new scheduler.
The workflow checks repository name, immutable ID and `refs/heads/main`, then
checks out the trusted event SHA with credential persistence disabled. It installs
no repository dependencies, builds no PR code and downloads no artifacts.
The short-lived workflow token is exposed only to the trusted controller process;
it has repository contents write for the exact-head squash endpoint, with
pull-requests/actions/checks read. Public PR execution keeps read-only permissions.
Fork workflow events are ignored before reading their PR evidence.

Workflow-run `head_sha` and attached PR head/base are the evidence association;
normal PR checks test GitHub's synthetic merge, not a claim that the raw head
tree alone was executed. The latest run/attempt of each mapped workflow must have
exactly its expected successful jobs from Actions app `15368`. A newer failed or
pending run cannot be replaced with older green evidence. All five names must
remain bound in effective rules, with resolved conversations and zero approval
quotas. Additional unmapped required checks fail closed pending a reviewed mapping.

Two complete reads cover repository, PR, base, files, rules, threads and CI.
Changed evidence aborts, followed by a final head/base recheck before a single
SHA-conditional squash request. Strict server-side current-base protections close
the remaining API race window; the controller does not have a bypass path.
Missing API fields/permissions, pagination limits, unavailable CI or an unknown
merge response stop processing without a mutation retry. A successful merge stops
the event batch because the base has changed. A workflow run started from older
main refuses to act after main moves.

Manual `workflow_dispatch` is read-only and accepts one PR number. Its token may
not expose repository merge settings; missing evidence is a blocker, never
inferred from permission defaults. Controller/readiness jobs are not required
checks. No native auto-merge, approvals, settings mutations or new credentials
are used. Review descriptions remain advisory; unresolved threads block.

Run `node tests/merge-controller.mjs` for synthetic boundary/race/API tests; it
also runs in `Mobile policy`. Tests never issue a real merge. Source tests and a
ruleset readback do not establish installed workflow behavior. Before initial
bootstrap, independently verify current rules, all exact-revision checks and
resolved threads; install only the reviewed source from a passing PR. This
does not grant release/signing/deployment, provider or source-cutover authority.
Rollback is a reviewed revert of this controller while retaining the five CI
checks and the existing hourly inventory. Website integration ownership is
unchanged; any later cutover requires separate review and a rollback plan.
