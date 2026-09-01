# SE121 Detailed Roadmap for Human Review

Purpose: provide one self-contained, from-scratch explanation of what the project is, what has already been established, what will be built, how each stage will be verified, and where human approval is required.

This is a **human review companion**. Use:

- `docs/current-state.md` for the operational truth now.
- `docs/roadmap.md` for canonical milestone sequencing and gate status.
- `docs/architecture/` for implemented contracts.
- `docs/decisions/` for approved decision rationale.
- `docs/research/` and benchmark directories for experimental evidence.

If this document conflicts with those canonical sources, inspect the repository and correct this document rather than treating it as hidden authority.

---

## 1. Project in One Page

### Mission

Build a deterministic, evidence-first Java/Spring Boot architecture intelligence platform that can:

1. Understand a repository and its Maven module/build structure.
2. Reconstruct architecture-relevant Java and Spring semantics.
3. Preserve uncertainty instead of inventing relationships.
4. Build a deterministic Software Knowledge Graph.
5. Evaluate explicit architecture policies.
6. Report violations with exact source and graph evidence.
7. Answer bounded dependency and impact questions.
8. Visualize architecture through stable query services.
9. Compare compatible repository snapshots and show architecture evolution.
10. Prove the system's bounded correctness through reproducible evaluation.

### Approved SE121 North Star

```text
TRACK A - Correctness foundation and complete product
                         +
TRACK B - Architecture evolution after Track A gates

TRACK C - Optional research moonshot; never required for core completion
```

### Priority Order

1. Technical depth
2. System correctness
3. Architecture quality
4. Engineering quality
5. Product quality
6. Empirical validation
7. Publication readiness later

### Not Part of SE121

- AI diagnosis or RAG
- Automated refactoring or patches
- OpenRewrite transformations
- Sandbox verification of generated changes
- Verified Pull Requests
- CI/CD verification product features
- Another programming language

---

## 2. Starting Repository Reality

At the beginning of M-1:

- The repository contained a root Maven reactor with empty `analyzer` and `backend` production modules.
- No production semantic frontend, graph, rule engine, CLI, API, or frontend existed.
- R1 existed as a tracked PoC under `benchmarks/poc/parser-eval/`.
- The R1 target was Spring PetClinic commit `818c4136ea971c21674525f9053de0d9c7ad8cfe`.
- The repository HEAD was `8a4e6e1bc6b8b29ee49ba37d78c9037ed8db3c90`.
- The worktree was clean before M-1 changes.
- JavaParser was PROVISIONAL, not confirmed.
- Project status and roadmap documents were stale.
- Agent/workflow files duplicated instructions and contained provider-specific assumptions.
- `.agents/rules/20-evidence-first.md` contained appended corrupted content.

### R1 Evidence That May Be Relied On

| Result | Config A | Config B |
|---|---:|---:|
| Java files parsed | 30 | 30 |
| Relationship attempts | 456 | 456 |
| Resolved | 218 | 456 |
| Unresolved | 238 | 0 |
| Relationship errors | 0 | 0 |

- Config A used project source plus the runtime JDK.
- Config B added the recorded exact Maven compile classpath.
- The 14-case ground-truth set matched its expected outcomes.
- All stored relationship spans were structurally complete and within their source files.
- Config B demonstrates full resolution coverage for the six measured categories on that repository.
- It does **not** prove universal or corpus-wide semantic correctness.
- PetClinic is too small and homogeneous to confirm production suitability by itself.

### R1 Decision

JavaParser + SymbolSolver remains **PROVISIONAL** behind a parser-neutral adapter. It must pass broader semantic, modern-Java, multi-module, Spring, scale, and correctness gates.

---

## 3. What M-1 Establishes

M-1 is the final operating-system/governance milestone before production work.

### M-1 Deliverables

- [x] Canonical mandatory session bootstrap protocol
- [x] Progressive context-loading rules
- [x] Mandatory completion and handoff protocol
- [x] Clear durable document responsibilities
- [x] Concise operational `current-state.md`
- [x] Track A + Track B tech-first roadmap
- [x] Normalized eight-role agent system
- [x] Provider-independent durable role definitions
- [x] Normalized workflows with `/execute` as orchestrator
- [x] Repaired evidence-first rule
- [x] Reduced contradictory/duplicated governance text
- [x] This from-scratch human roadmap

