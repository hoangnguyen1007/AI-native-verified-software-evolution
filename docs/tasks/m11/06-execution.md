# M11.6 — Historical evaluation and G7 adjudication

**Disposition (2026-11-15):** [ADR-011](../../decisions/ADR-011-full-pipeline-demo.md) closes M11 and G7 by human decision. G7 is not certified passed. The independent verdict and three unresolved assertions below remain part of the historical record; readjudication and a repeat campaign are no longer required for delivery.

2026-11-15. M10 authorized Track B; M11.1–M11.5 supplied authored and consumer controls. The working candidate is based on `4b52040` with the preceding M11 changes already in the worktree. This record applies the original [case registry](cases.json) and [protocol](PROTOCOL.md).

Frozen historical candidate: SHA-256 `1aff2ff96d58403083ff670c074a4e4b9f18ab453f80eb6c19d4f96827f7f51c`. The [runner](../../../benchmarks/g2-pipeline/m11_historical.py) reuses the M9 Windows process-tree and scratch observer, the retained Git object store, two exact-input graph roots from [M11.5](05-execution.md), and an explicit empty offline Maven cache for the three additional scans. It ran natural observations sequentially, with one JVM comparison job per observation, 2 GiB max heap, a 300 s watchdog and uncontrolled OS caches. Maven and bounded auxiliary checks overlapped some observations; treat elapsed times as development profile measurements. The raw local bundle `%TEMP%/evo-m116-g7-07dcc5eff7ef472dac2cfe567a37d3ee` contains the two candidates and graph stores. The compact local packet `D:/UIT/evo-benchmark-cohort/m11-g7-20261115-r1` retains the freeze, registry, requests, commands, receipts, and resource logs. Raw inputs and large outputs stay outside Git.

```powershell
python benchmarks/g2-pipeline/m11_historical.py --java $java --jar $frozenJar --objects $bareObjectStore --previous $m115ExactCapture --output $newEvidenceDirectory
```

**CONFIRMED / source evidence.** Recomputed the SHA-256 inventory for all 1,004 blob rows across five revisions. The runner checked all five trees, three direct-parent edges and six changed-file anchors; it read exact blob bytes per object instead of batching them. Independent read-only review also checked the actual POM/YAML and documentation diff directly in Git. It confirmed the declared module and gateway route with Spring Boot version drift, README-only change, and removal of local assignments with the declared config-server import retained. These labels establish source facts. They do not establish effective config-server values, runtime edges or a change in finding status.

| Scan | Graph | Result / elapsed |
|---|---|---|
| `a` | `sha256:0610e4b452ef7254227dc73deca2a7563ae1b714a54a42460b0362f7fc6c8bbe` | PARTIAL / 29.218 s |
| `b` | `sha256:4ad5f4a95862e7bd1ec6bd8ba2cb06b955743c44c8236cc1327bfbbf224855d9` | Reused M11.5 PARTIAL capture |
| `c` | `sha256:59b79c5603f71c9b57ae50f6ac793ceebccb0eafe9cd8a5dda75f6caa5c4562b` | Reused M11.5 PARTIAL capture |
| `d` | `sha256:00e9a9c73a992c322e68461a22ddc3a3cd53f5ac17ca449a4f907747d3624262` | PARTIAL / 37.746 s |
| `e` | `sha256:9a1ad77184fca751054dd63f89b4c13e1a5986dbcc6734578a63c4053d3c8146` | PARTIAL / 35.998 s |

The development pilot re-created the M11.5 documentation archive `sha256:7d794c9d9bfd51358fee04cd95b34d791a99f3901ba24b28ebcd0c79736a2f7d` from an empty comparison store. It took 178.840 s, with a sampled process-tree peak of 734,232,576 bytes and scratch peak of 431,723,172 bytes (includes the staged graph store). Its 58 context rows, 2,228 gap rows and zero events are consistent with the README-only label within this Java selection. The pilot is separate from the three registered cold trials.

All three cold trials independently reproduced that same PARTIAL archive from empty comparison stores. All three warm trials reopened the persisted result in a fresh JVM and returned the same 20-row first context page. The raw receipt SHA-256 matched across each three-trial group. No trial reached the watchdog.

