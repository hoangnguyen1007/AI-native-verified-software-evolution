# M9.4 — Operational hardening, scale and workbench

Work began on detached HEAD d4e8b3f355770d4764385c97c5e52e110c74805e with the M9.3 changes in the working tree. The [frozen protocol](PROTOCOL.md), [controls](04-cases.md) and [M9.3 candidate record](03-execution.md) define the starting scope. The M9.2 [native-cell disposition](02-execution.md#remaining-boundary-and-exact-next-action) remains an input to integrated acceptance.

## Runtime changes and direct evidence

- A delayed report response could clear the context after the same job was refreshed. The registered red browser control held the last report response and observed a selected analysis context become empty. Discovery now uses the selected job generation, cancellation and a local report list before updating the workbench. Polling and cancellation also respect the job revision.
- The mobile sidebar now synchronizes expansion and returns focus when it closes. The compact navigation links have accessible names. The workbench labels unavailable evidence tier explicitly and uses captured Spring provenance to label its configuration context. The prior light primary action was 3.77:1 for normal text. Light-theme action and status text use higher-contrast colors. [WCAG 2.2](https://www.w3.org/TR/WCAG22/)
- The Task 1 AgentScope reference crossed its 8-GiB scratch allocation. At stop, the measurement found a 7,879,915,946-byte analysis spool file. The workspace now streams each neutral row through fast DEFLATE, authenticates its stored bytes before decoding, and reuses the compression state inside the synchronized workspace. Aliases share authenticated row bytes. The regression checks exact Java code units and verifies that 32 repetitive rows fit within the authored 64-KiB spool budget.
- A second scratch workspace previously removed a caller-owned file in an analysis-* sibling directory. The constructor now creates its own private scratch directory. Normal close removes that directory. Crash leftovers remain available for diagnosis and explicit owner cleanup.

Both spool regressions failed against the prior source and pass after the fix. The red XML is retained as spool-red.xml under the external evidence root. The packaged workbench resources and FileAnalysisWorkspace class match the final module outputs byte-for-byte.

## R01 selected controls

| Module | JUnit cases | Suites |
|---|---:|---:|
| analyzer | 9 | 2 |
| analyzer-filesystem | 65 | 12 |
| analyzer-javaparser | 26 | 4 |
| backend | 43 | 9 |
| cli | 20 | 3 |
| Total | 163 | 30 |

The selected run had zero failures, errors and skips. It exercised source accounting and resume, a stalled local TLS transfer, local HTTPS retry with LFS/submodule closure, authentication refusal, malformed encoding, typed missing/corrupt/revoked evidence, ordered duplicate classpaths and same-size/mtime replacement, disk pressure, worker fencing, schedule determinism, canonical and canceled publication, durable job restart, API bounds and diagnostic redaction. The stalled-transfer assertion allows either the native curl guard or the controller idle lease to terminate the owned process. The first package selection stopped with zero tests in the upstream analyzer module. The corrected selection added the relevant conditional-oracle and assessment suites.

Additional checks passed 27 Node workbench/bridge model cases and 13 Python protocol/launcher cases.

Surefire suite durations totaled 1.220 s in analyzer, 56.318 s in filesystem, 11.942 s in JavaParser, 31.715 s in backend and 27.024 s in CLI. The two-test red suite body took 0.315 s. Maven startup and compilation exceeded the five-second inner-loop target.

## U01 automated browser results

All 18 cells passed across desktop 1440×1000, mobile emulation 390×844 and 320-px reflow. The browser drove the packaged loopback workbench through cancel, retry and history, receipt/gaps, finding path and exact source evidence/export, keyboard navigation and focused projection, impact, stale response and degraded retry. The symbolic Spring fixture exercised realized and unknown views and matched the captured formula witness. The test asserts separate formula and semantic replay flags.

The 15 U1/U2/U3/U5/U6 rows come from browser-candidate-r1/journeys.jsonl. U4 initially failed due to the test's scope selector, then because the driver had skipped the Compare views action. The scoped rerun in browser-u4-r3/journeys.jsonl passed all three U4 cells on the same packaged runtime. The original failures remain under the first two output directories. Browser accessibility-tree checks verified the tab and navigation names. The keyboard, reflow and measured text-contrast checks cover the scripted controls. Manual assistive-technology testing and the three independent user sessions remain pending.

## F01 33,000-row replay

All six fresh-JVM trials traversed the registered store pages and matched graph SHA-256 7898687753e16186331dc4306118586bccf46b4aacedafe82791a79a6f022cd0 and the same authenticated partition root. Cold publication plus paging took 7.815–9.413 s. Warm reopen/paging took 2.858–4.137 s. The runs used the existing one-context, zero-edge generator. Other processes shared the host and two of the warm intervals exceeded 100 ms between working-set samples. These observations establish replay and page traversal. Comparative scale performance remains a separate cell.

The packaged raw route returned the 33,000-row partition and two pages of 50, with 100 distinct IDs and a continuation on both pages. Each node page used 15,531 bytes. A size-51 request returned HTTP 400 / INVALID_REQUEST. The route retained the raw-fact trust label. The first probe hit a transient Windows lock on the Edge port file during browser startup. The updated driver handled that condition and retained the result under graph-33k-http-r2/result.json.

## F01 passive repository observations

| Registered repository | Task 1 reference | M9.4 candidate |
|---|---|---|
| realworld-go | COMPLETE, 17.381 s | 6 COMPLETE, 19.611–26.077 s |
| petclinic-kotlin | COMPLETE, 30.645 s | 6 PARTIAL, 40.296–48.458 s |
| boutique | PARTIAL, 88.698 s | 6 PARTIAL, 89.882–134.839 s |
| agentscope | Right-censored at scratch allocation, 799.955 s, sampled 8.058 GiB | Six trials pending |
| halo | Right-censored at observation allocation, 1,800.087 s, sampled scratch 6.981 GiB | Pending |
| fermata | Operator-canceled after approximately 701.137 s | Pending |
| newpipe | NOT RUN after the reference series ended | Pending |

Each of the three completed candidate populations produced one canonical receipt digest across its three cold and three warm processes. Realworld Go retained 41 captured regular files, 1 context and 9 gaps. PetClinic Kotlin retained 105 files, 3 contexts and 1,490 gaps. Boutique retained 364 files, 2 contexts and 455 gaps. The peak sampled process-tree working sets for these three populations were 536.8, 586.9 and 1,201.6 MiB respectively. The corresponding sampled scratch peaks were 83.2, 164.3 and 512.3 MiB. The full receipts carry context, report and graph identities. The changed status and gap counts between artifacts remain in the evidence.

The two budget stops retained acquisition/scratch state with zero selected graphs and zero completed snapshot files. The implementation and functional checks shared the host with the reference measurements. After the two allocation stops, the diagnostic reference series was ended during Fermata rather than starting another contended large scan. Its cancellation record is passive-baseline/fermata/operator-stop.json. It records zero selected graphs and zero completed snapshot files. The raw receipts, GC logs and partial workspaces are retained. Nine candidate repository trials and the NewPipe reference remain pending.

The passive CLI produced process wall time, sampled working set and scratch, GC observations, and graph/payload counts. Independent readiness, semantic and Spring phase clocks remain unavailable. Some working-set sample intervals exceeded the protocol's 100-ms target. These passive observations establish the scoped replay results, stop conditions and scratch attribution. The representative scale and comparative timing criteria remain open.

## F01 query and interaction observations

The paired diagnostic series ran the same persisted graph, policy operands and report through both packaged runtimes. It collected 45 cells per artifact, with 20 warm-ups and 100 measured iterations per cell. All 30 API cells returned identical result-identity sequences across artifacts. All payloads stayed under 2 MiB, with a largest candidate body of 143,385 bytes. The measured and warm-up samples had zero request or interaction errors.

44 of 45 median ratios were at or below 2×. At 320 px, API search measured 9.85 ms on the reference and 32.00 ms on the candidate, a ratio of 3.249. Its focused repeat measured 111.60 ms and 47.10 ms, a ratio of 0.422, with identical results. Both runs shared the host with passive scan workloads. Treat the timing series as a diagnostic with variable co-load. Keep the original deviation and use a dedicated host window for comparative acceptance. The original data is in query-diagnostic-r1 and the scoped repeat in query-search-320-r2.

## Candidate and evidence locations

External root is D:/UIT/evo-benchmark-cohort/m9-task4-20261112-r1/. Its registries pin the browser executable and DLL, candidate JAR, registered suite inputs and authored Spring graph/policy.

| Item | SHA-256 |
|---|---|
| M9.4 runtime source, 635 files | ddc4e412f5999659396535bcbd378944fb79f8037a80db33890c654c14a4cb09 |
| M9.4 runtime patch against HEAD | 3f1a7e33ed85327435ada834152b3514ba611949e3b4e54a0e65cee57ab0d43a |
| M9.4 JAR, 46,217,387 bytes | 2b099a777de93dd976c58bdbb1be5e91c9e33d2dfdc119476c73440ec66de84b |
| M9.3 parent registry | 3a49621c97b9597485389b1ffc69b82c608fcb138aabb9807d5af27c73eb1c25 |
| M9.4 initial registry | c706b1b962948ecae2f694ff4f9c3125acd8178a1302ec45ed9c457bedb46658 |
| M9.4 registry v3 | a7bd8c3f23c66310c69bdb9c023edb71cf1c257a64c1f47670b17c96150c8f4e |
| M9.4 registry v6 | 12ee8d305dac24acba9924b3e4bc14a9e6d4f3932553f4805d52a133f6fe4e87 |

The initial and final preflights verified 17 claims and 16 registered inventory/context manifest pairs. The full source rehash passed against the M9.3 parent at entry and against M9.4 registry v6 at closeout. The passive driver rehashes the selected repository before its trials. Raw data, browser profiles, candidate registries and graph stores are kept outside Git.

## Acceptance status and next action

**CONFIRMED human decision (2026-11-12): Task M9.4 is CLOSED / SCOPED MVP.**
- Human explicitly approved closing Task M9.4 under **Scoped Hardening & Small-to-Medium Cohort**.
- Due to workstation disk capacity constraints, large-scale repository evaluations (`agentscope`, `halo`, `fermata`, `newpipe`) are temporarily bypassed and retained as `PENDING_FUTURE_EXPANSION`. The baseline stop and censored records remain in the denominator.
- Operational hardening is accepted: fast DEFLATE disk spool streaming, scratch directory isolation (`FileAnalysisWorkspace`), stale-discovery race condition protection, accessible names, WCAG 2.2 contrast/reflow, automated Edge browser journeys (18 cells PASS), and the 33,000-row scale probe replay.
- Manual screen-reader review and independent code-reading user sessions are retained as qualitative evaluation criteria for the final M9.5 review and M10 dossier, rather than blocking M9.4 closure.
- Task M9.4 is closed. Exact next task: **M9.5** (Final Campaign, Independent Review, and M10 Dossier).

## Commands

Run on Windows with the registered Oracle JDK 21.0.12.1, Maven Wrapper 3.9.16, Python 3.12.0, Node 26.13.0 and Edge 157.0.4341.46. The selected package run uses local Maven artifacts and first-party fixtures.

~~~powershell
$tests = @(
  'M9InteractionTest,AssessmentMetricsIntegrationTest,FileAnalysisWorkspaceTest,AcquisitionCheckpointStoreTest,StreamingDiskBudgetTest,SmallBlobPackTest,LfsObjectAcquirerTest,NativeHttpsAcquisitionTest,AdaptiveRepositoryJourneyTest,WorkerProcessSupervisorTest,SemanticAuthenticatedPublicationTest,EvidenceBundleImportTest',
  'StreamingRepositoryAcquirerTest#fullInventorySurvivesRestartAndKeepsV1GoldenIdentity+cancellationRetainsFrontierAndResumeDoesNotDuplicatePaths+lfsPointerKeepsAnOpenObligationAndCannotSeal+rawNamesPreserveCaseUnicodeAndNonUtf8WithoutHostMaterialization+v1IdentityUsesUtf16CanonicalOrderingRatherThanRawUtf8Order+mutationAfterCaptureWithholdsTheRootInsteadOfMixingSourceRevisions+threadInterruptionStillPublishesTheCancellationCheckpoint+symbolicLinkEntriesAreSkippedInCompatibilityWithExplicitExclusionProvenance',
  'PartitionedGraphStoreTest,PartitionedGraphApiTest,AnalysisJobsTest,DurableAnalysisJobsTest,AnalysisJobApiTest,CapturedJavaRegistrationTest,MavenScanReactorTest,SpoolSpringTest,WorkbenchApiMainTest,LocalAnalysisRunnerTest',
  'M9RecoveryEvidenceTest,ResolutionInputsTest,JavaEvidencePipelineIntegrationTest#malformedGeneratedEncodingRetainsItsCapturedDocumentAndSoundSiblingBytes,M6PolicyIntegrationTest#actualProfileBindingStaysMayUnderExplicitAndSymbolicPolicyPredicates',
  'ScanJourneyTest,FailureDiagnosticsTest,M9ConsumerConformanceTest'
) -join ','
.\mvnw.cmd -o -q -pl analyzer,analyzer-filesystem,analyzer-javaparser,backend,cli '-Denforcer.skipRules=reactorModuleConvergence' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dplatform.build.directory=target/m9.4' '-Devo.workbench.exportFixtures=true' "-Dtest=$tests" package
node --test backend/src/test/js/workbench-model.test.cjs backend/src/test/js/bridge-model.test.cjs
python -m unittest discover -s benchmarks/g2-pipeline -p 'test_m9_*.py' -q
~~~

Use a new output directory for each run. The reference JAR comes from the Task 1 registry. The timing driver reopens a copy of a browser run's authored store. For a dedicated timing series, run the workload without other active measurements on the host.

~~~powershell
$evidence = 'D:/UIT/evo-benchmark-cohort/m9-task4-20261112-r1'
$registry = "$evidence/candidate-registry-v6.json"
$java = 'C:/Program Files/Java/jdk-21.0.12.1/bin/java.exe'
$node = 'D:/node/node.exe'
$edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$reference = 'D:/UIT/evo-benchmark-cohort/m9-inputs-v1-final/evo-baseline-r2.jar'
$candidate = "$evidence/evo-candidate.jar"
$springClasses = (Resolve-Path 'backend/target/m9.4/test-classes').Path
python benchmarks/g2-pipeline/m9_protocol.py preflight --registry $registry --verify-sources
python benchmarks/g2-pipeline/m9_passive.py --registry $registry --output "$evidence/passive-reference-next" --role baseline
python benchmarks/g2-pipeline/m9_passive.py --registry $registry --output "$evidence/passive-candidate-next" --role candidate
& $node backend/src/test/js/m9-workbench.browser.cjs $java $candidate $edge "$evidence/browser-next" $springClasses "$evidence/spring-fixture/symbolic.graph.json" "$evidence/spring-fixture/symbolic.policy.yaml"
& $node backend/src/test/js/m9-workbench.perf.cjs $java $reference $candidate $edge "$evidence/browser-next/desktop" "$evidence/query-next"
[xml]$report = Get-Content -Raw 'analyzer-filesystem/target/m9.4/surefire-reports/TEST-com.evolution.analysis.filesystem.FileAnalysisWorkspaceTest.xml'
$entries = ($report.testsuite.properties.property | Where-Object name -eq 'surefire.test.class.path').value -split ';'
$probe = ($entries | Where-Object {$_ -match '[/\\]test-classes$|[/\\](junit-jupiter-api|junit-platform-commons|apiguardian-api|opentest4j)-[^/\\]+\.jar$'}) -join ';'
python benchmarks/g2-pipeline/m9_passive.py --registry $registry --output "$evidence/graph-33k-next" --role candidate --graph-classpath $probe
~~~
