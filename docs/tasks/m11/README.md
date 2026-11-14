# M11 — Kế hoạch Architecture Evolution trên các snapshot tương thích

Ngày lập: **2026-11-14**. **M11.1 đã hoàn tất thiết kế và đăng ký ca kiểm; M11.2 đã triển khai so sánh cấu trúc nội bộ.** Hợp đồng public còn **PROVISIONAL**, G7 chưa được nghiệm thu. Xem [hồ sơ M11.1](01-execution.md), [hồ sơ M11.2](02-execution.md), [hợp đồng](../../architecture/m11-compatible-snapshot-evolution.md), [ADR-010](../../decisions/ADR-010-compatible-snapshot-evolution.md) và [protocol](PROTOCOL.md). Các đề xuất ban đầu dưới đây là lịch sử lập kế hoạch; nếu khác nhau, dùng hợp đồng, registry và hồ sơ thực hiện.

**CONFIRMED — quyết định human đã ghi trong repository:** [M10 đã CLOSED / APPROVED](../m10/01-release-sign-off.md); Track B được mở khóa. Không cần xin lại quyền bắt đầu M11. Kế hoạch này cụ thể hóa phạm vi M11 trong [roadmap](../../roadmap.md), không thay đổi quyết định Track A, các bảo lưu release hay phạm vi M12/Track C.

## 1. Kết quả M11 phải mang lại

Người dùng chọn hai snapshot và thấy **kiến trúc thay đổi gì, trong context/vùng cấu hình nào, dựa trên bằng chứng nào, và kết luận nào chưa thể đưa ra**. Từ một delta phải truy được hai phía: source → semantic fact/binding → graph → rule/finding → metric/score, cùng provenance và gaps.

M11 phải trả lời được:

1. Hai snapshot có so sánh được không; so sánh được những phần nào và tại sao?
2. Entity, dependency, binding hoặc kiến trúc có điều kiện đã thêm, mất, thay đổi hay chỉ đổi evidence?
3. Vi phạm thực sự được introduced/resolved/persisted/reintroduced, hay chỉ biến mất vì mất dữ liệu, thay policy hoặc thu hẹp không gian cấu hình?
4. Vùng bị ảnh hưởng và `MUST/MAY/NEVER/UNKNOWN` thay đổi thế nào? Có witness/counter-witness kiểm lại được không?
5. Metric/score thay đổi bao nhiêu, trên cùng định nghĩa và population nào? Có đủ điều kiện kết luận cải thiện hay phải withheld?
6. CLI, API, export và Workbench có cùng kết luận canonical, tái hiện được sau restart không?

**Exit cuối:** G7 có corpus lịch sử gắn nhãn, tái hiện đúng các sự kiện ẩn theo cấu hình và vùng ảnh hưởng; phân biệt drift của analyzer/build/configuration/evidence với thay đổi repository; mất evidence không được biến thành sửa xong vi phạm.

## 2. Baseline, nguồn thẩm quyền và giới hạn đã biết

### 2.1. Ba baseline phải tách biệt

| Baseline | Điều đã xác nhận | Cách sử dụng trong M11 |
|---|---|---|
| Track A release được chấp thuận | M10 ký duyệt JAR `2b099a777de93dd976c58bdbb1be5e91c9e33d2dfdc119476c73440ec66de84b` và các bảo lưu | Là mốc acceptance lịch sử, không tự đại diện working tree hiện tại |
| Working tree ngày lập kế hoạch | HEAD `c5517c3`, nhiều thay đổi chưa commit về reuse, graph/storage, Workbench và tài liệu; đã kiểm status/diff | Giữ nguyên công việc có sẵn; M11.1 xác định baseline phát triển có commit + patch/source digest khi cần |
| M11 evaluation candidate | Chưa có | Freeze đúng bản và toàn bộ input/version/policy trước campaign G7; không gán kết quả M9 cho candidate mới |

Các khẳng định implementation dưới đây là **CONFIRMED — đọc mã/tài liệu**, không phải test mới chạy trong lượt lập kế hoạch:

| Nền tảng hiện có | Hệ quả thiết kế |
|---|---|
| `RepositorySnapshot` hash inventory; revision/dirty là provenance. `AnalysisManifest` bind source/snapshot, ordered classpath, configuration, analyzer, rules, schema | Không lấy commit SHA, nhãn branch hay timestamp làm identity đầy đủ của phân tích |
| `EntityIdentity` dùng origin + stable scope + kind + canonical name; `ModuleIdentity` dùng repository + relative path; dependency/JDK scope bind artifact bytes | Entity có thể ổn định qua sửa source, nhưng đổi module path/artifact có thể đổi identity; cần correspondence riêng |
| `ArchitectureQueryService.comparisonPreparation` kiểm chính sách/context sơ bộ, chưa có delta; descriptor hiện chủ yếu nhận `analysis.manifest` và `spring.context` | Tái sử dụng kiểm tra hiện có như đầu vào; không xem Boolean `compatible` là đủ cho M11, nhất là polyglot/bridge |
| `PolicyEvaluation.Finding.key()` chứa snapshot, context, policy và route/edge IDs | Không so trực tiếp key này để nhận persisted/reintroduced qua snapshot; giữ key cũ, thêm correspondence evolution có version |
| M4/M5 đã có explicit/symbolic truth regions, witness, bounded reasoning và provenance | Dùng lại ngữ nghĩa T/F/U; không xây solver hay Spring engine thứ hai |
| `PartitionedGraphReader` có partition/node/adjacency pages; semantic query/metric/policy còn một số đường materialize typed graph | Thiết kế đọc/đối sánh theo trang ngay từ đầu; không quảng cáo toàn bộ consumer đã streaming |
| Result reuse chỉ replay đầu vào bằng nhau; graph `semanticIdentity()` là phục vụ replay trong hợp đồng của nó | Không dùng reuse hoặc semantic digest làm engine evolution, bằng chứng tương thích hay phép đo chất lượng |
| Generic stored-graph API không luôn có scan-level `evidenceTier` | Giữ trust/provenance ở từng fact/context, receipt nếu có; thiếu tier được báo unavailable, không suy ra `VERIFIED_EXACT` |

**CONFIRMED — sai lệch ghi nhận:** bảng M9.5 có 17 claim: 13 dòng `PASS`, 2 `SCOPED_PASS_WITH_RESERVATIONS`, 2 `NOT_RUN_CAPTURE_UNAVAILABLE`; phần summary M9/M10 ghi 14 PASS nên tổng nhãn thành 18. M11.1 ghi reconciliation theo từng claim ID và tham chiếu nguồn; không sửa raw evidence, không coi lỗi cộng số là thu hồi M10. Một số đoạn trạng thái cũ còn nói M9/M10 pending; opening/current next task và sign-off mới hơn sở hữu trạng thái hiện hành.