| Documentation profile | Wall seconds, samples (median) | Peak tree working set, bytes (median; range) | Peak scratch, bytes (median; range) | Receipt bytes |
|---|---|---|---|---|
| Cold compare | 150.104 / 172.084 / 155.095 (155.095) | 753,250,304; 742,600,704–777,928,704 | 431,817,507; 431,716,488–437,547,492 | 1,456 |
| Warm first-page reopen | 0.560 / 0.636 / 0.592 (0.592) | 116,277,248; 113,889,280–122,515,456 | 384,258,510; 384,211,455–384,258,510 | 17,222 |

The largest process sampling interval was 107.2 ms; scratch includes the graph and archive already on disk. Authenticated journal records for documentation trial 1 place the first scope checkpoint at +19.153 s, publication verification at +149.101 s and completion at +149.427 s relative to the pending commit. The journal does not isolate graph verification, indexing, alignment and delta costs. The warm sample measures startup plus reopen/page, with no forced warm recomputation. Three trials do not establish tail latency.

For `a→b`, result `sha256:e0483d3d17c7119cd8d60987968928fee0c9ff32a133274c88aa99dd226cde80`, the export shows GenAI MAIN as selected, available and `RIGHT_ONLY`; TEST and the unselected build component also remain visible. Of 55 context rows, 16 matched, 34 were not selected, four were unavailable and one was right-only. Fourteen compatibility rows report `CLASSPATH_DRIFT`; all 325 events have kind `OBSERVATION_CHANGED`, with 2,310 gaps retained. The precise Boot version and gateway route are source labels; the comparison reports classpath drift but does not prove the route. The cold job took 124.676 s; fresh JVM reads of the first and second 20-row context pages took 0.511 and 0.522 s, both matching the corresponding export slices. The second receipt was 17,158 bytes.

For `d→e`, result `sha256:440ef3495de930d9ea8f6a91ede8d76b39c2e7515e857b0a97ab7c2fccb4ec23`, the engine returned PARTIAL / 58 contexts / 2,994 gaps / zero emitted events. The cold job took 136.019 s, with a sampled process-tree peak of 611,966,976 bytes and scratch peak of 473,945,792 bytes. The 20-row reopen took 0.519 s. The source oracle confirms three local property removals and a retained external import; all three removals are outside the tested engine projection. No effective-value or finding-resolution claim is scored.

The registered 33,000-node fixture was reused as a development probe at its original graph root `sha256:7898687753e16186331dc4306118586bccf46b4aacedafe82791a79a6f022cd0`. Both inputs opened, but the legacy fixture does not establish lineage for a matched comparison. The result was `WITHHELD` in 2.314 s, with four gaps and zero correspondence, proof or event rows. This is a negative intake and withholding control; repeated positive comparison scale remains `NOT_RUN`.

**Independent review: CHANGES REQUIRED.** The reviewer found that the public row projection removed three content-addressed history references required to audit a reintroduction. The original candidate returns only `redactedOperand` for a row containing those references. The repair preserves `intervalProof`, `previousAbsenceProof` and `priorPresenceProof` when they are valid SHA-256 IDs, and keeps malformed values sealed. The reviewer also identified the narrower Java selection in the natural campaign, the need to distinguish JAR bytes from source provenance, and the absence of full historical, scale and product proof. The candidate under review and the disclosure repair are separate evidence revisions.

The repaired package is SHA-256 `3a51c45707619b67efa5d8abcf3e6267e939e7a1203d00180cb02d26a9272efc`. Archive entry names match the historical candidate, but 1,556 entry contents differ; the historical results are attributed only to their original JAR. The reviewer rechecked the repair, the module export, pilot and scale receipt, and confirmed that the SHA-only projection fix resolves the disclosure defect at the shared projection boundary. The re-review retains **CHANGES REQUIRED** for G7.

