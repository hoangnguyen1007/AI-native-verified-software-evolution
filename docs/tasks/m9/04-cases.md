# M9.4 controls

The [frozen protocol](PROTOCOL.md) and [M9.1 registry](registry.json) own the claims and their criteria. The [M9.3 record](03-execution.md) pins the starting candidate. An experimental run retains its original disposition and inputs. A repair or changed browser needs a new candidate or environment identity for the affected cells.

## F01 passive repository runs

The driver is [m9_passive.py](../../../benchmarks/g2-pipeline/m9_passive.py). It uses the existing protocol's locator, file hash and independent census functions. The same seven frozen repository identities and passive arguments apply to the Task 1 reference and the registered M9.4 candidate. Development and scale allocations are those in the protocol. Run in sequence using the pinned JDK and one empty artifact directory per series. Treat the OS page cache as uncontrolled.

| Registered case | Allocation | Task 1 reference | Candidate |
|---|---|---:|---:|
| realworld-go, petclinic-kotlin | Development | 1 cold each | 3 cold + 3 warm each |
| boutique, agentscope | Scale | 1 cold each | 3 cold + 3 warm each |
| halo, fermata, newpipe | Scale | 1 cold each | 1 cold each |

Each cold trial has a separate durable workspace and a new JVM. Its paired warm trial starts a new JVM with that workspace and the same inputs. This exercises persisted acquisition and graph stores. The scan starts fresh semantic scratch and JVM-local caches, so these measurements cannot certify semantic-result cache speedup. Evaluate reproducibility on graph/report identities and the captured status, gap summary, context and metric fields. Preserve the full receipts and workspaces for deeper comparison. A PARTIAL publication keeps its evidence tier and limitations.

Independent wall time covers process start through exit. Windows working sets are sampled with a 75-ms wait between observations; report missed samples and the longest observed interval. Scratch is sampled every two seconds and can peak between observations. Heap is limited by JVM flags and GC observations are retained separately. The packaged JSON CLI has no independent readiness, semantic or Spring phase clock. The repository series reports total wall time and retains this measurement gap.

Stop a run at the frozen observation, working set or scratch allocation. Retain the receipt if present, stderr, workspace, elapsed time and stop reason. A stopped cold trial cannot seed a warm trial. When the same failure condition still applies, retain the remaining repetitions as unavailable. Never start a build or dependency download. Source-rehash or platform failures stay in the denominator.

The 33,000-row cell uses the exact registered PartitionedGraphStoreTest generator through [M9GraphScaleProbe](../../../analyzer-filesystem/src/test/java/com/evolution/analysis/filesystem/M9GraphScaleProbe.java). Each of the three fresh stores is published and cold-opened in a new JVM, then reopened and paged from another JVM over its persisted root. Each trial must traverse every page, count 33,000 rows, recover the last generated row and produce the same authenticated root. The graph has one context, zero edges and a deterministic identity seed. Denser, multi-context and conditional behavior has separate small correctness controls.

For the HTTP projection control, start the packaged QueryApiMain over the first cold store. Request the single partition and two successive node pages of 50. Check the partition denominator of 33,000, 100 distinct returned node identities, continuation on both pages, explicit raw-fact trust, and the 2 MiB response cap. A size-51 request must receive a typed client error. This checks the actual transport bound against the scale fixture.

## F01 query and interaction timing

[The timing driver](../../../backend/src/test/js/m9-workbench.perf.cjs) reopens a copy of the same authored workbench store for every browser viewport and both JARs. The store contains the canonical graph and the persisted report with the Client → Store rule and exact coordinates. Verify graph, context, report, operand and page identities before comparing. The reference series runs first. Each operation has 20 warm-ups and 100 observations per viewport. The allowed median ratio and 2 MiB transport payload cap come from the frozen protocol and API defaults.

The browser performs actual loopback search, structure children, focused projection, shortest path, cycle, metric, assessment and finding, evidence bundle and source, impact and raw page requests. A distinct interaction series measures search, focus, metric inputs, finding source evidence and impact through the shipped UI. Record request fetch/body times and sizes, JSON decode where measured, action-to-ready wall time and elapsed time from the last observed response through two animation frames. The latter includes browser scheduling and DOM work. Errors and oversized responses stay in the full population. Raw page latency is an API cell.

Use M9_TIMING_CELL as viewport/operation to repeat only a deviating cell. The selected pair keeps the frozen warm-up count, measurement count, seed and median criterion. Preserve the full original series and record competing host work for any diagnostic repeat.

## R01 robustness controls

