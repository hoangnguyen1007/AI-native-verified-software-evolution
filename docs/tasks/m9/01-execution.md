# M9 Task 1 â€” execution and handoff

Date: 2026-11-11. **CONFIRMED / repository implementation and bounded verification: COMPLETE â€” protocol registered.** This closes Task 1 only. M9.2â€“M9.5, independent evaluation/review and M10 human release approval remain pending.

## Before and after

Before: HEAD `d8c26035f9254db040fc0a61336fada3fb2a98b6`; existing changes to CLI README, readiness/ADR-007/current-state and two historical reports, plus the untracked M9 plan. M8 was human-closed, passive completion 95% was human-approved, and the M9 task index was a proposal. Those changes were preserved; no commit/push or destructive cleanup occurred.

Delivered: [protocol v1](PROTOCOL.md) and [registry](registry.json), with **16 pinned external repositories / 18,367 physical entries / 359 descriptor owners**, including three holdouts not run through the analyzer. Descriptor ownership is not a proven effective-context count. **17 claims** have oracle, denominator, case and criterion; **22 existing label/test sets / 448 registered case IDs** are indexed with source hashes. Labels include source-authored expectations, not 448 independently adjudicated M9 successes. Detailed physical/context censuses and baseline JAR are outside Git at `D:/UIT/evo-benchmark-cohort/m9-inputs-v1-final/`.

Candidate executable identity:

| Field | Value |
|---|---|
| Source HEAD | `d8c26035f9254db040fc0a61336fada3fb2a98b6` |
| Executable source manifest (635 files) | `60bc5ac2b0b73ac6eed115fc4eeb7d0fdde996d030611e54ae59a9c02e29a502` |
| Executable dirty HEAD patch | `515e7732018e8d7357ab4d0dbe42db34e3d4f6e9024b229b946de0f97f119976` |
| Packaged JAR | `968242158bd4402935767544ee0bdfee516575090b6b315600f02c706ca0f0c2`, 46,218,773 bytes |
| Immutable operational location | `D:/UIT/evo-benchmark-cohort/m9-inputs-v1-final/evo-baseline-r2.jar` |

The registry separately pins exact JDK/JMOD/compiler/browser/Node, provider/schema/rule/metric/score/status policy, test/fixture/oracle bytes, source manifests and per-case passive command templates. The post-update 13-row results and pre-existing ten-row `results-m9-baseline` are pinned as historical/unbound; neither is silently assigned this candidate. Java PetClinic and ThingsBoard remain separate historical mappings. P/C/N lane and compatible-evidence requirements prevent promoting passive parsing to native binding or a historical capture to current qualification.

## Implementation and actual verification

`SemanticDepthArchitectureTest` now names the approved 95% policy and checks 949/950 independently for structure, hierarchy, mixed syntax and pure non-JVM, zero populations, worker/RPC/failed-unit guards and tier selection. This is characterization of existing policy; no production threshold changed.

The direct CLI consumer exposed an existing failure: `receiptCountsUniquePublishedGapsIncludingRpcProjection` expected partial/exit 3, but got `COMPLETE`/exit 0 with two final `GRPC_ROUTING_EVIDENCE_MISSING` gaps. Root cause: `PolyglotScan.project` adds graph gaps after the input gap list used by completion. `EvoApp` now uses the final published gap summary to block passive promotion for required RPC/worker evidence, checks final gap count for exact completion, and passes all frozen input gaps to the predicate. The receipt reuses the same summary, retaining gaps without a second traversal. The existing failing test plus status/tier assertions now passes. Native evidence, threshold and score formulas are unchanged.

