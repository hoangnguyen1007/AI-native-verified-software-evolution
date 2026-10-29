# M8.4 Step 5 — Kế hoạch ba bước cho polyglot và kiến trúc frontend

Ngày lập: **2026-10-26**; cập nhật nghiệm thu **2026-10-29**. Trạng thái: **BƯỚC 1 CLOSED / CORE MVP; BƯỚC 2–3 PLANNED**. [Bản ghi nghiệm thu Bước 1](04-step5-step1-execution.md) ghi quyết định human, code, bằng chứng và giới hạn; [implementation boundary](../../architecture/m8-polyglot-semantics.md) ghi phạm vi hiện tại. Đây là phân rã **đúng ba bước bên trong M8.4 Step 5**, không thay kế hoạch M8.4 sáu bước và không tự bắt đầu Step 6/M9/Track B. Cả ba bước đều triển khai năng lực; kiểm chứng diễn ra trong từng bước, không có bước thứ ba chỉ dành cho test hoặc đóng task.

**CONFIRMED — chỉ đạo human trong lượt này:** lập kế hoạch toàn diện, ưu tiên hiểu kiến trúc frontend, detect rộng các ngôn ngữ khác, không sa vào tái hiện mọi chi tiết ngôn ngữ nhưng vẫn hoàn thành năng lực phân tích cần thiết. **ASSUMPTION:** “front” là frontend/client của ứng dụng mục tiêu, không chỉ lớp parser của analyzer. Java/Spring giữ nguyên độ sâu đã có; catalog non-Java bắt buộc không được cắt về inventory-only.

