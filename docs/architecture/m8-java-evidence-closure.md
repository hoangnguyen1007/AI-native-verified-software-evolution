# M8.4 Step 4 — Generated and Java/Spring evidence closure

Date: **2026-10-20**. **CONFIRMED human sequencing:** Step 4 has Phase 1 (contracts/schemas) and Phase 2 (TDD implementation). This document and its [schema catalog](m8-java-evidence-schemas.md) are **PROVISIONAL implementation contracts**, authorized design within the existing Step-4 scope, not measured implementation or semantic acceptance. The [six-step plan](../tasks/m8/04-comprehensive-repository-readiness.md), [readiness contract](m8-repository-readiness.md), [coverage mandate](comprehensive-repository-coverage-problems.md) and [ADR-007](../decisions/ADR-007-isolated-build-and-polyglot-readiness.md) govern. Step 3 remains CLOSED / CORE MVP; its withdrawn security campaign is not restored here.

## 1. Outcome and boundaries

Consume immutable native outputs, reconcile their lineage and context, add generated source/resource/binary evidence without mutating the acquired snapshot, and rerun affected Java/Spring source sets with exact resolution inputs. Preserve every expected/observed output and semantic obligation. Close an obligation only with a validated answer to its question; expose both historical gaps and current residuals.

Phase 1 delivers this contract, executable structural schemas, validation examples, a [Phase-2 acceptance matrix](../tasks/m8/04-step4-verification-matrix.md) and one [implementation prompt](../tasks/m8/04-step4-phase2-prompt.md). Phase 2 implements the same Step 4; it is not an extra milestone. Non-Java engines and cross-language semantics stay Step 5; broad partitioned product/scale acceptance stays Step 6. Existing graph consumers and evidence drill-down must work for the bounded Step-4 journey. No new score formula, M1 identity algorithm, parser replacement, runtime execution entitlement or unrestricted sandbox certificate is introduced.

Step-4 completion requires positive native generator journeys as well as negative controls. A universal importer that only returns gaps is not complete. The required journeys remain proto/gRPC producer → generated Java → caller, Lombok generated members → caller, MapStruct implementation, custom generated root and a broken independent sibling. Import all declared/observed output kinds, including OpenAPI/ANTLR/QueryDSL/Dagger/kapt/KSP/custom outputs where encountered; mechanism-specific semantic claims require native fixture evidence. Language interpretation beyond Java remains Step 5; those artifacts still receive dispositions.

## 2. Verified starting points and implementation seams

Inspected at clean Git `ff0e031` before Phase-1 edits. These are **CONFIRMED code observations**, not newly executed behavior tests:

| Existing seam | Consequence for Phase 2 |
|---|---|
| `GeneratedSourceBundleImporter`, `generated-source-bundle-v2` | Java-only, declared-root-only, in-memory, all-or-nothing import; returns a new combined `RepositoryInputs`. Retain this legacy API/schema. New overlay lane handles streamed outputs, observed roots and per-scope recovery explicitly |
| `FilesystemResolutionBundleImporter`, `filesystem-resolution-bundle-v2` | Exact supplied artifact import, release-selected views and captured dependency graph exist; its `TRUSTED_CAPTURE` producer policy is a legacy supplied-evidence boundary, not the new native `VALIDATED_OBSERVATION` authority |
| `CoreMvpBuildPipeline`, `core-mvp-build-result:m8.4-v1` | Fixture-specific three-role export, SHA-256/CAS validation, `PRODUCER_ASSERTION`, `qualified=false`; publication is not a generic effective compiler manifest or a `BuildEvidenceBundle` |
| `DurableOutputPublication` / `OutputCensus` | Byte census and role dispositions are useful inputs; their existing wire schemas are distinct from the provisional Step-3 `OutputManifest` design |
| `EvidenceVerifier.VerifiedClaim` | Private accepted-claim capability plus separate receipt/validator authorities exists. Registered checks/signatures do not manufacture a missing native validation method; never sign an unverified producer assertion merely to obtain this token |
| `RequirementReconciler` | Current readiness view supports PENDING/SATISFIED/NOT_APPLICABLE/CONFLICTED. Reuse its authority boundary; add occurrence-gap bindings, causal reopening and predicate-specific answer comparison |
| `CapabilityGapRecord`, `GapResolutionRecord`, `EvidenceAcquisitionLedger` | Preserve v1 identities and audit records. Resolution v1 only has NARROWED/SATISFIED; it is not sufficient for current-state conflict/revocation/reopening |
| `FrontendRequest`, `BinaryInput`, `ResolutionEnvironment` | Exact M1 source subset and ordered binaries are enforced; binary API expects JARs, rejects repeated physical paths, and source solver precedes binaries. Directory/nested binary views, repeated native slots and Lombok source/binary reconciliation need an explicit bridge |
| `UniversalArchitecturePipeline.runWithEvidence` | Rebuilds passive model; no native-overlay input seam. Status uses analyzed/failed units and issues, so neither that heuristic nor historical gap count can establish new Step-4 completion |

