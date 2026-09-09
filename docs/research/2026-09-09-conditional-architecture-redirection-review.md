# Đánh giá chuyển hướng nghiên cứu: Conditional Architecture Assurance

Ngày đánh giá: **2026-09-09**  
Đối tượng được đánh giá: `AI_Native_Verified_Software_Evolution_RESEARCH_REDIRECTION_V2_IMPLEMENTATION_ALIGNED_2026_2028.md`  
Phạm vi: tài liệu, hợp đồng kiến trúc, lộ trình nghiên cứu và sản phẩm; **không thay đổi mã nguồn**.

## 1. Kết luận điều hành

Đề xuất V2 có một hạt nhân đủ mạnh để nhận làm hướng chính: một ứng dụng Spring Boot không nên bị mô hình hóa như chỉ có một kiến trúc duy nhất nếu bean, binding và vi phạm kiến trúc phụ thuộc vào profile, property, classpath, thứ tự auto-configuration hoặc trạng thái bean đã đăng ký. Hướng được lựa chọn là:

> **Staged Conditional Architecture Semantics and Evidence-Driven Assurance for Framework-Managed Systems**

Tên nghiên cứu SE121 đề xuất:

> **Configuration-Hidden Architecture Drift in Spring Boot: Phase/Order-Aware Conditional Semantics, Counterexample-Carrying Findings, and Empirical Evaluation**

Quyết định này **giữ nguyên M1–M3.8**, không thay đổi nhiệm vụ đang hoạt động và không tuyên bố G2/G3 đã đạt. M3 tiếp tục tạo một build/classpath/platform context chính xác; M4 mới là nơi mô hình hóa không gian cấu hình Spring bên trong context đó.

V2 không được áp dụng nguyên trạng. Các điều chỉnh bắt buộc là:

1. thay “fixpoint-style reasoning” chung chung bằng ngữ nghĩa chuyển trạng thái theo **pha và thứ tự đăng ký bean**;
2. tách `ConfigurationIdentity` hiện có của một cấu hình cụ thể khỏi `ConfigurationSpaceIdentity` của một không gian hữu hạn được mô hình hóa;
3. dùng IR và solver port trung lập, với bộ liệt kê exhaustive làm oracle cho fixture nhỏ trước khi chọn SAT/BDD;
4. giới hạn tổng hợp Lombok và Spring Data theo bằng chứng phiên bản/cấu hình/phạm vi đăng ký, không dùng heuristic rộng;
5. sửa baseline: ArchUnit là phân tích bytecode tĩnh; Spring Modulith có kiểm chứng cấu trúc tĩnh và runtime verifier riêng, không phải mặc định chỉ kiểm tra một `ApplicationContext` đang chạy;
6. không tuyên bố độ phổ biến, độ chính xác, novelty hay hiệu năng trước khi có corpus và ground truth;
7. chuyển kế hoạch 24 tuần thành chuỗi capability gate vì chủ dự án ưu tiên chiều sâu và có đủ thời gian để làm đúng.

## 2. Câu trả lời ngắn cho các câu hỏi quyết định

| Câu hỏi | Kết luận | Trạng thái bằng chứng |
|---|---|---|
| Hướng conditional architecture có thực tế không? | Có. Spring chính thức quy định nhiều condition theo profile/property/classpath/bean state; một số condition phụ thuộc vào các bean definition đã được xử lý và do đó nhạy với thứ tự. | **SUPPORTED** bởi tài liệu/API và mã nguồn framework[^1][^2][^3] |
| Có phù hợp mã nguồn hiện tại không? | Có. Hợp đồng hiện có đã có `CONDITIONAL`, `CONFIGURATION`, provenance, configuration identity và capability-gap ledger; chưa có Spring production code nên chi phí đổi hướng hiện tại thấp. | **CONFIRMED** bằng kiểm tra repository ngày 2026-09-09 |
| SAT/BDD có phải novelty không? | Không. Đây là công cụ biểu diễn/giải; novelty phải nằm ở ngữ nghĩa framework, drift có điều kiện, witness và bằng chứng. | **SUPPORTED** bởi dòng nghiên cứu variability/SPL[^13][^14][^15] |
| Có nên làm AI ngay trong SE121 không? | Không cho correctness core. Nên giữ AI như tầng đề xuất/giải thích/thay đổi có kiểm chứng sau khi fact và witness deterministic đã đủ mạnh. | **PROVISIONAL** dựa trên phạm vi dự án và xu hướng đánh giá repo-level agents[^18][^19][^20] |
| Có nên giữ Track B? | Có, nhưng đổi trọng tâm thành conditional architecture delta và evidence delta giữa snapshot tương thích. | **ADOPTED DIRECTION**, cần benchmark lịch sử |
| Có nên xóa M1–M3? | Không. Chúng là nền tảng nhận dạng, bằng chứng và build context cần thiết cho M4. | **CONFIRMED** bằng kiến trúc/mã nguồn hiện tại |

