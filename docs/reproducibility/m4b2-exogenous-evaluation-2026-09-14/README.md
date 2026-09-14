# M4B.2 Verification — 2026-09-14

## Scope and Outcome

**CONFIRMED by fresh execution:** M4B.2 delivers the passive evidence-to-IR lowering boundary, bounded exogenous condition evaluation (profiles, properties, web mode, build constants), Strong-Kleene 3-valued logic, and finite configuration space enumeration described in the [architecture contract](../../architecture/m4b2-evidence-lowering-exogenous-evaluation.md).

The final reactor passes **333 tests in 54 suites, zero failures/errors/skips**. All report files were checked for freshness against this run.

This is implementation self-verification. No independent reviewer or subagent was used. No real-repository benchmark, Spring runtime container, target lifecycle execution, network acquisition or production SAT solver was run. Gate G3 remains pending.

## Commands and Environment

Windows 11 amd64; Oracle JDK 21.0.12.1; repository wrapper Maven 3.9.16; UTF-8.
Explicit Maven home settings avoid ambient cache discovery:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd --version

# Focused test suites executed during development:
.\mvnw.cmd test "-Dtest=ConditionLoweringTest,ExogenousConditionEvaluatorTest,FiniteConfigurationEvaluationTest,ConditionModelTest" "-Dsurefire.failIfNoSpecifiedTests=false" -q

# Full reactor verification:
.\mvnw.cmd test -q
```

All final commands exited with code 0. Output was captured to quiet logs, and counts were verified directly from Surefire XML reports.

## Results and Evidence

| Test class / scope | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| ConditionLoweringTest | 7 | 0 | 0 | 0 |
| ExogenousConditionEvaluatorTest | 12 | 0 | 0 | 0 |
| FiniteConfigurationEvaluationTest | 4 | 0 | 0 | 0 |
| ConditionModelTest | 11 | 0 | 0 | 0 |
| Focused M4B total | 34 | 0 | 0 | 0 |
| Entire final reactor | 333 | 0 | 0 | 0 |

### Module Breakdown

| Module | Suites | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|---:|
| `analyzer` | 30 | 181 | 0 | 0 | 0 |
| `analyzer-maven` | 7 | 67 | 0 | 0 | 0 |
| `analyzer-filesystem` | 2 | 12 | 0 | 0 | 0 |
| `analyzer-javaparser` | 14 | 72 | 0 | 0 | 0 |
| `backend` | 1 | 1 | 0 | 0 | 0 |
| **Total** | **54** | **333** | **0** | **0** | **0** |

- [test-suites.json](test-suites.json): mechanically extracted final Surefire suite names, test counts, module distribution and timings.
- [source-hashes.json](source-hashes.json): SHA-256 digests of all 14 relevant production and test source files at verification time.
- [artifact-hashes.json](artifact-hashes.json): exact SHA-256 digests of the 5 built reactor module JARs.

## Established Boundaries

Tests establish:
- Literal condition extraction for `@Profile` and `@ConditionalOnProperty` with exact source span and character preservation (whitespace, empty values, Unicode escape rejection).
- Closed obligation denominator: every M4A obligation is categorized as `LOWERED`, `OPAQUE`, or `NOT_A_CONDITION`.
- Strong-Kleene 3-valued truth evaluation with absorbing Boolean laws (`FALSE AND UNKNOWN = FALSE`, `TRUE OR UNKNOWN = TRUE`).
- Opaque and bean-state obligations strictly evaluate to `UNKNOWN` and preserve their capability gaps in the reporting denominator.
- Precedence ordering (`last-active-v1`): higher-precedence sources overwrite lower ones; `MISSING` falls through without deleting lower definitions; empty strings are preserved.
- Profile policies (`defaults-includes-groups-v1`): default profiles apply only when no active profile is present; groups and includes expand via monotone finite reachability without infinite recursion.
- Build constant verification: whole-annotation class/resource presence requires matching M3 build context; conflicting proofs deterministically yield `BUILD_EVIDENCE_CONFLICT`.
- Finite space enumeration: small-space Cartesian product traversal with deterministic saturation guards (`maxCartesianAssignments`).

## Handoff

- **Before:** M4B.1 configuration-space foundation delivered (271 tests).
- **Completed:** 7 production classes (lowering, profile parser, literal parser, exogenous evaluator, finite evaluator, assignment model, issue catalog), 3 new test suites (23 tests), and build context integration. Full reactor passes 333 tests.
- **Next:** **Milestone 4 - Slice M4C.1: Phase/Order-Aware Spring Registration Plan and Discovery Transitions**.
