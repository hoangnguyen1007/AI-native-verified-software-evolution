# Current State

Last reconciled: 2026-09-30. The owner requested completion of the next task; the [implemented V2.2 boundary](architecture/m4uv2-v22-implementation-boundary.md) now attaches inherited scalar fields to bounded ordinary source components using evidenced superclass closure. Private/hidden fields retain their declaring points and full spans; shared ancestor sites keep separate conditional component owners. Incomplete/binary/generic hierarchies and inherited metadata/method semantics remain qualified. V2.2 remains partial and its exit criteria remain open. V2.1 acceptance also remains open in the [implementation ledger](research/m4-universal-v2-v21-implementation-ledger.md). Fresh checks and their scope belong in the task handoff; historical gate evidence is identified below.

## PHASE AND MILESTONE

SE121, Track A + B. M1 contracts are committed at `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`; G1 is recorded passed. M2 frontend delivery is recorded complete across all 18 relationship families. M3.1–M3.8 implementation is delivered and its historical full reactor checkpoint passes 221 tests. M4-R0 is human-accepted, M4A.1–M4A.2 deliver the production evidence-only Spring mechanism inventory and annotation-declaration graph, M4B.1–M4B.2 deliver the condition IR foundation, evidence-to-IR lowering and bounded exogenous condition evaluation, M4C.1–M4C.3 deliver normalized discovery/registration/binding for the pinned Framework 6.2.0 / Boot 3.4.0 fragment, and M4D delivers finite-space truth regions plus minimized independently replayed witnesses. M4E validation and G3 remain pending. Focused G2 hardening tolerates equivalent duplicate dependency rows throughout external parent/BOM/profile model hierarchies and resolves complete handwritten reactor modules from exact acquired source evidence when bytecode output is unavailable. Sibling parse failures retain closed source outcomes, while referenced implicit record members retain sibling scope and component provenance. Comprehensive Gate G2 evaluation across 5 representative real-world architectural archetypes is documented in [Gate G2 Comprehensive Architecture Evaluation](reproducibility/g2-comprehensive-architectural-evaluation-2026-09-10.md).

## IN PROGRESS — M4 UNIVERSAL V2, THREE TASKS BEFORE M4E

**CONFIRMED human direction:** add a new M4 Universal v2 task to maximize useful
Java/Spring architecture coverage and reduce UNKNOWN/UNRESOLVED through stronger
evidence acquisition. The owner explicitly selected deep Java/Spring analysis plus
polyglot inventory/boundaries, and requested fewer tasks. The plan has exactly
three tasks: [V2.1 intake/evidence closure](tasks/m4-universal-v2/01-adaptive-intake-and-evidence.md),
[V2.2 Java/Spring semantic closure](tasks/m4-universal-v2/02-java-spring-semantic-closure.md),
and [V2.3 integration/acceptance](tasks/m4-universal-v2/03-integration-and-acceptance.md).

**PROVISIONAL technical design:** [architecture](architecture/m4-universal-v2.md),
[coverage catalog](architecture/m4-universal-v2-coverage.md),
[evaluation protocol](research/m4-universal-v2-evaluation.md) and
[ADR-005](decisions/ADR-005-m4-universal-v2.md). Planned mechanisms include scoped
recovery, passive build/artifact/generated evidence, active requirement-driven
acquisition, automatic source-to-M4C normalization and validated semantic packs.
V2.1 now has bounded acquisition/inventory and decoding, bounded hardlink-alias detection, scoped Gradle failure
recovery, literal catalog and custom source-set projections, a producer-asserted named source-plan importer,
a lineage-checked generated-source importer,
an exact captured artifact importer with optional selected dependency-graph validation,
a permission-checked evidence coordinator with cooperative cancellation,
an integrated source-to-frontend-input journey, acquisition-stage checkpoints,
and a bounded trusted-JVM worker supervisor with sealed per-unit checkpoints.
The supervised journey now routes bounded archive expansion/selected-entry inspection
and exact-classpath receipt validation through trusted workers. The parent still
captures/hashes artifact bytes and runs build-model/frontend assembly. Worker failure
retains independent source structure, keeps the exact frontend request closed and
records a scoped capability gap; sealed completed archive checkpoints replay with the
same result identity. Build-model computation and semantic providers still run in the
parent process. JVM heap is bounded; OS-level total memory isolation remains open.
Focused controls include an actual generated-constructor resolution and exact
classpath success/denial/failure paths. The 2026-09-28 root `verify -q` passed
571 tests in 83 fresh Surefire suites with no failures, errors or skips. These are
implementation checks, not a new benchmark result or gate promotion. Older
verification totals below remain historical.

The [public L2 candidate cohort](research/m4-universal-v2-l2-cohort.md) now has seven
commit-pinned, bounded source exports with per-entry SHA-256 manifests and matched
license-file hashes. Independent labels, analyzed path inclusion, input modes,
weights and hardware remain open; no L2 acceptance campaign has run.

**Current task: V2.2, by explicit owner request.** V2.1's remaining acceptance
obligations are retained; neither V2.1 nor V2.2 is declared complete. V2.3 follows
their exits, then M4E adjudicates the shared acceptance evidence before G3.
The owner fixed the threshold at 99% or more correct,
evidenced coverage on registered eligible cohorts on 2026-09-27; this is an empirical target,
not worldwide measured coverage. Target build/runtime execution, a primary-parser
replacement and new analyzed languages are not authorized by this plan.

Completed sections below retain checkpoint history; their former next-slice
instructions do not override this current sequence or the final next-task section.

## IN PROGRESS — V2.2 SEMANTIC CLOSURE

The production adapter now resolves direct generated Lombok constructor/accessor
calls in a bounded evidenced fragment, retains annotation constant reads, inferred
`var` types and anchored lambda/catch parameter identities. Config ingestion expands
captured relative imports with precedence, profile variants and resource bounds.
`UniversalIngestionPipeline.prepareSpring` connects actual source evidence to M4C
registration/binding and existing exhaustive/SAT truth evaluation under explicit
container/order/closure proofs. The bridge now also acquires direct bean methods
on ordinary components/lite configuration classes, declared scalar source return
types, factory ownership, literal names/flags/qualifiers and scalar bean parameters.
Explicit owner gates and registration schedules drive conditional binding. This
is not automatic application bootstrap or full Spring normalization.

The [implementation boundary](architecture/m4uv2-v22-implementation-boundary.md)
lists supported fragments, APIs and remaining A–E obligations. The
[verification package](reproducibility/m4uv2-v22-2026-09-28/README.md) records fresh
commands and results, including authored compiler/Lombok, Boot import and Spring
container controls. No broad V2.2 checklist section is marked complete. No target
build/runtime, external benchmark, measured 99% claim or gate promotion is made.

**CONFIRMED final implementation verification (2026-09-28):** root `verify -q`
with Enforcer enabled passed **597 tests in 88 fresh suites**, with zero failures,
errors or skips. All 18 changed source/test hashes remained unchanged through the
run. The initial broad run exposed an obsolete annotation-read expectation; the
repaired expectation and upstream-gap-retention control pass in the final run.
These results do not close the remaining V2.2 exit criteria.

**CONFIRMED bean-producer slice verification (2026-09-29):** the root reactor
checkpoint passed **609 tests in 88 fresh suites**, zero failures/errors/skips,
with Enforcer enabled and 351 source/resource/build inputs unchanged during that
run. Final self-review then reproduced and repaired a Java identifier-ignorable
character in a default bean name. The full affected integration class was rerun
against that corrected state: **18/18 tests passed** (13 additions to the prior
slice), zero failures/errors/skips, with all 351 inputs stable. The 609-test
checkpoint predates this narrow final correction; no second reactor or clean-build
claim is made. The [slice verification package](reproducibility/m4uv2-v22-beans-2026-09-29/README.md)
preserves both states, the failing controls, pinned container checks and hashes.
Aliases, enhanced configuration, overloaded/colliding reader decisions, wider
return-type metadata and runtime parameter names remain explicit evidence gaps.
This completes the recorded next bean-producer slice, not all V2.2 A–E obligations.

**CONFIRMED initial field/method slice check (2026-09-30):** the focused
`SourceToSpringPlanTest` run passed **32/32 tests** (14 additions), with zero
failures/errors/skips. Actual source-to-pipeline controls cover conditional scalar
fields/methods, required/optional groups, declaration-order parameters, qualifier
mismatch, primary conflict, incomplete/unresolved evidence, static members, field
versus parameter names, source annotation impostors and retained bean-product
member obligations. Fixed authored Framework 6.2.0 container controls corroborate
selection, suppression, errors, static handling and distinct Unicode field names.
Exhaustive/SAT agreement, witness replay and deterministic identities are checked.
The slice also repairs inactive group parameters incorrectly becoming `UNKNOWN`
and raw mechanism spelling incorrectly requiring NFC. Entity/span preimages,
accepted binding semantics and historical evidence remain unchanged; implementation
provider versions record the changes.