## 3. Phương pháp đánh giá

Đánh giá dùng bốn lớp bằng chứng:

1. hợp đồng và trạng thái repository hiện tại;
2. API, reference documentation và mã nguồn chính thức của Spring Boot, Spring Framework, Lombok, Spring Data, Spring Modulith và ArchUnit;
3. công trình 2022–2026 về architecture recovery, variability, evolving product lines và repository-level AI;
4. tín hiệu chủ đề từ ICSE, SANER, SEAMS và AGENT 2027, chỉ dùng để định hướng chứ không coi là kết quả tương lai.

Không có thí nghiệm mới, build mới hoặc thay đổi code trong lần đánh giá này. Mọi dự báo 2027–2028 dưới đây là **FORECAST**, không phải fact.

## 4. Bằng chứng từ mã nguồn hiện tại

### 4.1 Nền tảng đã có và nên giữ

Kiểm tra các hợp đồng đang triển khai cho thấy:

- `ConfigurationIdentity` hiện tại hash một schema version và map giá trị cụ thể; nó phù hợp với **một realized configuration**, không phải toàn bộ không gian cấu hình.
- `SemanticStatus` đã có `CONDITIONAL` cùng `RESOLVED`, `PARTIAL`, `UNRESOLVED`, `AMBIGUOUS`, `UNSUPPORTED`, `ERROR`.
- `EvidenceRequirement.Kind` đã có `CONFIGURATION`, `BYTECODE`, `GENERATED_SOURCE`, `ISOLATED_BUILD_OUTPUT` và `RUNTIME_OBSERVATION`.
- `CapabilityGapRecord`/ledger đã content-addressed, giữ attempt, conflict và provenance; M4 có thể thêm câu hỏi namespaced mà chưa cần thay enum ngay.
- `SemanticFrontend` và graph/query boundaries đã được thiết kế thay thế được; parser AST không cần đi vào conditional semantics.
- M4 chưa có production implementation. Vì vậy thay đổi hợp đồng trước M4 rẻ hơn rất nhiều so với sửa graph, policy và UI sau này.

### 4.2 Biên giới phải giữ

M3 tạo **một thế giới build chính xác** gồm source roots, ordered classpath và platform symbols. Không gian biến thiên Spring của M4 phải nằm bên trong thế giới đó. Việc gộp Maven profile/toolchain/classpath alternatives vào cùng một solver ngay bây giờ sẽ trộn hai loại biến thiên và làm yếu tính tái lập.

Mô hình đúng theo giai đoạn:

```text
Repository snapshot
  -> exact BuildContext B (M3)
  -> bounded Spring ConfigurationSpace K inside B (M4)
  -> phase/order-aware bean-definition transitions
  -> conditional facts and bindings
  -> graph/policy/product projections
  -> compatible conditional deltas (Track B)
```

Một “build-context family” có thể được thêm sau SE121 để so sánh Maven profile, toolchain, Gradle variant hoặc AOT mode, nhưng không được giả vờ rằng M3 hiện đã cung cấp nó.

## 5. Kiểm tra hiện thực Spring

### 5.1 Vì sao single-world analysis là chưa đủ

