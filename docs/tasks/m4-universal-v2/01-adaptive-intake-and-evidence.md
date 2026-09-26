# V2.1 — Adaptive intake and evidence closure

Status: **PLANNED / PROVISIONAL implementation design**. Task triển khai đầu tiên của [kế hoạch ba task](README.md). Mục tiêu: repo đến được semantic providers với nhiều bằng chứng đúng nhất có thể, giữ phần độc lập khi gặp lỗi và tự tìm cách đóng missing-evidence obligations. Các mục dưới đây là checklist của cùng task.

## Đầu vào, phạm vi và đầu ra

Đọc [v2 architecture](../../architecture/m4-universal-v2.md), [coverage](../../architecture/m4-universal-v2-coverage.md), [protocol](../../research/m4-universal-v2-evaluation.md), [M3 evidence](../../architecture/evidence-acquisition.md), [v1 ingestion](../../architecture/m4u1-passive-ingestion.md) và current-state. Preserve M3.1–3.8, Universal v1, ADR-001/003 và immutable evidence.

| Đầu vào | Đầu ra có thể kiểm chứng |
|---|---|
| Local selection/snapshot/archive/bundle + policies/limits | Observed inventory + frontier + root/source-set ownership |
| Maven/Gradle/plain/legacy declarative metadata | Qualified build model, independent exact contexts, partial structural plan |
| Supplied/captured dependency/platform/generated outputs | Verified artifacts/resources/lineage + typed unanswered requirements |
| Gaps + permitted provider registry | Executed acquisition plan, attempts, narrowed/satisfied resolutions, conflicts, outstanding obligations |
| Injected failure/cancel/restart | Durable partial run, resumed result và captured-input identity |

Không implement Spring semantic rules, product CLI/API/UI hoặc target builds ở task này. Không tạo exact frontend request từ classpath đoán. V2.2 tiêu thụ contracts và evidence outputs; V2.3 tích hợp report/campaign.

## Ownership trong code

- Neutral contracts/coordinator: packages `acquisition`, `buildmodel`, `input`, `ingestion`, `evidence`, `dependency`, `classpath` dưới `analyzer/src/main/java/com/evolution/analysis/`.
- Filesystem/platform/archive I/O ở `analyzer-filesystem`; Maven acquisition/model ở `analyzer-maven`.
- `UniversalBuildIngestion` là điểm vào build projections hiện có; Gradle-specific dependency ở adapter nếu thực sự cần. Domain không phụ thuộc filesystem/Maven/parser AST.
- Contract checks với `analyzer-javaparser`; không thay M1 preimages hay nới strict exact frontend validation.

## Checklist thực thi

### A. Contracts và baseline

- [ ] Đăng ký coverage subcases, inventory/semantic/architecture-question denominators riêng; pin baseline v1 commit và policies.
- [ ] Initial before/after dùng compact fixtures; không corpus benchmark trong coding loop.
- [ ] Reuse ledger/attempt/conflict/resolution types; chỉ additive envelope/version khi có missing semantics thực sự.
- [ ] Golden input/run/revision/bundle preimages: không circular dependency; result-affecting policy/budgets có identity.
- [ ] Freeze cohort inclusion/labels và scoped 98–99% target; tham số chưa đủ evidence là provisional, bounded calibration trước acceptance scoring.

**Control:** mất một obligation làm reconciliation fail; đổi nhãn unknown mà không satisfaction evidence không tăng correct coverage.

### B. Resilient inventory và ownership

- [ ] Inventory observed paths gồm non-Java/generated/test/vendor; unreadable subtree/quota frontier được lưu, không bịa số file chưa thấy.
- [ ] Root discovery dùng declarations/conventions có origin; plain Java không cần fabricated Maven GAV. Ambiguous ownership giữ các claims.
- [ ] No external symlink/junction traversal; detect path escapes/case collisions/mutation và duplicate archive entries; bound expansion/depth/bytes.
- [ ] Charset/BOM/line ending/raw digest bất biến; malformed file còn denominator, source-independent evidence vẫn giữ.
- [ ] Partition source sets/build contexts và roles; MAIN/TEST/custom sets không trộn dependency inputs.
- [ ] Thay global failure scope bằng dependency footprint có proof; không đơn giản xóa `buildClosureUnknown`. Unknown broad effect giữ broad qualification.

**Control:** repo ba islands (plain Java tốt, module lỗi, non-Java) trả đủ outcomes. Module tốt có structure; consumers phụ thuộc module lỗi vẫn qualified; independent roots không bị chặn oan.

### C. Build, dependency và platform closure

- [ ] Maven inheritance/aggregation/profiles/BOM/scopes/exclusions/classifiers/mediation giữ invariants; mở rộng remaining rows theo matrix.
- [ ] Gradle settings/includes/catalogs/bundles/platforms/constraints/custom source sets/literal inherited declarations có safe grammar và unsupported-expression footprints.
- [ ] Lock/catalog không là exact classpath: selected configuration, variant, artifact bytes và order dùng validated fragment hoặc imported resolved model.
- [ ] Ant/Ivy/IDE/lib: literal roots/classpath extraction. Bazel/Buck/Pants/sbt/custom scripts: build-island inventory + usable generic source-plan import cho evaluated remainder; không execute script.
- [ ] Version ranges/dynamic selectors/SNAPSHOT/relocation bind captured metadata + selection policy; resolved artifact immutable; không chọn newest host cache thành truth.
- [ ] JDK locations do policy chọn; passive target view validation. Syntax/bytecode target/platform API riêng; no ambient host classpath.
- [ ] Reuse bounded dependency acquisition, atomic cache promotion, explicit endpoints, no ambient credentials; absent/denied/transient/corrupt outcomes khác nhau.
- [ ] Duplicate FQN/GAV, shading, MR-JAR, JPMS/module path, manifest Class-Path và OSGi metadata có context/policy proof; no hidden superset.
- [ ] Complete handwritten siblings tiếp tục cung cấp source evidence; unavailable output/classes có gap riêng, không tự suy ra source thiếu.

