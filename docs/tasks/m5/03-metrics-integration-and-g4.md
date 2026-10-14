# M5.3 — Metrics, end-to-end integration và bằng chứng G4

Trạng thái 2026-10-14: **IMPLEMENTED — bounded verification pass; catalog PROVISIONAL, G4 chờ adjudication**. Điều kiện vào M5.1–M5.2 đã có. [Contract thực thi](../../architecture/m5-architecture-metrics.md) và [bảng evidence/commands/handoff](../../research/m5-g4-bounded-evidence.md) là kết quả bàn giao. Các phần thiết kế dưới đây giữ yêu cầu baseline; không phải bằng chứng gate đã pass. Đọc [README](README.md), [product-outcome](../../architecture/product-outcome.md), [MetricEnvelope](../../../analyzer/src/main/java/com/evolution/analysis/contract/metrics/MetricEnvelope.java). Exit implementation: metric có ích, traceable; integration đầy đủ trong phạm vi bounded; package G4 cụ thể để review.

## A. Metric catalog và envelope

Package dự kiến: `com.evolution.analysis.metrics`. Mỗi catalog entry phải có ID/version, meaning/unit, scope, population, accepted relationship kinds, formula, aggregation/dedup, status/uncertainty policy, input refs và rounding. Tên catalog là proposed, không được tự gọi human-approved; ADR-002 vẫn yêu cầu duyệt baseline.

Giữ `MetricEnvelope` M1 và status rules. Tạo additive versioned context/result wrapper nếu cần: graph/projection identity, contributing analysis manifests, exact configuration/space/semantics, population và result-affecting budgets. Không gán một fake `ConfigurationIdentity` cho whole space; không ép metric multi-module thành kết quả của manifest đầu tiên. Nếu phải thay M1 serialized contract, trình migration cụ thể trước breaking change.

`COMPLETE` có value và đủ evidence; `PARTIAL` có value cùng giới hạn/uncertainty; `WITHHELD` không value vì thiếu evidence để tính có nghĩa; `NOT_APPLICABLE` không value vì công thức không áp dụng. Không thêm status cho tiện. `computedAt`/duration/memory là telemetry, không thay deterministic semantic digest.

## B. Catalog M5 và công thức bắt buộc

| Family | Quy tắc phải đóng trước code |
|---|---|
| Inventory | Module/dependencies, source roots/files/main/test/generated, packages, kinds of types/members/parameters, annotations/type uses, relationships theo category/origin/status; inclusion rules không mơ hồ |
| Physical LOC | Đếm trên decoded evidence; đề xuất LF/CRLF/CR là một terminator, dòng cuối không terminator vẫn một dòng, empty file=0, BOM không thêm dòng; file thiếu/undecodable ghi gap; không bịa logical LOC |
| Quality/provenance | Parse outcomes; attempted/emitted/resolved/unresolved/ambiguous/unsupported/omitted/error; full-span coverage và classpath completeness; provider attempts/conflicts/gaps theo reason. Correct/incorrect chỉ có nếu có ground truth |
| Ca/Ce | Đếm distinct neighboring packages/modules có dependency qua boundary, không đếm occurrences; internal same-component edges không tăng coupling |
| I/A/D | `I=Ce/(Ca+Ce)`; `A=Na/N`; `D=abs(A+I-1)`; Na=interfaces+abstract classes+annotation types; N gồm concrete enums/records |
| Fan-in/out, hotspots | Distinct neighbors theo scope/category; distribution và rank stable tie-break bằng identity; không hard-code ngưỡng “bad architecture” |
| Density | Directed simple component dependency graph, distinct nonself pairs / `n*(n-1)` trong cùng declared population; n<2 → NOT_APPLICABLE; external edges riêng nếu không ở denominator |
| SCC/cycles | SCC sizes/counts/participants tách feasible cycles và witness counts; cycle count chỉ exact khi M5.2 chứng minh complete |
| Crossings | Count distinct boundary edges và optionally occurrences, units tách rõ; layer crossings chỉ khi có versioned mapping, không có mapping → NOT_APPLICABLE |
| Depth/reachability | Bounded shortest-distance/reachable-scope summaries với frontier; không đặt tên là exact longest path trong cyclic graph |
| Concentration | Công thức proposed: tỷ phần dependency pairs theo target artifact/module và max-share, có catalog version + denominator; artifact coordinates chỉ từ evidence, unresolved bucket không biến mất |
| Spring | Components/stereotypes/configuration classes/producers/injection points/endpoints/candidate cardinalities/bindings; active count phân biệt discovered count; quantifiers, region/order gaps và activation explanation |
| Operational | Phase duration/failures và query/projection sizes có measurement provenance; latency/memory chưa đo thì không giả số hoặc tự đặt gate budget |

