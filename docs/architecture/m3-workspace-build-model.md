# M3 Workspace and Build-Model Intelligence Contract

## Status and Scope

**PROVISIONAL Milestone M3 baseline; bounded M3.1–M3.7 slices are implemented, and Slice M3.8 defines the completion slice.** M3.7 delivered progressive external Maven model-POM acquisition, qualified environment-profile baselines and cross-release `ct.sym`. M3.8 provides bounded dependency artifact (JAR) acquisition into an isolated or selected local cache, unblocking external dependencies for the Spring PetClinic checkpoint and enabling Gate G2 completion. Later sections define target contracts for generated-source lineage and POM-less/non-Maven source plans.

Authority: [Project Context](../project-context.md), [Roadmap](../roadmap.md), [ADR-001](../decisions/ADR-001-parser-technology.md), [ADR-003](../decisions/ADR-003-progressive-evidence-acquisition.md), and [Progressive Evidence Acquisition Contract](evidence-acquisition.md).

M3 bridges the gap between raw file snapshots and the semantic frontend (M2). It delivers verified build context to the analyzer without executing untrusted repository code.

### Boundary with Conditional Architecture

M3 produces one exact source/build/classpath/platform world for each admitted source set. Explicit Maven profiles/properties that affect that world remain part of the M3 request and its identity; an unevaluated build profile remains a qualified M3 gap. M3 does not reinterpret one context as every possible build or deployment.

After G2, M4 may derive a finite Spring `ModeledConfigurationSpace` **inside** that exact build context. Spring profile/property/bean-registration variation belongs to [Conditional Architecture Semantics](conditional-architecture-semantics.md). A future build-context-family layer may compare dependency/toolchain/Gradle/AOT alternatives, but it is not an M3 or SE121 implementation claim.

## Implemented Slice M3.1 — Passive Effective-POM Projection

**CONFIRMED by implementation and focused fixtures on 2026-09-06:** `BuildModelProvider` and immutable contracts live in `analyzer` under `com.evolution.analysis.buildmodel`; the replaceable `analyzer-maven` adapter pins Maven Model Builder 3.9.16. Maven types remain inside that adapter. This slice provides declarative POM projections, not a complete `WorkspaceModel` or `ExactClasspathManifest`. G2 stays open.

### Input, output and identity

- `BuildModelRequest` binds a repository snapshot, exact entry POM path, defensive copies of supplied workspace/artifact POM bytes, and explicit `BuildModelPolicy`. Workspace bytes must match the existing snapshot inventory. The request does not discover files, read a cache, download artifacts, or change the snapshot.
- Request identity `build-model-input-v1` hashes snapshot identity, entry path, sorted logical POM bindings with SHA-256 digests, explicit profile/property policy and limits. External artifact POM bytes participate even if a particular module does not use them. Host paths, host properties, clocks and mutable byte arrays cannot enter implicitly.
- M3.1 introduced `build-model-result-v1`, provider `build.maven-model:3.9.16-m3.1` (superseded by M3.2 below). The result retains each discovered module (including failed/missing ones), aggregator links, optional effective-POM projection, typed build problems, actual read-attempt outcomes and explicit limitations. The projection exposes coordinates, packaging, module declarations, effective properties, active profiles, ordered direct/managed dependency declarations, scopes, classifiers, optional flags, exclusions and all POM inputs read during that module's evaluation.
- Aggregation and inheritance remain separate. Dependency declaration order is preserved; it is not a classpath or dependency mediation result. A successful input read does not imply successful effective-model construction. Failed models remain absent, with problems and evidence; Maven's recovered model is used only to classify an error.
- Build `Problem`/`Attempt` values are versioned observations, **not** the full `CapabilityGapRecord`, acquisition coordinator, evidence-requirement satisfaction or provider-conflict implementation described in `evidence-acquisition.md`. That normalization remains M3 work and must bind the eventual analysis identity without circular identity inputs.

### Root-module compatibility

M3.1 adds the exact module path `.` for the actual repository root. Only module-path validation accepts this marker; source/snapshot file paths still reject `.` and `..`. Identity remains the documented M1 tuple `[repository identity, module path]`; all previously valid non-root preimages and golden cases are unchanged. The new root preimage has a golden test. Maven coordinates are metadata, not an extra input to M1 `ModuleIdentity`. This additive case avoids inventing a synthetic directory or renaming a module when its Maven coordinates change.

### Passive evaluation and failure boundaries

- Maven Model Builder handles inheritance, interpolation and dependency-management imports/injection. The factory creates no Maven session, extension container, lifecycle executor, artifact transport or annotation processor. Plugin processing is disabled; declared extensions remain explicitly unevaluated.
- Model sources are immutable memory streams. Relative parent paths are normalized lexically inside the supplied inventory; absolute/escaping paths cannot read the host. An unavailable/denied relative-parent hint can fall through to a supplied exact-coordinate artifact POM. The original read attempt stays visible; a later successful lookup does not leave a false missing-parent problem.
- Artifact bindings verify literal GAV against supplied XML (including literal group/version inherited from a parent declaration). Unresolved/range-based model coordinates are not guessed. M3.1 itself had no cache/transport; M3.7 now supplies missing exact parent/BOM coordinates through a separate bounded acquisition wrapper without changing this pure in-memory model boundary.
- Profiles activated by explicit IDs, explicit user properties or `activeByDefault` are supported. The declared policy is applied consistently to imported BOMs as well as reactor/parent POMs. M3.1 originally withheld the whole effective model for undecided OS/JDK/file activation. M3.7 supersedes that cascade: it records `UNSUPPORTED_ACTIVATION`, deterministically leaves the undecided profile inactive, and preserves unrelated effective declarations as a qualified baseline. Explicit activation/deactivation is still required before profile-dependent facts can be treated as exact target-build facts.
- Secure XML validation precedes every Maven parse, including parent/BOM inputs. DTDs, external entities and XInclude are rejected; XML nesting is bounded at 64. Policy supplies positive POM byte/count limits and a per-module model-read budget (at most 128, including preflight and repeated reads). These are safety limits, not registered performance claims.
- Cycles, duplicate module paths/coordinates, missing inputs, unresolved expressions, malformed/unsafe XML and exhausted budgets retain typed reasons. Diagnostics never serialize raw exception messages, XML bodies or invented source spans. Built-in projection limitations remain visible even when the problem list is empty.

