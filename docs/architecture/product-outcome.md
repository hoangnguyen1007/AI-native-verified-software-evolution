# Product Outcome, Metrics, Scoring, and Workbench Contract

## Status

- **Product outcome:** CONFIRMED by the human owner on 2026-09-01.
- **Capability boundaries and safety invariants:** CONFIRMED direction.
- **Bounded conditional-architecture product direction:** ACCEPTED by [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md); implementation and UX validation pending.
- **Exact metric formulas, score weights, thresholds, visual framework, and performance budgets:** PROVISIONAL until their milestone gates.

This document defines the minimum end-product contract for SE121. It prevents the project from completing as a technically strong analyzer without becoming a usable architecture-intelligence platform.

## Product Outcome

At the end of SE121, a user must be able to select a Java/Spring Boot repository snapshot, run an analysis, and use a clean visual workbench to:

1. understand the repository inventory and analysis quality;
2. inspect architecture structure and dependencies at repository, module, package, type, and member levels;
3. view detailed, explainable architecture metrics;
4. see an architecture health score with a transparent breakdown;
5. discover, filter, and trace architecture violations to exact source evidence;
6. explore focused interactive graphs without loading an unreadable whole-repository hairball;
7. inspect Spring components, endpoints, injection candidates, configuration truth regions, witnesses and uncertainty;
8. perform bounded impact analysis;
9. inspect complete analysis provenance and limitations; and
10. compare compatible snapshots and understand configuration-qualified architecture/evidence evolution after Track A gates pass.

The platform must remain useful when some relationships are unresolved. It must expose uncertainty instead of hiding incomplete analysis behind polished visuals.

## Non-Negotiable Product Principles

1. **Evidence before presentation.** Every derived metric, score contribution, violation, and graph edge links to its inputs and analysis provenance where applicable.
2. **Health is not confidence.** Repository architecture health and analyzer confidence are separate outputs.
3. **No false precision.** Unsupported metrics are `NOT_APPLICABLE`; insufficient evidence produces `PARTIAL` or `WITHHELD`, not a fabricated number.
4. **Explainability over a magic score.** The overall score is a summary, never a substitute for raw metrics, violations, evidence, or limitations.
5. **Focused visualization.** The UI loads bounded subgraphs and expands on demand; it does not render the entire knowledge graph by default.
6. **Stable query boundary.** CLI, API, exports, and UI consume the same versioned architecture query services.
7. **Deterministic results.** The same compatible snapshot, analyzer version, policy, classpath manifest, and configuration produce the same metric and score outputs.
8. **Accessible, clean interaction.** Meaning is not encoded by color alone, controls are keyboard reachable where practical, and dense views remain legible.
9. **Adaptive evidence, visible gaps.** The platform uses the least invasive sufficient evidence available, exposes what it attempted or could not acquire, and preserves extension paths for stronger providers rather than presenting current static limits as repository facts.
10. **One world is not every world.** The selected realized configuration, `MUST` projection, condition-labeled `MAY` projection and exploratory union are visibly distinct; mutually infeasible facts never become one certain path or violation.
11. **Counterexamples are product evidence.** A configuration-qualified finding exposes a deterministic witness and activation/registration rationale when the supported model can produce one.

## Required User Journey

```text
Select repository/snapshot
  -> configure exact build context, bounded configuration space and policy
  -> run/cancel analysis with visible progress
  -> inspect summary, modeled-space coverage and analysis confidence
  -> navigate realized/MUST/MAY graph, metrics, score, findings, Spring, witnesses, and impact
  -> open exact source evidence and provenance
  -> export results
  -> optionally compare compatible snapshots
```

An end-to-end acceptance scenario must demonstrate this journey on at least one pinned external repository. A second scenario must demonstrate honest degraded behavior with a partial classpath or deliberately unresolved cases.

## Workbench Information Architecture

### 1. Analysis Setup and History

Required capabilities:

- local repository or approved repository snapshot selection;
- exact commit/snapshot identity;
- configuration and policy selection;
- repository-declared versus user-supplied configuration envelopes, domains and unresolved external inputs;
- asynchronous progress, cancellation, failure diagnostics, and duration;
- completed-analysis history keyed by analysis identity;
- explicit dirty/untrusted/partial-input warnings;
- provenance and classpath-manifest access; and
- evidence-provider configuration/status and explicit acquisition-gap reasons where applicable.

