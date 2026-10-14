# M5.2 — Conditional projections và architecture query services

Trạng thái: **IMPLEMENTED / bounded verification pass (2026-10-14)**. Điều kiện vào M5.1 đã đáp ứng. [Contract triển khai](../../architecture/m5-architecture-queries.md) định nghĩa API/semantics thực tế; numerical product budgets vẫn PROVISIONAL và G4 chưa adjudicate. Đọc [README](README.md), graph contract và `ConfigurationReasoner`, `TruthRegionEvaluation`, `SymbolicTruthRegions`. Exit: query neutral, bounded và evidence-backed trên graph thật; không REST/UI/policy engine.

## Ownership và API

Package dự kiến: `com.evolution.analysis.query`; architecture query service và adjacency traversal là Pure Java in-memory trong `analyzer`. Phần kết hợp region ở semantic/query domain dùng reasoner port hiện có. Storage chỉ nạp/index dữ liệu; không tự giải điều kiện hoặc quyết định truth classification.

Request versioned phải chứa graph/analysis context, scope, projection, direction, relationship kinds, population filters, pagination/sort và limits. Không có default “load entire graph”. Response giữ request/projection identity, status, items, evidence references, gaps/limitations, truncation/frontier, continuation khi hợp lệ. Phân biệt semantic completeness với execution cancellation/error; dùng enum domain hiện có đúng nghĩa.

| Nhóm operation | Kết quả M5 cần cung cấp |
|---|---|
| Summary/provenance/inventory | Counts theo inclusion rules và link sang filtered detail/manifest/gap ledger |
| Entity search/detail | Tìm theo simple/FQN + module/origin; trả nhiều kết quả đúng scope, không chọn nhầm FQN |
| Focused subgraph/expand | Module/package/type/member/Spring neighborhoods, grouping và aggregate support |
| Dependencies/dependents | Direction và category rõ, distinct entities khác occurrence counts |
| Paths/cycles | Bounded path/cycle evidence, feasibility, regions/witness hoặc unknown reason |
| Evidence/gaps | Full source spans, derivation chain, attempts/conflicts, provider/version và missing payload state |
| Spring/configuration | Producers/candidates/injection/endpoints, modeled space, activation/registration/selection explanation và witness retrieval |
| Impact primitive | Direct/bounded transitive structural dependents, feasible paths và frontier; không guaranteed runtime impact |
| Comparison preparation | Compatibility descriptor/reasons cho context/schema/version; không tính snapshot delta |
| Metrics bridge | Query interface cho catalog/results M5.3; không trả fake zero trước khi engine có |

Score/violations chỉ tích hợp khi M6 có domain; không dựng stub `COMPLETE: []` gây hiểu nhầm là không có vi phạm. Endpoint chỉ trả khi upstream có evidence, không đoán route từ tên method.

## Projection semantics

| Mode | Nghĩa và xử lý |
|---|---|
| REALIZED | Assignment + material order/context chính xác; known true edges active, false inactive; unknown hiện overlay riêng |
| MUST | Fact đúng trên toàn feasible nonempty modeled space; không suy từ một assignment hoặc thiếu world |
| MAY | Condition-labeled facts có variation theo upstream classification, giữ U residue; MUST nếu cần kèm phải là option rõ |
| BOUNDED_UNION | Exploratory union, luôn gắn region; không chứng minh một thế giới runtime |
| UNKNOWN/GAP overlay | Giữ unknown/candidates/missing evidence có origin/reason; không biến thành definite dependency |

Java static relation giữ nghĩa “source dependency trong exact build context”, không phải lời gọi chắc chắn xảy ra runtime. Không nhân bản một entity cho mỗi world. Không merge MAIN/TEST/module classpath khác để giải một Spring path; phải chọn compatible partition hoặc báo unsupported/unknown context composition.

Aggregation type → package/module giữ underlying IDs và derivation version. Với nhiều cạnh cùng cặp scope, presence là **OR** của support regions; khi nối cạnh trong path là **AND**. Không intersect các support song song rồi làm mất dependency khả thi. Dedup sort ổn định, không mất occurrence evidence.

## Path, reachability và cycles

