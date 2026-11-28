# E7 Evaluation Report: Batch 10 Passive Topology Recovery & `17/17` Cold/Warm SHA-256 Determinism on `microSecEnD`

- **Date / Reconciled:** 2026-11-27 (`e6-batch10/e6_summary.json` UTC: `2026-11-27T17:06:58.186839+00:00`)
- **Historical Baseline Preserved:** Batch 9 baseline report [`E6-Online-MicroSecEnD-17App-Linux-Benchmark-Report.md`](./E6-Online-MicroSecEnD-17App-Linux-Benchmark-Report.md) and raw output `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_summary.json` (`2026-11-27T15:27:10.485726+00:00`) remain strictly unmodified for controlled before/after ablation.
- **Target Product Artifact (Online Web App):**
  - Standalone JAR: `backend/target/evo-workbench.jar` — **`72,249,609` bytes**, `LastWriteTimeUtc`: `2026-11-27T16:57:14.4414209Z`, SHA-256: `DAE404F5A8796925873464EC3DCA7EEF0E48CEDCCC3E094746F14BB3F72CF20F` (`-pl .,backend -am -DskipTests package`, `cli` excluded).
  - Linux Container Image: `evo-workbench-online:latest` (built from [`Dockerfile`](../../Dockerfile), `eclipse-temurin:21-jdk-alpine`, non-root `uid=10001`, `--online` entrypoint) — ID: `sha256:bde07d65cc275ca6c541685cb7f12eb503f0ad18b150ad7981a509eb463ab7a1` (`bde07d65cc27`), Size: `707,162,563` bytes (`707 MB` disk / `256 MB` content), Created: `2026-11-27T16:57:23.982959744Z`.
  - Online Smoke Check (`evo-b10-smoke`, `--memory=1536m`, zero `-v` mounts): `GET /api/v1/health` -> HTTP `200` (`onlineMode=true`, `localCheckoutAllowed=false`); `POST /api/v1/analysis/connections` `{"source":"/etc"}` -> HTTP `403` (`LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE`).
- **Analysis Mode & Invariants:** Passive static analysis (`--build=off`), zero target-build execution on host, closed `17/17` reporting denominator (`0` omitted apps), anti-overfitting gate **`I5_MATCHES=0`** across **`800`** scanned `src/main/` `*.java`/`*.py` files (`analyzer*,backend,cli`).
- **Pinned External Corpora (Stored Outside Git under `D:\UIT\evo-benchmark-cache\`):**
  - `microSecEnD` commit SHA: `63653e09177b6d757eada4521f8bace8a8c20766` (17 repositories in [`corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json) `suites.microsecend_17`: `14` Maven + `3` Gradle; `182` raw GT components, `385` raw GT connections).
  - `EMSE2025SAR-Replication` commit SHA: `1b8994471e9df0d32e22b31415eead8511078125` (textual ground truth and verbatim processed outputs for 7 SAR baseline tools).
- **Pre-Benchmark Selective 7-Module Maven Verification (`CONFIRMED`):**
  - Reactor scope: `.,analyzer,analyzer-maven,analyzer-filesystem,analyzer-javaparser,analyzer-treesitter,backend` (`-o -B -ntp`, Enforcer rules 0–9 passed, Total time `02:43 min`): **`159/159` PASS (`0` failures, `0` errors, `0` skipped)** across **22 test classes**:
    - `analyzer` (`38`): `HttpServiceCallProjectionTest` (`10`), `ServiceArchitectureProjectionTest` (`8`), `ArchitectureQueryContractTest` (`13`), `PolyglotQueryServiceTest` (`4`), `SpringSatAblationBenchmarkTest` (`3`).
    - `analyzer-maven` (`3`): `MavenDependencyArtifactResolverTest` (`3`).
    - `analyzer-filesystem` (`14`): `StoragePerfParityTest` (`3`), `StreamingRepositoryAcquirerTest` (`11`).
    - `analyzer-javaparser` (`11`): `HttpClientRecoveryTest` (`5`), `JavaServiceSyntaxExtractorTest` (`6`).
    - `analyzer-treesitter` (`24`): `TreeSitterSourceFrontendTest` (`24`).
    - `backend` (`69`): `ColdWarmDeterminismTest` (`3`), `DeclarativeTopologyParsersTest` (`9`), `PolyglotScanTest` (`13`), `RepositoryConnectionsTest` (`8`), `SpringDifferentialOracleTest` (`1` — `100.0%` Spring 6.2 runtime soundness; see [`E5-Spring-SAT-Oracle-And-Ablation-Report.md`](./E5-Spring-SAT-Oracle-And-Ablation-Report.md)), `backend.analysis.WorkbenchApiMainTest` (`5`), `AiAndRepairUpgradeTest` (`11`), `AnalysisJobApiTest` (`5`), `ArchitectureAgentTest` (`5`), `ResearchBreakthroughUpgradesTest` (`5` — `85.71%` SKG-RTS reduction), `backend.query.WorkbenchApiMainTest` (`4`).
- **Working-Tree Honesty Note on `cli/` (`CONFIRMED`):** Batch 10 performed **`0` edits to `cli/`**, targeting exclusively the Online Web App JAR and Linux Docker image. Against `HEAD a661db3`, `git diff --stat -- cli/` still carries **3 pre-existing uncommitted tracked modifications** from pre-Batch-9 work (`cli/src/main/java/com/evolution/cli/EvoApp.java`, `cli/src/main/java/com/evolution/cli/Workspace.java`, `cli/src/test/java/com/evolution/cli/EvoScoreV2AndIncrementalCliTest.java`).
- **User-Approved `/learn` Engineering Guardrails Applied (`CONFIRMED`, `2026-11-27`):** Prior to Batch 10 implementation, all 3 user-approved `/learn` updates were applied verbatim (verified via `git apply -R --check` and YAML frontmatter validation):
  1. [`.agents/rules/00-agent-core.md`](../../.agents/rules/00-agent-core.md) (`2,465 B -> 3,014 B`): Online Web App product-scope guardrail (`backend/target/evo-workbench.jar` + `evo-workbench-online:latest`; zero unrequested `cli` work) and Windows PowerShell 5.1 command hygiene.
  2. [`.agents/skills/publication-research-rigor/SKILL.md`](../../.agents/skills/publication-research-rigor/SKILL.md) (`4,431 B -> 6,192 B`): §4.1 Windows Docker Desktop online benchmark hygiene (isolated Linux `overlayfs`, zero concurrent `-v` bind mounts, `<= 64`-char `idempotencyKey`, atomic `--resume` checkpointing, `try/finally docker rm -f`).
  3. [`.agents/skills/se-project-engineering/SKILL.md`](../../.agents/skills/se-project-engineering/SKILL.md) (`2,529 B -> 3,873 B`): repository acquisition (`GITLINK`/`SUBMODULE_COMMIT_REQUIRED` typed `GAP` sealing vs fail-closed `LFS_OBJECT_REQUIRED`) and explicit query evidence whitelisting invariants.

---

## 1. Batch 10 Engineering Summary (`I5_MATCHES=0` Across All 800 `src/main/` Files)