```powershell
.\mvnw.cmd -B -ntp -q -pl cli -am '-Dtest=EvolutionCliTest,EvolutionApiTest,ApiSpecificationTest,EvolutionCompatibilityTest,SourcePlanProjectionTest,FileSnapshotComparisonTest,OutputCensusTest,CanonicalGraphPipelineIntegrationTest,TreeSitterSourceFrontendTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

**PASS:** 81 tests in nine selected suites, zero failures, errors or skips. Earlier Maven attempts stopped on the no-test module guard, a stale standalone classpath and a test generic type mismatch; none counted as passing checks. A Chrome 157 run against the repaired package reopened the two pinned PARTIAL archives, verified CLI/HTTP parity, degraded export, graph expansion, keyboard order, 320 px reflow and stale-response handling. It used [the existing browser driver](../../../backend/src/test/js/evolution-workbench.browser.cjs) and did not measure a fresh comparison or exercise positive region/history parity.

| Registered control family | Evidence and claim boundary |
|---|---|
| E01–E05 | `EvolutionCompatibilityTest`, `FileSnapshotComparisonTest` and the canonical Java pipeline controls cover self/reverse, scoped identity, censuses, drift and semantic/evidence separation on authored operands. [M11.2](02-execution.md) records the original scope. They do not label all historical structure or all move cases. |
| E06–E09 | `ConditionalRegionDeltaTest` and the canonical Spring pipeline exercise the 16 registered T/F/U pairs, domain boundaries, explicit and symbolic proof, exhaustion, selection, order and witness change. See the exact authored evidence in [M11.3](03-execution.md). |
| E10 | Authenticated evidence-loss, alteration and restoration controls distinguish missing or corrupt inputs from semantic absence. See [M11.2](02-execution.md) and [M11.3](03-execution.md). |
| E11–E14 | `FindingEvolutionTest` and the canonical Spring pipeline cover regional finding lifecycle, weak interval proof, exact arithmetic and insufficient evidence in metric/score changes. [M11.4](04-execution.md) records the oracle and limits. |
| E15 | [M11.3](03-execution.md) recorded a scoped replay of an existing unqualified native bridge publication. No new native campaign or stronger producer qualification was run here. |
| E16 | Filesystem and HTTP controls cover archive tampering, conflict, cancel/resume, simulated disk pressure and pinned cursors. [M11.5](05-execution.md) records the scope checkpoint result. These are bounded controls; large-history interruption and native execution are not inferred. |
| E17 | The original graph-only Workbench and CLI/HTTP/export controls are recorded in [M11.5](05-execution.md). The proof-link repair has a focused redaction regression. A full product journey for a witnessed conditional change and reintroduction is still required. |
| E18 | The registered five source revisions are present. Three passive graph comparisons and the repeatability protocol are tracked separately from the Git source labels. Historical triple lifecycle and matched-scope comparison scale are not established by the graph-only controls. |

The following groups account for all **42 registered case IDs**. `PASS` refers to the stated authored case or bounded historical control and its linked run, never the whole family or G7. A partial substitute for a registered input stays `NOT_RUN` for the exact case.

| Disposition | Case IDs | Evidence boundary |
|---|---|---|
| PASS | E01-SELF, E01-REVISION, E01-EDIT, E01-REVERSE, E03-CENSUS, E04-PROVIDER, E04-ORDER, E05-OCCURRENCE, E05-PAYLOAD | Authored structural, compatibility and provenance tests described in M11.2 and the targeted package. |
| PASS | E06-QUANTIFIERS, E07-SIDE-ONLY, E07-EMPTY, E08-FORMULA, E08-INFEASIBLE, E09-BINDING, E09-WITNESS | Authored region, witness and order controls from M11.3. |
| PASS | E10-LOSS, E10-CORRUPT, E10-RESTORE, E11-REGIONAL, E11-POLICY, E12-RETURN, E12-GAP, E13-EXACT, E13-DRIFT, E14-SCORE, E14-MODEL | Authored lifecycle, loss, exact arithmetic and score controls from M11.2–M11.4. |
| PASS | E16-PAGE, E16-REOPEN, E17-STALE | Authored paging, durability and cancellation controls and the graph-only browser interaction/redaction controls. |
| PASS | E18-NATURAL-DOC | Exact Git-only README change and matching archive for the selected Java projection. PARTIAL output and zero semantic events remain separately recorded. |
| NOT_RUN | E02-IDENTITY, E02-MOVE, E03-MULTIROOT, E04-SCHEMA, E05-TARGET, E15-TIER, E15-BRIDGE | Nearby cases exercise subsets. No complete run with all registered input dimensions was identified for these IDs. |
| FAIL / repair scoped | E17-PARITY | The original candidate drops the history proof links. The repair passes its regression and graph product replay. Full region/history parity on the repaired candidate is NOT_RUN. |
| UNSUPPORTED | E18-NATURAL-MODULE, E18-NATURAL-CONFIG | Only the source oracles cover the registered YAML facts. Module presence and classpath drift are observed separately; no runtime or resolution credit. |
| NOT_RUN (full) | E18-REPLAY | Historical repeats are scored separately from the legacy scale negative. Positive matched-scope scale, complete phase metrics and a qualifying single-candidate replay are missing. |

**Gate decision.** The registered YAML route and local configuration declarations are outside the implemented comparison projection exercised here. Keep them in the denominator as `UNSUPPORTED` at this tier. The passive graph requests cannot prove effective values or that a violation resolved. A `PARTIAL` result with an empty event page is a negative observation under that selection; it does not prove the absence of change. No human scope exception or acceptance has been recorded, and M12 is not authorized by this evidence.

## Follow-up on the linked candidate

The follow-up built and froze candidate `sha256:33774f241e8353c25fbe615e5005bee57c18a6f9f4b1b4b2ea2e67635b9d50c8` with runtime source digest `sha256:eba51e212821c640754b5c0815e8ed38fbde2383ddc08cecff6916fe67abf6b9`. The original findings above remain the disposition of the original candidate. The new measurements and receipts are under `D:/UIT/evo-benchmark-cohort/m11-g7-20261115-r2`; product and scale fixtures use sibling directories with the same prefix.

The declaration reader binds exact YAML byte evidence to each inventory, compares coordinates and preserves spans. It emits typed gaps for unavailable operands and unsupported or over-budget input. The comparison and job identities include the new implementation revision. The public query and export retain file/span/evidence references while withholding arbitrary keys and literal value hashes. Independent review identified the coordinate expansion and disclosure risks. The repaired parser enforces the 8 MB coordinate budget before indexing, and public projection tests cover both filters.

| Claim | Follow-up observation |
|---|---|
| E18-NATURAL-MODULE | **PASS for declared evidence.** The sealed result yields the GenAI route's id, URI and predicate at the expected YAML coordinates, each bound to both registered blob digests. This adds the source declaration to the module and drift evidence. The archive is PARTIAL, with 14 declaration rows across the selection. |
| E18-NATURAL-CONFIG | **PASS for declared evidence.** The authenticated result shows removal of the three named local assignments and retains the Docker document's config-server import. The archive is PARTIAL, with five YAML declaration rows. The observed removals have `semanticAbsenceProved=false`. |
| Natural triple | **PASS for ancestry.** The CLI opened a three-step request over `a→b→c` and verified each interval against the registered Git commit-object bytes, exact parent, and graph snapshot IDs. Archive `sha256:99357cf2ed0a31c94ea863a47c3541d7d99c636485a8fddd88e05c886cbd9e39` reports two authenticated parent intervals with PARTIAL finding evidence and no lifecycle transition. The check took 48.884 s. |
| E17-PARITY | **PASS for the authored regional lifecycle journey.** The production report store supplied the original evaluated policy and metrics from the real Spring fixture. CLI and HTTP returned archive `sha256:5099234cd1e8a91a6f718366e71af7e389129daa7525d9b6892358c3a58c3900`. The browser verified a reshaped conditional region, one proved reintroduction with all three proof references, identical CLI/HTTP/export pages, and replay of the exact uploaded request. The missing-target control returned WITHHELD and passed the stale-response, keyboard and 320 px checks. |
| E18-REPLAY scale pilot | **CENSORED.** Same-package generation hit the 300 s development allocation before a comparable pair was published. A sampled JVM stack was merging support for package nodes in the graph builder. A separately pinned fixture distributes 33,000 authored types across packages and retains the one expected dependency addition. |

The existing [historical runner](../../../benchmarks/g2-pipeline/m11_historical.py) replays all three pairs from the original graph publications with `--replay` and the registered Git objects. The trusted archive audit in `verify-declared-yaml.jsh` verifies the three route coordinate/value pairs, three local removals and one retained import, including their expected source digests. The production browser check uses [the existing driver](../../../backend/src/test/js/evolution-workbench.browser.cjs) with `--check-history`.

```powershell
.\mvnw.cmd -B -ntp -q -pl cli -am '-Dtest=DeclaredYamlEvolutionTest,EvolutionCompatibilityTest,SourcePlanProjectionTest,FileSnapshotComparisonTest,OutputCensusTest,CanonicalGraphPipelineIntegrationTest,TreeSitterSourceFrontendTest,EvolutionApiTest,ApiSpecificationTest,EvolutionCliTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

