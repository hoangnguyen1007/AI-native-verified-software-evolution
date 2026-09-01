# SE121 — Roadmap và kế hoạch triển khai dành cho người duyệt

## 1. Kết luận ngắn gọn

Roadmap hiện tại **đủ mạnh, thực tế và có chiều sâu kỹ thuật để bắt đầu triển khai**. Dự án không cần thêm một vòng tái thiết kế tổng thể trước khi làm việc. Điểm mạnh quan trọng nhất là kế hoạch đặt tính đúng đắn, bằng chứng, khả năng tái lập và quản lý bất định lên trước số lượng tính năng.

Tuy nhiên, cần phân biệt hai trạng thái:

- **Setup quản trị dự án đã hoàn thiện về mặt kỹ thuật:** agent, skill, rule, workflow và tài liệu điều hành cốt lõi đã có cấu trúc đầy đủ và đã được kiểm tra cục bộ.
- **Setup nền tảng triển khai chưa hoàn tất:** Maven Wrapper, Java toolchain, Maven Enforcer, cấu trúc module và kiểm chứng build trên hai môi trường là nội dung của M0, chưa được triển khai.

Vì vậy, dự án **sẵn sàng bắt đầu M0**, nhưng chưa sẵn sàng bỏ qua M0 để đi thẳng vào parser, graph, backend hoặc UI.

JavaParser + SymbolSolver hiện chỉ nên giữ trạng thái **PROVISIONAL**. R1 cho thấy công nghệ này còn khả thi, không chứng minh rằng mọi quan hệ ngữ nghĩa trong các repository thực tế đều được xử lý chính xác.

## 2. Trạng thái setup hiện tại

| Hạng mục | Trạng thái | Nhận định |
|---|---|---|
| Hợp đồng điều hành `AGENTS.md` | Hoàn tất kỹ thuật | Đã quy định thẩm quyền, trạng thái tri thức, vòng đời công việc, bằng chứng và an toàn Git |
| 8 vai trò agent | Hoàn tất kỹ thuật | Bao phủ kiến trúc, nghiên cứu, semantic, graph, triển khai, xác minh, red-team và benchmark |
| 5 bộ rules | Hoàn tất kỹ thuật | Bao phủ nguyên tắc agent, chất lượng kỹ thuật, evidence-first, nghiên cứu/kiến trúc và an toàn thay đổi |
| 8 workflows | Hoàn tất kỹ thuật | Có bootstrap, research, architect, implement, execute, review, verify và handoff |
| 4 project skills | Hoàn tất kỹ thuật | Có quy trình engineering, debugging, TDD và verification-before-completion |
| Tài liệu trạng thái lõi | Hoàn tất kỹ thuật | `project-context.md`, `current-state.md`, `roadmap.md` đã phân tách đúng trách nhiệm |
| Roadmap chi tiết cho người duyệt | Hoàn tất | Có bản đầy đủ và bản tiếng Việt này |
| Bằng chứng R1 | Hoàn tất có giới hạn | Đủ để giữ JavaParser ở trạng thái PROVISIONAL; chưa đủ để xác nhận tuyệt đối |
| Duyệt M-1 của con người | Đã hoàn tất | Baseline governance và product outcome đã được chấp thuận |
| Commit M-1 | Đã hoàn tất | Commit `86c4ca29fb747797df3e489d978804644a34f1ce` (`done setup`) |
| Maven/build foundation M0 | Chưa bắt đầu | Đây là công việc triển khai đầu tiên tiếp theo |
| Production analyzer | Chưa bắt đầu | Đúng với thứ tự milestone; không phải thiếu sót setup |

### Có thể bắt đầu công việc ngay chưa?

**Có.** M-1 đã được chấp thuận và commit; M0 đã được phép bắt đầu nhưng vẫn `NOT STARTED`. Công việc đầu tiên phải là nền tảng build tái lập. Việc chưa chốt một số chi tiết kỹ thuật tương lai là có chủ đích: các quyết định đó được đặt sau các cổng bằng chứng phù hợp, tránh khóa kiến trúc quá sớm.

## 3. Vì sao roadmap này đủ mạnh

