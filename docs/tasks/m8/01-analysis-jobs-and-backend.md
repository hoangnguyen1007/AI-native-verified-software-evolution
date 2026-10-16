# M8.1 — Asynchronous analysis jobs and backend API

**Scope:** requested by the owner on 2026-10-16. Entry was clean at `cf630b6` (M7 completion); recorded G3/G4/G5/G6 preparation remain unchanged. No earlier M8.1 task definition existed. [Contract](../../architecture/m8-analysis-jobs.md) defines this backend slice and its explicit limits; [guide](../../../backend/README.md) gives the runnable workflow.

Implemented artifacts:

- `backend.analysis`: bounded job state/admission/retry/history, execution deadlines, cooperative cancellation, exact output provenance, safe local runner and standalone composition root.
- `backend.query`: seven additive job routes, explicit capability discovery, atomic bounded report batches, correct HTTP method headers and twelve-route OpenAPI.
- Shared passive source-selection/Maven helpers moved from CLI to backend; CLI imports and selection tests updated. Analyzer domain semantics/formulas/versions remain unchanged.
- Tests cover actual Java/Maven source analysis, policy/report/query parity, deterministic replay, partial dependencies, absent platform, invalid policy, acquisition failure before snapshot, worker linkage failure, queue/history limits, retry conflicts, cancellation races, storage/report publication, shutdown, strict JSON, actual HTTP security and a separate launcher JVM.

TDD evidence: initial backend preparation failed because the local analyzer artifact lacked M7.3 classes; rebuilding the trusted analyzer in an isolated output directory repaired the dependency mismatch. The new capability test then failed at its missing job-version assertion and passed after implementation. A provider-linkage control reproduced a stuck job and uncaught provider error; the repaired worker closes it as sanitized FAILED. A missing-platform assertion initially expected PARTIAL; inspection showed the canonical pipeline correctly emits TERMINAL_FAILURE while preserving its diagnostic graph. The test now checks FAILED, absent semantic analyses, the graph and original typed gaps rather than changing domain behavior.

Verification is bounded to the changed backend and direct CLI consumers, with Maven 3.9.16 / Oracle JDK 21.0.12.1. Only the single-module-inapplicable `reactorModuleConvergence` rule is skipped. `target/m8-work` isolates Maven output from IDE compilation. Inner Maven commands exceeded the five-second target (Maven waits initially yielded at ten seconds); the real-source replay method's Surefire time was 22.50 seconds. No sub-five-second command claim follows. Unit-only lifecycle classes run much faster; source/provider/launcher integration belongs in pre-handoff checks.

Final pre-handoff commands (PowerShell):

```powershell
.\mvnw.cmd test -pl backend "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m8-work" -q
.\mvnw.cmd install -pl backend "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m8-work" -q
.\mvnw.cmd test -pl cli "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m8-work" -q
.\mvnw.cmd package -pl cli "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m8-work" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/m8-work/evo.jar
git diff --check
```

| Check on final production inputs | Result |
|---|---|
| Complete backend module | **70 tests / 12 suites**, zero failures/errors/skips; exit 0; measured command 58.479 s |
| Complete direct CLI consumer module | **58 tests / 8 suites**, zero failures/errors/skips; exit 0; measured command 42.054 s |
| Trusted backend install and CLI package | Exit 0; tests skipped intentionally, not counted as test evidence |
| Actual shaded-JAR CLI smoke | PASS; **1,144 platform classes**, zero IDE error stubs; seven source/query/report journeys preserve explicit incomplete exit 3 and withheld assessment |
| Actual shaded-JAR backend smoke | PASS; separate JVM with only the distributable JAR, explicit temporary source/cache/store and JDK; accepted job, PARTIAL diagnostic receipt, two canonical contexts, persisted graph, independently recomputed receipt digests, graph discovery and same-key retry |
| Diff, document and new-file checks | `git diff --check` PASS; 21 added/new local Markdown links resolve; 14 new/moved source/test/document files checked for trailing whitespace |

Standard Surefire reports are under `backend/target/m8-work/surefire-reports` and `cli/target/m8-work/surefire-reports`. Total final bounded test evidence is **128 tests / 20 suites**. The packaged-backend smoke used a temporary fixture and printed a compact summary; no custom result dump or reproducibility package was written. Final documentation-only edits do not change these production/test inputs.

Module install/package commands with tests skipped refresh trusted local dependencies; they are not additional test evidence. No full reactor, external repository benchmark, independent review, commit or push was performed. Seven unrelated untracked `.agents/skills/` directories were observed during work and left untouched; this task's ownership is backend, the two moved CLI helpers/imports/test and the named M8/state/readme documents.

Routine implementation decisions: reuse the reversible JDK transport, existing passive providers and score model; register host inputs rather than accept arbitrary paths over HTTP; keep job metadata separate from deterministic semantic identities; preserve original acquisition problems before snapshot creation. No unresolved consequential decision or human approval blocks this slice. The new external wire contract remains PROVISIONAL pending workbench consumer acceptance. Durable updates: current state, roadmap's M8 sequence, root/backend guides, M8 task index and contract; historical gate evidence was not changed.

Known boundaries: process-local jobs/retry/history/M6 publication; persistent canonical graphs; cooperative in-process cancellation; operator-registered selections; existing passive-provider/graph/report/transport limits; no UI or remote deployment authentication. The original detailed acquisition problems remain typed even before a snapshot is possible. Accepted partial/failed results never become a passed semantic/product gate.

Exact next slice: M8.2 analysis setup/history and overview UI using these versioned adapters, with explicit artifact availability and degraded-state/evidence navigation.
