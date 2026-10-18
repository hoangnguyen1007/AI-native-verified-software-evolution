# M8.4 — Nghiên cứu build, quy mô và semantic engines đa ngôn ngữ

Ngày đối chiếu nguồn: 2026-10-16–17. **Phương pháp:** đọc contracts/current-state, kiểm tra source và callers thực tế, đối chiếu tài liệu/specification/source chính thức. Không chạy target builds, corpus, cài toolchain hay đo benchmark trong nghiên cứu này. **CONFIRMED** bên dưới nghĩa quan sát trực tiếp source hoặc quyết định human, không phải thực nghiệm hiệu năng. Lựa chọn triển khai là **PROVISIONAL**; hypothesis có acceptance test trong [kế hoạch](../tasks/m8/04-comprehensive-repository-readiness.md).

## Câu hỏi quyết định

Làm sao bỏ những điều kiện khiến repo bị từ chối sớm, lấy được model/generated code thực, thêm semantic engines ngoài Java và giữ evidence/determinism/isolation mà không phải viết lại toàn bộ hệ sinh thái build/compiler? Human đã chấp thuận thay đổi scope này; M8.4 không còn bị giới hạn ở passive Java-only readiness.

## Bằng chứng từ mã hiện tại

Các đường dẫn là điểm thay đổi, không phải kết luận mọi provider đã được kiểm toán đầy đủ. Giá trị là mặc định hoặc validation tại lớp được nêu, không luôn là giới hạn hiệu lực của mọi launcher.

| Source/caller đã kiểm tra | Quan sát CONFIRMED | Hệ quả thiết kế |
|---|---|---|
| [RepositoryConnections](../../backend/src/main/java/com/evolution/backend/analysis/RepositoryConnections.java) | 120,000 ms, 128,000,000 bytes, 20,000 files; ba Git hosts; shallow/default branch, no submodules/LFS smudge | Thay global rejection bằng transport policy, full selected-tree hydration và recoverable acquisition |
| [LocalAnalysisRunner](../../backend/src/main/java/com/evolution/backend/analysis/LocalAnalysisRunner.java) | 10k files/dirs, 10MB/file, 128MB total, depth64; thiếu snapshot trả FAILED | Thay toàn đường acquisition/assembly, không chỉ remote adapter |
| [ScanSelection](../../backend/src/main/java/com/evolution/backend/analysis/ScanSelection.java) | Whitelist file suffix; nhiều JSON chỉ nhận ở resources/application; exclude tests/target/build/node_modules; traversal 50k/depth64 | `.proto`, `.py`, `.rs`, lockfile và root metadata có thể bị loại bởi selection hiện tại; comprehensive inventory phải độc lập semantic selection |
| [MavenScan](../../backend/src/main/java/com/evolution/backend/analysis/MavenScan.java) | `outsideEntryBuild()` có thể exclude Java/POM ngoài selected model và Gradle files; `inputs()` local-cache-only; custom source set chưa được compose | Multi-root discovery và exact per-source-set model là ưu tiên, không chỉ thêm heap |
| [RepositoryAcquisitionPolicy](../../analyzer/src/main/java/com/evolution/analysis/acquisition/RepositoryAcquisitionPolicy.java) | Max discovered 1m, depth256, file phải fit byte array | Repository-size independence cần data contract khác |
| [AcquiredFile](../../analyzer/src/main/java/com/evolution/analysis/acquisition/AcquiredFile.java) / [FilesystemRepositoryAcquirer](../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/FilesystemRepositoryAcquirer.java) | Defensive byte-array copies, whole file list và directory child lists | Streaming CAS/external sorting trước bỏ guard; không chuyển LIMIT thành OOM |
| [RepositorySnapshot](../../analyzer/src/main/java/com/evolution/analysis/contract/source/RepositorySnapshot.java) | Lists và whole `CanonicalJson.write(files)` vào content hash | Streaming hash phải match legacy canonical bytes; new envelope có version riêng |
| [UniversalBuildIngestion](../../analyzer/src/main/java/com/evolution/analysis/buildmodel/UniversalBuildIngestion.java) | Default build input files2048/characters1m/tokens200k/depth64 | Audit downstream limits theo scope/batch, không để intake rộng nhưng build discovery cụt |
| [DependencyAcquisitionPolicy](../../analyzer/src/main/java/com/evolution/analysis/dependency/DependencyAcquisitionPolicy.java) | Coordinates100k, repositories16, passes128, retries2 và per-artifact array assumption | Native exact dependency capture + resumable closure; retry hữu hạn mỗi attempt vẫn cần |
| [WorkerProcessSupervisor](../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/WorkerProcessSupervisor.java) | Trusted worker; 5min/1GiB heap, 64MiB input, 16MiB output, 1k tasks, 256MiB batch; không target executor | Không được gọi worker JVM hiện có là OS sandbox; dùng nó làm compatibility baseline |
| [GeneratedSourceBundleImporter](../../analyzer/src/main/java/com/evolution/analysis/ingestion/GeneratedSourceBundleImporter.java) | Java outputs, declared roots, bytes và lineage; tạo acquired revision từ supplied bundle | Đã có seed importer; cần build-backed overlays, dynamic generated roots, binaries và language-general outputs |
| [KnowledgeGraph](../../analyzer/src/main/java/com/evolution/analysis/graph/KnowledgeGraph.java) / [FileGraphStore](../../analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/FileGraphStore.java) | Whole lists/maps/adjacency; store default64MB/int byte limit; streamed JSON vẫn decode thành whole graph | Out-of-core input không đủ; query/store/metrics cũng phải xử lý partitions |
| [AnalysisJobs](../../backend/src/main/java/com/evolution/backend/analysis/AnalysisJobs.java) | 15min default/1h upper ceiling; process-local history/retry/report orchestration | Durable execution journal, lease/resource waiting và compatible API evolution |
| [Current state](../current-state.md) | Historical root self-scan 298 Java files, ~720MB graph, 44,539 gaps, ~210s được ghi nhận | Bằng chứng lịch sử về expansion pressure; không là phép đo mới hay benchmark M8.4 |

