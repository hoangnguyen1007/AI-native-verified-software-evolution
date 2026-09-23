# M4-UNIVERSAL Slice 2 verification — 2026-09-23

This package records the implementation and final verification of the four
bounded Slice 2 provider lanes. The [implementation contract](../../architecture/m4u2-universal-spring.md)
defines exact support and gaps. The [current state](../../current-state.md)
identifies M4E validation as the next task; G3 and global 98–99% coverage are not
established by this package.

**CONFIRMED final result:** **504 tests across 71 suites**, with **zero failures,
errors or skips**. The Maven command exited **0** in **212.45 seconds** with
Enforcer enabled. Every current `*Test.java` suite has a report written within
this invocation's time window; source/build inputs remained unchanged.

## Final execution evidence

The canonical final command is:

```powershell
.\mvnw.cmd -B -ntp verify -q '-Dmaven.repo.local=C:/Users/Admin/.m2/repository'
```

It runs the platform's root reactor, not an analyzed repository. Maven Enforcer
is enabled. This is `verify`, not `clean verify`; no clean-build claim is made.
[execution.json](execution.json) records actual timestamps, elapsed time, exit
code and the source-input stability check. [verification.json](verification.json)
records each Surefire suite's test/failure/error/skip counts, freshness and report
SHA-256. [verify.log](verify.log) preserves the console output.

| Module | Fresh suites | Tests |
|---|---:|---:|
| analyzer | 38 | 319 |
| analyzer-maven | 8 | 68 |
| analyzer-filesystem | 2 | 12 |
| analyzer-javaparser | 22 | 104 |
| backend | 1 | 1 |
| Total | 71 | 504 |

Eight pre-existing reports are retained on disk but excluded from these totals;
`excludedReports` lists their names and hashes. They include historical renamed
suites and a standalone oracle IT. The first report collector incorrectly included
them and mishandled PowerShell dictionary aggregation. Collection was corrected,
including UTC timestamp handling, and rerun with `-CollectOnly` against the saved
execution window. That mode revalidates source hashes and expected suite coverage
without rerunning Maven. [verification-unfiltered.json](verification-unfiltered.json)
preserves the initial invalid aggregation for audit; its totals are not evidence
of this final result. The platform reactor itself ran once and passed.

Run the checked-in evidence generator from the repository root to reproduce the
same collection procedure with an explicitly chosen local toolchain/cache:

```powershell
& ./docs/reproducibility/m4u2-spring-2026-09-23/verify.ps1 `
  -JavaHome 'C:/Program Files/Java/jdk-21.0.12.1' `
  -MavenUserHome 'C:/Users/Admin/.m2'
```

[toolchain.txt](toolchain.txt) records Maven 3.9.16 / Oracle JDK 21.0.12.1 on
Windows. [source-hashes.json](source-hashes.json) contains the complete reactor
source/resource, POM and wrapper input hashes. [artifact-hashes.json](artifact-hashes.json)
pins the exact Spring/Boot/Spring Data/injection API JARs used by the authored
controls. Runtime timing and JUnit XML hashes identify this execution; they are
not portable semantic identities or a scale benchmark.

## Behavioral checks

| Suite | Tests | Assertions relevant to Slice 2 |
|---|---:|---|
| `SatConfigurationReasonerTest` | 4 | 56 dimensions; SAT/UNSAT/UNKNOWN; deterministic witness replay; bounded search/depth; implication/equivalence; 100 seeded formula controls against an independent exhaustive Boolean oracle |
| `UniversalConditionSemanticsTest` | 9 | 56-profile symbolic regions; M4B replay; source precedence and MISSING fall-through; profile defaults/group cycles; opaque/infeasible/limit cases; missing constraint evidence; actual ConditionalOnExpression lowering; unsupported/malformed/cyclic/overflow SpEL |
| `UniversalFrameworkContextTest` | 7 | Boot 1/2/3 namespace/override/metadata policies; properties continuation, imports, deduplication, malformed/limited resources; hierarchy shadowing, closure, cycles, primary conflicts and unknown matches |
| `UniversalSpringSemanticsTest` | 10 | Exact real-artifact source analysis; common/JPA repositories and generic source ancestry; NoRepositoryBean, scan scope, auto-disable/exclusions, generation-qualified injection/names; route combinations and unsupported outcomes; controller/service/repository paths; seven expression comparisons with the actual Spring 6.2 interpreter |
| `TruthRegionEvaluationTest` | 17 total, 2 added | Existing 15 M4D cases plus SAT signatures running actual M4C registration/binding over 56 profiles, repeated identities, signature limits and small-space agreement for TRUE/FALSE/UNKNOWN descriptors |
| `UniversalIngestionPipelineTest` | 2 existing, extended | Automatic repository resource acquisition feeds the unified metadata result while preserving original ingestion/replay/partial-result assertions |

