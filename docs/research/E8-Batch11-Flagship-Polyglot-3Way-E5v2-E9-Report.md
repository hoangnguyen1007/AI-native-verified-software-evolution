# E8 Evaluation Report: Batch 11 `microSecEnD` SOTA Parity, Flagship Polyglot & 3-Way Timing, `E5-v2` Spring 6.2 Oracle, `E9` AI4SE Suite & Live Online Docker Product E2E

- **Date / Reconciled:** 2026-11-28 (`e6-batch11/e6_summary.json` UTC: `2026-11-27T18:47:25.131750+00:00`; `e8_summary.json` UTC: `2026-11-27T19:13:13.865328+00:00`; `live_product_e2e.json` UTC: `2026-11-27T18:44:06.747Z`)
- **Immutable Historical Reports Preserved:** [`E5-Spring-SAT-Oracle-And-Ablation-Report.md`](./E5-Spring-SAT-Oracle-And-Ablation-Report.md), [`E6-Online-MicroSecEnD-17App-Linux-Benchmark-Report.md`](./E6-Online-MicroSecEnD-17App-Linux-Benchmark-Report.md) (Batch 9), and [`E7-Batch10-Passive-Topology-And-Determinism-Report.md`](./E7-Batch10-Passive-Topology-And-Determinism-Report.md) (Batch 10) remain strictly untouched for longitudinal ablation integrity.
- **Target Product Artifact (Online Web App — Final Batch 11 Build):**
  - Standalone JAR: `backend/target/evo-workbench.jar` — **`72,260,135` bytes**, `LastWriteTimeUtc`: `2026-11-27T18:41:20.0936157Z`, SHA-256: `13641D62676EA657A695F2CBCF875ACB39ED1EC36E5B34B26D0635DEB56BE630` (`-pl .,backend -am -DskipTests package`, `cli` excluded).
  - Linux Container Image: `evo-workbench-online:latest` (built from [`Dockerfile`](../../Dockerfile), `eclipse-temurin:21-jdk-alpine`, non-root `uid=10001`, `--online` entrypoint) — ID: `sha256:5cdb07ad0f6c344cc46441098aebe898329d40a38dd6ed4ac38f3b067cb7824e` (`5cdb07ad0f6c`), Size: `707,180,483` bytes (`707 MB` disk / `256 MB` content), Created: `2026-11-27T18:41:26.050625884Z`.
- **Verification Gates (`CONFIRMED`):**
  - Combined Wave 2.5 selective 7-module Maven suite (`.,analyzer,analyzer-maven,analyzer-filesystem,analyzer-javaparser,analyzer-treesitter,backend`; Enforcer rules 0–9 passed, `01:38 min`): **`241/241` PASS (`0` failures, `0` errors, `0` skipped)** across **32 test classes** (`analyzer`: `89`, `analyzer-maven`: `3`, `analyzer-filesystem`: `14`, `analyzer-javaparser`: `14`, `analyzer-treesitter`: `24`, `backend`: `97`).
  - Post-`#111` (`ArchitectureQueryService` pre-GC `StopReason.MEMORY` fix) 7-module regression suite (`59.251 s`): **`120/120` PASS (`0` failures, `0` errors, `0` skipped)** (`analyzer`: `58`, `analyzer-maven`: `3`, `analyzer-filesystem`: `3`, `analyzer-javaparser`: `9`, `analyzer-treesitter`: `24`, `backend`: `23`).
  - Offline Headless Browser E2E Suites (`#90`): `turbo-workbench.browser.cjs`, `stage0-web-ux.browser.cjs`, and `moonshot1-repair-studio.browser.cjs` — **`3/3` PASS** (`Dark`/`Light` WCAG AA, `1440`/`375`/`320` zero overflow, zero-HTTPS CSP).
  - Anti-overfitting gate (**I5**): **`I5_MATCHES=0`** across **`800`** scanned `src/main/` `*.java`/`*.py` files (`analyzer*,backend,cli`).
  - Python harness check: `python -m py_compile benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py` -> `EXIT_CODE=0`.
- **Pinned External Corpora (Stored Outside Git under `D:\UIT\evo-benchmark-cache\`):**
  - `microSecEnD` commit SHA: `63653e09177b6d757eada4521f8bace8a8c20766` (`17` apps in [`corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json) `suites.microsecend_17`).
  - `EMSE2025SAR-Replication` commit SHA: `1b8994471e9df0d32e22b31415eead8511078125` (`7` baseline tools).
- **Working-Tree Honesty Note on `cli/` (`CONFIRMED`):** Batch 11 performed **`0` edits to `cli/`**, targeting exclusively the Online Web App JAR and Docker image. Against `HEAD a661db3`, `git diff --stat -- cli/` still carries **3 pre-existing uncommitted tracked modifications** from pre-Batch-9 work (`cli/src/main/java/com/evolution/cli/EvoApp.java`, `cli/src/main/java/com/evolution/cli/Workspace.java`, `cli/src/test/java/com/evolution/cli/EvoScoreV2AndIncrementalCliTest.java`).

---

## 1. Batch 11 Engineering & Product Summary (`I5_MATCHES=0`)

Batch 11 landed seven coordinated, open-standard engineering tracks across the analyzer, backend API, and Online Web App UI while preserving strict `I1`–`I5` invariants (`I5_MATCHES=0` across all `800` `src/main/` files):

1. **Declarative Parsers (`StaticPolyglotScan.java`, `DeclarativeTopologyParsers.java`, provider `source.declarative-infra:m8.4-v3`):**
   - Added Elastic **Filebeat YAML** parsing (`output.logstash.hosts`, `output.elasticsearch.hosts`, `output.kafka.hosts` across flat/nested YAML).
   - Extended **Spring Cloud Config Server** resource-subdirectory repository resolution (`src/main/resources/<subdir>/`) and **`native` profile** suppression of inactive `spring.cloud.config.server.git.uri` when `spring.profiles.active` includes `native` alongside `searchLocations`.
   - Split passive Maven/Gradle starter tokens (`spring-boot-starter-zipkin`/`micrometer-tracing-reporter-brave` -> `"zipkin"`, `hystrix-stream`/`hystrix-amqp` -> `"hystrix-stream"`, `turbine-stream`/`turbine-amqp` -> `"turbine-stream"` distinct from HTTP `"turbine"`). Verified in [`DeclarativeTopologyParsersTest.java`](../../backend/src/test/java/com/evolution/backend/analysis/DeclarativeTopologyParsersTest.java) (`12/12`).
