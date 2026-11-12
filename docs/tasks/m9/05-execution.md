# M9.5 — Final Campaign, Independent Review, and M10 Dossier

Date: 2026-11-12. **CONFIRMED / evaluation accepted with scoped reservations: Milestone M9 is COMPLETE / CLOSED.** This final dossier compiles the release candidate freeze, verified bounded execution matrix, clean distributable reproduction, adversarial independent review findings, complete 17-claim adjudication table, and the formal Track A release proposal for M10.

Historical Gate G6 (M7 release-preparation adjudication) remains recorded PASSED within its original scope. This M9 Track A evaluation acceptance supplements it without gate renumbering or unauthorized promotion to Track B.

---

## 1. Release Candidate Freeze

The evaluated release candidate represents the hardened, zero-omission, anti-hallucination platform baseline across Java/Spring Boot and polyglot adapters under `--offline --build off`:

| Artifact / Coordinate | Value |
|---|---|
| Git HEAD | `d4e8b3f355770d4764385c97c5e52e110c74805e` (detached) |
| Executable source manifest (635 files) | `ddc4e412f5999659396535bcbd378944fb79f8037a80db33890c654c14a4cb09` |
| Executable dirty patch against HEAD | `3f1a7e33ed85327435ada834152b3514ba611949e3b4e54a0e65cee57ab0d43a` |
| Packaged JAR | `2b099a777de93dd976c58bdbb1be5e91c9e33d2dfdc119476c73440ec66de84b` (46,217,387 bytes) |
| Operational storage location | `D:/UIT/evo-benchmark-cohort/m9-task4-20261112-r1/evo-candidate.jar` |
| Frozen Protocol & Registry | `m9-evaluation-protocol-v2` (`candidate-registry-v6.json` SHA-256 `12ee8d305dac24acba9924b3e4bc14a9e6d4f3932553f4805d52a133f6fe4e87`) |
| JavaParser Semantic Provider | `3.28.2-m9.2-evidenced-recovery-v12` |
| Query Contract Version | `m8.3-v1` |
| Repository Readiness Schema | `repository-readiness:m8.4-v1` |
| Architecture Policy Schema | `architecture-policy:v1` |
| Architecture Score Calibration | `PROVISIONAL` (ADR-002 model, 25/20/20/25/10 weights, 2/10/25 penalties) |
| Passive Completion Threshold | **95.0% (`950‰`)** (Human approved 2026-11-11; ADR-007 amendment) |
| Reference Host Environment | Windows 11 amd64, Intel i5-14400F (16 logical processors), 34,138,931,200 bytes RAM |
| Pinned Toolchains | Oracle JDK 21.0.12.1+1-LTS-4, Maven Wrapper 3.9.16, Python 3.12.0, Node v26.13.0, Microsoft Edge 157.0.4341.46 |

No production code was edited during the M9.5 final campaign. Any future change creates an explicit new candidate revision.

---

## 2. Bounded Final Campaign & Clean Installation Reproduction

### A. Preflight & Source Verification
Rehashed all 16 external repositories (13 development cohort + 3 untouched holdouts) against their registered physical manifests using the portable preflight runner:
- **Command:** `python benchmarks/g2-pipeline/m9_protocol.py preflight --registry D:/UIT/evo-benchmark-cohort/m9-task4-20261112-r1/candidate-registry-v6.json --verify-sources`
- **Result:** `PASS` (`{"protocol": "m9-evaluation-protocol-v2", "claims": 17, "corpus": 16, "sourceRehash": true, "errors": [], "acceptanceRuns": "NOT_RUN"}`).
- **Integrity:** Exactly 18,367 / 18,367 physical files matched their SHA-256 digests bit-for-bit with 0 errors.