### M-1 Exit Decision

Human must decide:

- [ ] Approve M-1 as the final governance redesign.
- [ ] Authorize M0 only.
- [ ] Commit the M-1 changes or request corrections.

After approval, redesign should stop unless new evidence forces a change.

---

## 4. End-to-End Product Story

A successful Track A + B system should support this flow:

```text
Repository locator / local snapshot
  -> safe Git and workspace acquisition
  -> Maven module/build-model discovery
  -> exact source roots and classpath manifest
  -> Java semantic frontend
  -> Spring semantic enrichment
  -> canonical semantic graph
  -> versioned architecture metrics
  -> typed architecture policies
  -> evidence-backed violations
  -> explainable architecture health assessment
  -> impact/query services
  -> CLI/API/complete visual workbench
  -> compatible snapshot comparison
  -> architecture evolution events
```

The final user must be able to:

1. Analyze a real repository.
2. See exactly what was included, excluded, resolved, and unresolved.
3. Browse modules, packages, types, and architecture relationships.
4. Inspect Spring candidate and injection relationships without fake runtime certainty.
5. Apply explicit architecture rules.
6. Open a violation and see its complete evidence path.
7. Inspect detailed repository, Java, Spring, structural, policy, and operational metrics.
8. See an explainable architecture health score beside a separate analysis-confidence result.
9. Explore focused, filtered, progressively expanded architecture graphs.
10. Ask for direct and bounded transitive dependents/impact.
11. Compare two compatible snapshots.
12. See introduced, removed, resolved, persisted, and reintroduced architecture relationships/violations.
13. Inspect provenance and benchmark evidence that bounds every accuracy/performance claim.

### Confirmed Visual Product Outcome

Track A must end as a complete visual architecture-intelligence platform, not an analyzer or CLI with an optional graph page. Its required views are:

1. analysis setup/history and provenance;
2. overview dashboard with inventory, semantic coverage, confidence, violations, metrics, score, hotspots, duration, and limitations;
3. repository/module/package/type/member explorer;
4. focused interactive graph with search, filters, grouping, expansion, path highlighting, limits, legends, and export;
5. metric catalog, distributions, drill-down, and score explanation;
6. violations with rule, severity, semantic status, graph path, and exact source evidence;
7. Spring components/endpoints/injection candidates and ambiguity;
8. bounded impact; and
9. compatible snapshot evolution after Track A approval.

Architecture health and analysis confidence remain separate. A partial analysis cannot appear healthy merely because unresolved dependencies hide violations. Affected scores must be qualified or withheld.

The canonical details, metric catalog, score safeguards, UX contract, and acceptance matrix are in [Product Outcome, Metrics, Scoring, and Workbench Contract](docs/architecture/product-outcome.md).

---

## 5. Core Architecture Boundaries

### 5.1 Repository and Workspace Acquisition

Input:

- repository path or approved Git locator
- requested commit/snapshot
- analysis limits and trust mode

Output:

- immutable repository snapshot
- repository identity and commit
- source file inventory and content hashes
- acquisition diagnostics

Must not:

- silently analyze a dirty or different snapshot
- escape the allowed repository root
- execute arbitrary target build commands

### 5.2 Build-Model Intelligence

Required Maven understanding:

- parent POM inheritance
- reactor modules
- module dependencies
- source and test roots
- generated-source roots where safely discoverable
- dependency management
- imported BOMs
- dependency scopes and exclusions
- exact selected dependency/classpath manifest

Output must be deterministic and preserve coordinates, scope, paths, hashes, and model-resolution diagnostics.

Normal analysis must not execute arbitrary Maven/Gradle lifecycle plugins. Gradle initially requires an explicit supplied classpath unless a safe isolated approach is approved.

### 5.3 Semantic Frontend

The frontend converts a repository/workspace model into parser-neutral facts. It owns parsing and symbol resolution but not graph storage or policy interpretation.

No JavaParser AST, `ResolvedType`, exception, or range object may cross the adapter boundary.

### 5.4 Spring Intelligence

Spring enrichment consumes language facts and emits:

- bean candidates
- producer methods
- injection points
- zero/one/many possible bindings
- qualifier/primary/fallback reasons
- profile/conditional applicability
- explicit unsupported/runtime-dependent cases