Relevant paths and direct-consumer tests are listed in the Phase-2 prompt. Do not pass a mutated repository through the old pipeline and hope native roots, source sets or lineage survive passive rediscovery.

## 3. Design choice and ownership

| Option | Assessment |
|---|---|
| Merge generated files into the original checkout/snapshot | Simple legacy shape but loses base identity, collision boundaries, incomplete-output accounting and trust. Rejected for the new lane |
| **Immutable overlay + scoped semantic input view + additive reconciliation ledger** | Selected reversible design. Reuses CAS/readiness/M1/frontend contracts; separates bytes, native assertions and validated claims; requires explicit compatibility mapping |
| Replace the entire frontend/graph with compiler-native storage | Could represent more native detail, but changes protected identity/parser boundaries and Step-5/6 scope. Rejected for this step; selective compiler attribution remains replaceable |

`analyzer` owns immutable records, predicates, overlay/classpath planning, obligation reconciliation and active coverage. `analyzer-filesystem` owns CAS tables, indexes, atomic publication and safe compatibility materialization. `analyzer-execution` reads registered Step-3 publications and runs permitted native generation/validation adapters. `analyzer-javaparser` owns parser/solver mapping and source/binary correspondence. `analyzer-maven` retains passive Maven behavior; native facts enter through a separate evidence-bound model view. Backend/CLI compose ports; they cannot construct trusted claims from uploaded JSON. No process, JavaParser, SQL or Maven-native object enters neutral contracts.

Proposed ports (names/packages may vary without changing wire semantics):

| Port | Input → output / authority |
|---|---|
| `BuildOutputReader` | Registered publication + expected scope → verified byte handles, all dispositions, operational binding; no semantic authority |
| `GeneratedOverlayImporter` | Base inventory, scope, output census, lineage/proofs → immutable overlay + gaps/frontier |
| `JavaResolutionPlanner` | Scope, overlay, validated compiler/model evidence, platform → exact or withheld ordered resolution manifest |
| `JavaEvidenceAssembler` | Base + overlay + source plan + resolution → per-context semantic input view and strict frontend bridge |
| `EvidenceClosureReconciler` | Closed obligations, old gaps, proofs, conflicts, causal decisions → current projection + audit links |

All ports accept cancellation and bounded pages/work policies where I/O or traversal applies. Missing evidence yields typed scoped results; invalid programmer contracts can reject before work. One malformed output cannot erase verified independent partitions.

## 4. Identity and safe import

There are distinct identities: acquired base inventory/snapshot; stable logical generation scope; captured generation lineage; generated overlay revision; semantic input/analysis revision; operational attempt/publication; current coverage projection. Never use one as another.

The stable logical scope binds repository, build-root logical key, module key, **named** source set and variant. The exact scope additionally binds base inventory, optional compatible M1 snapshot, passive readiness plan, selection and context digest. Tool/config/source changes invalidate exact lineage and analyses, but do not themselves rename a logical Java symbol. A cross-context source/binary match requires correspondence evidence, not equal names.

Lineage binds complete observed inputs (source/schema/config/material/tool/environment), generator and compiler identities/bytes, ordered arguments/argfiles, recipe, exact dependency/platform manifests and output rows. Missing generator identity or dependencies may be represented as an incomplete lineage, never a fake version or empty-as-known list. Unknown fields have null plus a gap/frontier. A source-independent generator is allowed only with a validated zero-source applicability proof; tools/config/recipe still exist.

A resource-only generator can have `compilerApplicability=NOT_APPLICABLE` with a scoped proof and null compiler; UNKNOWN cannot close lineage. This does not waive Java compiler/context requirements for a later consuming source set. `nativeResolution` references the upstream captured execution-input manifest (including a proved empty search space when appropriate), not the later overlay-dependent Java resolution manifest.

