# AGENTS.md

These instructions apply to contributors to this public Android client. See
[Android verification](docs/android-verification.md) for workflow definitions and
required checks. Examine GitHub settings separately for enforcement.

## Project and toolchain

- The Gradle project in `android/` uses Kotlin Multiplatform and Compose Multiplatform.
- Use JDK 21 (CI: Temurin 21.0.13+9), Gradle 9.9.0, AGP 9.5.1,
  Kotlin 2.4.21, Compose Multiplatform 1.12.2 and Android platform 37.0.
- CI installs only the necessary SDK packages. CI compares the checksum for
  command-line tools 19.0. Jobs do not share Gradle caches.
- See [build notes](docs/android-build.md), [verification](docs/android-verification.md)
  and [migration boundaries](docs/migration.md).

## Documentation style

Use [ASD-STE100 Simplified Technical English, Issue 9](https://www.asd-ste100.org/assets/files/ASD-STE100_ISSUE9.pdf) when you write or revise technical documentation. If the `$simple-english` skill is available, use its strict mode. If the skill is unavailable, use the official rules and dictionary.

Use active voice and consistent terms. Put each condition before its command. Give one instruction per sentence. Use at most 20 words per procedural sentence and 25 words per descriptive sentence.

Keep technical meaning, commands, identifiers, and quotations exact. Preserve licenses, upstream material, and archived references. If a document has a generator, edit its source and generate the document again.

## Security and test boundaries

- Never add credentials, `.env`/`.dev.vars`, keystores, signing material,
  Firebase configuration, customer data, or real diagnostic captures.
- Public PR CI uses read-only `contents` permissions on isolated `ubuntu-24.04`
  hosted runners. Only the trusted-main controller has scoped merge permission. Keep PR code and
  artifacts out of that controller. Do not use repository secrets, persistent runners, provider
  calls, backend source, deploy/release jobs or `pull_request_target`.
- Use local tests with synthetic data. Keep the private website Worker test apart
  from this public repository and client CI.
- Mobile API contracts and fixtures are public snapshots. Keep private backend
  source and history outside public client tests and their documentation.
- Provider, live-service, signed-release, physical-device, and customer acceptance
  require separate evidence. If app unit tasks report `NO-SOURCE`, record that
  result. Compare the committed reference screenshots. Do not record them in CI.
- Start with the smallest set of checks for the change. Before you push,
  complete the full credential-free gate. Keep raw logs and temporary evidence outside the repository.

## Public collaboration

- Use neutral, project-focused future branch names, commit messages, PR titles,
  issues and comments. Do not add optional AI/Devin branding, generated-by or
  session footers, agent co-author trailers, or session links. If tooling appends
  optional session/Desktop/Requested-by text, remove only that text with a normal
  body edit after creation. Keep substantive evidence and authenticated authorship.
- Preserve actual authorship, authentication actors, audit/signature data,
  license and other legally or technically required identifiers. Never
  impersonate a person, rewrite history, or expose private coordination details.
- Preserve factual platform/actor attribution, including automated Dependabot
  messages. Use neutral wording only for optional prose.
- Do not include private backend/source/history/logs/artifacts in this repository.

## Merge policy boundary

The owner authorizes automatic merge for same-repository, non-fork, non-draft
code PRs that meet the documented merge policy and current-revision checks.
Reviews are advisory. The policy has no human approval quota or sensitive-path
exclusion. Before the controller can operate on trusted main, review and merge
its implementation separately. Make sure that CI passes for the exact revision
and that the required protections are active. The controller bootstrap is a
technical condition for automation under the existing authority. Do not use native
queued auto-merge or bypass protection.
See [agent merge policy](docs/agent-merge-policy.md).

## Required checks

The five source-defined checks are `Mobile policy`, `Secret scan`,
`Assemble, lint and unit tests`, `Instrumented tests (API 28)` and
`Instrumented tests (API 36)`. Examine GitHub separately for check results
and branch protection.
