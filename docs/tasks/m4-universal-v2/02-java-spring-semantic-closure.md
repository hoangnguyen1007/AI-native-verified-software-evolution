# V2.2 — Java/Spring semantic closure

Status: **IN PROGRESS / partial implementation, chưa đạt exit criteria**, cập nhật 2026-09-29. Task triển khai thứ hai của [kế hoạch ba task](README.md). Mục tiêu: evidence V2.1 đi hết đến Java targets, Spring registration/binding và conditional architecture; không dừng ở các candidate helpers rời rạc.

Theo chỉ đạo trực tiếp của chủ dự án, công việc hiện tại là V2.2; các nghĩa vụ nghiệm thu V2.1 còn mở không được coi là đã qua. [Ranh giới triển khai](../../architecture/m4uv2-v22-implementation-boundary.md) ghi các phần đã nối vào code: direct Lombok constructor/accessor resolution, annotation reads/var/lexical parameter identities, captured relative Config Data imports và source-to-M4C/M4D cho ordinary components/scalar constructor injection với scope/order proof được cung cấp. [Gói kiểm chứng](../../reproducibility/m4uv2-v22-2026-09-28/README.md) ghi bằng chứng thực chạy. Những phần này chưa đóng toàn bộ bất kỳ nhóm A–E nào bên dưới; checkbox không được đánh dấu chỉ nhờ một fragment hoặc một typed gap.

## Entry, output và boundary

Slice ngày 2026-09-29 mở rộng bridge cho direct `@Bean`, scalar bean parameters và
constructor tiêu thụ bean products, dưới scope/order proof rõ ràng. Giữ riêng
producer identity, exposed return type và factory owner; thiếu metadata/name/order
vẫn có typed gaps. [Gói kiểm chứng slice bean producers](../../reproducibility/m4uv2-v22-beans-2026-09-29/README.md)
ghi positive/negative controls, container oracle và giới hạn; chưa đóng nhóm C hoặc V2.2.

Entry: V2.1 exact/partial inputs, artifact lineage, coordinator contracts và controls ổn định. Đọc [conditional semantics](../../architecture/conditional-architecture-semantics.md), [v1 Spring](../../architecture/m4u2-universal-spring.md), [v2 architecture](../../architecture/m4-universal-v2.md), [matrix J/S/Q](../../architecture/m4-universal-v2-coverage.md) và target M4B/C/D contracts.

Output: per-occurrence Java evidence; generated members tham gia resolution; complete source/artifact-to-Spring descriptors; qualified configuration spaces; versioned mechanism/effect packs; ordered registration/binding requests; truth regions/witnesses và requirements gửi lại coordinator.

Giữ JavaParser primary, M1 provenance/identity, logical-vs-operational states và one-build-context semantics. Không rewrite solver vì muốn engine mới; không xây graph DB/health score/UI hoặc target execution. Exact fact không đồng nghĩa toàn Spring runtime đã được mô phỏng.

## Code ownership

- `analyzer-javaparser`: parsing/attribution/derived-member integration; parser objects không vượt adapter.
- Neutral `frontend`, `spring/condition`, `registration`, `binding`, `truth`, `universal` ở `analyzer`: contracts/composition.
- Resource/bytecode acquisition tiêu thụ V2.1; không duplicate filesystem/network/archive logic.
- Nối `ComponentScanIngestion`, `ConstructorInjectionIngestion`, `SpringInjectionSites`, `SpringDataRepositories`, `AutoConfigurationMetadata`, `HierarchicalContexts`, `WebRouteMapper`, `TruthRegionEvaluation` bằng complete evidence, không chỉ thêm calls không tới canonical output.

## Checklist thực thi

### A. Java và generated symbol closure

- [ ] Account đầy đủ 18 relationship families: category/occurrence/caller/target/origin/span; CALLS-only không thành Java-wide evidence.
- [ ] Close known gaps có controls: array constructor references/length, annotation-value reads, inferred var, lambda/catch parameter identities và functional target contexts.
- [ ] Generic/overload/raw/wildcard/bridge/nested/local/anonymous/records/sealed/patterns có correct declaration identity; remainder giữ typed evidence needs.
- [ ] Lombok generated constructors/accessors/builders tham gia resolver, không chỉ output inventory. Check ancestor config, explicit suppression, initialization/nullness, generics và exact supported version.
- [ ] Imported MapStruct/protobuf/QueryDSL/metamodel/custom outputs hỗ trợ actual clients; processor không chạy, stale binary không override source.
- [ ] Partial source path giữ trustworthy local facts; recovery không tự tạo target/span chắc chắn. Syntax support tách target API support.
- [ ] Reproducible frontend gap: classify adapter defect vs provider limit; sửa adapter trước. Alternate frontend evaluation chỉ theo ADR-001 triggers, không tự thay primary.

