# V2.3 — Integrated architecture and acceptance

Status: **PLANNED / PROVISIONAL acceptance design**. Task cuối của [kế hoạch ba task](README.md). Mục tiêu: một đường input-to-architecture hữu ích, recovery đáng tin và evidence package cho M4E/G3. Không tạo task riêng cho mỗi report hay benchmark.

## Entry và ownership

V2.1 và V2.2 isolated controls xanh; identities/catalog/labels/targets được đăng ký trước scoring. Đọc [architecture](../../architecture/m4-universal-v2.md), [coverage](../../architecture/m4-universal-v2-coverage.md), [protocol](../../research/m4-universal-v2-evaluation.md) và product/conditional contracts liên quan.

Nối providers, sửa in-scope integration/recovery defects, cung cấp test-facing runnable entry point và canonical report, chạy acceptance khi đủ điều kiện. Không implement toàn M5 graph store, M6 policy/score, M7 CLI, M8 UI trước milestone. Report là stable input cho các consumer đó.

Code ownership: `analyzer/.../ingestion` orchestration/projection; evidence/conditional domain giữ truth; filesystem adapter giữ checkpoint/worker I/O. Integration tests ở module composition thực tế. New reproducibility package riêng; không sửa historical G2/M4 raw artifacts. Inspect runner/POM/side effects trước execution.

## Checklist thực thi

### A. Một đường input-to-report

- [ ] Entry nhận repository selection + explicit policies; caller không tự dựng mọi source-set/descriptor trước.
- [ ] Inventory -> roots -> structural/exact contexts -> evidence closure -> Java -> config -> Spring -> truth -> report xuyên suốt revision/context refs.
- [ ] Evidence gain chỉ recompute affected units; atomic report snapshot không trộn semantic revision.
- [ ] Report gồm module/source-set/language boundaries, package/type/dependency, component/producer/injection/selected definitions và entry-point paths.
- [ ] Visible facts có exact source/derived/binary provenance, compatible conditions/regions, context, limits và evidence navigation.
- [ ] Structural/exact layers có labels/denominators riêng; potential dependency path không gọi runtime call trace.
- [ ] Empty/non-Java/binary-only/broken input có terminal state rõ; non-Spring Java vẫn có useful architecture.

### B. UNKNOWN có lời giải thích và hành động cụ thể

Thay một tổng số UNKNOWN bằng root causes và architecture ảnh hưởng. Bảng sau chỉ minh họa format, không phải kết quả đo:

| Root cause | Ảnh hưởng | Đã thử | Bằng chứng tiếp theo |
|---|---|---|---|
| Missing generated contract types | Consumer calls/constructor bindings | Source recipe, selected cache, supplied bundle | Generated artifact khớp input lineage |
| External config chưa cung cấp | Beans/routes đọc đúng properties đó | Repository import closure | Explicit deployment envelope |
| Opaque registrar có global effects | Registry-closure-dependent conclusions | Source/binary metadata, bounded summary | Qualified observation hoặc validated semantic pack |

- [ ] Hiển thị phần đã chắc chắn và modules đủ structure để dùng ngay; không để gap summary che facts có ích.
- [ ] Root causes tách distinct affected obligations và historical gap events; grouping không xóa rows.
- [ ] Next action là typed evidence requirement. Tự thử provider áp dụng được và đã được phép trước khi nhờ người dùng.
- [ ] Avoidable defect và unavailable evidence phân loại theo mode; cả hai vẫn nằm trong overall coverage.
- [ ] Report budget/progress/frontier/partial/failure/cancel/no-progress; missing evidence không tăng health.
- [ ] Stable projection contract cho M5/M7/M8; consumers không tính lại canonical semantic values.

### C. Fault, safety và replay acceptance