**Controls:** same catalog/different lock -> different context; MAIN không dùng TEST-only jar; pinned range replay offline; Maven profile đổi dependencies khác Spring profile trong fixed context; dynamic build chỉ ảnh hưởng scoped roots khi đã chứng minh.

### D. Generated/binary/library resource providers

- [ ] Import source sets, class dirs, JAR/WAR/EAR/fat/nested JAR qua bounded readers; no target class-loading.
- [ ] Original artifact digest + selected entry/release + entry digest + origin; packaging runtime libs không tự thành compile classpath.
- [ ] Annotation defaults/meta-edges/member signatures/Spring resource bytes cho V2.2; no fake source spans từ binary.
- [ ] Generated output cần recipe/tool/input lineage. Hash integrity khác producer trust/freshness; stale outputs conflict thay vì thắng source.
- [ ] Lombok/MapStruct/protobuf/QueryDSL/custom outputs có typed demands; valid supplied bundle phải resolve consumer, không chỉ detect tên generator.
- [ ] JarTypeSolver vẫn baseline; selective bytecode decoder chỉ bổ sung metadata đã có reproducible gap, không tự thay parser.
- [ ] Config/AOT/runtime bundle import giữ exact build/config/run và observed completeness; observation không chứng minh universal absence.

**Controls:** valid generated constructor closes đúng requirement; same class name/wrong source hash không closes; corrupt resource giữ source evidence; binary thiếu debug tables có honest absent source coordinates.

### E. Active evidence coordinator và recovery

- [ ] Provider registry: typed question/prerequisites/effects/authorization/budgets/satisfaction predicate; không arbitrary command strings.
- [ ] Deterministic queue: prerequisite fan-out, least invasive permission/cost class, stable identity; candidate provider không tự cấp quyền.
- [ ] Attempt dedup theo requirement/provider/input revision/policy; retry hữu hạn cho transient reasons; no progress kết thúc rõ.
- [ ] Acquire -> validate context/digest/lineage -> additive ledger -> narrowed/satisfied -> dependency invalidation -> exact reassembly; original observations immutable.
- [ ] Provider conflicts ghi cả evidence dimensions, withhold affected conclusions; no last-writer-wins/majority voting.
- [ ] Quota/cancel/denial/unavailable có outstanding obligations, termination reason và next evidence requirements.
- [ ] Atomic stage checkpoint; trusted analyzer workers bounded IPC/memory/work. Supervisor ngoài worker ghi crash/timeout và giữ independent outputs.
- [ ] Captured closure replay deterministic; wall times/host paths tách semantic identity. Live network/host-failure differences được ghi, không hứa source-only determinism.

**Controls:** missing jar -> permitted exact artifact -> rerun affected units -> satisfied resolution, old gap còn; denied network không phát request; worker throws/dies không xóa committed outputs; revision mismatch invalidates dependent facts.

## Kiểm chứng tối thiểu và cách chạy

| Nhóm | Positive | Negative/interaction |
|---|---|---|
| Inventory | Plain Java + resources + mixed languages | Link/escape/collision/mutation/unreadable/frontier |
| Build | Maven reactor; Gradle fragment/captured model; custom bundle | Dynamic expression, ambiguous root, independent module recovery |
| Resolution | Locked artifacts + target JDK + source siblings | Wrong hash/order/version, duplicate class, missing private input |
| Artifact | Generated lineage + library annotations/resources | Stale output, archive bomb/corruption, fabricated span rejected |
| Coordinator | Narrow then satisfy, shared prerequisites | Loop, incompatible context, denial/conflict/budget |
| Recovery | Checkpoint/resume equals uninterrupted captured run | Worker kill, corrupt checkpoint, host interruption; partial != complete |

Đề xuất classes mới: `RepositoryUnderstandingContractTest`, `AdaptiveEvidenceCoordinatorTest`, `ScopedIngestionRecoveryTest`, `EvidenceBundleImportTest`; reuse existing `UniversalBuildIngestionTest` và dependency/platform/frontend input tests. Đây là tên planned. Chọn module từ actual ownership/POM; chạy một class quiet mode mỗi vòng. Java fixtures thường 5–15 lines + metadata tối thiểu; trusted worker harness, không target scripts. Inspect case count/exit/skip thật; no full corpus trong loop.

## Exit và exact next task

Done khi compact input-to-exact-input journeys dùng coordinator thật, structure còn hữu ích khi exact prerequisites thiếu, valid supplied evidence thật sự giảm open obligations, failure/replay controls xanh. Không được done chỉ vì thêm interface/record hoặc mọi input đều trả PARTIAL.

Handoff gồm versioned request/bundle/registry contracts, source/build/artifact outputs, cause-to-obligation map, checkpoint semantics, commands/results và residual catalog rows. Tiếp theo là [V2.2](02-java-spring-semantic-closure.md); M4E/G3 vẫn pending.