Martin metrics chỉ lấy **main production types**, loại test/generated/external khỏi internal N. Enums/records concrete, annotations abstract theo product contract. Đóng thêm population của dependency endpoints: mặc định đề xuất project MAIN nongenerated component graph; JDK/external/generated/test exposures trả riêng, không âm thầm trộn vào Ca/Ce. Tất cả population decisions phải ghi catalog và kiểm thử; không infer generated/main chỉ bằng directory name khi source plan đã có metadata.

`Ca+Ce=0` → I NOT_APPLICABLE; `N=0` → A NOT_APPLICABLE; một trong A/I không applicable → D NOT_APPLICABLE. Các kết quả này có value absent và reason, tuyệt đối không thay bằng 0 hoặc 1. Dùng BigDecimal, precision/rounding versioned; giữ exact numerator/denominator, không NaN/Infinity hoặc binary-double canonical output. Na dùng cùng filtered population với N, mỗi type identity đếm một lần; annotation type thuộc Na, annotation-use occurrence không thuộc Na/N.

## C. Conditional và partial metrics

1. Tính trên **full declared scope/projection**, không trên page/subgraph đã cắt cho UI. Nếu chỉ có bounded fragment, tên/scope/status phải nói rõ; không gọi truncated view là repository metric.
2. Realized metrics gắn assignment/order. MUST metrics là metric của MUST graph, **không** mặc nhiên bằng minimum mọi configuration. MAY/union metrics là exploratory structure, không phải metric một runtime world. Unknown overlay không cộng thành certain edges.
3. Không lấy average/uniform probability trên configuration space nếu chưa có distribution evidence. Không cần tính min/max mọi world trong M5.
4. Missing edges/types có thể làm ratio tăng hoặc giảm: không tự gọi I/A/D/density là lower bound. Trả PARTIAL chỉ khi partial value có nghĩa rõ; nếu denominator hoặc population không biết đủ thì WITHHELD. Gap không được biến thành zero dependency hoặc COMPLETE healthy result.
5. Semantic status, assessment status và configuration quantifier độc lập. Region/satisfiability UNKNOWN không bị đổi thành assessment NOT_APPLICABLE.
6. Không sinh health score, violation density, policy conformance hoặc confidence percentage chưa có mô hình. Cohesion chưa định nghĩa/validated được giữ ngoài supported catalog, không đổi nhãn một proxy thành cohesion.

## D. Golden tests và tích hợp thật

Suite mới đề xuất: `StructuralMetricsTest`, `InventoryQualityMetricsTest`, `ConditionalMetricContractTest`, `M5ArchitectureIntegrationTest`.

| ID | Expected result/control |
|---|---|
| M01 | 3 production packages P,Q,R; P→Q, R→P: P Ca=1, Ce=1, I=0.5. P có 1 interface+1 concrete class → A=0.5, D=0. Thêm 10 duplicate call occurrences không đổi Ca/Ce |
| M02 | Cùng graph 3 packages/2 distinct edges → density=2/6 theo rounding policy; self-edge không vào nonself numerator |
| M03 | Thêm test/generated/external types không đổi Martin Na/N; enum/record tăng N, annotation type tăng cả Na và N một lần dù có nhiều annotation uses; kiểm tra riêng I undefined, A undefined và propagation sang D: NOT_APPLICABLE + absent value + reason, không 0/1 |
| M04 | Missing classpath/failed file/ambiguous target hoặc truncated scope → PARTIAL/WITHHELD với input gaps, không clean zero hoặc score improvement |
| M05 | Inventory golden chứa nested/local/anonymous/generated/main/test, annotation/type-use và unresolved rows; số lượng reconcile nguồn, không lấy graph node count thay inventory |
| M06 | Empty/LF/CRLF/CR/BOM/final-unterminated/undecodable text → LOC và provenance đúng rule |
| M07 | Realized/MUST/MAY/union/unknown → metric context và interpretation khác nhau; impossible union cycle không counted như feasible cycle |
| M08 | SCC/cycle quota vượt → witnessesReturned khác exactTotal; feasible unknowns ngăn COMPLETE; fan-in/out và concentration tie-break ổn định |
| M09 | Duplicate/reorder/locale/timezone/serialization/store reload → semantic metric identity ổn định; formula/population/context/limit đổi → relevant identity/compatibility đổi |
| M10 | Compact multi-module Java/Spring source → actual pipeline → canonical graph → file reload → summary/search/path/activation → metrics → source evidence, không handcrafted graph thay integration |
| M11 | Partial counterpart của M10 với missing evidence và symbolic counterpart → giữ useful facts + root-cause gaps; không “mọi thứ UNKNOWN” để đạt kế toán |
| M12 | Metric computed từ full scope không đổi khi UI page size đổi; request scope/projection đổi thì context/results đúng, không reuse cache sai |