The targeted package passed. The YAML test initially reproduced zero declaration rows for the four expected outcomes and now passes with positive, missing/corrupt syntax and projected-coordinate limit controls. The authored real Spring lifecycle fixture also passed while exporting the graph and policy operands used by Chrome.

### Frozen comparison candidate and remaining registered controls

The frozen comparison runtime is `r3/candidate.jar`, SHA-256 `49d95c0b78b6a2ff7891eadedd8fcde8bc3096919703271fc386b41ea61f3d71`, with runtime source digest `c7abd6e4754185fcb149b0b4bae89d54f42031ffa8b4bd3a6e1bcce6fe02f96c`. Its freeze and receipts are under `D:/UIT/evo-benchmark-cohort/m11-g7-20261115-r3`. The scoped production changes preserve the previously authenticated structural evidence in the comparison scratch index and strip private YAML values, keys and derived correlation IDs before computing the public projection. The public equality regression fails against r2 and passes against r3.

| Previously open controls | Final targeted evidence |
|---|---|
| E02-IDENTITY, E02-MOVE | `EvolutionCompatibilityTest` retains two scoped `p.A` declarations, distinguishes both overloads and the nested and local types, and retains an unproved generated operand. A separate authored move presents two same-spelling candidate modules with different producer-asserted lineage and yields no definite correspondence or rename. |
| E03-MULTIROOT | The authored Java census checks the exact counts for matched, pending, out-of-selection and target-only build roots. The unselected `ci` variant and the new reactor are distinct. No missing context produces a deletion event. |
| E04-SCHEMA | `DeclaredYamlEvolutionTest` presents an unregistered graph schema on the right and asserts that its rows are never decoded. The comparison is WITHHELD with the typed UNSUPPORTED input. |
| E05-TARGET | The same source spelling with an unresolved target and a separately resolved target produces two observations with their original evidence. The supported target does not retroactively prove continuity or a certain repository addition. |
| E15-TIER, E15-BRIDGE | `BridgeMixedReplayIT#evolutionKeepsNativeTierDriftAndLostEndpointEvidenceUnknown` reads the retained M8.4 mixed capture, matches the exact span of the syntax-only and native Python call, and preserves the native provider/context and both evidence references separately. The fixture has a BOUND declaration. The negative control hides its endpoint table through a scratch overlay and returns an unavailable bridge operand and unknown continuity without claiming deletion or observed runtime behavior. The original capture is read-only. |

