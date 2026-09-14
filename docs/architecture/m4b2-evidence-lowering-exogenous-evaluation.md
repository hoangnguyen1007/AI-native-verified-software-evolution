# M4B.2 — Evidence-to-IR Lowering and Bounded Exogenous Evaluation

Date: 2026-09-14. Implements the M4B.2 boundary under [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md), the [accepted M4-R0 contract](m4-r0-semantics-gate.md), and the [M4B.1 condition foundation](m4b1-configuration-space-condition-ir.md). Package: `com.evolution.analysis.spring.condition`, in the neutral `analyzer` module.

## Delivered boundary

**CONFIRMED by implementation and verification:** this slice lowers exact M4A mechanism obligations and explicit configuration inputs into the delivered M4B.1 condition IR, then provides a bounded, evidence-qualified interpreter for exogenous conditions (profiles, properties, web mode, and build constants) before bean registration.

The slice delivers three cooperating providers:
1. `spring.condition-lowering:m4b.2` (result `spring-condition-lowering-v1`): lowers literal `@Profile` and `@ConditionalOnProperty` annotations, reconciles build observations (`@ConditionalOnClass`, `@ConditionalOnResource`, `@ConditionalOnJava`), and partitions all M4A obligations with a closed denominator.
2. `spring.exogenous-evaluator:m4b.2` (schema `spring-exogenous-evaluation-v1`, semantics `spring.exogenous-semantics:m4b.2-v1`): evaluates a single configuration assignment against the condition model under explicit source precedence (`spring.normalized-precedence:last-active-v1`), profile policy (`spring.normalized-profiles:defaults-includes-groups-v1`), and exact value conversion (`spring.normalized-values:exact-v1`).
3. `spring.finite-exogenous-evaluator:m4b.2` (schema `spring-finite-exogenous-evaluation-v1`): exhaustively enumerates small-space Cartesian assignments with strict step and assignment bounds, establishing baseline feasibility (`FEASIBLE`, `UNKNOWN`, or `INVALID_MODEL`).

Operational gaps are emitted into `evidence.spring-condition-gaps:m4b.2-v1`. This slice performs no target reflection, class loading, container lifecycle execution, bean registration, or production SAT solver execution. Endogenous bean conditions (`@ConditionalOnBean`, `@ConditionalOnMissingBean`) and custom `@Conditional` classes remain `UNKNOWN` with typed obligations. G3 remains pending.

## Formal invariants

### Closed Lowering Partition

For any `SpringMechanismInventory`, every obligation is accounted for without silent omission:

```text
inputObligations = loweredRows + opaqueRows + otherObligations
```

- `LOWERED`: the condition is within the bounded literal fragment (`@Profile`, `@ConditionalOnProperty`) or is a verified whole-annotation build observation with matching M3 context and provenance.
- `OPAQUE`: unsupported literal syntax, project-defined impostors, unverified version fragments, bean-state conditions, custom conditions, or lowering budget exhaustion. An opaque condition wraps an explicit reason and emits a typed `CapabilityGapRecord`.
- `NOT_A_CONDITION`: any obligation not tagged as `spring.condition.*`.

### Profile Expression Grammar

Profile expressions are parsed strictly via `ProfileExpressionLowering`:
- Operators: `!`, `&`, `|`, and balanced parentheses `(...)`.
- **Precedence disambiguation:** mixed `&` and `|` without parentheses is invalid syntax (e.g. `a & b | c` is rejected as unsupported).
- Bounded depth: expression nesting depth is capped at `maxProfileDepth` (default 256).
- Sibling array values are disjoined (`ANY`), and duplicate names are deduplicated canonically via `TreeMap`.

### Profile Policy and Group Closure

Profiles are resolved according to `spring.normalized-profiles:defaults-includes-groups-v1`:
1. Baseline profile variables are mapped to their assigned truth (`TRUE`, `FALSE`, or `UNKNOWN`).
2. Included profiles (`includes()`) are unconditionally asserted `TRUE`.
3. Positive group definitions (`groups()`) are expanded via monotone finite reachability (queue-based BFS), cycle-safe against recursive groups.
4. Default profiles (`defaultProfiles()`) are activated **only if no other profile is active** (`default = default OR NOT(anyActive)`).
5. If default profiles are activated, group expansion is re-run to closure.

### Property Evaluation and Precedence

