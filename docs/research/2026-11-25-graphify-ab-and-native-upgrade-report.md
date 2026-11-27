# Empirical A/B Benchmark: Native Waves 1–3 Upgrade vs. `Graphify-Labs/graphify` (`v0.9.120`)

*2026-11-25 / 2026-11-26 — Wave 4 Empirical Evaluation*

## 1. Executive Verdict

**CONFIRMED (direct empirical measurement on pinned 51-file corpus `ledger-commerce-ab-v1` + independent Round-2 audit & live JVM-to-Edge verification).**

Integrating the highest-value architectural patterns studied in `Graphify-Labs/graphify` (`v0.9.120`) natively in pure Java 21 / vanilla JS (Waves 1–3: secret-redacted LLM/MCP transport, Trigram+IDF `RepoMapBuilder`, Direct Local Store `WorkbenchMcpMain` + 6 MCP Resources, pure-Java deterministic Leiden communities & Package-vs-Community drift, Workbench collapsible Community Super-Nodes, deterministic multi-page Wiki Dossier ZIP, and quarantined `advisory.doc-link:v1` SQL/ADR/Terraform/JS-alias/MCP extractors) closes every verified capability gap observed against Graphify while preserving all five non-negotiable invariants in [AGENTS.md](../../AGENTS.md):

1. **Zero canonical contamination (`I2`/`I4`):** Adding the entire advisory overlay and Wave 1–3 read-side surfaces left the canonical `KnowledgeGraph` snapshot, all 7 contexts, and all 3,478 canonical edges bit-for-bit identical to the pre-upgrade baseline (only 5 `polyglot.source-syntax` records for TypeScript files updated after fixing relative `../` workspace alias resolution in `StaticPolyglotScan`), and mutating `.md`/`.sql`/`.tf`/`.mcp.json` left canonical counts unchanged at `[6,458 nodes, 3,478 edges]`.
2. **Closed denominator & compiler-backed Spring SAT (`I3`/`S1`/`S2`):** Evo covers **51/51** files with **2,362** explicit `CAPABILITY_GAP` nodes and models conditional Spring worlds (`5 TRUTH_REGION`, `5 CONDITIONAL_FACT`, `92 REGION_FORMULA`, `47 CONDITION_EXPR`, 20+ deterministic SAT witnesses), whereas Graphify `v0.9.120` (`--code-only`) silently omitted **7/51** files by default (**3/51** even with `[sql,terraform]`) with **0** gap records, **0** Spring truth regions, and **0/2** accuracy on same-arity Java overload calls (`calculateFee(PaymentCommand)` mis-wired to `calculateFee(long)`).
3. **Zero secret leakage vs. 6 raw leaks in Graphify `[terraform]` (`I1`):** Evo AFTER redacted **15/15** synthetic dummy secrets (**34** `[REDACTED...]` markers across `.tf`, `application.yml`, `.mcp.json`, including partial-slice single-line PEM body reads with `BEGIN`/`END` headers omitted) with **0** raw secret leaks across both source slices and advisory outputs. Under Docker-isolated execution with `[sql,terraform]`, Graphify `0.9.120` leaked **3** synthetic secret classes verbatim (`postgresql://user:pass@...`, JWT `eyJ...`, `Bearer ...`) into both `graph.json` and its AST cache JSON (**6** raw leaks total).
4. **Pure-Java Leiden + SQL/ADR/Wiki parity at faster wall time (`I4`/`I5`):** Evo AFTER ran in **11.77s cold / 8.34s warm** (vs. Graphify default **29.30s** and Graphify `[sql,terraform]` **72.14s**), achieved **4/4** SQL DDL↔JPA table links (+ 4 FKs, 1 view, 1 routine, 1 trigger vs. Graphify `[sql]`'s **0/4**), **2/2** ADRs (**67** links, exact heading slicing vs. Graphify `--code-only`'s **0/2**), **8** deterministic Leiden communities (`Q = 0.5078`, detecting deliberate misplaced type `reporting.LegacySettlementReconciler`, plus **8** visual Community Super-Nodes with external library types isolated in `compound:external`), **5/5 (1.00)** `RepoMap` Trigram+IDF Recall@5, **17** MCP tools + **6/6** MCP Resources with **100% CAS parity** in direct local store mode (`--store`), and a **13-page** deterministic Wiki Dossier ZIP (**10,949 B**, magic `PK\x03\x04`).