### 2.2. Các bảo lưu phải kế thừa

- Passive recovery `950‰` là predicate completion được human duyệt, **không phải ngưỡng chính xác của delta hay chứng minh absence**. Một scan `COMPLETE` vẫn có thể thiếu dữ kiện để kết luận `RESOLVED`.
- Envelope scale đã nghiệm thu của Track A còn giới hạn; các repo lớn bị hoãn không tự trở thành đã pass trong M11. Ngược lại, giới hạn thực nghiệm đó không được biến thành trần nhận repository của sản phẩm.
- Native execution giữ Core MVP/trust boundaries hiện hành. B01/D01 thiếu capture vẫn là chưa chạy; không tạo VM/campaign mới chỉ để lấp lịch sử đã được bảo lưu.
- Automated UI checks không chứng minh đã hoàn tất manual screen-reader/user trials. M11 kiểm các flow mới và báo rõ phương pháp đã thực hiện.
- Polyglot syntax/native/bridge producer evidence giữ đúng mức tin cậy. `BOUND` hoặc declaration-compatible không có nghĩa runtime communication hay semantic qualification đầy đủ.

Nguồn hợp đồng: [human mandate](../../architecture/comprehensive-repository-coverage-problems.md), [conditional semantics](../../architecture/conditional-architecture-semantics.md), [M5 graph](../../architecture/m5-canonical-graph.md), [query](../../architecture/m5-architecture-queries.md), [policy evaluation](../../architecture/m6-policy-evaluation.md), [assessment](../../architecture/m6-policy-metrics-and-assessment.md), [polyglot](../../architecture/m8-polyglot-semantics.md), [bridges](../../architecture/m8-architecture-bridges.md), [product outcome](../../architecture/product-outcome.md), [test selection](../../testing.md).

## 3. Phạm vi, non-goals và các nguyên tắc không được cắt

**Trong M11:** pair comparison; chuỗi lịch sử hữu hạn có lineage rõ; semantic/structural facts, conditional regions, Spring bindings, evidence/gaps, policy findings, metrics và health score; nhiều build-context trong một repository, polyglot và bridges theo dữ kiện đang có; persistence/query/CLI/API/export/UI; corpus lịch sử có ground truth và G7.

**Không tự đưa vào M11:** incremental reanalysis/selective reconstruction, automatic rename/refactor inference, architecture blame/causal attribution nâng cao, lịch sử quy mô lớn, cross-repository deployment federation, runtime assurance, AI diagnosis/RAG, tự sinh patch và Verified PR. Một mapping có bằng chứng để so sánh snapshot không phải triển khai refactoring engine. Build-context alignment không phải federation nhiều thế giới runtime.

1. Giữ M1–M8 identities và snapshot đã lưu bất biến. Evolution dùng envelope/index riêng có version; không sửa graph cũ để dễ match.
2. Phân biệt **artifact identity**, **logical correspondence**, **comparison compatibility**, **evidence sufficiency** và **operational completion**. Không thay bằng một Boolean hoặc điểm confidence tự đặt.
3. Không dùng FQN, source span, text similarity hoặc Git rename heuristic riêng lẻ để chứng minh cùng entity/finding. Ambiguous phải hiển thị candidates và gap.
4. Không xuất hiện trong output không đồng nghĩa false/absent. Cần coverage/denominator thích hợp, rule còn áp dụng và absence evidence.
5. Không trộn main/test, variants, modules, language identities hay configuration worlds. Mỗi partition phải có disposition, kể cả không match/không được chọn.
6. Không rút gọn UNKNOWN thành clean; không giảm denominator để làm score đẹp. Tăng evidence và sửa kiến trúc là hai loại thay đổi khác nhau.
7. Không áp tổng-repo/file/history cap làm điều kiện loại bỏ đầu vào. Page/working-set/solver budgets vẫn bắt buộc, với checkpoint/continuation và residual rõ.
8. Target builds/indexers chỉ qua executor hợp lệ. Đọc snapshot đã lưu là mặc định; thiếu input không kích hoạt host fallback hoặc tự thực thi target.

## 4. Thiết kế đề xuất cần chốt trong M11.1

Toàn bộ tên type/status mới trong phần này là **PROVISIONAL**, chưa phải public schema hay enum đã được phê duyệt. Chốt hợp đồng trước khi viết production; chi tiết implementation đảo ngược được thuộc quyền tự chủ kỹ thuật.

### 4.1. Ranh giới và dữ liệu

```text
Immutable snapshot/graph/report/evidence readers
  -> authenticated input descriptor + complete context census
  -> compatibility assessment + context/symbol/rule correspondence
  -> fact/region/binding/evidence delta
  -> finding lifecycle + metric/score comparison
  -> immutable comparison result + durable progress/publication
  -> stable evolution query service -> CLI / API / export / Workbench
```

Domain thuộc `analyzer`, persistence adapter thuộc `analyzer-filesystem`, orchestration/HTTP thuộc `backend`, command thuộc `cli`. Có thể thêm package `com.evolution.analysis.evolution`; không cần module/service/database mới khi chưa có nhu cầu đo được. Reader port chỉ cung cấp dữ kiện canonical đã xác thực; storage/SQL và frontend không sở hữu matching, region arithmetic hay lifecycle semantics.

| Hợp đồng đề xuất | Dữ liệu tối thiểu / trách nhiệm |
|---|---|
| `SnapshotDescriptor` | Repository/snapshot/graph IDs; context census; manifests; evidence/report roots; input availability; exact versions/digests; optional receipt và revision provenance |
| `ComparisonRequest` | Left/right direction; selected contexts/populations/projections; correspondence policy/mappings; rule/metric/score selections; result-affecting work policy; optional explicit history |
| `CompatibilityAssessment` | Từng dimension và từng capability: comparable, qualified, incompatible hoặc unknown; reason/evidence; allowed/withheld operations, không chỉ verdict toàn cục |
| `CorrespondenceMap` | Left/right identity, loại đối sánh, evidence, candidates/conflict, mapping version/digest; complete unmatched census; explicit operator mapping nếu được phép |
| `EvolutionEvent` | Event identity/kind; before/after references; subject correspondence; truth/evidence status; affected region; witness/replay; attribution qualifiers và gaps |
| `ComparisonResult` | Request/input/version identity; immutable events/index roots; coverage/denominators; completed/residual work; per-family compatibility; result status và canonical digest |
| `HistorySelection` | Ordered snapshots/ancestry hoặc explicit sequence; branch/parent relation; coverage interval; missing intervals và matching policy |