Initial slice command (PowerShell): `.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=SourceToSpringPlanTest" "-Denforcer.skipRules=reactorModuleConvergence" -q`.
The unmodified single-module command failed the reactor-parent membership rule;
only that inapplicable rule is skipped, with other Enforcer checks retained.
Generated target bytecode contained unresolved-compilation stubs, so JDK 21
`javac -proc:none --release 21` rebuilt analyzer/adapter sources and the selected
test class; `jar:jar install:install -pl analyzer -q` refreshed the local dependency.
At that checkpoint the Surefire report recorded **63.531 seconds** for the whole class; this did
not satisfy a sub-five-second whole-class budget. Inner controls were method-filtered
within the same class. No full reactor, external benchmark, new reproducibility
package, independent review or gate promotion is claimed. This is implementation
self-review with authored framework controls. Method-level qualifier merging,
inherited/generic/wrapper/aggregate/Resource descriptors and bean-product member
attachment retain explicit gaps. The next bounded slice is recorded below.

**CONFIRMED audit repair and cross-module verification (2026-09-30):** the supplied
audit's obsolete `SpringMechanismM4A2Test` provider assertion was reproduced as a
failure, then updated to the deliberately versioned source-spelling provider.
Three upstream controls now check exact Unicode spelling/digest integrity and
malformed spelling rejection, member-shape source provenance/name equality, and
all parameters of an inactive method group remaining `NOT_ACTIVE`. No production
semantics were changed in this audit repair. `MemberDeclarationRecord.java` is
selectively staged; all other changes remain unstaged, with no commit or push.
The unquoted dotted Maven property also reproduced PowerShell's unknown-phase
error; commands now quote the `-D` arguments.

Bounded pre-handoff commands (PowerShell, only the partial-reactor membership rule skipped):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=FrontendResultTest,FrontendContractTest,SpringMechanismM4A2Test,SpringMechanismInventoryTest,InjectionBindingsTest,InjectionBindingBoundaryTest,TruthRegionEvaluationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=JavaParserFrontendTest,ConstructorInjectionIngestionTest,GeneratedConstructorIntegrationTest,SpringMechanismM4A2IntegrationTest,ComponentIngestionTest,UniversalSpringSemanticsTest,SourceToSpringPlanTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Both commands exited zero: **102 analyzer + 61 adapter tests = 163 tests in 14
classes**, zero failures/errors/skips. These selected tests cover directly affected
contracts and consumers; they do not establish a full module/reactor build. The
whole `SourceToSpringPlanTest` class took **93.397 seconds** in this run. A subsequent
`"-Dtest=SourceToSpringPlanTest#fieldNamesAreEvidencedWhileMethodParameterNamesRemainUnknown"`
run passed one test, but still took **7.669 seconds for the command**; method filtering
does not by itself establish a sub-five-second inner loop. Its latest standard
Surefire report supersedes the earlier whole-class report. JDK 21
`javac -proc:none --release 21` compiled the three edited upstream tests against the standard test
classpath before verification.

`AGENTS.md` now owns two-tier verification: method/small-class inner loops with
honest timing, then bounded affected unit/contract and direct integration checks
across all changed modules before handoff. Rules, workflows, skills and the V2.2
task instructions agree; ordinary slices still prohibit full reactors, benchmarks
and ceremonial evidence packages. Diff checks, local Markdown links and skill
headers pass. The skill-creator Python validator could not run because `PyYAML`
is absent; header/link checks and the upstream regression exercise are self-checks,
not independent behavioral evaluation. No new gate, benchmark or build claim is made.

**CONFIRMED bean-product member slice (2026-09-30):** source-to-pipeline acquisition
now attaches member sites to every matching direct factory-product owner, preserves
source injection-point identity and full spans, and keeps source-method groups
separate per product. Conditional owners retain all inactive group parameters;
required/optional groups clear tentative edges independently. Factory ownership
is preserved, factory parameters remain separate dependencies, and an annotated
product constructor is never automatically injected. Complete product descriptors
currently require the bounded final ordinary source-class fragment; wider types
retain runtime-footprint gaps even without declared sites. An actual authored
Framework 6.2.0 subtype override control confirms why declared return types alone
cannot certify runtime method injection. Missing declarations/types and unsupported
metadata retain upstream obligations. Product fan-out preserves every acquired
request/group under the descriptor and match budgets with explicit resource gaps.
Provider `spring.source-to-plan:m4uv2.2-product-members-v1` records the new behavior;
M1/R0 injection-point preimages and existing acquisition/binding semantics are unchanged.

The initial scalar-field regression failed as intended (`COMPLETE` versus `UNKNOWN`),
then passed after implementation. Its commands took **7.069 s red / 7.842 s green**,
exceeding the five-second inner-loop target. A new missing-type control initially
expected fabricated parameter descriptors; inspection showed the unresolved method
has no fully evidenced sites. The corrected control checks retained frontend
observations/gaps and the actual acquired static-field requests instead.

Final bounded pre-handoff commands (PowerShell):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=InjectionBindingsTest,InjectionBindingBoundaryTest,TruthRegionEvaluationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=SourceToSpringPlanTest,UniversalIngestionPipelineTest,UniversalSpringSemanticsTest,ConstructorInjectionIngestionTest,ComponentIngestionTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Both exited zero: **78 analyzer + 64 adapter = 142 tests in 8 fresh classes**,
zero failures/errors/skips. `SourceToSpringPlanTest` passes **42 tests**, including
10 additions and the upgraded product-field regression, and took **135.169 s**.
Controls include actual pinned containers, exhaustive/SAT agreement, witness replay,
deterministic identities, provider version, owner/group isolation, negative metadata,
type gaps and budget closure. The trusted analyzer artifact was refreshed locally
with `jar:jar install:install -pl analyzer -q` before adapter verification. Only the
partial-reactor membership Enforcer rule was skipped. These checks are implementation
self-review, not an independent agent review, full reactor/clean build, target execution,
external benchmark, reproducibility package or milestone/gate acceptance. Git was clean
at entry; this slice is uncommitted and unstaged, with no commit or push.

**CONFIRMED direct method-qualifier slice (2026-09-30):** the next recorded slice
now acquires direct Spring method qualifiers for unqualified scalar parameters on
evidenced `void` methods. Parameter qualifiers take precedence; mismatching parameters
do not fall back, and non-void method qualifiers do not filter parameters. Component
and bounded product owners retain separate required/optional groups, source points,
full spans, conditional activation and obligation mappings. Missing return evidence,
empty/malformed/composed/impostor metadata and unproved method-level JSR-330/mixed
qualifier kinds remain typed gaps. Provider `m4uv2.2-method-qualifiers-v1` versions
both acquisition and source-to-plan composition; M1/R0 preimages and binding schemas
are unchanged. The [implementation boundary](architecture/m4uv2-v22-implementation-boundary.md)
owns the exact support contract.

The initial regression failed at descriptor completeness, then passed after the
implementation (**7.283 s red / 8.802 s green**, exceeding the inner-loop target).
Two newly authored controls initially had a wrong constructor API and optional-group
row-count expectation; both were repaired without changing the binding engine.
The bounded adapter bundle exposed one obsolete expectation that all method qualifiers
remain incomplete. Its updated control passes individually; the whole affected
integration class was then rerun on the final state. JDK 21 `javac -proc:none --release 21`
also compiled the two changed production classes and integration test class to avoid
IDE-generated unresolved-compilation stubs. The trusted analyzer artifact was refreshed
locally with `jar:jar install:install -pl analyzer -q`.

Commands actually run (PowerShell; only the partial-reactor membership rule skipped):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=InjectionBindingsTest,InjectionBindingBoundaryTest,TruthRegionEvaluationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=SourceToSpringPlanTest,UniversalIngestionPipelineTest,UniversalSpringSemanticsTest,ConstructorInjectionIngestionTest,ComponentIngestionTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=SourceToSpringPlanTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Final relevant evidence: **78 analyzer + 77 adapter = 155 passing tests in 8 classes**,
zero final failures/errors/skips. The middle command initially failed only the obsolete
expectation; its other four classes passed **22 tests** and remain unchanged. The final
command exited zero with **55/55 tests**, including **13 new tests**, in **123.233 s**.
The three changed code/test SHA-256 hashes stayed stable during that final class run.
Controls include authored Framework 6.2.0 containers, exhaustive/SAT agreement,
witness replay, deterministic identities, provider version and explicit negative gaps.
The unchanged `UniversalSpringSemanticsTest` report took **2448.760 s**, dominated by
`repositoryNamesRespectTheActiveInjectNamespace` (**2424.238 s**); this control calls
repository synthesis, not the new method-qualifier path. The timing cause is not isolated
and no performance-budget claim follows. Diff checks and 91 local document links pass.
This is implementation self-review, without independent agent review, full reactor,
clean build, target execution, benchmark, new reproducibility package or gate promotion.
All six changed files remain unstaged and uncommitted; no commit or push was performed.
V2.2 remains partial; the next bounded slice is recorded below.