Verify CAS digest and size, schema, registered publication ownership/scope and reference kinds before use. Core MVP input identity is attempt-bound and fixture-specific: retain it in operational provenance, never substitute it for the readiness input closure. Reconstruct stable exact input bindings from verified registered recipe/fixture/material bytes. If the base snapshot, root/context, compiler invocation or generator inputs cannot be bound, publish captured rows and `LINEAGE_SCOPE_UNPROVEN`; do not infer them from file names or the three expected output hashes.

Guest labels, `approvedBy`, `trust`, claimed SHA-256, successful exit and `qualified=false` are data, not promotion authority. Core MVP remains useful byte evidence without becoming generic validated native-model evidence. A protected parser may establish what captured source bytes declare without proving that they are the complete outputs of a particular original build.

No target code, classloader, annotation processor, compiler plugin, Maven model request, Gradle configuration, generator or executable index request runs on the host. Fresh isolated extraction/replay uses platform-owned pinned tools and immutable captured inputs; hooks requiring target execution return to the isolated execution lane. Missing registered worker/tool/materials leaves a precise requirement and continuation. It neither reopens Step 3 nor authorizes host fallback.

## 5. Overlay model and denominator

An overlay is a persistent context-qualified collection of output rows, not a patch to the source tree. Each expected output role and each observed entry has a unique row. Rows include origin locator, raw logical path, kind, content or absence reason, generator lineage, output-root ownership claim and disposition. Resources, class files, archives, directories, links, non-Java outputs, rejected entries and unexpected outputs remain counted.

Output-root claims distinguish DECLARED and OBSERVED. Both need exact source-set/context ownership; an observed root requires native evidence bound to the recipe/compiler/model. Location under `target/`/`build/` or an extension is not proof. Unknown/shared ownership retains candidates and `OUTPUT_OWNER_UNPROVEN`; no arbitrary first module wins.

`census=SEALED` means expected and observed rows are fully reconciled, including unusable rows. `closure=COMPLETE` additionally requires all required generation evidence and accepted outputs for this scope, empty frontier and no blocking conflict. SEALED can coexist with PARTIAL. An empty sealed overlay is legitimate with a proved zero-output expectation; a generator crash followed by an empty folder is not.

Partial processing retains immutable row/table checkpoints. A child partition can be complete if its independent input/output closure is established; its parent stays partial. Open tables cannot certify absent outputs or whole-source-set completeness. A new build producing different bytes makes a new lineage/overlay revision. Do not normalize timestamps, strip generated comments or overwrite old bytes to fake reproducibility.

Paths reuse raw-byte `LogicalPath`; no case folding or Unicode normalization. No raw path is used as a host extraction destination. Archives retain outer handle plus every nesting locator/member digest and entry ordinal; duplicate names do not disappear into a map. Links need closed containment/alias proof before serving bytes; unsupported device/link/path cases retain rows and gaps. Depth/expansion/record limits are work budgets with continuation, not total-repository admission ceilings.

## 6. M1 compatibility and semantic input view

Keep M1 factories, preimages, old snapshots and old frontend constructors unchanged. New `JavaSemanticInputView` binds base inventory/snapshot, exact context, overlay, model/source-plan revision, resolution manifest, correspondence table, active obligation projection and provider/policy versions. A materialized **derived** M1 compatibility snapshot is a view of these immutable inputs, not a replacement base snapshot. Record both identities and an explicit path map; never persist it as a new acquired source revision.

Handwritten documents retain their original path/document ID/hash/span. Generated documents have their own bytes and full positions. For collision-free paths a view may retain the actual generated logical path. To make mapping deterministic, select the namespace once per base inventory: the first absent root among `.evo-generated`, `.evo-generated-1`, … in ascending integer order (absent means no file or descendant at that segment). In the new lane **all generated Java documents** use `<namespace>/<logicalScopeDigestHex>/<hex(rawOutputPath)>.java` in the compatibility view. The raw original path remains the authoritative locator; no virtual path is a source-tree claim. The logical scope digest excludes tool/output bytes. Reject duplicate output paths within a logical scope unless a proved single-source correspondence reconciles them. Never overwrite a handwritten document. Very long/raw names stay in the logical view; choose a suitable worker if the compatibility consumer cannot represent them.

This projection is a new provider version. Old generated-bundle documents/identities remain historical; do not rewrite them to this namespace. Same logical Java declaration retains its M1 entity key when module/signature remain equal; generated document identity and original source-to-generated correspondence are separate concepts.

Virtual compatibility paths serve the parser/provenance view only. A native compiler replay must use the evidenced original input layout (including Java public-type filenames) through the verified guest mapping; it must not compile hash-named compatibility documents as if they were the original build.

