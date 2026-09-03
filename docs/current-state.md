# Current State

Last reconciled: 2026-09-03. Fresh checks and their scope belong in the task handoff; historical gate evidence is identified below.

## PHASE AND MILESTONE

SE121, Track A + B. M1 contracts are committed at `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`; G1 is recorded passed. M2 now implements all 18 relationship families in bounded tested forms, including method references, expression types, annotations, records, enum bodies and derived-member evidence. M2 acceptance remains open for final independent review and the complete reviewed ground-truth denominator; G2 is not passed and also requires M3 evidence.

## ACTIVE TASK — M2 IMPLEMENTATION

On 2026-09-03 the human resumed M2 implementation, using Codex only. The previous pause is superseded. Initial implementation started at `8f7582e`; the field-access continuation started clean at `5c1c415`, which includes the calls/type slices. No Antigravity/Gemini work, automation, agent commit or push was performed.

The human explicitly directed implementation using the sufficiently mature M2 contracts: do not reopen D1–D3 approval or expand preflight/design/oracle work unless implementation exposes a concrete correctness blocker. The [M2 contract](architecture/m2-semantic-frontend.md) is the provisional implementation baseline. Prioritize production code, tests, fixtures alongside code, verification, then documentation only when truth changes. Work in vertical slices; Codex owns difficult semantic/identity issues.

**CONFIRMED by implementation tests:** immutable strict-UTF-8 source inputs; source-plan identity (including module/source-set membership); exact ordered resolution inputs; Unicode-safe Java keys; exact original UTF-16 spans including escaped final delimiters; explicit semantic/parse/adapter errors; ledger/entity/source/catalog consistency. Existing M1 golden identities remain unchanged. See [M2 implementation evidence](reproducibility/m2-implementation-2026-09-03/README.md).

The four archived candidate fixtures were independently reviewed by a Codex reviewer and copied byte-for-byte into adapter test resources. The integrated adapter checks all 27 registered type/callable declarations and 23 invocations: 21 resolved targets and two correctly unresolved outcomes, exact callers/origins/spans, no unexpected registered-kind declarations/invocations, and deterministic reruns. Additional tests cover generic erasure, ambiguity, duplicate declarations, lexical execution owners, Unicode, real dependency JARs/order/removal, digest rejection and host-classpath isolation. This is bounded slice evidence, not full M2 accuracy.

The declared-type slice added immutable `JavaType`/`TypeUseRecord` output and parameter/return/field types, explicit inheritance/permits, throws, bounds and generic argument references. Known generic containers and callable erasures survive missing arguments; unknown targets remain explicit. Primitive/void information stays in type detail without invented entities. That slice used catalog `m2-java-2` and adapter `3.26.1-m2.2`; its [verification record](reproducibility/m2-types-2026-09-03/README.md) remains historical. No research/comparator or independent review campaign was added for that slice.

Field reads/writes distinguish simple assignment, compound assignment/increment, receiver reads and array-element updates. Source fields, inherited/hidden fields, static imports, enum constants and explicit dependency fields retain their actual declarations/origins. Unresolved explicit accesses remain occurrences; unclassified bare names remain unmapped ledger entries. The [field slice record](reproducibility/m2-fields-2026-09-03/README.md) is historical evidence for catalog `m2-java-3`/adapter `3.26.1-m2.3`; its former record boundary is superseded below.

The continuation from clean `dae013c` adds method/constructor references with functional compatibility checks; expression types and annotation-use identities; source records, compact constructors, component fields/accessors and default constructors; enum constant bodies with anonymous owners; and neutral `DerivedRelationshipRecord` evidence. Implicit members keep project origin, have no invented declaration span and cite owner/component inputs. Explicitly written record members suppress automatic body facts. Catalog is `m2-java-4`, adapter `3.27.1-m2.4`, parser pin 3.27.1. The new Java 21 fixture exercises every catalog row and independently compares all 15 explicit invocations with diagnostic-aware JDK 21 attribution. See [modern Java implementation evidence](reproducibility/m2-modern-2026-09-03/README.md) for tests, raw results, fixes and exact limits.

The [pause archive](reproducibility/m2-pause-2026-09-02/README.md), historical [oracle pilot](../benchmarks/m2-ground-truth/README.md) (34/34 labels, three identifier relations) and inactive [Antigravity package](reproducibility/m2-antigravity/README.md) remain unchanged. The new compiler comparison is one integrated modern fixture, not a renewed technology/comparator campaign.

The human checkpoint includes the prior governance and pause work. Its [verification record](reproducibility/governance-hardening-2026-09-02.md) remains historical. No product gate advanced.

The human approved JavaParser + SymbolSolver as the primary SE121/M2 frontend on 2026-09-02. This confirms the implementation choice, not universal accuracy, performance superiority or G2 acceptance. See [ADR-001](decisions/ADR-001-parser-technology.md). The replaceable SemanticFrontend boundary, validation gates and replacement triggers remain mandatory. OpenRewrite remains an independent comparator.