Properties are evaluated according to `spring.normalized-precedence:last-active-v1`:
- Sources in `precedencePolicy().lowToHigh()` are evaluated in strict order. Higher-precedence active sources override lower ones.
- A value of `MISSING` in a higher source represents an unconfigured key and **falls through** (does not delete lower values).
- The empty string `""` is a distinct exact value and does overwrite lower values.
- `matchIfMissing`: if the effective property value remains `MISSING`, the condition evaluates to `truth(matchIfMissing)`.
- `havingValue`:
  - If `havingValue` is empty: any value other than `"false"` (case-insensitive) matches (`TRUE`).
  - If `havingValue` is non-empty: exact case-insensitive match against the string value.
  - SpEL expressions (`${...}`), array indices (`[...]`), or unmapped `OTHER` abstractions yield `PROPERTY_SEMANTICS_UNSUPPORTED` / `OTHER_ABSTRACTION_NOT_VALIDATED` -> `UNKNOWN`.

### Strong-Kleene 3-Valued Logic

Evaluations strictly obey Strong-Kleene truth tables:
- `TRUE AND UNKNOWN = UNKNOWN`, `FALSE AND UNKNOWN = FALSE` (short-circuit absorption).
- `FALSE OR UNKNOWN = UNKNOWN`, `TRUE OR UNKNOWN = TRUE`.
- `NOT UNKNOWN = UNKNOWN`.
- Opaque obligations and bean-state conditions always evaluate to `UNKNOWN`. Absorbing Boolean values (e.g. `FALSE AND opaque`) evaluate to `FALSE`, but the opaque capability gap is strictly preserved in the result denominator.

### Finite Configuration Space Enumeration

The Cartesian space of baseline variables and independent source choices is bounded:
- If the Cartesian size exceeds `maxCartesianAssignments` or `maxAssignments`, enumeration halts, `complete = false`, and overall feasibility is `UNKNOWN`.
- An empty declared domain yields `INVALID_MODEL` immediately.
- If all evaluated assignments are infeasible and enumeration is complete, the model is `INVALID_MODEL`.
- If at least one assignment is feasible, the model is `FEASIBLE`.

## Provenance and Identities

- `SpringBuildContext` must match the inventory's analysis identity and framework evidence.
- Framework version fragments are accepted only when matching the exact SHA-256 pins for Framework 5.3.31/Boot 2.7.18, 6.1.14/Boot 3.3.5, or 6.2.0/Boot 3.4.0 from `benchmarks/m4-r0/artifacts.lock.json`. Any divergence yields `VERSION_FRAGMENT_NOT_VALIDATED`.
- Project types imitating Spring annotation names (`EntityOrigin.PROJECT`) are marked `POTENTIAL_CONDITION_UNCLASSIFIED` and remain `OPAQUE`.
- Build constant observations (`CLASS_PRESENT`, `RESOURCE_PRESENT`, `JAVA_VERSION`) require whole-annotation query proofs tied to the build context; conflicting proofs emit `BUILD_EVIDENCE_CONFLICT`.
- Results and assignments are content-addressed using canonical JSON representations.

## Deterministic Resource Budgets

Every limit is bound into the operation's input digest:
- Lowering: `Limits(maxLoweredRows=10000, maxMetadataCharacters=16384, maxProfileDepth=256)`.
- Exogenous Evaluation: `Limits(maxSteps=100000, maxSourceEntries=10000)`.
- Finite Evaluation: `Limits(maxAssignments=4096, maxOutputRows=100000, maxTotalSteps=100000)`.

Budget exhaustion deterministically yields `EVALUATION_LIMIT` or `LOWERING_LIMIT` with `UNKNOWN` truth, avoiding unhandled memory or recursion errors.

## Verification and Next Boundary

Verification passes **333 tests across 54 suites** in the full six-module reactor (parent plus five test-bearing modules) with zero failures, errors, or skips. The 23 new focused tests cover:
- Exact literal property/profile lowering with source provenance.
- Exhaustive 8-mask profile Boolean algebra fixture.
- Impostor rejection, tampered artifact lock rejection, and conflicting build proof handling.
- Property cascade, `MISSING` fall-through, whitespace/empty preservation, and `matchIfMissing`.
- Unavailable source footprint tainting and cycle-safe profile group expansion.
- Deterministic step-budget replay and Cartesian saturation guards.

See the verification package in `docs/reproducibility/m4b2-exogenous-evaluation-2026-09-14/README.md`.

Next boundary: **Milestone 4 - Slice M4C.1: Phase/Order-Aware Spring Registration Plan and Discovery Transitions**.