Spring Boot hỗ trợ condition dựa trên class, bean, property, resource, web application và expression; profile có biểu thức logic và nhiều profile có thể hoạt động đồng thời.[^1][^4][^5] Cấu hình bên ngoài còn có precedence, import, environment variables và config trees, nên repository không luôn chứa toàn bộ deployment configuration.[^6]

Điểm quan trọng nhất: `@ConditionalOnBean` và `@ConditionalOnMissingBean` chỉ nhìn thấy các bean definition đã được xử lý tại thời điểm condition chạy; tài liệu API yêu cầu dùng chúng cẩn thận và chủ yếu trong auto-configuration vì kết quả phụ thuộc thứ tự.[^2][^3] Spring Boot cũng có before/after ordering cho auto-configuration.[^1] Vì vậy:

- profile/property/classpath predicates có thể được xem như đầu vào ngoại sinh khi bằng chứng đủ;
- bean-present/bean-missing/single-candidate là predicate nội sinh phụ thuộc trạng thái đăng ký;
- `OnMissingBean` là không đơn điệu: thêm một bean có thể làm condition từ đúng thành sai;
- một least fixpoint chung không mô tả chính xác mọi trường hợp;
- khi không xác định được thứ tự, kết quả phải branch có giới hạn hoặc trở thành `UNKNOWN`.

Mã nguồn Spring Framework cũng đánh giá condition theo `PARSE_CONFIGURATION` và `REGISTER_BEAN` trong quá trình đọc bean definitions, củng cố yêu cầu về pha.[^7]

### 5.2 AOT làm hướng mở rộng có giá trị nhưng chưa phải CORE

Spring Boot AOT xử lý `BeanFactory` ở build time và áp đặt hạn chế lên các phần cấu hình phải được xác định trước.[^8][^9] Đây là một biến thể kiến trúc có ý nghĩa thực tiễn cho native image và deployment, nhưng cần một context identity riêng. Mô hình AOT nên là Track C/horizon sau khi JVM conditional semantics đạt G3, không kéo vào đường găng SE121.

## 6. Mô hình ngữ nghĩa được lựa chọn

### 6.1 Không gian cấu hình hữu hạn, có nguồn gốc

`ModeledConfigurationSpace` phải ghép hai envelope và giữ chúng tách biệt:

- `RepositoryDeclaredEnvelope`: profile/property documents, imports và default được tìm thấy trong snapshot;
- `UserSuppliedDeploymentEnvelope`: profile, property, environment/config-tree descriptor do người dùng cấp có chủ đích.

Không được gọi phần thứ nhất là “toàn bộ production configuration”. External config có thể nằm ngoài repository. Mỗi property source cần identity, precedence, origin và digest. Domain hữu hạn nên gồm giá trị quan sát được, `MISSING` và khi hợp lệ một lớp `OTHER`; nếu abstraction không bảo toàn condition đang xét thì phải mở domain hoặc trả `UNKNOWN`.

### 6.2 IR condition trung lập

IR v1 nên biểu diễn tối thiểu:

- Boolean structure: `ALL`, `ANY`, `NOT`, constant;
- profile expression;
- property present/equality/having-value/match-if-missing;
- class/resource/web-mode predicates;
- bean present, bean missing, single candidate;
- opaque custom condition/SpEL/dynamic registrar.

Mỗi node giữ framework/version semantics, operands chuẩn hóa, source/evidence references và reason khi opaque. IR không chứa kiểu của SAT/BDD library.

### 6.3 Chuyển trạng thái theo pha và thứ tự

Với build context `B`, configuration `c`, registration plan có thứ tự `R = [r1..rn]` và trạng thái bean-definition `S_i`:

```text
S_0 := definitions known before R
eval(condition(ri), B, c, S_i) = TRUE  -> S_(i+1) := register(ri, S_i)
eval(condition(ri), B, c, S_i) = FALSE -> S_(i+1) := S_i
eval(condition(ri), B, c, S_i) = UNKNOWN
  -> bounded branch if sound and within budget
  -> otherwise retain UNKNOWN and affected facts/gaps
```