1. Duyệt theo order canonical và budgets. Mỗi prefix giữ region intersection, không chỉ visited vertex. Hai đường tới cùng vertex với vùng cấu hình khác có thể cần tiếp tục riêng; memoization chỉ prune khi implication/subsumption đã chứng minh.
2. `findPaths(...)` kiểm tra mỗi prefix mở rộng: `Rnext = K_feasible ∧ Rprefix ∧ Region(edge) ∧ endpointActivation`, trong cùng compatible material-order/context. UNSAT → prune ngay prefix và mọi extension của nó, không đợi dựng hết path. SAT → prefix khả thi, chỉ xuất path khi tới target; UNKNOWN/limit → giữ qualified outcome và gap, không prune như UNSAT hoặc trả definite path. Solver calls có cumulative request budget riêng; một traversal step không được che một phép giải vô hạn.
3. Không dùng pairwise compatibility thay toàn-path conjunction. Witness từng cạnh không phải witness cả đường; witness path phải thỏa tất cả cạnh trong cùng assignment/order và được re-evaluate qua domain hiện có khi có đủ inputs. Thiếu replay evidence phải ghi rõ, không gắn `revalidated` giả.
4. Shortest path phải định nghĩa trên projection/region và deterministic tie-break. Reachability giữ bounds, depth, expanded nodes và unexplored frontier; target chưa thấy khi exhausted không có nghĩa unreachable.
5. Tarjan SCC là bước isolate `O(V+E)` trên graph projection trước Johnson; dùng explicit stack hoặc cơ chế tương đương tránh Java call-stack overflow trên graph sâu. Chỉ enumerate SCC có >1 đỉnh hoặc singleton có self-loop. SCC union chỉ là candidate structural component; không tự thành feasible cycle. Với aggregate package graph, internal same-package dependencies không tạo cycle package giả.
6. Johnson phải bounded theo bảng dưới; kiểm tra trước bước duyệt/cấp phát/push/output tiếp theo, không enumerate toàn bộ rồi mới `limit(50)`. Dense SCC vượt input cap trả condensation + metadata. Không dựa vào bắt `OutOfMemoryError` làm cơ chế giới hạn.
7. Chốt cycle identity: simple directed cycles, canonical rotation, không đếm lại start vertex; reverse direction chỉ khác nếu thật sự có directed edges. Parallel evidence của cùng logical edge không nhân cycle count. Mỗi cycle vẫn kiểm tra joint feasibility.
8. Chỉ báo exact cycle count COMPLETE khi enumeration và feasibility đều đầy đủ. Truncated là PARTIAL, số witness trả về không phải tổng cycle count. Unknown-feasibility candidates và rejected infeasible candidates có counters riêng; không lặng lẽ bỏ khỏi coverage.

### Hard caps và partial-result contract

| Budget | Quy định baseline M5 |
|---|---|
| Depth | `1 <= maxDepth <= 20`, mặc định 20; số đỉnh trên current simple-cycle search path, self-loop có độ sâu 1. Explicit stack vẫn chịu cap này |
| Traversal work | `1 <= maxSteps <= 100000`, mặc định 100000; tính mỗi edge examination và bookkeeping/unblock operation của Johnson, kể cả candidate bị UNSAT/UNKNOWN; counter không vượt cap |
| Output | `1 <= maxWitnessCycles <= 50`, mặc định 50; dừng khi đủ quota distinct feasible witnesses, không chờ cycle thứ 51 |
| Scope | Steps/quota/solver work dùng chung toàn request qua tất cả SCC; không reset theo SCC, start vertex hoặc page để vượt cap |
| Input/memory | Có graph/SCC vertex-edge bounds và bounded auxiliary state trước enumeration; Tarjan có input/work/cancellation guards riêng, không bị ép vào cap depth 20 của Johnson |

Giới hạn lớn hơn cap bị từ chối bằng validation; có thể yêu cầu giới hạn thấp hơn. Version/bind limits vào query identity. Khi depth/step/output/input guard ngăn completion: trả `PARTIAL`, `truncated: true`, `stopReason`, `witnessesReturned`, `stepsUsed`, frontier/SCC summary và typed gap phù hợp. Không khai báo exact total. `workBudgetExceeded: true` cho work/depth exhaustion; quota đơn thuần dùng `outputBudgetExceeded: true` và `workBudgetExceeded: false`. Không gắn sai nguyên nhân chỉ để mọi cờ đều true. Dừng tại quota khi chưa chứng minh exhausted vẫn là PARTIAL kể cả graph thực tế có đúng 50 cycles.

