# M4 Universal v2 — coverage obligations

**PROVISIONAL design catalog `m4uv2-coverage:v1`, 2026-09-23.** Đây là danh mục công việc và bằng chứng cần có, không phải bảng tính năng đã implement. [Task index](../tasks/m4-universal-v2/README.md) định nghĩa V2.1–V2.3. [Protocol](../research/m4-universal-v2-evaluation.md) quy định cách chấm.

Mỗi dòng phải có fixture dương, fixture âm/thiếu evidence, mapping đến output/gap và kết quả tích hợp. Một dòng có nhiều biến thể phải được tách thành subcase IDs trong manifest trước khi chấm; không đánh dấu cả dòng pass chỉ nhờ một happy path. Nhận diện được hoặc xuất một gap chỉ chứng minh accounting; chưa đạt acceptance năng lực của dòng đó.

Các lane: **P** = đọc/phân tích thụ động; **I** = import bundle/artifact có lineage; **S** = semantic summary/version pack có oracle. Lane cuối chưa có evidence thì vẫn còn obligation, không tự chuyển thành thành công. Mọi dòng cũng có failure/recovery controls ở V2.1/V2.3; V2.3 chịu trách nhiệm corpus acceptance.

## A. Repo, filesystem và cấu trúc

| ID | Biến thể bắt buộc được tính đến | Output tối thiểu có ích và proof để đi sâu | Task/lane |
|---|---|---|---|
| R01 | Empty, docs-only, non-Java | Inventory đầy đủ hoặc frontier; Java N/A, không phải Java success | V2.1 P |
| R02 | Repo sinh viên, file rời, default package, src tùy ý | Root candidates, source spans, declarations; ownership có bằng chứng trước exact request | V2.1/V2.2 P |
| R03 | Maven/Gradle mono- và multi-module, nested roots | Module/source-set inventory, dependency contexts riêng, tất cả roots được account | V2.1 P |
| R04 | Monorepo nhiều build roots, backend/frontend, examples | Context partition và cross-root evidence refs; không union classpath | V2.1/V2.3 P |
| R05 | Kotlin/Scala/Groovy/JS/Python/C#/C/C++ bên cạnh Java | File/language/component boundary; JVM binary API dùng I nếu có; không giả semantic coverage ngôn ngữ khác | V2.1/V2.3 P/I |
| R06 | Test, integrationTest, test fixtures, benchmark, generated, vendored | Membership/role riêng; production không nhiễm test dependency | V2.1 P/I |
| R07 | Submodule, LFS pointer, sparse/shallow export, missing vendored tree | Content availability và absent subtree obligations; không tự fetch target URL | V2.1 P/I |
| R08 | Symlink, junction, hardlink alias, path escape, case collision | Canonical containment/alias policy, no link traversal ngoài selection, collision rows | V2.1 P |
| R09 | Unicode/NFC/NFD, Windows paths, long paths, LF/CRLF, BOM | Byte identity, portable path identity và exact span controls | V2.1/V2.2 P |
| R10 | Mixed charset, malformed bytes, source đang đổi | Declared encoding hoặc policy có nhãn; mutation detection, không host-default guess | V2.1/V2.2 P |
| R11 | ZIP/JAR/WAR/EAR/fat JAR/nested archive, class-only | Inventory/resources/class structure theo bounded expansion và artifact chain | V2.1 P/I |
| R12 | Permission denied, disk full, timeout, OOM, corrupt file, huge repo | Scoped outcome, durable checkpoint/frontier, partial report/recovery | V2.1/V2.3 P |

## B. Build, dependency và platform