Framework semantics version, registration-plan identity, tie-breaking và branch/solver limits là một phần của analysis provenance. Không có thứ tự chính xác thì không được chọn một thứ tự “có vẻ hợp lý”.

### 6.4 Truth region và bốn nhãn

Với tập cấu hình khả thi được mô hình hóa `K`, mỗi fact hoặc policy predicate có ba vùng `T`, `F`, `U`:

- `MUST`: `T = K`;
- `NEVER`: `F = K`;
- `MAY`: tồn tại ít nhất một witness trong `T` và một counter-witness trong `F`;
- `UNKNOWN`: các trường hợp còn lại vì `U` có thể thay đổi kết luận hoặc không gian chưa đủ bằng chứng.

Nếu đã có cả true và false witnesses thì nhãn `MAY` vẫn có giá trị, nhưng coverage của phần `U` phải hiển thị riêng. `NEVER` thường là kết quả query/clean evidence, không phải một “violation” để đưa vào danh sách cảnh báo.

`ERROR`, `UNSUPPORTED`, `TIMEOUT`, `LIMIT_EXCEEDED` là operational outcomes; chúng không được ép thành giá trị logic false.

### 6.5 Witness có thể tái lập

Mọi `MAY` finding cần ít nhất:

- một configuration witness làm finding đúng;
- một counter-witness làm finding sai nếu cần chứng minh variation;
- tập assignment tối thiểu tương đối với default/envelope đã công bố;
- thứ tự tie-break deterministic;
- kiểm tra lại witness bằng chính evaluator/version đã tạo finding.

Runtime execution, nếu sau này được phép, chỉ là một evidence provider để xác nhận/narrow uncertainty; nó không viết đè source/static provenance.

## 7. Solver: lựa chọn thực tế và có đường lui

Đề xuất “set/map evaluator là đủ vì repository thường có 3–15 biến” bị bác bỏ: con số này chưa có bằng chứng và các tương tác profile/property/bean-order có thể tăng nhanh.

Hướng được chọn:

1. định nghĩa solver-neutral `ConfigurationReasoner` port;
2. xây exhaustive enumerator làm oracle cho microfixture và không gian nhỏ;
3. benchmark một SAT backend Java thuần như LogicNG cho satisfiability, witness và minimization; LogicNG công bố API SAT và kết quả ba trạng thái cho solver call.[^21]
4. chỉ thêm BDD nếu các phép toán vùng lặp lại chứng minh có lợi; variable ordering của BDD có thể gây tăng kích thước rất lớn.[^22]
5. mọi backend phải có canonical variable ordering, limits, timeout semantics và deterministic witness normalization.

Đây là quyết định kiến trúc về **port và quá trình chọn**, không phải phê duyệt LogicNG hay BDD trước benchmark.

## 8. Hai chỉnh sửa lớn về enterprise framework coverage

### 8.1 Lombok

Không được tổng hợp constructor chỉ từ “final field + `@RequiredArgsConstructor`”. Lombok quy định required constructor cho các field `final` chưa khởi tạo và các field `@NonNull` chưa khởi tạo; thứ tự tham số theo thứ tự field. `static`, explicit constructor, `staticName`, access level, annotation propagation và Lombok configuration đều có thể thay đổi kết quả.[^10][^11]

Chỉ tạo derived member khi annotation identity, Lombok semantics version/configuration và field eligibility đủ chính xác. Generated member giữ `EntityOrigin.PROJECT`; annotation span là supporting evidence chứ không phải declaration span. Nếu không đủ bằng chứng, yêu cầu generated source hoặc bytecode và giữ capability gap.

Tuyên bố “hơn 90% Spring Boot repository dùng các convention này” không có nguồn và bị loại.

### 8.2 Spring Data

Một interface extends `Repository` không tự động chứng minh có runtime bean. Cần chứng minh repository enablement/scanning scope, base packages, include/exclude filters, store binding, strict multi-module behavior và không có `@NoRepositoryBean`; custom fragments/factory/base class cũng ảnh hưởng mô hình.[^12]

Do đó Spring Data là một framework-synthesized conditional producer family, không phải một heuristic “mọi repository interface đều sinh proxy bean”. Khi registration scope không chắc chắn, giữ candidate/UNKNOWN hoặc capability gap.

