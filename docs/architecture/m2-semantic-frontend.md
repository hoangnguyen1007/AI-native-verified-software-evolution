# M2 Semantic Frontend and Ground Truth

## Status and entry conditions

**PROVISIONAL implementation baseline, 2026-09-02; implementation resumed 2026-09-03.** JavaParser + SymbolSolver is human-approved. The human directed Codex to proceed using D1–D3 in production vertical slices. All 18 relationship families now have bounded implementation, including modern Java and derived members; full M2 acceptance and G2 remain open. The [pause archive](../reproducibility/m2-pause-2026-09-02/README.md) remains immutable historical input; tested support is recorded in the [initial implementation](../reproducibility/m2-implementation-2026-09-03/README.md), [declared-type](../reproducibility/m2-types-2026-09-03/README.md), [field](../reproducibility/m2-fields-2026-09-03/README.md) and [modern Java](../reproducibility/m2-modern-2026-09-03/README.md) evidence.

Authority: the latest human implementation direction, [M1 contracts](m1-contracts.md), [ADR-001](../decisions/ADR-001-parser-technology.md), [roadmap](../roadmap.md), and [semantic evaluation procedure](../../.agents/skills/se-project-engineering/semantic-evaluation.md). Existing M1 identity preimages and validation remain unchanged; the D2 entity kinds are appended.

Implemented port details for this slice: `SourceInput` validates immutable UTF-8 bytes and digest; `FrontendRequest` validates the exact module/source-set subset and ordered platform/JAR inputs. Its configuration includes a digest of sorted selected `SourceDocument` records, because M1 snapshot hashing alone does not bind document module/source-set membership. Sources are supplied in memory, with no root discovery; limits are explicitly `unbounded` and no scale claim is made. Invalid inputs throw `FrontendInputException` carrying a structured diagnostic. `FrontendResult` validates all mapped observations, entity references, the full 18-row catalog and source outcomes, then `validateFor(request)` checks requested-document completeness. Actual extraction covers a subset: unsupported catalog rows do not represent a measured zero-reference count.

`DeclarationRecord` retains original syntax spelling, status, derivation and diagnostics. `FrontendResult.types` adds `TypeUseRecord` values with declaration/execution owner, syntactic role, complete written type span, varargs flag and recursive `JavaType`. Type components distinguish generic arguments, arrays, wildcards/bounds and member-type qualifiers; primitives/void have no entity target. Unknown leaves retain raw spelling and explicit status, while known containers/qualifiers remain available. Declared-type extraction covers parameters, returns, fields, explicit inheritance/permits, throws and bounds. Expression type uses were added in the modern continuation below. See [declared-type evidence](../reproducibility/m2-types-2026-09-03/README.md) for the earlier slice.

Field read/write extraction has `PARTIAL` coverage. It binds fields and enum constants to their actual source or verified JAR/JDK declarations, classifies writes separately from receiver reads, and retains complete access/name spans. A verified local/parameter/type name produces no field fact. Unresolved explicit accesses preserve their occurrence and uncertainty; unclassified bare names have `java.unclassified-name` unmapped observations because field-vs-local identity is unknown. Package/type qualifier fragments are excluded, while unresolved members of a known value receiver remain accounted for. Array `length` has no declared field entity and is explicitly unsupported; annotation value accesses cannot receive fabricated execution owners. The earlier [field evidence](../reproducibility/m2-fields-2026-09-03/README.md) used catalog `m2-java-3`/adapter `3.26.1-m2.3` and withheld record/enum bodies; that boundary is superseded below. Java identity and coordinate versions are unchanged.