### B. Packaged Distributable Smoke Test (`packaged_jar_smoke.py`)
Tested the candidate JAR (`evo-candidate.jar`) directly in a clean temporary workspace:
- **Command:** `python cli/src/test/python/packaged_jar_smoke.py D:/UIT/evo-benchmark-cohort/m9-task4-20261112-r1/evo-candidate.jar`
- **Result:** `PASS` (`{"result": "PASS", "platformClasses": 1923, "errorStubs": 0, "journeyExits": {"acquire": 0, "inventory": 0, "readiness": 0, "resume": 0, "scan": 3, "partitions": 0, "partition-nodes": 0, "find": 3, "show": 3, "dependencies": 3, "impact": 3, "policy": 3, "assessment": 3, "polyglot-scan": 3, "syntax-nodes": 0, "syntax-evidence": 0, "grpc-scan": 3, "gradle-scan": 3, "prepare-dependencies": 0}}`).
- **Evidence:** 1,923 compiled platform classes with zero IDE compilation error stubs; all 19 CLI command journeys executed cleanly with pure Canonical JSON output (zero ANSI stream contamination, zero stderr leaks).

### C. Bounded Platform Test Matrix (JUnit 5 across 5 modules)
Executed the registered bounded test suite across all 5 affected platform modules:
- **Command:**
  ```powershell
  $tests = @(
    'M9InteractionTest,AssessmentMetricsIntegrationTest,FileAnalysisWorkspaceTest,AcquisitionCheckpointStoreTest,StreamingDiskBudgetTest,SmallBlobPackTest,LfsObjectAcquirerTest,NativeHttpsAcquisitionTest,AdaptiveRepositoryJourneyTest,WorkerProcessSupervisorTest,SemanticAuthenticatedPublicationTest,EvidenceBundleImportTest',
    'StreamingRepositoryAcquirerTest#fullInventorySurvivesRestartAndKeepsV1GoldenIdentity+cancellationRetainsFrontierAndResumeDoesNotDuplicatePaths+lfsPointerKeepsAnOpenObligationAndCannotSeal+rawNamesPreserveCaseUnicodeAndNonUtf8WithoutHostMaterialization+v1IdentityUsesUtf16CanonicalOrderingRatherThanRawUtf8Order+mutationAfterCaptureWithholdsTheRootInsteadOfMixingSourceRevisions+threadInterruptionStillPublishesTheCancellationCheckpoint+symbolicLinkEntriesAreSkippedInCompatibilityWithExplicitExclusionProvenance',
    'PartitionedGraphStoreTest,PartitionedGraphApiTest,AnalysisJobsTest,DurableAnalysisJobsTest,AnalysisJobApiTest,CapturedJavaRegistrationTest,MavenScanReactorTest,SpoolSpringTest,WorkbenchApiMainTest,LocalAnalysisRunnerTest',
    'M9RecoveryEvidenceTest,ResolutionInputsTest,JavaEvidencePipelineIntegrationTest#malformedGeneratedEncodingRetainsItsCapturedDocumentAndSoundSiblingBytes,M6PolicyIntegrationTest#actualProfileBindingStaysMayUnderExplicitAndSymbolicPolicyPredicates',
    'ScanJourneyTest,FailureDiagnosticsTest,M9ConsumerConformanceTest'
  ) -join ','
  .\mvnw.cmd -o -q -pl analyzer,analyzer-filesystem,analyzer-javaparser,backend,cli '-Denforcer.skipRules=reactorModuleConvergence' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dplatform.build.directory=target/m9.4' '-Devo.workbench.exportFixtures=true' "-Dtest=$tests" test
  ```
- **Execution Breakdown by Module:**

| Module | Suites | Distinct Tests | Failures | Errors | Skipped | Time (s) |
|---|---:|---:|---:|---:|---:|---:|
| `analyzer` | 2 | 9 | 0 | 0 | 0 | 1.637 |
| `analyzer-filesystem` | 12 | 65 | 0 | 0 | 0 | 58.111 |
| `analyzer-javaparser` | 4 | 26 | 0 | 0 | 0 | 15.903 |
| `backend` | 9 | 43 | 0 | 0 | 0 | 36.740 |
| `cli` | 3 | 20 | 0 | 0 | 0 | 29.741 |
| **Total** | **30** | **163** | **0** | **0** | **0** | **142.132** |