| Perturbation | Existing control and expected outcome |
|---|---|
| Unrepresentable paths, encoding, symlink | StreamingRepositoryAcquirerTest retains distinct raw names, matches UTF-16 ordering and reports compatibility exclusions. AnalysisJobApiTest rejects malformed request encoding and ambiguous fields. JavaEvidencePipelineIntegrationTest keeps the undecodable generated document and unaffected captured siblings. |
| LFS, submodule, idle or transient transfer, authentication | StreamingRepositoryAcquirerTest keeps the pending LFS obligation. LfsObjectAcquirerTest closes a verified object from cache and rejects absent authorization. NativeHttpsAcquisitionTest fences a stalled transfer and exercises reset/shallow retry and LFS/submodule closure using first-party local HTTPS fixtures. |
| Stale, missing, corrupt or changed inputs | AdaptiveRepositoryJourneyTest and EvidenceBundleImportTest retain typed archive/selection gaps. CapturedJavaRegistrationTest rejects missing exact bytes. M9RecoveryEvidenceTest rejects replacement dependency bytes with the original size and mtime. ResolutionInputsTest preserves ordered duplicate candidates. SemanticAuthenticatedPublicationTest rejects a revoked publication. |
| Disk pressure and restart | StreamingDiskBudgetTest and SmallBlobPackTest withhold publication on exhausted capacity or failed flush. StreamingRepositoryAcquirerTest and AcquisitionCheckpointStoreTest resume the same frontier and preserve earlier independent entries. WorkerProcessSupervisorTest preserves completed units through timeout, crash, cancellation and stale checkpoint rejection. |
| Concurrent schedule, disk pages, cancellation and failure | PartitionedGraphStoreTest checks serial/parallel root equality, cold canonical rows, oversized rows, corrupt indexes and page-worker failures without a completed snapshot. PartitionedGraphApiTest checks the paged route, cursor binding and cancellation. AnalysisJobsTest and DurableAnalysisJobsTest preserve the accepted identity and prevent a successful receipt/report after interruption or failed storage. |
| Untrusted diagnostic text | FailureDiagnosticsTest retains typed failure context and excludes private text. The workbench uses DOM text for captured values. |

The selected tests use first-party fixtures and local acquisition controls. An unavailable local test prerequisite remains a named outcome. Native acceptance retains its recorded B01/D01 disposition.

Two spool regressions use FileAnalysisWorkspaceTest. Opening a second workspace under the same parent must preserve the first workspace and a caller-owned sibling directory. Thirty-two repetitive rows, including non-ASCII and unpaired Java code units, must use less than 64 KiB of value scratch in this authored fixture and round-trip byte-for-byte at the Java code-unit level. The existing corrupt-byte and authenticated-alias controls exercise the new physical spool representation. The fixture budget is a regression check, independent of the frozen external scale allocation.

## U01 browser scripts

[The browser driver](../../../backend/src/test/js/m9-workbench.browser.cjs) uses the packaged workbench over loopback with desktop 1440×1000, mobile emulation 390×844 and 320-px reflow. The authored two-type source and explicit rule expect Client → Store, a boundary finding, and an exact source span at line 1 columns [33,38). The degraded source leaves a type unresolved and has no policy. Browser-created output and downloaded evidence stay in the selected external directory. The optional Spring input is the symbolic, source-generated graph and its policy from M6PolicyIntegrationTest, served by the existing CapturedWorkbenchAcceptance harness. Each source, policy, graph, browser and script input is pinned for its run.

| Script | Observable outcome |
|---|---|
| U1 | Connect the local authored repository with explicit rules, cancel its queued/running job, retry, choose the published context, reopen the canceled receipt and return to the result. Cancellation must leave no selected graph. |
| U2 | Read the operational receipt, verify its published graph identity and gap accounting, inspect limitations, and return focus to the invoking control on Escape. |
| U3 | Open metrics and the boundary finding. Verify the two-symbol path, exact coordinates and source digest. Download evidence JSON and compare it with the displayed row. Inspect canonical context provenance. |
| U4 | Open the Spring configuration comparison, choose a recorded or modeled realization, inspect its baseline profile, then switch to the unknown overlay. Replay the stored witness and verify the formula-only outcome. |
| U5 | Use arrow keys and Enter to activate Structure, find the project type, open a focused graph and reach Change impact. Check 320-px document reflow and accessible tab names in the browser accessibility tree. |
| U6 | Hold a real discovery response until the same job has been refreshed and a context chosen. The late response must preserve the current selection. Control a one-shot service error and retry. Switch to the unresolved source and verify the missing-policy state. Mobile sidebar expansion must reflect its actual visibility. |

Use WCAG 2.2 criteria 1.4.3, 1.4.10, 2.1.1, 2.4.3, 2.4.7, 2.4.11 and 4.1.2 where applicable. Headless keyboard events, browser accessibility-tree inspection and layout checks have their own rows. The protocol separately requires a manual screen-reader spot check and three independent code-reading users. Record each participant's completion, errors, assistance and evidence-tier interpretation on the same journeys.

For the manual run, record the browser and assistive-technology versions. Navigate with Tab, arrow keys and Enter through the context selector, tab list, graph alternatives, finding dialog and source controls. Record the announced labels and statuses, visible focus, return target after Escape and any focus obscured by the mobile sidebar. Ask each participant to explain the receipt's operational status, which evidence tier the interface supplies, and what a missing dependency or an unknown configuration establishes. Record the participant's own answer and whether guidance was needed.