Comparison identity bind đầy đủ hai input roots, reports, direction, selection, mapping, evolution schema/algorithm, analyzer/provider/rule/model semantics và result-affecting budgets. Timestamps, job IDs, locale, absolute host paths và wall-clock interruption không được làm thay đổi semantic digest. Operational receipts lưu riêng; checkpoint/interrupted prefix không được giả làm complete canonical result. Cùng request/input đã freeze phải replay cùng event set/digest; page size chỉ đổi transport, không đổi completed domain result.

### 4.2. Tương thích: kiểm theo capability, không yêu cầu hai snapshot giống hệt

Một source edit hợp lệ đương nhiên đổi snapshot, context hoặc region ID. So sánh tương thích không thể đòi mọi digest bằng nhau. Ngược lại, cùng version string không chứng minh tool bytes/model semantics bằng nhau.

| Dimension | Xử lý đề xuất |
|---|---|
| Repository identity/lineage | Cùng repository được chứng thực; URL/path giống nhau chưa đủ. Fork/relocation cần mapping có provenance; không mặc định cross-repo continuity |
| Graph/manifest/identity schema | Giữ reader đúng version; unsupported/corrupt input bị từ chối theo scope. Migration chỉ qua adapter versioned đã được duyệt, tạo artifact mới |
| Analyzer/provider/extractor/tool bytes | Drift chặn unqualified repository-change claim; có thể hiển thị observation delta kèm drift. Muốn đánh giá source change, phân tích lại hai source snapshots bằng cùng analyzer khi input cho phép |
| Build root/module/source set/variant/platform/classpath | Ghép context có bằng chứng, giữ order và artifact hashes. Source-bound hash đổi là bình thường; dependency/JDK/variant drift phải được nêu riêng, không xóa khỏi preimage để ép compatible |
| Config space/domains/constraints/envelopes | So trên miền tương ứng được chứng minh; miền mới/mất tách riêng. Không loại tất cả source-config edits, cũng không coi mọi `prod` là cùng world |
| Framework/condition/registration semantics | Version hoặc order semantics thay đổi làm qualified/blocked các kết luận bị ảnh hưởng; không mặc định framework upgrade là sửa source |
| Rules/selectors/severity | Rule identity/semantic predicate/version phải tương ứng; policy removal/change là policy drift, không tự resolve vi phạm |
| Metric/score | Kiểm definition/formula/model/parameter digest, scope, population, projection, applicability, precision/units và denominator semantics |
| Solver/matching/result-affecting limits | Bind work policy; budget khác có thể đổi UNKNOWN/coverage. Page/viewport/timing không trở thành thay đổi kiến trúc |
| Evidence/trust/completion | Byte integrity bắt buộc; evidence lost/gained/conflicted và qualification changes độc lập với semantic delta. Artifact thiếu không được chứng minh fact removed |

Hai chế độ sản phẩm: **strict comparable** cho kết luận evolution đủ điều kiện; **qualified observation comparison** cho dữ kiện từng phía và drift explanations. Chế độ qualified không phải nút `force` bỏ kiểm tra, không cho badge “fixed” hoặc score improvement khi chưa có căn cứ. Các phần tương thích vẫn được dùng nếu phần khác bị withheld; tuy nhiên dependency closure phải chứng minh phần được dùng không chịu ảnh hưởng của phần thiếu.

Attribution dùng các chiều độc lập: repository input changed, build/configuration changed, analyzer/provider changed, policy/model changed, evidence changed. Hai hay nhiều chiều cùng đổi phải được báo **mixed/unattributed**, không tự kết luận nhân quả. Controlled reanalysis là phép đo bổ sung, không sửa lịch sử hoặc tự chứng minh toàn bộ nguyên nhân.

### 4.3. Correspondence entity, relationship, finding

Thứ tự ưu tiên: exact stable identity trong scope hợp lệ → canonical language symbol dưới context/module mapping đã chứng minh → explicit evidenced mapping. Mỗi mapping phải nhất quán một-một ở mức entity đang so; split/merge hoặc nhiều ứng viên được biểu diễn riêng và không tự chọn bằng điểm similarity.

- Java giữ origin, module, kind, full signature/overload/generic semantics cần thiết; declaration, occurrence và supporting evidence là ba tầng khác nhau.
- Polyglot dùng logical-symbol contract của engine, language/dialect/root/module/source set/variant và owner/kind/signature. Provider upgrade không tự đổi tên logical declaration; syntax record không tự nâng thành resolved callee.
- Move file nhưng symbol/scope không đổi: có thể giữ entity correspondence và báo source/provenance relocation. Rename package/module/signature chưa có chứng cứ continuity: báo unmatched add/remove observations và mapping gap; không tuyên bố semantic rename.
- Relationship key dùng mapped endpoints + kind/direction + semantic discriminator; occurrence multiplicity và support deltas giữ riêng. Unresolved target không match bừa với một resolved target cùng text.
- Finding correspondence dùng rule semantic identity + mapped subject/route/predicate; giữ directed cycle semantics, canonical rotation, không gộp reverse cycles. Thay supporting span/evidence không tạo violation mới nếu logical predicate và region vẫn tương ứng.
- Bridge delta giữ hai endpoint contexts, protocol/contract identity, binding/compatibility/trust state. Mất descriptor/producer evidence tạo uncertainty, không suy ra service dependency đã bị xóa.

### 4.4. Conditional regions, bindings và witness

Sau khi có mapping variables/domains/feasibility/order worlds được chứng minh, biểu diễn các miền true/false/unknown của hai phía trên **common comparable domain** `Kc`:

```text
definitely introduced region = F_left ∩ T_right
definitely resolved region   = T_left ∩ F_right
persisting violation region  = T_left ∩ T_right
remaining uncertain region  = phần Kc chưa có cặp kết luận definite
```

Không dùng đơn thuần `T_right - T_left` làm introduced: phần trừ có thể là UNKNOWN ở trái. Tương tự, `T_left - T_right` không tự là resolved. Khi T/F đã exhaustive và U rỗng trên Kc, set differences mới có ý nghĩa đầy đủ.

- Trước phép giao: xác minh tương ứng assignments, typed values, `MISSING/OTHER`, feasibility và order/branch semantics. Không so raw world/region hash qua snapshots.
- Giữ riêng `K_left_only` và `K_right_only`: domain removed/added hoặc không có correspondence. Một violation chỉ nằm ở domain bị bỏ không được gọi code fix.
- `REGION_EXPANDED`, `REGION_SHRUNK`, unchanged hoặc reshaped cần proof inclusion/equivalence; khi đồng thời thêm và mất vùng thì ghi cả hai. Không suy ra từ số lượng witness hoặc cardinality đơn thuần.
- Giữ classification gốc trên `K_left`, `K_right`; classification nếu tính trên `Kc` phải ghi rõ phạm vi. Miền rỗng/unsat không được vacuous MUST/NEVER.
- Binding change phải phân biệt candidate/type compatibility, activation, registration, selected binding và runtime observation. Source wiring/declared endpoint vẫn là evidence tier ban đầu.
- Witness thay đổi có thể chỉ do lựa chọn đại diện khác; không đủ chứng minh truth region thay đổi. Replay từng phía với đúng inputs/region/version, không chuyển witness bằng raw ID.
- Dùng exhaustive oracle cho miền nhỏ; reuse symbolic DAG/reasoner cho miền lớn. Budget exhaustion giữ proved subregions + residual/gaps, không Cartesian expansion toàn bộ hoặc trả clean.