| ID | Biến thể bắt buộc được tính đến | Output tối thiểu có ích và proof để đi sâu | Task/lane |
|---|---|---|---|
| B01 | Maven parent/relative parent, aggregation khác inheritance | Effective declarations với origin; không nhập nhằng module và artifact | V2.1 P |
| B02 | BOM/import/dependencyManagement, scopes, exclusions, optional, classifiers | Exact resolved graph và ordered artifacts cho MAIN/TEST | V2.1 P |
| B03 | Profiles property/file/JDK/OS, compiler toolchains | Context được chọn có activation evidence; alternatives không trộn classpath | V2.1 P/I |
| B04 | Version ranges, dynamic selectors, timestamped SNAPSHOT | Metadata snapshot/lock + resolver policy -> exact chosen coordinates/hashes; thiếu selection proof vẫn pending | V2.1 P/I |
| B05 | Relocation, duplicate GAV/class, split packages, shaded artifacts | Coordinate lineage, class origin và collision/shadow evidence; không dùng JAR obsolete | V2.1 P/I |
| B06 | Gradle Groovy/Kotlin settings, includes, subprojects, conventions | Safe declarative fragment có grammar; unsupported expressions có footprint | V2.1 P |
| B07 | Gradle catalogs, aliases, bundles, platforms, constraints, locks | Separate declared selectors/resolved versions/variant/artifact/order | V2.1 P/I |
| B08 | Gradle buildSrc, plugin logic, composite/included builds, custom source sets | Detect requirements; literal facts giữ lại; import resolved model khớp snapshot cho dynamic remainder | V2.1 P/I |
| B09 | Variants, attributes, capabilities, dependency substitution | Exact selected configuration + variant evidence, không dùng Maven mediation thay Gradle | V2.1 I/S |
| B10 | Ant/Ivy, Eclipse .classpath, IntelliJ modules, NetBeans, lib/ | Passive literal root/classpath extraction + qualified explicit bundle | V2.1 P/I |
| B11 | Bazel/Buck/Pants/sbt/Make/custom scripts | Recognize build islands, literal source declarations khi chứng minh được, generic source-plan import cho evaluation-dependent models | V2.1 P/I |
| B12 | Local JAR, systemPath, provided servlet/server APIs, private/offline cache | Read only selected paths/caches; content hashes, local origin, unavailable exact refs | V2.1 P/I |
| B13 | Java legacy <=7, 8, 11, 17, 21, 25/26, newer/preview | Syntax, bytecode target, platform API riêng; verified provider/tuple hoặc structural recovery | V2.1/V2.2 P/I/S |
| B14 | rt.jar/JMOD/ct.sym, Android bootclasspath, custom vendor image | Exact platform-symbol view; host JDK không thay target | V2.1 P/I |
| B15 | Multi-release JAR, module-info/JPMS, automatic module | Release-selected entries, readability/export evidence; classpath mode không giả module-path mode | V2.1/V2.2 P/I |
| B16 | Reactor sources/output directories, stale/missing output | Source/binary lineage, freshness/conflict; missing output không xóa handwritten siblings | V2.1 P/I |
| B17 | Manifest Class-Path, OSGi bundle metadata, servlet WAR libraries | Declared runtime resolution context và evidence; không tự append tùy tiện | V2.1 P/I/S |
| B18 | Network intermittent, absent repository, unauthorized private feed | Bounded retry, locked replay, user-supplied artifacts; credentials không ngầm đọc | V2.1 P/I |

## C. Java semantics và generated code

| ID | Biến thể bắt buộc được tính đến | Output tối thiểu có ích và proof để đi sâu | Task/lane |
|---|---|---|---|
| J01 | Đủ 18 relationship families M2 | Occurrence denominators, correct targets/origin/spans; regression giữ golden IDs | V2.2 P |
| J02 | Overload, generic bounds/wildcards/raw types, bridge methods | Exact declaration/signature, substitutions và invocation evidence | V2.2 P/I |
| J03 | Lambda, method/constructor/array references, var, catch/multi-catch | Functional target/type evidence; không bỏ occurrence khi inference thiếu | V2.2 P |
| J04 | Nested/local/anonymous/enum classes, implicit constructors | Lexical owners/identity, explicit-vs-derived facts, no fabricated spans | V2.2 P |
| J05 | Records, sealed, patterns, switch/text blocks, annotations/defaults | Version-qualified syntax + attribution controls riêng | V2.2 P/S |
| J06 | Lombok constructors/Data/Value/Builder, inherited config, generics | Generated declarations tham gia resolver; synthesis đủ preconditions hoặc exact imported output | V2.1/V2.2 P/I/S |
| J07 | SuperBuilder/With/Delegate/loggers, custom Lombok versions/options | Pack-specific synthesis hoặc generated artifacts; không suppression/guess làm mất fact | V2.1/V2.2 I/S |
| J08 | MapStruct, protobuf/gRPC, QueryDSL, JPA metamodel, Immutables, AutoValue | Detect generation recipe, ingest output + lineage, resolve clients; processor không chạy | V2.1/V2.2 P/I |
| J09 | JSP/JAXB/wsimport/OpenAPI/Thrift/custom processors | Generated Java/API inventory với source/generator association; source map nếu có | V2.1/V2.2 P/I |
| J10 | Binary-only dependencies, obfuscation, missing debug metadata | Class/member/annotation structure; absent source span là absent, không decompile thành source thật | V2.1/V2.2 P/I |
| J11 | Broken file/uncompilable project, unresolved imports, missing JDK | Retain verified local declarations; qualified structure, exact lane withheld đúng footprint | V2.1/V2.2 P |
| J12 | Reflection literal names, MethodHandle, ServiceLoader, JNI | Proven literal targets/services hoặc candidate effects; dynamic/native boundary explicit | V2.1/V2.2 P/I/S |