### 2. Overview Dashboard

The default landing page for a completed analysis must show:

- repository, commit, branch/reference when known, analysis time, analyzer version, and configuration;
- inventory cards for modules, packages, source files, types, members, relationships, Spring elements, and endpoints;
- semantic coverage and unresolved/ambiguous/error summary;
- evidence-gap and acquisition summary grouped by mechanism, reason, provider and affected output, with attempted/failed/denied/unavailable states where applicable;
- violation totals by severity, rule, scope, and semantic status;
- `MUST`/`MAY`/`UNKNOWN` finding totals, modeled-space coverage and unresolved-region summary;
- architecture health score, dimension scores, score status, and analysis confidence side by side;
- highest-risk cycles, boundary breaches, coupling hotspots, and affected modules/packages;
- links from every summary card to a filtered detailed view;
- clear limitations and unsupported-capability indicators.

### 3. Structure Explorer

- repository → module → source root → package → type → member hierarchy;
- search by qualified/simple name;
- type kind, visibility, modifiers, source location, stereotypes, and module ownership;
- inbound/outbound relationships grouped by category and target origin;
- direct navigation to source evidence;
- filters for project-local, JDK, external, unresolved, generated, and test sources.

### 4. Interactive Architecture Graph

- repository, module, package, type, Spring component, endpoint, and focused member views;
- zoom, pan, fit, search, selection, neighborhood expansion, collapse, and path highlighting;
- layouts appropriate to hierarchy, dependency flow, and cycles;
- grouping by module, package, configured layer, stereotype, or violation status;
- node/edge type filters, scope filters, confidence filters, relationship direction, realized configuration and truth quantifier;
- clear legend, labels, edge direction, selected-item details, and non-color status cues;
- shortest/bounded dependency paths and violation evidence paths;
- progressive loading, aggregation, and hard view limits to protect responsiveness;
- GraphML and image/snapshot export where supported;
- empty, partial, oversized, and failed-query states; and
- explicit projection mode: realized configuration, `MUST`, condition-labeled `MAY`, bounded union or unknown/gap overlay.

The default graph must be a meaningful architectural projection, not a raw dump of all nodes and edges.

### 5. Metrics and Architecture Score

- metric catalog with definitions, units, scope, formula version, and limitations;
- repository/module/package/type drill-down where the metric is meaningful;
- distributions and ranked hotspots rather than totals alone;
- architecture score breakdown with contribution and penalty explanations;
- links from a metric or penalty to contributing entities, relationships, cycles, or violations;
- comparison only when snapshots, configurations, policies, and formula versions are compatible;
- explicit `COMPLETE`, `PARTIAL`, `WITHHELD`, and `NOT_APPLICABLE` states.

### 6. Violations Explorer

- filters for severity, rule, module, package, layer, status, semantic confidence, and snapshot state;
- stable violation identity where inputs are compatible;
- rule ID/version, title, description, severity, rationale, and configuration provenance;
- configuration-space identity, quantifier, affected region and unresolved residue;
- deterministic witness/counter-witness and replay/export data where applicable;
- source and target identities;
- supporting relationships and complete source spans;
- graph evidence path;
- suppression/waiver state with reason and provenance when supported;
- introduced, resolved, persisted, and reintroduced states in Track B;
- no prescriptive AI remediation in SE121.

### 7. Spring and Impact Views

- components, bean-definition candidates, producers, and composed stereotypes;
- controllers and endpoints;
- configuration classes and `@Bean` producers;
- injection points, candidate sets, selected candidate where statically justified, and ambiguity reasons;
- qualifier, primary/fallback, profile, and conditional states;
- configuration-space summary and “why active/inactive/registered/selected?” explanation;
- realized-configuration comparison, `MUST`/`MAY`/`UNKNOWN` filters and affected-region/witness drill-down;
- direct and bounded transitive dependents/impact with path evidence;
- prominent warning that source analysis does not equal the complete runtime Spring container; and
- drill-down from each unresolved/dynamic Spring mechanism to its source/configuration evidence, capability gap and candidate next evidence without implying automatic execution or a guaranteed resolution.