**Control:** generated accessor/constructor call resolve exact signature/provenance; remove generator/config/artifact làm output qualified và invalidate cũ. Trusted compiler attribution cùng inputs, diagnostics/recovery được adjudicate.

### B. Config Data và finite-domain closure

- [ ] Selected local properties/YAML/profile/multi-document/import/optional/configtree closure, origins/precedence/cycles/byte bounds.
- [ ] Supplied env/CLI/system/config-service envelope riêng repository baseline; không đọc host env ngầm và không đồng nhất absent external evidence với MISSING.
- [ ] Placeholder/default/relaxed lookup/coercion có fragment/version/origin chain; custom loader/method/bean expression không thực thi.
- [ ] Profile defaults/includes/groups/source activation dependency graph; không tự đặt dev/prod exclusive.
- [ ] Domain lấy từ predicate-relevant constants + proven equivalence classes. OTHER đồng nhất trên mọi relevant predicate hoặc phải refine/unknown.
- [ ] Missing/empty/null/whitespace/case và framework coercion tách đúng; no secret values trong logs/report; protected artifact identity không bị bẻ để redact.
- [ ] Build profiles thay dependencies tạo context khác; Spring profile ở fixed context. Một config baseline không là mọi deployment.

**Control:** đổi import precedence đổi region đúng; optional missing input không chặn independent fact; correlated values không tạo impossible Cartesian assignments.

### C. Complete source-to-M4C bridge

- [ ] Source + binary annotation graph: AliasFor, defaults, repeatable/inherited/composed metadata, unresolved declaration footprint.
- [ ] Scan roots/filter/naming/membership/container evidence; unknown custom matcher không mặc định include/exclude.
- [ ] Bean/import/auto-config/XML producers -> parse/register guards + exact type + scheduling evidence.
- [ ] Constructor/field/method/bean-parameter descriptors: generic type, qualifier/name, flags, full source/derived provenance.
- [ ] Required/optional, lazy/provider, array/list/set/map, Resource/reference paths, method groups và resolvable dependencies giữ semantics riêng.
- [ ] Assemble actual `RegistrationPlan`, `BeanRegistrationPlan`, `InjectionBindingPlan` từ input evidence. Primary integration positive không nhận final descriptors dựng tay.
- [ ] Chạy ordered registration rồi binding rồi truth regions; unique structural candidate != selected definition != instantiated object.
- [ ] Library resources có class metadata/order proof; missing order không thay bằng sort alphabet rồi gọi framework semantics.
- [ ] Parent/child visibility, aliases/overrides, primary/fallback/priority version-qualified; open registry không chứng minh absence.

**Control:** compact source + artifact/config/XML -> full conditional binding; impostor annotations, unknown order, false scan member, generic/qualifier mismatch, duplicate primary và reversed missing-bean ordering phải đúng.

### D. Mechanism và version packs

Mỗi pack cần exact Framework/Boot/artifact tuple, mechanisms, metadata/defaults/order/coercion semantics ID, prerequisites, oracle cases và replacement trigger. Detection compatibility khác semantic compatibility. Same-major hay same namespace không đủ certify.

- [ ] Legacy XML/servlet context và modern annotation/Boot có minimal complete journeys. Pin representative historical/modern tuples từ protocol; new/future versions giữ structural evidence và explicit pack request.
- [ ] Spring Data stores/reactive: enable/store/scan/filter/NoRepositoryBean/fragments/base/factory/exclusions; actual binding còn cần registration/activation.
- [ ] FactoryBean/scoped/AOP/lookup: factory/product/exposed type/lifecycle distinctions; no invented proxy body.
- [ ] MVC/WebFlux annotation/composed/inherited/functional routes: path/method/media conditions, property/config/container activation, potential structural paths.
- [ ] Scheduled/async/events/messaging/batch/security/integration entry points và MyBatis/Feign/JPA/transaction/client metadata có concrete evidenced edges, không chỉ tên marker.
- [ ] Non-Spring frameworks giữ Java architecture và qualified framework boundary. CDI/Quarkus/Micronaut/Guice DI truth cần separately validated pack; không giả support bằng Spring heuristics.

**Control:** mỗi advertised pack có source-to-output positive; disabled store/wrong namespace/mismatched tuple/custom factory là negatives; pack mới không chỉ xóa version gap.

### E. Dynamic effects và symbolic refinement

