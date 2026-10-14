# M5 — Kế hoạch triển khai cho GPT-6.1 sol

Ngày lập: 2026-10-14. Vai trò lập kế hoạch: Lead Architect + Graph Architect.

**CONFIRMED — kiểm tra repository:** HEAD tại lúc lập kế hoạch là `046440c`, working tree sạch; [current state](../../current-state.md) và [G3 record](../../reproducibility/g3-spring-adjudication-record-2026-10-14.md) ghi M4 hoàn tất, G3 PASSED, M5 tiếp theo. Đây là trạng thái được ghi nhận, không phải lần tái kiểm chứng G3. G4 chưa bắt đầu.

**PROVISIONAL:** đây là baseline kế hoạch được lập trước implementation; các phần M5.2/M5.3, metric catalog, hiệu năng và G4 chưa được chứng nhận. Trạng thái implementation M5.1 được cập nhật ngay bên dưới.

**Cập nhật triển khai 2026-10-14:** M5.1 đã hoàn tất theo [contract graph/persistence](../../architecture/m5-canonical-graph.md), với 85 bounded tests pass, zero failures/errors/skips. Đoạn lập kế hoạch phía trên là trạng thái tại thời điểm tạo tài liệu; bước tiếp theo hiện tại là M5.2. M5.2/M5.3 và G4 chưa hoàn tất.

## Đúng ba task, thực hiện tuần tự

| Task | Kết quả phải sử dụng được | Phụ thuộc |
|---|---|---|
| [M5.1 — Canonical graph và persistence](01-canonical-graph-and-persistence.md) | Facts đầy đủ → graph có identity/provenance → ghi/đọc lại không mất nghĩa | M1–M4 hiện có |
| [M5.2 — Conditional projections và query](02-conditional-projections-and-queries.md) | Truy vấn có giới hạn; path/cycle có cấu hình khả thi và evidence | M5.1 |
| [M5.3 — Metrics, integration và G4](03-metrics-integration-and-g4.md) | Metric giải thích được; pipeline → store → query → metric; bộ bằng chứng G4 | M5.1 + M5.2 |

Checklist trong từng file là bước nội bộ, không phải task mới. Không giao ba task song song vì cùng phụ thuộc contract. Mỗi task phải hoàn thành chức năng và kiểm chứng, không kết thúc bằng interface rỗng hoặc kế hoạch tiếp theo.

## Nguồn chuẩn và những bẫy đã kiểm tra

Đọc [knowledge-graph](../../architecture/knowledge-graph.md), [product-outcome](../../architecture/product-outcome.md), [M1](../../architecture/m1-contracts.md), [conditional semantics](../../architecture/conditional-architecture-semantics.md), [M4D](../../architecture/m4d-truth-regions-witnesses.md), [ADR-002](../../decisions/ADR-002-product-outcome-and-explainable-assessment.md), [ADR-004](../../decisions/ADR-004-staged-conditional-architecture-semantics.md). Khi triển khai, đọc thêm source/test liên quan; không tái đọc toàn bộ lịch sử mỗi vòng TDD.

| Phát hiện từ tài liệu/code | Cách xử lý bắt buộc |
|---|---|
| Phác thảo Package/Class/Field identity theo FQN trong knowledge-graph là `HYPOTHESIS` | M1 typed scope/origin/module identity có ưu tiên; FQN chỉ là thuộc tính tìm kiếm |
| Sơ đồ `Class → CALLS → Method` trong R2 không khớp cạnh CALLS khai báo Method → Method | Giữ CALLS đúng endpoint; đường từ class đi qua DECLARES, hoặc cạnh type dependency dẫn xuất có support |
| R2 ghi “3 rules” nhưng liệt kê 4 tình huống | Kiểm chứng đủ 4 tình huống; đây là graph/query use cases, chưa phải policy engine |
| [UniversalArchitectureReport](../../../analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalArchitectureReport.java) giữ DependencyRecord dạng chuỗi và TruthRegionSummary rút gọn | Không tái tạo canonical facts/regions/witness từ report; M5.1 nối dữ liệu đầy đủ trước bước tóm tắt |
| [MetricEnvelope](../../../analyzer/src/main/java/com/evolution/analysis/contract/metrics/MetricEnvelope.java) chưa có configuration-space/projection context | Bổ sung wrapper versioned tương thích; không đổi ngầm preimage M1 hoặc giả realized configuration |
| M4 có explicit-world và symbolic region output | Giữ encoding/context/formula references; không Cartesian-expand symbolic space để dựng graph |
| README/roadmap và các đoạn lịch sử current-state còn “G3 pending/V2 next” | Lấy trạng thái mới nhất và EXACT NEXT TASK làm điểm vào; không sao chép chỉ dẫn lịch sử vào task M5 |

## Ranh giới và lựa chọn thiết kế

Luồng giữ nguyên: acquisition/build → frontend → Spring → canonical graph → query/metrics → presentation. Core graph/query/metric đặt trong `analyzer`; I/O persistence qua port, adapter file dự kiến ở `analyzer-filesystem`. Tên class/test mới trong kế hoạch đều là **đề xuất**, không phải API đã tồn tại.

Baseline triển khai đề xuất là Pure Java immutable in-memory graph, adjacency indexes và architecture query service trong `analyzer`, cộng canonical JSON snapshot versioned qua file adapter. Core và inner TDD không phụ thuộc Neo4j driver/server, database process hoặc container. G4 persistence round-trip phải chạy độc lập bằng serializer/deserializer và file store. Neo4j chỉ là optional secondary adapter ở module riêng nếu sau này có workload/evidence biện minh; không là điều kiện để hoàn tất M5. Không tự chọn JanusGraph/distributed stack.

