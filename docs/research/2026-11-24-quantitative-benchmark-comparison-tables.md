# Bảng Tổng Hợp Số Liệu Thực Nghiệm Dự Án `evo` Đối Chiếu Với Các Công Trình Khoa Học Đã Công Bố

**Ngày tổng hợp:** 2026-11-24  
**Quy tắc trình bày:**
1. Chỉ sử dụng số liệu đo đạc trực tiếp trong hồ sơ dự án **`evo`** (có mã băm SHA-256 / biên nhận thực thi) và số liệu công bố trong các bài báo khoa học gốc.
2. Mọi giá trị đo được của dự án **`evo`** đều được **in đậm**.
3. Các chỉ số không công bố trong bài báo gốc hoặc chưa được đo trên dự án được ghi rõ **`N/A`** kèm lý do khách quan, tuyệt đối không suy diễn hoặc nội suy.

---

## Bảng 1. Ánh Xạ Tổng Quan: Từng Phân Hệ Dự Án `evo` vs. Bài Báo Khoa Học Gần Nhất

| STT | Phân hệ / Mô-đun trong dự án **`evo`** | Bài báo / Công cụ đối chiếu | Chỉ số định lượng chính của **`evo`** | Chỉ số công bố trong bài báo đối chiếu | Khác biệt phạm vi phép đo (Khách quan) |
|---:|---|---|---|---|---|
| 1 | **`analyzer-javaparser` + `JavacOracle` (M2 / M9.2)** | **OpenRewrite** (v8.87.7 comparator), **Jasmine** (ASE '22), **JackEE** (PLDI '20), **Micans** (ISSTA '25) | Config B PetClinic (`89` JAR): **`220/220 (100.0%)`**, **`0`** lỗi span; M2 `javac` oracle: **`34/34 (100.0%)`**; M9 `J01`: **`41/41 (100.0%)`** | OpenRewrite PetClinic Config B: `220/220 (100.0%)`, `89` lỗi span; JackEE reachability: `58.04%` (vs `14.48%` Doop); Jasmine: `12` leak paths trên `18` app (`Precision/Recall = N/A`) | Jasmine/JackEE/Micans yêu cầu bytecode `.class`/`.jar` đóng kín; **`evo`** phân giải từ mã nguồn thụ động + classpath |
| 2 | **`analyzer` Spring Conditional Engine (`M4-R0` / `S01` / `P01`)** | **Jasmine** (ASE '22), **ArchUnit**, **Spring Modulith** | **`79/79 (100.0%)`** runtime Spring (`5.3/6.1/6.2`); **`70/70`** formal; **`64/64`** SAT/DPLL; thứ tự `@ConditionalOnMissingBean`: **`4/4 (100.0%)`**; `P01` policy: **`12/12`** (`F1 = 1.00`) | Baseline điều kiện phẳng nội bộ: `2/4 (50.0%)` sai thứ tự; Jasmine / ArchUnit / Spring Modulith: **`0`** chỉ số trên không gian `@Conditional` SAT (`N/A`) | Các công cụ trước chỉ phân tích 1 cấu hình đơn lẻ hoặc bytecode tĩnh; **`evo`** mô hình hóa pha/thứ tự trên không gian cấu hình hữu hạn |
| 3 | **`UniversalArchitecturePipeline` + `analyzer-treesitter` (`M9 Baseline` / `M8.4 Step 6`)** | **Schneider et al.** (EMSE '25 / `arXiv:2412.08352`): **Code2DFD, MicroDepGraph, RAD, Prophet** | **`8/10`** repo đạt **`COMPLETE` (exit `0`)** ở `--offline --build off`; `structure` Java/Kotlin: **`97.40%–100.0%`**; `usage`: **`74.63%–99.72%`**; **`3`** `CALLS_SERVICE` (PetClinic), **`30`** `INVOKES_RPC` (Boutique) | Code2DFD: `F1 = 0.86` (`P = 0.93, R = 0.80`); MicroDepGraph: `F1 = 0.74` (`P = 0.99, R = 0.59`); RAD: `F1 = 0.37`; Prophet: `F1 = 0.08`; tổ hợp 4 công cụ: `F1 = 0.91` | Schneider et al. đo `P/R/F1` đồ thị dịch vụ cấp cao so với chuẩn thủ công; **`evo`** đo tỷ lệ phân giải cấp ký hiệu trên mẫu số trích xuất thực tế (`resolved/attempted`) |
| 4 | **`StreamingContentStore` + `PartitionedGraphStore` + `CanonicalGraphBuilder`** | **Reliable Graph-RAG** (`arXiv:2601.08773`: **DKB** & **LLM-KB**) | ThingsBoard (`13,920` tệp, `1.95M` LOC): thu nạp **`100.0%` (`0`** bỏ sót, **`20,566`** CAS); dựng đồ thị RAM chạm **`[MEMORY_LIMIT]` tại `1,467s` (`-Xmx8g`)**. Halo (`1.51 GB`): truy vấn trong **`-Xmx256m`** (`26.73–100.50 ms`). NewTube (`1.97 GB`): ghi đĩa **`592.35s → 18.47s` (`-96.9%`)** | Trên ThingsBoard: DKB index `13.77s` (cú pháp nông), LLM-KB index `883.74s` (trên Shopizer bỏ sót `377/1,210` tệp, tỷ lệ thành công `68.8%`, phủ `64.1%`) | DKB chỉ trích xuất đồ thị cú pháp Tree-sitter nông không giải kiểu Java/540 POM; **`evo`** phân tích AST + kiểu Java đầy đủ và băm SHA-256 từng trang |
| 5 | **`ArchitectureAgent` + `WorkbenchMcpMain`** | **CodexGraph** (NAACL '25), **RepoProbe** (ASE '26) | **`10`** công cụ GraphRAG đọc-duy-nhất (`[E#]`), **`7`** công cụ MCP / **`20`** phép truy vấn `graph_query`; độ chính xác trên benchmark ngoài: **`N/A`** | CodexGraph: `27.90%` (CrossCodeEval), `22.96%` (SWE-bench Lite); RepoProbe (`500` câu/`50` repo): GPT-5.2 `62.7%` overall (`60.3%` knowledge, `84.7%` clarity) | CodexGraph & RepoProbe đã công bố điểm benchmark chuẩn ngoài; **`evo`** mới đo kiểm thử chức năng/trích dẫn (`59/59` suite backend) |
| 6 | **`M11` Evolution + `CapturedRepair` + `analyzer-execution` (QEMU / Hyper-V)** | **CausalRepair** (ISSTA '26), **AutoCodeRover** (ISSTA '24), **ArchFix** (SPE '13) | **`42`** ca M11 (`31 PASS` ban đầu + cập nhật r3); cặp **`33,000`** types: **`142.820s`** lạnh / **`0.530s`** mở trang; **`7`** công thức Java + **`3`** vòng `javac`; tái phân tích **`12 ms`** (cached); QEMU: **`9/9 (100.0%)`** (`VERIFIED_DEMO`); tỷ lệ sửa trên Defects4J/SWE-bench: **`N/A`** | CausalRepair: `313` lỗi Defects4J, `101/199 (50.75%)` GitBug-Java (`$0.029`/lỗi); AutoCodeRover: `19%–22.3%` SWE-bench Lite (`$0.43–$0.70`/issue); ArchFix: `32` mẫu sửa trên `2` hệ thống | CausalRepair/AutoCodeRover đánh giá tỷ lệ qua unit test trên benchmark sửa lỗi đại trà; **`evo`** đánh giá chứng minh chuyển trạng thái kiến trúc (`RESOLVED` vs `PERSISTED`) trong VM cô lập |

---

## Bảng 2. Phân Hệ 1 — Phân Tích Tĩnh Java (`analyzer-javaparser`) & So Sánh Frontend

*Nguồn nội bộ: [semantic-frontend-comparison.md](semantic-frontend-comparison.md), [02-execution.md](../tasks/m9/02-execution.md).*  
*Nguồn ngoài: Jasmine (ASE '22) [DOI: `10.1145/3551349.3556910`], JackEE (PLDI '20) [DOI: `10.1145/3385412.3386026`], Micans (ISSTA '25).*

### 2A. Đối chiếu trực tiếp JavaParser vs. OpenRewrite trên cùng đầu vào và cùng oracle `javac 21`

| Tập kiểm chứng & Cấu hình | Chỉ số đo | **`evo` (`JavaParser` adapter)** | OpenRewrite (`8.87.7` comparator) | Ghi chú kỹ thuật |
|---|---|---:|---:|---|
| Java 21 Microfixture (`7` call sites) — Config A (`source + JDK`) | Lời gọi phân giải được | **`3 / 7 (42.86%)`** | `4 / 7 (57.14%)` | Cả hai báo `UNRESOLVED` với phụ thuộc thiếu, không đoán đích |
| Java 21 Microfixture (`7` call sites) — Config B (`exact classpath`) | Lời gọi phân giải được | **`6 / 7 (85.71%)`** | `7 / 7 (100.0%)` | `evo` adapter thiếu 1 chuỗi generic `values.get(0).trim()` |
| Java 21 Microfixture (`7` call sites) — Config C (`incomplete cp`) | Lời gọi phân giải được | **`5 / 7 (71.43%)`** | `6 / 7 (85.71%)` | Phản ánh cùng khác biệt chuỗi generic ở Config B |
| `spring-petclinic` (`818c4136`, `220` calls) — Config A (`source + JDK`) | Calls phân giải / Lỗi span nguồn | **`116 / 220 (52.73%)`** · **`0`** lỗi span | `124 / 220 (56.36%)` · `57` lỗi span | OpenRewrite LST cần tầng ánh xạ vị trí riêng |
| `spring-petclinic` (`818c4136`, `220` calls) — Config B (`89` JARs) | Calls phân giải / Lỗi span nguồn | **`220 / 220 (100.0%)`** · **`0`** lỗi span | `220 / 220 (100.0%)` · `89` lỗi span | Ngang bằng ngữ nghĩa (`220/220`); **`evo`** giữ nguyên vị trí gốc |
| `spring-petclinic` (`818c4136`, `220` calls) — Config C (`-spring-web`) | Calls phân giải / Lỗi span nguồn | **`215 / 220 (97.73%)`** · **`0`** lỗi span | `215 / 220 (97.73%)` · `87` lỗi span | Giữ `5` lời gọi thiếu `spring-web` ở trạng thái `UNRESOLVED` |
| Standalone M2 `javac` oracle (`30` dương + `4` lỗi biên dịch) | Độ khớp nhãn oracle | **`34 / 34 (100.0%)`** | `N/A` | Có `3` quan hệ định danh & `4` đối chứng lỗi biên dịch |
| M9.2 `J01` (`13` strict + `13` recovery + `15` modern, `18` loại quan hệ) | Độ khớp đối chứng compiler | **`41 / 41 (100.0%)`** | `N/A` | Bao gồm đối chứng chữ ký, erasure, origin và full span |
| M9.2 `J02` (kiểm soát chống bịa kiểu khi thiếu JAR/import) | Số phép kiểm âm đạt chuẩn | **`7 / 7 (100.0%)`** | `N/A` | Không sinh khai báo/origin giả khi thiếu bằng chứng |

### 2B. Đối chiếu số liệu công bố của Jasmine, JackEE, Micans

| Hệ thống | Tập dữ liệu | Khả đạt phương thức / Độ phủ | Phát hiện / Hiệu năng được công bố | Số liệu chưa đo / không công bố |
|---|---|---|---|---|
| **Jasmine** (ASE '22) | `Spring-micro-benchmarks` (`ID-1`..`ID-8`) + `18` app Spring thực tế | Cải thiện hoàn chỉnh call graph IoC/AOP trên Soot/Doop | Phát hiện `12` luồng rò rỉ thông tin nhạy cảm khi ghép FlowDroid | **Không tính Precision/Recall tổng thể** trên `18` app thực tế (thiếu dynamic ground truth) |
| **JackEE** (PLDI '20) | Doanh nghiệp Java EE/Spring (gồm Alfresco: `9,164` app / `37,163` total classes; pybbs) | Trung bình `58.04%` in-app reachability (vs `14.48%` Doop gốc); Alfresco: `43.48%` (vs `1.8%`); pybbs: `>0%` (vs `0%`) | Tăng tốc phân tích `2objH` trung bình `~5.9×` (tối đa `15.1×` trên `bitbucket-server`) | Không vận hành trên mã nguồn thiếu bytecode; không có tỷ lệ phân giải trên repo chưa build |
| **Micans** (ISSTA '25) | Microservices Java (gồm `TrainTicket`) | Cải thiện call graph & value flow liên dịch vụ REST/RPC/MQ trên Tai-e | Cải thiện precision, recall và tốc độ so với bản chuyển thể đơn khối | Số % cụ thể không nằm trong tóm tắt công khai truy xuất được (`N/A`) |
| **`evo` (`analyzer-javaparser`)** | `10` repo M9 Baseline (`--offline --build off`) + `M2`/`M9.2` | Cấu trúc khai báo: **`97.40%–100.0%`** (`8/8` repo JVM); Thân hàm: **`74.63%–99.72%`** | **`8/10`** repo đạt **`COMPLETE` (exit `0`)**; độ khớp compiler oracle **`34/34`** (M2) và **`41/41`** (`J01`) | Precision/Recall trên toàn bộ repo lớn chưa gán nhãn thủ công: **`N/A`** |

---

## Bảng 3. Phân Hệ 2 — Ngữ Nghĩa Spring Có Điều Kiện (`M4-R0`, `S01`, `P01`)

*Nguồn nội bộ: [2026-09-11-m4-r0-spring-semantics.md](2026-09-11-m4-r0-spring-semantics.md), [02-execution.md](../tasks/m9/02-execution.md), [03-execution.md](../tasks/m9/03-execution.md).*

| Hạng mục đo đạc | **`evo` (`M4-R0` / `M9`)** | Đối chứng phẳng nội bộ (`evo` ablation) | Jasmine (ASE '22) | ArchUnit / Spring Modulith |
|---|---:|---:|---|---|
| Số JAR & classfile khảo sát xây dựng danh mục Spring | **`26` JAR (`15,594` classfiles, `402` annotations)** | `N/A` | `N/A` | `N/A` |
| Danh mục họ cơ chế Spring đóng (`1.x XML` $\rightarrow$ `Boot 3.4.x`) | **`29` họ cơ chế** (`13` nhóm cũ `NOT_VALIDATED`) | `N/A` | IoC (Singleton/Prototype) + AOP | Kiểm tra bytecode / module tĩnh & runtime |
| Đối chiếu Spring container thực (`5.3.31/2.7.18`, `6.1.14/3.3.5`, `6.2.0/3.4.0`) | **`79 / 79 (100.0%)`** | `N/A` | `N/A` | `N/A` |
| M9.2 Spring `6.2.0` runtime binding & tổ hợp cấu hình (`S01`) | **`23 / 23`** binding · **`96 / 96`** tổ hợp | `N/A` | `N/A` | `N/A` |
| Đặc tả hình thức & bộ giải DPLL/SAT (`2/4/8/12` biến, seed `0–15`) | **`70 / 70 (100.0%)`** formal · **`64 / 64 (100.0%)`** SAT | `N/A` | `0` (không có SAT/DPLL) | `0` (không có SAT/DPLL) |
| Ca kiểm soát thứ tự nạp `@ConditionalOnMissingBean` | **`4 / 4 (100.0%)`** | `2 / 4 (50.0%` sai lệch) | `N/A` (không mô hình thứ tự pha) | `N/A` |
| Đánh giá policy có điều kiện & sinh witness (`P01`) | **`12 / 12`** (**`5 TP, 7 TN, 0 FP, 0 FN`**, `F1 =` **`1.00`**) · **`6 / 6`** witness | `N/A` | `N/A` | `N/A` |
| Sổ cái khoảng trống nghiên cứu `M4-R0` | **`452`** bản ghi (`402+29+3+13+5`) | `0` | `0` (không có gap ledger) | `0` |

---

## Bảng 4. Phân Hệ 3 — Tái Tạo Kiến Trúc Microservices & Đa Ngôn Ngữ

*Nguồn nội bộ: [M9-Baseline-10-Repository-Cohort-Benchmark-Report.md](M9-Baseline-10-Repository-Cohort-Benchmark-Report.md), [2026-11-10-post-update-empirical-evaluation-report.md](2026-11-10-post-update-empirical-evaluation-report.md), [current-state.md](../current-state.md), [04-step5-step1-execution.md](../tasks/m8/04-step5-step1-execution.md).*  
*Nguồn ngoài: Schneider et al. (EMSE 2025, `arXiv:2412.08352`).*

### 4A. Kết quả công bố của 9 công cụ trong Schneider et al. (EMSE 2025)

| Công cụ / Chiến lược | Thành phần (Precision / Recall / F1) | Kết nối dịch vụ (F1) | Điểm cuối REST (Precision / Recall / F1) | F1 tổng hợp cao nhất công bố |
|---|---|---|---|---:|
| **Code2DFD** | `0.93` / `0.80` / `0.86` (tới `0.98`) | `0.87` | `0.87` / `0.54` / `0.66` | `0.86` |
| **MicroDepGraph** | `0.99` / `0.59` / `0.74` | `0.67` | `N/A` | `0.74` |
| **RAD** | `0.94` / `0.23` / `0.37` | `N/A` | `0.94` / `0.68` / `0.79` | `0.79` (endpoints) |
| **RAD-source** | `N/A` | `N/A` | `0.89` / `0.54` / `0.67` | `0.67` (endpoints) |
| **Attack Graph Generator** | `N/A` | `0.54` | `N/A` | `0.54` (connections) |
| **Prophet** | `0.57` / `0.04` / `0.08` | `N/A` | `R = 0` (`P = N/A`) | `0.08` |
| **Tổ hợp `BestR` (4 công cụ)** | `N/A` | `N/A` | `N/A` | `0.91` (`Recall = 0.96`) |

### 4B. Kết quả thực đo của **`evo`** trên 10 kho mã nguồn M9 Baseline (`--offline --build off --all-files --json`, 2026-11-11)

| # | Repository | Tổng tệp / Java* | Types | Cấu trúc (`structure`) | Thân hàm (`usage`) | Gaps (`Iso / Pas`) | Issues | Thời gian (Speedup) | Trạng thái 3 trục (Exit) |
|---:|---|---:|---:|---|---|---:|---:|---:|---|
| 1 | `golang-gin-realworld` | **`41 / 0`** | **`0`** | **Thuần Go (Tree-sitter v5)** | **Thuần Go** | **`9 (7 / 2)`** | **`0`** | **`12.33s`** (**`1.54×`**) | **`COMPLETE` (0)** |
| 2 | `spring-petclinic-kotlin` | **`89 / 24*`** | **`26`** | **`363 / 365 (99.45%)`** | **`406 / 445 (91.24%)`** | **`220 (15 / 205)`** | **`42`** | **`16.69s`** (**`1.59×`**) | **`PARTIAL` (3)** |
| 3 | `full-stack-fastapi` | **`252 / 0`** | **`0`** | **`23 Py + 23 TS routes`** | **Thuần Py/TS** | **`49 (47 / 2)`** | **`0`** | **`21.66s`** (**`1.53×`**) | **`COMPLETE` (0)** |
| 4 | `android/sunflower` | **`143 / 49*`** | **`72`** | **`935 / 960 (97.40%)`** | **`1,830 / 2,452 (74.63%)`** | **`1,050 (40 / 1,010)`** | **`86`** | **`29.43s`** | **`PARTIAL` (3)** |
| 5 | `spring-boot-realworld` | **`119 / 93`** | **`100`** | **`1,499 / 1,519 (98.68%)`** | **`3,494 / 3,930 (88.91%)`** | **`762 (11 / 751)`** | **`37`** | **`29.82s`** (**`2.85×`**) | **`COMPLETE` (0)** |
| 6 | `mapstruct-examples` | **`250 / 149`** | **`124`** | **`1,284 / 1,284 (100.0%)`** | **`2,231 / 2,266 (98.46%)`** | **`1,692 (564 / 1,128)`** | **`180`** | **`54.05s`** (**`1.62×`**) | **`COMPLETE` (0)** |
| 7 | `microservices-demo` | **`364 / 2`** | **`3`** | **`56 / 56 (100.0%)` + `30 INVOKES_RPC`** | **`241 / 294 (81.97%)`** | **`277 (113 / 164)`** | **`15`** | **`81.85s`** | **`COMPLETE` (0)** |
| 8 | `petclinic-microservices` | **`190 / 53`** | **`65`** | **`655 / 655 (100.0%)` + `3 CALLS_SERVICE`** | **`1,227 / 1,254 (97.85%)`** | **`1,516 (58 / 1,458)`** | **`30`** | **`214.31s`** | **`COMPLETE` (0)** |
| 9 | `sqshq/piggymetrics` | **`168 / 72`** | **`74`** | **`903 / 903 (100.0%)`** | **`1,759 / 1,764 (99.72%)`** | **`1,566 (67 / 1,499)`** | **`38`** | **`185.76s`** (**`1.48×`**) | **`COMPLETE` (0)** |
| 10 | `TeamNewPipe/NewPipe` | **`4,450 / 555*`** | **`845`** | **`16,427 / 16,809 (97.73%)`** | **`63,491 / 76,605 (82.88%)`** | **`15,032 (88 / 14,944)`** | **`152`** | **`528.43s`** | **`COMPLETE` (0)** |

*\*Các kho Kotlin/Android gồm đơn vị hạ cấp AST từ Kotlin sang JavaParser. Tổng thời gian chạy liên tục 10 kho: **`1,174.33s (~19.57 phút)`**.*

### 4C. Các phép đo bổ sung của **`evo`** (Cache trực tuyến, Chế độ Turbo, Polyglot Native)

| Phép đo bổ sung của **`evo`** | Kho / Phạm vi đo | Kết quả trước / Baseline | Kết quả sau / Nâng cấp |
|---|---|---:|---:|
| Maven Central cache (`FAST_ARCHITECTURE`, `2026-11-23`) | `spring-petclinic` (`500158f7`) | **`741`** gaps (`602` `SEMANTIC_UNRESOLVED`) | **`179`** gaps (`0` `SEMANTIC_UNRESOLVED`) |
| Maven Central cache (`FAST_ARCHITECTURE`, `2026-11-23`) | `mapstruct-field-mapping` / `mapstruct-on-gradle` | **`75`** (`12` unresolved) / **`71`** (`8` unresolved) | **`46`** (`0` unresolved) / **`46`** (`0` unresolved) |
| Maven cache + readiness (`FAST_ARCHITECTURE`, `2026-11-23`) | `dorm-trading-platform` (`32,809` tệp) | **`1,393`** gaps (`1,194` unresolved, `1,305` roots) | **`425`** gaps (`0` unresolved, `5` modules vs `32,464` deps) |
| Chế độ `FAST_ARCHITECTURE` (Turbo, `2026-11-19/21`) | `spring-petclinic-microservices` | `FULL`: **`174.633s`** cold / **`153.574s`** warm | `FAST`: **`88.166s`** cold / **`87.224s`** warm (**`1.98×`**) |
| `ArchitectureInsightsAdvisor` (`2026-11-21`) | Tarjan SCC, Hotspots, BlastRadius, DSM | `N/A` | **`54–237 ms`** cold |
| Polyglot Native Step 5.1 (`2026-10-29`) | **`18`** language adapters, **`216`** category controls | `N/A` | **`4`** worker exports (**`250`**, **`129`**, **`271`**, **`337`** rows), **`5`** Swift contexts / **`7`** labels (**`26.332s`** & **`24.907s`**), **`33/33`** M1 / **`8`** contexts |

---

## Bảng 5. Phân Hệ 4 — Lưu Trữ CAS, Đồ Thị Quy Mô Gigabyte & GraphRAG

*Nguồn nội bộ: [M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md](M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md), [2026-11-13-newtube-architecture-analysis-performance.md](2026-11-13-newtube-architecture-analysis-performance.md), [current-state.md](../current-state.md), [04-step3-phase2-execution.md](../tasks/m8/04-step3-phase2-execution.md).*  
*Nguồn ngoài: Chinthareddy (`arXiv:2601.08773`), CodexGraph (NAACL '25), RepoProbe (ASE '26).*

### 5A. Đối chiếu trực tiếp trên `thingsboard/thingsboard` (và `Shopizer`, `OpenMRS Core`)

| Hệ thống | Kho mã nguồn | Thời gian lập chỉ mục | Độ phủ tệp / Thu nạp | Kích thước / Số lượng thực thể | Trạng thái & Giới hạn |
|---|---|---:|---|---|---|
| **DKB (Tree-sitter)** (`arXiv:2601.08773`) | `ThingsBoard` | `13.77s` | `100%` cú pháp (không giải kiểu Java / POM) | Chỉ đồ thị cú pháp nông | Không phân giải 540 POM, không gap ledger |
| **LLM-KB** (`arXiv:2601.08773`) | `ThingsBoard` | `883.74s` | Bỏ sót xác suất; không gap ledger | Đồ thị trích xuất từ LLM | Tốn chi phí token cao, dễ bỏ sót tệp |
| **DKB vs LLM-KB** (`arXiv:2601.08773`) | `Shopizer` (`1,210` files) / `OpenMRS` | DKB: `2.81s` / `5.60s` · LLM-KB: `200.14s` / `222.17s` | DKB: `4,873` chunks (`90.2%`), `0` skipped · LLM-KB: `3,465` (`64.1%`), `377` skipped (`68.8%`) | DKB: `1,158` nodes · LLM-KB: `842` nodes | DKB cao nhất về multi-hop trên `15` câu hỏi/repo |
| **`evo`** (`e48b5780`, `1,945,681` LOC) | `ThingsBoard` (`13,920` files, `169.55 MB`, `6,931` Java, `540` POMs) | Thu nạp **`100%`**; dừng tại **`1,467s` (`-Xmx8g`)** | **`100.0%`** CAS seal (**`0`** bỏ sót): **`20,566`** đối tượng (**`193.06 MB`**), **`16,785`** dòng SQLite (**`36.97 MB`**) | Phân tích AST **`6,931`** Java + **`540`** POMs (`>1,200,000` đỉnh ước tính) | Dừng an toàn tại **`[MEMORY_LIMIT]`** khi dựng đồ thị nguyên khối trong RAM |
| **`evo` (`StreamingScaleTest`)** | Tập kiểm thử tổng hợp | **`155.210s`** (M11.6) | **`1,000,001`** mục inventory, **`160,000,001 B`** | Heap worker **`48 MiB`** (M8.4.3) | Chứng minh tầng thu nạp CAS tuyến tính hóa không vướng trần |

### 5B. Hiệu năng lưu trữ phân vùng (`PartitionedGraphStore`) & truy vấn quy mô Gigabyte của **`evo`**

| Kho mã nguồn | Quy mô đồ thị chuẩn tắc | Thời gian xử lý & Tối ưu lưu trữ của **`evo`** | Độ trễ truy vấn & Mức RAM tiêu thụ |
|---|---|---|---|
| **`halo-dev/halo`** (`402,600` LOC, `3` contexts) | **`852,845`** đỉnh · **`53,153`** cạnh · **`631,754`** chứng tích · **`1.51 GB`** JSON (`1,505,668,739 B`) | Phân trang đầu: **`420.962s`** (`-Xmx8g`) · Xác thực lạnh: **`195.730s`** (`-Xmx256m`) · Cold server: **`119.712s`** | Heap **`-Xmx256m`** (Peak RSS **`364,224,512 B` ~`347 MB`**) · 1 đỉnh: **`40.296 ms`** · 6 cạnh ra: **`100.503 ms`** · 1 cạnh vào: **`26.725 ms`** |
| **`aleixrodriala/newtube`** (`20` contexts) | **`1,054,591`** đỉnh · **`423,916`** cạnh · **`1,075,690`** chứng tích · **`1.97 GB`** JSON (`1,969,764,232 B`) · **`13,153`** leaf | Xuất bản 1 lượt: **`592.347s → 18.471s` (`-96.9%`)** · DEFLATE gap pack: **`357,392,256 B → 39,521,405 B` (`-88.9%`)** · Tổng job: **`1,435.141s → 600.968s` (`2.39×`)** | Đọc trang gap: **`71 ms`** / **`16 ms`** · Discovery 20 contexts: **`9.134s`** · Trang 50 gap đầu: **`0.256s`** |
| **`MP-Manager`** (`16` contexts) | **`4.01 GB`** `.graph.json` (`4,014,549,238 B`) | Nạp lạnh: **`110,251 ms`** · Nén slice **`> 1,024`** ký tự & giới hạn **`32`** chẩn đoán/tệp | Trong lúc pre-warm: `/` **`249 ms`**, `/app.js` **`251 ms`**, `/jobs` **`38 ms`** · Warm manifest **`104 ms`** · `16` `/provenance` **`1,149 ms`** · Graph `PACKAGE` **`898 ms`** |
| **`33k-row scale probe`** (`M9.4`) | **`33,000`** TYPE rows (`15,531 B`/page of `50`) | Cold publication + paging: **`7.815–9.413s`** (`6` trials, SHA-256 giống nhau từng bit) | Warm reopen/paging: **`2.858–4.137s`** |

### 5C. Số liệu GraphRAG & Hiểu mã nguồn (`ArchitectureAgent` vs. CodexGraph & RepoProbe)

| Hệ thống | Tập đánh giá | Chỉ số định lượng công bố / đo được |
|---|---|---|
| **CodexGraph** (NAACL '25) | *CrossCodeEval Lite* (Python), *SWE-bench Lite* | CrossCodeEval: `27.90%` (single-query) / `25.90%` (multi-query), `+10.4%`–`+17.1%` EM vs No-RAG (GPT-4o); SWE-bench Lite: `22.96%` / `17.90%` |
| **RepoProbe** (ASE '26) | `500` câu hỏi mở / `50` repo (`15` ngôn ngữ) | GPT-5.2: `62.7%` overall (`60.3%` knowledge, `84.7%` clarity, `26.0%` perfect); Claude Opus 4.6: `62.1%`; GPT-5.4: `60.4%`; Sonnet 4.6: `60.2%`; GLM-5: `54.8%` |
| **`evo` (`ArchitectureAgent` + MCP)** | Kiểm thử tích hợp nội bộ (`Wave 1–3`, `2026-11-23`) | **`10`** công cụ GraphRAG đọc-duy-nhất (`[E#]`), **`7`** công cụ MCP (`20` `graph_query` ops), **`59/59`** backend test suite; độ chính xác trên RepoProbe/CrossCodeEval: **`N/A`** |

---

## Bảng 6. Phân Hệ 5 — Tiến Hóa Kiến Trúc (`M11`), Sửa Lỗi & Xác Minh Cô Lập (`M8.4 Step 3`, `Demo Step 3–5`)

*Nguồn nội bộ: [06-execution.md](../tasks/m11/06-execution.md), [04-step3-phase2-execution.md](../tasks/m8/04-step3-phase2-execution.md), [demo-repair-verification.md](../architecture/demo-repair-verification.md), [demo-finding-transition.md](../architecture/demo-finding-transition.md), [current-state.md](../current-state.md).*  
*Nguồn ngoài: CausalRepair (ISSTA '26), AutoCodeRover (ISSTA '24), ArchFix (SPE '13).*

| Hệ thống | Cơ sở đánh giá | Số liệu tiến hóa / sửa lỗi | Thời gian & Tiêu thụ tài nguyên | Tỷ lệ qua kiểm định / Trạng thái |
|---|---|---|---|---|
| **CausalRepair** (ISSTA '26) | *Defects4J* (V1.2 & V2.0), *GitBug-Java* (`199` bugs) | Sửa `313` lỗi Defects4J; `101 / 199 (50.75%)` GitBug-Java (`73` single-func, `21` multi-func, `7` other) | `$0.029` / lỗi (DeepSeek-V3) | Đánh giá qua unit test chức năng |
| **AutoCodeRover** (ISSTA '24) | *SWE-bench Lite* (`300` GitHub issues) | `19%–22.3%` resolved (tối đa `~37%` ở mở rộng tương tác) | `~4–10 phút` / task · `$0.43–$0.70` / issue | Đánh giá `Pass@1` qua unit test ẩn |
| **ArchFix** (SPE '13) | `2` hệ thống công nghiệp | `32` hướng sửa vi phạm kiến trúc | `N/A` | Đề xuất tái cấu trúc tĩnh, không VM cô lập |
| **`evo` — `M11` Compatible Evolution** | `42` ca đăng ký (`E01`–`E18`) + `5` Git rev (`1,004` blobs) + `33k` types | **`31`** `PASS` + **`7`** `NOT_RUN` + **`1`** `FAIL/scoped` + **`2`** `UNSUPPORTED` + **`1`** `NOT_RUN full` (r3 bổ sung kiểm soát mở) | Doc cold (r3): **`60.330s`** (`0.472s` warm); Scale `33k` cold: **`142.820s`** (**`475.3–488.3 MB`** RAM), warm page **`0.530s`** | Trùng từng byte SHA-256 `b6219f71...` (`33,000` TYPE pairs + `1` `RELATION_ADDED`) |
| **`evo` — `M8.4 Step 3` Core MVP** | Hyper-V guest offline (Maven + generated code) | **`664`** census entries, **`428`** file objects, **`7,615,532 B`** captured | **`435.532s`** (`CONTROLLER_PROTOCOL_COMPLETE`) | **`0`** pending/rejected · **`Core MVP CLOSED`** (`qualified=false`) |
| **`evo` — `CapturedRepair` & Demo QEMU** | `7` công thức Java + `3` vòng `javac` + QEMU Linux (`1 vCPU, 1 GiB`) | Tái phân tích vi sai: **`287 ms`** uncached / **`12 ms`** cached; Demo `client.no-store` | `N/A` chi phí token chuẩn hóa trên tập lớn | **`9 / 9 (100.0%)`** build & Spring profile checks (`dev=off`: `CLEAN`; `dev=on`: `RESOLVED` vs `PERSISTED`), **`VERIFIED_DEMO` (exit `0`)**; Defects4J/SWE-bench = **`N/A`** |

---

## Bảng 7. Sổ Cái Minh Bạch: Các Khoảng Trống, Ca Chưa Chạy & Trạng Thái Cổng Kiểm Định Của `evo`

| Mốc / Hạng mục | Tổng số hạng mục | Đạt chuẩn (`PASS` / `COMPLETE`) | Giới hạn phạm vi / Tạm hoãn / Lỗi | Nguyên nhân thực tế ghi nhận trong hồ sơ |
|---|---:|---:|---|---|
| **`M9.5` 17-Claim Ledger** *(đối soát `M11.1`)* | **`17`** | **`13`** `PASS` *(bản tóm tắt cũ ghi `14`)* | **`2`** `SCOPED_PASS_WITH_RESERVATIONS` (`F01`, `U01`) · **`2`** `NOT_RUN_CAPTURE_UNAVAILABLE` (`B01`, `D01`) | Thiếu bản capture native `m844-*` lịch sử; thiếu dung lượng đĩa & đánh giá người dùng thực |
| **`M9.4` Large Repo Stops** | **`7`** repo (`4` lớn) | **`3`** (`realworld-go`, `petclinic-kotlin`, `boutique`) | `agentscope` (**`799.955s` / `8.058 GiB`** scratch), `halo` (**`1,800.087s` / `6.981 GiB`**), `fermata` (hủy **`701.137s`**), `newpipe` (`NOT_RUN` tại M9.4) | Tràn ngưỡng scratch/observation trước nâng cấp DEFLATE & streaming; `newpipe` hoàn thành ở M9 Baseline (`528.43s`) |
| **`M11 / Gate G7`** | **`42`** cases | Xem Bảng 6 | **`CLOSED BY HUMAN DECISION / NOT CERTIFIED PASSED`** (ADR-011) | Còn **`3`** assertion chưa giải quyết trong protected reactor (`2` M4E completeness, `1` Spring metric scope) |
| **Kiểm thử JUnit 7 mô-đun (`2026-11-23/24`)** | **`1,947`** (và **`254`** + **`11`** JS) | **`1,947 / 1,947`** (`2026-11-23`) · **`254 / 254`** + **`11 / 11`** (`2026-11-24`) | Loại trừ **`10`** bộ kiểm thử phụ thuộc môi trường/tài nguyên ngoài | `PlatformReactorModelTest`, `NativeAbiBridgeIT`, `StreamingScaleTest`, `WorkerProcessSupervisorTest`, `SpringBindingOracleIT`, `UniversalArchitectureM4EAdjudicationTest`, `UniversalArchitecturePipelineIntegrationTest`, `*IT`, `CapturedRepairVerificationTest`, `2` test `StreamingStorageTest` |