| Command/check actually run | Result |
|---|---|
| `mvnw.cmd -q -pl backend test -Denforcer.skipRules=reactorModuleConvergence -Dtest=SemanticDepthArchitectureTest` before/after added boundary characterization | PASS. Final 3 cases. Initial invocation 8.910 s; boundary invocation 6.876 s, both exceed the five-second inner-loop target. |
| `mvnw.cmd -q -pl cli -am package -DskipTests`, followed by dependency-closure `install -DskipTests` | PASS; builds trusted platform dependencies/current source for candidate packaging and module-only integration checks. These are builds, not full-reactor verification or native acceptance. Installed old SNAPSHOT hashes differed, so they were not used as current-source evidence. |
| First `-pl backend,cli -am test` with class filter | Selection failed in the dependency module with no matching tests; no passing campaign claimed. Corrected to module-only checks after current dependency install. |
| First module-only backend/CLI check | FAIL: RPC projection completion regression above; expected 3, actual 0. Retained as negative evidence in this record. |
| `mvnw.cmd -q -pl cli test -Denforcer.skipRules=reactorModuleConvergence -Dtest=ScanJourneyTest#receiptCountsUniquePublishedGapsIncludingRpcProjection` after repair | PASS; 8.010 s wall, exceeds five-second inner target. |
| `mvnw.cmd -q -pl backend,cli test -Denforcer.skipRules=reactorModuleConvergence -Dtest=SemanticDepthArchitectureTest,ScanJourneyTest,TerminalReportsTest` | **26 cases, 0 failures/errors/skips**: backend 3; CLI journeys 13; CLI presentation 10. Journey suite body 12.085 s. Final self-review retained the input-gap guard in addition to the published-gap guard; the final CLI-only `test package -Dtest=ScanJourneyTest,TerminalReportsTest` rerun passed 23 cases (0 failures/errors/skips), with journey body 11.448 s. Unchanged backend checks remain valid. These are the bounded pre-handoff unit/direct integration consumers. |
| `mvnw.cmd -q -pl cli package -DskipTests -Denforcer.skipRules=reactorModuleConvergence` | PASS at the first repaired candidate revision; wall 8.159 s. The final r2 artifact/hash above was produced by the final CLI `test package` check, preserving the earlier JAR separately. |
| Final packaged JAR on authored proto + Go client + Python server, offline/build off, explicit empty cache/workspace | PASS of negative honesty control: exit **3**, status **PARTIAL**, tier **PARTIAL_RECOVERY**, exactly **2 RPC gaps**. Raw bounded receipt: `D:/UIT/evo-benchmark-cohort/m9-protocol-pilot-v1/rpc-guard/stdout-r2.json`. No target code executed. |
| `python -m unittest discover -s benchmarks/g2-pipeline -p test_m9_protocol.py -q` | **7 cases PASS**: unknown/binary/generated/vendor census, nearest ownership, same-size changed bytes, missing oracle/cases, duplicate claims, changed/missing candidate/index rejection, orphan disposition and overwrite refusal. Initial implementation had a reproduced nearest-owner tie defect; fixed before successful registration. |
| `m9_protocol.py freeze --corpus-root D:/UIT/evo-benchmark-cohort --jar cli/target/evo.jar --output D:/UIT/evo-benchmark-cohort/m9-inputs-v1-final` | PASS; 16 corpus cells, all source checkouts clean, external census and durable JAR. Earlier attempt failed with `FileNotFoundError` at candidate identity; shortened Windows Git pathspec arguments, preserved the incomplete directory and froze to a new destination. No raw result was edited. The final executable refinement is registered as candidate `m9.1-r2-final`; earlier candidate JAR/registries remain preserved and the same unchanged census is reused. |
| `& benchmarks/run-g2-benchmark.ps1 -M9Preflight` | PASS: 17 claims, 16 corpus cells, `sourceRehash=true`, `errors=[]`, `acceptanceRuns=NOT_RUN`. After the final CLI-only refinement, candidate/tool/index/label preflight was refreshed without repeating unchanged source acquisition or semantic suites. No build, network, worker or acceptance execution. |

One development pilot (Go, original pre-repair JAR) completed in **9.922 s**, exit 0, fresh analysis/artifact cache, uncontrolled OS cache. Its actual original JAR hash and receipt are separately pinned; it selects observation allocation only. It establishes neither holdout correctness, repeated performance nor peak memory. The final patched packaged control establishes the RPC guard separately. No 13-repository rerun, native VM campaign, browser/UI acceptance, independent usability study or full `clean verify` was run for Task 1.

## Scope, decisions and next task

Files: benchmark preflight/helper/tests and G2 entry switch; backend status-policy tests; CLI completion and journey tests; root README, roadmap and product-outcome status reconciliation; M9 protocol/registry/execution index and current state. Existing ADR-007/readiness/report edits were preserved. Frozen raw sources, old results, captures and unqualified trust markers were not improved by hand.

Routine choices: reuse G2/M2/M4 infrastructure; purposive three-repository holdout; physical-byte census outside Git; explicit P/C/N, experimental allocations, pilot-relative comparison and closed failure denominator. Apply existing human 95% and G4/G5 decisions. No new consequential human decision is needed for Task 1. Historical G6 remains passed in its original scope; no gate is renumbered or promoted.

Limits: census conventions do not prove effective build/generator closure; external semantics are not yet adjudicated; C/N compatibility/qualification remains per-claim; emitted calibration remains `PROVISIONAL`; no independent reviewer was assigned. New interaction/UI labels must be source-bound before their first scoring run. These are recorded prerequisites for later tasks, not invented Task-1 acceptance evidence. There is no Task-1 blocker after successful preflight.

**Exact next task: M9.2**, starting with J01/J02 Java recovery attribution and independent javac/source controls after source-rehash preflight. Add preregistered negative labels before scoring; preserve first holdout outcomes and reclassify any debugged holdout as regression. M9.3â€“M9.5 and M10 human release approval remain pending.

