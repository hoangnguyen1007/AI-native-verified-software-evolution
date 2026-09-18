# M4D Truth Regions and Revalidated Witnesses — Verification Record

Date: 2026-09-18. Scope: complete M4D production boundary for the normalized M4C fragment.

## Result

**CONFIRMED by fresh execution:** the focused M4D suite passes 15 tests. The final six-module reactor
passes 469 tests across 62 suites with zero failures, errors or skips. Maven Enforcer remained active
for the final reactor verification.

This establishes implementation and regression behavior for the authored bounded controls. It does
not establish representative-repository accuracy, historical-framework coverage, runtime-container
equivalence or Gate G3. No external repository benchmark or independent review was performed; those
belong to M4E.

## Post-delivery audit disposition

| Finding | Decision and verified disposition |
|---|---|
| REGION_LIMIT versus replay | Accepted. Exhausted/error/not-evaluated worlds remain explicit but are no longer semantic-witness candidates; false replay failures are eliminated. |
| False certainty in implication/equivalence | Accepted. `FALSE` now requires an observed opposing value for the same world; unobserved incomplete residue yields `UNKNOWN`. |
| Reason-code test deficit | Accepted as a coverage deficit, not evidence that every untested branch was defective. The focused suite now directly asserts all ten M4D reason codes, including an adversarial replay rejection. |
| Reallocated dependency map | Accepted. Descriptor lookup is built once per evaluation and reused in the world/fact inner loop. |
| Context-mismatch witnesses | Accepted and specified. Invalid top-level contexts emit no semantic witnesses or replay records. |

Replay hardening goes beyond the proposed remediation: a candidate that fails independent replay is
retained as a diagnostic replay plus `WITNESS_REPLAY_FAILED`, but is not published in the revalidated
witness collection.

## Commands actually run

TDD red check after adding the specification test:

```powershell
.\mvnw.cmd test -pl analyzer '-Dtest=TruthRegionEvaluationTest' '-Denforcer.skip=true' '-Dsurefire.failIfNoSpecifiedTests=false' -q
```

Expected result: test compilation failed because the new `spring.truth` production API did not yet
exist. An earlier unquoted/unenforced invocation failed at reactor-module convergence and was classified
as build invocation setup, not the behavioral red result.

Post-delivery audit regression check used the same focused command. Before the correction it executed
14 tests and failed three independently specified behaviors: incomplete-region implication returned
false certainty, region exhaustion emitted false replay failures, and operational-error worlds were
selected as witnesses. This was the behavioral red result for the audit hardening.

Focused final verification:

```powershell
.\mvnw.cmd test -pl analyzer '-Dtest=TruthRegionEvaluationTest' '-Denforcer.skip=true' '-Dsurefire.failIfNoSpecifiedTests=false' -q
```

Result: 15 tests, 0 failures, 0 errors, 0 skipped. Surefire-reported suite time: 2.102 s in
the final reactor report; focused invocations exited 0.

One-shot final reactor verification:

```powershell
.\mvnw.cmd -B -ntp verify -q
```

Result: exit code 0. Aggregation of the fresh `TEST-*.xml` reports under `analyzer`, `analyzer-maven`,
`analyzer-filesystem`, `analyzer-javaparser` and `backend`: 62 suites, 469 tests, 0 failures,
0 errors, 0 skipped.

Toolchain check:

```powershell
.\mvnw.cmd --version
```

- Apache Maven 3.9.16
- Oracle JDK 21.0.12.1
- Windows 10 / amd64
- UTF-8 platform encoding; `en_US` default locale

## Tested behavior

The focused suite independently specifies:

- `MUST`, `MAY`, `NEVER` and `UNKNOWN` classification;
- disjoint/exhaustive true, false and unknown regions over known feasible worlds;
- definition-presence, injection-candidate and selected-binding fact families;
- unknown binding evidence retained separately from a definite inactive-world false result;
- empty modeled space as `INVALID_MODEL`, never vacuous certainty;
- invalid baselines, incomplete enumeration and foreign build contexts withholding universal conclusions;
- direct typed-gap assertions for all ten M4D reasons;
- explicit fact/region/witness limits with closed counts and no synthetic unknown witness;
- operational failures retained but excluded from semantic-witness selection;
- adversarial reasoner output rejected by independent replay rather than published as a witness;
- owner-qualified binding fact identity;
- minimum-cardinality baseline deltas and independently successful witness replay;
- deterministic canonical result bytes and replay identities;
- result identity sensitivity to limits and evaluator artifact digest; and
- exhaustive reasoner satisfiability, witness, implication and equivalence behavior, including
  observed counterexamples and incomplete/unobserved `UNKNOWN`.

## Content hashes

SHA-256 over final file bytes before this record was created:

| Artifact | SHA-256 |
|---|---|
| `analyzer/src/main/java/com/evolution/analysis/spring/truth/TruthIdentity.java` | `4621a230284ea33a30145c50d6060f9c6ffd32b3c8f14f5301ce485c3ac4ae6c` |
| `analyzer/src/main/java/com/evolution/analysis/spring/truth/ConfigurationReasoner.java` | `1a2f4408bd0be1eaadc943c2dce5b205402fd39a2fc7afe97812272f18a73a72` |
| `analyzer/src/main/java/com/evolution/analysis/spring/truth/ExhaustiveConfigurationReasoner.java` | `b5b433ccb7297e34c3533d86e1fe0e3f86a474abb35f17ca35a8e948506808eb` |
| `analyzer/src/main/java/com/evolution/analysis/spring/truth/ConditionalFactKey.java` | `4265a4cf83e46b1e86333d8d2f9557045d5bd71412df3b25bfbbe9eb862f07c1` |
| `analyzer/src/main/java/com/evolution/analysis/spring/truth/TruthProcessing.java` | `7839239dbfde06033403aca54fac2573389f0e219af798773accde8daaeb5706` |
| `analyzer/src/main/java/com/evolution/analysis/spring/truth/TruthRegionEvaluation.java` | `ec342b1696e50c8c3681d5d73ce728f5afd85198bb633f627dc211bc0ed027a7` |
| `analyzer/src/test/java/com/evolution/analysis/spring/condition/TruthRegionEvaluationTest.java` | `15e26db6f7dd27ba82a2133e8ddf5288006df8c422b6369b796cc92348da97b2` |
| `docs/architecture/m4d-truth-regions-witnesses.md` | `54871f467f1df0b0614bf2ec0ba94368140803e3d84f150d565f134218b00adc` |

## Boundaries retained

- Framework 6.2.0 / Boot 3.4.0 is the normalized M4C registration/binding fragment.
- Historical tuples, parent contexts, factory/proxy/runtime products, arbitrary resolvable dependencies,
  custom processors/resolvers and automatic source-to-complete-descriptor acquisition remain explicit
  provider/version gaps.
- Config Data loading, sound `OTHER` verification, partial-order realization and correlated opaque
  branching remain outside this M4D implementation boundary.
- Exhaustive enumeration is the delivered correctness reference; no SAT/BDD backend is selected.
- M4E must execute registered fixtures/baselines/representative repositories and obtain human G3
  adjudication before downstream stable-claim promotion.

## Exact next task

Execute M4E validation under the accepted protocol: freeze permitted fixtures/corpus/baselines, include
all failed/unknown/unsupported cases in denominators, measure false certainty and false unconditional
warnings, validate witness replay/minimality across pinned framework tuples, and seek human G3 review.