### 8. Evolution Comparison

Track B must provide:

- compatible baseline and target selection;
- inventory, semantic, conditional-region, metric, score, graph, Spring-binding, witness, finding and evidence deltas;
- added, removed, changed, introduced, resolved, persisted, and reintroduced classifications;
- configuration/analyzer drift warnings;
- build-context/configuration-space/framework-registration compatibility checks;
- affected-region expanded/shrunk and `MUST`/`MAY`/`NEVER`/`UNKNOWN` transitions;
- side-by-side and delta-focused navigation to evidence;
- no comparison when identity or formula compatibility requirements are not met.

**Evidence Loss Invariant:** A loss of evidence (e.g. missing dependency JAR, unanalyzed source scope, skipped file, failed provider, or disabled rule) must **NEVER** be classified as `RESOLVED` or reported as a verified improvement in architecture health. A disappearance of violations caused by reduced analysis coverage must be explicitly reported as an evidence delta or degraded evaluation state.

## Metric Contract

### Required Metric Envelope

Every metric result must contain, as applicable:

| Field | Requirement |
|---|---|
| `metricId` | Stable namespaced identifier |
| `metricVersion` | Formula/semantic version |
| `displayName` and `description` | Human-readable meaning |
| `scopeType` and `scopeIdentity` | Repository, module, package, type, or analysis |
| `value` and `unit` | Typed value and unit |
| `status` | `COMPLETE`, `PARTIAL`, `WITHHELD`, or `NOT_APPLICABLE` |
| `numerator` / `denominator` | Required for ratios where meaningful |
| `inputs` | Referenced facts, relationships, violations, or aggregate identities |
| `analysisIdentity` | Exact producing analysis |
| `configurationIdentity` | Exact relevant configuration and policy |
| `configurationSpaceIdentity` | Exact modeled space for region-qualified metrics/findings, when applicable |
| `uncertainty` | Missing inputs, unresolved relationships, exclusions, and limitations |
| `computedAt` | Timestamp; not part of deterministic identity unless contract requires it |

Metric computation must be deterministic and independently testable from canonical facts/query outputs.

### A. Inventory Metrics

At minimum:

- Maven/reactor modules and module dependencies;
- source roots, source files, generated sources when safely known, and test sources;
- packages;
- classes, interfaces, enums, records, and annotation types;
- methods, constructors, fields, and parameters;
- annotations and relevant generic/type-use occurrences;
- Java relationships by category;
- project-local, JDK, external dependency, unresolved, ambiguous, unsupported, omitted, and error outcomes;
- Spring components by stereotype, configuration classes, bean producers, injection points, candidate bindings, and endpoints;
- physical source lines with the exact counting rule; logical LOC is optional until a validated definition exists.

Counts must state inclusion rules. For example, type counts must say whether nested, local, anonymous, generated, and test types are included.

### B. Semantic Quality and Provenance Metrics

- parse success/failure counts;
- attempted, correctly resolved where ground truth exists, incorrectly resolved, unresolved, ambiguous, omitted, unsupported, and error counts;
- resolution coverage by relationship category and target origin;
- provenance completeness for source file and complete span;
- analysis phase duration and failure counts;
- classpath/module-model completeness indicators.
- evidence-provider attempts, successes, failures, conflicts and unresolved capability gaps by reason where applicable.
- modeled configurations/regions, definite and unresolved region coverage, solver/branch outcomes and false-certainty audit counts where ground truth exists.

These describe analyzer evidence quality and must not be silently mixed into architecture health.

### C. Structural Architecture Metrics

Initial required catalog, subject to formula validation:

- node and relationship counts by architectural scope/category;
- afferent coupling (`Ca`) and efferent coupling (`Ce`) at package/module scope;
- instability `I = Ce / (Ca + Ce)`, with `NOT_APPLICABLE` when the denominator is zero;
- abstractness `A = Na / N` and normalized distance `D = |A + I - 1|`;
- fan-in and fan-out distributions and hotspots;
- dependency density with an explicit denominator;
- strongly connected component and cycle counts, sizes, and participating scopes;
- layer/module/package boundary crossings;
- dependency path depth and bounded reachability summaries;
- external dependency concentration and module dependency concentration;
- cohesion metrics only after a precise supported definition and validation; do not label a proxy as cohesion.

