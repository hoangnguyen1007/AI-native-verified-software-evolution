# M4A.2 Mechanism Acquisition Verification — 2026-09-13

## Scope

This record verifies the production M4A.2 evidence-only annotation-graph and remaining Java mechanism-acquisition slice. It does not advance G3 or claim condition evaluation, scan enablement, bean registration effects/order, injection binding, lifecycle execution, truth regions, runtime-container equivalence or representative-repository correctness.

The implementation remains in the neutral `analyzer` contract/provider boundary and consumes only supplied M2 frontend, M3 classpath and decoded resource evidence. The integration fixture compiles a minimal deterministic API-stub JAR in a JUnit temporary directory; it does not run Spring, load target handlers, execute an analyzed repository build/lifecycle, access the network or acquire external evidence.

## TDD and focused checks

The initial annotation-graph specification failed at compile time because M4A.1 had no graph records or `ANNOTATION_GRAPH_INCOMPLETE` reason. A later negative control exposed that callable detection had used declaration spelling rather than the canonical method identity: the project-defined Spring-FQN impostor was omitted instead of retained as an unclassified raw row. Both failures were corrected before the focused suite passed. Final regressions also establish that source-to-source composed annotations are complete, verified meta-edges require exact targets, unresolved parameter type arguments retain a classified `@Bean` injection point plus a typed gap, and identical evidence replays to the same inventory identity.

Focused commands:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd test -pl analyzer -am "-Dtest=SpringMechanismInventoryTest,SpringMechanismM4A2Test,CapabilityGapContractTest" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q

$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd test -pl analyzer-javaparser -am "-Dtest=SpringMechanismM4A2IntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
```

Result: 28 tests, 0 failures, 0 errors, 0 skipped.

| Test class | Tests | Main boundary |
|---|---:|---|
| `CapabilityGapContractTest` | 12 | Gap schema/ledger closure and compatibility |
| `SpringMechanismInventoryTest` | 12 | M4A.1 compatibility, exact scopes, resource/raw closure and M4A.2 gap normalization |
| `SpringMechanismM4A2Test` | 1 | Declaration/meta graph, aliases/defaults, repeatable container, cycle SCC and edge invariant |
| `SpringMechanismM4A2IntegrationTest` | 3 | JavaParser evidence, composed source graph, Java mechanism families, unresolved parameter shape and FQN-impostor negative control |

The first sandboxed Maven attempt could not read the host dependency cache. Verification then used the explicit existing `C:\Users\Admin\.m2\repository` through the supported execution approval. This was an environment-permission failure, not a semantic or test failure.

## Reactor verification

Final byte-matched gate command:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -B -ntp verify "-Dmaven.repo.local=C:\Users\Admin\.m2\repository"
```

Result: `BUILD SUCCESS` in 54.222 seconds at `2026-09-13T12:46:49+07:00`.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `analyzer` | 95 | 0 | 0 | 0 |
| `analyzer-maven` | 67 | 0 | 0 | 0 |
| `analyzer-filesystem` | 12 | 0 | 0 | 0 |
| `analyzer-javaparser` | 70 | 0 | 0 | 0 |
| `backend` | 1 | 0 | 0 | 0 |
| **Total** | **245** | **0** | **0** | **0** |

All six reactor modules passed. The root enforcer passed Maven/Java requirements, dependency and reactor convergence, pinned plugins, release dependencies and upper-bound checks. Existing deprecation notices in the Maven-model and JavaParser adapters remained warnings and did not affect the result.

## SHA-256 evidence

```text
72f1547794fd7733a8672565cb71a7f2b6567934c3f403961de142e7a95d9520  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismInventory.java
9d7a93145d9fb9ee822659aee8a509333884874eb30430d34700046caadf70e7  analyzer/src/main/java/com/evolution/analysis/spring/SpringMechanismScanner.java
7351ef2d7f1eb2a34aca23c4d95aa6e463aaa1b8888065b0c3c43fde454a6b01  analyzer/src/main/java/com/evolution/analysis/spring/SpringCapabilityGapCatalog.java
cf9ddfb57e42d664825e01cdca693ab8656acedf93a4818036afbfb0074b0804  analyzer/src/main/java/com/evolution/analysis/evidence/EvidenceAcquisitionLedger.java
da8c89fe3f870cadab7a8d07a89cb953ea9ba8d174a5d8321e270ea6181d4fc7  analyzer/src/main/java/com/evolution/analysis/evidence/CapabilityGapNormalizer.java
01c169a867f0927a7d600845a712f9ac8c2cbeac32a8acf1f769d00d392f4399  analyzer/src/test/java/com/evolution/analysis/spring/SpringMechanismM4A2Test.java
6a0ef3a8badcf69077db4a30960576025e8a56e0fcead0016cc429d3f14a4af6  analyzer-javaparser/src/test/java/com/evolution/analysis/javaparser/SpringMechanismM4A2IntegrationTest.java
7474ce4c86ca26f771c67452540eb1ebbdce2e79a4d872c791698d378404723e  analyzer/target/analyzer-0.1.0-SNAPSHOT.jar
```

## Result and limits

**CONFIRMED:** `spring.mechanism-scanner:m4a.2` produces `spring-mechanism-inventory-v2`; its raw/marker/obligation denominator closes; annotation declaration, meta-edge and cycle evidence is content-addressed; composed traversal uses exact graph/artifact evidence; the remaining registered Java mechanism candidates are retained without fabricating their runtime effects; and every emitted problem maps through `evidence.spring-mechanism-gaps:m4a.2-v1` and `evidence.gap-normalizer:m4a.2` to the unchanged M3 gap schema.

**LIMITATION:** this bounded checkpoint used compact deterministic fixtures and the full platform reactor, not a standalone M4 benchmark, real Spring container, representative external repository or independent reviewer. Those are not required to complete the M4A.2 detection/provenance slice and cannot be inferred as passed G3 evidence.