---

## 2. Benchmark Corpus & Isolated Methodology

### 2.1. Pinned Multi-Module Corpus (`ledger-commerce-ab-v1`)

**CONFIRMED (manifest hash verified, Java 21 + Lombok APT compiled cleanly).**

- **Corpus ID:** `ledger-commerce-ab-v1`
- **Canonical Corpus SHA-256:** `36e3f3bc6f4f53bd2e959f59ab39743e6b54a782989a8b64ebc424ec5f518303`
- **Inventory:** **51 files** (`58,697` bytes) across 3 Maven modules (`ledger-domain`, `ledger-payments`, `ledger-api`) + Flyway SQL + Markdown ADRs + Terraform + TypeScript + `.mcp.json`:
  - **4 Maven POMs** (parent `<dependencyManagement>` with property interpolation `${spring.version}`, `${spring.boot.version}`, `${lombok.version}`).
  - **32 Java 21 sources** (31 main + 1 sibling test `PaymentControllerTest.java`), compile-verified offline via `javac --release 21` + Lombok `1.18.36` APT (`35 .class` files, runtime smoke check `RUNTIME_SMOKE_OK`). Exercises:
    - Overloaded methods including same-arity distinct-type overloads (`FeeCalculator.calculateFee(long)` vs. `FeeCalculator.calculateFee(PaymentCommand)`), `PaymentGatewayPort.authorize(...)` (3 overloads), `RiskScoringPort.evaluate(...)` (2 overloads).
    - Interface dispatch across 3 ports and 6 concrete implementations.
    - Lombok `@Getter`, `@Setter`, `@Builder`, `@RequiredArgsConstructor`, `@NoArgsConstructor`, `@AllArgsConstructor`.
    - Mutually exclusive Spring worlds (`@Profile("prod")` + `@ConditionalOnProperty("ledger.gateway.provider")` `stripe` vs. `adyen`, `@Profile("dev")` `SandboxStubPaymentGateway`, `@ConditionalOnProperty("ledger.risk.mode")` `ml` vs. `rules`, conditional `@Bean` `GatewayToggleConfiguration`).
    - 4 JPA `@Entity`/`@Table(name=...)` classes (`AccountEntity`, `PaymentTransactionEntity`, `MerchantProfileEntity`, `AuditLogEntry`).
    - Deliberate architectural drift probe: `com.acme.ledger.payments.reporting.LegacySettlementReconciler` declared in `reporting` alongside 4 cohesive reporting types, while coupling structurally to `persistence` and `orchestration`.
  - **2 Flyway SQL migrations** (`V1__create_ledger_accounts_and_merchants.sql`, `V2__create_payment_transactions_and_audit.sql`): 4 tables, 4 foreign keys, 1 view, 1 SQL function, 1 trigger.
  - **3 Markdown files** (`README.md`, `ADR-001-payment-gateway-isolation.md`, `ADR-002-ledger-schema-and-audit.md`) citing classes, HTTP routes, SQL tables, wikilinks (`[[...]]`), and literal prompt-injection sentinel strings (`<|im_start|>`, `</untrusted_source>`).
  - **2 Terraform HCL files** (`infra/terraform/main.tf`, `variables.tf`), **`application.yml`**, and **`.mcp.json`** containing **15 synthetic dummy secrets** (AWS key, Stripe key, OpenAI key, Gemini key, Slack token, GitHub tokens, PostgreSQL URLs with passwords, JWT, Bearer header, and multi-line PEM private key block) solely to measure redaction and leakage.
  - **6 TypeScript/frontend files** (`tsconfig.json` `@domain/*`/`@api/*`/`@components/*` aliases, `package.json` `exports`/`#internal/*`, and client calls to Spring REST endpoints).