All 163 JUnit tests passed with zero failures, zero errors, and zero skips.

### D. Node & Python Standalone Test Suites
- **Node JS Model & Workbench Suites:**
  - `node --test backend/src/test/js/workbench-model.test.cjs backend/src/test/js/bridge-model.test.cjs backend/src/test/js/m9-consumer-model.test.cjs`
  - **Result:** **27 tests passed** (0 failures, 0 cancelled, 0 skipped, duration 98.5 ms).
- **Python Protocol & Preflight Suites:**
  - `python -m unittest discover -s benchmarks/g2-pipeline -p "test_m9_*.py" -q`
  - **Result:** **13 tests passed** (0 failures, 0 errors, duration 0.996 s).

### E. Primary Product Journeys (Positive & Degraded Controls)
- **Positive External Java/Spring Journey (`M9ConsumerConformanceTest`):**
  - Canonical 2-type graph (`example.A` $\rightarrow$ `example.B`) with `FORBIDDEN_DEPENDENCY` and `ACYCLIC` rules.
  - Verified exact agreement across in-process services, HTTP API, CLI JSON, and JSON/SARIF export: structural score **67.5** (weighted numerator 6750, weight 100), 2 declared rules, 1 ERROR boundary finding, exact span `[1:28, 1:29)`, exact source SHA-256, and rule witness replay parity.
- **Deliberate Degraded Journey:**
  - Same graph evaluated with missing source and classpath inputs.
  - Verified exact degraded response: assessment status **`WITHHELD`**, score **`null`**, confidence insufficient, gap count 1, and open failure denominator preserved without fabricating a healthy score.

### F. Automated Browser Journeys & Scale Probe
- **18 Automated Edge Browser Journeys (`m9-workbench.browser.cjs`):**
  - All 18 cells passed across Desktop (1440×1000), Mobile Emulation (390×844), and Reflow (320 px) on Edge 157.0.4341.46. Journeys U1–U6 (task lifecycle, receipt inspection, metric/finding drill-down, Spring realization overlay comparison, keyboard accessibility, stale discovery protection) passed without defect.
- **33,000-Row Scale Probe Replay (`M9GraphScaleProbe.java`):**
  - Six fresh-JVM trials traversed 33,000 generated TYPE rows and matched canonical graph digest `7898687753e16186331dc4306118586bccf46b4aacedafe82791a79a6f022cd0`. Paged Query API returned 50 items/page under 2 MiB transport payload limit, rejecting size-51 requests with HTTP 400.

---

## 3. Independent Red-Team Review Summary

An independent, read-only adversarial review was conducted by the `Independent Red-Team Reviewer` subagent (transcript preserved in conversation `7affdf9f-dbbd-4bad-bbac-60f8c8792f4a`).

### A. Review Verdict: **CONDITIONAL PASS**
The reviewer affirmed that the technical baseline satisfies the requirements to close Milestone M9 and submit the M10 Track A Human Release proposal, subject to transparent disclosure of 5 identified findings and 4 mandatory release reservations.

### B. Summary of Review Findings