1. **Evidence-first:** mọi kết luận kiến trúc quan trọng phải truy ngược được repository, snapshot, cấu hình, nguồn, vị trí và trạng thái semantic.
2. **Không che giấu bất định:** unresolved, ambiguous, partial và lỗi phải là dữ liệu hạng nhất, không bị biến thành kết luận chắc chắn.
3. **Kiến trúc module hóa nhưng không phân tán sớm:** modular monolith giúp tách trách nhiệm mà vẫn giữ chi phí vận hành hợp lý.
4. **Multi-module và build model là năng lực lõi:** Maven modules, source roots, dependency management, BOM, scope và classpath được mô hình hóa rõ ràng.
5. **Danh tính phân tích ổn định:** analysis identity dựa trên nội dung và cấu hình, tạo nền cho cache, tái lập và so sánh snapshot.
6. **Không rò rỉ công nghệ:** AST của parser và truy vấn riêng của Neo4j không đi xuyên qua domain boundary.
7. **Query layer ổn định:** CLI, API và UI dùng cùng architecture query services, giảm sai khác hành vi.
8. **Đánh giá liên tục:** ground truth, corpus nhiều repository, negative cases, determinism và benchmark được đưa vào milestone thay vì để cuối dự án.
9. **Track A + B có điều kiện:** sản phẩm kiến trúc hoàn chỉnh được bảo vệ trước khi mở rộng sang architecture evolution.
10. **Có cổng dừng và fallback:** nếu một hướng kỹ thuật không đạt ngưỡng, dự án có cách thu hẹp hoặc thay adapter mà không phá lõi.

Roadmap mạnh không có nghĩa là mọi chi tiết đã được quyết định. Những nội dung như canonical identity, safe Maven model resolution, semantic denominator, ngưỡng benchmark và quyết định Neo4j phải được chốt bằng bằng chứng trong đúng milestone.

## 4. North Star và phạm vi

### Track A — Correctness foundation và sản phẩm hoàn chỉnh

Xây dựng nền tảng có thể:

- hiểu Java và Spring Boot repository;
- tái dựng quan hệ kiến trúc có nguồn gốc và bằng chứng;
- phát hiện vi phạm dependency/architecture policy;
- giải thích đường dẫn, vị trí nguồn và mức độ chắc chắn;
- cung cấp CLI, API và workbench trực quan;
- cung cấp dashboard chi tiết, metrics có định nghĩa, architecture score giải thích được và graph tương tác sạch;
- tái chạy và so sánh kết quả một cách xác định.

### Track B — Architecture evolution

Sau khi các cổng đúng đắn của Track A đạt yêu cầu, hệ thống mới phân tích thay đổi giữa các snapshot tương thích: node/edge delta, thay đổi kiến trúc, regression policy và xu hướng theo thời gian.

### Track C — Moonshot có điều kiện

Incremental analysis, selective re-evaluation, bytecode validation hoặc temporal analysis nâng cao chỉ được làm khi không làm suy yếu hay trì hoãn Track A + B.

### Ngoài phạm vi SE121

- AI diagnosis hoặc graph-guided RAG;
- sinh patch/refactoring tự động;
- OpenRewrite transformation pipeline;
- sandbox verification cho thay đổi do AI sinh;
- CI/CD verified pull request;
- ngôn ngữ lập trình thứ hai.

## 5. Luồng hệ thống mục tiêu

`Repository snapshot → Workspace/build model → Java semantic extraction → Spring enrichment → Canonical graph → Policy/evidence → Query services → CLI/API/UI`

Các hợp đồng xuyên suốt phải giữ:

- repository và snapshot identity;
- analysis configuration và analyzer version;
- caller/source và target/candidate identity;
- source file cùng begin/end line/column;
- semantic status, uncertainty và diagnostics;
- derivation, dependency path và rule provenance;
- manifest đủ để tái lập phân tích.

### Kết quả sản phẩm cuối bắt buộc

Track A phải kết thúc bằng một platform trực quan hoàn chỉnh, không chỉ analyzer hoặc CLI. Giao diện tối thiểu phải có:

1. thiết lập/lịch sử phân tích, tiến độ, hủy job, lỗi và provenance;
2. overview dashboard với số module, package, file, class, interface, enum, record, method, field, relationship, Spring component, endpoint và các trạng thái semantic;
3. analysis confidence tách biệt với architecture health;
4. architecture score 0–100 khi đủ bằng chứng, kèm điểm thành phần, metric đầu vào, lý do cộng/trừ, formula version và trạng thái `COMPLETE`, `PARTIAL`, `WITHHELD` hoặc `NOT_APPLICABLE`;
5. structure explorer từ repository → module → package → type → member;
6. graph tương tác có tìm kiếm, lọc, nhóm, expand/collapse, path/cycle highlighting, legend, giới hạn tải và export;
7. metrics explorer cho coupling, `Ca`, `Ce`, instability, fan-in/fan-out, dependency density, cycle/SCC, boundary crossings, policy và Spring;
8. violations explorer với rule/severity/status, graph path và exact source evidence;
9. Spring components, endpoints, injection candidates, ambiguity/conditional states;
10. bounded impact và Track B snapshot comparison.