- [ ] Literal reflection, ServiceLoader, functional registration/static factory có exact summaries khi proven; dynamic remainder không đoán.
- [ ] Bounded pure/effect-summary grammar cho custom conditions/selectors/registrars/processors; operation whitelist, recursion/loop budgets và unknown calls residual, no execution.
- [ ] Footprints đến config reads/candidate sets/registry prefix/order/exposed types. Không proof noninterference thì ảnh hưởng vẫn broad.
- [ ] Correlated opaque inputs giữ shared identity/constraints khi proven; không hóa chúng thành independent booleans để biến U thành MAY giả.
- [ ] SAT dependency slicing, signature partition/memoization nếu có correctness proof; cache key gồm context/config/order/semantics/provider versions.
- [ ] T/F/U, feasibility, operational status, witness/counter-witness riêng; imported runtime/AOT observation không thành universal MUST/NEVER.
- [ ] Independent M4B/C witness replay; no UNSAT proof từ timeout. Residual budgets vẫn account; no infinite-space/millisecond guarantee.
- [ ] Exhaustive small-space oracle, partial-order and saturation controls; later evidence có conflict/recompute, không overwrite.

**Control:** opaque local effect không làm independent bean UNKNOWN khi independence proven; global registrar vẫn qualify registry. >50 dimensions có cả sparse case và high-signature case để kiểm honest limit handling.

## Kiểm chứng và verification boundary

| Boundary | Complete positive | Negative/oracle |
|---|---|---|
| Generated Java -> DI | Generated constructor/accessor -> consuming service | Wrong version/config/lineage, explicit conflict; compiler truth |
| Evidence -> plan | Source + binary meta + XML/auto-config -> schedule | Impostor FQN, order/cycle/missing descriptor; framework truth |
| Config -> binding | Profile/import/property -> registration -> selection | Changed precedence, OTHER, absent/correlated values |
| Framework packs | Legacy/modern/data/proxy/route/event journeys | Disabled/inactive/ambiguous cases; trusted container oracle |
| Conditional facts | T/F/U + replayed witnesses | Impossible combined path, budget residual, evidence conflict |

Reuse focused suites; planned new names where useful: `GeneratedSymbolResolutionTest`, `SourceToSpringPlanTest`, `FrameworkPackConformanceTest`, `ConditionalEvidenceRefinementTest`. Tests cạnh production owner; mỗi vòng một class quiet mode, compact fixtures. Trusted oracle setup tách rõ untrusted target analysis. Labels không sinh bằng chính implementation được chấm; no full repo benchmark trong coding.

### Quy định kiểm thử tinh gọn & Cắt tỉa phạm vi (Lean Execution & Scope Pruning)

1. **Tuyệt đối cấm sinh gói nghiệm thu hình thức cho slice hàng ngày:**
   - KHÔNG tạo folder `reproducibility/`, không sinh file `input-hashes.json` (danh sách hash hàng nghìn dòng) hay file dump `verification.json` trong các commit code/TDD thường ngày.
   - Các gói bằng chứng đầy đủ chỉ được tạo 1 lần duy nhất khi nghiệm thu toàn bộ Milestone Gate (G3).
2. **Kiểm thử hai tầng theo AGENTS.md:**
   - Inner TDD loop: chạy một method hoặc class nhỏ ở chế độ quiet; với lớp tích hợp chậm dùng `"-Dtest=SourceToSpringPlanTest#<method>"`. Mục tiêu <5 giây, <20 dòng chẩn đoán; ghi nhận thời gian thực nếu vượt, không bỏ bằng chứng cần thiết.
   - Trước handoff: chạy tập unit/contract test và consumer tích hợp trực tiếp bị ảnh hưởng ở mọi module đã sửa, gồm assertion provider/version. Ghi rõ tập test và kết quả; ngân sách thời gian inner loop không áp cho tập này.
   - Bọc tham số `-D` trong dấu ngoặc kép khi dùng PowerShell. Không chạy full reactor hoặc benchmark repository cho slice thường ngày; dành cho milestone gate được phép.
3. **Cắt tỉa phạm vi (Scope Pruning) - Tránh sa lầy ở M4:**
   - Tập trung hoàn thành lõi Spring DI thiết yếu: Field Injection, Method Injection & Method Groups.
   - Các framework/tính năng ngoại vi (MapStruct, Protobuf, WebFlux routes, Spring Batch, SpEL phức tạp): Phân loại dứt khoát là `CapabilityGapRecord(UNSUPPORTED)` theo chuẩn của repo, không cố code chi tiết làm chậm tiến độ tiến sang M5 (Knowledge Graph) và M10 (Visual Workbench).

## Exit và exact next task

Done khi input-to-conditional-architecture journeys dùng providers thật, mỗi claimed-supported fragment có positive/negative evidence, actual targets/bindings tăng với đúng provenance, old gaps chỉ closes qua evidence và false-certainty controls xanh. Standalone helper không được pipeline dùng chưa đủ done.

Handoff [V2.3](03-integration-and-acceptance.md): fact/context/region API, pack registry, effect footprints, witness replay entry point, case/rule/test map, commands/results và residuals. Broad coverage, external comparison và G3 vẫn cần nghiệm thu.