Custom source sets retain exact names and semantic roles in the view/configuration. A legacy MAIN/TEST projection is permitted only with a proved role and a source membership table for that **one named set**; do not combine two custom sets because both map to TEST. Unknown roles remain unclassified, excluded from production-only metrics with an explicit applicability gap. If a combined compatibility snapshot would violate `FrontendRequest`'s exact-subset invariant, build separate per-context views with explicit parent inventory mapping, never relax the invariant to accept extra/missing documents.

Generated resources stay byte-addressed and are passed to resource/config ingestion only with decoding and ownership evidence; never coerce binary resources into `SourceInput`. Graph provenance must resolve generated spans through the view/path map to the captured artifact bytes. Keep original acquisition evidence available for ordinary source drill-down. Public views redact protected raw registration/environment data.

## 7. Ordered resolution and binary correspondence

The resolution manifest distinguishes a **known empty** search space from an **unknown** one. It binds compiler/source-language/target/release/preview/encoding/platform separately, ordered source roots and CLASS_PATH, MODULE_PATH, PROCESSOR_PATH and runtime/resource search spaces. Processor path is provenance, never an instruction for JavaParser to execute processors. Bind every argfile by exact bytes and resolved order.

Each slot has a contiguous zero-based ordinal **within its search space**, original logical name, entry kind, original handle/directory tree, selected release view, source-set/module origin proof and validation proof. Preserve repeats and native order. Never sort classpath entries by path/hash, deduplicate native slots, append the local Maven cache or fallback to the host runtime. Maven/Gradle dependency selection is evidence from the exact context, not a union across roots or variants. Reactor output occupies the same evidenced slot as the native build; a source-level substitute needs a declared proof and retains its distinct evidence kind.

Class-directory trees use a sealed entry table with every file/resource/link disposition. JAR, WAR and nested archive views retain raw archive bytes and content addresses of selected entries. A WAR layout or Spring Boot nested JAR is not automatically a Java compile classpath; an observed loader/compiler layout and order are required. A deterministic synthetic JAR may adapt a directory or nested view to the old solver only as a separately hashed derived artifact, with complete entry correspondence, original hashes and `MODULE_OUTPUT` origin. It never replaces the native output hash. Do not synthesize an empty/fake reactor JAR to make assembly succeed.

Multi-release selection uses the actual manifest, target release and versioned reader; preserve selected and shadowed entries. JPMS requires module descriptors, readability/exports, patch/add-reads/add-exports, split-package and module resolution evidence; flattening module path into classpath is forbidden. Where the current provider cannot answer, keep a scoped gap and request the selective compiler-backed attribution provider. Malformed class/archive, duplicate archive entry, unsupported class version and missing platform yield distinct dispositions.

Duplicate binary names are not automatically ambiguous: a validated ordinary classpath can establish first-slot selection. Retain all candidates and selected/shadowed evidence. Unproved order, conflicting source membership, illegal JPMS duplicates or incompatible correspondence are conflicts/unknown. Same bytes under different coordinates do not prove equal origins. Package prefixes do not prove PROJECT/JDK/DEPENDENCY.

Lombok needs special attention: the source solver currently wins before binary solvers. Add an explicit per-member correspondence layer so native constructor/builder/accessor evidence can augment the same project owner without hiding handwritten members, shadowing unrelated source, or creating a second owner. Original generated-member synthesis remains legacy/version-qualified; it cannot override conflicting native output or manufacture missing members. Source-to-binary matching needs exact enclosing owner, descriptor/signature, context/input lineage and relevant transformation metadata. Descriptors with lost generic information cannot certify richer source types.

Binary-only declarations carry artifact/class/member coordinates and no invented declaration span. Annotation spans and schema spans are supporting inputs, not generated declaration/body spans. A generated Java declaration uses its own actual UTF-16 span. Class `SourceFile`/line tables alone do not prove a source document or precise column. Selective compiler attribution has a declared category/census and must preserve missing/incorrect/ambiguous mappings; it cannot replace JavaParser globally.

## 8. Claim predicates and Spring continuation

Use separate proof questions. Neither lineage acceptance nor binary metadata validation is blanket semantic correctness.