It does not claim to reproduce the complete runtime container.

### 5.5 Canonical Graph

The canonical graph is deterministic and storage-neutral. It owns semantic normalization, identity, adjacency/indexes, and graph invariants.

Neo4j may store/query the graph through an adapter. Neo4j and Cypher do not define domain semantics.

### 5.6 Architecture Query Layer

Stable services must cover:

- symbol lookup
- dependencies
- dependents
- bounded paths
- violations
- evidence
- impact
- snapshot comparison

Backend and frontend must use these services instead of embedding arbitrary graph-store queries as domain logic.

### 5.7 Policy and Evidence

External rules are schema-validated and compiled into a typed internal representation. Every violation must carry a complete Evidence Bundle.

### 5.8 Product Interfaces

- CLI is first and must run the complete core workflow without Neo4j/backend.
- Backend is a composition root and query/job API.
- Workbench renders focused subgraphs, violations, evidence, uncertainty, source, impact, and Track B comparison.

---

## 6. Cross-Cutting Contracts

### 6.1 Semantic Status

Use separate axes rather than one misleading confidence field:

- Resolution: resolved, unresolved, ambiguous, partial, unsupported, error
- Derivation: declared, symbol-resolved, framework-inferred
- Applicability: unconditional or conditional
- Origin: project, JDK, dependency, language, unknown

### 6.2 Source Evidence

Every source fact should preserve:

- repository-relative path
- source content hash
- complete begin/end offsets
- documented display line/column convention
- declaration/caller identity
- target or candidate identities
- configuration and analysis identity
- diagnostics when not resolved

### 6.3 Content-Addressed Analysis Identity

Analysis identity derives from canonical stable inputs:

```text
repository snapshot identity
+ source/document hashes
+ workspace/build/classpath manifest
+ analysis configuration
+ rule-set version/hash
+ graph schema version
+ analyzer version/hash
```

Uses:

- provenance
- deterministic comparison
- Track B compatibility
- future caching readiness
- future incremental invalidation

A cache is not implemented until measurement shows value and invalidation correctness is defined.

### 6.4 Graph Identity

- Repository identity is namespaced.
- Snapshot identity is content/configuration addressed.
- Logical symbol identity includes language, module, owner, kind, and signature.
- Source occurrence identity adds document hash and span.
- Relationship fact identity includes category, source occurrence, target/candidates, derivation, and configuration.
- Violation fingerprint uses rule version and semantic path identity while retaining current snapshot evidence.

### 6.5 Reproducibility Manifest

Record:

- repository locator and commit
- analyzer commit/artifact/source hash
- JDK vendor/version and compiler release
- Maven/parser/Spring/graph/rule/schema versions
- OS/hardware essentials
- exact source roots and classpath coordinates/hashes
- configuration
- exact command
- timestamps and phase durations
- failures, exclusions, and coverage

---

## 7. Detailed Milestones

## M-1 - Project Operating System Hardening

Goal: make every future session reconstruct reality and finish with durable, verifiable state.

Entry:

- Master redesign approved in direction.
- Human supplied governance adjustments.

Work:

- Normalize canonical rules, roles, workflows, project context, current state, roadmap, and handoff.

Exit:

- [ ] Human approves the diff.
- [ ] No provider-specific model/tool requirement remains in durable role/workflow contracts.
- [ ] Current state matches repository reality.
- [ ] Roadmap shows Track A + B target.
- [ ] No production M0 work occurred.

## M0 - Reproducible Foundation

Goal: a clean, deterministic engineering foundation before semantic production work.

Planned work:

- Add Maven Wrapper and pin/checksum the distribution.
- Use `maven.compiler.release=21`.
- Enforce supported Java/Maven, release dependencies, plugin versions, and dependency convergence.
- Align test platform versions after compatibility validation.
- Add root README and exact build/environment instructions.
- Establish deterministic JSON/build output practices.
- Verify Windows and a second clean environment.

Do not:

- implement semantic extraction
- add Neo4j/backend/frontend
- upgrade technologies unrelated to foundation

Exit evidence:

- clean checkout build commands
- exact tool versions
- two-environment result
- inspected artifact/diff
- no hidden local setup dependency

## M1 - Semantic and Identity Contracts

Goal: define the stable language between every future component.

Planned models:

- `RepositorySnapshot`
- `WorkspaceModule`
- `BuildModel`
- `SourceDocument` and `SourceSpan`
- `SemanticEntity` and `SemanticRelationship`
- `ResolutionStatus`, `DerivationKind`, `Applicability`, `TargetOrigin`
- `Diagnostic`
- `AnalysisConfiguration` and `AnalysisManifest`
- `MetricDefinition`, `MetricResult`, `MetricStatus`, and metric formula identity
- `AnalysisConfidence`
- `ArchitectureAssessment`, dimension result, contribution/penalty, and score status
- content-addressed IDs
- parser/enricher/storage/query ports

Required tests:

- identity stability
- collision/namespace cases
- complete source spans
- canonical serialization/order
- analysis identity input sensitivity
- metric/score version and missing-evidence behavior
- no adapter type leakage

Exit evidence:

- reviewed contract docs/ADR
- unit/golden/invariant tests
- deterministic output fixture

## M2 - Java Semantic Frontend and Ground Truth

Goal: replace the R1 PoC categories with production-quality parser-neutral extraction.

Required semantic coverage:

- classes, interfaces, enums, records, annotations, nested/sealed types
- methods, constructors, fields
- extends, implements, permits
- generic parameters and bounds
- method/constructor calls
- field reads/writes
- method references
- return, parameter, field, thrown, and general type uses
- annotations and relevant meta-annotation identity
- package/import/static-import source evidence
- relevant lambda/pattern relationships

Ground-truth outcomes:

- attempted
- correct
- incorrect
- unresolved
- ambiguous
- omitted
- unsupported
- error
- not applicable

Evaluation configurations:

- A: source + matching target JDK
- B: exact compile classpath
- C: controlled partial classpath only where it answers a defined question

Exit evidence:

- exhaustive microfixtures
- stratified real-source labels
- independent compiler/JDT oracle where applicable
- JavaParser upgrade comparison
- no hidden denominator categories

Parser promotion decision:

- remain PROVISIONAL if evidence is still narrow
- continue if viable with bounded limitations
- switch adapter if critical correctness fails

## M3 - Multi-Module Workspace and Build Model

Goal: analyze repository architecture as modules and build relationships, not only one source directory.

Planned work:

- parse Maven reactor/parent hierarchy safely
- resolve effective dependency management and BOM selection
- identify main source roots and safe generated roots
- distinguish compile/provided/runtime/test/optional relationships
- model module-to-module and module-to-external dependencies
- generate exact portable classpath manifests
- report unresolved parents/dependencies and version conflicts

Required fixtures:

- single module
- parent/child reactor
- imported BOM
- dependency management override
- optional/provided/test dependencies
- duplicate/version conflict
- missing parent/dependency
- safely discoverable generated root

Exit evidence:

- deterministic build model
- no project lifecycle execution
- real multi-module repository reproduction

## M4 - Spring Semantic Intelligence

Goal: reconstruct a bounded, auditable Spring architecture model.

Required positive cases:

- direct stereotypes
- composed/meta stereotypes
- `@Configuration` and `@Bean`
- single-constructor implicit injection
- explicit autowired constructor
- interface implementation candidate
- qualifier, primary, fallback
- list/set/array/map candidates

Required difficult/negative cases:

- multiple constructors
- missing candidate
- multiple candidates
- same-name/type collisions
- profiles and conditions
- self reference
- factory/programmatic registration
- Lombok/generated constructor gap

Output:

- candidates and injection points
- candidate sets with reasons
- resolved/ambiguous/conditional/unresolved/unsupported states
- exact evidence for declarations and injection points

Exit evidence:

- language-only versus Spring-enriched paired results
- pre-registered correctness metrics
- no runtime-equivalence claim

## M5 - Canonical Graph, Metrics, and Query Layer

Goal: make semantic facts queryable without coupling to storage.

Graph concepts:

- repository, snapshot, module, package
- type, method, constructor, field
- Spring bean candidate/injection point
- rule, violation, external symbol
- source evidence and analysis manifest where query value justifies graph entities

Core relation families:

- contains/declares
- extends/implements/permits
- calls/constructs/reads/writes/uses type
- returns/has parameter/throws/annotated with
- produces bean/has injection point/may inject/injects
- violates/supported by/impacts

Mandatory invariants:

- all edges have provenance
- resolved targets are known or explicitly external
- no duplicate logical declaration identity
- deterministic/idempotent construction
- persistence round trip preserves canonical graph
- violations reference valid rule and path
- comparisons require compatible identities/configuration

Query services:

- symbol lookup
- dependencies/dependents
- paths
- violations/evidence
- impact
- snapshot comparison preparation
- analysis summary and inventory
- metric catalog/results by scope
- focused subgraph projections and bounded expansion
- cycles and structural hotspots
- score input/explanation retrieval

Initial metric work includes exact inventory counts, `Ca`, `Ce`, instability, fan-in/fan-out, dependency density with an explicit denominator, cycle/SCC metrics, boundary crossings, policy coverage, and operational measurements. Cohesion is not claimed until a precise validated definition exists.

Every metric exposes ID/version, scope, value/unit, status, inputs, denominator where applicable, analysis/configuration identity, and uncertainty.

Neo4j decision:

- trial only after canonical tests exist
- reject or defer it if adapter cost/licensing/query lock-in adds no measured value

## M6 - Architecture Policy, Evidence, and Explainable Assessment

Goal: detect architecture violations through explicit, testable, explainable policies.

Initial rule families:

- forbidden dependency
- package/module/layer boundary
- cycles
- stereotype boundary when evidence supports it

Every rule defines:

- ID/version/intent/severity
- selectors and scope
- relation categories and predicate
- treatment of inferred/ambiguous/conditional facts
- evidence requirements
- exceptions and limitations

Every violation contains:

- rule/version and explanation
- source/target symbols
- supporting relationship facts
- exact source spans
- semantic status and derivation
- minimal graph path
- snapshot/configuration
- stable fingerprint
- diagnostics/limitations

Assessment outputs:

- architecture health score intended for a 0–100 presentation;
- dimension scores for dependency direction, cyclicity, modularity/coupling, policy conformance, and hotspot concentration;
- raw metrics, contributions, penalties, caps, and withheld reasons;
- separate analysis confidence;
- `COMPLETE`, `PARTIAL`, `WITHHELD`, and `NOT_APPLICABLE` states;
- formula/policy/configuration versions and comparison compatibility.

Exact weights and thresholds remain provisional until labeled examples, sensitivity analysis, missing-evidence tests, and human review are complete.

Architecture mutation benchmark:

- forbidden dependency
- controller-to-repository crossing
- package/module boundary crossing
- cycle
- unauthorized Spring injection
- qualifier removal creating ambiguity
- negative control change

Exit evidence:

- positive/negative/ambiguous tests
- mutation detection results
- score golden cases, sensitivity and versioning results
- proof that incomplete evidence cannot improve the architecture score
- reviewed natural cases reported separately

## M7 - Impact, CLI, and Exports

Goal: provide a complete local product interface before backend/UI complexity.

CLI commands should cover:

- analyze
- rules validate/evaluate
- violations list/show
- summary/inventory/metrics/score show
- impact
- graph export
- benchmark run
- compare after Track B

Impact output separates:

- direct dependencies
- bounded transitive dependencies
- affected modules/boundaries
- policy impact
- potential structural impact from guaranteed runtime behavior

Exports:

- canonical JSON/JSONL
- versioned metric and assessment results with explanations
- SARIF violations
- GraphML
- CSV benchmark tables only

Exit evidence:

- end-to-end local run without backend/Neo4j
- documented exit codes
- versioned schemas and golden outputs

## M8 - Backend and Complete Architecture Workbench

Goal: make the evidence useful and inspectable without moving domain logic into presentation.

Backend:

- cancellable asynchronous analysis jobs
- analysis/snapshot/summary/metric/score/symbol/relation/rule/violation/evidence/impact endpoints
- versioned OpenAPI contract
- root-restricted repository paths
- stable query-service use

Workbench:

- analysis setup/history, progress, cancellation, errors, and provenance
- overview dashboard with detailed counts, semantic coverage, confidence, violations, metrics, score, hotspots, and limitations
- repository/module/package/type/member explorer
- focused graph with search, filters, grouping, expansion/collapse, paths, cycles, legends, limits, and export
- metrics catalog, distributions, scope drill-down, and ranked hotspots
- architecture score breakdown and links to every contribution/penalty
- violations list/detail with evidence graph and exact source panel
- uncertainty/derivation/origin/status filters
- Spring component, endpoint, injection candidate, and ambiguity views
- direct/bounded impact view
- Track B compatible before/after and delta view later
- loading, empty, partial, error, canceled, and oversized-result states