The same three historical pairs on r3 retain the r2 canonical archives. The module run establishes one target-only GenAI MAIN scope, 14 classpath-drift rows and the three bound route declarations. The configuration run retains the three local removals and import evidence. The natural triple again has two authenticated parent intervals and a PARTIAL archive with no finding transition. The r3 Workbench rerun reproduces the unchanged authored region/history archive, proof references, withheld control and exact request submission.

The package and scoped checks reported 83 tests in 12 suites with zero failures, errors or skips. The subsequent compatibility filter passed after adding the final authored controls. The captured-data bridge method passed separately using `-Dbridge.fixtureRoot=D:/UIT/evo-authored-fixtures/m845-mixed-20261102-v3`.

### E18 final repeatability and resource observations

The same-package graph generation and the r2 matched-scope comparison exceeded the 300 s allocation. Both were retained with no completed comparison. The package-distributed fixture is pinned under `r2-scale-packages/fixture` and generated in 15.847 s. r3 reused those two publications with fresh comparison stores. Each cold trial completed and produced archive `sha256:b6219f71bfba1d1d6d7333188c707dadac9161e121d8bff1155de542ca1acc60`. The sealed correspondence census contains exactly 33,000 distinct identity-matched TYPE pairs and one right-only relationship. The only event is `RELATION_ADDED`. The first receipt was retained after the harness incorrectly expected 33,000 total rows instead of 33,000 TYPE rows plus the relationship. Its independent audit counts the two families separately.

| r3 workload | Wall seconds, samples (median) | Peak tree working set, bytes (range) | Peak scratch, bytes (range) | Receipt bytes |
|---|---|---|---|---|
| Documentation cold | 60.009 / 60.333 / 60.330 (60.330) | 678,637,568–686,321,664 | 488,868,487–502,465,844 | 1,456 |
| Documentation first page reopen | 0.472 / 0.457 / 0.498 (0.472) | 114,036,736–121,147,392 | 431,370,632 | 17,222 |
| Scale cold | 144.862 / 142.820 / 123.703 (142.820) | 475,312,128–488,321,024 | 1,202,943,489–1,208,173,062 | 1,373 |
| Scale first page reopen | 0.530 / 0.527 / 0.643 (0.530) | 125,968,384–130,527,232 | 742,557,853 | 91,239 |
| Scale later page reopen | 0.515 / 0.523 / 0.700 (0.523) | 124,665,856–133,730,304 | 742,558,046 | 91,263 |