| Question / predicate ID (version `m8.4-step4-v1`) | Required evidence | May close |
|---|---|---|
| `java.output-bytes` | Registered publication binding, CAS size/hash, exact row/locator, census disposition | Acquisition of those bytes only |
| `java.generated-lineage` | Exact source/schema/config/tool/recipe/material closure, scoped output association, closed generator census | Generation/output ownership requirement for those roles |
| `java.resolution-context` | Independently validated compiler/model inputs, all slots/platform/source membership, target settings and no contradictory evidence | Exact context/classpath requirement |
| `java.binary-shape` | Trusted non-loading extractor on captured class bytes, format/release/owner/member census, origin proof | Named class/member metadata questions |
| `java.source-binary-correspondence` | Exact scope and source lineage, owner/signature/transform matching, discrepancy checks | Equivalence/augmentation for specified members |
| `java.semantic-occurrence` | Reanalysis of exact input view, original occurrence/category, target/candidates/origin/provenance and accepted mapping evidence | That semantic requirement; not all uses of a class |
| `java.generator-not-applicable` | Closed generator discovery for context plus proof no relevant generator/outputs are needed | Generation applicability only |
| `java.build-validation` | Existing category-specific native build-validation evidence | Native build requirement only; not Spring runtime semantics |

Proofs are issued by platform-owned validators and checked at use against registered predicate/catalog/tool/policy, reference closure, exact scope and revocation state. Required check names are fixed per predicate, not a producer-selected list. An ACCEPT record in JSON is not a capability. Reuse `EvidenceVerifier` for protected native claims; local byte/shape checks remain explicitly narrower decisions. Qualified capture methods unavailable in the current implementation remain requirements, not zero-check success.

Spring ingestion consumes exact version-qualified source/resource/binary metadata through existing M4 condition/order/binding contracts. Generated bean methods/constructors can improve descriptors, not automatically activation or successful instantiation. Preserve configuration-space identity, phase/order version, truth regions and witnesses. AOT/runtime proxy outputs are observations for their captured context only. Custom registrars, reflection, runtime factory types and missing configuration/order retain obligations. Do not bypass scope/processor-policy proofs or enlarge the accepted Spring fragment from build success.

## 9. Append-only ledger and current coverage

Reuse `CapabilityGapRecord`/attempt/conflict/resolution v1 as immutable evidence. Add a versioned **closure ledger** with:

1. An independently enumerated requirement table keyed by exact scope + question + subject + category + predicate. Bind readiness requirements and every affected occurrence/gap. Multiple gap IDs can map to one requirement; one gap may require ALL of several questions. Retain generated-source census and opaque/unparsed units, including missing provider rows.
2. Content-addressed decisions referencing verified proofs, prerequisite decisions, conflict records and explicit superseded decisions. Allowed states: PENDING, NARROWED, SATISFIED, NOT_APPLICABLE, CONFLICTED, REOPENED. Only SATISFIED/NOT_APPLICABLE are inactive.
3. Durable operational binding to actual acquisition/execution attempt IDs and publications; these are not semantic preimage inputs.
4. A deterministic **current projection**, separately hashed from history, with one active row for every requirement and audited links to old records.

No wall-clock or arrival-order last-writer-wins. Decisions form an acyclic causal graph per requirement. Initial decisions supersede none; a later decision must cite all heads it resolves. Duplicate identical events are idempotent. Concurrent equivalent answers can converge; concurrent substantive disagreement yields CONFLICTED until explicit adjudication with evidence supersedes all relevant heads. A rejected alternative with no answer is not itself a contradictory answer. Failed/canceled acquisition cannot supply satisfaction proof.

The deterministic fold validates references and supersession first, then evaluates requirements in prerequisite topological order (digest tie-break). For each requirement, find the unsuperseded decision heads, revalidate their proofs against the current captured policy/input state, and compare predicate-normalized answers. No heads gives PENDING. Unresolved substantive conflict gives CONFLICTED; invalidated prior closure gives REOPENED; otherwise incomplete prerequisites/evidence/census gives PENDING or NARROWED when an evidenced proper subset remains. Only mutually compatible terminal heads with a closed producer disposition census yield SATISFIED or proved NOT_APPLICABLE. Pending/no-answer alternatives cannot create a conflict by themselves, but an undisposed required producer prevents closure. Equivalent proofs are retained as a canonically sorted unique set; no first-finish winner. A newer timestamp, or a supersedes reference without verified adjudication, cannot suppress contrary evidence.

SATISFIED requires all applicable predicate checks, accepted prerequisite decisions, a closed relevant denominator and no current unresolved contradiction/revocation. NOT_APPLICABLE needs its own applicability proof; it never silently changes the selected population. NARROWED keeps the unresolved remainder and affected occurrences. Revocation/corruption/lost closure reopens the requirement and transitively invalidates dependent decisions/views; retain immutable prior results but remove their eligibility for current verified reuse. A different base/input scope creates new requirements with explicit mapping; do not copy satisfaction by local ID or reuse a resolution merely because a gap disappeared.