### 4.5. Finding lifecycle và lịch sử

`PRESENT` dưới đây là violation có vùng true được chứng minh; `ABSENT` là không vi phạm đã được chứng minh trên scope/domain tương ứng, với coverage đủ cho phép phủ định. `UNKNOWN` bao gồm mất evidence, chưa match, rule không được evaluate đủ hoặc work chưa xong. Đây là trạng thái phục vụ lifecycle, không thay thế classification gốc.

| Before → after | Kết luận được phép |
|---|---|
| ABSENT → PRESENT | INTRODUCED, ghi rõ relative to baseline / affected region |
| PRESENT → PRESENT | PERSISTED; region, severity, evidence/binding có thể vẫn đổi |
| PRESENT → ABSENT | RESOLVED chỉ cho phần/domain đã chứng minh, không phải từ finding-list vắng mặt |
| UNKNOWN → PRESENT | Mới quan sát/khôi phục bằng chứng; không chứng minh introduced |
| PRESENT → UNKNOWN | Evidence/coverage lost hoặc unresolved evolution; không resolved |
| UNKNOWN → UNKNOWN | Chưa quyết định; giữ before/after reasons và residual |
| ABSENT → ABSENT | Không có violation trên scope đã chứng minh; vẫn có thể có fact/evidence changes |
| ABSENT → UNKNOWN hoặc UNKNOWN → ABSENT | Thay đổi khả năng kết luận; không tự gán introduced/resolved |

Reintroduced cần **ít nhất ba mốc trên chuỗi đã khai báo**: PRESENT → ABSENT đã chứng minh → PRESENT, với correspondence/rule/domain liên tục. PRESENT → UNKNOWN → PRESENT không là reintroduced. Khoảng lịch sử thiếu, branch merge, ancestry không rõ hoặc mapping không bắc cầu phải hiển thị unknown/ambiguous continuity. Pair comparison chỉ nói introduced-relative-to-baseline, không nói lần đầu trong đời repository.

Earliest-observed chỉ áp dụng trong lịch sử đã cung cấp, có khoảng quan sát rõ. Chronological timestamp không thay ancestry. Split/merge findings được giữ các event tương ứng mà không nhân bản/tự xóa lịch sử. Không bắt buộc triển khai kiến trúc blame hoặc tìm commit gây lỗi trên toàn Git history.

### 4.6. Metric, score và evidence delta

- Trả before/after raw value, unit, rational operands, denominator, scope/population/projection, completeness, definition/model digest và applicability. `delta = after - before` chỉ khi compatible; percent delta với baseline 0 là not applicable.
- Population đổi do repository thật thêm/bớt thành phần có thể so khi measurement semantics và census đầy đủ; báo cả size/denominator change. Population bị lọc/thiếu dữ liệu hoặc đổi scope không được coi tương đương.
- Đổi model/formula/weights/caps/rules tạo version drift. Có thể trình bày hai số riêng có nhãn, không nối thành trend tương thích hay claim cải thiện.
- Missing/UNKNOWN/WITHHELD vẫn có giá trị absent, không đổi thành 0 hoặc score 100; giữ known contributions để giải thích. Nếu score PARTIAL do calibration marker đã biết, qualification phải đi cùng delta; không tự sửa score model đã được human duyệt.
- M6 exposure score phạt cả MUST/MAY; thu hẹp MAY region có thể là cải thiện có điều kiện dù score không đổi. Hiển thị region delta bên cạnh score, không chế công thức mới để làm số thay đổi.
- Health, evidence sufficiency, structural completion và semantic accuracy báo riêng. Evidence gain có thể phát hiện thêm violation; đó không tự là repository regression.
- Gap lifecycle dùng obligation/context/root-cause correspondence và ledger resolutions; resolved gap khác resolved violation. Gap biến mất do census bị thiếu không là evidence gain.

### 4.7. Alternatives và trigger thay thế

| Lựa chọn | Đánh giá |
|---|---|
| Raw JSON/graph-ID diff | Baseline kỹ thuật đơn giản cho kiểm thử; không đủ làm sản phẩm vì identity churn, evidence noise và missing ≠ absent |
| **Khuyến nghị: exact immutable snapshots + explicit correspondence + compatibility per capability + semantic delta** | Phù hợp contracts hiện hữu; giữ graph identities, có thể dùng disk indexes/replay; phải thiết kế absence và region proofs rõ |
| Reanalyze mọi snapshot bằng analyzer chung trước mọi compare | Hữu ích khi có tool drift, nhưng đắt, phụ thuộc input/toolchain và thay evidence revision; là lựa chọn explicit, không prerequisite cho mọi compare |
| Persistent temporal graph + inferred rename/blame | Linh hoạt cho lịch sử lớn nhưng thêm schema/coupling/false matching; thuộc hướng mở rộng sau khi có nhu cầu đo được |

Chỉ thay matcher khi có labeled false-match/missed-match cases; thay region backend khi measured workload vượt khả năng hiện hữu mà giữ oracle equivalence; thêm store/index lớn khi profiling cho thấy bottleneck. Không chọn công nghệ mới hoặc tối ưu incremental chỉ vì M11 mang tên evolution.

## 5. Sáu task và dependency

| Task | Đầu ra bắt buộc | Phụ thuộc |
|---|---|---|
| **M11.1 — Scope, contracts, baseline** | Baseline inventory; compatibility/correspondence/lifecycle/region/API contracts; decision record; protocol/case registry nhỏ | M10 đã chấp thuận |
| **M11.2 — Snapshot alignment và delta core** | Authenticated readers, context/symbol correspondence, compatibility, structural/semantic delta, paged domain port | M11.1 chốt các quyết định consequential |
| **M11.3 — Conditional/binding/evidence evolution** | Region/quantifier/binding/witness/gap delta và drift-safe proof | M11.2; oracle định nghĩa ở M11.1 |
| **M11.4 — Finding lifecycle, history, metrics/score** | Lifecycle đúng absence semantics, reintroduction theo history, explainable compatible assessment delta | M11.2–M11.3 |
| **M11.5 — Durable product integration** | Persistence/recovery; stable query, CLI/API/export và Workbench đầy đủ | Domain contracts từ M11.1; tích hợp cuối sau M11.4 |
| **M11.6 — Historical corpus, hardening, G7** | Labeled historical evaluation, determinism/scale/product checks, independent review và adjudication | M11.1–M11.5 |