The current continuation supersedes those earlier support boundaries: catalog `m2-java-4`, adapter `3.27.1-m2.4`, JavaParser/SymbolSolver 3.27.1. Method references, expression type uses, annotation metadata, source records/compact constructors, enum constant bodies and bounded implicit members are implemented. `FrontendResult.annotations` retains syntax-site use identities; nested/default annotation values retain type uses without false direct annotations. `FrontendResult.derivedRelationships` carries `DerivedRelationshipRecord` values under the rules below, separate from explicit coverage. Implicit member declarations retain project origin and absent declaration spans. Record components support derived field/accessor/parameter types and automatic body facts; explicit members suppress the corresponding automatic facts. See [modern Java implementation evidence](../reproducibility/m2-modern-2026-09-03/README.md) for exact tested scope and limitations. Only the verified running JDK 21 `lib/modules` view and explicit single-release dependency JARs are accepted; module outputs, alternative platform views, multi-release JARs and JAR manifest classpaths are not silently approximated.

The outcome is a replaceable Java 21 frontend producing architecture-relevant declarations and relationship occurrences, with inspectable origins, exact source evidence, deterministic identities and explicit degraded outcomes. M3 supplies safe build acquisition; M2 accepts explicitly supplied, verified inputs. Spring inference, graph construction, metrics, policy, UI and snapshot matching belong to their later milestones.

## Decisions adopted provisionally for implementation

| Decision | Recommended design | Strongest alternative and consequence |
|---|---|---|
| D1: Java symbol identity | Versioned, escaped language-level identifiers; erased declaration parameter types for callable keys; source anchors for local/anonymous constructs | A full generic-signature key changes identity on generic-only edits; JVM/compiler-generated local names depend on compiler naming. A versioned relaxation of M1 NFC validation is possible but affects every consumer |
| D2: Port and evidence envelope | Immutable parser-neutral input/output records around M1; declaration diagnostics and unmappable observations remain in an explicit ledger; new explicit entity kinds for record components, lambdas and initializer blocks | Flatten into the nearest enclosing M1 entity and retain execution context only as metadata; this loses independently addressable execution/declaration units. Extending M1 occurrence spans to optional would weaken an existing invariant |
| D3: Source coordinates | Original decoded source, UTF-16 code-unit columns, tabs width one, CR/LF/CRLF line handling, exclusive end; version this interpretation | Unicode code-point columns are defensible but require conversion from Java APIs; display/tab-expanded columns depend on presentation settings |

The human's subsequent implementation direction resolves the earlier request to wait for D1–D3 approval. Do not request that approval again. Validate these decisions through code and tests; revisit only a concrete correctness blocker. Module/package names and fixture organization are reversible implementation details. Implementation authorization does not establish empirical accuracy, numeric performance budgets or M3 approval.

## Dependency and implementation boundary

Keep `com.evolution.analysis.contract` in `analyzer`. Place the `SemanticFrontend` port and Java-neutral language contracts in `analyzer`, with no JavaParser, compiler-tree, graph or Spring types in public signatures. Add a reactor adapter module `analyzer-javaparser` depending on `analyzer`; only the adapter module depends on JavaParser/SymbolSolver. This follows ADR-001's isolated implementation-module requirement. Do not move existing M1 files or introduce a second domain model in the adapter.

The future composition root constructs the adapter through the port. `backend` remains dependent on stable contracts until an application-composition task needs the implementation. The experimental comparison package is neither a dependency nor a production source template.

Use a pinned JavaParser/SymbolSolver version selected and tested at implementation time. The old comparison used 3.26.1; that is historical provenance, not an implicit choice for the production dependency.

## Proposed input contract

`SemanticFrontend.analyze(FrontendRequest)` returns `FrontendResult`. The request represents one compilation unit of configuration: one module/source-set/release/classpath combination. It may contain many source files. Different module or test-source classpaths require separate requests, not a merged dependency superset.

| Value | Required contents and validation |
|---|---|
| `FrontendRequest` | M1 `AnalysisManifest`; requested module and source-set identity; language/coordinate/catalog versions; immutable source bytes; ordered resolution inputs; declared limits |
| `SourceInput` | M1 `SourceDocument`; exact bytes and explicit UTF-8 decoding policy; source-root membership. SHA-256 must equal the manifest document and snapshot inventory digest |
| `ResolutionInput` | A manifest classpath entry plus a verified local artifact handle or indexed source input; owning module/artifact identity; input precedence. Local absolute paths are runtime handles, not stable identity components |
| JDK input | Java release, actual JDK version/vendor and content identity of the selected platform symbol view; no ambient host classloader fallback |
| Configuration | Release 21, preview disabled, explicit source-set/source-root mapping, exact resolution order, support catalog, identity and coordinate versions, deterministic file/result limits |