The strongest alternative was hand-written POM inheritance/interpolation. Using the pinned official model builder preserves Maven's own declarative merge semantics behind a replaceable boundary; a full Maven session would introduce unnecessary host settings, transport and extension-execution surfaces. The official [Model Builder sequence](https://maven.apache.org/ref/3.9.16/maven-model-builder/) defines the inherited/interpolated/imported stages used here. No Maven CLI-equivalence, complete repository acquisition, source-set isolation, classpath correctness, alternate-platform support or G2 acceptance is claimed by this slice.

## Implemented Slice M3.2 — Declarative Source Plans

**CONFIRMED by implementation fixtures:** `SourcePlanModel` adds immutable, ordered `MAIN` and `TEST` plans for each successfully modeled module. `build-model-result-v2` and provider `build.maven-model:3.9.16-m3.2` include these plans in result identity; request schema and existing module identities are unchanged. This is a declaration plan, not the M2 acquired-file/source-membership plan or a completed workspace analysis. See [verification evidence](../reproducibility/m3-source-plans-2026-09-06/README.md).

- **Paths:** inherited effective build source/test, resource and output directories are normalized lexically relative to the owning module inside the supplied inventory. `${project.basedir}`, `${basedir}` and `${project.build.directory}` have bounded path expansion; remaining expressions are unresolved, and absolute/escaping paths have no usable value. `pom` packaging gets no implicit Java roots; explicitly declared roots remain. Equal or nested main/test build roots are qualified as overlapping candidates. Resource directories are hints only; resource filtering and file membership are not evaluated. Cross-module ownership, symlinks, path existence and compiler `compileSourceRoots`/output overrides remain acquisition or plugin interpretation work.
- **Compiler declarations:** Maven effective inheritance/plugin management is retained. The matching `default-compile` or `default-testCompile` configuration overlays the plugin configuration through Maven's XML merge. Explicit scalar configuration precedes parameter property defaults; supplied user properties precede effective POM properties. Test source/target/release fall back separately to their main counterparts only when unspecified. Empty, unresolved or malformed explicit values do not silently fall through. Named custom executions and other configuration trees (including toolchains, processor settings, arguments, filters and generators) remain neutral data with explicit gaps. This bounded mapping is checked against Compiler Plugin 3.15.0; it does not simulate plugin execution, version-specific defaults or lifecycle binding. Non-3.x/missing explicit compiler versions and unknown packaging/configuration are qualified.
- **Separate requirements:** `syntaxLevel` uses declared release, otherwise source; `bytecodeTarget` uses release, otherwise target; `platformRelease` uses release only. The original six main/test source/target/release settings remain available. Legacy `1.1`–`1.8` source/target notation normalizes to integer levels. Positive integer release declarations are requirements, not proof of compiler, parser or platform availability. `java.version`, toolchain version and analyzer JDK never supply an inferred language/API level. Preview is retained as a declaration, without claiming preview support.
- **Encoding:** explicit goal/plugin `encoding`, then the `encoding` user/POM property, then `project.build.sourceEncoding` supply the declared charset. A fixed portable table recognizes UTF-8, UTF-16, UTF-16BE, UTF-16LE, ISO-8859-1 and US-ASCII with a bounded alias set. Other syntactically valid names remain `UNSUPPORTED`; malformed names are `INVALID`. No installed charset-provider lookup, byte decoding, BOM handling, heuristic guess or ambient fallback occurs in this slice. Broader charset support requires the later decoding provider and tests.
- **Provenance and gaps:** each setting retains the effective input expression, selector, optional normalized value, status, origin and POM derivation inputs. Selectors address the effective projection, not invented parent-file spans; raw POM bytes remain in the request. Convention paths retain model inputs because their base directory may be inherited. User-property origin is tied to the hashed request policy. Complete plugin/execution configuration trees preserve child order and attributes. Generated annotation-output directories are discovery hints, never acquired roots. `hasGaps()` now includes source-plan gaps as well as model problems; callers needing only model-construction problems inspect `problems()`.

