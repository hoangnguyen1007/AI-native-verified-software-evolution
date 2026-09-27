# M4 Universal v2 — evaluation and acceptance protocol

**PROVISIONAL preregistration design, 2026-09-23; no results.** V2.1 freezes the executable denominator, initial fixture manifest and acceptance parameters before implementation scoring. V2.3 executes the final campaign and hands evidence to M4E/G3. [Coverage catalog](../architecture/m4-universal-v2-coverage.md) defines required categories; [task index](../tasks/m4-universal-v2/README.md) defines owners.

## 1. Evaluation questions and claims

Measure whether v2 acquires more correct architecture facts, reduces avoidable uncertainty, preserves useful independent regions under failure, and produces complete source-to-Spring conditional explanations. Compare with unchanged v1 and applicable external baselines under explicitly different input modes.

**CONFIRMED owner decision (2026-09-27):** the release target is **at least 99% correct, evidenced coverage on registered eligible cohorts**. This is a preregistered acceptance threshold, not a measured result or a claim about all repositories. Freeze cohort inclusion, labels, weighting, input modes and hardware before scoring; the threshold must not be weakened after observing failures. Correctness, inventory closure and safety checks below are blocking regardless of percentage.

## 2. Three denominators, no silent conversion

1. **Input denominator:** all supplied entries plus discovered filesystem regions. Partition observed files as acquired, policy-excluded, unreadable/mutated/invalid, or deferred. Unvisited directory frontiers are explicit separate unknown inventory, never a made-up file count. Full inventory coverage is withheld if frontier is open.
2. **Semantic obligation denominator:** independently registered declarations, occurrences, relationships and framework questions for each category/context, including omitted/unsupported/error rows. For large unlabeled repositories, observed extraction counts are coverage telemetry, not ground-truth recall.
3. **Architecture-question denominator:** registered end-to-end questions such as “which definitions can satisfy this constructor in profile p?” or “what evidence connects this endpoint to a data boundary?”. A pile of parsed fields cannot mask failure on these questions.

Build-category applicability is established from independent inventory/labels, not whichever files a provider happened to accept. A test-only repo or non-Spring library remains in repo counts; Spring can be N/A with reason. A completely non-Java repo is an intake case, not Java architecture success.

For every adjudicated eligible obligation, use one outcome partition: `CORRECT_EVIDENCED`, `INCORRECT_DEFINITE`, `INSUFFICIENT_EVIDENCE`, `AMBIGUOUS_UNDECIDED`, `UNSUPPORTED`, `ERROR`, `OMITTED`. Correctly established absence or legitimate semantic ambiguity can be `CORRECT_EVIDENCED` **for a question whose expected answer is absence/ambiguity**; this does not count as a resolved Java target. Provenance quality and provider operational status remain separate axes.

## 3. Metrics

Let N be all eligible labeled obligations for a particular task/category/cohort; never aggregate different units into one unlabeled percentage.

| Metric | Definition | Protection |
|---|---|---|
| Correct evidenced coverage | CORRECT_EVIDENCED / N | Incorrect, omitted and provider failures remain in N |
| Definite precision | Correct definite answers / all definite answers | Report raw n/d; empty denominator is N/A |
| Target resolution | Correct resolved target occurrences / all registered target-resolution occurrences | Legitimate no-target cases declared separately, no candidate promotion |
| Operational repository completion | Runs yielding a readable terminal report / eligible intake attempts | PARTIAL/FAILED reports listed separately from useful architecture |
| Useful architecture completion | Repos satisfying all preregistered mandatory question checks / eligible Java repos | Empty report cannot pass |
| Uncertainty residual | Count by reason, affected obligation, region, provider and evidence mode | Historical gaps distinct from currently open obligations |
| Avoidable uncertainty reduction | (Initially unresolved obligations correctly answered by v2 − initially answered obligations made unresolved by v2) / initial unresolved obligations | Paired fixed denominator, raw gains/losses; N/A when initial count is zero |
| False certainty | Definite answers inconsistent with labels; false MUST/NEVER/absence reported separately | Any known unremediated correctness defect blocks affected capability acceptance |
| Witness validity | Independently replayed valid witnesses / emitted witnesses | Excluded/failed replay remains visible; never count withheld witnesses as valid |
| Evidence setup cost | Required manual input items/actions, artifact prerequisites, permitted operations | No claim of zero setup when private/generated evidence is required |
| Runtime resources | Phase duration, peak RSS, bytes/files/artifacts, deterministic steps, retries, output size | Cold/warm/failed/timeout runs separated, environment pinned |