Luồng mặc định **M11.1 → M11.2 → M11.3 → M11.4 → M11.5 → M11.6**. Không để evaluation đến cuối: mỗi task có golden/negative/integration controls, corpus được đăng ký sớm. UI skeleton và storage interface có thể chuẩn bị sau contract nhưng không nghiệm thu trước domain. Các checklist/slice bên dưới không tạo milestone/task mới. Không áp thời hạn tùy ý; estimate chỉ sau pilot và throughput thực tế.

### M11.1 — Chốt phạm vi, hợp đồng và baseline

**Việc cần làm**

1. Refresh Git/current state; lập map release artifact ↔ current source ↔ development baseline. Giữ dirty changes; không cần commit/reset để lập baseline. Ghi reconciliation số claim M9 và các stale status references cần chỉnh, giữ nguồn lịch sử.
2. Kiểm trực tiếp M1 identities, polyglot logical symbols, bridge contexts, M5 comparison preparation, M6 key/report semantics, reader ports và consumers. Lập bảng input nào thật có/thiếu trong persisted graph/report.
3. Chốt comparison modes, per-capability compatibility, context census/mapping, finding key, region correspondence, absence proof, history và partial-completion semantics. Có ví dụ payload positive/degraded, không chỉ prose.
4. So sánh alternatives ở §4.7; tạo **draft** architecture contract/ADR cho các identity/schema/API choices. Chỉ ghi ACCEPTED sau quyết định cần thiết; không hỏi lại M10, score model hay permission đã có.
5. Đăng ký test matrix ở §6, independently authored expectations, natural-history candidates và resource protocol. Pin commits chính xác sau khi kiểm history; không đoán SHA, không lấy issue/commit message làm truth label.
6. Chốt vertical slice nhỏ đầu tiên: hai immutable Java structural snapshots cùng policy/tool profile, exact entity correspondence, một dependency đổi, một evidence-loss control, deterministic report và context dispositions.

**Artifacts dự kiến:** `docs/architecture/m11-compatible-snapshot-evolution.md`, ADR đánh số theo registry lúc tạo, `docs/tasks/m11/PROTOCOL.md`, compact case/claim registry; cùng execution note ngắn khi task thực sự chạy. Không tạo bộ reproducibility hoặc hàng nghìn dòng hash manifest cho planning/slice.

**Exit:** các choices consequential đã có disposition/approval cần thiết; mọi field truy được về producer thật; negative cases định nghĩa độc lập; có test-first slice thực thi được. Bản kế hoạch hiện tại chưa đáp ứng thay cho task này.

### M11.2 — Authenticated alignment và delta core

**Slices nội bộ:** input/compatibility → context/entity mapping → relationship/occurrence delta → paging/reader integration.

1. Đọc immutable snapshot/graph/report bằng verifier hiện hữu; validate graph/context/report references, source/evidence hashes và completeness. Thiếu/corrupt khác incompatible khác unsupported.
2. Census hai phía cho repository/root/module/source set/variant/language/context. Mỗi row là matched, left-only, right-only, ambiguous, unavailable hoặc explicitly not selected; không bỏ partition không đọc được.
3. Implement matcher đã duyệt và mapping provenance. Giữ duplicates, origin, overload, module moves, generated lineage và provider identity boundaries.
4. Tính entity/relationship deltas; tách semantic payload, observation/occurrence count, location và evidence changes. Unmatched chưa đủ chứng minh removed khi coverage chưa đóng.
5. Thiết kế disk-backed comparison index/streaming scan theo stable logical keys, không giữ đồng thời hai full graphs trong heap. Nếu port thiếu edge scan/evidence paging, bổ sung neutral port nhỏ và test memory/disk parity; không SQL hóa semantics.
6. Canonical sort, exact counters, typed gap reasons, bounded per-page working set, cancellation/checkpoint ownership và conflict detection có từ slice này; persistent product endpoint hoàn thiện ở M11.5.

**Exit:** self-compare không semantic delta; known edit đúng label; reorder input/page boundaries không đổi kết quả; corruption/ambiguous mapping không tạo false matches; cả hai context censuses reconcile; storage port có integration test thực.

### M11.3 — Conditional regions, bindings, witness và evidence

**Slices nội bộ:** common-domain mapping → explicit-region oracle → symbolic operations → bindings/witness/evidence integration.

1. Implement config-space correspondence có proof; preserve version, envelope precedence, domain types/abstractions, feasibility và material order. Không đồng nhất identity đơn thuần hoặc tên biến.
2. Tính regions theo §4.4, gồm unchanged/expanded/shrunk/reshaped và unknown residual; full-scope và common-domain classifications phải phân biệt.
3. Reuse explicit/symbolic reasoner; test representation equivalence bằng oracle độc lập, SAT/UNSAT/UNKNOWN và empty/incomplete universe.
4. So Spring producer/candidate/registration/selected binding riêng; negative controls cho missing-bean ordering, mutually infeasible paths và opaque conditions.
5. Witness/counter-witness được derive/replay mỗi phía; preserve minimality claim đúng khả năng hiện hữu, không nâng formula replay thành runtime replay.
6. Evidence/gap/trust/conflict deltas gồm generated output lineage, artifact lost/restored, native producer qualification và bridge operands. Native replay dùng capture thực hiện có; không viết tay stub để thay bằng chứng.

**Exit:** mọi transition đã đăng ký khớp oracle; không false MUST/NEVER; evidence/domain loss không sinh definitely-resolved region; symbolic path không bị ép enumerate Cartesian; witnesses hợp lệ với đúng side và context.

### M11.4 — Lifecycle, bounded history và assessment delta

**Slices nội bộ:** pair lifecycle → explicit history → metric/score integration.

1. Evolution finding correspondence giữ rule/predicate/route semantics, không sửa `Finding.key()` cũ. Lưu reference tới cả bundles và các empty/unknown rule results.
2. Implement bảng lifecycle §4.5 với absence chứng minh; partial findings pagination, query frontier hoặc policy không được evaluate không bao giờ là clean result.
3. History nhận sequence/ancestry tường minh; kiểm continuity và incompatible intervals. Reintroduced chỉ khi có intermediate clean evidence; earliest-observed có phạm vi lịch sử rõ.
4. Hỗ trợ regional lifecycle: cùng logical finding có persisted region và resolved/introduced subregions. Không ép một badge toàn cục làm mất các phần này.
5. Metric/score compare qua canonical services/reports; validate semantics/population/limits; giữ exact operands, rounding, withheld/applicability và calibration markers. Explain dimension/root contributions trước/sau, không đổi công thức.
6. Kiểm policy/version/severity/model drift, missing reports, added/removed dimensions, duplicate symptoms và evidence recovery. Không lấy score giảm/tăng làm chứng minh runtime risk hoặc causality.

