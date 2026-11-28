---
name: se-project-engineering
description: Route non-trivial work in the software evolution platform to applicable roles, workflows, rules, and verification. Use for engineering, research, reviews, benchmarks, and governance changes; skip simple prose edits.
---

# SE Project Engineering

Use the bootstrap, routing table, authority and completion contract in [AGENTS.md](../../../AGENTS.md). Read the selected role/workflow/rules once per task; refresh changed context rather than loading every file.

## Make the task executable

Identify the requested outcome, current gate, approved decisions, affected contracts, non-goals, likely files, and observable exit criteria. Select a route from AGENTS.md without requiring the user to name it.

- Ordinary implementation: preserve approved boundaries and choose reversible details autonomously.
- Consequential uncertainty: define the question and collect only evidence that can change the decision.
- Review: inspect actual artifacts and remain read-only.
- Implementation self-review: fix supported defects inside the authorized scope and reverify.
- Documentation/configuration: validate links, structure, effective behavior or rendered output as relevant; do not invent production tests for prose.

For language semantics, Java/Spring extraction, ground truth, or semantic benchmark claims, read [semantic-evaluation.md](semantic-evaluation.md). It defines evidence checks, not a replacement for milestone contracts. M8.4 target builds/indexers require the validated isolated executor; platform verification commands below are not permission to run untrusted targets on the host.

## Repository acquisition & query evidence invariants

- **Uninitialized Git submodules vs. fail-closed acquisition (`SqliteInventoryStore`):** `InventoryEntry.Kind.GITLINK` entries with `Disposition.GAP` and `SUBMODULE_COMMIT_REQUIRED` must preserve typed `GAP` records while allowing inventory sealing (`compatible = false` / `compatibleV1Snapshot().isEmpty()`). `LFS_OBJECT_REQUIRED`, `Disposition.PENDING`, and all other `GAP` entries remain strictly fail-closed (`INVENTORY_FRONTIER_OPEN`).
- **Explicit evidence family whitelisting (`ArchitectureQueryService`):** Any new graph evidence family emitted for UI/API consumers (e.g., `polyglot.build-component`, `architecture.infra-component`, `architecture.external-actor`, `architecture.declarative-topology`, `architecture.service-call`, `polyglot.event-broker-link`, `polyglot.event-topic-match`) must be explicitly whitelisted in `ArchitectureQueryService` (safe label description and `select(...)` metadata projection) without relaxing untrusted redaction (`SAFE_FAMILIES` fail-closed `Map.of()` default).

## Verification selection

Separate verification cadence by track while keeping invariants `I1-I6` identical:
- **Track A (Product SWE — Online Web App & Backend):** follow `T0-T3` in [fast-verification](../fast-verification/SKILL.md) (narrow batched tests, Vanilla Web UI `node --check`/`node --test`, zero `cli` or `T4` benchmark overhead unless touched).
- **Track B (Publication Rigor — CORE A* / Q1 Research):** combine `T0-T3` with `T4` publication gates in [publication-research-rigor](../publication-research-rigor/SKILL.md).

Use [fast-verification](../fast-verification/SKILL.md), [README](../../../README.md), and the affected module POM/runner to choose exact commands. Root verification does not cover standalone benchmarks.

Windows PowerShell 5.1 examples (chain with `;`, never `&&`; quote `-D` flags; reuse default `target/`; check `git diff --name-only` before skipping `-am`; include `.` in `-pl` with both `'-Dtest.failIfNoTests=false'` and `'-Dtest.failIfNoSpecifiedTests=false'` on selective multi-module runs and verify total `Tests run: N > 0`):
- `.\mvnw.cmd --version`
- `.\mvnw.cmd -o -B -ntp -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dtest=ChangedTest,ApiSpecificationTest' test` when `0` upstream `analyzer*` files changed.
- `.\mvnw.cmd -o -B -ntp -pl .,analyzer,backend '-Dtest=ChangedTest,ContractTest' '-Dtest.failIfNoTests=false' '-Dtest.failIfNoSpecifiedTests=false' test` for selective multi-module behavior.
- `.\mvnw.cmd -B -ntp verify` for reactor integration; use `clean verify` for a clean-build claim.

On POSIX use `sh ./mvnw` with the same arguments. Inspect the actual runner before executing a standalone experiment: tests may regenerate evidence or require separately built artifacts/classpaths.

Use the [lean verification policy](../../../docs/testing.md#lean-verification-for-the-demo) to select only the required scope. Inspect the final diff and report what the named check established. Reuse it at handoff if the relevant inputs stayed unchanged.
