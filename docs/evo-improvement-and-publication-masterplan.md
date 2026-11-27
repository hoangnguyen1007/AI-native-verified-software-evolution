# EVO MASTER ENGINEERING & SCIENTIFIC PUBLICATION BLUEPRINT (2026–2027)

*Kế hoạch Tổng thể Cải tiến Kỹ thuật Cấp Mã nguồn, Kiến trúc Nghiên cứu Mũi nhọn (Frontier) & Lộ trình Công bố Khoa học Quốc tế cho Hệ thống Evo*

> **Document Role:** Canonical, self-contained Master Engineering & Publication Blueprint inside the repository (`docs/evo-improvement-and-publication-masterplan.md`), engineered for zero-context-loss resumption by future high-reasoning agent sessions (e.g., Gemini 4 Neon XHigh) and human maintainers.  
> **Baseline Commit Audited:** `evo.jar` (`HEAD cb79c322`, `--mode=fast --spring-sat --build=off`, `JDK 21.0.12.1 LTS`)  
> **Strategy & Audit Date:** `2026-11-26` (all conference deadlines, GitHub/Zenodo datasets, source-file paths, and line anchors directly verified on this date)  
> **Cross-Reference Baseline Report:** [`evo-comparative-evaluation-report.md`](evo-comparative-evaluation-report.md) (v6 empirical baseline across `39` peer-reviewed/technical works `2000–2026` & `9` open-source benchmark repositories)  
> **PC-Chair Audit Status:** **ACCEPTED (`CONFIRMED`)** — incorporates 100% of Round 1 & Round 2 adversarial PC-Chair/Reviewer #2 corrections (Shopizer `41.89 s` pre-spill floor, TeaStore anti-overfitting split, `F4+F4b` realistic sizing & bespoke-DSL ban, Tết Nguyên Đán `06/02/2027` calendar & `Micans` budget, EMSE '25 per-app mean units & `ASSUMPTION ~730 GT`, OpenRewrite `122` Strict vs `124/220` `SOURCE_SCOPED`, non-tautological `S1` Go/No-Go thresholds, and Pillar 2 storage ablation).

---

## Mục lục Điều hướng (Master Navigation)