The four new suites add 30 tests; the two staged-integration additions bring the
slice's additions to 32 tests. Assertions in existing pipeline tests were extended
without counting them as new test methods. Closed negative/unknown cases remain
part of the denominator rather than being removed to improve apparent coverage.

Focused development commands ran one class (or one regression method) at a time:

```powershell
.\mvnw.cmd -q -pl analyzer '-Dtest=UniversalConditionSemanticsTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' test
.\mvnw.cmd -q -pl analyzer-javaparser '-Dtest=UniversalSpringSemanticsTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' test
```

Single-module invocations omit the parent from the selected reactor, so only
those focused runs skipped Enforcer's reactor-convergence check. Local analyzer
installation refreshed the adapter's dependency after neutral API changes. The
final root verification enables Enforcer. Compilation/JVM startup and frontend
tests exceeded a five-second wall-clock iteration budget in this environment;
no claim that all development iterations met that budget is made. No standalone
real-repository benchmark was run during development or handoff.

Observed red-to-green cases include the initial unavailable symbolic backend,
top-level repository declarations mistaken for nested declarations, conflicting
primaries in different ancestor factories, a repository name from the wrong
injection namespace, and unary numeric overflow in passive SpEL. Three final
audit regressions are preserved in `context-audit-red.log`, `names-audit-red.log`
and `spel-audit-red.log`; these are intentional earlier failing checks, not the
final build result. Final assertions preserve the unknown/gap outcomes for these
inputs instead of inventing semantic certainty.

## Evidence strength and limits

Source controls use real digest-pinned Spring 6.2.0, Boot 3.4.0, Spring Data 3.4.0,
Jakarta Inject 2.0.1 and Javax Inject 1 artifacts. Generation-policy unit tests use
explicit authored artifact evidence; they are not runtime oracles for Boot 1/2.
Seven authored, safe expressions are evaluated by Spring's actual interpreter;
the production evaluator never invokes that interpreter on target input.
SAT also has an independent Boolean oracle and comparison with the original
small-space M4B/M4C path. Agreement with another platform path is regression
evidence, not an independent framework-wide semantic certification.

Code review was implementation self-review, supported by pinned upstream primary
sources and library comparisons. No independent agent review, representative
external corpus evaluation, cross-OS verification, all-version runtime comparison
or universal worst-case performance measurement was performed. The implementation
records provider gaps for incomplete evidence, unsupported semantics and bounded
reasoning. It does not claim arbitrary infinite-domain solving, unconditional
repository bean activation or complete custom meta-annotation acquisition.

## Handoff

| Field | Result |
|---|---|
| State before | Slice 1 ingestion delivered; Slice 2 providers and production symbolic backend remained next |
| Work completed | Four Slice 2 lanes, unified-source integration, real M4B SpEL lowering, normalized staged SAT execution, focused regressions and durable contracts/evidence |
| Files changed | `analyzer` Spring universal/truth/condition providers and ingestion pipeline; four new test suites and two existing suites; test-only adapter POM dependencies; README/current state/contracts and this package |
| Tests and results | Root `verify`: exit 0, 504 tests / 71 fresh suites, 0 failures/errors/skips; all 71 expected suites accounted for; Enforcer enabled |
| New evidence | Symbolic replay and independent Boolean controls; actual source-to-repository/path cases; pinned SpEL comparisons; explicit negative/limit/version/hierarchy cases |
| Decisions made | Pure-Java DPLL behind the replaceable reasoner port, approved by the existing capability contract; deterministic finite budgets and explicit unknown residues |
| Decisions requiring approval | None for this bounded implementation; broader provider/gate acceptance remains subject to its existing process |
| Limitations | Exact support boundaries above and in the implementation contract; worldwide coverage and G3 remain unmeasured/unpassed |
| Blockers | No known blocker within the verified provider contracts; remaining acceptance obligations belong to M4E/G3 |
| Durable state | `docs/current-state.md`, `docs/architecture/m4u2-universal-spring.md` and this immutable-execution record describe the delivered state |
| Exact next task | M4E: register version/corpus/baseline/ground-truth and performance protocols, then assess both supported and unresolved obligations before G3 adjudication |

Git inspection was explicitly omitted by the owner. No Git command, commit,
push, destructive cleanup or target-repository lifecycle was performed.
