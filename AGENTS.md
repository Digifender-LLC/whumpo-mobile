# AGENTS.md

Guidance for contributors to this public Android client. Workflow definitions
and required-check targets are described in [Android verification](docs/android-verification.md);
they do not prove that GitHub settings enforce them.

## Project and toolchain

- Kotlin Multiplatform and Compose Multiplatform; Gradle project in `android/`.
- Use JDK 21 (CI: Temurin 21.0.13+9), Gradle 9.8.2, AGP 9.5.0,
  Kotlin 2.4.21, Compose Multiplatform 1.12.2 and Android platform 37.0.
- CI installs only required SDK packages, verifies command-line tools 19.0 by
  checksum, and does not share Gradle caches between jobs.
- See [build notes](docs/android-build.md), [verification](docs/android-verification.md)
  and [migration boundaries](docs/migration.md).

## Security and test boundaries

- Never add credentials, `.env`/`.dev.vars`, keystores, signing material,
  Firebase configuration, customer data, or real diagnostic captures.
- Public CI uses read-only `contents` permissions on isolated `ubuntu-24.04`
  hosted runners. Do not use repository secrets, persistent runners, provider
  calls, backend source, deploy/release jobs or `pull_request_target`.
- Keep tests local, synthetic and backend-free. The private website Worker
  journey is not part of this public repository or client CI.
- Mobile API contracts and fixtures are public snapshots; never access private
  backend source or history to run or explain public client tests.
- Client checks do not prove provider, live-service, signed-release, physical
  device, or customer acceptance. App unit tasks may be `NO-SOURCE`; report this
  accurately. Verify committed screenshot baselines; never record them in CI.
- Use the narrowest relevant checks, then the complete credential-free gate
  before pushing. Keep raw logs and temporary evidence outside the repository.

## Public collaboration

- Use neutral, project-focused future branch names, commit messages, PR titles,
  issues and comments. Do not add optional AI/Devin branding, generated-by or
  session footers, agent co-author trailers, or session links.
- Preserve actual authorship, authentication actors, audit/signature data,
  license and other legally or technically required identifiers. Never
  impersonate a person, rewrite history, or expose private coordination details.
- Preserve factual platform/actor attribution, including automated Dependabot
  messages; neutralize only optional prose.
- Do not include private backend/source/history/logs/artifacts in this repository.

## Merge policy boundary

Same-repository, non-fork, non-draft code PRs are authorized for automatic merge
once the documented exact-head checks and policy are satisfied. Reviews are
advisory; no human approval quota or sensitive-path exclusion applies. The trusted
main-branch merge controller is not implemented or installed in this packet.
Its separately reviewed bootstrap is an automation limitation, not a new owner
approval requirement. Do not use native queued auto-merge or bypass protection.
See [agent merge policy](docs/agent-merge-policy.md).

## Required checks

The five source-defined checks are `Mobile policy`, `Secret scan`,
`Assemble, lint and unit tests`, `Instrumented tests (API 28)` and
`Instrumented tests (API 36)`. Their source names are not evidence of remote
execution or branch-protection configuration.