Giữ toàn bộ witnesses đã chứng minh. Nếu chưa tìm được witness, `witnessCycles: []` là hợp lệ **chỉ với qualified partial response và lý do**, không phải COMPLETE/no-cycle và không được chế witness để lấp danh sách. Unknown candidates phải có bounded summary/denominator, không buffer vô hạn vì chúng chưa tiêu thụ feasible-witness quota. Bộ đếm/lists/SCC indexes đều phải bounded; ba số 20/100000/50 riêng lẻ chưa chứng minh an toàn bộ nhớ của toàn pipeline.

Fixture `@Profile("dev")` và `@Profile("prod")` phải có hai control: không có exclusion constraint thì có thể đồng thời active; khi supplied constraint chứng minh mutually exclusive thì đường qua cả hai bị prune. Cặp `p` và `¬p` là negative control trực tiếp. Feasible dependency path cũng chưa tự là policy violation; kết luận đó thuộc M6.

Memory/time/cancellation là operational guards, không thể hứa prefix giống nhau trên mọi host. Complete deterministic runs phải cùng digest; deterministic step/output limits cho reproducible partial runs. Khi guard phụ thuộc host kích hoạt, trả interrupted/partial evidence, không cache/publish như canonical complete result hoặc dùng trong metric complete.

## Pagination, compatibility và an toàn

- Cursor bind graph identity, query version, normalized filters/projection/sort; foreign/stale cursor phải bị reject. Ổn định thứ tự và không trùng/mất item trên snapshot bất biến.
- Hard result limits áp dụng cả evidence expansion; total không biết thì trả unknown/absent, không lấy page size làm total.
- Trước arithmetic region: validate build/space/semantics/encoding/limits. Explicit-world universe không tương thích thì không union. Symbolic encoding giữ formula DAG, dùng bounded reasoner, không ép enumerate 56+ dimensions.
- Compatibility preparation tách “cùng artifact để cache/query” khỏi “có thể so sánh snapshot tương lai”: snapshot/source hash khác là điều bình thường cho evolution; schema/formula/context drift mới cần lý do qualification. Chưa triển khai delta M11.
- Không export secret config values/host paths vào response; dùng evidence descriptor/redacted digest theo upstream policy. Không redaction bằng cách sửa raw evidence.

## Test matrix

Suite mới đề xuất: `ArchitectureProjectionTest`, `ArchitectureQueryContractTest`, `ConditionalPathCycleTest`; integration consumer: `ArchitectureQueryPipelineIntegrationTest`.

| ID | Expected control |
|---|---|
| Q01 | Một fixture có MUST/MAY/NEVER/UNKNOWN; cả 5 mode phân biệt đúng và region references resolve được |
| Q02 | A→B chỉ khi p, B→C chỉ khi ¬p: UNSAT prefix bị prune trước khi expand tiếp; thêm dev/prod controls có/không exclusion constraint, không suy exclusion từ tên |
| Q03 | Ba edge với regions `{a,b}`, `{b,c}`, `{a,c}`: pairwise có giao nhưng toàn path rỗng → reject |
| Q04 | Hai prefix tới cùng node có regions khác; chỉ prefix thứ hai nối được target → không prune mất valid path |
| Q05 | Hai support song song p và ¬p cho cùng aggregate edge → OR khả thi; thiếu feasible-space closure vẫn không tuyên bố MUST |
| Q06 | Feasible conditional path/cycle → đúng region và joint witness; order/context mismatch hoặc U → không definite |
| Q07 | SCC singleton/no-loop không gọi Johnson; self-loop hợp lệ; duplicate/disconnected/dense SCC đúng counts; long acyclic chain không call-stack overflow ở Tarjan |
| Q08 | Boundary depth 20/21, steps 100000/attempted-next-step, quota 49/50/51; counters không vượt cap hoặc reset qua SCC; quota stop có PARTIAL/output reason; exhaustion trước witness đầu giữ empty-list PARTIAL; memory/cancellation và UNKNOWN candidates đều bounded |
| Q09 | Pagination/filters/shuffled input/storage reload → stable page, evidence; stale cursor bị reject |
| Q10 | Compact exhaustive oracle đối chiếu symbolic path semantics; large symbolic space không Cartesian expansion |
| Q11 | Summary → entity → edge → occurrence → exact span; gap → attempts; activation → transition → witness chain truy xuất được |
| Q12 | Main/test/source-set context tách biệt; evidence loss không thành unreachable/healthy; comparison descriptor không chạy delta |