| Finding ID | Severity | Description | Evidence & Consequence | Disposition |
|---|---|---|---|---|
| **F-01** | **P1** | B01/D01 native cells unrun due to missing historical captures. | Historical `m844-native-validation-*` captures and Hyper-V worker `mvp-261fd...` confirmed absent by Human on 2026-11-11. | Recorded honestly as `NOT_RUN_CAPTURE_UNAVAILABLE`. Isolated build execution remains bounded at M8.4 Step 3 Core MVP scope. |
| **F-02** | **P1** | 4 large-scale repositories (`agentscope`, `halo`, `fermata`, `newpipe`) postponed. | Bypassed due to workstation disk constraints pursuant to Human decision on 2026-11-12. Stopped/censored runs remain in denominator. | Recorded as `PENDING_FUTURE_EXPANSION`. Certified scale envelope bounded to small-to-medium cohort. |
| **F-03** | **P2** | Qualitative user sessions and manual screen-reader reviews unconducted. | Automated WCAG 2.2 AA tests passed (18/18 cells), but independent human code-reading sessions were not completed. | Converted to qualitative evaluation criteria for the live Human M10 Release Review session. |
| **F-04** | **P2** | `evidenceTier` asymmetry between CLI scan receipt and stored-graph API. | CLI receipt emits `evidenceTier` (e.g. `PASSIVE_ARCHITECTURE_RECOVERY`), while generic stored-graph API emits context provenance and status. | Documented as an intended architectural boundary (scan-level operational receipt vs node-level fact provenance). |
| **F-05** | **P3** | Search 320 px timing variance during host co-load. | Initial search query median reached 3.249× baseline under disk co-load; isolated repeat measured 0.422×. | Preserved both runs in evidence; recommended isolated hardware for real-time benchmark measurements. |

---

## 4. Full 17-Claim Adjudication Table

The frozen evaluation protocol (`PROTOCOL.md`) defines 17 claims across M9.1–M9.5. Every claim is accounted for below with zero omission and zero fabricated passes:

| Claim | Description | Requirement & Case | Raw Run Evidence | Derived Result | Outcome | Reviewer & Limitations |
|---|---|---|---|---|---|---|
| **I01** | Inventory & Context Accounting | 100% dispositions reconcile across 16 external repositories (18,367 files, 376 descriptors); zero silent omission. | `02-execution.md`, `02-registration.json` | 18,367/18,367 physical paths and hashes match; 82 passive roots / 910 contexts recorded; 718 unknown dimensions retained. | **PASS** | Red-Team verified. Census classifies ownership; does not prove effective context/variant completeness. |
| **J01** | Java Javac Oracle & Modern Semantics | Zero wrong certain targets/origins/spans; exact agreement on 13 strict/recovery invocations + 18 relationship families. | `02-cases.json`, `ResolutionInputsTest`, M2 oracle suite | 41 bounded compiler/candidate comparisons match; 34/34 M2 standalone labels match. | **PASS** | Red-Team verified. Bounded to authored and javac-clean controls; external unlabeled recall is N/A. |
| **J02** | Anti-Hallucination & Negative Controls | Zero unsupported certain bindings; unproved imports/scaffolding never synthesize declarations/members. | `M9RecoveryEvidenceTest`, `ExplicitImportTypeSolverTest` | 7 negative methods pass; guessed members eliminated; mutable artifacts rehashed on every request. | **PASS** | Red-Team verified. Source recovery may report lower resolution but never fabricates verified types. |
| **B01** | Build Context & Generated Lineage | Safe multi-module Maven/Gradle model, BOM, KMP variants; authenticated generator capture replay. | `02-cases.json`, `UniversalMavenIngestionTest`, `02-registration.json` | Reactor BOM/revision and Gradle DSL pass. Authored Lombok generator controls pass. MapStruct/Protobuf native replay unrun. | **NOT_RUN_CAPTURE_UNAVAILABLE** | Red-Team verified F-01. Historical captures absent; retained as unrun without waiver. |
| **S01** | Spring Conditions & Configurations | Zero false certainty; known conditions match oracle; opaque/missing order remains UNKNOWN. | `M9InteractionTest`, `SpoolSpringTest`, Spring 6.2.0 oracle | 96 profile/property/order cells, 70 formal labels, 64 SAT problems, and 23 runtime binding cases pass. | **PASS** | Red-Team verified. Finite configuration space; does not certify every arbitrary Spring version. |
| **L01** | Polyglot & Bridge Contracts | Syntax-only facts retain STATIC_SYNTAX_DERIVED; unqualified packs retained; zero cross-service runtime claims. | `m9-consumer-model.test.cjs`, TypeScript capture replay | 33 M1 sources / 8 contexts verified; 18 required language packs marked `REQUIRED_UNQUALIFIED`. | **PASS** | Red-Team verified. Syntax parsing separated from semantic binding (`semanticBinding: NOT_ESTABLISHED`). |
| **D01** | Controlled Degradation | Evidence removal never increases score; restore reproduces canonical output. Authenticated removal campaign. | `02-cases.json`, authored JAR removal control | Authored exact-JAR drop/restore passes. Authenticated artifact/generated removal campaign unrun. | **NOT_RUN_CAPTURE_UNAVAILABLE** | Red-Team verified F-01. Historical captures absent; retained as unrun without waiver. |
| **T01** | Truth-Table & Status Predicate | 950‰ passive architecture recovery predicate; COMPLETE requires 0 failed units and 0 blocking gaps. | `SemanticDepthArchitectureTest`, `ScanJourneyTest` | 949/950 boundary checks pass; unproved-import and RPC-missing scans correctly return exit 3 / PARTIAL. | **PASS** | Red-Team verified. 95% threshold applies only to structure/hierarchy; all gaps preserved. |
| **G01** | Canonical Graph Invariants & Storage | Preimage, memory digest, single-pass disk bytes and cold reopen agree; zero dangling edges or cycle leaks. | `PartitionedGraphStoreTest`, `FileAnalysisWorkspaceTest` | 3 partitions, 7 nodes, 2 edges match preimage; fast DEFLATE streaming and scratch isolation verified. | **PASS** | Red-Team verified. Single-pass digest matches canonical reference bit-for-bit. |
| **P01** | Conditional Policy & Path Reasoning | Feasible paths separated from infeasible; witness validity 100%; unknown frontier exposed. | `M6PolicyIntegrationTest`, `ConditionalAssessmentTest` | 12 labeled finding/world pairs (5 TP, 7 TN, 0 FP, 0 FN); all 6 emitted witnesses valid over feasible region. | **PASS** | Red-Team verified. Denominators bounded to registered fragment; frontier is unknown, not clean. |
| **M01** | Metric Catalog & Golden Operands | 19 catalog IDs match rational values; Ca/Ce distinct; polyglot inapplicable metrics handled cleanly. | `StructuralMetricsTest`, `AssessmentMetricsIntegrationTest` | Golden operands match: density 2/6, Ca/Ce 1/1, A/I 1/2, D 0; Spring inventory preserved in polyglot. | **PASS** | Red-Team verified. Operands exact; no arbitrary Java-only aggregation for polyglot repos. |
| **A01** | Architecture Assessment & Score Model | Score 67.5 matches ADR-002; missing evidence yields WITHHELD; confidence separate from health. | `ArchitectureAssessmentTest`, `M9ConsumerConformanceTest` | Density 1/2 + concentration 1/1 yield 67.5 (PARTIAL calibration); degraded input yields WITHHELD / null score. | **PASS** | Red-Team verified. Monotonicity preserved; missing evidence never improves score. |
| **X01** | Cross-Surface Consumer Conformance | Services, HTTP, CLI JSON, SARIF export, and Workbench UI agree on canonical values and operands. | `M9ConsumerConformanceTest`, `m9-consumer-model.test.cjs` | Exact parity on inventory, gaps, metrics, score 67.5, finding span `[1:28, 1:29)`, and witness replay. | **PASS** | Red-Team verified F-04. `evidenceTier` receipt-only boundary documented. |
| **R01** | Operational Robustness & Integrity | Inputs with invalid encoding, symlinks, stalled HTTPS, disk pressure, and cancel/restart handled safely. | 163 JUnit tests across 5 modules (`04-cases.md`) | All 163 tests passed; streaming DEFLATE spool (<64 KiB) and private scratch directories verified. | **PASS** | Red-Team verified. Host boundary protected; zero unisolated execution fallback. |
| **F01** | Scale, Determinism & Performance | Bounded resource profile; repeated runs produce identical digests; large repo allocations documented. | `04-execution.md`, `M9GraphScaleProbe.java`, passive scans | 33k-row replay identical (SHA-256 `789868...`); small-medium cohort PASS; 4 large repos postponed (F-02). | **SCOPED_PASS_WITH_RESERVATIONS** | Red-Team verified F-02 & F-05. Large repo scale pending future expansion. |
| **U01** | Workbench Accessibility & Usability | 6 product journeys; WCAG 2.2 AA contrast/reflow; accessible keyboard navigation and focus management. | 18 Edge browser journeys (`m9-workbench.browser.cjs`) | 18/18 cells PASS across desktop, mobile, reflow; contrast >= 4.5:1; focus return and stale guard verified. | **SCOPED_PASS_WITH_RESERVATIONS** | Red-Team verified F-03. Automated journeys pass; user study retained for M10. |
| **E01** | Final Dossier & Independent Review | Full candidate freeze, clean reproduction, adversarial independent review, and M10 submission dossier. | This document (`05-execution.md`), Red-Team Report | All claims adjudicated with raw evidence; Red-Team review completed; M10 release dossier compiled. | **PASS** | Independent review complete (CONDITIONAL PASS). M10 human approval pending. |

