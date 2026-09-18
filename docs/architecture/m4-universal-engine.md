# M4-UNIVERSAL Contract: Universal Multi-Repository & Spring Architecture Intelligence Engine

**Status: APPROVED by Human Owner Directive (2026-09-18); Core Architecture Specification for Universal Coverage.**

---

## 1. Tầm nhìn & Nguyên lý Đặc tả Năng lực (Capability-Driven Philosophy)

Nền tảng kiến trúc xác lập mục tiêu đạt **~99% độ bao quát thực tế trên mọi kho lưu trữ mã nguồn trên toàn thế giới (Universal Multi-Repository Intelligence)**:
- Thấu hiểu toàn diện từ Java cổ điển (Java 1.0–8) đến Java hiện đại (Java 11, 17, 21, lên tới Java 26+).
- Thấu hiểu mọi thế hệ framework từ Spring Framework 1.x–6.x đến Spring Boot 1.x–3.x+.
- Tương thích với mọi kiểu tổ chức dự án: Java thuần không build tool, dự án đơn module, đa module Maven (kế thừa POM, BOMs, dependency management) và dự án sử dụng **Gradle** (`build.gradle` Groovy DSL và `build.gradle.kts` Kotlin DSL).
- Giải mã tự nhiên các cấu trúc sinh mã và dynamic framework phổ biến nhất thế giới (Lombok, Spring Data, Records, Sealed types, Web Routes).
- Loại bỏ triệt để tình trạng bùng nổ tổ hợp trong giải toán cấu hình thông qua bộ giải SAT Boolean thuần Java.

### Nguyên tắc Bất biến về Quyền Tự chủ của Mô hình Suy luận (Autonomous Reasoning Mandate)
> [!IMPORTANT]
> Tài liệu kiến trúc này **chỉ xác định các năng lực và kết quả bắt buộc phải bao quát (Required Outcomes & Capabilities)** cùng các bất biến an toàn cốt lõi.
> **Tuyệt đối không ra lệnh hay chỉ đạo vi mô từng bước thuật toán**; tạo không gian tự do tối đa để các mô hình suy luận cao (Reasoning Models) tự tư duy, thiết kế giải thuật tối ưu, cấu trúc dữ liệu và cơ chế giải mã linh hoạt phù hợp với thực tế repository.

---

## 2. Bảng Năng lực & Kết quả Bắt buộc Phải Bao quát

| Chiều không gian | Năng lực & Kết quả Bắt buộc Phải Bao quát |
|---|---|
| **Cú pháp & Ngôn ngữ (Java 8 – 26+)** | Phân tích đầy đủ cú pháp Java cổ điển đến Java hiện đại (Records, Sealed classes/interfaces, Pattern Matching, Virtual Threads). Tự động tổng hợp và giải mã các cấu trúc sinh mã thông dụng: Lombok (`@RequiredArgsConstructor`, `@AllArgsConstructor`, `@NoArgsConstructor`, `@Data`, `@Value`, `@Builder`) và Java 17/21 canonical record constructor DI mà không đòi hỏi phải biên dịch bytecode trước. |
| **Hệ thống Build (Maven, Gradle, Plain Java)** | Nạp và trích xuất cấu trúc dự án an toàn không qua thực thi lifecycle: Java thuần (dựa theo cấu trúc thư mục quy ước), đa module Maven (cha-con, BOMs, quản lý dependency), và Gradle (`build.gradle` / `build.gradle.kts`), thu nhận chính xác tọa độ GAV, dependencies và source roots (`src/main/java`, `src/test/java`, `src/main/resources`). |
| **Cấu hình Tự động (Zero Manual Setup)** | Tự động quét và đọc các tệp cấu hình nguồn (`application.properties`, `application.yml`, `application.yaml`, cùng các biến thể profile `application-{profile}.*`). Làm phẳng cấu trúc phân cấp thành các thuộc tính dạng chấm, nhận diện profile kích hoạt (`spring.profiles.active`) và nạp thẳng vào `ConfigurationAssignment` phục vụ giải quyết `@Value` và `@ConditionalOnProperty`. |
| **Quét Component Tự động** | Tự động phát hiện base package từ `@SpringBootApplication` và `@ComponentScan` để quét toàn bộ cây thư mục mã nguồn, phát hiện đầy đủ mọi Stereotype (`@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Configuration`). |
| **Cơ chế Sinh Bean Động (Spring Data)** | Nhận diện và tự động mô hình hóa các interface kế thừa họ Spring Data (`Repository`, `CrudRepository`, `JpaRepository`, `MongoRepository`, v.v.) thành các Candidate Bean hợp lệ, xóa bỏ hoàn toàn tình trạng báo thiếu dependency ở các Service tiêu thụ. |
| **Đa Thế hệ Framework (Dual Namespace)** | Hỗ trợ song song cả hai thời kỳ: `javax.*` (Spring Boot 1.x / 2.x) và `jakarta.*` (Spring Boot 3.x+). Đọc tự động cả hai cơ chế đăng ký tự động: `META-INF/spring.factories` và `META-INF/spring/*.imports`. Áp dụng đúng chính sách ghi đè bean mặc định theo phiên bản. |
| **Bộ giải Logic SAT Bất biến Quy mô** | Tích hợp bộ giải SAT Boolean thuần Java (DPLL / CNF / Sat4j) vào giao diện `ConfigurationReasoner`, triệt tiêu hoàn toàn gap `ENUMERATION_INCOMPLETE` trên không gian cấu hình khổng lồ ($2^{50}+$ biến), hoàn thành giải trong mili-giây. |
| **Kiến trúc Luồng Web API & Nâng cao** | Trích xuất các HTTP endpoints (`@RequestMapping`, `@GetMapping`, `@PostMapping`, v.v.) ánh xạ thành chuỗi liên kết kiến trúc: HTTP Route -> Controller -> Service -> Repository. Hỗ trợ mô hình context phân cấp cha-con và đánh giá SpEL cơ bản cho `@ConditionalOnExpression`. |