## 9. Baseline và novelty được sửa lại

### 9.1 Baseline công bằng

ArchUnit phân tích Java bytecode tĩnh để kiểm tra architecture rules.[^23] Spring Modulith `ApplicationModules.verify()` kiểm tra cycle, module API/internal access và allowed dependencies; runtime verification là capability riêng.[^24][^25] Vì vậy benchmark không được nói hai công cụ này “chỉ nhìn một active ApplicationContext”.

Baseline đề xuất:

1. Java static dependency graph không có Spring enrichment;
2. Spring analysis tại một realized configuration;
3. flat presence-condition model không có ordered bean state;
4. ArchUnit cho rule có thể biểu diễn tương đương;
5. Spring Modulith cho module boundaries tương đương;
6. Jasmine nếu artifact có thể tái lập; Jasmine là baseline gần về static Spring dependency extraction hơn.[^16]
7. staged conditional semantics được đề xuất.

Cần đo cả **missed configuration qualification** và **over-approximate warning**. Một static type dependency có thể vẫn hiện trong ArchUnit dù condition không hoạt động; vấn đề không phải luôn là “miss” mà có thể là thiếu điều kiện hoặc cảnh báo quá rộng.

### 9.2 Contribution có thể bảo vệ

Knowledge graph, SAT/BDD, presence conditions, architecture recovery và RAG tự thân không mới. Các công trình gần đây tiếp tục cải thiện architecture recovery và repo comprehension,[^17][^18] trong khi variability-aware analysis và evolving product-line assurance đã có nền tảng rõ.[^13][^14][^15]

Contribution cần kiểm chứng là tổ hợp cụ thể:

1. framework/version-specific condition IR;
2. phase/order-aware bean-definition transition semantics;
3. conditional architecture facts và policy findings không tạo false certainty;
4. minimal counterexample configurations có provenance;
5. conditional/evidence delta giữa snapshot tương thích;
6. selective escalation sang runtime evidence theo witness nếu thực nghiệm chứng minh hiệu quả.

Chỉ sau literature review có hệ thống và benchmark mới được dùng từ “novel” trong claim chính thức.

## 10. Chương trình thực nghiệm được lựa chọn

### 10.1 Research questions

- **RQ1 — Prevalence/taxonomy:** Single-configuration analysis phân loại sai hoặc không đủ điều kiện hóa Spring facts/policy findings theo những dạng nào và với tần suất nào trong corpus đã đăng ký?
- **RQ2 — Semantic effectiveness:** Phase/order-aware semantics cải thiện precision/recall/calibration ra sao so với Java-static, current-config và flat-condition baselines?
- **RQ3 — Witness/cost:** Witness và affected configuration region có đúng, tối giản, tái lập và có chi phí thực tế không?
- **RQ4 — Evolution:** Bao nhiêu thay đổi lịch sử tạo architecture drift chỉ trong một phần không gian cấu hình?
- **RQ5 — Evidence efficiency:** Witness-directed runtime evidence có giảm số context execution để giải quyết uncertainty so với exhaustive/random/covering-array sampling không?
- **RQ6 — Future AI:** Sau SE121, deterministic facts/witnesses có cải thiện độ đúng của AI change planning và repair verification so với repository-text-only context không?

RQ1–RQ4 là trục chính Track A+B; RQ5 chỉ được nâng lên contribution chính nếu có kết quả mạnh; RQ6 nằm ngoài SE121.

### 10.2 Benchmark shape

Quy mô là **mục tiêu thiết kế**, không phải fact:

- 40–80 microfixtures cho từng condition/registration/binding rule;
- interaction fixtures cho property + profile + classpath + bean state + order;
- 5–8 repository thực với version/snapshot pin và inclusion criteria công bố trước;
- historical snapshot pairs cho Track B;
- controlled mutations có expected affected region.

Hỗ trợ version phải được pin theo corpus. Tại ngày đánh giá, tài liệu Spring Boot liệt kê nhánh stable 4.1.x, 4.0.x và 3.5.x; không nên chỉ benchmark một version rồi quảng bá semantics phổ quát.[^26]