Visualization trial:

- Cytoscape.js primary candidate
- Sigma.js assessment for larger visible subgraphs
- never render the whole graph by default
- progressive loading and bounded graph payloads
- registered reference-environment interaction/performance budgets before implementation completion
- accessible contrast, keyboard-primary workflows, and non-color-only status communication

Exit evidence:

- analyze-to-dashboard-to-score/violation/graph-to-source-evidence user journey
- honest partial-evidence user journey with qualified or withheld results
- API contract tests
- UI component/E2E tests
- representative graph tasks, accessibility checks, and measured performance budgets
- CLI/API/export/UI value agreement
- no arbitrary storage queries in UI/backend domain logic

## M9 - External Evaluation and Hardening

Goal: determine the exact bounded claims the engineering system can support.

Corpus roles:

1. exhaustive microfixtures
2. PetClinic smoke/reference
3. medium real Spring project
4. multi-module real project
5. larger heterogeneous project
6. mutated and historical variants

Experiments:

- semantic correctness
- classpath degradation
- Spring enrichment
- policy/mutation correctness
- evidence/span correctness
- malformed/adversarial robustness
- deterministic repeated output
- phase runtime and peak memory
- graph growth and query performance
- exact inventory/metric correctness
- score golden cases, missing-evidence behavior, sensitivity, and version compatibility
- focused graph payload/render/interactivity performance on registered reference environments
- accessibility and primary-workflow usability review

Reporting:

- per repository and per category
- correct/incorrect/unresolved/ambiguous/omitted/unsupported/error
- raw counts before derived rates
- synthetic and natural results separated
- limitations and threats explicit

Exit evidence:

- independent verification of raw metrics
- clean second-environment run
- bounded Track A claim language

## M10 - Track A Release Gate

Human checklist:

- [ ] Semantic frontend passes supported exhaustive cases.
- [ ] No critical unexplained incorrect resolution remains.
- [ ] Multi-module/build model is deterministic and safe.
- [ ] Spring claims match measured support.
- [ ] Graph/query invariants pass.
- [ ] Policy mutations and negative controls pass.
- [ ] Every certain violation has complete evidence.
- [ ] CLI completes the product workflow.
- [ ] API/workbench trace results to source.
- [ ] Robustness, determinism, runtime, and memory are reported.
- [ ] Documentation and current state match reality.
- [ ] Human explicitly authorizes Track B.

## M11 - Track B Architecture Evolution

Goal: compare compatible snapshots and explain architecture change.

Required outputs:

- added/removed/changed entities
- added/removed/changed semantic relationships
- Spring candidate/binding changes
- introduced/resolved/persisted/reintroduced violations
- metric changes
- diagnostic/coverage changes
- source evidence for each event

Compatibility rules:

- same repository identity
- compatible analysis configuration
- compatible analyzer/semantic/graph schema
- comparable rule set or explicitly reported rule drift

Architecture blame boundary:

- report earliest observed event in an explicit analyzed history
- do not assign human fault
- violations may appear, disappear, and reappear
- do not use binary search unless monotonicity is established
- first-parent/merge semantics must be explicit

Exit evidence:

- labeled commit pairs/history
- natural and controlled evolution events
- identity continuity results
- analyzer drift distinguished from source change

## M12 - Track A + B Integration

Goal: finish a reliable technical product and evidence package.

Deliver:

- production analyzer and tests
- CLI/backend/workbench
- canonical schemas and example rules
- corpus, fixtures, labels, scripts, and raw results
- architecture and decision documentation
- clean-clone reproducibility
- known limitations and unsupported semantics
- deterministic local demonstration

Publication packaging is optional later. Technical evidence must already be preserved.

---

## 8. Track C Moonshot

Track C starts only after M12 or explicit surplus-capacity approval.

### Correctness-Preserving Incremental Analysis

Required oracle:

```text
canonical(incremental snapshot B) == canonical(full snapshot B)
```

Invalidation must account for declarations, overload sets, inheritance, Spring candidate sets, classpath, rules/configuration, deleted/generated sources, and affected dependency closure. Fall back to full analysis when safety is uncertain.

