# Current State

Last reconciled: 2026-09-02. Fresh checks and their scope belong in the task handoff; historical gate evidence is identified below.

## PHASE AND MILESTONE

SE121, Track A + B. M1 semantic/identity/uncertainty/provenance contracts are implemented and committed at `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`; G1 is recorded passed. M2 contract design and a bounded JDK oracle pilot are prepared and verified at their stated scope; production extraction is not implemented and G2 is not passed.

## ACTIVE TASK — PAUSED BY HUMAN

On 2026-09-02 the human paused work until a later conversation/token reset and instructed Codex to own subsequent work without Antigravity/Gemini. Do not automatically resume, schedule work, or dispatch external workers. The human will commit; Codex has not committed or pushed.

The human explicitly directed implementation using the sufficiently mature M2 contracts: do not reopen D1–D3 approval or expand preflight/design/oracle work unless implementation exposes a concrete correctness blocker. The [M2 contract](architecture/m2-semantic-frontend.md) is the provisional implementation baseline. Prioritize production code, tests, fixtures alongside code, verification, then documentation only when truth changes. Work in vertical slices; Codex owns difficult semantic/identity issues.

The new [oracle pilot](../benchmarks/m2-ground-truth/README.md) passed 34/34 registered call labels and three identifier relations in a coordinator rerun: 30 targets were adjudicated and four error-case labels verified withholding. This is bounded design evidence, not the full semantic ground truth or a JavaParser accuracy claim. Raw results are archived with byte-copy hashes. Earlier in this task, before implementation drafts, the existing reactor passed 26 tests with no failures/errors/skips; it was not rerun at pause. See the [M2 handoff and evidence](reproducibility/m2-design-2026-09-02.md).

Implementation preparation produced an identity test draft, adapter POM/coordinate stub and tests, and four candidate fixture families with 23 occurrence expectations. None is an integrated production adapter. They are preserved outside the reactor in the versionable [pause handoff and draft archive](reproducibility/m2-pause-2026-09-02/README.md). The incomplete identity test was moved out of Maven test sources, preserving its exact bytes. Later targeted builds hit Maven/JUnit-cache access failures before executing these tests; no TDD red/green or final build success is claimed for the drafts.

Antigravity is discontinued for this work by the latest human instruction. After explicit repository-payload consent, a Gemini 3.1 Pro High session initialized but ended CANCELED following a CLI file-permission denial; it produced no implementation/review result or successful nonce round-trip. The task-started CLI server was stopped at pause. Historical [dispatch artifacts](reproducibility/m2-antigravity/README.md) are inactive, not prerequisites for continuation. The prior [orchestrator skill revision](reproducibility/antigravity-orchestrator-skill-2026-09-02.md) remains a separate maintenance record.

Prior repository governance hardening remains complete and uncommitted; its [verification record](reproducibility/governance-hardening-2026-09-02.md) retains the actual checks and limits. No product gate advanced.

The human approved JavaParser + SymbolSolver as the primary SE121/M2 frontend on 2026-09-02. This confirms the implementation choice, not universal accuracy, performance superiority or G2 acceptance. See [ADR-001](decisions/ADR-001-parser-technology.md). The replaceable SemanticFrontend boundary, validation gates and replacement triggers remain mandatory. OpenRewrite remains an independent comparator.

## REPOSITORY REALITY

- M-1 approved baseline: `86c4ca29fb747797df3e489d978804644a34f1ce`.
- M0 foundation commit: `375702f9b871dd78fbad99f8bc5994b7b2c499fb`.
- M1 contracts commit: `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`.
- Comparison package commit and task starting HEAD: `83797e840e414bf99a0f71117892da355d94be55`.
- Root Maven reactor contains `analyzer` and `backend`. Production Java consists of parser-neutral M1 contracts under `analyzer/src/main/java/com/evolution/analysis/contract/`.
- Root tests cover 24 M1 contract cases and two Java 21 build-boundary cases. Standalone benchmarks are outside root verification.
- R1 PoC: `benchmarks/poc/parser-eval/`. Independent experimental adapters/comparison: `benchmarks/semantic-frontend-evaluation/`.
- M2 oracle pilot: `benchmarks/m2-ground-truth/`, separate from the reactor and legacy comparator. It uses only JDK 21/Python standard libraries; no production port/adapter is present.
- No production frontend adapter, workspace acquisition, Spring inference, graph, policy engine, metric/scoring calculation, CLI, backend API or workbench is implemented.
- `frontend/` and root `tests/` have no tracked product implementation.

## EVIDENCE AND LIMITS

