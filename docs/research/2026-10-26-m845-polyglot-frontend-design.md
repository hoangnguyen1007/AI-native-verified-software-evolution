# M8.4 Step 5 — Lựa chọn semantic engines và phân tích kiến trúc frontend

Ngày đối chiếu: **2026-10-26**. Phương pháp: đọc hợp đồng, trạng thái và source/test liên quan tại Git `de6969c`; đối chiếu tài liệu/source chính thức. Không cài engine, chạy target, benchmark hay đo accuracy. **CONFIRMED** dùng cho quan sát trực tiếp hoặc chỉ đạo human; phương án tích hợp là **PROVISIONAL**, chưa phải chứng nhận hỗ trợ. [Kế hoạch triển khai đúng ba bước](../tasks/m8/04-step5-polyglot-frontend-plan.md) sở hữu công việc và exit criteria.

## Câu hỏi cần quyết định

Làm thế nào hoàn thành catalog ngôn ngữ của Step 5, ưu tiên hiểu frontend và các ranh giới kiến trúc, mà không tự viết lại compiler hay sa vào mô phỏng mọi hành vi runtime? **CONFIRMED — yêu cầu human trong lượt này:** kế hoạch có đúng ba bước thực chất; ngôn ngữ ngoài nền tảng Java/Spring không cần đào sâu mọi chi tiết, nhưng phải phân tích được và phát hiện rộng; bước thứ ba không chỉ là test/đóng task.

**ASSUMPTION về cách hiểu từ “front”:** frontend của ứng dụng mục tiêu, gồm web/client, component/template, routing, state và giao tiếp backend. Đây không chỉ là tên lớp `SemanticFrontend` của analyzer. Java/Spring vẫn giữ độ sâu và hợp đồng đã nghiệm thu; không quay lại inventory-only cho ngôn ngữ khác.

## Bằng chứng repository ảnh hưởng thiết kế

| Nguồn đã kiểm tra | Quan sát trực tiếp | Hệ quả |
|---|---|---|
| [Current state](../current-state.md) | Step 4 đã CLOSED; trust PUBLIC_TEST_POLICY_ONLY/PRODUCER_ASSERTION và các genuine gaps vẫn hiện hữu | Dùng lại nền tảng và bằng chứng đúng phạm vi; không coi 605 tests lịch sử là qualification cho engine mới |
| [ReadinessPlanner](../../analyzer/src/main/java/com/evolution/analysis/readiness/ReadinessPlanner.java) | `language()` dùng bảng extension; chưa có `.vue`, `.svelte`, `.astro`, `.vb`, `.m/.mm`, nhiều biến thể script/module; `.h` được gán C | Cần registry phân loại theo nhiều bằng chứng, xử lý ambiguity và embedded languages; thêm extension riêng lẻ chưa đủ |
| [PassiveReadinessDiscovery](../../analyzer/src/main/java/com/evolution/analysis/buildmodel/PassiveReadinessDiscovery.java) và [test](../../analyzer/src/test/java/com/evolution/analysis/readiness/PassiveReadinessDiscoveryTest.java) | Có discovery nhiều build family và native-model requirements; không có semantic engines tương ứng | Không đồng nhất nhận ra `package.json`/`Cargo.toml` với hiểu project/dependency/symbol |
| [NativeBuildRecipes](../../analyzer-execution/src/main/java/com/evolution/analysis/execution/nativebuild/NativeBuildRecipes.java) | Lowering hiện có MAVEN/GRADLE; family khác trả `NATIVE_ADAPTER_REQUIRED` | Recipe và worker registration cho các ecosystem là phần triển khai của Step 5, không giả đã dùng chung được |
| [FrontendRequest](../../analyzer/src/main/java/com/evolution/analysis/frontend/FrontendRequest.java), [SemanticFrontend](../../analyzer/src/main/java/com/evolution/analysis/frontend/SemanticFrontend.java), [EntityKind](../../analyzer/src/main/java/com/evolution/analysis/contract/semantic/EntityKind.java) | Request, platform/options và catalog `m2-java-4` mang nghĩa Java | Thêm envelope/provider-neutral; bridge Java giữ nguyên preimage, không nhồi mọi ngôn ngữ vào Java request/kinds |
| [PreparedJavaRepositoryRunner](../../backend/src/main/java/com/evolution/backend/analysis/PreparedJavaRepositoryRunner.java) | Root runner giữ child contexts/snapshots riêng, nhưng composition và authority còn Java-specific | Tạo polyglot orchestration bên ngoài; tái sử dụng pattern và ports, không thay class name rồi gọi là generic |
| [KnowledgeGraph](../../analyzer/src/main/java/com/evolution/analysis/graph/KnowledgeGraph.java) và [contract tests](../../analyzer/src/test/java/com/evolution/analysis/graph/CanonicalGraphContractTest.java) | Target/region phải thuộc cùng context; graph materializes lists/maps | Cần envelope liên context riêng và projection tương thích; Step 6 vẫn sở hữu full out-of-core graph/scale integration |

