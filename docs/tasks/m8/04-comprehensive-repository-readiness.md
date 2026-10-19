# M8.4 — Kế hoạch nâng cấp khả năng đọc và phân tích repository toàn diện

Ngày lập kế hoạch: **2026-10-17**; cập nhật thực hiện: **2026-10-19**. Trạng thái: **BƯỚC 1–2 IMPLEMENTED / BOUNDED VERIFICATION; BƯỚC 3–6 PENDING**. M8.3 đã có bounded acceptance; M8.4 bắt buộc trước M9. Kế hoạch có **đúng 6 bước**, checklist trong mỗi bước không tạo milestone/task bổ sung. Bàn giao thực hiện từng bước ở cuối tài liệu; phần handoff lập kế hoạch là lịch sử.

**CONFIRMED — yêu cầu human:** build mục tiêu được chạy trong sandbox, không trực tiếp trên host; bỏ các trần cứng cản repo lớn; phân tích mọi module/build root; thu nhận generated code; giảm FAILED/PARTIAL bằng bằng chứng thực. Human đã chọn **thêm semantic engine cho từng ngôn ngữ ngay trong M8.4**, thay thế phạm vi cũ chỉ inventory ngoài Java. **PROVISIONAL — lựa chọn kỹ thuật:** [hợp đồng kiến trúc](../../architecture/m8-repository-readiness.md), [nghiên cứu và phương án](../../research/2026-10-17-m8-readiness-design.md), [ADR-007](../../decisions/ADR-007-isolated-build-and-polyglot-readiness.md).

## Đích đến và cách chọn phương án

Người dùng đưa repo vào; thấy inventory thật sớm; hệ thống tự phát hiện môi trường, module, ngôn ngữ và thiếu dữ kiện; dùng static analysis trước, build/index trong môi trường cô lập khi cần, nối evidence về graph; lỗi cục bộ không xóa phần đã làm; khởi động lại tiếp tục công việc; CLI/API/UI trả cùng giá trị. Toàn bộ snapshot được bao quát, cả file không nằm trong root Maven đầu tiên và các source set ngoài production.

Lựa chọn chính: **CAS và inventory trên đĩa + durable module/task DAG + disposable VM build executor + native semantic engines sau adapter + graph/query phân trang**. Đây là thay đổi đường dữ liệu từ đầu đến cuối, không chỉ tăng `MAX_BYTES`. Java/Spring tiếp tục là năng lực sâu được bảo vệ; non-Java engines là deliverable thực, không được thay bằng phát hiện extension hay TODO. Native toolchain/indexer được tái sử dụng để tránh viết lại compiler.

Không cam kết “100% mọi ngôn ngữ, mọi chương trình, vô hạn dung lượng, nhanh nhất” như một kết quả đã biết. Cam kết thiết kế là **không có artificial repository ceiling, không loại bỏ component, tiếp tục mở rộng provider, và chỉ báo complete khi denominator thực đã đóng**. Ngôn ngữ/SDK mới, nguồn không có quyền truy cập, phần động không chứng minh được vẫn có obligation cần xử lý. Không làm mất những trường hợp này để đạt tỷ lệ đẹp.

## Bước 1 — Thu nhận toàn repo và nền tảng lưu trữ/checkpoint vượt RAM

**Mục tiêu:** loại bỏ giới hạn tổng repo, giữ toàn bộ input và trạng thái đang xử lý trên đĩa; làm nền cho tất cả bước sau. Phạm vi chính: `analyzer` acquisition/identity contracts; `analyzer-filesystem`; `backend.analysis.RepositoryConnections`, `ScanSelection`, `AnalysisJobs`; storage/query ports và CLI composition.

**Công việc chi tiết**

- Chuyển bytes sang stream/CAS handles, inventory sang cursor/external sort; tách filesystem path khỏi logical repository path. Giữ hash raw bytes, encoding, kinds, ownership và source classification. Gỡ trần 128 MB/20k/10k và 120 giây khỏi admission; thay per-file-array/int-size assumptions bằng streaming. Không chỉ sửa constructor max values.
- Comprehensive mode inventory mọi file/source set: `.proto`, `.py`, `.rs`, lockfiles, tests, docs, assets, binaries, unknown extensions; không dùng whitelist hiện tại của `ScanSelection.input()`. Inventory generated/vendor/cache riêng, semantic work và metric population riêng. Exclusions thực sự do user chọn có ledger/selection identity, không âm thầm biến thành default coverage.
- Tải Git pin revision/tree; partial clone là tối ưu thứ tự tải, hydration toàn snapshot là điều kiện complete. Journal validated objects, retries/idle-progress leases, checksum, resume sau crash; shallow/full-fetch fallback. Không duyệt lại toàn checkout mỗi 150 ms để cộng dung lượng như hiện tại.
- Implement Git host policy mở cho public/private/self-hosted HTTPS và configured SSH với strict host keys; submodule gitlink closure, LFS object acquisition, safe relative links và archive intake. Auth qua explicit read-only credential reference; không ambient helper/target ProxyCommand. URL/redirect/DNS/endpoint đều kiểm policy. Local filesystem có mutation detection; logical paths có case/non-UTF8/Windows-incompatible handling.
- Tạo durable `JobJournal` và content/inventory revisions; pending frontier không giả root snapshot. CAS publication atomic, checkpoint trước complete marker; disk full, cancellation và process restart có continuation. Queue/workers/history admission theo tài nguyên/retention policy; không giữ toàn lịch sử trong RAM và không silently evict accepted jobs.
- Chốt schema migration từ M1 list snapshot/whole `KnowledgeGraph` sang streaming inventory và partitioned graph access. Giữ v1 read/golden identity; v2 thêm features có explicit version. SQLite local cho metadata/index/journal là phương án mặc định tạm thời, CAS là evidence; không leak SQL vào domain. Chọn/pin driver và kiểm corruption/recovery trước sử dụng.

**Kiểm chứng và exit**

- Compact tests cho count/byte/depth đúng trước/sau ngưỡng cũ; streaming test tổng bytes lớn hơn heap; generated inventory hơn một triệu entries không cần commit corpus lớn. Test root 300 levels, giant directory, giant single file, invalid paths, link cycles, LFS/submodule missing và source mutation. Parser một file quá lớn phải route worker phù hợp, không giả parse đủ.
- Inject network reset, stalled transfer, interrupted pack, disk-full và kill/restart; không tải lại blob đã xác thực, không mất/nhân đôi path, không báo complete khi frontier mở. Old/new snapshot golden parity và inventory ledger reconciliation phải pass.
- Deliver một journey acquisition → durable inventory → restart/resume → sealed manifest; các consumer cũ chạy bằng bounded compatibility view. **Không tuyên bố đã giải quyết full semantic scale trước bước 6.**