- [ ] Malformed/corrupt/stale/mutating input, missing JDK/private dependency, dynamic build, unknown version, external config có closed outcomes.
- [ ] Controlled worker kill/OOM/stack overflow/timeout/cancel kiểm retained independent outputs, sanitized errors và terminal status.
- [ ] Resume dùng atomic checkpoints, reject corrupt/mismatched state, no infinite retry; complete captured replay bằng uninterrupted run.
- [ ] Acquisition journal pin bytes/outcomes; offline replay, cold/warm input equivalence và semantic digest tách wall timing.
- [ ] No target scripts/plugins/processors/apps execute; no ambient credentials/target-directed network; path/archive containment controls.
- [ ] Cross-world/context path rejection, false absence, artifact conflicts và independent witness replay chạy qua integrated entry point.

### D. Campaign và M4E handoff

- [ ] Freeze final analyzer/schema/pack/tool versions/hashes, v1 baseline và independent labels.
- [ ] Targeted integration controls trước; sửa defects trong scope. Không đổi bug thành limitation để đủ điểm.
- [ ] One proportional final root integration verification; record suite/case/skip/failure/toolchain/Enforcer thật.
- [ ] Final corpus campaign chỉ sau preregistration, isolated checks và gate execution conditions/permissions. Đây là shared evidence campaign của v2/M4E, không chạy lại full campaign vì đổi nhãn milestone.
- [ ] Campaign chứa paired evidence modes và cold/warm/replay repetitions đã đăng ký; retain failed/timeout/excluded rows và deviations.
- [ ] V1-v2 ablations đo artifact/build/generated/descriptor/reasoner gains và losses; union obligations gồm những row v1 bỏ sót.
- [ ] Applicable external baselines cùng evidence/query mode; unavailable/not-comparable cells explicit. Không chạy untrusted build chỉ để competitor hoạt động.
- [ ] Publish correct coverage, precision/false certainty, resolution, useful architecture completion, residual causes, setup effort, resources, worst cohort/category và witness validity.
- [ ] Independent semantic review và human G3 adjudication thuộc M4E. Thiếu review/corpus thì chỉ implementation-ready/acceptance-pending, chưa fully accepted.

## Acceptance journeys

| Journey | Kết quả phải chứng minh |
|---|---|
| Student/plain Java, no build | Inventory + useful declarations/dependencies; exact lane dùng declared platform policy; no fake GAV |
| Enterprise-like Maven + absent private JAR | Independent modules survive; affected consumers qualified; valid imported evidence closes đúng gaps |
| Gradle Kotlin DSL + catalog/generated client | Correct declared/captured model, no dependency guess; complete semantic path khi đủ evidence |
| Legacy XML/servlet | Context/import/ref/namespace/version-qualified beans và entry points |
| Modern Boot conditional Spring Data | Route -> component -> selected repository region + valid witnesses |
| Polyglot/multiple Java roots | Complete inventory/boundaries; no cross-context classpath pollution |
| Broken/hostile/oversized | Bounded progress, recovery, partial report; failure vẫn giảm functional coverage |
| Changed source/evidence | New identity/invalidation, immutable old report; evidence loss không gọi resolved violation |

Trong coding dùng compact fixtures, representative repos chỉ chạy ở registered final campaign. Không dùng fixture-only caller path bypass normal coordinator. Trusted runtime oracle khác execution của target repo.

## Exit và exact next task

Implementation exit cần integrated journeys/report/fault/replay và final relevant checks pass. V2 acceptance còn cần registered corpus/review; unmet targets, known false certainty hoặc thiếu required cohorts giữ gate open. Full accounting không đủ pass functionality.

Package: before/after cause-level results, exact commands/versions, input/run manifests/digests, raw references, claim matrix, failures/deviations, blockers và actual review status. Update current-state/implementation contracts theo evidence thật. Không commit/push trừ khi được yêu cầu.

**Exact next task: M4E adjudication of the shared v2 acceptance package and Gate G3.** Nếu campaign/review entry conditions chưa đủ, M4E hoàn thành phần evidence còn thiếu, giữ các kết quả đã có. M5/M6 không claim G3-stable Spring facts trước acceptance.
