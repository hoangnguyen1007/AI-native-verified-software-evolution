# M8.4 Step 6 — Real-Repository Scale & Empirical Evaluation Plan & Master Upgrade Plan (`masterupdateplan`)

Date: **2026-11-03** (Updated: **2026-11-08**). Status: **M8.4 STEP 6 CLOSED / SCOPED EMPIRICAL BASELINE; MASTER UPGRADE PLAN (`masterupdateplan`) ACTIVE / IN PROGRESS**.  
Authority: Approved human decision (2026-11-08). **M8 and M8.4 Step 6 are officially CLOSED at the 10-repository empirical baseline** recorded in [M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md](../../research/M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md), and execution has transitioned to the **Master Upgrade Plan (`masterupdateplan` — 6 Phases / 15 Slices)** in [Section 6](#6-master-upgrade-plan-2026-11-08--6-phase-execution-roadmap-for-90-attribution--universal-repository-compatibility).

## Human correction — semantic depth and adaptive execution (2026-11-03)

**CONFIRMED human authorization:** repair and expand the implementation to minimize real-repository semantic gaps; hardware is not a reason for fixed product ceilings. The earlier evaluation-only restriction on modifying core code is superseded. Semantic capability and correctness take priority over publication tables. The 90–100% ambition is a target to measure on independently labeled categories and exact captured contexts, not an achieved or universally provable result.

**CONFIRMED source inspection:** `ObservationRecord.compareTo` serialized both full records for every comparison. `evo scan` composes passive Maven acquisition and does not dispatch an isolated build. `SOURCE_RECOVERY` deliberately withholds build-dependent attribution, even if some JARs are available. `HyperVMvpLauncher` launches a fixed offline fixture recipe; merely invoking it from scan would not build arbitrary Maven/Gradle roots. Default scan supplies neither a Spring configuration space nor a baseline assignment, so a prepared Spring plan does not by itself establish selected DI bindings. `QueryApiMain` had a separate 64 MB whole-file byte-array/string loader, while CLI workspace storage already used streaming row decoding. The reported ThingsBoard/Halo memory and duration numbers have not been independently remeasured in this change.

The following are implementation work within active Step 6, not a milestone acceptance or reopening of withdrawn earlier qualifications:

1. **Remove avoidable serialization/memory work.** Compare observation fields without JSON allocation while retaining the exact old canonical order/digests. Stream graph publication to CAS and API snapshot loading; remove the launchers' forced 4-GB heap and honor standard operator JVM allocation settings. Retain atomic publication, corruption detection and cancellation. This is the delivered repair in this change; a fully disk-backed graph/query path remains pending. Streaming JSON alone still retains typed graph rows and indexes in memory.
2. **Connect readiness to real isolated acquisition.** Normal scan must discover every selected build root/context, choose compatible registered tool/image/resource/network policy, reuse a matching validated capture or schedule the actual Maven/Gradle/native recipe, and resume from durable state. Capture effective modules/source sets, ordered per-context classpaths/platform, generated sources, processor/compiler options and output lineage; feed these to `JavaEvidenceAssembler` and existing prepared runners. A build of a fixed fixture is not evidence for a target repository. Generic-controller qualification and the accepted Core MVP lane keep their actual separate meanings; neither missing registration nor unavailable tool/image may trigger host fallback. Budget exhaustion must produce a checkpoint and resource request, not a repository exclusion. Native build output remains producer evidence until its applicable checks pass.
3. **Activate architecture semantics in those exact contexts.** Construct named Spring application/container scopes and evidence-backed finite profile/property/configuration spaces; retain unknown external inputs and phase/order uncertainty. Run existing registration/binding/truth engines and publish candidates separately from selected conditional bindings. Preserve distinct Maven/Gradle roots and generated-code origins. Required positive/negative labels cover constructor/field/method injection, Lombok/MapStruct/Protobuf native generation, qualifiers/primary/collections, factories/conditions and cross-module inheritance/calls. Available source/type attribution must be reconciled with stronger native evidence; missing context cannot become a blanket claim that the repository has no relationships.
4. **Close communication and scale paths end to end.** Feed actual language/schema/configuration evidence to B01–B08, including Kafka producer/topic/listener and gRPC proto/generated stub/client/server correspondence. Distinguish contract correspondence, configured destinations and observed runtime communication; a shared string or service name alone proves none of the latter. Replace monolithic graph loading with content-addressed context/row partitions and rebuildable adjacency/evidence indexes behind stable query services. API/CLI pages remain bounded, with continuation over the entire population. No whole-repository file/count/time ceiling or giant response is introduced.

**Observable exit evidence:** a named snapshot/context has a complete inventory disposition; native requirements are satisfied or explicitly pending; a labeled real DI relationship traverses acquisition → attribution → Spring → graph → API/CLI; labeled Kafka/gRPC cases traverse the bridge path; missing/ambiguous/dynamic cases remain counted and never fabricate edges. Check cold reload and unchanged-input replay, interruption/resume, allocation and query working sets. Register heavier native/scale runs only after their changed prerequisite is available; do not rerun blocked campaigns or reinterpret old passive captures as deep semantic evidence.

For each category report attempted/resolved observations, independently correct/incorrect matches, unresolved/ambiguous/unsupported/error/omitted outcomes and provenance failures. The coverage denominator includes **all applicable labeled obligations**, including candidates and omitted obligations. Report precision and recall separately, plus file/module/context/generator coverage; no single percentage averages away an empty category. Release wildcards in the cohort descriptions below are selection intentions until exact commit/input hashes are captured, not immutable pins.

---

## 1. Mission and Research Objective

The objective of Step 6 is to transition from micro-fixtures to **empirical validation on production-grade, real-world open-source repositories at scale**. This provides the concrete, reproducible experimental evidence required for:
1. **Semantic capability and correctness:** Repair the acquisition-to-architecture path and validate real relationships against independent labels, including negative controls and the complete missing-evidence denominator.
2. **Milestone M8.4 Formal Closure:** Demonstrating that streaming storage, multi-root readiness, isolated build evidence, language engines and architecture bridges satisfy their scoped contracts without fixed repository ceilings or silent omissions.
3. **Later research output:** Derive empirical tables and publication artifacts only from verified capability and measurements.

---

## 2. Evaluation Cohort: 4 Real-World Architectural Archetypes

In accordance with empirical software engineering methodology, the evaluation cohort must represent diverse architectural paradigms, build systems, language combinations, and communication protocols. Four active, reputable, and brand-new/maintained open-source repositories are selected:

### Archetype 1: Distributed Cloud-Native Microservices (Spring Cloud + Maven + Angular)
* **Repository:** `spring-petclinic/spring-petclinic-microservices`
* **Official URL:** `https://github.com/spring-petclinic/spring-petclinic-microservices`
* **Pinned Release:** `v3.4.1` (Commit `f9fd5594e5ec2844883445657cae01a8848db71c`, Release date: December 28, 2024)
* **License:** Apache-2.0
* **Build System:** Multi-module Maven (Parent POM, BOM, `dependencyManagement`)
* **Technology Stack:** Java 17/21, Spring Boot 3.4.1, Spring Cloud 2024, Spring Cloud Gateway, Angular / TypeScript frontend, Python GenAI service integration.
* **Architecture Topology:**
  - `api-gateway`: Spring Cloud Gateway exposing unified routing.
  - `customers-service`: Core domain microservice (Owners & Pets).
  - `vets-service`: Core domain microservice (Veterinarians).
  - `visits-service`: Core domain microservice (Visits & Appointments).
  - `spring-petclinic-genai-service`: Polyglot AI service module.
  - Supporting modules: `admin-server`, `config-server`, `discovery-server`.
* **Target Invariants Tested:** B02 (Frontend ⇄ Gateway ⇄ Microservices), Multi-module Maven hierarchy, Spring Cloud Gateway route propagation, cross-context boundary discovery.

### Archetype 2: Modern Enterprise Modular Fullstack (Spring Boot 3 + Gradle + Vue 3)
* **Repository:** `halo-dev/halo`
* **Official URL:** `https://github.com/halo-dev/halo`
* **Pinned Release:** `v2.27.x` (Active 2026 stream)
* **License:** GPL-3.0
* **Build System:** Multi-project Gradle (`settings.gradle.kts`) + pnpm / Vite frontend workspaces
* **Technology Stack:** Java 17/21, Spring Boot 3, OpenAPI 3 contract generator, Vue 3 / Vite / TypeScript.
* **Architecture Topology:**
  - `application`: Executable Spring Boot orchestration layer.
  - `api`: Data models, DTOs, and extension contracts.
  - `framework`: Plugin runtime, security filters, framework extensions.
  - `infra`: Persistence layer (Spring Data / Hibernate), caching, storage.
  - `ui/console` & `ui/uc`: Modern Vue 3 / Vite TypeScript administrative dashboards.
* **Target Invariants Tested:** B01/B08 (Gradle multi-project builds, generated OpenAPI TypeScript clients), B02 (Vue 3 components ⇄ Spring Boot REST controllers), Plugin extension boundaries.

### Archetype 3: Polyglot Distributed Systems Benchmark (gRPC + Protobuf + Multi-Language)
* **Repository:** `GoogleCloudPlatform/microservices-demo` (Online Boutique)
* **Official URL:** `https://github.com/GoogleCloudPlatform/microservices-demo`
* **Pinned Release:** `v0.10.x` (Active 2026 maintenance)
* **License:** Apache-2.0
* **Build System:** Polyglot (Maven, Go modules, npm, pip)
* **Technology Stack:** Java (`emailservice`, `adservice`), Go (`frontend`, `productcatalogservice`, `shippingservice`), Python (`recommendationservice`), Node.js (`currencyservice`, `paymentservice`), C# (`cartservice`), gRPC, Protocol Buffers (`.proto`).
* **Target Invariants Tested:** B03/B08 (Protobuf definition ⇄ gRPC client/server code generation across 5 languages), multi-language context identification without flattening identities.

### Archetype 4: Large-Scale Industrial IoT Platform (>500K LOC, Kafka, Netty)
* **Repository:** `thingsboard/thingsboard`
* **Official URL:** `https://github.com/thingsboard/thingsboard`
* **Pinned Release:** `v4.0` / `v4.4` (Active 2026 stream)
* **License:** Apache-2.0
* **Build System:** Complex multi-module Maven (dozens of submodules)
* **Technology Stack:** Java 17/21, Spring Boot, Netty, gRPC, Protobuf, Kafka message queues, Cassandra / PostgreSQL, Angular TypeScript.
* **Target Invariants Tested:** Physical CAS scalability (>100MB, >10,000 files), memory bounding under large graph partitions, B04 (Kafka message channels ⇄ listener consumer beans).

### Expanded Universal Benchmark Cohort (Added 2026-11-08 — Archetypes 5–10)
To eliminate blind spots across Android, Kotlin Multiplatform, CI-friendly enterprise Maven reactors, pure Kotlin Spring Boot, Python/TypeScript web stacks, and multi-tool code generation, six additional repositories are pinned in `D:/UIT/evo-benchmark-cohort/`:

| # | Repository | Pinned Commit (`HEAD`) | Build & Language Stack | Core Architectural & Semantic Challenges Tested |
|---|---|---|---|---|
| **5** | `TeamNewPipe/NewPipe` | `8917ff1b29a3146c34057c62a7b3cd49c1ac6d97` | Gradle Kotlin DSL (`.kts`), Version Catalog (`libs.versions.toml`), Android App + KMP `shared` (293 `.java`, 300 `.kt`, 562 `.xml`) | Triple-quoted strings in `.kts`, `projects.shared` typesafe accessors, mixed Java $\leftrightarrow$ Kotlin symbol resolution, Android `.aar` (`classes.jar`), `R.*` / `*Binding` / `BuildConfig` synthesis |
| **6** | `agentscope-ai/agentscope-java` | `5baff2613401bf87b3a967b185f2f14945ec594e` | 99-module Maven reactor (`${revision}` CI-friendly versions + intra-reactor imported BOMs) + Polyglot (2,147 main `.java`, 551 `.go`, 406 `.ts`/`.tsx`, 63 `.py`) | Intra-reactor `<scope>import</scope>` BOMs in `workspacePoms`, `${revision}` parent/BOM coordinate validation, Java 17–21 switch/lambda/pattern expressions, $O(M \times N_{\text{files}})$ graph projection scaling |
| **7** | `AndreyPavlenko/Fermata` | `8f97cc46425fc051fc4b24523b0a110c83c968ec` | 17-root / 16-Java-module Android Auto & Media app (`fermata`, `depends/utils`, 14 dynamic-feature `modules/*`, `.aidl`, JNI C/C++) | Enclosing-method unresolved parameter crash in `LombokExtraction`, `ReactorSourceInput.order` compaction when sibling module has 0 Java files, `settings.gradle` `projectDir` remapping & `eachDir` dynamic includes, `.aidl` interfaces |
| **8** | `spring-petclinic/spring-petclinic-kotlin` | `817dba3b02261e4291f138d5d8bb8c85c7d49758` | Pure Kotlin Spring Boot 3 (38 `.kt`, 0 `.java`), Gradle `.kts`, `io.spring.dependency-management` plugin | Pure-Kotlin repository admission (`javaFilesCount == 0`), Kotlin AST $\rightarrow$ Spring Boot component/route/DI enrichment, `ISO-8859-1` `.properties` fallback (`messages_de.properties`), implicit Spring Boot BOM derivation |
| **9** | `fastapi/full-stack-fastapi-template` | `aabb90126964a164e02aff39e7c385bb0598236d` | Python FastAPI backend (43 `.py`, `pyproject.toml`) + React/TypeScript frontend (109 `.ts`/`.tsx`, `package.json`, OpenAPI) | Passive intra-repository symbol/import/call linking across Python modules and TypeScript/TSX components without a guest VM |
| **10** | `mapstruct/mapstruct-examples` | `3f9fd5ffe9e2272b6219a4216e842f8c009d1005` | 26 independent Maven & Gradle subprojects (172 `.java`, 10 `.kt`, MapStruct, Lombok, Protobuf, Kotlin) | Dual build files (`pom.xml` + `build.gradle` in same directory), relative `../` source roots within repository boundary, multi-version Lombok & MapStruct mapper synthesis |

---

## 3. Experimental Research Questions & Metrics (RQs)

To ensure the empirical results meet the highest academic rigor, the evaluation answers four formal Research Questions:

### RQ1: Ingestion & Storage Scalability (Khả năng thu nạp và lưu trữ mở rộng)
*How effectively does the streaming CAS storage and SQLite inventory scale across large, multi-module repositories without memory exhaustion?*
- **Metrics:**
  - Total Raw Files ($N_{\text{files}}$) and Total Lines of Code ($N_{\text{LOC}}$).
  - Raw Byte Volume ($S_{\text{raw}}$) vs. Content-Addressed Store size ($S_{\text{CAS}}$).
  - Deduplication Savings: $R_{\text{dedup}} = 1 - (S_{\text{CAS}} / S_{\text{raw}})$.
  - Acquisition Time ($T_{\text{acq}}$) and Peak Resident Set Size ($\text{RSS}_{\text{peak}}$).
  - Pass/Fail of the 128 MB / 20,000 file scalability ceiling.

### RQ2: Multi-Context Dependency & Topology Recovery (Phục hồi cấu trúc đa ngữ cảnh)
*Can the platform accurately discover and separate multi-module build roots, independent contexts, and internal dependency structures?*
- **Metrics:**
  - Context Partitions identified ($K_{\text{contexts}}$) vs. Build Modules.
  - Software Knowledge Graph size: Vertices ($|V|$) and Edges ($|E|$).
  - Cross-module Dependency Density: $D = |E| / (|V| \cdot (|V|-1))$.
  - Strongly Connected Components (SCC) and Cyclic Dependency count ($N_{\text{cycles}}$).

### RQ3: Cross-Context Polyglot Bridge Precision (Độ chính xác cầu nối liên ngữ cảnh)
*What proportion of cross-language and cross-service architectural bindings can be formally resolved, and how are unresolved requirements accounted for?*
- **Metrics:**
  - Total Requirements Identified ($N_{\text{req}}$) and Endpoints Discovered ($N_{\text{endp}}$).
  - Outcome Breakdown:
    - $\text{BOUND}$: Proven match between caller and endpoint.
    - $\text{CANDIDATE}$: Ambiguous or conditional candidates.
    - $\text{UNRESOLVED}$: Explicitly unmatched requirement.
    - $\text{GAPS}$: Explicit `CapabilityGapRecord` with typed root causes.
  - Binding coverage: independently correct resolved bindings divided by **all applicable labeled binding obligations**, including candidate, unresolved, unsupported, error and omitted outcomes. A `BOUND` producer status alone does not establish correctness; report independently judged precision and recall separately.
  - Omission Denominator: Verification that omitted census rows equal **zero**.

### RQ4: Query Latency & Replay Determinism (Hiệu năng truy vấn và tính tái hiện)
*What is the query responsiveness on real-world graphs, and does cold cache replay yield byte-identical canonical digests?*
- **Metrics:**
  - P50, P95, P99 query latency for `/api/v1/bridges/summary`, `/api/v1/bridges/page`, and `/api/v1/bridges/topology`.
  - Replay Determinism: Hash comparison of analysis output across two separate runs on identical inputs ($\text{Digest}_1 \equiv \text{Digest}_2$).

---

## 4. Execution Protocol & Verification Guardrails

Every step of the empirical evaluation strictly adheres to the platform's non-negotiable core invariants:

1. **Host Execution Safety:**
   - Target repositories are never compiled or executed directly on the host machine.
   - Passive analysis reads captured source bytes, descriptors, and build configurations directly into CAS via `StreamingContentStore`.
   - Build execution (if required for generated bytecode) occurs only within the validated isolated executor.
2. **Zero Hallucination & Cryptographic Verification:**
   - Every entity, source span, and bridge relation is content-addressed with SHA-256 digests.
   - Candidate bindings without concrete source evidence remain marked as `CANDIDATE` or `UNRESOLVED`; no target is invented.
3. **Closed Reporting Denominator:**
   - Dynamic reflection, unmapped framework conventions, or missing external libraries are recorded as typed `CapabilityGapRecord` entries with root-cause provenance.
4. **Authorized repairs and integration:**
   - Reuse existing services (`BridgeQueryService`, `ArchitectureQueryApi`, `EvoApp`); repair supported core defects and missing integration under the human correction above. Preserve existing evidence and verify changed behavior before reporting success.

---

## 5. Next Steps & Artifact Deliverables

1. **Phase 6.1:** Set up automated acquisition and local repository clones for the cohort repositories into the dedicated data cache outside Git (`D:/UIT/evo-benchmark-cohort/`).
2. **Phase 6.2:** Execute passive streaming ingestion and inventory generation, recording $S_{\text{raw}}$, $S_{\text{CAS}}$, $T_{\text{acq}}$, and deduplication ratios.
3. **Phase 6.3:** Implement and verify the isolated build/model/generated-output → exact language attribution → Spring configuration/binding → bridge/query path above, then measure it on pinned cohort inputs. Inventory or zero-edge scans do not pass this phase.
4. **Phase 6.4:** Assess the actual Step-6 exit evidence and remaining gaps; derive the empirical report from those results. No publication artifact closes a missing semantic capability.

---

## 6. Master Upgrade Plan (2026-11-08) — 6-Phase Execution Roadmap for 90%+ Attribution & Universal Repository Compatibility

Following the 10-repository empirical benchmark documented in [M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md](../../research/M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md), ten structural defects and capability gaps were isolated. The remediation roadmap is structured into six bounded, verifiable phases that preserve all five non-negotiable platform invariants (host execution safety, cryptographic evidence provenance, closed reporting denominator, deterministic replay, and comprehensive repository coverage).

### Phase 1: Eliminate Provider Crashes (`SEMANTIC_PROVIDER_FAILED`) & Dropped Modules (`NOT_ANALYZED` / `INVALID_MODEL`) — COMPLETE & VERIFIED (2026-11-08)
* **Objective:** Bring module-level crashes and dropped reactor modules across `Fermata`, `agentscope-java`, and multi-module repositories to **0**.
* **Slice 1.1 — Guard `LombokExtraction` & Enclosing Callable Erasure (`Fermata` main module crash) — COMPLETE:**
  - **Files:** `analyzer-javaparser/.../NativeMemberExtraction.java`, `analyzer-javaparser/.../LombokExtraction.java`, `analyzer-javaparser/.../ImplicitExtraction.java`, `analyzer-javaparser/.../Extraction.java`, `analyzer-javaparser/.../LombokCompatibilityTest.java`.
  - **Change:** Added `NativeMemberExtraction.hasOwners()` fast-path and `try/catch (RuntimeException)` in `Extraction.nativeOwner(TypeDeclaration)`; in `LombokExtraction.unsupportedGenerators(CompilationUnit)` and `LombokExtraction.sameOwner`, filtered `GENERATORS` / `otherGenerators` and `localsOnly` *before* calling `context.nativeOwner(...)` inside `try / catch (RuntimeException)`.
  - **Verification:** Regression test `LombokCompatibilityTest#localClassInsideMethodWithUnresolvedParameterDoesNotAbortAnalysis` PASS.
* **Slice 1.2 — Compact `ReactorSourceInput.order` When Upstream Sibling Slots Are Empty (`Fermata` 14 `modules/*` dropped) — COMPLETE:**
  - **Files:** `analyzer/.../ingestion/UniversalSourceIngestion.java`, `analyzer/.../ingestion/ScopedIngestionRecoveryTest.java`.
  - **Change:** In `UniversalSourceIngestion.assemble`, tracked `skippedReactorSlots` when an upstream `ReactorSlot` has no Java sources (`reactorComplete = false`), compacting surviving `ReactorSourceInput` slot indices via `slot.order() - skippedReactorSlots` into a contiguous `0 .. (binaries.size() + reactorSources.size() - 1)` sequence while retaining `reactorComplete = false` and the corresponding capability gap.
  - **Verification:** Regression test `ScopedIngestionRecoveryTest#emptyLeadingReactorSlotCompactsSurvivingPeerSourceOrderInRecovery` PASS.
* **Slice 1.3 — Intra-Reactor Maven BOM (`<scope>import</scope>`) & Parent Lookup with `${revision}` Interpolation (`agentscope-java` intra-reactor BOMs) — COMPLETE:**
  - **Files:** `analyzer-maven/.../ModelInputs.java`, `analyzer-maven/.../MavenBuildModelProvider.java`, `analyzer-maven/.../UniversalMavenIngestionTest.java`.
  - **Change:** Propagated root CI-friendly properties (`revision`, `sha1`, `changelist`) into `MavenBuildModelProvider` effective user properties, indexed `request.workspacePoms()` by `(groupId, artifactId, effectiveVersion)` with property interpolation, checked `workspaceBomPath` in `ModelInputs.resolver().resolve(group, artifact, version, "BOM")` before `request.artifactPoms()`, and interpolated properties in `ModelInputs.validateCoordinate`.
  - **Verification:** Regression test `UniversalMavenIngestionTest#workspaceImportedBomWithRevisionPropertyResolvesWithoutExternalArtifactBundle` PASS; full affected suite (`ScopedIngestionRecoveryTest`, `UniversalMavenIngestionTest`, `MavenBuildModelProviderTest`, `LombokCompatibilityTest`, `MavenBuildModelResolverTest`, `MavenScanReactorTest`, `GradleScanTest`) PASS (exit 0).

### Phase 2: Universal Gradle, Android & Multi-Root Build Engine
* **Objective:** Accurately model real-world Gradle Kotlin/Groovy DSLs, Android variants/manifests, `.aar` binary dependencies, and nested microservice roots.
* **Slice 2.1 — Multiline Strings & Nested Comments in `DeclarativeScript` (`NewPipe/shared/build.gradle.kts`):**
  - **Files:** `analyzer/.../buildmodel/DeclarativeScript.java`.
  - **Change:** Support triple-quoted raw/multiline strings (`"""..."""` and `'''...'''`) without misinterpreting embedded single `"` / `'` or braces `{}` as token boundaries.
  - **Verification:** Unit test in `UniversalBuildIngestionTest` parsing `NewPipe/shared/build.gradle.kts` triple-quoted `generateBuildConfig` block with 0 `MALFORMED_INPUT` issues.
* **Slice 2.2 — Rich Gradle DSL & Android Build Semantics (`UniversalBuildIngestion` & `GradleScan`):**
  - **Files:** `analyzer/.../buildmodel/UniversalBuildIngestion.java`, `analyzer/.../buildmodel/GradleVersionCatalog.java`, `backend/.../GradleScan.java`.
  - **Change:**
    1. Resolve typesafe project accessors (`implementation(projects.foo.bar)` $\rightarrow$ `project(":foo:bar")`).
    2. Accept Android, KMP, and processor dependency configurations (`*Implementation`, `*Api`, `*CompileOnly`, `ksp`, `kapt`, `annotationProcessor`, `coreLibraryDesugaring`, and KMP `sourceSets { commonMain.dependencies { ... } }`), and permit trailing configuration closures (`implementation(...) { exclude(...) }`).
    3. Propagate `subprojects { ... }`, `allprojects { ... }`, and `android { compileOptions { sourceCompatibility ... } namespace ... }` to child modules.
    4. Parse `project(':name').projectDir = file('...')` / `new File(settingsDir, '...')` and bounded static directory loops (`new File(settingsDir, 'modules').eachDir { ... }`) in `settings.gradle(.kts)`, and discover standalone nested `build.gradle(.kts)` roots (e.g., `src/adservice/build.gradle` in `microservices-demo`).
    5. Recognize Android/KMP variant source roots (`src/main/kotlin`, `src/debug/java`, `src/release/java`, `src/<flavor>/java`, `src/commonMain/kotlin`, `src/androidMain/kotlin`) as `OWNED` source sets.
    6. Auto-derive the `org.springframework.boot:spring-boot-dependencies:<bootVersion>` BOM when both `org.springframework.boot` (with explicit version) and `io.spring.dependency-management` plugins are applied.
  - **Verification:** Focused tests in `UniversalBuildIngestionTest` and `GradleScanTest` covering each construct.
* **Slice 2.3 — Android `.aar` (`classes.jar`) Extraction & Google Maven Repository Support:**
  - **Files:** `analyzer-maven/.../GradleDependencyArtifactResolver.java`, `analyzer-maven/.../LocalArtifactCache.java`, `backend/.../MavenScan.java`.
  - **Change:** Allow `<packaging>aar</packaging>` in `GradleDependencyArtifactResolver`; verify the outer `.aar` SHA-256 digest and extract its embedded `classes.jar` entry into a content-addressed solver jar for `BinaryInput`. Include `https://dl.google.com/dl/android/maven2/` in `MavenScan.DEFAULT_REPOSITORIES`.
  - **Verification:** Unit test in `GradleDependencyArtifactResolverTest` verifying `.aar` acquisition, `classes.jar` extraction, and symbol resolution against an AndroidX `.aar` fixture.

### Phase 3: High-Recall Java, Lombok, Android & `SOURCE_RECOVERY` Semantic Attribution (90%+ Target)
* **Objective:** Resolve 90%+ of type uses, annotations, calls, field accesses, and method references even when external binary jars are partially or wholly absent.
* **Slice 3.1 — Multi-Version & Source-Evidenced Lombok Synthesis + `@Slf4j` Logger Field:**
  - **Files:** `analyzer-javaparser/.../Extraction.java`, `analyzer-javaparser/.../LombokExtraction.java`.
  - **Change:** Accept any hash-verified `org.projectlombok:lombok` dependency artifact on the classpath (removing the hardcoded 2-version `1.18.36`/`1.18.46` map in `Extraction.isLombok`), and in `SOURCE_RECOVERY` mode permit structural synthesis when explicit `import lombok.<Generator>;` / `import lombok.extern.slf4j.Slf4j;` statements are present in the compilation unit (recording `DerivationKind.DERIVED` with explicit rule provenance). Add `@Slf4j`, `@Log4j2`, `@Log`, `@CommonsLog` synthesis (`private static final Logger log` with standard logging methods), `@UtilityClass`, `@With`, and `@Accessors(chain = true)`.
  - **Verification:** Tests in `LombokCompatibilityTest` verifying `@Slf4j` `log.info(...)` call resolution and `@Data`/`@Builder`/`@Getter`/`@Setter` synthesis in both binary-verified and `SOURCE_RECOVERY` modes.
* **Slice 3.2 — Evidenced Explicit-Import Type & Member Recovery (`ExplicitImportTypeSolver`) + Java 17–21 Expression Fixes:**
  - **Files:** `analyzer-javaparser/.../ResolutionEnvironment.java`, `analyzer-javaparser/.../Extraction.java`, `analyzer-javaparser/.../ExpressionTypeExtraction.java`.
  - **Change:**
    1. In `SOURCE_RECOVERY` mode, index unambiguous single-type `import pkg.TypeName;` declarations per compilation unit. When `TypeName` is not found in `project`, `reactorSources`, or `platform`, synthesize an evidenced external type declaration bound to the import span (`EntityOrigin.DEPENDENCY` when matching a declared build dependency prefix, or external-imported scope), enabling resolution of `java.annotated-with`, `java.type-uses`, `java.parameter-type`, `java.returns`, `java.field-type`, `java.throws`, `java.type-argument`, `java.extends`, `java.implements`, static/instance calls on imported receivers, and constructor calls with deterministic signature erasure.
    2. In `ExpressionTypeExtraction`, support `SwitchExpr` result type inference across yield/expression arms, pattern variable bindings in `InstanceOfExpr` / `SwitchEntry`, and method references on recovered stream/optional/collection chains to eliminate the 81 `java.adapter-error` and 447 `java.adapter-unsupported` observations seen on `agentscope-java`.
  - **Verification:** Focused unit tests in `SourceRecoveryAttributionTest` and `ExpressionTypeUsesTest`.
* **Slice 3.3 — Android Resource (`R`), `BuildConfig`, ViewBinding (`*Binding`), `.aidl`, & Core `android.*` Platform Stubs:**
  - **Files:** `analyzer-javaparser/.../AndroidSyntheticDeclarations.java` (new helper in `analyzer-javaparser`), `analyzer-javaparser/.../ResolutionEnvironment.java`.
  - **Change:** From captured module files (`AndroidManifest.xml`, `build.gradle` `namespace`, `res/values/*.xml`, `res/layout/*.xml`, `res/menu/*.xml`, `res/xml/*.xml`, and `*.aidl`), deterministically synthesize project-scoped declarations for `R.<inner>.<id>`, `BuildConfig`, `<LayoutPascalCase>Binding` (with `@+id` view fields and `inflate`/`bind`/`getRoot`), and `.aidl` interfaces/`Stub.asInterface`, backed by exact XML/AIDL/Gradle source spans. Provide a minimal deterministic `android.*` / `androidx.annotation.*` core hierarchy stub solver in `SOURCE_RECOVERY` mode when `android.jar` is absent.
  - **Verification:** Unit tests verifying `R.id.*`, `R.string.*`, `FragmentVideoDetailBinding`, and `.aidl` resolution on `NewPipe` and `Fermata` fixtures.

### Phase 4: Kotlin Semantic Engine & Passive Intra-Repository Polyglot Linker
* **Objective:** Eliminate `0%` (`NO_ANALYSIS`) outcomes on pure-Kotlin (`spring-petclinic-kotlin`) and non-Java repositories (`full-stack-fastapi-template`) and enable Java $\leftrightarrow$ Kotlin cross-resolution (`NewPipe`).
* **Slice 4.1 — Kotlin $\leftrightarrow$ Java Symbol Bridge & Pure-Kotlin Spring Boot Pipeline:**
  - **Files:** `analyzer-javaparser/.../KotlinSourceTypeSolver.java`, `analyzer/.../ingestion/UniversalArchitecturePipeline.java`, `analyzer-treesitter/.../TreeSitterSourceFrontend.java`.
  - **Change:** Extract Kotlin package, class/interface/object/enum, constructor, property (with JVM getter/setter synthesis), and function declarations (including top-level `<File>Kt` and `Companion`) from captured `.kt` files into `ResolutionEnvironment` so Java classes in mixed modules (`NewPipe`, `mapstruct-examples`) resolve Kotlin targets. In `UniversalArchitecturePipeline`, admit modules containing `.kt` files even when `javaFilesCount == 0` (`spring-petclinic-kotlin`), projecting Kotlin declarations, call sites, and Spring annotations (`@SpringBootApplication`, `@Controller`, `@RestController`, `@Service`, `@Repository`, `@Bean`, `@GetMapping`, `@PostMapping`) into `FrontendResult` and `UniversalSpringSemantics`.
  - **Verification:** Integration test on a pure-Kotlin Spring Boot controller/service/repository fixture and a mixed Java+Kotlin module fixture; verify `spring-petclinic-kotlin` transitions from `NO_ANALYSIS` to `PARTIAL_RELATIONSHIPS` / `ATTRIBUTED_RELATIONSHIPS` with evaluated Spring beans and routes.
* **Slice 4.2 — Passive Intra-Repository Symbol & Import/Call Linker for Python, TypeScript/TSX, and Go:**
  - **Files:** `backend/.../StaticPolyglotScan.java`, `backend/.../PolyglotScan.java`.
  - **Change:** Build a repository-scoped export/declaration table from `TreeSitterSourceFrontend` `DECLARATION` and `IMPORT` facts across `.py`, `.ts`/`.tsx`/`.js`, and `.go` files. Link relative and package-internal imports and call sites to their resolved intra-repository `SOURCE_DECLARATION` nodes (`syntax.imports-declaration`, `syntax.calls-declaration`, `syntax.inherits-declaration`) with explicit `STATIC_SYNTAX_DERIVED` provenance, and report resolved vs. unresolved static polyglot relationships in `semanticDepth`.
  - **Verification:** Unit tests in `PolyglotScanTest` and verification on `full-stack-fastapi-template` and `agentscope-java`.
* **Slice 4.3 — Fix False-Positive `JAVA_RPC_UNICODE_MAPPING_REQUIRED` in `JavaGrpcSourceFrontend`:**
  - **Files:** `analyzer-javaparser/.../JavaGrpcSourceFrontend.java`.
  - **Change:** Remove the `\\u` trigger from `JavaGrpcSourceFrontend.candidate(byte[])` so non-gRPC files containing ordinary Unicode escapes (`"\u2022"`) are not misclassified as gRPC candidates, and use `OriginalSource` / `UnicodeSource` inside `parse(byte[], ...)` to map JLS §3.3 Unicode escapes back to exact byte/UTF-16 coordinates when a real gRPC file contains Unicode escapes.
  - **Verification:** Unit test in `JavaGrpcSourceFrontendTest` and zero `JAVA_RPC_UNICODE_MAPPING_REQUIRED` gaps on `NewPipe`.

### Phase 5: Universal Input Decoding, Path Normalization & Unbounded Compatibility Intake
* **Objective:** Eliminate avoidable `MALFORMED_INPUT`, `UNSAFE_PATH`, `AMBIGUOUS_INPUT`, and `COMPATIBILITY_WORKER_REQUIRED` rejections.
* **Slice 5.1 — `ISO-8859-1` Fallback for `.properties` & BOM-Aware Decoding:**
  - **Files:** `analyzer/.../ingestion/RepositoryInputDecoder.java`.
  - **Change:** Per the Java `.properties` specification, when a `.properties` file fails strict UTF-8 decoding (e.g., `messages_de.properties` in `spring-petclinic-kotlin`), decode it with `StandardCharsets.ISO_8859_1` instead of rejecting it as `MALFORMED_INPUT`.
* **Slice 5.2 — Repository-Bounded `..` Source Root Normalization & Dual Build-File Disambiguation:**
  - **Files:** `analyzer/.../buildmodel/UniversalBuildIngestion.java`, `analyzer/.../ingestion/UniversalSourceIngestion.java`.
  - **Change:** Resolve relative source roots against the owning module directory and normalize `..` segments *before* checking repository containment, permitting in-repo sibling paths like `../protobuf3-Async/...` in `mapstruct-examples`. When a directory contains both `pom.xml` and `build.gradle(.kts)` (`mapstruct-examples/mapstruct-lombok`), select the primary build model deterministically (preferring a reactor child of an enclosing parent aggregator, or Maven when standalone) so source files are claimed once without `AMBIGUOUS_INPUT`.
* **Slice 5.3 — Lift Legacy `10,000`-File / `128-MB` Compatibility Ceiling & Filter Symlinks Gracefully:**
  - **Files:** `analyzer-filesystem/.../RepositoryIntake.java`.
  - **Change:** In `RepositoryIntake.compatibility`, skip `SYMBOLIC_LINK` entries with an explicit inventory record instead of throwing `V1_KIND_UNREPRESENTABLE`, and remove the legacy `10_000`-file / `128_000_000`-byte hard abort so large monorepos (`ThingsBoard`) proceed through full Java semantic analysis.

### Phase 6: 20x–50x Performance & Storage Optimization
* **Objective:** Reduce multi-module scan and graph publication times from 15–30 minutes to 15–45 seconds and cut temporary disk payload volume by 80%+.
* **Slice 6.1 — Eliminate $O(M \times N_{\text{files}})$ Snapshot Duplication in `CanonicalGraphBuilder`:**
  - **Files:** `analyzer/.../graph/CanonicalGraphBuilder.java`.
  - **Change:** In `CanonicalGraphBuilder.frontend`, project `SOURCE_DOCUMENT` and `MODULE` nodes only for documents/modules belonging to that analysis unit rather than iterating all $N_{\text{files}}$ repository documents across all $M$ module contexts. Reference source content by its `ContentDigest` rather than re-serializing raw `byte[]` arrays as JSON integer arrays inside every context's `source.bytes` artifact.
* **Slice 6.2 — Cache Verified `PlatformInput` (`jmod`/`ct.sym`) & `JarTypeSolver` Across Module Contexts:**
  - **Files:** `analyzer-javaparser/.../ResolutionEnvironment.java`, `analyzer-javaparser/.../JavaParserFrontend.java`.
  - **Change:** Cache verified platform `TypeSolver` instances by `(release, ContentDigest)` and dependency `JarTypeSolver` instances by `ContentDigest` across `FrontendRequest` invocations within a scan, avoiding repeated 70-JMOD decompression and temporary JAR creation on every module in 100-module reactors (`agentscope-java`).
* **Slice 6.3 — Indexed Edge Filtering in Service Projections & Batched CAS Flush in `PartitionedGraphStore`:**
  - **Files:** `analyzer/.../polyglot/ServiceArchitectureProjection.java`, `analyzer/.../polyglot/HttpServiceCallProjection.java`, `analyzer/.../polyglot/GrpcServiceCallProjection.java`, `analyzer-filesystem/.../SqliteGraphProjection.java`, `analyzer-filesystem/.../PartitionedGraphStore.java`.
  - **Change:** Add a lightweight `kind` column/index (or prepared statement reuse) in `SqliteGraphProjection` so HTTP/gRPC service projections query only candidate edge/node kinds rather than deserializing all 300,000+ graph rows; defer `FileChannel.force(true)` to segment rotation and root publication boundaries.