2. **Java Source Syntax (`JavaServiceSyntaxExtractor.java`):**
   - Added import-verified Spring Cloud role markers `@EnableTurbineAmqp` (`TURBINE_STREAM`) and `@EnableCircuitBreaker`/`@EnableHystrix` (`CIRCUIT_BREAKER`).
   - Added `@FeignClient(url = "${...}")` placeholder capture (`propertyKey` + default URL extraction) into `HTTP_CALL_CANDIDATE`.
   - Added symbolic-topic fallback (`symbolicTopic="true"`) for `@RabbitListener`, `@KafkaListener`, `@StreamListener`, `RabbitTemplate.convertAndSend`, and `KafkaTemplate.send` when topic/queue expressions do not constant-fold locally. Verified in [`JavaServiceSyntaxExtractorTest.java`](../../analyzer-javaparser/src/test/java/com/evolution/analysis/javaparser/JavaServiceSyntaxExtractorTest.java) (`9/9`).
3. **18 Projection Refinements under Strict Epistemic Separation (`DeclarativeTopologyProjection.java`, `EventFlowProjection.java`):**
   - Multi-context `BUILD_COMPONENT` identity key; Compose suffix `architecture.service-identity` fallback; `imageTail` infra guard; unhyphenated `canonicalBaseToken`; Compose `buildDir` + Dockerfile parent-dir + Compose-before-proxy unification + `resolveOwnerService` + existing infra/image reuse (including Zipkin); role-before-port loopback precedence; two-pass multi-profile datastore precedence; canonical `mail-server` for SMTP; shared `application.yml` `eureka-client` guard; direct Java `@EnableEurekaClient`/`@EnableDiscoveryClient` -> unique `@EnableEurekaServer` (`trust="SOURCE_SYNTAX"`); `symbolicTopic="true"` broker edges in `EventFlowProjection` without speculative peer-to-peer topic matching; refined `DFD_EXPORT` (`trust="DERIVED_DFD_POLICY"`) orientation for discovery/Consul/datastore/broker-consumer/Turbine/OAuth2-gateway, starter guards (Zipkin stream, `bus-amqp`, `turbine-stream`, HTTP Turbine `CIRCUIT_BREAKER`, declared-only datastore infra), Zuul explicit-route guard, Docker-socket monitor `target -> monitor` direction, outer reverse-proxy/SPA `user` ingress precedence, and canonical `external-website` `EXTERNAL_ACTOR` with GET/non-GET orientation. Verified in [`ServiceArchitectureProjectionTest.java`](../../analyzer/src/test/java/com/evolution/analysis/polyglot/ServiceArchitectureProjectionTest.java) (`14/14`).
4. **Pre-GC False-Positive `StopReason.MEMORY` Fix (`ArchitectureQueryService.java` & Benchmark Harness):**
   - Root cause proven during Batch 11 `#8`/`#15` runs: `ArchitectureQueryService.operationalGuard(...)` checked raw `maxMemory - totalMemory + freeMemory < 8_000_000` without letting G1GC reclaim uncollected garbage from preceding scans, halting `POST /queries/edges` with `StopReason.MEMORY` (`items: []`).
   - Fix: [`ArchitectureQueryService.java`](../../analyzer/src/main/java/com/evolution/analysis/query/ArchitectureQueryService.java) now executes at most one cooldown-bounded (`500 ms`) `System.gc()` reclaim check before failing closed with `StopReason.MEMORY` when genuinely out of heap; [`run_online_microsecend_benchmark.py`](../../benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py) now detects `operation == "RESOURCE_LIMIT"` / `stopReason in ("MEMORY", "TIME")`, retries with backoff, and raises `RuntimeError` rather than silently scoring `0` edges. Verified in [`PolyglotQueryServiceTest.java`](../../analyzer/src/test/java/com/evolution/analysis/query/PolyglotQueryServiceTest.java) (`5/5`).
5. **Spring DI `@Order` Integer & Equal-Tier Soundness Fix (`SourceToSpringPlan.java`):**
   - Discovered by `E5-v2` live Spring 6.2 differential oracle (Section 4): fixed `@Order(N)` integer literal parsing and added fail-closed `order = UNKNOWN` when non-monotone `@ConditionalOnMissingBean` candidates share an equal/unspecified `@Order` tier, restoring `100.0%` soundness (`0` False-MUST / `0` False-NEVER) across all `24` scenarios and `345` concrete worlds.
6. **Online Web App Product API (`AnalysisJobs.java`, `AnalysisJobDiskStore.java`, `LocalAnalysisRunner.java`, `AnalysisJobApi.java`, `ArchitectureQueryApi.java`):**
   - Added per-job `projectionProfile` (`"strict"` | `"dfd-export"`, HTTP `400` on invalid value, profile-scoped reuse keys + `409 IDEMPOTENCY_CONFLICT` guard), out-of-band `acquisitionSummary` (`{acquisitionAttempts, pomAttempts, projectionProfile}` keeping canonical graph SHA-256 bit-identical across Cold/Warm), and `/api/v1` + `/api/v1/health` discovery metadata (`mcpEndpoint`, `mcpProtocolVersion: "2025-11-25"`, `projectionProfiles`, `defaultProjectionProfile`).
7. **Online Web App UX & Turnkey Operator Docs (`index.html`, `app.js`, `mcp-scip-hub.js`, `repair-studio.js`, `ux.js`, [`docs/mcp-quickstart.md`](../mcp-quickstart.md), [`docs/ci-integration.md`](../ci-integration.md)):**
   - Added 1-click `#header-mcp-trigger` (`MCP · AI Agent`) modal with Claude Code / Cursor / Antigravity / Streamable HTTP (`2025-11-25`) copyable configs, 21-tool & 6-resource catalog + security posture cards, `#projection-profile` / `#connect-projection-profile` selectors + `#job-profile-pill`, and `.rs-rts-pill` (`SKG-RTS` vs `FULL_SUITE`) telemetry rendering.

---

## 2. `Batch 9 -> Batch 10 -> Batch 11` Controlled Ablation & 7-Tool `EMSE2025SAR-Replication` Head-to-Head (`17/17`)

### 2.1. Longitudinal Controlled Ablation (`Batch 9 -> Batch 10 -> Batch 11`, `17/17` Closed Denominator)