“Avoidable” is labeled against a registered evidence mode: could a permitted provider answer using available inputs? Private artifact absent everywhere is an acquisition limitation; failing to read a supplied valid artifact is an avoidable implementation gap. Both remain in overall coverage; cause-level analysis never removes the harder rows.

For U regions use exact counting only if a validated model-counting operation/domain measure is available. SAT satisfiability alone cannot produce a percentage of all worlds. Otherwise report symbolic residual formulae, affected facts and bounded labeled assignments with their explicit sample denominator.

Publish micro aggregates, macro average per repository/cohort, worst required cohort, per-category counts and tail failures. Freeze any weighting; do not let easy declarations dominate injection/condition errors. Retain intersection/union mapping when v2 discovers obligations v1 missed: all union obligations enter recall, and absent v1 rows are omissions.

## 4. Corpus layers and input modes

| Layer | Required content | Purpose |
|---|---|---|
| L0 microfixtures | Every catalog subcase; compact Java fixtures usually 5–15 lines, small companion metadata | Independent exact expected behavior |
| L1 interactions | The catalog’s interaction controls, scoped-error and provider-failure injection | Prove integration and containment |
| L2 representative repos | Student/plain Java, legacy XML servlet app, modern Boot, Maven reactor, Gradle Groovy/Kotlin, generated-heavy, enterprise-like monorepo, binary-heavy/offline, large modular codebase | Assess practical scope without claiming population prevalence |
| L3 adversarial envelopes | Empty/non-Java, malformed/partial acquisition, unavailable private input, archive/link attacks, worker crash, cancellation and resource saturation | Show terminal reporting and honest residuals |

V2.1 chooses pinned public or owner-supplied repositories by these features, records exact snapshot/license/input hashes and expected strata before scoring. Existing PetClinic/ChatServer evidence is historical; any reuse creates a separately identified run, never rewrites G2 artifacts. Owner-supplied enterprise-like cases are not assumed to represent all private enterprise code.

The [candidate public L2 roster](m4-universal-v2-l2-cohort.md) records selected revisions, bounded source-export and license-file SHA-256 identities, plus remaining freeze fields. It is not an eligible scored corpus until analyzed input modes and independent labels are fixed and residual content/license questions are adjudicated.

Input modes:

- **A — passive repository-only:** source/build/config bytes plus declared platform policy; no target scripts, prebuilt outputs or hidden cache.
- **B — passive resolved evidence:** A plus permitted exact dependency/platform acquisition or captured artifacts, with same closure supplied to paired implementations.
- **C — imported generated/build/config evidence:** B plus user/trusted-harness supplied outputs with lineage. Do not call this zero setup or passive-only discovery success.
- **D — imported realized observations:** C plus a bounded observed config/runtime/AOT report. Only those observations are supported; this protocol does not authorize obtaining them by executing target applications.

Report absent/denied evidence in every mode. If a comparator requires a build, use trusted authored fixtures or supplied outputs only under current permission. Mark unavailable comparator cells with their cause rather than executing an untrusted lifecycle or assigning the competitor zero accuracy.

## 5. Independent truth and baselines