### 10.3 Ground truth và chỉ số

Mỗi fixture/case phải ghi:

- exact build context và configuration space;
- expected registration sequence hoặc reason không thể biết;
- expected fact/binding/policy truth region;
- expected witness/counter-witness;
- provenance/source spans;
- expected gap/UNKNOWN/operational outcome.

Chỉ số chính:

- fact/binding/policy precision, recall và F1 theo denominator đóng;
- false-unconditional-warning rate;
- false-certainty rate;
- MUST/MAY/NEVER/UNKNOWN confusion matrix;
- witness validity và minimality;
- affected-region precision/recall;
- runtime-evidence reduction so với baseline;
- analysis time, memory, solver/branch limits và deterministic digest agreement.

### 10.4 Kill/simplification criteria

- Nếu variation do Spring conditions hiếm trong corpus đại diện, thu hẹp claim/domain thay vì phóng đại.
- Nếu flat condition model đạt kết quả tương đương ordered transition model trên benchmark đã đăng ký, chọn mô hình đơn giản hơn.
- Nếu witness-directed runtime không giảm đáng kể execution cost, dùng runtime như oracle/diagnostic chứ không làm contribution chính.
- Nếu abstraction tạo false certainty, withhold kết luận, mở rộng domain hoặc yêu cầu evidence; không “tối ưu” bằng cách coi UNKNOWN là false.

## 11. Lộ trình sản phẩm 2026–2028

### 11.1 SE121 — sản phẩm lõi có thể dùng được

Track A hoàn thành một workbench có thể:

- thấy exact build context và modeled configuration space;
- lọc theo realized configuration hoặc vùng cấu hình;
- phân biệt MUST/MAY/UNKNOWN findings;
- mở witness và giải thích “vì sao active/inactive/selected”;
- truy tới source/evidence/gap;
- so sánh current-config với union projection mà không đánh đồng chúng.

Track B thêm conditional architecture delta, affected configuration region và evidence-loss protection. Đây là sản phẩm có giá trị trực tiếp cho PR reviewer, architecture team và starter/framework maintainer.

### 11.2 Capability horizons sau M12

Các horizon này không phải SE121 commitment:

- **Horizon D — Context federation:** nhiều build contexts, Maven/Gradle/POM-less adapters, generated/AOT/runtime evidence và deployment descriptors (Docker/Kubernetes/Helm/config trees) với provenance.
- **Horizon E — System-of-systems assurance:** API/event/schema/database-migration dependencies, cross-repository policy, organizational ownership và CI/PR/IDE integration.
- **Horizon F — Verified AI evolution:** AI giải thích/prioritize gap, soạn policy, lập change plan và đề xuất patch; deterministic analyzer vẫn là authority, patch phải qua diff, test, policy, sandbox và evidence gate.

Xu hướng 2027 đang nhấn mạnh trustworthy AI, architecture/evolution, uncertainty, runtime models và evidence for agentic systems.[^27][^28][^29][^30] Đây là tín hiệu để giữ extension boundary, không phải lý do kéo agentic implementation vào SE121.

### 11.3 Dự báo 2028

**FORECAST:** retrieval, code generation và generic graph construction có khả năng trở nên phổ biến hơn; giá trị khác biệt sẽ dịch sang environment/configuration reasoning, provenance, calibrated uncertainty, reproducible counterexamples và verification of change. Vì vậy hướng conditional/evidence-first tạo nền tảng tốt hơn cho “AI-native” so với việc gắn LLM sớm vào một graph chưa chứng minh đúng.

## 12. Ma trận xử lý nội dung V2