`git status --short`, working/staged diff tại entry sạch; HEAD `39cd969` (`done workbench`). Command discovery thấy `git.exe`, `docker.exe`, `wsl.exe`; không thấy `Get-VM`/QEMU trong command lookup này. Điều đó **không xác minh** Docker daemon, Hyper-V firmware/edition hoặc VM isolation. Plan không giả máy hiện tại đã có executor khả dụng.

## Phương án thu nhận và scale

| Phương án | Ưu điểm | Nhược điểm / lựa chọn |
|---|---|---|
| Xóa constants, giữ whole-memory pipeline | Ít diff | Dịch failure sang OOM/disk/full graph; loại |
| Tăng quota cấu hình | Hotfix nhanh | Chỉ dời trần; không đáp ứng yêu cầu |
| Sparse checkout chỉ phần Java | Có kết quả nhanh | Bỏ component, trái comprehensive; chỉ là thứ tự hydration, không final scope |
| CAS + external inventory + durable work DAG + partitioned graph | Scale theo working set, deduplicate, resume, source evidence exact | Chọn; cần migration xuyên nhiều module |
| Distributed cluster ngay | Mở scale-out | Tăng vận hành/cost/consistency trước khi giải in-memory contracts; giữ port, chưa chọn |

Git hỗ trợ shallow history và partial object filters; filter không có nghĩa dữ liệu đầy đủ. Dùng selected commit/tree và hydration checklist; batch missing objects để tránh per-file round trip. Git không cung cấp một lời hứa resume byte-offset cho mọi pack download; chỉ tái sử dụng phần object store hợp lệ và retry transaction bị ngắt. [Git clone](https://git-scm.com/docs/git-clone), [partial clone design](https://git-scm.com/docs/partial-clone).

LFS pointer chứa object digest và size, không phải nội dung thực. Acquisition cần verify object tương ứng, giữ cả pointer provenance; disabled smudge không đủ cho comprehensive payload coverage. [Git LFS specification](https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md).

SQLite WAL cho phép readers và writer cùng hoạt động nhưng một writer tại một thời điểm, cần checkpoint và không phù hợp network filesystem. **Suy luận thiết kế:** phù hợp metadata/journal local của modular monolith nếu batched writes và read transactions ngắn; CAS/graph segments vẫn ngoài DB. Phiên bản runtime/driver cần được pin và kiểm security/corruption regression khi triển khai, không dùng DB bytes như semantic digest. [SQLite WAL](https://www.sqlite.org/wal.html).

## Build strategy: hybrid là lựa chọn phù hợp nhất

| Phương án | Khả năng bao phủ | Disposition |
|---|---|---|
| Passive parsing mãi mãi | Hữu ích khi thiếu môi trường, không đọc đủ dynamic model/generators | Giữ fast path, bỏ vai trò ceiling |
| Build toàn repo mọi lần ngay từ đầu | Có real outputs khi build được | Lãng phí, lỗi toàn cục, không dùng evidence sớm; không mặc định |
| Viết emulator cho mọi plugin/generator | Có thể deterministic trong subset nhỏ | Chi phí không bền vững, lệch behavior thực; loại làm hướng chính |
| Passive requirement discovery → cached capture → native isolated build → reconcile | Dùng facts sớm, build đúng phần cần, vẫn có root reactor fallback | Chọn |
| Chỉ nhập CI artifacts do user cung cấp | Hữu ích cho SDK/private/offline | Giữ fallback, không thay automatic build lane |

Maven lifecycle liên kết generation/compilation/test/package theo phase; chạy compile có thể chạy generator. Reactor ordering không chỉ dựa thứ tự folder và có lựa chọn selected projects/also-make/fail-at-end. **Suy luận:** lên recipe từ requirement và native reactor semantics, không chạy riêng module bằng một vòng `cd && mvn` tùy tiện. [Maven lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html), [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html).

Gradle initialization/configuration là thực thi build logic; Tooling API có model, progress, cancellation và dùng daemon, không phải passive parser. Included builds có dependency substitution. **Suy luận:** Tooling API/capture phải chạy trong guest, pin versions/variants và terminate daemon theo attempt; không lấy regex DSL làm exact model. [Build lifecycle](https://docs.gradle.org/current/userguide/build_lifecycle.html), [Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html), [Composite builds](https://docs.gradle.org/current/userguide/composite_builds.html).

Dependency locking và checksum/signature verification giải các vấn đề khác nhau. **Suy luận:** giữ selected coordinates, metadata, repository, bytes và producer trust riêng; không coi lockfile hay SHA-256 là proof source correctness. [Gradle locking](https://docs.gradle.org/current/userguide/dependency_locking.html), [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html).

JDK 23 thay đổi điều kiện kích hoạt annotation processing. **Suy luận:** pin target JDK và ghi actual processor configuration; không globally thêm `-proc:none` làm mất generators, cũng không thêm `-proc:full` rồi tuyên bố là build gốc. [JDK 23 release notes](https://www.oracle.com/java/technologies/javase/23-relnote-issues.html).

Maven toolchains tách tool JDK khỏi JVM đang chạy Maven; Android variants kết hợp build type/flavor với source/resources riêng; Jasper có thể biên dịch JSP thành servlet Java. **Suy luận:** readiness và cohort phải có plain/desktop/web/Android Java, exact toolchain/variant và generated sources, không mặc định mọi project là Spring Boot + Maven. Plan thêm các archetype này cùng JPMS/native/binary-transformation controls; đây là yêu cầu kiểm chứng, chưa phải support đã đo. [Maven toolchains](https://maven.apache.org/guides/mini/guide-using-toolchains.html), [Android build variants](https://developer.android.com/build/build-variants), [Tomcat Jasper](https://tomcat.apache.org/tomcat-11.0-doc/jasper-howto.html).

## Sandbox: compatibility trước, optimization có kiểm chứng

| Backend | Trade-off quyết định |
|---|---|
| Host subprocess/thư mục tạm/`-Xmx` | Không cô lập target lifecycle; loại |
| Ordinary container riêng trên host | Hữu ích đóng gói; mặc định mount/capabilities/kernel surface không đủ để coi là đạt threat model arbitrary repo |
| Disposable full VM + pinned toolchain image | Chọn baseline compatibility; guest đúng OS/ABI, resource enforcement ngoài guest; boot/RAM cost cần đo |
| gVisor | Application kernel, có syscall compatibility/performance trade-offs; chọn optional fast backend sau cohort conformance |
| Firecracker | Linux/KVM microVM, yêu cầu host/jailer/guest image; hợp lý khi cần density, không tự là Windows-native solution |
| Remote managed service | Có thể cung cấp OS/resource không có local | Chỉ operator-configured executor và explicit data/cost policy; không tự upload source/mua cloud |

Docker docs mô tả rủi ro daemon, capabilities, mounts và kernel vulnerabilities; rootless giảm một phần quyền nhưng không tự chứng minh isolation contract. **Suy luận của nghiên cứu:** không chọn ordinary host Docker như sole default boundary cho mọi untrusted build. [Docker security](https://docs.docker.com/engine/security/).

gVisor intercept system calls qua Sentry, không implement mọi specialized API và vẫn phụ thuộc host resource/network controls. Vì mục tiêu compatibility rộng, gVisor không thay thế bắt buộc mọi full VM. [gVisor security model](https://github.com/google/gvisor/blob/master/g3doc/architecture_guide/security.md).

Firecracker cần Linux/KVM và production jailer; Hyper-V phụ thuộc Windows edition/hardware virtualization. **Suy luận:** executor SPI với VM baseline phù hợp workspace Windows hiện tại hơn hard-code Firecracker. Những lựa chọn này chưa được đo trên máy chủ dự án. [Firecracker getting started](https://github.com/firecracker-microvm/firecracker/blob/main/docs/getting-started.md), [Hyper-V requirements](https://learn.microsoft.com/en-us/windows-server/virtualization/hyper-v/host-hardware-requirements).

Bazel sandbox phục vụ input/build isolation và không che mọi host filesystem trong mọi mode. Do đó Bazel chạy bên trong execution boundary của platform; một build tool tự gọi task là sandboxed chưa đủ. [Bazel sandboxing source](https://github.com/bazelbuild/bazel/blob/master/docs/docs/sandboxing.mdx).

## Semantic engines: reuse compiler, giữ domain độc lập

| Hướng | Đánh giá |
|---|---|
| Tree-sitter/ctags cho tất cả | Rộng và phục hồi syntax tốt; không đủ symbol/type/call resolution |
| Một LSP client cho mọi language | Tận dụng server nhưng capability tùy chọn, query interactive, có thể không có complete batch denominator |
| Một universal AST/type system tự viết | Mất language semantics và rất tốn công; không chọn |
| Native compiler/indexer packs + SCIP/SemanticDB/IndexStore imports + neutral evidence envelope | Chọn; supplement native facts khi format không chứa architecture categories |
| Phụ thuộc Sourcegraph server/hosted analysis | Không cần; local open-source formats/indexers đã đủ làm adapter candidates, license từng tool kiểm riêng |

Tree-sitter xây syntax trees và xử lý lỗi parse; đây là fast structural lane. LSP là protocol với capabilities và position encoding, không certificate exhaustive semantic analysis. [Tree-sitter](https://tree-sitter.github.io/tree-sitter/), [LSP specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/).

SCIP schema cho biết producer có thể ở phổ heuristic tới compiler-backed; hỗ trợ streaming documents và nhiều position encodings. Nó thiếu một closed architecture-obligation ledger của platform; reference không đủ tự chứng minh call category. **Suy luận:** nhập SCIP cùng source/tool/context hashes và supplemental evidence, giữ unresolved/omitted rows từ denominator độc lập. [SCIP schema](https://raw.githubusercontent.com/scip-code/scip/main/scip.proto).

Các indexer cụ thể được upstream liệt kê cho Java/Kotlin/Scala, JS/TS, Python, Go, C/C++, Rust, Ruby, .NET, Dart/PHP. Danh sách upstream không phải empirical acceptance của platform và không chứng minh mọi construct/version. [SCIP upstream](https://github.com/scip-code/scip), [Sourcegraph indexer documentation](https://sourcegraph.com/docs/code-navigation/precise-code-navigation).

Nguồn theo engine, dùng để chọn adapter chứ chưa xác nhận integration:

| Engine | Bằng chứng và giới hạn ảnh hưởng quyết định |
|---|---|
| JVM | [scip-java](https://github.com/scip-code/scip-java) hiện mô tả Java/Kotlin; dùng [SemanticDB specification](https://scalameta.org/docs/semanticdb/specification.html) cho Scala, kiểm compiler-version support riêng. Không dựa riêng bảng marketing tổng quát. |
| Kotlin | [Analysis API](https://kotlin.github.io/analysis-api/index_md.html) có types/scopes/diagnostics; standalone còn có incompatible-change risk. Pin adapter/compiler pair, không expose PSI/Ka symbols vào core. |
| Groovy | [Native semantics](https://docs.groovy-lang.org/latest/html/documentation/core-semantics.html) phân biệt dynamic/static checking. Adapter không hứa giải mọi metaprogramming runtime. |
| JS/TS | [scip-typescript](https://github.com/sourcegraph/scip-typescript) là candidate batch engine; [TypeScript compiler API](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API) có type/symbol access và cảnh báo API thay đổi. Dùng versioned supplement, không hard-code mọi major tương đương. |
| Python | [scip-python](https://github.com/sourcegraph/scip-python) là analysis/indexer candidate. Phải test imports/stubs/type inference/dynamic residuals trên actual pin; không chạy application imports trên host. |
| Go | [scip-go](https://github.com/scip-code/scip-go) làm batch navigation input; native type/package facts bổ sung architecture categories. |
| C/C++ | [scip-clang](https://github.com/sourcegraph/scip-clang) theo compilation inputs; compile commands/defines/platform là identity, không chỉ file extension. |
| .NET | [scip-dotnet](https://github.com/sourcegraph/scip-dotnet) mô tả Roslyn-backed C#; VB dùng Roslyn adapter nếu pin không có capability, không suy diễn từ tên .NET. |
| Swift/ObjC | [IndexStoreDB](https://github.com/swiftlang/indexstore-db) đọc compiler-generated symbols/occurrences/relations; [SourceKit indexing](https://github.com/swiftlang/sourcekit-lsp/blob/main/Contributor%20Documentation/Background%20Indexing.md) cần target preparation. SDK/OS environment phải thật. |

Rust/Ruby/PHP/Dart candidates được SCIP upstream liên kết, nhưng exact release/license/semantic-category conformance chưa chạy. Đây là implementation work của bước 5, không được quảng cáo “đã hỗ trợ” chỉ từ sự tồn tại tool. Các ngôn ngữ khác dùng cùng provider qualification contract và giữ open obligations đến khi có implementation.

## Determinism và đánh giá có ý nghĩa

Maven reproducible-build hướng dẫn timestamp/environment/tool configuration; `SOURCE_DATE_EPOCH` giúp tool hỗ trợ nó chứ không biến build bất kỳ thành deterministic. **Suy luận:** phân biệt replay immutable captured inputs với repeated clean builds; lưu raw hash và report drift. [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html), [SOURCE_DATE_EPOCH](https://reproducible-builds.org/docs/source-date-epoch/).

SLSA provenance tách build definition, builder/run details và resolved dependencies. **Suy luận:** mượn vocabulary để thiết kế bundle, không tuyên bố đạt SLSA level hay trust chỉ vì có JSON/hash. Đặc biệt target plugin có thể tác động capture/indexer trong guest; trust qualification và independent labels vẫn cần. [SLSA provenance](https://slsa.dev/spec/v1.1/provenance).

Các hypothesis cần kiểm: incremental evidence reuse giảm total work; module-local recovery giảm failed-root rate; native build capture giảm generated/classpath gaps; compiler-backed polyglot packs tăng correct coverage; disk-backed graph làm peak RAM phụ thuộc working set. Phải đo fixed cohort với cold/warm caches, resource profile, phase times, disk/network cost, failures và held-out cases. Không có speedup/coverage percentage mới được công bố trong tài liệu này.

## Quy định nào phải thay và quy định nào tiếp tục có hiệu lực

- Thay blanket no-build bằng host prohibition + validated isolated execution; đổi ASSESS/HOLD của build lane thành authorized M8.4 direction, giữ chưa-implemented status.
- Thay Java-only delivery restriction bằng semantic-provider expansion đã được human chọn. Java primary parser vẫn giữ; không thêm ngôn ngữ bằng cách phá Java contracts.
- Thay total-size/time ceilings bằng resumable resource policy; giữ byte/payload/OS/network abuse controls để hệ thống có thể tiếp tục phục vụ.
- Thay “one-shot benchmark only” bằng registered campaign có repeated runs; targeted real-world readiness checkpoints được phép khi cần kiểm integration, không bắt cả platform reactor mỗi slice.
- Giữ evidence/uncertainty, host containment, credential boundaries, no silent omissions, metric honesty, Git no-commit/no-push và M10/Track B gates. Historical raw results và checkpoint statements không sửa hồi tố; thêm supersession references vào contracts cũ.
