# Progressive Evidence Acquisition and Capability-Gap Contract

## Status and Scope

**PROVISIONAL architecture contract for M3 and later; the bounded M3.6 core below is implemented.** This document refines [ADR-003](../decisions/ADR-003-progressive-evidence-acquisition.md). The implementation does not alter the M1 identity contract and does not authorize or invoke target builds.

M2 preserves parser-neutral observations, input coverage, diagnostics and unmappable facts. M3.6 now supplies the first normalized capability-gap/acquisition contract over those observations and the typed M3.1-M3.5 problem/attempt ledgers. Later provider types may reuse the contract after their own safety and acceptance gates.

## Responsibilities

The acquisition layer must distinguish five things:

1. an observation made by a provider;
2. a capability gap explaining why a requested fact is not yet established;
3. an evidence requirement that could answer a specific unresolved question;
4. an acquisition attempt and its bounded outcome; and
5. a conflict when providers produce incompatible observations.

A capability gap is not a semantic fact and not proof that a fact is unknowable. A suggested provider is not authorization to invoke it.

## `CapabilityGapRecord` Logical Schema

The storage-neutral Java contract is implemented in `analyzer` under `com.evolution.analysis.evidence`. The logical schema is:

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

Every actual attempt records provider/version, requested requirement, exact input identities, configuration identity when supplied, trust/permission decision, resource limits, optional start/end instants, output artifact identities, diagnostics and declared side effects. Attempt outcomes include `SUCCEEDED`, `PARTIAL`, `FAILED`, `DENIED`, `UNAVAILABLE`, `CANCELED`, `EXCLUDED` and `LIMIT_EXCEEDED`; these are acquisition outcomes, not semantic statuses. When an older provider result preserves only a content-addressed request identity rather than expanding its policy, the normalizer retains that exact input reference and does not invent authorization, timing or limit values.

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

## Implemented M3.6 Core

**CONFIRMED by implementation and contract/integration tests on 2026-09-08:** the neutral core uses `capability-gap-record-v1`, catalog `evidence.capability-gap-catalog:m3.6-v1`, `acquisition-attempt-record-v1`, `provider-conflict-record-v1`, `gap-resolution-record-v1` and aggregate `evidence-acquisition-ledger-v1` produced by `evidence.gap-normalizer:m3.6`. See the [verification record](../reproducibility/m3-capability-gaps-2026-09-08/README.md).

- **Non-circular context and identity:** `EvidenceContext` always binds the snapshot and optionally binds an already-derived M1 `AnalysisIdentity`. Build-stage gaps therefore need no fabricated analysis identity; `EvidenceContext.forAnalysis` derives the pair from an existing manifest. A gap identity includes schema/catalog version, context, detecting provider/version, mechanism, stable reason, typed subject, real spans and normalized evidence requirements. Candidate providers, observation/attempt history, diagnostics and limitations remain additive provenance and cannot churn the stable gap identity.
- **Original observation preservation:** every normalized gap cites a content-addressed `ProviderObservationReference` binding the original provider, provider-result digest, observation kind and exact payload digest. M2 observations, source outcomes, category coverage and run state remain authoritative; M3 repository, build-model, source-plan, ownership, classpath, decoding, platform and frontend-assembly problems/attempts remain immutable source records.
- **Closed current denominator:** exhaustive switches map every registered degraded enum value in M2/M3 to a stable mechanism/reason and typed evidence requirement. The normalizer separately retains semantic status, missing origin, missing provenance and unmapped-observation dimensions instead of collapsing them into one success/failure flag. New enum values force a compile-time mapping decision and the catalog-coverage test guards the denominator.
- **Acquisition provenance without implied authority:** normalized attempts preserve success, partial, failure, denial, unavailability, cancellation, explicit exclusion and limit exhaustion as acquisition outcomes. A denied outcome requires a denied permission decision; legacy observations without an explicit permission record use `NOT_RECORDED`, never invented authorization. Candidate providers require one explicit versioned ordering policy and contiguous ranks, remain advisory records only, and are never populated or executed automatically by the normalizer.
- **Conflict and later evidence:** conflict identity is symmetric in the two provider observations, records the exact conflicting dimensions, requires explicit adjudication and never selects truth by provider order. A later successful/partial attempt may add a `GapResolutionRecord` with `NARROWED` or `SATISFIED`; the ledger validates its references while retaining the original immutable gap.
- **Deterministic aggregation:** the ledger sorts records, merges only equal stable gap identities while unioning their observation/attempt/diagnostic history, rejects cross-context references and prevents denied/failed attempts from satisfying a gap.

