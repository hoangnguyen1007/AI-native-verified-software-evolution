# M5.2 — Architecture query contract

**CONFIRMED by implementation and bounded tests:** `architecture.query:m5.3-v1` is a Pure Java API in `analyzer`, over the immutable [M5.1 graph](m5-canonical-graph.md). It does not require a database, parser object, REST server or application startup. Numerical limits are defensive implementation choices, **PROVISIONAL** as product performance budgets. [M5.3 metrics](m5-architecture-metrics.md) are implemented; catalog approval and formal G4 adjudication remain open. The version advances from m5.2-v1 for constituent-type package/module populations and the additive full-scope metric projection; old request/cursor identities must be recomputed, while graph snapshots remain unchanged.

## Requests and public operations

`ArchitectureQuery.Request` binds graph content identity, exactly one context, scope, projection, direction, explicit relationship kinds, population, realization and limits. Empty relationship kinds and foreign graph/context are rejected. Scope is ENTITY, TYPE, PACKAGE, MODULE or SPRING_COMPONENT. Entity search honors scope and supports identity, spelling and structural M1/FQN labels; it does not merge identical names across module/origin/context. Detail/evidence operations can drill into supporting records beyond the search scope. Population filters use evidenced module, origin and source classification; unavailable metadata is qualified rather than guessed.

Document population metadata is reserved against the auxiliary budget **before** parsing/retention. Its context index keeps only module/source-classification columns, not complete parsed document payloads, and description reuses those columns. Exhaustion remains PARTIAL/INPUT. Inventory still scans the context for exact filtering/counts; this is not a promise of lazy whole-query execution. Malformed PACKAGE symbol tuples are qualified as `INVALID_CANONICAL_NAME`, with absent derived labels and unchanged source evidence, rather than crashing or inventing a package name.

`ArchitectureQueryService` provides:

| Operations | Contract |
|---|---|
| `summary`, `provenance`, `entities`, `entity` | Distinct inventory, context provenance and bounded identity-ordered search/detail |
| `edges`, `dependencies`, `expand` | Focused logical edges with raw support/evidence IDs; INCOMING gives dependents |
| `relationships` | Raw resolved/candidate/unresolved denominator; unresolved expression text is exposed only as a digest |
| `findPaths`, `shortestPath`, `findCycles` | Simple directed paths/cycles, joint feasibility and bounded witnesses |
| `reachability`, `impact` | Bounded structural paths; impact requires INCOMING and does not claim runtime impact |
| `evidence`, `explain`, `gaps` | Source spans, derivation/provider, typed gap/attempt/conflict inventory and bounded descriptors |
| `spring`, `configurations` | Evidenced candidates, owner-qualified injection uses, registration, conditions, regions, worlds and witness/replay records |
| `artifacts` | Explicit-family **graph-level provenance** catalog, including modeled-space descriptors; no cross-context region composition |
| `comparisonPreparation` | Conservative compatibility descriptor, not snapshot delta or an M11 gate approval |
| `metricCatalog`, `metric` | M5.3 ArchitectureMetricsService supplies the optional port; absent provider returns PARTIAL + `METRICS_PROVIDER_UNAVAILABLE`, without invented zeros |
| `metricProjection` | Full declared component population and logical edges, selected/unknown definition activation; input/solver/auxiliary bounds apply, independent of UI pagination |

Metadata relationships describe structural provenance. Consumers choose dependency kinds explicitly; `graph.contains` or `spring.has-witness` is not a runtime dependency. Java dependency edges mean source dependencies in the selected exact build context. Endpoint inventory without evidenced coverage is qualified; no route is inferred from names. Policy violations/scores are outside this API.

## Projection and region arithmetic

REALIZED requires one evidenced explicit world or a complete Boolean assignment for the selected symbolic context. False edges are absent and unknown edges remain in the separate UNKNOWN_OVERLAY. MUST requires a complete, nonempty modeled space and proof of universal truth. MAY contains varying facts, adding MUST only through `includeMust`. BOUNDED_UNION is exploratory and retains region references. UNKNOWN_OVERLAY includes qualified relationships and unknown residue without manufacturing definite targets.

