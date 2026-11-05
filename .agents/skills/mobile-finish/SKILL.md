---
name: mobile-finish
description: Finish Whumpo Mobile changes with exact local evidence and current required-check status.
---

# Finish Whumpo Mobile work

1. Read `AGENTS.md`, relevant feature docs, `docs/android-verification.md` and
   `docs/agent-merge-policy.md`. Review the complete base-to-head diff and keep
   implemented, locally verified, CI-verified, deployed and accepted separate.
2. Run the credential-free local gate in `docs/android-verification.md` before
   pushing. Record exact commands, exit statuses, test counts/`NO-SOURCE` and
   environment limits in `/home/ubuntu/.devin-files/mobile-ci-verification.md`.
3. Public branch names, commits, PR titles and discussion are neutral and
   project-focused. Do not add optional AI/Devin branding, generated-by/session
   footers, agent co-author trailers or session links. Preserve legally,
   technically and audit-required identity; never impersonate or rewrite history.
4. Require the exact five checks documented in `docs/agent-merge-policy.md`, on
   the current PR head and base with resolved review threads. Do not claim
   workflow definitions or stale statuses are passes.
5. This repository has no trusted-main merge controller in this packet. Its
   absence is a bootstrap limitation, not a new owner-approval rule. Do not
   substitute native queued auto-merge, change settings, bypass protections, or
   treat a hosted Actions billing/runner refusal as a source failure. Controller
   bootstrap and source cutover are separately reviewed work.
6. Do not deploy, release, sign, activate providers or claim device/customer
   acceptance from local or CI checks.
