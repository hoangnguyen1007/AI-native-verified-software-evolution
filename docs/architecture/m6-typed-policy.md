# M6.1 — Typed policies, compilation and graph mapping

**CONFIRMED by implementation and bounded tests (2026-10-15):** the neutral `analyzer`
module implements the policy compilation and query-planning foundation below. G4
remains passed. M6.1 does not complete M6 or pass G5.

**PROVISIONAL format:** `architecture-policy:v1` is a reversible, versioned YAML
representation following the existing YAML direction. Its grammar/implementation
are tested; this is not human approval of a frozen external API or scoring model.
No dependency was added: the existing pinned SnakeYAML 2.6 parser is reused.

## Scope and ownership

`PolicyCompiler` consumes exact supplied UTF-8 bytes and an evidence context. It
validates the closed schema and compiles to immutable `ArchitecturePolicy` records,
without parser nodes escaping. `PolicyGraphMapper` binds that AST to exactly one
graph/context through `ArchitectureQueryService`. It returns selector memberships,
dependency candidates and executable query requests. `ArchitectureCondition` adds
bounded conjunction/disjunction of their logical edge regions to the query layer.

The compiler/mapper produce no violation, conformance verdict, score, metric,
generated patch, target execution, database query or external network access.
The implemented [M6.2 evaluator](m6-policy-evaluation.md) owns whole-rule
quantification, bounded cycle evaluation over the selected induced population,
finding identity/deduplication and Evidence Bundles. Explainable
candidate scoring is implemented by [M6.3](m6-policy-metrics-and-assessment.md);
human score calibration/approval and G5 adjudication remain M6
obligations. An empty candidate list is not a policy result or a score of 100.

## External representation

The packaged [JSON-tree schema](../../analyzer/src/main/resources/policy/architecture-policy-v1.schema.json)
and [four-rule example](../../analyzer/src/main/resources/policy/examples/layered-architecture.yaml)
describe the external representation. JSON documents using that tree are also
accepted by the YAML parser. The compiler additionally enforces cross-field,
cross-reference, identifier and resource invariants described here.

The root requires `schema`, namespaced `id`, string `version` and nonempty `rules`.
Every rule requires namespaced `id`, string `version`, `kind`, `severity`, `scope`
and explicit nonempty `relationships`. Versions must be quoted when YAML would
resolve them as numbers. Unknown fields/enums, irrelevant kind-specific fields,
duplicate keys/values/rule IDs and unknown layer references withhold the entire
executable policy. Independent schema-invalid rule rows retain separate problems.
The compiler never executes only the valid subset of an invalid policy.

| Kind | Additional fields | Candidate predicate |
|---|---|---|
| `FORBIDDEN_DEPENDENCY` | `from`, `to` selectors | source matches `from` AND target matches `to` |
| `BOUNDARY` | `from`, `allowed` selectors | source matches `from` AND target does not match `allowed` |
| `LAYER_BOUNDARY` | named `layers` with `select`; `allowed` list of `{from,to}` rows | cross-layer edge absent from the declared allowed directions |
| `ACYCLIC` | `within` selector | eligible traversal edges whose endpoints both match; an edge is not a cycle finding |

Scopes are `TYPE`, `PACKAGE`, `MODULE`, `SPRING_COMPONENT`. Java scopes use
`graph.depends-on`; Spring component scope uses `spring.selected-binding` and/or
`spring.injection-candidate`. Candidate compatibility remains distinct from selected
binding. Provenance/containment relationships are rejected as policy dependencies.
Severity (`INFO`, `WARNING`, `ERROR`) is independent of configuration quantification.

Selectors use one or more dimensions: `packages`, `names`, `modules`, `identities`,
`origins`, `sourceSets`. Alternatives within one dimension are OR; dimensions are
AND. Absent dimensions impose no restriction. Each supplied dimension is nonempty.
`names: ['*']` explicitly selects all components without requiring a guessed label.
Module and node selectors use exact canonical identities, preventing equal display
names in different modules/origins/contexts from collapsing.

Package/name patterns are literal equality, `*`, or a dotted prefix ending `.**`.
The suffix includes the prefix itself and descendants separated by a dot, so
`com.app.web.**` excludes `com.app.webbing`. Arbitrary regex, scripts and embedded
expressions are not supported. `packages: ['']` selects the default package.
Names preserve exact Unicode equality. Package ownership comes from validated M1
structural keys, including nested type owners; it is never guessed by splitting a
type FQN. Missing/malformed metadata yields three-valued `UNKNOWN` membership.
Source-set selection on aggregate package/module views is conservatively `UNKNOWN`:
one display label does not prove every constituent type's source classification.

Layer membership must be unambiguous. Overlap, unknown membership or a dependency
from a configured layer into an unmapped target stays qualified. Same-layer edges
are allowed. Missing `allowed` source rows permit no outgoing cross-layer direction.
Sources outside every declared layer are explicitly outside this rule's scope;
ordinary libraries are not assigned to invented layers. An empty known selector
match produces `SELECTOR_MATCH_EMPTY`, guarding misspelled or inapplicable policies.