**CONFIRMED inherited-field slice (2026-09-30):** `SpringSourceEvidence.sourceHierarchy`
now closes bounded ordinary source superclass chains using source declarations,
neutral class shapes, written parent types and resolved edges. Typed closure problems
retain the acquired prefix; missing/binary/generic/raw/interface/conflicting evidence
and traversal exhaustion cannot certify closure. `SourceToSpringPlan` provider
`m4uv2.2-inherited-fields-v1` attaches source member sites to every evidenced component
owner, preserving private and hidden same-named fields, original source documents/spans,
qualifier/requiredness, independent profile activation and obligation fan-out.
Acquired inherited methods remain incomplete with separate owner groups; unsupported
member annotations retain footprint gaps. Inherited class metadata and wider factory
product types remain qualified. The site provider, M1/R0 identities and binding schemas
are unchanged. Exact support and the 64-source-class traversal cap belong in the
[implementation boundary](architecture/m4uv2-v22-implementation-boundary.md).

The initial regression failed as intended (two hidden fields expected, one acquired),
then passed after implementation: **8.659 s red / 8.559 s green**, exceeding the
five-second command target. A new test initially called the nonexistent
`obligationBindings()` API; it was repaired to use `obligations()`. Its test bytecode
contained an IDE-generated unresolved-compilation stub. JDK 21
`javac -proc:none --release 21` explicitly compiled the two changed production classes
and the integration class; `jar:jar install:install -pl analyzer -q` refreshed the
trusted local analyzer artifact. Two authored expectations were corrected: truth
inventories also contain `NEVER` rows for nonmatching candidates, and a multi-variable
annotation did not supply the assumed M4A obligation rows. The fan-out budget control
now uses separate compact annotated field declarations rather than inventing mappings.

