# M1 Semantic, Identity, Uncertainty, and Provenance Contracts

## Status

Implemented in the `analyzer` module for M1. The contracts are parser-neutral, storage-neutral, and framework-neutral. G1 state is recorded in [Current State](../current-state.md) after the complete verification gate.

JavaParser + SymbolSolver is the human-approved primary frontend (ADR-001); empirical G2 acceptance remains pending. These contracts do not reference parser types.

## Placement and dependency boundary

Repository evidence places the contracts in the existing `analyzer` module:

- the accepted Maven reactor already defines `analyzer` as the core semantic-analysis module;
- `backend` depends on `analyzer`, so stable domain contracts point in the existing dependency direction;
- no production source or competing core module existed before M1; and
- a new Maven module would add a boundary without current consumers or measured need.

The root package is `com.evolution.analysis.contract` with these concern packages:

| Package | Responsibility |
|---|---|
| `common` | contract validation, content digests, versioned identifiers |
| `identity` | typed stable identities and content-addressed entity scopes |
| `source` | modules, full snapshot inventory, source documents, complete spans |
| `semantic` | entities, relationship target states, occurrences, derivation, uncertainty, diagnostics |
| `analysis` | configuration, exact classpath entries, manifest, provenance |
| `metrics` | versioned metric value and scope envelopes |
| `assessment` | assessment status, architecture health, analysis confidence |
| `serialization` | deterministic canonical JSON |

No ADR was created for this placement. The choice follows the accepted reactor, is reversible while no adapter depends on it, and did not present a consequential alternative requiring a durable decision record.

## Immutability and validation

Contract values are Java 21 records, enums, sealed target variants, or final value classes. Constructors and factories reject nulls, blank or non-NFC stable text, invalid paths, malformed hashes, duplicate set-like inputs, inconsistent identities, incomplete source evidence, and incompatible status/value combinations. Collections and maps are defensively copied.

Repository-relative paths:

- use `/` separators;
- preserve case;
- contain no empty, `.`, or `..` segments;
- are not absolute and contain no drive or URI prefix; and
- are not locale-normalized or lowercased.

## Stable identity contract

All derived identities use SHA-256 over UTF-8 canonical JSON with an explicit domain separator:

```text
{"components":[...],"kind":"<identity-kind>","version":1}
```

The stored representation is `<identity-kind>:sha256:<64 lowercase hex characters>`. JSON component boundaries prevent concatenation collisions.

| Identity | Stable inputs |
|---|---|
| Repository | caller-supplied canonical absolute URI; scheme and host are lowercase and the URI is already normalized |
| Snapshot | repository identity plus SHA-256 of the complete sorted `SnapshotFile` path/digest inventory |
| Module | repository identity plus normalized repository-relative module path |
| Source document | repository identity plus normalized repository-relative source path |
| Entity scope | project module identity, or an origin-specific content address from canonical origin plus content digest |
| Entity | origin, typed entity scope, entity kind, canonical language-level name/signature |
| Relationship | source entity, namespaced relationship kind, and explicit resolved/candidate/unresolved target representation |
| Occurrence | relationship identity, complete source span, and non-negative stable ordinal |
| Configuration | configuration schema ID/version plus canonical option map |
| Analysis | manifest version, snapshot identity/content digest, module model, ordered exact classpath, configuration identity, analyzer version/content, rule-set version/content, and graph-schema version/content |

Revision labels, dirty flags, runtime timestamps, diagnostics, and limitations are provenance. They do not enter snapshot or analysis identity. Snapshot content still changes when any inventoried file changes, whether or not that file is a Java source.

Module, source, entity, relationship, and occurrence identities are distinct Java types so callers cannot interchange them accidentally.

## Deterministic ordering and serialization

Set-like inputs are sorted with locale-independent natural ordering and reject duplicates:

- snapshot file inventory;
- source documents;
- modules;
- relationship candidates;
- diagnostics and uncertainty;
- configuration maps, limitations, and referenced input identities.

Classpath order is deliberately preserved because first-match resolution can be semantically meaningful. Reordering the same classpath entries therefore changes `AnalysisIdentity`.

Canonical JSON:

- sorts object and map keys lexicographically;
- serializes record components by component name;
- serializes typed identities and digests as scalar strings;
- preserves contract-defined list order and sorts sets by their encoded value;
- uses normalized plain `BigDecimal` notation;
- uses UTC `Instant` text;
- rejects binary floating point, non-string map keys, unsupported types, and unpaired surrogates; and
- does not depend on the default locale, timezone, platform path separator, or map implementation order.

Any change to an identity preimage, canonical field name, ordering rule, or meaning requires a new relevant contract/version input. Existing identities must not be silently reinterpreted.

