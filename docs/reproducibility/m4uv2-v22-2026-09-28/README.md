# V2.2 implementation verification — 2026-09-28

Scope: the partial implementation described by the
[boundary contract](../../architecture/m4uv2-v22-implementation-boundary.md).
This is implementation verification, not a cohort benchmark or V2.2 acceptance.
The starting checkout was clean at `e18c901` (`feat(v2.1): isolate archive inspection`).
The owner explicitly requested V2.2 and continued completion of the same task.

## Executed controls

Focused development commands use one class per invocation, in quiet mode:

```powershell
.\mvnw.cmd -q -pl analyzer '-Denforcer.skip=true' '-Dtest=ConfigImportClosureTest' test
.\mvnw.cmd -q -pl analyzer '-Denforcer.skip=true' '-Dtest=ConfigDataIngestionTest' test
.\mvnw.cmd -q -pl analyzer '-Denforcer.skip=true' '-Dtest=JavaSymbolNameTest' test
.\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=ConfigImportOracleTest' test
.\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=JavaSemanticClosureTest' test
.\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=GeneratedSymbolResolutionTest' test
.\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=SourceToSpringPlanTest' test
.\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=FieldAccessTest' test
```

Enforcer is skipped only in these single-module development commands because the
root reactor-convergence rule rejects an intentionally partial reactor. The final
root `verify` runs with Enforcer enabled. The current analyzer artifact was installed
locally with `-pl analyzer '-Denforcer.skip=true' '-DskipTests' install` when required
by the adapter's focused test classpath; this command is not a test pass.

All Maven commands operate on this trusted platform checkout. No target Maven,
Gradle, application, script or processor was executed. The independent oracles
execute only fixed authored controls: Lombok 1.18.46 through javac attribution,
Boot 3.4.0's Config Data loader, and Framework 6.2.0's container.

| Control | Evidence and qualification |
|---|---|
| Imported configuration | Nested/repeated precedence, optional/missing/malformed inputs, profile groups/variants, inactive imports, containment, cycles, byte/depth budgets and deterministic replay |
| Boot oracle | Three independent authored import fixtures; repeated-import precedence initially disagreed with Boot and drove a correction |
| Generated Java calls | Derived constructor/accessor targets, explicit accessor preservation, wider handwritten overload, private/null/wrong-type controls; inherited generated override regression demonstrated before correction |
| Compiler oracle | Attributed generated `String` constructor beats handwritten `Object` constructor on the same fixed source with pinned Lombok |
| Java evidence | Annotation constant/default reads, inferred generic `var`, distinct lexical lambda/catch declarations; existing identity tuple golden assertion preserved |
| Source-to-truth | Real frontend and acquisition providers produce plans, dependencies and selected-binding regions; small-space SAT/exhaustive agreement, replay and active/inactive Spring container comparison |
| False certainty | Missing order, open registry, incorrect/empty qualifiers and budget exhaustion; acquisition/evaluation gap retention |

Observed red states included unresolved generated direct clients, incorrect wider
constructor selection, repeated-import precedence disagreement, a false inherited
generated target, the empty-qualifier exception and a dropped upstream evidence
ledger in the pipeline continuation. Some added Java characterization
tests first encountered a stale local dependency/setup failure; no behavioral RED
is claimed for those tests. The initial broad run found the legacy field-read test
still expecting the now-supported annotation read to be omitted; the replacement
expectation must assert its target, owner and accounting rather than merely relax
the count. Those repaired expectations and the upstream-ledger regression pass
in the final run.

Focused commands use bounded authored inputs and compact logs, but Maven startup,
compiler work and some integration classes exceeded the repository's requested
five-second development-loop target. No claim of meeting that latency target is
made. The SLF4J no-provider message is an observed test-runtime warning, not a
semantic failure or skipped test.

## Final verification record

**CONFIRMED by execution:** final `.\mvnw.cmd -q verify` exited **0**, with Enforcer
enabled: **597 tests in 88 fresh suites; 0 failures, 0 errors, 0 skips**. Toolchain:
Maven 3.9.16, Oracle JDK 21.0.12.1, Windows amd64, UTF-8. This was `verify`, not a
claim of a clean build. All 18 changed source/test file hashes stayed unchanged
through the final invocation.

The initial root invocation exited 1 on the obsolete annotation-read expectation;
focused repairs then passed before the final root invocation. This justified
verification rerun was not a second external benchmark; no external benchmark ran.
`git diff --check` and local documentation-link validation also passed.

Machine-generated records:

- [Initial verification outcome](initial-verification.json) and [final command outcome](verification.json).
- [Fresh suite totals, report SHA-256 digests and excluded stale reports](test-summary.json).
- [18 changed source/test hashes](source-hashes.json), also identifying the exact changed files.
- [Build input hashes](build-hashes.json) and [11 pinned semantic artifact hashes](semantic-artifact-hashes.json).

Historical reports are excluded by invocation timestamp. Existing historical
evidence packages are unchanged. Ignored local `.v22-*.log` files retain command
output; this package stores selected summaries/digests rather than secret-bearing
environment properties from raw Surefire XML. The only final console messages
were the SLF4J no-provider notice and the explicit zero exit status.

## Handoff boundary

The implementation is a coherent bounded advance, not full V2.2 closure. Its
remaining A–E obligations are enumerated in the boundary contract and task file.
No independent agent review, external cohort evaluation, 99% coverage measurement,
V2.3 handoff or M4E/G3 promotion occurred. Self-review and independent compiler/
framework test oracles are separate forms of evidence.

No new consequential architecture approval is requested for these reversible
changes. No environmental blocker is asserted. No commit or push was made.
`docs/current-state.md` and the V2.2 task status preserve the unfinished work.
The next bounded implementation is source-acquired bean producers/parameters with
evidenced scheduling and pinned-container positive/negative controls.