Explicit regions preserve T/F/U membership and closure. Symbolic regions reconstruct the hash-verified M4 DAG and use the existing bounded DPLL reasoner; they never enumerate Cartesian assignments. Unknown feasibility, unsupported encoding/semantics, missing evaluation evidence, foreign space or incompatible universe/context produces a typed query gap. Empty or incomplete universes cannot prove MUST. Classification is recomputed from the composed regions, not inferred from observed sample counts.

Type/package/module grouping follows evidenced ownership. Internal package/module dependencies do not produce artificial self-cycles. Spring component grouping maps an owner-qualified INJECTION_USE back to its candidate owner. Parallel supports for one logical edge combine with **OR**, preserving their raw IDs. Consecutive edges and endpoint activation combine with **AND**. A single request does not fuse MAIN/TEST, classpath, material-order or Spring evaluation contexts.

Every path prefix retains its own conjunction. UNSAT prefixes are pruned immediately; a visited vertex alone never subsumes another prefix. Pairwise intersections do not replace full conjunction. A SAT result contains one joint witness. Symbolic witnesses are independently evaluated against the joint Boolean DAG by the existing reasoner (`formulaReplayed=true`). Explicit witnesses identify a common evidenced world. `semanticReplayed=false` deliberately indicates that M5.2 does not rerun extraction or the Spring container; upstream semantic replay records remain independently accessible. `dev` and `prod` are not automatically exclusive.

Shortest paths use breadth-first traversal, canonical target order and simple paths. A qualified shorter candidate prevents an unqualified completeness claim. Reachability returns a deterministic first feasible path per dependent but continues other prefixes where their regions differ. Missing results under a guard never mean proven unreachability.

## Bounded traversal and result state

Tarjan uses explicit stacks and linear graph work before cycle enumeration. Singleton SCCs without self-loops skip enumeration. The enumerator uses iterative canonical-minimum-start simple-cycle search, an equivalent cycle-set implementation to Johnson, with smaller bounded auxiliary state rather than unbounded blocked-set bookkeeping. Each directed simple cycle is emitted once by canonical rotation; reverse direction is distinct only when its directed edges exist. Parallel evidence does not multiply cycles. Union SCCs are structural candidates, not feasibility proofs.

This enumerator does not claim Johnson's output-sensitive efficiency. Dense SCCs can exhaust the global work budget and legitimately return PARTIAL. A vertex-only blocked cache must not be added to SAT-pruned traversal: failure under one prefix's truth region does not establish failure under another. A regression deliberately explores an infeasible prefix first and then finds the feasible cycle through the shared vertex. Any future optimization needs region-aware correctness controls and bounded evidence of improvement.

| Limit | Default / hard maximum |
|---|---|
| Vertices in a current simple search path | 20 / 20; self-loop depth 1 |
| Enumeration steps | 100,000 / 100,000; edge examination plus cycle push/pop/start bookkeeping |
| Returned path/cycle witnesses or page size | 50 / 50 |
| Context input vertices / edges | 100,000 / 500,000 |
| Cumulative solver steps | 1,000,000 / 10,000,000 |
| Formula nodes | 100,000 / 100,000; reconstruction/solver depth bounded at 128 |
| Auxiliary state units | 200,000 / 500,000; indices, memberships/supports and frontier guarded before growth |

Counters are cumulative across all SCCs/start vertices in one request; cycle enumeration has no continuation that resets them. Tarjan/preprocessing has its own bounded scan counter and is not subject to cycle depth 20. Unknown candidates use counters and at most 50 frontier IDs, not an unbounded pending-candidate list. Prefix queues and SCC auxiliary state have separate allocation guards. A default available-heap reserve is enabled; callers can add cancellation, time or heap guards through `operationalGuard`/the service constructor.