Final bounded pre-handoff commands (PowerShell; only partial-reactor membership skipped):

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=FrontendResultTest,FrontendContractTest,InjectionBindingsTest,InjectionBindingBoundaryTest,TruthRegionEvaluationTest" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=SourceToSpringPlanTest,UniversalIngestionPipelineTest,ConstructorInjectionIngestionTest,ComponentIngestionTest,UniversalSpringSemanticsTest#sourceToRepositoryInjectionAndControllerServiceRepositoryPath+dualNamespaceIsResolvedFromArtifactAndGeneration+inheritedCustomAndUnresolvedMappingsRemainVisibleWithoutInventedRoutes" "-Denforcer.skipRules=reactorModuleConvergence" -q
```

Both exited zero: **88 analyzer + 81 adapter = 169 passing tests in 10 classes**,
zero failures/errors/skips. `SourceToSpringPlanTest` passes **66 tests** (11 additions)
in **241.005 s**. Three directly relevant universal Spring methods were selected;
the previously slow unrelated repository-name control was not rerun. Checks cover
actual authored Framework 6.2.0 containers, cross-document/hidden/shared fields,
qualifier mismatch, optional/required failures, missing/conflicting hierarchy metadata,
inherited-method gaps, resource closure, exhaustive/SAT agreement, deterministic replay,
provider version and revalidated witnesses. This is implementation self-review;
no independent agent review, full reactor/clean build, target execution, external
benchmark, new reproducibility package or gate promotion is claimed. Git was clean
at entry. This slice remains unstaged and uncommitted; no commit or push was performed.

## IMPLEMENTED — M4-UNIVERSAL SLICE 2 SPRING SEMANTICS AND SAT

**CONFIRMED by implementation and focused tests:** the unified ingestion pipeline
now exposes Spring Data repository candidates, generation-qualified namespace and
metadata policies, direct injection/route evidence and potential component paths.
The neutral providers add supplied-resource auto-configuration parsing, normalized
parent/child visibility and selection, and passive basic SpEL lowering into the
actual M4B condition evaluator.

`SatConfigurationReasoner` implements bounded pure-Java DPLL over content-addressed
Boolean formulae. `TruthRegionEvaluation.evaluateSymbolic` supports condition
regions and partitions configuration truth signatures before running the unchanged
M4C registration/binding engine. Tests cover 56 profile dimensions without Cartesian
enumeration, witness replay, unknown residuals and small-space agreement with the
existing exhaustive evaluator. Legacy explicit enumeration remains available.

The [Slice 2 implementation contract](architecture/m4u2-universal-spring.md)
records exact APIs and support boundaries; the
[verification package](reproducibility/m4u2-spring-2026-09-23/README.md) records
commands, fresh report totals, toolchain and hashes. Implementation self-review
includes regressions for inherited-primary conflicts, namespace-specific repository
names and numeric-expression bounds; it is not independent agent review.

**CONFIRMED final verification (2026-09-23):** root `verify` passed **504 tests in
71 fresh suites**, with zero failures, errors or skips and Enforcer enabled.
All 71 current test suites are accounted for; eight historical report files were
excluded explicitly, and verified source/build input hashes remained unchanged.
No target repository build, external corpus benchmark or Git inspection was run.

**OPEN acceptance obligations:** the registered-cohort 99% target is unmeasured.
Structural repository candidates and dependency paths do not prove runtime
activation. Historical framework policies do not certify historical containers;
M4C execution remains pinned to Framework 6.2.0 / Boot 3.4.0. General library-resource
acquisition, complete source-to-descriptor normalization, arbitrary composed/inherited
mappings and dynamic expressions remain explicit provider boundaries. Finite SAT
does not establish arbitrary infinite-domain solving or worst-case millisecond
latency. M4E/G3 remain pending, including unresolved obligations of both universal
slices; the newly requested Universal v2 implementation precedes that validation.

## IMPLEMENTED — M4-UNIVERSAL SLICE 1 PASSIVE INGESTION

**CONFIRMED by implementation and focused tests:** `UniversalBuildIngestion` adds
passive Gradle Groovy/Kotlin and plain-Java projections while preserving original
Maven results. `UniversalSourceIngestion` closes source ownership and binds exact
caller-supplied platform/classpath evidence. `UniversalIngestionPipeline` joins
frontend output, automatic configuration baselines, component discovery,
constructor injection sites and typed gaps into one reproducible result.

JavaParser is now pinned to 3.28.2 (`frontend.javaparser:3.28.2-m4u.1`), with exact
non-preview parser levels through 26 and neutral type-declaration shape metadata.
Pinned Lombok 1.18.46 structural synthesis covers the six requested annotations
in the documented fragment; record and generated constructor parameters retain
type evidence. Config ingestion covers properties/YAML, profile variants,
bounded activation/placeholder handling and deterministic precedence. Component
roots support literal packages and resolved class markers; all six standard
stereotypes are checked against exact artifact evidence. Unique constructor
sites are acquired without claiming downstream binding or runtime activation.

The [slice contract](architecture/m4u1-passive-ingestion.md) defines the actual
support and gap boundaries. The [verification package](reproducibility/m4u1-ingestion-2026-09-20/README.md)
records final commands, totals, compiler/config-loader controls and file hashes.
**CONFIRMED historical Slice 1 verification:** that checkpoint passed **472 tests across
67 suites**, with zero failures, errors or skips and Enforcer enabled. All reports
are fresh for this invocation; 43 tests in 10 suites were added for M4U.1.
Implementation self-review is not independent code review. No target repository
build, external corpus benchmark, Git inspection, commit or push was performed.

**OPEN acceptance claim:** worldwide repository coverage of at least 98% remains
unmeasured. Dynamic Gradle behavior and exact classpath resolution, unsupported
Lombok configurations/versions, external Config Data and complete source-to-runtime
Spring normalization retain explicit gaps. This delivery does not pass G3 or
certify the entire M4-UNIVERSAL target. Slice 2 is now implemented above;
M4E/G3 evidence must cover both supported results and unresolved obligations.

## COMPLETED IMPLEMENTATION — M4D TRUTH REGIONS AND REVALIDATED WITNESSES

**CONFIRMED by implementation and focused verification (2026-09-18):** the neutral
`spring.truth` package supplies a solver-neutral `ConfigurationReasoner`, deterministic exhaustive
small-space backend and complete M4D aggregation over exact M4C registration/binding inputs. It emits
content-addressed definition-presence, injection-candidate and selected-binding facts; disjoint and
exhaustive `T`/`F`/`U` regions; `MUST`/`MAY`/`NEVER`/`UNKNOWN` quantifiers; separate feasibility and
operational outcomes; and minimum-cardinality baseline-delta witnesses independently replayed through
M4B/M4C. Empty, incomplete, mismatched, unknown and exhausted inputs cannot become vacuous universal
facts. Upstream and M4D gaps remain in a closed denominator. See the [architecture contract](architecture/m4d-truth-regions-witnesses.md).

**CONFIRMED audit hardening (2026-09-18):** implication/equivalence now require an observed opposing
truth value before returning `FALSE`; absence from an incomplete operand remains `UNKNOWN`. Region
limits and context mismatches retain unknown cells without manufacturing semantic witnesses, error and
limit outcomes are excluded from witness selection, failed replay candidates are not published as
revalidated witnesses, and descriptor lookup is reused outside the world/fact inner loop. Direct tests
now assert every M4D reason code, including adversarial replay rejection.

M4D remains bounded to the normalized Framework 6.2.0 / Boot 3.4.0 M4C fragment and supplied finite
configuration spaces. It does not select SAT/BDD, acquire Config Data or complete descriptors, execute
target applications, validate historical framework tuples, run representative repositories or pass
G3. **CONFIRMED final verification:** 15 focused M4D tests pass; the final six-module reactor passes
469 tests across 62 suites with zero failures, errors or skips under Maven 3.9.16 / Oracle JDK 21.0.12.1.
Exact commands are recorded in the
[M4D verification package](reproducibility/m4d-truth-regions-2026-09-18/README.md). Exact next task:
**Milestone M4-UNIVERSAL (Big Task: Universal Multi-Repository & Spring Intelligence Engine)**,
executing the comprehensive practical overhaul across Lombok, Spring Data, auto-config ingestion, Sat4j and dual-version Boot 2/3 prior to M4E validation.

## COMPLETED IMPLEMENTATION — M4C.3 INJECTION BINDING AND DEPENDENCY RESOLUTION

**CONFIRMED by implementation and verification (2026-09-18):** the passive `spring.binding` provider resolves evidenced descriptors against the completed M4C.2 registration state. It implements strict/generic-fallback/self passes, candidate flags and qualifier eligibility, the 6.2.0 name shortcut and primary/non-fallback/name/priority selection order, aggregate membership/order, required/optional behavior, optional-method groups, separate Resource/reference paths, and deferred provider/lazy requests. Injection-site/candidate/context identities are SHA-256 addressed; every request and M4A obligation remains accounted for; unsupported or missing evidence yields typed gaps without erasing upstream gaps. See the [architecture contract](architecture/m4c3-injection-binding.md).

**CONFIRMED verification:** 60 specification/boundary tests pass, and 23 differential observations agree with the actual SHA-256-pinned Spring 6.2.0 container. The final reactor passes **406 tests across 56 suites** (with 8 additive boundary tests in the latest hardened suite), with zero failures, errors or skips and the enforcer enabled; the separate oracle IT is additional. Independent red-team QC verified root-cause gap attribution in shortcut primary conflicts, bidirectional FactoryBean metadata conflict detection, and added boundary tests covering alias-shadow shortcuts, prior container errors, comparator policies, context mismatch, open descriptor inventories, and missing/ambiguous dependency gaps. Exact commands and input hashes are recorded in the [verification package](reproducibility/m4c3-injection-binding-2026-09-18/README.md). The oracle executes only trusted authored controls, not analyzed repositories. This task used implementation self-review, independent framework oracle verification, and adversarial red-team QC; no external repository benchmark is claimed. Git inspection was explicitly omitted by the owner.

The completed slice is the normalized definition-selection boundary. Automatic complete descriptor/type/qualifier acquisition, parent contexts, arbitrary resolvable dependencies, custom resolver/processor effects, FactoryBean/proxy/runtime creation and other framework versions retain explicit provider obligations. Selected definitions do not establish successful instantiation or universal truth. M4D now aggregates this boundary above; M4E/G3 validation remains separate.

## COMPLETED IMPLEMENTATION — M4C.2 ORDERED BEAN REGISTRATION

**CONFIRMED by implementation and focused verification (2026-09-17):** M4C.2 production code consumes the normalized M4C.1 plan through an additive registration-preparation mode and a separately evidenced registration schedule. It provides current-prefix bean-condition evaluation, configuration/method gates, explicit name/alias/override/removal history, query-type resolution evidence, candidate flags and per-selector matching, closed step/condition/candidate outcomes, SHA-256 identities and additive typed capability gaps. The first registration fragment is pinned to Framework 6.2.0 / Boot 3.4.0. Literal string bean queries are refined additively; class literals, inferred return types and more complex metadata require a supplying semantic provider.

Final implementation review and independent testing added actual-false-condition checks for negative removal gates, immediate query-type error termination, alias-shadow exclusion for type/ignored-type lookups, and verified reversed missing-bean fallback suppression, distinct selectors matching different candidates, autowire-ineligible exclusion, and single-candidate primary/fallback cardinality. Annotation lookup across alias-shadowed definitions remains an explicit typed gap.

Unknown execution/order/effects stop the evidenced registration prefix and leave the suffix explicitly `UNKNOWN`; definite registry errors retain `NOT_REACHED` outcomes. No binding, runtime-container equivalence, multi-world truth-region or G3 claim follows. The existing M4C.1 evaluator remains separate. See the [architecture contract and verification record](architecture/m4c2-ordered-bean-registration.md).

**CONFIRMED behavioral verification:** 28 targeted tests in `BeanRegistrationTransitionsTest` pass cleanly (0 failures, 0 errors, 0 skipped, ~5.0 s runtime). Regression check across all 11 Spring condition, space, lowering, discovery, ordering, and registration suites confirms zero regressions. M4C.3 binding is now delivered above; multi-world truth regions belong to M4D.

## COMPLETED IMPLEMENTATION — M4C.1 REGISTRATION PLAN AND DISCOVERY TRANSITIONS

**CONFIRMED by implementation and focused verification (2026-09-15):** the neutral `spring.registration` package provides immutable producer/candidate/event contracts, an evidence-normalized `RegistrationPlan`, bounded `DiscoveryTransitions`, and Boot 3.4.0 `AutoConfigurationOrdering`. Inputs bind one exact M3 context, M4A inventory, M4B conditions, explicit container/membership evidence and precedence. Unproven membership, unknown selectors/registrars, missing order, cycles, unsupported versions and resource limits remain typed gaps. Parse guards and deferred register-phase conditions are distinct; method discovery occurs during parsing without asserting final registration. Same-content condition objects replay by content identity. The predecessor comparator now retains distinct predecessors whose pending-list positions tie, following the supplied Gemini audit.

This delivers the normalized plan/discovery boundary, not an automatic scan/import acquisition adapter or runtime-container equivalence. Candidate activation, endogenous registration predicates, alias/override resolution, injection binding and G3 remain pending. Existing upstream gaps are preserved. See the [architecture contract](architecture/m4c1-registration-plan-discovery.md) and [focused verification record](reproducibility/m4c1-discovery-2026-09-15/README.md). The owner's latest instruction limits final verification to short focused checks and explicitly omits Git inspection and broader runs; no reactor, benchmark or independent-review claim follows. Exact next slice: M4C.2.

## COMPLETED IMPLEMENTATION — M4B.2 EVIDENCE-TO-IR LOWERING AND BOUNDED EXOGENOUS EVALUATION

**CONFIRMED by fresh implementation and verification (2026-09-14):** `analyzer` now provides the passive lowering provider `spring.condition-lowering:m4b.2`, bounded exogenous evaluator `spring.exogenous-evaluator:m4b.2`, finite configuration-space evaluator `spring.finite-exogenous-evaluator:m4b.2`, literal annotation parser `LiteralConditionAnnotation`, strict profile-expression parser `ProfileExpressionLowering`, and typed gap catalog `evidence.spring-condition-gaps:m4b.2-v1`.

Every M4A obligation is partitioned into a closed denominator: `LOWERED`, `OPAQUE`, or `NOT_A_CONDITION`. Supported literal `@Profile` and `@ConditionalOnProperty` annotations are lowered with strict AST escape handling, whitespace/empty preservation, and Spring's mandatory parentheses rule for mixed AND/OR profile expressions. Build-context conditions (`@ConditionalOnClass`, `@ConditionalOnResource`, `@ConditionalOnJava`) are verified against whole-annotation query proofs; conflicting proofs emit `BUILD_EVIDENCE_CONFLICT`. Project-defined Spring impostors and unverified framework version fragments remain `OPAQUE`.

Exogenous evaluation supports Strong-Kleene 3-valued logic, source precedence ordering (`spring.normalized-precedence:last-active-v1`) with `MISSING` fall-through, profile policies (`spring.normalized-profiles:defaults-includes-groups-v1`) with cycle-safe monotone reachability, Spring Boot `matchIfMissing` and `havingValue` matching rules, and deterministic step/depth budgets. Endogenous bean conditions and custom `@Conditional` classes remain `UNKNOWN` with typed obligations. Finite small-space evaluation enumerates Cartesian assignments with deterministic saturation guards.

The final single reactor verification passes **333 tests across 54 suites**, with no failures, errors or skips; 34 focused tests cover the M4B suite. See the [architecture contract](architecture/m4b2-evidence-lowering-exogenous-evaluation.md) and [verification evidence](reproducibility/m4b2-exogenous-evaluation-2026-09-14/README.md). No independent review, real-repository benchmark, Spring bean registration/binding, truth-region or G3 claim follows. The next slice is M4C.1.

## COMPLETED IMPLEMENTATION — M4B.1 CONFIGURATION-SPACE AND CONDITION IR FOUNDATION

**CONFIRMED by fresh implementation and verification (2026-09-13):** `analyzer` now provides the storage-/solver-neutral `spring.condition-ir:m4b.1-v1`, separate content-addressed `ConfigurationSpaceIdentity`, exact M3 build projection, explicit repository/deployment envelopes, typed finite domains and a closed condition-row validator. Existing M1/M3 identities and schemas remain unchanged. Formula identity is separate from occurrence provenance; source evidence must match the M3 snapshot and raw digest; opaque and stateful operands retain order; MISSING, empty strings, whitespace and Unicode normalization forms remain distinct.

Provider `spring.condition-model:m4b.1`, result `spring-condition-model-v1` and catalog `evidence.spring-condition-gaps:m4b.1-v1` retain unsupported/opaque inputs, unverified OTHER partitions, unavailable configuration sources and every exhausted deterministic budget as typed gaps. Original rows/formulas remain available when expansion is withheld. `REPRESENTED` is an IR status, not a truth or feasibility claim; feasibility remains `NOT_EVALUATED` except explicit empty domains are `INVALID_MODEL`.

The historical single reactor verification at M4B.1 checkpoint passed 271 tests across 47 suites; see the [architecture contract](architecture/m4b1-configuration-space-condition-ir.md) and [verification evidence](reproducibility/m4b1-condition-foundation-2026-09-13/README.md).

## COMPLETED GATE — M4-R0 RESEARCH AND SEMANTICS GATE ACCEPTED

**CONFIRMED human decision (2026-09-11):** Gate M4-R0 is formally **PASSED** following human review and acceptance. The contract candidate, 29-family mechanism catalog (`spring-mechanisms:v2` with unclassified catch-all and bounded XML), additive identity preimages (`m4-r0-identity-v1`), historical version matrix (Spring 1.x through Boot 3.4.x), 79/79 runtime fixture observations across 3 pinned framework pairs, 70/70 formal cases, 64/64 CNF comparisons, 452 capability gaps, and evaluation protocol are approved as the baseline for Milestone 4.

**ACCEPTED M4-R0 artifacts:** [architecture contract](architecture/m4-r0-semantics-gate.md), [research assessment](research/2026-09-11-m4-r0-spring-semantics.md), and [reproducibility package](reproducibility/m4-r0-2026-09-11/README.md). The hardened v2 manifest verifies all 97 checked-in package files with explicit LF-canonical text identity and preserves the eight original external-document digests as historical reference-only metadata. Production implementation began with slice M4A.1.

## COMPLETED MAINTENANCE — M4-R0 PACKAGE INTEGRITY HARDENING

**CONFIRMED by regression tests and offline verification (2026-09-12):** the M4-R0 manifest no longer treats evolving architecture/governance documents as immutable package members and no longer depends on platform checkout newlines. Package scope is explicit and closed; unsafe/duplicate paths, undeclared files, hash-mode changes and digest mismatches fail deterministically. Repository attributes now pin common source/evidence text formats to LF and binary artefacts remain binary.

The standalone package verifier now separates checked-in evidence from 60 Git-ignored external cache inputs. Its default mode validates locks, denominators, saved input identities, replay equality, typed gaps, snapshot/summary identities and local links on a clean clone without network or execution. `--require-cache` verifies the 26 JAR and 34 acquired-source payloads when explicitly fetched; missing inputs produce a closed `UNAVAILABLE` report with exit code 2 instead of a traceback. The historical oracle remains limited to already-filtered binding candidates, and runtime evidence remains limited to three exact tuples with 13 explicit version-fragment gaps.

## COMPLETED IMPLEMENTATION — M4A.1 EVIDENCE-ONLY MECHANISM INVENTORY

**CONFIRMED by implementation and focused tests (2026-09-12):** `analyzer` now contains the accepted 29-family `spring-mechanisms:v2` catalog, passive provider `spring.mechanism-scanner:m4a.1`, content-addressed `spring-mechanism-inventory-v1`, exact M3 classpath/artifact binding, decoded XML/metadata resource provenance, closed raw-observation/semantic-obligation coverage, bounded DTD-disabled XML validation and logical `spring.factories` continuation handling. Exact annotation family classification requires both the resolved M2 type and its dependency scope to match a supplied classpath artifact, preventing project-defined Spring-FQN impostors.

Only the accepted M4A.1 reason subset is emitted: `UNCLASSIFIED_MECHANISM`, `ANNOTATION_SEMANTICS_NOT_ADJUDICATED` and `VERSION_FRAGMENT_NOT_VALIDATED`. The unchanged M3 `CapabilityGapRecord` schema is extended through catalog `evidence.spring-mechanism-gaps:m4a.1-v1` and normalizer `evidence.gap-normalizer:m4a.1`; gaps retain exact observations, spans, evidence needs and limitations. Focused verification covers all three artifact-lock tuples, deterministic replay, annotation roles and provenance boundaries, legacy XML closure, unknown namespaces, malformed/external/internal-DTD controls, metadata continuation rows and gap normalization. Final reactor verification at that checkpoint passes 241 tests with no failures, errors or skips. See the [M4 Spring contract](architecture/m4-spring-intelligence.md#implemented-slice-m4a1--evidence-only-mechanism-inventory) and [verification record](reproducibility/m4a1-mechanism-inventory-2026-09-12/README.md). M4A.2 subsequently closes annotation composition and the remaining non-annotation Java detection scope; activation, registration effects, binding, truth regions and G3 remain pending.

## COMPLETED IMPLEMENTATION — M4A.2 REMAINING EVIDENCE-ONLY ACQUISITION

**CONFIRMED by implementation and focused tests (2026-09-13):** provider `spring.mechanism-scanner:m4a.2` and `spring-mechanism-inventory-v2` add content-addressed annotation declarations, exact meta-edges, strongly connected cycles, attribute/default/alias evidence, repeatable-container targets and explicit unresolved external declaration nodes. Composed classification follows resolved graph edges only, accepts exact source-to-source declarations, propagates downstream incompleteness, and still requires exact accepted dependency/JDK scopes for known terminals; name-only and project-defined Spring-FQN impostors remain unclassified.

M4A.2 also closes the remaining M2 Java evidence shapes for exact single-constructor and `@Bean`-parameter injection, aggregate/provider requests, `FactoryBean`, Spring Data repository ancestry, generated-member annotations, registry extension SPIs, framework entrypoint/callback SPIs, programmatic registration calls and container lookups. It records typed gaps instead of synthesizing generated members, factory products, repository beans, dynamic targets or lifecycle effects. Catalog `evidence.spring-mechanism-gaps:m4a.2-v1` and normalizer `evidence.gap-normalizer:m4a.2` preserve the unchanged M3 gap schema and exact source/artifact/runtime authorization boundaries. Focused verification passes 28 tests and the delivery-checkpoint six-module reactor passes 245 tests with no failures, errors or skips; see the [verification record](reproducibility/m4a2-mechanism-acquisition-2026-09-13/README.md). See also the [M4A.2 architecture record](architecture/m4-spring-intelligence.md#implemented-slice-m4a2--remaining-evidence-only-mechanism-acquisition). M4A is detection/provenance complete; activation, registration effects/order, binding, truth regions and G3 remain pending.

**CONFIRMED QC hardening (2026-09-13):** a post-delivery audit identified two outer-type keyword false classifications and missing exact lookup owners. The scanner now classifies only the outer type header with delimiter-aware tokens, so nested annotations/classes cannot change the enclosing class/interface kind; exact lookup detection now includes the concrete context/factory declaring types exercised by JavaParser. Real frontend coverage also verifies `@AliasFor` attributes/defaults. The final affected suites pass 18 tests with no failures, errors or skips; see the [QC record](reproducibility/m4a2-qc-hardening-2026-09-13/README.md). Public schemas and the M4A/M4B boundary are unchanged.

## COMPLETED IMPLEMENTATION — M3.1–M3.8 WORKSPACE AND BUILD-MODEL INTELLIGENCE

**CONFIRMED by implementation:** M3.1 adds the neutral immutable `BuildModelProvider` contract and isolated `analyzer-maven` adapter (`build-model-input-v1`, `build-model-result-v1`, provider `3.9.16-m3.1`). It passively models root/nested aggregation, relative and explicitly supplied artifact parents, imported BOMs, inherited/interpolated properties, explicit/property/default profiles and ordered dependency-management/direct declarations. Inputs bind exact POM bytes/digests and explicit policy; missing/failed modules, read attempts, limits and typed problems remain visible. `.` now represents the real root module through an additive module-path extension; existing M1 identity preimages remain unchanged. See the [M3.1 contract](architecture/m3-workspace-build-model.md#implemented-slice-m31--passive-effective-pom-projection) and [verification record](reproducibility/m3-model-2026-09-06/README.md).

**CONFIRMED by implementation tests:** M3.2 extends that projection with immutable per-module main/test source plans (`build-model-result-v2`, provider `3.9.16-m3.2`; input identity unchanged). It projects inherited directories and default-goal compiler configuration, separates syntax/bytecode/API requirements, preserves encoding and unknown plugin declarations with POM provenance, and reports unresolved/unsafe/unsupported cases. `hasGaps()` includes plan gaps; `problems()` remains the model-construction problem list. The original M3.2 checkpoint's five platform POMs verify inherited Java 21/UTF-8 plans and module-relative roots; the current smoke test also covers the new filesystem module POM. See the [M3.2 contract](architecture/m3-workspace-build-model.md#implemented-slice-m32--declarative-source-plans) and [verification record](reproducibility/m3-source-plans-2026-09-06/README.md).

**CONFIRMED by implementation tests:** M3.3 adds neutral immutable acquisition and candidate-ownership contracts plus the isolated `analyzer-filesystem` module (`repository-acquisition-input-v1`, `repository-acquisition-result-v1`, provider `repository.filesystem:m3.3`; `candidate-source-ownership-v1`, provider `workspace.source-ownership:m3.3`). An explicitly selected directory is read without following links or executing content, under exact file/directory/byte/depth limits and explicit exclusions. Only complete selections produce M1 snapshots and M3 POM inputs; partial bytes, typed failures and attempts remain visible. Candidate Java paths preserve module/main/test/POM claims and expose missing roots, overlapping files and unowned files without decoding or semantic promotion. See the [M3.3 contract](architecture/m3-workspace-build-model.md#implemented-slice-m33--safe-filesystem-acquisition-and-candidate-ownership) and [verification record](reproducibility/m3-filesystem-acquisition-2026-09-07/README.md).

**CONFIRMED by implementation tests:** M3.4 adds neutral `ClasspathProvider` contracts (`classpath-resolution-input-v1`, `exact-classpath-result-v1`) and the isolated `classpath.maven-local:3.9.16-m3.4` adapter. It emits deterministic per-module `MAIN`/`TEST` manifests; implements Maven nearest/first-declaration mediation, dependency management, scope propagation, exclusions, optionality, classifiers and reactor precedence; and binds ordered JARs plus all acquired POMs to exact SHA-256 evidence. Cache paths are portable and cache-root independent in identity. Exact-coordinate cache reads are finite and reject links, escapes, mutation and non-regular inputs. External parent/BOM models use secure XML and explicit properties/profiles only; network, settings, transport, plugin processing and target lifecycles are absent. Duplicate reactor GAVs are withheld without cache fallback, and unavailable reactor outputs remain explicit. See the [M3.4 contract](architecture/m3-workspace-build-model.md#implemented-slice-m34--passive-exact-classpath-manifests) and [verification record](reproducibility/m3-classpath-2026-09-07/README.md).

**CONFIRMED by implementation tests:** M3.5 adds strict source decoding (`source-decoding-result-v1`, `source.decoder:m3.5`), explicit platform acquisition (`platform-symbol-request-v1`, `platform-symbol-result-v1`, `platform.jdk-filesystem:m3.5`) and per-source-set assembly (`frontend-input-assembly-v1`, `frontend.input-assembler:m3.5`). Declared charset evidence precedes an optional explicit policy; malformed bytes, BOM conflicts and invalid/unsupported declarations are typed and never guessed. Raw bytes/digests, BOM and LF/CRLF/CR counts remain bound. A canonical configured JDK supplies Java 8 `rt.jar` or sorted Java 9+ JMODs with finite reads and release/vendor/version/digest provenance; `frontend.javaparser:3.27.1-m3.5` consumes verified JAR/JMOD symbols independently of the Java 21 runtime and configures non-preview Java 8–21 syntax separately. Assembly verifies the acquisition/build/ownership/classpath provenance chain and withholds any source set with missing, partial, mismatched or extra dependency/reactor/platform evidence. M3.4 is compatibly extended to `exact-classpath-result-v2` / `classpath.maven-local:3.9.16-m3.5` so reactor and external inputs retain one exact shared order. See the [M3.5 contract](architecture/m3-workspace-build-model.md#implemented-slice-m35--decoded-source-platform-views-and-frontend-inputs) and [verification record](reproducibility/m3-frontend-inputs-2026-09-07/README.md).

**CONFIRMED by implementation tests:** M3.6 adds the storage-neutral `com.evolution.analysis.evidence` core with `capability-gap-record-v1`, `evidence.capability-gap-catalog:m3.6-v1`, `acquisition-attempt-record-v1`, `provider-conflict-record-v1`, `gap-resolution-record-v1` and `evidence-acquisition-ledger-v1` produced by `evidence.gap-normalizer:m3.6`. It binds snapshot plus optional pre-existing M1 analysis identity without feeding gaps back into analysis identity; preserves content-addressed references to every normalized provider result/payload; maps every current degraded M2/M3 enum through exhaustive versioned mappings; keeps attribution, origin, provenance and unmapped dimensions separate; and retains typed questions, authorization classes, attempt outcomes, conflicts, adjudication and additive later evidence. Candidate providers are advisory only, missing permission provenance stays `NOT_RECORDED`, and the normalizer performs no I/O or provider execution. See the [M3.6 contract](architecture/m3-workspace-build-model.md#implemented-slice-m36--normalized-capability-gaps-and-acquisition-provenance) and [verification record](reproducibility/m3-capability-gaps-2026-09-08/README.md).

**CONFIRMED by implementation tests and real-repository execution:** M3.7 adds the `build.maven-local-inputs:3.9.16-m3.7` provider ladder: exact supplied/workspace POM bytes, one explicitly selected Maven2 cache, then optional explicitly configured credential-free HTTPS release POM repositories. Counts, bytes, passes and timeouts are finite; target-declared repositories, redirects, snapshots, settings, credentials, plugins and lifecycles stay inert. Unavailable host-dependent Maven profiles now qualify a deterministic inactive baseline instead of erasing the rest of the model, while explicit activation remains authoritative. A newer configured JDK can provide an older requested Java release through a verified `ct.sym` view. The evidence normalizer now also preserves observed input evidence from failed build reads without mislabeling it as a produced artifact. See the [M3.7 contract](architecture/m3-workspace-build-model.md#implemented-slice-m37--progressive-maven-model-inputs-and-cross-release-platform-views), [verification record](reproducibility/m3-adaptive-maven-2026-09-08/README.md), and [Spring PetClinic cross-check](reproducibility/g2-petclinic-check-2026-09-08/README.md).

**CONFIRMED by implementation tests and the official real-repository checkpoint:** M3.8 adds the neutral `DependencyArtifactProvider` contracts (`dependency-acquisition-request-v1`, `dependency-acquisition-result-v1`), `dependency.artifact-cache:m3.8`, and `dependency.maven-fixpoint:3.9.16-m3.8`. Exact release POM/JAR requirements are resolved through one explicit standard Maven2 cache and explicitly authorized credential-free HTTPS repositories under finite coordinate, pass, retry, artifact-byte, total-byte and timeout limits. Cache reads reject links, escapes, mutation and invalid content; downloads reject redirects, authentication and snapshots, validate secure POM or complete ZIP/JAR content, bind SHA-256, and use same-directory atomic promotion. Every coordinate and local/remote attempt remains in the current `evidence.capability-gap-catalog:m3.8-v2` / `evidence.gap-normalizer:m3.8`; `LOCAL_WRITE` distinguishes cache promotion authority from read/network authority. The fixpoint reruns passive classpath resolution to closure and supplies exact acquired JAR bytes to frontend assembly. See the [M3.8 contract and evidence](architecture/m3-workspace-build-model.md#implemented-slice-m38--bounded-dependency-artifact-acquisition-and-isolated-cache) and [Spring PetClinic checkpoint](reproducibility/g2-petclinic-check-2026-09-09/README.md).

In the official checkpoint, pinned Spring PetClinic snapshot `818c4136ea971c21674525f9053de0d9c7ad8cfe` ran twice from separate empty caches. Each run accounted for 629 requested files: 266 acquired JARs and 363 acquired/verified POM descriptors recorded as `SKIPPED_POM` because they do not become frontend binary inputs. Each run consumed 171,800,181 bytes, assembled both `MAIN` and `TEST`, analyzed all 50 Java files and emitted 4,250 frontend observations. The canonical outputs are byte-identical at 20,150,259 bytes with digest `sha256:8806702323d9bc307aef7795bee0b10bdf15e43da65353f75015517b46eb2796`. Multi-release JARs are projected deterministically for the analyzed release; manifest `Class-Path` is recorded as a content-addressed warning instead of silently extending the Maven classpath. Real-run failures also drove deterministic missing-span AST provenance, separation of snapshot/build and per-analysis evidence ledgers, and canonical URI serialization tests.

**CONFIRMED by the post-fix ChatServerMicroservices full-pipeline execution:** pinned revision `d9aa0cf5d9c4de70a16d36708a027f9d45c71792` ran cleanly across two independent empty caches in 58m 17s. The two runs are byte-identical at 57,647,513 bytes with digest `sha256:3a8903dd8fc96de64dddf3dd4fd17c45a2146c5a7d432cee929985d52fa66bd2`. Hardening eliminated the previous `POM_MODEL_FAILED` (now 0), acquired 397 JARs across 17 rounds without failure, assembled 8 source sets with 587 frontend observations (145/158 calls resolved), while cleanly reporting 442 typed capability gaps (including 30 unacquired protobuf generated sources and 24 unbuilt reactor outputs).

M3.1/M3.2 remain declarative projections; M3.3 supplies bounded repository inputs/candidate ownership, M3.4 supplies passive dependency/JAR manifests, M3.5 supplies decoded source/platform/frontend inputs, M3.6 normalizes gaps, M3.7 supplies exact parent/BOM POM and cross-release platform evidence, and M3.8 closes exact dependency release artifacts for the supported Maven context. Complete handwritten sibling modules may additionally provide source-level symbol evidence at their exact reactor classpath position; this does not acquire or claim bytecode output. Reactor-output acquisition, class-directory inputs, generated-source lineage and toolchain discovery remain future provider boundaries. M4U.1 now provides bounded POM-less and Gradle source-plan projections. Version ranges/dynamic selectors, relocations, system paths, non-JAR binaries, nonstandard/split cache layouts, timestamped snapshots and manifest-classpath expansion remain explicit gaps; relocation never admits the obsolete JAR. M3.8 received implementer self-review, reactor verification and real external-repository execution; the later hardening has focused unit verification only. That implementation checkpoint did not itself advance G2/G3/product gates; G2 is subsequently recorded passed below.

On 2026-09-04, M2 frontend implementation was fully validated across all 18 relationship families (catalog `m2-java-4`, adapter `3.27.1-m2.4`), with 98 root reactor tests passing cleanly and whole-project multi-file extraction confirmed. That checkpoint transitioned implementation from M2 to Milestone M3 (Multi-Module Workspace and Build-Model Intelligence).

The human explicitly directed implementation using the sufficiently mature M2 contracts: do not reopen D1–D3 approval or expand preflight/design/oracle work unless implementation exposes a concrete correctness blocker. The [M2 contract](architecture/m2-semantic-frontend.md) is the provisional implementation baseline. Prioritize production code, tests, fixtures alongside code, verification, then documentation only when truth changes. Work in vertical slices; Codex owns difficult semantic/identity issues.

**CONFIRMED by implementation tests:** immutable strict-UTF-8 source inputs; source-plan identity (including module/source-set membership); exact ordered resolution inputs; Unicode-safe Java keys; exact original UTF-16 spans including escaped final delimiters; explicit semantic/parse/adapter errors; ledger/entity/source/catalog consistency. Existing M1 golden identities remain unchanged. See [M2 implementation evidence](reproducibility/m2-implementation-2026-09-03/README.md).

The four archived candidate fixtures were independently reviewed by a Codex reviewer and copied byte-for-byte into adapter test resources. The integrated adapter checks all 27 registered type/callable declarations and 23 invocations: 21 resolved targets and two correctly unresolved outcomes, exact callers/origins/spans, no unexpected registered-kind declarations/invocations, and deterministic reruns. Additional tests cover generic erasure, ambiguity, duplicate declarations, lexical execution owners, Unicode, real dependency JARs/order/removal, digest rejection and host-classpath isolation. This is bounded slice evidence, not full M2 accuracy.

The declared-type slice added immutable `JavaType`/`TypeUseRecord` output and parameter/return/field types, explicit inheritance/permits, throws, bounds and generic argument references. Known generic containers and callable erasures survive missing arguments; unknown targets remain explicit. Primitive/void information stays in type detail without invented entities. That slice used catalog `m2-java-2` and adapter `3.26.1-m2.2`; its [verification record](reproducibility/m2-types-2026-09-03/README.md) remains historical. No research/comparator or independent review campaign was added for that slice.

Field reads/writes distinguish simple assignment, compound assignment/increment, receiver reads and array-element updates. Source fields, inherited/hidden fields, static imports, enum constants and explicit dependency fields retain their actual declarations/origins. Unresolved explicit accesses remain occurrences; unclassified bare names remain unmapped ledger entries. The [field slice record](reproducibility/m2-fields-2026-09-03/README.md) is historical evidence for catalog `m2-java-3`/adapter `3.26.1-m2.3`; its former record boundary is superseded below.

The continuation from clean `dae013c` adds method/constructor references with functional compatibility checks; expression types and annotation-use identities; source records, compact constructors, component fields/accessors and default constructors; enum constant bodies with anonymous owners; and neutral `DerivedRelationshipRecord` evidence. Implicit members keep project origin, have no invented declaration span and cite owner/component inputs. Explicitly written record members suppress automatic body facts. Catalog is `m2-java-4`, adapter `3.27.1-m2.4`, parser pin 3.27.1. The new Java 21 fixture exercises every catalog row and independently compares all 15 explicit invocations with diagnostic-aware JDK 21 attribution. See [modern Java implementation evidence](reproducibility/m2-modern-2026-09-03/README.md) for tests, raw results, fixes and exact limits.

The [pause archive](reproducibility/m2-pause-2026-09-02/README.md), historical [oracle pilot](../benchmarks/m2-ground-truth/README.md) (34/34 labels, three identifier relations) and inactive [Antigravity package](reproducibility/m2-antigravity/README.md) remain unchanged. The new compiler comparison is one integrated modern fixture, not a renewed technology/comparator campaign.

The human checkpoint includes the prior governance and pause work. Its [verification record](reproducibility/governance-hardening-2026-09-02.md) remains historical. No product gate advanced.

The human approved JavaParser + SymbolSolver as the primary SE121/M2 frontend on 2026-09-02. This confirms the implementation choice, not universal accuracy, performance superiority or G2 acceptance. See [ADR-001](decisions/ADR-001-parser-technology.md). The replaceable SemanticFrontend boundary, validation gates and replacement triggers remain mandatory. OpenRewrite remains an independent comparator.

## REPOSITORY REALITY

- M-1 approved baseline: `86c4ca29fb747797df3e489d978804644a34f1ce`.
- M0 foundation commit: `375702f9b871dd78fbad99f8bc5994b7b2c499fb`.
- M1 contracts commit: `b04220e722cc4bc772cbb3ad8531d4dc1ea1a058`.
- Comparison package commit: `83797e840e414bf99a0f71117892da355d94be55`; current implementation continuation starts at checkpoint `dae013c`.
- Root reactor: `analyzer`, `analyzer-maven`, `analyzer-filesystem`, `analyzer-javaparser`, `backend`. The neutral frontend lives under `analyzer/src/main/java/com/evolution/analysis/frontend/`; build-model and acquisition contracts live under their neutral `analyzer` packages. Maven Model Builder 3.9.16, local filesystem access and JavaParser libraries remain in their respective adapters.
- Root verification includes the original M1/build tests and new frontend/adapter tests. Exact final totals and raw console output are in the implementation evidence. Standalone benchmarks are outside root verification.
- R1 PoC: `benchmarks/poc/parser-eval/`. Independent experimental adapters/comparison: `benchmarks/semantic-frontend-evaluation/`.
- M2 oracle pilot: `benchmarks/m2-ground-truth/`, separate from the reactor and legacy comparator, using JDK 21/Python standard libraries.
- Production adapter pin: JavaParser/SymbolSolver 3.28.2, current provider `frontend.javaparser:3.28.2-m4uv2.2-injection-v1`. M4U.1 supplied bounded Lombok synthesis, parser-neutral type shapes and record constructor parameter evidence; V2.2 adds the bounded resolution/attribution and neutral member-shape changes described above. Historical M2 evidence remains unchanged. No general compiler-equivalence or scale claim is made.
- Progressive effective-POM/source-plan projection, bounded filesystem/dependency acquisition, explicit platform/frontend assembly, capability-gap normalization and M4A–M4D bounded Spring semantics are implemented. M4U.1 adds passive POM-less/Gradle source plans, configuration baseline ingestion and component/constructor acquisition. M4U.2 adds the bounded Spring Data, version/namespace/metadata, SAT, route, hierarchy and SpEL providers described above. V2.1 supports supplied generated sources/artifacts with lineage; V2.2 adds a bounded source-to-plan continuation. General generated-root acquisition, complete source-to-Spring-descriptor normalization, M4E external validation, graph, policy engine, metric/scoring calculation, CLI, backend API and workbench remain unimplemented.
- `frontend/` and root `tests/` have no tracked product implementation.

## EVIDENCE AND LIMITS

Historical evidence (the M2 implementation record identifies fresh checks separately):
- G0: wrapper 3.3.4, Maven 3.9.16 with pinned/checksummed distribution, Java 21 enforcement; clean Windows/Oracle 21.0.12.1 and Docker Linux/Temurin 21.0.12 builds. At M0 the empty module JARs matched; this is not a hash claim for the later M1 analyzer. Exact commands/hashes and negative checks are in [M0 evidence](reproducibility/m0-foundation.md).
- G1: 24 focused contract tests, 25 analyzer tests plus one backend test through root verification. Golden identities, full-inventory snapshot hashing, ordered classpaths, explicit target/status/uncertainty constraints and locale/timezone-independent serialization are documented in [M1 contracts](architecture/m1-contracts.md) and executable tests.
- R1 PetClinic snapshot `818c4136ea971c21674525f9053de0d9c7ad8cfe`: 30 files; A = 218 resolved / 238 unresolved out of 456 attempts; B = 456 resolved / 0 unresolved; 14 labeled cases matched expected configuration-specific outcomes. This is bounded viability, not universal semantic accuracy.
- R1 narrative still has a stale JDK 17 statement; saved provenance records Oracle 21.0.12.1. Correct the narrative when working on that report; preserve raw evidence.
- Comparative saved PetClinic CALLS results: 220 occurrences; B resolves 220 for both adapters, C resolves 215 for both. Resolution is not independently established full semantic correctness.
- Intake audit found 89 placeholder OpenRewrite spans in each saved PetClinic configuration, dropped provenance diagnostics on resolved M1 mapping, and project-local targets labeled DEPENDENCY by a shared heuristic (85 per adapter in B). These experimental defects remain unfixed; do not reuse the package as proof of provenance/origin correctness or G2 acceptance.
- Controlled generic-chain evidence and comparative limits are in [frontend comparison](research/semantic-frontend-comparison.md). Full semantic denominator, robust provenance, multi-module evidence and fair resource measurements remain incomplete.

## DECISIONS

Confirmed: Track A + B target; Java 21/Maven/monorepo; primary JavaParser/SymbolSolver choice behind SemanticFrontend; parser/storage-neutral domain; safe multi-module modeling; content-addressed analysis; stable query services; separate health/confidence; complete visual workbench; milestone scope is not an ultimate capability ceiling; progressive, provenance-preserving evidence acquisition through replaceable providers is the long-term architecture direction. See [ADR-003](decisions/ADR-003-progressive-evidence-acquisition.md).

Accepted direction and bounded multi-world evaluation delivered: [ADR-004](decisions/ADR-004-staged-conditional-architecture-semantics.md) makes bounded, phase/order-aware conditional architecture semantics the M4+ direction. M4A.1–M4A.2 provide evidence-only detection and gap normalization; M4B provides the condition IR, finite configuration-space identity and bounded exogenous evaluation; M4C provides normalized per-assignment discovery, registration and binding; M4D provides explicit truth regions and replayed witnesses. Existing `ConfigurationIdentity` remains one realized configuration; the separate implemented `ConfigurationSpaceIdentity` describes one finite modeled space inside an exact M3 build context. M5 becomes a projection of conditional facts, M6 evaluates configuration-qualified policy, and M11 compares conditional/evidence regions. M4U.2 now implements a replaceable pure-Java DPLL backend and symbolic signature execution within the approved SAT capability direction; this does not change G2/G3 status. See the [research review](research/2026-09-09-conditional-architecture-redirection-review.md) for the earlier decision context.

Provisional: Neo4j Community adapter, Spring Boot API, YAML external policies, Cytoscape.js, exact metric/score formulas and thresholds. The M3.6 capability-gap core, M3.7–M3.8 provider refinements and M4A.1–M4A.2 acquisition are implemented against their contracts; downstream query/assessment integration remains pending. The M4-R0 baseline is human-accepted; the M4U.2 solver's corpus performance and semantic coverage still require M4E evidence. ADR-005 plans passive selective metadata/generated/artifact providers and qualified evidence imports for Universal v2; V2.1 has implemented a bounded subset while broad capability and empirical acceptance remain pending. Executing controlled build/sandbox, AOT or runtime producers remains ASSESS/HOLD and needs its separate security/authorization contract. Context federation, cross-system assurance and verified AI evolution remain future horizons.

## OPEN QUESTIONS AND BLOCKERS

The initial Maven sandbox JUnit-cache denial and the final benchmark install-cache denial were resolved through the host's supported execution approval, with explicit `MAVEN_USER_HOME` / `maven.repo.local`. Those historical M3 execution denials are resolved. M4-R0 now has its separate contract/adjudication checkpoint described above.

Current input boundaries: explicit strict portable charsets; configured Java 8 `rt.jar`, Java 9+ JMOD or verified cross-release `ct.sym` platform views; exact release dependencies from a single standard Maven2 cache plus explicit credential-free HTTPS repositories; exact reactor-output JARs supplied by the caller or complete owned/decoded handwritten sibling sources at the same logical classpath position; and non-preview parser-level selection through Java 26 (bounded M4U.1 controls, separate from platform API verification). V2.1 additionally supports lineage-checked supplied generated Java under declared source roots and explicit exact artifact captures. There is no toolchain discovery, class-directory module output, arbitrary generated-root acquisition, manifest `Class-Path` expansion, version-range/relocation/snapshot resolution or transport-authenticity claim beyond HTTPS. The existing dependency reader selects multi-release JAR entries deterministically for the analyzed release, and the V2.1 importer records a corresponding release-selected class entry digest view for captured dependency JARs; JPMS/module-path semantics remain unproven. Remaining explicit degraded frontend cases include array constructor references/array length, unsupported functional target/inference contexts and broader generated overload/builder/configuration cases. V2.2 now supplies bounded annotation-value field reads, inferred `var` detail and typed lambda/catch parameter identities; see its implemented boundary and verification package. These known boundaries remain limitations of the G2 checkpoint recorded as passed; they are not a reopened G2 blocker.

Current authorized work is V2.2, with V2.1 acceptance obligations preserved, then V2.3 and M4E/G3. Historical container equivalence, hierarchy/factory/runtime behavior beyond the documented fragment, complete source-to-descriptor normalization, library resources, external Config Data, OTHER abstraction and correlated opaque/order reasoning remain implementation boundaries targeted by the new plan. Finite SAT is implemented; arbitrary infinite spaces and global latency/coverage remain unproven. Passive provider expansion, registered baselines, representative evidence and G3 adjudication are distinct stages. A new external G2 checkpoint still needs separate authorization and immutable evidence; preserved G2 runs stay unchanged. Policy criteria, metric/score thresholds, graph/query/UI budgets and frontend framework selection remain later decisions.

## QUALITY GATES

| Gate | State | Remaining acceptance boundary |
|---|---|---|
| G-1 governance baseline | PASSED (historical) | Current hardening is a separate maintenance task |
| G0 build foundation | PASSED (historical) | Preserve pinned toolchain and reproducibility |
| G1 contracts | PASSED (historical) | Preserve tested identity, uncertainty and evidence invariants |
| G2 frontend/build model | PASSED | M2 frontend and M3.1–M3.8 verified; PetClinic and ChatServer checkpoints audited and confirmed via [Independent Semantic Adjudication Record](reproducibility/g2-semantic-adjudication-record-2026-09-10.md) |
| M4-R0 research/semantics | PASSED | Concrete contract/catalog/identities, bounded oracle evidence and preregistered protocol approved by human supervisor on 2026-09-11 |
| G3 Spring | NOT STARTED | M4A–M4D and both universal provider slices are implemented within documented bounds; M4C.3 has 23 pinned runtime oracle observations. M4E expanded provider/version/corpus evidence, baseline comparison and G3 adjudication remain required |
| G4 graph/metric/query | NOT STARTED | Invariants, metric correctness, bounded storage-neutral queries |
| G5 policy/evidence/assessment | NOT STARTED | Negative/mutation controls, complete evidence, score safeguards |
| G6 Track A release | NOT STARTED | Complete visual product and multi-repository verification |
| G7 Track B | NOT STARTED | Compatible snapshots and labeled evolution events |

## EXACT NEXT TASK

**M4 Universal v2 / V2.2 — acquire inherited scalar method injection and override
selection on bounded ordinary source components.** Reuse the evidenced source
superclass closure in `SpringSourceEvidence`, `SpringInjectionSites`, `SourceToSpringPlan`
and the [implemented boundary](architecture/m4uv2-v22-implementation-boundary.md).
Use authored Framework 6.2.0 controls for annotated/unannotated overrides and private
methods; distinguish declaring points from owning components. Preserve per-owner method
groups, declaration-order parameters, qualifier precedence, requiredness, conditional
activation, closed obligations and witness replay. Resolve only evidenced override
relationships; generic/bridge/binary/incomplete cases keep typed gaps. Wider
bean-product runtime types remain explicit gaps until separately evidenced. Remaining
bean aliases, reader collisions, generic/binary return types and inferred bean-query
type metadata stay explicit V2.2 obligations alongside this next bounded slice.

**Mandatory Lean Execution for all agents:**
- Follow AGENTS.md's two-tier verification: filter a relevant method/small class during coding, then run affected unit/contract and direct integration tests across every changed module before handoff. Quote PowerShell `-D` arguments; report actual duration and selected test scope.
- Do NOT generate ceremonial `reproducibility/` folders, `input-hashes.json` (multi-thousand-line hash dumps), or raw JSON test outputs for intermediate slices.
- Prune scope aggressively: classify non-essential/fringe mechanisms (MapStruct, Protobuf, WebFlux routes, Spring Batch, SpEL internals) cleanly as `CapabilityGapRecord(UNSUPPORTED)` to avoid stalling progress toward M5 (Knowledge Graph) and M10 (Visual Workbench).

Continue the remaining A–E obligations in the [V2.2 task](tasks/m4-universal-v2/02-java-spring-semantic-closure.md).
Retain V2.1's [unfulfilled acceptance rows](research/m4-universal-v2-v21-implementation-ledger.md).
V2.3, M4E and G3 remain later steps in the approved three-task plan. No Universal v2
corpus benchmark or M4E/G3 gate assessment has been run.