Hand-computed goldens là oracle độc lập, không gọi engine để tạo expected values. Property tests chỉ cho invariant có nghĩa (duplicate occurrence invariance, stable ordering, no cross-scope collision); không khẳng định mọi coupling ratio đơn điệu khi xóa evidence.

## E. Pre-handoff và G4

Bounded pre-handoff: các suite mới tại `analyzer` cùng `AnalysisAndAssessmentContractTest`, graph/query/store contracts M5.1–2; `M5ArchitectureIntegrationTest`, `UniversalArchitecturePipelineIntegrationTest`, `UniversalArchitectureM4EAdjudicationTest` tại `analyzer-javaparser` nếu integration boundary đổi; file-store parity tại `analyzer-filesystem`. Test nằm ở consumer module phù hợp; nếu integration cần filesystem adapter, chỉ thêm dependency test-scoped đúng chiều. Backend chỉ cần bounded consumer check nếu API/dependency của nó bị đổi.

Lệnh metric/integration dự kiến; chạy thêm bounded graph/query suites đã nêu và refresh các adapter bị đổi trước consumer:

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=StructuralMetricsTest,InventoryQualityMetricsTest,ConditionalMetricContractTest,AnalysisAndAssessmentContractTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M5ArchitectureIntegrationTest,UniversalArchitecturePipelineIntegrationTest,UniversalArchitectureM4EAdjudicationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Nếu M5 integration vừa dùng file adapter mới, package/install module `analyzer-filesystem` đã test trước command consumer; tương tự cho dependency thực sự thay đổi khác. Các lệnh trên là kế hoạch, chưa chạy trong lần viết tài liệu này.

Trước formal G4, lập bảng mỗi gate assertion → test/fixture → command → actual result → remaining limit. Đừng dùng tổng “tests green” thay nghĩa cần chứng minh:

| G4 obligation | Evidence tối thiểu |
|---|---|
| Identity, uniqueness, idempotency | G01/G02/G04/G10 + M1 golden regression |
| Provenance và closed denominator | G03/G06 + Q11 + M04/M05/M11 |
| Persistence round-trip/integrity | G07/G08, fresh store instance và query/metric parity |
| Conditional projections | G05 + Q01/Q05/Q10, explicit + symbolic |
| No infeasible path/cycle | Q02/Q03/Q04/Q06/Q07 + M07 |
| Resource bounds/pagination/cancellation | Q08/Q09 + M08/M12 |
| Metric correctness/versioning | M01–M09, exact ratios/status/populations |
| Product-facing traceability | M10/M11, summary/metric/path → canonical evidence |

G4 là lần đóng toàn milestone: chỉ lúc đó mới tạo formal reproducibility package, ghi exact commit/dirty state, input/tool/provider/schema/formula/limit versions và commands. Full reactor và một full-repository benchmark campaign chỉ chạy khi được authorize cho gate, unit/contract đã xanh và inputs/questions/expected outcomes đã đăng ký. Nếu chưa được authorize, hoàn thành toàn bộ bounded integration rồi ghi rõ phần gate chưa chạy; không tự bỏ yêu cầu và tuyên bố pass. Không tái dùng con số 37/37 của G3 làm graph/metric accuracy.

Independent review nếu có phải read-only và có authorization delegation; không có thì nêu limitation, không đổi role rồi tự gọi independent. Human review package cần cụ thể: schema/migration impact nếu có, metric catalog baseline, measured budgets hoặc lý do chưa freeze. Chỉ các quyết định thật sự còn mở mới cần duyệt.

## Exit và bước tiếp theo

- [x] Catalog phần M5, M01–M12 và G4 matrix có actual bounded evidence; gaps không bị che.
- [x] Graph/query/metric contracts và `knowledge-graph.md` phản ánh implementation; current-state ghi đúng completion/gate status.
- [x] Metric catalog approval và G4 adjudication tách khỏi implementation tests; G4 vẫn IN PROGRESS.
- [x] Handoff trong linked evidence package ghi trạng thái trước/sau, files, commands/results, evidence, decisions, limitations/blockers và exact next task.

**Exact next task:** nếu còn thiếu approval/evidence, trình chính package G4 và catalog để adjudicate; chỉ sau G4 đạt và có authorization phù hợp mới chuyển M6 conditional policy/evidence/assessment. Không tự triển khai M6 trong task này.