**Exit:** true fix và apparent disappearance phân biệt được; PRESENT→ABSENT→PRESENT đúng và PRESENT→UNKNOWN→PRESENT không bị reintroduced; score golden và version mismatch controls pass; mọi findings/rules/metrics thuộc selection có disposition.

### M11.5 — Persistence, API, CLI, export và Workbench

**Slices nội bộ:** durable job/result → service/CLI/HTTP parity → UI flow → resilience/accessibility.

1. Lưu comparison envelope/events/index bằng immutable content address, verify before publish; receipt trỏ tới root đã commit. Không ghi đè snapshots/reports, không public partial artifact như complete result.
2. Job restart/resume/cancel, idempotency, conflict detection và result reuse bind đủ input/mapping/version/work-policy. Equal inputs mới reuse; không dùng một comparison để cập nhật facts sang source khác.
3. Query service có summary/compatibility/context map, paged events, lifecycle/history, regions/bindings, metrics/score và evidence detail. Cursor bind comparison ID, side/filter/order/schema; count exact hoặc lower-bound rõ; page end không là domain completion.
4. CLI compare/inspect/export và HTTP routes versioned được chốt ở M11.1; dùng existing error/status conventions sau khi đối chiếu. JSON canonical là export nền; không ép temporal event vào SARIF/GraphML field làm mất meaning. Nếu mở rộng format, mô tả và test loss/qualifications.
5. Workbench journey: chọn baseline/target → xem compatibility/drift/context coverage → lọc events → graph before/after theo scope → affected region/witness → source/evidence hai phía → score/metric contribution → export/history.
6. UI luôn cho biết direction, revision + content identity, selected context/projection và qualifications. Compare hai baseline đảo chiều phải đổi introduced/resolved đúng; không để request cũ ghi vào view mới khi người dùng đổi snapshot.
7. Có loading/index preparing, empty-no-change, qualified, unknown, incompatible, corrupt/missing input, cancelled, resumed, oversized-paged states. Partial comparison không có tiêu đề “không còn vi phạm”; delta không khả dụng không hiển thị 0.
8. Progressive graph expansion, search/filter, keyboard/focus, non-color status cues, reduced motion, small viewport; evidence redaction kế thừa allowlist M7/M8, không lộ config literals/secrets/host paths. Browser không fetch current source thay immutable historical source.

**Exit:** hành trình external history và deliberately degraded pair chạy được qua UI; canonical parity CLI/API/export/UI; cold restart mở lại đúng result; stale cursor/request bị chặn; bounded payload và cancellation đạt protocol; không cần Neo4j hoặc target execution để đọc compare.

### M11.6 — Corpus lịch sử, hardening và G7

1. Freeze candidate/source/tool/version/mapping/work-policy và corpus labels trước acceptance run; mở rộng runner hiện hữu khi phù hợp, không copy hệ benchmark/SDK/capture mới.
2. Chạy campaign đúng matrix đã đăng ký; fix defect có reproduction bằng focused regression rồi chỉ rerun claims bị ảnh hưởng. Không rerun native/environment failure khi blocker chưa thay đổi.
3. Thực hiện natural historical pair/triple, authored conditional/mutation controls, evidence-loss controls, drift controls và product parity. Không gọi authored mutation là sự kiện lịch sử tự nhiên.
4. Đo determinism, cold/warm read/compare, phase time, peak heap/disk, output/page bounds và restart/cancel. Chọn workload theo property cần kiểm; giữ failed/censored trials và deviations.
5. Independent read-only red-team review ở G7; cung cấp frozen artifacts, labels, source contracts, negative controls, unresolved gaps. Nếu chưa có reviewer, ghi independent review NOT RUN và không tự coi self-review đủ để pass G7.
6. Adjudicate từng claim theo requirement → input/label → command/run → result → limitation → reviewer. Không rút cases/threshold sau khi thấy kết quả để pass; scope exception cần quyết định human cụ thể.
7. Cập nhật current state, contract, task record; tạo formal reproducibility package chỉ ở closeout này theo quyền cho phép. G7 chưa đạt thì giữ M11 mở; G7 đạt mới chuẩn bị handoff M12 theo roadmap, không tự triển khai M12 trong cùng task.

**Exit:** tất cả gate-critical claims có evidence hợp lệ; 0 false certainty/false resolution/silent omission trong controls; natural-history result tái hiện được; consumer workflow hoàn chỉnh; independent review và adjudication hoàn tất với limitations minh bạch.

## 6. Verification matrix và corpus

### 6.1. Claim families phải đăng ký trước implementation acceptance

Đây là **mẫu số yêu cầu**, không phải số test đã chạy hoặc độ chính xác đã đo. M11.1 chuyển từng family thành case IDs/fixtures/oracle và pin trước khi chấm.

| ID | Cases bắt buộc | Oracle / điều kiện pass | Task sở hữu |
|---|---|---|---|
| E01 | Self-compare; source edit; revision-only/telemetry change; swapped sides | Không semantic delta giả; direction đúng; artifact/provenance change vẫn lưu | 1–2 |
| E02 | Duplicate FQN khác module/origin; overload; nested/local/generated symbol; ambiguous move | Independently authored mapping; 0 false merge/guess; unmatched population đầy đủ | 2 |
| E03 | Module/source-set/variant thêm/bớt/không được chọn; multi-root; missing context | Hai census reconcile; missing không là deleted | 2 |
| E04 | Analyzer/tool bytes/schema/provider/limits drift; same source khác evidence | Capability-specific qualified/blocked; không repository-change certainty giả | 1–2 |
| E05 | Definite add/remove/change, same logical relation khác spans/supports, unresolved→resolved | Semantic và occurrence/evidence axes tách đúng; provenance hai phía đầy đủ | 2 |
| E06 | MUST/MAY/NEVER/UNKNOWN transitions; region expand/shrink/reshape | Small-domain exhaustive T/F/U labels, gồm toàn bộ 16 cặp classification có thể xét; impossible combination ghi rõ | 3 |
| E07 | Domain added/removed, constraints/OTHER/MISSING đổi, empty/unknown feasible space | Common-domain proof; removed configurations không là fixed violation | 3 |
| E08 | Explicit/symbolic equivalence; infeasible joint path; solver budget exhaustion | Independent formula/world replay; UNKNOWN/residual đúng, không Cartesian fallback | 3 |
| E09 | Candidate/selected binding đổi; registration order; witness representative đổi | Binding axes đúng; valid side-specific witnesses; witness-only change không thành region change | 3 |
| E10 | Artifact/source/generated lineage thiếu/restore/corrupt; conflict; gap ledger history | Không false resolution/improvement; truthful trust; replay nguyên evidence revision | 2–4 |
| E11 | Introduced/resolved/persisted; policy removed/changed; evaluation/page truncated | Absence proof bắt buộc; empty findings ≠ clean | 4 |
| E12 | PRESENT→ABSENT→PRESENT; unknown/missing interval; branch/merge; non-transitive mapping | Reintroduced/earliest-observed chỉ theo declared history, không blame giả | 4 |
| E13 | Metric golden, denominator/scope/projection/model/version đổi, baseline zero | Hand-computed exact operands; invalid delta withheld; no divide-by-zero | 4 |
| E14 | Score contributions, duplicate roots, PARTIAL calibration, evidence loss, applicability | Giữ ADR-002; no false health improvement; unknown không renormalize sạch | 4 |
| E15 | Polyglot syntax/native tier, changed provider, bridge contract/endpoint drift | Language/context boundaries; không nâng syntax/BOUND thành runtime certainty | 2–5 |
| E16 | Durable reopen, tamper, cursor replay, cancel/restart, disk pressure, concurrent publication | Integrity trước read/publication; no overwritten evidence; exact continuation/accounting | 2, 5 |
| E17 | CLI/API/export/UI cùng event/region/value/evidence; stale UI request; redaction | Cross-surface parity; không lộ dữ liệu nhạy cảm; keyboard/degraded journeys đúng | 5 |
| E18 | Natural historical corpus; repeated deterministic/scale runs; independent review | Registered labels/denominators, same canonical digest khi inputs/work policy bằng nhau; G7 adjudication | 6 |