Hash the complete stable request plan into the M1 configuration options, so a change in module/source-root mapping, release, resolution order or semantic catalog changes `AnalysisIdentity`. Retain the structured plan alongside its digest for inspection. M1's global ordered classpath alone does not represent a per-module build model.

Before parsing, reject input/manifest mismatches, duplicate physical bindings, unsupported decoding, absent requested modules and unverified artifact bytes with structured diagnostics. A bad request cannot yield a successful empty analysis. Do not discover extra roots, download dependencies, execute target build scripts or load annotation processors. Re-validate mutable file handles before use, or copy them into an immutable input snapshot.

Collections and source byte arrays require defensive copies; a read-only Java reference alone does not make bytes immutable. M1 currently rejects non-NFC repository paths. Such a path must be an explicit input limitation until a separately versioned path contract is approved; silently normalizing it can select a different filesystem entry.

M2 does not invent a root-module path to work around M1's rejection of `.`. Root-module representation remains an explicit M3 contract question; the controlled M2 fixture uses the legal module path `fixture`.

## Proposed output and failure contract

`FrontendResult` contains the input analysis identity, frontend/version, sorted declaration records, M1 relationships/occurrences, derived relationship records, an observation ledger, diagnostics and input coverage. Runtime timings belong in separate operational provenance. Every emitted relationship must be supported by an explicit occurrence or a derived relationship record; a bare relationship is not evidence.

| Output | Meaning |
|---|---|
| `DeclarationRecord` | M1 `Entity`, language declaration detail, semantic status, derivation, uncertainties and diagnostics. A declaration need not be fully attributed merely because its syntax parsed |
| `RelationshipOccurrence` | Existing M1 value, emitted only when its source, target representation, origin requirements and complete occurrence span can truthfully satisfy M1 |
| `DerivedRelationshipRecord` | M1 relationship, semantic status, non-DIRECT M1 derivation with input identities, uncertainties, diagnostics and real supporting spans when available. It does not invent an explicit occurrence or declaration span |
| `ObservationRecord` | Stable source/category/anchor, optional span, outcome, optional mapped occurrence, raw reference in a safe representation, candidate evidence and diagnostic codes. Missing provenance or origin remains here when M1 mapping is impossible |
| `InputCoverage` | Every requested document with processed/partial/rejected/error/not-processed status and reason; every registered category with attempted/emitted/unmappable counts |
| Run state | Completed, partial, invalid-input, failed or canceled; an incomplete file/run never becomes a successful zero-fact result |

The observation ledger is parser-neutral. It does not serialize ASTs, exception objects or arbitrary stack traces. It separates semantic attribution, origin evidence and source evidence. A resolved compiler/parser target with missing provenance is `RESOLVED` in the attribution dimension and `MISSING` in the provenance dimension; it is withheld from M1 occurrence output instead of receiving a placeholder span.

Deterministic file/fact limits operate on canonical source order and report the unprocessed tail. A wall-clock stop or user cancellation is operationally partial and is not eligible for the complete-output determinism claim. Preserve its stop reason and processed inputs without presenting the partial result as equivalent to a completed analysis with the same input identity.

Use explicit error classes: absent symbol/dependency → unresolved; demonstrated competing applicable targets → ambiguous; intentionally unsupported construct → unsupported; malformed syntax → parse error; unexpected adapter exception → adapter error. Do not classify every runtime exception as unresolved. A recovered tree in an erroneous file needs a recorded recovery limitation; resolution alone cannot remove it.