The pinned official [test compiler source](https://github.com/apache/maven-compiler-plugin/blob/maven-compiler-plugin-3.15.0/src/main/java/org/apache/maven/plugin/compiler/TestCompilerMojo.java) defines independent test parameter fallbacks and generated-test paths; [shared compiler parameters](https://github.com/apache/maven-compiler-plugin/blob/maven-compiler-plugin-3.15.0/src/main/java/org/apache/maven/plugin/compiler/AbstractCompilerMojo.java) distinguish release/source/target and encoding defaults. These references refine the earlier provisional encoding/discovery ordering below; neither they nor successful model construction establish analyzed-platform availability.

## Implemented Slice M3.3 — Safe Filesystem Acquisition and Candidate Ownership

**CONFIRMED by implementation fixtures on 2026-09-07:** the neutral `com.evolution.analysis.acquisition` contracts and isolated `analyzer-filesystem` adapter acquire one explicitly selected local directory without executing repository code. Acquisition input/result schemas are `repository-acquisition-input-v1` and `repository-acquisition-result-v1`; the provider is `repository.filesystem:m3.3`. Candidate ownership uses `candidate-source-ownership-v1` and `workspace.source-ownership:m3.3`. See [verification evidence](../reproducibility/m3-filesystem-acquisition-2026-09-07/README.md).

- **Immutable acquisition:** every accepted regular file retains a defensive copy of its exact bytes and SHA-256 digest. A complete, sorted inventory creates the existing M1 `RepositorySnapshot` with no invented decoded `SourceDocument`; exact acquired `pom.xml` bytes can then create the M3 `BuildModelRequest`. The selected host path never enters request/result identity or persisted diagnostics.
- **Explicit selection and bounds:** policy supplies positive limits for files, directories, their combined discovered-entry ceiling, one-file bytes, total bytes and depth, plus sorted explicit exclusions. The combined entry budget also has a fixed one-million hard ceiling so deterministic child sorting cannot allocate an unbounded directory listing. No ambient ignore rules are inferred. Excluded paths are recorded as attempts and lie outside that selected snapshot; only a fully traversed selection can expose a snapshot. Limit, read, containment, link, special-entry or detected mutation failures retain partial observations but withhold the snapshot.
- **Containment and link policy:** the adapter requires the selected root's normalized absolute path to equal its canonical real path, compares real `Path` values rather than string prefixes, does not follow symbolic links or junction-like aliases, opens final files with `NOFOLLOW_LINKS`, and checks file and directory attributes around reads. A caller selecting through a symlinked ancestor or platform alias (for example macOS `/tmp`) must explicitly pass `selectedRoot.toRealPath()`; otherwise acquisition returns `ROOT_SYMBOLIC_LINK`. Raw exception text, target paths and host roots are not retained. This is passive local read access only: there is no Maven/Gradle lifecycle, plugin, process, user-settings, cache or network facility in the production adapter.
- **Candidate ownership:** acquired `.java` paths are compared against usable M3.2 source-root declarations. Every Java candidate is classified as exactly owned, overlapping or unowned; claims preserve module, `MAIN`/`TEST`, source root and POM evidence. Missing source roots and exact overlapping/unowned files remain typed problems. Candidate membership does not decode bytes, create semantic documents, evaluate generated-source hints, or establish source correctness.

## Implemented Slice M3.4 — Passive Exact Classpath Manifests

**CONFIRMED by implementation fixtures on 2026-09-07:** neutral contracts under `com.evolution.analysis.classpath` define `classpath-resolution-input-v1` and `exact-classpath-result-v1`; the replaceable Maven adapter is `classpath.maven-local:3.9.16-m3.4`. For every successfully modeled module it emits separate ordered `MAIN` and `TEST` manifests. See [verification evidence](../reproducibility/m3-classpath-2026-09-07/README.md).

- **Identity and evidence:** the request binds the exact M3 build request/result plus finite resolution policy. Each manifest identity binds module, source set, status, ordered entries, reactor requirements, mediation decisions and typed problems. The result also binds every successful/failed acquisition attempt, exact POM/JAR SHA-256 and byte size. Cache-relative Maven repository paths are portable locators; the selected absolute cache root is adapter state and never an identity or diagnostic input. Equal bytes and logical inputs therefore reproduce equal results across cache locations, while changed JAR bytes change identity.
- **Maven closure and ordering:** deterministic breadth-first selection implements nearest definition and first declaration at equal depth; selected dependency trees are flattened in pre-order (direct dependency, its selected descendants, then the next direct dependency). This matches the historical Maven Resolver behavior used by Maven 3.9.16, which embeds Resolver 1.9.27. Resolver 2 defaults to level-order, so adopting it is a provider-version/schema replacement trigger with new ordering fixtures, not a silent algorithm change. Root dependency management applies before transitive mediation. Compile/provided/runtime/test/system propagation, exclusions (including Maven wildcards), transitive optional omission, direct optional dependencies, classifiers, `test-jar`, `ejb-client` and descriptor-only POM dependencies have explicit behavior. Main and test manifests are resolved independently; dependencies never bleed between modules or source sets.
- **Reactor precedence:** exact reactor GAVs are resolved before cache artifacts. Their module identity, target source set and declared output directory remain explicit, but unacquired compiled outputs produce `REACTOR_OUTPUT_NOT_ACQUIRED`; no directory digest or classpath entry is invented. Duplicate reactor coordinates are withheld and cannot fall back to a cache copy. POM-packaged reactor dependencies contribute only their descriptor closure.
- **Passive cache boundary:** the caller selects one existing canonical standard Maven2-layout cache root. Reads use exact coordinate paths, real-path containment, link/junction refusal, final-component no-follow access, regular-file checks, before/after mutation checks and explicit coordinate/file/POM-byte/JAR-byte/total-byte/depth limits. Supplied artifact POM bytes from the hashed build request take precedence over cache POMs. Missing, denied, changed, invalid or over-limit inputs remain typed attempts/problems without raw host paths. Gradle caches and Maven split/custom layouts are different provider inputs; this adapter never guesses their structure. A future passive Maven Local Repository Manager adapter requires explicit configuration and the same no-settings/no-network evidence boundary.
- **External descriptor isolation:** each dependency POM is secure-XML validated before the pinned Maven Model Builder constructs its effective descriptor, including exact-coordinate parents and imported BOMs from the same bounded inputs. The M3 build policy's finite model-read budget is retained as `POM_MODEL_READ_LIMIT`. Plugin processing is disabled; repository declarations are inert; system properties, OS/JDK/file profile activation, user settings, transport, network and target lifecycles are unavailable. Only explicit profile/property policy is applied. From M3.7, an undecided environment profile records a qualifier while the descriptor's deterministic inactive baseline remains usable; the assembler does not confuse that qualifier with a missing JAR.
- **Deliberate gaps:** version ranges, `LATEST`/`RELEASE`, relocations, system paths and non-JAR classpath artifacts are withheld rather than guessed. A relocation is reported as `RELOCATION_UNSUPPORTED` and the obsolete coordinate's JAR is not admitted. M3.4 performs a bounded JAR signature check; timestamped remote-SNAPSHOT metadata/layout resolution, structural archive validation, manifest `Class-Path`, multi-release views, verified reactor binaries and construction of frontend `BinaryInput` values remain downstream responsibilities. These problems are versioned observations, not yet the normalized `CapabilityGapRecord` contract.

The mediation rules follow Maven's official [dependency mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html), [optional/exclusion semantics](https://maven.apache.org/guides/introduction/introduction-to-optional-and-excludes-dependencies.html), [Resolver ordering description](https://maven.apache.org/resolver/how-resolver-works.html) and [Maven 3.9.16 Resolver pin](https://maven.apache.org/ref/3.9.16/apache-maven/dependencies.html). The [repository layout contract](https://maven.apache.org/repository/layout.html) defines the exact path mapping. The adapter deliberately uses no Maven repository system or transport, so successful fixtures establish this bounded standard-layout provider rather than Maven CLI equivalence.

M3.5 extends the result schema to `exact-classpath-result-v2` and the provider to `classpath.maven-local:3.9.16-m3.5` by assigning one unique contiguous `order` across external and reactor requirements. Dependency mediation and pre-order semantics are unchanged; the additive ordinal prevents loss of shadowing order when the two typed lists are assembled.

## Implemented Slice M3.5 — Decoded Source, Platform Views and Frontend Inputs

**CONFIRMED by implementation fixtures on 2026-09-07:** neutral contracts under `com.evolution.analysis.input` provide content-addressed source-decoding, platform-acquisition and per-source-set frontend-assembly ledgers. `analyzer-filesystem` implements `platform.jdk-filesystem:m3.5`; JavaParser consumes explicit JAR, Java 8 `rt.jar`, Java 9+ JMOD, or explicitly verified running-module views. See [verification evidence](../reproducibility/m3-frontend-inputs-2026-09-07/README.md).

- **Strict source decoding:** `SourceDecoder` requires a complete M3.3 acquisition, exact candidate-ownership coverage, and the matching build request/result chain. It uses M3.2's usable declared encoding first; only an absent declaration may use an explicit `SourceDecodingPolicy`. Invalid, unresolved or unsupported declarations never fall through. The fixed portable catalog is UTF-8, UTF-16, UTF-16BE/LE, ISO-8859-1 and US-ASCII. Decoding reports malformed/unmappable input, BOM conflicts, unowned/overlapping files and missing evidence without charset guessing.
- **Byte and coordinate provenance:** `SourceInput` retains defensive raw bytes, raw SHA-256 through `SourceDocument`, canonical charset and origin, exact POM/policy evidence, detected UTF-8/UTF-16 BOM, and separate LF/CRLF/CR counts. BOM bytes are excluded from parser text, while line/column coordinates remain over the decoded original text; line endings are not normalized.
- **Analyzed-platform acquisition:** `PlatformSymbolRequest/Result` bind a target feature release and finite artifact/byte limits. The filesystem provider reads exactly one canonical configured JDK root, strict UTF-8 `release` metadata, and either `rt.jar` for Java 8 or a sorted JMOD set for Java 9+. It rejects links/aliases, non-regular or invalid archives, containment failures, mutation and release mismatch. Absolute paths are runtime locators and do not enter platform/result identity; exact artifact digests, release, full version and vendor do.
- **Frontend decoupling:** `PlatformInput` is an explicit artifact set. JavaParser adapter `frontend.javaparser:3.27.1-m3.5` builds platform solvers from verified JAR bytes or from JMOD `classes/*.class` entries, and assigns platform entities the aggregate JDK scope. The legacy running-module format is explicit and digest-verified; it is not selected by the M3.5 filesystem provider. Syntax is configured independently for non-preview Java 8–21; missing/out-of-range levels and preview requests are rejected rather than parsed as Java 21.
- **Exact assembly:** `FrontendInputAssembler` verifies the ownership/build/classpath/decoding provenance chain and emits one `FrontendRequest` per manifest only when its complete decoded source subset, platform view, external binaries and reactor-output JARs are present. Supplied dependency entries must exactly match M3.4 entries. Reactor outputs carry their producing module identity and declared output-root binding; their entities retain project/module scope. External and reactor inputs are merged by the shared ordinal. Missing, mismatched, materially incomplete or unreferenced binary inputs withhold the request, so a dependency superset cannot be introduced silently. From M3.7, an explicit inactive-profile qualifier remains in the ledger but does not by itself erase an otherwise exact input set.
- **Manifest provenance:** the M1 analysis manifest binds the platform aggregate, ordered binaries, syntax/bytecode/preview plan, classpath-manifest identity, source-decoding identity, analyzed platform release/version/vendor/hash and component versions. Machine paths remain outside stable identity.

M3.5 does not execute target builds, acquire compiled reactor outputs, interpret class directories, select older releases from a newer JDK's `ct.sym`, discover toolchains, enable preview syntax or acquire generated sources. M3.6 now normalizes its typed problems; the other boundaries remain explicit and G2 remains open.

## Implemented Slice M3.6 — Normalized Capability Gaps and Acquisition Provenance

**CONFIRMED by implementation and tests on 2026-09-08:** neutral contracts under `com.evolution.analysis.evidence` normalize the existing M2/M3 evidence ledgers without mutating or replacing their observations. Schemas/catalog are `capability-gap-record-v1`, `acquisition-attempt-record-v1`, `provider-conflict-record-v1`, `gap-resolution-record-v1`, `evidence-acquisition-ledger-v1` and `evidence.capability-gap-catalog:m3.6-v1`; provider is `evidence.gap-normalizer:m3.6`. See the [detailed contract](evidence-acquisition.md#implemented-m36-core) and [verification evidence](../reproducibility/m3-capability-gaps-2026-09-08/README.md).

- Gap identity binds the existing snapshot and optional pre-existing M1 analysis identity, provider/version, mechanism/reason catalog, typed subject, real spans and typed evidence questions. It deliberately excludes runtime messages, candidate providers and attempt history. Build gaps can be recorded before an analysis exists, so no identity cycle or synthetic analysis identity is introduced.
- Each gap cites exact content-addressed provider-result and observation payload digests. Exhaustive normalization covers repository acquisition, effective model, source-plan, candidate ownership, exact classpath, decoding, platform acquisition, frontend assembly, frontend run/source/category state, semantic degradation, missing origin/provenance and unmapped observations.
- Attempt records preserve provider, requirement, subject, input/configuration identities, trust/permission state, limits when explicitly supplied, optional timing, outputs, diagnostics and declared side effects. Missing legacy permission evidence remains `NOT_RECORDED`; it is not promoted to authorization.
- Provider conflicts are explicit and order-neutral. Later successful/partial evidence is additive through a resolution record; the original gap remains present. Denied, failed, unavailable, canceled, excluded or limit-exhausted attempts cannot satisfy a gap.
- Candidate providers remain ordered advisory values only. The normalizer neither selects nor invokes a provider, and this slice adds no network, filesystem or process capability.

M3.6 core does not run the representative repository pipeline, adjudicate correctness labels, emit the category/reason Gate table or accept G2. Generated-source and reactor-output acquisition remain separate provider decisions.

## Implemented Slice M3.7 — Progressive Maven Model Inputs and Cross-Release Platform Views

**CONFIRMED by implementation, TDD fixtures and local real-repository execution on 2026-09-08:** `MavenBuildModelResolver` wraps the pure model provider with `maven-build-model-resolution-v1` / `build.maven-local-inputs:3.9.16-m3.7`; model, classpath and assembly providers advance to M3.7 behavior. `platform.jdk-filesystem:m3.7` and `frontend.javaparser:3.27.1-m3.7` add exact `ct.sym` release views.

- **Progressive model-POM ladder:** use already supplied/workspace POMs first, then one explicitly selected standard Maven2 cache, then only release POMs from caller-configured credential-free HTTPS base repositories. Resolution iterates to a finite fixpoint so a parent may reveal imported BOMs and further parents. Exact coordinates, bytes, SHA-256, origin, logical location, attempts, failures, policy identity and final model/request identities are retained.
- **Network boundary:** remote repositories are explicit policy, limited to 16 HTTPS bases ending in `/`; user info, query/fragment, redirects and remote snapshots are rejected. Counts, per-POM bytes, one aggregate byte budget shared across cache and remote acquisition, passes and connect/request timeouts are finite. Target POM repository declarations, Maven settings/mirrors/credentials, metadata search, plugins, extensions and target lifecycles never control transport. A successful later provider does not remain mislabeled as an open missing-POM problem; earlier attempt history remains visible.
- **Qualified model continuity:** unevaluated JDK/OS/file profiles remain explicit configuration gaps but no longer invalidate every unrelated declaration in the same parent/BOM. The stable inactive baseline can establish modules, source roots, declared Java release and dependencies; profile-dependent claims remain qualified until explicit configuration is supplied.
- **Cross-release platform:** when the configured JDK is newer than the declared target release, the provider verifies and hashes `lib/ct.sym`, selects only signature entries whose release set contains the requested release, normalizes `.sig` entries to an in-memory class view and excludes module descriptors. This allows a Java 21 analyzer host to model Java 17 APIs without substituting Java 21 JMODs.
- **Shape matrix:** fixtures cover self-contained POMs, workspace-relative parents, external parent plus nested imported BOM, absent root POM, genuinely missing external parent, coordinate mismatch, local-cache acquisition, explicit HTTPS fallback and invalid remote configuration. Spring PetClinic demonstrates external Spring Boot parent and imported BOM acquisition without executing its lifecycle.

M3.7 does not pretend one provider covers every repository. A POM-less repository remains visible but needs a neutral explicit/convention source-plan provider; Gradle or other build tools need their own adapters. Remote dependency descriptor/JAR acquisition is separate from parent/BOM model acquisition, and both real-repository runs exposed missing local dependency artifacts. These are registered next-provider inputs, not evidence that the source cannot be analyzed in principle and not permission to fabricate a classpath.

---

## Core Invariants

1. **No Arbitrary Lifecycle Execution:** Analysis never executes untrusted target Maven/Gradle lifecycle phases (`compile`, `test`, `package`) or arbitrary target plugins. Build understanding comes from passive inspection, bounded exact data acquisition, or safe isolated model parsing.
2. **Strict Module and Source-Set Isolation:** Every source file belongs to an explicit module and source-set (`main` vs `test`). Classpaths and language levels are computed per module and per source-set; dependency supersets across modules are forbidden.
3. **Deterministic Classpath Ordering:** Classpaths are ordered lists of logical artifacts with SHA-256 digests. Local filesystem paths are machine locators, not identity inputs.
4. **Platform Decoupling (`analyzer-runtime != analyzed-platform`):** The host analyzer runtime (Java 21) is decoupled from the analyzed repository target platform (e.g. Java 8, 11, 17, 21). Platform symbol views do not guarantee parser syntax support beyond verified versions.
5. **No Speculative Encoding Guessing:** Source encoding is derived from authoritative build declarations or explicit analysis policy. Invalid byte sequences yield explicit degraded outcomes; original bytes and digests are strictly preserved.
6. **Lineage for Acquired/Generated Artifacts:** Generated sources do not mutate the immutable original repository snapshot. They are recorded as acquired artifacts with distinct identities, generator provenance, freshness status, and conflict handling.
7. **Explicit Capability Gaps:** Missing parents, unresolved dependencies, absent platform symbols, or stale generated code yield typed `CapabilityGapRecord` entries rather than silent omissions or artificial fallbacks.
8. **One Context Is Not Universal:** A complete M3 manifest establishes one exact build context. It does not establish all Spring deployment configurations or all build variants; downstream universal claims require proof over an explicitly identified modeled space.

---

## Workspace Structure and Module Model

### Module Identity and Aggregator Hierarchy

A workspace may contain a single module or a multi-module reactor hierarchy.

| Concept | Definition and Responsibilities |
|---|---|
| `WorkspaceModel` | Content-addressed container representing all modules, inter-module dependencies, source-sets, and analysis contexts in the repository snapshot |
| `RootAggregatorModule` | Maven aggregator module (often packaging `pom`) declaring `<modules>`. It provides reactor coordination and shared configuration, but does not contain Java source roots unless explicitly declared |
| `LeafModule` | Code-bearing or artifact-producing module with declared source roots, resource roots, and dependencies |
| `SourceSetModel` | Logical partition within a module (primarily `main` and `test`), carrying its own source roots, generated roots, output directories, compile dependencies, and runtime dependencies |

Canonical M1 modules are identified by repository identity and relative workspace path (`.` for the root, as added by M3.1). Logical Maven coordinates (`groupId:artifactId:version`) are separate build metadata; they do not replace or silently reinterpret canonical module identity.

---

## Safe Maven Model Interpretation

### Model Discovery Boundary

Maven model interpretation resolves the effective POM for each module by evaluating:
- Explicit parent POM declarations (resolving relative paths `../pom.xml`, local reactor siblings, or local cache artifacts);
- Dependency management (`<dependencyManagement>`) and imported Bill of Materials (BOMs);
- Build properties and interpolation (`${project.version}`, `${java.version}`);
- Profile activation based on explicit, declared analysis context (active profiles, OS, JDK properties);
- Standard Maven dependency mediation (nearest-definition-wins, first-declaration-wins for equal depth);
- Dependency scopes (`compile`, `provided`, `runtime`, `test`, `system`);
- Dependency exclusions (`<exclusions>`) and optional flags (`<optional>true</optional>`).

### Security and Isolation Boundary for Maven APIs

When invoking embedded Maven APIs (such as Maven Model Builder or Maven Resolver):
1. **Extension Lockdown:** Custom build extensions (`<extensions>`) declared in repository POMs are disabled and not loaded into the analyzer process.
2. **Filesystem and Network Containment:** Resolution starts with local reactor modules and a pre-configured read-only or isolated local repository cache. An explicit policy may additionally authorize bounded, credential-free HTTPS retrieval of exact release POM coordinates. Target-declared repositories, redirects, settings, credentials, metadata search and uncontrolled downloads remain prohibited.
3. **Host Property Isolation:** Ambient host system properties, environment variables, and user-level `~/.m2/settings.xml` must not silently contaminate repository evaluation. All effective properties are supplied via explicit, reproducible analysis configuration.
4. **XML Security:** XML parsers must disable external entity resolution (XXE prevention) and enforce entity expansion limits.

---

## Exact Classpath Manifests

### Classpath Manifest Schema

For each module and source-set, M3 produces an `ExactClasspathManifest` consumed by the semantic frontend:

| Field | Requirement |
|---|---|
| `manifestIdentity` | Content-addressed SHA-256 hash of all ordered entry identities, module identity, and source-set kind |
| `module` | Canonical module identity |
| `sourceSet` | `MAIN` or `TEST` |
| `status` | `COMPLETE` only when the manifest has no typed problem; otherwise `PARTIAL` |
| `entries` | Ordered list of `ClasspathEntry` records representing the exact resolution order |
| `reactorEntries` | Ordered module/output requirements kept separate from acquired dependency JARs |
| `decisions` | Deterministic nearest/first-declaration/duplicate-path/dependency-management mediation evidence |
| `problems` | Missing, denied, unsupported, conflicting or bounded inputs with stable reason and requirement codes |

### Classpath Entry Schema

| Field | Requirement |
|---|---|
| `coordinate` | Logical Maven coordinate (`groupId:artifactId:version`, classifier, type) |
| `scope` | Resolved dependency scope (`COMPILE`, `PROVIDED`, `RUNTIME`, `TEST`, `SYSTEM`) |
| `contentDigest` | SHA-256 digest of the artifact binary |
| `repositoryPath` | Portable path relative to the explicitly selected Maven cache; the absolute host root is not identity |
| `order` | Zero-based ordinal shared by external and reactor entries, preserving the exact assembled classpath order |
| `isDirect` | Boolean indicating whether the dependency was directly declared or transitively mediated |
| `depth` | Selected dependency depth used by mediation |
| `evidence` | Exact POM/JAR logical inputs and SHA-256 digests supporting the entry |

### Integration with `JarTypeSolver`

M3.4 exposes the ordered logical `ClasspathEntry` sequence and verified cache-relative JAR evidence. Supplying those exact bytes as `BinaryInput` values, adding verified reactor outputs and composing the analyzed-platform entry into a frontend `AnalysisManifest` remain M3.5 integration work. The existing frontend rechecks every supplied binary digest and preserves manifest order; M3.4 does not bypass that boundary.

---

## Platform Decoupling (`analyzer-runtime != analyzed-platform`)

The analyzer executes on Java 21, but repositories may target Java 8, 11, 17, or 21.

```text
┌──────────────────────────────────────────────────────────────┐
│                    ANALYZER RUNTIME (Java 21)                │
└──────────────────────────────┬───────────────────────────────┘
                               │
               Decoupled Platform Resolution
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                  ANALYZED PLATFORM SYMBOL VIEW               │
│   • Java 8:  rt.jar / core platform view                     │
│   • Java 11: ct.sym (release 11) or JDK 11 jmods             │
│   • Java 17: ct.sym (release 17) or JDK 17 jmods             │
│   • Java 21: ct.sym (release 21) or JDK 21 jmods             │
└──────────────────────────────────────────────────────────────┘
```

### Decoupling Rules

1. **Target Specification Discovery:** Preserve separate syntax, bytecode and API requirements per source set. Apply explicit compiler configuration and parameter properties as specified in M3.2. Release supplies a requested API level; source/target alone do not. `java.version` matters only when referenced by compiler configuration; a toolchain identifies a compiler requirement, not automatically a language or API level. A future explicit analysis fallback must record its assumption; M3.2 applies no default Java version.
2. **Symbol View Acquisition:**
   - For Java 9+: Standard library types are resolved using `ct.sym` for the targeted release number, or explicit platform modules (`jmods`).
   - For Java 8: Standard library types are resolved from a configured `rt.jar` platform view.
3. **Syntax vs Symbol Boundary:** Supplying a platform symbol view for Java $N$ provides type definitions for standard APIs (e.g. `java.lang.String`). It **does not guarantee** that the parser frontend can process syntax features beyond the frontend's verified language baseline. Syntax features beyond verified capabilities yield explicit `UNSUPPORTED` outcomes with stable reason codes (e.g. `java.syntax.unsupported-preview`).
4. **Platform Provenance:** Analysis manifests record analyzed Java platform version, vendor, source/target release, and symbol-view digest alongside analyzer runtime details.

---

## Source Encoding Policy

1. **Authoritative Charset Resolution:** Source encoding is determined per compiler goal in strict order:
   - Explicit effective compiler goal/plugin `<encoding>` configuration;
   - The parameter's `encoding` user/POM property, then `<project.build.sourceEncoding>`;
   - Explicit analysis configuration policy (e.g. declared default UTF-8).
   - *Ambient host default charset (`file.encoding`) must NEVER be inherited.*
2. **Byte-Order-Mark (BOM) Handling:**
   - Files containing a UTF-8 BOM (`0xEF, 0xBB, 0xBF`) are stripped of the BOM bytes during character decoding without corrupting 1-indexed line and column coordinates.
   - The existence of a BOM is recorded in file-level provenance.
3. **Invalid Byte Sequences:**
   - If a file contains byte sequences that are malformed under the selected charset, or if declared encoding conflicts with actual byte structure, the analyzer emits an explicit `INVALID_INPUT` diagnostic and treats the document as degraded.
   - *Speculative charset guessing (heuristics) is strictly prohibited.*
4. **Preservation of Raw Inputs:** Original raw bytes and SHA-256 digests are permanently retained in `SourceDocument` metadata.

---

## Generated Sources: Discovery, Lineage, and Freshness

Code generated by annotation processors (MapStruct, Lombok, QueryDSL) or build plugins (OpenAPI generator, Protobuf, ANTLR) requires explicit handling.

```text
┌────────────────────────┐         ┌────────────────────────┐
│ Original Snapshot Plan │         │ Acquired Artifact Plan │
│ (Immutable Git Source) │         │   (Generated Sources)  │
└───────────┬────────────┘         └───────────┬────────────┘
            │                                  │
            ▼                                  ▼
     Document Hash                      Document Hash
            │                                  │
            └─────────────────┬────────────────┘
                              │
                              ▼
                  Unified Analysis Context
                  (Explicit Lineage & Gaps)
```

### Discovery and Lineage Principles

1. **Discovery Hints:** Known build output locations (e.g., `target/generated-sources/annotations`, `target/generated-sources/openapi`) are inspected as discovery hints, not as authoritative source trees.
2. **Independent Document Lineage:**
   - Generated files **never** alter the original repository snapshot hash.
   - They are recorded in an `AcquiredArtifactPlan` with their own content digests, generator metadata (tool name, version, configuration), and input source references.
3. **Module and Source-Set Ownership:** Every generated source root must be attached to an explicit module and source-set (`main` vs `test`).
4. **Freshness and Conflict Verification:**
   - A generated file is marked `STALE` if input source files have newer modification indicators or differing digests than the recorded generation inputs.
   - If an entity is declared in both handwritten source and generated source with identical fully-qualified names:
     - The handwritten declaration takes precedence;
     - A `CONFLICT` diagnostic is recorded;
     - The duplicate generated entity is marked `SHADOWED_BY_SOURCE`.
5. **Missing Generator Outputs:** If a repository declares generator plugins or annotations (e.g. `@Mapper`) but corresponding generated source files are absent from `target/`, M3 emits a typed `CapabilityGapRecord` (`build.generated-source.missing`), identifying the missing artifacts.

---

## Target Slice M3.8 — Bounded Dependency Artifact Acquisition & Isolated Cache

### Purpose and Completion Boundary

Slice M3.8 is the designated completion slice for Milestone M3. While M3.7 resolved POM inheritance, BOM imports, and cross-release platform views, real-world repositories (such as the 27 missing dependency coordinates in Spring PetClinic) require external binary JARs to satisfy `FrontendInputAssembler` compile classpath requirements. 

M3.8 bridges this final gap by delivering a bounded, credential-free, policy-controlled dependency artifact provider that acquires missing external JARs into an isolated or selected local cache, verifies cryptographic digests, and admits complete source sets to the JavaParser semantic frontend for Gate G2 evaluation.

### Logical Contracts and Provider Schema

- **Provider Identity:** `dependency.artifact-cache:m3.8`.
- **Request Input (`dependency-acquisition-request-v1`):**
  - Ordered list of required coordinates (`groupId:artifactId:version`, optional classifier, packaging).
  - Explicit target cache directory path (selected `~/.m2/repository` or isolated content-addressed scratch storage).
  - Explicit list of authorized remote release repositories (default: Maven Central HTTPS `https://repo.maven.apache.org/maven2/`).
  - Strict resource limits: `maxTotalBytes`, `maxArtifactBytes` (default 100MB per JAR), `connectionTimeoutMs`, `readTimeoutMs`, `maxRetries` (maximum 2).
- **Result Output (`dependency-acquisition-result-v1`):**
  - Deterministic map of coordinates to `AcquisitionOutcome`:
    - `CACHED`: Artifact was already present in local cache and verified against expected SHA-256.
    - `ACQUIRED`: Artifact successfully downloaded via HTTPS, validated, and stored.
    - `SKIPPED_POM`: Packaging `pom` requires no binary JAR.
    - `FAILED`: Acquisition failed with typed reason (`NOT_FOUND`, `CORRUPTED`, `TIMEOUT`, `SIZE_EXCEEDED`).
  - Exact SHA-256 digest and portable cache-relative path for every resolved artifact.

### Exhaustive Edge-Case Handling Matrix (Common to Rare)

To ensure the platform handles any real-world repository robustly without guessing or silent failure, M3.8 enforces the following edge-case matrix:

| Scenario / Edge Case | Frequency | Architectural Behavior & Deterministic Strategy | Result / Capability Gap |
|---|---|---|---|
| **Standard Release GAV** | Most Common | Download from Maven Central via HTTPS, stream to temporary file, verify ZIP magic bytes (`PK\x03\x04`), calculate SHA-256, atomically move to cache. | `ACQUIRED` (SHA-256 bound) |
| **Already Cached Artifact** | Common | Verify file existence and non-zero size in local cache; read digest without network I/O. | `CACHED` (SHA-256 bound) |
| **Packaging `pom` (e.g. BOM)** | Common | Recognize `pom` packaging from model; do not attempt JAR download. | `SKIPPED_POM` (Complete) |
| **Custom Classifiers (`sources`, `test`)** | Common | Filter out non-binary classifiers during compile classpath assembly unless explicitly demanded by a test source plan. | Filtered / `SKIPPED` |
| **Corrupted / Truncated Download** | Edge / Network | Validate ZIP header (`PK\x03\x04`) and file integrity before promoting from temp. Corrupt files are deleted. | `FAILED(CORRUPTED_DOWNLOAD)` |
| **Network Timeout / HTTP 5xx / 404** | Edge / Transient | Bounded retry with exponential backoff (max 2 retries). On final failure, record explicit attempt outcome without crashing analyzer. | `FAILED(NETWORK_TIMEOUT)` / `FAILED(ARTIFACT_NOT_FOUND)` |
| **Relocation POMs (`<relocation>`)** | Occasional | Parse relocation metadata if present; record relocation provenance and resolve target coordinate. | Relocation provenance recorded |
| **Multi-Release JARs (MR-JARs)** | Occasional | Validate standard JAR; SymbolSolver reads root classes or Java N classes matching the target source plan's platform release. | `ACQUIRED` (MR-JAR verified) |
| **Version Ranges (`[1.0, 2.0)`)** | Rare / Dynamic | Deterministic fallback: select highest matching version available in local cache; if absent, query metadata with strict limit or emit gap. | `FALLBACK_CACHED_VERSION` or `UNRESOLVED_VERSION_RANGE` |
| **Timestamped Remote Snapshots** | Rare | Require explicit snapshot policy; default rejects non-reproducible snapshots to preserve deterministic replay. | `NON_DETERMINISTIC_SNAPSHOT` |
| **Untrusted POM Repositories** | Security Boundary | Repository URLs declared inside target POMs are ignored by default. Only explicitly configured system repositories are queried. | Untrusted repos ignored |
| **Credentials & Auth** | Security Boundary | Target repositories requiring authentication return `CREDENTIALS_NOT_SUPPORTED`; no ambient tokens or passwords are read. | `CREDENTIALS_REQUIRED` |

---

## M3 Capability-Gap Taxonomy

When build-model discovery encounters incomplete inputs, it emits typed gaps under the [Capability-Gap Contract](evidence-acquisition.md):

| Mechanism Category | Reason Code | Meaning | Downstream Effect |
|---|---|---|---|
| `build.pom` | `MISSING_PARENT_POM` | Parent POM remains unavailable after applicable supplied/workspace, selected-cache and explicitly configured HTTPS providers | Module inheritance incomplete; confidence `PARTIAL` |
| `build.pom` | `UNRESOLVED_IMPORT_BOM` | Imported BOM in `dependencyManagement` missing | Dependency versions may remain unresolved |
| `build.dependency` | `UNRESOLVED_ARTIFACT` | Declared JAR dependency missing from local repository | External symbol resolution fails; occurrences unresolved |
| `build.platform` | `MISSING_PLATFORM_SYMBOLS`| Configured target JDK symbol view unavailable | Preserve the gap; no implicit host-symbol substitution |
| `build.encoding` | `MALFORMED_BYTE_SEQUENCE` | Raw bytes invalid under declared charset | Document degraded or omitted with `INVALID_INPUT` |
| `build.generated-source`| `MISSING_OUTPUT_ROOT` | Declared generator output directory does not exist | Generated types missing; downstream references unresolved |
| `build.generated-source`| `STALE_GENERATED_OUTPUT` | Output files older than input source modifications | Potential semantic discrepancy; flagged in provenance |

---

## Gate G2 Acceptance Criteria for M3

1. **Pinned Multi-Module Verification:** Reproduces exact module hierarchy, source-sets, and inter-module dependencies across multi-module fixtures without dependency bleeding.
2. **Deterministic Classpath Manifests:** Generates identical ordered manifests and SHA-256 digests across repeated runs in clean environments.
3. **Platform Decoupling Verification:** Correctly applies target platform symbol views for Java 8, 11, 17, and 21 fixtures on a Java 21 host.
4. **Encoding Robustness:** Correctly handles UTF-8 with BOM, CRLF line endings, and declared non-UTF-8 charsets, rejecting malformed bytes with explicit diagnostics.
5. **No Silent Omissions:** All missing POMs, dependencies, or generated roots are accounted for in capability-gap records.
6. **Representative Real-Repository Checkpoint:** Evaluates an external multi-module Spring Boot repository, measuring attempted, resolved, unresolved, and degraded outcomes with reason-level breakdowns.