Authority: [Human Mandate](../../architecture/comprehensive-repository-coverage-problems.md), [ADR-007](../../decisions/ADR-007-isolated-build-and-polyglot-readiness.md), [hợp đồng M8.4](../../architecture/m8-repository-readiness.md), [Step 5 của plan gốc](04-comprehensive-repository-readiness.md#bước-5--semantic-engines-cho-từng-ngôn-ngữ-và-liên-kết-xuyên-ngôn-ngữ). [Nghiên cứu và nguồn chính thức](../../research/2026-10-26-m845-polyglot-frontend-design.md) giải thích lựa chọn và alternatives. Toàn bộ API/schema/profile mới dưới đây là **PROVISIONAL design**; tên lớp có thể đổi, observable contracts và coverage obligations phải giữ.

**Điểm xuất phát CONFIRMED:** Git sạch tại `de6969c`; Steps 1–2 đã triển khai; Step 3 CLOSED / CORE MVP; Step 4 CLOSED với 70 registered controls theo current state. Các trust labels và genuine Java/Spring gaps vẫn giữ nguyên. Chưa có evidence cho một polyglot engine mới. Không dùng số test lịch sử để chứng nhận kế hoạch này.

**Phương án được đề xuất:** một lớp semantic trung lập dùng lại native compiler/typechecker, một lớp hiểu frontend/framework theo version, và một lớp nối các ranh giới kiến trúc bằng bằng chứng. Tree-sitter/syntax traversal giúp phát hiện và kiểm kê; SCIP/SemanticDB/IndexStore là interchange; chúng không sở hữu domain hoặc tự chứng minh mọi binding. Không viết một universal compiler hoặc runtime emulator cho tất cả ngôn ngữ.

**Ý nghĩa kiểm chứng được của “không bỏ sót”:** mọi input/component/context phát hiện được đều có disposition, mọi category kiến trúc áp dụng đều có nghĩa vụ phân tích, mọi fact có evidence, mọi phần thiếu còn hiện rõ và được tiếp tục bằng provider phù hợp. Mục tiêu 100% là **accounting của snapshot và catalog đã khai báo**; không hứa chứng minh mọi hành vi runtime của mọi chương trình. `UNKNOWN` trung thực là kết quả hợp lệ cho cơ chế động chưa đủ dữ kiện, nhưng không được dùng nó thay toàn bộ implementation của một pack.

| Chiều phải báo riêng | Câu hỏi cần trả lời |
|---|---|
| Inventory và bytes | Đã nhìn thấy/thu nhận những gì; còn frontier hoặc bytes không truy cập được nào? |
| Readiness và context selection | Root/module/source set/variant nào được chọn, chưa chọn, chưa biết; tool/dependency/generator nào còn thiếu? |
| Semantic coverage | Category/occurrence nào đã xử lý, thiếu, lỗi, không áp dụng có proof? |
| Semantic correctness | Những kết quả nào được đối chiếu độc lập đúng/sai; trên corpus và version nào? |
| Architecture usefulness | Trả lời được những câu hỏi component/dependency/route/contract/impact nào và giới hạn gì? |
| Operational completion | Công việc hoàn thành, đang chờ, bị hủy hay thất bại; tiếp tục từ đâu? |

Không trộn các chiều này thành một phần trăm “hiểu kiến trúc” hoặc score mới. Step 6 vẫn sở hữu full partitioned graph/query/UI scale, broad performance và nghiệm thu tích hợp M8.4; Step 5 phải có bounded integration thật để các engine và bridge dùng được.

## Bước 1 — Hoàn thiện nền semantic chung và các engine ngôn ngữ đủ chiều sâu kiến trúc

**CONFIRMED — human nghiệm thu Core MVP, 2026-10-29:** chủ dự án chọn **“Chấp nhận Core MVP và đóng Task 1”** cho 18 adapter và các lượt native đã có, outcomes thiếu/unsupported hiển thị rõ, durable replay, embedded/cross-provider và cơ chế xác thực đã kiểm chứng. Bước 1 **CLOSED / CORE MVP**. Qualification đầy đủ A01–A12 và đăng ký phương thức production được rút khỏi điều kiện đóng lần này, không ghi PASS và không tự tạo task thay thế. Phạm vi được nhận dùng captured/registered contexts; automatic ecosystem/model execution và mapping biến đổi/nhiều tầng tổng quát vẫn là giới hạn năng lực. Các catalog chi tiết dưới đây giữ vai trò tham chiếu, không mở lại điều kiện đóng đã rút. [ADR-007](../../decisions/ADR-007-isolated-build-and-polyglot-readiness.md) sở hữu quyết định; [bản ghi](04-step5-step1-execution.md) sở hữu bằng chứng. Trust labels và capability gaps giữ nguyên; nghiệm thu này không đóng toàn Step 5 hoặc thay phạm vi Bước 2–3.

**Kết quả cần có:** từ sealed inventory và selected build contexts, chạy được semantic provider thích hợp, tạo symbols/relationships có provenance, phát hiện omissions, giữ partial results và replay được. Mười tám ngôn ngữ lập trình trong bảng dưới có adapter hoạt động và qualification riêng; schema/config/build families dùng contract/census chung ở đây và được hoàn thiện semantic profiles trong Bước 2. Bước 1 không kết thúc ở SPI, registry hoặc một TS demo.

**Discovery và ownership toàn diện**

- Thay việc lấy extension làm kết luận duy nhất bằng registry kết hợp logical path, file signature/shebang, source syntax, manifests, native source membership và khai báo có provenance. Phân biệt language, dialect, framework, runtime và build family. `.h` có thể C/C++; `.m` có thể Objective-C hoặc ngôn ngữ khác; không chọn tùy ý khi ambiguity còn tồn tại.
- Bao quát file không extension; `.mjs/.cjs/.mts/.cts`, JSX/TSX, `.kts/.sc`, C/C++ variants, `.vb/.vbproj`, `.m/.mm`, `.vue/.svelte/.astro`, HTML/CSS/preprocessors, template/build/config/schema files và notebook/code blocks có vai trò source. Embedded regions có mapping về document gốc; plain docs không tự trở thành executable source.
- Mỗi entry thuộc main/test/integration/custom/generated/vendored/external/build-logic/resource/documentation/unknown population có bằng chứng. Vừa giữ origin vừa giữ vai trò; generated không tự đồng nghĩa external. Shared file ở nhiều compilation contexts được xử lý theo từng context, không nhân tổng file vật lý.
- Đối chiếu native file list với sealed inventory: file ngoài include, excluded, shadowed, conditional, generated sau build, submodule hoặc symlink đều có row. Loại khỏi một compile context không có nghĩa xóa khỏi repository denominator. Unknown source phải mở discovery/semantic obligation.
- Đọc đủ manifests/workspaces/lockfiles và bản mở rộng được phát hiện: Node package workspaces/project references/import maps; Python packages/environments; Cargo workspaces/features; Go modules/workspaces; .NET solutions/project references/multi-targeting; CMake/Make compilation contexts; Composer/Bundler/Dart/Swift; Maven/Gradle/sbt JVM mixed. Executable configuration chỉ chạy trong guest.

**Contract và data path chung**

| Thành phần đề xuất | Trách nhiệm bắt buộc |
|---|---|
| `LanguageProfile` / registry | Language/dialect, identity/category schema, tool/provider/version range đã qualify, input modes, native recipe, source encoding, capabilities, license và status |
| `LanguageSemanticRequest` | Base inventory + optional M1 view, exact root/module/named source set/variant/platform, immutable documents/overlays, ordered resolution environment, catalog, work policy, trust policy, cancellation/checkpoint |
| `LanguageSemanticProvider` | Stream/batch observations và diagnostics theo scope; declarations/refs/calls/types áp dụng; không leak AST/PSI/compiler objects hoặc trả toàn graph trong một IPC message |
| `SemanticObligationCensus` | Đếm độc lập input documents/scopes/categories/occurrences trước đối soát output; có frontier và opaque scopes khi không đọc hết |
| `LanguageSemanticResult` | Native-to-neutral mapping, emitted/unmapped facts, gap bindings, source maps, provider diagnostics, semantic/evidence digests và coverage; không suy completeness từ exit code |
| `SemanticReconciler` | Reuse causal-ledger principles của Step 4; answer/predicate-specific satisfaction, conflict/revocation/reopening, current/history riêng |

Đầu vào mới không bắt buộc phải materialize cả repo thành M1 list snapshot. Java đi qua compatibility bridge giữ `FrontendRequest`, `m2-java-4`, 18 families, exact classpath và existing M1 preimages; adapter ngôn ngữ khác dùng native options. CAS/table cursors, bounded messages và checkpoints phải là đường chính; compilation unit không được cắt tùy tiện làm mất semantics.

**Catalog kiến trúc chung — mỗi pack phải disposition toàn bộ các nhóm này**

| ID | Bằng chứng/quan hệ phải cung cấp khi áp dụng |
|---|---|
| A01 | Project/module/package/namespace/source-set ownership; source và dependency membership |
| A02 | Declarations, lexical scopes, visibility, exports/public API, aliases/re-exports và scoped logical symbols |
| A03 | Import/include/package/reference binding; actual target, missing target hoặc candidate set có lý do |
| A04 | Type uses, signatures, parameters/returns, field/property types, type aliases và generic arguments cần để hiểu dependency |
| A05 | Inheritance/implementation/traits/protocols/mixins/extensions, override và realization khi native evidence đủ |
| A06 | Direct calls/constructors/callable references; caller owner, receiver/dispatch evidence, callback registration; reference không tự thành CALLS |
| A07 | State/field/property/global reads và writes theo native semantics; compound accesses, captured variables và dynamic access còn rõ |
| A08 | Annotation/decorator/attribute/macro/framework entry points và generated/synthetic declarations có lineage |
| A09 | Imports/external ABI/resource/schema/config usage tạo ranh giới kiến trúc; unresolved external endpoint không bị bỏ |
| A10 | Conditions: compile flags/features/target/source-set selection và runtime guard đã hiểu; không gán Spring truth semantics cho ngôn ngữ khác |
| A11 | Diagnostics, syntax/type recovery, ambiguous/dynamic/unsupported outcomes, full source coordinates hoặc explicit absent location |
| A12 | Input/output/census accounting, generated/binary correspondence, cancellation/restart/replay và applicability proof |

Không tự xây solver cho toàn bộ type system. Lấy kết quả từ native engine, bảo toàn cấu trúc type cần thiết và dùng refinement có bằng chứng cho câu hỏi kiến trúc cụ thể. Reflection/eval/metaprogramming/dynamic dispatch phải được detect; chỉ mở rộng thành target/edges khi có scope và proof. Một candidate duy nhất vẫn có thể chưa chắc chắn: số lượng candidate không thay semantic status.

**Baseline engine bắt buộc — từng ngôn ngữ được nghiệm thu riêng**

| Language pack | Engine/nguồn ưu tiên PROVISIONAL | Phạm vi kiến trúc và góc cạnh cần kiểm |
|---|---|---|
| Java | Existing JavaParser + captured compiler evidence | Regression 18 families; exact context, generated/class-only output; không đổi primary parser |
| TypeScript | Versioned TS checker adapter; SCIP supplement | Project references, paths/baseUrl, ESM/CJS, package exports/imports/conditions, declaration merging, overloads/generics, JSX/TSX, type-only imports, `.d.ts`, library/platform views |
| JavaScript | Checker-compatible JS/JSDoc lane + native syntax supplement | `allowJs/checkJs`, prototype/module/global scripts, callbacks, dynamic property/import/require, browser/Node/Bun/Deno context; Flow/dialect khác có lane hoặc gap riêng |
| Kotlin | Kotlin Analysis API / compatible scip-java lane | JVM/multiplatform source sets, null/platform types, extension/property/delegation, Java correspondence, generated kapt/KSP; API-family pins |
| Scala | Version-matched compiler SemanticDB + supplement | Scala 2/3, traits, givens/implicits, extension/generated members, overloads và Java binary correspondence |
| Groovy | Native compiler AST/type-check metadata | Static/dynamic modes, properties/closures/traits, AST transforms trong guest; dynamic hooks không được biến thành exact calls |
| Python | Compatible Pyright/scip-python lane + scoped supplement | Module/namespace/import resolution, `.pyi` và dependency types, decorators/descriptors, annotations/inference, dynamic attributes; legacy versions cần đúng provider; không import app để lấy types |
| Go | `go/packages` + `go/types`; SCIP nếu hữu ích | Workspace/modules, build tags/GOOS/GOARCH, interfaces/method sets, generics, embed/generated/cgo; không trộn Load contexts |
| Rust | rust-analyzer semantic adapter / compatible SCIP export | Cargo package/crate/features/cfg/target, traits/generics, macro expansion, generated code; build.rs/proc macros chỉ trong execution lane |
| C | Clang tooling + exact compile commands | Translation unit/includes/macros/defines, header context, linkage, functions/globals, callbacks/function pointers và ABI |
| C++ | Clang tooling / scip-clang + supplement | C capabilities cộng namespace/classes/templates/specialization/overload/virtual dispatch/modules; instantiated và written source không nhập nhằng |
| C# | Roslyn semantic model / compatible SCIP | Project/framework/reference closure, partial declarations, properties/events/delegates, generators, conditional compile, extension methods |
| Visual Basic | Roslyn VB adapter; SCIP chỉ cho capabilities đã qualify | VB binding/imports/modules/partial types/events/default properties và C# assembly bridges; không xem C# passing là VB passing |
| PHP | Qualified scip-php hoặc static type analyzer adapter | Composer/autoload/include/namespaces/classes/traits; callable/type/property bindings, dynamic dispatch; framework conventions có profile riêng |
| Ruby | Qualified scip-ruby/Sorbet-backed lane + supplement | require/load/gems/modules/mixins, method/constants; typed và untyped code có controls, metaprogramming giữ unresolved remainder |
| Dart | Dart analyzer; SCIP optional | Package/import/export, classes/mixins/extensions, generics, generated code; Flutter widget/navigation/native boundary ở mức architectural profile |
| Swift | Native compiler/index store + supplement | Modules/packages/protocols/extensions/generics, generated/macro source, target/SDK; host OS của analyzer không thay SDK context |
| Objective-C | Clang/index store + supplement | Headers/modules/classes/categories/protocols/selectors, dynamic dispatch, bridge headers/Swift correspondence; SDK đúng nền tảng |
| Schema/config/build families — implementation owner: Bước 2 | Native descriptors/resolvers/dialect-aware adapters | Protobuf/OpenAPI/GraphQL/JSON Schema/SQL/XML/YAML/JSON/HCL/build DSL; Bước 1 giao registry/census/envelope, Bước 2 hoàn thiện structural/reference semantics, Bước 3 nối architecture |

Bảng là backlog bắt buộc, không phải toàn bộ ngôn ngữ thế giới. Language mới giữ inventory/census, tạo provider implementation/qualification obligation và đi qua cùng registry. Không tự cài plugin do target yêu cầu; không gọi “complete” cho language/context chưa có pack đạt profile. Với pack nhiều dialect/version, declare supported tuple và unverified tuples riêng; không dùng một modern fixture chứng nhận mọi historical syntax.

**Execution, evidence và identity**

- Bổ sung native recipe/export/reader cho Node/Python/Go/Cargo/.NET/Composer/Bundler/Dart/Swift/Clang và JVM lanes cần thiết. Ưu tiên offline exact captures hoặc registered isolated execution. Node install hooks, Python build backends, MSBuild tasks, macros và compiler/indexer plugins đều là target execution.
- Core MVP hiện chỉ là bounded registered path: xây registration theo ecosystem với pinned tools/images/transports, input layout, private writable state, controlled resources/network, output census và hash/lineage checks. Không biến API thành tùy ý command runner, không mở host fallback, không phục hồi campaign Step 3 đã rút. New method qualification chỉ cho claim/recipe cần trong Step 5.
- Phân biệt observed package graph, exact compiler resolution, captured bytes và validated semantic facts. Fresh isolated semantic worker đọc captured immutable inputs; hooks phải chạy lại execution lane. Không dùng test signer hoặc target-written `approved=true` để cấp production proof. Registered production method chưa có thì capability đó vẫn chưa đạt.
- Logical symbol key bind language + repository/package/module scope + native signature + identity-schema version. Native IDs chỉ là locator có mapping, không copy nguyên provider version/path/hash vào stable symbol key. Package instance/context và local/anonymous symbol collision phải được mô hình rõ; không hứa local-ID stability qua source edits nếu schema chưa hỗ trợ. Provider upgrade trên cùng declarations phải giữ identity.
- Analysis identity bind exact files/overlays, ordered dependencies, platform/flags, provider/tool/profile/work-policy versions; audit identity giữ attempt/time/receipt riêng. Không normalize raw generated bytes để che nondeterministic build.
- Source mapping kiểm raw bytes/charset/BOM/CRLF/non-BMP, UTF-8/16/32 offsets, macro expansion và nhiều tầng virtual/generated files. Không bịa full spans từ line-only metadata hoặc source-map điểm; giữ generated location và mapping precision/gap khi source không có.
- Census không lấy từ emitted index. Scanner/traversal tạo expected document/scope/occurrence ledger riêng; đối chiếu bằng golden labels độc lập. Missing document/call, empty-success index và malformed tail phải bị phát hiện. Parser fail vẫn giữ opaque scope; không thu nhỏ denominator theo số nodes parse được.
- Reuse current/history/conflict/reopening của Step 4 qua neutral bridge có version; không copy Java trust predicates cho language khác. Root closure phải tính cả missing providers, open discovery và selected-but-unprocessed contexts. Provider failure không xóa healthy siblings.

**Ownership mã dự kiến:** `analyzer` giữ contracts/catalogs/reconciliation; `analyzer-filesystem` giữ CAS tables/cursors/publication; `analyzer-execution` giữ guest recipes/registrations; adapter modules/workers giữ native API dependencies; `backend`/`cli` compose và expose capability/coverage. `analyzer-javaparser` chỉ thêm bridge/regressions cần thiết. Chọn module theo boundary/dependency thực, không bắt buộc tạo một module Maven cho mỗi file hay mỗi language.

**Catalog qualification gốc — full qualification được rút khỏi điều kiện đóng Core MVP ngày 2026-10-29**

- Mỗi programming language ở bảng phải có actual native positive journey và independent expectations cho mọi applicable A01–A12; các negative/dynamic cases có đúng outcomes. Schema/config families áp dụng cùng controls khi triển khai ở Bước 2. Ruby/Python/JS untyped cases không bị bỏ để làm đẹp coverage. N/A phải có predicate; toàn UNKNOWN không đạt pack exit.
- Controls chung: wrong/missing/reordered dependency, wrong dialect/toolchain/variant, duplicate names/scopes, generated/native mismatch, Unicode/full-span correctness, omitted occurrence/file, empty-success exporter, diagnostic recovery, stable IDs qua provider upgrade, conflict/revocation, cancel/resume và replay ở process mới.
- Đăng ký compiler/tool/package/SDK pins, license, expected facts và category denominator trước đọc kết quả. Compiler engine không tự là oracle duy nhất; kết hợp hand-reviewed golden cases, native diagnostics và independent compiler/second source khi phù hợp. Disagreements phải adjudicate, không majority vote.
- TDD method/small-class trong loop; pre-handoff chạy bounded unit/contract + direct consumers ở mọi module ảnh hưởng, gồm provider/version assertions. Actual target execution chỉ trong registered guest. Không chạy full reactor platform hoặc tạo ceremonial reproducibility package cho từng pack.
- **Exit Core MVP đã được human chấp nhận:** registry/neutral lane, 18 adapter với tested scoped native capabilities, healthy/broken sibling handling, durable replay, handoff schema/config, embedded/cross-provider và cơ chế xác thực; giữ Java golden contracts. Full pack/method qualification không còn là điều kiện đóng Task 1. Toolchain/method/capability thiếu vẫn ghi đúng **BLOCKED/NOT RUN/UNQUALIFIED** theo đối tượng, không chuyển thành PASS hoặc repository semantic completeness.

## Bước 2 — Phân tích frontend cùng toàn bộ cấu hình, tài nguyên và hợp đồng tạo nên kiến trúc

**Kết quả cần có:** một frontend architecture model có thể trả lời trang nào dùng component nào, route nào gọi code/API nào, state/service nào được dùng, code chạy phía nào, tài nguyên/schema/config nào chi phối nó. Ưu tiên độ sâu ở những quan hệ này; không triển khai browser rendering engine, mô phỏng toàn bộ lifecycle hay arbitrary runtime dataflow.

**Framework-aware frontend thay vì chỉ đọc TS/JS**

| Nhóm profile và coverage | Công việc thực chất |
|---|---|
| HTML + browser JS/TS, Web Components | Entry documents/scripts/modules/import maps, DOM/custom-element registrations, event handlers và bindings có evidence, iframe/worker boundaries; dynamic DOM/selectors có candidates/unknown |
| React và JSX/TSX | Function/class components, component references/composition, props/context/hooks, state/store/service usage, lazy/Suspense boundaries, event handlers; phân biệt actual imported framework symbol với hàm tự đặt trùng tên |
| Vue | SFC script/setup/template/style, props/emits/slots, directives, composables/provide-inject, router/store; dùng Vue language tooling/native compiler mapping |
| Angular | Components/directives/pipes/templates, standalone/NgModule boundaries, DI/providers, inputs/outputs, route/lazy modules; compiler config và template-checking mode thật |
| Svelte | Component/template/script/style, props/events/slots hoặc cơ chế tương ứng version, stores/reactivity, generated virtual mappings; compiler/language-tools đúng version |
| Astro | `.astro`, pages/layouts/islands/hydration directives, component integration và endpoints; không để `tsc` bỏ file rồi vẫn báo complete |
| Meta-framework profiles: Next, Nuxt, SvelteKit, React Router/Remix | File/declaration routing, layout nesting, params, loaders/actions/server endpoints, server/client boundaries, middleware/rewrites và generated manifests; mỗi framework/version có controls riêng |
| Legacy/custom frontend và UI runtime khác | Detect AngularJS/jQuery/Backbone, Solid/Qwik, Electron/React Native/Flutter/native UI hoặc custom frameworks khi có evidence; generic semantic path giữ structure/calls/resources. Framework claim cần profile riêng; thiếu profile mở obligation, không pretend tương đương React |

Sáu nhóm HTML/browser, React, Vue, Angular, Svelte, Astro và bốn meta-framework profiles Next/Nuxt/SvelteKit/React Router–Remix là baseline implementation của bước này, theo version tuples được đăng ký. Hàng legacy/custom/UI runtimes là detection/extension coverage bắt buộc; framework semantics chưa có profile vẫn là nghĩa vụ mở của repository, không được quảng cáo là supported. Không yêu cầu đào sâu mọi API framework: capability contract tập trung ownership, composition, routing, execution boundary, state/service và external contract. Không coi “package có trong lockfile” là proof ứng dụng đang dùng framework đó.

**Catalog frontend phải có disposition đầy đủ**

| ID | Thành phần/góc cạnh | Evidence và kết quả |
|---|---|---|
| F01 | Workspace/app/library/package/public API | Package exports, entrypoints, internal/shared libraries, aliases, re-exports, cyclic package dependencies; ownership không theo folder-name heuristic riêng |
| F02 | Component/template/composition | Parent-child uses, props/input/output/slots/children, template-bound expressions và owner symbols; dynamic component selection giữ candidate/unknown |
| F03 | Route/layout/navigation | Nested/dynamic/catch-all/file-based/config routes, lazy loaders, route guards, redirects/rewrites/base path/i18n, query vs path params; route declaration khác khả năng truy cập runtime |
| F04 | State, event và service | Local/shared/global state, context/DI, store/action/reducer/selector/composable usage, event handlers/bus/subscription; biểu diễn dependency/registered flow, không hứa full value flow hoặc race-freedom |
| F05 | Network/API boundary | fetch/XHR/Axios/wrappers/SDK, GraphQL operations, gRPC-Web, WebSocket/SSE, upload/download, interceptor/base URL và auth/config references; không log secret values |
| F06 | Execution environment | Browser/server/SSR/SSG/edge/worker/service-worker, server actions, browser extension/Electron IPC nếu có; environment-specific package conditions và forbidden-side usage giữ context |
| F07 | Assets và styling | HTML resource refs, CSS imports/modules/composes/custom properties/layers, Sass/Less dependencies, CSS-in-JS/Tailwind config refs, images/fonts/SVG/locales; không suy rendered visibility từ class name |
| F08 | Build/bundler/module loading | Vite/Webpack/Rollup/esbuild/SWC/Babel hoặc native equivalent, loaders/plugins/transforms, dynamic import/glob/context modules, code splitting, virtual modules, generated types/source maps |
| F09 | Microfrontend/embedded surface | Module Federation host/remotes/exposes/shared deps, web components/iframes, runtime remote URLs, workspace/app boundaries; local declarations không chứng minh remote deployed content |
| F10 | Config và conditional architecture | Modes/env keys/feature flags/target/SSR/browser conditions, route guards; giữ known finite alternatives và opaque remainder; không Cartesian-expand vô hạn hoặc gọi union là một runtime world |
| F11 | Test/story/demo/generated code | Unit/e2e/component tests, Storybook/stories/examples, mocks, snapshots, generated clients và vendor bundles; đủ accounting nhưng production metrics tách population |
| F12 | Incomplete/dynamic/recovery | Missing SFC/template processor, unknown plugin, eval, reflection, unresolved selectors/computed routes, minified output thiếu source map; có usable subset và scoped continuation |

Wrapper refinement phải hữu hạn và định danh: bắt đầu bằng direct resolved API, theo alias/re-export và pure local wrappers khi proof đủ, giữ visited set/work policy/dependency footprint; dừng với residual obligation khi code động hoặc semantics không chắc. Không regex-match tất cả `get()` thành HTTP hay tất cả `useX()` thành React hook. Không chạy app/browser để tự khám phá phần động trong phạm vi Step 5 này.

**Schema, dữ liệu, build/config và các thành phần ngoài code**

| Family | Semantic minimum phục vụ kiến trúc |
|---|---|
| Protobuf/gRPC | Descriptor-set imports/packages/messages/enums/fields/services/methods, proto2/proto3/editions theo pin, generated language roots/lineage; không coi cùng message name là cùng schema |
| OpenAPI/JSON Schema | Version/dialect, local/external refs, base URI/anchors, cycles, operation/method/path/server/parameter/body/response/schema; resolution qua captured resources/broker, không fetch tùy ý URL trong file |
| GraphQL | Schema/types/fields/operations/fragments/directives, operation-to-schema binding, generated clients/resolver declarations; resolver ownership/federation chưa có proof thì không suy network topology |
| Event/serialization schemas | AsyncAPI/Avro hoặc format phát hiện được: channels/messages/schema references/version/producer-consumer declarations; discriminator/subject namespace và ownership riêng |
| SQL/DDL/migration/ORM | Dialect, schema/table/view/key/procedure, migration order/context, parsed query reads/writes và ORM mappings; dynamic SQL/connection/schema search path chưa rõ có gap. Không kết nối DB sống hoặc suy actual DB state từ migration files |
| XML/XSD/templates | Namespace/reference/bean/resource/template ownership; JSP/Thymeleaf/Freemarker/Jinja/Razor/Blade/ERB/Pug và equivalents có scoped binding profiles khi phát hiện; không xem generic XML/HTML parse là framework execution semantics |
| JSON/YAML/TOML/properties/env | Schema-qualified keys, imports/includes/overrides, merge/precedence, resource links và referenced config keys. `.env`/credentials chỉ giữ protected raw evidence theo access policy và public redaction |
| HCL/Docker/Compose/Kubernetes/Helm và manifests | Declared modules/images/services/ports/volumes/config refs/build contexts và template/rendered provenance. Render/tool execution chỉ trong guest; không apply/deploy hoặc chứng nhận một deployment world |
| Build/CI/scripts | Referenced packages/tasks/artifacts/generators, shell/PowerShell commands như source evidence, declared workflow jobs/dependencies/outputs. Không thực thi script chỉ vì tìm thấy; unknown effects còn obligations |
| Documents/assets/binaries/archives | Mỗi file có disposition/owner/hash. Architecture docs là declared claims, cần corroboration trước verified architecture facts; binary-only inputs cần metadata adapter hoặc explicit gap, không giả source |

Không biến bảng này thành yêu cầu viết lại mọi DSL interpreter. Implement schema/reference/resource semantics và native model/metadata capture cần để trả lời câu hỏi kiến trúc; công cụ thực thi template/build đi qua sandbox. Unsupported dialect có row và provider obligation; claimed supported profile phải có meaningful positive results.

**Làm đúng source/build context của frontend**

- Giữ dependency closure theo lock/resolved package instances, peer dependencies, workspace links và package-manager modes; npm/pnpm/yarn/PnP/Bun/Deno là native resolver context khi áp dụng, không gộp cả cache vào symbol search.
- Tách source imports, type-only imports, compiled/bundled dependencies và reachable entrypoint graph. Tree-shaking hoặc file không vào một bundle không xóa source architecture inventory; extra bundle evidence có kind/selection riêng.
- Bản build transpile xanh chưa chứng minh type correctness. Chạy native semantic validation phù hợp; TS, Vue, Angular, Svelte, Astro có modes và input sets khác nhau. Không tự đổi strict flags/tsconfig để “pass”; diagnostic variants nếu cần phải mang identity riêng.
- Source-map chains của SFC/template/JS transforms cần input hashes, original/generated document identities, mapping precision và unmapped segments. Không đem virtual helper nodes đếm thành authored application components.
- Cross-source references giữa HTML/script/style và templates phải có context-qualified scope; generated symbol trùng tên với handwritten symbol không tự merge. Trường hợp multiple frameworks trong cùng workspace giữ từng profile/owner.
- Framework không nhận ra vẫn có language-level facts và detection outcome; runtime-conventional fact chỉ được phát khi profile/version và native/imported symbols đúng. Missing plugin/tool/SDK tạo actionable evidence request, giữ kết quả đã có.

**Kiểm chứng gắn với triển khai và điều kiện hoàn thành Bước 2**

- Mỗi baseline frontend profile có real source → native checker/compiler → architecture facts → evidence navigation. Đối chiếu component/route/state/API/side-boundary labels độc lập, không chỉ so symbol count.
- Negative controls: fake React hook/framework symbol; type-only import; same endpoint path ở hai apps; computed route/base URL; mixed browser/server package conditions; missing template file; SFC parse error; dropped virtual mapping; generated helper double counting; wrong source map; source-map-less minified bundle.
- Cross-surface controls: component → store/action → API wrapper; route → lazy module → component; server action/loader → backend contract; CSS/asset/config ref → exact source; two contexts có mutually exclusive feature flags. Không phát certain edge xuyên các cấu hình không đồng thời khả thi.
- Schema controls: unresolved/cyclic/external ref, version mismatch, duplicate names namespaces, SQL dialect/search-path ambiguity, migration conflict, template/include cycle, native render failure và preserved independent resources.
- Chọn pairwise interactions cho matrix lớn, thêm case riêng cho rủi ro cao; mỗi required category vẫn có positive/negative/unknown disposition. Không loại khó khỏi labels hoặc để framework emitter tự định nghĩa denominator.
- Exit: F01–F12 và schema/config families có actual scoped implementation, baseline profile journeys và provenance/denominator checks; frontend có thể truy ngược từng quan hệ về raw source/generated bytes. Không cần chứng minh mọi lifecycle/runtime trace, nhưng không chấp nhận chỉ dependency imports hoặc package-name detection.

## Bước 3 — Nối frontend, backend và các ngôn ngữ thành mô hình kiến trúc có thể truy vấn

**Kết quả cần có:** hệ thống trả lời xuyên biên giới ngôn ngữ về component, hợp đồng, dữ liệu, dependency và potential impact; phân biệt liên kết đã chứng minh với candidate. Đây là bước triển khai interop/domain/query/consumer integration, không phải một chiến dịch test hoặc đóng milestone.

Để nối được backend ngoài Java, bổ sung profile trích xuất **boundary declarations** từ resolved framework/library APIs và registration/config evidence: route/controller/handler/resolver, service/DI registration, message consumer/producer, scheduled/background worker, persistence/cache client và auth/middleware references. Các ecosystem Node, Python, .NET, PHP/Ruby, Go/Rust và JVM dùng cùng contract nhưng detector có version riêng; giữ scope thực của Spring hiện có. Chọn concrete framework tuples từ mixed fixtures và repository đã đăng ký, ghi chúng trong capability catalog trước scoring. Đây là phân tích role/dependency/contract, không mô phỏng transaction, authentication correctness, lifecycle hay mọi internals của framework. Framework chưa nhận ra còn obligation; folder `services/` hoặc tên `Controller` chỉ là detection signal, không phải verified role.

**Bridge catalog và mức chứng minh**

| ID | Bridge | Điều kiện để tạo quan hệ chắc chắn trong phạm vi đã nêu |
|---|---|---|
| B01 | Java ↔ Kotlin/Scala/Groovy | Exact module/context + source/binary/native symbol correspondence, descriptor/signature và generation metadata; giữ overload/bridge/synthetic cases |
| B02 | Frontend/client ↔ HTTP backend | Resolved call/wrapper + method/path/parameter normalization + owning endpoint/contract/version + evidenced base URL/rewrite/config binding. Nếu chỉ hợp schema thì chỉ là contract binding, chưa phải selected deployed service |
| B03 | GraphQL/gRPC/generated SDK | Operation/service/method identity, schema digest/namespace/version, generated lineage, client/server declaration bindings; generated cùng schema không tự là runtime call |
| B04 | Events/messages/WebSocket/SSE | Producer/consumer declarations, protocol/channel/topic namespace, message schema, routing/config evidence. Cùng tên topic hoặc string literal không đủ chọn deployment endpoint |
| B05 | Persistence/data | Query/ORM/migration/schema bindings và logical database/connection context; cùng table name ở hai database không merge; không suy transaction/runtime state |
| B06 | JNI/JNA/C ABI, .NET P/Invoke, Python/native, Rust FFI, Swift/ObjC | Calling convention, exported/imported symbol, signature/layout/target ABI, library and registration evidence. Mangled-name hoặc filename trùng không đủ xác nhận binding |
| B07 | WASM, worker/IPC, mobile/native bridge | Import/export/message/method/channel declarations và matching payload/registration/config evidence; external runtime endpoints giữ unresolved external references |
| B08 | Build/generated/resource boundary | Source/schema/config → generator → generated artifact → owning symbol/component; declared build/deployment relations tách khỏi semantic CALLS |

Graph catalog phân biệt `IMPORTS`, `REFERENCES`, `CALLS`, `COMPONENT_USES`, `ROUTES_TO`, `USES_STATE`, `READS/WRITES`, `CONSUMES/EXPOSES_CONTRACT`, `GENERATED_FROM`, `ABI_BINDING`, `PUBLISHES/SUBSCRIBES` và candidate relations bằng versioned meanings. Tên cuối có thể đổi; không collapse mọi bridge thành `CALLS`. Cạnh verified contract/ABI không được quảng cáo là đã quan sát runtime traffic.

**Identity, graph và projection tương thích**

- Thêm envelope liên context/versioned relation chứa endpoint snapshot/context/symbol identities, relation semantics, direction, derivation, trust, evidence, applicability/conditions và resolution status. Không nới kiểm tra same-context của M5 v1 hoặc chèn node giả vào context khác để lách invariant.
- Root catalog tham chiếu immutable child semantic partitions và bridge tables. Không deduplicate bằng FQN, URL path, topic hoặc table name đơn lẻ. Cross-repository links chỉ là external contract references khi chưa có captured target; không tự bước sang deployment-world federation hoặc Track B.
- Reconcile source/SCIP/binary/generated views bằng correspondence proof: preserve one logical symbol khi thật sự cùng declaration; lưu observations riêng, conflicts và source-specific locations. Same name khác module/package instance/ABI/context vẫn là entities khác.
- Conditions trên bridge phải tương thích với cả hai endpoints. Nếu chưa có proof nối hai configuration domains thì giữ candidate/UNKNOWN hoặc contract-only relation; không nhân chéo mọi variants rồi nói là executable path. Spring truth/witness dùng đúng existing scope; non-Spring conditions giữ semantics/version riêng.
- Publish facts/bridge/census/current-coverage bằng immutable CAS revisions và fenced markers. Cancellation/retry/late result/revoked proof không thay current pointer bằng output cũ. Keep known-good child results và explicit failed/pending rows khi một pack hỏng.
- Cache theo exact observed footprints/profile/version/context/selection; chưa chứng minh footprint nhỏ thì dùng conservative closure. Source/tool/config/schema/SDK changes invalidate dependent facts và bridges theo evidence DAG; không cần temporal architecture diff của M11.
- Implement paged bridge/entity/evidence reads và bounded joins cho Step-5 journeys; tránh thêm full-root in-memory dependency. Global out-of-core traversal/aggregation, old full-graph consumers và million-scale product acceptance vẫn là Step 6, không claim đã giải quyết chỉ vì importer streaming.

**Các truy vấn phải trả lời được bằng dữ liệu thật**

| Query/use case | Kết quả và điều kiện |
|---|---|
| Toàn repo có những app/module/language/framework nào? | Ownership, contexts/versions, completeness/gaps, typed roles; unknowns không bị filter mặc định |
| Trang/route này gồm và dùng những gì? | Route → layout/component → shared modules/state/services/assets/config; mỗi hop có source/evidence |
| Frontend này liên quan backend nào? | Client call → contract → endpoint/service candidate hoặc verified binding; scope và missing routing proof hiển thị |
| Schema/API/symbol này đổi thì đâu có thể bị ảnh hưởng? | Bounded incoming structural/contract impact, dependency path, candidate/unknown frontiers; không khẳng định runtime breakage chắc chắn |
| Có dependency cycle/boundary crossing nào? | Qualified relation kinds/context selection, exact proven operands; infeasible/unknown paths không thành certain violation |
| Dữ liệu/shared schema được đọc ghi ở đâu? | Evidenced query/ORM/schema/use relations; database namespace và ambiguous dynamic SQL còn rõ |
| Vì sao quan hệ tồn tại hoặc chưa biết? | Fact → method/provider/version → source/generated/binary/schema → input/context/lineage → validation/gap/attempt |
| Điều gì còn thiếu để phân tích tiếp? | Current obligations theo root/language/category/cause, attempted methods, next evidence need và resume handle; historical gaps tách riêng |

Thêm language/capability/applicability descriptors cho stable query services; API/CLI/export không tự suy semantics hoặc tính metrics bằng kết quả một page. Bổ sung bounded workbench structure/search/bridge/evidence view trong UI hiện có để journey quan sát được. Full UX polish/large graph interactions và scale budgets vẫn Step 6; không viết lại frontend sản phẩm trong Step 5.

Metrics giữ operands/population/status rõ: physical inventory khác logical context observations; production/test/generated/vendor tách biệt; missing evidence không tăng health. Java Martin/Spring/G5 formulas không tự áp cho mọi ngôn ngữ. Structural metric chỉ reuse khi meaning/operands tương đương và đã có controls; không tạo weighted whole-repo score mới trong lượt này. Unsupported assessment trả WITHHELD/NOT_APPLICABLE đúng nghĩa, vẫn hiển thị đầy đủ facts.

**Kiểm chứng gắn với triển khai và điều kiện hoàn thành Bước 3**

- Mixed repository bắt buộc: Java + Kotlin + TS frontend + Python; thêm schema-generated client/server, nested roots, shared library, main/test/custom sources, one broken sibling và một dynamic boundary. Query cho thấy toàn bộ contexts và relationships đã biết; chỉ vùng thực sự phụ thuộc mới bị degrade.
- Native bridge bắt buộc: một actual Java/native hoặc equivalent registered fixture, positive exact ABI/registration và negative wrong-library/signature/ABI controls. Mỗi B01–B08 có applicable positive/negative controls; N/A trên fixture này không xóa nghĩa vụ catalog khác.
- User journeys bắt buộc: route → component → API → backend; schema → generated clients → consumers; symbol → qualified potential impact; missing dependency → preserved partial → evidence added → gap closure; proof revoked → reopened requirement và withheld dependent edge.
- Integrity controls: same endpoint/topic/table name khác owner; wrong context/schema version; incompatible feature conditions; missing bridge endpoint; conflicting source/index/native output; candidate count bằng một vẫn uncertain; tampered source map/census; old reader/new schema compatibility rejection/mapping.
- Replay controls: cold/fresh-process reload, changed arrival order/attempt/time, cancel/restart giữa table và publication, cache invalidation; so typed semantic/current-gap meanings và digests phù hợp, audit bindings được phép khác. Rebuild nondeterminism báo riêng với raw output hashes.
- CLI/API/export/bounded UI trên cùng snapshot/context/capability selection phải có cùng counts/status/evidence. Paging/filter không đổi global denominator; healthy child không che missing/broken child. Test old Java-only journeys và historical identity/schema contracts.
- Exit: B01–B08 có implementation theo registered profiles; queries trên trả được evidence-backed answers; all required baseline language/frontend profiles đã qualify trên finite controls; actual native/mixed journeys pass; remaining genuine dynamic unknowns không bị che. Step 5 không complete nếu mandatory engine/profile chỉ có mocks, syntax-only fallback hoặc NOT RUN native evidence.

Qualification của pack/profile và completeness của một repository là hai kết quả riêng: pack có thể đạt registered positive/negative/unknown controls nhưng một repo dùng cơ chế động chưa giải được vẫn semantic PARTIAL. B01–B08 được triển khai theo explicit mechanism subcatalog; một JNI fixture không chứng nhận cả JNA/PInvoke/Python extension/Rust FFI. Mỗi cơ chế được công bố supported cần native evidence riêng; cơ chế phát hiện nhưng chưa qualify giữ obligation và không được dùng để nâng root completion.

**Cách thực thi liên tục trong ba bước:** đi từng vertical slice source → context → provider → census → canonical fact → consumer. Bước 1 đã được human chấp nhận ở Core MVP; không mở lại các exit đã rút hoặc tự chuyển chúng thành task mới. Bước 2–3 giữ phạm vi và điều kiện riêng; không tự bắt đầu trong lượt đóng này. Ưu tiên evidence có khả năng giải quyết capability gaps thực; retry khi input/resource/cause đã thay đổi, không lặp vô hạn một lỗi. Không đặt deadline giả hoặc ép ba gói lớn thành ba patch.

**Điểm có thể cần operator input khi triển khai:** pinned SDK/toolchain/worker cho platform-specific targets, scoped access tới dependencies private, license hoặc allocation chưa có. Lập plan này không bị chặn; triển khai phần độc lập tiếp tục. Không tự upload source, mua cloud capacity, mở credential scope hay chứng nhận executor tổng quát. Không hỏi lại từng module cho hành động đã nằm trong policy/authorization.

**Traceability với Step 5 gốc:** common provider/census/native binding/identity/source-position obligations thuộc Bước 1; đầy đủ language table được giữ ở Bước 1, schema/config và frontend profiles ở Bước 2; interop/metric applicability/mixed/native journeys thuộc Bước 3. Positive/negative/version/determinism/omission controls nằm trong cả ba. Step 6 giữ full graph-scale/product/performance acceptance và M9/M10 giữ các gates gốc.

**Kiểm tra tài liệu đã chạy:** `git diff --check` PASS; kiểm 215 local Markdown link targets trên sáu tài liệu thay đổi, không broken target; kiểm đúng ba bước ở plan này và sáu bước ở plan mẹ; đủ 18 language rows và 32 category IDs (A01–A12, F01–F12, B01–B08); hai tài liệu mới không trailing whitespace. Đây là structural checks cùng self-review nội dung, không phải behavioral/semantic acceptance.

**Bàn giao lượt lập kế hoạch:** trước là Step 4 CLOSED và Step 5 chưa được phân rã chi tiết; sau là plan ba bước cùng research note, đồng bộ pointers/status ở current-state, M8 index, plan mẹ và readiness contract, không có implementation mới. Technical choices là PROVISIONAL, trọng tâm frontend và ba bước do human yêu cầu. Không có compiler accuracy/performance/new test pass claim, không independent review, commit/push hoặc native execution. Không blocker cho plan. Exact next implementation slice khi bắt đầu Step 5: **Bước 1, regression full inventory/embedded-language classification + independent census và TS/JS native vertical slice trên Java-preserving envelope**, sau đó rollout đủ mandatory packs; không bắt đầu bằng UI mock hoặc chỉ scaffold provider registry.
