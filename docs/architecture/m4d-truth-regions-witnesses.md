# M4D — Truth Regions and Revalidated Witnesses

Date: 2026-09-18. Implements the complete M4D production boundary under
[ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md), the
[accepted M4-R0 contract](m4-r0-semantics-gate.md), and the normalized
[M4C.2 registration](m4c2-ordered-bean-registration.md) / [M4C.3 binding](m4c3-injection-binding.md)
contracts. Package: `com.evolution.analysis.spring.truth`, neutral `analyzer` module.

## Delivered boundary

**CONFIRMED by implementation and focused specification tests:** M4D aggregates one closed,
bounded finite configuration space into storage-neutral conditional facts with explicit
`T`/`F`/`U` regions and `MUST`/`MAY`/`NEVER`/`UNKNOWN` classifications. It delivers:

- a solver-neutral `ConfigurationReasoner` port and deterministic exhaustive small-space backend;
- content-addressed modeled-world, fact-key, region, legal-order, witness, semantics-context and
  evaluation-result identities using the accepted `m4-r0-identity-v1` envelope;
- definition-presence, injection-candidate and selected-binding fact families derived from exact
  M4C input/result identities;
- separate configuration feasibility, semantic truth and operational outcome axes;
- deterministic minimum-cardinality baseline-delta witnesses for every non-empty replay-eligible
  `T`, `F` and `U` region, followed by independent replay through M4B/M4C;
- a closed fact/world/region denominator, typed capability gaps and deterministic result-affecting
  limits.

The provider performs no filesystem/network/environment acquisition, reflection, target class
loading, bean creation, build lifecycle or application execution. It selects no SAT/BDD backend.
M4E evaluation and Gate G3 remain pending.

## Entry point and ownership

```java
TruthRegionEvaluation.Result result = TruthRegionEvaluation.evaluate(
        new TruthRegionEvaluation.Request(
                bindingPlan,
                conditionModel,
                conditionSemantics,
                baselineAssignment,
                ExhaustiveConfigurationReasoner.INSTANCE,
                limits,
                evaluatorArtifactDigest));
```

| Component | Responsibility |
|---|---|
| `ConfigurationReasoner` | Solver-neutral finite enumeration, satisfiability, canonical witness, implication and equivalence operations |
| `ExhaustiveConfigurationReasoner` | Deterministic reference backend over the existing bounded M4B finite enumerator; not a production SAT claim |
| `ConditionalFactKey` | Typed fact identity for final definition presence, eligible injection candidate and selected binding |
| `TruthRegionEvaluation` | Context validation, per-world M4C evaluation, region aggregation, classification, witness minimization/replay and closed coverage |
| `TruthProcessing` | Additive `evidence.spring-truth-region-gaps:m4d-v1` reason catalog using the unchanged M3 `CapabilityGapRecord` schema |

M4D consumes normalized evidence. It does not acquire scan membership, source-to-descriptor mappings,
type/qualifier proofs, external deployment values or legal registration orders. Those remain the
responsibility of the supplying M4A–M4C providers and their explicit gaps.

## World and feasibility semantics

The exhaustive backend enumerates the exact M4B Cartesian dimensions, including independent
property-source alternatives. Each assignment is evaluated for normalized constraint feasibility
before M4C is invoked.

| Assignment result | M4D handling |
|---|---|
| `TRUE` feasibility | Member of the known feasible world set `K`; evaluate M4C when contexts and limits permit |
| `FALSE` feasibility | Retained as an explicit infeasible assignment; excluded from `T`/`F`/`U` |
| `UNKNOWN` feasibility | Retained separately; prevents a universal classification and is never converted to `FALSE` |
| empty complete space | `INVALID_MODEL`; never a vacuous `MUST` or `NEVER` |
| incomplete enumeration | Partial explored worlds remain inspectable, but every affected classification is `UNKNOWN` |

`spring-world` identity binds the complete assignment, M4D semantics context, evidenced legal-order
identity and branch evidence. The current M4C fragment supplies one evidenced total registration
schedule or an incomplete prefix; M4D does not fabricate alternate legal orders from missing order
evidence. The legal-order identity preserves the exact prefix, order evidence and completeness.

## Fact denominator and truth mapping

The implemented denominator is derived deterministically from the M4C plan:

1. one `DEFINITION_PRESENT` fact for every initial or planned bean-definition candidate;
2. one `INJECTION_CANDIDATE` fact for every exact descriptor/candidate match-evidence pair;
3. one `SELECTED_BINDING` fact for every such pair.

The binding owner participates in the fact identity when present, so repeated contextual uses of one
source injection declaration cannot collapse. The injection-point identity remains the exact site.

Definition presence consumes M4C.2 `finalPresence`. Injection-candidate truth consumes the effective
M4C.3 eligible-candidate set; selected-binding truth consumes the effective selected-target set.
Definite inactive, absent, unsatisfied, ambiguous or group-skipped results establish that a selected
edge is false, while deferred, unknown, error and not-reached results remain `UNKNOWN`. Operational
status is retained independently, so a definite semantic non-selection never erases a container or
resolution error.

Fact limits never silently claim closure: input fact count, emitted regions and withheld fact count
must reconcile, and `FACT_LIMIT` is a typed capability gap.

## Region classification

