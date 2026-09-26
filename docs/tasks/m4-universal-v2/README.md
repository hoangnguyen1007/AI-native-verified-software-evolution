# M4 Universal v2 — kế hoạch 3 task

Thiết kế từ 2026-09-23; hoàn thiện 2026-09-26. **CONFIRMED — yêu cầu chủ dự án:** thêm M4 Universal v2; Java/Spring toàn diện, kiểm kê và thể hiện ranh giới phần đa ngôn ngữ; ít task nhưng giữ chất lượng. **PROVISIONAL — kế hoạch kỹ thuật:** chưa implement, chưa benchmark, G3 chưa passed.

## Mục tiêu và ranh giới

Repo đi qua pipeline có kiểm kê, thu thập bằng chứng, phân tích, recovery và báo cáo kiến trúc. Trở ngại build/config/generated code được chủ động xử lý trong quyền đã cấp. UNKNOWN còn lại gắn câu hỏi cụ thể, nguyên nhân gốc, phạm vi ảnh hưởng và các provider đã thử. Giảm UNKNOWN bằng evidence đúng, không bằng xóa row, gom sai context hoặc đoán target.

Giữ JavaParser primary, M1 identity, exact M3 contexts, M4B/C/D conditional semantics, immutable evidence và an toàn không thực thi target. Xem [hợp đồng v2](../../architecture/m4-universal-v2.md), [coverage catalog](../../architecture/m4-universal-v2-coverage.md), [protocol đo](../../research/m4-universal-v2-evaluation.md), [ADR-005](../../decisions/ADR-005-m4-universal-v2.md).

## Chỉ ba task được giao và theo dõi

| Task | Kết quả bàn giao | Phụ thuộc | File chi tiết |
|---|---|---|---|
| **V2.1 — Adaptive intake and evidence closure** | Repo đa hình dạng -> contexts, exact/safely partial inputs, acquired artifacts, active evidence coordinator, recovery checkpoints | Universal v1 delivered; bootstrap hiện trạng | [Task 1](01-adaptive-intake-and-evidence.md) |
| **V2.2 — Java/Spring semantic closure** | Inputs -> resolved Java + generated symbols + config + Spring descriptors -> ordered bindings/conditional architecture | V2.1 contracts và evidence outputs | [Task 2](02-java-spring-semantic-closure.md) |
| **V2.3 — Integrated architecture and acceptance** | Một đường input-to-report, uncertainty deltas, fault recovery, paired evidence package chuyển M4E/G3 | V2.1 và V2.2 controls xanh | [Task 3](03-integration-and-acceptance.md) |

```text
Delivered M4A–D + Universal v1
  -> V2.1 intake / build / evidence / coordinator
  -> V2.2 Java / Spring / config / reasoning
  -> V2.3 integration / acceptance evidence
  -> M4E adjudication -> G3
  -> M5–M10 Track A -> human approval -> Track B
```

V2.1 và V2.2 là hai task triển khai lớn. V2.3 tích hợp, sửa lỗi trong phạm vi và đóng gói nghiệm thu; không tạo thêm task riêng cho từng parser, framework hoặc fixture. Checklist bên trong từng file là điểm kiểm chứng; chỉ ba task được giao, theo dõi và nghiệm thu.

## Cách ít task vẫn giữ chất lượng

- Mỗi task sở hữu một kết quả end-to-end có thể dùng; interface rỗng không là delivery.
- Một coverage catalog chung với stable case IDs và producer/consumer owners; không sao chép backlog.
- Mỗi thay đổi hành vi có regression/specification test nhỏ, gồm negative/partial/conflicting cases.
- Checkpoint nội bộ giữ thay đổi reviewable và cho phép tiếp tục khi hết context; không cần task mới hay approval cho lựa chọn routine.
- Regression trong phạm vi phải sửa trước done; scope/security/schema break thực sự mới đưa ra quyết định riêng.
- Khi tiếp tục phiên làm việc, dùng checklist trạng thái và output/test artifacts; không đánh dấu done vì hết một phiên.

## Coverage ownership

V2.1 sở hữu R01–R12, B01–B18 và phần acquisition/lineage của J06–J12, S02/S12/S22, Q03/Q04/Q06. V2.2 sở hữu J01–J12, S01–S23, Q01/Q02/Q07. V2.3 sở hữu tích hợp và acceptance toàn catalog, đặc biệt Q05/Q08. Các phần dùng chung giữ producer/consumer boundary, không viết trùng abstraction.

“Mọi Java version/framework/build” là chiều cần account và mở rộng provider, không là giấy phép đánh dấu hỗ trợ bằng catch-all gap. Fragment công bố supported phải có positive control; evidence-import lanes phải thực sự resolve trên valid bundles. Residuals vẫn làm giảm coverage của cohort tương ứng.

## Định nghĩa done chung

| Điều kiện | Bằng chứng |
|---|---|
| Chức năng có ích | Entry point chạy từ input thật trên compact fixture, không chỉ object dựng tay ở cuối pipeline |
| Không certainty giả | Missing/corrupt/stale/conflicting inputs, order/version/ownership negative controls |
| Denominator khép kín | Inventory + obligations + gaps/frontiers reconciliation |
| Coverage tiến bộ | Same-case before/after, exact targets/affected outputs; legitimate unknowns giải thích rõ |
| Bảo toàn source/context | SHA-256, origins, full spans, classpath order, no cross-context merge |
| Recovery | Unit/stage/worker failure, retained outputs và captured-input replay |
| Durable truth | Current-state/catalog implementation status đúng; targets không gọi là measurements |

Schema/envelopes additive hoặc có migration decision cụ thể. Exact manifest validation không bị nới để “nuốt repo”. Structural lane có trạng thái riêng; imported evidence có trust/lineage riêng. UNKNOWN hợp lệ không là defect, nhưng bỏ qua permitted provider áp dụng được là defect của coordinator.

## Kiểm chứng và cách bắt đầu

Trước mỗi điểm triển khai đọc sources/tests và tạo failing compact control. Trong coding loop chỉ chạy một targeted test class, quiet mode theo AGENTS; báo cáo test runtime riêng với Maven startup. Không chạy full reactor/real-repo benchmark lặp lại. Broader integration chỉ ở handoff thực sự; một registered corpus campaign tại gate cuối.

Tên test mới trong task files là proposed, chưa tồn tại. Chọn module/command sau khi xem POM; không gọi zero tests là pass. Không tạo thêm task Codex, automation, commit hoặc push trong lần cập nhật tài liệu này.

**Exact next task:** mở [V2.1](01-adaptive-intake-and-evidence.md), xác nhận additive contracts và tạo regression cho một module build lỗi làm ảnh hưởng module độc lập. Freeze denominator/acceptance trên compact fixtures trước scoring; tiếp tục V2.1 đến exit, không dừng ở việc tạo thêm plan.