Không được trộn analysis confidence vào architecture score. Nếu analyzer thiếu classpath hoặc bỏ sót quan hệ quan trọng, score phải bị giới hạn hoặc không được công bố; thiếu dữ liệu không được làm repository có vẻ tốt hơn.

Chi tiết chuẩn nằm tại [`docs/architecture/product-outcome.md`](docs/architecture/product-outcome.md).

## 6. Roadmap triển khai

### M-1 — Project Operating System Hardening

**Trạng thái:** **HOÀN THÀNH — ĐÃ DUYỆT — ĐÃ COMMIT** tại `86c4ca29fb747797df3e489d978804644a34f1ce` ngày 2026-09-01.

**Mục tiêu:** thiết lập governance để mọi công việc sau có thể kiểm tra và bàn giao.

**Đã có:** hợp đồng `AGENTS.md`, 8 agent roles, 5 rules, 8 workflows, 4 skills và bộ tài liệu trạng thái.

**Cổng thoát:** đã đạt; G-1 đã qua.

### M0 — Reproducible Foundation

**Trạng thái:** **ĐÃ ĐƯỢC PHÉP BẮT ĐẦU, CHƯA TRIỂN KHAI**.

**Mục tiêu:** tạo nền build nhỏ nhất nhưng tái lập được.

**Công việc:**

- chọn cấu trúc Maven module tối thiểu;
- thêm Maven Wrapper và khóa phiên bản plugin quan trọng;
- cấu hình Java toolchain/compiler release nhất quán;
- thêm Maven Enforcer cho Java/Maven/dependency convergence;
- thiết lập unit, integration và architecture test boundaries;
- tạo README với lệnh build sạch;
- kiểm chứng trên môi trường phát triển và môi trường sạch thứ hai.

**Cổng thoát:** clean build/test lặp lại được, không phụ thuộc IDE hoặc trạng thái máy cục bộ không được ghi nhận.

### M1 — Semantic, Identity, Metrics, Assessment và Provenance Contracts

**Mục tiêu:** chốt domain contract trước khi xây analyzer production.

**Công việc:** định nghĩa node/relationship identity, semantic status, evidence span, diagnostics, analysis manifest, deterministic ordering, metric envelope, metric/score version, analysis confidence, score status và compatibility.

**Cổng thoát:** contract tests chứng minh serialization ổn định, complete spans và không biến unresolved thành resolved.

### M2 — Java Semantic Frontend và Ground Truth

**Mục tiêu:** triển khai adapter JavaParser/SymbolSolver sau contract.

**Công việc:** type/method/constructor/field relations, overload, inheritance, interface, collision, unresolved và evaluator tự động.

**Cổng thoát:** đạt ngưỡng coverage và semantic correctness trên ground truth; không dùng “resolve() thành công” làm bằng chứng duy nhất.

### M3 — Multi-Module Workspace và Build Model

**Mục tiêu:** hiểu repository Maven thực tế một cách an toàn.

**Công việc:** module graph, source roots, scopes, dependency management, BOM, classpath manifest và failure diagnostics; không chạy lifecycle tùy ý của repository không tin cậy.

**Cổng thoát:** fixtures multi-module và corpus thực tế được phân tích tái lập với exact classpath.

### M4 — Spring Semantic Intelligence

**Mục tiêu:** bổ sung ngữ nghĩa Spring có bằng chứng.

**Công việc:** stereotypes, component identity, constructor/field injection, bean candidates, configuration, endpoints và uncertainty khi wiring không duy nhất.

**Cổng thoát:** positive/negative/ambiguous fixtures và ít nhất một repository Spring thực tế đạt tiêu chí đã công bố.

### M5 — Canonical Graph, Metrics và Architecture Query Layer

**Mục tiêu:** chuyển semantic facts thành graph ổn định, độc lập storage.

**Công việc:** graph schema/version, invariants, deterministic import/export, focused projections, metric engine, inventory/structural metrics, traversal/query services và đánh giá Neo4j sau khi query contract rõ ràng.

**Cổng thoát:** không có AST/storage query rò rỉ qua domain; graph invariants và query contract tests đạt.

### M6 — Policy, Evidence và Explainable Architecture Assessment

**Mục tiêu:** phát hiện vi phạm kiến trúc có thể giải thích.