### Kết quả rà soát góp ý bổ sung

**PROVISIONAL — lựa chọn kỹ thuật sau rà soát (2026-10-14):** nhận hướng bảo vệ tài nguyên, Pure Java core, Martin counting và joint-region pruning. M5.2 bổ sung hard caps depth 20 / 100.000 traversal steps / 50 witness cycles, budget toàn request và boundary tests. Đây là defensive limits để kiểm chứng, chưa là hiệu năng đã đo hoặc catalog được human-approved.

Điều chỉnh ba điểm để giữ đúng nghĩa: witness list có thể rỗng khi hết budget trước witness đầu tiên nhưng response phải PARTIAL và có frontier/reason; `workBudgetExceeded` chỉ phản ánh work/depth exhaustion, quota có `outputBudgetExceeded`; hai profile `dev`/`prod` không tự loại trừ nhau. GraphML chỉ đủ làm round-trip evidence nếu giữ toàn bộ canonical payload; baseline dùng canonical JSON, không dùng export visualization có mất dữ liệu.

M5 sở hữu inventory, evidence-quality counters, structural/Spring metrics và query primitive cho sản phẩm. Policy/violation engine, score/weights thuộc M6; CLI/export sản phẩm thuộc M7; REST/jobs/UI thuộc M8; so sánh delta Track B thuộc M11. M5 chỉ chuẩn bị compatibility metadata, không triển khai lịch sử/violation lifecycle. Layer query chỉ dùng mapping được cung cấp và versioned, không đoán layer bằng tên package.

**Quyết định cần phân biệt:** nội bộ reversible được tự quyết; thay canonical identity/schema không tương thích phải có impact/migration decision. Theo ADR-002, metric catalog baseline và numerical query/UI performance budgets còn cần human approval trước khi được gọi là CONFIRMED. Có thể triển khai, kiểm thử và trình catalog cụ thể dưới nhãn PROVISIONAL; không dừng công việc độc lập chỉ vì điểm duyệt cuối chưa xảy ra.

## Quy tắc triển khai và kiểm chứng chung

- Mỗi output giữ derivation, semantic status, provenance/gaps, truth classification và operational outcome riêng biệt. Không tự thêm `UNKNOWN` vào DerivationKind hoặc `TRUNCATED` vào AssessmentStatus.
- SHA-256/canonical ordering; giữ classpath và material registration order. Không đổi identity theo locale, host path, wall clock hoặc thứ tự input không có nghĩa.
- Một input outcome phải có projection hoặc disposition/gap giải thích được; không xóa unresolved/candidate/NEVER/unknown khỏi denominator. Filter hợp lệ có metadata, không tự trở thành capability defect.
- Pipeline không chạy Maven/Gradle/plugin/application của repository mục tiêu. Chỉ build/test chính platform và authored fixtures.
- Inner loop: một method/class nhỏ, quiet; mục tiêu <5 giây và <20 dòng diagnostic, báo thời gian thực nếu vượt. Handoff chạy bounded unit/contract và direct consumers ở mọi module bị ảnh hưởng.
- Không full reactor/real-repository benchmark hoặc tạo gói reproducibility cho intermediate slice. Một campaign cuối G4 chỉ sau unit/contract xanh, preregistration và authorization phù hợp. Không tái chạy corpus G3 để “làm chắc”.
- Không commit/push, tạo chat, automation hoặc sửa raw evidence. Self-review không phải independent review.

Lệnh mẫu tại root, PowerShell, sau khi xác nhận JDK 21:

```powershell
.\mvnw.cmd --version
.\mvnw.cmd test -pl analyzer "-Dtest=CanonicalGraphContractTest#preservesSameNameAcrossModules" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Chỉ skip `reactorModuleConvergence` cho invocation một module như các slice hiện tại; giữ các Enforcer rule khác. Không dùng `-am` để vô tình chạy cả reactor, không dùng `failIfNoTests=false` để biến filter sai thành pass. Tên suite mới được ghi trong từng task; thay filter bằng tên thực sau khi tạo test.

Khi adapter cần core mới, refresh đúng dependency đã build/test, không dựa vào SNAPSHOT cũ. Với POM hiện tại có thể dùng `package install -pl analyzer -DskipTests` sau bounded test core; đây là packaging, không được báo thành test pass. Nếu thay upstream khác, refresh đúng tập dependency đó. Không mặc định dùng workaround `javac` từ lịch sử.

## Exit và handoff chung

Mỗi task bàn giao: files changed, command thật + count/fail/error/skip + thời gian, limitation, exact next task. Cập nhật `docs/current-state.md` khi chức năng đã đổi; không đánh dấu G4 PASSED chỉ vì ba checklist xong. M5.3 cung cấp ma trận evidence để review gate, ghi riêng phần chưa được kiểm chứng/duyệt.

Prompt giao việc dùng cho từng lần:

> Thực hiện trọn vẹn task M5.N tại `docs/tasks/m5/0N-...md`, tuân thủ `AGENTS.md` và README M5. Đọc source/test trước khi sửa; làm TDD và tự sửa lỗi trong phạm vi; giữ canonical identity, conditional semantics và closed denominator. Không tự chuyển sang task sau. Kết thúc bằng evidence kiểm chứng và trạng thái còn thiếu, không chỉ báo đã viết code.

**Bước tiếp theo hiện tại:** giao M5.2; sử dụng graph contexts tách repository/Java analysis/Spring evaluation đã kiểm chứng, bắt đầu với prefix-region intersection và infeasible-path controls.