#### Robert C. Martin Metrics Counting Semantics

To prevent ambiguous or inflated metrics, counting semantics must be strictly defined before formula evaluation:
1. **Component Coupling (`Ca` and `Ce`):**
   - Count **distinct neighboring components** (modules or packages) across the component boundary, **NOT** raw method invocation occurrences or syntax occurrences.
   - For package-level coupling, `Ce` is the count of distinct external packages depended upon by types in the package. `Ca` is the count of distinct external packages that depend on types in the package.
2. **Abstractness (`A = Na / N`):**
   - `Na` counts interfaces and abstract classes within the component.
   - `N` counts total types in the component. Enums and records are counted as concrete types in `N`. Annotations are counted in `Na` as abstract contract types.
3. **Population Filtering:**
   - Evaluated strictly over **main production sources**. Test sources, generated sources, and external dependency types are excluded from the component's internal type denominator `N`.
4. **Zero Denominator Handling:**
   - If `Ca + Ce = 0`, Instability `I` is `NOT_APPLICABLE`.
   - If `N = 0`, Abstractness `A` is `NOT_APPLICABLE`.
   - If either `A` or `I` is `NOT_APPLICABLE`, Distance `D` is `NOT_APPLICABLE`. Never invent a default zero or one.
5. **Diagnostic Lens Role:** Martin metrics are diagnostic structural lenses, not an absolute verdict on architecture health.

#### Cycle Engine and Multi-Level RAM Protection

To prevent combinatorial explosion ($O((V+E)(C+1))$) and Out-Of-Memory errors on densely connected graphs:
1. **Linear Component Isolation (Tarjan SCC):**
   - The primary defense is Tarjan's Strongly Connected Components algorithm, running in linear time $O(V + E)$ on the projected architecture graph.
   - A component with one vertex is cyclic only if it contains a self-loop.
