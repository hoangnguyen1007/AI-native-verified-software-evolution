# M5.1 — Canonical Graph and Snapshot Persistence

**M8.4 additive source projections (2026-11-04):** `SOURCE_DECLARATION`, `CALL_SITE`, `ROUTE_CANDIDATE` and other `SOURCE_*` nodes preserve passive syntax with provider records and full captured positions. `syntax.call` resolves the reference to a call-site node, not the program's callee. Source wiring has separate `SOURCE_BEAN_CANDIDATE` / `SOURCE_INJECTION_SITE` nodes and `spring.source-type-candidate` edges, retaining unknown activation/selection. These cannot be interpreted as qualified conditional bindings or active endpoints. A Java compatibility-worker fallback builds a real inventory-addressed root with explicit Java gaps; it does not invent a legacy empty-source snapshot. Single-record authenticated evidence lookup (`evo graph-pages evidence SHA256`) follows the evidence Merkle tree and verifies membership in the selected graph without materializing it. Wire fields and protected Java identities remain unchanged.

**2026-11-05 continuation:** neutral HTTP client rows retain their exact bound
Java occurrence, caller/API entities, URI/method and source range. Projection adds
`SOURCE_HTTP_CALL_CANDIDATE` plus `java.http-client-call` evidence; it does not parse
Java source. Root HTTP call/endpoint references preserve cross-context provenance
while candidate edges stay inside the root partition. Dynamic URI and missing
routing/activation remain explicit. Passive Protobuf declarations and same-file
RPC/message candidate references retain source-only trust; no generated stub,
native callee or runtime connection is invented. Compiler-attribution copies and
semantic replay preserve the additive HTTP rows and constructor candidates.

Date: 2026-10-14. Implementation: `com.evolution.analysis.graph` in `analyzer`, with `FileGraphStore` in `analyzer-filesystem`. This is the graph/persistence slice; projection queries, structural metric computation and G4 adjudication remain M5.2/M5.3.

## Entry points and ownership

`UniversalArchitecturePipeline.runWithEvidence(...)` returns a `DetailedResult` containing the existing report plus optional `CanonicalGraphInput`. It captures the original typed repository/build/source/semantic/Spring artifacts during the same execution, before report deduplication/summarization. Existing `run(...)` callers retain their report API. No second frontend, registration or binding run obtains missing graph data.

