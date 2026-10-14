# M5.1 — Canonical graph, evidence closure và persistence

Trạng thái: **CONFIRMED — đã triển khai và kiểm chứng ngày 2026-10-14**, bằng implementation/self-review và 85 bounded tests, không phải G4 acceptance. [Contract triển khai](../../architecture/m5-canonical-graph.md). Đọc [quy tắc chung](README.md). Exit: graph dựng từ facts upstream, kiểm tra invariant và persistence round-trip; metric engine/query traversal thuộc task sau.

## Đầu vào và điểm sửa

- Core: `analyzer/src/main/java/com/evolution/analysis/contract/{identity,semantic,analysis,serialization}`, `frontend/FrontendResult.java`, `evidence/`, `spring/{registration,binding,truth}`.
- Orchestration: [UniversalArchitecturePipeline](../../../analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalArchitecturePipeline.java), [UniversalIngestionPipeline](../../../analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalIngestionPipeline.java), report hiện có và integration tests trong `analyzer-javaparser`.
- Packages mới dự kiến: `com.evolution.analysis.graph` và port storage trong core; file adapter ở `analyzer-filesystem`. Core không import parser, Maven adapter, filesystem adapter hoặc database driver.
- Đọc `FrontendContractTest`, `FrontendResultTest`, `IdentityAndSourceContractTest`, `CanonicalSerializationTest`, `TruthRegionEvaluationTest` trước khi đổi boundary.

## Checklist triển khai

### A. Đóng input boundary, không dựng graph từ summary

1. Lập mapping từ module/source plan, manifest, declarations, occurrences, derived relationships, observation ledger, Spring plans/results và gap ledger sang graph. Ghi rõ field giữ nguyên, field dẫn xuất, field không có evidence.
2. Bổ sung entry point/result versioned giữ full typed artifacts trước `UniversalArchitecturePipeline` tạo summary. Giữ API report cũ tương thích; không parse Markdown/JSON summary, không chạy frontend/Spring lần hai chỉ để lấy dữ liệu bị bỏ.
3. Input bind snapshot, các analysis/source-set manifests, exact build contexts và Spring evaluation contexts. MAIN/TEST hoặc module có classpath khác phải phân vùng; graph repository có thể tổng hợp cấu trúc nhưng không coi mọi context là một world. Không chọn analysis ID đầu tiên làm identity toàn repository.
4. Giữ raw source evidence, declared/generated distinctions, failed/unprocessed sources, unmatched observations và acquisition attempts/conflicts. Có thể reference content-addressed payload thay nhúng lặp; reference phải resolve/verify được.

### B. Chốt mapping versioned tối thiểu

| Thành phần | Quy tắc |
|---|---|
| Repository/module/source root/package | Package có module/scope; source-root membership là evidence relation, không merge package cùng tên giữa module |
| Type/member/annotation/type-use | Reuse M1 identity và neutral records; phân biệt annotation type với occurrence sử dụng |
| Java relationship | Reuse relationship identity; giữ tất cả occurrence IDs/full spans; dedup relation không dedup mất evidence |
| Candidate/unresolved target | Giữ target variant và uncertainty; candidate links được gắn nhãn, không thành resolved dependency |
| Derived DEPENDS_ON | Versioned derivation từ relationship kinds đã liệt kê; giữ support IDs; không đếm chứa/khai báo như dependency |
| Spring | Producer, definition candidate, owner-qualified injection use, condition, registration transition, selected binding tách biệt |
| Region/witness/gap | Preserve canonical IDs, encoding, T/F/U/formulas, replay/order evidence và typed gaps; không dùng classification summary thay payload |

Mọi input fact có disposition kiểm chứng được: emitted, retained as evidence/gap, excluded-by-declared-projection hoặc withheld-with-reason. Không bắt buộc mọi ledger row thành một node UI; vẫn phải truy xuất được. `NEVER` còn trong canonical records để giải thích dù không xuất hiện như cạnh active.

Viết contract M5 versioned và cập nhật phần sketch mâu thuẫn của `knowledge-graph.md` khi mapping thực sự được triển khai. Tên property/node mới là quyết định có thể review, không retroactively sửa M1/R0 goldens.

### C. Identity và invariants

- Graph identity bind schema/version, complete input artifact IDs, exact contexts, derivation/projection policy và limits ảnh hưởng kết quả; result digest bind canonical payload. Không tạo vòng input → result → input.
- Cùng ID + cùng payload: import lại idempotent. Cùng ID + payload khác: conflict/integrity failure có provenance; không last-write-wins.
- Referential integrity của entity, occurrence, source/span, conditional context, region/formula, witness và ledger; dangling reference không được xuất như verified fact.
- External/JDK identities lấy từ scope/origin upstream; không tạo fake coordinates hoặc declaration spans. Implicit/generated members giữ owner/input evidence, không gán span giả.
- Kiểm tra cùng snapshot/build/space/semantics khi liên kết conditional objects; cross-context relation không được hợp thức hóa bằng FQN.
- Partial input giữ phần hợp lệ và typed gaps. Corrupt identity/hash/schema là integrity error, không bị nuốt thành empty-success; lỗi ở unit độc lập không làm mất unit hợp lệ.

### D. Storage port và hai implementation dùng chung contract

Port tối thiểu: write immutable snapshot, load by identity, get manifest/availability; không để Cypher hay SQL xuất hiện ở API domain. KnowledgeGraph và adjacency indexes là Pure Java trong `analyzer`; implement in-memory store và canonical JSON file store qua adapter. Serializer/deserializer trung lập ở core, filesystem I/O ở adapter. Không thêm Neo4j driver/server vào core hoặc dùng database/container cho unit tests và G4 round-trip. Kiểm tra cả dependency trực tiếp lẫn transitive của core khi review POM.