## Bước 2 — Readiness planner cho mọi build root, module và môi trường

**Mục tiêu:** biết cần build cái gì, theo thứ tự nào, cần dữ kiện gì; bảo toàn phần độc lập khi dependency/module khác lỗi. Phạm vi: build-model/classpath/evidence core, `analyzer-maven`, Gradle/native build adapters, `MavenScan`, `UniversalBuildIngestion`, scheduler.

**Công việc chi tiết**

- Discovery toàn inventory: Maven aggregators/parents, Gradle multi-project/composite/buildSrc, plain Java/IDE, Ant, Bazel, sbt, custom roots và polyglot workspaces. Thay `MavenScan.outsideEntryBuild()` loại nested roots bằng multi-root ownership/DAG. Root directory không đương nhiên là một build duy nhất.
- Module descriptor chứa build root/target/source set/variant/platform/toolchain/language và evidence. MAIN/TEST/custom không trộn. Detect duplicate ownership, overlapping roots, cycles/co-compilation; dùng native build semantics, không ép mọi repo vào Maven.
- Versioned `BuildContextSelection`/coverage manifest ghi requested/discovered/selected/unselected/unknown profiles, flavors, feature flags và targets; bind identity và hiển thị scope. Planner mở rộng feasible discovered contexts theo policy. Không suy root complete cho mọi context từ build mặc định; dynamic dimensions chưa khám phá giữ discovery obligation.
- Static preflight đọc wrapper/config/lock/toolchain/compiler/generator declarations; tạo explicit requirements và recipe. Phân biệt analyzer JDK với build JDK và analyzed platform. Source level, release, preview, encoding, JPMS/classpath và platform vendor/version là các chiều riêng.
- Java archetypes không thu hẹp vào Spring Boot: plain/IDE/Ant, Swing/AWT/JavaFX, Servlet/JSP/WAR và Jakarta EE, Android/AGP, OSGi, JNI và AspectJ/weaving có acquisition/build requirements riêng khi xuất hiện. Capture SDK/framework artifacts, JSP-generated Java hoặc woven bytecode thực; không suy framework runtime semantics chỉ từ tên dependency. Thiếu platform/SDK giữ đúng obligation thay vì loại project.
- Comprehensive mode tạo build-validation obligation cho mọi selected buildable context ngay cả khi static pass không có gap. Generation/compile và test/custom compile áp dụng được phải chạy trong sandbox hoặc có validated capture exact closure để reuse. Không có compile phase phải chứng minh `NOT_APPLICABLE` và vẫn thực hiện native semantic validation; không chạy application tùy ý.
- Coordinator chọn cache/captured evidence → passive model/dependency provider → isolated model/build/index provider. Wire dependency-acquisition capability hiện có vào luồng sản phẩm thay vì `LOCAL_CACHE_ONLY` cứng. Range/SNAPSHOT/relocation/variant được resolve bằng tool thích hợp thành exact closure, không bịa version. Không dùng dependency superset để làm SymbolSolver hết lỗi.
- Requirement DAG ưu tiên prerequisite giải nhiều obligations, scheduling deterministic tie-break; resource scheduling không đổi semantic result. Shared outputs/source set SCC build chung khi cần; module độc lập được checkpoint và tiếp tục. Global plugin effects chưa biết thì giữ root scope, không giả independence.
- Recipes khai báo declared inputs/outputs, tool pins, side effects, network/resource policy và satisfaction checks. Native build command/template do adapter quản lý; repository suggestions là data. Generic custom recipe là escape hatch có evidence, không auto chạy bất kỳ script tìm thấy.

**Kiểm chứng và exit**

- Fixtures Maven parent khác aggregator, nested independent root, missing sibling, duplicate GAV, Gradle composite/dependency substitution/custom source sets, Java không POM, Ant/Bazel/sbt và mixed workspace. Mỗi module/file có disposition; engine không loại build root cạnh nhau.
- Mutually exclusive variants có source riêng, profile tạo module mới và unknown dynamic configuration: inventory không mất file; selected-context completion không biến thành all-context coverage.
- Exact manifests kiểm bằng native tool observations trong executor của bước 3; trước đó kiểm planner bằng fixtures/captured models. Wrong version/order/variant/toolchain negatives phải fail satisfaction, không làm “exact”.
- Deliver readiness report + module/build DAG + recipe list + root-cause requirements. Bộ planner usable ở passive mode; build-dependent obligations còn pending cho đến executor được kiểm chứng.

## Bước 3 — Build và index thật trong sandbox cô lập

**Mục tiêu:** lấy effective build facts, compiler outputs và generated inputs một cách tự động khi cần; không target execution trên host. Phạm vi: `SandboxExecutor`/`BuildEvidenceProvider` ports, adapter mới, artifact broker/cache và durable scheduler.

**Công việc chi tiết**

- Implement backend disposable VM đầu tiên. Với Windows: Hyper-V nếu environment hỗ trợ; Linux executor qua KVM/QEMU hoặc configured remote VM là đường triển khai tương đương. Không suy ra `docker.exe`/`wsl.exe` tồn tại nghĩa isolation đã sẵn sàng. Driver/container image versions và image digest được pin; không dùng tag `latest` làm evidence identity.
- Source/captured artifacts đưa vào guest bằng immutable copy/volume, writable overlay riêng. Host không expose writable checkout/home/cache/socket/agent. Controller ngoài guest enforce CPU/RAM/process/disk/network, progress lease, cancellation và cleanup đúng namespace job. Guest thất bại không làm coordinator chết.
- Artifact broker cache download bytes theo digest, policy kiểm mọi endpoint kể cả plugin/wrapper download và redirect; resolve online có journal rồi replay offline. Không tự sửa POM/build script, đổi dependency/JDK hay disable processor để “build xanh”. Adaptation bắt buộc phải là recipe variant có provenance và kết quả được qualified.
- Maven recipe: model/generation/compile, rồi test-compile/package/verify khi requirement cần; reactor `-pl`/`-am`/fail-at-end có kiểm aggregator effects. Gradle: Tooling API/model + native task execution/capture; buildSrc/configuration đều trong guest, daemon chỉ sống trong attempt. Custom build systems dùng provider recipe, không bị buộc rewrite.
- Capture actual source roots, ordered compiler classpath/module path, toolchain/args, profile/variant, generators/processors, exit per task, artifact bytes và logs. POM/Gradle model hay dependency tree không được tự thay compile invocation evidence. Failed build vẫn export completed stage outputs với completeness riêng.
- gVisor là optimization backend sau conformance; full VM là fallback khi syscall/native-tool incompatibility. Firecracker chỉ thêm khi Linux/KVM worker economics đo được cần nó. Toolchain OS-specific route đúng guest/runner; thiếu SDK/license ghi readiness requirement, không host fallback.
- Kiểm output như dữ liệu không tin cậy: path containment, digest, size, schema, origin, context, forged capture và cache poisoning. Artifact receipt chứng minh attempt/output lineage; không tự chứng minh semantic statement của target-controlled plugin đúng.
- Enforce trust classes và promotion predicate trong contract: captured bytes / producer assertion / validated observation. Exact compile/model facts cần protected capture ngoài target-controlled process/tool binaries hoặc independently validated controlled replay; semantic extraction chạy trong fresh isolated worker trên immutable input. In-process plugin-generated index/args không đủ tự xác nhận. Không capture được thì giữ unmet evidence requirement.

