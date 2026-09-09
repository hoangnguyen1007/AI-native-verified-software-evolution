# Conditional Architecture Semantics

## Status and Authority

**ACCEPTED pre-implementation architecture direction.** [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md) selects this model for M4+ on 2026-09-09. Exact Java schemas, the first supported semantic fragment, solver backend, numerical limits and empirical claims remain **PROVISIONAL** until their gates.

This contract does not start M4, modify the active M3.8 slice, pass G2/G3, or claim equivalence to a complete Spring runtime container.

## Purpose

Represent architecture facts whose truth depends on Spring configuration and ordered framework registration without:

- treating one analyzed configuration as universal;
- combining mutually infeasible edges into one runtime world;
- interpreting missing or opaque evidence as absence;
- coupling domain semantics to a graph database or solver library; or
- losing content-addressed identity and provenance.

## Boundary with M1–M3

M3 produces one exact `BuildContext`: snapshot, workspace/source plan, ordered dependency/reactor classpath, platform-symbol view and associated evidence. M4 constructs one or more bounded Spring configuration models **inside** that context.

Existing `ConfigurationIdentity` remains the identity of one realized set of configuration values. It is not reinterpreted as the identity of every feasible deployment. Cross-build alternatives such as Maven profiles that change dependencies, JDK/toolchain, Gradle variants or AOT preparation require different build-context identities or a future context-family layer.

```text
BuildContext B
  + ModeledConfigurationSpace K
  + SpringSemanticsVersion V
  + RegistrationPlan R
  -> ConditionalFactSet F
  -> Graph / Policy / Product Projections
```

## Canonical Concepts

| Concept | Meaning |
|---|---|
| `BuildContext` | One exact M3 source/build/classpath/platform world |
| `RealizedConfiguration` | One explicit assignment with the existing `ConfigurationIdentity` |
| `RepositoryDeclaredEnvelope` | Configuration declarations found in the immutable snapshot, with source precedence and provenance |
| `UserSuppliedDeploymentEnvelope` | Explicit deployment values/domains supplied for analysis; never inferred from ambient host state |
| `ModeledConfigurationSpace` | Finite domains, constraints, source envelopes and feasibility rules modeled inside one build context |
| `ConfigurationSpaceIdentity` | Content-addressed identity of that modeled space: build context, envelopes, domains, constraints and precedence |
| `ConditionExpression` | Solver-neutral normalized IR for one framework condition |
| `RegistrationPlan` | Versioned ordered/partially ordered set of candidate definition transitions and phase boundaries |
| `ConditionalSemanticsContextIdentity` | Non-circular identity binding build context, configuration space, condition/framework/registration semantics, plan, reasoner policy and result-affecting limits |
| `BeanDefinitionState` | Definitions known at one transition point, including names, exposed types and relevant metadata |
| `ConditionalFact` | Fact plus truth region, evidence, derivation, uncertainty and framework/configuration identities |
| `ConfigurationWitness` | Deterministic assignment demonstrating a fact/finding or its counterexample |
| `AffectedConfigurationRegion` | Canonical formula/region under which a fact, finding or delta is true |

## Configuration-Space Identity

`ConfigurationSpaceIdentity` must hash a canonical serialization of at least:

1. schema version;
2. exact build-context identity;
3. repository-declared and user-supplied envelope identities;
4. ordered property-source/import/precedence semantics;
5. variable names, typed domains and `MISSING`/`OTHER` abstraction semantics;
6. feasibility constraints and profile-group semantics;
7. feasibility/domain-abstraction version.

It must not depend on a registration plan that is itself derived from the application and condition expressions. A separate `ConditionalSemanticsContextIdentity` hashes the build context, configuration-space identity, condition IR/catalog version, Spring/Boot semantic version matrix, registration-plan identity, reasoner/branching policy and result-affecting limits. A registration-plan preimage is derived only from build facts, discovered producers/conditions, framework semantics and ordering evidence—not from the semantics-context identity that later contains it.