The stable current projection contains sorted requirement ID, effective state, normalized answer/proof-content IDs, blockers and conflicts. It excludes operational attempt IDs, event arrival order, signatures, wall-clock values and supersession history hashes. Its trust-policy/validator versions, substantive invalidation state and exact source/output/input digests remain bound. Distinct historical paths to identical current verified facts may yield identical projection identity; their ledger/publication identities differ. No cycles: analysis input binds the **pre-analysis** readiness/overlay/resolution projection; post-analysis occurrence reconciliation references that analysis and yields a separate result, never feeds its own hash back into the same analysis.

Pre-analysis projection contains readiness/generation/resolution requirements only. The post-analysis occurrence census is a new versioned census referencing the input view and resulting analysis, not an extension that changes the already consumed pre-analysis digest. A ledger's append-only decisions stay within one census; an expanded census uses a new ledger with an explicit predecessor/mapping artifact and revalidates inherited decisions.

Within a declared requirement census:

`total = pending + narrowed + satisfied + notApplicable + conflicted + reopened`

`active = pending + narrowed + conflicted + reopened`

Counts are checked nonnegative 64-bit values. Historical gap count, active requirement count and affected occurrence count are different measures; root-cause grouping must not drop individual rows. Report residuals before/after on the same requirement/occurrence scope with explicit new/removed-by-proved-applicability populations. `gaps.isEmpty()`, build exit 0, bytes imported, or reduced diagnostics is not a completion predicate.

Unknown/inaccessible/unselected contexts and open discovery remain in root-level coverage. Per-context semantic COMPLETE requires its closed applicable census, accepted exact input view, all required semantic outcomes and no active unmet requirement. Inventory/generation/build/native execution/semantic coverage/semantic accuracy/operational completion remain separate fields. Never assign FULL confidence solely from job success. Existing metric/score formulas are unchanged; only re-evaluate with the appropriate new complete/qualified inputs, preserving generated/test exclusions and missing-evidence withholding.

## 10. Publication, cache and failure boundaries

Write verified immutable blobs/tables → validate all references and scopes → persist overlay/view/ledger revision → atomically publish a fenced pointer. Failed validation or crash before commit leaves the previous publication intact; replay verifies handles and does not rerun generation. A CAS object becoming unavailable invalidates dependent reuse and creates a typed storage/evidence gap; do not silently re-read mutable `target/`.

Cache keys bind exact base/context/selection, input closure, raw generated outputs, ordered resolution/platform/release/options, provider and predicate versions, trust/revocation state and deterministic work policy. Conservative full-scope invalidation is acceptable initially; narrower invalidation needs an evidenced dependency footprint. Changed generator/tool/config/material/source or output invalidates dependent entries; unrelated scope reuse requires independence proof. Operational waits/retries do not change canonical facts when captured inputs/policy remain equal.

Do not reuse partial semantic results as complete. Checkpoints retain row cursor/table digest and unresolved frontier; scheduling/resource shortage is an operational wait plus a scoped unmet requirement. Native failure retains independently validated outputs, but partial processor output cannot close generation for the entire source set. Rebuild byte differences remain separate evidence revisions and a reproducibility finding, not necessarily a semantic contradiction unless normalized answers actually disagree.

## 11. Exit and replacement triggers

Phase-1 exit: inspect current APIs; specify ownership, identities, wire fields, validation beyond shape, migration, trust and failure semantics; cover every Step-4 checklist item with Phase-2 tests; validate schemas/examples and local links; hand over one executable prompt. This is self-review, not independent review or production verification.

Phase-2 exit is the matrix's full Step-4 boundary, not merely unit tests. Register actual tool/artifact pins and independent expected callers/targets/origins/spans before interpreting native fixture results. No invented pins, handwritten generated stand-ins or disabled required hooks in positive native acceptance. Missing infrastructure/materials must be reported as unexecuted acceptance, not a passing gap test.

Replacement triggers: source-first solver cannot reconcile evidenced native members; duplicate/nested/JPMS semantics cannot be represented faithfully; compatibility views exceed configured worker resources; a binary reader cannot expose required metadata without loading target code; measured provider errors violate the registered category. Prefer a narrow neutral adapter extension or selective compiler provider. A broader M1 identity/schema/numerical-policy change needs an explicit new decision; this contract authorizes only additive views and schemas.
