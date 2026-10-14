# M5.3 — Architecture metrics

**CONFIRMED by implementation and bounded tests:** Pure Java engine in `analyzer`, consuming the canonical graph and query projection. **PROVISIONAL:** the 19-entry catalog baseline and defensive resource settings; human approval under [ADR-002](../decisions/ADR-002-product-outcome-and-explainable-assessment.md) remains separate. See [bounded verification and G4 review package](../research/m5-g4-bounded-evidence.md) for exact commands and limits. No health score, policy verdict, runtime-container completeness or performance SLA is claimed.

## API and identity

`ArchitectureMetricsService.compute(request, metricId, subject)` returns an immutable `ArchitectureMetric.Result`. `queries()` supplies the existing `ArchitectureQuery.Metrics` port; plain `ArchitectureQueryService` without a provider still reports its explicit unsupported outcome. `MetricCatalog.entries()` exposes definitions, scopes, population, relationship categories, formulas, units, rounding and status rules. Catalog pagination binds graph/request/catalog/version/page size.

Use a graph context identity as the subject for inventory, LOC, quality, operational, Spring, density, SCC, cycles, hotspots, crossings and concentration. Use a component identity for I/A/D and reachability; Ca/Ce/fan-in/out accept a component or a context sum. Inapplicable subjects/scopes produce NOT_APPLICABLE; foreign graph/context or unknown metric IDs are invalid requests. No package-name guessing is performed.

`computeBatch` evaluates declared context partitions independently in canonical order. It never merges incompatible Java source sets, build manifests or Spring worlds. Its metric list is evaluated with context subjects; component ratios need explicit per-component `compute` calls. All genuine contributing analysis identities are retained; a snapshot-only context has no fabricated analysis identity.

The result binds graph digest, definition digest, metric version `m5.3-v1`, partition, contributing analyses, the complete query request (projection, realization, population, limits), metric limits and optional layer-mapping digest. Its semantic identity excludes `computedAt`. Operational measurements are supplied, content-addressed input observations, not a timer sampled during deterministic metric computation. Their payload must match context, phase, nanoseconds and failure state. Missing or conflicting measurements withhold the value.

M1 `MetricEnvelope`, canonical preimages and exact-realization requirements are unchanged. The additive M5 wrapper does not invent a `ConfigurationIdentity` for a configuration space. Graph schema/snapshot format are unchanged. Query version advances to `architecture.query:m5.3-v1` because package/module populations now derive from eligible constituent types, and the full-scope metric projection is added. Recompute request identities/cursors; persisted M5.1 graphs remain readable.

## Implemented catalog baseline

| IDs | Population / interpretation |
|---|---|
| `inventory` | Canonical declarations, kinds/origins/statuses, nested/local/anonymous types, source roles, separate module/root/package/annotation/type-use metadata, relationship rows, evidenced build dependencies and declared source roots. Prefix is `metric.` for every ID here. |
| `physical-loc` | Selected SourceDocument decoded bytes: LF/CRLF/CR each one terminator; final unterminated line counts; empty=0; BOM adds no line. Missing/invalid bytes stay explicit; raw source never enters results. |
| `quality` | Frontend category attempted/emitted/unmapped, source outcomes, semantic status, provenance state, full-span operands, gap/attempt/conflict ledger. Accuracy is NOT_MEASURED_WITHOUT_GROUND_TRUTH. Manifest-bound classpath evidence is not assumed complete from successful parsing. |
| `ca`, `ce` | Distinct incoming/outgoing production package/module neighbors, excluding self-boundary and repeated occurrences. Context totals sum internal directed pairs. |
| `instability`, `abstractness`, `distance` | Martin I, A, D, per package/module only; exact operands below. |
| `fan-in`, `fan-out`, `hotspots` | Distinct projected neighbor counts; hotspot rank by total degree descending then canonical identity. Value for hotspots is internal pair count; bounded ranking and its population size are separate details. |
| `density` | Directed nonself distinct pairs / `n*(n-1)` over the full declared component population. |
| `scc`, `cycles` | Structural SCC count/sizes/cyclic participants, including singleton self-loops. Jointly feasible cycle total only if the query engine proves an exact total; witnesses do not become an exact count. |
| `crossings` | Distinct component pairs whose layers differ under an explicit versioned mapping; no mapping => NOT_APPLICABLE. Different component pairs between the same layers remain distinct. |
| `reachability` | INCOMING bounded dependent count, shortest feasible witness distances and frontier; no exact longest path. |
| `concentration` | Maximum incoming-pair share / total internal pairs; stable target ranking. Raw excluded/external/unresolved exposures remain separate from the internal denominator. |
| `spring` | Discovered candidates/producers/sites/owner-qualified uses/registration events; projected active definitions and unknown activation; discovered versus feasible candidate cardinalities and projected selected-binding pairs; evidenced mechanism obligations. Missing endpoint extraction is a typed gap, never an invented zero-route claim. |
| `operational` | Supplied, verified phase durations/failures; query/projection work and sizes appear in relevant metric details. Latency and memory SLA remain unmeasured. |