### Selective Reconstruction/Re-evaluation

- changed-file/symbol analysis
- affected relationship closure
- selective graph replacement
- selective rule evaluation
- measured speedup and memory benefit

### Optional Bytecode Validation

Use ASM/WALA/other bytecode analysis only if a concrete source-semantic gap materially affects architecture correctness and a focused experiment shows net value.

### Advanced Temporal Work

- larger histories
- richer identity continuity
- architecture blame beyond basic earliest-observed events
- transparent hotspot tuples using churn, structural centrality, violation history, and uncertainty

Do not invent a composite architecture health score.

---

## 9. Stage Gates at a Glance

| Gate | Human/technical question | Minimum evidence |
|---|---|---|
| G-1 | Is governance stable enough to stop redesigning? | M-1 consistency verification and human approval |
| G0 | Can the project build reproducibly? | Pinned tools and two clean environments |
| G1 | Are contracts stable and adapter-neutral? | Invariants and golden serialization |
| G2 | Is Java/build-model extraction trustworthy enough? | Expanded ground truth and safe multi-module cases |
| G3 | Are bounded Spring claims supported? | Pre-registered candidate/binding results |
| G4 | Is graph/query behavior deterministic and storage-neutral? | Invariants and adapter round trips |
| G5 | Are policy violations correct and auditable? | Positive/negative/mutation/evidence tests |
| G6 | Is Track A a complete credible product? | Multi-repository evidence and user workflow |
| G7 | Is Track B evolution correct? | Labeled event reproduction and compatibility evidence |
| G8 | Is Track A + B reproducible and finished? | Clean-clone final verification |

---

## 10. Approximate 24-Week View

```text
Week  1      M-1 review/approval
Weeks 2-3   M0 reproducible foundation
Weeks 3-5   M1 semantic/identity/provenance contracts
Weeks 5-8   M2 frontend/ground truth + M3 workspace/build model
Weeks 8-11  M4 Spring intelligence + M5 graph/query layer
Weeks 11-14 M6 policy/evidence
Weeks 14-16 M7 impact/CLI/export
Weeks 16-19 M8 backend/workbench + continuous M9 evaluation
Weeks 19-20 Track A hardening and G6/M10 human gate
Weeks 20-23 M11 Track B evolution
Weeks 23-24 M12 integration/reproducibility
```

The calendar does not override quality gates.

---

## 11. Parallel Workstreams

```text
Infrastructure/reproducibility  M0 ------------------------------- M12
Semantic contracts/frontend    M1 -------- M2
Workspace/build intelligence   M1 -------- M3
Ground truth/benchmark          M1 ------------------------------- M12
Spring intelligence                        M4
Graph/query                                M5 ----------- M11
Policy/evidence                                  M6 ----- M12
Product                                              M7 -- M12
Evolution                                                    M11
Documentation                 continuous at contract/decision/state changes
```

Shared contracts must be agreed before workstreams implement competing representations.

---

## 12. Continuous Verification Matrix

| Capability | Required verification |
|---|---|
| Build foundation | clean Windows/second-environment builds and artifact inspection |
| Identity | stability, collision, sensitivity, canonical ordering tests |
| Java semantics | exhaustive fixtures, independent oracle, real labels |
| Build model | multi-module/BOM/scope/partial failure fixtures |
| Spring | positive, negative, ambiguity, condition, unsupported labels |
| Graph | uniqueness, provenance, idempotency, deterministic round trip |
| Policy | positive/negative/ambiguous and mutation cases |
| Evidence | source hash/span/path/rule/configuration validation |
| Impact | bounded path and exclusion fixtures |
| CLI/API | contract, exit-code, integration, E2E tests |
| Workbench | component and evidence user-flow E2E tests |
| Evolution | labeled snapshot events and compatibility checks |
| Performance | repeated end-to-end phase runtime and peak memory |
| Reproducibility | immutable manifests/raw output and second environment |

---

## 13. Main Risk Register for Human Monitoring

