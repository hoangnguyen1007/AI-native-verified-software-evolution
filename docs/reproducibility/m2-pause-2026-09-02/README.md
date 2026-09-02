# M2 pause and Codex continuation — 2026-09-02

**CONFIRMED human direction:** stop work now and resume in a later conversation after token reset. Codex owns future implementation; do not use or troubleshoot Antigravity/Gemini. No scheduled continuation was created. The human will commit to GitHub; Codex made no commit or push.

## State and completed work

Starting and pause HEAD: `83797e840e414bf99a0f71117892da355d94be55`. The checkout already contained substantial governance changes; preserve them. M1/G1 is historical completed work. M2 has design and bounded pilot evidence, but no production SemanticFrontend port or JavaParser adapter. G2 is not passed and also requires later M3 evidence.

- [M2 contracts](../../architecture/m2-semantic-frontend.md): Unicode-safe canonical keys, neutral frontend/evidence boundary, exact original-source coordinates, origins and explicit degraded outcomes. Independent Codex design review led to corrections for nested local-owner identity, initializer ownership, derived evidence and occurrence ordinals.
- [Oracle pilot](../../../benchmarks/m2-ground-truth/README.md): six source fixtures, seven compilation groups, 34 occurrence labels and three identifier relations. The coordinator run passed 34/34 labels: 30 adjudicated targets and four correctly withheld error-case targets. Raw results are archived in the benchmark package. This does not establish JavaParser correctness or full M2 coverage.
- Production preparation: six identity-test cases; three coordinate-test cases with an unimplemented `OriginalSource` stub; candidate adapter POM pinned to JavaParser/SymbolSolver 3.26.1; four fixture families with 23 occurrence expectations (calls 7, missing 3, spans 5, owners 8), plus declaration expectations. These remain **unvalidated drafts**, not accepted production behavior.
- At pause, both Codex workers were already interrupted and were instructed to stay stopped. The task-started Antigravity CLI server PID 29204 was explicitly stopped; no new external job was dispatched.

## Durable files and draft preservation

[archive.json](archive.json) records original locations, destination paths and SHA-256 for 12 byte-preserved files. Draft Java/POM files use `.txt` suffixes and are outside Maven modules. `JavaSymbolNameTest.java` was moved from the root analyzer test tree into [drafts](drafts/JavaSymbolNameTest.java.txt), because its referenced production classes do not exist. This preserves the work without making an incomplete test part of the human's checkpoint commit. Other scratch originals remain untouched.

The archive's local `.gitattributes` disables text conversion for draft, fixture and evidence payloads so recorded bytes survive Git checkouts.

| Archive | Contents and limits |
|---|---|
| `drafts/` | Identity test, adapter POM, coordinate stub/test. `OriginalSource` throws `UnsupportedOperationException`; implement it rather than treating it as working code. Check all draft assumptions against actual APIs. |
| [candidate-fixtures/fixture-expectations.json](candidate-fixtures/fixture-expectations.json) | Four fixture families and 23 expectations, authored independently of adapter output. Java fixtures are retained beside the manifest. No production adapter was run against them. |
| `evidence/` | Two actual worker build-failure outputs and missing-dependency compiler diagnostics. Filenames containing `red` describe intended test attempts, not an observed behavioral TDD red. |

The root reactor remains `analyzer` + `backend`; the adapter module exists only as an archived draft/scratch scaffold. No production source or root POM change was integrated. Ignored `out/` contains additional local scratch/logs, but the continuation-critical drafts above are versionable and do not require that ignored directory to survive a clone. Do not commit whole scratch trees, dependency caches or compiled classes.

Durable state updates at pause: [current state](../../current-state.md), the M2 contract's authorization/status wording, this handoff/archive, and supersession notices on the historical [preflight record](../m2-design-2026-09-02.md) and [Antigravity package](../m2-antigravity/README.md). Roadmap scope and milestone order did not change.

## Verification and concrete blocker

Earlier in this task, `.\mvnw.cmd -B -ntp verify` completed with 25 analyzer tests and one backend test, zero failures/errors/skips. The standalone oracle command and exact archived results are recorded in the preflight record. These checks preceded the later implementation drafts; no new Maven or oracle run was started during this requested pause.

Later root test attempts did not reach the new identity tests:

1. The sandbox JVM attempted to use `C:\.m2` and hit access denial.
2. Setting process-local `MAVEN_USER_HOME=C:\Users\Admin\.m2` and Maven `-Dmaven.repo.local=C:/Users/Admin/.m2/repository` got farther. Selecting `-pl analyzer` alone violated the existing reactor-convergence enforcer; use `-am` to include the parent.
3. The attempt below reached test compilation but failed opening cached `junit-jupiter-engine-5.11.0.jar`. The worker's private scaffold hit the same cache-access problem. This is an execution-environment blocker; missing-class TDD failure and a successful build of new code were not observed.

```powershell
$env:MAVEN_USER_HOME = 'C:\Users\Admin\.m2'
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer -am '-Dtest=JavaSymbolNameTest' test
```

That command is historical: the identity test is now archived outside the reactor. Restore it when implementing its production classes and performing the intended TDD cycle. On resumption, use systematic debugging and supported execution approval for the actual cache/JDK access problem. Do not weaken permission controls, copy caches around a denial, or claim an environment failure is a semantic regression test.