Invariants/metamorphic controls: self-comparison semantic empty; reverse direction đảo add/remove và numeric sign khi đủ compatibility; shuffled input/page boundaries không đổi kết quả; loss of evidence không tạo resolved/health gain; adding unknown sibling context không làm mất kết quả đã chứng minh nhưng phải qualify aggregate; missing history không được tăng certainty. Không giả tính bắc cầu của mapping hoặc cộng dồn pair deltas khi chưa chứng minh.

### 6.2. Corpus tối thiểu theo loại bằng chứng

| Stratum | Đề xuất chọn | Claim được thiết lập / giới hạn |
|---|---|---|
| Compact authored pairs/triples | Tái dùng Java/M4/M5/M6 fixtures, bổ sung biến thể có expected regions và lifecycle độc lập | Exact semantics/negative controls; không chứng minh lịch sử tự nhiên hay prevalence |
| **Một natural historical corpus bắt buộc** | Ưu tiên repo Java/Spring đã có acquisition/caches, ví dụ một PetClinic phù hợp; kiểm commit history rồi pin ít nhất một pair kiến trúc thay đổi thật | Natural event/source/evidence linkage; nếu chưa đủ case conditional/reintroduced thì bổ sung authored controls và ghi rõ nguồn, không gán nhãn tự nhiên giả |
| History continuity | Chuỗi ít nhất ba snapshot có clean intermediate; có thể authored khi natural history chưa có event này | Kiểm reintroduction và missing-interval semantics, không bắt toàn bộ Git history |
| Multi-context/polyglot/bridge | Dùng compact owned fixture và capture còn có thể xác thực; thêm repo hiện có khi cần consumer evidence | Context/semantic-tier preservation; không bắt qualification lại toàn bộ 18 engine |
| Drift/degradation | Cùng source với controlled artifact/config/tool/policy loss/change; pin mỗi revision | Phân biệt repository change và observation drift; không suy nhân quả từ uncontrolled runs |
| Scale/recovery | Generator graph hiện có, sparse/dense/SCC/large evidence, many contexts, small-to-medium real history | Đo disk/heap/page/checkpoint; large external repo chỉ khi nguồn lực và claim đòi hỏi, không lặp các case đã hoãn vô ích |

M11.1 chọn natural repo/revisions bằng **khả năng có labels và captured inputs**, không chọn vì kết quả engine đẹp. Nếu candidate đầu không có lịch sử thích hợp, đổi candidate trước freeze và ghi lý do. Một synthetic-only campaign không đủ thay natural historical corpus của G7.

### 6.3. Đo lường và chính sách nghiệm thu

- Báo event precision/recall theo family, false correspondence, false resolution, false certainty, affected-region correctness, witness validity, context/obligation coverage và completion riêng. Labels unknown/disputed/unavailable và omitted giữ trong denominator tương ứng.
- Region precision/recall chỉ tính cardinality khi finite-domain oracle cho phép; symbolic equivalence/inclusion proofs là phép kiểm riêng. Không lấy số assignments làm xác suất runtime.
- **Tiêu chí đề xuất cần freeze ở M11.1:** tất cả exact authored gate-critical expectations phải khớp; 0 false certain match, false resolved và silent omission trong registered controls; mọi definite witness replay hợp lệ. Returning UNKNOWN cho mọi case không đạt: các positive controls phải ra đúng definite events.
- Natural corpus phải có đủ adjudicated events để chứng minh flow yêu cầu; báo kết quả theo cases, không suy universal 100% hay lấy ngưỡng recovery 95% làm ngưỡng delta accuracy. Các threshold khác chỉ chốt bằng rationale trước acceptance.
- Performance budgets dựa measured pilot/reference environment, tách cold verification, index preparation, compare, first page và subsequent pages. Khi workload quá lớn, checkpoint/resume và typed resource outcome; deadline của phép đo không thành product repository ceiling.
- Determinism cần repeated fresh-process runs trên inputs/work policy giống nhau; cold/warm definition và repetitions freeze trước campaign. Interrupted wall-clock prefixes là operational evidence, không so như completed canonical digest.

### 6.4. Lệnh và phạm vi kiểm chứng dự kiến

Dùng JDK 21/Maven Wrapper và [test selection](../../testing.md). Các tên `Evolution*Test` dưới đây là **test dự kiến sẽ tạo**, không phải test đã tồn tại hay đã pass.

```powershell
# Inner TDD loop: chọn đúng method vừa thêm, quoted -D trong PowerShell.
.\mvnw.cmd test -pl analyzer "-Dtest=EvolutionCompatibilityTest#sourceEditKeepsComparablePolicy" -q

# Before handoff: các lớp mới/ảnh hưởng trong module, không dùng fast-tests để bỏ integration cần thiết.
.\mvnw.cmd test -pl analyzer "-Dtest=EvolutionCompatibilityTest,EvolutionCorrespondenceTest,EvolutionRegionTest,EvolutionLifecycleTest" -q

# Adapter/consumer checks chạy riêng với current platform dependencies đã build đúng.
# Chốt danh sách test thực tế theo slice; không chạy command placeholder rồi báo pass.
# analyzer-filesystem: comparison persistence + PartitionedGraphStoreTest khi port/store bị ảnh hưởng.
# backend: evolution API/job + ArchitectureQueryApiTest/PersistentReports consumers bị ảnh hưởng.
# cli: evolution journey + M9ConsumerConformanceTest cho protected published values.
# Node/browser: evolution model + real browser journeys; model tests không thay browser acceptance.
```