## Conditional predicate foundation

`ArchitectureQueryService.condition` accepts only OUTGOING/BOUNDED_UNION requests
and an `ArchitectureCondition.Predicate`: nonempty OR alternatives, each a nonempty
AND term of logical edge identities. It reuses M5 region reconstruction, endpoint
activation, parallel-support union and bounded SAT. It does not intersect labels
such as MAY or infer joint feasibility from pairwise intersections.

The result contains a context/request-bound joint-region digest, conservative
`MUST`/`MAY`/`NEVER`/`UNKNOWN` classification, true witness, counter-witness, supporting
evidence and upstream region references. Counter-witnesses complement the full
predicate within the same feasible universe. Explicit witnesses identify modeled
worlds; symbolic witnesses are replayed against the formula DAG. `semanticReplayed`
remains false: this API does not rerun Spring extraction or a container. An empty or
incomplete universe cannot prove a universal result. UNKNOWN residue remains visible.

A missing/foreign/filtered-out edge reference yields UNKNOWN and a typed gap,
never false. Exhaustion/cancellation cannot prove conformance. Multiple terms can
combine candidate paths or cycles in a later evaluator; M6.1 does not infer a
whole-rule verdict from one candidate. Unknown selector eligibility must remain
unknown even if the selected graph edge itself is MUST.
`Candidate.predicate()` therefore rejects UNKNOWN eligibility; the candidate and
its typed mapping gaps remain available for the later qualified evaluator.

## Identity, evidence and safety

Policy and rule identities bind the typed AST, semantic version and declared
policy/rule versions. Rules, selectors, layers and allow directions have canonical
order. Formatting changes preserve semantic policy identity but change the exact
raw SHA-256 and compilation identity. Compiler results also bind parser syntax
version, limits, schema paths and 1-based code-point locations with exclusive ends.
Coordinates describe the external policy; no repository SourceSpan is fabricated.

Mapping identity binds compilation, graph content, exact context, query/versioned
requests, limits, memberships, candidates and gaps. One immutable rule plan is
retained for every compiled rule, including withheld rules after global exhaustion.
Original graph evidence IDs, source-span drill-down and raw supports survive file
round-trip. `evidence.policy-gaps:m6.1-v1` reuses `CapabilityGapRecord` with provider
observations, exact source/result hashes, requirements and affected POLICY outputs.
Query gaps remain attached. Upstream records are not overwritten or promoted.

The compiler composes YAML nodes without object construction and admits only
mapping, sequence and string tags. It rejects aliases/anchors, merges, custom tags,
multiple documents, malformed UTF-8 and implicit numeric/boolean/null values. Errors
expose codes/schema paths/coordinates and hashes, never input snippets or exception
messages. No filesystem include, environment substitution or remote schema lookup
exists. The JSON schema has local definitions only.

| Bound | Default / hard maximum |
|---|---|
| Input bytes | 262,144 / 1,048,576 |
| Rules | 256 / 256 |
| YAML nodes / nesting | 20,000 / 20,000; depth 24 |
| Scalar UTF-16 units / selector alternatives | 512 / 512; 128 / 128 |
| Layers per rule | 64 / 64 |
| Cumulative mapping bindings | 200,000 / 500,000 |
| Predicate alternatives / edges per term | 50 / 50; 20 / 20 |

Mapping counts every component-selector membership plus candidate edge against one
global budget. Each projection additionally enforces existing M5 input, auxiliary,
solver and operational limits. Query projection truncation withholds that rule's
executable mapping. These are bounded defensive controls, not a latency/memory SLA.

M1 serialization, graph snapshot schema, existing M5 query identities, metric
formulas/catalog and scoring contracts are unchanged. The new condition contract
is separately versioned `architecture.condition:m6.1-v1`.

## Design alternatives and remaining gates

The baseline JSON-only format would be safe and simple; strict YAML reuses the
existing approved direction/dependency and supports the same JSON tree. A general
expression DSL would add execution/validation ambiguity and is unnecessary for
these four rule kinds. The AST/query boundary allows another external adapter
without changing semantic identities. New rule kinds, dependency catalogs or
selector semantics require explicit versions and compatible contract tests.

Bounded tests cover schema errors, unsafe YAML, malformed input, raw/semantic
identity, Unicode/default/nested packages, typed kinds, canonical module/package
ownership, layer overlaps, uncertainty, resource/cancellation closure, infeasible
joint predicates, witnesses, source mutations and persistence. Exact commands and
results are in the [M6.1 execution record](../tasks/m6/01-typed-policy-and-compiler.md).
Self-review is not independent review. M6.2 policy evaluation is delivered
separately; score acceptance and G5 remain open; no external benchmark or full
reactor was run for this slice.