Java truth: diagnostics-aware compiler attribution on trusted fixtures, manually adjudicated source/artifact facts, exact spans and origins. Compiler recovery output after an error needs adjudication; it is not automatically ground truth. Annotation processors run only in the existing trusted fixture verification arrangement, never in target analysis.

Spring truth: reviewed version-specific framework implementation plus pinned trusted container fixtures; condition/registration/binding outcomes validated per tuple. Small finite spaces use an independent exhaustive oracle. A version pack cannot oracle-test itself. XML, annotation, order, hierarchy, proxy and generated interactions need negative controls.

Architecture questions: label expected paths with supporting fact IDs, compatible context/region and gaps. A dependency path is not a call trace. Human labels carry adjudicator/protocol version and disagreements; independent review is a M4E obligation, not something self-review can replace.

| Baseline | Fair comparison |
|---|---|
| Frozen Universal v1 at pinned commit | Same bytes/platform/classpath/config; provider ablation attribution |
| Static Java facts only | Same supported relationship/query categories |
| One realized configuration; flat condition model | Same finite space/queries; measure missed variation and false union paths |
| ArchUnit / Spring Modulith where applicable | Relevant module/dependency facts and evidence, exact imported classes/config; do not imply they require only one live context |
| CodeQL Java / SonarQube where outputs are comparable and available | Input requirements, resolved evidence and selected architecture queries; no blanket security ranking |

Pin tool edition/version/query/rule bundle, configuration and artifact digests. “Better than baseline X on metric Y in mode Z” requires paired results and correctness controls; absence of equivalent feature is `NOT_COMPARABLE`, not a manufactured loss. Market-wide superiority is outside the evidence obtainable from a finite comparison.

## 6. Mandatory blockers and calibrated targets

Blocking controls: no lost registered obligation; no false promotion from candidate to active/selected/runtime fact; no fake target/span/coordinate; no target execution or unauthorized network; no inconsistent-context merge; exact replay for captured complete inputs; valid published witnesses; recovery retains committed independent evidence. Every known incorrect definite answer in the acceptance fragment needs repair before that capability is accepted.

A typed terminal failure passes an accounting control but fails a required functionality control when the input/evidence is available. “Everything has a gap row” cannot pass the task. Every P/S row claimed supported needs a positive end-to-end example; every I row needs both a valid imported-evidence success and absent/stale/forged evidence negatives.

Coverage/performance acceptance parameters must be preregistered in V2.1 from owner targets and bounded calibration controls, with reference hardware, data sizes and budgets. No invented millisecond/zero-OOM guarantee. For challenging evidence-poor cohorts report attainable evidence mode and residual cause breakdown; do not lower the denominator or use global mean to hide a failed required cohort. Unmet targets leave the corresponding gate open.

## 7. Execution discipline and artifacts

During implementation run only the targeted test class quietly; bounded fixtures replace full repo benchmarks. No corpus loop per feature. Final V2.3/M4E campaign is one explicitly registered gate verification after isolated tests pass; planned paired modes/replay repetitions are internal to that single campaign, not repeated exploratory reruns. If it fails, preserve results, repair under a new task and register any new campaign; do not overwrite or quietly tune the corpus.

Required artifacts: `protocol`, `corpus-manifest`, `capability-case-map`, `independent-labels`, `tool-locks`, `environment`, `input-hashes`, `acquisition-journal`, `raw-outcomes`, `run-manifest`, `summary`, `claim-matrix`, `deviations`, and `checksums`. Names are logical artifacts, not claims these files already exist. Raw results are immutable and potentially large; checked-in compact summaries point to exact raw identities. User-facing diagnostics/exports redact secrets without silently changing the protected evidence identity model.

Output the exact acceptance matrix: task/category -> implementation state -> checks actually run -> result -> residual gap -> accountable next task. V2.3 readiness is not G3 acceptance; M4E owns adjudication. No new G2 gate is inferred or required merely because v2 improves providers.