## So sánh phương án

| Phương án | Ưu điểm | Điểm không phù hợp / quyết định |
|---|---|---|
| Regex/extension + Tree-sitter/ctags | Detect nhanh, rộng, phục hồi source hỏng | Dùng cho discovery/census/structural evidence; không đủ binding/type/call correctness |
| Chỉ SCIP hoặc một LSP client | Tái sử dụng navigation tools | Coverage và category tùy provider; reference không mặc định là call; không có census độc lập của platform |
| Tự viết semantic/type system đầy đủ cho mỗi ngôn ngữ | Kiểm soát implementation | Chi phí lớn, dễ lệch compiler, ngược ưu tiên kiến trúc thực dụng của yêu cầu này |
| Dùng một code graph server/cloud làm domain | Có hệ thống index sẵn | Coupling storage/identity/trust, thêm vận hành và data disclosure; không cần cho kiến trúc modular monolith |
| **Native semantic adapters + census độc lập + framework/contract extractors + neutral evidence graph** | Tận dụng compiler, giữ khả năng thay provider, dành độ sâu cho quan hệ kiến trúc | **Chọn làm thiết kế PROVISIONAL**; cần qualification từng tuple và xử lý thiếu category rõ ràng |

## Những điểm đã xác minh từ nguồn chính thức

Các dòng dưới là căn cứ chọn adapter. Không dòng nào chứng minh engine đã chạy trong platform. Version dùng khi triển khai phải khóa theo artifact/commit/digest thực; URL `latest` không phải pin.