**Công việc:** layer/package/module dependency rules, cycles, forbidden edges, rule versioning, path evidence, suppression, uncertain findings, policy metrics và architecture score có version/breakdown/evidence. Trọng số chỉ được chốt sau golden cases và sensitivity analysis.

**Cổng thoát:** architecture-mutation fixtures chứng minh true positive, true negative và chống regression.

### M7 — Impact, CLI và Interoperability

**Mục tiêu:** cung cấp giá trị sử dụng sớm, không vượt quá bằng chứng.

**Công việc:** impact analysis có giới hạn, CLI ổn định, JSON/export schema cho inventory, metrics, score explanation, violations, provenance và scriptable workflows.

**Cổng thoát:** cùng một snapshot/config tạo output xác định và có mã lỗi rõ ràng.

### M8 — Backend API và Complete Architecture Workbench

**Mục tiêu:** đưa năng lực lõi ra sản phẩm sử dụng được.

**Công việc:** API trên query layer; analysis history/progress; overview dashboard; structure explorer; focused graph; metrics/score explorer; violations và source evidence; Spring/endpoint/injection views; impact; provenance; đầy đủ loading/empty/partial/error/canceled/oversized states; accessibility và performance budgets.

**Cổng thoát:** người dùng hoàn tất luồng analyze → dashboard → metric/score/violation/graph → exact source evidence; partial evidence được hiển thị trung thực; CLI/API/export/UI nhất quán và không truy vấn storage tùy tiện.

### M9 — Multi-Repository Evaluation và Hardening

**Mục tiêu:** kiểm tra khả năng tổng quát hóa ngoài PetClinic.

**Công việc:** corpus đa dạng về quy mô, module, Spring style và failure mode; đo coverage, correctness, inventory/metric accuracy, score sensitivity/missing-evidence behavior, graph/query/UI scale, accessibility, runtime, memory, determinism và provenance completeness.

**Cổng thoát:** báo cáo công khai denominator, omissions, limitations và raw evidence.

### M10 — Track A Release Gate

**Mục tiêu:** quyết định Track A có đủ tin cậy để đóng gói hay không.

**Công việc:** full clean build, end-to-end product scenarios, dashboard/score/graph/violation acceptance, security/accessibility/reproducibility review, operator documentation và adversarial audit. Track A không đạt nếu chỉ có analyzer hoặc CLI.

**Cổng thoát:** con người duyệt release candidate hoặc yêu cầu sửa có mục tiêu.

### M11 — Track B Architecture Evolution

**Mục tiêu:** phân tích kiến trúc giữa các snapshot tương thích.

**Công việc:** compatibility rules, graph/metric/score delta, added/removed/changed facts, policy regression và evidence liên snapshot; từ chối hoặc cảnh báo so sánh khác analyzer/config/policy/formula version.

**Cổng thoát:** fixtures lịch sử và repository thật chứng minh delta chính xác, không nhầm đổi identity với thay đổi kiến trúc.

### M12 — Technical Integration và Final Reproducibility

**Mục tiêu:** tích hợp Track A + B thành kết quả cuối có thể tái tạo.

**Công việc:** benchmark cuối, demo dashboard/metrics/score/graph/violations/evidence/impact/evolution, packaged artifacts, limitations, architecture documentation và reproduction guide.

**Cổng thoát:** người độc lập có thể dựng, chạy và kiểm tra kết quả từ trạng thái sạch.

## 7. Lịch dự kiến 24 tuần

| Giai đoạn | Tuần dự kiến | Trọng tâm |
|---|---:|---|
| M-1 | Trước tuần 1 | Governance và duyệt baseline |
| M0–M1 | 1–3 | Build tái lập và semantic contracts |
| M2–M3 | 4–8 | Java frontend, ground truth, multi-module/build model |
| M4 | 9–11 | Spring semantics |
| M5–M6 | 12–16 | Graph/query layer và policy engine |
| M7–M8 | 17–19 | CLI, impact, API và workbench |
| M9–M10 | 20–22 | External evaluation và Track A gate |
| M11–M12 | 23–24 | Evolution có giới hạn và tích hợp cuối |

Lịch này là khung quản lý, không phải cam kết cứng. Correctness gate có quyền kéo dài hoặc thu hẹp phạm vi phía sau.

## 8. Các cổng quyết định bắt buộc

