# M4-UNIVERSAL Contract: Universal Multi-Repository & Spring Architecture Intelligence Engine

**Status: human-approved Universal v1 direction (2026-09-18), refined by the owner's M4 Universal v2 request (2026-09-23; compact plan finalized 2026-09-26).** The current implementation plan is [Universal v2](m4-universal-v2.md), with [three task files](../tasks/m4-universal-v2/README.md). This document preserves the two-slice v1 capability direction; exact implementation boundaries are in the slice contracts below. Global coverage and latency aspirations are not measured results.

Implementation evidence: [Slice 1](m4u1-passive-ingestion.md) and
[Slice 2](m4u2-universal-spring.md) record the delivered providers, executable
boundaries and remaining M4E/G3 acceptance obligations. These records do not turn
the coverage or latency targets below into measured results.

---

## 1. Tầm nhìn & Nguyên lý Đặc tả Năng lực (Capability-Driven Philosophy)

Nền tảng hướng tới độ bao quát Java/Spring cao trên repo sinh viên, doanh nghiệp, legacy và hiện đại. **Mục tiêu 98–99% phải được đo trên corpus/cohort đã đăng ký**, không được công bố là độ bao quát mọi repo thế giới:
- Đăng ký các thế hệ Java legacy/modern trong coverage matrix, mở rộng provider bằng exact syntax/platform controls; phiên bản ngoài fragment đã verify vẫn có intake và evidence requirements.
- Bao quát các thế hệ Spring bằng version/mechanism packs có oracle, không coi một version tuple là bằng chứng cho toàn bộ lịch sử framework.
- Tương thích với mọi kiểu tổ chức dự án: Java thuần không build tool, dự án đơn module, đa module Maven (kế thừa POM, BOMs, dependency management) và dự án sử dụng **Gradle** (`build.gradle` Groovy DSL và `build.gradle.kts` Kotlin DSL).
- Giải mã tự nhiên các cấu trúc sinh mã và dynamic framework phổ biến nhất thế giới (Lombok, Spring Data, Records, Sealed types, Web Routes).
- Giảm chi phí duyệt cấu hình bằng SAT Boolean thuần Java trên finite abstractions đã kiểm chứng; giữ residual unknown khi vượt giới hạn.

### Nguyên tắc Bất biến về Quyền Tự chủ của Mô hình Suy luận (Autonomous Reasoning Mandate)
> [!IMPORTANT]
> Tài liệu kiến trúc này **chỉ xác định các năng lực và kết quả bắt buộc phải bao quát (Required Outcomes & Capabilities)** cùng các bất biến an toàn cốt lõi.
> **Tuyệt đối không ra lệnh hay chỉ đạo vi mô từng bước thuật toán**; tạo không gian tự do tối đa để các mô hình suy luận cao (Reasoning Models) tự tư duy, thiết kế giải thuật tối ưu, cấu trúc dữ liệu và cơ chế giải mã linh hoạt phù hợp với thực tế repository.

---

## 2. Bảng Năng lực & Kết quả Bắt buộc Phải Bao quát

| Chiều không gian | Năng lực & Kết quả Bắt buộc Phải Bao quát |
|---|---|
| **Cú pháp, API và generated code** | Bao quát Java legacy/modern bằng provider/version controls; syntax khác platform API support. Lombok/record synthesis cần đủ version/config/member evidence; generated imports giúp mở rộng phần chưa thể synthesize đúng. Virtual-thread APIs không được đánh đồng với cú pháp parser. |
| **Hệ thống Build** | Passive Maven/Gradle/plain Java và legacy/custom build evidence; literal declarations khác exact resolved classpath. Dynamic build remainder dùng qualified source-plan/resolved-model imports, không execute target hoặc bịa GAV. |
| **Cấu hình Tự động** | Repository properties/YAML/profile/import closure tự động, có precedence/origin. External deployment sources cần explicit evidence; không hứa zero setup khi input không tồn tại trong repo. |
| **Quét Component Tự động** | Tự động phát hiện base package từ `@SpringBootApplication` và `@ComponentScan` để quét toàn bộ cây thư mục mã nguồn, phát hiện đầy đủ mọi Stereotype (`@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Configuration`). |
| **Cơ chế Sinh Bean Động (Spring Data)** | Mô hình hóa interface/store/scan/enablement/fragments thành candidates có provenance; registration/activation/descriptor proof mới cho selected binding. Không xóa legitimate missing/ambiguous dependency để tăng coverage. |
| **Đa Thế hệ Framework** | Namespace/resource/override rules theo exact version pack; legacy XML và modern annotations có oracle. Namespace compatibility không chứng minh historical-container equivalence. |
| **Bộ giải Logic SAT hữu hạn** | Pure-Java DPLL đã có sau `ConfigurationReasoner`; symbolic path tránh Cartesian enumeration cho fragment phù hợp. SAT/signature/witness budgets vẫn hữu hạn; không hứa hết mọi gap hoặc mili-giây worst case. |
| **Kiến trúc Luồng Web API & Nâng cao** | Trích xuất các HTTP endpoints (`@RequestMapping`, `@GetMapping`, `@PostMapping`, v.v.) ánh xạ thành chuỗi liên kết kiến trúc: HTTP Route -> Controller -> Service -> Repository. Hỗ trợ mô hình context phân cấp cha-con và đánh giá SpEL cơ bản cho `@ConditionalOnExpression`. |

