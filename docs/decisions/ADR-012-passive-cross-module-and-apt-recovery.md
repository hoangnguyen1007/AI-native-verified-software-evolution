# ADR-012 — Passive Cross-Module Resolution and Isolated APT Evidence Recovery

Date: **2026-11-27**. **Status: PROVISIONAL architectural decision** under `AGENTS.md` Core Invariants `I1–I5`, ADR-007 (Isolated Build Evidence), ADR-008 (Passive Syntax & Open-Context Wiring), and the 5-Moonshot Consistency Contract.

---

## 1. Context and Problem

Real-world multi-module Java and Spring Boot repositories routinely combine:
1. **Multi-module reactor siblings** (Maven multi-module POMs and Gradle composite/multi-project builds) where module `A` imports types, interfaces, or Spring components declared in sibling module `B` before any sibling JAR has been built into `~/.m2` or `build/libs`.
2. **Compile-time Annotation Processing Tools (APT) and code generators**, notably **MapStruct** (`*MapperImpl`), **Protobuf / gRPC** (generated message/service classes from `.proto`), **QueryDSL** (`Q*` metamodel classes), **Immutables / Dagger**, and **Lombok** synthetic members.

This creates a direct architectural tension between two non-negotiable platform invariants:
- **`I1` (Execution Safety & Zero Host Execution):** The analyzer MUST NEVER execute untrusted target `javac` compiler hooks, annotation processors, Maven plugins, Gradle tasks, or `protoc` generators directly on the controller/developer host.
- **`I5` (Human Mandate on Comprehensive Coverage) & `I2` (Evidence-First / Zero Hallucination):** Repositories of arbitrary scale and multi-module architecture must maximize truthful semantic recovery without silently omitting unresolved generated symbols or fabricating fake types to inflate coverage metrics.

---

## 2. Decision: Strict Two-Tier Partition

We partition cross-module and APT recovery cleanly across two distinct Maven modules and epistemic boundaries:

### 2.1 Passive Reactor-Sibling Resolution (`analyzer-javaparser`)
`analyzer-javaparser` MAY resolve cross-module sibling declarations purely from captured, immutable repository bytes without executing any target code:
- **Mechanism:** Use declarative build-model dependency edges (`analyzer-maven` POM / Gradle model) to wire captured sibling source sets into `JavaParserFrontend`'s read-only declaration skeleton index (`LazySkeletonTypeSolver` / `ResolutionEnvironment`) and already-captured bytecode archives (`AsmBytecodeIndex`).
- **Scope:** Covers ordinary Java/Kotlin source declarations across reactor siblings, explicit imports, and bounded passive Lombok member extraction (`LombokExtraction` over `lombok.config` + explicit annotations).
- **Epistemic Labeling (`TrustTier`):**
  - Exact classpath + verified sibling source/bytecode resolution retains `TrustTier.COMPILER`.
  - Unbound passive syntax/open-context fallback retains `TrustTier.SOURCE_SYNTAX` and MUST NEVER be silently promoted to `TrustTier.COMPILER`.
- **Execution Boundary:** Strictly read-only AST/bytecode parsing in-process; zero target `javac`, zero APT SPI loading, zero `ServiceLoader` invocation of target JARs.

### 2.2 Isolated-Worker Bytecode & APT Evidence (`analyzer-execution`)
Whenever a repository depends on compile-time code generation (MapStruct, Protobuf, QueryDSL, Dagger, Immutables, OpenAPI generators, etc.):
- **Mechanism:** Target compilation and APT execution occur **exclusively** inside a validated, disposable isolated worker managed by `analyzer-execution` (container with `--network=none --read-only --cap-drop=ALL` or operator-registered Hyper-V VM under ADR-007).
- **Provenance & Re-Ingestion:** Generated `.java` sources and `.class`/JAR bytecode are exported as opaque bytes, verified against SHA-256 input/output lineage (`ContentDigest`), stored in CAS, and re-ingested passively by `analyzer-javaparser` (`JavaParserFrontend` + `AsmBytecodeIndex`).
- **Fail-Closed Guarantee:** If no validated isolated worker is configured or available, analysis MUST NOT fall back to host `javac`/APT execution and MUST NOT crash; it completes passive recovery and emits explicit typed `CapabilityGapRecord` entries.