| Nguồn | Nội dung ảnh hưởng lựa chọn |
|---|---|
| [SCIP schema](https://github.com/scip-code/scip/blob/main/scip.proto), [upstream indexer list](https://github.com/scip-code/scip) | Có symbols/occurrences/relations/ranges và nhiều indexer. Dùng như interchange; platform vẫn sở hữu categories, census, identity, trust và mapping. Không suy toàn bộ call graph từ reference rows |
| [TypeScript compiler API](https://github.com/microsoft/TypeScript/wiki/Using-the-Compiler-API) | Có Program/TypeChecker/symbol/type APIs. Tài liệu hiện cảnh báo nội dung áp dụng TS 6.0 trở về trước và TS 7.1 có API khác. Cần adapter theo API family, không mặc định nâng version là tương thích |
| [Vite features](https://vite.dev/guide/features.html) | Vite transpile TS nhưng không tự type-check. Một build Vite xanh không đóng semantic-validation obligation; cần checker phù hợp và diagnostics |
| [Vue language tools](https://github.com/vuejs/language-tools) | Có `vue-tsc` và `vue-component-meta` cho type-check và props/events/slots. Cần bridge template/virtual document về source thật |
| [Angular template checking](https://angular.dev/tools/cli/template-typecheck) | Mức kiểm template phụ thuộc flags. Ghi config thật, không bật strict ngầm rồi coi đó là build gốc; phải phản ánh vùng `any`/unchecked |
| [Svelte language tools](https://github.com/sveltejs/language-tools), [Astro TypeScript](https://docs.astro.build/en/guides/typescript/) | Cần tool riêng cho embedded files. Astro ghi rõ `tsc` bỏ qua `.astro`; dùng framework-aware extraction/validation, không lấy TS-only success làm full frontend coverage |
| [Next server/client components](https://nextjs.org/docs/app/getting-started/server-and-client-components) | Có boundary giữa server/client module graphs. Catalog kiến trúc phải giữ execution side và provenance, không suy mọi TSX đều browser code |
| [ECMA-426 source maps](https://tc39.es/ecma426/) | Source-map là representation cần kiểm version/positions/mappings. Generated offset không thay được full original occurrence span; mapping thiếu/không chính xác phải ghi rõ |
| [scip-java](https://github.com/scip-code/scip-java), [Kotlin Analysis API](https://kotlin.github.io/analysis-api/index_md.html), [SemanticDB](https://scalameta.org/docs/semanticdb/specification.html) | README scip-java ghi Java/Kotlin; Kotlin API có targets/types/scopes/diagnostics, stable parts khác experimental parts. Scala chọn compiler SemanticDB theo version thay vì suy support từ bảng indexer tổng quát |
| [scip-python](https://github.com/sourcegraph/scip-python) | Fork Pyright cho SCIP; README công bố Python 3.10+. Legacy versions cần provider phù hợp, không tự coi Python mọi version đều covered. Environment inspection phải dùng captured environment; không import ứng dụng |
| [go/packages](https://pkg.go.dev/golang.org/x/tools/go/packages) | Có source/type/import/module information; tài liệu cảnh báo không trộn type information từ các lần Load khác nhau. Giữ mỗi context và input closure riêng |
| [rust-analyzer](https://rust-analyzer.github.io/book/features.html), [Clang LibTooling](https://clang.llvm.org/docs/LibTooling.html) | Là candidates cho Rust/native semantics. Cần actual configuration, macro/generated inputs và compilation context; category/SCIP export qualification theo pin vẫn chưa thực hiện |
| [Roslyn model](https://learn.microsoft.com/en-us/dotnet/csharp/roslyn-sdk/compiler-api-model) | API cung cấp analysis của cả C# và VB. Hai ngôn ngữ có qualification riêng; indexer `.NET` không tự chứng minh đủ VB |
| [Dart analyzer](https://pub.dev/packages/analyzer), [IndexStoreDB](https://github.com/swiftlang/indexstore-db), [Groovy semantics](https://docs.groovy-lang.org/latest/html/documentation/core-semantics.html) | Có native analysis/indexing paths. SDK/OS và static/dynamic distinctions vẫn là inputs; không giả mọi Swift/ObjC SDK chạy được trên Linux |
| [OpenAPI](https://spec.openapis.org/oas/v3.2.1.html), [Protobuf](https://protobuf.dev/programming-guides/proto3/), [GraphQL pinned specification](https://spec.graphql.org/October2021/) | Contract identities, schema references và generation lineage là nền để nối client/server. Version/dialect được lấy từ input; không ép repo sang phiên bản tài liệu đang đọc. GraphQL link là edition cụ thể, không tuyên bố latest |

PHP/Ruby và các indexer tùy chọn còn cần kiểm exact implementation/license/category support. Upstream list chỉ xác nhận có candidate; native/static analyzer fallback phải được chọn bằng fixture thực trước khi chốt pack. Flow, legacy dialects và framework ngoài baseline đi qua registry với obligations, không giả TS checker bao phủ chúng.

## Quyết định thiết kế và giới hạn

- **PROVISIONAL:** dùng một architectural semantic profile chung, specialization theo ngôn ngữ, sau đó framework profiles và evidence-backed bridges. Giữ raw/native types ở adapter; neutral type detail vẫn bảo toàn union/nullability/generic/ownership information cần cho quan hệ kiến trúc.
- **PROVISIONAL:** TypeScript/JavaScript là vertical slice đầu tiên để chứng minh contract; sau đó đưa đủ mọi pack bắt buộc của plan gốc vào cùng lane. Thứ tự triển khai không là quyền hoãn hoặc xóa pack.
- **PROVISIONAL:** ưu tiên frontend web theo requested focus; các UI/native frameworks khác có boundary/detection và semantic facts theo pack, chỉ claim framework inference cho profile đã có controls.
- **CONFIRMED boundary:** Step 3 Core MVP không là launcher đa ngôn ngữ tổng quát. Thêm registered recipe/export/reader đúng ecosystem; không mở arbitrary command endpoint, không ký lại producer assertion thành proof. Không phục hồi 98-case security campaign đã rút.
- **HYPOTHESIS cần kiểm:** native reuse + architecture-focused supplements giảm công viết engine nhưng vẫn tăng correct coverage; giảm gap phải đo cùng denominator và input/context, không suy từ lượng symbol tăng.
- Thay provider khi không trích đủ required category, API/pin không tương thích, source mapping sai, license không phù hợp, native context không thu được, hoặc footprint vượt resource class đã đăng ký. Giữ evidence cũ và qualification lại mapping; không thay Java primary tùy ý.

## Handoff nghiên cứu

Đã xác minh source seams và căn cứ chọn hướng; chưa đo performance, accuracy, version matrix hay worker availability cho từng ecosystem. Không blocker cho việc lập kế hoạch. Actual artifact pins, OS/SDK/license/access, production validator methods và independent labels là đầu việc rõ trong ba bước triển khai, không phải giả định đã đáp ứng. Self-review; không independent agent review. Không production edit, target execution, source upload, cloud spending, commit hoặc push.
