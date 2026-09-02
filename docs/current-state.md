# Current State

Last verified: 2026-09-02

## PROJECT PHASE

SE121 - Software Architecture Intelligence Platform.

## CURRENT MILESTONE

M0 - Reproducible Foundation (**COMPLETE; G0 PASSED; UNCOMMITTED**).

## ACTIVE TASK

Human review of the completed M0 Maven/Java 21 foundation and its reproducibility evidence.

## STATUS

M0 is implemented and verified but not committed. Gate G0 passed on 2026-09-02 with clean wrapper builds in local Windows/Oracle JDK 21 and Docker Linux/Temurin JDK 21 environments. Both environments used wrapper-managed Maven 3.9.16, passed one module-boundary test per module, and produced identical JAR SHA-256 values. No M1 or production semantic feature has started.

## LAST COMPLETED MILESTONE / TASK

- R1 corrected JavaParser + SymbolSolver viability evidence package.
- M-1 governance, roadmap, architecture/product-outcome foundation, consistency verification, human approval, and baseline commit.
- M0 pinned wrapper/toolchain/build foundation and two-environment G0 verification.

## NEXT EXPECTED TASK

Review and record the M0 diff. The next implementation milestone is M1: define parser-neutral semantic, identity, uncertainty, provenance, manifest, metric-envelope, and assessment-status contracts with tests. Do not begin production JavaParser extraction first.

### M0 Completion Boundary

- Preserved commit `86c4ca29fb747797df3e489d978804644a34f1ce` in current history.
- Retained the existing root, `analyzer`, and `backend` reactor without adding modules.
- Added only wrapper/checksum, Java/compiler enforcement, pinned build controls, module test boundaries, build instructions, and reproducibility evidence.
- Passed clean verification in two documented environments and kept JavaParser PROVISIONAL.
- Added no production semantic extraction or later-milestone feature.

## BLOCKERS

None for human review of M0. M1 work has not started.

## REPOSITORY REALITY

- Approved M-1 baseline: `86c4ca29fb747797df3e489d978804644a34f1ce`; current `HEAD`: `4cbcd1211d3abf9cc5ccbd5bcd975b9050e907ae` plus the uncommitted M0 diff.
- Root Maven reactor contains `analyzer` and `backend` modules.
- Production Java source does not yet exist; tracked source trees contain placeholders.
- Each module has one Java 21 build-boundary test; both are run by the root `verify` lifecycle.
- `frontend/` and root `tests/` have no tracked implementation.
- The tracked R1 PoC and raw results live under `benchmarks/poc/parser-eval/`.
- No M0 semantic frontend, graph, policy engine, CLI, backend API, or workbench implementation exists.

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

- Exact analyzer Maven submodule layout and package boundaries for M0.
- Safe effective-Maven-model/classpath acquisition design.
- Final content-addressed identity canonicalization rules.
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
| G1 semantic/metric contract | NOT STARTED | Parser-neutral facts, identity, uncertainty, provenance, metric/assessment envelopes, deterministic serialization |
| G2 frontend/build model | NOT STARTED | Expanded ground truth, safe multi-module/build-model support, parser gate |
| G3 Spring intelligence | NOT STARTED | Pre-registered correctness evidence |
| G4 graph/metric/query layer | NOT STARTED | Invariants, deterministic construction, metric correctness, focused projections, storage-neutral query contracts |
| G5 policy/evidence/assessment | NOT STARTED | Mutation/negative controls, complete Evidence Bundles, score golden/sensitivity/missing-evidence tests |
| G6 Track A release | NOT STARTED | Multi-repository correctness, robustness, performance, accessibility, and complete visual product workflow |
| G7 Track B evolution | NOT STARTED | Compatible snapshot identity and labeled evolution events |

## DURABLE STATE UPDATED BY M0

- `README.md`
- `docs/current-state.md`
- `docs/roadmap.md`
- `docs/reproducibility/m0-foundation.md`