Final document checks: `git diff --check` passes; all local Markdown targets in changed active-state/product/M9 documents exist. The stale historical review-delivery JAR link is now explicitly an unavailable historical location; its original recorded hash is preserved. Final candidate preflight returns zero with 17 claims / 16 repositories / no errors; its unchanged corpus source-rehash evidence is reused from the successful existing-entry-point preflight. Implementation review is self-review only.

## QC follow-up: portable preflight

**CONFIRMED / reproduced and repaired (2026-11-11): both audit findings are supported.** Initial verification covered invocation with `&` and did not exercise Windows PowerShell 5.1's `-File` parameter-binding boundary. The exact reported command failed with `FileNotFoundError`. A test with the real script and a data-only helper independently observed the default binding as `C:\docs\tasks\m9\registry.json`, while the test checkout lived in a different temporary directory. The original completion report therefore overstated launcher coverage; the newly added boundary tests close that specific gap.

Changes and decisions:

- `run-g2-benchmark.ps1`: all three script-relative path defaults are initialized in the script body, after parameter binding; explicit caller values are preserved. `-M9RootsFile` forwards an optional local root-binding JSON file. No legacy benchmark/build/publish operation was run for this repair.
- `m9_protocol.py` and `registry.json`: portable registration schema v2 uses `repo:...` locators against the actual checkout and explicit external `corpus/evidence/jdk/node/browser/authored` roots. Recorded external paths remain provenance/defaults; a local roots file can rebind them. Unknown roots, attempts to override the repository root, lexical traversal/absolute suffixes and resolved containment escapes are rejected. No basename guessing, network fallback, skipped hash, tool substitution or candidate promotion.
- Keep the evaluation protocol at v1. Candidate runtime/patch/JAR digests, source commits/manifests, original source/label expectations, 17 claims and all numerical criteria are unchanged. Refresh only the four changed benchmark/test/protocol pins and add the launcher-test pin. The 135 current file pins contain **zero absolute locators** (58 checkout-relative, 77 external-tool locators); JAR/census locations also use external-root locators.
- Preserve exact previous workspace registry bytes at `evidence:registry-workspace-v1.json`, SHA-256 `eb17a7e88905a7efe92a7cbc7edd668601235aa01e3c425273c51903a8e66831`. The earlier `adopted-protocol-r2.json` contains equivalent JSON with LF serialization and a different byte digest; it is also preserved, never relabeled. The new external snapshot is `evidence:adopted-protocol-portable-v2.json`. No raw evidence or baseline JAR was modified.

Verification actually run:

| Check | Result |
|---|---|
| Original `powershell -ExecutionPolicy Bypass -File benchmarks\run-g2-benchmark.ps1 -M9Preflight` | Initially FAIL / exit 1. After repair and explicit pin revision: **PASS / exit 0**, v2, 17 claims, 16 repositories, `sourceRehash=true`, `errors=[]`, `acceptanceRuns=NOT_RUN`. |
| New regression tests before repair | Correctly failed on the wrong default registry path and inability to relocate inputs. |
| `python -m unittest discover -s benchmarks/g2-pipeline -p 'test_m9_*.py' -q` | **13 tests / 0 failures/errors/skips** on Windows: 10 protocol tests and 3 real Windows PowerShell `-File` boundary tests. Final body ~0.8 s. Includes spaced checkout/config paths, unrelated cwd, explicit registry/root forwarding, nonzero propagation, relocating repo/evidence/source roots, changed source/JAR bytes, traversal and resolved escape controls. |
| Actual v2 registry + explicit roots file, invoked by Python from unrelated cwd | **PASS / exit 0**, all candidate/tool/label/index pins verified. |
| Actual v2 registry + explicit evidence binding to an absent directory | Expected rejection / exit 1, **33 missing JAR/census errors**, candidate JAR rejected; no fallback to the old evidence location. |

The launcher was also exercised after its path repair but before pin revision: it reached the real registry and correctly rejected the four changed pinned files. This expected failure demonstrates that relocation does not bypass the registration's byte checks. No analyzer/Java/native/browser suite was rerun because those inputs and implementations did not change. A different physical machine/OS was not exercised; relocation is established by isolated temporary-root tests and real configured preflight, not an unrestricted CI or cross-OS certificate. A new host still needs the registered artifact/tool bytes or a prospectively registered environment.

Files for this QC slice: benchmark launcher/helper and their tests, portable registry/protocol, this execution addendum and current state. Self-review only; no independent review is inferred from applying the supplied findings. No commit/push. **Exact next slice remains M9.2 J01/J02**, after preflight with explicit local roots where needed.
