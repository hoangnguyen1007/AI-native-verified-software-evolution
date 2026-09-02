# Current State

Last verified: 2026-09-02

## PROJECT PHASE

SE121 - Software Architecture Intelligence Platform.

## CURRENT MILESTONE

M1 - Semantic, Identity, Uncertainty, and Provenance Contracts (**COMPLETE; G1 PASSED; UNCOMMITTED**).

## ACTIVE TASK

Human review of the completed M1 parser-neutral contract foundation and G1 evidence, plus the pre-M2 JavaParser-vs-OpenRewrite semantic-frontend technology gate. Controlled javac-oracle and PetClinic call-only parity evidence is recorded, but the full semantic denominator, provenance strategy, and resource comparison remain incomplete; no technology lock is justified.

## STATUS

M1 is implemented and verified without a commit. Gate G1 passed on 2026-09-02 with immutable Java 21 contracts for repository/snapshot/module/source/entity/relationship/occurrence identity, complete spans, explicit semantic states, derivation, uncertainty, diagnostics, manifest/provenance, versioned metrics, architecture-health status, and separate analysis confidence. Canonical JSON and SHA-256 identities are deterministic across registered locale/timezone variants. No parser, workspace acquisition, Spring, graph, rule, scoring, API, UI, Track B, or Track C implementation was added.

## LAST COMPLETED MILESTONE / TASK

- R1 corrected JavaParser + SymbolSolver viability evidence package.
- M-1 governance, roadmap, architecture/product-outcome foundation, consistency verification, human approval, and baseline commit.
- M0 pinned wrapper/toolchain/build foundation and two-environment G0 verification.
- M1 parser-neutral semantic, identity, uncertainty, provenance, metric-envelope, and assessment-status contracts with G1 verification.

## NEXT EXPECTED TASK

Begin M2 design and ground-truth expansion before production frontend code: approve canonical Java symbol/signature rules, relationship-category coverage, adapter port shape, and positive/negative/unresolved/ambiguous/unsupported/error fixtures. JavaParser remains PROVISIONAL.

### M0 Completion Boundary

- Preserved commit `86c4ca29fb747797df3e489d978804644a34f1ce` in current history.
- Retained the existing root, `analyzer`, and `backend` reactor without adding modules.
- Added only wrapper/checksum, Java/compiler enforcement, pinned build controls, module test boundaries, build instructions, and reproducibility evidence.
- Passed clean verification in two documented environments and kept JavaParser PROVISIONAL.
- Added no production semantic extraction or later-milestone feature.

## BLOCKERS

None for human review of M1. M2 must not begin by bypassing the M1 contracts or its ground-truth gate.

## REPOSITORY REALITY

- Approved M-1 baseline: `86c4ca29fb747797df3e489d978804644a34f1ce`; human-accepted M0 commit and current starting `HEAD`: `375702f9b871dd78fbad99f8bc5994b7b2c499fb`.
- Root Maven reactor contains `analyzer` and `backend` modules.
- Production Java now consists only of the parser-neutral M1 contract foundation under `analyzer/src/main/java/com/evolution/analysis/contract/`.
- The analyzer has focused M1 contract tests plus its Java 21 build-boundary test; backend retains its Java 21 boundary test. All run through root `verify`.
- `frontend/` and root `tests/` have no tracked implementation.
- The tracked R1 PoC and raw results live under `benchmarks/poc/parser-eval/`.
- No semantic frontend adapter, workspace/build acquisition, Spring intelligence, graph, policy engine, metric calculation, scoring formula, CLI, backend API, or workbench implementation exists.

## RECENT VERIFIED EVIDENCE

- Wrapper 3.3.4 uses a repository JAR and a pinned Maven 3.9.16 ZIP with embedded SHA-256 verification.
- The Maven ZIP matched Apache's published SHA-512 before its SHA-256 was recorded.
- Local Windows/Oracle JDK 21.0.12.1 and Docker Linux/Temurin JDK 21.0.12 clean builds passed.
- Both environments produced SHA-256 `02a0444ff1abefa808a6ae25f3f0644fc226b839af81da8474a59f4a24ebe26d` for each currently empty module JAR.
- System Maven 3.9.15, compiler release 17, and JDK 17 were independently rejected by Enforcer.
- M-1 approval commit independently verified at `86c4ca29fb747797df3e489d978804644a34f1ce`; the worktree was clean before this documentation-only transition update.
- R1 PetClinic target commit: `818c4136ea971c21674525f9053de0d9c7ad8cfe`.
- Config A: 30 files, 456 attempts, 218 resolved, 238 unresolved, 0 relationship errors.
- Config B: 30 files, 456 attempts, 456 resolved, 0 unresolved, 0 relationship errors.
- Ground truth contains 14 cases; all matched their configuration-specific expected outcomes.
- This establishes bounded viability and classpath impact, not universal semantic accuracy.
- Runtime used by the saved local experiment provenance was Oracle JDK `21.0.12.1`; the narrative R1 report still contains a stale JDK 17 statement that requires later research-document correction without altering raw evidence.
- JavaParser remains PROVISIONAL.
- Focused M1 verification passed 24 tests covering identity, validation, equality, ordering, serialization, golden values, provenance, semantic states, and assessment envelopes.
- Root `clean verify` passed 25 analyzer tests and the backend Java 21 boundary test on wrapper-managed Maven 3.9.16 / Oracle JDK 21.0.12.1.
- Snapshot identity hashes the complete normalized file inventory, not only Java sources; every source document must match an inventoried path/digest.
- Analysis identity includes the ordered exact classpath because classpath order can affect resolution; set-like inputs are sorted and duplicate-free.
- Golden repository identity: `repository:sha256:0a98d9ce7629974142838c8611196506990eb604ea12eaf0822637bf992728b4` for the registered canonical coordinate fixture.
- Golden ordered-manifest analysis identity: `analysis:sha256:1920a08b08b2b362d5666f95e8847d2eb882bd447e4662b8ff28e3cda55143a5`.
- Canonical serialization produced the same bytes under `tr-TR`/Honolulu and `ja-JP`/Tokyo default locale/timezone variants.
- Human-confirmed product outcome: analyzer + canonical metrics/assessment + API + complete visual workbench; an analyzer or CLI alone is insufficient.
- Architecture health and analysis confidence are separate; insufficient evidence qualifies or withholds an assessment.

