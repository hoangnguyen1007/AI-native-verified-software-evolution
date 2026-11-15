# M11.5 — Durable comparison and product integration

2026-11-14. M10 had authorized Track B. Main was at `4b52040` with the prior M11 slices partly in the working tree. G7 was unadjudicated.

**CONFIRMED / implementation.** The [comparison archive](../../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/EvolutionResultStore.java) stores bounded immutable section trees with a verified census and an atomically installed result reference. The [durable job adapter](../../../backend/src/main/java/com/evolution/backend/query/EvolutionJobs.java) pins requests, journals progress and cancellation, verifies eligible cached results and retains conflicting roots. The [query service](../../../backend/src/main/java/com/evolution/backend/query/EvolutionQueries.java), [CLI](../../../cli/src/main/java/com/evolution/cli/EvolutionCommand.java), [OpenAPI](../../../backend/src/main/resources/api/openapi.yaml) and [Workbench](../../../backend/src/main/resources/web/evolution.html) share one redacted projection. The server prepares the page index for canonical snapshots already published by Workbench. See the [implemented contract](../../architecture/m11-compatible-snapshot-evolution.md#9-implemented-m115-consumer-contract).

| Control | Implementation and verification scope |
|---|---|
| E16 publication and recovery | Cold reopen, damage to a selected leaf, stale/foreign cursors, staged cancellation, injected disk pressure, simultaneous equivalent publication, conflict retention, process restart and restored graph availability. The scope recovery control interrupts after a durable checkpoint, resumes with a different input page size, and matches the uninterrupted archive. The direction control swaps `RELATION_ADDED` and `RELATION_REMOVED` on a closed authored fixture. |
| E17 product behavior | Schemas, graph-only CLI/HTTP views and export equality, loss-aware disclosure, pinned graph reads and expansion, keyboard focus and 320 px layout, and stale response protection. Public descriptors preserve proof IDs while redacting configuration assignments and embedded literals. |

The final cross-module command uses JDK 21.0.12.1 and the wrapper-pinned Maven 3.9.16. The report files are local Surefire outputs.

```powershell
.mvnw.cmd -B -ntp -q -pl cli -am '-Dtest=EvolutionCliTest,EvoAppTest,EvolutionApiTest,ApiSpecificationTest,ArchitectureHttpServerTest,EvolutionCompatibilityTest,ConditionalRegionDeltaTest,FindingEvolutionTest,SourcePlanProjectionTest,FileSnapshotComparisonTest,PartitionedGraphStoreTest,OutputCensusTest,CanonicalGraphPipelineIntegrationTest,PublishedReportsTest,WorkbenchApiMainTest,TreeSitterSourceFrontendTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
```

The bounded pre-handoff pass reported 144 executed checks across 16 suites with zero failures, errors or skips, including scope restart and history-registration provenance. Earlier attempts caught inherited CLI option collisions and an outdated absolute OpenAPI path-count assertion. One run of the existing Workbench restart check returned `NO_MATCH` under concurrent analysis load. The check passed in later selections.

The browser driver is [evolution-workbench.browser.cjs](../../../backend/src/test/js/evolution-workbench.browser.cjs). The registered PetClinic documentation pair uses commits `79b527a35d2141f619386bb94a9e18355aab7880` and `104410a0cdac5ddcc4e43f659db0732cdc972c0d`. Both inventories were independently recreated byte for byte with `git ls-tree -r -z` and `git cat-file --batch`, with 205 blobs each and the exact [registry](cases.json) SHA-256 values. Passive commands used `scan --all-files --release 17 --offline --build off --revision COMMIT`. The deliberately degraded control pins the same baseline and an unavailable target graph. The first 90 s integration attempt was censored. Edge failed to start through the headless driver on this host, and Chrome was used for the browser checks. A pilot using `git archive` had Git attribute transformations and is excluded from the exact-input claim.

| Exact-input result | Verified outcome |
|---|---|
| PetClinic documentation pair | Baseline graph `sha256:4ad5f4a95862e7bd1ec6bd8ba2cb06b955743c44c8236cc1327bfbbf224855d9`, target graph `sha256:59b79c5603f71c9b57ae50f6ac793ceebccb0eafe9cd8a5dda75f6caa5c4562b`. Archive `sha256:7d794c9d9bfd51358fee04cd95b34d791a99f3901ba24b28ebcd0c79736a2f7d`, `PARTIAL`, 58 context rows, 2,228 gap rows and 0 emitted events. |
| Missing target | Archive `sha256:4db8dea525305ff63847aff1eb076fbaf3c34f6974e736d14b3fa18d5db16e2b`, `PARTIAL`, target input `MISSING`, 1,078 gap rows and 0 emitted events. |
| Consumer replay | Chrome 157 opened both results, expanded a pinned context, preserved keyboard order and 320 px reflow, and discarded the delayed response from the previous selection. CLI and HTTP summaries agreed, and the degraded HTTP query and export returned equal pages. A fresh server reopened both archive IDs and passed the same checks. Submitting the exact pinned request through the Workbench returned the verified historical archive again. |

**Limits.** Scope recovery starts after the most recent committed high-water key. If interruption happens during graph intake or no compatible checkpoint survives, the worker replays from pinned inputs. Report, bridge and history processing after scope alignment can replay. The public projection withholds exact configuration assignments. The bounded Workbench controls use captured descriptors and side graph slices. Independent review, deterministic historical campaign repeats, broader history/region product parity and G7 adjudication belong to M11.6. This task ran no target lifecycle.

**Exact next task.** M11.6 freezes the engineering candidate and the registered historical/author-generated operands, runs the scoped campaign, and obtains independent G7 adjudication.
