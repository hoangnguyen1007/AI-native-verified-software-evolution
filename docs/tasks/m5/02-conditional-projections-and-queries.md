# M5.2 — Conditional projections và architecture query services

Trạng thái: **PROVISIONAL / chưa triển khai**. Điều kiện vào: M5.1 pass. Đọc [README](README.md), graph contract vừa triển khai và `ConfigurationReasoner`, `TruthRegionEvaluation`, `SymbolicTruthRegions`. Exit: query neutral, bounded và evidence-backed trên graph thật; không REST/UI/policy engine.

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

- [ ] Q01–Q12 pass, các stores cùng query results, giới hạn và statuses đã kiểm chứng.
- [ ] Query version/limits/cycle counting/projection semantics được document; không storage/parser leakage.
- [ ] Current-state ghi query đã có và metrics/G4 còn mở.

**Exact next task:** M5.3, xây metric catalog/versioned results trên graph/query đầy đủ và lập evidence G4.