| Profile | Metric | Batch 9 (`E6`, `905b9e9c4b0f`) | Batch 10 (`E7`, `bde07d65cc27`) | Batch 11 (`E8`, `5cdb07ad0f6c`) | Total Gain (`B11 - B9`) |
| :--- | :--- | ---: | ---: | ---: | ---: |
| `strict` | Apps Analyzed (`PARTIAL`, `0` `ERROR`) | `17/17` | `17/17` | `17/17` | `+0` |
| `strict` | **`cold == warm1` / `steady` SHA-256** | `12/17` / `17/17` | **`17/17` / `17/17`** | **`17/17` / `17/17`** | **`+5` (`100%`)** |
| `strict` | Cold Median ± IQR (ms) | `37,603.0 ± 132,391.0` | `19,508.0 ± 25,538.0` | `48,825.0 ± 72,274.5` | `+11,222.0` ms *(deep pass)* |
| `strict` | Warm Median ± IQR (ms) | `4,474.0 ± 20,588.0` | `2,870.0 ± 1,999.0` | `6,305.0 ± 8,662.5` | `+1,831.0` ms (`7.7×` warm speedup) |
| `strict` | Strict-Norm COMP uF1 / mF1 (`TP/FP/FN`) | `0.5760` / `0.5581` (`108/85/74`) | `0.8629` / `0.8555` (`151/17/31`) | `0.8953` / `0.8794` (`154/8/28`) | `+0.3193` / `+0.3213` |
| `strict` | Canonical COMP uF1 / mF1 (`TP/FP/FN`) | `0.7258` / `0.7388` (`131/51/48`) | `0.8870` / `0.8871` (`153/13/26`) | `0.9150` / `0.9076` (`156/6/23`) | `+0.1892` / `+0.1688` |
| `strict` | **Collapsed COMP uF1 / mF1 (`TP/FP/FN`)** | `0.8045` / `0.8086` (`142/32/37`) | `0.8947` / `0.8938` (`153/10/26`) | **`0.9204` / `0.9123` (`156/4/23`, `uP=97.50%`)** | **`+0.1159` / `+0.1037`** |
| `strict` | Strict-Norm CONN uF1 / mF1 (`TP/FP/FN`) | `0.1395` / `0.1044` (`41/162/344`) | `0.3780` / `0.3727` (`127/160/258`) | `0.4489` / `0.4507` (`156/154/229`) | `+0.3094` / `+0.3463` |
| `strict` | Canonical CONN uF1 / mF1 (`TP/FP/FN`) | `0.2153` / `0.1661` (`62/131/321`) | `0.4000` / `0.4213` (`134/153/249`) | `0.4733` / `0.4998` (`164/146/219`) | `+0.2580` / `+0.3337` |
| `strict` | **Collapsed CONN uF1 / mF1 (`TP/FP/FN`)** | `0.2226` / `0.1792` (`64/128/319`) | `0.4030` / `0.4238` (`135/152/248`) | **`0.4733` / `0.4998` (`164/146/219`)** | **`+0.2507` / `+0.3206`** |
| `dfd-export` | Apps Analyzed (`PARTIAL`, `0` `ERROR`) | `17/17` | `17/17` | `17/17` | `+0` |
| `dfd-export` | **`cold == warm1` / `steady` SHA-256** | `13/17` / `17/17` | **`17/17` / `17/17`** | **`17/17` / `17/17`** | **`+4` (`100%`)** |
| `dfd-export` | Cold Median ± IQR (ms) | `37,317.0 ± 55,431.5` | `19,445.0 ± 25,782.0` | `47,759.0 ± 75,021.5` | `+10,442.0` ms |
| `dfd-export` | Warm Median ± IQR (ms) | `4,216.0 ± 17,995.0` | `2,723.0 ± 1,835.0` | `5,821.0 ± 8,682.0` | `+1,605.0` ms (`8.2×` warm speedup) |
| `dfd-export` | Strict-Norm COMP uF1 / mF1 (`TP/FP/FN`) | `0.6272` / `0.6148` (`122/85/60`) | `0.8747` / `0.8701` (`164/29/18`) | `0.9415` / `0.9340` (`169/8/13`) | `+0.3143` / `+0.3192` |
| `dfd-export` | Canonical COMP uF1 / mF1 (`TP/FP/FN`) | `0.7634` / `0.7738` (`142/51/37`) | `0.8943` / `0.8950` (`165/25/14`) | `0.9577` / `0.9570` (`170/6/9`) | `+0.1943` / `+0.1832` |
| `dfd-export` | **Collapsed COMP uF1 / mF1 (`TP/FP/FN`)** | `0.8375` / `0.8412` (`152/32/27`) | `0.9016` / `0.9012` (`165/22/14`) | **`0.9632` / `0.9617` (`170/4/9`, `uP=97.70%`)** | **`+0.1257` / `+0.1205`** |
| `dfd-export` | Strict-Norm CONN uF1 / mF1 (`TP/FP/FN`) | `0.1880` / `0.1449` (`66/251/319`) | `0.6859` / `0.6903` (`286/163/99`) | `0.8949` / `0.8691` (`349/46/36`) | `+0.7069` / `+0.7242` |
| `dfd-export` | **Canonical CONN uF1 / mF1 (`TP/FP/FN`)** | `0.2599` / `0.2082` (`89/213/294`) | `0.7244` / `0.7619` (`301/147/82`) | **`0.9266` / `0.9343` (`360/34/23`)** | **`+0.6667` / `+0.7261`** |
| `dfd-export` | **Collapsed CONN uF1 / mF1 (`TP/FP/FN`)** | `0.2673` / `0.2217` (`91/207/292`) | `0.7253` / `0.7627` (`301/146/82`) | **`0.9266` / `0.9343` (`360/34/23`, `uR=93.99%`)** | **`+0.6593` / `+0.7126`** |

### 2.2. Head-to-Head Comparison vs All 7 `EMSE2025SAR-Replication` Tools (`17/17` Closed Denominator)