Candidate sets require independently identifiable candidates with origins. If their identity/origin cannot be established, preserve the ambiguous observation and available evidence in the ledger, without fabricating an M1 `Candidates` value. Ordinary dynamic dispatch to overrides is not overload ambiguity: a call records its statically selected declaration, not a runtime implementation set.

Derived records obey the same target/status/uncertainty/error-diagnostic rules as M1 occurrences. Their deterministic key within one result is the relationship identity plus derivation method/version and ordered canonical input identities. Supporting spans point to actual owner/component syntax and are labeled as support, not as the nonexistent implicit member's declaration. Input entities/artifacts provide provenance even where no supporting source span exists. Derived facts have a separate registered denominator and are not counted as explicit source occurrences.

## D1: Proposed canonical symbol grammar

Use `java:v1:` followed by M1 canonical JSON of a structural tuple as the language key within M1 `canonicalName`. The M1 origin, scope and entity-kind inputs remain part of entity identity. The prefix prevents a new grammar from silently reinterpreting existing example keys. Tuple strings below are literal tags; identifiers are encoded as specified next. Nested owners are tuples, never concatenated names.

Identifiers are obtained after Java Unicode-escape translation and Java identifier-ignorable handling. Do not normalize composed/decomposed spellings into one identifier. Encode each identifier component with a reversible ASCII encoding: ASCII letters, digits and `_` remain literal; all other code points, including `$`, `%` and grammar delimiters, become `%` followed by six uppercase hexadecimal digits. Encode separators structurally, not as part of the identifier. Preserve original source spelling separately. This makes keys NFC-safe while retaining Java's equality rules. Java's lexical rules distinguish composed and decomposed identifiers and ignore certain identifier characters; NFC normalization is not Java symbol equality. [JLS 21 §3.8](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html#jls-3.8)

| Entity | JSON tuple after `java:v1:` |
|---|---|
| Package | `["package", [package identifiers]]`; empty identifier array is the unnamed package |
| Top-level type | `["type", [package identifiers], encodedName]` |
| Named member type | `["member-type", ownerTypeTuple, encodedName]`; recurse through named, local or anonymous owners |
| Method | `["method", ownerTypeTuple, encodedName, [erased parameter type tuples]]` |
| Constructor | `["constructor", ownerTypeTuple, [erased parameter type tuples]]` |
| Field | `["field", ownerTypeTuple, encodedName]` |
| Formal parameter | `["parameter", callableTuple, zeroBasedIndex]` |
| Type parameter | `["type-parameter", ownerTuple, zeroBasedIndex]` |
| Record component | `["record-component", ownerTypeTuple, encodedName]` |
| Local/anonymous type | `["local-type", ownerTuple, documentIdentity, startOffset, "local" or "anonymous", encodedNameOrEmpty]` |
| Lambda/initializer | `["lambda" or "initializer", ownerTuple, documentIdentity, startOffset, syntaxKind]`; initializer syntax kind distinguishes static/instance |
| Annotation use | `["annotation-use", ownerTuple, documentIdentity, startOffset]` |

An erased parameter type is `["primitive", keyword]`, `["declared", typeTuple]` or `["array", nonArrayElementTypeTuple, positiveRank]`. Type variables use the erasure of their first bound, or `java.lang.Object` when unbounded. Source-level parameter lists exclude JVM-injected enclosing-instance and enum name/ordinal parameters. For example, `example.A.run(int)` has exactly `java:v1:["method",["type",["example"],"A"],"run",[["primitive","int"]]]`. Golden tests freeze these bytes before extraction. Retain a type's binary name as metadata for source/classpath joins, without substituting compiler numbering for local/anonymous identity. A member `N` inside a local class `L` includes L's anchored owner tuple; two methods each declaring `class L { class N {} }` cannot collapse their N entities.

Callable identity uses the declaring type and declaration-level erased parameter types. Arrays retain rank; varargs normalize to arrays; primitive types use Java keywords. Return type, parameter names, throws list, annotations, type-parameter spelling, inferred arguments and call-site substitutions do not enter the callable key. Generic type syntax, bounds and substitutions remain explicit language detail/relationships. Overriding methods belong to their declaring type; inherited calls target the declaration selected by static attribution. The JLS defines method signatures and override equivalence; the proposed erased key is this platform's deliberate stable key, not a claim that it reproduces the full JLS signature definition. [JLS 21 §8.4.2](https://docs.oracle.com/javase/specs/jls/se21/html/jls-8.html#jls-8.4.2)

If declaration parameter erasure cannot be established, do not mint a guessed fully qualified callable entity. Keep a source-anchored declaration observation with uncertainty. An unresolved key cannot later be silently treated as a resolved identity.

Source-anchored keys are deterministic within an exact snapshot but may change when preceding text moves. This is an explicit limitation for later Track B matching; do not promise edit-stable local or lambda identities. Duplicate/invalid declarations receive separate source observations and diagnostics rather than one apparently valid merged entity.

Every `startOffset` in a key is zero-based in the original decoded source's UTF-16 code units, before Unicode-escape translation. It is not a byte offset, display column or position in parser-pretty-printed text.

An explicit catalog fact is unique by source entity, relationship kind, target representation and exact source span, and uses M1 occurrence ordinal `0`. Multiple type-role annotations on that same fact are combined in sorted language detail; they do not create multiple identical facts. Different source occurrences retain different spans, while read/write and other overlapping categories retain different relationship kinds. Repeated emissions of the same fact from extractor traversal are adapter defects: retain a duplicate diagnostic/ledger entry and fail evaluation, rather than inventing traversal-based ordinals or silently inflating coverage. Ordinals greater than zero are reserved in this catalog version; introducing a semantic reason for them requires a catalog/identity decision and new golden cases.

## Origin and declaration evidence

- **PROJECT:** target declaration is linked to an inventoried source and its owning module. A class from another supplied project module remains PROJECT, not DEPENDENCY.
- **DEPENDENCY:** target declaration is linked to the selected artifact content digest and its logical coordinate. Two artifacts containing the same name are resolved using the explicit ordered environment, with duplicate-definition diagnostics; package prefixes are insufficient.
- **JDK:** the selected platform symbol index establishes membership and content identity. A `javax.*` name does not establish JDK origin.
- **SYNTHETIC:** only analyzer-created semantic constructs with a documented derivation and content-addressed scope; never a fallback for unknown origin.

Implicit Java members remain associated with their declaring project's/artifact's origin. Absence of source spelling is a derivation property, not proof of SYNTHETIC origin. Their `Entity.declaration` is absent. A derived declaration record references the real owner/component inputs and a versioned language rule. Do not assign the caller's occurrence span as the target's declaration span.

D2 proposes adding `RECORD_COMPONENT`, `LAMBDA` and `INITIALIZER` to M1 `EntityKind`, with separate typed Java detail. This requires compatibility tests and an explicit semantic catalog/schema version. M1's existing values, identity preimages and golden cases remain unchanged. No undocumented synthetic method named `lambda$...` or `<clinit>` is substituted for a source construct.

## D3: Source evidence rules

Coordinate conversion operates on the original UTF-8-decoded source before Unicode-escape translation. Lines recognize actual CR, LF and CRLF; CRLF is one line break. Columns count UTF-16 code units from one, tabs count one, and the end is exclusive. An astral code point occupies two columns. Do not use editor display columns or normalize line endings/source text before hashing or locating evidence.

Convert verified parser offsets/ranges through an original-source map. JDK source-position APIs may report `NOPOS`; that is absent evidence, not offset zero. [JDK 21 SourcePositions](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/SourcePositions.html)

Register and test duplicate text, nested expressions, multiline invocations, tabs, CRLF, supplementary characters, Unicode escapes and escapes that affect lexical line structure. Missing or irreconcilable ranges produce ledger diagnostics and withheld mapping. Do not blindly add one to an end column without confirming parser coordinates and the original slice.

## Relationship catalog and denominator

The following catalog is the proposed M2 scope. A category is not implemented merely because it appears here. The oracle matrix must contain every row, including explicit unsupported/not-applicable cases. Generic, modern-language and error dimensions cross these categories rather than disappearing into a CALLS denominator.

| Category | Source → target; occurrence evidence |
|---|---|
| `java.declares` | Lexical owner → explicit type/member/parameter/component; declaration syntax |
| `java.extends` | Type/interface → explicit superclass/superinterface; each type syntax |
| `java.implements` | Class/record/enum → explicit interface; each type syntax |
| `java.permits` | Sealed type → explicitly permitted type; each listed type syntax |
| `java.type-uses` | Owning declaration/execution unit → referenced declared type/type parameter; each named type leaf outside imports, with syntactic role metadata |
| `java.calls` | Executable owner → statically selected method declaration; complete invocation expression |
| `java.constructor-calls` | Executable owner → constructor; explicit `new`, `this(...)` or `super(...)` expression |
| `java.reads-field` | Executable owner → selected field; complete field access/name |
| `java.writes-field` | Executable owner → selected field; complete field access/name |
| `java.method-references` | Executable owner → method or constructor; complete `::` expression; not an immediate call |
| `java.has-parameter` | Callable → declared parameter; parameter syntax, positional detail |
| `java.parameter-type` | Parameter → referenced type/type parameter; each named type leaf |
| `java.returns` | Method → declared return type/type parameter; each named type leaf; `void` has no entity edge |
| `java.field-type` | Field/component → declared type/type parameter; each named type leaf |
| `java.throws` | Callable → declared exception type/type parameter; throws type syntax; not an assertion about all runtime exceptions |
| `java.annotated-with` | Annotated declaration/type-use owner → annotation type; annotation syntax with use/role identity |
| `java.type-parameter-bound` | Type parameter → explicitly written bound type/type parameter; each named bound leaf |
| `java.type-argument` | Owning declaration/execution unit → each explicit type-argument type/type parameter; role path and variance detail |

`java.type-uses` overlaps purpose-specific type edges intentionally. Counts are per category; they must never be summed into a unique-reference metric without deduplication by source anchor/role. Primitive/void type information remains language detail, not invented JDK entities. Wildcards, arrays, intersections and parameterized types retain recursive type structure; merely flattening their leaf edges does not preserve type semantics.

`x.f += 1` and `x.f++` emit both read and write for `f`; `x.f = 1` emits write only for `f`. Reads in the receiver expression are accounted separately. Local-variable accesses must not become field accesses. Static imports and inherited fields resolve to their actual declaration.

Lambdas, local/anonymous classes and initializer blocks have their own lexical owners under D2. Each field initializer has an INITIALIZER entity owned by the field tuple, anchored at the initializer expression's start, with syntax kind `field-static` or `field-instance`. Each enum constant's written arguments have an INITIALIZER owned by the constant's FIELD tuple, anchored at the constant declaration start, with syntax kind `enum-constant`. Its written argument calls belong to that initializer. Implicit enum construction is a derived constructor relationship; the constant name is not a fabricated explicit `new` occurrence. Class-body static/instance blocks are INITIALIZER entities owned by their enclosing type. A lambda within any initializer owns its own body calls.

A lambda's body does not execute at lambda creation; method references do not become immediate calls. Record components, explicit and implicit record members, compact constructors, enums, sealed types, pattern matching and switch expressions require separate registered cases. Derived implicit relationships use `DerivedRelationshipRecord` with traceable language-rule inputs and are not counted as explicit source occurrences.

Java 21 non-preview syntax is the baseline. Preview/newer syntax, reflection-computed targets, dynamic proxy targets and runtime dependency injection are explicit unsupported boundaries for M2. Their presence must not silently remove a document from input coverage. Array constructor references and other source constructs without a normal declared callable need an explicit supported representation or an unsupported ledger observation.

## Ground-truth protocol

Freeze source bytes and expected labels before inspecting candidate frontend output. Use a new M2 corpus, not repaired-in-place legacy raw results. Each label records case/configuration/category, source owner, target/candidates, declaration origin evidence, exact source file/span/text, expected status, support boundary and rationale/oracle evidence. Declare negative controls as forbidden facts, separate from expected-positive denominators.

Use `javac` 21 compiler bindings with a diagnostic collector and source offsets, supplemented by reviewed source and classpath evidence. No annotation processing or target lifecycle execution. Reject/review recovered bindings in a compilation containing relevant errors; do not treat a returned compiler element as a valid oracle by itself. Compiler tree/element names are raw oracle observations, not the production canonical formatter. [JDK 21 Trees](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/Trees.html)

Keep distinct partitions:

1. Expected registered positives: mapped, unmappable, or omitted. Every label is accounted for once.
2. Candidate attribution: resolved, partial, unresolved, ambiguous, unsupported or error, without collapsing diagnostic dimensions.
3. Independent verdict: correct, incorrect or not-adjudicated. An accurately reported unresolved outcome can be correct handling without being a correctly resolved relationship.
4. Provenance/origin: valid, missing, incorrect or not-applicable.
5. Unexpected candidate facts and forbidden negative-control facts: reported separately as false positives, never lost through matching only expected labels.

Match on registered source anchor, category and role; check caller, target/kind/signature/scope, candidate set, origin, derivation, span, status and diagnostics separately. Preserve duplicate occurrences. Do not use printed text alone as a key. A repeated identical output must not inflate coverage or disappear through a set conversion.

Register A (sources + JDK), B (exact supplied dependencies), C (named artifact removed), module/source-set collisions, overload/varargs/boxing/generic chains, invalid ambiguity, syntax errors, duplicate text, Unicode, missing provenance/origin, adapter faults and deterministic reruns. Pin all JAR hashes and resolution order; M3 owns automated effective-build acquisition and real multi-module acceptance.

## Legacy comparison quarantine

The saved comparison remains historical CALLS-only evidence. Its raw files are immutable and are excluded from M2 ground truth, origin/provenance acceptance and G2 totals. This is a methodological quarantine, not file deletion.

Inspection found package-prefix origin classification, file-level pseudo callers, method/caller entities mapped as TYPE, signature-derived external content digests, caller spans reused for project target declarations, missing provenance diagnostics during mapping and compiler-oracle recovery without diagnostic adjudication. The known OpenRewrite placeholder spans are an additional issue. See the [comparison limits](../research/semantic-frontend-comparison.md).

Repairing that experimental generator may later produce a newly identified comparator run. M2 never imports its mapper, origin heuristic or labels as authoritative production behavior.

## Implementation order and acceptance

1. Use D1–D3 as the authorized provisional baseline. Freeze exact tuple golden values and fixture labels for the first slice alongside implementation; do not require all 18 categories to be designed or labeled before coding.
2. Implement neutral contracts and golden/negative tests in `analyzer`; preserve existing M1 tests. Add the isolated adapter reactor module.
3. Implement declarations/origins/coordinates, then relationship families. Start each behavior with independent expected cases; observe regression sensitivity. Record unsupported cases without reducing the registered denominator.
4. Verify integrated adapter outputs against the frozen matrix, including false positives, degraded states, malformed inputs, mapping failures and determinism. Inspect raw outputs, not only aggregate pass counts.
5. Run focused tests and full reactor verification. Run standalone oracles/comparators explicitly; root Maven verification cannot stand in for them.
6. Obtain independent read-only review of final contracts, source, labels and evidence. Resolve material findings before claiming M2 complete.

M2 completion requires the registered architecture categories and modern-Java cases to meet their explicitly reviewed support/handling expectations, no hidden omissions, no fabricated origins/spans, no unresolved blocking identity/provenance defects, and deterministic output on pinned inputs. Passing only a pilot subset cannot complete M2. G2 additionally requires M3's pinned multi-module and real-repository build-model evidence. Performance budgets must be registered against representative inputs before a scale claim; the initial protocol makes no speed or memory superiority claim.
