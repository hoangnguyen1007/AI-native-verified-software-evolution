# M6.2 — Configuration-qualified policy evaluation and Evidence Bundles

**CONFIRMED by implementation and bounded tests (2026-10-15):** the neutral analyzer
evaluates M6.1 forbidden-dependency, directional boundary, layer-boundary and
acyclic rules. This slice does not complete M6 or certify G5. External policy v1
remains PROVISIONAL; no score formula, weight or threshold is adopted here.

## Ownership and entry point

`PolicyEvaluator.evaluate(compilation, plan, queryService, limits)` consumes a
compiler result and its complete `PolicyGraphMapper` plan. These are trusted
platform computations, not externally deserialized claims of selector correctness.
Compilation, graph, context, rule denominator and request scope/relationship
compatibility are checked. Foreign inputs are caller contract errors. Invalid
external policy remains WITHHELD with its original typed compilation gaps.

Business predicates use stable query services; graph payload access is restricted
to bounded acquisition of neutral source/provenance evidence. No parser, storage
adapter, target lifecycle, network access, plugin execution or ambient discovery
is introduced. M1 serialization, graph snapshot schema, existing M5 query identities,
M6.1 compiler/mapping/condition identities and metric formulas remain unchanged.

## Rule and finding semantics

Every compiled rule retains one `RuleResult`, even after mapping failure,
cancellation or exhaustion. Direct constraints evaluate each selected logical
dependency. Cycles use only the mapper's eligible induced edges; an individual edge
is not a cycle. Iterative SCC discovery and canonical simple-cycle traversal reuse
the bounded M5 implementation. Directed reverse cycles remain distinct; rotations
and parallel raw supports do not multiply a logical cycle.

For each route, AND combines complete edge truth regions, endpoint activation and
three-valued selector eligibility. At rule level, OR combines the resulting
violating regions. Two complementary MAY findings can therefore make a MUST rule.
An unresolved selector never becomes a known predicate. Missing source provenance
also gates its route as unresolved. An open rule denominator adds unresolved
residue over the modeled universe; already proved universal violations remain
visible, but missing alternatives cannot prove conformance or supply a clean
counter-witness.

`NEVER` is retained as a clean rule result and produces no warning. `MUST`, `MAY`
and `UNKNOWN` route findings are separate from severity and evidence completeness.
Withheld/unknown rules may have no concrete route finding: the rule row and typed
gaps still account for them. A positive finding does not erase upstream gaps.

The additive `architecture.rule-condition:m6.2-v1` query contract accepts zero to
50 qualified OR alternatives, each containing 1–20 AND edges. An empty predicate
is false only inside a nonempty, complete modeled universe. Empty, incompatible,
unavailable or incomplete universes retain UNKNOWN. Structural contexts use their
single exact structural world. Spring contexts reconstruct explicit or symbolic
universes from canonical region evidence; a declared space without regions cannot
certify a universal result. Preferred explicit/symbolic representations are not
mixed. Distinct symbolic universe formulae require bounded SAT equivalence proof;
conflicts or exhausted proof budgets retain UNKNOWN instead of narrowing the space.
The original M6.1 predicate API retains its behavior.

`policyCycles` is an additive query restricted to outgoing BOUNDED_UNION requests.
Unknown cycles retain their complete bounded route without an invented witness.
The original `findCycles` API remains unchanged. Pairwise feasibility never proves
joint feasibility, and an exploratory union never becomes one runtime world.

## Evidence and identities

`policy.evaluation:m6.2-v1` and `policy.evidence-bundle:m6.2-v1` bind:

- snapshot, analysis, graph, exact context and configuration-space identity;
- semantic policy/rule, declared versions, severity, exact raw policy SHA-256,
  compilation/mapping and query identity, request, limits and replayable predicate;
- directed logical route, original supporting relationships, region references,
  Java semantic statuses and derivation kinds;
- full original source spans, document identities, source SHA-256 and evidence
  payload references;
- affected-region digest, witness/counter-witness, gaps and limitations; and
- context/manifest provenance plus original upstream gap/conflict payload references
  with their own contexts for evidence drill-down.

Source spans are acquired from original occurrences/declarations. Spring descriptors
also follow exact `siteSlot`, `ownerDeclarationKey` and `declarationSlot` references
to Java declarations in the same analysis partition. Source digests come from the
graph's document inventory; missing/conflicting digests, malformed spans and source
evidence digest mismatches qualify the route. A span on a supporting declaration
is not represented as a generated relationship's own source span.

The full finding and bundle identities hash their content. `Finding.key()` provides
a separately hashed logical key within compatible inputs: raw policy formatting
can change evidence content without changing the key. Directed route order and
canonical rule order support deterministic deduplication. M11 correspondence across
different snapshots is not implemented by this key.

Witnesses are bound to the composed region and deterministically replayed against
the canonical formula/world evidence. Bundle predicates and requests allow fresh
query replay, including after graph persistence. `semanticReplayed` stays false:
this slice does not rerun Spring acquisition, registration or a runtime container,
and makes no new minimum-baseline-delta witness claim. Original M4 witness/replay
records remain available through the graph.

## Bounds and degraded results

| Bound | Default / hard maximum |
|---|---|
| Evaluated route/finding candidates across all rules | 50 / 50 |
| Distinct decoded evidence payload reads | 1,024 / 4,096 |
| Graph node + edge input items; cumulative payload visits | 500,000 / 1,000,000 |
| Cumulative decoded evidence UTF-16 characters | 8,000,000 / 16,000,000 |
| One evidence payload | 1,000,000 characters, depth 64, 20,000 JSON values |
| Composed term and traversal budgets | Existing M5 request limits; at most 20 edges |

Budget exhaustion preserves found evidence and every rule row, adding typed
`CapabilityGapRecord` records under `evidence.policy-gaps:m6.2-v1`. There is no
fallback healthy verdict. Query cancellation/resource outcomes remain attached.
The evidence and candidate budgets are global to an evaluation; query solver and
traversal budgets remain per bounded query. Upstream evidence pointers are bounded
separately by the evidence-read count and qualify on exhaustion. Bounds are
defensive controls, not measured latency/memory SLAs.

## Verification and remaining work

The [execution record](../tasks/m6/02-policy-evaluation-and-evidence.md) names exact
commands, counts, initial failures and verification limits. Controls include all
four rule kinds, package/module ownership, negatives and mutations, Boolean oracle
agreement, explicit/symbolic equivalence, infeasible cycles, source corruption,
unknown selectors, closed denominators, cancellation, resource limits, deterministic
replay and actual source/Spring plus persistence integration.

Implementation self-review is not independent review. [M6.3](m6-policy-metrics-and-assessment.md)
now implements policy metrics, explainable candidate assessment, authored labels,
sensitivity and missing-evidence scoring checks. Human score calibration/approval
and G5 acceptance remain open. No full reactor, external repository benchmark,
formal reproducibility package, gate promotion, commit or push is claimed.