| Tool / Configuration | Apps Done | COMP TP / FP / FN | COMP uP | COMP uR | **COMP uF1** | COMP mP | COMP mR | **COMP mF1** | CONN TP / FP / FN | CONN uP | CONN uR | **CONN uF1** | CONN mP | CONN mR | **CONN mF1** |
| :--- | :---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`Evo[dfd-export]` (Collapsed, B11)** | **17/17** | 170 / 4 / 9 | 0.9770 | 0.9497 | **0.9632** | 0.9844 | 0.9480 | **0.9617** | 360 / 34 / 23 | 0.9137 | **0.9399** | **0.9266** | 0.9428 | **0.9355** | **0.9343** |
| **`Evo[dfd-export]` (Canonical, B11)** | **17/17** | 170 / 6 / 9 | 0.9659 | 0.9497 | **0.9577** | 0.9752 | 0.9480 | **0.9570** | 360 / 34 / 23 | 0.9137 | **0.9399** | **0.9266** | 0.9428 | **0.9355** | **0.9343** |
| `Evo[dfd-export]` (Strict-Norm, B11) | 17/17 | 169 / 8 / 13 | 0.9548 | 0.9286 | 0.9415 | 0.9569 | 0.9221 | 0.9340 | 349 / 46 / 36 | 0.8835 | 0.9065 | 0.8949 | 0.8797 | 0.8689 | 0.8691 |
| **`Evo[strict]` (Collapsed, B11)** | **17/17** | 156 / 4 / 23 | **0.9750** | 0.8715 | **0.9204** | **0.9833** | 0.8607 | **0.9123** | 164 / 146 / 219 | 0.5290 | 0.4282 | **0.4733** | 0.5759 | 0.4516 | **0.4998** |
| `Evo[strict]` (Canonical, B11) | 17/17 | 156 / 6 / 23 | 0.9630 | 0.8715 | 0.9150 | 0.9733 | 0.8607 | 0.9076 | 164 / 146 / 219 | 0.5290 | 0.4282 | 0.4733 | 0.5759 | 0.4516 | 0.4998 |
| `Evo[strict]` (Strict-Norm, B11) | 17/17 | 154 / 8 / 28 | 0.9506 | 0.8462 | 0.8953 | 0.9512 | 0.8282 | 0.8794 | 156 / 154 / 229 | 0.5032 | 0.4052 | 0.4489 | 0.5193 | 0.4076 | 0.4507 |
| `code2DFD` *(EMSE2025SAR)* | 17/17 | 178 / 0 / 4 | 1.0000 | 0.9780 | 0.9889 | 1.0000 | 0.9755 | 0.9869 | 320 / 0 / 65 | 1.0000 | 0.8312 | 0.9078 | 1.0000 | 0.8554 | 0.9151 |
| `attack-graph-generator` *(EMSE2025SAR)* | 12/17 | 123 / 0 / 21 | 1.0000 | 0.8542 | 0.9213 | 1.0000 | 0.8643 | 0.9219 | 279 / 1 / 45 | 0.9964 | 0.8611 | 0.9238 | 0.9833 | 0.8550 | 0.9031 |
| `MicroDepGraph` *(EMSE2025SAR)* | 10/17 | 84 / 0 / 26 | 1.0000 | 0.7636 | 0.8660 | 1.0000 | 0.7683 | 0.8641 | 124 / 0 / 121 | 1.0000 | 0.5061 | 0.6721 | 1.0000 | 0.5139 | 0.6535 |
| `microMiner` *(EMSE2025SAR)* | 5/17 | 32 / 0 / 25 | 1.0000 | 0.5614 | 0.7191 | 0.8000 | 0.5695 | 0.6586 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `Prophet` *(EMSE2025SAR)* | 17/17 | 27 / 0 / 155 | 1.0000 | 0.1484 | 0.2584 | 0.5294 | 0.1608 | 0.2372 | 4 / 0 / 381 | 1.0000 | 0.0104 | 0.0206 | 0.1765 | 0.0261 | 0.0418 |
| `RAD` *(EMSE2025SAR)* | 13/17 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0 / 0 / 259 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `RAD-source` *(EMSE2025SAR)* | 17/17 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 7 / 0 / 378 | 1.0000 | 0.0182 | 0.0357 | 0.2353 | 0.0341 | 0.0551 |