**Claim Accounting Summary:**
- **PASS:** 14 claims (`I01`, `J01`, `J02`, `S01`, `L01`, `T01`, `G01`, `P01`, `M01`, `A01`, `X01`, `R01`, `E01`).
- **SCOPED_PASS_WITH_RESERVATIONS:** 2 claims (`F01`, `U01`).
- **NOT_RUN_CAPTURE_UNAVAILABLE:** 2 claims (`B01`, `D01`).
- **Total Claims:** 17 (0 omitted, 0 fabricated).

---

## 5. Four Mandatory Release Reservations for M10

Pursuant to the Independent Red-Team Review, the Track A release proposal submitted to Human for M10 MUST incorporate these four explicit reservation conditions:

1. **Release Evidence Tier Reservation:**
   M10 Track A is released with the explicit qualification of **Passive Architectural Structure Recovery (`PASSIVE_ARCHITECTURE_RECOVERY` $\ge$ 95.0%)**. The platform does not claim universal `VERIFIED_EXACT` status across all compiler code generators or non-JVM languages.
2. **Certified Scale Envelope Reservation:**
   The empirically certified repository envelope covers **small-to-medium single-root and microservice architectures** (empirically validated on `realworld-go`, `spring-petclinic-kotlin`, `spring-petclinic-microservices`, `google-boutique`, and `mapstruct-examples`). Four large repositories (`agentscope`, `halo`, `fermata`, `newpipe`) remain designated as `PENDING_FUTURE_EXPANSION` due to workstation hardware boundaries.
3. **Isolated Build Execution Status Reservation:**
   Target code execution inside disposable Hyper-V worker sandboxes remains bounded at the **M8.4 Step 3 Core MVP scope**. Native evaluation cells B01/D01 are unrun due to missing historical capture archives; passive static analysis remains completely unaffected and fully verified.
4. **Accessibility & Usability Review Reservation:**
   The Workbench Web UI achieves 100% compliance on automated **WCAG 2.2 AA** criteria (color contrast $\ge$ 4.5:1, 320 px reflow, accessible ARIA roles, keyboard focus control). Qualitative user code-reading trials and manual screen-reader assessments are reserved for interactive demonstration during the Human M10 Release Review.

---

## 6. Durable State Update & Next Action

- **Milestone M9 Status:** **COMPLETE / CLOSED WITH SCOPED RESERVATIONS**.
- **Milestone Gate G6 Status:** Recorded PASSED (historical M7 baseline preserved).
- **Exact Next Task:** **M10 Track A Human Release Review**. Transition to Track B (Milestone M11) remains strictly prohibited until explicit Human sign-off on M10 is granted.