Kiểm chứng đủ 4 R2 use cases: controller→repository dependency; package cycle; singleton candidate→prototype selected binding; configuration-hidden boundary. Đây là raw query evidence, **không** phán quyết scope violation hoặc policy conformance.

## Verification và exit

Chạy suite mới tại `analyzer`, thêm `TruthRegionEvaluationTest`, `SatConfigurationReasonerTest` nếu region composition/reasoner boundary bị ảnh hưởng; core graph/store contracts từ M5.1. Chạy integration suite mới tại `analyzer-javaparser`; chạy file-store/query parity ở `analyzer-filesystem`. Không full reactor hay external benchmark.

Lệnh chính dự kiến (bổ sung core/store regression nêu trên theo affected inputs):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=ArchitectureProjectionTest,ArchitectureQueryContractTest,ConditionalPathCycleTest,TruthRegionEvaluationTest,SatConfigurationReasonerTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=ArchitectureQueryPipelineIntegrationTest,CanonicalGraphPipelineIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Mở rộng `FileGraphStoreTest` với query parity hoặc ghi rõ tên suite thay thế thực tế; không chỉ chạy round-trip counts cũ rồi gọi là query parity.

- [x] Q01–Q12 có bounded assertions, các stores cùng query results, giới hạn và statuses đã kiểm chứng.
- [x] Query version/limits/cycle counting/projection semantics được document; không storage/parser leakage.
- [x] Current-state ghi query đã có và metrics/G4 còn mở.

### Kết quả thực tế và handoff

Từ HEAD `06bcbb5` với working tree sạch, đã thêm package `com.evolution.analysis.query` gồm request/result contracts, region catalog/algebra, bounded traversal và service; ba core suites mới và một adapter integration suite; mở rộng file-store query parity. Sửa đúng một thiếu sót graph consumer phát hiện: các typed Spring gaps có trong plan/evaluation payload nay cũng có CAPABILITY_GAP node để truy vấn nguyên nhân. Không đổi M1 preimage hoặc snapshot schema, không thêm dependency/database.

Lệnh pre-handoff cuối (exit 0):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=ArchitectureProjectionTest,ArchitectureQueryContractTest,ConditionalPathCycleTest,CanonicalGraphBuilderTest,CanonicalGraphContractTest,InMemoryGraphStoreTest,IdentityAndSourceContractTest,CanonicalSerializationTest,FrontendContractTest,FrontendResultTest,AnalysisAndAssessmentContractTest,TruthRegionEvaluationTest,SatConfigurationReasonerTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package install -pl analyzer "-DskipTests" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=ArchitectureQueryPipelineIntegrationTest,CanonicalGraphPipelineIntegrationTest,UniversalArchitecturePipelineIntegrationTest,UniversalArchitectureM4EAdjudicationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

**86 core / 13 suites + 6 filesystem / 1 suite + 26 adapter / 4 suites = 118 tests / 18 suites**, failures/errors/skips đều 0. Có 29 test mới, ngoài assertions parity trong suite file-store hiện có. Core/adapter suite time là 9.298 / 29.156 giây, không tính Maven startup; core command chạy bất đồng bộ không được đo end-to-end riêng. Filesystem command cuối mất 4.165 giây. Packaging/install skipTests chỉ refresh dependency, không là test evidence.

Inner TDD core có command 3.672–6.257 giây; không phải mọi loop đều đạt <5 giây. Focused integration và cold compilation cũng vượt mục tiêu; không bỏ required checks để giữ số thời gian. IDE từng ghi bytecode stub `Unresolved compilation problems`, dù source hợp lệ. Sau khi xác nhận lỗi môi trường và source compile, đã dùng `dependency:build-classpath` (test scope, output dưới target) và JDK 21 `javac -encoding UTF-8 -proc:none --release 21` biên dịch có phạm vi các selected adapter test files cùng `SourceToSpringPlanTest.java` trước lần Maven test cuối. Không clean/full reactor hay chỉnh IDE/user settings.