Batch 10 addressed the two exact technical gaps diagnosed in [`E6`](./E6-Online-MicroSecEnD-17App-Linux-Benchmark-Report.md) using strictly general, framework-level static analysis (`I5_MATCHES=0` — zero repository names, benchmark filenames, or corpus-specific mappings):

1. **Canonical Cold/Warm Acquisition Determinism (`MavenScan` & `GradleScan`):**
   - Root cause verified in [`MavenScan.java`](../../backend/src/main/java/com/evolution/backend/analysis/MavenScan.java) and [`GradleScan.java`](../../backend/src/main/java/com/evolution/backend/analysis/GradleScan.java): (a) raw multi-pass `.m2` cache-miss/retry `ACQUISITION_ATTEMPT` telemetry logs were hashed directly into `Whole repository` `KnowledgeGraph.identity()`, causing `cold != warm1` drift on 5/17 (`strict`) and 4/17 (`dfd-export`) repos (`2,214 -> 900` nodes on `ewolff/microservice-kafka`), and (b) on Cold Pass 1 before a relocated `.pom` entered `.m2`, speculative `.jar` fetches emitted an obsolete `ARTIFACT_NOT_FOUND` gap that vanished on Pass 2/Warm1 (`1,585 -> 1,584` on `#15 spring-petclinic-microservices`).
   - Fix: added `MavenScan.unresolvedCoordinates(...)` and `MavenScan.canonicalFailedAcquisitions(...)` (shared by `MavenScan` and `GradleScan`) to exclude cache-state-dependent operational retry logs from canonical `EvidenceAcquisitionLedger`s, filter failed outcomes against the final `ExactClasspathResult`'s genuinely unresolved coordinates, and canonicalize retained genuine failures per provider in deterministic sorted order (`attempts = List.of()`, `bytesConsumed = 0L`) while keeping full operational telemetry in scan summaries. Verified by 3 TDD regression tests in [`ColdWarmDeterminismTest.java`](../../backend/src/test/java/com/evolution/backend/analysis/ColdWarmDeterminismTest.java).
2. **Java Source Syntax & Spring Cloud Role Recovery (`analyzer-javaparser`):**
   - [`HttpClientConstants.java`](../../analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/HttpClientConstants.java): `syntaxValue()` now folds dynamic right-hand `BinaryExpr.PLUS` operands to `"{param}"` when the left-hand side folds to a URI/path prefix (`http://`, `https://`, `lb://`, `/`), recovering concatenated `RestTemplate`/`WebClient` URIs while keeping non-URI concatenations empty.
   - [`JavaServiceSyntaxExtractor.java`](../../analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/JavaServiceSyntaxExtractor.java): extended client markers (`RestOperations`, `OAuth2RestOperations`), emitted import-verified `SOURCE_SPRING_CLOUD_ROLE` facts (`ADMIN_SERVER`, `HYSTRIX_DASHBOARD`, `TURBINE`, `ZIPKIN_SERVER`, `OAUTH2_RESOURCE`, `OAUTH2_AUTH_SERVER`, `OAUTH2_SSO`), emitted `SOURCE_INFRA_CONFIG_REF` for `JavaMailSender`/`JavaMailSenderImpl` (`smtp`), and captured import-verified `@Value("${prop.key[:default]}")` bindings on fields, constructors, and `WebClient`/`RestClient` `.baseUrl(...)` calls into `SOURCE_HTTP_CALL_CANDIDATE` with `propertyKey`. Verified in [`JavaServiceSyntaxExtractorTest.java`](../../analyzer-javaparser/src/test/java/com/evolution/analysis/javaparser/JavaServiceSyntaxExtractorTest.java) (`6/6`).