Structural scopes: TYPE/PACKAGE/MODULE/SPRING_COMPONENT; Martin scopes: PACKAGE/MODULE. Inventory/quality/LOC/operational are context inventories under declared filters. Metadata/relationship details explicitly labeled as context counts are distinct from filtered declaration counts. Missing build inventory is qualified. Available dependency relationship inputs are `graph.depends-on`, Java semantic dependency categories excluding declares/has-parameter, and Spring selected/candidate bindings; ownership edges are rejected.

Java structural population is PROJECT + MAIN, with generated/test/external types excluded. Source classification comes from evidence, not directory spelling. Package/module owners are induced by eligible types; a generated sibling cannot relabel or remove a production package. Explicit filters excluding production yield NOT_APPLICABLE. Spring structural vertices are projected active definitions plus definite edge endpoints, with uncertainty retained. Type/member inventory remains separate from semantic metadata nodes.

Martin type-owner aggregation is used only for I/A/D, never to require a Java component owner from Spring definitions. Non-Martin Java structural metrics still validate their production population. A Spring context containing Java declarations must not acquire a false COMPONENT_OWNER_UNAVAILABLE gap; real activation/acquisition gaps continue to qualify or withhold results.

Martin operands: `Ca = distinct incoming neighbors`, `Ce = distinct outgoing neighbors`, `N = production types`, `Na = interfaces + abstract classes + annotation types`. Enums/records are concrete; annotation uses do not enter Na/N. `I=Ce/(Ca+Ce)`, `A=Na/N`, `D=abs(Na*(Ca+Ce)+Ce*N-N*(Ca+Ce))/(N*(Ca+Ce))`. D is rounded only after exact rational arithmetic. Decimal scale 12, HALF_EVEN, normalized trailing zeros. Exact numerator and denominator are retained. `Ca+Ce=0` makes I NOT_APPLICABLE; `N=0` makes A NOT_APPLICABLE; D propagates either zero denominator. No substitute 0/1, NaN or Infinity.

## Conditional, degraded and bounded behavior

Metrics use `metricProjection` over full declared scope, never `edges(Page)` or a UI subgraph. Changing UI page size cannot change a metric. MUST means the metric of the MUST graph, not a minimum over configurations. MAY/BOUNDED_UNION are exploratory structures, not one runtime world. No probability or uniform average is inferred. UNKNOWN_OVERLAY cannot produce a definite structural scalar. Path/cycle witnesses retain joint region satisfiability; incompatible prefixes are pruned by M5.2 arithmetic.

COMPLETE has a value with complete declared input. PARTIAL retains useful observed counts and qualifications. WITHHELD omits a value when population/denominator/exact cycle total is unproved. NOT_APPLICABLE omits a value when the formula or scope does not apply. Raw unresolved/unmapped semantic observations qualify the resolved dependency projection, so omission from `graph.depends-on` cannot manufacture clean zero coupling. Upstream snapshot/analysis/Spring gaps remain reachable; conservative withholding is intentional and is not a health improvement.

Default metric limits: 200,000 work/fact units, 8,000,000 characters per parsed payload, 16,000,000 decoded source bytes, 50 detail rows. Payload decoding is charged as `1 + ceil(characters/64)` work units. Node/shape/ownership/aggregation work also consumes units. Limits and operational guards produce typed gaps and preserve useful observed counts; ratios with unknown population are withheld. Defensive caps are not validated product performance budgets. Source byte/charset/BOM/digest checks reuse `SourceInput`.

Query depth/steps/solver/auxiliary/cycle budgets remain enforced by M5.2; iterative Tarjan runs before bounded cycle enumeration. Metric cycles reuse that engine. Full-scope projection and traversal each have their own bounded execution; `queryExecutions` and bridge bounds expose aggregate work, stop reason, frontier and witnesses. A 50-witness quota has outputBudgetExceeded, distinct from workBudgetExceeded. Detail/evidence samples are explicitly truncated; the bound graph identity retains full evidence closure. Sampling details alone does not change the scalar population. Runtime free-memory guards remain operational and GC-dependent, not a deterministic SLA.

## Delivery boundary

No database/server/Neo4j dependency was introduced. The file-store adapter is test-scoped in the JavaParser integration consumer. No full reactor, external corpus campaign, independent review, formal G4 package or gate pass is implied by bounded tests. Human catalog approval, registered product budgets and formal G4 adjudication remain next; M6 is not started by this delivery.