For the known feasible set `K`, each emitted fact has disjoint regions `T`, `F` and `U`, and their
union must equal `K`.

| Classification | Implemented rule |
|---|---|
| `MUST` | enumeration/feasibility/context are complete, `K` is non-empty and `T = K` |
| `NEVER` | enumeration/feasibility/context are complete, `K` is non-empty and `F = K` |
| `MAY` | classification inputs are complete and both `T` and `F` are non-empty; any `U` residue remains visible |
| `UNKNOWN` | all other cases, including one-sided `T+U` / `F+U`, unknown feasibility, incomplete enumeration/context, limits or empty `K` |

The explicit-world region encoding is `spring.truth-region-encoding:explicit-worlds-m4d-v1`.
Regions contain modeled-world keys, never witness identities. Region identity includes the fact key,
M4D semantics context, `T`/`F`/`U` and a feasibility/completeness summary.

## Witness minimization and replay

The caller supplies one explicit baseline assignment. M4D derives a realized M1
`ConfigurationIdentity` from its exact typed values and source choices under
`spring.realized-configuration:m4d-v1`; evidence metadata is not mistaken for a configuration value.

For every non-empty replay-eligible `T`, `F` and `U` region, the witness policy
`spring.witness-policy:minimum-cardinality-revalidated-m4d-v1`:

1. compares every evidenced region world with the baseline;
2. minimizes the number of differing baseline/source dimensions globally;
3. breaks equal-cardinality ties by canonical full-assignment digest;
4. retains the selected full assignment, exact delta, material order and expected truth;
5. independently re-evaluates assignment feasibility and the M4C fact;
6. stores replay identity/result separately so replay cannot feed back into witness identity.

This is a minimum-cardinality exact baseline-delta witness over the enumerated finite space, not a
partial implicant. Omitted fields inherit the declared baseline. Only worlds with an actual M4C result
and operational status `COMPLETE` or `PARTIAL` are replay-eligible. `NOT_EVALUATED`,
`LIMIT_EXCEEDED` and `ERROR` worlds remain in their truth/operational denominators but cannot act as
semantic witnesses; context mismatch therefore emits no witness. A candidate is published only after
successful replay. A mismatch retains its replay diagnostic and typed `WITNESS_REPLAY_FAILED` gap but
is not exposed as a revalidated witness.

## Reasoner port and bounded set operations

`ConfigurationReasoner` exposes bounded enumeration plus satisfiability, witness, implication and
equivalence over explicit regions. `ExhaustiveConfigurationReasoner` returns:

- satisfiable when a member exists;
- unsatisfiable only for an empty complete region;
- unknown for an empty incomplete region;
- implication/equivalence `FALSE` only when an opposing truth value is observed for the same explored
  world; a world absent from an incomplete operand is unknown, not false;
- implication/equivalence `TRUE` only over the same complete universe;
- a lexicographically canonical world witness.

This preserves the accepted backend replacement boundary. A later SAT/BDD implementation must agree
with these semantics and normalized witnesses before adoption; M4D does not select one.

## Identity and compatibility

The M4D `spring-semantics-context` binds exact build/configuration-space/registration-plan identities,
mechanism catalog, condition IR, framework/registration/binding semantics, binding-plan identity,
reasoner policy and every result-affecting limit. Changing match evidence, configuration domains,
registration/binding plans, reasoner policy or limits changes the relevant identities.

The final `spring-evaluation-result` preimage follows M4-R0 exactly: semantics context, ordered region
and witness identities, operational-outcome digest, evidence-ledger digest and caller-supplied evaluator
artifact digest. Timings, host paths and traversal order are excluded. Canonical output contains no
non-string JSON map keys; replay and reordered-equivalent inputs are deterministic.

## Limits, failures and safety

Default M4D limits are 4,096 finite assignments, 100,000 facts, 1,000,000 region cells and 10,000
witness replays, in addition to all M4B/M4C limits. They are deterministic defensive bounds, not
measured repository-scale acceptance thresholds.

M4D adds typed reasons for context/baseline mismatch, incomplete enumeration, unknown/invalid
feasibility, fact/region/witness limits, operational failures and replay failure. A region-cell limit
creates explicit `UNKNOWN` cells and operational outcomes but never a synthetic `UNKNOWN` witness.
Upstream M4A–M4C gaps are unioned unchanged. No missing world, candidate, match or replay is converted
into a negative fact or a clean classification.

## Verification and next boundary

Focused authored tests cover all four quantifiers, definition/candidate/selected-binding regions,
separate unknown and operational states, empty and incomplete spaces, foreign contexts, baseline,
fact, region and witness limits, all ten M4D reason codes, owner-qualified identities, deterministic
canonical replay, identity sensitivity and three-valued reasoner set operations. The focused suite
passes 15 tests and the final six-module reactor passes 469 tests across 62 suites, with zero failures,
errors or skips. Exact commands and hashes are recorded in the
[M4D verification package](../reproducibility/m4d-truth-regions-2026-09-18/README.md).

**Exact next task:** M4E validation and Gate G3 evidence. Run the accepted adjudicated interaction
fixtures and representative repositories across pinned framework tuples, compare registered baselines,
include all failures in denominators, and seek human G3 acceptance. M4D completion alone does not pass
G3 or establish complete Spring runtime-container equivalence.
