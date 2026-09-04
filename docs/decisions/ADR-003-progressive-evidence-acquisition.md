# ADR-003: Progressive Evidence Acquisition and Capability Boundaries

- **Status:** ACCEPTED DIRECTION; individual provider designs and phase entry remain gated
- **Date:** 2026-09-04
- **Decision authority:** Human project owner

## Context

SE121 correctly emphasizes deterministic source semantics, safe build modeling, explicit uncertainty and bounded claims. Some milestone and non-goal wording could nevertheless be read as making the current static frontend, explicit classpath, or source-only evidence envelope a permanent platform limit.

Repositories vary in build conventions, generated code, dependency metadata, language/platform levels, framework configuration and runtime behavior. Treating a current provider's unresolved result as the end of analysis would force repositories to fit the analyzer and could turn missing evidence into silent omission.

## Decision

The platform uses progressive evidence acquisition. It starts with the least invasive sufficient source and declarative evidence, records capability gaps by reason, and may add stronger evidence through replaceable providers when the question, phase, permissions, safety model and expected value justify doing so.

Potential providers include:

- alternate source/semantic frontends and platform symbol views;
- declarative Maven/Gradle/build-tool models and resolved dependency artifacts;
- safely acquired generated sources;
- bytecode and framework/configuration metadata;
- explicitly authorized, isolated and resource-bounded build/sandbox outputs; and
- controlled runtime observations.

Milestone scope controls what is implemented and claimed now. It does not forbid these extension paths. `UNRESOLVED`, `UNSUPPORTED`, `PARTIAL`, `CONDITIONAL` and `ERROR` remain truthful outcomes for a provider/run, but not proof that a fact is permanently unknowable.

Every evidence provider must expose its identity/version, inputs, configuration, trust and permission boundary, attempts, outputs, failures, resource limits and provenance. Later evidence may corroborate, qualify or contradict earlier evidence; reconciliation is explicit and cannot silently overwrite observations, invent identities/spans, erase uncertainty, or promote inference to direct fact.

## Hard Boundaries

- No fabricated fact, identity, source span, origin or certainty.
- No arbitrary untrusted build/lifecycle execution during normal analysis.
- No execution-backed provider without explicit authorization and an approved isolation, resource and side-effect contract.
- No silent omission of files, categories, evidence needs, failed attempts or provider conflicts.
- No assessment improvement caused by missing evidence.
- No loss of snapshot, input, provider, derivation or rule provenance.

Parser/storage/component boundaries remain strong modularity constraints, but they are replaceable seams rather than feature prohibitions.

## Alternatives Considered

### Static source analysis as a permanent ceiling

Rejected because repository-specific generated code, build semantics, configuration, bytecode and runtime behavior can remain invisible even when another safe evidence source could answer the question.

### Execute repository builds automatically for maximum coverage

Rejected because target repositories and lifecycle plugins are untrusted, may mutate state or contact external systems, and can make analysis non-reproducible. Execution-backed acquisition must remain explicit, isolated and policy-controlled.

### Merge stronger evidence directly into one final truth

Rejected because providers can disagree and have different trust, completeness and semantic meaning. The canonical model must preserve observations, derivation, conflicts and uncertainty.

## Consequences

- Provider gaps and acquisition attempts become first-class provenance and coverage concerns.
- Current milestones can remain bounded without hard-coding current limitations into the domain.
- Future providers can be added behind stable contracts and evaluated independently.
- The platform may stop with an explicit gap when stronger acquisition is unsafe, unauthorized, unavailable or not worth its cost.
- Provider selection, reconciliation and compatibility contracts require future milestone decisions and tests before implementation.

## Follow-Up Gates

M3 should define the first acquisition/gap contract for build models, platform views, source encodings and generated-source discovery. Later bytecode, sandbox/build, configuration or runtime providers require a demonstrated gap, bounded threat model, provenance contract, deterministic/reproducible expectations where applicable, and human approval when they alter phase scope, security or cost.

The provisional record shapes and acceptance requirements are maintained in the [Progressive Evidence Acquisition and Capability-Gap Contract](../architecture/evidence-acquisition.md).
