# M8.4 Step 6 — Real-Repository Scale & Empirical Evaluation Plan

Date: **2026-11-03**. Status: **ACTIVE / IN PROGRESS**.  
Authority: Approved human decision (Direction B). M8.4 Step 5 is **CLOSED / SCOPED MVP**; this document establishes the empirical evaluation protocol, real-world repository cohort, and quantitative experimental design for **M8.4 Step 6 (Comprehensive Real-Repository Scale & Empirical Evaluation)**.

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

1. **Phase 6.1:** Set up automated acquisition and local repository clones for the 4 cohort repositories into the dedicated data cache outside Git (`D:/UIT/evo-benchmark-cohort/`).
2. **Phase 6.2:** Execute passive streaming ingestion and inventory generation, recording $S_{\text{raw}}$, $S_{\text{CAS}}$, $T_{\text{acq}}$, and deduplication ratios.
3. **Phase 6.3:** Implement and verify the isolated build/model/generated-output → exact language attribution → Spring configuration/binding → bridge/query path above, then measure it on pinned cohort inputs. Inventory or zero-edge scans do not pass this phase.
4. **Phase 6.4:** Assess the actual Step-6 exit evidence and remaining gaps; derive the empirical report from those results. No publication artifact closes a missing semantic capability.

