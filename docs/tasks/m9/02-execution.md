# M9.2 — semantic and evidence verification

Date: 2026-11-11–12. **CONFIRMED / implementation and bounded verification delivered; full Task-2 acceptance is not claimed.** The existing authenticated Java generated-code/degradation cells are **NOT RUN / capture unavailable**. The human confirmed that both historical `m844-native-validation-*` captures and worker `mvp-261fd9b5c76d428f8c8291c498b55319` no longer exist. This confirms absence, not a waiver or passing evidence. M8 remains human-closed; M9 and M10 are not promoted.

The [registered controls](02-cases.json) and [evidence index](02-registration.json) own the exact cases, candidate and immutable external results. No commit, push, worker creation, host target build, source disclosure or independent human review occurred. Route: lead architect/semantic analyst → implementer → verification self-check; javac, the existing formal oracle and the pinned Spring container are independent instruments, not an independent reviewer.

## Before and resulting behavior

Entry: clean HEAD `82ea7a722e1a7d329bfa201e92b7fd046ebf829a`; M9.1 registered against a pre-merge dirty candidate. The original preflight rejected only the changed HEAD metadata; runtime, corpus and label bytes matched. An append-only, metadata-only baseline revision passed the full 16-repository source rehash before testing. The original Task-1 registry is unchanged.

The new controls reproduced two correctness defects:

1. Recovery imports, wildcard package guesses and Android scaffolding could create canonical types, callable/field targets, guessed signatures and apparently verified origins without declaration/artifact/generator evidence. An unknown argument could select an overload. A further control reproduced `choose(null)` resolving to a callable whose parameter erasure came from an unproved import.
2. A warmed digest cache accepted changed dependency bytes when path, length and old mtime were unchanged.

**Implemented:** parser scaffolding cannot establish an origin or canonical type/signature; speculative external member construction is removed; argument types must have evidence before recovery overload selection; guessed nested types cannot suppress a field obligation. Written source fields and independently proven source/JDK/JAR bindings remain usable. Unmappable declarations, calls and type uses retain original spans, diagnostics and ledger rows, becoming typed capability gaps through the existing normalizer. Mutable artifacts are rehashed on every request; immutable parsed archive caches remain content-addressed. The frontend is versioned `3.28.2-m9.2-evidenced-recovery-v12`, with explicit Spring scanner/constructor-consumer registration.

The approved **950‰** completion predicate, canonical identity format, score formula, weights and trust/qualification contracts are unchanged. A source-proven positive still earns passive completion. The original missing Spring/JPA/generated-gRPC CLI fixture now correctly stays `PARTIAL` with its gaps, rather than earning completion from guessed declarations. Source recovery may therefore report lower measured resolution; it is not relabeled as exact semantics.

Changed files are confined to the JavaParser recovery/resolution boundary, its provider and two Spring version consumers; four new semantic/interaction/census test classes; the affected Java/Kotlin/Maven/CLI goldens and Spring oracle cache selection; LF fixture checkout policy; and Task-2 controls/evidence/testing documentation. `docs/current-state.md` and the M9 task index now record bounded delivery and the confirmed unavailable inputs. No roadmap scope, historical acceptance, score policy or future milestone was changed.

## Controls and interpretation

| Claim | Observed evidence and boundary |
|---|---|
| I01 | Actual acquisition/readiness versus all 16 independent censuses: **18,367/18,367 physical paths, hashes and lengths**, complete ownership ledger, **376 descriptors**, **82 passive roots / 910 contexts**, **718 unknown dimensions** retained. Every descriptor has passive model evidence; this is not proof of effective-context, variant or generator completeness. |
| J01 | New diagnostic-clean javac fixture checks **13 written invocations in each of strict/recovery lanes**, including selected declaration, execution owner, erasure, origin and complete source span. The existing modern fixture independently checks 15 invocations and all 18 relationship families. These establish 41 bounded candidate/compiler invocation comparisons, not external-repository accuracy. |
| J02 | Seven new negative/integrity/degradation methods plus existing missing receiver, overload, imports, duplicates, platform and source-layout controls. Preserve unresolved/unmappable outcomes, actual source fields and original spans; no fabricated recovery declaration or origin is admitted in these controls. |
| B01 | Maven/BOM/CI-friendly/reactor, Gradle/KMP/root selection, ordered JAR/platform and source ownership goldens pass in the named suites. Real authored Lombok generator/compiler controls remain bounded evidence. **Authenticated MapStruct/Protobuf/generated-output capture replay: NOT RUN**, because the required captures no longer exist. |
| S01 | **96** authored profile × property × order × qualifier × primary/fallback cells, existing staged/truth-region/frontier/witness controls, **70/70** formal labels and **64/64** SAT problems. The existing runtime oracle matches **23** normalized binding cases against hash-pinned Spring **6.2.0**. Replay type and framework/version scope remain explicit; this does not certify every Spring version or container mechanism. |
| L01 | Native TypeScript captures/source-map/coordinate/bridge contracts and existing Kotlin annotation controls pass. Real mixed-capture cold replay verifies **33/33 M1 sources / 8 contexts**, a broken sibling, two reopened obligations and `qualified=false`. This is authored producer evidence and consumer replay, not a new worker observation, all-language semantic certificate or production-method qualification. |
| D01 | Authored exact-JAR removal/restore preserves both call obligations/spans, withholds certainty after removal and reproduces canonical output after restoration. Existing native census-drop/source-map controls preserve omissions. **The registered authenticated artifact/generated/config removal-restoration campaign is NOT RUN**; this authored control does not replace it. |