Historical evidence, not rerun by this documentation/governance task:
- G0: wrapper 3.3.4, Maven 3.9.16 with pinned/checksummed distribution, Java 21 enforcement; clean Windows/Oracle 21.0.12.1 and Docker Linux/Temurin 21.0.12 builds. At M0 the empty module JARs matched; this is not a hash claim for the later M1 analyzer. Exact commands/hashes and negative checks are in [M0 evidence](reproducibility/m0-foundation.md).
- G1: 24 focused contract tests, 25 analyzer tests plus one backend test through root verification. Golden identities, full-inventory snapshot hashing, ordered classpaths, explicit target/status/uncertainty constraints and locale/timezone-independent serialization are documented in [M1 contracts](architecture/m1-contracts.md) and executable tests.
- R1 PetClinic snapshot `818c4136ea971c21674525f9053de0d9c7ad8cfe`: 30 files; A = 218 resolved / 238 unresolved out of 456 attempts; B = 456 resolved / 0 unresolved; 14 labeled cases matched expected configuration-specific outcomes. This is bounded viability, not universal semantic accuracy.
- R1 narrative still has a stale JDK 17 statement; saved provenance records Oracle 21.0.12.1. Correct the narrative when working on that report; preserve raw evidence.
- Comparative saved PetClinic CALLS results: 220 occurrences; B resolves 220 for both adapters, C resolves 215 for both. Resolution is not independently established full semantic correctness.
- Intake audit found 89 placeholder OpenRewrite spans in each saved PetClinic configuration, dropped provenance diagnostics on resolved M1 mapping, and project-local targets labeled DEPENDENCY by a shared heuristic (85 per adapter in B). These experimental defects remain unfixed; do not reuse the package as proof of provenance/origin correctness or G2 acceptance.
- Controlled generic-chain evidence and comparative limits are in [frontend comparison](research/semantic-frontend-comparison.md). Full semantic denominator, robust provenance, multi-module evidence and fair resource measurements remain incomplete.

## DECISIONS

Confirmed: Track A + B target; Java 21/Maven/monorepo; primary JavaParser/SymbolSolver choice behind SemanticFrontend; parser/storage-neutral domain; safe multi-module modeling; content-addressed analysis; stable query services; separate health/confidence; complete visual workbench.

Provisional: Neo4j Community adapter, Spring Boot API, YAML external policies, Cytoscape.js, exact metric/score formulas and thresholds. Bytecode validation remains ASSESS/HOLD. Track C remains optional.

## OPEN QUESTIONS AND BLOCKERS

Work is paused by request, not awaiting contract approval or Antigravity. On resumption, diagnose the observed Maven user-home/cache permission problem through supported execution permissions, then implement the first production slice. Do not bypass access controls or copy caches to route around denied access.

Open work: validate the adopted provisional canonical signatures/port/coordinates through implementation; complete independently reviewed M2 labels and support denominator; safe effective-Maven-model/classpath acquisition including root-module representation; broader labeled repositories; Spring/policy gate criteria; metric catalog, score formula/compatibility/confidence thresholds; graph/query/UI budgets and frontend framework selection at their later gates.

## QUALITY GATES

| Gate | State | Remaining acceptance boundary |
|---|---|---|
| G-1 governance baseline | PASSED (historical) | Current hardening is a separate maintenance task |
| G0 build foundation | PASSED (historical) | Preserve pinned toolchain and reproducibility |
| G1 contracts | PASSED (historical) | Preserve tested identity, uncertainty and evidence invariants |
| G2 frontend/build model | NOT PASSED; M2 paused, production adapter not implemented | Production frontend, full ground truth, safe multi-module model, frontend evidence |
| G3 Spring | NOT STARTED | Registered bounded correctness evidence |
| G4 graph/metric/query | NOT STARTED | Invariants, metric correctness, bounded storage-neutral queries |
| G5 policy/evidence/assessment | NOT STARTED | Negative/mutation controls, complete evidence, score safeguards |
| G6 Track A release | NOT STARTED | Complete visual product and multi-repository verification |
| G7 Track B | NOT STARTED | Compatible snapshots and labeled evolution events |

## EXACT NEXT TASK

When the human resumes, read the [pause handoff](reproducibility/m2-pause-2026-09-02/README.md), refresh repository context/Git state, resolve build execution permissions and implement one production vertical slice: minimal Unicode-safe Java symbol identity and neutral SemanticFrontend contracts in `analyzer`, plus the isolated `analyzer-javaparser` module for declarations and method calls with exact spans, origins, diagnostics and deterministic outputs. Use the archived drafts as unvalidated inputs; run focused and reactor verification. Continue relationship slices by dependency/risk without a new broad design phase. Use Codex only; do not troubleshoot or launch Antigravity. Preserve legacy raw evidence and existing user changes; no commit/push or silent entry into M3.