---

## 3. Hai implementation slices của Universal v1

Universal v1 đã được chia thành hai implementation slices dưới đây. Universal v2 là task mới với hai task triển khai và một task tích hợp/nghiệm thu; không coi giới hạn v1 là cấm các task v2 chi tiết.

### SLICE 1: Universal Source, Build & Configuration Ingestion
1. **Lombok & Record Preprocessor (`analyzer-javaparser`):** Mở rộng bộ trích xuất implicit để nhận diện `@RequiredArgsConstructor`, `@AllArgsConstructor`, `@NoArgsConstructor`, `@Data`, `@Value`, `@Builder` và Record Canonical Constructors; sinh các thực thể Constructor, Parameter và TypeUseRecord tương ứng với kiểu trường phục vụ Dependency Injection.
2. **Universal Gradle Build Model Adapter (`analyzer`):** Passive declarative projection vào `UniversalBuildModel`; existing Maven `BuildModelResult` giữ nguyên. Declaration coverage không chứng minh resolved artifacts, variants hoặc classpath order.
3. **Auto Configuration & Resource Reader (`analyzer`):** Phân tích native `application.properties` và `application.yml`/`.yaml` (kèm profile cascading) thành `ConfigurationAssignment` tự động.
4. **Auto Component Scanner (`analyzer`):** Phát hiện base package từ `@SpringBootApplication` và thu nhận toàn bộ component stereotypes hợp lệ trong phạm vi package.

### SLICE 2: Universal Spring Semantics, Dynamic Frameworks & SAT Reasoning
1. **Spring Data Repository Evidence:** Interface ancestry cùng scan/store/enablement evidence tạo qualified candidate; selected binding đi qua M4C với đủ descriptors và order.
2. **Cross-Generation Dual Namespace Engine:** Hỗ trợ song song `javax.*` và `jakarta.*`, đọc cả `spring.factories` và `.imports`, cấu hình bean override policy theo thời kỳ framework.
3. **Pure-Java SAT Solver Engine:** DPLL/CNF sau `ConfigurationReasoner`, finite supported formulas và explicit budgets; không cần native C++ hoặc mạng. Infinite domains cần sound finite abstraction hoặc giữ UNKNOWN.
4. **Web API Route Mapper & Hierarchical Contexts:** Ánh xạ các annotation Web Controller thành các endpoint kiến trúc; mô hình hóa context phân cấp cha-con và bộ đánh giá SpEL cơ bản.

---

## 4. Bất biến Cốt lõi Không Thương lượng (Core Invariants)

Bất kỳ mô hình hay agent nào thực thi các slices này đều phải tuân thủ 4 trụ cột bất biến:
1. **An toàn Thực thi & Sandbox Tuyệt đối:** Không bao giờ chạy lệnh build Maven/Gradle hay plugin của repository đối tượng. Mã nguồn repository đối tượng là dữ liệu đầu vào thuần túy, không có quyền thực thi.
2. **Bằng chứng Cấp một & Zero Hallucination:** Mọi định danh thực thể, quan hệ, kết quả phân tích đều phải có địa chỉ nội dung (SHA-256), có nguồn gốc (provenance) rõ ràng.
3. **Mẫu số Báo cáo Khép kín (Closed Reporting Denominator):** Không bao giờ âm thầm bỏ qua các trường hợp suy thoái hoặc chưa xử lý được. Bất kỳ giới hạn nào đều phải được ghi nhận thành `CapabilityGapRecord` chuẩn tắc.
4. **Tái hiện Xác định (Deterministic Replay):** Cùng captured input closure, context, provider/policy versions và deterministic budgets cho cùng canonical results. Network/host telemetry tách khỏi semantic identity; cùng source nhưng acquired artifacts khác không phải cùng đầy đủ đầu vào.
