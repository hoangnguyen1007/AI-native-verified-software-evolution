# Demo Step 5 — Strict finding comparison

**CLOSED for the pinned full-pipeline demo (2026-11-16).**

The pinned `client.no-store` comparison distinguishes feasibility of the two `dev` configurations from proof of the selected binding in each snapshot. An independent source census closes the candidate's binding denominator for the sealed fixture. M11 can use it to prove `RESOLVED` with `dev` enabled and `CLEAN` with `dev` disabled. The comment-only control gives `PERSISTED` and `CLEAN` in those same cells. [M11 absence requirements](m11-compatible-snapshot-evolution.md#4-proofs-lifecycle-and-numeric-comparison) · [Isolated verification](demo-repair-verification.md)

## What changed

The baseline and control encode the injection site as a derived constructor parameter. The old comparison could authenticate derived bean declarations, but rejected the constructor parameter even when its Java declaration and type-use records were present. The comparison now checks both records against membership in the relevant frontend result, verifies that they belong to the same parameter and source document, and checks the signature and parameter position against the declared owner. It accepts the two recorded constructor ingestion versions. Other unsupported or mismatched derivations remain unproved.

The old symbolic finding comparison treated missing route truth as missing configuration feasibility. It now obtains feasible regions from the authenticated symbolic traces when route truth is missing, while keeping the possibly feasible domain conservative. A feasible cell with missing route evidence contributes to `UNCERTAIN`, so the solver never uses that cell as a proof of absence. The comparison version is `architecture.evolution.internal:m11.6-v2`. It produces new comparison identities for the same immutable graph and report inputs.

## Original coverage gap

The original candidate report lacks a complete M6 rule predicate and projection. Its passive graph has no selected binding edge, but preserves 17 Java `CATEGORY_PARTIAL` records, four `DESCRIPTOR_INCOMPLETE` records from two implicit constructor obligations and two configuration signatures, and two discovery-stage `REGISTRATION_NOT_EVALUATED` records. The scanner records the declaring type as observation owner and the constructor as resolved target, so the original owner lookup misses the constructor descriptors. Interpreting the empty edge list as a definite absence would lose that information.

## Closed census for the pinned repair

The verifier reopens all four candidate files from the authenticated VM capture, verifies each hash against the plan and the input manifest, and independently reconstructs the repository snapshot identity. A parse-only JDK 21 pass checks the complete sealed source fragment. It requires the `app` component scan, the two component declarations in their exact files, and the `dev` profile on `Client`. Each bean must have one public, empty constructor with no parameters. Fields, inherited application members, extra methods, imports, nested declarations, additional files and custom annotations fall outside this census. This discharges the two implicit constructor obligations in the selected scope while the original graph and report keep their recorded gaps.

The census carries the snapshot, graph, context, source file digests, parser version and classpath census digest. The registered classpath digest `sha256:a7c5763f21dbaf56f84c53291c74e87c86d6232db1e2e3734443fae938f7d0e2` hashes the nine captured binary input rows sorted by logical name. The JAR bytes were rehashed against those rows and their ZIP inventories checked for `app/` entries, including multi-release entries. Ordinary M11 compatibility still checks exact classpath order across the original operands.

M11 admits the census only on the compatible right report for `client.no-store` version 2. It verifies the matching Spring plan and source-anchored producers, the closed Boolean `dev` domain without external configuration inputs, and the absence of contradictory binding rows in the actual projection. It then uses the checked empty binding predicate as the negative operand and intersects it with the authenticated baseline truth regions. The comparison request and `RESOLVED` proof bind the census digest. Missing or mismatched bytes, a retained constructor parameter, an unrecognized analysis scope or a conflicting projection leave this proof unavailable.

The PR preview and CLI mark `candidate-finding` as `PASSED` with `FINDING_RESOLVED_IN_DEMO_SCOPE`. The underlying result stays `PARTIAL` where aggregate M6 coverage remains open. The runtime result is still grounded in the separate authenticated VM capture. The comparison without the scoped census still withholds candidate absence. [Verifier and result contract](demo-repair-verification.md)

## Historical run before the census

**CONFIRMED / 2026-11-16.** The source-generated regression reproduced `UNKNOWN_FEASIBILITY` for the comment-only control before the fix. The final run passed 24 JUnit cases across `FindingEvolutionTest`, `ConditionalRegionDeltaTest`, `EvolutionCompatibilityTest`, and `M6PolicyIntegrationTest#repairVariantsRetainTheirExactChangedSourceInPassiveGraphs`, with zero failures, errors or skips.

The packaged [live browser journey](demo-live-diagnosis.md#reproduce) used the original snapshot, policy and VM capture digests. Edge `157.0.4341.60` opened the live citation, downloaded the original OpenRewrite patch and showed the same machine-readable checks as `evo verify-repair`. CLI exit codes were `0` for `VERIFIED_DEMO`, `4` for a wrong candidate snapshot and `2` for tampered proposal bytes. The VM capture was reused.

| Strict pair | Status | Transition | Comparison identity |
|---|---|---|---|
| Baseline → repair | `WITHHELD` | `UNCERTAIN` | `sha256:791ba324956992ac35f48baa26d0fac285b3f507aba5a4d101cb04ce5cf8b3f7` |
| Baseline → comment-only control | `PARTIAL` | `CLEAN` and `PERSISTED` | `sha256:b074fdf359b10e0e310b071f69d2b189b34bb593ef9967fc7f1e5d2e542c8413` |

The control's regions are proved. Its aggregate rule retains `RULE_COVERAGE_UNPROVED`; the candidate also retains `ROUTE_TRUTH_UNPROVED` and `ROUTE_REGION_UNPROVED`. Neither comparison truncated its finding rows.

## Closure run

**CONFIRMED / 2026-11-16.** The packaged [live browser journey](demo-live-diagnosis.md#reproduce) ran on Edge `157.0.4341.60` with the [original graph, policy and VM capture hashes](demo-live-diagnosis.md#observed-run). Keep the capture's `context/inputs/candidate` tree together with its manifest and transcript when reproducing this check. The source census used JDK `21.0.12.1+1-LTS-4` and rehashed all four candidate inputs. It certified zero applicable injection sites.

| Profile | Repair | Comment-only control |
|---|---|---|
| `dev` off | `CLEAN` | `CLEAN` |
| `dev` on | `RESOLVED` | `PERSISTED` |

The scoped candidate comparison is `sha256:21a66ebe26a5e9bfcbdcc7fea768d8a1de0471d9e3084c41d57c39fe0c8f0b01`. Both comparison summaries keep `PARTIAL / RULE_COVERAGE_UNPROVED` for aggregate coverage and report no truncation. The local PR preview passed all four checks. The CLI returned the same JSON and exited `0` with `VERIFIED_DEMO`, `4` for a wrong candidate snapshot, and `2` for tampered proposal bytes. This run reused the authenticated nine-check VM capture from Step 3.

The focused Maven selections passed 26 JUnit cases with zero failures, errors or skips. The analyzer selection was `FindingEvolutionTest,ConditionalRegionDeltaTest,EvolutionCompatibilityTest`. The source-generated `M6PolicyIntegrationTest#repairVariantsRetainTheirExactChangedSourceInPassiveGraphs` checked the uncertified candidate, certified candidate, retained dependency and foreign source bytes. The backend selection was `ApiSpecificationTest,DemoRepairCaptureTest`. [Current delivery status](../current-state.md)