Timestamps, localized text, host paths and solver traversal order are not identity inputs. If a limit can change a result from definite to `UNKNOWN`, it is result-affecting and must be bound to the conditional semantics context and comparison compatibility.

## Configuration Evidence and Finite Domains

### Source envelopes

Repository declarations and user/deployment declarations remain separate evidence layers. The model must preserve:

- exact file/artifact/environment descriptor identity;
- key/value or symbolic domain;
- activation document/profile expression;
- import chain and precedence;
- origin, source span when available and acquisition provider;
- unsupported loaders or unresolved external locations.

Repository files are not proof of the complete production environment. Missing external imports, secrets, config trees, custom loaders or environment state produce `CONFIGURATION` evidence requirements and qualify affected conclusions.

### Domain abstraction

For each property/profile variable, the v1 finite domain may include:

- exact values observed in registered sources/annotations/fixtures;
- `MISSING`;
- a canonical `OTHER` equivalence class only when all affected predicates evaluate uniformly over that class.

If uniformity cannot be established, split the domain, request evidence or return `UNKNOWN`. An empty property and a missing property are distinct when framework semantics distinguish them.

Feasibility constraints must account for profile expressions/groups, mutually exclusive benchmark assumptions only when explicitly supplied, and cross-variable restrictions derived from authoritative declarations. The analyzer never invents “dev/prod are mutually exclusive.”

## Condition IR v1

The IR is immutable, canonical and solver-neutral. The initial node catalog should cover:

| Class | Representative nodes | State dependency |
|---|---|---|
| Boolean | constant, `ALL`, `ANY`, `NOT` | child expressions |
| Exogenous configuration | profile expression, property presence/value, web mode | realized configuration |
| Build-context constant | class/resource present, Java/framework version | exact build context |
| Endogenous bean state | bean present, bean missing, single candidate | current ordered bean-definition state |
| Opaque/dynamic | custom `Condition`, unresolved SpEL, registrar/post-processor/runtime computation | explicit missing evidence |

Every node carries a stable kind/version, normalized typed operands, framework semantic version, source/evidence references and any unsupported reason. Unrecognized conditions remain an opaque node and capability gap; they are not dropped or evaluated false.

## Evaluation Semantics

### Logical values

Condition evaluation returns:

- `TRUE`: established true under the exact inputs/state;
- `FALSE`: established false under the exact inputs/state;
- `UNKNOWN`: current evidence or bounded reasoning cannot establish either.

Kleene-style composition may be used for pure Boolean nodes if its truth table is versioned and tested. Operational outcomes—`ERROR`, `UNSUPPORTED`, `TIMEOUT`, `LIMIT_EXCEEDED`, `DENIED`, `UNAVAILABLE`—remain separate records. They commonly force affected logical results to `UNKNOWN`, but are never erased into it.

### Framework phases

The semantic version defines the supported phase/order model. At minimum it distinguishes configuration parsing from bean registration and separates user definitions from ordered auto-configuration where the registered Spring version does so.

For an ordered registration plan `R = [r1, ..., rn]`:

```text
S0 = bean definitions established before R

evaluate(ri.condition, B, c, Si) = TRUE
  => Si+1 = register(ri, Si)

evaluate(ri.condition, B, c, Si) = FALSE
  => Si+1 = Si

evaluate(ri.condition, B, c, Si) = UNKNOWN
  => branch only when the branch is sound and within the declared budget;
     otherwise mark ri and all dependent conclusions UNKNOWN
```

`@ConditionalOnMissingBean` and similar predicates make the general system non-monotone. The analyzer must not replace this transition model with an unordered least fixpoint unless equivalence is proved for a restricted fragment.

If only a partial order is known, deterministic topological exploration may be used within a registered bound. If alternative legal orders change a conclusion, the fact is order-dependent (`MAY` or `UNKNOWN` as evidence permits), and witnesses retain the order identity. An arbitrary lexicographic order is not framework evidence.

### Binding semantics

For each realized world/branch:

1. determine registered and active bean candidates;
2. apply type/generic/container compatibility;
3. apply qualifier/name semantics;
4. apply primary/fallback/priority semantics for the pinned framework version;
5. emit zero/one/many candidate and selected-binding results without hiding ambiguity.

Candidate compatibility, activation, registration, selection and runtime instantiation remain distinct axes.

## Truth Regions and Finding Classification

For feasible modeled configurations `K`, each fact/finding has definite-true, definite-false and unresolved regions `T`, `F`, `U`.

| Classification | Rule |
|---|---|
| `MUST` | `T = K` and `K` is non-empty |
| `NEVER` | `F = K` and `K` is non-empty |
| `MAY` | `T` and `F` are both non-empty; unresolved residue is reported separately |
| `UNKNOWN` | all remaining cases where unresolved space can change the classification or feasibility is unknown |

An empty/unsatisfiable `K` is an input/model error, not vacuous `MUST` or `NEVER`.

The model also records:

- exact/canonical representation of `T`, `F`, `U` or a bounded summary;
- explored/total region semantics where meaningful;
- reason and capability gaps for `U`;
- build, configuration-space, conditional-semantics-context, policy and solver identities.

`NEVER` is normally a successful clean query/policy result rather than a user-facing violation. A union graph may include every `MAY` edge for exploration but must expose its region and cannot be fed to certain-cycle/policy logic as one world.

## Witness Contract

A `ConfigurationWitness` contains:

- fact/finding and region identity;
- exact assignments that differ from a declared baseline plus inherited/default values needed for replay;
- relevant build/classpath constants;
- registration-order/branch identity when material;
- expected truth value;
- solver/evaluator/semantic versions;
- supporting evidence and gaps;
- a digest of independent re-evaluation.

Minimization removes assignments only when the witness remains valid. Multiple minima use a canonical variable/value order. Runtime confirmation, when available, is a distinct observation attached to the witness.

## Reasoning Port and Resource Safety

The domain depends on a `ConfigurationReasoner` contract, not a SAT/BDD class. Required operations are bounded satisfiability, witness, counter-witness, implication/equivalence checks and optional region enumeration/counting.

Selection sequence:

1. exhaustive deterministic enumerator as a correctness oracle for small spaces;
2. benchmark a pure-Java SAT implementation for larger supported fragments;
3. add BDD or another compiled representation only if repeated region operations justify it.

All implementations enforce variable/constraint/branch/time/memory/output bounds, deterministic input ordering and normalized witness output. Exhaustion produces typed operational evidence and `UNKNOWN`, never a guessed result.

## Generated and Framework-Synthesized Members

### Lombok

Derived constructors/accessors are emitted only when exact annotation identity, field initialization/`@NonNull` eligibility, field order, Lombok semantic/configuration version and relevant explicit-member interactions are known for the supported fragment. Annotation spans are supporting evidence, not generated declaration spans; generated project members retain project origin. Otherwise request generated source/bytecode or retain a gap.

### Spring Data

A repository interface becomes a bean-definition candidate only when registration evidence establishes enablement/scanning scope, base packages/filters, store binding and exclusion semantics including `@NoRepositoryBean`. Custom fragments, base classes and factories remain visible. An interface extension alone is insufficient.

## Capability Gaps and Evidence Escalation

M4 reuses existing requirement kinds; new enum kinds are not assumed:

| Missing question | Existing requirement kind | Example namespaced question |
|---|---|---|
| external property/profile value | `CONFIGURATION` | `spring.condition.resolve-property-domain` |
| generated Lombok/processor member | `GENERATED_SOURCE` or `BYTECODE` | `spring.injection.resolve-generated-member` |
| dependency auto-configuration metadata/type | `DEPENDENCY_ARTIFACT` or `BYTECODE` | `spring.registration.resolve-auto-configuration` |
| custom condition/registrar outcome | `ISOLATED_BUILD_OUTPUT` or `RUNTIME_OBSERVATION` | `spring.condition.observe-dynamic-outcome` |
| runtime proxy/product type | `RUNTIME_OBSERVATION` | `spring.binding.observe-runtime-product-type` |