2. **Multi-Level Enumeration Bounds:**
   - **Input Graph Thresholds:** Dense SCCs exceeding configured vertex/edge bounds are condensed without attempting full cycle enumeration.
   - **Traversal Work Budget:** Depth-first cycle enumeration (Johnson's algorithm) enforces a maximum visitation step counter and recursion depth budget.
   - **Memory and Cancellation:** The engine checks runtime memory thresholds and cooperative cancellation tokens at every traversal step.
   - **Output Candidate Budget:** Enumeration halts upon collecting a bounded candidate quota of witness cycles (default 50).
3. **Status Reporting:**
   - If enumeration completes within all budgets, the exact cycle count is reported with status `COMPLETE`.
   - If any budget is exceeded, the result returns with canonical M1 status `PARTIAL`, accompanied by enumeration metadata (`witnessesReturned: N`, `workBudgetExceeded: true/false`, `truncated: true`). No ad-hoc enum constants are introduced.

### D. Policy and Violation Metrics

- total violations by rule, severity, scope, and semantic status;
- violation density using a declared denominator;
- suppressed/waived findings separated from active findings;
- policy coverage: scopes and relationship categories evaluated versus excluded;
- cycle and forbidden-dependency findings;
- evidence completeness and uncertain-finding counts;
- findings by `MUST`, `MAY` and `UNKNOWN`; `NEVER` clean results reported separately;
- false-unconditional-warning and false-certainty rates on adjudicated evaluation data;
- witness validity/minimality and affected-region precision/recall on benchmark data.

### E. Spring Architecture Metrics

- component counts by direct/composed stereotype;
- endpoint counts by controller, method, and HTTP mapping;
- injection points by constructor/field/setter style;
- zero/one/many candidate-set counts;
- ambiguous, conditional, profile-dependent, unsupported, and unresolved bindings;
- registration/condition facts by truth quantifier and condition class;
- ordered-registration uncertainties and opaque/dynamic condition gaps;
- dependency direction between configured Spring layers where a policy defines those layers.

### F. Operational and Scale Metrics

- total and per-phase duration;
- peak memory where the measurement method is controlled and documented;
- graph node/edge counts before and after projection/aggregation;
- query latency for registered representative queries;
- UI graph payload size and render/interactivity measurements on a documented reference environment;
- deterministic-result comparison across repeated runs.

### G. Evolution Metrics

- inventory deltas;
- added/removed/changed entity and relationship counts;
- metric and score deltas with compatibility checks;
- introduced/resolved/persisted/reintroduced violations;
- changed cycles, boundaries, coupling hotspots, Spring bindings, and endpoints.
- affected configuration region expansion/contraction, quantifier transitions and witness changes;
- evidence gained/lost/conflicted, never conflated with architecture improvement.

## Explainable Architecture Score Contract

### Separation of Outputs

The workbench must display two separate concepts:

1. **Architecture Health Score:** the repository's measured architecture condition under a versioned model and policy.
2. **Analysis Confidence:** whether the available semantic/build evidence is sufficient to trust that assessment.

Incomplete analysis must never improve the health score. When required evidence falls below an approved threshold, the score is `WITHHELD`; when non-critical evidence is incomplete, it is `PARTIAL` with a visible qualification.

### Required Score Shape

- overall normalized score, intended for a 0–100 presentation;
- dimension scores rather than only one total;
- raw metric values and violations behind every dimension;
- positive contributions, penalties, caps, and withheld reasons;
- formula ID/version and policy/configuration identity;
- compatibility rules for comparison across snapshots;
- deterministic output for identical inputs.

Initial dimensions to validate:

- dependency direction and boundary conformance;
- cyclicity;
- modularity and coupling;
- policy conformance;
- structural hotspot/risk concentration.

Spring-specific health may be an additional dimension only when the repository is applicable and Spring evidence meets the required confidence gate.

### Score Safeguards

- Exact weights and thresholds are not confirmed until labeled examples and sensitivity analysis are reviewed.
- A score must not be advertised as a universal measure of software quality.
- Size alone must not be treated as poor architecture.
- Missing policies must not be interpreted as perfect policy conformance.
- Unsupported or unresolved relationships must not be counted as absent dependencies.
- Unknown configuration regions must not be counted as conforming or used to improve the score.
- Conditional severity and configuration quantifier are separate; a high-severity `MAY` finding remains prominent and explainable.
- Duplicate symptoms of one root structure should not create uncontrolled penalty multiplication.
- Users must be able to understand why a score changed.
- Score model changes require a new version and invalidate direct comparison unless a migration/recomputation rule exists.

## API and Query-Service Requirements

The architecture query layer must expose versioned, storage-neutral operations for:

- analysis summary and provenance;
- inventory and metric queries by scope;
- score and score-explanation retrieval;
- entity search and detail;
- focused subgraph projections and bounded expansion;
- dependency/dependent/path/cycle queries;
- violation listing, aggregation, detail, and evidence;
- Spring component/injection/endpoint queries;
- configuration-space summary, realized/MUST/MAY/unknown projections, activation explanation and witness retrieval;
- bounded impact;
- compatible snapshot comparison.

UI-specific aggregation may exist in a presentation service, but it must not recompute canonical metrics or bypass query contracts with arbitrary graph-store queries.

## Visual and Interaction Quality Criteria

Before M8 implementation, representative corpus sizes and reference hardware must be registered and numerical performance budgets approved. M8 cannot pass on appearance alone.

Minimum qualitative acceptance:

- consistent navigation, spacing, typography, status vocabulary, and component states;
- responsive layout for the supported desktop viewport range;
- loading, empty, partial, error, canceled, and oversized-result states;
- keyboard access for primary workflows where practical;
- readable contrast and non-color-only severity/status communication;
- deterministic deep links or stable navigation state where practical;
- bounded API payloads, pagination, cancellation, and progressive graph expansion;
- no raw stack traces, internal paths, or secrets exposed to users;
- source evidence remains easy to reach from metrics, scores, graph elements, and violations.

## Verification and Acceptance Matrix

| Area | Required verification |
|---|---|
| Inventory | Golden fixtures for exact counts and inclusion rules |
| Semantic-quality metrics | Evaluator ground truth and provenance completeness checks |
| Structural metrics | Hand-computed micrographs, property tests, and graph invariants |
| Violations | Positive, negative, ambiguous, and controlled mutation fixtures |
| Conditional semantics | Exhaustive-oracle conformance, phase/order fixtures, infeasible-path controls, truth-region classification and resource-limit UNKNOWN behavior |
| Witnesses | Re-evaluation, deterministic minimization/tie-break and affected-region checks |
| Score | Golden examples, monotonicity checks where expected, sensitivity analysis, missing-evidence tests, and formula version tests |
| Query/API | Storage-adapter contract tests, pagination, limits, cancellation, and compatibility tests |
| Graph UI | Representative graph tasks, focused-load limits, interaction tests, and measured reference-environment performance |
| Dashboard | End-to-end trace from summary value to underlying facts/evidence |
| Accessibility | Automated checks plus keyboard/manual review of primary flows |
| Evolution | Labeled two-snapshot fixtures and compatibility rejection tests |
| Reproducibility | Clean-clone run with pinned versions, manifests, and deterministic result comparison |

## Milestone Ownership

| Milestone | Product-contract responsibility |
|---|---|
| M0 | Reproducible frontend/backend build foundations and test boundaries |
| M1 | Metric envelope, score status, analysis confidence, identity, provenance, and serialization contracts |
| M2–M4 | Produce trustworthy Java/build facts plus versioned conditional Spring facts and witnesses used by metrics |
| M5 | Canonical realized/MUST/MAY/unknown graph projections, structural metrics, and storage-neutral query services |
| M6 | Configuration-qualified policy metrics/findings, explainable score engine, penalty evidence, and score safeguards |
| M7 | CLI and canonical exports for inventory, metrics, scores, violations, and provenance |
| M8 | Complete workbench views and product acceptance workflow |
| M9 | Multi-repository metric correctness, score sensitivity, graph scale, performance, and usability hardening |
| M10 | Human Track A product acceptance gate |
| M11 | Compatible conditional fact/region/evidence/metric/score/finding evolution |
| M12 | Reproducible integrated demonstration |

## Explicitly Deferred Decisions

The following require later evidence and are not silently decided by this contract:

- exact scoring formula, weights, caps, and grade labels;
- exact cohesion metric;
- numerical UI/query performance budgets;
- frontend framework and component/design system;
- primary graph visualization library;
- persistence adapter, including Neo4j;
- advanced maintainability predictions, technical-debt estimation, or AI recommendations;
- exact configuration-space UI representation, solver backend and numerical exploration limits until M4-R0/M8 evidence;
- multi-build-context, AOT and deployment-environment federation until a post-SE121 gate.

Deferral governs SE121 delivery and claim scope, not permanent platform capability. [ADR-003](../decisions/ADR-003-progressive-evidence-acquisition.md) governs how future evidence providers extend the product without weakening safety or epistemic integrity.

## Track A Product Exit Criteria

Track A is not complete unless all of the following are true:

1. A clean installation can analyze a pinned external Java/Spring repository.
2. The overview shows the required inventory, semantic confidence, violations, metrics, and score status.
3. A user can drill from a score penalty or violation to graph and exact source evidence.
4. Graph exploration remains bounded and usable on the registered corpus.
5. Missing or partial evidence visibly qualifies or withholds affected results.
6. CLI, API, export, and UI values agree through the canonical query contracts.
7. Metric and score formulas are versioned and pass their registered correctness tests.
8. A compatible two-snapshot flow is demonstrated after Track B authorization.
9. Limitations, unsupported cases, configuration, and provenance are visible.
10. Current provider limits are presented as scoped capability gaps, not claims that repository facts are absent or permanently unknowable.
11. The complete demo is reproducible from a clean clone.
12. A user can distinguish a realized architecture from `MUST`, condition-labeled `MAY` and unknown regions, and can replay at least one configuration-hidden finding witness.
13. Infeasible cross-configuration paths are never presented as certain violations, and evidence/configuration loss is never presented as improvement.

## Related Documents

- [ADR-004: Staged Conditional Architecture Semantics](../decisions/ADR-004-staged-conditional-architecture-semantics.md)
- [Conditional Architecture Semantics](conditional-architecture-semantics.md)
- [M4 Spring Intelligence](m4-spring-intelligence.md)
- [Technical Roadmap](../roadmap.md)
- [Research Review](../research/2026-09-09-conditional-architecture-redirection-review.md)