## D. Configuration và Spring toàn chuỗi

| ID | Biến thể bắt buộc được tính đến | Output tối thiểu có ích và proof để đi sâu | Task/lane |
|---|---|---|---|
| S01 | properties/YAML/multi-document/profile/precedence | Effective value/domain + source origin + shadowing explanation | V2.2 P |
| S02 | Config import, optional imports, relative locations, configtree | Bounded local/import closure, precedence, cycles; external loader yêu cầu supplied envelope | V2.2 P/I |
| S03 | Env/CLI/system properties/config service/secrets | Explicit supplied deployment envelope, absent vs unknown; không lấy env máy analyzer | V2.2 I |
| S04 | Placeholders/default/relaxed binding/ConfigurationProperties | Property lookup/coercion relevant to condition/injection, type/source proof | V2.2 P/S |
| S05 | Profiles groups/default/includes, property predicates, OTHER | Finite abstraction phân biệt mọi predicate trong fragment, proof/counterexample | V2.2 P/S |
| S06 | Direct/composed stereotypes, AliasFor/default/repeatable/inherited | Annotation graph từ source và binary -> names/conditions/qualifiers chính xác | V2.1/V2.2 P/S |
| S07 | Scan roots, include/exclude filters, name generator, bootstrap roots | Membership/order context proof; custom matcher không được mặc định include | V2.2 P/S |
| S08 | @Bean, @Import, nested configurations, imports metadata | Producer/container/return type/conditions -> M4C plan | V2.2 P/S |
| S09 | Constructor/field/method/@Bean parameter, optional/lazy/provider/aggregate | Complete descriptors, generics/qualifiers/name flags, registration-qualified binding | V2.2 P/S |
| S10 | Primary/fallback/priority, aliases, override, hierarchy | Exact version/order semantics, ambiguous candidates vẫn explicit | V2.2 P/S |
| S11 | Legacy Spring XML, import, profiles, ref/id/alias, namespaces | XML producers/conditions/references/containers; safe local schema parsing | V2.2 P/S |
| S12 | spring.factories, AutoConfiguration.imports, resource conditions | Exact library resources + annotation/ordering metadata -> registry | V2.1/V2.2 P/S |
| S13 | Spring Data JPA/Mongo/JDBC/R2DBC/Redis/Elasticsearch/Cassandra/Neo4j/Couchbase, reactive | Enable/store/scan/filter/NoRepositoryBean/fragments proof; candidate khác selected bean | V2.2 P/I/S |
| S14 | FactoryBean, scoped/AOP proxies, lookup method, resolvable dependencies | Product/exposed type + lifecycle qualification; không tạo fake implementation class | V2.2 P/I/S |
| S15 | Custom Condition/selector/registrar, BDRPP/BFPP/BPP | Bounded pure/effect summaries; dynamic residual taints đúng affected outputs | V2.2 P/I/S |
| S16 | Legacy javax và jakarta; Framework 1–7 / Boot 1–4 and future | Exact tuple/fragment packs theo evidence, historical fixtures; new tuple không auto certify | V2.2 P/I/S |
| S17 | MVC composed/inherited mapping, WebFlux annotation/functional router | Endpoint conditions/path/method evidence, controller binding; không gọi dependency path là runtime trace | V2.2 P/S |
| S18 | Scheduled/async/events/listeners, messaging, batch/integration/security configuration | Entry-point and component/dependency evidence theo extension pack; no runtime-delivery guarantee | V2.2 P/I/S |
| S19 | JPA/entity/transaction, MyBatis mappers, Feign clients, external client factories | Evidenced resources/data/client boundaries và generation facts; no SQL effect/running service invention | V2.1/V2.2 P/I/S |
| S20 | SpEL constants/property expressions, bean/type/method access, scripts | Safe grammar/coercion fragment + symbolic dependencies; remainder effect envelope | V2.2 P/S |
| S21 | Parent/child contexts, multiple applications/tests, web modes | Per-container visibility/shadowing/order, distinct world IDs | V2.2 P/S |
| S22 | AOT/native hints/runtime bean reports/traces supplied by user | Import provenance/config/run coverage; evidence of one world cannot prove universal absence | V2.1/V2.2 I |
| S23 | Non-Spring Java EE/CDI/Guice/Quarkus/Micronaut/OSGi applications | Java architecture/dependency/entry metadata retained; framework DI truth only with separately validated pack | V2.2/V2.3 P/I/S |