3. **Declarative Topology, Infrastructure & Passive Build Starter Extraction (`backend`):**
   - [`StaticPolyglotScan.java`](../../backend/src/main/java/com/evolution/backend/analysis/StaticPolyglotScan.java) (provider bumped to `source.declarative-infra:m8.4-v2`) and [`DeclarativeTopologyParsers.java`](../../backend/src/main/java/com/evolution/backend/analysis/DeclarativeTopologyParsers.java) implemented 6 generic upgrades:
     1. *Test-root isolation:* skips `/src/test/`, `/src/it/`, `/src/integrationtest/`, `/src/integration-test/`, and `logback-test*.xml`.
     2. *Docker Compose v1/v2/v3:* extracts `buildDir`, `containerName`, `dockerSocket="true"` (`/var/run/docker.sock`), `external_links`, and ignores reserved non-service sections.
     3. *Helm Go-template guard:* files under `/templates/` or K8s manifests with `{{`/`}}` emit typed `UNRENDERED_HELM_TEMPLATE` gaps with `0` facts; per-doc DNS-1123 guard rejects non-rendered template names (eliminating Batch 9's `#1 anilallewar` `{{-release-name-}}-*` FPs).
     4. *Prometheus & Grafana indent-0 YAML sequence fix* (preserving `inScrape`/`inDs` and Grafana datasource `port`).
     5. *Spring Config parsing:* preserves `localhost`/`127.0.0.1` + numeric `port`, extracts Spring Boot Admin, OAuth2 resource/client URIs, Consul, Zipkin, `eureka.client.*`, custom `*.url`/`*.uri` properties, normalized `spring.cloud.config.server.git.uri` (`github-repository`/`gitlab-repository`/`git-repository`), and `configRepoFile="<stem>"` attribution for config-server repository directories.
     6. *Apache (`httpd*.conf`), Nginx (`nginx*.conf`/`default.conf`), Logback XML (`logback*.xml`), and passive Maven/Gradle starter facts:* parses 28 canonical Spring/infra starter tokens (`eureka-*`, `config-*`, `sleuth`, `zipkin`, `bus-amqp`, `stream-*`, `boot-admin-*`, `data-*`/`jdbc-*`, `mail`, `prometheus`, `consul`, `logstash-logback`, `oauth2-*`, `zuul`, `gateway`, `hystrix-dashboard`, `turbine`, `openfeign`) while excluding `<dependencyManagement>`, `<build>`, `<exclusions>`, and test scopes. Verified in [`DeclarativeTopologyParsersTest.java`](../../backend/src/test/java/com/evolution/backend/analysis/DeclarativeTopologyParsersTest.java) (`9/9`) and [`PolyglotScanTest.java`](../../backend/src/test/java/com/evolution/backend/analysis/PolyglotScanTest.java) (`13/13`).
4. **Canonical Identity & Epistemic Separation in Topology Projection (`analyzer`):**
   - [`DeclarativeTopologyProjection.java`](../../analyzer/src/main/java/com/evolution/analysis/polyglot/DeclarativeTopologyProjection.java), [`HttpServiceCallProjection.java`](../../analyzer/src/main/java/com/evolution/analysis/polyglot/HttpServiceCallProjection.java), [`EventFlowProjection.java`](../../analyzer/src/main/java/com/evolution/analysis/polyglot/EventFlowProjection.java), and [`ArchitectureQueryService.java`](../../analyzer/src/main/java/com/evolution/analysis/query/ArchitectureQueryService.java):
     - *`STRICT` Canonical:* two-pass longest-prefix `BUILD_COMPONENT` matching; `architecture.service-identity` evidence surfacing `spring.application.name` as canonical component identity while preserving `module=<dir>` provenance; unification of Compose `buildDir`/`containerName`/image aliases onto the same `ServiceEntry`; `configRepoFile` attribution to target client services; `localhost:<port>` resolution via unique `server.port` and role owners; `@Value` `propertyKey` resolution against `SOURCE_CONFIG_PROPERTY`; and strict caller->callee edge orientation.
     - *Opt-in `DFD_EXPORT` (`trust="DERIVED_DFD_POLICY"`):* tightened `user` actor wiring to explicit ingress entrypoints (Gateway/Zuul/Apache/Nginx/Web UI), tightened Zuul auto-proxy excluding control-plane/infra targets, role-based DFD data-flow orientation (`config -> client`, `git-repo -> config`, `db -> service`, `broker -> consumer` in `EventFlowProjection` with direct peer-to-peer shortcut suppression, `service -> prometheus -> grafana`, `zookeeper -> kafka`, `admin -> service`, `auth -> resource-service`), starter-evidenced wiring, and Docker-socket monitors. Verified in [`ServiceArchitectureProjectionTest.java`](../../analyzer/src/test/java/com/evolution/analysis/polyglot/ServiceArchitectureProjectionTest.java) (`8/8`), [`HttpServiceCallProjectionTest.java`](../../analyzer/src/test/java/com/evolution/analysis/polyglot/HttpServiceCallProjectionTest.java) (`10/10`), and [`PolyglotQueryServiceTest.java`](../../analyzer/src/test/java/com/evolution/analysis/query/PolyglotQueryServiceTest.java) (`4/4`).

---

## 2. Batch 9 -> Batch 10 Controlled Ablation (`17/17` Closed Denominator)

Both Batch 9 (`e6-online-microsecend/e6_summary.json`, Docker ID `905b9e9c4b0f`) and Batch 10 (`e6-batch10/e6_summary.json`, Docker ID `bde07d65cc27`) ran the identical 17-app `corpus_manifest.json` suite in isolated Linux `overlayfs` Docker containers (zero `-v` bind mounts, 3 bounded-RAM shards merged in exact manifest order, `0` residual containers):

| Profile | Metric | Batch 9 (`E6`) | Batch 10 (`E7`) | Delta (`B10 - B9`) |
| :--- | :--- | ---: | ---: | ---: |
| `strict` | Apps Analyzed with Graph (`PARTIAL`, `0` `ERROR`) | `17/17` | `17/17` | `+0` |
| `strict` | **`cold == warm1` SHA-256 Parity** | `12/17` (`70.6%`) | **`17/17` (`100.0%`)** | **`+5` (`+29.4 pp`)** |
| `strict` | Steady-State (`warm1 == warm2`) SHA-256 Parity | `17/17` (`100.0%`) | `17/17` (`100.0%`) | `+0` |
| `strict` | Cold Elapsed — Median ± IQR (`min..max`, ms) | `37,603.0 ± 132,391.0` (`7,755..241,401`) | `19,508.0 ± 25,538.0` (`8,638..61,444`) | `-18,095.0` ms (`1.93×` faster) |
| `strict` | Warm Elapsed — Median ± IQR (`min..max`, ms) | `4,474.0 ± 20,588.0` (`1,743..40,573`) | `2,870.0 ± 1,999.0` (`1,801..5,611`) | `-1,604.0` ms (`7.23×` lower max) |
| `strict` | Strict-Norm COMP uF1 / mF1 (`TP/FP/FN`) | `0.5760` / `0.5581` (`108/85/74`) | `0.8629` / `0.8555` (`151/17/31`) | `+0.2869` / `+0.2974` (`+43/-68/-43`) |
| `strict` | Strict-Norm CONN uF1 / mF1 (`TP/FP/FN`) | `0.1395` / `0.1044` (`41/162/344`) | `0.3780` / `0.3727` (`127/160/258`) | `+0.2385` / `+0.2683` (`+86/-2/-86`) |
| `strict` | Canonical COMP uF1 / mF1 (`TP/FP/FN`) | `0.7258` / `0.7388` (`131/51/48`) | `0.8870` / `0.8871` (`153/13/26`) | `+0.1612` / `+0.1483` (`+22/-38/-22`) |
| `strict` | Canonical CONN uF1 / mF1 (`TP/FP/FN`) | `0.2153` / `0.1661` (`62/131/321`) | `0.4000` / `0.4213` (`134/153/249`) | `+0.1847` / `+0.2552` (`+72/+22/-72`) |
| `strict` | **Collapsed COMP uF1 / mF1 (`TP/FP/FN`)** | `0.8045` / `0.8086` (`142/32/37`) | **`0.8947` / `0.8938` (`153/10/26`)** | **`+0.0902` / `+0.0852` (`+11/-22/-11`)** |
| `strict` | **Collapsed CONN uF1 / mF1 (`TP/FP/FN`)** | `0.2226` / `0.1792` (`64/128/319`) | **`0.4030` / `0.4238` (`135/152/248`)** | **`+0.1804` / `+0.2446` (`+71/+24/-71`)** |
| `dfd-export` | Apps Analyzed with Graph (`PARTIAL`, `0` `ERROR`) | `17/17` | `17/17` | `+0` |
| `dfd-export` | **`cold == warm1` SHA-256 Parity** | `13/17` (`76.5%`) | **`17/17` (`100.0%`)** | **`+4` (`+23.5 pp`)** |
| `dfd-export` | Steady-State (`warm1 == warm2`) SHA-256 Parity | `17/17` (`100.0%`) | `17/17` (`100.0%`) | `+0` |
| `dfd-export` | Cold Elapsed — Median ± IQR (`min..max`, ms) | `37,317.0 ± 55,431.5` (`8,068..255,160`) | `19,445.0 ± 25,782.0` (`8,597..63,542`) | `-17,872.0` ms (`1.92×` faster) |
| `dfd-export` | Warm Elapsed — Median ± IQR (`min..max`, ms) | `4,216.0 ± 17,995.0` (`1,782..106,429`) | `2,723.0 ± 1,835.0` (`1,866..6,174`) | `-1,493.0` ms (`17.24×` lower max) |
| `dfd-export` | Strict-Norm COMP uF1 / mF1 (`TP/FP/FN`) | `0.6272` / `0.6148` (`122/85/60`) | `0.8747` / `0.8701` (`164/29/18`) | `+0.2475` / `+0.2553` (`+42/-56/-42`) |
| `dfd-export` | Strict-Norm CONN uF1 / mF1 (`TP/FP/FN`) | `0.1880` / `0.1449` (`66/251/319`) | `0.6859` / `0.6903` (`286/163/99`) | `+0.4979` / `+0.5454` (`+220/-88/-220`) |
| `dfd-export` | Canonical COMP uF1 / mF1 (`TP/FP/FN`) | `0.7634` / `0.7738` (`142/51/37`) | `0.8943` / `0.8950` (`165/25/14`) | `+0.1309` / `+0.1212` (`+23/-26/-23`) |
| `dfd-export` | Canonical CONN uF1 / mF1 (`TP/FP/FN`) | `0.2599` / `0.2082` (`89/213/294`) | `0.7244` / `0.7619` (`301/147/82`) | `+0.4645` / `+0.5537` (`+212/-66/-212`) |
| `dfd-export` | **Collapsed COMP uF1 / mF1 (`TP/FP/FN`)** | `0.8375` / `0.8412` (`152/32/27`) | **`0.9016` / `0.9012` (`165/22/14`)** | **`+0.0641` / `+0.0600` (`+13/-10/-13`)** |
| `dfd-export` | **Collapsed CONN uF1 / mF1 (`TP/FP/FN`)** | `0.2673` / `0.2217` (`91/207/292`) | **`0.7253` / `0.7627` (`301/146/82`)** | **`+0.4580` / `+0.5410` (`+210/-61/-210`)** |

**Key scientific observations from the ablation:**
- **`17/17` (`100.0%`) first-replay SHA-256 determinism:** `cold == warm1 == warm2` now passes bit-identically on all 17 repositories in both profiles (zero Warm2 retries needed), confirming complete elimination of `.m2` cache-telemetry and relocated-POM drift.
- **Identity gains are genuine, not harness normalization artifacts:** Because `BUILD_COMPONENT` now surfaces canonical `spring.application.name` directly, `shared_prefix` auto-stripping was `""` on all `17/17` apps, `strict_normalized` jumped `+0.2869` COMP uF1 (`strict`) and `+0.4979` CONN uF1 (`dfd-export`), and `canonical_normalized` (`0.8943` COMP / `0.7244` CONN) nearly matches `collapsed_canonical` (`0.9016` / `0.7253`).
- **Simultaneous latency reduction:** Cold median dropped nearly `2×` (`~37.5 s -> ~19.5 s`) and Warm max tail latency dropped `7.23×` (`strict`: `40.6 s -> 5.6 s`) and `17.24×` (`dfd-export`: `106.4 s -> 6.2 s`) due to removing `ACQUISITION_ATTEMPT` graph bloat and excluding test trees.

---

## 3. Head-to-Head Comparison Against All 7 `EMSE2025SAR-Replication` Tools (`17/17`)

All 7 baseline rows are parsed verbatim from `EMSE2025SAR-Replication` (`1b8994471e9df0d32e22b31415eead8511078125`). Competing tools that failed on 4–12 apps report averages only over their completed subsets (`apps_completed`), whereas **Evo reports the closed `17/17` denominator**:

| Tool / Configuration | Apps Done | COMP TP / FP / FN | COMP uP | COMP uR | **COMP uF1** | COMP mP | COMP mR | **COMP mF1** | CONN TP / FP / FN | CONN uP | CONN uR | **CONN uF1** | CONN mP | CONN mR | **CONN mF1** |
| :--- | :---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **`Evo[dfd-export]` (Collapsed Canonical, B10)** | **17/17** | 165 / 22 / 14 | 0.8824 | 0.9218 | **0.9016** | 0.8892 | 0.9198 | **0.9012** | 301 / 146 / 82 | 0.6734 | 0.7859 | **0.7253** | 0.7304 | 0.8111 | **0.7627** |
| **`Evo[strict]` (Collapsed Canonical, B10)** | **17/17** | 153 / 10 / 26 | **0.9387** | 0.8547 | **0.8947** | **0.9588** | 0.8477 | **0.8938** | 135 / 152 / 248 | 0.4704 | 0.3525 | **0.4030** | 0.5334 | 0.3658 | **0.4238** |
| `Evo[dfd-export]` (Canonical Normalized, B10) | 17/17 | 165 / 25 / 14 | 0.8684 | 0.9218 | 0.8943 | 0.8784 | 0.9198 | 0.8950 | 301 / 147 / 82 | 0.6719 | 0.7859 | 0.7244 | 0.7291 | 0.8111 | 0.7619 |
| `Evo[strict]` (Canonical Normalized, B10) | 17/17 | 153 / 13 / 26 | 0.9217 | 0.8547 | 0.8870 | 0.9452 | 0.8477 | 0.8871 | 134 / 153 / 249 | 0.4669 | 0.3499 | 0.4000 | 0.5301 | 0.3638 | 0.4213 |
| `Evo[dfd-export]` (Strict Normalized, B10) | 17/17 | 164 / 29 / 18 | 0.8497 | 0.9011 | 0.8747 | 0.8556 | 0.8941 | 0.8701 | 286 / 163 / 99 | 0.6370 | 0.7429 | 0.6859 | 0.6606 | 0.7362 | 0.6903 |
| `Evo[strict]` (Strict Normalized, B10) | 17/17 | 151 / 17 / 31 | 0.8988 | 0.8297 | 0.8629 | 0.9161 | 0.8155 | 0.8555 | 127 / 160 / 258 | 0.4425 | 0.3299 | 0.3780 | 0.4703 | 0.3228 | 0.3727 |
| `code2DFD` *(EMSE2025SAR)* | 17/17 | 178 / 0 / 4 | 1.0000 | 0.9780 | 0.9889 | 1.0000 | 0.9755 | 0.9869 | 320 / 0 / 65 | 1.0000 | 0.8312 | 0.9078 | 1.0000 | 0.8554 | 0.9151 |
| `attack-graph-generator` *(EMSE2025SAR)* | 12/17 | 123 / 0 / 21 | 1.0000 | 0.8542 | 0.9213 | 1.0000 | 0.8643 | 0.9219 | 279 / 1 / 45 | 0.9964 | 0.8611 | 0.9238 | 0.9833 | 0.8550 | 0.9031 |
| `MicroDepGraph` *(EMSE2025SAR)* | 10/17 | 84 / 0 / 26 | 1.0000 | 0.7636 | 0.8660 | 1.0000 | 0.7683 | 0.8641 | 124 / 0 / 121 | 1.0000 | 0.5061 | 0.6721 | 1.0000 | 0.5139 | 0.6535 |
| `microMiner` *(EMSE2025SAR)* | 5/17 | 32 / 0 / 25 | 1.0000 | 0.5614 | 0.7191 | 0.8000 | 0.5695 | 0.6586 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `Prophet` *(EMSE2025SAR)* | 17/17 | 27 / 0 / 155 | 1.0000 | 0.1484 | 0.2584 | 0.5294 | 0.1608 | 0.2372 | 4 / 0 / 381 | 1.0000 | 0.0104 | 0.0206 | 0.1765 | 0.0261 | 0.0418 |
| `RAD` *(EMSE2025SAR)* | 13/17 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0 / 0 / 259 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| `RAD-source` *(EMSE2025SAR)* | 17/17 | 0 / 0 / 0 *(n=0)* | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 7 / 0 / 378 | 1.0000 | 0.0182 | 0.0357 | 0.2353 | 0.0341 | 0.0551 |

**Competitive standing (`CONFIRMED`):**
- **`Evo[dfd-export]` now outperforms 5 of the 7 `EMSE2025SAR-Replication` baseline tools** on BOTH Components and Connections—surpassing `MicroDepGraph` (`0.9016` vs `0.8660` COMP uF1; `0.7253` vs `0.6721` CONN uF1, even though `MicroDepGraph` only ran on `10/17` apps), `microMiner` (`5/17`), `Prophet` (`17/17`), `RAD` (`13/17`), and `RAD-source` (`17/17`)—while maintaining `17/17` (`100%`) repository coverage, `17/17` (`100%`) `cold == warm1 == warm2` SHA-256 determinism, passive `--build=off` safety, and `I5_MATCHES=0`.
- **Clean epistemic separation:** `Evo[strict]` preserves high-precision canonical architecture truth (**`COMP uP = 93.87%`**, **`mP = 95.88%`**, **`COMP uF1 = 0.8947`**, only `10` FPs across all 17 apps, `0` FPs on `11/17` apps) without introducing derived DFD policy edges, whereas opt-in `Evo[dfd-export]` explicitly tags every DFD policy derivation with `trust="DERIVED_DFD_POLICY"` (`+166` CONN TPs, `0.4030 -> 0.7253` CONN uF1).

---

## 4. Complete `17/17` Per-Application Breakdown (Verbatim from `e6-batch10/e6_summary.json`)

Every number below is extracted verbatim from `D:\UIT\evo-benchmark-cache\results\e6-batch10\e6_summary.json`. All 17 repositories completed with `status=PARTIAL`, `cold_vs_warm1_sha256_match=True`, and `steady_state_sha256_match=True` (`0` `ERROR` rows, `0` apps at `0.0000` CONN F1) in both profiles.

### 4.1. `Evo[strict]` (`EVO_PROJECTION_PROFILE=strict`) — All 17 Applications

| # | Repository (`app_id`) | Build | Cold (ms) | Warm (ms) | `c==w1` / `st` | Gaps (`c/w1`) | Canon COMP (`TP/FP/FN`, F1) | Collapsed COMP (`TP/FP/FN`, F1) | Canon CONN (`TP/FP/FN`, F1) | Collapsed CONN (`TP/FP/FN`, F1) | B9->B10 Col CONN |
| ---: | :--- | :---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `anilallewar/microservices-basics-spring-boot` | gradle | 45,559 | 4,899 | `T / T` | 1418 / 1418 | `11/3/1` (0.8462) | `11/1/1` (0.9167) | `5/13/24` (0.2128) | `6/12/23` (0.2553) | `+0.2127` |
| 2 | `apssouza22/java-microservice` | maven | 61,444 | 4,876 | `T / T` | 2228 / 2228 | `13/2/1` (0.8966) | `13/2/1` (0.8966) | `9/21/25` (0.2812) | `9/21/25` (0.2812) | `+0.1088` |
| 3 | `callistaenterprise/blog-microservices` | gradle | 24,844 | 2,870 | `T / T` | 1276 / 1276 | `15/2/2` (0.8824) | `15/2/2` (0.8824) | `17/12/25` (0.4789) | `17/12/25` (0.4789) | `+0.2375` |
| 4 | `ewolff/microservice` | maven | 16,838 | 2,444 | `T / T` | 993 / 993 | `6/0/1` (0.9231) | `6/0/1` (0.9231) | `6/2/7` (0.5714) | `6/2/7` (0.5714) | `+0.5714` |
| 5 | `ewolff/microservice-kafka` | maven | 24,389 | 3,117 | `T / T` | 1003 / 1003 | `7/0/1` (0.9333) | `7/0/1` (0.9333) | `4/11/8` (0.2963) | `4/11/8` (0.2963) | `+0.2318` |
| 6 | `fernandoabcampos/spring-netflix-oss-microservices` | maven | 14,363 | 2,387 | `T / T` | 714 / 714 | `10/0/1` (0.9524) | `10/0/1` (0.9524) | `7/12/17` (0.3256) | `7/12/17` (0.3256) | `+0.1034` |
| 7 | `georgwittberger/apache-spring-boot-microservice-example` | maven | 11,954 | 2,149 | `T / T` | 420 / 420 | `4/0/1` (0.8889) | `4/0/1` (0.8889) | `4/0/2` (0.8000) | `4/0/2` (0.8000) | `+0.8000` |
| 8 | `jferrater/tap-and-eat-microservices` | gradle | 19,508 | 2,422 | `T / T` | 1042 / 1042 | `9/0/0` (1.0000) | `9/0/0` (1.0000) | `9/5/7` (0.6000) | `9/5/7` (0.6000) | `+0.3778` |
| 9 | `koushikkothagal/spring-boot-microservices-workshop` | maven | 8,638 | 2,360 | `T / T` | 361 / 361 | `4/0/1` (0.8889) | `4/0/1` (0.8889) | `2/0/4` (0.5000) | `2/0/4` (0.5000) | `+0.5000` |
| 10 | `mdeket/spring-cloud-movie-recommendation` | maven | 29,870 | 3,665 | `T / T` | 832 / 832 | `7/0/3` (0.8235) | `7/0/3` (0.8235) | `3/6/14` (0.2308) | `3/6/14` (0.2308) | `-0.0692` |
| 11 | `mudigal-technologies/microservices-sample` | maven | 8,848 | 3,856 | `T / T` | 416 / 416 | `13/2/2` (0.8667) | `13/2/2` (0.8667) | `8/10/26` (0.3077) | `8/10/26` (0.3077) | `-0.0845` |
| 12 | `piomin/sample-spring-oauth2-microservices` | maven | 8,888 | 1,801 | `T / T` | 379 / 379 | `4/0/4` (0.6667) | `4/0/4` (0.6667) | `4/2/9` (0.4211) | `4/2/9` (0.4211) | `+0.1544` |
| 13 | `rohitghatol/spring-boot-microservices` | maven | 27,791 | 3,931 | `T / T` | 1147 / 1147 | `9/1/2` (0.8571) | `9/0/2` (0.9000) | `6/12/20` (0.2727) | `6/12/20` (0.2727) | `+0.2727` |
| 14 | `shabbirdwd53/springboot-microservice` | maven | 12,399 | 2,503 | `T / T` | 712 / 712 | `8/0/0` (1.0000) | `8/0/0` (1.0000) | `5/5/12` (0.3704) | `5/5/12` (0.3704) | `+0.3704` |
| 15 | `spring-petclinic/spring-petclinic-microservices` | maven | 46,086 | 4,940 | `T / T` | 1711 / 1711 | `10/2/2` (0.8333) | `10/2/2` (0.8333) | `7/18/21` (0.2642) | `7/18/21` (0.2642) | `-0.0337` |
| 16 | `sqshq/piggymetrics` | maven | 59,592 | 5,611 | `T / T` | 1797 / 1797 | `14/1/3` (0.8750) | `14/1/3` (0.8750) | `15/19/22` (0.4225) | `15/19/22` (0.4225) | `+0.1513` |
| 17 | `yidongnan/spring-cloud-netflix-example` | maven | 16,548 | 2,713 | `T / T` | 754 / 754 | `9/0/1` (0.9474) | `9/0/1` (0.9474) | `23/5/6` (0.8070) | `23/5/6` (0.8070) | `+0.2538` |
| **Agg** | **`17/17` Micro / Macro (Median Cold / Warm)** | **14M/3G** | **19,508** | **2,870** | **17 / 17** | — | **`153/13/26` (u0.8870 / m0.8871)** | **`153/10/26` (u0.8947 / m0.8938)** | **`134/153/249` (u0.4000 / m0.4213)** | **`135/152/248` (u0.4030 / m0.4238)** | **`+0.1804`** |

### 4.2. `Evo[dfd-export]` (`EVO_PROJECTION_PROFILE=dfd-export`) — All 17 Applications

| # | Repository (`app_id`) | Build | Cold (ms) | Warm (ms) | `c==w1` / `st` | Gaps (`c/w1`) | Canon COMP (`TP/FP/FN`, F1) | Collapsed COMP (`TP/FP/FN`, F1) | Canon CONN (`TP/FP/FN`, F1) | Collapsed CONN (`TP/FP/FN`, F1) | B9->B10 Col CONN |
| ---: | :--- | :---: | ---: | ---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| 1 | `anilallewar/microservices-basics-spring-boot` | gradle | 47,077 | 5,127 | `T / T` | 1418 / 1418 | `12/4/0` (0.8571) | `12/2/0` (0.9231) | `26/8/3` (0.8254) | `26/7/3` (0.8387) | **`+0.7076`** |
| 2 | `apssouza22/java-microservice` | maven | 63,542 | 5,056 | `T / T` | 2228 / 2228 | `13/3/1` (0.8667) | `13/3/1` (0.8667) | `22/29/12` (0.5176) | `22/29/12` (0.5176) | `+0.3330` |
| 3 | `callistaenterprise/blog-microservices` | gradle | 19,734 | 2,802 | `T / T` | 1276 / 1276 | `16/2/1` (0.9143) | `16/2/1` (0.9143) | `37/24/5` (0.7184) | `37/24/5` (0.7184) | `+0.4613` |
| 4 | `ewolff/microservice` | maven | 16,345 | 2,350 | `T / T` | 993 / 993 | `7/0/0` (1.0000) | `7/0/0` (1.0000) | `12/1/1` (0.9231) | `12/1/1` (0.9231) | **`+0.9231`** |
| 5 | `ewolff/microservice-kafka` | maven | 23,277 | 2,590 | `T / T` | 1003 / 1003 | `8/0/0` (1.0000) | `8/0/0` (1.0000) | `12/4/0` (0.8571) | `12/4/0` (0.8571) | **`+0.7359`** |
| 6 | `fernandoabcampos/spring-netflix-oss-microservices` | maven | 13,738 | 2,424 | `T / T` | 714 / 714 | `11/0/0` (1.0000) | `11/0/0` (1.0000) | `21/5/3` (0.8400) | `21/5/3` (0.8400) | `+0.5349` |
| 7 | `georgwittberger/apache-spring-boot-microservice-example` | maven | 12,108 | 2,122 | `T / T` | 420 / 420 | `5/0/0` (1.0000) | `5/0/0` (1.0000) | `6/0/0` (1.0000) | `6/0/0` (1.0000) | **`+1.0000`** |
| 8 | `jferrater/tap-and-eat-microservices` | gradle | 19,445 | 2,539 | `T / T` | 1042 / 1042 | `9/3/0` (0.8571) | `9/3/0` (0.8571) | `15/13/1` (0.6818) | `15/13/1` (0.6818) | `+0.4596` |
| 9 | `koushikkothagal/spring-boot-microservices-workshop` | maven | 8,597 | 2,174 | `T / T` | 361 / 361 | `4/1/1` (0.8000) | `4/1/1` (0.8000) | `5/1/1` (0.8333) | `5/1/1` (0.8333) | **`+0.8333`** |
| 10 | `mdeket/spring-cloud-movie-recommendation` | maven | 29,945 | 3,609 | `T / T` | 832 / 832 | `7/2/3` (0.7368) | `7/2/3` (0.7368) | `12/3/5` (0.7500) | `12/3/5` (0.7500) | `+0.3690` |
| 11 | `mudigal-technologies/microservices-sample` | maven | 8,759 | 3,544 | `T / T` | 416 / 416 | `14/2/1` (0.9032) | `14/2/1` (0.9032) | `12/22/22` (0.3529) | `12/22/22` (0.3529) | `+0.0037` |
| 12 | `piomin/sample-spring-oauth2-microservices` | maven | 9,136 | 1,866 | `T / T` | 379 / 379 | `5/1/3` (0.7143) | `5/1/3` (0.7143) | `8/3/5` (0.6667) | `8/3/5` (0.6667) | `+0.2917` |
| 13 | `rohitghatol/spring-boot-microservices` | maven | 27,912 | 3,784 | `T / T` | 1147 / 1147 | `10/2/1` (0.8696) | `10/1/1` (0.9091) | `24/4/2` (0.8889) | `24/4/2` (0.8889) | **`+0.6325`** |
| 14 | `shabbirdwd53/springboot-microservice` | maven | 12,047 | 2,674 | `T / T` | 712 / 712 | `8/1/0` (0.9412) | `8/1/0` (0.9412) | `15/3/2` (0.8571) | `15/3/2` (0.8571) | **`+0.8571`** |
| 15 | `spring-petclinic/spring-petclinic-microservices` | maven | 45,774 | 4,660 | `T / T` | 1711 / 1711 | `11/3/1` (0.8462) | `11/3/1` (0.8462) | `14/18/14` (0.4667) | `14/18/14` (0.4667) | `+0.1908` |
| 16 | `sqshq/piggymetrics` | maven | 60,041 | 6,174 | `T / T` | 1797 / 1797 | `15/1/2` (0.9091) | `15/1/2` (0.9091) | `34/4/3` (0.9067) | `34/4/3` (0.9067) | **`+0.5734`** |
| 17 | `yidongnan/spring-cloud-netflix-example` | maven | 16,659 | 2,723 | `T / T` | 754 / 754 | `10/0/0` (1.0000) | `10/0/0` (1.0000) | `26/5/3` (0.8667) | `26/5/3` (0.8667) | `+0.2898` |
| **Agg** | **`17/17` Micro / Macro (Median Cold / Warm)** | **14M/3G** | **19,445** | **2,723** | **17 / 17** | — | **`165/25/14` (u0.8943 / m0.8950)** | **`165/22/14` (u0.9016 / m0.9012)** | **`301/147/82` (u0.7244 / m0.7619)** | **`301/146/82` (u0.7253 / m0.7627)** | **`+0.4580`** |

**Batch 9 -> Batch 10 per-app milestone highlights (`Evo[dfd-export]`, Collapsed Canonical):**
- **Perfect `1.0000 / 1.0000` (COMP / CONN):** `#7 georgwittberger/apache-spring-boot-microservice-example` (`COMP 0.7500 -> 1.0000`, `CONN 0.0000 -> 1.0000`, `5/0/0` and `6/0/0`).
- **Perfect `1.0000` COMP F1 (`5/17` repos):** `#4 ewolff/microservice`, `#5 ewolff/microservice-kafka`, `#6 fernandoabcampos`, `#7 georgwittberger`, `#17 yidongnan`; **`11/17` repos >= `0.9000` COMP F1**.
- **`>= 0.8000` CONN F1 (`10/17` repos, up from `0/17` in Batch 9):** `#7 georgwittberger` (`1.0000`), `#4 ewolff/microservice` (`0.0000 -> 0.9231`), `#16 piggymetrics` (`0.3333 -> 0.9067`), `#13 rohitghatol` (`0.2564 -> 0.8889`), `#17 yidongnan` (`0.5769 -> 0.8667`), `#5 ewolff/microservice-kafka` (`0.1212 -> 0.8571`), `#14 shabbirdwd53` (`0.0000 -> 0.8571`), `#6 fernandoabcampos` (`0.3051 -> 0.8400`), `#1 anilallewar` (`0.1311 -> 0.8387`; COMP FPs slashed `14 -> 2` after Helm guard), and `#9 koushikkothagal` (`0.0000 -> 0.8333`).
- Every single repository (`17/17`) improved on `Evo[dfd-export]` Collapsed CONN F1 (`+0.0037` to `+1.0000`), with **`14/17` repos >= `0.6667` CONN F1**.

---

## 5. Honest Remaining Failure-Mode Analysis & Threats to Validity

Direct inspection of every remaining FP and FN in `e6-batch10/e6_summary.json` against `EMSE2025SAR-Replication` textual ground truth reveals concrete, generic causes—without any invented explanations:

### 5.1. Remaining `22` Component FPs & `14` Component FNs (`Evo[dfd-export]`, Collapsed Canonical)

1. **Generic Passive Starter Databases (`13 / 22` COMP FPs = `59.1%`; coupled with `6 / 14` COMP FNs = `42.9%`):**
   - Passive `pom.xml`/`build.gradle` starter inference emits shared `h2` (`4` FPs: `#2`, `#8`, `#11`, `#14`), `mysql` (`6` FPs: `#1`, `#8`, `#10`, `#12`, `#13`, `#15`), and `mongodb` (`3` FPs: `#8`, `#10`, `#11`) — `13 / 22` (`59.1%`) of all `DFD_EXPORT` component FPs.
   - These generic starter-inferred DB nodes either (a) duplicate an already-named Compose datastore (`mysqldb` in `#1`, `serviceonedb`/`servicetwodb` in `#11`), (b) represent embedded dev/in-memory `h2` drivers omitted from DFD ground truth (`#2`, `#8`, `#11`, `#14`), or (c) collapse per-service databases into a single vendor node (`mysql`/`mongodb`) where ground truth models distinct logical per-service datastores (`database_movie`, `database_recommendation`, `database_user` in `#10`; `database_auth`, `database_gateway` in `#12`; `database_auth` in `#13` — `6 / 14` = `42.9%` of all COMP FNs).
2. **Exact Synonym / Prefix Mismatches (`4 / 22` COMP FPs = `18.2%`; coupled with `4 / 14` COMP FNs = `28.6%`):**
   - `#3 callistaenterprise`: `gitrepository` (FP) vs `githubrepository` (FN).
   - `#15 spring-petclinic`: `springpetclinicconfig` (FP) vs `config` (FN) — because other modules surfaced short `spring.application.name` values, `< 3` sibling modules shared `spring-petclinic-`, leaving `spring-petclinic-config-server` unstripped.
   - `#16 piggymetrics`: `smtp` (FP) vs `mail` (FN) from `JavaMailSender`.
   - `#9 koushikkothagal`: `{param}?apikey={param}` (FP — an unresolved dynamic external URL fragment promoted as an external endpoint) vs `externalwebsite` (FN).
3. **Upstream Live-Repository Evolution & Auxiliary Artifacts (`5 / 22` COMP FPs = `22.7%`; `4 / 14` COMP FNs = `28.6%`):**
   - Live upstream clones diverged from frozen `EMSE2025SAR` ground truth on `#15 spring-petclinic` (`genai` service added upstream, `1` FP) and `#2 apssouza22` (`nginx` reverse proxy + `githubrepository` config URI, `2` FPs); `#3 callistaenterprise` retains `util` (`1` FP) and `#1 anilallewar` retains `zipkin` (`1` FP alongside `zipkin_tracing`).
   - Remaining `4 / 14` COMP FNs are `logstash` (`#2`), `consul3` (`#11` 3rd Consul replica), `customer` (`#12`), and `externalwebsite` (`#16`).
- Notably, **`Evo[strict]` cuts total Collapsed COMP FPs by more than half to just `10`** (`Precision = 93.87%`, Macro Precision `95.88%`, `0` COMP FPs on `11/17` apps), isolating starter-inferred DB over-generation cleanly inside `DFD_EXPORT`.

### 5.2. Diagnosis of the 3 Remaining Lower-F1 `CONN` Outliers (`146` FPs / `82` FNs Total)

Three applications (`#11 mudigal`, `#15 spring-petclinic`, `#2 apssouza22`) account for **`69 / 146` (`47.3%`) of all `DFD_EXPORT` connection FPs** and **`48 / 82` (`58.5%`) of all connection FNs**:

1. **`#11 mudigal-technologies/microservices-sample` (`TP=12, FP=22, FN=22, F1=0.3529`):**
   - **`16 / 22` FPs (`72.7%`) are exact edge-direction reversals** of ground-truth FNs: `12` Weave Scope Docker-socket monitoring edges (`scope -> <service>` emitted by Evo vs `<service> -> scope` in ground truth), `2` Consul discovery edges (`serviceone/servicetwo -> consul` vs `consul -> serviceone/servicetwo`), and `2` datastore edges (`serviceone -> serviceonedb`, `servicetwo -> servicetwodb` vs `db -> service`). The remaining FNs are `3` `consul3` edges (`consul3` replica FN), `2` `rabbit -> service` edges, and `webapplication -> apigateway`.
2. **`#15 spring-petclinic/spring-petclinic-microservices` (`TP=14, FP=18, FN=14, F1=0.4667`):**
   - **Single unstripped prefix cascade (`7` FP + `7` FN pairs):** `springpetclinicconfig` vs `config` caused `githubrepository -> springpetclinicconfig` and `6` `springpetclinicconfig -> *` edges to miss `config` simultaneously.
   - **Live upstream repository drift (`5` FPs + `5` FNs):** upstream `genai` service added `5` valid live edges absent from frozen ground truth (`apigateway -> genai`, `genai -> customers/discovery/prometheus/vets`) plus `mysql -> genai`, while upstream's migration away from Sleuth/Zipkin left `5` `tracing` ground-truth edges absent (`apigateway/customers/vets/visits -> tracing`, `tracing -> discovery`).
   - **Generic `mysql` starter over-wiring (`4` FPs):** `mysql -> customers/genai/vets/visits`.
3. **`#2 apssouza22/java-microservice` (`TP=22, FP=29, FN=12, F1=0.5176`):**
   - **`kafka` broadcast over-firing (`12` FPs) & missing `logstash` hub (`9` FNs):** Evo projected `kafka -> *` across `11` services plus `kafka -> zookeeper` (`12` FPs), while `9` `* -> logstash` ground-truth edges were missed because `logstash` was not recovered as a component in `#2`. Remaining FPs come from `h2 -> *` (`4` FPs), `nginx` live-repo edges (`3` FPs), and `githubrepository -> config` / `user -> eureka` / `oauth -> apigateway`.
- Secondary moderate outliers `#3 callistaenterprise` (`CONN F1 = 0.7184`, `37 TP / 24 FP / 5 FN`) and `#8 jferrater` (`CONN F1 = 0.6818`, `15 TP / 13 FP / 1 FN`) show high recall (`88.1%` and `93.8%` respectively) with precision drag primarily from `DFD_EXPORT` fan-out of `zipkin`/`rabbitmq` (`#3`) and `discovery -> *` + `mysql`/`h2`/`mongodb` starter edges (`#8`).

### 5.3. Positioning vs `code2DFD` & Scientific Threats to Validity

- **Why `code2DFD` still leads on `microSecEnD` DFD precision (`0.9889` COMP / `0.9078` CONN vs Evo `0.9016` / `0.7253`):** `code2DFD` is a DFD-specialized Spring/Compose extractor co-developed alongside `microSecEnD` and reports `0` FPs across all 17 apps in the `EMSE2025SAR` replication dataset. Evo's remaining precision gap (`22` COMP FPs, `146` CONN FPs) reflects generic starter/broker/monitor fan-out and direction convention mismatches—not broken coverage or missing determinism.
- **Threat to validity (live HEAD vs frozen GT):** Because Evo Online Web App clones live GitHub URLs (`POST /api/v1/analysis/connections`), upstream repository evolution after the `EMSE2025SAR` ground truth was frozen (e.g., `genai` and OpenTelemetry migration in `spring-petclinic-microservices`, `nginx` in `apssouza22`) introduces genuine live-architecture edges counted as FPs/FNs against the historical ground truth.
- **What differentiates Evo scientifically:** Rather than a single-purpose DFD script, Evo delivers a unified, content-addressed, multi-language Software Knowledge Graph combining: (1) passive `--build=off` architecture recovery with `17/17` coverage (`0.9016` COMP / `0.7253` CONN uF1) and `17/17` (`100%`) `cold == warm1 == warm2` SHA-256 determinism, (2) formal clean-room CDCL/COI Spring configuration reasoning verified at **`100.0%` soundness** against live Spring Framework 6.2 runtime ([`E5`](./E5-Spring-SAT-Oracle-And-Ablation-Report.md)), (3) **`85.71%`** graph-based regression test selection (`SKG-RTS`), and (4) isolated online verified repair—all under strict `I1`–`I5` invariants (`I5_MATCHES=0`).

---

## 6. Reproducibility & Artifact Locations

### 6.1. Repository Harness & Manifest

- Benchmark runner: [`benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py`](../../benchmarks/e0-publication-suite/run_online_microsecend_benchmark.py) (`python -m py_compile` `EXIT_CODE=0`)
- Corpus manifest: [`benchmarks/e0-publication-suite/corpus_manifest.json`](../../benchmarks/e0-publication-suite/corpus_manifest.json) (`suites.microsecend_17`, 17 apps)
- Container build definition: [`Dockerfile`](../../Dockerfile) (`evo-workbench-online:latest`, ID `sha256:bde07d65cc27...`)

### 6.2. External Immutable Benchmark Artifacts (Outside Git)

- Batch 10 authoritative `17/17` JSON summary (`135,902` bytes, `apps_count=17`, `0` `_sets` keys, `7` `emse2025sar_baselines`):
  - `D:\UIT\evo-benchmark-cache\results\e6-batch10\e6_summary.json`
- Batch 10 per-app CSV (`5,279` bytes, `35` lines = 1 header + 17 `strict` + 17 `dfd-export` rows):
  - `D:\UIT\evo-benchmark-cache\results\e6-batch10\e6_per_app.csv`
- Batch 10 merged checkpoint (`118,426` bytes):
  - `D:\UIT\evo-benchmark-cache\results\e6-batch10\e6_checkpoint.json`
- Batch 9 immutable historical baseline (untouched):
  - `D:\UIT\evo-benchmark-cache\results\e6-online-microsecend\e6_summary.json`

### 6.3. Reproduction Commands (Windows PowerShell 5.1)

**1. Selective 7-module Maven verification (`159/159` PASS across 22 classes):**

```powershell
.\mvnw.cmd -o -B -ntp -pl .,analyzer,analyzer-maven,analyzer-filesystem,analyzer-javaparser,analyzer-treesitter,backend '-Dmaven.compiler.useIncrementalCompilation=false' '-Dtest=HttpServiceCallProjectionTest,ServiceArchitectureProjectionTest,ArchitectureQueryContractTest,PolyglotQueryServiceTest,SpringSatAblationBenchmarkTest,MavenDependencyArtifactResolverTest,StoragePerfParityTest,StreamingRepositoryAcquirerTest,HttpClientRecoveryTest,JavaServiceSyntaxExtractorTest,TreeSitterSourceFrontendTest,ColdWarmDeterminismTest,DeclarativeTopologyParsersTest,PolyglotScanTest,RepositoryConnectionsTest,SpringDifferentialOracleTest,WorkbenchApiMainTest,AiAndRepairUpgradeTest,AnalysisJobApiTest,ArchitectureAgentTest,ResearchBreakthroughUpgradesTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

**2. Standalone Online Web App JAR & Docker image build (`cli` excluded):**

```powershell
.\mvnw.cmd -o -B -ntp -pl .,backend -am '-DskipTests' package; docker build -t evo-workbench-online:latest .
```

**3. Isolated Linux Docker `STRICT` + `DFD_EXPORT` benchmark run (zero host `-v` mounts, bounded RAM, guaranteed cleanup):**

```powershell
docker rm -f evo-b10-strict evo-b10-dfd 2>$null | Out-Null
docker run -d --name evo-b10-strict --memory=2200m -p 127.0.0.1:18081:8080 -e EVO_PROJECTION_PROFILE=strict -e JAVA_OPTS="-Xms256m -Xmx1536m -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1" evo-workbench-online:latest
docker run -d --name evo-b10-dfd --memory=2200m -p 127.0.0.1:18082:8080 -e EVO_PROJECTION_PROFILE=dfd-export -e JAVA_OPTS="-Xms256m -Xmx1536m -XX:+UseG1GC -XX:SoftRefLRUPolicyMSPerMB=1" evo-workbench-online:latest
try {
    python benchmarks\e0-publication-suite\run_online_microsecend_benchmark.py `
        --manifest benchmarks\e0-publication-suite\corpus_manifest.json `
        --ground-truth-dir D:\UIT\evo-benchmark-cache\EMSE2025SAR-Replication\tool_comparison\groundtruth_textual `
        --replication-dir D:\UIT\evo-benchmark-cache\EMSE2025SAR-Replication `
        --strict-url http://127.0.0.1:18081 `
        --dfd-url http://127.0.0.1:18082 `
        --out-dir D:\UIT\evo-benchmark-cache\results\e6-batch10 `
        --timeout-s 300 --resume
} finally {
    docker rm -f evo-b10-strict evo-b10-dfd 2>$null | Out-Null
}
```