**Kiểm chứng và exit**

- Trước repo thật: authored adversarial fixtures thử đọc host sentinel, ghi host source/cache, truy cập metadata/private network, fork/log/disk bomb, spawn child sống sau cancel, forged receipt, poisoned artifact, unsafe extraction và missing sandbox. Không thành công bất kỳ host escape/side-effect case đã đăng ký.
- Malicious build plugin sửa classpath/capture agent/index rồi exit 0: receipt vẫn có thể capture bytes, nhưng forged facts không đạt promotion, không đóng obligation và không contaminate shared semantic cache. Test này bắt buộc qua executor thật.
- Positive fixtures Maven/Gradle generations và đa module thực sự build trong guest; output import sau executor restart có hash/context đúng; offline re-execution theo captured dependency closure.
- Static pass xanh vẫn không bỏ native build-validation obligation; exact validated capture cho phép cache hit, đổi input/toolchain phải invalidate. Build failure giữ source facts và readiness status, không fabricate successful compilation.
- Test standalone host invocation và arbitrary command từ API bị từ chối. Phải có **real executor tests**, mocks không đủ để mở execution lane. Nếu không có môi trường VM, phần còn lại vẫn tiến triển nhưng không được báo bước 3 hoàn tất.

## Bước 4 — Đóng khoảng trống generated code và Java/Spring bằng build evidence

**Mục tiêu:** xử lý các nguyên nhân đang tạo hàng loạt missing symbols, đặc biệt gRPC/Protobuf/Lombok và reactor output. Phạm vi: generated/artifact importers, exact classpath assembly, JavaParser adapter, Spring ingestion, evidence reconciliation.

**Công việc chi tiết**

- Import generated Java/resources/classes từ declared và observed output roots: protoc/gRPC, OpenAPI, ANTLR, annotation processors (Lombok, MapStruct, QueryDSL, Dagger khi fixtures yêu cầu), kapt/KSP và custom generators. Dùng generator thật trong guest; không viết emulator từng thư viện để thay byte output.
- Lineage ràng buộc source/schema/config, tool/version/compiler, recipe, dependency closure và output SHA-256. Original snapshot giữ nguyên; generated overlay riêng. Mở importer hiện chỉ Java/declared source root một cách versioned; root được build bổ sung phải có observed evidence, không nằm ngoài model vô cớ.
- Class directories, JAR/WAR/nested archives và reactor outputs được capture đúng vị trí ordered manifest. Metadata reader không class-load target. Multi-release JAR/JPMS, target platform, binary/source correspondence và duplicate classes có explicit outcome. Không nhận stale `target/`/`build/` chỉ vì file tồn tại.
- Java semantic acquisition dùng exact evidence mới để rerun affected source sets; provider alternate compiler-backed attribution cho gaps có proof, JavaParser vẫn primary. Parse/type error của một source giữ local declarations/evidence hợp lệ, không biến recovery symbol thành correct target.
- Spring enrich dùng version-qualified metadata/generated descriptors và existing condition/order engine. Build thành công, compiled `@Bean`, generated proxy hoặc một AOT context không chứng minh active bean ở mọi cấu hình. Reflection/custom registrar có closure proof hoặc residual unknown.
- Gap resolution ledger append-only nhưng **active unmet requirements** mới quyết định residual coverage. `SATISFIED` phải có output validation; historical gaps còn trong audit, không vĩnh viễn buộc job PARTIAL. Conflict có thể mở lại obligation và withhold dependent results.
- Cache parsed/generated/binary facts theo exact dependency footprint, provider version và observed input closure. Generated non-reproducibility là evidence, không được xóa timestamps hoặc bytes để giả determinism. Retry chỉ khi nguyên nhân/input/resource thay đổi hợp lệ.

**Kiểm chứng và exit**

- Fixtures 2–3 modules: proto producer → generated stub → Java caller; Lombok builder/constructor → caller; MapStruct output; custom generated root; one broken sibling. Assert exact target/owner/origin/span/lineage và root-cause delta, không chỉ assert build exit 0.
- Stale output, changed generator/version/config, same class name ở hai artifacts, mismatched source/compiler index, partial processor output và nondeterministic generator đều có negative controls. Resolved gap vẫn audit được nhưng không đếm active; unresolved/contradiction vẫn qualify score.
- Deliver source → build → generated/binary → exact Java/Spring graph journey. Với fixtures expected-complete, không còn gap do chưa acquire generated/reactor output; unsupported runtime semantics vẫn giữ đúng trạng thái.

## Bước 5 — Semantic engines cho từng ngôn ngữ và liên kết xuyên ngôn ngữ

**Mục tiêu:** triển khai non-Java semantic analysis thực, dùng native compiler/indexer sau contract chung; không đổi nhãn syntax inventory thành semantic coverage. Phạm vi: polyglot domain catalogs/identities, language adapter modules/workers, graph/query mapping, interop providers.

**Contract chung bắt buộc**

- Thêm `LanguageSemanticProvider` có language/dialect versions, build environment, dependency closure, document hashes, category obligations, cancellation/checkpoint và capability report. Existing `FrontendRequest`/18 Java categories vẫn nguyên nghĩa; bridge Java sang envelope mới, không ép engine khác đi qua Java options.
- Tạo per-document/category obligation census độc lập với output rows của indexer, từ inventory + syntax/compiler traversal; reconciliation phát hiện omitted file/occurrence. Unparsable/opaque scope giữ denominator-open; không lấy “engine không báo lỗi” làm proof exhaustive.
- Adapter có compiler/typechecker-backed declarations, scope/import binding, references, types và applicable callable/inheritance/read/write relationships; exact diagnostics và source mapping. SCIP/SemanticDB/IndexStore là input formats; category thiếu trong format cần supplemental native extraction. Parser syntax/LSP search fallback luôn giữ qualification, không đạt exit semantic engine.
- Stable language-native logical symbol keys dùng namespace/scope/native signature và identity-schema version; compiler/provider versions và source/binary correspondence nằm ở evidence identity/relation. Source/SCIP/bytecode cùng declaration không nhân bản tùy provider. Convert range encoding UTF-8/UTF-16/UTF-32 → actual raw-document span, giữ non-BMP/CRLF/escaped source-map controls. Không sửa M1 Java IDs hồi tố.
- Provider/runtime/package acquisition có pin/license/availability check; tool có thể execute compiler plugin, macros hay package install hook thì luôn guest. Không cài/chạy executable lấy từ repo trên host.

