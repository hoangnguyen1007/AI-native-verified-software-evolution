# Prompt bàn giao — M8.4 Bước 4, Phase 2 (GPT-6.1 sol)

**PROMPT 2 / STEP 4 COMPLETE / CLOSED — REVIEW FOLLOW-UP, 2026-10-26:** unchanged **70 original IDs / 70 full registered controls PASS / 0 FAIL / 0 NOT RUN**. Readiness proofs reach both product runners; nested multi-release selection authenticates raw leaf before selected release view; R03 changes captured attempt/time bindings and compares actual Spring/root/current-gap meaning and typed projections with distinct full graph/audit bindings. The former same-capture evidence is retained as a subset.

Fresh final checks: **605 JUnit cases / 70 suites / zero failures/errors/skips**, including **22 native / 5 suites**; 15 Python controls, schema, packaged source-only and captured CLI/query journeys pass. No registered blocker remains. [Review execution](04-step4-phase2-execution.md#review-follow-up--2026-10-26) owns repairs, exact commands/counts, retained evidence, new JAR/hash, DEFAULT_SCOPE_TRUTH_NONE and public-test/producer trust limits. Self-review only; no new independent review. **Stop here: no automatic Step 5, commit or push.** The historical implementation prompt below is retained for context, not an instruction to repeat completed work.

The original implementation prompt follows for scope/history; its full checklist is unchanged and does not reinstate earlier missing counts as current truth.

```text
Bạn là GPT-6.1 sol, làm việc tại D:\UIT\AI-native-verified-software-evolution.
Hãy hoàn thành M8.4 Bước 4 — Phase 2: TDD Implementation, dựa trên Phase 1 đã
thiết kế. Tiếp tục tự chủ qua các lát cắt nội bộ đến khi đạt exit criteria;
không dừng sau một lớp DTO/importer hoặc chỉ đưa kế hoạch. Không cần hỏi lại
những quyết định kỹ thuật đã được giao. Không commit/push, không tự sang Bước 5.

Đọc AGENTS.md và bootstrap đúng mức. Đọc đầy đủ Human Mandate, current state
và các contract trực tiếp; ban đầu kiểm tra Git status/diff/log, giữ nguyên
mọi thay đổi của người dùng, bao gồm tài liệu Phase 1 chưa commit.
Dùng se-project-engineering, implementer + semantic-analyst, workflow implement,
test-driven-development và verification-before-completion; systematic-debugging
khi có lỗi. Không tự spawn agent nếu chưa có authorization phù hợp.

Nguồn sự thật:
1. docs/architecture/m8-java-evidence-closure.md
2. docs/architecture/m8-java-evidence-schemas.md
3. docs/architecture/schemas/m8-java-evidence-v1.schema.json
4. docs/tasks/m8/04-step4-verification-matrix.md
5. docs/tasks/m8/04-comprehensive-repository-readiness.md — Bước 4 nguyên vẹn
6. ADR-007 + m8-repository-readiness.md + m8-build-readiness-planner.md
7. m8-isolated-execution.md / schemas và 04-step3-phase2-execution.md chỉ ở
   phần native-output/trust/acceptance liên quan.
8. docs/tasks/m8/04-step4-phase2-execution.md — checkpoint implementation,
   verification và các phần code/native acceptance chưa hoàn thành.

Ưu tiên phần Implementation audit và checkpoint mới nhất trong matrix/execution;
các đoạn 11/59, 17/53, provider cũ và NEXT TASK cũ trong lịch sử không phải
trạng thái hiện hành. Không dừng ở việc chạy lại P02: phần này đã được kiểm.
Hoàn thiện phần implementation còn thiếu, rồi kiểm acceptance còn thiếu;
không chuyển mọi full NOT RUN thành PASS chỉ vì có test cùng tên.

Human đã đóng Bước 3 ở Core MVP: registered Hyper-V offline build/export,
CAS/replay, PRODUCER_ASSERTION, qualified=false. Không mở lại chiến dịch an ninh
98-case đã rút, không giả full qualification và không bỏ yêu cầu an toàn/trust.
CoreMvpBuildPipeline là recipe fixture cố định, không phải pipeline compiler
manifest tổng quát. Target builds/generators/processors/compiler hooks/native
model/index requests chỉ chạy trong worker cô lập đã đăng ký đúng phạm vi;
không chạy trên host dù dùng temp directory hoặc skipTests. Thiếu phương thức
validation thì giữ gap; không ký assertion để làm nó thành VerifiedClaim.

Phạm vi triển khai đầy đủ:
- Domain records/codecs/validators cho lineage, overlay, exact resolution,
  source membership/correspondence và append-only closure ledger/current view.
- Stream CAS handles/tables; giữ full output census, expected/observed roots,
  resources/classes/archives/non-Java dispositions, partial frontier/recovery.
- Bridges đọc đúng Core MVP và generic output publication, không đổi schema cũ;
  exact base/context/source-set/variant/tool/config/material binding.
- Original snapshot bất biến; generated overlay riêng; derived M1 input view
  có path map/collision rules và analysis configuration bindings. Không đổi M1
  preimages, không gộp các named source sets thành một MAIN/TEST.
- Exact ordered classpath/module-path/platform/source membership; class-directory,
  JAR/WAR/nested and reactor views; multi-release/duplicates explicit. Không append
  cache hay host JDK; không flatten JPMS. Byte readers không class-load target.
- JavaParser wiring thật: nguồn generated, source-first Lombok binary augmentation,
  owner/member correspondence, exact origin/span, selective compiler-backed
  attribution theo gap. Không emulator handwritten generator/stub thay bằng chứng.
- Spring enrichment giữ version/configuration/phase/order/UNKNOWN và witness.
- Gap SATISFIED phải có predicate/proof đúng phạm vi; historical audit riêng,
  active requirements quyết định residual; conflict/revocation mở lại dependent.
- Wire vào UniversalArchitecturePipeline và graph/query/direct consumers,
  không để feature chỉ tồn tại trong unit tests hoặc API không có caller.
- Exact cache invalidation, atomic publication/restart, provider/version assertions,
  current-state và execution handoff trung thực.

Điểm vào code đã kiểm tra ở Phase 1 (đọc lại phiên bản hiện tại trước sửa):
analyzer/src/main/java/com/evolution/analysis/ingestion/GeneratedSourceBundleImporter.java
analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalArchitecturePipeline.java
analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalSourceIngestion.java
analyzer/src/main/java/com/evolution/analysis/frontend/FrontendRequest.java
analyzer/src/main/java/com/evolution/analysis/frontend/BinaryInput.java
analyzer/src/main/java/com/evolution/analysis/readiness/CapturedBuildEvidence.java
analyzer/src/main/java/com/evolution/analysis/execution/EvidenceVerifier.java
analyzer/src/main/java/com/evolution/analysis/execution/RequirementReconciler.java
analyzer/src/main/java/com/evolution/analysis/evidence/EvidenceAcquisitionLedger.java
analyzer/src/main/java/com/evolution/analysis/evidence/GapResolutionRecord.java
analyzer-filesystem/src/main/java/com/evolution/analysis/filesystem/FilesystemResolutionBundleImporter.java
analyzer-execution/src/main/java/com/evolution/analysis/execution/mvp/CoreMvpBuildPipeline.java
analyzer-execution/src/main/java/com/evolution/analysis/execution/controller/DurableOutputPublication.java
analyzer-execution/src/main/java/com/evolution/analysis/execution/output/OutputCensus.java
analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/ResolutionEnvironment.java
analyzer-javaparser/src/main/java/com/evolution/analysis/javaparser/LombokExtraction.java

Làm TDD theo dependency order: strict contracts/identities → publication/overlay
import → ordered resolution & correspondence → frontend/Spring integration →
causal ledger/active coverage → durable product journey. Đây là gợi ý triển khai,
không được bỏ integration đến sau khi tuyên bố hoàn tất.
Mỗi slice phải có failing regression/specification rồi implementation và self-review.
Schema shape tests không thay domain/authority/semantic tests.

Kiểm chứng:
- Chạy checker schema Phase 1; port các invariant vào production tests.
- Inner loop chọn một method/class, PowerShell quote mọi "-D..."; ghi thật duration,
  không nói đạt <5 giây nếu không đo/không đạt.
- Trước handoff chạy bounded unit/contract + direct consumer tests trên từng module
  bị ảnh hưởng. Không chạy full reactor chỉ vì tiện; không generate reproducibility/.
- Các suite hiện hữu cần cân nhắc theo impact:
  analyzer: IdentityAndSourceContractTest, FrontendContractTest,
  CapabilityGapContractTest, GeneratedEvidenceImportTest, EvidenceVerifierTest,
  ReadinessPlanTest, CapturedBuildEvidenceTest, CanonicalGraphContractTest.
  analyzer-filesystem: EvidenceBundleImportTest, FileGraphStoreTest.
  analyzer-execution: CoreMvpBuildPipelineTest, OutputCensusTest,
  OutputImporterTest, DurableOutputPublicationTest.
  analyzer-javaparser: ResolutionInputsTest, ReactorSourceResolutionTest,
  GeneratedConstructorIntegrationTest, GeneratedSymbolResolutionTest,
  UniversalIngestionPipelineTest, UniversalArchitecturePipelineIntegrationTest,
  CanonicalGraphPipelineIntegrationTest, ArchitectureQueryPipelineIntegrationTest;
  thêm affected Spring/version/metric tests.
  backend/cli: affected jobs/readiness/report/EvoApp consumers nếu thay composition.
- Mẫu lệnh platform test (không phải target build):
  .\mvnw.cmd test -pl analyzer "-Dtest=<ActualClass>#<ActualMethod>" "-Denforcer.skipRules=reactorModuleConvergence" -q
  Chỉ skip partial-reactor membership rule khi thực sự không áp dụng; không skip
  failing test/quality rules. Refresh changed upstream trusted artifacts trước
  downstream checks; kiểm JDK 21 và tránh IDE-generated error stubs theo repo.
- Đăng ký và chạy fixtures native thực trong sandbox: protoc/gRPC 2–3 modules,
  Lombok builder/constructor caller, MapStruct, custom generated root/resources,
  broken independent sibling; input/tool/image hashes thật, independent expected
  caller/target/owner/origin/span/lineage. Giữ compiler diagnostics và failure cases.
  N01–N06 không được PASS từ mocks, parser resolve success hay build exit 0.
- Test stale/version/config/order/duplicate/source-index mismatch/partial processor/
  nondeterminism, restart/cancellation/storage và conflict reopening theo matrix.

Hoàn tất khi matrix có kết quả kiểm được, positive journeys đóng đúng acquisition
obligations, unresolved/runtime uncertainty còn nguyên và source→build→generated/
binary→Java/Spring graph journey dùng production wiring. Không tự thu hẹp Step 4
về Core MVP hoặc chỉ schema. Nếu thiếu worker/tool/material/quyền để thực hiện một
native acceptance, hoàn thành mọi phần độc lập, ghi đúng blocker và NOT RUN IDs;
không tuyên bố toàn Bước 4 complete. Hỏi human chỉ khi có quyết định consequential
thực sự chưa được duyệt hoặc không thể làm tiếp phần phụ thuộc.

Cuối cùng inspect diff, cập nhật docs/current-state.md và task record theo kết quả
thật; ghi files/commands/counts/failures/skips/native evidence/limits, không coi
self-review là independent review. Giữ các bước 5–6 và M8/M10/Track B chưa promote.
```
