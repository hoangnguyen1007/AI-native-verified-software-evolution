# Progressive Evidence Acquisition and Capability-Gap Contract

## Status and Scope

**PROVISIONAL architecture contract for M3 and later.** This document refines [ADR-003](../decisions/ADR-003-progressive-evidence-acquisition.md). It does not claim that the records below exist in production code, does not alter the implemented M1 identity contract, and does not authorize execution of target builds.

M2 already preserves parser-neutral observations, input coverage, diagnostics and unmappable facts. M3 should introduce the first normalized capability-gap/acquisition contract over that evidence. Later provider types may reuse the contract after their own safety and acceptance gates.

## Responsibilities

The acquisition layer must distinguish five things:

1. an observation made by a provider;
2. a capability gap explaining why a requested fact is not yet established;
3. an evidence requirement that could answer a specific unresolved question;
4. an acquisition attempt and its bounded outcome; and
5. a conflict when providers produce incompatible observations.

A capability gap is not a semantic fact and not proof that a fact is unknowable. A suggested provider is not authorization to invoke it.

## `CapabilityGapRecord` Logical Schema

The exact Java/package placement remains an M3 implementation decision. The logical schema is:

| Field | Requirement |
|---|---|
| `schemaVersion` | Versioned capability-gap contract identifier |
| `gapIdentity` | Deterministic identity derived from the stable fields below |
| `analysisIdentity` / `snapshotIdentity` | Exact analysis and repository snapshot in which the gap was observed |
| `detectingProvider` | Stable provider ID plus implementation/catalog version, for example `frontend.javaparser` + `3.27.1-m2.4` |
| `mechanismCategory` | Versioned namespaced category such as `java.reflection`, `spring.registration.programmatic`, or `build.generated-source.missing` |
| `reasonCode` | Stable technical reason code; never an exception message used as identity |
| `subject` | Canonical entity/document/module/configuration identity or other typed scope affected by the gap |
| `sourceSpans` | Zero or more real spans that expose the gap; empty is valid for build/repository-level gaps and must not be replaced by a placeholder |
| `observationReferences` | IDs of provider observations, input-coverage entries or diagnostics that establish the gap |
| `evidenceRequirements` | One or more typed requirements describing what evidence could answer the unresolved question |
| `candidateProviders` | Ordered or scored only by a documented policy; candidates do not imply availability, trust or permission |
| `affectedOutputs` | Relationship categories, graph projections, metrics, policies or assessments that may be incomplete |
| `acquisitionAttemptReferences` | Attempts already made, including failure/denial/unavailability outcomes |
| `diagnostics` / `limitations` | Sanitized explanation and user-visible bounded-claim language |

`gapIdentity` is content-addressed from contract version, analysis identity, detecting-provider identity/version, mechanism category, reason code, typed subject, real source anchors when present, and normalized evidence requirements. Runtime timestamps, localized messages and attempt order are provenance, not identity inputs.

Reason codes and mechanism categories are versioned catalogs. Free-form messages may explain a gap but cannot replace stable codes or determine behavior.

## Typed Evidence Requirements

An evidence requirement records the unanswered question, not merely a provider name.

| Field | Meaning |
|---|---|
| `requirementKind` | Examples: `BUILD_MODEL`, `DEPENDENCY_ARTIFACT`, `GENERATED_SOURCE`, `PLATFORM_SYMBOLS`, `BYTECODE`, `CONFIGURATION`, `ISOLATED_BUILD_OUTPUT`, `RUNTIME_OBSERVATION`, `ALTERNATE_FRONTEND` |
| `question` | Stable namespaced question such as “resolve generated declaration identity” or “determine active conditional bean candidates” |
| `requiredInputs` | Known module/artifact/configuration/symbol identities and any missing input descriptor |
| `authorizationClass` | `PASSIVE`, `LOCAL_READ`, `NETWORK`, `ISOLATED_EXECUTION`, or another versioned policy value |
| `satisfactionCriteria` | Observable evidence that would answer or narrow the question |

The coordinator selects the least invasive sufficient permitted requirement. It may stop with the gap open when no safe, authorized, available or proportionate provider exists.

## Acquisition Attempts and Conflicts

Every actual attempt records provider/version, requested requirement, exact inputs, configuration, trust/permission decision, resource limits, start/end state, output artifact identities, diagnostics and declared side effects. Attempt outcomes include `SUCCEEDED`, `PARTIAL`, `FAILED`, `DENIED`, `UNAVAILABLE` and `CANCELED`; these are acquisition outcomes, not semantic statuses.

Evidence artifacts are immutable and content-addressed where possible. A later successful attempt satisfies or narrows a gap by adding evidence and derivation references; it does not edit or delete the original observation/gap history.

Incompatible provider observations create an explicit conflict record containing both observation identities, the dimensions in conflict, provider provenance, adjudication status and downstream impact. Provider order alone must not silently decide semantic truth.

## Mapping from Current M2 Output

M2's `ObservationRecord`, diagnostics and `InputCoverage` remain the authoritative current output. A future normalizer may derive a capability gap when, for example:

- a registered observation is unresolved or unsupported for a reason that identifies missing evidence;
- attribution succeeds but origin or source provenance is missing;
- a requested source/module/category is rejected or not processed;
- generated source, dependency, platform or build-model input is known to be absent; or
- a dynamic Spring/reflection mechanism is detected without enough evidence to establish a target.

Not every unresolved observation needs escalation. The normalizer must preserve the original semantic/provenance dimensions, deduplicate only by the deterministic gap identity, and retain gaps even when no candidate provider is known.

## Product and Query Projection

Stable query services should support:

- capability gaps grouped by mechanism, reason, provider and affected scope;
- attempted/failed/denied/unavailable acquisition counts;
- evidence requirements and candidate next providers without implying automatic execution;
- conflicts and their effect on confidence, metrics, policies and assessments; and
- drill-down to source spans, observations, artifacts and provenance.

The workbench may present this as an “evidence gaps and acquisition paths” view. It must not present candidate providers as guaranteed fixes or an open gap as a repository defect.

## Acceptance Requirements

- Golden serialization and identity cases for source-anchored and build-level gaps.
- Distinct cases for missing evidence, unsupported mechanisms, provider failure, permission denial and provider conflict.
- No placeholder source spans or raw exception text in stable identity.
- Deterministic deduplication without merging different subjects, reasons or provider versions.
- A successful later provider adds traceable evidence while preserving the original gap/attempt history.
- Missing or denied acquisition visibly qualifies affected downstream outputs and never improves architecture health.

## Related Documents

- [ADR-003: Progressive Evidence Acquisition](../decisions/ADR-003-progressive-evidence-acquisition.md)
- [Architecture Overview](architecture.md)
- [M1 Contracts](m1-contracts.md)
- [M2 Semantic Frontend](m2-semantic-frontend.md)
- [M4 Spring Intelligence](m4-spring-intelligence.md)
- [Product Outcome Contract](product-outcome.md)