| Thành phần V2 | Quyết định | Cách áp dụng |
|---|---|---|
| Giữ M1–M3.8 | **ADOPT** | M3.8 vẫn là exact next task |
| Conditional architecture facts | **ADOPT** | Hợp đồng canonical mới và ADR-004 |
| Staged semantics | **ADOPT WITH CORRECTION** | Phase/order-aware transitions, không generic fixpoint |
| Three-valued evaluation | **ADOPT WITH CORRECTION** | Logic truth tách operational outcomes |
| MUST/MAY/NEVER/UNKNOWN | **ADOPT WITH CORRECTION** | `NEVER` là clean query result; UNKNOWN không bị ép thành false |
| Configuration mining | **ADOPT WITH BOUNDARY** | Repository envelope + user deployment envelope; không tuyên bố đầy đủ production |
| Witness generation | **ADOPT** | Deterministic minimal witness + re-evaluation |
| Set/map solver mặc định | **REJECT** | Solver-neutral port; exhaustive oracle; benchmark SAT/BDD |
| Lombok synthesizer rộng | **REJECT/REPLACE** | Evidence-gated derived members hoặc gap |
| Spring Data proxy heuristic | **REJECT/REPLACE** | Registration/scanning/store-aware producer model |
| Spring Modulith/ArchUnit blind spot claim | **REJECT/CORRECT** | Fair static/runtime baseline definitions |
| Selective runtime evidence | **DEFER/GATE** | Candidate RQ5; permission/sandbox/provenance required |
| AOT | **DEFER** | Track C/post-M12 horizon after JVM semantics |
| AI diagnosis/refactoring | **DEFER, PRESERVE BOUNDARY** | Future verified-AI horizon, không SE121 core |
| 24-week calendar | **REMOVE AS AUTHORITY** | Capability waves và gates |

## 13. Tài liệu được thay đổi bởi quyết định này

- ADR mới ghi quyết định và alternatives.
- Hợp đồng canonical mới định nghĩa configuration space, transition semantics, truth regions, witnesses và compatibility.
- M4 contract được tái cấu trúc thành M4A–M4E.
- Roadmap chuyển Track A/B sang conditional architecture assurance và thêm post-M12 horizons.
- Architecture/KG/product/evidence contracts được cập nhật để graph là projection của conditional facts.
- Research questions được thay bằng RQ có thể đo và kill criteria.
- Đề xuất V2 gốc được giữ làm design input nhưng có banner chỉ rõ phần nào đã bị supersede.

Không xóa tài liệu cũ vì proposal gốc và audit lịch sử vẫn có giá trị provenance. Tránh tạo hai nguồn authority bằng cách để ADR/hợp đồng kiến trúc/roadmap mới là nguồn canonical.

## 14. Giới hạn của lần đánh giá

- Chưa chạy thí nghiệm prevalence, solver hoặc runtime context.
- Chưa hoàn tất systematic literature review; novelty vẫn là hypothesis.
- Chưa chốt framework semantic fragment v1, solver backend, corpus repository hoặc budget số.
- Không có claim universal container equivalence.
- Version/framework behavior phải được pin và kiểm thử; tài liệu “current” sau ngày 2026-09-09 có thể thay đổi.

## Sources