File format có version + digest + payload/reference closure; decode phải tái lập typed model, không chỉ trả generic JSON map. Đọc giới hạn bytes/depth/count, kiểm tra schema/hash/duplicate/reference. Ghi atomic vào thư mục caller cấp; path suy từ validated digest, chặn traversal; lỗi ghi/cancel không publish snapshot nửa chừng. Snapshot cũ bất biến, ghi lại cùng input không tạo bản sao khác nghĩa.

Round-trip phải kiểm tra **mô hình và evidence**, không chỉ node/edge counts: identities, candidate sets, full spans, derivation, context, T/F/U, witness/replay, gap/attempt/conflict records. Missing external payload phải báo explicit unavailable; không coi reference là evidence đã đọc.

Fixture round-trip đầy đủ phải kèm closure của referenced evidence để chứng minh lossless restore ở store instance mới, không nhờ object cache còn sống. GraphML có thể là export sau này; graph topology/labels đơn thuần không chứng minh preservation của canonical evidence/regions.

## Test bắt buộc

Các suite đã triển khai: `CanonicalGraphContractTest`, `CanonicalGraphBuilderTest`, `InMemoryGraphStoreTest`, `FileGraphStoreTest`, `CanonicalGraphPipelineIntegrationTest`.

| ID | Control / kỳ vọng |
|---|---|
| G01 | Cùng FQN ở hai module hoặc project/JAR khác origin → identities khác; overload/local/anonymous giữ identity upstream |
| G02 | Hai occurrences một CALLS → một relation, đủ hai spans/IDs; shuffled set-like input và rerun giữ digest |
| G03 | AMBIGUOUS/UNRESOLVED/UNSUPPORTED/ERROR và unmapped observation → denominator khép kín, không invented edge |
| G04 | Same source injection site dùng bởi hai bean owners → không mất contextual bindings |
| G05 | Explicit và symbolic outputs → giữ region encoding/replay/gap, không materialize toàn không gian |
| G06 | Mismatched snapshot/build/space, missing reference, conflicting ID → rejected/qualified đúng scope, không complete giả |
| G07 | Cả hai stores cùng contract; canonical JSON file load ở instance mới giữ canonical payload và evidence closure; chạy không database/container; core không có Neo4j dependencies |
| G08 | Corrupt/truncated/oversized/unknown-version file, interrupted write, unsafe path → lỗi typed, không overwrite bản hợp lệ |
| G09 | Compact Java/Spring fixture qua frontend/pipeline thật → graph; report API cũ còn chạy và không là source of truth |
| G10 | Thay provenance/region/schema/input limit ảnh hưởng kết quả → digest đổi; thay timestamp/traversal vô nghĩa không đổi semantic identity |

Dùng fixture nhỏ 5–15 dòng mỗi Java class. Test expected values viết độc lập; không lấy actual graph làm oracle. G03 phải có positive resolved facts để tránh implementation “mọi thứ đều gap”.

## Verification và exit

Chạy focused tests theo README, rồi bounded core set gồm suite mới + identity/serialization/frontend contracts. Chạy `FileGraphStoreTest` tại `analyzer-filesystem`; chạy `CanonicalGraphPipelineIntegrationTest` và `UniversalArchitecturePipelineIntegrationTest` tại `analyzer-javaparser`. Refresh dependency core trước consumer tests. Không full reactor.

Lệnh pre-handoff thực tế, tất cả exit 0:

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=CanonicalGraphBuilderTest,CanonicalGraphContractTest,InMemoryGraphStoreTest,IdentityAndSourceContractTest,CanonicalSerializationTest,FrontendContractTest,FrontendResultTest,AnalysisAndAssessmentContractTest,TruthRegionEvaluationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=CanonicalGraphPipelineIntegrationTest,UniversalArchitecturePipelineIntegrationTest,UniversalArchitectureM4EAdjudicationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Kết quả cuối: 57 core + 6 filesystem + 22 adapter = **85 tests / 13 suites**, failures/errors/skips đều 0. Có 24 test mới; journey Spring hiện có thêm graph assertions. Suite test time tương ứng 3.667 / 0.282 / 16.787 giây, không phải thời gian toàn command Maven. Core command cuối mất 7.651 giây; filesystem command mất 4.480 giây; tổng command adapter chạy bất đồng bộ không được đo riêng. Packaging/install dùng `skipTests` chỉ refresh dependency.

Bytecode IDE chứa unresolved-compilation stub đã được xử lý bằng JDK 21 `javac -encoding UTF-8 -proc:none --release 21 -cp analyzer/target/classes -d analyzer/target/classes` trên tất cả file trong package `graph` và `UniversalArchitecturePipeline.java`, trước artifact refresh và các consumer commands cuối. Đây là compilation có phạm vi, không phải clean/full build.

G01/G02/G03/G06/G07/G10 được bao phủ bởi builder/contract/store tests; G04/G05/G09 bởi source-to-graph integration (cùng FQN đa module, owner fan-out, explicit/symbolic regions, witness replay, partial parsing, acquisition bytes/attempts, revision provenance); G08 bởi file-store corruption/cancellation/bounds controls. Final checks là self-review, không independent review hay external corpus campaign.

- [x] G01–G10 có assertion và evidence pass; không zero/all-skipped tests.
- [x] Contract mapping/storage format được ghi, compatibility preserved, final diff reviewed.
- [x] `docs/current-state.md` ghi đúng graph đã có, query/metrics/G4 chưa xong.

**Exact next task:** M5.2, dùng graph/store đã kiểm chứng để làm region-safe projection/path/cycle queries.