**Backlog pack bắt buộc khởi đầu — tất cả nằm trong bước 5, không phải milestone mới**

| Ngôn ngữ | Phương án engine ưu tiên, cần pin và kiểm chứng | Điểm kiểm quan trọng |
|---|---|---|
| Java | JavaParser hiện có + isolated compiler/SCIP evidence khi cần | Giữ 18 families/G2 contracts; không replace primary tùy ý |
| Kotlin | `scip-java` Kotlin compiler lane; Kotlin Analysis API bổ sung khi thiếu category | Nullable/platform types, overload, extension, Java interop, kapt/KSP; standalone API có instability |
| Scala | Compiler SemanticDB cho version tương ứng + importer | implicits/givens, traits, generated members, Scala 2/3; không dựa vào tuyên bố chung scip-java hỗ trợ mọi Scala |
| Groovy | Native compiler AST/type-checking adapter | dynamic dispatch giữ candidates/unknown; AST transforms trong guest |
| TypeScript và JavaScript | `scip-typescript` + versioned TypeScript checker extractor | project references, path aliases, JS module resolution, JSX/TSX, dynamic calls; compiler API version boundary |
| Python | `scip-python`/Pyright-derived analysis + semantic supplement | imports/namespace packages, annotations/inference, unresolved dynamic behavior; không import app để thu types |
| Go | `scip-go` + `go/packages`/`go/types` adapter | modules/workspaces, build tags, interfaces, generated/cgo inputs |
| Rust | rust-analyzer semantic/SCIP lane | cfg/features, traits/generics, macro expansion, proc macros và build.rs trong guest |
| C và C++ | `scip-clang`/Clang tooling + compile database | macros/includes, templates, compile commands/defines/target ABI; nhiều config không trộn |
| C# và Visual Basic | Roslyn; `scip-dotnet` cho capability được version chứng minh, VB adapter riêng khi cần | target framework, partial types, generators, MSBuild tasks, exact references |
| PHP | `scip-php` candidate + native/static type-analysis adapter khi thiếu catalog | Composer/autoload, traits, namespaces, dynamic dispatch; không coi plugin có tên là đã đạt coverage |
| Ruby | `scip-ruby`/Sorbet-backed lane + scoped supplement | mixins/metaprogramming, unresolved runtime effects, exact dependencies |
| Dart | `scip-dart`/Dart analyzer adapter | package resolution, mixins/extensions, generated sources |
| Swift và Objective-C | Swift/Clang index store + IndexStoreDB/native semantic adapter | modules, protocols, bridge headers, toolchain/SDK/OS requirements |
| Schema/config/build languages | Protobuf descriptor sets, OpenAPI/JSON Schema/GraphQL resolvers; SQL dialect model; XML/YAML/JSON/HCL/build DSL packs | Reference/schema binding, generated client/server lineage và config semantics; không gọi generic YAML parsing là execution semantics |

Đây là baseline implementation catalog đề xuất, **không là whitelist ngôn ngữ được nhận**. Với ngôn ngữ khác được phát hiện: inventory và syntax vẫn chạy, registry mở semantic-provider obligation; triển khai/qualify pack trong cùng framework trước khi báo complete cho ngôn ngữ đó. Từng pack có sub-checklist trong task này và trạng thái delivered/unverified/missing rõ ràng. Không tuyên bố toàn bộ ngôn ngữ thế giới đã được giải quyết khi baseline table mới pass.

**Interop và sản phẩm semantic**

- Java↔Kotlin/Scala/Groovy dùng compiler symbol/source/binary correspondence. JNI/JNA dùng declaration/signature/registration evidence; native library export name một mình chưa đủ chứng minh call target.
- Java backend↔TS/Python/Go client qua schema/generated lineage, endpoint/method/version và configuration evidence. HTTP path/topic/table-name trùng nhau chỉ là candidate nếu thiếu routing/ownership proof. Graph giữ `contract-reference`/`abi-binding`/`possible-call` khác nhau.
- Thêm language applicability vào queries/metrics/policies; chưa có equivalent formula/calibration thì không tính một score tổng bịa. Không làm Spring truth regions áp lên arbitrary non-Spring code. Cấu hình build của nhiều engine giữ từng context và liên kết được chứng minh.

**Kiểm chứng và exit**

- Mỗi pack phải qua golden definition/reference/call/type cases, wrong/ambiguous/missing dependency, Unicode spans, version mismatch, generated input, deterministic replay và actual module integration. Compiler/indexer là implementation không tự làm oracle; labels độc lập/hand-reviewed/second source cần cho correctness.
- Dropped document/call và empty-but-exit-0 indexer không đạt completion; provider upgrade giữ ID unchanged declaration, đổi identity schema phải có explicit compatibility mapping/rejection.
- Ít nhất một mixed repo fixture Java+Kotlin+TS+Python và một native bridge fixture chạy end-to-end; thêm isolated positive/negative fixtures cho **mọi row/ngôn ngữ trong baseline**, không tính engine trả toàn UNKNOWN là pass.
- Per-language/per-category attempted, correct, incorrect, unresolved, ambiguous, omitted, unsupported, error và missing-provenance được báo riêng. Mọi pack mandatory chưa đạt khiến bước 5 chưa complete; không chuyển về inventory-only để đóng task.

## Bước 6 — Hoàn thiện graph/query/UI, đo hiệu quả và nghiệm thu M8.4

**Mục tiêu:** chứng minh pipeline mới giảm nguyên nhân thất bại thực, chạy vượt trần cũ, giữ correctness, và dùng được trong sản phẩm. Phạm vi: partitioned graph persistence/query, metrics/policy consumers, CLI/API/workbench, evaluation fixtures/protocol.

**Công việc chi tiết**