The 2026-11-05 performance repair validates Spring/source-set provenance with one
unit-header index and O(U + S) lookups rather than replaying all U units for each
Spring slice. It retains duplicate/absent/foreign-analysis rejection. Independent
report groups and CAS leaf preparation may run in bounded request-owned workers;
winner selection, SQLite ownership, canonical ordering, audits and publication
remain deterministic. Serial/parallel controls compare identical graph bytes and
authenticated roots, including cold reads and injected failures. The graph schema
and identity rules are unchanged. See the [streaming implementation contract](m8-streaming-acquisition.md#linear-validation-and-bounded-aggregation--2026-11-05).

For acquisition inputs, the detailed result also retains the original acquisition record, including terminal failures. No graph input is fabricated when decoding cannot supply an authoritative repository input. Empty/non-Java decoded inputs may produce an honest repository/source-evidence graph with no Java entities.

`CanonicalGraphBuilder.build(input)` projects these artifacts. Overloads support a manifest/frontend pair and optional Spring slices and evidence-acquisition ledgers. Inputs and supplied ledgers must share the authoritative snapshot and compatible analysis contexts. The core depends on neutral contracts only; no parser AST, Maven adapter, database driver or filesystem API enters this boundary.

## Contexts and identities

| Partition | Identity and meaning |
|---|---|
| Repository structure | Snapshot identity; module/source-root ownership and original source evidence |
| Java semantics | Exact M1 analysis-manifest identity, including ordered classpath and source-set configuration |
| Explicit Spring evaluation | Existing M4D conditional-semantics-context identity; references its originating Java analysis |
| Symbolic or unevaluated Spring plan | Versioned derived context binding analysis, normalized plan, optional space, symbolic evaluation input and exact evaluation parameters |

Node and edge indexes use `(context, canonical identity)`. A repository graph groups these partitions; it does not merge them into one executable world. Spring `Context.analysis` links back to the producing Java analysis. All resolved/candidate endpoints and region references must exist **inside the same partition**.

Java entities, semantic relationships and occurrences reuse M1 identities without changing preimages. Packages use typed origin/scope plus the canonical Java package name, never FQN alone. Additional graph identities use SHA-256 over schema/version, kind and canonical input components. Parent/module/source-root derivations cite their actual supports. Conflicting payloads for one canonical statement fail integrity validation; repeated identical imports are idempotent.

The pipeline's analysis manifest now uses `KnowledgeGraph.schemaComponent()`, whose digest binds the actual versioned graph-format/field/target/derivation descriptor. M1 identity rules are unchanged; using the new component intentionally changes analyses previously produced with the placeholder schema component.

`KnowledgeGraph.identity()` is the exact content address of the complete canonical graph payload. `semanticIdentity()` separately supports semantic replay while excluding known acquisition timestamps and snapshot revision/dirty provenance labels. Their original values remain in the stored payload, so changing telemetry changes content identity without erasing evidence. Configuration keys with similar names are not stripped. Classpath order, registration order, source content, regions, provider/formula/schema versions and result-affecting limits remain significant.

## Mapping and evidence closure

| Input | Canonical graph representation |
|---|---|
| Snapshot/build/source plans | Repository, module, source document and source-root nodes; ownership links only for evidenced owned sources; complete build/ownership/configuration payloads |
| Java declarations | Typed entity nodes, complete declaration metadata, origin/scope and real optional declaration span |
| Member/package ownership | Versioned `graph.declares`/`graph.contains`, derived only from the neutral `java:v1` symbol grammar and available owner declarations |
| Java relationships | Original relationship ID and resolved/candidate/unresolved target variant; every occurrence is separately preserved and contributes its full payload |
| Type/annotation uses | Occurrence-specific nodes and owner links, distinct from type declarations; full neutral type/annotation records retained |
| Type dependencies | `graph.depends-on` over resolved Java dependency categories, excluding `java.declares` and `java.has-parameter`; type owners must be evidenced. Intra-type relations do not add self-dependencies |
| Degraded/withheld frontend output | Complete observation/source/category ledgers and normalized typed capability gaps, rather than fabricated target entities |
| Spring plans | Producers, definition candidates, injection sites, owner-qualified injection uses, discovery events, registration transitions, condition occurrences and expression DAG nodes |
| Spring conditional facts | Separate fact and truth-region nodes; selected/candidate edges originate at the contextual injection use and retain a region reference, including `NEVER`/`UNKNOWN` rows |
| Explicit regions | All T/F/U world memberships, feasibility/completeness, configuration/order, witnesses and separate replay records |
| Symbolic regions | Formula DAG and T/F/U references, space/evaluation identities, representative traces, operation outcomes and residual gaps; no Cartesian expansion |
| Evidence acquisition | Typed gap, attempt, conflict and resolution payloads from supplied ledgers, with provider identity, permissions/trust, outcomes and provenance preserved |

Graph edges describe canonical relationships and resolved identities; a resolved target is not proof that a conditional edge is active. M5.2 must select compatible truth regions before path/cycle reasoning. No solver or policy quantification is performed by the graph builder/store.

The additive reason catalog `evidence.graph-gaps:m5.1-v1` records `TYPE_OWNER_UNAVAILABLE` when a raw dependency cannot be projected to evidenced type owners, and `REFERENCED_EVIDENCE_UNAVAILABLE` when an acquisition-attempt reference has no supplied payload. Raw facts and gaps remain present; unavailable references are explicitly receipted rather than promoted to verified support. Existing gap histories may append provenance without overwriting prior payloads.

`GraphEvidence` holds a family, canonical JSON payload and independently verified content digest. Public neutral records are encoded with all their components; non-record provider result classes use their declared canonical export. Typed map keys become ordered key/value entries, avoiding lossy display-string keys. Provider canonical DAG exports retain flat child identities; graph links validate expression/formula references. Unsupported object shapes fail explicitly rather than disappearing.

The snapshot restores typed `KnowledgeGraph.Context`, `Node`, `Edge`, `Target`, `GraphEvidence` and adjacency indexes. Provider SDK objects are not deserialized by reflective class loading; their complete neutral payloads remain inspectable evidence. Supplied source bytes are retained with decoding metadata through the pipeline capture path. The manifest/frontend-only overload cannot invent bytes it was not given. Original dependency/JDK binary files are identified by their existing manifest hashes, not archived or advertised as presently available by the graph store. Graph restoration does not claim to rerun extraction without those external inputs.

## Persistence and failure contract

**2026-11-04 additive disk assembly:** `CanonicalGraphBuilder.project` applies the same domain projection through `GraphProjectionStore`. `SqliteGraphProjection` stores canonical rows and statement/attempt indexes on disk with bounded row/page/cache budgets. `PartitionedGraphStore.build` streams canonical ordering/digests and the original snapshot envelope, builds page/adjacency trees, audits references and only then publishes immutable outputs. The default CLI scan uses this path and summarizes Java depth from existing input plus edge counters, avoiding a second whole graph. Golden/cold-read controls compare exact v1 bytes and reject dangling contributions. Existing in-memory entry points remain compatible. This removes graph-assembly population maps, not frontend AST/result materialization or all legacy query memory use.

`GraphStore` exposes save, load, availability and a typed storage manifest. `InMemoryGraphStore` and `FileGraphStore` use the same immutable graph model. `GraphSnapshotCodec` serializes `graph-snapshot:m5.1-v1` with schema, content identity and full graph/evidence closure. Decode reconstructs typed values, checks schema/fields/digests, validates all graph references and requires canonical re-encoding equality through streaming SHA-256 comparison of the exact UTF-8 bytes. Duplicate keys, duplicate serialized rows, unknown fields/versions, malformed UTF-8, trailing content and mismatching digests cannot become empty success.

Default file/JSON limits are 64,000,000 bytes/characters, depth 128 and 2,000,000 JSON values; numeric expansion is capped at 4,096 digits/scale. These are defensive limits, not measured product performance budgets. Callers may supply explicit file/reader bounds; the M7.2 CLI supplies a larger bounded snapshot profile, documented in its contract. Limit exhaustion is typed `LIMIT_EXCEEDED`; invalid data is `CORRUPT_SNAPSHOT`. Missing files return absent availability, not a fabricated graph. I/O errors, unsafe paths and cooperative cancellation have separate sanitized reasons.

The file adapter accepts an explicitly supplied existing output directory, checks link/nonregular paths, and derives filenames solely from validated content hashes. It streams canonical UTF-8 into and flushes a same-directory temporary file, then atomically publishes it with create-if-absent hard-link semantics. This avoids the overwrite behavior permitted by some atomic-move implementations. Loading strictly decodes bounded UTF-8 through a buffered Reader, without first allocating a whole-file byte/string copy. An existing snapshot is verified and never replaced; concurrent publication by another instance is checked. Cancellation leaves earlier snapshots intact and does not publish incomplete bytes. Temporary files are owned by the operation and cleaned up on failure.

The output directory must be caller-controlled. A filesystem without hard-link support returns an explicit I/O failure; there is no unsafe overwrite fallback. This adapter does not claim OS-level isolation against a hostile process concurrently replacing the output directory or modifying published files. Subsequent loads still check content integrity.

## Verification boundary

The M5.1 suites cover scope/origin collisions, repeated occurrences, all M1 semantic statuses, unmapped observations, immutable indexes, typed attempts/conflicts, explicit/symbolic Spring regions, owner fan-out, witnesses/replays, context/baseline/limit mismatches, canonical round-trip, corruption, cancellation, storage limits, multi-module and partial-source pipeline journeys. Existing M1/frontend/truth and pipeline/M4E consumer suites form the bounded pre-handoff regression set; exact results belong in [current state](../current-state.md).

The implementation self-review is not independent review. No Neo4j adapter, query traversal, metric calculation, policy engine, external corpus campaign or G4 certification is claimed by M5.1. The subsequent [M5.2 contract](m5-architecture-queries.md) delivers query traversal and additionally projects embedded Spring plan/evaluation gaps as searchable typed gap nodes; M1 identities and the snapshot wire schema are preserved. The next slice is now M5.3.

## M7.2 evidence indexes and streaming implementation

### M8.4 declaration-level endpoint projection — 2026-11-04

The universal ingestion graph projects actual `WebRouteMapper` routes into `ENDPOINT` nodes in their exact Java analysis context, with handler containment and versioned `spring.endpoint-declaration:m8.4-v1` evidence. Each row retains the route's controller, handler, method/path constraints and source evidence; public query metadata exposes the route and its declaration scope. `activation=UNKNOWN`, `runtimeObserved=false` and `scope=DECLARATION_ONLY` distinguish these rows from container activation or observed traffic. Missing/unsupported routes retain upstream obligations; an observed row does not close the whole endpoint denominator. Spring inventory metrics identify these as `OBSERVED_DECLARATIONS`. Snapshot wire fields, M1 identities and metric formulas remain unchanged; newly projected graph content identities change naturally. Historical graphs are not rewritten.

M7.2 introduced versioned auxiliary `ingestion.outcome-index:m7.2-v1`, `frontend.result-index:m7.2-v1` and `evidence.ledger-index:m7.2-v1` payloads instead of nesting duplicated full populations. Individual source/frontend/gap records remain captured; indexes preserve original identity, metadata, order and references. The frontend index includes the provider-result SHA-256 and reconstructs the original neutral canonical record exactly in a regression test. Ledger indexing preserves attempts/conflicts/resolutions and applies the same operational telemetry normalization. Historical full evidence families remain decodable; metrics accept both frontend representations. This changes constructed graph content identities, not the snapshot wire schema, M1 identities or metric formulas.

Canonical serialization caches record accessors and streams hashes/writes with unchanged golden preimages. Structural preflight counts the decoder's actual JSON values/depth, including flattened identifiers/optionals. Streaming parser regressions cover fragmented reads, stable EOF, malformed JSON, duplicate keys and resource limits. File-store tests cover canonical UTF-8 equivalence, corruption, cancellation and independent byte/character/structural ceilings. See [M7.2 verification](../tasks/m7/02-evo-cli.md) for executed scope.


### M8.4 disk-retained intermediates and paged ingestion references — 2026-11-04

CLI scan owns an `AnalysisWorkspace` through graph publication. Completed neutral units, report populations, issues/gaps and inherited gap lists replay from SHA-256-checked scratch rows; in-memory APIs remain available. Sealed replay lists preserve canonical ordering without aggregate copies. `ingestion.outcome-index:m8.4-paged-v2` references ordered `ingestion.issue-index-page` and `ingestion.gap-index-page` evidence, each containing at most 1,024 IDs. Original provider-result identity and all individual records remain. This bounds index payloads without changing the snapshot wire schema; newly constructed graph digests change naturally. Historical v1 indexes remain readable as raw evidence.
