# M9 — Multi-Repository Evaluation and Hardening

Ngày lập: **2026-11-11**. Baseline mã nguồn được đọc: **`d8c2603`**.

**Trạng thái hiện hành: M9.1 COMPLETE / protocol registered; M9.2–M9.5 chưa nghiệm thu.** User đã yêu cầu thực thi Task 1; [protocol v1](PROTOCOL.md), [registry](registry.json) và [execution record](01-execution.md) sở hữu các lựa chọn đã khóa và kiểm chứng thực tế. Nội dung lập kế hoạch ban đầu bên dưới được giữ để truy vết; criterion đã đăng ký trong protocol có hiệu lực trước acceptance. M8 giữ CLOSED; không tự sửa acceptance M8 hoặc cho phép Track B.

**CONFIRMED — human, 2026-11-11:** ngưỡng hoàn thành phục hồi kiến trúc thụ động đã được chốt ở **95.0% (`950‰`)**, đúng với code hiện tại, nhằm giảm `PARTIAL` không cần thiết do ngưỡng cũ. Áp dụng [readiness contract](../../architecture/m8-repository-readiness.md#ngưỡng-hoàn-thành-phục-hồi-kiến-trúc-thụ-động-hiện-hành-95) và [amendment ADR-007](../../decisions/ADR-007-isolated-build-and-polyglot-readiness.md#human-approved-passive-recovery-completion-threshold--2026-11-11); đây không còn là quyết định cần hỏi tại M9. Ngưỡng này không thay semantic correctness, evidence tier hoặc gap accounting.

## 1. Mục tiêu và căn cứ

M9 phải biến sản phẩm đã giao ở M8 thành một baseline Track A có **độ đúng được kiểm tra độc lập, giới hạn được đo, vận hành bền vững và hành trình người dùng tái hiện được**. Kết quả là bộ bằng chứng để human xét M10. M9 không chỉ bổ sung repository rồi đếm số lần CLI trả `COMPLETE`.

Căn cứ chính:

- [AGENTS.md](../../../AGENTS.md), [human mandate](../../architecture/comprehensive-repository-coverage-problems.md), [current state](../../current-state.md) và [roadmap](../../roadmap.md).
- [Product outcome](../../architecture/product-outcome.md), [conditional semantics](../../architecture/conditional-architecture-semantics.md), [M8.4 readiness](../../architecture/m8-repository-readiness.md), [polyglot semantics](../../architecture/m8-polyglot-semantics.md).
- [M8.3 acceptance](../m8/03-structure-configuration-and-workbench-acceptance.md), [M8 closure report](../../research/M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md), [post-update report](../../research/2026-11-10-post-update-empirical-evaluation-report.md).
- [Test selection](../../testing.md), [ADR-002](../../decisions/ADR-002-product-outcome-and-explainable-assessment.md), [ADR-007](../../decisions/ADR-007-isolated-build-and-polyglot-readiness.md).

### Những gì đã xác nhận và những gì chưa được suy ra

| Quan sát tại thời điểm lập kế hoạch | Ý nghĩa đối với M9 |
|---|---|
| **CONFIRMED — human:** M8 đóng ngày 2026-11-10; user tái xác nhận trong yêu cầu này | Giữ M8 CLOSED. Không khôi phục các chiến dịch Step 3/Step 5 đã được human rút khỏi điều kiện nghiệm thu |
| **CONFIRMED — human và đọc mã:** ngưỡng completion hiện hành là **95% (`950‰`)**, khớp `SemanticDepth.ARCHITECTURE_THRESHOLD_PER_MILLE=950L`; `980‰` thuộc checkpoint lịch sử | Quyết định đã đóng; dùng 95% cho passive completion, giữ correctness và evidence requirements riêng |
| **CONFIRMED — đọc test:** test tên `passiveArchitectureThresholdRequiresNinetyEightPercentStructureAndNoFailedUnits` đang kiểm negative tại `949‰` | Tên test còn mang ngưỡng lịch sử; đồng bộ tên và kiểm biên 949/950 theo policy 95% trong slice test phù hợp, không hỏi lại ngưỡng |
| **CONFIRMED — đọc báo cáo:** bảng 13 repo gắn với `b6846f8` + bản vá; phần sau mô tả các sửa đổi ngày 11/11; HEAD hiện khác | Số liệu cũ là bằng chứng của bản đã đo, không phải xác nhận thực nghiệm cho HEAD hiện tại |
| **CONFIRMED — đọc báo cáo:** bảng đó có 3 `TIMEOUT` và 7 `PARTIAL` | Giữ nguyên lịch sử. Tình trạng sau các bản vá cần đo đúng phạm vi mới, không tự coi các trường hợp này còn lỗi hoặc đã hết lỗi |
| **CONFIRMED — đọc mã:** lane thuần non-JVM dùng tỷ lệ parsed syntax và category counts trong predicate phục hồi thụ động | Tỷ lệ parse, recovery kiến trúc và semantic correctness phải được báo riêng; không dùng parse thành công để chứng nhận binding/call/route đúng |
| **CONFIRMED — tài liệu:** current state đã đồng bộ M8 CLOSED, ngưỡng 95% và next task M9.1; README gốc còn mô tả M8.4 chưa bắt đầu | Hoàn tất đối chiếu các mục trạng thái còn lại trong Task 1; giữ nguyên hồ sơ lịch sử và raw evidence |
| **CONFIRMED — đọc roadmap/current state:** G6 lịch sử đã PASSED cho M7 release preparation, trong khi mục M9 vẫn mô tả exit G6 | Hồ sơ M9 cần ghi rõ phạm vi nghiệm thu M9 và liên kết G6 lịch sử; không ghi đè adjudication cũ, không suy ra M10 đã đạt |

**HYPOTHESIS cần kiểm tra:** các tối ưu/PPA mới có thể tăng coverage và tốc độ nhưng cũng có thể ảnh hưởng attribution, provenance, cache integrity hoặc determinism. Đây là lý do chọn kiểm chứng có trọng tâm, chưa phải kết luận chúng có lỗi.

### Phạm vi và giới hạn

- Giữ Java/Spring là nền tảng được bảo vệ; đánh giá thêm Kotlin/Android, generated code, multi-root/module và polyglot/bridges theo capabilities đã giao.
- Bao quát inventory, selected contexts, semantic categories, active/historical gaps, graph, policy, metrics/score, query/API/CLI/export/UI, recovery, performance và accessibility.
- Không đổi parser chính, kiến trúc modular monolith, graph store hay UI framework nếu chưa có bằng chứng kích hoạt thay thế.
- Không thêm AI diagnosis, tự sinh patch, Verified PR, deployment-world federation hay snapshot evolution của M11. So sánh baseline/candidate và perturbation trong benchmark không phải triển khai sản phẩm Track B.
- Không biến M9 thành đợt qualification toàn bộ A01–A12/98-case đã rút. Khuyết thiếu thực tế vẫn được ghi rõ; M9 chỉ nghiệm thu các claim có bằng chứng tương ứng.

## 2. Đúng 5 task và thứ tự thực hiện

| Task | Kết quả phải giao | Phụ thuộc |
|---|---|---|
| **M9.1** | Baseline, corpus, oracle, denominator, protocol và tiêu chí nghiệm thu đã khóa | M8 đã đóng |
| **M9.2** | Ngữ nghĩa/evidence được đối chiếu độc lập; sửa các lỗi có reproduction | M9.1 |
| **M9.3** | Graph, conditional policy, metric, score và các consumer thống nhất, có golden evidence | M9.1; dùng đầu ra đã ổn định từ M9.2 |
| **M9.4** | Vận hành, scale và workbench đạt kịch bản đã đăng ký | M9.2 + M9.3 cho nghiệm thu tích hợp |
| **M9.5** | Campaign cuối, review độc lập, kết luận M9 và hồ sơ trình M10 | M9.1–M9.4 |

Luồng mặc định: **M9.1 → M9.2 → M9.3 → M9.4 → M9.5**. Có thể chuẩn bị golden micrographs/UI scripts sau M9.1 khi không phụ thuộc kết quả khác; không cần tạo agent/worker song song chỉ để tăng số kiểm tra. Các checklist bên dưới nằm trong năm task này, không phải task bổ sung.

## 3. M9.1 — Chốt baseline và protocol đánh giá

**Mục tiêu:** mọi kết luận M9 phải trả lời được: đo bản nào, trên dữ liệu nào, với oracle nào, mẫu số nào và tiêu chí nào đã được xác lập trước khi xem kết quả.

### Công việc

1. **Lập bảng đối chiếu baseline.** Ghi commit/dirty patch digest, JAR SHA-256, schema/provider/rule/metric/score/status-policy versions, runtime, captured inputs, exact classpaths và selection. Phân biệt M8 accepted baseline, báo cáo hậu cập nhật và M9 candidate. Không trộn ba phiên bản trong một dòng kết quả.
2. **Áp dụng policy 95% đã được duyệt.** Dùng **95.0% (`950‰`)** cho passive completion với các guard hiện hữu; lưu baseline/policy áp dụng cho từng run. Đồng bộ tên test còn ghi 98%, kiểm biên 949/950 và các điều kiện lỗi/evidence trong slice phù hợp. Semantic correctness, inventory completeness, evidence tier và health score tiếp tục có phép đo riêng. Không yêu cầu phê duyệt lại 95% hoặc tự tính lại status của raw runs lịch sử.
3. **Đồng bộ trạng thái đang có hiệu lực.** Sửa phần đầu/cuối current state, mục active milestone README và liên kết task tiếp theo cho nhất quán với M8 CLOSED và M9 planning. Roadmap chỉ sửa chỗ cần làm rõ phạm vi G6/M9/M10, không viết lại lịch sử hoặc thay milestone sequence. Các chỗ đề cập score/calibration phải kế thừa quyết định human đã có ở G4/G5/ADR-002, không mở lại công thức chỉ vì đoạn văn cũ còn ghi provisional.
4. **Đăng ký corpus theo strata.** Dùng bảng dưới đây; pin commit đầy đủ và source manifest. Có independent inventory census cho file/module/source set/language/context/generator. Ghi chính xác main/test/generated/vendor selections, ownership và các context chưa phân tích. Một file dùng ở nhiều context được phân biệt với nhiều file vật lý.
5. **Đăng ký oracle/labels trước khi chấm điểm.** Mỗi claim có independent expected outcome, phạm vi áp dụng, positive/negative/degraded control, nguồn evidence và quy tắc adjudication. Parser đang được đánh giá không tự sinh nhãn đúng cho mình. Bất đồng giữa oracle, source evidence và analyzer phải được lưu và xử lý, không lấy đa số tool làm chân lý.
6. **Đăng ký protocol và ngân sách.** Chốt reference hardware/runtime/browser, resource profile, cold/warm definition, queries/views, time/memory/disk/payload budgets, số lần lặp, exclusion/deviation policy và tiêu chí dừng. Dùng pilot trên development corpus để chọn budget rồi freeze trước acceptance; pilot không phải kết quả holdout. Ngân sách thời gian của thí nghiệm không trở thành trần từ chối repository của sản phẩm.
7. **Tái sử dụng hạ tầng.** Kiểm tra `benchmarks/g2-pipeline`, `benchmarks/m2-ground-truth`, `benchmarks/m4-r0` và runner hiện có; mở rộng một entry point có preflight/report gọn. Không viết hệ benchmark thứ hai hoặc sao chép SDK/captures. Chỉ tạo protocol/labels/index nhỏ; raw captures và caches ở ngoài Git.

### Corpus và ba lane bằng chứng

| Nhóm | Chọn và sử dụng |
|---|---|
| Microfixtures | Tái sử dụng Java relationship families, Spring condition/registration/binding, independent census, generated lineage, graph/metric golden; bổ sung đúng cơ chế thiếu coverage |
| Development/regression | Tái sử dụng cohort 13 repo trong báo cáo hậu cập nhật. ThingsBoard và PetClinic Java chuẩn ở hồ sơ trước được mapping riêng; tránh nhầm PetClinic Java, Kotlin và microservices là cùng một case |
| Scale | Đại diện Maven nhiều module (`agentscope-java`), Gradle/Spring (`halo`), Android nhiều variant (`Fermata`/`NewPipe`), polyglot/gRPC (`microservices-demo`) và graph sinh bằng generator sẵn có với seed/shape/size được pin |
| Holdout | Chọn trước theo tiêu chí độc lập, không chạy thử để lấy repo có điểm đẹp. Đề xuất khởi đầu 3–5 repo bổ sung các archetype/rủi ro còn thiếu, không lấy số lượng repo làm bằng chứng bao phủ mọi ngôn ngữ. Khi dùng một holdout để sửa lỗi, ghi rõ đã trở thành regression; không tiếp tục gọi lần đo sau là kiểm chứng chưa từng thấy |
| Native/captured | Dùng các captured native journeys tương thích đã có cho engines/bridges. Mỗi capability được quảng bá có case hoặc giới hạn chưa qualified rõ ràng; không khôi phục qualification toàn pack bị rút |

Ba lane được report riêng: **P — passive/offline**, **C — replay captured native evidence**, **N — native execution mới nếu claim đòi hỏi**. C/N chỉ so sánh paired với P khi cùng nguồn/context và khác biệt input được ghi chính xác. Không có worker/capture phù hợp thì ghi unavailable/NOT RUN và ảnh hưởng claim; không có host fallback và không tự tạo VM hàng loạt.

### Mẫu số và metric chính

- `inventory accounting`: đối chiếu inventory độc lập; mọi entry/context có disposition, kể cả bị lỗi, chưa thu được hoặc ngoài selection. Accounting đầy đủ không đồng nghĩa byte acquisition/semantics đầy đủ.
- `resolution coverage`: resolved / independent eligible obligations, report theo repo × context × language × category × evidence tier; mẫu số rỗng là N/A. Giữ số attempted riêng để phát hiện engine bỏ sót.
- `semantic correctness`: correct/incorrect adjudicated outcomes, precision/recall trên phần có labels. Report độ bao phủ labels và trường hợp chưa adjudicate; không coi unresolved là incorrect binding, không bỏ omitted khỏi recall.
- `structure` và `usage` tách nhau; syntax parsing, architecture candidates, native binding và runtime observation cũng tách nhau. Không dùng tỷ lệ Java làm điểm đại diện toàn polyglot repo.
- False certainty, false unconditional warning, witness validity và affected-region precision/recall có denominator được định nghĩa trước. Report số đếm cùng tỷ lệ; 0 lỗi quan sát không phải chứng minh lỗi bằng 0 trên mọi chương trình.
- Report per-repo/per-stratum trước, rồi macro và micro aggregates nếu có ý nghĩa; timeout/failure/unavailable giữ trong toàn bộ population. Không tính trung bình latency chỉ trên successful runs rồi gọi là latency toàn cohort.

**Đầu ra dự kiến:** một protocol có version, corpus/label registry nhỏ, claim-to-test matrix, baseline identity và danh sách unresolved acceptance decisions. Chỉ thêm `benchmarks/m9/` nếu entry point hiện hữu không phù hợp; implementation chọn vị trí tối thiểu.

**Điều kiện xong:** mỗi claim có oracle + denominator + case + tiêu chí quyết định; chọn corpus/holdout được freeze; thông tin trạng thái hiện hành không mâu thuẫn. Các ngưỡng định lượng mới phải được chốt trước khi chấm acceptance, không hồi tố sau kết quả.

## 4. M9.2 — Kiểm chứng độc lập semantic recovery và evidence

**Mục tiêu:** đo analyzer hiểu đúng đến đâu, thu hẹp gap bằng bằng chứng và sửa defect đã tái hiện, bảo vệ độ đúng sau các cập nhật M8.

### Công việc

1. **Java và partial-program recovery.** Đối chiếu caller/target/signature/overload/origin/span bằng javac bindings hoặc labels được review trên cùng classpath/release. Bao phủ explicit/wildcard imports, tên trùng package/type, shadowing, nested/local/anonymous types, generics, inheritance, method references và source không compile. Thiếu JAR, chỉ một wildcard import hay tên giống Spring không tự chứng minh declaration tồn tại.
2. **Negative controls cho các thay đổi mới.** Wildcard chứa kiểu không tồn tại; kiểu cùng tên ở dependency khác; overload thiếu receiver/type; duplicate Android variants; dependency bytes khác dù tên giống; AST Kotlin annotation dùng ở nhiều vị trí; source layout ngoài conventional roots. Kiểm cả strict/exact và recovery lanes, không ép hai lane có cùng trust label.
3. **Build context và generated code.** Kiểm nested reactors/BOM/CI-friendly version, Maven/Gradle multi-root, KMP/Android variants, release/platform view, source ownership và thứ tự classpath. Đối chiếu Lombok/MapStruct/Protobuf/gRPC với capture native có lineage thật. Handwritten stub không thay compiler/generated evidence; capture stale hoặc sai context không được dùng để đóng gap.
4. **Spring conditions và interactions.** Tái sử dụng catalog/fragment/version đã chấp nhận; bổ sung interaction cases thiếu coverage: profile/property/classpath × registration order × OnBean/OnMissingBean × qualifier/primary/fallback. Exhaustive oracle cho không gian nhỏ; runtime capture có scope khi cần. Kiểm MUST/MAY/NEVER/UNKNOWN, infeasible region, opaque condition, unknown order, solver frontier và witness/counter-witness. Phân biệt formula replay với semantic/runtime replay.
5. **Polyglot và bridges.** Đối chiếu các native/source captures hiện có; tập trung Kotlin-Java, Python/TS/Go, frontend/config/schema và cross-provider/source mapping. Kiểm import/call target, scope, embedded offsets, UTF-8/UTF-16/byte columns, routes/contracts, candidate vs proven edge. Đặc tả API hoặc URL trùng tên không chứng minh kết nối runtime xuyên service.
6. **Controlled degradation.** Từ một captured exact context, lần lượt bỏ đúng artifact/generated output/config input; lưu thứ tự và digest phần bị bỏ. Đo status/coverage/correctness/provenance/gap lifecycle. Evidence loss không sinh certain target hoặc silently clean architecture. Restore input phải cho kết quả tương thích ban đầu.
7. **Sửa lỗi theo root cause.** Mỗi lỗi có fixture nhỏ red → fix → green và consumer check phù hợp; sau đó chỉ chạy lại external checkpoint bị ảnh hưởng. Lỗi môi trường, thiếu quyền/SDK và unsupported semantics không gom chung thành parser defect. Có attempt đã biết blocker thì chỉ resume khi blocker thực sự đổi.

**Vùng code/test liên quan:** `analyzer-javaparser`, `analyzer-maven`, `analyzer` ingestion/Spring/polyglot/bridges, `analyzer-execution` khi native producer thay đổi, `backend` composition. Ưu tiên `SourceRecoveryAttributionTest`, `ResolutionInputsTest`, `SemanticDepthArchitectureTest` và các capture/source-to-Spring suites hiện hữu.

**Đầu ra:** adjudicated results theo category/context/tier; regression fixtures cho defect thật; patch và verification bị ảnh hưởng; gap inventory có current vs historical disposition.

**Điều kiện xong:** mọi case bắt buộc đã có outcome; không còn defect false-certainty/provenance/omission đã xác nhận trong phạm vi claim nghiệm thu. Controlled unsupported cases có gap đúng là pass của honesty contract, không phải pass của semantic-completeness claim. Recall/coverage/correctness đạt criterion đã khóa theo từng cohort; không cần ép tất cả repo thành VERIFIED_EXACT.

## 5. M9.3 — Kiểm chứng graph, policy, metrics và score xuyên suốt sản phẩm

**Mục tiêu:** fact đúng phải tạo graph/query/assessment đúng, và cùng một giá trị phải có cùng nghĩa trên CLI, API, export và UI.

### Công việc

1. **Canonical graph và persistence.** Kiểm identity, dedup, context/source-set/variant ownership, dangling edge, partitions, CAS/cursor/adjacency, cold reopen và import audit. So graph logic giữa memory/disk projections trên cùng input; single-pass digest phải tương đương canonical reference, không lấy implementation mới làm oracle duy nhất. Multi-context không tự union thành một thế giới.
2. **Conditional paths/cycles/policy/impact.** Tạo mutations tối thiểu có expected source/edge/region: thêm dependency cấm, chu trình thật, chu trình chỉ tồn tại trong union bất khả thi, đổi profile/property/registration order, đổi boundary. Đo false positive/negative, affected-region precision/recall, path/witness validity và impact trong depth/work bounds. Ngoài frontier là unknown, không là unaffected.
3. **Metric golden độc lập.** Hand-computed micrographs cho counts, Ca/Ce distinct neighbors, fan-in/out, SCC/self-loop, density, A/I/D, scope/main/test/generated populations và zero denominator. Một call lặp không làm coupling nhân lên; context duplicate không tăng inventory vật lý. `PARTIAL`, `WITHHELD`, `NOT_APPLICABLE` có operands/reason rõ.
4. **Score sensitivity và missing evidence.** Giữ model/version/weights đã được human duyệt, kiểm caps/penalties/dedup, severity vs MAY, thiếu policy, unknown regions, mất classpath, chưa phân tích module, tăng size không đổi cấu trúc tương ứng. Kiểm monotonicity chỉ ở biến đổi có kỳ vọng toán học được định nghĩa. Khi mất evidence, số điểm mới không được trình bày như verified improvement. Nếu phát hiện model defect, lập bằng chứng và phương án versioning trước khi đổi numerical policy.
5. **Language applicability.** Java Martin metrics và Spring rules không mặc định hợp lệ cho Python/JS/SQL. Dùng applicability hiện có và N/A/WITHHELD đúng; inventory/semantic observations vẫn hiển thị. Không tạo điểm health toàn polyglot repository bằng cách chỉ chấm phần Java hoặc bỏ ngôn ngữ khó.
6. **Consumer conformance.** Chạy cùng query/context/configuration qua services → HTTP → CLI/export → workbench model; so canonical values/status/tier/gaps/denominators. Kiểm mọi summary card, score penalty và violation dẫn tới đúng facts/path/source span và provenance. Pagination/cursor đổi context/filter/version phải reject hoặc restart đúng contract; giới hạn view không làm mất inventory phía server.

**Vùng code/test:** `analyzer` graph/query/metrics/policy/assessment; `analyzer-filesystem`; `backend` query/report/web; `cli`. Reuse `StructuralMetricsTest`, `ConditionalPathCycleTest`, `PolicyConditionalEvaluationTest`, `ConditionalAssessmentTest`, `AssessmentMetricsIntegrationTest`, `PartitionedGraphStoreTest`, `PartitionedGraphApiTest`, các JS model tests và packaged CLI smoke.

**Đầu ra:** golden/mutation fixtures, cross-surface conformance matrix, các defect fixes và measured results. Mutations là fixture đánh giá rule, không phải chức năng tự sửa repository.

**Điều kiện xong:** exact golden operands/counts/statuses khớp; không còn confirmed false certain path/cycle, fabricated score hoặc mismatch giữa các consumer trong bộ nghiệm thu. Mỗi derived claim truy được về evidence và version phù hợp.

## 6. M9.4 — Hardening vận hành, scale và workbench

**Mục tiêu:** những kết quả đã đúng vẫn truy cập được khi repo lớn, môi trường thiếu dữ kiện hoặc người dùng hủy/tiếp tục công việc.

### A. Robustness và integrity

- Đăng ký matrix cho input lỗi/encoding/path collision/symlink/LFS/submodule, network idle/transient/auth failure, thiếu/corrupt dependency/generated capture, disk pressure, cancellation/restart, concurrent jobs và stale/revoked evidence. Tái dùng compact fixtures và existing captures.
- Kiểm checkpoint/recovery không bỏ hoặc nhân đôi entry/context; failed/canceled publication không tạo complete root; unsupported component vẫn thuộc census và hiển thị gap.
- Kiểm correctness của cache và concurrency mới: input bytes đổi cùng size/mtime; JVM giữ cache qua nhiều analysis; classpath order/duplicate FQN; schedule/thread count đổi; worker/page failure. `ResolutionEnvironment` hiện có cache theo path/size/mtime, nên integrity control này có căn cứ từ mã; chưa kết luận một exploit đã được thực nghiệm.
- Bảo vệ host/worker boundary và output hash/lineage, API input/limits, render text an toàn và redaction trong các luồng đã giao. Không mở lại chiến dịch sandbox certification bị rút. Khi cần native run mới, preflight profile/image/transport/tool/source identity và resource policy trước.

### B. Determinism và performance

- Đo riêng acquisition, readiness, semantic extraction, Spring/conditional reasoning, graph publication, query, UI; thu wall time, peak process memory/heap theo phương pháp đã ghi, temporary disk, page/byte counts và payload sizes. Đo cả context/module count và graph density, không chỉ LOC.
- Tối ưu từ phase/profile có số liệu. Các nguyên nhân bottleneck từng được nêu trong báo cáo là giả thuyết cần xác minh lại trên candidate; không mặc định mọi timeout là I/O hoặc solver.
- **Đề xuất protocol ban đầu:** trên các representative cells được chọn ở M9.1, ba independent cold runs và ba warm runs để kiểm repeatability/range; tăng số lần chỉ khi variance hoặc tiêu chí cần. Cold process, cold analysis cache và cold OS cache phải ghi riêng; không gọi process mới là cold disk nếu chưa kiểm soát OS cache. Không dùng 3 mẫu để khẳng định tail p95/p99 ổn định; latency query/UI cần workload/sample count riêng.
- Replay captured inputs phải giữ canonical identity/digest, metrics, findings, current gaps và witness ordering khi environment variation thuộc contract. Live download/retry timing là operational evidence; native build outputs thay đổi là revision mới, không normalize mất khác biệt.
- Reuse existing scale generator cho graph thưa/dày, SCC lớn, nhiều contexts, oversized row, memory/disk pressure. API/viewport vẫn bounded; toàn repo được paging/checkpoint, không dùng hard total-repository ceiling. Watchdog thật sự hung khác một scan lớn đang tiến triển.
- Timeouts trong benchmark giữ kết quả censored và checkpoint, không bị loại khỏi denominator, không báo hoàn thành. Rerun trường hợp lịch sử chỉ khi candidate/environment thay đổi liên quan và mục tiêu đo đã rõ.

### C. Workbench và accessibility/usability

Kiểm sáu hành trình đã có sản phẩm hỗ trợ:

1. Chọn pinned repo/context/policy → submit → progress → cancel/retry/reopen history.
2. Overview → hiểu operational status, evidence tier, active gaps và denominators.
3. Metric/score penalty/violation → graph/path → source span → provenance → export.
4. Realized/MUST/MAY/UNKNOWN → affected region → witness; UI ghi đúng loại replay thực tế.
5. Search/structure/focused graph/paging/impact với data lớn và bounded frontier.
6. Degraded/empty/error/missing-policy/missing-native states; đổi nhanh repo/context/tab không hiện stale response như kết quả mới.

- Tái sử dụng UI tại `backend/src/main/resources/web/`, không dựng frontend mới. Kế thừa bounded projections và viewport controls M8.3; xác nhận giá trị thực tế trong candidate.
- Kết hợp automation với keyboard, visible/non-obscured focus, dialog focus-return, screen reader spot check, contrast, non-color status, reflow/zoom và graph/table alternative. Tham chiếu các tiêu chí phù hợp của [WCAG 2.2](https://www.w3.org/TR/WCAG22/); scoped review không được gọi là chứng nhận WCAG toàn sản phẩm.
- Dùng viewports M8.3 làm baseline (1440×1000, 390×844, kiểm reflow 320 px), đăng ký browser/runtime thực dùng. Tách network/query time, render time và interaction-ready time.
- Đề xuất 3–5 người dùng thử có khả năng đọc code, giao task cụ thể và ghi completion, lỗi, mức trợ giúp và khả năng phân biệt recovery/exact/unknown. Đây là review định tính nhỏ, không là ước lượng đại diện population. Nếu chưa có người độc lập, ghi kiểm tra tự thực hiện; chưa đóng claim independent usability review.

**Đầu ra:** robustness matrix, phase measurements, deterministic replay pairs, prioritized performance fixes, browser/manual findings và user-task outcomes. Không chứng nhận hiệu năng chỉ từ một lần load đẹp.

**Điều kiện xong:** đạt các budget/hành trình đã đăng ký; không mất dữ liệu/gap, không false publication, không cần host fallback; không còn blocker correctness, recovery, accessibility hoặc UX ở hành trình bắt buộc. Residual limits phải gắn vào claim cụ thể, không được che bằng một bảng average.

## 7. M9.5 — Campaign cuối, review độc lập và hồ sơ M10

**Mục tiêu:** có một kết luận có thể kiểm tra lại về candidate cuối, không chỉ tổng hợp các test xanh ở nhiều phiên bản khác nhau.

### Công việc

1. **Freeze release candidate.** Ghi exact source/patch digest, packaged artifact, versions, policy và environment. Không sửa code trong lúc campaign đang được tính như một candidate. Sửa sau đó tạo candidate revision và chỉ rerun phần evidence bị vô hiệu cùng integration consumers; các kết quả cùng baseline không bị chạy lại vô cớ.
2. **Chạy bounded final matrix.** Registered corpus theo claim/lane, microfixtures/interactions, mutations, metric/score goldens, robustness, repeated cells và UI journeys. Nếu reuse evidence cũ, phải chứng minh compatibility/result-affecting inputs không đổi; stale artifact không mang sang candidate chỉ vì test name giống nhau.
3. **Clean installation/reproduction.** Từ checkout/output/cache tách biệt, build trusted platform và chạy distributable thực tế. Tái hiện ít nhất một pinned external Java/Spring journey và một deliberate degraded journey, gồm dashboard → finding/penalty → evidence → export; nguồn captured và network prerequisites rõ ràng. Full platform reactor dùng tại milestone gate; native campaign và standalone benchmarks có commands/scope riêng, không được suy ra từ `verify`.
4. **Review độc lập.** Người review khác implementer kiểm protocol/labels, raw-to-summary trace, soundness/trust boundaries, score applicability, failure denominator và primary product journey. Có thể tổ chức reviewer read-only khi được phân công; không thay đổi role của cùng agent rồi gọi là độc lập. Review findings có disposition và check sau sửa.
5. **Đóng hồ sơ M9.** Bảng claim → requirement → case → raw run → derived result → reviewer → limitation. Kết luận PASS / FAIL / NOT RUN / NOT APPLICABLE có căn cứ; case blocked vẫn hiện và không tự tính là pass. Ghi native pack/method còn unqualified. Chỉ tạo formal reproducibility package ở milestone closeout này; tránh raw SDK/VM/graph lớn trong Git.
6. **Cập nhật durable state và trình M10.** Ghi trạng thái M9 theo bằng chứng thực tế; bảo toàn G6 lịch sử. Tên mục tài liệu có thể là “M9 Track A evaluation acceptance” để phân biệt phạm vi, không tự tạo gate mới hoặc đổi số G6. Exact next task là M10 Track A human release review. M11 chỉ được bắt đầu sau quyết định human ở M10.

**Điều kiện xong M9 đề xuất:**

- Các mandatory acceptance cells đã hoàn tất theo protocol; không có missing run bị biến thành positive result.
- Không còn blocking defect đã biết về semantics/evidence, omission, conditional reasoning, graph/metric/score, integrity, recovery hoặc required product journey.
- Nhãn COMPLETE/recovery/exact phù hợp versioned contract và evidence. Không claim mọi repo/ngôn ngữ đạt correctness tuyệt đối từ một corpus hữu hạn.
- Registered correctness, coverage, resource, query/UI performance và usability criteria đạt trong đúng reference environments/scopes.
- Reproduction và independent review có kết quả thật; missing independent review giữ gate pending.
- Handoff đủ cho human đánh giá M10; không tự gọi Track A released hay Track B authorized.

## 8. Cách thực hiện tiết kiệm và kiểm chứng đúng phạm vi

- Mỗi defect bắt đầu bằng một method/class phân biệt red/green; trước handoff chạy bounded affected unit/contract + direct integration consumers, gồm provider/version assertions. Không dùng full reactor trong inner loop.
- Reuse fixed verified SDK trong authored test process; production tool-byte/hash checks vẫn nguyên. Native VM chỉ dùng khi behavior thay đổi không thể chứng minh từ existing captures.
- `test`/`verify` mặc định không chứng minh native campaign đã chạy. `-Pfast-tests` không thay integration handoff; `-Pnative-campaign` cũng không thay ordinary tests. Xem [docs/testing.md](../../testing.md) cho prerequisites và explicit capture properties.
- Commands mẫu dưới đây là **định hướng cho lúc thực thi**, chưa được chạy trong lượt lập kế hoạch; phải kiểm runtime/module dependencies/capture prerequisites trước. Module-only commands dùng dependencies platform hiện hành, không cache artifact cũ rồi claim đang test source mới.

```powershell
# Focused semantic/status control; dùng skip reactorModuleConvergence đúng hướng dẫn docs/testing.md khi cần.
.\mvnw.cmd test -pl backend "-Dtest=SemanticDepthArchitectureTest" -q

# Core metric/policy invariants, không native execution.
.\mvnw.cmd test -pl analyzer "-Dtest=StructuralMetricsTest,ConditionalPathCycleTest,PolicyConditionalEvaluationTest,ConditionalAssessmentTest,AssessmentMetricsIntegrationTest" -q

# Storage integrity/replay.
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=PartitionedGraphStoreTest" -q

# Existing browser model contracts; bổ sung browser/manual journeys riêng.
node --test backend/src/test/js/workbench-model.test.cjs backend/src/test/js/bridge-model.test.cjs

# Milestone closeout only, trong workspace/output phù hợp:
.\mvnw.cmd -B -ntp clean verify
python cli/src/test/python/packaged_jar_smoke.py cli/target/evo.jar
```

- Không tạo `reproducibility/`, hash manifests khổng lồ hoặc raw verification dumps cho từng intermediate slice. Trước final gate chỉ cần protocol/labels, regression fixtures và summary tham chiếu immutable raw runs ngoài Git.
- Không commit/push, xóa evidence, provision hạ tầng tốn phí hay liên hệ reviewer/user thử nghiệm qua dịch vụ bên ngoài nếu chưa được cho phép.

## 9. Traceability của phạm vi M9 trong roadmap

| Yêu cầu M9 | Task sở hữu |
|---|---|
| Preregister corpus, labels, baselines, primary metrics | M9.1 |
| Condition/registration/binding microfixtures và interaction fixtures | M9.2 |
| PetClinic, medium, multi-module, large và polyglot repositories | M9.1 chọn; M9.2/M9.4 kiểm; M9.5 nghiệm thu |
| Partial classpath/configuration experiments | M9.2; tác động assessment ở M9.3 |
| Architecture mutations, expected affected regions | M9.3 |
| Metric golden, score sensitivity/missing evidence | M9.3 |
| Graph/query/UI scale | M9.4 |
| Accessibility/usability của primary workflows | M9.4 |
| Robustness, repeated determinism/performance | M9.4 |
| Immutable raw results, reproducible product, independent review | M9.5; discipline xuyên suốt |

**Cơ sở kỹ thuật bên ngoài đã đối chiếu:** [JDK 21 Trees API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/Trees.html) cung cấp element/type/source-position access để mở rộng javac oracle sẵn có; oracle vẫn cần compile context và diagnostics hợp lệ. [WCAG 2.2](https://www.w3.org/TR/WCAG22/) cung cấp tiêu chí kiểm tra accessibility. Không nguồn nào tự chứng nhận hệ thống đã đạt các tiêu chí đó; corpus size, repetitions và usability sample trong plan là đề xuất của dự án.

## 10. Hồ sơ lượt lập kế hoạch ban đầu (historical)

- Đã đọc đối chiếu contracts, hồ sơ đóng M8, báo cáo hậu cập nhật, code/test status policy, cache integrity boundary và test selection; kiểm Git status/log và phạm vi thay đổi.
- Lượt này chỉ thêm file kế hoạch; chưa sửa production code, chưa đổi ngưỡng/trust policy, chưa chạy benchmark/unit/native/browser acceptance, chưa promote gate.
- **Historical next task at planning:** M9.1 — baseline/claim reconciliation và protocol registration. Task này hiện đã hoàn tất; xem [execution record](01-execution.md). **Exact next task hiện hành: M9.2**, bắt đầu từ preflight frozen inputs và các oracle/negative controls J01/J02. Ngưỡng **95% (`950‰`)** tiếp tục có hiệu lực; không cần hỏi lại hoặc mở worker để làm preflight.