| Capability | Owner Module | Target Execution? | Max `TrustTier` | Unresolved Behavior |
|---|---|---|---|---|
| Sibling source-set AST/skeleton lookup | `analyzer-javaparser` | **NO (Passive)** | `COMPILER` (when classpath-closed) / `SOURCE_SYNTAX` (open) | Explicit `CapabilityGapRecord` |
| Bounded Lombok passive member model | `analyzer-javaparser` | **NO (Passive)** | `SOURCE_SYNTAX` (or `COMPILER` with isolated bytecode) | Explicit uncertainty + gap |
| MapStruct / Protobuf / QueryDSL / APT output | `analyzer-execution` -> `analyzer-javaparser` | **YES (Isolated worker only)** | `COMPILER` (after SHA-256 verified capture) | Open `CapabilityGapRecord` (`ISOLATED_EXECUTION`) |

---

## 3. Hard Prohibition Against Fabricating APT Stubs

Under `I2` (Evidence-First & Zero Hallucination), `AGENTS.md` ("Native Evidence before Emulation"), and the `TrustTier` Promotion Guard:
1. **`analyzer-javaparser` MUST NEVER fabricate synthetic stub classes or methods for MapStruct (`*Impl`), Protobuf messages/builders, QueryDSL `Q*` types, Dagger components, or arbitrary APT outputs.**
2. **LLMs, regex heuristics, and advisory overlays MUST NEVER synthesize missing generated types or promote a `SOURCE_SYNTAX` / unresolved reference to `TrustTier.COMPILER`.**
3. Any `ExplicitImportTypeSolver` entry for well-known annotation names (e.g., `org.mapstruct.Mapper`, `com.google.protobuf.GeneratedMessage`) exists solely to preserve the *annotation/framework marker identity* in passive source-recovery mode; it never synthesizes generated implementation classes, bean bindings, or call targets.

---

## 4. Closed `CapabilityGapRecord` Denominator & Replacement Triggers

### 4.1 Closed Denominator Accounting (`I3`)
- Every unresolved APT-generated type, missing sibling binary, or unexecuted generator plugin MUST be recorded as a deterministic `CapabilityGapRecord` (`capability-gap-record-v1`) carrying:
  - Detecting provider (`frontend.javaparser`),
  - Mechanism category (e.g., `java.apt-codegen`, `java.cross-module-sibling`),
  - Typed `reasonCode`, exact `SourceSpan`s, and `EvidenceRequirement` with `AuthorizationClass.ISOLATED_EXECUTION` (or `PASSIVE` when only a sibling source set is missing from acquisition),
  - Non-empty `affectedOutputs` and explicit `limitations`.
- **Upgrading Evidence Without Rewriting History:** When an isolated worker subsequently supplies SHA-256-verified generated sources or bytecode, covered gaps transition to historical/resolved disposition in the new evidence revision, while uncovered obligations remain open in the closed denominator. Health scores and completeness metrics MUST NEVER shrink the denominator by hiding APT gaps.

### 4.2 Replacement Triggers
Revisit or supersede this ADR if any of the following empirical triggers occur:
1. **Memory Pressure on Mega-Monorepos:** In-memory sibling AST skeleton indexes exceed worker heap budgets on 100+ module reactors -> replace with disk-backed partitioned ABI/skeleton indexes (`ModuleIncrementalCache` / SQLite index) preserving canonical digests.
2. **JDK Language-Level Drift:** Bundled JavaParser 3.28.2 cannot parse new JDK preview/LTS constructs required by target repos -> upgrade parser frontend behind `SemanticFrontend` boundary with oracle parity tests.
3. **SCIP Supersession (Moonshot #5):** Isolated-worker `scip-java` / `index.scip` captures provide faster or more complete compiler-exact cross-module + APT symbol resolution -> `ScipSemanticCaptureAdapter` supersedes passive fallback for covered compilation units while keeping the exact same `I1–I4` guarantees and closed gap denominator.
4. **Isolated Worker Runtime Incompatibility:** Container/Hyper-V worker images fail offline APT recipes on target toolchains -> extend isolated worker recipe catalog without relaxing the zero-host-execution boundary.