- Wire từ acquisition tới semantic workers, graph segments và stable queries; mọi complete/partial partition có manifest. Existing services không được load full `KnowledgeGraph` ngầm khi dataset lớn. Implement disk-backed adjacency, batched joins, exact aggregate reduction và deterministic external traversal; metrics global không được cộng component-local values sai (đặc biệt SCC/cycle, distinct reachability và cross-module denominators).
- API/workbench/CLI hiển thị module/language/build progress, active requirements, historical attempts, captured artifact lineage, resource waiting và resume; selection/paging không đổi denominator. Exact source drill-down đọc range từ CAS, không gửi toàn file/graph. Mọi language pack xuất hiện trong structure/search/dependency/evidence views.
- Versioned operational waiting/resume states không thay semantic completeness; publication có transaction/commit marker và receipt không nhận late success sau cancel. Durable reports/history/selected snapshot phục hồi sau restart. Legacy jobs/API có compatibility mapping và discovery, không silently thêm enum vào client cũ.
- Đăng ký cohort trước chấm điểm: plain/legacy/modern Java, Maven nested/multi-root, Gradle composite, generated gRPC/annotation-heavy, JVM mixed, Java+web/Python, native bridge, mỗi language pack baseline, repository rất nhiều file, rất lớn byte/graph, slow/flaky network, intentionally broken/private/missing-toolchain và malicious fixtures. Pin commit/artifacts/toolchain, expected inventories/relationships, labels, license/access và input mode.
- So sánh **cùng snapshot/cohort**: baseline hiện tại, static+streaming, static+isolated build, build+polyglot; phân biệt warm/cold cache và bytes/toolchain download cost. Fixtures và targeted readiness checkpoints được phép trong development; một registered acceptance campaign cuối có **nhiều repetitions** cho determinism/performance. Không giới hạn chỉ một lần chạy khiến không đo được repeatability.

Ma trận archetype bắt buộc có cả đồ án Java ngoài Spring: IDE/Ant và thư viện local; desktop Swing/JavaFX; Servlet/JSP/Jakarta WAR; Android với hai variants có source/resources riêng; JPMS/multi-release; OSGi hoặc AspectJ binary transformation; JNI/native bridge. Cross với JDK legacy/current/preview đã pin, generated/handwritten, main/test/custom, nested roots và missing dependency. Dùng pairwise cho interaction rộng và case riêng cho rủi ro cao, không tạo tích Descartes vô hạn. Compiler/source-map/framework limitations được đo riêng; việc nhận đủ bytes không tự chứng nhận framework semantics. Sáu bước là sáu gói công việc lớn; riêng catalog engine cần nhiều adapter và qualification cases, không là sáu bản vá nhỏ.

**Ma trận đạt/không đạt bắt buộc**

| Nhóm | Điều kiện nghiệm thu |
|---|---|
| Không bỏ sót | Toàn bộ oracle-known paths/modules/languages/roots/source sets có disposition; acquired bytes đúng hash; không full-snapshot claim khi frontier mở |
| Semantic correctness | Toàn bộ labeled expected facts/negative controls của required catalogs pass; zero observed invented targets/spans/false certainty trên controls; báo denominators và chưa suy rộng ngoài cohort |
| Generated/build readiness | Complete fixtures đóng active generated/reactor/classpath gaps thực; thất bại module A không xóa independent B; capture đúng variant/profile/compiler |
| Safety | Real VM adversarial isolation tests pass; missing executor không host fallback; no writable host/source/cache/network-secret exposure trong controls |
| Scale/recovery | Vượt 128 MB, 20k files, 120 s và giới hạn job cũ; chọn thêm dataset lớn hơn available heap/old graph-store limit; complete hoặc resource waiting có resume, không global crash/false complete |
| Determinism | Cùng captured closure/provider/policy và complete work cho cùng canonical digests/active gaps qua schedule/cold-warm/restart; rebuild nondeterminism được tách riêng |
| Product parity | CLI/API/UI/export cùng snapshot/context/language counts, findings, metric status và source evidence; page/filter không thay metric population |
| Hiệu quả | Report FAILED/PARTIAL theo root cause trên fixed cohort, acquired/resolved **correct** coverage, time-to-first-useful-result, closure time, peak RSS/disk/network, retries/cache hits; không dùng reduced denominator/renaming để cải thiện số |

Resource profiles, số repetitions, concrete corpus hashes và performance budgets phải chốt trước scoring trong bước này dựa trên hardware thực và product budget đã duyệt; không bịa SLA ngay khi chưa có prototype. Target engineering: memory tăng theo active working set/index cache thay vì toàn repo; unchanged sealed partitions không chạy lại. Phải đo cả worst observed và phân vị trên repetitions đủ nghĩa; không tuyên bố “nhanh nhất” khi chưa có comparison hợp lệ.

**Exit cuối:** publish bounded acceptance results, compatibility/migration notes, residual obligations và runnable local journey; independent review về isolation/identity/denominator; cập nhật current-state. M8.4 implementation có thể được nghiệm thu cho published tested capability catalog; claim “mọi repo mọi ngôn ngữ đã complete” không được suy ra từ gate hữu hạn. M9 tiếp tục evaluation/hardening rộng; M10 vẫn cần human Track A approval; không tự bắt đầu Track B.

## Handoff của lượt lập kế hoạch

Trước: M8.3 implemented, Git clean ở `39cd969`, chưa có plan M8.4; target execution ở ASSESS/HOLD, extra-language semantics ngoài phạm vi. Sau: sáu bước, contract, research và ADR được viết; cập nhật chỉ đạo build/scale/language và next task. **Không production code, target build, image install hay benchmark nào được chạy để lập kế hoạch này.** Mọi số test/gate lịch sử giữ nguyên, không dùng chúng làm evidence cho thiết kế mới.

| Hạng mục bàn giao | Kết quả / giới hạn |
|---|---|
| Files changed / durable state | Bốn tài liệu mới plan/contract/research/ADR-007; đồng bộ AGENTS, rules, semantic-evaluation skill, project-context/current-state/roadmap, task index và supersession notes ở contracts/ADRs cũ; chỉ Markdown |
| New evidence / decisions | Kiểm source/callers và nguồn chính thức; chọn streaming CAS, native build/context DAG, VM executor và compiler-backed language packs như thiết kế PROVISIONAL; scope build/polyglot do human xác nhận |
| Document verification | PASS `git diff --check`; PASS 318 local Markdown link targets trên 25 changed/new documents; PASS đúng sáu bước, whitespace của bốn file mới và skill frontmatter không đổi; không sửa production/evidence artifacts |
| Skill validator limitation | `quick_validate.py` không chạy được do thiếu PyYAML trên cả Python hệ thống và bundled runtime; không cài package để lập kế hoạch. Frontmatter không thay đổi; kiểm cấu trúc/liên kết riêng không thay cho một full validator pass |
| Independent review | Read-only review bốn draft ban đầu đưa bốn finding: trust promotion, logical symbol identity, build-context denominator và independent occurrence census. Đã sửa trong contract/tasks và tự rà; reviewer hết giới hạn sử dụng trước final re-review, nên không tuyên bố independent final approval |
| Remaining validations / blockers | Không blocker cho plan. Implementation còn phải kiểm real isolation backend/hardware, pins/licenses, engine accuracy và resource/performance profiles; chưa có benchmark hoặc tốc độ/coverage mới |
| Decisions requiring approval | Không cần hỏi lại scope đã được human chọn. Không tự mua hạ tầng, upload source hoặc đổi metric/score calibration; những thay đổi ngoài scope đó vẫn theo governance |
| Git actions | Không commit, push, history rewrite hoặc xóa work |