---

## 3. Phân rã Thực thi: 2 Slices Tối đa (Consolidated 2-Slice Roadmap)

Toàn bộ Mega Task được phân chia thành **tối đa 2 slices độc lập, liên tục**, bàn giao cho các agent thực thi:

### SLICE 1: Universal Source, Build & Configuration Ingestion
1. **Lombok & Record Preprocessor (`analyzer-javaparser`):** Mở rộng bộ trích xuất implicit để nhận diện `@RequiredArgsConstructor`, `@AllArgsConstructor`, `@NoArgsConstructor`, `@Data`, `@Value`, `@Builder` và Record Canonical Constructors; sinh các thực thể Constructor, Parameter và TypeUseRecord tương ứng với kiểu trường phục vụ Dependency Injection.
2. **Universal Gradle Build Model Adapter (`analyzer`):** Trích xuất từ vựng an toàn cho `build.gradle` (Groovy) và `build.gradle.kts` (Kotlin), tạo ra `BuildModelResult` chuẩn hóa chứa đầy đủ ModuleModel, dependencies và source plans.
3. **Auto Configuration & Resource Reader (`analyzer`):** Phân tích native `application.properties` và `application.yml`/`.yaml` (kèm profile cascading) thành `ConfigurationAssignment` tự động.
4. **Auto Component Scanner (`analyzer`):** Phát hiện base package từ `@SpringBootApplication` và thu nhận toàn bộ component stereotypes hợp lệ trong phạm vi package.

### SLICE 2: Universal Spring Semantics, Dynamic Frameworks & SAT Reasoning
1. **Spring Data Dynamic Repository Synthesizer:** Tự động đăng ký Candidate Bean cho mọi interface kế thừa Spring Data types, giải quyết triệt để điểm tiêm phụ thuộc ở Service.
2. **Cross-Generation Dual Namespace Engine:** Hỗ trợ song song `javax.*` và `jakarta.*`, đọc cả `spring.factories` và `.imports`, cấu hình bean override policy theo thời kỳ framework.
3. **Pure-Java SAT Solver Engine:** Tích hợp bộ giải DPLL / CNF SAT vào `ConfigurationReasoner`, bảo đảm giải quyết mọi không gian cấu hình vô hạn mà không phụ thuộc thư viện native C++ hay mạng.
4. **Web API Route Mapper & Hierarchical Contexts:** Ánh xạ các annotation Web Controller thành các endpoint kiến trúc; mô hình hóa context phân cấp cha-con và bộ đánh giá SpEL cơ bản.

---

## 4. Bất biến Cốt lõi Không Thương lượng (Core Invariants)

Bất kỳ mô hình hay agent nào thực thi các slices này đều phải tuân thủ 4 trụ cột bất biến:
1. **An toàn Thực thi & Sandbox Tuyệt đối:** Không bao giờ chạy lệnh build Maven/Gradle hay plugin của repository đối tượng. Mã nguồn repository đối tượng là dữ liệu đầu vào thuần túy, không có quyền thực thi.
2. **Bằng chứng Cấp một & Zero Hallucination:** Mọi định danh thực thể, quan hệ, kết quả phân tích đều phải có địa chỉ nội dung (SHA-256), có nguồn gốc (provenance) rõ ràng.
3. **Mẫu số Báo cáo Khép kín (Closed Reporting Denominator):** Không bao giờ âm thầm bỏ qua các trường hợp suy thoái hoặc chưa xử lý được. Bất kỳ giới hạn nào đều phải được ghi nhận thành `CapabilityGapRecord` chuẩn tắc.
4. **Tái hiện Xác định (Deterministic Replay):** Cùng một đầu vào mã nguồn và cấu hình phải luôn cho ra đúng một kết quả và một mã băm đầu ra duy nhất.