**Headline SOTA milestone (`CONFIRMED`):**
- On inter-service connections (`CONN`), **`Evo[dfd-export]` (`uF1 = 0.9266`, `mF1 = 0.9343`, `uR = 93.99%`, `360/34/23` — identical across both Canonical and Collapsed tiers!) now surpasses ALL 7 competing tools in `EMSE2025SAR-Replication`**—exceeding both `code2DFD` (`0.9078` uF1 / `0.9151` mF1, `320` TP vs Evo's `360` TP) and `attack-graph-generator` (`0.9238` uF1 / `0.9031` mF1 on only `12/17` apps)—while operating at `17/17` (`100%`) coverage, `17/17` (`100%`) `cold == warm1 == warm2` SHA-256 determinism, passive `--build=off` host safety, and `I5_MATCHES=0`.
- On components (`COMP`), **`Evo[dfd-export]` achieves `0.9632` uF1 / `0.9617` mF1 (`170/4/9`)**—surpassing 6 of 7 tools (`10/17` apps at `1.0000` COMP F1; **`5/17` apps at perfect `1.0000` COMP / `1.0000` CONN**; `14/17` apps at `0` COMP FPs)—while **`Evo[strict]`** preserves canonical evidence-only precision at **`uP = 97.50%` (`mP = 98.33%`, `uF1 = 0.9204`, only `4` FPs across all 17 apps)**.

### 2.3. Complete `17/17` Batch 11 Per-App Table (`strict` & `dfd-export`, Verbatim from `e6-batch11/e6_summary.json`)

#### `Evo[strict]` (`EVO_PROJECTION_PROFILE=strict`) — All 17 Applications

| # | Repository (`app_id`) | Build | Cold (ms) | Warm (ms) | `c==w1`/`st` | Gaps (`c/w1`) | Canon COMP (`TP/FP/FN`, F1) | Collapsed COMP (`TP/FP/FN`, F1) | Canon CONN (`TP/FP/FN`, F1) | Collapsed CONN (`TP/FP/FN`, F1) |
| ---: | :--- | :---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `anilallewar/microservices-basics-spring-boot` | gradle | 48,825 | 5,095 | `T / T` | 1258 / 1258 | `11/2/1` (0.8800) | `11/1/1` (0.9167) | `7/12/22` (0.2917) | `7/12/22` (0.2917) |
| 2 | `apssouza22/java-microservice` | maven | 163,636 | 19,396 | `T / T` | 1947 / 1947 | `14/0/0` (1.0000) | `14/0/0` (1.0000) | `19/19/15` (0.5278) | `19/19/15` (0.5278) |
| 3 | `callistaenterprise/blog-microservices` | gradle | 41,266 | 4,782 | `T / T` | 1155 / 1155 | `15/2/2` (0.8824) | `15/2/2` (0.8824) | `21/12/21` (0.5600) | `21/12/21` (0.5600) |
| 4 | `ewolff/microservice` | maven | 38,293 | 14,214 | `T / T` | 1081 / 1081 | `6/0/1` (0.9231) | `6/0/1` (0.9231) | `6/2/7` (0.5714) | `6/2/7` (0.5714) |
| 5 | `ewolff/microservice-kafka` | maven | 83,156 | 6,235 | `T / T` | 854 / 854 | `7/0/1` (0.9333) | `7/0/1` (0.9333) | `4/8/8` (0.3333) | `4/8/8` (0.3333) |
| 6 | `fernandoabcampos/spring-netflix-oss-microservices` | maven | 18,294 | 2,679 | `T / T` | 712 / 712 | `10/0/1` (0.9524) | `10/0/1` (0.9524) | `7/12/17` (0.3256) | `7/12/17` (0.3256) |
| 7 | `georgwittberger/apache-spring-boot-microservice-example` | maven | 14,649 | 2,398 | `T / T` | 387 / 387 | `4/0/1` (0.8889) | `4/0/1` (0.8889) | `4/0/2` (0.8000) | `4/0/2` (0.8000) |
| 8 | `jferrater/tap-and-eat-microservices` | gradle | 80,094 | 7,863 | `T / T` | 1043 / 1043 | `9/0/0` (1.0000) | `9/0/0` (1.0000) | `9/7/7` (0.5625) | `9/7/7` (0.5625) |
| 9 | `koushikkothagal/spring-boot-microservices-workshop` | maven | 71,564 | 7,458 | `T / T` | 387 / 387 | `4/0/1` (0.8889) | `4/0/1` (0.8889) | `5/0/1` (0.9091) | `5/0/1` (0.9091) |
| 10 | `mdeket/spring-cloud-movie-recommendation` | maven | 103,094 | 14,221 | `T / T` | 832 / 832 | `7/0/3` (0.8235) | `7/0/3` (0.8235) | `6/7/11` (0.4000) | `6/7/11` (0.4000) |
| 11 | `mudigal-technologies/microservices-sample` | maven | 72,764 | 7,544 | `T / T` | 986 / 986 | `14/0/1` (0.9655) | `14/0/1` (0.9655) | `10/7/24` (0.3922) | `10/7/24` (0.3922) |
| 12 | `piomin/sample-spring-oauth2-microservices` | maven | 24,648 | 3,390 | `T / T` | 403 / 403 | `4/0/4` (0.6667) | `4/0/4` (0.6667) | `4/2/9` (0.4211) | `4/2/9` (0.4211) |
| 13 | `rohitghatol/spring-boot-microservices` | maven | 20,934 | 3,328 | `T / T` | 1000 / 1000 | `9/1/2` (0.8571) | `9/0/2` (0.9000) | `7/12/19` (0.3111) | `7/12/19` (0.3111) |
| 14 | `shabbirdwd53/springboot-microservice` | maven | 18,720 | 6,305 | `T / T` | 1028 / 1028 | `8/0/0` (1.0000) | `8/0/0` (1.0000) | `9/6/8` (0.5625) | `9/6/8` (0.5625) |
| 15 | `spring-petclinic/spring-petclinic-microservices` | maven | 122,937 | 10,356 | `T / T` | 1583 / 1583 | `11/1/1` (0.9167) | `11/1/1` (0.9167) | `7/18/21` (0.2642) | `7/18/21` (0.2642) |
| 16 | `sqshq/piggymetrics` | maven | 139,425 | 13,687 | `T / T` | 1512 / 1512 | `14/0/3` (0.9032) | `14/0/3` (0.9032) | `16/17/21` (0.4571) | `16/17/21` (0.4571) |
| 17 | `yidongnan/spring-cloud-netflix-example` | maven | 20,767 | 2,267 | `T / T` | 730 / 730 | `9/0/1` (0.9474) | `9/0/1` (0.9474) | `23/5/6` (0.8070) | `23/5/6` (0.8070) |
| **Agg** | **`17/17` Micro / Macro (Median Cold / Warm)** | **14M/3G** | **48,825** | **6,305** | **17 / 17** | — | **`156/6/23` (u0.9150 / m0.9076)** | **`156/4/23` (u0.9204 / m0.9123)** | **`164/146/219` (u0.4733 / m0.4998)** | **`164/146/219` (u0.4733 / m0.4998)** |

#### `Evo[dfd-export]` (`EVO_PROJECTION_PROFILE=dfd-export`) — All 17 Applications

| # | Repository (`app_id`) | Build | Cold (ms) | Warm (ms) | `c==w1`/`st` | Gaps (`c/w1`) | Canon COMP (`TP/FP/FN`, F1) | Collapsed COMP (`TP/FP/FN`, F1) | Canon CONN (`TP/FP/FN`, F1) | Collapsed CONN (`TP/FP/FN`, F1) |
| ---: | :--- | :---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `anilallewar/microservices-basics-spring-boot` | gradle | 47,759 | 5,119 | `T / T` | 1258 / 1258 | `12/2/0` (0.9231) | `12/1/0` (0.9600) | `29/4/0` (0.9355) | `29/4/0` (0.9355) |
| 2 | `apssouza22/java-microservice` | maven | 163,577 | 18,064 | `T / T` | 1947 / 1947 | `14/0/0` (1.0000) | `14/0/0` (1.0000) | `32/9/2` (0.8533) | `32/9/2` (0.8533) |
| 3 | `callistaenterprise/blog-microservices` | gradle | 41,117 | 4,323 | `T / T` | 1155 / 1155 | `16/2/1` (0.9143) | `16/2/1` (0.9143) | `40/5/2` (0.9195) | `40/5/2` (0.9195) |
| 4 | `ewolff/microservice` | maven | 38,774 | 14,630 | `T / T` | 1081 / 1081 | **`7/0/0` (1.0000)** | **`7/0/0` (1.0000)** | **`13/0/0` (1.0000)** | **`13/0/0` (1.0000)** |
| 5 | `ewolff/microservice-kafka` | maven | 88,308 | 5,821 | `T / T` | 854 / 854 | **`8/0/0` (1.0000)** | **`8/0/0` (1.0000)** | **`12/0/0` (1.0000)** | **`12/0/0` (1.0000)** |
| 6 | `fernandoabcampos/spring-netflix-oss-microservices` | maven | 16,163 | 2,533 | `T / T` | 712 / 712 | `11/0/0` (1.0000) | `11/0/0` (1.0000) | `24/2/0` (0.9600) | `24/2/0` (0.9600) |
| 7 | `georgwittberger/apache-spring-boot-microservice-example` | maven | 14,243 | 2,343 | `T / T` | 387 / 387 | **`5/0/0` (1.0000)** | **`5/0/0` (1.0000)** | **`6/0/0` (1.0000)** | **`6/0/0` (1.0000)** |
| 8 | `jferrater/tap-and-eat-microservices` | gradle | 79,848 | 7,140 | `T / T` | 1043 / 1043 | **`9/0/0` (1.0000)** | **`9/0/0` (1.0000)** | **`16/0/0` (1.0000)** | **`16/0/0` (1.0000)** |
| 9 | `koushikkothagal/spring-boot-microservices-workshop` | maven | 66,617 | 7,737 | `T / T` | 387 / 387 | **`5/0/0` (1.0000)** | **`5/0/0` (1.0000)** | **`6/0/0` (1.0000)** | **`6/0/0` (1.0000)** |
| 10 | `mdeket/spring-cloud-movie-recommendation` | maven | 105,754 | 14,587 | `T / T` | 832 / 832 | `7/0/3` (0.8235) | `7/0/3` (0.8235) | `13/0/4` (0.8667) | `13/0/4` (0.8667) |
| 11 | `mudigal-technologies/microservices-sample` | maven | 75,171 | 7,596 | `T / T` | 986 / 986 | `15/0/0` (1.0000) | `15/0/0` (1.0000) | `33/1/1` (0.9706) | `33/1/1` (0.9706) |
| 12 | `piomin/sample-spring-oauth2-microservices` | maven | 24,083 | 3,442 | `T / T` | 403 / 403 | `5/0/3` (0.7692) | `5/0/3` (0.7692) | `8/0/5` (0.7619) | `8/0/5` (0.7619) |
| 13 | `rohitghatol/spring-boot-microservices` | maven | 21,862 | 3,329 | `T / T` | 1000 / 1000 | `10/1/1` (0.9091) | `10/0/1` (0.9524) | `23/2/3` (0.9020) | `23/2/3` (0.9020) |
| 14 | `shabbirdwd53/springboot-microservice` | maven | 19,216 | 5,432 | `T / T` | 1028 / 1028 | `8/0/0` (1.0000) | `8/0/0` (1.0000) | `16/0/1` (0.9697) | `16/0/1` (0.9697) |
| 15 | `spring-petclinic/spring-petclinic-microservices` | maven | 119,358 | 10,634 | `T / T` | 1585 / 1585 | `12/1/0` (0.9600) | `12/1/0` (0.9600) | `26/7/2` (0.8525) | `26/7/2` (0.8525) |
| 16 | `sqshq/piggymetrics` | maven | 136,155 | 13,501 | `T / T` | 1512 / 1512 | `16/0/1` (0.9697) | `16/0/1` (0.9697) | `36/2/1` (0.9600) | `36/2/1` (0.9600) |
| 17 | `yidongnan/spring-cloud-netflix-example` | maven | 22,157 | 2,254 | `T / T` | 730 / 730 | `10/0/0` (1.0000) | `10/0/0` (1.0000) | `27/2/2` (0.9310) | `27/2/2` (0.9310) |
| **Agg** | **`17/17` Micro / Macro (Median Cold / Warm)** | **14M/3G** | **47,759** | **5,821** | **17 / 17** | — | **`170/6/9` (u0.9577 / m0.9570)** | **`170/4/9` (u0.9632 / m0.9617)** | **`360/34/23` (u0.9266 / m0.9343)** | **`360/34/23` (u0.9266 / m0.9343)** |

*(Remaining `4` COMP FPs across all 17 apps under `Evo[dfd-export]` Collapsed: `#1 zipkin`, `#3 gitrepository` + `util`, `#15 genai` [live upstream service added after GT freeze]. All `7` CONN FPs on `#15 spring-petclinic` are likewise genuine live-HEAD `genai` edges; `8/17` apps have `0` CONN FPs and `13/17` apps exceed `0.9000` CONN F1.)*

---

## 3. `E8` Flagship Polyglot & Multi-Framework Results + True 3-Way Timing Decomposition

All `E8` experiments ran on the final Batch 11 Linux Docker image `evo-workbench-online:latest` (`sha256:5cdb07ad0f6c...`, zero host `-v` bind mounts) and are persisted in `D:\UIT\evo-benchmark-cache\results\e8-flagship-polyglot\e8_summary.json`.

### 3.1. Flagship Polyglot & Multi-Framework Online Docker Benchmark (`Part 1`)

| Flagship Repository | Container Budget | Status | Cold `strict` (ms) | Warm `strict` (ms) | `dfd-export` (ms) | `c==w1` / `steady` | Gaps | Contexts / Total Nodes | Build / Infra Comps | Service Edges (`strict` / `dfd`) | Framework / Language Semantics Extracted |
| :--- | :---: | :---: | ---: | ---: | ---: | :---: | ---: | :---: | :---: | :---: | :--- |
| **Online Boutique** (`GoogleCloudPlatform/microservices-demo`, 5-lang gRPC) | `3500m`, `-Xmx2560m` | `PARTIAL` | 10,755 | 5,014 | 5,939 | `True` / `True` | 258 | `2` (`repo:1, java:1`) / `29,525` sum (`28,519` whole) | `11` / `4` | **`48`** (`27` HTTP + `21` RPC) / **`50`** (`29` HTTP + `21` RPC) | **`40` `PROTOBUF_SERVICE`**, **`66` `PROTOBUF_RPC_METHOD`**, `140` `PROTOBUF_MESSAGE`, `279` `PROTOBUF_FIELD`, `15` `ROUTE_CANDIDATE`, `230` `SOURCE_DOCUMENT` |
| **TeaStore** (`DescartesResearch/TeaStore`, Java JAX-RS + Servlet) | `3500m`, `-Xmx2560m` | `PARTIAL` | 23,908 | 7,547 | 6,808 | `True` / `True` | 1,567 | `12` (`repo:1, java:11`) / `49,583` sum (`7,449` whole) | `15` / `13` | **`33`** / **`41`** (`CALLS_SERVICE`) | **`48` `ENDPOINT`** (`28` `jaxrs.endpoint-declaration` + `20` `servlet.endpoint-declaration`), `600` `TYPE`, `1,256` `METHOD` |
| **Shopizer** (`shopizer-ecommerce/shopizer`, Large Multi-Module Spring Boot) | `5500m`, `-Xmx4g` *(isolated)* | `PARTIAL` | 257,462 | 51,164 | 39,537 | `True` / `True` | 48,296 | `10` (`repo:1, java:6, spring:3`) / `406,588` sum (`52,445` whole) | `6` / `1` | **`1`** / **`1`** (`CALLS_SERVICE`) | **`36` `ENDPOINT`** (`spring.endpoint-declaration`), **`10,120` `CONDITIONAL_FACT`**, **`10,120` `TRUTH_REGION`**, `88` `BEAN_DEFINITION_CANDIDATE`, `2,034` `TYPE`, `5,867` `METHOD` |

**Honest operational disclosure on Shopizer (`CONFIRMED`):**
- Running Shopizer 3rd inside a shared `--memory=3500m -Xmx2560m` container after Online Boutique + TeaStore populated the 2-hot-graph RAM cache (`3.266 GiB / 3.418 GiB`, `95.55%`) triggered `OutOfMemoryError` -> `FAILED (SEMANTIC_WORKER_REQUIRED)` at the `GRAPH` stage (`253,649 ms` cold / `43,224 ms` warm / `38,627 ms` dfd). Isolating Shopizer in a dedicated `--memory=5500m -Xmx4g` container completed cleanly (`PARTIAL`, `cold==w1=True`, `406,588` nodes, `10,120` conditional facts & truth regions).
- Shopizer Cold elapsed (`257,462 ms`) honestly exceeds `60 s` due to live online Maven dependency resolution (`2,136` acquisition attempts + `68` POM attempts), while Warm (`51,164 ms`) and DFD (`39,537 ms`) complete under `60 s`; `gapCount=48,296` (down `~35%` vs historical `74.5k`) remains an honest large-repo resolution horizon.

### 3.2. True 3-Way Timing Decomposition (`Part 2`, `ewolff/microservice`, `n=3` Independent Repeats)

- **Setup & Honest Mode Disclosure:** Target `ewolff/microservice` pinned at Commit A (`d1ccf504df5617288479902ad9eb2111df73ed65`) with a deterministic 1-file Java edit adding `private String evoBenchEditMarker()` in `microservice-demo/microservice-demo-order/src/main/java/com/ewolff/microservice/order/logic/OrderController.java` -> Commit B (`1ac1aa69e6d38d886c365825006a96ba5c47e7a8`, verified identical Commit B SHA in clean containers). Executed in `container_local_overlayfs` (`EVO_ONLINE_MODE=false` on the exact `evo-workbench-online:latest` `5cdb07ad0f6c` image, zero host `-v` mounts) because `--online` `GitHostPolicy` strictly enforces public HTTPS/SSH and blocks RFC1918/loopback hosts (zero SSRF bypass).

| Timing Stage (`strict`, `n=3` fresh containers) | Median ± IQR (ms) | Min .. Max (ms) | Speedup vs Cold | SHA-256 Digest Parity Verification (`3/3`) |
| :--- | ---: | ---: | :---: | :--- |
| **1. `Cold (Commit A)`** | **`29,199.0 ± 1,459.0`** | `28,096.0 .. 29,555.0` | `1.00×` | Baseline Commit A (`sha256:ea6920112295...`, `1,081` gaps) |
| **2. `Warm NO_CHANGE (Commit A, force=true)`** | **`10,155.0 ± 342.0`** | `9,838.0 .. 10,180.0` | **`2.88×`** | **`3/3` (`100%`) bit-identical** to `Cold (Commit A)` (`sha256:ea6920112295...`) |
| **3. `Warm 1-File Edit (Commit B, force=true)`** | **`21,104.0 ± 461.0`** | `20,756.0 .. 21,217.0` | **`1.38×`** | **`3/3` (`100%`) genuinely changed** (`sha256:ea6920112295... -> sha256:f76acf263255...`) |
| **4. `Cold on Edited (Commit B, fresh container)`** | **`28,326.0 ± 382.0`** | `28,216.0 .. 28,598.0` | `1.03×` | **`3/3` (`100%`) bit-identical** to `Warm 1-File Edit` (`sha256:f76acf263255...`) |

---

## 4. `E5-v2` Scaled Spring Framework 6.2 Runtime Differential Soundness Oracle (`24` Scenarios / `345` Worlds / `67` Beans)

### 4.1. Second Real `False-MUST` Soundness Defect Discovered & Eliminated (`CONFIRMED`)

Expanding [`SpringDifferentialOracleTest.java`](../../backend/src/test/java/com/evolution/backend/analysis/SpringDifferentialOracleTest.java) from `6` to `24` scenarios against live **Spring Framework 6.2 `AnnotationConfigApplicationContext`** immediately caught a **second genuine static-analysis soundness bug (`False-MUST`)** in [`SourceToSpringPlan.java`](../../analyzer/src/main/java/com/evolution/analysis/spring/universal/SourceToSpringPlan.java) (`#98`, pre-fix `soundness=98.4%`, `falseMust=1`):

- **Failing Witness (`ChainedMissingBeanFallbackOrder`, `12` worlds):** `RedisCacheProvider` (`@Order(10) @Profile("redis")`), `CaffeineCacheProvider` (`@Order(20) @ConditionalOnProperty(...) @ConditionalOnMissingBean(name="redisCache")`), and `NoOpCacheProvider` (`@Order(30) @ConditionalOnMissingBean(name={"redisCache","caffeineCache"})`). Live Spring 6.2 classified `noOpCache` as **`MAY`**, whereas Evo classified it as **`MUST`**.
- **Two Coupled Root Causes Fixed in `SourceToSpringPlan.java`:**
  1. `annotationOrder(...)` called `LiteralConditionAnnotation.parse("@Order(10)")`, which did not parse integer literals (`10`, `20`, `30`, `-5`) and threw `IllegalArgumentException`, silently dropping explicit `@Order(N)` priorities.
  2. When multiple non-monotone `@ConditionalOnMissingBean` candidates shared an equal or unspecified `@Order` tier, tie-breaking by SHA-256 identity hash fabricated a deterministic evaluation order (`order = COMPLETE`), enabling `False-MUST`/`False-NEVER`.
- **Fix & Regression Proof:** Added integer-literal `@Order` parsing (including negative integers and `Ordered.*` constants) and fail-closed `order = UNKNOWN` whenever any `spring.condition.bean-state` candidate shares an equal/unspecified tier or has an unparseable `@Order`. Verified by `explicitOrderRecoveryAndOmittedOrderEqualTierFailClosedAreBoth100PercentSound` (`ChainedMissingBeanFallbackOrder`: `3/3` exact; `OmittedOrderEqualTierFailClosed`: `3/3` safe `UNKNOWN`, `0` False-MUST).

### 4.2. Verbatim `E5` Baseline vs `E5-v2` Scaled Oracle Metrics

| Benchmark Suite | Scenarios | Concrete Worlds | Checked Beans | Exact Match | Safe `UNKNOWN` | False-MUST | False-NEVER | **Soundness** | CDCL Median ± IQR | Real Starter JAR COI (`spring-boot-autoconfigure-3.5.14.jar`) |
| :--- | ---: | ---: | ---: | :---: | ---: | ---: | ---: | :---: | :---: | :--- |
| **`E5` Baseline** ([`E5`](./E5-Spring-SAT-Oracle-And-Ablation-Report.md)) | 6 | 59 | 13 | `11` (`84.6%`) | 2 | **0** | **0** | **`100.0%`** | — | `542` targets, undirected max `415` -> directed max `14` |
| **`E5-v2` Scaled Oracle** *(post-fix)* | **24** | **345** | **67** | **`54` (`80.6%`)** | **13** | **0** | **0** | **`100.0%`** | **`0.013 ± 0.014` ms** | **`542` targets, undirected max `415` -> directed max `14` in `8.313` ms** |

*(Representative `E5-v2` categories: `ConfigUserOverridesAutoOrder` `8` worlds `3/3` exact; `ChainedMissingBeanFallbackOrder` `12` worlds `3/3` exact; `OmittedOrderEqualTierFailClosed` `12` worlds `3/3` safe `UNKNOWN`; `BytecodeComposedAliasFor` `18` worlds `3/3` exact; `RealBoot3514StarterCoi` `36` worlds `4/4` exact.)*

---

## 5. `E9` AI4SE Empirical Evaluation Suite (`SpringScaleAndAi4seBenchmarkTest`, Zero-Network Deterministic)

Measured verbatim in [`SpringScaleAndAi4seBenchmarkTest.java`](../../backend/src/test/java/com/evolution/backend/analysis/SpringScaleAndAi4seBenchmarkTest.java) (`3/3` PASS, `2.538 s` in `#113`):

| AI4SE Pillar | Verbatim Benchmark Metrics | Key Guarantee |
| :--- | :--- | :--- |
| **`[E9-SKG-RTS]`** (Graph Regression Test Selection) | `architectures=5`, `totalModules=46`, `totalTestClasses=90`, `codeEdits=21`, **`overallReductionPct=86.19%`**, **`missedDependentTests=0`**, **`closureSoundnessPct=100.0%`**, **`safetyFallbackTriggers=7/7 (100.0%)`** | `100%` transitive recall with fail-closed `FULL_SUITE` fallback on `pom.xml`/config/unmapped edits |
| **`[E9-QUARANTINE]`** (Advisory Graph Quarantine Invariance) | `canonicalGraphSha256Match=true`, `analysisSubgraphSha256Match=true`, `leidenPartitionMatch=true` (`communities=3`, **`modularity=0.6136`**), **`scoreV2=96.29`**, `advisoryNodes: 9 -> 19`, `advisoryLinks: 4 -> 19`, **`canonicalLeakage=0`** | AI/advisory graph writes cannot mutate canonical SHA-256, Leiden communities, or `scoreV2` |
| **`[E9-REDACT]`** (`llm-redact:v2` Secret Redaction) | `secretPatterns=15`, **`redacted=15 (100.0%)`**, **`leaked=0`**, **`benignPreserved=12/12`**, **`falsePositives=0`**, **`diskLeaks=0`** | `100%` secret redaction before LLM/MCP egress with zero benign corruption |
| **`[E9-RRF]`** (`ppr-bm25-trigram-rrf-v2` Hybrid Retrieval) | `files=48`, `queries=12`: **Hybrid Recall@5 = `100.0%`**, **Recall@10 = `100.0%`** vs BM25-only (`58.3%` / `75.0%`, **`+41.7 pp` @5**), No-BM25 (`70.8%` / `91.7%`, **`+29.2 pp` @5**), Unseeded-PPR (`20.8%` / `52.8%`) | Hybrid graph-seeded PPR + BM25 + trigram RRF dominates all single-signal ablations |

---

## 6. Live Online Docker Product E2E Proof (`live_product_e2e.json`, Image `5cdb07ad0f6c`)

Verified end-to-end against a single live Linux container (`evo-product-e2e` on `http://127.0.0.1:18089`, zero `-v` mounts, removed post-test, persisted in `D:\UIT\evo-benchmark-cache\results\e8-flagship-polyglot\live_product_e2e.json`):

1. **Single-Container Per-Job `projectionProfile` Isolation & Out-of-Band `acquisitionSummary`** (`georgwittberger/apache-spring-boot-microservice-example.git`, `setup=repo-1c581bfc7e5ed0d3ae81e864`):
   - **Job 1 (`strict`, `force=false`):** `PARTIAL`, `40,656 ms`, `acquisitionSummary={acquisitionAttempts:784, pomAttempts:48, projectionProfile:"strict"}`, `strictGraphId=sha256:460886a8d4d2...`, `gapCount=387`.
   - **Job 2 (`dfd-export`, `force=false`):** `PARTIAL`, `4,330 ms`, `acquisitionSummary={acquisitionAttempts:30, pomAttempts:36, projectionProfile:"dfd-export"}`, `dfdGraphId=sha256:5527477ee005...` (**`strictGraphId != dfdGraphId`**, zero cross-profile cache collision).
   - **Job 3 (`strict`, `force=true`):** `PARTIAL`, `2,272 ms`, `acquisitionSummary={acquisitionAttempts:30, pomAttempts:36, projectionProfile:"strict"}`, **`graphId == strictGraphId`** (bit-identical despite `acquisitionAttempts` dropping `784 -> 30`!).
   - Negative guards: `POST /api/v1/analysis/connections {"source":"/etc"}` -> HTTP `403` (`LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE`); `POST /api/v1/analysis/jobs {"setupId":"missing","projectionProfile":"bogus"}` -> HTTP `400` (`INVALID_REQUEST`).
2. **Live Streamable HTTP `/mcp` (`2025-11-25`):**
   - `POST /mcp` with `Origin: https://evil.example` -> HTTP `403` (`FORBIDDEN_ORIGIN`).
   - `initialize` -> HTTP `200` (`protocolVersion="2025-11-25"`, `serverInfo="evo-workbench" v2.0.0`, `Mcp-Session-Id` returned); `notifications/initialized` -> `202`; `tools/list` -> **`21` tools**; `resources/list` -> **`6` `evo://` resources**; **`6/6` real `tools/call` PASS** (`graph_get` [dfd & strict], `graph_query` [summary], `get_architecture_score`, `architecture_insights`, `search_captured_source` — all HTTP `200`, `isError=false`); `DELETE /mcp` -> HTTP `200`.
3. **Live Headless Edge CDP (`Edg/158.0.4370.45`) on `http://127.0.0.1:18089/`:**
   - **`0` uncaught JS errors**, **`0` console errors**, **`0` HTTP 5xx responses**.
   - Both `#connect-projection-profile` and `#projection-profile` rendered `["strict", "dfd-export"]`.
   - Clicking `#header-mcp-trigger` opened `#mcp-setup-dialog` (`open=true`), rendered `http://127.0.0.1:18089/mcp`, `21` tool chips, security cards (`Scoped PAT Auth`, `Origin & DNS-Rebinding Guard`, `Shannon-Entropy Secret Redaction`), confirmed `"Copy config" -> "Copied!"`, closed cleanly (`open=false`), and displayed the live analyzed repository.

---

## 7. Reproducibility & Immutable External Artifacts

- **Benchmark harnesses & docs in Git:** [`run_online_microsecend_benchmark.py`](../../benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py), [`corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json), [`Dockerfile`](../../Dockerfile), [`docs/mcp-quickstart.md`](../mcp-quickstart.md), [`docs/ci-integration.md`](../ci-integration.md).
- **External immutable JSON/CSV outputs (outside Git):**
  - Batch 11 `microSecEnD` `17/17`: `D:\UIT\evo-benchmark-cache\results\e6-batch11\{e6_summary.json,e6_per_app.csv,e6_checkpoint.json}` (`133,573 B` / `5,196 B` / `116,246 B`).
  - `E8` Flagship Polyglot + 3-Way Timing: `D:\UIT\evo-benchmark-cache\results\e8-flagship-polyglot\e8_summary.json` (`39,418 B`).
  - Live Online Docker Product E2E: `D:\UIT\evo-benchmark-cache\results\e8-flagship-polyglot\live_product_e2e.json` (`88,176 B`).
  - Historical baselines (untouched): `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_summary.json` (Batch 9), `D:\UIT\evo-benchmark-cache\results\e6-batch10\e6_summary.json` (Batch 10).