## E. Reasoning, reporting và acceptance

| ID | Biến thể bắt buộc được tính đến | Output tối thiểu có ích và proof để đi sâu | Task/lane |
|---|---|---|---|
| Q01 | >50 dimensions, many signatures, correlated values | Finite SAT with residual accounting, exhaustive small-space equivalence | V2.2 S |
| Q02 | Unknown condition/order/side effects | Footprint proof, order witness, correlated opaque operands, no false MUST/NEVER | V2.2 S |
| Q03 | Imported evidence stale/wrong/conflicting | Reject/quarantine scope, conflict record, invalidate derived facts | V2.1 P/I |
| Q04 | Same-input replay, permutation, worker restart, cold/warm artifacts | Equal canonical IDs/gaps; telemetry separate; captured closure required | V2.1/V2.3 P |
| Q05 | Useful architecture with partial input | Module/type/dependency/entry point paths, certainty/condition overlay, exact source drilldown | V2.3 P |
| Q06 | Thousands of downstream unknowns from one cause | Root cause + distinct affected obligations + next requirement; history giữ nguyên | V2.1/V2.3 P |
| Q07 | Cross-world path/cycle, unknown health, evidence loss | Infeasible paths rejected; health withheld; evidence loss không thành architecture improvement | V2.2/V2.3 S |
| Q08 | New unclassified mechanism/build shape | Catch-all obligation + fixture + owner + intake result; catalog version tăng, không exclude để tăng điểm | V2.1/V2.3 P |

## Phủ tổ hợp và chống bỏ sót

Không có bảng hữu hạn nào chứng minh đã liệt kê mọi chương trình có thể viết. Vì vậy coverage registry là mở rộng có version và có catch-all, còn denominator của mỗi lần đánh giá là khép kín. Bắt buộc có cả positive/negative control cho tương tác sau:

- Gradle Kotlin DSL + catalog + composite build + generated repository client.
- Maven parent/BOM + private dependency absent + module sibling vẫn phân tích được.
- Legacy XML + annotation scan + parent/child + javax Resource + alias shadowing.
- Lombok config + generic constructor + qualifier + conditional Spring Data store.
- Fat JAR + multi-release entry + composed annotation defaults + imports metadata.
- Multiple source roots/JDKs + duplicate FQN + test fixtures + main/test isolation.
- Config import/profile group + opaque condition + missing-bean registration order.
- Broken source + unsupported preview + worker failure + surviving independent module.
- Polyglot monorepo + unavailable submodule + no-Java island + explicit source-plan bundle.
- Huge finite space + many relevant signatures + early budget exhaustion + witness replay.

Các tổ hợp này là controls có chủ đích; V2.1 còn phải lập pairwise interactions theo những chiều có thể cùng tồn tại. Không đánh giá mọi tổ hợp giả tạo, không suy luận rằng pairwise testing chứng minh mọi interaction.