**Next task tại thời điểm lập kế hoạch: M8.4.1 — implement bước 1**, bắt đầu bằng regression chứng minh comprehensive inventory giữ `.proto`/`.py`/unknown file và nested build root, sau đó streaming CAS/inventory+journal contracts, compatibility controls và resumable acquisition. Không triển khai sáu bước cùng một diff; mỗi bước hoàn thành vertical behavior và affected-consumer checks trước bước phụ thuộc.

## Thực hiện và bàn giao bước 1 — 2026-10-18

**STATE BEFORE:** Git clean ở `0a21db2`; M8.3 bounded acceptance, M8.4 chưa triển khai. Human đã duyệt scope acquisition/build/polyglot qua ADR-007; lượt này chỉ thực hiện bước 1. Route: lead architect cho identity/storage boundary, implementer + TDD, verifier và self-review; không có independent reviewer.

**CONFIRMED — implementation và kiểm thử trong phạm vi công bố:**

| Exit / phần thay đổi | Kết quả và bằng chứng |
|---|---|
| Full inventory / CAS | `analyzer.acquisition.streaming` thêm raw-byte logical paths, SHA-256 content handles, long counts, inventory/selection identities, cursor và journal ports. `StreamingContentStore` stream/range/verify, atomic create-if-absent publication; không giữ toàn bytes trong heap. Comprehensive inventory giữ mọi extension, tests/unknown/nested roots, explicit exclusions; classification không loại nhầm Java package tên `build` hoặc `test` |
| Frontier / recovery | `SqliteInventoryStore`: immutable hash-chained operation log + pinned SQLite index/WAL; corruption rebuild, read-only live pages, CAS journal transitions, seal chỉ sau frontier đóng và không còn gap. Process thực `Runtime.halt(71)` giữa log/index replay exactly once. Disk-reserve pressure, actual thread interruption/cancel, missing/corrupt sealed bytes và restart có controls; checkpoint giữ interrupt flag |
| Git / transport | Pin commit/tree/blob, hydrate raw objects không checkout; independent object/CAS checksums; no vendor whitelist, kiểm toàn bộ resolved endpoints, DNS pinning/TLS/redirect policy, explicit scoped credential refs và configured strict SSH. Native HTTPS fixture có trusted local CA, shallow→full fallback, reset giữa large blob, idle stall termination, LFS thật, submodule exact closure, offline restart reuse. Không chạy target hooks/build |
| Paths / archives | Raw case/NFC/non-UTF8/Windows-invalid Git names được giữ; v1 supplementary-Unicode ordering parity. Local depth 300, source mutation, links/cycles và missing LFS/submodule giữ obligations. ZIP/ZIP64 central/local/CRC reconciliation, traversal/duplicate/truncation controls; không extract trên host |
| Product / durable state | `RepositoryIntake`, persistent connections, disk-backed jobs/backlog/frozen history và report CAS; setup drift và late output/cancellation guarded. Explicit v2 acquisition/history/resume HTTP + OpenAPI; CLI `acquire`/`inventory`, scan capture trước bounded semantic view. Oversized semantic work yêu cầu worker, OOM worker trả `SEMANTIC_WORKER_REQUIRED` và không giữ slot vô hạn |
| Compatibility / migration | M1 golden identities giữ nguyên; v2 inventory tách v1 semantic selection. Bounded view ghi actual flags/exclusions và full parent digest. Scan receipt canonical chỉ bind immutable manifest, không bind UUID vận hành. V1 graph/query/formula unchanged; additive partition reference/cursor port, physical graph segmentation để bước 6 |
| Scale / replay | Fresh JVM `-Xmx48m`: 160,000,001 content bytes và 1,000,001 generated entries; seal, reopen, cursor reconciliation. Repeated runs cho cùng manifest `sha256:6ec2d2c98c5281d8df20418302341954b68ae31eaf0902de0d5eee3c6fdf77c3`. Đây là storage/acquisition test, không là million-file semantic hoặc throughput gate |
| Physical acquisition vượt trần cũ | Thư mục thật có 20,002 files, gồm single file 128,000,001 bytes; full inventory/count/cursor reconciliation pass. Bounded legacy consumer trả `COMPATIBILITY_WORKER_REQUIRED`, sealed manifest giữ nguyên. Fixture được tạo trong JUnit temp directory, không commit corpus |

**Routine decisions trong scope:** SQLite JDBC `3.53.4.0` (license/binary SHA-256 trong [implementation contract](../../architecture/m8-streaming-acquisition.md)); raw byte ordering cho v2, UTF-16 ordering cho M1; immutable CAS/log làm authority và SQL làm rebuildable index. Không cần quyết định scope mới. Native Git tested `2.53.0.windows.3`; provider `source.git-cas:m8.4-v2`.

### Verification commands and results

