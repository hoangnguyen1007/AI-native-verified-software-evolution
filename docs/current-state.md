# Current State

Last verified: 2026-09-01

## PROJECT PHASE

SE121 - Software Architecture Intelligence Platform.

## CURRENT MILESTONE

M-1 - Project Operating System Hardening.

## ACTIVE TASK

Human review of the completed M-1 governance, roadmap, and confirmed visual-product outcome contract.

## STATUS

M-1 governance and confirmed product-outcome documentation are implemented and locally verified. They remain uncommitted and awaiting final human approval. The human has confirmed that the SE121 outcome must be a complete visual platform with detailed metrics, an explainable architecture score, violations, focused graph exploration, and evidence navigation. M0 production work has not started.

## LAST COMPLETED MILESTONE / TASK

- R1 corrected JavaParser + SymbolSolver viability evidence package.
- M-1 governance implementation and consistency verification.

## NEXT EXPECTED TASK

After explicit human approval of M-1: begin M0 with reproducible build/toolchain hardening and parser-neutral semantic/identity/provenance contracts. Do not begin parser production extraction first.

## BLOCKERS

- Human review/approval of the M-1 diff.
- Human commit remains pending.

## REPOSITORY REALITY

- Last verified repository commit: `8a4e6e1bc6b8b29ee49ba37d78c9037ed8db3c90`.
- Root Maven reactor contains `analyzer` and `backend` modules.
- Production Java source does not yet exist; tracked source trees contain placeholders.
- `frontend/` and root `tests/` have no tracked implementation.
- The tracked R1 PoC and raw results live under `benchmarks/poc/parser-eval/`.
- No M0 semantic frontend, graph, policy engine, CLI, backend API, or workbench implementation exists.

## RECENT VERIFIED EVIDENCE

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
| G-1 M-1 operating system | AWAITING HUMAN APPROVAL | Governance consistency, current-state accuracy, tech-first roadmap, no provider-specific role assumptions |
| G0 reproducible foundation | NOT STARTED | Wrapper/toolchain/enforcement and clean reproducible build |
| G1 semantic/metric contract | NOT STARTED | Parser-neutral facts, identity, uncertainty, provenance, metric/assessment envelopes, deterministic serialization |
| G2 frontend/build model | NOT STARTED | Expanded ground truth, safe multi-module/build-model support, parser gate |
| G3 Spring intelligence | NOT STARTED | Pre-registered correctness evidence |
| G4 graph/metric/query layer | NOT STARTED | Invariants, deterministic construction, metric correctness, focused projections, storage-neutral query contracts |
| G5 policy/evidence/assessment | NOT STARTED | Mutation/negative controls, complete Evidence Bundles, score golden/sensitivity/missing-evidence tests |
| G6 Track A release | NOT STARTED | Multi-repository correctness, robustness, performance, accessibility, and complete visual product workflow |
| G7 Track B evolution | NOT STARTED | Compatible snapshot identity and labeled evolution events |

## DURABLE STATE UPDATED BY M-1

- `AGENTS.md`
- `.agents/rules/`
- `.agents/skills/se-project-engineering/SKILL.md`
- `.agents/agents/`
- `.agents/workflows/`
- `docs/project-context.md`
- `docs/current-state.md`
- `docs/roadmap.md`
- `roadmap_for_user.md`
- `roadmap_for_user_vi.md`
- `docs/architecture/product-outcome.md`
- `docs/architecture/architecture.md`
- `docs/architecture/knowledge-graph.md`
- `docs/decisions/ADR-002-product-outcome-and-explainable-assessment.md`