| Cổng | Quyết định |
|---|---|
| G-1 — ĐÃ ĐẠT | Con người đã chấp thuận và commit M-1 governance baseline tại `86c4ca2` |
| G-2 | M0 build tái lập trên hai môi trường |
| G-3 | Semantic/identity/provenance contracts đủ ổn định |
| G-4 | JavaParser đạt ngưỡng R1 mở rộng và được phép dùng production ở trạng thái PROVISIONAL |
| G-5 | Build model và Spring semantics đạt corpus/ground-truth gates |
| G-6 | Graph/query contracts đủ rõ để quyết định storage adapter, gồm Neo4j |
| G-7 | Track A đạt release gate và được con người duyệt |
| G-8 | Chỉ khi G-7 đạt mới mở Track B; Track C cần phê duyệt riêng |

## 9. Chiến lược kiểm chứng

Mỗi milestone phải kiểm tra theo mức rủi ro tương ứng:

- unit tests cho thuật toán và domain behavior;
- contract tests cho identity, serialization, query và adapter boundaries;
- integration tests cho parser, build model, graph và API;
- ground truth cho semantic correctness, gồm incorrect/unresolved/omitted;
- architecture tests chống rò rỉ AST hoặc storage-specific logic;
- clean-room reproduction và exact manifest;
- adversarial review cho quyết định có ảnh hưởng lớn;
- corpus bên ngoài để chống overfitting vào PetClinic.

Không dùng một phần trăm resolution đơn lẻ để tuyên bố semantic accuracy. Báo cáo phải tách attempted, correctly resolved, incorrectly resolved, unresolved và omitted.

## 10. Những giới hạn và câu hỏi còn mở

Các điểm sau chưa được chốt, nhưng **không ngăn bắt đầu M0**:

- cấu trúc module Maven chính xác;
- cách dựng Maven model/classpath an toàn cho repository không tin cậy;
- quy tắc canonical identity cho overload, nested/local/anonymous/generated elements;
- denominator chính thức của từng benchmark semantic;
- corpus và ngưỡng chấp nhận cho Java/Spring;
- thời điểm và điều kiện dùng Neo4j;
- metric catalog baseline, công thức score, trọng số, caps và ngưỡng withholding;
- performance budgets cho graph/query/UI và lựa chọn frontend/design system;
- phạm vi impact analysis và snapshot compatibility của Track B.

Các quyết định này phải được giải quyết ở M0–M6 bằng prototype, test và bằng chứng, không bằng phỏng đoán.

## 11. Kế hoạch bắt đầu ngay

1. M-1 đã được duyệt và commit sạch tại `86c4ca29fb747797df3e489d978804644a34f1ce`.
2. Bắt đầu M0 bằng việc kiểm kê JDK/Maven hiện có và chọn cấu trúc module tối thiểu.
3. Thêm Maven Wrapper, toolchain/compiler release, Enforcer và test boundaries.
4. Chạy clean build trên máy hiện tại và môi trường sạch thứ hai; ghi exact command/version.
5. Chỉ sau khi cổng G0 đạt mới chuyển sang M1 contracts.

Không nên bắt đầu bằng Neo4j, UI hoặc analyzer production. Những phần đó phụ thuộc vào contract và correctness gates phía trước.

## 12. Quyết định con người cần đưa ra

- [x] Chấp thuận hướng **Track A + Track B**, Track C là tùy chọn có điều kiện.
- [x] Chấp thuận M-1 là governance baseline của dự án.
- [x] M-1 đã được commit riêng biệt tại `86c4ca29fb747797df3e489d978804644a34f1ce`.
- [x] Cho phép bắt đầu M0 Reproducible Foundation.
- [x] Giữ JavaParser + SymbolSolver ở trạng thái **PROVISIONAL** cho tới cổng parser tương ứng.
- [ ] Không xác nhận Neo4j trước khi query contract và benchmark ở G-6 hoàn tất.
- [x] Kết quả cuối là visual platform hoàn chỉnh với dashboard chi tiết, explainable architecture score, violations, focused graph và evidence navigation.

## 13. Kết luận cuối

Roadmap hiện tại đã bao phủ đủ các yếu tố để xây một hệ thống kỹ thuật cao nhưng vẫn thực tế: correctness, provenance, uncertainty, safe build modeling, semantic intelligence, Spring, graph, policy, product interface, benchmark, reproducibility và evolution.

**Khuyến nghị:** giữ commit `86c4ca29fb747797df3e489d978804644a34f1ce` làm baseline M-1 và bắt đầu M0 bằng một nhiệm vụ foundation có giới hạn. Không cần tiếp tục mở rộng roadmap ở thời điểm này; giá trị tiếp theo phải đến từ implementation nhỏ, test được và bằng chứng thực nghiệm. Setup quản trị đã hoàn tất, còn setup kỹ thuật của sản phẩm chính là nhiệm vụ M0 kế tiếp.
