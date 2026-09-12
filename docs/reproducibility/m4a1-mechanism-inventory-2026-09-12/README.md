# M4A.1 Mechanism Inventory Verification — 2026-09-12

## Scope

This record verifies the production M4A.1 evidence-only mechanism inventory and `CapabilityGapRecord` normalization slice. It does not advance G3 or claim Spring activation, registration, binding, truth-region, runtime-container or representative-repository correctness.

The implementation is confined to the neutral `analyzer` module. It consumes supplied M2/M3/resource evidence and performs no target build, class loading, network access, namespace-handler invocation or application execution.

## TDD and focused checks

The regression tests first failed on all three newly identified boundaries: metadata continuation was counted as two rows, a project-defined Spring FQN crossed the catalog boundary, and a classpath without a manifest identity certified a version. The production changes then made the same tests pass.

Focused command:

```powershell
$env:MAVEN_OPTS='-Duser.home=C:\Users\Admin'
.\mvnw.cmd test -pl analyzer -am "-Dtest=SpringMechanismInventoryTest,CapabilityGapContractTest" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
```

Result: 24 tests, 0 failures, 0 errors, 0 skipped.

- `SpringMechanismInventoryTest`: 12 tests.
- `CapabilityGapContractTest`: 12 tests.

The M4A.1 tests cover the exact 29-family catalog, all three accepted artifact-lock tuples, deterministic replay, exact dependency-scope binding, a project-FQN negative control, missing manifest/version evidence, annotation owner-role classification, closed legacy XML counts, unknown namespaces, malformed XML, external identifiers, non-expansion of internal DTD entities, exact resource digest/decoding provenance, `spring.factories` continuation rows, unknown metadata keys, context validation and normalization of both unclassified and version gaps.

## Reactor verification

Final one-shot command:

```powershell
$env:MAVEN_OPTS='-Duser.home=C:\Users\Admin'
.\mvnw.cmd -B -ntp verify "-Dmaven.repo.local=C:\Users\Admin\.m2\repository"
```

Result: `BUILD SUCCESS` in 52.703 seconds at `2026-09-12T11:40:08+07:00`.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `analyzer` | 94 | 0 | 0 | 0 |
| `analyzer-maven` | 67 | 0 | 0 | 0 |
| `analyzer-filesystem` | 12 | 0 | 0 | 0 |
| `analyzer-javaparser` | 67 | 0 | 0 | 0 |
| `backend` | 1 | 0 | 0 | 0 |
| **Total** | **241** | **0** | **0** | **0** |

The root enforcer passed Maven, Java 21, dependency convergence, reactor convergence, pinned plugin, release-dependency and upper-bound rules. Existing deprecation notices in Maven-model and JavaParser adapter code remained warnings and did not affect the result.

## SHA-256 evidence

```text
db3b05ae9e98521867cab025937e0598f457689125adfc1fa79cc94df7d4ba73  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismCatalog.java
edde63a8e57354aa90c3dea159adb9b5340aed59fb3e9b80f98d70cceb0ec9de  analyzer/src/main/java/com/evolution/analysis/spring/SpringFrameworkEvidence.java
ddac166f9d2a8f335f0127603fce5c408a0785806db13899eaa763793e0deea6  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismInventory.java
1d53092495a7c4ed5d7ebea6e5e97493235d0876a2d0148b25e5bed1f2e90d04  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismScanRequest.java
159cf006d5bba657658dca7465e0714f20781210118e419c3956115497e34231  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismScanner.java
479f05285d5d7f4311d2feaf10319e8c3e0bb7c5d713b481de84157125130cad  analyzer/src/main/java/com/evolution/analysis/spring/SpringCapabilityGapCatalog.java
fca007c7dbb17d6c7b035ba4057f12403521a0ffa6a6a8e7ac8835b1fe96f497  analyzer/src/main/java/com/evolution/analysis/evidence/CapabilityGapRecord.java
740fc425fc81cc77c2789b7786a8193f0a13d76848175274fc87a5c19275958e  analyzer/src/main/java/com/evolution/analysis/evidence/EvidenceNormalizationInput.java
da892b4af3ff7e090a7b13280dfa2192dfdf15301ee135c4b5f1b9355e9edd2d  analyzer/src/main/java/com/evolution/analysis/evidence/EvidenceAcquisitionLedger.java
52fc9b21c0674d3cdf82906a75155bd4e5bb9c62738e5f123e21a86293b0502d  analyzer/src/main/java/com/evolution/analysis/evidence/CapabilityGapNormalizer.java
97a7d8a13cfca19d3ebe349dcd60546bef9e035b0a95e40e81e1c14afefbd2c8  analyzer/src/test/java/com/evolution/analysis/spring/SpringMechanismInventoryTest.java
66b66dc009e32ffba01b68eb75dbf0e92909effeb30effa4c8928d66d60c9ab4  analyzer/target/analyzer-0.1.0-SNAPSHOT.jar
```

## Result and limits

**CONFIRMED:** M4A.1 closes its raw-observation/semantic-obligation accounting for supplied annotation, bounded XML and supported metadata inputs; exact source, decoding, classpath and artifact provenance participates in deterministic identities; unclassified annotation/resource cases and unvalidated framework fragments normalize through the unchanged M3 gap schema using the accepted M4A.1 reason subset.

**LIMITATION:** remaining M4A mechanisms that require declaration/meta-annotation graphs or callable/type evidence are the next slice. No standalone M4 benchmark, real-repository run or independent reviewer was used for this bounded implementation checkpoint. Git status/diff/log checks were explicitly omitted at the human request; verification used direct file inspection, hashes, focused tests and the full reactor instead.