- [0. Agent Bootstrap & Operating Contract](#0-agent-bootstrap--operating-contract)
- [1. Verified Empirical Baseline & Root-Cause Map (`HEAD cb79c322`)](#1-verified-empirical-baseline--root-cause-map-head-cb79c322)
- [2. Ranked Publication Portfolio, 2026–2027 Calendar & Before→After Target Matrix](#2-ranked-publication-portfolio-20262027-calendar--beforeafter-target-matrix)
- [3. Deep Code-Level Engineering Blueprint (Executable Work Packages)](#3-deep-code-level-engineering-blueprint-executable-work-packages)
  - [3.1 Pillar A — Storage, Spill & Warm Cache (`P2-1`..`P2-6`)](#31-pillar-a--storage-spill--warm-cache-p2-1p2-6--total-105110-d)
  - [3.2 Pillar B — Microservice, Polyglot & Java Extractors (`F1`..`F8`)](#32-pillar-b--microservice-polyglot--java-extractors-f1f8--total-195210-d-20802330-loc)
  - [3.3 Pillar C — Spring Auto-Configuration ASM + COI-Sliced CDCL SAT (`S1`..`S5`)](#33-pillar-c--spring-auto-configuration-asm--coi-sliced-cdcl-sat-s1s5--total-170200-d--gono-go-gate)
  - [3.4 Pillar D — GraphRAG & Closed-Loop Verified Architectural Repair (`R1`..`R4`)](#34-pillar-d--graphrag--closed-loop-verified-architectural-repair-r1r4--deferred-to-phase-3-except-optional-r1)
- [4. Reproducible Benchmark Harness & Ablation Protocols (`E1`..`E5`)](#4-reproducible-benchmark-harness--ablation-protocols-e1e5)
- [5. Deep Open Research Questions & Autonomous Investigation Prompts (RQ-1..RQ-8)](#5-deep-open-research-questions--autonomous-investigation-prompts-rq-1rq-8)
- [6. Pre-Emptive Reviewer #2 Kill List (10 Fatal Objections & Exact Defenses)](#6-pre-emptive-reviewer-2-kill-list-10-fatal-objections--exact-defenses)
- [7. Sprint-by-Sprint Execution Schedule (Nov 2026 → Jul 2027)](#7-sprint-by-sprint-execution-schedule-nov-2026--jul-2027)
- [8. Verified Master Source-File Index (Existing vs. Planned/New)](#8-verified-master-source-file-index-existing-vs-plannednew)
- [9. Key Public Benchmark & Reference Directory](#9-key-public-benchmark--reference-directory)

---

## 0. Agent Bootstrap & Operating Contract

### 0.1. Mission & Strategic Objective

Mục tiêu của tài liệu này là biến **Evo** từ trạng thái cơ sở `cb79c322` (nơi Evo có `Precision = 1.000`, `0` lỗi span, `DPLL SAT` trên fixture tổng hợp và `QEMU`/`Hyper-V Negative Control`, nhưng bị chặn xuất bản Research Track vì 4 lỗi kỹ thuật khu trú) thành một **nền tảng nghiên cứu đạt chuẩn công bố CORE A\* / Q1** theo lộ trình 3 nhịp (`11/2026 – 07/2027`):

1. **Nhịp 0 (Launchpad — Track 4):** **FSE 2027 Tool Demonstrations** (`CORE A*`, hạn chính thức `26/01/2027`) sau khi hoàn tất **P0 (`9.5–10.0` ngày lập trình)**.
2. **Nhịp 1 (Bài Chính #1 — Track 2):** *Host-Safe, Evidence-Typed Static Architecture Recovery for Polyglot & Multi-Framework Microservices* → **EMSE Q1 Springer** (mục tiêu chính không nén lịch, đầu `04/2027`) hoặc **TOSEM** (`CORE A*`), với nhánh tăng tốc **ASE 2027 Research** (`dự kiến ~10/03/2027`).
3. **Nhịp 2 (Bài Kế tiếp Đỉnh cao #2 — Track 1):** *Variability-Aware, Phase-Ordered Static Analysis of Spring Conditional DI via COI-Sliced CDCL SAT* → cổng **Go/No-Go 1 tuần phi hiển nhiên (`S1`)** rồi tiến tới **ICSE 2028 Research** (`dự kiến ~01/07/2027`) hoặc nhánh rẽ **OOPSLA 2027 Round 2** (`đã công bố 07/04/2027`).
4. **Pha 3 (Hoãn chiến lược — Track 3):** *Symbol-SKG GraphRAG (`RepoProbe`) & Host-Isolated Verified Architectural Refactoring (`RefAgent`/`MANTRA`)* chỉ mở sau khi Track 4/2/1 ổn định.

### 0.2. Non-Negotiable Engineering & Scientific Invariants

Mọi agent thực thi bất kỳ gói công việc nào (`P2-*`, `F*`, `S*`, `R*`) **BẮT BUỘC** giữ vững 6 bất biến sau (vi phạm dù chỉ 1 bất biến = **tự động loại bản sửa**):

| # | Bất biến (Invariant) | Quy định Cứng (Hard Contract) | Kiểm chứng Tự động Bắt buộc |
| :--- | :--- | :--- | :--- |
| **I1** | **Host Safety (`--build=off`)** | Tuyệt đối **KHÔNG** thực thi `mvn`, `gradle`, script, plugin, annotation processor, hay build lifecycle của repository mục tiêu trực tiếp trên máy chủ. Tải `.jar`/`.pom` phụ thuộc chỉ được thực hiện **thụ động qua HTTPS không dùng credential môi trường** (`MavenDependencyArtifactProvider.java:28-34`) và đọc `.class` qua **ASM bytecode reader**. Thực thi mã mục tiêu/bản vá LLM chỉ được phép trong sandbox cô lập (`DOCKER_HARDENED_CONTAINER` hoặc `QEMU_IN_DOCKER` / `Hyper-V`). | Audit không có `ProcessBuilder` gọi build mục tiêu trong `analyzer*`/`backend`/`cli`; kiểm thử `--offline --build=off` không phát sinh lưu lượng mạng hay tiến trình con. |
| **I2** | **100% Bit-Identical SHA-256 Determinism** | Mọi lượt chạy ngoại tuyến lặp lại trên cùng `(snapshot, build contexts, options, provider versions)` — kể cả giữa **Cold Run**, **Warm `NO_CHANGE`**, và **Warm 1-File Edit** (so với Cold Run trên phiên bản đã sửa) — phải xuất ra `graphIdentity` `SHA-256` **trùng khớp tuyệt đối 100%**. Mọi tập hợp/map trước khi ghi hoặc băm phải sắp xếp thứ tự tất định (`TreeMap` / `Comparator`). | Regression test so sánh `cold.graphId().equals(warm.graphId())` và `coldEdited.graphId().equals(incrementalEdited.graphId())`. |
| **I3** | **Closed Reporting Denominator (`CapabilityGapRecord`)** | Không bao giờ âm thầm xóa, che giấu hay bỏ qua thực thể chưa phân giải (`SEMANTIC_UNRESOLVED`), không hỗ trợ (`ROUTE_UNSUPPORTED`), hay vượt giới hạn (`SOLVER_LIMIT`). Gộp gap (`P2-1`) **phải** bảo toàn đủ `occurrenceCount`, `K=3` span đại diện đầu tiên, và `SHA-256` digest của toàn bộ danh sách span gốc. | `CapabilityGapContractTest`: tổng `occurrenceCount` trước/sau gộp bằng chính xác số `ObservationRecord` chưa phân giải. |
| **I4** | **Zero Hallucination & Explicit Soundness Tiers** | Không suy đoán kiểu/tọa độ/cạnh không có căn cứ nguồn. Mọi cạnh hoặc lời gọi suy luận theo phạm vi nguồn phải gắn nhãn provenance tường minh (`EXACT_BYTECODE` vs `SOURCE_SCOPED` vs `DECLARATIVE_CONFIG` vs `DFD_SYNTHETIC_ACTOR`). Tách rạch ròi `--projection-profile=strict` (`S5`) với `--projection-profile=dfd-export` (`S6`), và tách `122/220` Strict Resolved (`F8b`) với `124/220` `SOURCE_SCOPED` (`F8c`). | Contract test xác nhận chế độ `strict` không phát sinh nút `user` hay cạnh `@EnableZuulProxy` phỏng đoán; `F8b` không hạ ngưỡng `requireRecoveryHierarchy`. |
| **I5** | **Strict Anti-Overfitting Ban (CẤM Hardcoding Benchmark)** | **CẤM TUYỆT ĐỐI** viết quy tắc riêng tên lớp/enum/DSL đặc thù cho từng benchmark: **(1) CẤM hardcode `ServiceLoadBalancer`, `LoadBalanced*Operations`, hoặc `tools.descartes.teastore.registryclient.Service.<ENUM>` của TeaStore**; **(2) CẤM viết parser bespoke cho `logstash.conf` của `blog-microservices`**. Chỉ được xây dựng bộ trích xuất cho **chuẩn công nghiệp mở** (Docker Compose, Kubernetes YAML, Spring Boot/Cloud YAML, Prometheus `scrape_configs`, Grafana provisioning YAML, Jakarta/Javax JAX-RS `@Path`/`WebTarget`, `@WebServlet`, Spring AMQP/Kafka/Cloud Stream, gRPC/Protobuf). | Code review & grep gate: `0` chuỗi `"ServiceLoadBalancer"`, `"teastore"`, `"logstash.conf"`, `"blog-microservices"`, `"piggymetrics"`, `"petclinic"` trong bất kỳ file `src/main/` nào! |
| **I6** | **Storage & Repo Cleanliness** | Không lưu repository benchmark đã clone, cache `.m2`, ảnh Docker/VM, hay tập dump lớn trong Git (`D:\UIT\AI-native-verified-software-evolution`). Mọi benchmark clone và đầu ra thô đặt ngoài Git (ví dụ `scratch/`). Trong PowerShell, luôn đặt tham số Maven `-D...` trong dấu nháy đơn/kép (ví dụ `'-Dtest=...'`). | `git --no-optional-locks status --short` chỉ chứa mã nguồn/test/docs gọn nhẹ thuộc phạm vi gói công việc. |

### 0.3. Epistemic Status Tag Taxonomy

Mọi phát biểu định lượng hoặc quyết định trong tài liệu này và trong mọi báo cáo kế tiếp của agent **BẮT BUỘC** mang một trong 5 nhãn nhận thức chuẩn từ `AGENTS.md`:

- **`CONFIRMED`**: Sự thật đã đo trực tiếp trên `evo.jar` (`cb79c322`), xác minh trực tiếp trong mã nguồn Evo / repository chuẩn, hoặc hạn nộp/quyết định chính thức đã được con người/hội nghị xác nhận.
- **`PROVISIONAL`**: Dự báo bảo thủ hoặc thiết kế kỹ thuật đã thông qua kiểm toán dòng mã & chiết khấu tổng quát hóa, chờ kiểm chứng thực nghiệm sau khi triển khai.
- **`HYPOTHESIS`**: Giả thuyết nghiên cứu có thể kiểm chứng (hoặc bác bỏ) bằng thực nghiệm — không được cam kết thành kết quả bài báo khi chưa vượt qua cổng thực nghiệm (ví dụ cổng Go/No-Go `S1` của Track 1).
- **`ASSUMPTION`**: Giả định làm việc tạm thời (ví dụ mẫu số ước tính `~730 GT` cho 17 app `microSecEnD` trước khi chạy parser trực tiếp trên `M3SOulu/EMSE2025SAR-Replication`).
- **`OPEN QUESTION`**: Bài toán nghiên cứu/thuật toán còn mở cần điều tra sâu hơn (Mục 5).

### 0.4. Autonomous Resumption Loop for Future High-Reasoning Agents

Khi một phiên agent lý luận cao (ví dụ **Gemini 4 Neon XHigh**) mở tài liệu này để tiếp tục triển khai hoặc nghiên cứu, hãy tuân thủ chính xác **vòng lặp 7 bước** sau (không cần đọc lại lịch sử hội thoại cũ):

```text
STEP 1 [Bootstrap]:
  - Đọc AGENTS.md + docs/current-state.md + Mục 0 & Mục 3 tương ứng trong file này.
  - Chạy `git --no-optional-locks status --short; git --no-optional-locks log -n 5 --oneline`
    để xác định các gói (P2-*, F*, S*, R*) đã land và nhánh làm việc hiện tại.

STEP 2 [Select Next Atomic Work Package]:
  - Chọn ĐÚNG 1 gói công việc chưa xong theo thứ tự ưu tiên:
    Sprint 1 (P0): P2-1 -> P2-2 -> P2-3 -> P2-4 -> F1 -> F6a -> F6b -> F8
    Sprint 3 (P1): P2-5 -> P2-6 -> F4+F4b -> F2 -> F3 -> F5 -> F7(Generic) -> B1/B2
    Sprint 5 (Go/No-Go): S1 Spike trên 5 OSS -> Đánh giá 3 tiêu chí Mục 3.3.6 -> S2..S5 (nếu GO)

STEP 3 [Inspect Target Anchors Before Editing]:
  - Đọc chính xác các tệp & dòng neo liệt kê trong gói công việc (Mục 3) và tệp test liên quan.
  - Kiểm tra Anti-Overfitting Ban (I5) trước khi thiết kế thuật toán.

STEP 4 [TDD — Failing Specification/Regression Test First]:
  - Viết test JUnit 5 tối giản tái hiện chính xác lỗi (ví dụ MediaType.* constant,
    Go split-file Unimplemented*Server, Shopizer gap coalescing count, NO_CHANGE SHA-256).
  - Xác nhận test FAIL trước khi sửa `src/main/`.

STEP 5 [Minimal Coherent Implementation]:
  - Cài đặt thuật toán theo đúng Data Contract trong Mục 3; bảo toàn I1–I5.
  - Chạy lệnh Maven có trích dẫn PowerShell `mvn -pl <module> '-Dtest=<TestClass>' test`.

STEP 6 [End-to-End Parity & Determinism Check]:
  - Với P0/P1: build `evo.jar` (`mvn -pl cli -am '-DskipTests' package`) và chạy lại
    ít nhất 1 repo chuẩn tương ứng (Cold + Warm NO_CHANGE + Warm 1-File Edit),
    xác nhận `SHA-256` đồng nhất 100% và không vi phạm Anti-Overfitting grep gate.

STEP 7 [Compact Durable State Update & Handoff]:
  - Cập nhật `docs/current-state.md` (chỉ trạng thái mới) và xuất Lean Slice Handoff:
    (1) Files Changed, (2) Exact Verification Command & Measured Metric Delta, (3) Next Work Package.
```

---

## 1. Verified Empirical Baseline & Root-Cause Map (`HEAD cb79c322`)

Toàn bộ số liệu dưới đây là **`CONFIRMED`** từ chạy thực tế `evo.jar` (`cb79c322`, `SHA-256: 089df794...`, `--mode=fast --spring-sat --build=off`, Testbed: `Intel Core i5-14400F` `10C/16T`, `31.79 GB` RAM, `Windows 11 Pro 26100`, `JDK 21.0.12.1 LTS`, `Maven 3.9.16`) và giải phẫu trực tiếp mã nguồn + đĩa SQLite/CAS. Xem bảng đối chiếu đầy đủ 39 công trình tại [`evo-comparative-evaluation-report.md`](evo-comparative-evaluation-report.md).

### 1.1. Master Empirical Baseline Across All Evaluated Benchmarks (`CONFIRMED`)

| Nhóm Benchmark | Repository / Fixture (`Commit`) | Quy mô (`Files / kLOC / Ctx`) | Chỉ số Chính Thực đo (`cb79c322`) | Cold / Warm Wall (`s`) | Chẩn đoán Cốt lõi (Root-Cause Anchor) |
| :--- | :--- | ---: | :--- | ---: | :--- |
| **EMSE '25 (5-Repo)** | `spring-petclinic-microservices` (`81152fa`, Maven B) | `84 / 4.4 / 8` | Cp `8/12`, Cn `1/28`, E `10/10` (`15/15` HEAD) | `62.98 / 47.01` *(Mode A: `44.61 / 29.50`)* | Thiếu `F4` (`20` infra), `F3` (`2` Gateway YAML), `F2` (`VisitsServiceClient` non-final field) |
| **EMSE '25 (5-Repo)** | `sqshq/piggymetrics` (`6bb2cf9`, Maven A) | `105 / 6.7 / 9` | Cp `9/17`, Cn `0/37`, E `10/10` (`11/11` HEAD) | `60.20 / 44.81` | Thiếu `F4` (`19` infra, `5` compose DB/RMQ), `F2` (`4` `@FeignClient`, `3` OAuth2), `F3` (`4` Zuul), `F5` (`4` AMQP) |
| **EMSE '25 (5-Repo)** | `ewolff/microservice` (`d1ccf50`, Maven A) | `42 / 2.4 / 6` | Cp `6/7`, Cn `0/13`, E **`0/15`** | `29.44 / 21.66` | **`F1`:** `11` thiếu `*Mapping` + `4` lỗi `MediaType.*`; thiếu `F2` (`LoadBalancerClient`), `F4` (`6` infra) |
| **EMSE '25 (5-Repo)** | `microservices-basics-spring-boot` (`ac0a249`, Gradle A) | `~60 / 3.1 / 9` | Cp `9/12`, Cn `0/29`, E **`0/8`** | `50.07 / 46.17` | **`F1`:** `6` thiếu `*Mapping` + `1` `MediaType.*` + `1` HEAD drift; thiếu `F4` (`21` infra), `F2` (`OAuth2RestTemplate`) |
| **EMSE '25 (5-Repo)** | `blog-microservices` (`1681bdb`, Gradle A) | `~55 / 2.5 / 11` | Cp `11/17`, Cn `0/42`, E **`0/10`** | `43.32 / 40.66` | **`F1`:** `10` thiếu `*Mapping`; thiếu `F4` (`24` infra), `F5` (`10` RMQ/Stream), `F2` (`3` `LoadBalancerClient`) |
| **Gộp 5 EMSE '25** | `267` GT Table 1 (`65 Cp, 149 Cn, 53 E` / `63` HEAD) | `5 repos` | **Cp `0.796` (`43/65`), Cn `0.013` (`1/149`), E `0.548` (`20/53`), Overall `0.387` (`P=1.000`)** | **`49.2 / 40.1` (TB/app)** | `27+5/33` Endpoint là lỗi 35-LOC (`F1`); `90/148` (`60.8%`) Connection là control-plane/infra (`F4`) |
| **Polyglot Microsvc** | *Online Boutique* (`c7cb6d5`, 5 langs + `.proto`) | `67 / 24.5 / 1` | **Svc `11/12` (`0.957`), Deps `11/16` (`0.815`), Raw `0.688` (`5` self-loop FP), Comb `0.880`** | `22.43 / 16.25` (`1.38×`; sem `16.47×`) | `3/5` gRPC FN từ lọc file `GrpcSourceSyntax:36`; `5` FP từ `GrpcServiceCallProjection:43`; `1/5` Locust+Go `var_spec` (`F6b`); `1` Svc+`1` Edge từ K8s YAML (`F4-K8s`) |
| **Non-Spring Java** | *TeaStore* (`34b37f7`, JAX-RS / `@WebServlet`) | `409 / 25.7 / 11` | **Cp `6/7` (`0.923`), JAX-RS `0/41` (`0/61`), Servlet `0/20`, Edges `0/13`, `9` Locust FP** | **`68.22 / 89.72` (`0.76×` CHẬM!)** | `SpringSourceEvidence:140` bỏ `javax/jakarta.ws.rs.*`; `graph_publish_ms` tăng `34.45 s → 60.43 s` (`+75.4%`); `EvoApp` bỏ qua `NO_CHANGE` |
| **Java 21 & OpenRewrite** | `spring-petclinic` (`818c4136`, `220` calls) & Java 21 fixture | `50 / 4.3 / 1` | **Java 21: `6/7` (`85.71%`); Config A: `116/220` (`52.73%`, `0` span err); Config B: `220/220` (`0` span err)** | `25.41 / 19.91` (`500158f`, `17/17` EP) | Benchmark ghim `JavaParser 3.26.1` cũ (prod `3.28.2`+`AsmTypeSolver` đã đạt `7/7`!); Config A thiếu `2` overload arity (`ORW-2`) + `4` controller (`ORW-1a`) + `2` repo interface (`ORW-1b`) |
| **GraphRAG / Spill** | `Shopizer` (`6a4a0a6`, `1,173` Java units, `6` modules) | `1,340 / 115.1 / 6–12` | **TIMEOUT `>180 s` (`6/6` lượt: `180.12–180.45 s`)**; `1,173/1,173` Java parsed (`0` dropped) | **`>180 s` / `>180 s`** | Tiền-spill mất **`41.89 s`** (`intake 3.46 s` + `polyglot 2.70 s` + `JavaParser 34.12 s`), sau đó **`137.43 s`** kẹt `FileAnalysisWorkspace` (`428k–965k` rows, `395–549 MB` `values.bin`, **`96.8–97.4%` `SEMANTIC_UNRESOLVED`**) |
| **Spring SAT Reality** | `9` Real OSS (`1,573` main `.java`) + `17` cached `*autoconfigure*.jar` | `1,573` files / `2,218` `.class` | User code 9 OSS: **`5` `@Profile`, `0` `@ConditionalOn*`**; Shopizer user `4` `@Profile` vs **`1,333` starter JAR markers (`99.7%`)**; `41` `bootstrap.*` bị bỏ | Synthetic `79/79` runtime, `4/4` order, `12/12` P01 (`0` FP) | `AutoConfigurationMetadata:14-57` không đọc ASM bytecode; `ConditionEvidenceLowering:392-408` khóa 2 artifact; `MAX_DIMENSIONS=32`; naive DPLL clone `byte[]` |
| **Enterprise Scaling** | `C10K` → `OWASP Dep-Track` (`415.6k` LOC) → `MP-Manager` (`794.2k` LOC) → `ThingsBoard` (`1.95M` LOC) | `3` → `13,920` files | Dep-Track: `444.8k` nodes (`919 MB`); MP-Manager: `2.22M` nodes (`4.01 GB`); Halo: `1.51 GB` in `-Xmx256m`; ThingsBoard: `100%` CAS, `MEMORY_LIMIT` at `1,467 s` (`-Xmx8g`) | Dep-Track `1,089.45 s / 4.38 s`; MP-Manager `843.22 s / 0.10 s` | `LocalAnalysisRunner:1670` có `NO_CHANGE` nhanh nhưng CLI `EvoApp` chưa nối; `ThingsBoard` cần luồng phân mảnh vượt `-Xmx8g` (xem RQ-6) |

### 1.2. Exact Root-Cause Attribution Matrix (Every Single Count Reconciled)

#### A. EMSE '25 5-Repo Exact Reconciliation (`64 TP + 203 FN = 267 GT`, `0 FP`)

| Loại Mục GT | Mã Bucket | Nguyên nhân Chính xác trong Mã nguồn Evo | PetClinic-MS | PiggyMetrics | ewolff | anilallewar | blog-ms | Tổng FN | Tỷ trọng | Gói Sửa |
| :--- | :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :--- |
| **Components (`FN=22`)** | `CP-D1` | `StaticPolyglotScan.parseDockerCompose()` (`L756–844`) đã parse `compose_service` nhưng `PolyglotScan.project()` (`L172–178`) không nâng lên nút `INFRA_COMPONENT` | 3 | 5 | 0 | 1 | 4 | **13** | `59.1%` | `F4` |
| **Components (`FN=22`)** | `CP-D2` | Tác nhân ngoài `user` trong mô hình DFD `microSecEnD` (Evo strict không tự sinh actor người dùng) | 1 | 1 | 1 | 1 | 1 | **5** | `22.7%` | `F4b` *(dfd-export)* |
| **Components (`FN=22`)** | `CP-D3` | Dịch vụ ngoài (`spring.cloud.config.server.git.uri`, `spring.mail.host`, `@FeignClient(url=...)`) | 0 | 2 | 0 | 1 | 1 | **4** | `18.2%` | `F4` / `F2` |
| **Endpoints (`FN=33` T1 / `37` HEAD)** | `EP-E1` | `SpringSourceEvidence.SIMPLE_SPRING_ANNOTATIONS` (`L78–125`) thiếu `Request/Get/Post/Put/Delete/PatchMapping` → `WebRouteMapper:37` đánh dấu `unknown` (`ROUTE_UNSUPPORTED`) | 0 | 0 | 11 *(16 H)* | 6 | 10 | **27** *(32 H)* | `81.8%` | **`F1` (P0)** |
| **Endpoints (`FN=33` T1 / `37` HEAD)** | `EP-E2` | `LiteralConditionAnnotation:63,124` parse `MediaType.*_VALUE` là `Enums` → `WebRouteMapper.decode():98` ném `IllegalArgumentException` | 0 | 0 | 4 | 1 | 0 | **5** | `15.2%` | **`F1` (P0)** |
| **Endpoints (`FN=33` T1 / `37` HEAD)** | `EP-E3` | Độ lệch `anilallewar` HEAD (`7` `@RequestMapping` hoạt động vs `8` Table 1 — endpoint `/oauth/token` ngầm định hoặc bị xóa ở HEAD) | 0 | 0 | 0 | 1 *(0 H)* | 0 | **1** *(0 H)* | `3.0%` | *(Giữ nguyên FN)* |
| **Connections (`FN=148`)** | `CN-D` | **Nút thắt Pareto #1 (`60.8%`):** Control-plane (`31` Config-Server, `30` Eureka), Observability (`24` Zipkin/Prometheus/Grafana/Turbine/ELK), DB (`5` Mongo/MySQL) trong `bootstrap/application.yml` & Compose | 20 | 19 | 6 | 21 | 24 | **90** | **`60.8%`** | **`F4` (P1)** |
| **Connections (`FN=148`)** | `CN-E` | `EventFlowProjection:22–49` chỉ nhận non-Java Tree-sitter; Java bỏ qua Tree-sitter (`StaticPolyglotScan:191`) → thiếu RabbitMQ/AMQP/Cloud Stream/Turbine-Stream/Zipkin-Stream | 0 | 4 | 0 | 0 | 10 | **14** | `9.5%` | **`F5` (P1)** |
| **Connections (`FN=148`)** | `CN-F` | Cạnh từ tác nhân ngoài `user → gateway/dashboard` và `config-server → git` / `notification → smtp` | 4 | 3 | 2 | 2 | 3 | **14** | `9.5%` | `F4` / `F4b` |
| **Connections (`FN=148`)** | `CN-B` | `HttpClientConstants:39–44` từ chối biến effectively-final/trường non-final; `Extraction:1677–1734` thiếu Spring 6 `RestClient`, `OAuth2RestTemplate`, `DiscoveryClient`, `LoadBalancerClient`, `String.format` & chế độ `SOURCE_RECOVERY` | 1 *(+2 H)* | 3 | 2 | 2 | 3 | **11** | `7.4%` | **`F2` (P1)** |
| **Connections (`FN=148`)** | `CN-C2` | `@EnableZuulProxy` tự định tuyến theo Eureka (`ewolff`) hoặc `zuul.routes` nằm ở config-repo Git bên ngoài (`anilallewar`, `blog-ms`) | 0 | 0 | 3 | 4 | 2 | **9** | `6.1%` | `F4b` *(dfd-export)* |
| **Connections (`FN=148`)** | `CN-C1` | `StaticPolyglotScan` chưa parse `spring.cloud.gateway.*.routes` (`lb://`, `StripPrefix`) & `zuul.routes.*` (làm đứt luôn 5 JS `fetch` của `petclinic-ms`) | 2 *(+1 H)* | 4 | 0 | 0 | 0 | **6** | `4.1%` | **`F3` (P1)** |
| **Connections (`FN=148`)** | `CN-A` | Thiếu hoàn toàn bộ trích xuất `@FeignClient(name, url)` trong `Extraction` & `SpringSourceEvidence` | 0 | 4 | 0 | 0 | 0 | **4** | `2.7%` | **`F2` (P1)** |

#### B. Shopizer Pre-Spill Floor (`41.89 s`) & Spill Amplification Forensics (`CONFIRMED`)

Từ timestamp tệp trên đĩa của `ws-shopizer-clean-xmx8g/.evo` (`-Xmx8g`, offline SAT) và giải nén trực tiếp `index.sqlite` + `values.bin` trên cả `5` bản chụp Shopizer bị timeout:

```text
[t = +0.00 s -> +3.46 s]   +3.46 s  acquisition/metadata (intake: 1,340 files)
[t = +3.46 s -> +7.77 s]   +4.31 s  incremental-cache/polyglot-v3 (1,212 files) + build model prep
[t = +7.77 s -> +41.89 s] +34.12 s  incremental-cache/java-sets (1,173 JavaParser units / 6 Maven modules)
-------------------------- -------- -----------------------------------------------------------------------
PRE-SPILL FLOOR           = 41.89 s (KHÔNG bị ảnh hưởng bởi P2-1..P2-4 -> Cold Win không thể <45 s nếu chưa song song hóa JavaParser!)
-------------------------- -------- -----------------------------------------------------------------------
[t = +41.89 s -> +179.32 s] +137.43 s  KẸT TRONG FileAnalysisWorkspace SPILL (kill tại 180 s!)
  - Offline SAT (-Xmx8g):    558,592 rows | index.sqlite 91.70 MB | values.bin 502.34 MB | 74,529 unique space=1 gaps (96.8% SEMANTIC_UNRESOLVED, avg 2,090.8 B raw / 831.8 B comp)
  - Offline No-SAT Run 2:    965,792 rows | index.sqlite 161.32 MB| values.bin 549.17 MB | 73,596 unique gaps copy qua 72 spaces!
  - Prepared (313 JARs):     428,032 rows | index.sqlite 69.77 MB | values.bin 395.46 MB | 63,142 unique space=1 gaps (97.4% SEMANTIC_UNRESOLVED, avg 2,098.9 B raw / 832.7 B comp)
```

#### C. TeaStore `0.76×` Warm Slowdown & Cross-Repo `graph_publish_ms` Regression (`CONFIRMED`)

Trên `6/7` repository `swarm-v4`, giai đoạn `graph_publish_ms` **chậm hơn khi chạy Warm** (`+5.3%` đến `+75.4%`), đạt đỉnh điểm ở TeaStore (`Cold 68.22 s → Warm 89.72 s`, trong đó `graph_publish_ms` tăng `34,452 ms → 60,428 ms`, `+26.0 s`) dù `last-incremental-summary.json` ghi nhận `mode="NO_CHANGE"`, `reusedModules=11`, `recomputedModules=0`, `polyglotCacheHits=233`:

1. **`W1` (`EvoApp.java:926–1140`):** CLI `evo scan` tính xong `fileDelta = 0/0/0` nhưng **không short-circuit `NO_CHANGE`** (khác `LocalAnalysisRunner.java:1670`), gán cứng `reusedGraphContexts=0` (`L1139`) và `baseGraphId=Optional.empty()` (`L1136`).
2. **`W2` (`UniversalArchitecturePipeline.java:39–41`):** `DISK_SPILL_MIN_CONTEXTS = 8` ép TeaStore (`11` contexts) tràn đĩa `FileAnalysisWorkspace` dù 100% hit cache, đốt `20,729 ms` ở `semantic_ms` với `0` lượt parse Java!
3. **`W3` (`StreamingContentStore.java:300–350`):** `writeInternal()` ghi đủ `662` trang Merkle (`133.53 MB` / `127.34 MiB`) ra file tạm, thấy `hasLoose(handle)==true` (`L334`), rồi gọi `verify(handle)` (`L335`) **mở, đọc và băm SHA-256 lại toàn bộ `662` file trang cũ** trước khi xóa `662` file tạm.
4. **`W4` (`PartitionedGraphStore.java:772–778`):** `assemble()` stream+`fsync` `95.09 MB` (`90.68 MiB`) `.graph.json` ra `.part`, gặp `FileAlreadyExistsException` (`L774`), rồi gọi `!hash(target).equals(hash(pending))` (`L776`) — đọc & băm lại **`190.18 MB`** dù `frame.digest()` đã có sẵn!
5. **`W5` (`PartitionedGraphStore.java:1036, 1328–1450`):** Luôn gọi `canonicalDigest(root, cancelled, true, frame)` dựng lại **SQLite kiểm toán thứ hai (`graph-audit-*.sqlite`)**, chạy 3 phép `LEFT JOIN` và băm lại toàn bộ JSON lần thứ 3.

#### D. Spring Conditional DI Bytecode Reality (`CONFIRMED`)

| Phạm vi Kiểm kê | Tổng `.java` / `.class` | `@Profile` | `@ConditionalOnMissingBean` | `@ConditionalOnClass` | `@ConditionalOnBean` | `@ConditionalOnProperty` | `@AutoConfigureAfter/Before` | Khác (`Web/MissingCls/Expr`) | Tổng Marker |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **User `src/main/java` — Shopizer (`6a4a0a6`)** | `1,173 .java` | `4` | `0` | `0` | `0` | `0` | `0` | `0` | **`4`** (`0.3%`) |
| **Starter Bytecode — `m2-shopizer` (`5` JARs, Boot 2.5.12)** | `1,768 .class` | `2` | `375` | `368` | `174` | `117` | `152 / 51` | `85 / 7 / 2` | **`1,333`** (**`99.7%`**) |
| **User `src/main/java` — Toàn bộ `9` Real OSS** | `1,573 .java` | `5` | **`0`** | **`0`** | **`0`** | **`0`** | **`0`** | **`0`** | **`5`** *(+`41` `bootstrap.*` bị bỏ)* |
| **Starter Bytecode — `m2-cache` (`12` JARs, Boot 4.1.2 + Spring AI 2.0.2)** | `450 .class` | `0` | `60` | `27` | `20` | `21` | `3 / 3` | `6 / 8 / 2` | **`150`** (`6` factories / `50` imports) |

---

## 2. Ranked Publication Portfolio, 2026–2027 Calendar & Before→After Target Matrix

### 2.1. Strategic Portfolio Architecture & Why "All-In-One" Fails CORE A\*

Tại ICSE / FSE / ASE / OOPSLA (giới hạn `10` trang nội dung + `2` trang tài liệu tham khảo), việc gom cả 4 trục (`Spring SAT` + `Polyglot Microservice SAR` + `GraphRAG` + `QEMU Repair`) vào một bài Research Track gần như chắc chắn nhận **Strong Reject** vì 4 hiệu ứng PC kinh điển: *(1) Depth Deficit*, *(2) Weakest-Link Rejection*, *(3) Reviewer Community Mismatch*, và *(4) Portfolio Cannibalization*.

**Chiến lược Danh mục 3 Nhịp đã được PC-Chair phê duyệt (`CONFIRMED` tại mốc `26/11/2026`, đã trừ nghỉ Tết Nguyên Đán `06/02/2027`):**

| Thứ tự | Track & Tên Định vị Bài báo | Venue Mục tiêu & Hạn nộp (Mốc `26/11/2026`) | Trạng thái Hạn nộp | Novelty | Xác suất Thắng SOTA | Điều kiện Mở khóa (Gate) |
| :---: | :--- | :--- | :--- | :---: | :---: | :--- |
| **Nhịp 0 (Launchpad)** | **Track 4 — Tool Demo:** *Evo Workbench: Host-Safe Static Architecture Recovery, Conditional DI Reasoning, and Hypervisor-Verified Repair* | **FSE 2027 Tool Demonstrations** (`CORE A*`, 4+1 tr. + video): **`26/01/2027`** *(Notify `17/03/2027`; Backup: ASE '27 Demo mid-2027)* | **ĐÃ CÔNG BỐ** (`conf.researchr.org`) | `8.0/10` | **`80–85%`** | Hoàn tất **P0** (`9.5–10.0 d`: `P2-1..P2-4` + `F1, F6a, F6b, F8`). Giữ **Zero-Overlap Boundary** (chỉ demo Workbench & kiến trúc tổng quan, giữ `>75%` đóng góp thuật toán/ablation cho Bài #1 và #2). |
| **Bài Chính #1 (Primary)** | **Track 2 — Research Paper #1:** *Host-Safe, Evidence-Typed Static Architecture Recovery for Polyglot & Multi-Framework Microservices* | • **Primary (Zero-Crunch):** **EMSE** (`Q1 Springer`, rolling — đúng tạp chí Schneider '25, mục tiêu **đầu `04/2027`**) hoặc **TOSEM** (`CORE A*`, Q1)<br>• **Stretch Goal:** **ASE 2027 Research** (`CORE A*`, **`~10/03/2027`** — chỉ khả thi nếu kéo trước `F2–F3` vào Sprint 2 hoặc chạy 2 kỹ sư qua Tết!) | EMSE: **Rolling**;<br>ASE '27: **`dự kiến`** (tentative) | `7.5/10` | **`85–90%`** (`PROVISIONAL`) | Hoàn tất **P1** (`20.5–22.0 d`: `P2-5/6`, `F4+F4b`, `F2`, `F3`, `F5`, `F7 generic`) + **B1/B2** (`5–7 d`: đủ `17/17` `microSecEnD` + `M3SOulu` + `Micans` Zenodo `15050505`) + Linux 5-repeat `E1–E4`. |
| **Bài Flagship #2 (Follow-Up)** | **Track 1 — Research Paper #2:** *Variability-Aware, Phase-Ordered Static Analysis of Spring Conditional DI via COI-Sliced CDCL SAT* | • **Primary:** **ICSE 2028 Research** (`CORE A*`, **`~01/07/2027`**)<br>• **Nhánh rẽ PL/PA:** **OOPSLA 2027 Round 2** (`CORE A*`, **`07/04/2027`** — chỉ chọn nếu đảo ưu tiên Track 1 ngay sau FSE Demo!) | ICSE '28: **`dự kiến`**;<br>OOPSLA '27 R2: **ĐÃ CÔNG BỐ** | **`9.5/10`** *(Blue-Ocean)* | `65–75%` (`HYPOTHESIS`) | **BẮT BUỘC vượt Cổng Go/No-Go `S1` 1 tuần** với 3 ngưỡng khoa học phi hiển nhiên (Mục 3.3.6) trước khi đầu tư trọn `S2–S5` (`17–20 d`) + `ApplicationContextRunner` oracle (`15–20` OSS). |
| **Pha 3 (Hoãn)** | **Track 3 — Phase 3:** *Symbol-SKG GraphRAG (`RepoProbe`) & Host-Isolated Verified Architectural Refactoring (`RefAgent`/`MANTRA`)* | **ASE 2028 / ICSE 2028–2029** *(ISSTA '27 `11/01/2027` quá gấp — **LOẠI**)* | Rolling / Late 2027 | `6.0/10` | `35–50%` (`HYPOTHESIS`) | Trì hoãn do bão hòa cạnh tranh (`RefAgent` `90%`, `MANTRA` `82.8%`), chi phí API `$200–$800+` trên `RepoProbe`, và `4` nút thắt repair (`3–5` tuần). Thu hoạch nhanh `evo ask --batch` cho Chinthareddy `15/15` làm phụ trợ sau P0. |

*(Các hạn nộp **ĐÃ ĐÓNG** trước `26/11/2026`: `FSE '27 Research` `03/10/2026`, `OOPSLA '27 R1` `14/10/2026`, `ICSE '27 Tool Demo` `24/10/2026`, `ICSA '27 Research` `30/10/2026`; `ICSA '27 Showcase` `20/12/2026` quá gấp 3.5 tuần — loại.)*

### 2.2. Before (`CONFIRMED cb79c322`) → After (`PROVISIONAL` / `HYPOTHESIS`) Conservative Target Matrix

#### Table 2.2a — Track 2 & Pillar 2 (Microservice SAR, Polyglot, Non-Spring, OpenRewrite, Scalability)

| Benchmark & Chuẩn Đối chiếu | Đối thủ SOTA (Công bố gốc) | **Evo — BEFORE (`CONFIRMED cb79c322`)** | **Evo — AFTER (`PROVISIONAL`, Post P0–P1)** | Gói Kỹ thuật | Khẳng định Khoa học Trung thực |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **EMSE '25 — 5-Repo Audited** (`267` GT: `65 Cp, 149 Cn, 53 E`) | *(Tập con 5 repo Bảng 1 EMSE '25)* | Cp `0.796` (`43/65`), Cn `0.013` (`1/149`), E `0.548` (`20/53`), **Overall `0.387`** (`P=1.000`, **`49.2 s` TB/app**) | **Strict `S5`:** Cp `0.952`, Cn `0.840`, **E `0.991`** (`52/53`), **Overall `0.900`** (`P=0.987`)<br>**DFD `S6`:** Cp `0.985`, Cn `0.885`, E `0.991`, **Overall `0.932`** (`P=0.972`) | `F1–F5` (+`F4b`) + `P2-1..P2-4` | **#1 Endpoints (`0.991`)**; tăng `+0.513` đến `+0.545` F1 mà vẫn `--build=off` |
| **EMSE '25 — FULL 17-App `microSecEnD`** (`ASSUMPTION`: `~730` GT ước tính, chờ parse `M3SOulu`) | `Code2DFD`: **`0.86`** (`0.98` Cp, `0.87` Cn, `0.66` E, `P=0.93`, `7.3 s` **TB/app**); `MicroDepGraph`: `0.74` (`P=0.99`, `1.5 s` TB/app); `RAD`: `0.37` (`0.79` E); `Ensemble`: **`0.91`** | Chưa chạy đủ 17 apps (5 apps: `0.387`, `49.2 s` TB/app) | **Strict `S5`:** Cp `0.910`, Cn `0.793`, **E `0.927`**, **Overall `0.858`** (`P=0.967`)<br>**DFD `S6`:** Cp `0.945`, Cn `0.844`, E `0.927`, **Overall `0.891`** (`P=0.946`)<br>**TB/app Cold:** **`~18–30 s` (Win) / `~12–22 s` (Linux)**; **Warm `NO_CHANGE`:** **`~1.0–2.5 s`** | `F1–F5` (+`F4b`) + `P2-1..P2-6` + `B1` | **Strict `S5` ngang `Code2DFD` với Precision cao hơn (`0.967` vs `0.93`)**; **DFD `S6` vượt `Code2DFD` (`+0.031 F1`)**. Thừa nhận thẳng Cold TB/app chậm hơn `7.3 s` do dựng full Symbol SKG. |
| **Online Boutique** (`12 Svc, 16 Deps`, 5 langs + `.proto`) | `Ripple eBPF` (Runtime K8s): **`1.000` / `1.000`**; Java-only Static: `0.00` trên 10 dịch vụ phi-Java | Svc `0.957` (`11/12`), Deps `0.815` (`11/16`), Raw `0.688` (`5` FP), Comb `0.880`, `22.43 s / 16.25 s` | **Svc `1.000` (`12/12`)**, **Deps `1.000` (`16/16`)**, **Raw `1.000` (`0` FP)**, **Comb `1.000` (`28/28`)**, Warm `NO_CHANGE` **`~1.0–2.0 s`** | `F6a` + `F6b` + `F4-K8s` + `P2-3, P2-4` | **San bằng `Ripple eBPF` (`1.000`) hoàn toàn bằng phân tích tĩnh** — không cần cụm Kubernetes hay lưu lượng runtime! |
| **TeaStore** (Non-Spring Java: `7 Cp, 41/61 EP, 20 Servlet, 13 Edges`) | Reference MASCOTS '18 (`7 Cp, 41` direct / `61` concrete JAX-RS, `20` Servlet, `13` Edges) | Cp `0.923` (`6/7`), JAX-RS `0/41` (`0/61`), Edges `0/13`, `9` Locust FP; Cold `68.22 s`, **Warm `89.72 s` (`0.76×`)** | **Generic `F4+F6b+F7` (0 hardcoding):** **Cp `1.000` (`7/7`)**, **JAX-RS `41/41` (`61/61` concrete) + `20` `@WebServlet` (`1.000`)**, **`6/13` Edges (`F1=0.632`)**, `0` Locust FP.<br>**Optional Generalized Wrapper (`+2–3 d`):** `10–13/13` Edges.<br>**Perf:** Cold **`18–26 s`**, Warm `NO_CHANGE` **`1.5–3.0 s`**, **1-file edit `4.0–7.5 s` (`3.5×–6.0×`)** | `F4` + `F6b` + `F7` + `P2-1..P2-4, P2-6` | Tách minh bạch `6/13` generic vs `7/13` custom Ribbon/enum wrapper để **triệt tiêu cáo buộc overfitting**; đảo chiều `0.76× → 3.5×–6.0×`! |
| **OpenRewrite `v8.87.7`** (`Java 21` + `PetClinic Config A/B`) | Java 21: **`7/7`**; Config A: **`124/220` (`56.36%`)**, `57` span err; Config B: `220/220`, `89` span err | Java 21: `6/7` *(legacy `3.26.1`)*; Config A: `116/220` (`52.73%`), **`0` span err**; Config B: `220/220`, **`0` span err** | Java 21: **`7/7` (`100%`)** *(prod `3.28.2`+`AsmTypeSolver`)*;<br>Config A **Strict (`F8b`):** **`122/220` (`55.45%`)**, `0` approx, **`0` span err**;<br>Config A **`SOURCE_SCOPED` (`F8c`):** **`124/220` (`56.36%`)**, **`0` span err** | `F8` (`1.25 d`) | Tách rõ tầng soundness `Strict (122)` vs `SOURCE_SCOPED (124)` trong khi giữ tuyệt đối **`0` vs `57–89` lỗi span** |
| **Shopizer Indexing & Spill** (`1,340` files, `115.1 kLOC`, `1,173` Java) | `Chinthareddy DKB`: **`2.81 s`**, `15/15` Q&A; `LLM-KB`: `200.14 s`, `13/15`, `377` lỗi | **TIMEOUT `>180 s` (`6/6`)** — `41.89 s` tiền-spill + `137.43 s` spill `SQLite` (`428k–965k` rows, `395–549 MB`) | **Chỉ `P2-1..P2-4`:** **Cold `~45–60 s` (Win) / `~30–45 s` (Linux)**; **Warm `NO_CHANGE` `1.5–3.5 s`**; **1-file edit `4.0–9.0 s`**; `0` file rơi.<br>*(Đạt `18–30 s` Cold đòi hỏi tối ưu thêm tầng JavaParser song song ngoài `P2-1..P2-4`)* | `P2-1..P2-4, P2-6` | Xóa sổ timeout (`>3×–6×` nhanh hơn); thắng áp đảo `LLM-KB` (`200.14 s`, rơi `377` file); thừa nhận thẳng Cold `30–60 s` chậm hơn `2.81 s` của DKB shallow Tree-sitter |

#### Table 2.2b — Track 1 & Track 3 (Spring Conditional DI SAT, GraphRAG, Verified Repair)

| Trục & Đối thủ SOTA | **Evo — BEFORE (`CONFIRMED cb79c322`)** | **Evo — AFTER (`PROVISIONAL` / `HYPOTHESIS`)** | Gói Kỹ thuật | Cổng Kiểm soát Rủi ro |
| :--- | :--- | :--- | :--- | :--- |
| **Track 1: Spring Conditional DI SAT** vs `JackEE` (PLDI '20), `Jasmine` (ASE '22), `MeCheck` (FSE '25), `ArchUnit`, `Spring Modulith` | Synthetic: `79/79` runtime, `4/4` order, `12/12` P01 (`0` FP). **OSS thực:** Shopizer `4` user vs `1,333` starter JAR (`99.7%` mù); `9` OSS: `5` `@Profile`, `0` `@ConditionalOn*`, `41` `bootstrap.*` rơi, trần `32` chiều & `256` sig. | **`PROVISIONAL`:** Đọc thụ động `100%` starter JAR ASM (`1,333+` Boot 2.5 + `150` Boot 4.1/AI) + `bootstrap.*` + COI-sliced incremental CDCL (`<10 s`/repo, `<5%` `SOLVER_LIMIT`).<br>**`HYPOTHESIS`:** `15–20` OSS thực + `ApplicationContextRunner` oracle: `>=98%` khớp runtime, loại `>=20` single-config FP, phát hiện `>=5` lỗi/xung đột conditional thực. | `S1–S5` (`17–20 d`) + Oracle (`1.5–2.5 wks`) | **Cổng Go/No-Go 1 tuần (`S1` spike trên 5 OSS)** với 3 ngưỡng định lượng phi hiển nhiên (Mục 3.3.6). |
| **Track 3a: Chinthareddy Shopizer Q&A** (`15` Qs) | `N/A` (Shopizer timeout `>180 s`, không có CLI `evo ask`) | **`PROVISIONAL`:** `15/15` kèm trích dẫn tất định `[E#]` sau Pillar 2 + `R1` (`evo ask --batch`). | `P2-1..P2-4` + `R1` (`~1 wk`) | Chỉ làm kết quả bổ trợ (preprint 15 câu chưa đủ lực cho Research Track độc lập). |
| **Track 3b: RepoProbe** (ASE '26, `500` Qs / `50` repos / `15` langs; `GPT-5.2 = 62.7%`) | `N/A` (`MAX_ROUNDS=10`, `compactToolMessages` cắt `>1,200` chars, chưa chống Edit Bias, `RepoMapBuilder` trần `500` files) | **`HYPOTHESIS`:** Controlled ablation trên **cùng backbone LLM**: mục tiêu **`+5–12 pp`** (`~67–74%`) trên lát cắt Java/Spring & polyglot chéo file. | `R1` + `R2` (`2–3 wks`) | Tốn `$200–$800+` LLM API + cần mở rộng polyglot cross-file symbol (xem RQ-7). |
| **Track 3c: RefAgent** (ICSE '26, `90%`) & **MANTRA** (`82.8%`) | `16` recipe DI hẹp (0 Fowler), `CapturedRepair` chỉ retry cú pháp, `RepairContextPlanner` trần `6` files / `3` callers, `run.py` rebuild Docker + khóa `1` JDK. (`9/9` QEMU Negative Control nội bộ). | **`HYPOTHESIS` (Pha 3):** Chiếm ngách **Host-Isolated Multi-File Architectural Refactoring** (cycle breaking, God-Class/SDP decoupling trên `DesigniteJava`) bằng vòng lặp `DOCKER_HARDENED_CONTAINER` (`javac`+`JUnit`) + chứng nhận `QEMU_IN_DOCKER`, **`0%` rủi ro máy chủ**. | `R3` + `R4` (`3–5 wks`) | Hoãn sang Pha 3 (cuối 2027) để dồn nguồn lực cho FSE Demo + Track 2 + Track 1. |

---

## 3. Deep Code-Level Engineering Blueprint (Executable Work Packages)

> **PowerShell & Maven Reactor Note:** Trên Windows PowerShell, luôn đặt mọi cờ `-D...` của Maven trong dấu nháy đơn (ví dụ `'-Dtest=CapabilityGapContractTest'`, `'-DskipTests'`). Ngoài ra, vì `pom.xml:138` cấu hình `<failIfNoTests>true</failIfNoTests>` trên `maven-surefire-plugin`, mọi lệnh chạy nhiều mô-đun (`-pl modA,modB`) bên dưới đều kèm đủ test class khớp cho từng mô-đun **và** truyền tường minh `'-DfailIfNoTests=false'` để tránh lỗi `BUILD FAILURE: No tests were executed!` khi chọn lọc test theo gói.

### 3.1. Pillar A — Storage, Spill & Warm Cache (`P2-1`..`P2-6` | Total: `10.5–11.0 d`)

Mục tiêu: Triệt tiêu tận gốc timeout `>180 s` trên Shopizer (`41.89 s` floor + `137.43 s` spill) và suy giảm Warm `0.76×` trên TeaStore (`68.22 s → 89.72 s`), đồng thời tạo bằng chứng ablation trực tiếp cho Claim `(C3)` của Track 2.

---

#### Work Package `P2-1` (P0 | `2.0 d`) — Deterministic Per-Document `SEMANTIC_UNRESOLVED` Gap Coalescing

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/evidence/CapabilityGapNormalizer.java` (`L445–509`: vòng lặp `for (ObservationRecord observation : result.observations())`)
- **Root Cause:** Mỗi `ObservationRecord` Java chưa phân giải tạo một đối tượng `CapabilityGapRecord` độc lập nặng `~2,091 B` raw (`832 B` comp), gây ra `74,529` gap offline / `63,142` gap prepared trên Shopizer (`96.8–97.4%` `SEMANTIC_UNRESOLVED`) và `4,378/5,132` (`85.3%`) trên TeaStore.
- **Algorithm & Data Contract:**
  1. Giữ nguyên mọi gap không phải `SEMANTIC_UNRESOLVED` (hoặc có severity/category cấu trúc đặc biệt) ở độ phân giải 1:1.
  2. Nhóm tất cả các `ObservationRecord` có trạng thái `SEMANTIC_UNRESOLVED` theo khóa tất định:
     $$\text{CoalesceKey} = (\text{contextId},\; \text{sourceDocumentPath},\; \text{reasonCode},\; \text{relationshipCategory})$$
  3. Trong mỗi nhóm, sắp xếp các observation theo `(startLine, startColumn, endLine, endColumn, stableReference)` để bảo đảm bất biến `I2` (SHA-256 tất định).
  4. Phát hành **đúng 1 `CapabilityGapRecord` tổng hợp** chứa:
     - `occurrenceCount = group.size()` (bảo toàn 100% mẫu số đóng `I3` — tổng `occurrenceCount` toàn repo không đổi!).
     - `representativeSpans`: tối đa `K = 3` span đại diện đầu tiên theo thứ tự đã sắp xếp.
     - `allSpansDigest`: `ContentDigest.sha256(...)` tính trên danh sách chuẩn hóa của toàn bộ `N` span trong nhóm.
     - `diagnostics`: kèm khóa tường minh `coalesced=true`, `occurrenceCount=<N>`, `allSpansDigest=<sha256>`.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer '-Dtest=CapabilityGapContractTest,PipelinePerformanceAndGapFixTest' test
  ```
  - **Exit Gate:** (1) Unit test mới xác nhận 100 unresolved observations trong 1 file gộp thành 1 `CapabilityGapRecord` với `occurrenceCount == 100`, `representativeSpans.size() == 3`, và `allSpansDigest` bất biến khi xáo trộn thứ tự đầu vào; (2) Tổng `occurrenceCount` trước/sau gộp bằng nhau 100%; (3) Shopizer `space=1` gaps giảm `74,529 → ~1,500–2,500` (`~35×`), TeaStore gaps giảm `5,132 → ~400–600` (`~10×`).

---

#### Work Package `P2-2` (P0 | `0.5 d`) — Heap-Aware Adaptive Disk-Spill Thresholds

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalArchitecturePipeline.java` (`L39–41`: `DISK_SPILL_MIN_BYTES = 16_000_000L`, `DISK_SPILL_MIN_FILES = 400`, `DISK_SPILL_MIN_CONTEXTS = 8`; `L71–75`: hàm quyết định spill)
  - `cli/src/main/java/com/evolution/cli/EvoApp.java` (`L1056`) & `backend/src/main/java/com/evolution/backend/analysis/LocalAnalysisRunner.java` (`L1198`)
- **Algorithm & Data Contract:**
  - Thay ngưỡng tĩnh thấp bằng hàm thích ứng theo `Runtime.getRuntime().maxMemory()` (vẫn cho phép ghi đè tất định qua system properties `-Devo.spill.minFiles`, `-Devo.spill.minBytes`, `-Devo.spill.minContexts` cho kiểm thử/ablation):
    ```java
    long maxHeap = Runtime.getRuntime().maxMemory();
    int minFiles = Integer.getInteger("evo.spill.minFiles", 5_000);
    long minBytes = Long.getLong("evo.spill.minBytes", Math.max(64_000_000L, maxHeap / 16L));
    int minContexts = Integer.getInteger("evo.spill.minContexts", 64);
    boolean useDiskSpill = totalFiles >= minFiles || totalBytes >= minBytes || contextCount >= minContexts;
    ```
  - **Invariant Check (`I2`):** `AnalysisWorkspace.MEMORY` và `FileAnalysisWorkspace` phải xuất ra **cùng một `graphIdentity` `SHA-256`** khi chạy cùng đầu vào (kiểm chứng bằng chạy `-Devo.spill.minFiles=1` vs mặc định).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,analyzer-filesystem '-Dtest=PipelinePerformanceAndGapFixTest,StreamingStorageTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** TeaStore (`409` files, `11` contexts) và Shopizer (`1,340` files, `~8 MB`) chạy 100% trong `AnalysisWorkspace.MEMORY` ở `-Xmx4g`/`-Xmx8g`; test parity khẳng định `SHA-256(MEMORY) == SHA-256(DISK_SPILL)`.

---

#### Work Package `P2-3` (P0 | `1.5 d`) — CLI `evo scan` `NO_CHANGE` Fast-Path Short-Circuit

- **Exact Files & Anchors:**
  - `cli/src/main/java/com/evolution/cli/EvoApp.java` (`L926–1140`: phương thức `Scan`, nơi `L1133–1139` tính `MODE_NO_CHANGE` nhưng đã chạy lại toàn bộ pipeline!)
  - Tham chiếu chuẩn đối xứng: `backend/src/main/java/com/evolution/backend/analysis/LocalAnalysisRunner.java` (`L612–620, 1666–1676`)
- **Algorithm & Data Contract:**
  1. Ngay sau `intake.localCompleteIncremental()` trả về `fileDelta` với `addedFiles == 0 && modifiedFiles == 0 && deletedFiles == 0` VÀ `baseSnapshotId.isPresent()`:
  2. Tính `optionsFingerprint = SHA-256(mode, springSat, release, offline, jdkIdentity, projectionProfile, mavenCacheDigest, analyzerVersion)`.
  3. Đọc `selection.json` / metadata xuất bản trước đó trong workspace: nếu tồn tại `priorGraphId`, `priorOptionsFingerprint.equals(optionsFingerprint)`, và `r.storage().hasPublishedRoot(priorGraphId)` trả `true` (xác minh nhanh header/handle CAS tồn tại và không hỏng):
     - **Short-circuit ngay lập tức:** bỏ qua `MavenScan`, `GradleScan`, `UniversalArchitecturePipeline`, và `r.storage().publish()`.
     - Ghi `last-incremental-summary.json` với `mode="NO_CHANGE"`, `baseGraphId=Optional.of(priorGraphId)`, `reusedGraphContexts=priorContextCount`, `rebuiltGraphContexts=0`.
     - Trả về đúng biên nhận canonical (`graphId`, counts, health score) giống hệt Cold Run!
  4. Bất kỳ sai khác nào về tùy chọn (`--spring-sat`, `--offline`, `--projection-profile`, thay đổi `.m2` cache, hoặc xóa mất handle CAS) tự động rớt về đường phân tích đầy đủ/tăng dần (zero stale cache risk!).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl cli '-Dtest=EvoScoreV2AndIncrementalCliTest' test
  ```
  - **Exit Gate:** Chạy CLI `evo scan` 2 lần liên tiếp (Cold → Warm `NO_CHANGE`): (1) `warm.graphId == cold.graphId` (`100%` SHA-256); (2) đổi `--spring-sat` hoặc sửa 1 byte ở 1 file làm vô hiệu hóa `NO_CHANGE` đúng quy ước; (3) Warm `NO_CHANGE` trên TeaStore `<3.0 s` và Shopizer `<3.5 s`.

---

#### Work Package `P2-4` (P0 | `2.5–3.0 d`) — Eliminate CAS-Hit Double-I/O, `.graph.json` 190 MB Double-Hash & Redundant Self-Audit SQLite

- **Exact Files & Anchors:**
  - `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/StreamingContentStore.java` (`L233–254` tham chiếu `putLocalFile()`, `L300–350` sửa `writeInternal()`, đặc biệt `L334–337`)
  - `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/PartitionedGraphStore.java` (`L697–788` sửa `assemble()`, đặc biệt `L772–778`; `L1032–1040` sửa `publish()`; `L1328–1450` `canonicalDigest()`)
- **Algorithm & Data Contract:**
  1. **In-Memory Hash-First for Small Pages (`StreamingContentStore.writeInternal()`):**
     - Đối với trang Merkle có dung lượng `<= 2 MiB` (hầu hết `662` trang của TeaStore đều `<256 KB`), ghi vào `ByteArrayOutputStream` trong RAM + `MessageDigest("SHA-256")` trước khi chạm hệ thống tệp.
     - Tính `handle = ContentHandle(sha256, size)`. Nếu `hasLoose(handle) || packs.contains(handle)` và kích thước file khớp (`Files.size(path(handle)) == size`): **trả ngay `handle` mà không tạo file tạm và không gọi `verify(handle)` đọc lại toàn bộ `127.34 MiB`!** *(Giữ cờ `-Devo.cas.verifyOnHit=true` cho chế độ kiểm toán chặt khi cần).*
     - Chỉ mở temp file + `fsync` + `createLinkWithRetry` khi miss CAS hoặc dung lượng `> 2 MiB`.
  2. **Zero Double-Hash on Existing `.graph.json` (`PartitionedGraphStore.java:772–778`):**
     - Trong `assemble()`, bộ đếm `frame` đã tính `SHA-256` trong quá trình stream ra `pending`. Khi `createLinkWithRetry(target, pending)` ném `FileAlreadyExistsException`: kiểm tra `Files.size(target) == pendingSize` và đối chiếu `storedDigestMetadata(identity)` == `frame.digest()` (hoặc chỉ băm `target` một lần duy nhất nếu metadata vắng mặt — tuyệt đối không gọi `hash(pending)` lại nữa!).
  3. **Gate Redundant `graph-audit-*.sqlite` (`PartitionedGraphStore.java:1036, 1328–1450`):**
     - Khi đồ thị vừa được chính `SqliteGraphProjection` dựng trong cùng tiến trình (`trustedInternalProjection == true`), xác nhận tính toàn vẹn canonical ngay trên stream xuất bản (`auditIndexes = false`), bỏ dựng `graph-audit-*.sqlite` thứ hai và bỏ vòng đọc lại `662` trang JSON.
     - Giữ `auditIndexes = true` bắt buộc cho mọi đường nhập ngoài (`import` từ archive/bundle bên ngoài chưa xác thực).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-filesystem,analyzer '-Dtest=StreamingStorageTest,GraphPerfParityTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** (1) `StreamingStorageTest` bổ sung kiểm thử xác nhận CAS-hit trên trang `<=2 MiB` tạo `0` temp file và không đọc lại file trang cũ; (2) Corruption test trên đường `import` ngoài vẫn ném `GRAPH_PARTITION_DIGEST_MISMATCH`; (3) Cold `graph_publish_ms` trên TeaStore giảm `60–75%` (`34.45 s → ~5–8 s`) và xóa hoàn toàn suy giảm Warm trên `6/7` repo V4.
  - **Sprint 1 Measured Gate on Shopizer (`P2-1..P2-4` alone):** **`<60 s` Cold (Windows) / `~30–45 s` Cold (Linux)** (tôn trọng sàn tiền-spill thực đo `41.89 s` Win!) và **`<3.5 s` Warm `NO_CHANGE`**.

---

#### Work Package `P2-5` (P1 | `2.0 d`) — Batched SQLite Spill & Zero-Copy Space Aliasing for `>5,000`-File Enterprise Repos

- **Exact Files & Anchors:**
  - `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/FileAnalysisWorkspace.java` (`L140–195` `distinct()` / `insertKey()`, `L230–270`, `L352–380` `executeUpdate()`)
- **Algorithm & Data Contract:**
  - Khi ngưỡng spill thực sự kích hoạt ở quy mô doanh nghiệp (`>5,000` files như OWASP Dependency-Track, MP-Manager, ThingsBoard):
    1. Duy trì `HashSet<Long>` / `Bloom + Digest` trong RAM theo từng `spaceId` thay cho `2` lệnh `SELECT 1` đồng bộ mỗi dòng.
    2. Chuyển `executeUpdate()` từng dòng sang JDBC `PreparedStatement.addBatch()` + `executeBatch()` mỗi `2,048` dòng trong một transaction.
    3. Trong `distinct()` và phép gộp space, chỉ copy con trỏ `(offset, compressedLen, digest)` trong bảng chỉ mục SQLite (**zero-copy `values.bin` aliasing**) thay vì giải nén + khởi tạo lại đối tượng Java bằng Reflection rồi nén ghi lại lần nữa.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-filesystem '-Dtest=StreamingStorageTest' test
  ```
  - **Exit Gate:** Synthetic micro-benchmark `100,000` staged records với `-Devo.spill.minFiles=1` đạt tăng tốc `>=5×` so với baseline và giữ `100%` đồng nhất `SHA-256`.

---

#### Work Package `P2-6` (P1 | `2.0 d`) — Wire `IncrementalGraphProjector` into CLI `evo scan` for Real 1-File Edits

- **Exact Files & Anchors:**
  - `cli/src/main/java/com/evolution/cli/EvoApp.java` (`L1098`, `L1135–1139`)
  - `cli/src/main/java/com/evolution/cli/Workspace.java` (`L78`)
  - `analyzer/src/main/java/com/evolution/analysis/graph/IncrementalGraphProjector.java` (đã tồn tại và đã được kiểm thử!)
- **Algorithm & Data Contract:**
  - Khi `fileDelta` khác rỗng (`added + modified + deleted > 0`) nhưng tồn tại `baseGraphId` tương thích (cùng options fingerprint):
    1. Nạp danh mục context đã xuất bản từ `baseGraphId` trong `PartitionedGraphStore`.
    2. Truyền `baseGraph` + tập `recomputedContexts` vào `IncrementalGraphProjector` để **chỉ chiếu lại `1–2` build context có file thay đổi** và tái sử dụng nguyên trạng phân vùng đồ thị của các context không đổi (`reusedGraphContexts > 0`).
    3. Ghi đúng `baseGraphId`, `reusedGraphContexts`, `rebuiltGraphContexts` vào `last-incremental-summary.json`.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,cli '-Dtest=IncrementalReadinessAndGraphDeltaTest,EvoScoreV2AndIncrementalCliTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Sửa 1 comment/method trong 1 file `.java` trên TeaStore và Shopizer: (1) `SHA-256(Warm 1-File Edit) == SHA-256(Cold Run on Edited Repo)` (`100%` bit-identical!); (2) TeaStore 1-file edit `4.0–7.5 s` (`3.5×–6.0×` speedup vs improved Cold), Shopizer 1-file edit `4.0–9.0 s`.

---

### 3.2. Pillar B — Microservice, Polyglot & Java Extractors (`F1`..`F8` | Total: `19.5–21.0 d`, `~2,080–2,330 LOC`)

Mục tiêu: Khóa chặt toàn bộ đóng góp kỹ thuật cho **Bài Chính #1 (Track 2)** với **0% overfitting** và **100% `--build=off`**.

---

#### Work Package `F1` (P0 | `0.5 d`, `~35 LOC`) — Source-Scoped Spring `*Mapping` & `MediaType.*` Constant Decoding

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/spring/universal/SpringSourceEvidence.java` (`L78–126`: `SIMPLE_SPRING_ANNOTATIONS`)
  - `analyzer/src/main/java/com/evolution/analysis/spring/universal/WebRouteMapper.java` (`L82–99`: `decode()`)
  - `analyzer/src/main/java/com/evolution/analysis/spring/condition/LiteralConditionAnnotation.java` (`L124–127`: `strings()`)
- **Algorithm & Data Contract:**
  1. Thêm đúng `6` ánh xạ vào `SpringSourceEvidence.SIMPLE_SPRING_ANNOTATIONS` (`L78–125`):
     - `"RequestMapping" → "org.springframework.web.bind.annotation.RequestMapping"`
     - `"GetMapping" → "org.springframework.web.bind.annotation.GetMapping"`
     - `"PostMapping" → "org.springframework.web.bind.annotation.PostMapping"`
     - `"PutMapping" → "org.springframework.web.bind.annotation.PutMapping"`
     - `"DeleteMapping" → "org.springframework.web.bind.annotation.DeleteMapping"`
     - `"PatchMapping" → "org.springframework.web.bind.annotation.PatchMapping"`
     *(Áp dụng chung logic guard `COMPATIBLE_FAMILY_OR_SOURCE_SCOPED` sẵn có: chỉ khớp khi file có import tường minh hoặc wildcard `org.springframework.web.bind.annotation.*`, tránh xung đột với annotation cùng tên từ framework khác!)*
  2. Trong `WebRouteMapper.decode()` (`L97–98`), thêm bộ giải mã hằng chuẩn `org.springframework.http.MediaType.*_VALUE` (bảng hằng công khai của Spring Framework: `APPLICATION_JSON_VALUE`, `APPLICATION_JSON_UTF8_VALUE`, `TEXT_HTML_VALUE`, `TEXT_PLAIN_VALUE`, `APPLICATION_XML_VALUE`, `MULTIPART_FORM_DATA_VALUE`, `APPLICATION_OCTET_STREAM_VALUE`, `ALL_VALUE`, `APPLICATION_FORM_URLENCODED_VALUE`, `TEXT_EVENT_STREAM_VALUE`) khi `attrs.get("produces")` hoặc `attrs.get("consumes")` là `LiteralConditionAnnotation.Enums` mang tiền tố `MediaType.`.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer '-Dtest=SpringSourceEvidenceImportsTest,SpringAndCoreHardeningTest' test
  ```
  - **Exit Gate:** EMSE '25 5-repo Endpoints nhảy từ `20/53` (`0.548`) → **`52/53` (`F1 = 0.991`, `0 FP`)**; HEAD `63/63` (`1.000`), bao gồm `ewolff` (`15/15` T1), `anilallewar` (`7/7` HEAD), `blog-ms` (`10/10`) dưới `--offline --build=off`.

---

#### Work Package `F6a` (P0 | `0.75 d`, `~55 LOC`) — Go gRPC Split-File `Unimplemented*Server` Embedding & Service Self-Loop Filter

- **Exact Files & Anchors:**
  - `analyzer-treesitter/src/main/java/com/evolution/analysis/treesitter/GrpcSourceSyntax.java` (`L36`: fast filter; `L141–170`: `handler()` & `registrations()`)
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/GrpcServiceCallProjection.java` (`L41–44`)
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/ServiceArchitectureProjection.java` (`L28–42`)
- **Algorithm & Data Contract:**
  1. Ở `GrpcSourceSyntax.java:36`, mở rộng điều kiện lọc Go để không bỏ qua file chứa struct embedding chuẩn của `protoc-gen-go-grpc`:
     ```java
     if (language.equals("go") && !source.contains("Client") && !source.contains("Register")
             && !source.contains("Unimplemented") && !source.contains("Server")) return 0;
     ```
  2. Trong `GrpcSourceSyntax.registrations()` (`L153–170`), bổ sung nhận diện `type_declaration → struct_type` có trường ẩn danh (embedded field) khớp mẫu `(?:<pkg>\.)?Unimplemented(<ID>)Server` hoặc `Unsafe(<ID>)Server`, thêm ánh xạ `structType → serviceName` vào `serverTypes`. Nhờ đó `product_catalog.go` (chứa `type productCatalog struct { pb.UnimplementedProductCatalogServiceServer }`) tự động phát ra `RPC_HANDLER_CANDIDATE` cho `ListProducts`, `GetProduct`, `SearchProducts` ngay cả khi `pb.RegisterProductCatalogServiceServer` nằm ở `server.go`!
  3. Trong `ServiceArchitectureProjection.java:28–42` (và `GrpcServiceCallProjection.java:41–44`), chuẩn hóa ranh giới thành phần dịch vụ (`serviceComponentId(caller) != serviceComponentId(callee)`) và gắn nhãn/lọc caller nằm trong đường dẫn kiểm thử/demo nội bộ (`*Test*`, `*Tests*`, `test/`, `__tests__/`, `*_test.go`) ra khỏi cạnh liên dịch vụ chính (giữ provenance trong chi tiết hoặc kênh `TEST_CALL`).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-treesitter,analyzer '-Dtest=TreeSitterSourceFrontendTest,GrpcServiceCallProjectionTest,ServiceArchitectureProjectionTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Online Boutique Inter-Service Deps nhảy `11/16` (`0.815`) → **`14/16` (`0.933`)**, Raw Unfiltered Deps nhảy `0.688` → **`0.933` (`0/5` self-loop FP)**.

---

#### Work Package `F6b` (P0 | `0.5 d`, `~30 LOC`) — Python Dotted HTTP Client Receiver & Go `var_spec` `expression_list` Unwrap

- **Exact Files & Anchors:**
  - `analyzer-treesitter/src/main/java/com/evolution/analysis/treesitter/TreeSitterSourceFrontend.java` (`L185`: `PYTHON_HTTP_CLIENT`; `L620–645`; `L942–995`: `resolveStaticString()`)
- **Algorithm & Data Contract:**
  1. Sửa `PYTHON_HTTP_CLIENT` (`L185`) thêm tiền tố receiver có dấu chấm tùy chọn `(?:[A-Za-z_][A-Za-z0-9_]*\.)*`:
     ```java
     private static final Pattern PYTHON_HTTP_CLIENT = Pattern.compile(
         "(?:(?:[A-Za-z_][A-Za-z0-9_]*\\.)*(?:requests|httpx|session|client|aiohttp|urllib\\.request))"
       + "\\.(?:get|post|put|patch|delete|head|options|urlopen|request)");
     ```
     Điều này định tuyến đúng `l.client.get("/")`, `self.client.post("/cart")` thành `HTTP_CALL_CANDIDATE` thay vì rơi vào `emitRouteCandidate()` (`L1281`) tạo route máy chủ giả!
  2. Trong `resolveStaticString()` (`L942–995`), khi gặp node Go `var_spec` có field `value` kiểu `expression_list`, mở bọc (unwrap) con đơn `namedChild(0)` trước khi đánh giá literal chuỗi (`baseUrl = ""` trong `frontend/main.go:58`), nhờ đó toàn bộ 14 Gorilla Mux routes `r.HandleFunc(baseUrl + "/cart", ...)` được phục hồi chính xác.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-treesitter,analyzer '-Dtest=TreeSitterSourceFrontendTest,HttpServiceCallProjectionTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Online Boutique Deps nhảy `14/16` → **`15/16` (`0.968`, `0 FP`)** nhờ khớp `loadgenerator -> frontend`; xóa sạch `7` Locust route ảo ở Online Boutique và `9` Locust route ảo ở TeaStore.

---

#### Work Package `F8` (P0 | `1.25 d`, `~90 LOC`) — OpenRewrite Comparator Parity (`Java 21 7/7` + PetClinic Config A `122` Strict / `124` `SOURCE_SCOPED`)

- **Exact Files & Anchors:**
  - `benchmarks/semantic-frontend-evaluation/pom.xml`
  - `benchmarks/semantic-frontend-evaluation/src/main/java/com/evolution/benchmark/frontend/JavaParserFrontend.java`
  - `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/Extraction.java` (`L2707–2913`, đặc biệt `requireRecoveryHierarchy` tại `L2880`)
  - `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/ExplicitImportTypeSolver.java` (`L1040–1125`)
- **Algorithm & Data Contract:**
  1. **`F8-Java21` (`0.25 d`):** Nâng cấp `benchmarks/semantic-frontend-evaluation/pom.xml` & adapter từ `JavaParser 3.26.1 + ReflectionTypeSolver` lên nối trực tiếp production `analyzer-javaparser` (`JavaParser 3.28.2` + `AsmTypeSolver` + `ExplicitImportTypeSolver`). Ngay lập tức đóng `ArchitectureFixture.java:14` (`values.get(0).trim()`) từ `6/7 → 7/7` (`100%`).
  2. **`F8a` — Arity-First Overload Screening Guard (`0.5 d`):** Trong `Extraction.java` (trước khi gọi `resolve()` trên `MethodCallExpr` khi nhận `UnsolvedSymbolException` từ overload khác số tham số), kiểm tra receiver type có khai báo tập overload cùng tên và lọc trước theo **arity khớp chính xác** (trừ varargs). Nếu chỉ còn đúng 1 ứng viên khớp arity và mọi tham số của ứng viên đó đều phân giải được (`Collection<Vet> findAll()` với `0` tham số, bỏ qua `findAll(Pageable)` 1 tham số!), chọn ứng viên đó → khôi phục sạch `2` lời gọi `ORW-2` (`VetController.java:70`: `findAll()` & `List.addAll(...)`, **`116 → 118/220`**, `0` approximation, `0` span errors).
  3. **`F8b` — Production `ExplicitImportTypeSolver` (`0.25 d`):** Với adapter đã nối production `SOURCE_RECOVERY`, 4 lời gọi controller cùng lớp (`ORW-1a`: `OwnerController:121`, `PetController:129,171`, `VetController:47`) được phân giải chính xác với FQN đầy đủ → **`122/220` (`55.45%`) Strict Resolved**, `0` approximation, `0` span errors!
  4. **`F8c` — Explicit `SOURCE_SCOPED` Candidate Tier (`0.25 d`):** **KHÔNG** hạ ngưỡng `requireRecoveryHierarchy` (`L2880`) của tầng Strict! Thay vào đó, khi 1 interface dự án mở rộng interface ngoài thiếu JAR (`Repository`) nhưng tự khai báo duy nhất 1 method khớp tên & arity (`OwnerRepository.findByLastNameStartingWith`, `VetRepository.findAll(Pageable)`), phát hành quan sát ở tầng **`CANDIDATE / SOURCE_SCOPED`** có gắn thẻ tường minh (`resolutionTier = SOURCE_SCOPED`, `missingSupertype = org.springframework.data.repository.Repository`). Đạt **`124/220` (`56.36%`)** khi tính cả `SOURCE_SCOPED`, giữ trọn **`0` vs `57` lỗi span**.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-javaparser '-Dtest=JavaParserFrontendTest,M9JavacOracleTest,SourceRecoveryAttributionTest' test
  ```
  - **Exit Gate:** Java 21 `7/7` (`100%`), PetClinic Config A `122/220` Strict / `124/220` `SOURCE_SCOPED` (`0` span errors), Config B `220/220` (`0` span errors), không phá vỡ bất kỳ test nào trong `M9JavacOracleTest`.

---

#### Work Package `F4 + F4b` (P1 | `6.5–8.0 d`, `~750–1,000 LOC`) — Standard Cloud-Native Declarative Topology, Control-Plane YAML & Dual `strict` / `dfd-export` Mode

- **Exact Files & Anchors:**
  - `backend/src/main/java/com/evolution/backend/analysis/StaticPolyglotScan.java` (`L631–844`: `declarativeInfraLanguage()`, `parseDockerCompose()`)
  - `backend/src/main/java/com/evolution/backend/analysis/PolyglotScan.java` (`L170–233`)
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/ServiceArchitectureProjection.java`
  - `cli/src/main/java/com/evolution/cli/EvoApp.java` (thêm cờ `--projection-profile=strict|dfd-export`, mặc định `strict`)
- **Strict Anti-Overfitting Scope Guard (`I5`):**
  - **ĐƯỢC PHÉP (Standard Cloud-Native Schemas):**
    1. **Docker Compose (`docker-compose*.yml`):** Nâng dịch vụ container-only (DB, RabbitMQ, Redis, Zipkin, Prometheus, Grafana, Elasticsearch, Kibana) thành nút `INFRA_COMPONENT` kèm provenance `(file, line, image)`; liên kết `depends_on`, `links`, và biến môi trường dạng `*_HOST` / `*_ADDR` / `*_URL` / `SPRING_DATASOURCE_URL` / `SPRING_RABBITMQ_HOST` khớp tên dịch vụ Compose.
    2. **Kubernetes Manifests (`*.yaml`/`*.yml` multi-document có `apiVersion:` + `kind: Deployment|StatefulSet|DaemonSet|Service`):** Phát hành `INFRA_COMPONENT` cho container không có thư mục nguồn (`redis-cart`), ánh xạ `Service.metadata.name ↔ Deployment`, và liên kết `spec.template.spec.containers[].env` dạng `*_ADDR` / `*_HOST` / `*_URL` (`FRONTEND_ADDR="frontend:80"`, `REDIS_ADDR="redis-cart:6379"`).
    3. **Spring Boot & Spring Cloud Config YAML/Properties (`application*.yml`, `bootstrap*.yml`, `config/.../shared/*.yml`):** Phân giải `spring.application.name`, `spring.cloud.config.{uri,discovery.service-id}`, `spring.cloud.config.server.git.uri`, `eureka.client.serviceUrl.defaultZone`, `spring.datasource.url`, `spring.data.mongodb.{host,uri}`, `spring.zipkin.baseUrl`, `spring.mail.host`, `turbine.appConfig` kết hợp annotation nguồn `@EnableDiscoveryClient`/`@EnableEurekaClient`/`@EnableEurekaServer`/`@EnableConfigServer`.
    4. **Observability Standard YAML Schemas:** `prometheus.yml` (`scrape_configs[].static_configs[].targets[]` & `metrics_path`) và Grafana provisioning datasources YAML (`datasources[].url`).
  - **BỊ CẤM (`I5`):** **KHÔNG** viết parser tùy biến cho DSL log riêng lẻ không chuẩn YAML như `logstash.conf` (`input { tcp { ... } } output { elasticsearch { ... } }`); nếu gặp tệp cấu hình log ngoài schema chuẩn, ghi nhận `CapabilityGapRecord(reason=UNSUPPORTED_CONFIG_DSL)`.
- **Dual Projection Profile (`F4b`):**
  - `--projection-profile=strict` (**Mặc định — `S5`**): Chỉ phát hành nút/cạnh có bằng chứng trực tiếp trong mã nguồn hoặc file khai báo chuẩn. `0` tác nhân tổng hợp `user`, `0` cạnh auto-route suy đoán.
  - `--projection-profile=dfd-export` (**Tùy chọn so sánh DFD — `S6`**): Bật 2 quy tắc tương thích DFD chuẩn `Code2DFD`/`microSecEnD` với nhãn provenance tường minh `DERIVED_DFD_POLICY`: (a) `@EnableZuulProxy` không có `zuul.ignored-services: '*'` tạo cạnh proxy tới các dịch vụ đăng ký Eureka trong cùng repo; (b) phát hành nút ngoại vi `EXTERNAL_ACTOR("user")` nối vào API Gateway / Web UI / Dashboard có công bố cổng ngoài (`ports:` trong Compose/K8s).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,backend,cli '-Dtest=ServiceArchitectureProjectionTest,PolyglotDiscoveryTest,PolyglotScanTest,EvoScoreV2AndIncrementalCliTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** (1) Online Boutique đạt **`12/12` Services (`1.000`) & `16/16` Deps (`1.000`, `0 FP`)** — san bằng `Ripple eBPF` tĩnh; (2) TeaStore đạt `7/7` Components (`1.000`) & `6/13` generic infra edges (`0.632`); (3) EMSE '25 5-repo (cùng F1–F3) đạt `>=0.875` Overall F1 ở `strict` mode; (4) `grep` xác nhận `0` chuỗi `"logstash.conf"` hay tên repo benchmark trong `src/main/`.

---

#### Work Package `F2` (P1 | `3.0 d`, `~320 LOC`) — Java `@FeignClient`, Spring 6 `RestClient`, `OAuth2RestTemplate`, `DiscoveryClient`/`LoadBalancerClient` & URI Folding

- **Exact Files & Anchors:**
  - `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/HttpClientConstants.java` (`L20–46`)
  - `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/Extraction.java` (`L1677–1734`)
  - `analyzer/src/main/java/com/evolution/analysis/spring/universal/SpringSourceEvidence.java` (`L78–126`)
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/HttpServiceCallProjection.java` (`L56–89`)
- **Algorithm & Data Contract:**
  1. **`HttpClientConstants.java:20–46`:** Nới điều kiện gập hằng an toàn cho: (a) biến cục bộ *effectively-final* trong cùng block/method (`String vetsHostname = "http://vets-service/"`); (b) trường thành viên có biểu thức khởi tạo mặc định và không bị gán lại bằng giá trị không tĩnh trong cùng file (`VisitsServiceClient.hostname`); (c) biểu thức `String.format("http://svc/path/%s", ...)` bằng cách giữ nguyên phần scheme+host+prefix tĩnh và thay placeholder `%s`/`%d` thành `{param}`.
  2. **`SpringSourceEvidence.java`:** Thêm `FeignClient` (`org.springframework.cloud.openfeign.FeignClient` & legacy `org.springframework.cloud.netflix.feign.FeignClient`), `EnableDiscoveryClient`, `EnableEurekaClient`, `EnableEurekaServer`, `EnableConfigServer`, `EnableZuulProxy` vào danh mục source-scoped.
  3. **`Extraction.java:1677–1734`:**
     - Cho phép trích xuất HTTP client hoạt động cả khi lớp client thuộc `ExplicitImportTypeDeclaration` (`SOURCE_RECOVERY` dưới `--build=off` cho Gradle/Maven cũ!).
     - Hỗ trợ `@FeignClient(name|value, url, path)` kết hợp các method `*Mapping` bên trong interface Feign (hỗ trợ cả placeholder `${rates.url}` tra từ `application*.yml`).
     - Hỗ trợ Spring 6.1 `RestClient` (`uri(...)`, `baseUrl(...)`), `OAuth2RestTemplate` (kế thừa `RestOperations`), `DiscoveryClient.getInstances("svc-name")`, và `LoadBalancerClient.choose("svc-name")`.
  4. **`HttpServiceCallProjection.java`:** Khớp host trong URI (`http://visits-service/...`, `lb://vets-service`, `serviceId`) với `spring.application.name` và tên module/service chuẩn hóa (case-insensitive).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-javaparser,analyzer '-Dtest=HttpClientRecoveryTest,HttpSpringPatternOracleTest,HttpServiceCallProjectionTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Phục hồi **`+14/15` cạnh RPC/HTTP nghiệp vụ** trên 5 repo EMSE '25 (`4` PiggyMetrics `@FeignClient`, `3` `OAuth2RestTemplate`, `VisitsServiceClient` + `genai` `RestClient`/`WebClient`, `ewolff` & `blog-ms` `LoadBalancerClient`, `anilallewar` `OAuth2RestTemplate`) với **`0` FP**.

---

#### Work Package `F3` (P1 | `1.5 d`, `~200 LOC`) — Spring Cloud Gateway (`lb://`, `StripPrefix`) & Netflix Zuul YAML Routes

- **Exact Files & Anchors:**
  - `backend/src/main/java/com/evolution/backend/analysis/StaticPolyglotScan.java`
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/HttpServiceCallProjection.java`
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/ServiceArchitectureProjection.java`
- **Algorithm & Data Contract:**
  1. Parse mảng `spring.cloud.gateway.routes[]` (và Boot 3.x/4.x `spring.cloud.gateway.mvc.routes[]` / `server.webflux.routes[]`): trích `id`, `uri` (`lb://<service>` hoặc `http(s)://<host>`), predicates (`Path=/api/vet/**`), và filters (`StripPrefix=N`, `RewritePath=...`).
  2. Parse bản đồ `zuul.routes.<id>.{path, serviceId, url, stripPrefix}` (kể cả trong `shared/gateway.yml` của Config Server).
  3. Phát hành: (a) cạnh `CALLS_SERVICE(gateway -> targetService)` kèm provenance YAML; (b) quy tắc viết lại tiền tố trong `HttpServiceCallProjection` để các lệnh `fetch("/api/vet/vets")` của frontend JS/TS trong `api-gateway` khớp chính xác với endpoint `/vets` sau `StripPrefix=2` ở `vets-service`.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl backend,analyzer '-Dtest=PolyglotScanTest,HttpServiceCallProjectionTest,ServiceArchitectureProjectionTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Phục hồi `6/6` cạnh Gateway/Zuul YAML trên PetClinic-MS & PiggyMetrics (`0` FP) + nối thông `5/5` lệnh JS `fetch` của PetClinic-MS tới đúng backend endpoint.

---

#### Work Package `F5` (P1 | `2.0 d`, `~220 LOC`) — Java Spring AMQP / RabbitMQ / Kafka / Spring Cloud Stream Extraction

- **Exact Files & Anchors:**
  - `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/Extraction.java`
  - `analyzer/src/main/java/com/evolution/analysis/spring/universal/SpringSourceEvidence.java`
  - `analyzer/src/main/java/com/evolution/analysis/polyglot/EventFlowProjection.java` (`L22–49`)
- **Algorithm & Data Contract:**
  1. Trích xuất các điểm sản xuất/tiêu thụ thông điệp Java chuẩn trong `Extraction.java` (hỗ trợ cả `SOURCE_RECOVERY`): `@RabbitListener(queues=...)`, `@KafkaListener(topics=...)`, `@StreamListener(...)`, `RabbitTemplate.convertAndSend(...)`, `KafkaTemplate.send(...)`.
  2. Trích xuất annotation & cấu hình broker chuẩn: `@EnableTurbineStream`, `@EnableZipkinStreamServer`, `@EnableBinding`, `spring.rabbitmq.host`, `spring.kafka.bootstrap-servers`, `spring.cloud.stream.bindings.*.destination`, và dependency `spring-cloud-starter-bus-amqp` / `spring-cloud-starter-stream-{rabbit,kafka}` từ build model thụ động.
  3. Mở rộng `EventFlowProjection.java` hợp nhất sự kiện từ cả Java và Tree-sitter, phát hành cạnh `PUBLISHES_TO` / `CONSUMES_FROM` tới broker `INFRA_COMPONENT` và cạnh `EVENT_FLOW` giữa producer/consumer cùng topic/queue.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer-javaparser,analyzer '-Dtest=JavaPolyglotFrontendTest,ServiceArchitectureProjectionTest,SpringAndCoreHardeningTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Phục hồi `+12/14` cạnh AMQP/Stream trên EMSE '25 5-repo (đẩy Strict `S5` lên `0.900`, DFD `S6` lên `0.932`) và phủ sạch các ứng dụng Kafka trong bộ 17 apps (`ewolff/microservice-kafka`).

---

#### Work Package `F7` (P1 | `3.5 d` Generic, `~380 LOC`) — Non-Spring Java: Standard JAX-RS (`@Path`, Inherited Abstract Resources, `WebTarget`) & `@WebServlet`

- **Exact Files & Anchors:**
  - `[PLANNED/NEW] analyzer/src/main/java/com/evolution/analysis/jaxrs/JaxRsRouteMapper.java`
  - `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/Extraction.java` (`L1677–1734`)
  - `analyzer/src/main/java/com/evolution/analysis/graph/CanonicalGraphBuilder.java` (`L239–254`)
- **Algorithm & Data Contract (Strict Generic Standard — ZERO TeaStore Hardcoding!):**
  1. **`JaxRsRouteMapper.java` *(PLANNED/NEW)*:**
     - Nhận diện annotation chuẩn Jakarta EE & Java EE: `{jakarta,javax}.ws.rs.Path`, `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `Produces`, `Consumes`, `ApplicationPath`.
     - **Kế thừa lớp tài nguyên trừu tượng tổng quát (Standard Java OO / JAX-RS Spec §3.6):** Khi một lớp cụ thể (không `abstract`) có `@Path("/categories")` kế thừa lớp cha `abstract` chứa các method `@GET/@POST/@PUT/@DELETE` (`AbstractCRUDEndpoint` trong TeaStore, hoặc bất kỳ base resource nào trong Quarkus/Jersey/RESTEasy), chiếu đủ các endpoint cụ thể bằng phép nối đường dẫn `combine(classPath, inheritedMethodPath)` và ghi rõ `inheritedFrom` trong provenance!
     - Nhận diện `{jakarta,javax}.servlet.annotation.WebServlet(value|urlPatterns=...)` phát hành `ENDPOINT` loại `SERVLET`.
  2. **Standard JAX-RS Client (`Extraction.java`):** Trích xuất chuỗi chuẩn `ClientBuilder.newClient().target(baseUri).path(segment).request().{get,post,put,delete}(...)` và `WebTarget.path(...)` có URI/host tĩnh hoặc tra được từ property/env.
  3. **Chốt Chống Overfitting (`I5`) & Chia Tầng Trung thực:**
     - **Tầng Generic `F7` (`3.5 d`):** Đạt `7/7` Components (`1.000`), `41/41` direct (`61/61` concrete) JAX-RS + `20/20` `@WebServlet` (`1.000`), `0` Locust FP, và **`6/13` Edges (`F1 = 0.632`)** trên TeaStore (từ Compose `env:DB_HOST/REGISTRY_HOST` + `WebTarget` trực tiếp). **TUYỆT ĐỐI KHÔNG** hardcode `ServiceLoadBalancer` hay `Service.<ENUM>`!
     - **Tầng Mở rộng Tùy chọn (`+2–3 d`, xem RQ-2):** Chỉ kích hoạt nếu cài đặt phân tích lan truyền wrapper inter-procedural đa mô-đun tổng quát và kiểm chứng trên ít nhất 1 repo phi-TeaStore (`6/13 → 10–13/13`).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,analyzer-javaparser '-Dtest=CanonicalGraphBuilderTest,CanonicalGraphPipelineIntegrationTest,HttpClientRecoveryTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Unit test mới `[PLANNED/NEW] analyzer/src/test/java/com/evolution/analysis/jaxrs/JaxRsRouteMapperTest.java` pass 100% cho `@Path` kế thừa lớp `abstract`, `jakarta` + `javax`, `@WebServlet`, và `WebTarget`; trên TeaStore đạt `7/7` Cp, `41/61` JAX-RS + `20` Servlet (`1.000`), `>=6/13` edges (`0.632`), `0` Locust FP, `0` chuỗi `"ServiceLoadBalancer"` trong `src/main/`.

---

### 3.3. Pillar C — Spring Auto-Configuration ASM + COI-Sliced CDCL SAT (`S1`..`S5` | Total: `17.0–20.0 d` + Go/No-Go Gate)

Mục tiêu: Mở khóa bài Flagship #2 (**Track 1: ICSE 2028 Research / OOPSLA 2027 R2** — độ mới học thuật `9.5/10`) bằng cách xóa điểm mù `99.7%` điều kiện starter JAR bytecode (Shopizer: `4` `@Profile` user vs `1,333` starter markers) và loại bỏ trần `MAX_DIMENSIONS = 32` / naive DPLL `byte[].clone()`, trong khi vẫn giữ **100% `--build=off`**.

---

#### Work Package `S1` *(P2 / Go-No-Go Spike | `5.0 d`)* — Passive ASM Bytecode Auto-Configuration Extractor

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/spring/universal/AutoConfigurationMetadata.java` (`L14–57`: đọc danh sách class name từ `META-INF/spring.factories` `L35` & `AutoConfiguration.imports` `L36`)
  - `analyzer/src/main/java/com/evolution/analysis/spring/universal/SourceToSpringPlan.java` (`L28–1178`)
  - Tái sử dụng thụ động hạ tầng ASM sẵn có: `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/ResolutionEnvironment.java` (`AsmBytecodeIndex` / `org.ow2.asm:asm:9.10.1` trong root `pom.xml:63`) và tải thụ động HTTPS `analyzer-maven/src/main/java/com/evolution/analysis/maven/MavenDependencyArtifactProvider.java` (`L28–34`)
  - `[PLANNED/NEW] analyzer/src/main/java/com/evolution/analysis/spring/universal/BytecodeAutoConfigScanner.java`
- **Algorithm & Data Contract (100% Passive `--build=off`):**
  1. Từ kết quả `AutoConfigurationMetadata.read(...)`, lấy tập các lớp ứng viên `candidates` (và đệ quy các lớp lồng `InnerClasses` có `@Configuration` / `@Import` bên trong cùng starter JAR).
  2. Dùng `org.objectweb.asm.ClassReader` (`SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES` — siêu nhanh, không bao giờ nạp hay thực thi `ClassLoader` JVM!) đọc từng `.class` ứng viên trực tiếp từ các JAR đã xác thực SHA-256 trên exact classpath:
     - **Class-level:** Trích `@AutoConfiguration`, `@Configuration`, `@Profile`, mọi `@ConditionalOn*` (`MissingBean`, `Bean`, `Property`, `Class`, `MissingClass`, `WebApplication`, `SingleCandidate`, `Expression`, `Resource`, `Java`), `@AutoConfigureBefore`, `@AutoConfigureAfter`, `@AutoConfigureOrder`, `@Import`.
     - **Method-level (`@Bean`):** Trích return type descriptor, tên bean, mọi `@ConditionalOn*` / `@Profile` / `@Primary` / `@Fallback` trên phương thức `@Bean`, và danh sách kiểu tham số (injection sites của starter!).
  3. Gắn `ConditionEvidence.Artifact(jarCoord, jarSha256, entryPath, entrySha256)` cho từng bản ghi phát hành vào `SpringMechanismInventory` & `SourceToSpringPlan`.
  4. **Invariant Check (`I1`, `I2`):** Tuyệt đối không chạy `Class.forName()`, không chạy build tool; sắp xếp ứng viên theo `(jarSha256, classFqn, methodDescriptor)` để bảo đảm 100% bit-identical SHA-256.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,analyzer-javaparser '-Dtest=SourceToSpringPlanTest,SpringSatSoundnessTest,UniversalSpringSemanticsTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Trên Shopizer classpath (`m2-shopizer`, 5 starter JARs), trích xuất thụ động được `>=1,300` class-level markers + method-level `@Bean` + ordering edges trong `<2.0 s`; trên `m2-cache` (Boot 4.1.2 + Spring AI 2.0.2), trích xuất `>=145` markers từ `AutoConfiguration.imports`.

---

#### Work Package `S2` (P2 | `3.0 d`) — Modular Boot 4 / Spring Cloud / Spring AI & Meta-Annotation Condition Lowering

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/spring/condition/ConditionEvidenceLowering.java` (`L233`, `L255` `ANNOTATION_COMPOSITION_UNSUPPORTED`, `L392–408` `exactAnnotationType()` khóa cứng `spring-boot-autoconfigure` và `spring-context`)
- **Algorithm & Data Contract:**
  1. Thay bộ lọc cứng 2 artifact (`L397–405`) bằng xác thực dựa trên: (a) FQN chuẩn của họ annotation Spring (`org.springframework.boot.autoconfigure.condition.*`, `org.springframework.context.annotation.*`, `org.springframework.cloud.*`, `org.springframework.ai.*`) nằm trong bất kỳ verified dependency JAR nào thuộc group allowlist có chữ ký SHA-256 (`org.springframework.*`, `org.springframework.boot`, `org.springframework.cloud`, `org.springframework.ai`, `org.springframework.security`).
  2. Cài đặt **Bounded Meta-Annotation Traversal (`maxDepth = 3`, chống chu trình bằng `visited` set)** trên bytecode ASM của annotation: phân giải các annotation tổ hợp như `@ConditionalOnEnabledHealthIndicator`, `@ConditionalOnDiscoveryEnabled`, `@ConditionalOnMissingFilterBean`, `@SpringBootApplication` thành hội (conjunction) các điều kiện cơ sở + alias thuộc tính (`@AliasFor`).
  3. Khi gặp `@Conditional(CustomCondition.class)` mà không thể hạ bậc tĩnh, giữ nguyên nút điều kiện có kiểu tường minh `OPAQUE_CUSTOM_CONDITION(conditionClassFqn)` (tạo biến boolean ngoại sinh tự do trong SAT để bảo toàn soundness `MAY`/`UNKNOWN`, không bao giờ xóa âm thầm).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,analyzer-javaparser '-Dtest=SpringSatSoundnessTest,UniversalSpringSemanticsTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Pass 100% trên Spring Boot 4.1.2 modular starter JARs, Spring AI 2.0.2, và fixture meta-annotation depth 2–3.

---

#### Work Package `S3` (P2 | `2.0 d`) — Phase-0 `bootstrap.*`, Boot 2.x `spring.profiles` & Config Server `shared/*.yml`

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/spring/condition/ConfigDataIngestion.java` (`L47–62` `isApplicationConfigFile()`, `L98–109`, `L130–132` `CONFIG_ACTIVATION_UNKNOWN`)
- **Algorithm & Data Contract:**
  1. Mở rộng `isApplicationConfigFile()` nhận diện tầng **Phase-0** `bootstrap.{yml,yaml,properties}` và `bootstrap-<profile>.{yml,yaml,properties}` với độ ưu tiên hợp nhất đúng ngữ nghĩa Spring Cloud Bootstrap Context (thấp hơn `application.*` cục bộ khi không override, nhưng cung cấp `spring.application.name` và cấu hình Config Client).
  2. Tại `L131`, phân tích cú pháp legacy Spring Boot 2.x `spring.profiles: "!prod"` / `"dev,staging"` thành biểu thức Boolean profile chuẩn thay vì gán `enabled = false` (`CONFIG_ACTIVATION_UNKNOWN`). Giữ `spring.config.activate.on-cloud-platform` là biến ngoại sinh hữu hạn (`KUBERNETES | CLOUD_FOUNDRY | HEROKU | SAP | AZURE_APP_SERVICE | NONE`).
  3. Quét thụ động thư mục `search-locations` cục bộ trong cùng repo của Spring Cloud Config Server (như `config/src/main/resources/shared/*.yml`), ánh xạ `<service>.yml` vào ngữ cảnh dịch vụ tương ứng với provenance rõ ràng.
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,analyzer-javaparser '-Dtest=ConfigDataIngestionTest,ConfigDataOracleTest,ConfigImportOracleTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** Khôi phục toàn bộ `41/41` tệp `bootstrap.*` trên `piggymetrics` (`15`), `blog-ms` (`11`), `anilallewar` (`9`), `ewolff` (`6`) với `0` lỗi `CONFIG_ACTIVATION_UNKNOWN` trên `spring.profiles` Boot 2.x.

---

#### Work Package `S4 + S5` (P2 | `7.0–10.0 d`) — Cone-of-Influence (COI) Slicing + Incremental CDCL SAT & Cube Generalization

- **Exact Files & Anchors:**
  - `analyzer/src/main/java/com/evolution/analysis/spring/condition/RepositoryConfigurationSpaceBuilder.java` (`L19–20`: `MAX_DIMENSIONS = 32`, `MAX_VALUES_PER_PROPERTY = 8`; `L130–145, 167`)
  - `analyzer/src/main/java/com/evolution/analysis/spring/truth/SatConfigurationReasoner.java` (`L10, 62–95`: naive DPLL cloning `byte[]` tại `L93`)
  - `analyzer/src/main/java/com/evolution/analysis/spring/truth/SymbolicTruthRegions.java` (`L143, 228, 236, 253–258`: double `InjectionBindings.evaluate()` tại `L228 & L236` + 256-signature cap)
  - `analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalArchitecturePipeline.java` (`L98`: `DEFAULT_SAT_MAX_SIGNATURES = 256`)
  - `[PLANNED/NEW] analyzer/src/main/java/com/evolution/analysis/spring/condition/ConeOfInfluenceSlicer.java`
  - `[PLANNED/NEW] analyzer/src/main/java/com/evolution/analysis/spring/truth/IncrementalCdclReasoner.java`
- **Algorithm & Data Contract:**
  1. **Cone-of-Influence (COI) Connected-Component Decomposition (`S4`):**
     - Dựng đồ thị phụ thuộc hai phía có hướng `G_dep = (V_bean ∪ V_var ∪ V_site, E)`:
       - Cạnh `var → bean` nếu điều kiện của `bean` đọc biến cấu hình/profile/property `var`.
       - Cạnh `bean_A → bean_B` nếu `bean_B` có `@ConditionalOnBean(T)` / `@ConditionalOnMissingBean(T)` / `@ConditionalOnSingleCandidate(T)` khớp kiểu cung cấp của `bean_A`, hoặc `bean_B` nằm trong pha sau phụ thuộc thứ tự (`@AutoConfigureAfter`/`@AutoConfigureBefore`).
       - Cạnh `bean → site` nếu `bean` là ứng viên tiêm cho `InjectionSite` `site`.
     - Tính các thành phần liên thông yếu (hoặc backward COI slice từ từng nhóm `InjectionSite` + quy tắc kiến trúc):
       $$\text{COI}(s) = \text{Reach}^{-1}_{G_{\text{dep}}}(s)$$
     - **Định lý Bảo toàn Soundness (`HYPOTHESIS` chứng minh hình thức ở RQ-1):** Vì mọi ràng buộc trong 4 pha đăng ký Spring của một `InjectionSite` chỉ phụ thuộc vào tập bean ứng viên và tập biến ngoại sinh thuộc $\text{COI}(s)$ (và các ràng buộc chéo giữa các biến property chung), giải SAT độc lập trên từng thành phần liên thông $\mathcal{C}_k$ cho kết quả `MUST/MAY/NEVER` **đồng nhất 100%** với giải toàn cục — trong khi kích thước cục bộ thường chỉ `2–12` biến thay vì `300–500+` biến toàn cục!
     - Gỡ bỏ trần cứng `MAX_DIMENSIONS = 32` toàn cục; áp dụng ngân sách trên từng thành phần liên thông (ví dụ `MAX_COMPONENT_VARS = 64`), chỉ đánh dấu `CONFIGURATION_SPACE_DIMENSION_BOUNDED` cho riêng thành phần nào thực sự vượt ngưỡng.
  2. **Incremental CDCL Solver with Assumptions (`S5a`):**
     - Thay thế DPLL 107 dòng (`byte[].clone()` + linear scan) bằng bộ giải **Incremental CDCL thuần Java** (tích hợp **Sat4j** `org.ow2.sat4j.core` hoặc bộ giải `2-Watched-Literals + 1-UIP Conflict Clause Learning + VSIDS + Non-Chronological Backjumping` thuần Java) hỗ trợ `solve(int[] assumptions)` và `addClause(int[] blockingClause)`.
     - **Encode CNF ĐÚNG 1 LẦN** cho mỗi thành phần COI (xóa bỏ hoàn toàn chi phí re-encode toàn bộ Boolean DAG ở mỗi chữ ký!).
  3. **Prime-Implicant Cube Generalization (`S5b`):**
     - Khi bộ giải tìm ra một nghiệm $\alpha$ thỏa mãn một kết quả injection/chữ ký $\sigma$, rút gọn $\alpha$ thành **prime implicant cube** $c \subseteq \alpha$ (loại bỏ mọi biến *don't-care* không làm thay đổi đánh giá pha của các bean trong $\text{COI}$) trước khi thêm mệnh đề chặn $\neg c$ vào solver. Điều này nén `2^k` nghiệm đẳng cấu thành `1` bước lặp thay vì chạm trần `256` chữ ký ở `8` biến độc lập!
  4. **Eliminate Production Double-Eval (`S5c`):**
     - Tại `SymbolicTruthRegions.java:228–241`, chỉ chạy `replay = InjectionBindings.evaluate(...)` lần thứ 2 khi bật cờ kiểm toán `-Devo.spring.verifyWitnessReplay=true` (luôn bật trong JUnit test, tắt mặc định ở production scan — cắt ngay **50%** chi phí đánh giá binding!).
- **Verification Commands & Exit Criteria:**
  ```powershell
  mvn -pl analyzer,analyzer-javaparser '-Dtest=SpringSatSoundnessTest,UniversalArchitectureM4EAdjudicationTest,UniversalSpringSemanticsTest' '-DfailIfNoTests=false' test
  ```
  - **Exit Gate:** (1) `79/79` runtime parity & `4/4` ordering & `12/12` P01 giữ nguyên `100%`; (2) Property-based equivalence test so sánh `COI-CDCL` vs `Brute-Force / Naive DPLL` trên 500 đồ thị ngẫu nhiên đạt `100%` khớp `MUST/MAY/NEVER`; (3) Trên Shopizer + 5 starter JARs (`1,333` markers), thời gian giải SAT `<10 s` và tỷ lệ `SOLVER_LIMIT < 5%`.

---

#### 3.3.6. Mandatory Non-Tautological Track 1 Go/No-Go Quantitative Gate (End of `S1` Spike — 1 Week)

> **PC-Chair Rule (Blocker #6):** Nghiêm cấm dùng tiêu chí hiển nhiên kiểu `>=500 conditional beans/repo` (vì riêng `spring-boot-autoconfigure-2.5.12.jar` đã có `834` markers!). Đội ngũ chỉ được cam kết `S2–S5` + viết bài Track 1 (ICSE '28 / OOPSLA '27 R2) khi nguyên mẫu 1 tuần `S1 + COI prototype` trên **5 OSS Spring Boot giàu conditional** (đề xuất: *Spring Cloud Gateway*, *Spring Authorization Server*, *Halo CMS*, *Flowable Engine*, *Shopizer + starter JARs*) vượt đồng thời cả **3 tiêu chí khoa học bắt buộc**:

| # | Tiêu chí Go/No-Go Bắt buộc | Ngưỡng PASS (`GO`) | Hành động nếu FAIL (`NO-GO`) |
| :---: | :--- | :--- | :--- |
| **G1** | **Non-Trivial User ↔ Starter Interaction Density** | **`>= 10`** điểm tương tác thực `user @Bean/@Configuration/application.yml ↔ starter @ConditionalOnMissingBean/@Profile/@ConditionalOnProperty` mỗi repo trên **`>= 3 / 5`** repo thử nghiệm | Nếu đa số chỉ là starter độc lập không đụng user code → thu hẹp phạm vi sang *Multi-Config Call-Graph/Bean Soundness* (kiểu JackEE/Jasmine) hoặc chuyển nguồn lực sang Track 3. |
| **G2** | **Verified Real Findings OR Single-Config FP Elimination** | **`>= 5`** phát hiện conditional DI/architecture có thật (shadowed security/cache bean, dead `NEVER` bean, conditional cycle, profile-gated missing bean) **HOẶC** loại bỏ **`>= 20`** báo động giả cấu hình xung khắc — **xác nhận thực thi bằng `ApplicationContextRunner`** | Dừng cam kết bài Research Track 1 tại ICSE/OOPSLA; giữ `S1` như cải tiến sản phẩm nội bộ cho Evo. |
| **G3** | **COI-Sliced SAT Scalability** | Thời gian giải COI-CDCL **`< 10 s` / repo** với tỷ lệ rơi về **`SOLVER_LIMIT < 5%`** khi đã nạp toàn bộ starter JAR bytecode | Giải quyết nút thắt thuật toán COI/cube generalization (xem RQ-1) trước khi tiến hành đánh giá 15–20 OSS. |

---

### 3.4. Pillar D — GraphRAG & Closed-Loop Verified Architectural Repair (`R1`..`R4` | Deferred to Phase 3, Except Optional `R1`)

Mục tiêu: Đóng gói rõ các nút thắt kỹ thuật đã kiểm toán ở `02_spring_perf_and_repair_forensics.md` để có thể kích hoạt ngay `R1` (~1 tuần sau Pillar A cho Chinthareddy `15/15` phụ trợ) và triển khai bài thứ 3 (`RepoProbe` / `RefAgent` Architectural Refactoring) vào cuối 2027.

| Mã gói | Exact Evo Files & Line Anchors | Thuật toán / Thay đổi Kỹ thuật Chính xác | Công sức | Lệnh Kiểm chứng & Exit Criteria |
| :--- | :--- | :--- | ---: | :--- |
| **R1** *(Optional Post-P0)* | `cli/src/main/java/com/evolution/cli/EvoApp.java`, `backend/src/main/java/com/evolution/backend/query/ArchitectureAgent.java` (`L1–2126`) | Bổ sung lệnh CLI không giao diện `evo ask --graph <graphId> --batch questions.jsonl --out answers.jsonl --max-rounds 6`, gọi trực tiếp `ArchitectureAgent` với 20 tool chỉ đọc + trích dẫn `[E#]`, xuất JSONL chuẩn hóa (answer, citations, toolCalls, latencyMs, tokenUsage). | `~5 d` | `mvn -pl backend,cli '-Dtest=Wave1SecurityAndAgentTest,EvoScoreV2AndIncrementalCliTest' '-DfailIfNoTests=false' test`. Exit: Chạy trơn `15/15` câu hỏi Chinthareddy trên Shopizer với `100%` `[E#]` citations hợp lệ. |
| **R2** *(Phase 3: RepoProbe)* | `backend/src/main/java/com/evolution/backend/query/ArchitectureAgent.java` (`L30, 93, 157, 179–215, 623`), `backend/src/main/java/com/evolution/backend/query/ArchitectureChatModel.java` (`L33–90`), `backend/src/main/java/com/evolution/backend/query/RepoMapBuilder.java` (`L69, 215`) | (a) Nâng `MAX_ROUNDS` từ `10 → 16–20` và thay `compactToolMessages()` (cắt `>1,200` chars thành stub) bằng **importance-preserving sliding window** giữ nguyên danh sách file/symbol/line đã xác minh; (b) Thêm chế độ prompt `BENCHMARK_QA` trong `ArchitectureChatModel` triệt tiêu *Edit Bias* & lời khuyên thừa để tối ưu chấm atomic checklist của RepoProbe; (c) Nâng trần `RepoMapBuilder` non-symbol seeding `500 → 5,000` files; (d) Tích hợp cross-file symbol linking polyglot (xem RQ-7). | `2–3 wks` | `mvn -pl backend '-Dtest=Wave1SecurityAndAgentTest' test`. Exit: Controlled ablation trên **cùng backbone LLM** đạt **`+5–12 pp`** trên tập con Java/Spring/polyglot của `Tencent-Hunyuan/RepoProbe`. |
| **R3** *(Phase 3: Closed-Loop Repair)* | `backend/src/main/java/com/evolution/backend/query/CapturedRepair.java` (`L131, 220–228, 254, 273–367`), `backend/src/main/java/com/evolution/backend/query/repair/RepairSyntaxGate.java` (`L1–247`), `backend/src/main/java/com/evolution/backend/query/repair/RepairContextPlanner.java` (`L27, 117–145`), `backend/src/main/java/com/evolution/backend/query/CapturedRepairRunner.java` (`L1–336`), `backend/src/main/java/com/evolution/backend/query/CapturedRepairVerification.java` (`L53, 149, 237`) | (a) Nối vòng phản hồi đóng `CapturedRepair ↔ DOCKER_HARDENED_CONTAINER` (`--network=none --read-only --cap-drop=ALL`): sau `RepairSyntaxGate`, chạy `javac` + affected JUnit trong container cứng và truyền ngược cấu trúc `compilerErrors` + `failingTests` stack trace vào LLM tối đa `3–4` vòng; chỉ nâng lên **`QEMU_IN_DOCKER` hypervisor** để chứng nhận cuối + Negative Control cho ứng viên pass container! (b) Nâng giới hạn `RepairContextPlanner` từ `MAX_EXPANDED_FILES=6`, `callerAdded<=3` lên `MAX_EXPANDED_FILES=20`, `maxCallers=15` điều khiển theo token budget. | `2–3 wks` | `mvn -pl backend '-Dtest=CapturedRepairV2Test,RepairVerificationReportTest' test`. Exit: Vòng retry sửa thành công lỗi type + JUnit regression trên fixture đa file (`>=10` callers) với `0` thực thi trên máy chủ. |
| **R4** *(Phase 3: Multi-JDK Sandbox & Arch Recipes)* | `analyzer-execution/src/main/resources/captured-repair/run.py` (`L25, 254–354, 731–735`), `analyzer-execution/src/main/resources/captured-repair/guest-init.sh`, `backend/src/main/java/com/evolution/backend/query/JavaRepairRecipes.java` (`L12–28, 228`) | (a) Chuyển `run.py` sang **base image dựng sẵn theo đa JDK (`8`, `11`, `17`, `21`)** (gỡ khóa cứng 1 digest `eclipse-temurin@sha256:82801f9d...` tại `L25`) + gắn read-only bind-mount `.m2` cache thay cho rebuild Docker mỗi ứng viên (`30–120 s` → `<3 s` startup) và thay regex `stage_m2_subset`; (b) Thêm bước baseline test preflight + bộ chọn `-Dtest=...` theo SKG impact graph; (c) Mở rộng `JavaRepairRecipes` thêm `4–6` recipe kiến trúc đa file (Cycle Breaking, Extract Class / God-Class Decomposition, Move Method) + adapter `DesigniteJava`. | `2–3 wks` | `mvn -pl analyzer-execution,backend '-Dtest=ExecutionControllerTest,CapturedRepairV2Test' '-DfailIfNoTests=false' test`. Exit: Chạy kiểm chứng thành công trên repo Java 8/11/17/21 không rebuild image mỗi lượt; duy trì `9/9` QEMU Negative Control. |

---

## 4. Reproducible Benchmark Harness & Ablation Protocols (`E1`..`E5`)

Mọi thực nghiệm cho bài báo phải tuân thủ **Giao thức Tái lập Chuẩn Linux (`E0`)** nhằm triệt tiêu nhiễu Windows Defender / NTFS (giải quyết Kill List `#8`) và khóa chặt tính tái lập Zenodo:

### 4.0. Standardized Measurement Protocol (`E0`)

- **Primary Publication Hardware/OS:** Bare-metal Ubuntu `24.04 LTS` (hoặc Linux Docker container ghim CPU affinity, `cpupower frequency-set -g performance`, tắt background jobs, không có antivirus real-time hook). Giữ bảng phụ Windows 11 Pro (`i5-14400F`, `31.79 GB`) trong Appendix để chứng minh tính di động đa nền tảng.
- **Statistical Rigor:** Chạy **`5` lượt lặp độc lập** cho mỗi cấu hình `(Repo × Mode × RunType)`; báo cáo **`Median ± IQR`** (và `Min/Max` trong artifact CSV).
- **Mandatory 3-Way Runtime Decomposition (Chống Kill List `#4`):** Mọi bảng hiệu năng **bắt buộc** báo cáo tách biệt 3 cột:
  1. **Cold Run** (xóa sạch `.evo` workspace trước mỗi lượt),
  2. **Warm `NO_CHANGE`** (chạy lại ngay trên cùng snapshot — xác minh `SHA-256` bằng 100% Cold),
  3. **Warm `1-File Edit`** (tạo thay đổi AST thật trong 1 phương thức non-trivial của 1 service module — xác minh `SHA-256(Warm 1-File Edit) == SHA-256(Cold on Edited Repo)`!).

### 4.1. Experiment `E1` — Full 17-App `microSecEnD` Head-to-Head (`Track 2`)

- **Public Repositories & Scoring Harness:**
  - Dataset & Ground-Truth DFDs: `https://github.com/tuhh-softsec/microSecEnD` (**đủ `17/17` ứng dụng**).
  - Exact Evaluation Scripts: `https://github.com/M3SOulu/EMSE2025SAR-Replication`.
  - Baseline Tools for Verification: `https://github.com/tuhh-softsec/code2DFD`, `https://github.com/clowee/MicroDepGraph`.
- **Complete Verified Inventory of All 17 `tuhh-softsec/microSecEnD` Applications (`CONFIRMED`):**
  1. `anilallewar/microservices-basics-spring-boot` *(audited in 5-repo baseline)*
  2. `apssouza22/java-microservice`
  3. `callistaenterprise/blog-microservices` *(audited in 5-repo baseline)*
  4. `ewolff/microservice` *(audited in 5-repo baseline)*
  5. `ewolff/microservice-kafka` *(chú ý: đây là ứng dụng Kafka thật trong 17 apps; **`oversengineer/microservices-kafka` KHÔNG thuộc `microSecEnD`** — nghiêm cấm nhầm lẫn trong bài báo!)*
  6. `fernandoabcampos/spring-netflix-oss-microservices`
  7. `georgwittberger/apache-spring-boot-microservice-example`
  8. `jferrater/tap-and-eat-microservices`
  9. `koushikkothagal/spring-boot-microservices-workshop`
  10. `mdeket/spring-cloud-movie-recommendation`
  11. `mudigal-technologies/microservices-sample`
  12. `piomin/sample-spring-oauth2-microservices`
  13. `rohitghatol/spring-boot-microservices`
  14. `shabbirdwd53/springboot-microservice`
  15. `spring-petclinic/spring-petclinic-microservices` *(audited in 5-repo baseline)*
  16. `sqshq/piggymetrics` *(audited in 5-repo baseline)*
  17. `yidongnan/spring-cloud-netflix-example`
- **Mandatory `B1` Verification Checks (PC-Chair Blocker #5):**
  1. Ghim đúng commit SHA của bảng EMSE '25 từ `M3SOulu/EMSE2025SAR-Replication` (tránh trôi HEAD).
  2. Thay thế hoàn toàn con số `ASSUMPTION ~730 GT` bằng **mẫu số GT thực tế** parse từ 17 file DFD chuẩn.
  3. **Xác minh công thức tổng hợp của EMSE '25 (`Micro-Average F1` vs `Macro-Average F1`)** trực tiếp trong script `M3SOulu` và báo cáo **cả hai** (Micro + Macro) cho cả 2 chế độ `--projection-profile=strict` (`S5`) và `--projection-profile=dfd-export` (`S6`).

### 4.2. Experiment `E2` — Cumulative Extractor Ablation (`S0 → S6`, `Track 2`)

Chạy tuần tự từng cờ trích xuất trên 5-repo audited + 17-app full suite để chứng minh đóng góp từng tầng và phát hiện Pareto `60.8%` control-plane:

```text
S0 (cb79c322 baseline)
 -> S1 (+F1: Source-scoped *Mapping & MediaType.*)
 -> S2 (+F2: @FeignClient, RestClient, OAuth2RestTemplate, Discovery/LB, URI folding)
 -> S3 (+F3: Spring Cloud Gateway lb:///StripPrefix & Zuul YAML)
 -> S4 (+F4: Standard Compose/K8s Infra & Spring Cloud Control-Plane/Prometheus/Grafana)
 -> S5 (+F5: Java AMQP/RabbitMQ/Kafka/Stream)  [STRICT EVIDENCE-FIRST CEILING]
 -> S6 (+F4b: --projection-profile=dfd-export) [DFD COMPATIBILITY MODE]
```

### 4.3. Experiment `E3` — Polyglot, Non-Spring & Build-Safety Generalizability (`Track 2`)

1. **Online Boutique (`GoogleCloudPlatform/microservices-demo`, commit `c7cb6d5`):** Chạy ablation `OB0 → OB1 (+F6a) → OB2 (+F6b) → OB3 (+F4-K8s)` so với `Ripple eBPF` (`arXiv:2510.15490`) và công cụ tĩnh Java-only.
2. **TeaStore (`DescartesResearch/TeaStore`, commit `34b37f7`):** Chạy `TS0 → TS1 (+F4+F6b) → TS2 (+F7 generic)` chứng minh `7/7` Cp, `41/61` JAX-RS + `20` `@WebServlet` (`1.000`), `0` Locust FP, `6/13` edges (`0.632`) với **0% hardcoding** (và báo cáo dòng tách biệt nếu bổ sung tầng inter-procedural tổng quát RQ-2).
3. **`Micans` (ISSTA '25, Zenodo `15050505`) Controlled Comparison (`B2`):** Tải bộ microservice chuẩn từ Zenodo `15050505`, đo tỷ lệ phân tích thành công khi **không biên dịch được trên máy chủ (`--build=off`)**, thời gian chạy và độ phủ cạnh liên dịch vụ giữa Evo (`--build=off`) và `Micans` (yêu cầu bytecode biên dịch `Tai-e`).
4. **Mode A (`--offline`) vs Mode B (Prepared Passive Maven Cache):** Đo chênh lệch Recall/Precision và `CapabilityGapRecord` khi không có mạng so với có cache JAR thụ động.

### 4.4. Experiment `E4` — Java Semantic Fidelity & Pillar 2 Storage/Incremental Ablation (`Track 2`, Claim C3)

1. **OpenRewrite `v8.87.7` Comparator:** Chạy trên `ArchitectureFixture.java` (Java 21 chained generics: `7/7`) và `spring-petclinic` (`818c4136`, `220` calls: Config A tách rõ `122/220` Strict vs `124/220` `SOURCE_SCOPED`, Config B `220/220`, `0` vs `57–89` span errors).
2. **Pillar 2 Storage & Incremental Ablation (PC-Chair Blocker #6 — Bắt buộc):** Trên toàn bộ `9+` repo (nhấn mạnh **Shopizer** & **TeaStore**), đo tuần tự 5 cấu hình (`5` lượt Linux, `Median + IQR`, kiểm tra `100%` `SHA-256` parity):
   - **`A0 (Baseline cb79c322)`**: Timeout `>180 s` Shopizer, `68.22 s / 89.72 s (0.76×)` TeaStore.
   - **`A1 (+P2-1 Gap Coalescing)`**: Đo giảm số gap (`74,529 → ~2k`), giảm dung lượng `values.bin` & `.graph.json`.
   - **`A2 (+P2-2 Heap-Aware Memory Workspace)`**: Đo cắt bỏ toàn bộ `137.43 s` spill Shopizer & `20.73 s` TeaStore warm `semantic_ms`.
   - **`A3 (+P2-3 NO_CHANGE + P2-4 CAS In-Memory Hash & Skip Self-Audit)`**: Đo cắt `60–75%` `graph_publish_ms` và đưa Warm `NO_CHANGE` xuống `1.0–3.5 s`.
   - **`A4 (+P2-6 CLI IncrementalGraphProjector)`**: Đo tăng tốc thật **`3.5×–6.0×` trên 1-File Edit** (`4.0–7.5 s` TeaStore, `4.0–9.0 s` Shopizer) với `100%` SHA-256 khớp Cold Run của bản đã sửa.

### 4.5. Experiment `E5` — Track 1 Automated `ApplicationContextRunner` Oracle & SAT Ablation (`Track 1`)

- **Corpus (`15–20` Conditional-Heavy Real OSS Spring Boot Apps):** *Spring Cloud Gateway*, *Spring Authorization Server*, *Spring Boot Admin*, *Halo CMS*, *Flowable Engine*, *Apache ShenYu*, *JHipster Sample Microservice*, *RuoYi-Cloud*, *Shopizer + starter JARs*, *Spring PetClinic + Boot 4 modular starter JARs*, cộng các dự án Spring Boot thực từ GitHub có mật độ `@ConditionalOn*`/`@Profile` cao.
- **Zero-Bias Oracle (`ApplicationContextRunner`):** Với mỗi kết luận `(bean/injectionSite, MUST|MAY|NEVER, witnessAssignment)` của Evo, bộ sinh test tự động tạo bài kiểm thử `new ApplicationContextRunner().withUserConfiguration(...).withPropertyValues(witness)...` kiểm tra thực tế bean có được tạo/tiêm hay ném `NoSuchBeanDefinitionException`/`NoUniqueBeanDefinitionException`.
- **Ablation 3 tầng SAT:** So sánh trực tiếp **(1) Naive DPLL hiện tại (trần 32 biến / 256 sig)** vs **(2) Global CDCL (không COI)** vs **(3) COI-Sliced Incremental CDCL + Cube Generalization** về thời gian giải, `% SOLVER_LIMIT`, số mệnh đề CNF, và kích thước thành phần COI lớn nhất.
- **Baselines:** Đối chiếu `Flat-Condition Baseline`, `Single-Config Baseline (ArchUnit/Jasmine)`, và `MeCheck` (FSE '25).

### 4.6. Zenodo One-Command Reproducibility Artifact Specification (`Artifact Available + Reusable`)

Mỗi bản nộp (FSE Demo, Track 2 EMSE/ASE, Track 1 ICSE/OOPSLA) đi kèm gói artifact chuẩn lưu trữ lâu dài trên Zenodo:
```bash
# 1-command reproduction inside pinned Docker image (zero host side effects)
docker run --rm --network=none -v "$PWD/artifact-out:/out" evo-artifact:<paper-tag> \
  /opt/evo/reproduce.sh --suite all --repeats 5 --verify-sha256
```
- Chứa: `evo.jar` build tái lập (`project.build.outputTimestamp`), bản clone nông đã ghim SHA commit + `.m2` offline cache của từng repo chuẩn, `manifest.sha256` kiểm tra tính toàn vẹn đầu vào/đầu ra, script sinh lại 100% bảng số liệu LaTeX/CSV và biểu đồ từ kết quả thô.

---

## 5. Deep Open Research Questions & Autonomous Investigation Prompts (RQ-1..RQ-8)

Mục này cung cấp **8 bài toán nghiên cứu & thuật toán mũi nhọn (frontier)** thiết kế riêng cho các phiên agent lý luận sâu tiếp theo (**Gemini 4 Neon XHigh**). Mỗi RQ gồm: bối cảnh & neo mã nguồn Evo, giả thuyết hình thức (`HYPOTHESIS`), tài liệu cốt lõi cần khảo sát, hướng thiết kế thuật toán, và bài kiểm thử chấp nhận nguyên mẫu (Prototype Acceptance Test).

---

### RQ-1: Formal Soundness & Completeness of COI-Sliced Incremental CDCL over Non-Monotone 4-Phase Spring Bean Registration

- **Problem & Evo Anchor:** Trong Spring Boot, `@ConditionalOnMissingBean` làm cho phép đánh giá bean trở nên **phi đơn điệu (non-monotone)**: sự xuất hiện của bean $b_1$ ở Pha 1 (user `@Component`/`@Bean`) *triệt tiêu* bean $b_2$ ở Pha 3 (`@AutoConfiguration`), và sự triệt tiêu $b_2$ lại có thể *kích hoạt* bean $b_3$ ở Pha 4 (`@AutoConfigureAfter(b2)`). Khi cắt lát **Cone-of-Influence (COI)** (`S4`, `RepositoryConfigurationSpaceBuilder.java`, `SymbolicTruthRegions.java`), liệu chia tách thành phần liên thông yếu có giữ nguyên tính vững (`Soundness`) và đầy đủ (`Completeness`) nếu 2 thành phần chia sẻ cùng một biến property ràng buộc (property mutex / YAML profile activation) hoặc có `@AutoConfigureOrder` tạo chu trình phụ thuộc điều kiện?
- **`HYPOTHESIS`:** Nếu đồ thị phụ thuộc COI bao gồm **đồng thời** (i) cạnh điều kiện `var → bean`, (ii) cạnh đăng ký phi đơn điệu `provider(T) → consumer(@ConditionalOn[Missing]Bean(T))` có thứ tự pha, và (iii) cạnh đồng xuất hiện trong các ràng buộc nền YAML (`ConfigDataIngestion` profile/document activation), thì mọi thành phần liên thông yếu $\mathcal{C}_k$ là **độc lập xác suất/logic tuyệt đối**, tức $\text{Truth}_{\text{global}}(\phi) \equiv \text{Truth}_{\mathcal{C}_k}(\phi)$ với mọi $\phi \in \mathcal{C}_k$, đồng thời giảm kích thước không gian biến cực đại từ $O(N_{\text{repo}})$ (`300–500+`) xuống trung vị `<=12` biến.
- **Key Literature to Investigate:** Kästner et al. (*TypeChef*, OOPSLA '11); Sundermann et al. (d-DNNF / feature-model slicing, TSE Jan '26); Stratification in Non-Monotone Logic Programming / Answer Set Programming (ASP / Datalog$\neg$); Antoniadis et al. (*JackEE*, PLDI '20); Kabir et al. (*MeCheck*, FSE '25).
- **Investigation Prompt / Algorithmic Attack:**
  1. Hình thức hóa ngữ nghĩa 4 pha Spring như một chương trình logic phân tầng (*stratified Boolean fixed-point*) $\mathcal{F} = \mathcal{F}_4 \circ \mathcal{F}_3 \circ \mathcal{F}_2 \circ \mathcal{F}_1$; chứng minh điều kiện cần và đủ khi nào chu trình `@ConditionalOnMissingBean` giữa 2 auto-configuration rơi vào tầng không xác định (`UNKNOWN / AMBIGUOUS_ORDER`) thay vì `NEVER`.
  2. Thiết kế thuật toán rút gọn **Prime-Implicant Cube Generalization** trên 4-phase Tseitin CNF sao cho các biến nội sinh (`bean_active_i`) được loại trừ khỏi mệnh đề chặn (projected model counting / AllSAT trên tập biến ngoại sinh $\mathcal{X}_{\text{exo}}$).
- **Prototype Acceptance Test:** Bộ kiểm thử property-based 1,000 đồ thị ngẫu nhiên 4-phase phi đơn điệu + `m2-shopizer` (`1,333` markers): `0` sai khác `MUST/MAY/NEVER` giữa COI-CDCL và giải toàn cục (khi $N \le 16$), và trên Shopizer hoàn tất `<10 s` với `<5%` `SOLVER_LIMIT`.

---

### RQ-2: Generalized Cross-Module Inter-Procedural HTTP/RPC Client Wrapper Propagation Without Benchmark Overfitting

- **Problem & Evo Anchor:** `Extraction.java:1677–1734` chỉ nhận diện lời gọi trực tiếp trên `RestTemplate`/`WebClient`/`WebTarget`. Trong các hệ thống microservice doanh nghiệp thực tế (như TeaStore `services/webui → utilities/registryclient/ServiceLoadBalancer → RESTClient → WebTarget`, hoặc các SDK wrapper nội bộ trong Spring/Quarkus/Micronaut), nơi gọi nghiệp vụ truyền một **tên dịch vụ dạng hằng chuỗi hoặc hằng Enum** qua **2–4 bước gọi phương thức xuyên mô-đun** (kèm lambda callback) xuống lớp HTTP/RPC client tầng dưới. Làm thế nào phục hồi các cạnh này một cách **100% tổng quát (không hardcode tên lớp/enum của bất kỳ repo nào)** trong thời gian tuyến tính?
- **`HYPOTHESIS`:** Phân tích tóm tắt hàm liên thủ tục có giới hạn độ sâu ($k \le 4$ hops, context-sensitive parameter-to-sink summary): phát hiện mọi **HTTP/RPC Sink** chuẩn (`RestTemplate`, `WebClient`, `RestClient`, `WebTarget`, `HttpClient`, gRPC Stub), truy ngược ngược chiều (backward taint/constant propagation) xem tham số nào của phương thức bao ngoài chảy vào thành phần `host`/`serviceId`/`uri` của sink (kể cả qua `Enum` constructor field map `enumConst -> stringField` và `Function<Client, R>` lambda), rồi áp dụng tóm tắt đó tại mọi call site ở các mô-đun khác — có thể phục hồi **`>=85%`** cạnh wrapper trên **cả TeaStore (`>=11/13`) lẫn `>=3` hệ thống microservice Java phi-TeaStore** với **`Precision >= 0.95`**.
- **Key Literature to Investigate:** Zhang et al. (*Micans*, ISSTA '25 — cross-service pointer analysis); Reps/Horwitz/Sagiv (IFDS/IDE inter-procedural dataflow); Schneider et al. (*Code2DFD*, JSS '23 / EMSE '25).
- **Investigation Prompt / Algorithmic Attack:**
  1. Thiết kế `HttpSinkSummary(methodId, serviceParamIndex, pathParamIndex, enumFieldExtractor)` tính 1 lần theo thứ tự tô-pô call graph (giới hạn `k <= 4`, không chạy points-to toàn cục nặng như Tai-e).
  2. Xây dựng bộ trích bảng ánh xạ trường hằng của mọi `Enum` trong dự án (`enum Foo { BAR("service-a") }` → `Foo.BAR → "service-a"`).
  3. Kiểm chứng chặt quy tắc `I5`: thuật toán hoàn toàn vô tri với tên `ServiceLoadBalancer`, `Service`, hay `TeaStore`.
- **Prototype Acceptance Test:** Chạy cùng thuật toán không chỉnh sửa trên **3 repo độc lập**: TeaStore (`>=11/13` edges, `0` FP), ít nhất 2 repo Spring/Quarkus/Jakarta có custom HTTP wrapper trong `microSecEnD` / GitHub (`Precision >= 0.95`), thời gian tăng thêm `<15%`.

---

### RQ-3: Streaming Binary Delta-Graph Format (`EVOGBIN2`) with Canonical Merkle `SHA-256` Subtree Equivalence

- **Problem & Evo Anchor:** Hiện tại `GraphBinaryCache.java:30–55` dùng định dạng `EVOGBIN1` (v2 chunked 64 KB raw-DEFLATE) nhưng **chỉ là cache phụ trợ**, còn `PartitionedGraphStore.java:697–788` vẫn bắt buộc hợp nhất và ghi toàn bộ `.graph.json` nguyên khối (`95.09 MB` TeaStore, `919 MB` Dependency-Track, `4.01 GB` MP-Manager) mỗi khi có 1 file đổi. Liệu Evo có thể chuyển sang định dạng **Streaming Binary Delta-Graph (`EVOGBIN2`)** nơi các phân vùng context bất biến được chia sẻ zero-copy dưới dạng cây Merkle `SHA-256`, mà vẫn bảo toàn **100% tính tương đương mật mã** với canonical JSON khi xuất kiểm toán?
- **`HYPOTHESIS`:** Bằng cách định nghĩa `graphIdentity` là **Merkle Root `SHA-256` phân cấp theo `(contextPartitions, crossContextEdges, capabilitySummary)`** với mỗi lá là chunk nhị phân chuẩn hóa (`EVOGBIN2` canonical varint + sorted string table + zstd/DEFLATE), thời gian xuất bản tăng dần cho 1-file edit trên repo `400k–800k LOC` giảm **`>=10×`** (`>15 s → <1.5 s`) và dung lượng đĩa cho 10 snapshot lịch sử giảm **`>=6×`** nhờ chia sẻ trang CAS bất biến.
- **Key Literature to Investigate:** Git Packfile / Commit-Graph & Merkle DAG storage; Cap'n Proto / FlatBuffers canonical deterministic encoding; Incremental Datalog / Differential Dataflow (McSherry et al.).
- **Investigation Prompt / Algorithmic Attack:**
  1. Thiết kế đặc tả nhị phân chuẩn tắc `EVOGBIN2` (bijection 1-1 với `KnowledgeGraph` canonical JSON để `decode(encode(G)) == G` và mọi công cụ kiểm toán cũ vẫn xuất được `.graph.json` theo yêu cầu *lazy export*).
  2. Chứng minh không có va chạm định danh khi một cạnh liên mô-đun thay đổi làm cập nhật duy nhất nhánh `crossContextEdges` mà không chạm `K-1` phân vùng context còn lại.
- **Prototype Acceptance Test:** Trên TeaStore & OWASP Dependency-Track: (1) Round-trip JSON ↔ `EVOGBIN2` bảo toàn `100%` node/edge/evidence/span; (2) 1-file edit chỉ ghi mới `<=2` Merkle leaves; (3) `SHA-256(Incremental EVOGBIN2) == SHA-256(Cold EVOGBIN2)` trên `100/100` phép sửa ngẫu nhiên.

---

### RQ-4: Spring Cloud Gateway Java Fluent `RouteLocatorBuilder` DSL & `@FeignClient` Property/SpEL Resolution under `--build=off`

- **Problem & Evo Anchor:** Gói `F3` & `F2` giải quyết hoàn chỉnh tuyến Gateway/Zuul khai báo trong YAML và `@FeignClient` literal. Tuy nhiên, trong hệ sinh thái Spring Cloud 2023–2026 (Spring Boot 3/4), rất nhiều dịch vụ cấu hình định tuyến bằng **Java Fluent DSL** (`RouteLocatorBuilder.routes().route(r -> r.path("/orders/**").filters(f -> f.stripPrefix(1)).uri("lb://order-service"))`) hoặc khai báo `@FeignClient(name = "${app.services.billing.name:billing-service}", url = "#{@serviceUrls.billing}")` kèm biểu thức property lồng nhau / SpEL đơn giản.
- **`HYPOTHESIS`:** Một bộ phân tích biểu thức chuỗi & lambda chuỗi gọi (fluent chain AST pattern matcher) giới hạn cho `RouteLocatorBuilder` kết hợp bộ giải property đa tầng từ `ConfigDataIngestion` (`S3`) có thể phục hồi **`>=90%`** tuyến Gateway viết bằng Java DSL và `@FeignClient` dùng `${...}` default value với **`0` FP**, trong khi vẫn gắn nhãn `CAPABILITY_GAP(SPEL_DYNAMIC_URI)` trung thực cho biểu thức SpEL phụ thuộc runtime.
- **Key Literature to Investigate:** Spring Cloud Gateway Reference (WebFlux & MVC `GatewayRouterFunctions`); Spring Expression Language (SpEL) static partial evaluation; `MeCheck` (FSE '25).
- **Investigation Prompt / Algorithmic Attack:**
  1. Trích chuỗi gọi `route(...) → path(...) → filters(stripPrefix/rewritePath) → uri(...)` trực tiếp từ `MethodCallExpr` + `LambdaExpr` trong `Extraction.java` (hoạt động cả trong `SOURCE_RECOVERY`).
  2. Tích hợp bộ giải `${key:default}` đa cấu hình với `ConfigDataIngestion` (`S3`) sao cho nếu `key` có nhiều giá trị theo `@Profile` (`dev` vs `prod`), cạnh dịch vụ mang **Truth Region** tương ứng (`MAY` dưới profile `prod`)!
- **Prototype Acceptance Test:** Fixture gồm 10 mẫu `RouteLocatorBuilder` Java DSL + `GatewayRouterFunctions.route()` + `@FeignClient("${...}")` đa profile + SpEL động: `>=90%` recall, `100%` precision, `100%` SpEL động phát sinh gap tường minh.

---

### RQ-5: Automated Differential `ApplicationContextRunner` Oracle Synthesis & Minimal Counterexample Witness Verification on 15–20 Real OSS Spring Boot Apps

- **Problem & Evo Anchor:** Trở ngại lớn nhất của Track 1 (ICSE '28 / OOPSLA '27 R2) là tạo **ground truth không thiên vị** cho hàng nghìn bean/injection có điều kiện trên 15–20 dự án Spring Boot thật mà không phải gắn nhãn thủ công. Evo sinh ra `TruthRegion` (`MUST`, `MAY`, `NEVER`) kèm cấu hình chứng nhân (`WITNESS`). Làm thế nào tổng hợp tự động các bộ test `ApplicationContextRunner` (chạy hoàn toàn trong sandbox cô lập `DOCKER_HARDENED_CONTAINER`) để **kiểm chứng vi phân (differential oracle)** từng khẳng định của Evo?
- **`HYPOTHESIS`:** Bằng cách tổng hợp các lát cắt ngữ cảnh Spring tối tiểu (`Minimal Configuration Slice`) cho từng thành phần COI — chỉ nạp các lớp `@Configuration`/`@AutoConfiguration` thuộc $\text{COI}(s)$ và mock các hạ tầng ngoài (DB/Broker socket) bằng cấu hình tắt khởi tạo kết nối — `ApplicationContextRunner` có thể thực thi tự động `>=80%` các kết luận `MUST`/`MAY`/`NEVER` trên `15–20` OSS Spring Boot thực, đạt độ khớp **`>=98%`** và tự động phát hiện chính xác các lỗi mô hình hóa Spring semantics nếu có sai lệch!
- **Key Literature to Investigate:** Spring Boot Test `ApplicationContextRunner` & `AutoConfigurations.of(...)`; Differential Testing of Static Analyzers (e.g., Soot/Doop/Tai-e runtime soundness oracles); `JackEE` (PLDI '20) recall benchmarking.
- **Investigation Prompt / Algorithmic Attack:**
  1. Thiết kế bộ sinh mã JUnit 5 từ `(COI_component, witness_properties, active_profiles, filtered_classpath)` chạy trong `DOCKER_HARDENED_CONTAINER` (`--network=none`).
  2. Với mỗi kết luận `MAY`, chạy **cặp đôi kiểm chứng (Positive Witness vs Negative Counter-Witness)**: xác nhận bean tồn tại dưới `witness+` và vắng mặt (hoặc đổi bean mục tiêu) dưới `witness-`.
  3. Phân loại tự động mọi sai lệch (false positive của Evo vs giới hạn runtime mock của runner) thành báo cáo lỗi ngữ nghĩa có thể sửa ngay.
- **Prototype Acceptance Test:** Chạy oracle tự động trên 5 dự án spike `S1` (ít nhất `200` cặp witness): tỷ lệ biên dịch & chạy thành công `>=80%`, tỷ lệ khớp Evo ↔ Spring runtime `>=98%`, phát hiện được ít nhất 1 lỗi/xung đột conditional thật có thể tái hiện bằng JUnit!

---

### RQ-6: Memory-Bounded (`-Xmx8g`) Partitioned Streaming Analysis & Zero-Copy External Merge for `2M+` LOC Monorepos (`ThingsBoard`)

- **Problem & Evo Anchor:** Trong [`evo-comparative-evaluation-report.md`](evo-comparative-evaluation-report.md) (§2.2), Evo hoàn tất `OWASP Dependency-Track` (`415.6k` LOC, `919 MB`) và `MP-Manager` (`794.2k` LOC, `4.01 GB`), nhưng trên **`ThingsBoard` (`1.95M` LOC, `13,920` files)** Evo thu nhận `100%` CAS rồi chạm ngưỡng **`MEMORY_LIMIT` sau `1,467 s` ở `-Xmx8g`**. Nguyên nhân kép: (i) giữ đồng thời `FrontendResult` của nhiều mô-đun lớn trên heap trước khi giải phóng, và (ii) hợp nhất toàn bộ ký hiệu/cạnh liên mô-đun trong RAM trước khi đẩy xuống `SqliteGraphProjection` / `PartitionedGraphStore`.
- **`HYPOTHESIS`:** Chuyển pipeline từ mô hình *collect-all-then-project* sang mô hình **2-Pass Streaming Symbol-Index + External Sort-Merge Partitioning** (Pass 1: xuất chỉ mục chữ ký công khai `ABI-only` gọn nhẹ ~20–40 MB ra `SqliteInventoryStore` và giải phóng ngay AST/body từng module; Pass 2: giải tham chiếu từng module 1, stream trực tiếp node/edge/gap đã gộp `P2-1` vào phân vùng `PartitionedGraphStore` rồi `GC` trước khi sang module kế tiếp) sẽ giúp phân tích trọn vẹn **ThingsBoard (`1.95M` LOC, `13,920` files) trong `<=-Xmx6g` heap và `<900 s`** với `0` lần `MEMORY_LIMIT` và `100%` SHA-256 determinism!
- **Key Literature to Investigate:** External Merge-Sort & Partitioned Graph Ingestion (`GraphChi`, `SQLite WAL` bulk loading); Incremental ABI Fingerprinting (Bazel / Gradle Java Compile Avoidance; tham chiếu `analyzer-javaparser/src/test/java/com/evolution/analysis/javaparser/SourceAbiFingerprinterAndDependencyIndexTest.java`).
- **Investigation Prompt / Algorithmic Attack:**
  1. Đo heap profile (JFR / `-XX:+HeapDumpOnOutOfMemoryError`) trên ThingsBoard để xác định chính xác Top-3 đối tượng chiếm `>6 GB` tại mốc `1,467 s`.
  2. Tách `ModuleAnalysisSession` xử lý nối tiếp theo bounded worker pool (`maxInFlightModules = 2`), ghi thẳng `FrontendResult` đã gộp gap (`P2-1`) xuống `SqliteGraphProjection` thay vì giữ danh sách `List<FrontendResult>` toàn repo trên heap.
- **Prototype Acceptance Test:** Chạy `evo scan --mode=fast --build=off` trên `ThingsBoard` (`1.95M` LOC) với `-Xmx8g` (và kiểm thử stress ở `-Xmx4g`): hoàn tất `exit code 0`, peak RSS/heap `<6.5 GB`, `0` OOM/`MEMORY_LIMIT`, và 2 lượt chạy ngoại tuyến cho `SHA-256` đồng nhất 100%.

---

### RQ-7: Polyglot Cross-File Symbol & Call Resolution for Tree-sitter Frontends (`Python`/`TypeScript`/`Go`/`Rust`/`C++`) to Close the `RepoProbe` (ASE '26) Gap

- **Problem & Evo Anchor:** `RepoProbe` (ASE '26, `500` Q&A / `50` repos / `15` ngôn ngữ) kiểm tra hiểu biết kiến trúc đa bước xuyên file. Hiện tại Evo chỉ có phân giải ký hiệu sâu cho Java (`analyzer-javaparser`); các ngôn ngữ `TreeSitterSourceFrontend.java` (`Python`, `TS/JS`, `Go`, `Rust`, `C++`) chủ yếu phát hành nút cú pháp cục bộ + HTTP/gRPC/Event, khiến `ArchitectureAgent` mất cạnh `CALLS`/`IMPORTS`/`INHERITS` chéo file ở các repo phi-Java và phải rơi về `search_captured_source` (giới hạn `60` kết quả tại `ArchitectureAgent.java:623`).
- **`HYPOTHESIS`:** Xây dựng tầng **Lightweight Scope-Graph & Import-Table Resolver** (chỉ dùng Tree-sitter AST + tệp manifest ngôn ngữ thụ động `pyproject.toml`, `tsconfig.json`/`package.json`, `go.mod`, `Cargo.toml` — giữ 100% `--build=off`) liên kết các cạnh `IMPORTS`, `EXTENDS/IMPLEMENTS`, và `CALLS_SYMBOL` (gắn provenance `POLYGLOT_IMPORT_SCOPED`) cho 4 ngôn ngữ cốt lõi (`Python`, `TS/JS`, `Go`, `Rust`) sẽ nâng tỷ lệ giải đúng trên `RepoProbe` thêm **`+5–12 pp`** so với cùng backbone LLM không có đồ thị chéo file, mà chỉ tăng `<20%` thời gian phân tích Tree-sitter.
- **Key Literature to Investigate:** Néron/Visser (*Scope Graphs*, ESOP '15 / `stack-graphs` GitHub); Yang et al. (*RepoProbe*, ASE '26); Ouyang et al. (*RepoGraph*, ICLR '25); Gauthier (*Aider RepoMap*).
- **Investigation Prompt / Algorithmic Attack:**
  1. Tái sử dụng ý tưởng `backend/src/main/java/com/evolution/backend/analysis/JsWorkspaceAliasResolver.java` mở rộng thành `PolyglotExportIndex` theo từng ngôn ngữ (`go.mod` package path, `tsconfig.json` `paths`, Python package `__init__.py` + relative imports, Rust `mod`/`use crate::`).
  2. Bảo toàn nguyên tắc `I4`: khi không xác định được kiểu động (ví dụ duck-typing Python `x.foo()`), chỉ nối cạnh khi có tên duy nhất trong import scope hoặc gắn thẻ `AMBIGUOUS_CANDIDATE` + `CapabilityGapRecord`, không làm nhiễu `RepoMapBuilder` PPR!
- **Prototype Acceptance Test:** Benchmark nội bộ 4 repo đa ngôn ngữ (`Python`, `TS`, `Go`, `Rust`) đạt `>=80%` Precision / `>=70%` Recall trên cạnh gọi hàm/kế thừa chéo file, và kiểm chứng ablation trên lát cắt `50` câu hỏi `RepoProbe` (cùng 1 model LLM).

---

### RQ-8: Two-Tier Host-Isolated Verified Architectural Refactoring (`DOCKER_HARDENED_CONTAINER` + `QEMU_IN_DOCKER`) with SKG-Guided Affected-Test Selection

- **Problem & Evo Anchor:** `RefAgent` (ICSE '26, `90%` pass) và `MANTRA` (`82.8%`) chạy trực tiếp `mvn test` trên mã nguồn do LLM sửa ngay trên máy chủ — tạo lỗ hổng thực thi mã tùy ý nghiêm trọng nếu LLM chèn lỗi hoặc bị prompt injection từ repo mục tiêu. Ngược lại, chạy toàn bộ `QEMU_IN_DOCKER` (`run.py`) cho mọi lượt thử sai của LLM lại quá chậm (`30–120 s+` rebuild Docker + full test suite chạm trần `900 s`).
- **`HYPOTHESIS`:** Kiến trúc kiểm chứng **2 tầng cô lập phân cấp (Two-Tier Sandboxed Repair)** — Tầng 1: vòng phản hồi đóng `CapturedRepair ↔ DOCKER_HARDENED_CONTAINER` (`--network=none --read-only --cap-drop=ALL`, base image đa JDK `8/11/17/21` dựng sẵn, read-only `.m2` mount) chỉ chạy `javac` + tập kiểm thử bị ảnh hưởng chọn bởi **SKG Transitive Reverse-Impact Graph (`-Dtest=...`)** (`<10–25 s`/vòng); Tầng 2: **chứng nhận cuối cùng trong `QEMU_IN_DOCKER` hypervisor** kèm chạy **Negative Control** (xác nhận lỗi kiến trúc tồn tại trên bản chưa vá và biến mất trên bản đã vá) — sẽ đạt **`>=80%` compile+test pass** trên các ca tái cấu trúc kiến trúc đa file (phá chu trình, tách God-Class/SDP) với **`0%` rủi ro máy chủ** và **giảm `>=5×` thời gian kiểm chứng trung bình** so với chạy QEMU thuần ở mọi vòng!
- **Key Literature to Investigate:** Oueslati et al. (*RefAgent*, ICSE '26); Xu/Tsantalis et al. (*MANTRA*, `arXiv:2503.14340`); Gligoric et al. (*Ekstazi* / RTS — Regression Test Selection); Wu et al. (*CausalRepair*, ISSTA '26).
- **Investigation Prompt / Algorithmic Attack:**
  1. Thiết kế bộ chọn **SKG-RTS (`AffectedTestSelector`)**: duyệt ngược cạnh `CALLS`/`REFERENCES`/`INJECTS`/`EXTENDS` trong SKG từ các file bị sửa tới mọi lớp `*Test.java` (fallback sang module test nếu sửa cấu hình Spring/DI).
  2. Xây dựng lược đồ JSON có cấu trúc cho `javac` diagnostics + JUnit XML failure trace gửi ngược vào `CapturedRepair.java` kèm kiểm soát chống vòng lặp dao động (oscillation detection: không lặp lại patch digest đã thất bại).
- **Prototype Acceptance Test:** Chạy trên 20 bài toán phá chu trình & giảm smell `DesigniteJava` trên repo Java 11/17/21: (1) `0` tiến trình build/test chạy trên host; (2) Tầng 1 giảm thời gian mỗi lượt retry `>=5×`; (3) `100%` bản vá được chấp nhận vượt qua chứng nhận Tầng 2 `QEMU_IN_DOCKER` + `Negative Control`.

---

## 6. Pre-Emptive Reviewer #2 Kill List (10 Fatal Objections & Exact Defenses)

| # | Đòn tấn công của Reviewer #2 | Track Bị Đánh | Phòng thủ Tiền nghiệm & Bằng chứng Bắt buộc phải có trong Bản thảo |
| :---: | :--- | :--- | :--- |
| **1** | **"Chỉ là gom rule kỹ thuật hoặc overfitting vào benchmark (hardcode `ServiceLoadBalancer` TeaStore hay `logstash.conf` của `blog-ms`)!"** | Track 2 (EMSE/ASE) | Thi hành **Anti-Overfitting Scope Guard (`I5`)**: chỉ parse **schema công nghiệp mở** (Docker Compose, K8s manifests, Spring Boot/Cloud YAML, Prometheus `scrape_configs`, Jakarta/Javax JAX-RS & Servlet); **cấm** parser bespoke `logstash.conf` và **cấm** hardcode `ServiceLoadBalancer`/`Service.<ENUM>` (báo cáo trung thực `6/13` generic vs tầng wrapper tổng quát RQ-2 kiểm chứng ngoài TeaStore); đóng khung quanh 3 đóng góp phương pháp luận `(C1–C3)`. |
| **2** | **"So sánh không công bằng: ngoại suy 5 apps lên 17 apps, mập mờ micro/macro F1, hay tự thêm actor `user` ăn gian F1 trên EMSE '25!"** | Track 2 | **Bắt buộc chạy đủ `17/17` apps** từ `tuhh-softsec/microSecEnD` trước khi nộp (xóa sạch `ASSUMPTION ~730 GT` khỏi bản thảo chính thức!), chấm bằng đúng script `M3SOulu/EMSE2025SAR-Replication`, báo cáo **cả Micro & Macro F1**, và tách rạch ròi 2 dòng **`Strict S5`** (`0` synthetic `user`) vs **`DFD-Export S6`**. |
| **3** | **"Sao Evo vẫn chậm hơn `Code2DFD` (`7.3 s` TB/app), `MicroDepGraph` (`1.5 s`), và `Chinthareddy DKB` (`2.81 s`) ở Cold Run?"** | Track 2 & 3 | **Thừa nhận thẳng & định lượng Semantic Cost Breakdown:** Các công cụ kia chỉ quét regex/Tree-sitter nông để xuất DFD hoặc `1,158` nút AST; Evo dựng **Symbol-level SKG đầy đủ kiểu JavaParser (`34.12 s` cho `1,173` đơn vị Shopizer) + `SHA-256` provenance + phân tích chu trình/chỉ số** — nên Cold `~12–30 s` TB/app (EMSE) hay `~30–60 s` (Shopizer) là đánh đổi chính đáng và được hoàn vốn bằng **Warm `1.0–3.5 s` (`NO_CHANGE`) / `4–9 s` (1-File Edit)** trong CI/IDE. |
| **4** | **"Warm Cache `1.5 s` của các bạn chỉ là `NO_CHANGE` (không đổi file) — đó là memoization chứ không phải phân tích tăng dần!"** | Tất cả Tracks | Luôn tách **3 cột thời gian** (`Cold` vs `Warm NO_CHANGE` vs **`Warm 1-File Edit` qua `P2-6`**) kèm **Pillar 2 Ablation (`A0 → A4`)**, chứng minh tăng tốc thật **`3.5×–6.0×`** trên sửa đổi mã nguồn thật với **`100%` SHA-256 trùng Cold Run của bản đã sửa**. |
| **5** | **"Spring SAT của các bạn chỉ thử trên fixture 6–9 file; ứng dụng Spring Boot thực tế mấy ai tự viết `@ConditionalOnMissingBean`?"** | Track 1 (ICSE/OOPSLA) | Chính phát hiện thực nghiệm `#97` (Shopizer: `4` `@Profile` user vs **`1,333` starter JAR markers = `99.7%`**; `9` OSS: `5` `@Profile`, `0` `@ConditionalOn*`) là **động lực khoa học số 1 của bài báo**! Chứng minh tính biến thiên nằm tại **biên tương tác `user ↔ starter JAR`**, vượt cổng Go/No-Go `G1–G3` và kiểm chứng bằng oracle `ApplicationContextRunner` trên `15–20` OSS thực. |
| **6** | **"Đọc bytecode starter JAR (`.class`) trong Track 1 thì còn gì là `--build=off` nữa?"** | Track 1 | Phân biệt sắc nét giữa **Passive Bytecode Inspection** (tải thụ động tệp `.jar` phụ thuộc qua HTTPS trong `MavenDependencyArtifactProvider.java:28–34` + đọc cấu trúc `.class` bằng ASM `SKIP_CODE`, **0 lần chạy `mvn`/`gradle` hay plugin/lifecycle trên máy chủ**) với **Untrusted Target Compilation** (`JackEE`/`Jasmine`/`Micans` đòi biên dịch `.war`/`.jar` của chính dự án mục tiêu trên máy chủ). |
| **7** | **"Sao không so trực tiếp với `Micans` (ISSTA '25), `Jasmine` (ASE '22), `JackEE` (PLDI '20), và `MeCheck` (FSE '25)?"** | Track 1 & 2 | Đã cấp ngân sách riêng **`B2` (`+2–3 d`)** chạy đối chứng trực tiếp Evo (`--build=off`) trên bộ ứng dụng Spring của **`Micans` Zenodo `15050505`** (đo tỷ lệ chạy thành công không cần biên dịch & độ phủ cạnh); và ở Track 1 đưa đối chứng chạy thật chứng minh `JackEE`/`Jasmine` chỉ thấy 1 cấu hình, còn `MeCheck` không chứng minh được `NEVER` hay sinh `WITNESS`. |
| **8** | **"Đo tốc độ trên Windows 11 Pro (có Windows Defender) thiếu tính tái lập và gây nhiễu I/O so với Linux!"** | Tất cả Tracks | Mọi bảng số liệu hiệu năng chính thức của bài báo chạy theo giao thức **`E0` trên Linux Ubuntu LTS cố định** (`5` lượt lặp, `Median + IQR`, không antivirus hook); giữ bảng Windows 11 trong Phụ lục để minh chứng tính di động đa hệ điều hành. |
| **9** | **"Trùng lặp nội dung (Double Submission / Self-Plagiarism) giữa FSE '27 Tool Demo và bài Research Track (EMSE / ASE / ICSE)!"** | Track 4 ↔ 1/2 | Áp dụng kỷ luật **Zero-Overlap Boundary** theo chuẩn ACM/IEEE: Bài FSE Demo (`4+1` trang, `26/01/2027`) chỉ trình bày kiến trúc Workbench, luồng người dùng và video; các bài Research Track trích dẫn tường minh bài Demo và mang **`>75%` đóng góp mới** (thuật toán `F1–F7` / `S1–S5`, phát hiện Pareto, full 17-app / 20-OSS + oracle, và toàn bộ ablation `E1–E5`). |
| **10** | **"Commit `HEAD` trôi so với Table 1 (2023) và `F8c` làm suy yếu tính vững (soundness) của phân giải Java!"** | Track 1, 2, 4 | (a) Ghim chính xác SHA commit Table 1 từ `M3SOulu` tách biệt với HEAD (`anilallewar` 7 vs 8 endpoints, `petclinic-ms` `genai-service`); (b) OpenRewrite Config A luôn tách 2 tầng: **`122/220` Strict Resolved (`F8b`)** vs **`124/220` kèm `SOURCE_SCOPED` Candidates (`F8c`)**, giữ **`0` vs `57` lỗi span**; (c) Xuất gói Docker 1 lệnh + `manifest.sha256` trên Zenodo. |

---

## 7. Sprint-by-Sprint Execution Schedule (Nov 2026 → Jul 2027)

> **Calendar Ground Truth (`CONFIRMED`):** Mốc khởi đầu `26/11/2026`. **Tết Nguyên Đán 2027 rơi vào Thứ Bảy `06/02/2027` (Giao thừa `05/02/2027`)**, khấu trừ thực tế **`5–7` ngày làm việc (`04/02 – 11/02/2027`)**. Lịch dưới đây vận hành theo **nhánh chính không nén lịch (Zero-Crunch: FSE '27 Demo → EMSE Q1 → ICSE '28)** và ghi rõ nhánh tăng tốc **ASE '27** / nhánh rẽ **OOPSLA '27 R2**.

| Sprint | Khung Thời gian (Tuần) | Gói Công việc & Ngân sách Công sức | Cổng Thoát Bắt buộc (Hard Exit Gate) |
| :--- | :--- | :--- | :--- |
| **Sprint 1 — P0 Foundation** | Tuần 1–3 (`27/11 – 18/12/2026`) | **`P2-1` (`2.0 d`), `P2-2` (`0.5 d`), `P2-3` (`1.5 d`), `P2-4` (`2.5–3.0 d`), `F1` (`0.5 d`), `F6a` (`0.75 d`), `F6b` (`0.5 d`), `F8` (`1.25 d`)**<br>*(Tổng: **`9.5–10.0 d` code** / `~2.5 tuần` gồm test & xác minh)* | • **Shopizer:** **`<60 s` Cold (Win)** / **`<3.5 s` Warm `NO_CHANGE`** (`0` timeout!)<br>• **TeaStore:** `<26 s` Cold / `<3.0 s` Warm `NO_CHANGE`<br>• **EMSE '25 5-repo Endpoints:** **`0.991` (`52/53`, `0 FP`)**<br>• **Online Boutique:** **`0.968` (`15/16`, `0 FP`)**<br>• **OpenRewrite:** Java 21 **`7/7`**, Config A **`122` Strict / `124` `SOURCE_SCOPED`** (`0` span err) |
| **Sprint 2 — FSE '27 Tool Demo** | Tuần 4–8 (`19/12/2026 – 26/01/2027`) | Đóng gói Docker/JAR tái lập, quay screencast Workbench (`--build=off` + SAT + Polyglot + QEMU Negative Control), viết bản thảo `4+1` trang + walkthrough appendix.<br>*(Tùy chọn Stretch ASE '27: kéo trước `F2 + F3` = `4.5 d` làm song song tại đây!)* | • **NỘP FSE 2027 Tool Demonstrations (`26/01/2027`, hạn ĐÃ CÔNG BỐ, CORE A\*)**<br>• Xác nhận tuân thủ Zero-Overlap Boundary (`>75%` đóng góp thuật toán giữ lại cho Bài #1 & #2). |
| **Sprint 3 — P1 Track 2 Core (Có đệm Tết `06/02`)** | Tuần 9–14 (`27/01 – 10/03/2027`, *trừ 1 tuần nghỉ Tết `04–11/02`*) | **`P2-5/6` (`4.0 d`) + `F4/F4b` (`6.5–8.0 d`) + `F2` (`3.0 d`) + `F3` (`1.5 d`) + `F5` (`2.0 d`) + `F7 generic` (`3.5 d`)** = **`20.5–22.0 d` code**<br>Cộng **`B1 + B2` (`5.0–7.0 d`)**: tải & chạy đủ `12` unseen `microSecEnD` apps + script `M3SOulu` + đối chứng `Micans` Zenodo `15050505`. | • **17/17 `microSecEnD` thực đo:** `Strict S5 >= 0.85` / `DFD S6 >= 0.88` (đã xác minh micro vs macro F1)<br>• **Online Boutique:** **`1.000 / 1.000`** (`12/12` Svc, `16/16` Deps, `0 FP`)<br>• **TeaStore:** `7/7` Cp, `41/61` JAX-RS + `20` Servlet (`1.000`), **`>=6/13` Edges (`0.632`, `0` hardcoding)**<br>• **Warm 1-File Edit:** **`>=3.5×`** trên TeaStore & Shopizer (`100%` SHA-256) |
| **Sprint 4 — Research Paper #1 (Zero-Crunch)** | Tuần 15–18 (`11/03 – 07/04/2027`) | Chạy trọn bộ **`E0` Linux 5-repeat (`Median + IQR`)** cho `E1–E4` (bao gồm Pillar 2 ablation `A0→A4`), đóng gói 1-command Zenodo Docker artifact, viết & phản biện nội bộ (Kill List `#1–#10`). | • **NỘP EMSE (Q1 Springer, rolling — đúng tạp chí Schneider '25) hoặc TOSEM (CORE A\*) vào đầu `04/2027`**.<br>• *(Nhánh Stretch: chỉ nộp **ASE '27 `dự kiến ~10/03/2027`** nếu đã hoàn thành `F2–F3` trong Sprint 2 hoặc chạy 2 kỹ sư song song qua Tết!)* |
| **Sprint 5 — Track 1 Go/No-Go Spike** | Tuần 19 (`08/04 – 15/04/2027`) *(hoặc Tuần 9 nếu đảo sang nhánh OOPSLA '27 R2)* | Dựng nguyên mẫu **`S1` (`5.0 d`)** ASM bytecode starter extractor + COI prototype, chạy thăm dò trên `5` OSS Spring Boot giàu conditional + kiểm thử `ApplicationContextRunner`. | • **GO** khi đồng thời đạt **`G1`** (`>=10` user↔starter interactions/repo trên `>=3/5` repo), **`G2`** (`>=5` findings thực hoặc `>=20` single-config FP bị loại, xác nhận bằng `ApplicationContextRunner`), và **`G3`** (`<10 s`/repo & `<5%` `SOLVER_LIMIT`).<br>• **NO-GO** → rẽ sang Phase 3 (Track 3: `R1–R4`). |
| **Sprint 6–7 — P2 & Research Paper #2** | Tuần 20–31 (`16/04 – ~01/07/2027`, `~11` tuần) | Hoàn tất **`S2` (`3.0 d`), `S3` (`2.0 d`), `S4+S5` (`7.0–10.0 d`)** + tuyển `15–20` OSS Spring Boot + xây dựng `ApplicationContextRunner` differential oracle (`E5`, `1.5–2.5` tuần) + SAT ablation + Zenodo artifact + viết bài #2. | • **NỘP ICSE 2028 Research (`dự kiến ~01/07/2027`, CORE A\*)**.<br>• *(Nhánh rẽ PL/PA: chỉ nhắm **OOPSLA '27 Round 2 — đã công bố `07/04/2027`** nếu chủ động làm Track 1 trước Track 2 ngay sau FSE Demo `26/01`!)* |

---

## 8. Verified Master Source-File Index (Existing vs. Planned/New)

Mọi tệp trong Mục 8.1 dưới đây đã được xác minh trực tiếp bằng PowerShell `Test-Path` trên `D:\UIT\AI-native-verified-software-evolution` (**`MISSING = 0`**). Mọi tệp mới dự kiến tạo trong quá trình thực thi được tách riêng và đánh dấu tường minh `[PLANNED/NEW]` tại Mục 8.2.

### 8.1. Verified Existing Evo Repository Files (`54` Existing Files Verified — `0` Missing)

| Maven Module / Dir | Repo-Relative File Path (Verified `Test-Path = True`) | Primary Work Package(s) & Key Line Anchors |
| :--- | :--- | :--- |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/evidence/CapabilityGapNormalizer.java` | `P2-1` (`L445–509`: per-observation `SEMANTIC_UNRESOLVED` loop) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalArchitecturePipeline.java` | `P2-2` (`L39–41, 71–75`: spill thresholds); `S5` (`L98`: `DEFAULT_SAT_MAX_SIGNATURES=256`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/buildmodel/UniversalBuildIngestion.java` | Pillar B / Pillar C build-model context ingestion |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/graph/CanonicalGraphBuilder.java` | `F1` / `F7` (`L239–254`: `ENDPOINT` emission) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/graph/IncrementalGraphProjector.java` | `P2-6` (context-level incremental graph projection for 1-file edit) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/polyglot/HttpServiceCallProjection.java` | `F2`, `F3`, `F6b` (`L56–89`: HTTP call-to-route & service-host matching) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/polyglot/GrpcServiceCallProjection.java` | `F6a` (`L41–44`: node-level self-loop check) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/polyglot/ServiceArchitectureProjection.java` | `F3`, `F4`, `F4b`, `F6a` (`L28–42`: service-component topology & profile projection) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/polyglot/EventFlowProjection.java` | `F5` (`L22–49`: AMQP/Kafka/Stream event projection) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/polyglot/HttpUriPattern.java` | `F3` (`StripPrefix` & URI pattern matching) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/universal/SpringSourceEvidence.java` | `F1` (`L78–126`: `SIMPLE_SPRING_ANNOTATIONS`); `F2`, `F5`; `F7` (`L140`: `ws.rs.` exclusion) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/universal/WebRouteMapper.java` | `F1` (`L37`: unknown `*Mapping`; `L82–99`: `decode()` `MediaType.*`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/universal/AutoConfigurationMetadata.java` | `S1` (`L14–57`: `spring.factories` `L35`, `AutoConfiguration.imports` `L36`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/universal/SourceToSpringPlan.java` | `S1` (`L28–1178`: Spring mechanism plan construction) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/condition/LiteralConditionAnnotation.java` | `F1` (`L63, 124–127`: `strings()` / `Enums`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/condition/ConditionEvidenceLowering.java` | `S2` (`L233, 255` composition, `L392–408` `exactAnnotationType()`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/condition/ConfigDataIngestion.java` | `S3` (`L47–62` `isApplicationConfigFile()`, `L98–109, 130–132` `spring.profiles`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/condition/RepositoryConfigurationSpaceBuilder.java` | `S4` (`L19–20` `MAX_DIMENSIONS=32`, `L130–145, 167`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/truth/SatConfigurationReasoner.java` | `S5` (`L10, 62–95`: naive DPLL `byte[].clone()` at `L93`) |
| **`analyzer`** | `analyzer/src/main/java/com/evolution/analysis/spring/truth/SymbolicTruthRegions.java` | `S5` (`L143, 228, 236, 253–258`: double `evaluate()` + 256-sig cap) |
| **`analyzer-filesystem`** | `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/FileAnalysisWorkspace.java` | `P2-5` (`L140–195, 230–270, 352–380`: unbatched SQLite spill & `distinct()`) |
| **`analyzer-filesystem`** | `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/StreamingContentStore.java` | `P2-4` (`L233–254` `putLocalFile()`, `L300–350` `writeInternal()`, `L334–337`) |
| **`analyzer-filesystem`** | `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/PartitionedGraphStore.java` | `P2-4` (`L697–788` `assemble()`, `L772–778` 190 MB double hash, `L1036, 1328–1450` audit SQLite) |
| **`analyzer-filesystem`** | `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/IncrementalJavaFrontendCache.java` | `P2-2` (`L212–257`: `.fres.gz` Java cache inflation) |
| **`analyzer-filesystem`** | `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/GraphBinaryCache.java` | `RQ-3` (`L32–39`: `EVOGBIN1` v2 chunked binary cache foundation) |
| **`analyzer-filesystem`** | `analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/SqliteGraphProjection.java` | `P2-4`, `RQ-6` (trusted internal graph projection) |
| **`analyzer-javaparser`** | `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/JavaParserFrontend.java` | `F8`, `P2` (production `JavaParser 3.28.2` frontend) |
| **`analyzer-javaparser`** | `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/Extraction.java` | `F2`, `F5`, `F7` (`L1677–1734`); `F8` (`L2707–2913`, `requireRecoveryHierarchy` `L2880`) |
| **`analyzer-javaparser`** | `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/HttpClientConstants.java` | `F2` (`L20–46`: effectively-final local / field / `String.format` URI folding) |
| **`analyzer-javaparser`** | `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/ExplicitImportTypeSolver.java` | `F8b` (`L1040–1125`: source-recovery imported type stubs) |
| **`analyzer-javaparser`** | `analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/ResolutionEnvironment.java` | `F8`, `S1` (`AsmBytecodeIndex` / `AsmTypeSolver`) |
| **`analyzer-maven`** | `analyzer-maven/src/main/java/com/evolution/analysis/maven/MavenDependencyArtifactProvider.java` | `I1`, `S1` (`L28–34`: credential-free passive HTTPS JAR acquisition) |
| **`analyzer-treesitter`** | `analyzer-treesitter/src/main/java/com/evolution/analysis/treesitter/TreeSitterSourceFrontend.java` | `F6b` (`L185` `PYTHON_HTTP_CLIENT`, `L620–645`, `L942–995` Go `var_spec` unwrap); `RQ-7` |
| **`analyzer-treesitter`** | `analyzer-treesitter/src/main/java/com/evolution/analysis/treesitter/GrpcSourceSyntax.java` | `F6a` (`L36` Go fast-filter, `L141–170` `handler()` / `registrations()`) |
| **`analyzer-execution`** | `analyzer-execution/src/main/resources/captured-repair/run.py` | `R4`, `RQ-8` (`L25` pinned single JDK, `L254–354` `stage_m2_subset`, `L731–735` timeout) |
| **`analyzer-execution`** | `analyzer-execution/src/main/resources/captured-repair/guest-init.sh` | `R4`, `RQ-8` (QEMU networkless initramfs guest harness) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/analysis/StaticPolyglotScan.java` | `F3`, `F4`, `F4b` (`L631–844`: `declarativeInfraLanguage()`, `parseDockerCompose()`) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/analysis/PolyglotScan.java` | `F4` (`L170–233`: infra component promotion) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/analysis/LocalAnalysisRunner.java` | `P2-3` reference (`L612–620, 1198, 1666–1676`: backend `MODE_NO_CHANGE` & graph reuse) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/ArchitectureAgent.java` | `R1`, `R2` (`L30` `MAX_ROUNDS=10`, `L179–215` `compactToolMessages()`, `L623`) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/ArchitectureChatModel.java` | `R2` (`L27–90`: `[E#]` citations & `BENCHMARK_QA` prompt mode) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/RepoMapBuilder.java` | `R2` (`L22, 69, 215, 241, 270`: PPR & 500-file seeding cap) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/JavaRepairRecipes.java` | `R4` (`L12–28`: 16 DI recipes; `L228`: `EXTRACT_INTERFACE_DECOUPLE` cap) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/CapturedRepair.java` | `R3` (`L131, 220–228, 254, 273–367`: syntax-only retry loop) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/CapturedRepairRunner.java` | `R3`, `RQ-8` (`L1–336`: container/QEMU runner dispatch) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/CapturedRepairVerification.java` | `R3`, `RQ-8` (`L53, 149, 237`: JUnit XML `failingTests` & Negative Control) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/repair/RepairContextPlanner.java` | `R3` (`L27` `MAX_EXPANDED_FILES=6`, `L117–145` `callerAdded<=3`) |
| **`backend`** | `backend/src/main/java/com/evolution/backend/query/repair/RepairSyntaxGate.java` | `R3` (`L1–247`: `javac` parse + 15 Tree-sitter syntax gate) |
| **`cli`** | `cli/src/main/java/com/evolution/cli/EvoApp.java` | `P2-3`, `P2-6`, `F4b`, `R1` (`L926–1144`: CLI `Scan`, ignored `NO_CHANGE`) |
| **`cli`** | `cli/src/main/java/com/evolution/cli/Workspace.java` | `P2-6` (`L78`: workspace persistence & base graph lookup) |
| **`benchmarks`** | `benchmarks/semantic-frontend-evaluation/pom.xml` | `F8` (legacy `JavaParser 3.26.1` pin → upgrade to production `3.28.2`) |
| **`benchmarks`** | `benchmarks/semantic-frontend-evaluation/src/main/java/com/evolution/benchmark/frontend/JavaParserFrontend.java` | `F8` (`L24`: raw `ReflectionTypeSolver` → wire production `analyzer-javaparser`) |
| **`benchmarks`** | `benchmarks/semantic-frontend-evaluation/fixtures/project/fixture/ArchitectureFixture.java` | `F8` (`L14`: `values.get(0).trim()` JDK 21 chained generic case) |
| **`docs`** | `docs/evo-comparative-evaluation-report.md` | Baseline v6 scientific comparative report (`cb79c322`, 39 works, 9 OSS repos) |

### 8.2. Planned New Files (`[PLANNED/NEW]` — Do Not Exist Yet on `HEAD cb79c322`)

| Tag | Target Repo-Relative Path | Owner Package | Purpose |
| :---: | :--- | :---: | :--- |
| **`[PLANNED/NEW]`** | `analyzer/src/main/java/com/evolution/analysis/jaxrs/JaxRsRouteMapper.java` | `F7` | Generic Jakarta/Javax JAX-RS `@Path` + inherited abstract resource + `@WebServlet` mapper |
| **`[PLANNED/NEW]`** | `analyzer/src/test/java/com/evolution/analysis/jaxrs/JaxRsRouteMapperTest.java` | `F7` | Unit & inheritance regression tests for `JaxRsRouteMapper` |
| **`[PLANNED/NEW]`** | `analyzer/src/main/java/com/evolution/analysis/spring/universal/BytecodeAutoConfigScanner.java` | `S1` | Passive ASM `ClassVisitor` reading starter JAR `.class` `@AutoConfiguration` & `@ConditionalOn*` |
| **`[PLANNED/NEW]`** | `analyzer/src/main/java/com/evolution/analysis/spring/condition/ConeOfInfluenceSlicer.java` | `S4` | Connected-component COI slicer over `(Vars ↔ Beans ↔ InjectionSites)` |
| **`[PLANNED/NEW]`** | `analyzer/src/main/java/com/evolution/analysis/spring/truth/IncrementalCdclReasoner.java` | `S5` | Incremental pure-Java CDCL SAT reasoner with assumptions & prime-implicant cube generalization |

---

## 9. Key Public Benchmark & Reference Directory

### 9.1. Public Benchmark Repositories & Artifact Archives

| Benchmark / Tool | Official Public URL / Archive ID | Used In | Role in Evo Evaluation |
| :--- | :--- | :---: | :--- |
| **`microSecEnD` (17 Spring Cloud Apps + GT DFDs)** | `https://github.com/tuhh-softsec/microSecEnD` | `E1`, `E2` (Track 2) | Primary 17-app Spring Cloud SAR benchmark (Schneider et al., EMSE '25) |
| **`EMSE2025SAR-Replication`** | `https://github.com/M3SOulu/EMSE2025SAR-Replication` | `E1` (Track 2) | Exact scoring harness, pinned commit SHAs, micro/macro F1 verification |
| **`Code2DFD`** | `https://github.com/tuhh-softsec/code2DFD` | `E1` (Track 2) | Leading static DFD baseline (`0.86` Overall F1, `7.3 s` TB/app) |
| **`MicroDepGraph` & `MicroserviceDataset`** | `https://github.com/clowee/MicroDepGraph` | `E1` (Track 2) | High-precision static microservice baseline (`0.74` F1, `P=0.99`, `1.5 s`) |
| **`Micans` (ISSTA '25 Artifact)** | Zenodo Record `15050505` (`https://zenodo.org/records/15050505`) | `E3` / `B2` (Track 2) | Cross-service `Tai-e` bytecode pointer analysis baseline (compiled vs `--build=off`) |
| **Online Boutique (`microservices-demo`)** | `https://github.com/GoogleCloudPlatform/microservices-demo` (`c7cb6d5`) | `E3` (Track 2) | 5-language + Protobuf benchmark vs `Ripple eBPF` (`12` Svc, `16` Deps) |
| **TeaStore** | `https://github.com/DescartesResearch/TeaStore` (`34b37f7`) | `E3`, `E4` (Track 2) | Non-Spring Java EE/Jakarta JAX-RS & Servlet generalizability (`7` Cp, `41/61` EP, `13` Edges) |
| **Shopizer** | `https://github.com/shopizer-ecommerce/shopizer` (`6a4a0a6`) | `E4`, `R1` (Pillar A & Track 1/3) | `115.1 kLOC` spill stress test (`41.89 s` floor), `1,333` starter markers, Chinthareddy 15 Q&A |
| **Spring PetClinic (`spring-petclinic`)** | `https://github.com/spring-projects/spring-petclinic` (`818c4136`, `500158f`) | `E4` (Track 2) | OpenRewrite `v8.87.7` Config A/B (`220` calls, `0` vs `57–89` span errors) |
| **`RepoProbe` (ASE '26)** | `https://github.com/Tencent-Hunyuan/RepoProbe` | `R2`, `RQ-7` (Track 3) | `500` GitHub Discussion arch Q&A / `50` repos / `15` langs (`GPT-5.2 = 62.7%`) |
| **`RefAgent` (ICSE '26) & `DesigniteJava`** | `https://github.com/anonymAgent/RefAgent` & `https://github.com/tushartushar/DesigniteJava` | `R3–R4`, `RQ-8` (Track 3) | Multi-agent refactoring (`90.0%` pass, `-52.5%` smell) & architectural smell oracle |

### 9.2. Core Scientific References (Primary Rivals & Foundations)

1. **S. Schneider et al.**, "Comparison of Static Analysis Architecture Recovery Tools for Microservice Applications," *Empirical Software Engineering (EMSE)*, vol. 30, no. 5, Art. 128, 2025. `doi:10.1007/s10664-025-10686-2` (`arXiv:2412.08352`).
2. **S. Schneider and R. Scandariato**, "Automatic Extraction of Security-Rich Dataflow Diagrams for Microservice Applications Written in Java (*Code2DFD*)," *J. Syst. Softw.*, vol. 202, Art. 111722, 2023. `doi:10.1016/j.jss.2023.111722`.
3. **T. Zhang et al.**, "Bridge the Islands: Pointer Analysis for Microservice Systems (*Micans*)," *Proc. ACM SIGSOFT ISSTA*, 2025. `doi:10.1145/3728896` (Zenodo `15050505`).
4. **D. Landau et al.**, "Retrofitting Service Dependency Discovery in Distributed Systems (*Ripple eBPF*)," `arXiv:2510.15490`, Oct. 2025.
5. **J. von Kistowski et al.**, "TeaStore: A Micro-Service Reference Application for Benchmarking, Modeling and Resource Management Research," *Proc. IEEE MASCOTS*, 2018, pp. 223–236. `doi:10.1109/MASCOTS.2018.00030`.
6. **C. Kästner et al.**, "Variability-Aware Parsing in the Presence of Lexical Macros and Conditional Compilation (*TypeChef*)," *Proc. ACM SIGPLAN OOPSLA*, 2011, pp. 805–824. `doi:10.1145/2048066.2048128`.
7. **A. Antoniadis et al.**, "Static Analysis of Java Enterprise Applications: Frameworks and Caches, the Elephants in the Room (*JackEE*)," *Proc. ACM SIGPLAN PLDI*, 2020, pp. 794–807. `doi:10.1145/3385412.3386026`.
8. **M. Chen et al.**, "Jasmine: A Static Analysis Framework for Spring Core Technologies," *Proc. IEEE/ACM ASE*, 2022. `doi:10.1145/3551349.3556910`.
9. **M. Kabir, Y. Wang, and N. Meng**, "Detecting Metadata-Related Bugs in Enterprise Applications (*MeCheck / RSL*)," *Proc. ACM Softw. Eng. (FSE)*, 2025.
10. **C. Sundermann et al.**, Pseudo-Boolean d-DNNF Feature-Model Analysis, *IEEE Trans. Softw. Eng. (TSE)*, Jan. 2026.
11. **Y. Yang et al.**, "RepoProbe: Benchmarking Architecture-Aware Repository Comprehension with Checklists," *Proc. 41st IEEE/ACM ASE*, 2026. `doi:10.1145/3832783.3837452` (`arXiv:2608.04783`).
12. **M. R. Chinthareddy**, "Reliable Graph-RAG for Codebases: AST-Derived Graphs vs LLM-Extracted Knowledge Graphs (*DKB*)," `arXiv:2601.08773`, Jan. 2026.
13. **K. Oueslati, M. Lamothe, and F. Khomh**, "RefAgent: A Multi-agent LLM-based Framework for Automatic Software Refactoring," *Proc. IEEE/ACM 48th ICSE*, 2026. `doi:10.1145/3744916.3773153` (`arXiv:2511.03153`).
14. **Y.-S. Xu et al.**, "MANTRA: Enhancing Automated Method-Level Refactoring with Contextual RAG and Multi-Agent LLM Collaboration," `arXiv:2503.14340`, Mar. 2025.
15. **L. Wu et al.**, "CausalRepair: Bridging the Causality Gap in LLM-Based Automated Program Repair via Dual-Slicing," *Proc. ACM Softw. Eng. (ISSTA)*, vol. 3, Art. ISSTA134, 2026. `doi:10.1145/3832252`.
16. **Moderne & OpenRewrite Contributors**, "OpenRewrite Lossless Semantic Trees (v8.x)," `https://docs.openrewrite.org/`.