## Source evidence

`SourceSpan` always contains a `SourceDocumentIdentity` and one-based begin/end line and column. The end position is exclusive and must be strictly after the start. A partial line-only or detached span is invalid.

Absence of a declaration span remains explicit through `Optional<SourceSpan>`; callers must not invent coordinates.

## Semantic facts, uncertainty, and diagnostics

`SemanticRelationship` defines the stable source, relationship kind, and target representation. `RelationshipOccurrence` adds source evidence, status, derivation, uncertainty, and diagnostics.

Target representations are explicit:

- `Resolved` contains exactly one target;
- `Candidates` contains at least two sorted, distinct candidates; and
- `Unresolved` retains a stable unresolved reference without inventing an entity.

Occurrence status invariants:

| Status | Required representation and evidence |
|---|---|
| `RESOLVED` | resolved target; no unresolved uncertainty |
| `PARTIAL` | explicit uncertainty; target shape retained |
| `UNRESOLVED` | unresolved reference and explicit uncertainty |
| `AMBIGUOUS` | candidate targets and explicit uncertainty |
| `CONDITIONAL` | explicit uncertainty; target/candidate state retained |
| `UNSUPPORTED` | unresolved reference and explicit uncertainty |
| `ERROR` | unresolved reference, explicit uncertainty, and at least one error diagnostic |

`DIRECT`, `DERIVED`, and `INFERRED` are derivation kinds, not confidence aliases. Derived and inferred occurrences require canonical input identities and a versioned derivation method.

## Analysis manifest and provenance

`AnalysisManifest` contains all stable analysis-defining inputs. It rejects source documents whose module is absent and modules from another repository. Every source document must match the path and digest of a file in the snapshot inventory.

`AnalysisProvenance` binds the computed analysis identity to the complete manifest, start/completion instants, sorted diagnostics, and limitations. Completion cannot precede start. Runtime timestamps are serialized deterministically but excluded from identity.

M1 defines the classpath manifest entry shape only. It does not acquire, resolve, or execute Maven/Gradle models.

## Metric and assessment envelopes

`MetricEnvelope` exposes:

- namespaced metric ID and formula/semantic version;
- display meaning, typed scope, exact decimal value/unit when permitted;
- `COMPLETE`, `PARTIAL`, `WITHHELD`, or `NOT_APPLICABLE` status;
- optional numerator/denominator evidence;
- referenced canonical inputs;
- analysis and configuration identity;
- uncertainty, limitations, and computation time.

Status/value rules are shared by metric, health, and confidence envelopes:

| Status | Value rule |
|---|---|
| `COMPLETE` | value required; unresolved uncertainty forbidden |
| `PARTIAL` | value required; uncertainty or limitation required |
| `WITHHELD` | value forbidden; explicit reason required |
| `NOT_APPLICABLE` | value forbidden; explicit reason required |

A ratio with a zero denominator is `NOT_APPLICABLE`; M1 does not calculate the ratio.

`ArchitectureHealthAssessment` and `AnalysisConfidence` are separate Java types. Health may carry a 0–100 score and dimension values. Confidence may carry a 0–1 evidence-quality value. M1 defines neither formula, thresholds, weights, nor any rule that turns confidence into health. Missing evidence can qualify or withhold health later; it cannot improve health.

## Verification contract

M1 tests cover:

- identity golden values and component-boundary collision resistance;
- complete inventory, source, module, entity-scope, relationship, and occurrence invariants;
- source-span validation;
- sorted set-like equality and order-sensitive classpath identity;
- configuration, manifest, and provenance validation;
- semantic target/status/uncertainty/diagnostic combinations;
- all four assessment states and false-precision rejection;
- health/confidence type separation;
- canonical JSON golden output; and
- locale/timezone-independent manifest serialization and analysis identity.

## Explicit limitations and deferred work

M1 does not implement repository acquisition, Maven/classpath discovery, JavaParser/SymbolSolver, Spring inference, graph construction, persistence, graph queries, metrics, scoring, policy rules, CLI, API, UI, Track B, or Track C.

Canonical Java symbol naming rules for every modern Java construct remain an M2 responsibility and must use these identity inputs. Exact module/build acquisition remains M3. Graph schema, metric formulas, assessment formulas, confidence thresholds, and comparison compatibility rules remain at their later gates.

## Related documents

- [Architecture Overview](architecture.md)
- [Product Outcome Contract](product-outcome.md)
- [Knowledge Graph](knowledge-graph.md)
- [ADR-001: Parser Technology](../decisions/ADR-001-parser-technology.md)
- [Current State](../current-state.md)
