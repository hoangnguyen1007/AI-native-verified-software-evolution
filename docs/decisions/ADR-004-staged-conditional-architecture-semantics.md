# ADR-004: Staged Conditional Architecture Semantics

- **Status:** ACCEPTED DIRECTION; implementation and empirical claims remain gated
- **Date:** 2026-09-09
- **Decision owner:** project owner authorized evidence-based selection and documentation update
- **Applies from:** M4 design onward; no change to the active M3.8 implementation slice

## Context

The existing platform establishes deterministic repository, analysis, configuration, semantic-status, provenance and capability-gap contracts. M3 models one exact source/build/classpath/platform context. The pre-implementation M4 design recognizes profiles and conditions, but a single realized configuration cannot support universal claims about a Spring application's architecture.

Spring conditions are not one homogeneous set. Profile/property/classpath predicates can be external inputs; `@ConditionalOnBean`, `@ConditionalOnMissingBean` and related predicates depend on bean definitions processed so far and therefore on framework phase and registration order. External configuration may also be absent from the repository. Treating all candidate edges as simultaneously active creates impossible paths; evaluating one default context hides configuration-specific behavior.

The reviewed proposal and evidence are recorded in [the 2026-09-09 redirection review](../research/2026-09-09-conditional-architecture-redirection-review.md).

## Decision

M4+ will model **bounded conditional architecture facts** inside one exact M3 build context.

1. Existing `ConfigurationIdentity` continues to identify one realized configuration. A separate versioned, content-addressed `ConfigurationSpaceIdentity` identifies a finite modeled space, its build context, source envelopes, domains, constraints and precedence.
2. A distinct `ConditionalSemanticsContextIdentity` binds that space to condition/framework/registration semantics, registration-plan identity, reasoner policy and result-affecting limits. This avoids overloading configuration identity or creating circular preimages.
3. Conditions compile to a storage- and solver-neutral canonical IR.
4. Spring activation uses versioned **phase/order-aware bean-definition transition semantics**. A generic unordered fixpoint is not the canonical model.
5. Logical evaluation is `TRUE`, `FALSE` or `UNKNOWN`. Operational outcomes such as unsupported input, error, timeout and limit exhaustion remain separate and may force affected logical results to `UNKNOWN`.
6. Facts and policy results are classified over the feasible modeled space as `MUST`, `MAY`, `NEVER` or `UNKNOWN`, with coverage and uncertainty retained separately.
7. `MAY` findings carry deterministic, revalidated witness configurations; counter-witnesses are retained when variation is claimed.
8. The graph is a deterministic projection of conditional facts, not the owner of condition semantics. A union projection is never represented as one executable application world.
9. Track B compares compatible conditional fact regions, findings, evidence and configuration/semantics-context identities; evidence loss cannot be reported as architectural improvement.
10. Solver integration remains behind a `ConfigurationReasoner` port. An exhaustive small-space oracle precedes any SAT/BDD selection; backend adoption requires measured evidence.
11. Runtime, build and AOT observations remain separate evidence providers with explicit authorization, identity and provenance. They corroborate, narrow or conflict with static/configuration conclusions; they do not silently overwrite them.

The canonical semantics are defined in [Conditional Architecture Semantics](../architecture/conditional-architecture-semantics.md).

## Scope Decision

### SE121 Track A

- M4A mechanism ground truth and framework-version matrix;
- M4B configuration-space and condition IR;
- M4C phase/order-aware registration and binding semantics for an approved fragment;
- M4D truth regions, witnesses and conditional policy inputs;
- M4E fixture/real-repository validation and G3 evidence;
- product views for configuration space, activation explanation and `MUST`/`MAY`/`UNKNOWN` findings.

### SE121 Track B

- compatible conditional architecture delta;
- affected configuration region and witness deltas;
- analyzer/configuration/evidence drift distinguished from repository change.

### Deferred

- multiple build-context federation, comprehensive Gradle/POM-less/AOT/runtime providers;
- cross-service/API/event/schema evolution;
- AI diagnosis, change planning, refactoring and patch verification.

Deferred items remain architectural horizons, not current commitments.

## Alternatives Considered

### A. Analyze only one selected configuration

Rejected as the canonical research direction. It remains a useful baseline and product projection, but cannot justify universal architecture claims or detect configuration-hidden drift.

### B. Union every possible candidate edge

Rejected. It combines mutually infeasible facts and can create false cycles, boundaries and bindings. A union remains a labeled exploratory projection only.

### C. Flat Boolean presence conditions without bean state/order

Retained as an experimental baseline, not the complete Spring model. It is acceptable for exogenous conditions but insufficient for order-sensitive bean predicates.

### D. Generic least-fixpoint semantics

Rejected as the default because missing-bean conditions are non-monotone and framework processing order is observable. A specialized monotone sub-fragment may use fixpoint techniques if proved equivalent.

### E. Exhaustively boot every configuration

Rejected as the normal analyzer strategy because spaces can be large, external configuration may be unknown, and executing target applications crosses safety and side-effect boundaries. Bounded runtime execution may later validate selected witnesses under an explicit provider policy.

### F. Adopt one SAT/BDD library immediately

Rejected. The architecture adopts a solver-neutral port and benchmark gate so domain semantics do not depend on one engine.

## Consequences

### Positive

- avoids converting a convenient default configuration into universal truth;
- provides explainable counterexamples and configuration-qualified policy results;
- creates a defensible Track B contribution beyond generic graph diff;
- aligns with current identity/provenance/gap foundations without rewriting M1–M3;
- supports future deployment, AOT and AI-assisted evolution through stable evidence contracts.

### Cost and Risk

- M4, graph, policy, query and UI contracts become more demanding;
- framework-version semantics and registration order require careful fixtures;
- configuration spaces need sound finite abstractions and resource bounds;
- unknown external configuration may withhold results more often;
- novelty and practical benefit still require empirical validation.

## Required Gates

Before M4 production implementation:

- approve the v1 condition fragment and condition/registration version matrix;
- approve `ConfigurationSpaceIdentity` and `ConditionalSemanticsContextIdentity` inputs, non-circular preimages and compatibility rules;
- create a small exhaustive oracle and adjudicated phase/order fixtures;
- register solver and branching limits plus UNKNOWN behavior;
- register fair baselines and a corpus protocol.

G3 cannot pass unless the closed mechanism denominator is reconciled and the implementation demonstrates no silent omissions, no false universal promotion, valid witnesses, deterministic replay and version-bounded claims.

## Superseded Claims

This ADR supersedes the following parts of the V2 proposal:

- generic “fixpoint-style” wording as the canonical semantics;
- the unsupported 3–15 configuration-variable assumption;
- broad Lombok constructor and Spring Data proxy synthesis heuristics;
- the claim that ArchUnit and Spring Modulith fundamentally inspect only one active test `ApplicationContext`;
- any numeric prevalence, coverage, accuracy, performance or novelty claim without registered evidence.

## Compatibility

- M1 identities and existing `ConfigurationIdentity` remain unchanged.
- M2 `SemanticFrontend` remains unchanged.
- M3.8 scope and Gate G2 remain unchanged.
- Future M4 schema versions are additive unless implementation evidence demonstrates a required breaking change.

## Related Documents

- [Conditional Architecture Semantics](../architecture/conditional-architecture-semantics.md)
- [M4 Spring Intelligence](../architecture/m4-spring-intelligence.md)
- [Technical Roadmap](../roadmap.md)
- [Research Review](../research/2026-09-09-conditional-architecture-redirection-review.md)
- [ADR-003: Progressive Evidence Acquisition](ADR-003-progressive-evidence-acquisition.md)