Candidate providers remain advisory. Runtime/build execution requires explicit authorization and the isolation rules in [ADR-003](../decisions/ADR-003-progressive-evidence-acquisition.md).

## Graph Projection Contract

Canonical conditional semantics exist before storage projection. M5 may represent condition expressions/regions as nodes, relationships or indexed records, but must preserve:

- conditional fact identity and support;
- producer/candidate/injection/selection distinction;
- `T`/`F`/`U` or canonical region reference;
- witness and capability-gap references;
- framework/configuration-space/registration-plan versions.

Required projections:

- one realized configuration;
- `MUST` facts only;
- `MAY` facts with condition labels;
- bounded union for exploration;
- unknown/gap overlay.

Every projection reports its semantics. Only a realized projection is a candidate representation of one runtime world, and even it is not runtime proof without observation.

## Policy and Assessment Contract

Policy evaluation composes rule predicates with fact regions rather than treating conditional edges as unconditional:

- `MUST` violation: rule violated across all feasible modeled configurations;
- `MAY` violation: both violating and non-violating witnesses exist;
- `UNKNOWN`: missing/opaque evidence can change the verdict;
- `NEVER`: no violation in the modeled space.

Severity and configuration quantifier are orthogonal. A severe `MAY` finding is not silently demoted to informational, and an `UNKNOWN` finding is not counted as clean. Health scoring must define aggregation over configuration regions and withhold when evidence is insufficient; confidence remains separate.

## Evolution Compatibility and Delta

Track B comparison requires compatible or explicitly migrated:

- build-context semantics;
- configuration-space schema/domains/constraints/envelopes;
- framework/condition/registration semantics;
- analyzer, policy, metric and score versions;
- solver result-affecting limits.

Delta categories include fact added/removed/changed-region, `MUST`↔`MAY`↔`NEVER`↔`UNKNOWN`, witness changed, affected-region expanded/shrunk, binding changed and evidence gained/lost/conflicted. A finding disappearance caused by evidence/configuration-space loss is not `RESOLVED`.

## Versioning

Versions are separate where their compatibility concerns differ:

- configuration-space schema;
- conditional-semantics-context schema;
- condition IR catalog;
- Spring/Boot semantics matrix;
- registration semantics;
- mechanism denominator;
- solver normalization policy;
- policy/metric/score semantics.

Adding support for a new condition or framework version changes the relevant catalog/version and triggers compatibility review. Framework versions are pinned per analysis; “current Spring behavior” is not a timeless contract.

## Verification Gates

### M4-R0 — before production M4 semantics

- approve canonical identities/non-circular preimages and v1 fragment;
- adjudicate phase/order examples against official docs/source and controlled fixtures;
- define envelope/precedence semantics and UNKNOWN behavior;
- establish exhaustive oracle and solver conformance suite;
- preregister baselines, corpus protocol, metrics and kill criteria.

### G3 — bounded Spring correctness

- every `spring-mechanisms:v2` row accounted for;
- no silent condition/mechanism omission;
- no false universal promotion in adjudicated fixtures;
- binding and truth-region precision/recall reported with closed denominators;
- witness validity/minimality and deterministic digests pass;
- framework-version and resource-limit boundaries visible;
- representative real repositories evaluated with failures included.

## Explicit Non-Claims

- not all Spring runtime behavior is statically knowable;
- repository configuration files are not the complete production environment;
- no solver backend is approved by this document;
- no prevalence, accuracy, speed or novelty threshold has yet passed;
- no M4 production capability is implemented;
- no runtime/build execution is authorized by this contract.

## Related Documents

- [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md)
- [Research Review](../research/2026-09-09-conditional-architecture-redirection-review.md)
- [M4 Spring Intelligence](m4-spring-intelligence.md)
- [M3 Workspace and Build Model](m3-workspace-build-model.md)
- [Progressive Evidence Acquisition](evidence-acquisition.md)
- [Knowledge Graph](knowledge-graph.md)
- [Product Outcome](product-outcome.md)