## REPOSITORY REALITY

- M-1 approved baseline: `86c4ca29fb747797df3e489d978804644a34f1ce`.
- M0 foundation commit: `375702f9b871dd78fbad99f8bc5994b7b2c499fb`.
- M1 contracts commit: `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`.
- Comparison package commit: `83797e840e414bf99a0f71117892da355d94be55`; current implementation continuation starts at checkpoint `dae013c`.
- Root reactor: `analyzer`, `analyzer-javaparser`, `backend`. The neutral frontend lives under `analyzer/src/main/java/com/evolution/analysis/frontend/`; parser libraries remain isolated in `analyzer-javaparser`.
- Root verification includes the original M1/build tests and new frontend/adapter tests. Exact final totals and raw console output are in the implementation evidence. Standalone benchmarks are outside root verification.
- R1 PoC: `benchmarks/poc/parser-eval/`. Independent experimental adapters/comparison: `benchmarks/semantic-frontend-evaluation/`.
- M2 oracle pilot: `benchmarks/m2-ground-truth/`, separate from the reactor and legacy comparator, using JDK 21/Python standard libraries.
- Production adapter pin: JavaParser/SymbolSolver 3.27.1. Source records, enum constant bodies, bounded implicit members, method references, expression types and annotation uses are implemented. Detailed support and remaining degraded cases are in the modern Java evidence record; no compiler-equivalence or scale claim is made.
- No workspace acquisition, Spring inference, graph, policy engine, metric/scoring calculation, CLI, backend API or workbench is implemented.
- `frontend/` and root `tests/` have no tracked product implementation.

## EVIDENCE AND LIMITS

Historical evidence (the M2 implementation record identifies fresh checks separately):
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

The initial Maven sandbox JUnit-cache denial was resolved through supported execution permissions. This continuation runs under the supplied unrestricted execution profile with explicit `MAVEN_USER_HOME` and `maven.repo.local`; no execution or human-approval blocker remains.

Current input boundaries: verified running JDK 21 platform image and explicit single-release JARs (M3 decouples analyzer runtime from analyzed platform symbol views and source language levels without claiming parser syntax support beyond verified versions); no alternate platform, module-output resolution, multi-release JAR or JAR manifest classpath support. Remaining explicit degraded cases include array constructor references/array length, annotation-value field reads, inferred `var` detail, typed lambda/catch parameter identities and unsupported functional target/inference contexts. M2 final acceptance is blocked on unfinished independent review and the complete reviewed denominator, not Maven execution. The Codex reviewer delivered concrete findings, then stopped because of its usage limit; final repairs have implementer verification only.

Open work: validate the adopted provisional canonical signatures/port/coordinates through implementation; complete independently reviewed M2 labels and support denominator; safe effective-Maven-model/classpath acquisition including root-module representation, build-declared source encodings without heuristic guessing, and decoupling analyzer runtime from analyzed repository target platforms (separating source language level from platform symbol view, toolchain/JAVA_HOME handling, platform provenance); early representative real-repository category/reason-level coverage checkpoint for G2; broader labeled repositories; Spring/policy gate criteria; metric catalog, score formula/compatibility/confidence thresholds; graph/query/UI budgets and frontend framework selection at their later gates.

## QUALITY GATES

| Gate | State | Remaining acceptance boundary |
|---|---|---|
| G-1 governance baseline | PASSED (historical) | Current hardening is a separate maintenance task |
| G0 build foundation | PASSED (historical) | Preserve pinned toolchain and reproducibility |
| G1 contracts | PASSED (historical) | Preserve tested identity, uncertainty and evidence invariants |
| G2 frontend/build model | NOT PASSED; all M2 categories have bounded implementation | M2 final independent review/full reviewed ground truth, M3 safe multi-module model, platform/charset decoupling, and early real-repository category/reason-level coverage checkpoint |
| G3 Spring | NOT STARTED | Registered bounded correctness evidence |
| G4 graph/metric/query | NOT STARTED | Invariants, metric correctness, bounded storage-neutral queries |
| G5 policy/evidence/assessment | NOT STARTED | Negative/mutation controls, complete evidence, score safeguards |
| G6 Track A release | NOT STARTED | Complete visual product and multi-repository verification |
| G7 Track B | NOT STARTED | Compatible snapshots and labeled evolution events |

## EXACT NEXT TASK

Complete one independent read-only Codex review of the final M2 implementation and modern fixture evidence; finish exact non-invocation/derived labels where existing tests do not establish the registered handling expectations. Repair concrete findings and verify affected inputs before closing M2. Use the modern evidence record as the entry point; do not repeat parser research or redesign D1–D3 without a correctness blocker. Preserve raw archives; no commit/push or silent M3 entry.