| Risk | Warning signal | Required response |
|---|---|---|
| Incorrect symbol resolution | compiler/human oracle disagreement | block claim, fix or narrow support |
| Spring false certainty | binding candidate mismatch | retain candidates/conditions; narrow inference |
| Parser lock-in | JavaParser types outside adapter | block merge and restore boundary |
| Graph identity failure | duplicate/discontinuous symbols | stop Track B; repair identity contract |
| Neo4j lock-in | Cypher in core/backend logic | move behavior to query service/adapter |
| Ground truth too small | confident metrics from few cases | expand stratified labels before claim |
| Benchmark bias | corpus selected after results | preselect by documented criteria |
| Hidden denominator | unsupported/omitted absent from output | block report/claim |
| Scope explosion | incomplete Track A while Track B/C starts | stop advanced work |
| Frontend distraction | graph UI before CLI/correctness | return to M7/G6 path |
| Opaque architecture score | unexplained number or hidden weights | block score release; expose inputs/version/penalties |
| Confidence/health confusion | missing relationships improve score | withhold affected score and repair assessment gate |
| Dashboard drift | UI counts differ from CLI/API/export | block release; use canonical query/metric services |
| Graph hairball | whole repository rendered by default | require focused projection, aggregation, and limits |
| Unsafe target build | lifecycle/plugin execution | stop and redesign acquisition mode |
| Performance failure | superlinear memory/time | profile phases; optimize measured bottleneck |
| Reproducibility failure | clean environment differs | no gate pass until cause recorded/resolved |
| Weak novelty | baseline already offers same claim | frame as engineering/empirical work honestly |
| Technology churn | upgrades without experiment | pin and gate versions |

---

## 14. Scope-Cut Order

If time or evidence is insufficient, cut:

1. Incremental analysis and large temporal history.
2. Advanced architecture blame/hotspot work.
3. Optional bytecode validation.
4. Advanced comparison visualization beyond the required Track B comparison flow.
5. Neo4j persistence while retaining canonical graph/file output.
6. Nonessential backend/workbench extras while retaining the required Track A dashboard, metrics, score, graph, violation, evidence, and provenance workflow.

Never cut:

- ground truth
- explicit uncertainty
- source evidence/provenance
- content-addressed identity
- graph/data invariants
- metric definitions, denominators, provenance, and correctness tests
- explainable score safeguards and separation from analysis confidence
- required Track A visual workbench
- policy positive and negative tests
- deterministic outputs
- reproducibility manifests
- honest limitations and complete denominators

---

## 15. Human Decision Checklist

### Now: M-1

- [ ] Governance is concise enough to use repeatedly.
- [ ] Bootstrap protocol provides sufficient context without blind full-repo reading.
- [ ] Eight roles have clear non-overlapping boundaries.
- [ ] Workflows share one lifecycle.
- [ ] `current-state.md` reflects reality.
- [ ] Track A + B is the intended North Star.
- [ ] Publication work is appropriately de-emphasized.
- [ ] No M0 production implementation was included.
- [ ] Authorize M0.
- [x] Confirm the final outcome is a complete visual platform with detailed metrics, explainable architecture score, violations, focused graph, and evidence navigation.

### Before JavaParser Production Promotion

- [ ] Approve supported relationship denominator.
- [ ] Approve ground-truth protocol/corpus.
- [ ] Review JavaParser version/differential evidence.
- [ ] Decide CONTINUE PROVISIONALLY, PROMOTE, or REPLACE ADAPTER.

### Before Neo4j Adoption

- [ ] Canonical graph/query requirements exist.
- [ ] Adapter experiment shows a real product/query benefit.
- [ ] Licensing/deployment/version implications are accepted.

### Before Track B

- [ ] All Track A blockers are closed.
- [ ] Snapshot/content identity is stable.
- [ ] Comparison compatibility rules are approved.
- [ ] Track B corpus/events are labeled.
- [ ] Human explicitly authorizes M11.

### Before Track C

- [ ] Track A + B is already complete and reproducible.
- [ ] The selected moonshot has a measurable research/engineering question.
- [ ] Failure cannot damage the completed core.

---

## 16. Exact Next Action After This Document

1. Human reviews the M-1 diff and this checklist.
2. Codex corrects any requested M-1 issues only.
3. Human approves and commits M-1.
4. Start M0 with a bounded task:

> Add and verify the reproducible Maven/Java build foundation—Maven Wrapper with checksum, Java 21 release/enforcement, plugin/dependency controls, root build instructions, and two-environment verification—without implementing semantic production code.

Do not proceed directly to JavaParser extraction, graph code, Spring inference, backend, CLI, or frontend.