Chạy trên trusted platform modules, không chạy lifecycle của repository mục tiêu. Output chuẩn ở từng `target/m84/surefire-reports`; không tạo formal reproducibility package. Các lệnh Maven dùng `-Denforcer.skipRules=reactorModuleConvergence` vì chạy module độc lập, và `-Dplatform.build.directory=target/m84` để tránh IDE-output contamination. Artifact install với `-DskipTests` chỉ refresh direct-consumer dependencies, không tính là test pass.

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=StreamingContractTest,IdentityAndSourceContractTest,CanonicalSerializationTest,RepositoryAcquisitionContractTest,RepositoryUnderstandingContractTest,CanonicalGraphContractTest,InMemoryGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m84" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=StreamingRepositoryAcquirerTest,StreamingStorageTest,ArchiveSnapshotAcquirerTest,LfsObjectAcquirerTest,GitSnapshotAcquirerTest,NativeHttpsAcquisitionTest,FilesystemRepositoryAcquirerTest,AcquisitionCheckpointStoreTest,FileGraphStoreTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m84" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=StreamingScaleTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m84" -q
.\mvnw.cmd test -pl backend "-Dtest=DurableAnalysisJobsTest,PersistentReportsTest,AcquisitionApiTest,ComprehensiveSelectionTest,RepositoryConnectionsTest,AnalysisJobsTest,AnalysisJobApiTest,LocalAnalysisRunnerTest,ArchitectureQueryApiTest,PublishedReportsTest,WorkbenchApiMainTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m84" -q
.\mvnw.cmd package -pl cli "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m84" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/m84/evo.jar
node --test backend/src/test/js/workbench-model.test.cjs
git diff --check
```

**PASS:** analyzer **31 tests / 7 suites**; filesystem **43 / 10** (9 compact/integration suites cộng scale worker); backend **61 / 11**; CLI **59 / 9** — tổng **194 Java cases / 37 suites**, zero failures/errors/skips trên tập checks nêu trên. Node **7/7**. Packaged JAR **10/10 journeys**, **1,212 platform classes**, zero IDE error stubs; `acquire`/`inventory`/`resume` exit 0, existing source/query/report journeys giữ expected PARTIAL exit 3 của fixture, không bị đổi thành complete giả. CLI package PASS.

Scale suite lần cuối **147.994 s**, digest khớp các lần trước. Focused physical-directory/single-file case **199.543 s** (suite **199.598 s**); đây không phải inner-loop <5 s và không là throughput SLA. Kiểm thử stall cho phép cả native curl low-speed guard lẫn controller idle lease dừng request; không đòi một guard cụ thể phải thắng race. Operator-configured internal HTTPS path/port có red→green regression ở backend; public URLs không còn bị tự thêm `.git`.

Document checks: `git diff --check` PASS; **179 local Markdown link targets** trên **68 changed/new files**, whitespace và đúng sáu step headings PASS. Generated verified JAR được đặt ở `cli/target/evo.jar` sau packaged smoke, SHA-256 `cbcdfc7d5b45a3513f09356621314aca963af95a1c6e1a10a33fde231c93db36`; JAR cũ được giữ trong `cli/target/`, source changes chưa commit.

**New evidence / regression repairs:** red→green controls cho comprehensive file selection, canonical scan replay, worker OOM release và interrupted-checkpoint persistence; corrupt derived index rebuild khác corrupt authoritative CAS (trường hợp sau phải khôi phục bytes gốc, không overwrite evidence). Source-gone replay đọc exact captured closure; setup configuration đổi phải bị chặn. TDD method filters được dùng; Maven startup và một số integration cases vượt inner-loop target 5 giây. Không coi target đó đã đạt; scale/native transport/consumer checks là pre-handoff set cần thiết.

**LIMITATIONS / BLOCKERS:** không blocker cho bước 1 trong tested adapter contract. Live SSH-server positive qualification chưa chạy (host có SSH client, không có sshd); policy/configuration fail-closed được kiểm. Archive ngoài ZIP/ZIP64 giữ typed requirement. Hardware power-loss durability, arbitrary remote/corpus interoperability và full semantic scale chưa chứng nhận. Local filesystem có mutation detection, không phải atomic OS snapshot. Physical graph/query segmentation, multi-root semantic planning, VM execution và actual language engines còn lần lượt ở bước 6/2/3/5; complete acquisition không được promote thành complete semantics. Không có independent final review, full reactor, external corpus gate hay M8/M10 promotion.

**DURABLE STATE / FILES CHANGED:** streaming domain + filesystem adapters/tests; backend intake/jobs/connections/reports/API/OpenAPI; CLI commands/help/packaged tests; [implementation contract](../../architecture/m8-streaming-acquisition.md), readiness contract link, module READMEs, current-state, roadmap status link và task index. Không đổi M1 preimages, metric/score formulas, project scope hoặc historical gates. Không commit, push, xóa user work hoặc sửa raw generated evidence.

**EXACT NEXT TASK: M8.4.2 — readiness planner cho mọi build root/module/environment.** Bắt đầu từ sealed inventory và fixture hai nested independent roots, thay first-entry semantic filtering bằng ownership/context coverage và requirement DAG. Native facts ban đầu dùng passive/captured models; target execution chỉ sau step-3 executor conformance. Các bước sau chưa được thực hiện trong lượt này.

## Thực hiện và bàn giao bước 2 — 2026-10-19

**STATE BEFORE:** Git clean ở `f0870a5`; bước 1 đã commit, bước 2 pending. Scope và execution boundary theo ADR-007 không thay đổi. Route: lead architect cho ranh giới readiness/identity; implementer + TDD, verifier và implementation self-review. Không có independent reviewer.

**CONFIRMED — implementation và kiểm thử trong phạm vi công bố:**

| Exit / phần thay đổi | Kết quả và bằng chứng |
|---|---|
| Mọi build root / module | `ReadinessDiscovery`, passive native-family adapter và secure Maven adapter phát hiện các root độc lập/lồng nhau. Maven aggregation khác parent, missing sibling và duplicate GAV có requirement; Gradle include/composite/buildSrc/custom source sets được giữ; Bazel package BUILD nằm dưới workspace root. IDE/plain, Ant/sbt và polyglot markers có native-model discovery obligations, không bị chuyển giả sang Maven |
| Context / ownership denominator | `BuildContextSelection` versioned, partition discovered/selected/unselected và unknown dimensions; main/test/custom/variant không bị gộp. Profile-only module không lọt vào baseline. Authored captured exclusive variants giữ cả hai source populations. Streaming JSON-lines ownership ledger có đúng một disposition trên mỗi inventory entry, gồm unknown/raw paths và explicit exclusions; unresolved source roots không bị thay bằng default giả |
| Preflight / requirements / recipes | Source/target/release/encoding/preview khác build JDK và analyzed platform. Wrapper/config/lock digests, generator/platform/archetype requirements được giữ. Mọi selected buildable context có build-validation obligation dù static pass không có gap. Recipes chứa declared inputs, expected outputs, tool/config pins, root execution scope, broker/network/resource policy, side effects và satisfaction checks; không có host execution method |
| DAG / independent recovery | Validated requirement DAG, deterministic priority/tie-break và joint module SCC groups. Recipe giữ root scope khi chưa chứng minh plugin effects. Regression thực giữ đúng hai source types từ outer + nested independent Maven roots dù sibling POM malformed; lỗi của root kia không còn xóa phần lành |
| Provider ladder / exactness | Luồng Maven sản phẩm duyệt mọi captured root và dùng dependency acquisition fixpoint sẵn có; CLI `--artifact-repository` mở explicit supported artifact lane. Range/SNAPSHOT/relocation/variant vẫn cần native resolver, không bịa version/classpath superset. Conflicting overlapping captured inputs được withhold kèm gap |
| Captured-model satisfaction | `CapturedBuildEvidence` kiểm exact context/selection/closure, provider version, toolchain, ordered classpath/module path, args và generated inputs. Producer assertions, receipt/exit 0 riêng lẻ, wrong variant/order/version/toolchain không đóng requirement. No-compile phase cần proof + native semantic validation. Protected verifier là port; chưa có real executor qualification |
| Durable product journey | Readiness report + ownership ledger ở CAS, separate journal marker sau publication, restart/cache identity checks. CLI `readiness` và v2 API phân trang roots/modules/contexts/requirements/recipes/groups/selection/unknown. Analysis manifest bind `readiness.plan`. Scan canonical receipt loại UUID vận hành. Authored offline sealed-Git replay kiểm release acquisition writer trước planning; không phải fresh remote qualification |

**FILES CHANGED / DURABLE STATE:** core readiness contracts/planner/capture controls và build/source ingestion; Maven adapter; backend readiness CAS/journal, multi-root dependency composition, runner/connections/API/OpenAPI; CLI command/scan/distributable smoke; affected tests, module READMEs, [implementation contract](../../architecture/m8-build-readiness-planner.md), current-state và task index. Không đổi M1 entity/snapshot preimages, metric/score formulas, roadmap sequence hay historical gates.

### Verification commands and results

Chỉ chạy trusted platform modules; không chạy lifecycle/model/index của target. Native Maven reports ở `target/m842/surefire-reports`. Artifact install với `-DskipTests` refresh dependency cho consumer, không tính là test pass. Skip riêng reactor-membership Enforcer rule cho selected-module runs; các rule khác giữ nguyên.

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=ReadinessPlanTest,CapturedBuildEvidenceTest,PassiveReadinessDiscoveryTest,UniversalBuildIngestionTest,ScopedIngestionRecoveryTest,SourcePlanContractTest,IdentityAndSourceContractTest,StreamingContractTest,CanonicalSerializationTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m842" -q
.\mvnw.cmd test -pl analyzer-maven "-Dtest=MavenReadinessDiscoveryTest,UniversalMavenIngestionTest,MavenBuildModelProviderTest,MavenLocalClasspathProviderTest,MavenDependencyArtifactResolverTest,SourcePlanProjectionTest,PlatformReactorModelTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m842" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=UniversalArchitecturePipelineIntegrationTest,UniversalIngestionPipelineTest,CanonicalGraphPipelineIntegrationTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m842" -q
.\mvnw.cmd test -pl backend "-Dtest=ReadinessReportsTest,ComprehensiveSelectionTest,AcquisitionApiTest,LocalAnalysisRunnerTest,RepositoryConnectionsTest,AnalysisJobsTest,AnalysisJobApiTest,ArchitectureQueryApiTest,PublishedReportsTest,WorkbenchApiMainTest,DurableAnalysisJobsTest,PersistentReportsTest,ApiSpecificationTest" "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m842" -q
.\mvnw.cmd package -pl cli "-Denforcer.skipRules=reactorModuleConvergence" "-Dplatform.build.directory=target/m842" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/m842/evo.jar
node --test backend/src/test/js/workbench-model.test.cjs
git diff --check
```