The fixture worker reported successful isolated `javac` 21 compilation of calls/spans/owners and four expected missing-dependency errors (`--release 21 -proc:none -encoding UTF-8`, empty classpath/sourcepath). The coordinator inspected saved artifacts but did not rerun or independently adjudicate these new candidate fixtures. Treat them as inputs to the next implementation, distinct from the already rerun 34-label pilot.

Pause verification: all 12 archive hashes and four source hashes matched; the candidate manifest contains 23 occurrences; 38 local links in the five updated/new Markdown documents resolved. No tracked analyzer/backend/root-POM diff or staged change was present. Of 48 initial file states, 46 remained identical; `docs/current-state.md` was intentionally updated, while `.agents/workflows/handoff.md` had changed outside this pause's edits and was preserved. The scoped documentation `git diff --check` passed. The whole-checkout check reported trailing whitespace at line 2 and an extra blank line at EOF in that existing `.agents/workflows/handoff.md`; no repair was made to this unrelated, read-only path. No final reactor-build claim is made.

## Decisions and next implementation slice

**CONFIRMED:** the human said preflight/design is sufficient and directed settling the mature M2 contracts for coding. Earlier requests to wait for D1–D3 approval are superseded. No further human decision is needed to begin this scope. Only a concrete new consequential correctness/scope issue should reopen a decision.

Implement in `analyzer/src/main/java/com/evolution/analysis/frontend/` and a separate root module `analyzer-javaparser`. Keep JavaParser AST/resolution types inside the adapter. The first slice is declarations and method calls with explicit unresolved/unmappable outcomes, exact spans, real origins and deterministic identities/results. Handle only the minimal Unicode identity blocker before adapter work. Extend relationship slices by dependency/risk; do not wait to finish designing all 18 categories.

The working API sketch below was communicated between Codex workers but has **not** been implemented or compiled. Use it with the M2 contract rather than treating it as an existing API:

- `SemanticFrontend.analyze(FrontendRequest)` returns `FrontendResult`.
- `SourceInput(SourceDocument, byte[])`: defensive bytes, strict UTF-8, verified document digest; original UTF-16 coordinates.
- `PlatformInput(ClasspathEntry, Path javaHome)` and `BinaryInput(ClasspathEntry, Path path)`: neutral runtime handles; paths do not define identity. Verify actual content and exact ordered manifest entries. First slice may explicitly limit platform handling to verified current JDK 21 and dependencies to supplied JARs.
- `FrontendRequest(AnalysisManifest, ModuleIdentity, SourceClassification, sources, platform, dependencies)`: exact inventoried source subset for module/source-set, no duplicates/omissions/ambient discovery, immutable lists and versioned configuration. Candidate option keys: `java.release`, `java.symbols`, `java.coordinates`, `java.frontend.catalog`, `java.module`, `java.source-set`; freeze concrete values in tests when implemented.
- `FrontendResult`: analysis identity, frontend version, deterministic entity/occurrence lists, diagnostics, unmapped ledger and per-source outcomes. Diagnose duplicate facts and never manufacture entities for unknown identities/origins. `UnmappedObservation` retains document, category, optional real span, status and diagnostics. Source outcomes distinguish processed/partial/error/unsupported.
- `JavaSymbolName` and `ErasedType`: recursive `java:v1:` canonical JSON tuples described in the contract. Preserve composed/decomposed Java identifier distinctions; remove Java identifier-ignorable characters; encode other non-ASCII-safe code points as `%` plus six uppercase hex digits. This keeps M1's NFC validation intact. Callable keys use erased declaration parameters, not return type or parameter names; local owners retain source anchors. Arrays retain positive rank; `void` is not a parameter primitive.

Candidate adapter approach to validate during coding: parse immutable supplied sources, use a project `MemoryTypeSolver`, exact supplied JAR solvers and the platform classloader rather than the application classloader. JavaParser 3.26.1 was locally available and used in the draft POM; it is not yet a verified production pin. Confirm overload/generic erasure, original Unicode-escape coordinates, source membership and external artifact origins with tests. Reject unsupported input/platform views explicitly rather than silently resolving against the host.

Continuation order:

1. Refresh AGENTS/context/current state/roadmap and Git state, including the human's possible checkpoint commit. Read applicable implementation, semantic, TDD, debugging and verification procedures; preserve user changes.
2. Resolve build access and implement minimal identity/port behavior with golden and negative tests. Integrate the isolated adapter for declarations and method calls in the same vertical slice.
3. Promote candidate fixtures only after inspecting labels; test exact callers/targets/origins/spans, missing dependencies, duplicate calls, Unicode, local owners, false positives and deterministic reruns. Do not hide omissions by shrinking denominators.
4. Run focused tests and full reactor verification, inspect the actual diff, and continue subsequent relationship slices. Update durable docs only when implementation changes truth. Full M2 and G2 remain separate acceptance claims.

Do not resume Antigravity, repeat a broad design/audit/oracle phase, reopen the approved parser choice, commit/push, or enter M3 automatically. No automatic wake-up or token-reset monitor is active; continuation begins when the human asks.