Q01/Q05: năm modes, OR support, incomplete space; Q02–Q04/Q06/Q10: prefix pruning, full conjunction, distinct prefixes, explicit universe/context mismatch, joint witnesses, small exhaustive oracle và 60 symbolic variables; Q07–Q08: iterative SCC/deep DAG, directed identity, quota 49/50/51, depth 20/21, global steps đúng 100000, solver/input/queue/operational guards; Q09: cursor identity/reload/store parity; Q11–Q12: occurrence spans, gap/provider references, registration→event và witness→replay, MAIN/TEST partitions, partial-source confidence và compatibility. R2 controller→repository/package-cycle/hidden-configuration controls có source thật. Prototype scope control giữ `ANNOTATION_UNSUPPORTED` và UNKNOWN từ M4, không chế definite binding hay scope violation.

Không blocker còn lại trong M5.2. Self-review không phải independent review. Chưa có external corpus/performance certification, semantic replay mới cho joint path, numerical product-budget approval, metric implementation hoặc G4 acceptance. Không commit/push hay reproducibility package. Các giới hạn và thuật toán canonical simple-cycle tương đương Johnson là lựa chọn nội bộ có thể thay thế, đã document; không phát sinh yêu cầu đổi scope hay quyết định human mới cho slice này.

### Rà soát góp ý Gemini — 2026-10-14

| Finding | Quyết định và evidence |
|---|---|
| F1 — document metadata heap | **Sửa.** Không giữ full parsed `source.document` trong map; chỉ giữ module/classification, reserve 4 auxiliary units trước parse/retention và reuse khi describe. Test với budget thấp tái hiện COMPLETE sai trước sửa, nay PARTIAL/INPUT. Context scan cho exact inventory/filter vẫn cần thiết; chưa tuyên bố lazy pagination cho toàn query. |
| F2 — DFS thiếu Johnson blocked-set | **Giữ thuật toán; chấp nhận giới hạn hiệu năng.** Dense SCC có thể sớm PARTIAL, đúng defensive-budget contract. Thêm regression infeasible-prefix-first → shared vertex → feasible cycle: blocked-state chỉ theo vertex trong SAT-pruned traversal có thể bỏ sót. Không tuyên bố độ phức tạp output-sensitive của Johnson; tối ưu sau cần đo lường và kiểm chứng region-aware. |
| F3 — PACKAGE tuple parse | **Sửa.** Tái hiện exception, thêm bounded shape/arity validation và fallback có `INVALID_CANONICAL_NAME`; giữ evidence, không chế label. Controls gồm malformed JSON, non-array, empty/null/non-string tag, missing/extra components và package-parts sai kiểu. |
| F4 — GC-sensitive heap guard | **Đồng ý với lưu ý; không đổi thuật toán guard.** Contract nay nói rõ headroom tức thời không phải SLA hoặc exact byte accounting. |
| F5 — IDE stub/Maven handoff | **Đồng ý với Maven handoff; không ép clean/full reactor.** Sau sửa, chạy Maven `test` chuẩn ở cả 3 module (các selectors bên trên), không cần javac workaround trong lần rà soát này. Historical stub/workaround record được giữ nguyên; incremental Maven success không tự là bằng chứng clean build. |

Verification mới nhất: **89 core / 13 suites + 6 filesystem / 1 suite + 26 adapter / 4 suites = 121 tests / 18 suites**, zero failures/errors/skips, tất cả command exit 0. Có thêm 3 regression methods so với checkpoint 118 tests phía trên. Hai regression ban đầu đỏ đúng lỗi (3.776 s); controls fix/region-cycle xanh (4.345 s); null-tag control tiếp tục bắt NPE trước khi shape validation được hoàn thiện. File-store command cuối 4.511 s. Core suite time 9.417 s và filesystem 0.309 s, không tính Maven startup; consumer artifact được refresh bằng package/install skipTests trước kiểm chứng. Không thêm dependency, đổi identity/schema, commit/push, external benchmark hoặc independent-review claim.

**Exact next task:** M5.3, xây metric catalog/versioned results trên graph/query đầy đủ và lập evidence G4.