The documentation cold archive is `sha256:c5302821eb7143dca950478e54beb9e74142eb47cacd9ffd009378d0f8715754`. Reopen pages agree within each fixed query group. The scale first and next pages each contain 50 disjoint subjects. The JDK 21 process used a 2 GiB heap limit. The maximum process sampling interval across these rows was 111.7 ms. Scratch measurements include staged graph data, and those for page reads include the persisted archive. Each warm measurement includes JVM startup and opening the completed archive. Other local verification overlapped some samples, so the wall times describe this development host.

The separate `r3-scale/phase-verified` probe measured phase boundaries on the exact frozen JAR and generated pair using JDK method-entry and return breakpoints. It re-created the scale archive in 179.726 s and observed seven instrumented methods with no pending interval. Opening and verifying pinned graph closures took 49.864 s in total. Intake and indexing took 24.787 s excluding nested graph opens, context alignment took 17.973 s excluding delta, and structural delta took 64.295 s. Preparing, verifying and publishing the archive took 13.837 s combined. Validation within intake, scope compatibility and output writing remains in the measured owning phases. Other work and observer startup account for the residual. The instrumented sample measures method boundaries under JDI overhead. The three normal cold/warm samples above supply repeatability and resource observations.

Earlier JDI attempts are retained. The first selected the wrong CLI entry point. The next two produced the expected archive but failed phase accounting because a blank line in the disassembly clipped the return offsets for `align`. The final probe scans to the next method header, covers all observed exits and validates the child's exit and result.

The original default reactor stopped after a failure in `SemanticEmbeddedPublicationTest`. Git had converted the authored mixed-EOL Vue container to all CRLF. The existing generator and source map specify the exact 142 bytes, SHA-256 `9a42a599918ed40461ce12f6ab441f206fa553680f95b1427fc2c6726cdbd908`. The worktree now contains those bytes, and the path-specific Git attribute prevents normalization. The sealed source map and captured native output retain their original bytes. The fresh fixture check passed. The first run independently passed the unchanged `StreamingScaleTest` over 1,000,001 inventory entries in 155.210 s. The reactor retry reuses that result and selects the remaining default test patterns.

The retry passed the embedded publication check and reached JavaParser. The retry ended after 400 JavaParser tests, with four failures and one error. A focused reproduction isolated the duplicate-declaration exception to a resolved observation that still referenced a declaration withheld from the entity census. The worktree follow-up refuses that source or target before emission and retains the typed duplicate diagnostic. It identifies the new producer as recovery v13. The frozen historical and scale artifacts above remain attributable to r3 and their original graph producer pins.

The inference-collision fixture used an unspecified dependency order and an assertion on a bare count. The follow-up fixes the classpath order and asserts the four distinct spans of the typed nested calls with zero adapter errors. The original failure and the captured spans remain in the logs.

Three protected assertions remain. The two M4E controls expect a complete report, while the reproduced reports contain explicit category and annotation evidence gaps. The cohort case also retains unsupported frontend and partial source outcomes. The endpoint control expects Spring metric detail from a Java manifest partition, but the registered Spring metric rejects that partition scope. The fixture still verifies the endpoint and its non-runtime declaration via graph and query. These assertions retain their original expectations. Their treatment needs to preserve the protected completeness, scope and provenance contracts.

The downstream reactor selection in `r3/downstream-test-selection.txt` finished in 449.480 s with **335 tests in 59 suites**, zero failures, errors or skips. It covers the repaired HTTP, semantic boundary and JMOD suites and the downstream Tree-sitter, backend and CLI default selections. The resulting local v13 package SHA-256 is `a1e9196c032bbab7df33accc53e1ce1c9719e4ec96ecc5df6a069e5a7aefebbb`. The scoped run and the frozen r3 gate campaign retain their respective artifact identities.

**Final independent review: CHANGES REQUIRED.** The reviewer independently confirmed the 42 registered r3 cases, the matched scale archives and phase diagnostic, the frozen product evidence, and the v13 focused recovery reports. The original G7 process remained open at review on the two M4E completeness assertions and the Spring metric scope assertion in the protected reactor. Those expectations were not resolved by the later human closure.