## ACTIVE ARCHITECTURAL DECISIONS

- Track A is the protected correctness foundation.
- Track B architecture evolution is the intended SE121 technical target after Track A gates.
- Track C remains optional moonshot work.
- Java 21, Maven, and a single monorepo are confirmed.
- Core semantic contracts must remain parser-neutral.
- Canonical graph/domain behavior must remain storage-neutral.
- Multi-module workspace/build-model intelligence is required.
- Analysis identity will be content-addressed from stable inputs.
- Backend/frontend domain access will use architecture query services.
- Bytecode validation remains ASSESS/HOLD.
- Eight permanent agent roles are retained; no role proliferation is planned.
- Track A requires the dashboard, structure explorer, focused graph, metrics/score, violations/evidence, Spring/impact, and provenance product workflow.
- Metrics and scores use stable versions, deterministic inputs, explicit status, provenance, and limitations.

## PROVISIONAL DECISIONS

- JavaParser + SymbolSolver as the semantic frontend implementation.
- Neo4j Community as an optional graph persistence/query adapter.
- Spring Boot for the backend/API.
- YAML as the likely external architecture-policy representation.
- Cytoscape.js as the likely primary graph visualization.

## OPEN QUESTIONS

- Safe effective-Maven-model/classpath acquisition design.
- Exact M2 canonical Java symbol/signature rules for local, anonymous, lambda, generic, overloaded, and modern Java constructs.
- Exact semantic relationship support denominator for the first production frontend.
- Track A validation repositories beyond PetClinic.
- Ground-truth scale and pre-registered Spring/policy gate criteria.
- Neo4j adoption after canonical graph/query requirements are implemented.
- Baseline metric catalog and exact inclusion/counting rules.
- Architecture score dimensions, formula, weights, caps, thresholds, and comparison compatibility.
- Analysis-confidence thresholds that qualify or withhold score output.
- Registered graph/query/UI performance budgets and reference environments.
- Frontend framework, design system, and graph library after bounded evaluation.

## CURRENT QUALITY GATES

| Gate | State | Required evidence |
|---|---|---|
| G-1 M-1 operating system | PASSED | Human approval and clean baseline commit `86c4ca29fb747797df3e489d978804644a34f1ce` |
| G0 reproducible foundation | PASSED | Clean wrapper builds and identical current artifact hashes on Windows/Oracle and Docker Linux/Temurin |
| G1 semantic/metric contract | PASSED | Parser-neutral immutable contracts, typed/content-addressed identity, explicit uncertainty/provenance, versioned metric/assessment envelopes, deterministic canonical serialization, golden/adversarial tests |
| G2 frontend/build model | NOT STARTED | Expanded ground truth, safe multi-module/build-model support, parser gate |
| G3 Spring intelligence | NOT STARTED | Pre-registered correctness evidence |
| G4 graph/metric/query layer | NOT STARTED | Invariants, deterministic construction, metric correctness, focused projections, storage-neutral query contracts |
| G5 policy/evidence/assessment | NOT STARTED | Mutation/negative controls, complete Evidence Bundles, score golden/sensitivity/missing-evidence tests |
| G6 Track A release | NOT STARTED | Multi-repository correctness, robustness, performance, accessibility, and complete visual product workflow |
| G7 Track B evolution | NOT STARTED | Compatible snapshot identity and labeled evolution events |

## DURABLE STATE UPDATED BY M1

- `docs/current-state.md`
- `docs/roadmap.md`
- `docs/project-context.md`
- `docs/architecture/architecture.md`
- `docs/architecture/knowledge-graph.md`
- `docs/architecture/m1-contracts.md`