### 2.2. Strict Isolation Protocol (`I1` / `GHSA-g6c9-j4vm-mwq4`)

**CONFIRMED (container read-only mount verified inside entrypoint; zero host execution of Graphify).**

Because `Graphify-Labs/graphify` suffered high-severity Windows host-indexing vulnerabilities (`GHSA-g6c9-j4vm-mwq4` SMB/UNC auth coercion; `GHSA-pcc4-rvhr-2pr8` `#include` path traversal) and ambient-env LLM egress risks, `graphifyy==0.9.120` was **never executed on the Windows host**. Both `graphifyy==0.9.120` (default PyPI wheel) and `graphifyy[sql,terraform]==0.9.120` were built on `python:3.12-slim` (unprivileged UID `10001`, `GRAPHIFY_BACKEND=none`, `PYTHONHASHSEED=42`) and executed strictly inside Docker Desktop Linux engine `29.4.0` with:

```text
docker run --rm --network=none --read-only --tmpfs /tmp:rw,size=512m \
  --cpus=2 --memory=2g --pids-limit=256 --cap-drop=ALL \
  --security-opt=no-new-privileges:true \
  -v "<corpus>:/workspace/repo:ro" -v "<out>:/workspace/out:rw" <image>
```

Each Graphify image executed `graphify extract /workspace/repo --code-only --force --out <dir>` twice inside the container to verify deterministic replay, recording `time.perf_counter()` wall time and `resource.getrusage(RUSAGE_CHILDREN).ru_maxrss` peak RSS.

Evo BEFORE (pre-Waves-1–3 `evo.jar`, `72,041,908` B) and Evo AFTER (Round-2 rebuilt `evo.jar`, `72,300,917` B, zero new Maven dependencies) ran offline with `-Xmx2g` and `--offline --maven-cache ~/.m2/repository --spring-sat --include-tests --all-files`, measuring cold/warm wall time, Windows `GetProcessMemoryInfo` (`PeakWorkingSetSize`), 2-run SHA-256 determinism, `WorkbenchMcpMain --store` direct local store JSON-RPC, and live loopback `WorkbenchApiMain` HTTP endpoints (cleanly terminated after capture).

---

## 3. Empirical 3-Way A/B Scorecard

**CONFIRMED (all figures directly measured on `ledger-commerce-ab-v1`, SHA-256 `36e3f3bc...`).**