The standalone M2 oracle also passes **34/34 labels**, including 30 admissible compiler targets, four error-compilation controls and three identifier relations. These raw compiler observations are not counted again as candidate/frontend comparisons. Unlabeled external semantic precision/recall is **N/A**. An honesty pass is not a semantic-completeness pass.

The final packaged candidate also ran the three preregistered development checkpoints with the frozen P-lane flags, pinned JDK, 2-GiB heap, empty dependency cache and external workspace. Every physical inventory/content digest matches its independent census; all three correctly retain `PARTIAL` / `PARTIAL_RECOVERY`, exit 3, with no isolated build executed. These single-run wall times are operational observations, not comparative performance acceptance.

| Repository | Physical files | Contexts in receipt | Structure resolved / attempted | Usage resolved / attempted | Current gaps | Wall seconds |
|---|---:|---:|---:|---:|---:|---:|
| PetClinic microservices | 205 | 15 | 393 / 724 | 724 / 1,645 | 2,015 | 24.203 |
| PetClinic Kotlin | 105 | 3 | 261 / 546 | 463 / 1,362 | 1,490 | 18.172 |
| MapStruct examples | 283 | 53 | 1,105 / 1,382 | 2,195 / 4,100 | 4,538 | 44.859 |

These are analyzer-attempted, context-qualified populations, not independent external recall denominators or physical-file counts. Missing dependency/generated/context evidence is not repaired by guessing declarations. The registered older reports remain historical and are not paired current-candidate comparisons.

All three holdouts have now been observed by acquisition/passive-readiness accounting. No holdout source was executed or used to debug/select the implementation; semantic holdout scoring remains unrun. Do not describe their inventory/readiness inputs as unseen.

## Fixture repairs and preserved failures

- Native TypeScript source/NDJSON checkout bytes were CRLF while capture hashes/offsets referred to the checked-in LF originals. Restored exact Git blobs, verified the source SHA-256 `77145e0ed8ec3d6b0e23cb7d44c8af3d4a32c39bacbde334d57563caaca3a5be`, and pinned LF in `.gitattributes`. Capture labels and generator outputs were not rewritten.
- The platform POM smoke fixture omitted `analyzer-treesitter/pom.xml`; added it and correctly expects nine modules including root.
- The Kotlin test silently selected a workstation corpus or fallback and asserted historical 980‰ coverage without dependency closure. Replaced it with a fixed three-annotation original-span control; no external Kotlin correctness result is inferred.
- Replaced unsound recovery goldens with explicit missing-evidence expectations. The source-callable identity control also preserves dependent return-type rows whose owner cannot be established. The Spring fixture explicitly enables qualifier processing and protects retained discovery-only gaps separately from registration/binding issues.

Original red runs, fixture failures, the pre-final experimental JAR and their metadata remain at the external evidence root. No failing result was edited into a pass. The final evidence index identifies the actual rerun subset and final candidate; unrelated passed suites are reused only where their inputs/behavior did not change.

## Verification and reproduction

External evidence root: `D:/UIT/evo-benchmark-cohort/m9-task2-20261111-r1/`. SDKs, caches, acquisition CAS, raw outputs and distributions stay outside Git. The evidence index pins final source/JAR/labels and the current external preflight registry. Root `clean verify`, M9.4 scale/UI/performance campaigns and M9.5 independent review were not run or implied.

The bounded handoff set has **516 distinct JUnit cases / 59 suites / zero failures, errors or skips after the named repairs**, plus **13 Python protocol/launcher tests**. Repeated runs of the same case are not added to this denominator. Source/report aggregate hashes and the exact suite names are in the evidence index; generated Surefire reports are referenced in place and cannot be reused after overwrite without digest verification.

