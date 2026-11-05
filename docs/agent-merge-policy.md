# Agent merge policy

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

The trusted-main merge controller is not implemented or installed by this
packet. Its separate, reviewed bootstrap is a technical limitation, not a new
owner-approval loop. Until bootstrap, the existing external hourly coordination
path is the fallback. If CI-event automation or hosted Actions is unavailable,
report the actual infrastructure status; do not retry an unchanged billing or
runner refusal, alter settings, or add a public-PR runner exception.

A future controller must:

- Run from trusted `main` and be the sole merge writer; public PR workflows keep
  `contents: read` and cannot merge.
- Only process same-repository, non-fork, non-draft code PRs. It must not apply
  special sensitive-path exclusions.
- Require the five named checks above, all attached to the current head, with
  the current base SHA recorded; require all review threads resolved.
- Read PR, head/base, checks, threads and protection evidence; immediately before
  merge, read them again and compare. Any head/base/status/thread race aborts the
  merge and schedules bounded reevaluation rather than acting on stale evidence.
- Use protections that require the checks, zero human approvals, an
  Actions-bound merge actor, no deletion and no non-fast-forward updates.
- Never use a bypass guard or native queued auto-merge. Preserve one writer per
  branch and cap retries at three; future Caps/Fusion orchestration must preserve
  those limits.

The first public CI/policy packet does not implement or claim this controller,
change branch protections, create settings selectors or schedulers, or change
the existing website integration ownership. A future source cutover requires a
separate review and a rollback plan that restores website ownership.