Nếu Maven cần build các dependency platform đã thay đổi, prepare đúng bounded dependency closure trước test; không chạy consumer bằng stale installed artifacts. Target module command không chứng minh toàn reactor. G7 closeout mới chạy broader integration/distributable verification thích hợp và formal package; không chạy clean reactor/native campaigns cho mỗi slice. Ghi command thật, exit code, executed/fail/error/skip counts, wall time, selected modules và limitations.

## 7. Rủi ro, quyết định và cách giữ tiến độ

| Rủi ro | Cách xử lý / điều kiện dừng claim |
|---|---|
| Snapshot identity churn khiến hầu hết entities tưởng là mới | Match logical scope/symbol có evidence, không bỏ hashes; bắt bằng E01/E02/E05 |
| Strict compatibility từ chối mọi config/build edit | So per capability, tách normal input changes khỏi analysis-policy drift; có qualified observed deltas và common-domain proof |
| Missing evidence bị nhận là absence | Require denominator/absence proof, rule results và unknown frontier; E07/E10/E11 là gate-critical |
| M5/M6 legacy limits ngăn complete comparison | Không dùng một page 50 findings làm full set; dùng exact completion markers, paged domain extraction hoặc typed residual. Sửa consumer boundary đúng slice nếu cần, không che bằng UI |
| Hai graph lớn làm hết heap/disk | Stream/external index, bounded working set và durable progress từ M11.2; measured profile, no arbitrary repository cap |
| Symbolic region correspondence khó hoặc budget hết | Reuse reasoner/oracle; giữ proved region + unknown remainder; chặn definitive expansion/shrink nếu proof chưa có |
| Polyglot/native evidence thiếu | Giữ semantic tier/gaps, dùng passive/owned data-only controls phù hợp; không claim native equivalence |
| Score cải thiện chỉ vì rule/domain/evidence biến mất | Independent compatibility/coverage guards; display before/after with withheld improvement |
| Cache sai vì tool/mapping/model đổi | Content-bind all result-affecting inputs; tamper/version/restart controls; preserve conflicts |
| Hết lịch sử hoặc branch/mapping không liên tục | Bounded-history claim, explicit interval, no reintroduced/first-ever inference |
| Corpus chọn sau khi xem kết quả | Freeze labels/selection trước acceptance; deviations append-only; không xóa failure |
| Workspace đang thay đổi song song | Refresh touched files trước edit; protect user changes; baseline đúng source/digest, không commit/push tự động |

**Các câu hỏi của lượt lập kế hoạch, đã có disposition trong [M11.1](01-execution.md):**

1. Public schema/version, compatibility statuses và key preimages nào được chấp thuận? Khuyến nghị additive evolution envelope, giữ nguyên M1/M5/M6 keys.
2. Explicit operator correspondence có được nhận ở v1 không, và authority/validation ra sao? Khuyến nghị chỉ accept mapping có provenance và validate bijection/scope; không promote human assertion thành compiler proof.
3. Mapping domains thay đổi nào hỗ trợ definite comparisons trong v1? Khuyến nghị exact typed-domain mapping/equivalence trước, verified common-domain extension sau trong M11.3; phần chưa chứng minh giữ qualified, không cắt khỏi denominator.
4. Natural repo/revisions và reference resource profile nào đủ điều kiện freeze? Chọn qua input availability/history inspection/pilot, không tự đặt deadline, RAM SLA hay success rate.

Các quyết định architecture/identity/schema/security thực sự còn thiếu mới cần human xét trên proposal cụ thể. Không cần xin lại quyền cho đọc evidence, authored tests, additive internal implementation, hay các approval Track A đã có. Không cấp quyền mới cho spend, source disclosure, target host execution hoặc thay score model.

## 8. Traceability và Definition of Done G7

| Yêu cầu roadmap M11 | Owner / chứng cứ |
|---|---|
| Compatible snapshot comparison | M11.1–2; E01–E04 |
| Semantic/entity/relationship deltas, context preservation | M11.2; E02–E05/E15 |
| Conditional regions, bindings, witnesses, quantifier transitions | M11.3; E06–E09 |
| Evidence drift/gain/loss/conflict, no false resolution | M11.3–4; E10–E11/E14 |
| Introduced/resolved/persisted/reintroduced findings | M11.4; E11–E12 |
| Graph/policy/metric/score evolution with explanation | M11.2–4; E05/E11/E13–E14 |
| Evidence-backed queries/UI/export, durable replay | M11.5; E16–E17 |
| Labeled historical corpus, correct affected regions, reproducible G7 | M11.6; E18 cùng E01–E17 |

G7 chỉ được báo đạt khi: domain/contracts được chấp thuận; implementation có parity với labels; toàn bộ selected/unselected/unknown population có accounting; no-false-resolution controls pass; history và conditional events tái hiện; artifacts immutable và restart-safe; product journey hoạt động; resource outcomes trung thực; independent review có disposition; claims/limitations và durable state được đối chiếu. **M10 approved không tự pass G7; plan hoàn tất không tự hoàn tất M11.1.**

## 9. Bàn giao lượt lập kế hoạch (lịch sử)

- **State before:** M10 approved, M11 unlocked; working tree đã có thay đổi lớn ngoài task này.
- **Work completed:** đối chiếu roadmap/sign-off/contracts và các điểm implementation liên quan; tạo kế hoạch 6 task, proposed semantics, test/corpus matrix, risks/decision points và G7 exit criteria. Thêm pointer tại current-state next task.
- **Verification — PASS:** Python document checks xác nhận đúng 6 task, 18 claim families, 13 local links hợp lệ, code fences cân bằng và một current-state pointer. Đếm trực tiếp bảng M9.5 xác nhận 13 PASS + 2 scoped + 2 not-run = 17. `git diff --check -- docs/current-state.md` và `git diff --no-index --check -- NUL docs/tasks/m11/README.md` không báo whitespace errors. Không chạy production/unit/native/browser benchmark trong lượt planning; chưa có independent review.
- **Decisions:** chỉ đề xuất thứ tự/thiết kế; không giả human đã duyệt schema/identity mới. Không thay scope/công thức/threshold/gate hay raw evidence.
- **Limitations:** chưa freeze natural historical commits, chưa có M11 schema implementation/campaign hoặc independent review. Đây là công việc M11.1–M11.6, không phải các kết quả đã có.
- **Exact next task:** **M11.1 — Scoping, Contracts and Baseline Architecture Design**: lập baseline/input-availability matrix, contract examples và decision proposal cho compatibility/correspondence/absence/common-domain semantics; freeze positive/negative cases của vertical slice M11.2 đầu tiên trước khi sửa production.