[^1]: Spring Boot, [Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html), truy cập 2026-09-09.
[^2]: Spring Boot API, [`ConditionalOnBean`](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnBean.html), truy cập 2026-09-09.
[^3]: Spring Boot API, [`ConditionalOnMissingBean`](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnMissingBean.html), truy cập 2026-09-09.
[^4]: Spring Framework API, [`Profiles`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/core/env/Profiles.html), truy cập 2026-09-09.
[^5]: Spring Boot, [Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html), truy cập 2026-09-09.
[^6]: Spring Boot, [Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html), truy cập 2026-09-09.
[^7]: Spring Framework source, [`ConfigurationClassBeanDefinitionReader`](https://github.com/spring-projects/spring-framework/blob/main/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassBeanDefinitionReader.java), truy cập 2026-09-09.
[^8]: Spring Boot Gradle Plugin, [Ahead-of-Time Processing](https://docs.spring.io/spring-boot/gradle-plugin/aot.html), truy cập 2026-09-09.
[^9]: Spring Boot, [Introducing GraalVM Native Images](https://docs.spring.io/spring-boot/reference/packaging/aot.html), truy cập 2026-09-09.
[^10]: Project Lombok, [`@XArgsConstructor`](https://projectlombok.org/features/constructor), truy cập 2026-09-09.
[^11]: Project Lombok, [`@Data`](https://projectlombok.org/features/Data), truy cập 2026-09-09.
[^12]: Spring Data, [Creating Repository Instances](https://docs.spring.io/spring-data/jpa/reference/repositories/create-instances.html), [Core Concepts](https://docs.spring.io/spring-data/commons/reference/repositories/core-concepts.html), và [`@NoRepositoryBean`](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/repository/NoRepositoryBean.html), truy cập 2026-09-09.
[^13]: El-Sharkawy et al., [Incremental analysis of evolving software product lines](https://link.springer.com/article/10.1007/s10664-021-10090-6), *Empirical Software Engineering*.
[^14]: [Analysing Self-Adaptive Systems as Software Product Lines](https://doi.org/10.1016/j.jss.2024.112324), *Journal of Systems and Software*, 2025.
[^15]: [Requirements-driven variability management](https://doi.org/10.1016/j.infsof.2026.108017), *Information and Software Technology*, 2026; [Assurance Case Development for Evolving Software Product Lines](https://doi.org/10.1145/3796233), *Formal Aspects of Computing*, 2026.
[^16]: Cai et al., [Jasmine: A Static Analysis Framework for Spring](https://doi.org/10.1145/3551349.3556910), ASE 2022.
[^17]: [SSAR: A Novel Software Architecture Recovery Approach Enhancing Accuracy and Scalability](https://conf.researchr.org/details/icse-2026/icse-2026-research-track/221/SSAR-A-Novel-Software-Architecture-Recovery-Approach-Enhancing-Accuracy-and-Scalabil), ICSE 2026; [Beyond Lexical Similarity](https://doi.org/10.1109/SANER67736.2026.00123), SANER 2026.
[^18]: [RepoProbe: Benchmarking Architecture-Aware Repository Comprehension with Checklists](https://conf.researchr.org/details/ase-2026/ase-2026-research-track/156/RepoProbe-Benchmarking-Architecture-Aware-Repository-Comprehension-with-Checklists), ASE 2026.
[^19]: [Hydra: Do Not Treat Code as Natural Language](https://arxiv.org/abs/2602.11671), FSE 2026.
[^20]: [CausalRepair](https://conf.researchr.org/details/issta-2026/issta-2026-research-papers/134/CausalRepair-Bridging-the-Causality-Gap-in-Large-Language-Model-based-Automated-Prog), ISSTA 2026; [PatchLens](https://arxiv.org/abs/2606.25863), 2026 preprint.
[^21]: LogicNG, [SAT Solving](https://logicng.org/documentation/solvers/sat-solving/), truy cập 2026-09-09.
[^22]: LogicNG, [Binary Decision Diagrams](https://www.logicng.org/documentation/knowledge-compilation/bdd/), truy cập 2026-09-09.
[^23]: ArchUnit, [official repository and documentation](https://github.com/TNG/ArchUnit), truy cập 2026-09-09.
[^24]: Spring Modulith, [Verifying Application Module Structure](https://docs.spring.io/spring-modulith/reference/verification.html), truy cập 2026-09-09.
[^25]: Spring Modulith, [Runtime Support](https://docs.spring.io/spring-modulith/reference/runtime.html), truy cập 2026-09-09.
[^26]: Spring Boot, [Reference Documentation](https://docs.spring.io/spring-boot/), truy cập 2026-09-09; [Spring Boot 4.1.1 available now](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/).
[^27]: ICSE 2027, [Research Track](https://conf.researchr.org/track/icse-2027/icse-2027-research-track), truy cập 2026-09-09.
[^28]: SANER 2027, [Research Papers](https://conf.researchr.org/track/saner-2027/saner-2027-papers), truy cập 2026-09-09.
[^29]: SEAMS 2027, [Research Track](https://conf.researchr.org/track/seams-2027/seams-2027-research-track), truy cập 2026-09-09.
[^30]: AGENT 2027, [International Workshop on Agentic Engineering](https://conf.researchr.org/home/icse-2027/agent-2027), truy cập 2026-09-09.