| Module | Distinct cases | Suites |
|---|---:|---:|
| analyzer | 228 | 23 |
| analyzer-maven | 43 | 5 |
| analyzer-javaparser | 196 | 20 |
| backend | 25 | 9 |
| cli | 24 | 2 |

The final frontend selection includes `SourceToSpringPlanTest` and `ResourceFieldIngestionTest`; there is no fast-profile exclusion. Provider/version assertions and direct backend/CLI consumers are included. Inner tests are separate from this integration set: an observed seven-control run takes 2.951 seconds of Surefire body time; Maven startup/compilation exceeds the five-second inner-loop target. The 17-suite frontend integration attempt took 73.196 seconds wall time; the affected 15-case owner recheck took 17.544 seconds. Final backend integration/install and CLI scan/package took 26.675 and 40.241 seconds respectively. These are verification durations, not a product performance claim.

Final executable: **46,215,566 bytes**, JAR SHA-256 **`972c463c4f4141b36a20d73ddc0a50670c8d1f749f3d893c87fa8a9620c23486`**; runtime source SHA-256 **`e7972b98d1ab7cdffe19855cb1ae8a42943272ffec61f7b894e2f2b91d645a7b`** (635 files). Eight affected/direct runtime class entries match the compiled modules byte-for-byte; test signing fixtures and the new test classes are absent from the distributable. This is a selected-module build/package claim, not a clean-reactor build claim.

The external `candidate-registry-v3.json` is append-only, SHA-256 **`0b51f9152981f5c0228445c0cf17aaf0eee386838d17731470ded8972f8e7975`**. Its r2 parent passed `--verify-sources` on all 16 repositories. The v3 amendment adds exact original fixture/control/head pins while inheriting identical source/corpus/JAR/criteria; its own complete pin preflight passes. No unchanged corpus rehash was repeated merely for the metadata amendment. Both original and final control preimages are preserved; the reconstructed original authored controls/test bytes match the prior frozen SHA-256 values exactly.

```powershell
python benchmarks/g2-pipeline/m9_protocol.py preflight --registry D:/UIT/evo-benchmark-cohort/m9-task2-20261111-r1/candidate-registry-v3.json
```

Select this explicit candidate revision for current checks. Default Task-1 preflight intentionally rejects changed executable inputs; the immutable Task-1 registry was not rewritten to conceal that change.

Reproduce focused controls and the real pinned Spring oracle:

```powershell
.\mvnw.cmd -q -pl analyzer-javaparser "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=M9RecoveryEvidenceTest,M9JavacOracleTest,SourceRecoveryAttributionTest,ResolutionInputsTest,JmodPlatformTest" test
.\mvnw.cmd -q -pl analyzer "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=M9InteractionTest,TruthRegionEvaluationTest,BeanRegistrationTransitionsTest,InjectionBindingsTest" test
.\mvnw.cmd -q -pl analyzer "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=SpringBindingOracleIT" "-Dspring.oracle.repository=C:/Users/Admin/.m2/repository" test
python -m unittest discover -s benchmarks/g2-pipeline -p 'test_m9_*.py' -q
```

Use current trusted platform dependencies when running a module alone. The [testing guide](../../testing.md#m92-explicit-evidence-checks) describes the explicit census inputs and nonexistent output directory. The census checkpoint writes ordinary product CAS/readiness outputs and compact reconciliations, rather than a milestone reproducibility package. Its wall/memory/disk behavior is not M9.4 performance acceptance; no peak-memory, cold/warm or percentile claim is made.

## Remaining boundary and exact next action

**CONFIRMED human / missing inputs:** both historical Java native-validation captures and their Core MVP worker are gone. Their associated B01/D01 cells remain `NOT_RUN_CAPTURE_UNAVAILABLE`. Do not retry the deleted paths, forge a capture/authority, recreate workers merely to accumulate evidence, or run target lifecycles on the host. These are existing M9 cells; withdrawn M8 qualification campaigns are not reopened.

All separable implementation and bounded checks in this record are delivered. The evidence index also closes the reporting list for all 18 required programming packs: unscored current native scopes and `REQUIRED_UNQUALIFIED` markers stay explicit. Full M9.2 acceptance still needs compatible, prospectively registered replacement evidence for mandatory unrun native scopes, particularly B01/D01, or an explicit human change to those acceptance criteria. Neither is inferred from deletion; full A01–A12 production qualification is not silently restored as an M8 exit. M9.3–M9.5 and M10 remain unaccepted; no later task was automatically started. The exact next action for full Task-2 closure is to establish that replacement-evidence/acceptance disposition before scoring unavailable native cells. There is no unresolved numerical-policy or routine implementation approval request.