| Dimension | Evo BEFORE (Pre-Waves 1–3) | Evo AFTER (Waves 1–3, Round 2) | Graphify `0.9.120` (Docker `--code-only`) | Graphify `0.9.120[sql,terraform]` (Docker `--code-only`) |
| :--- | :--- | :--- | :--- | :--- |
| **Cold Wall Time** | `27.25s` | **`11.77s`** | `29.30s` | `72.14s` |
| **Warm Incremental Wall Time** | `20.39s` | **`8.34s`** | N/A (`--force` cold) | N/A (`--force` cold) |
| **Peak RAM (RSS)** | `1,465.82 MiB` | `1,356.27 MiB` | **`64.03 MiB`** | `68.48 MiB` |
| **Graph JSON Size** | `15,578,238 B` | `15,578,238 B` | `402,339 B` | `425,553 B` |
| **2-Run Deterministic SHA-256** | **YES** | **YES** | **YES** | **YES** |
| **Graph Model (Nodes / Edges)** | Directed multi-edge + SHA-256 evidence (`6,458 / 3,478`) | Directed multi-edge + SHA-256 evidence (`6,458 / 3,478`) | Undirected `nx.Graph` (`269 / 679`, `599 EXTRACTED / 80 INFERRED`) | Undirected `nx.Graph` (`292 / 715`, `635 EXTRACTED / 80 INFERRED`) |
| **Closed File Denominator & Gaps** | **51/51** (`2,362` explicit `CAPABILITY_GAP` nodes) | **51/51** (`2,362` explicit `CAPABILITY_GAP` nodes) | `44/51` (**7 silently omitted**: `.md`, `.sql`, `.tf`; `0` gaps) | `48/51` (**3 silently omitted**: all `.md`; `0` gaps) |
| **Java Overloads & Same-Arity Calls** | `3/3` compiler-typed sigs; intra-module resolved; cross-mod `0/2` (passive, no host build) | `3/3` compiler-typed sigs; intra-module resolved; cross-mod `0/2` (passive, no host build) | `3/3` hash-suffix nodes (`0` typed sigs); **same-arity calls `0/2`** (mis-wired to `calculateFee(long)`) | `3/3` hash-suffix nodes (`0` typed sigs); **same-arity calls `0/2`** (mis-wired to `calculateFee(long)`) |
| **Interface Dispatch (`implements`)** | `6/6` edges recorded (`0/6` cross-mod resolved without compiled sibling JAR) | `6/6` edges recorded (`0/6` cross-mod resolved without compiled sibling JAR) | `6/6` (AST `implements`) | `6/6` (AST `implements`) |
| **Lombok Synthesized Members & Calls** | `179` synthesized methods (`lombok.*`); `3/9` intra-module calls resolved | `179` synthesized methods (`lombok.*`); `3/9` intra-module calls resolved | `8/9` `INFERRED` getter calls; **misses `@Builder`** (`PaymentTransactionEntity.builder()`) | `8/9` `INFERRED` getter calls; **misses `@Builder`** |
| **Spring Conditional SAT Truth Regions** | **YES** (`5 TRUTH_REGION`, `5 CONDITIONAL_FACT`, `92 REGION_FORMULA`, `47 CONDITION_EXPR`, 20+ witnesses) | **YES** (`5 TRUTH_REGION`, `5 CONDITIONAL_FACT`, `92 REGION_FORMULA`, `47 CONDITION_EXPR`, 20+ witnesses) | **NO** (`0` truth regions, `0` DI edges; flat graph merges mutually exclusive beans) | **NO** (`0` truth regions, `0` DI edges; flat graph) |
| **SQL DDL ↔ JPA `@Entity`/`@Table`** | `0/4` (pre-Wave-3) | **`4/4` tables** (`4` JPA links, `4` FKs, `1` view, `1` routine, `1` trigger) | `0/4` (`0` SQL nodes) | `0/4` (`10` SQL nodes, **0 JPA links**) |
| **Markdown ADR ↔ Code & Section Slice** | `0/2` (pre-Wave-3) | **`2/2` ADRs (`67` links, `2` wikilinks); exact section slice (`6/6` headings, L21–30)** | `0/2` (`--code-only` omits `.md`) | `0/2` (`--code-only` omits `.md`) |
| **Terraform & `.mcp.json`** | `0/2` (pre-Wave-3) | **`6` TF nodes (`2/2` resources, `4` refs, `0` leaks); `4` MCP env-names-only** | `0` TF nodes; `4` MCP `requires_env` | `13` TF nodes (**leaked raw secrets**); `4` MCP `requires_env` |
| **TypeScript Workspace Aliases** | Passive CST (pre-Wave-3) | **`3` `tsconfig` aliases + `2` `package.json` exports** | `4/4` `imports_from`; `0/2` HTTP bridges | `4/4` `imports_from`; `0/2` HTTP bridges |
| **Leiden Communities & Misplaced Drift** | `NO` (pre-Wave-2) | **Pure-Java Leiden: `8` clusters, `Q=0.5078`, `1` bridge; `LegacySettlementReconciler` Misplaced=`YES`** | `14` clusters (**silent Louvain fallback**); `0` drift report | `14` clusters (**silent Louvain fallback**; `.sql`/`.tf` reshuffled Java IDs); `0` drift report |
| **Community Super-Nodes (Visual Graph)** | `NO` (pre-Wave-2) | **`8` Community Super-Nodes** (externals isolated in `compound:external`, offline Cytoscape PNG/SVG, zero CDN) | HTML `vis-network` from external `unpkg.com` CDN (>5k cap) | HTML `vis-network` from external `unpkg.com` CDN |
| **RepoMap Trigram+IDF Recall@5** | Flat `contains()` (pre-Wave-1) | **`5/5` (`1.00`)** (rank #1 on all 5 queries) | N/A (BFS `query_graph`) | N/A (BFS `query_graph`) |
| **Secret Redaction & Zero Leakage** | Unredacted read slice (pre-Wave-1) | **`15/15` redacted (`34` markers, partial-PEM=`YES`), `0` source leaks / `0` advisory leaks** | `0/15` (only because `.tf`/`.yml` skipped) | **`6/15` RAW SECRET LEAKS** (Postgres URL creds, JWT, Bearer in `graph.json` + AST cache) |
| **MCP Tools & Resources** | HTTP-only `14` tools / `0` resources | **`17` tools / `6/6` MCP Resources** (Direct Local Store `--store` **100% CAS parity** + `--port` auto-bind) | `12` tools (`graphify-mcp`) | `12` tools (`graphify-mcp`) |
| **Multi-Page Wiki Dossier & ZIP** | `NO` (pre-Wave-2) | **`13` pages, deterministic ZIP `10,949 B` (`PK\x03\x04`, 2-run SHA-256 identical)** | `wiki.py` Markdown export | `wiki.py` Markdown export |
| **Advisory Quarantine Invariance** | Baseline | **YES** (snapshot/contexts/all 3,478 edges bit-identical; non-code mutation leaves `[6,458, 3,478]` unchanged) | **NO** (single mixed `graph.json`; non-code files reshuffled Java clusters) | **NO** (single mixed `graph.json`; `.sql`/`.tf` reshuffled `LegacySettlementReconciler` 1→2, `StatementLineItem` 7→1) |

---

## 4. Key Empirical Findings

1. **Tree-sitter Same-Arity Java Overload Failure (`CONFIRMED`):** Graphify `v0.9.120` creates hash-suffixed node IDs (`calculatefee`, `calculatefee_78b93d`, `calculatefee_c13345`, `calculatefee_f7c255`) for the four `FeeCalculator.calculateFee` overloads, all labeled `.calculateFee()`. Because Tree-sitter lacks type resolution and matches only call-site arity, **both** cross-file calls to `calculateFee(PaymentCommand)` (`AdyenPaymentGateway.java:39` and `PaymentSettlementOrchestrator.java:32`) were mis-wired to `calculateFee(long)` (`0/2` same-arity accuracy). Evo's canonical identities preserve full compiler parameter signatures (`[["declared",["type",["com","acme","ledger","domain","model"],"PaymentCommand"]]]`).
2. **Secret Leakage in Graphify `[terraform]` vs. Full-Document Pre-Slice Redaction in Evo (`CONFIRMED`):** Enabling `tree-sitter-hcl` caused Graphify `0.9.120` to persist 3 secret classes verbatim (`postgresql://ledger_admin:FakeTfDbPass77@...`, `eyJhbGciOi...`, `Bearer FakeBench...`) into both `graph.json` and the AST cache JSON (**6** leaked occurrences). In Evo AFTER, `LlmTransport.redactDocumentForPath()` redacts full documents with line-count preservation before line slicing; live probes confirmed **15/15** secrets redacted, **0** leaks in source or advisory payloads, and **line-20-only** partial PEM reads (omitting both `BEGIN` and `END` markers) cleanly redacted.
3. **Quarantine Prevents Cross-Modality Community Drift (`CONFIRMED`):** Adding `.sql` and `.tf` extras in Graphify altered its undirected graph topology and reshuffled Java cluster assignments (`LegacySettlementReconciler` shifted from community `1` to `2`; `StatementLineItem` shifted from `7` to `1`). In Evo AFTER, `AdvisoryOverlayService` (`advisory.doc-link:v1`, `ADVISORY_UNVERIFIED`) is computed strictly read-only from immutable CAS bytes and never enters canonical `KnowledgeGraph` digests, `LeidenCommunityDetector`, `PolicyEvaluator`, or `ArchitectureAssessmentV2`.
4. **Live Benchmarking Caught & Closed Real Production Gaps (`CONFIRMED`):** Round-1 live verification caught `WorkbenchMcpMain.openDirectStore()` omitting `RepairSource.registerContentFallback()` (blocking CAS-backed `.sql`/`.md`/`.tf` reads in `--store` mode), `WorkbenchMcpMain --port` reading `jobs` instead of `items`, partial-slice PEM redaction bypass, 320px Advisory panel overflow, and external JDK/library nodes entering visual-graph community clustering. Round-2 re-verification confirmed **all** findings closed with 100% CAS parity between direct-store `--store` MCP and live HTTP `WorkbenchApiMain`.

---

## 5. Scope, Limitations & Threats to Validity

- **Corpus Scale (`PROVISIONAL`):** `ledger-commerce-ab-v1` is a 51-file, 58.7 KB controlled corpus engineered for ground-truth precision across 10 orthogonal probes; throughput and RAM ratios on 10,000+ file polyglot monorepos require separate scale benchmarks (where Evo's `PartitionedGraphStore` + 4-layer ABI incremental cache avoid Graphify's 512 MiB full-JSON RAM cap).
- **Passive Cross-Module Resolution Under `I1` (`CONFIRMED`):** Because Evo enforces strict host execution safety (never running `mvn compile` on untrusted target repositories on the host), cross-module calls from `ledger-payments` to unbuilt sibling module `ledger-domain` (`PaymentGatewayPort`, `FeeCalculator`, `PaymentCommand`) remain explicitly `UNRESOLVED` with typed `CAPABILITY_GAP` records in passive mode (`0/6` cross-module `implements` resolved vs. Graphify's AST name match `6/6`), whereas intra-module resolution (`ledger-domain` overloads and Lombok getters) is 100% compiler-resolved. Full cross-module bytecode/APT resolution is achieved via the M8.4 isolated build worker (`--build=auto`).
- **Offline `--code-only` Comparison Scope (`CONFIRMED`):** Graphify was evaluated strictly offline (`--code-only`, `GRAPHIFY_BACKEND=none`) to match Evo's zero-LLM, zero-egress offline guarantee. Graphify's paid/cloud LLM semantic pass for Markdown/PDF/images was intentionally excluded under `I1` (no implicit third-party source upload) and `I4` (non-deterministic LLM edges).
- **Memory Footprint Trade-Off (`CONFIRMED`):** Python Tree-sitter AST indexing (`64–68 MiB` RSS) uses substantially less resident memory than a Java 21 JVM executing Maven model resolution, ASM bytecode indexing, JavaParser symbol solving, and DPLL Spring SAT (`1,356 MiB` peak RSS on `-Xmx2g`), while finishing 2.5×–6.1× faster in wall-clock time.

---

## 6. Reproducibility

The complete benchmark corpus, `ground_truth.json`, hardened `docker/Dockerfile` + `docker/run_graphify_docker.ps1`, `scripts/run_evo_benchmark.py`, `scripts/inspect_graphify_output.py`, `scripts/compare_ab.py`, and raw JSON artifacts live outside Git in the scratch benchmark bundle (`scratch/graphify-ab-bench/`):

```powershell
# 1. Verify pinned corpus SHA-256 (36e3f3bc6f4f53bd2e959f59ab39743e6b54a782989a8b64ebc424ec5f518303)
python scripts\corpus_manifest.py --corpus corpus --ground-truth ground_truth.json --output results\corpus_manifest.json

# 2. Run Evo benchmark (offline scan + direct-store MCP + live loopback HTTP + quarantine probe)
python scripts\run_evo_benchmark.py --jar d:\UIT\AI-native-verified-software-evolution\cli\target\evo.jar --corpus corpus --ground-truth ground_truth.json --out results\evo-after --label evo-after --before-metrics results\evo-before\metrics.json

# 3. Run Graphify v0.9.120 strictly inside hardened Docker (--network=none --read-only)
.\docker\run_graphify_docker.ps1
.\docker\run_graphify_docker.ps1 -WithExtras -ExtrasList "sql,terraform"

# 4. Regenerate 3-way A/B scorecard
python scripts\compare_ab.py --bench-root .
```