| Trusted module | Java cases / suites | Final result |
|---|---|---|
| analyzer | 53 / 9 | PASS; identity/serialization/streaming, source plans, scoped recovery and readiness controls |
| analyzer-maven | 53 / 7 | PASS; native-family discovery, source-root unknowns, existing passive model/classpath/acquisition contracts |
| analyzer-javaparser | 21 / 3 | PASS; source → semantic → graph integration and provider/version assertions |
| backend | 67 / 13 | PASS; persisted/reopened readiness, API/OpenAPI, source analysis, connections, job/report durability |
| cli | 59 / 9 | PASS; complete module, acquire/readiness/restart and canonical scan replay |

Tổng **253 Java cases / 41 suites**, zero failures/errors/skips. Node **7/7**. Đây là bounded pre-handoff set, không phải full reactor hoặc corpus gate. Inner loops dùng method/class filters; Maven startup và real-source/JDK/report journeys vượt target 5 giây. Không tuyên bố target đó đã đạt; suite timings trong native reports không bao gồm toàn command wall time.

Packaged JAR smoke **11/11 journeys PASS**, **1,243 platform classes**, zero IDE error stubs. `acquire`, `inventory`, `readiness`, `resume` exit 0; source/query/policy/assessment journeys giữ expected PARTIAL exit 3, không giả native validation đã complete. Verified distributable được copy tới `cli/target/evo.jar`, SHA-256 `e8b98c8171b6853f9219af21d30b6e3710b5b85edb30fc592344aef7a316bc68`; bản cũ giữ ở `cli/target/evo.before-m842.6b571a11affd.jar`. `git diff --check`, **155 local Markdown targets** trên bảy changed/new documents và đúng sáu step headings PASS.

**NEW EVIDENCE / REPAIRS:** red→green cho first-entry filtering, malformed independent-root suppression, profile-only baseline leakage, Gradle appended defaults, Bazel workspace grouping, unresolved/unsafe replacement roots và operational UUID trong canonical scan output. Các invariant/capture controls khác được bổ sung quanh implementation; không tuyên bố mọi assertion đều có observed-red. Provider assertion trong consumer được update theo version mới. POM-only platform fixture được đồng bộ đủ module CLI đang được root POM khai báo; OpenAPI fixture kiểm đủ 17 routes. Không sửa raw generated evidence để làm xanh.

**DECISIONS MADE / REQUIRING APPROVAL:** chọn additive readiness schema/provider v1, separate CAS/journal report, conservative full-inventory input closure, candidate passive projections và isolated root recipes trong scope đã được duyệt. Không cần quyết định human mới; không mua infrastructure, tải VM image hoặc upload source. Cache footprint rộng có thể invalidate nhiều hơn cần thiết; không tự giả independence để tối ưu.

**LIMITATIONS / BLOCKERS:** không blocker cho passive step-2 exit trong tested contract. Real VM isolation và native effective manifests chưa được kiểm; protected capture verifier trong tests là authored fixture, không phải executor certificate. Dynamic profiles/variants/targets, SDK/toolchains, generated outputs và language engines giữ active requirements. Metadata plan hiện materialize root/module/context working set; report decoding có bounded worker, oversized report giữ bytes và báo `READINESS_REPORT_WORKER_REQUIRED`. Physical planning/semantic/graph scale và workbench readiness views cần step-6 integration qualification. Không independent review, broad remote/corpus acceptance hoặc M8/M10 gate promotion. Không commit, push, history rewrite hay xóa user work.

**EXACT NEXT TASK: M8.4.3 — validated isolated native build/index executor.** Dùng sealed inventory + readiness selection/DAG/recipes, implement disposable VM/controller, broker/cache, resource/network policy và protected capture/import. Chạy real positive/adversarial isolation fixtures trước target execution. Không có VM thì giữ blocker/evidence đúng; tuyệt đối không host fallback. Bước 3–6 chưa hoàn tất trong task này.