This core provides the normalized data needed by the G2 checkpoint; it is not the checkpoint itself. No real-repository category/reason report, independent semantic acceptance or Gate G2 decision was produced in M3.6 core.

## M3.7 Provider-Ladder Refinement

**CONFIRMED by implementation on 2026-09-08:** a gap from one provider is no longer treated as a terminal repository verdict. The Maven build-model coordinator tries exact evidence in increasing authority/cost order: supplied/workspace bytes, a selected bounded local Maven2 cache, then optionally caller-configured bounded credential-free HTTPS release-POM endpoints. Cache and remote reads share one aggregate byte budget. It records each attempt and binds the final exact bytes into a new build request before recomputing the model. A successful fallback closes the acquisition problem while preserving earlier attempt history.

The ladder is capability-based, not Maven-exclusive. POM absence must route to a future explicit or convention-backed neutral source-plan provider; Gradle and other build systems require their own replaceable adapters. Missing dependency descriptors/binaries, generated sources, reactor outputs and build-derived configuration are separate evidence requirements. The coordinator must not turn “current provider unavailable” into “repository unanalyzable,” but it also must not fabricate module ownership or a dependency superset merely to continue.

## Product and Query Projection

Stable query services should support:

- capability gaps grouped by mechanism, reason, provider and affected scope;
- attempted/failed/denied/unavailable acquisition counts;
- evidence requirements and candidate next providers without implying automatic execution;
- conflicts and their effect on confidence, metrics, policies and assessments; and
- drill-down to source spans, observations, artifacts and provenance.

## Specific Evidence Providers and Conventions

### Dependency Binary Symbols: `JarTypeSolver` as Primary Baseline

1. **Primary Provider:** The baseline provider for external dependency binary symbols is `JarTypeSolver` (Javassist-based) inside `analyzer-javaparser`. It consumes the ordered `ExactClasspathManifest` produced by M3, resolving type declarations and member signatures directly from verified JAR bytes.
2. **Selective Bytecode Provider (ASM):** A separate bytecode provider (such as ASM-backed inspection) is not an automatic fallback for generic solver failures. It is evaluated only when measured capability gaps require metadata that the baseline cannot safely extract (e.g., raw bytecode instruction offsets, specialized framework class attributes, or unparsed annotation parameters).

### Lombok Generation and Provenance Policy

1. **Execution Boundary:** Running `delombok` or annotation processors in a scratch buffer is an **unisolated provider execution**. It is NOT a sandbox. It requires explicit configuration, declared toolchain inputs, and authorized execution policy.
2. **Declaration Spans vs Supporting Evidence:**
   - Generated methods, accessors, and constructors **MUST NOT** assign the source annotation span (e.g. the span of `@Getter` or `@Builder`) as their declaration or body span.
   - The original annotation span is strictly recorded as **supporting input evidence** (`supportingSpans`) in derivation provenance.
   - The generated entity carries either an exact coordinate within an acquired `GeneratedDocument` artifact, or carries an empty declaration span (modeled as an implicit/derived member with project origin, citing the annotation as derivation input).
3. **Entity Origin Invariant:** `GENERATED_LOMBOK` is generator tool metadata and derivation provenance. It **must not alter** the canonical `EntityOrigin` enum (which remains `PROJECT`, `JDK`, `DEPENDENCY`). A generated member for a project class retains `EntityOrigin.PROJECT`.
4. **Lineage Preservation:** Original source files, bytes, and UTF-16 spans remain immutable. Generated documents are tracked separately in an acquired artifact plan with their own content digests.

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
- [M3 Workspace and Build-Model Contract](m3-workspace-build-model.md)
- [M4 Spring Intelligence](m4-spring-intelligence.md)
- [Product Outcome Contract](product-outcome.md)