Guarded results retain found witnesses and return M1 `AssessmentStatus.PARTIAL`, `truncated=true`, stop reason and typed `CapabilityGapRecord`. Work/depth set `workBudgetExceeded`; output quota sets `outputBudgetExceeded` without falsely setting the work flag. Stopping at witness 50 is PARTIAL even when the graph might contain exactly 50. Empty witnesses are valid when a guard fires before the first witness. Exact cycle totals are present only after full enumeration and feasibility resolution. Rejected-infeasible counters count pruned prefixes/candidates, **not** an exact number of all cycles hidden behind those prefixes.

Semantic qualification may return PARTIAL without truncation. Cancellation and host resource interruption have separate operational outcomes. Host-dependent interrupted prefixes are not reproducibility guarantees or canonical complete cache entries. Complete and deterministic-budget results have canonical result digests. No `OutOfMemoryError` catch is used as budget enforcement; the finite limits do not guarantee survival if an unrelated process/allocator exhausts the host.

Heap headroom (`maxMemory - totalMemory + freeMemory`) is an instantaneous GC-sensitive signal, used only as an operational guard. It is neither an exact byte-accounting model nor a strict memory/latency SLA; deterministic work/input/output limits remain separate.

## Pagination, evidence and compatibility

Pages use fixed identity-ascending sort. Their cursor binds graph, API version, normalized filters, projection, operation arguments and page size. Invalid/foreign/stale cursors are rejected. Page truncation is explicit; exact totals describe fully enumerated matching items, never the page size. Evidence/support lists are separately capped with qualifications and aggregate support counts. Raw graph evidence remains immutable and retrievable through its graph provenance.

Public evidence descriptors use a family and field allowlist. They preserve source coordinates, opaque IDs, provider/version, registration links, gap reasons/attempt references, truth-region/witness references and domain cardinalities. Raw source bytes, annotation spelling containing literals, configuration values/full assignments, host paths and free-text diagnostics are withheld. Modeled domains expose hashed variable descriptors and value counts. `artifacts` is graph provenance, not an assertion that all listed spaces belong to the selected conditional world.

Comparison preparation distinguishes identical graph artifacts from compatible future inputs. It compares manifest/provider/rule/schema, module/source-set, ordered binaries, platform, evaluator/reasoner/limits and formula-encoding policy. Known snapshot/source/classpath-manifest input-binding hashes are excluded from policy comparison; ordered binary contents and context partition are still checked. Upstream capability gaps qualify confidence without automatically changing policy compatibility. Configuration-space correspondence, full evolution semantics and deltas still belong to M11; this descriptor does not authorize Track B.

The M5.2 consumer tests exposed a narrow M5.1 omission: Spring plan/evaluation gaps were retained inside full payloads but not always searchable as CAPABILITY_GAP nodes. The builder now additionally projects those exact existing typed records into their Spring context. No M1 identity preimage or graph snapshot wire schema changes.

## Verification boundary

Core projection/query/path-cycle suites cover Q01–Q12 with negative controls, exhaustive small Boolean comparisons, a 60-variable symbolic case, long DAGs, depth 20/21, quota 49/50/51, exactly 100,000 global steps, solver/input/cancellation/memory/time guards, cursor replay and redaction. Adapter tests exercise actual Java/Spring extraction, package cycles, profile-hidden bindings, snapshot comparison and qualified partial sources. File-store tests compare query results after fresh-instance load with the in-memory store.

The current M4 provider qualifies `@Scope("prototype")` as `ANNOTATION_UNSUPPORTED` in the tested source path. M5.2 retains its evidence and UNKNOWN binding overlay; it does not pretend to implement missing scope/lifetime semantics or issue a violation. Independent review, numerical product-budget approval, corpus performance evidence and G4 adjudication are not claimed. M5.3 implementation and its actual bounded evidence are recorded in the [review package](../research/m5-g4-bounded-evidence.md).
