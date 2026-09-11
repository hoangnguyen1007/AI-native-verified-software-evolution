# M4-R0 Spring semantics research assessment

Date: 2026-09-11. Author roles: Lead Architect and Semantic Analyst. **ACCEPTED gate assessment: reviewed and approved by human supervisor on 2026-09-11.**

## Decision and result

The [R0 contract candidate](../architecture/m4-r0-semantics-gate.md) extends research coverage from Spring 1.x XML through Boot 3.4.x while retaining a finite, versioned implementation fragment. It proposes exact additive identity preimages, ten Spring relationship meanings, a 29-family closed catalog with an unclassified catch-all, separate property/profile envelopes, nested phase/order events, three-valued regions and reproducible witness semantics.

**CONFIRMED by this task's controlled execution:** 79/79 registered framework observations matched their pre-execution labels across selected Framework/Boot pairs 5.3.31/2.7.18, 6.1.14/3.3.5 and 6.2.0/3.4.0. These exercise property missing/empty/case/whitespace distinctions, profile sets/defaults, ordered missing/present/single-candidate conditions, name/priority selection, qualifier-versus-primary, XML alias/ref wiring and the 6.2 default-candidate boundary. Each configuration creates only authored in-memory objects, then closes the context.

**CONFIRMED by the executable formal specification:** after correcting one mislabeled ambiguity-control input, 70/70 formal cases and 64/64 Boolean CNF comparisons pass. A deliberately naive flat registration baseline disagrees on 2/4 ordered controls. This is evidence that the tested state-dependent cases need order, not a prevalence estimate or a claim against every flat-condition technique.

**CONFIRMED by passive artifact inspection:** 15,594 classfiles in exactly 26 pinned JARs contain 402 annotation declarations. Of those, 192 have no candidate family hint. All 402 still require declaration-level semantic adjudication. This inventory is complete only for those JARs; it is neither a historical annotation-use census nor proof of production semantics.

**Recommendation:** retain deterministic exhaustive evaluation as the small-space reference, and keep the ConfigurationReasoner port for later validated SAT/BDD encodings. The bounded DPLL experiment establishes satisfiability agreement on its own CNF denominator; it does not benchmark LogicNG, Java SAT performance, Spring state encoding or BDD region operations. No production backend is selected.

## Evidence and sources

The [protocol](../../benchmarks/m4-r0/PROTOCOL.md), [pre-execution runtime labels](../../benchmarks/m4-r0/runtime-labels.json), [formal cases](../../benchmarks/m4-r0/cases.json), [exact artifacts](../../benchmarks/m4-r0/artifacts.lock.json) and [verification handoff](../reproducibility/m4-r0-2026-09-11/README.md) define inputs and limitations. Source IDs below resolve through [sources.json](../../benchmarks/m4-r0/sources.json); the [acquisition lock](../../benchmarks/m4-r0/sources-acquired.lock.json) records 34 successful acquisitions, 5,086,990 bytes and three failures. Source bytes are cached locally and SHA-256 verified. URL/version labels alone are not hashes.

| Finding | Primary evidence inspected | Design consequence |
|---|---|---|
| XML already models factories, references, aliases, inheritance and processor extensions in 1.2.9 | [1.2.9 IoC manual](https://docs.spring.io/spring-framework/docs/1.2.9/reference/beans.html) (S01) | Keep XML producer and dependency roles; do not force annotation-only inputs |
| Annotation DI and mixed XML/container metadata need separate provenance | [2.5.6 IoC manual](https://docs.spring.io/spring-framework/docs/2.5.6/reference/beans.html) (S02) | Annotation presence alone does not prove activation |
| Single-constructor inference has a version boundary | [4.3.30 Autowired API](https://docs.spring.io/spring-framework/docs/4.3.30.RELEASE/javadoc-api/org/springframework/beans/factory/annotation/Autowired.html) (S04) | No retrospective rule for all older Spring versions |
| Boot 2.7 supports both discovery files and de-duplicates entries | [2.7 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.7-Release-Notes) (S05), pinned selector S24 | Model exact metadata entries, precedence and deduplication |
| Boot 3 removes only the EnableAutoConfiguration discovery key from spring.factories | [3.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide) (S06), pinned selector S25 | Other factory SPI keys still matter; imports are not new only in 3.4 |
| Config Data changes loading and activation assumptions at Boot 2.4 | [Config Data migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-Config-Data-Migration-Guide) (S08) | Distinct legacy/Config-Data adapters and precedence versions |
| Profiles and properties are environment inputs available when conditions execute | [Boot 3.4 configuration](https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html) (S09), profiles S10, pinned ConditionEvaluator S11 | Correct the previous misleading “activate after registration” phase outline |
| Bean conditions inspect state processed so far and candidate eligibility | [Boot 3.4 auto-configuration guide](https://docs.spring.io/spring-boot/3.4/reference/features/developing-auto-configuration.html), pinned OnBeanCondition S23 and annotations S27–S29 | Keep event state and exact framework semantics, not final-set presence |
| Empty property differs from missing; value matching ignores case without trimming | [OnPropertyCondition 3.4.0](https://github.com/spring-projects/spring-boot/blob/v3.4.0/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/OnPropertyCondition.java) (S22) | Separate finite domain cells; runtime controls confirm selected values |
| 6.2 changes name versus priority selection and has a non-fallback pass | [DefaultListableBeanFactory 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java) (S15), compared with S16/S17 | Selection algorithms are pinned; runtime name/priority controls distinguish versions |
| Condition phases, processor dispatch and lifecycle ordering have different consumers | Pinned ConditionEvaluator (S11), PostProcessorRegistrationDelegate (S14), DefaultLifecycleProcessor (S19) | Use nested event traces; never sort all annotations by one numeric Order |
| Meta-annotation alias semantics need declaration metadata | [AliasFor 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-core/src/main/java/org/springframework/core/annotation/AliasFor.java) (S21) | A simple-name match is only a research family hint |
| Static architectural comparators are not inherently single-context runtime analyzers | [ArchUnit guide](https://www.archunit.org/userguide/html/000_Index.html) (S32), [Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html) (S33) | Compare equivalent static rules and disclose semantic conversion loss |

The fetched wiki/reference pages may be mutable; their acquired bytes are locked, and pinned source tags plus SHA-256 support the selected algorithm claims. A SHA-256 establishes content identity, not cryptographic publisher authenticity or semantic correctness. Runtime results refer to exact JAR bytes, not to the current content of an unversioned website.

## Controlled experiments and independent-oracle boundary

The framework comparator executes actual library condition/registration/injection behavior on authored fixtures. The formal oracle is separately authored Python code over hand-translated IR. Shared author and shared conceptual labels mean this is **not independent semantic adjudication**. Runtime agreement helps discriminate label/algorithm errors but cannot establish the full extraction/provenance pipeline, all annotation mechanisms or production-container equivalence.

The formal evaluator supports supplied total orders, a single token type, complete pre-filtered DI candidates, profile atoms and a bounded ASCII property partition. Strong-Kleene truth controls include U; state uncertainty propagates conservatively. It does not compile Java/XML into IR, load Config Data, evaluate arbitrary custom conditions, enumerate partial orders or prove correlated opaque-state branching. Those are explicit gaps, not omitted successful tests.

The CNF study uses 64 deterministic formula instances: variable counts 2/4/8/12 and seeds 0–15, with explicit contradictions in one-quarter of seed classes. Exhaustive valuation provides the SAT/UNSAT expectation; a separate DPLL traversal must agree and every returned assignment is checked against the original clauses. These synthetic clause families are small and structurally narrow. Time samples are recorded separately and support no general performance comparison, backend choice or repository scale claim.

World witnesses in the small formal space carry a full baseline and changed values. The finite evaluator selects a minimum changed-value assignment among enumerated valid worlds and checks replay/subset minimality. This does not prove the proposed future Java witness identity or universal minimization for larger fragments. Empty feasible space is an invalid model; missing evidence is not logical false.

## Deviations and negative evidence

1. Initial passive Python source acquisition failed for all 37 URLs under the restricted process environment. [sources.lock.json](../../benchmarks/m4-r0/sources.lock.json) preserves those URLError outcomes. Supported host escalation then allowed a separate acquisition lock: 34 success, S34/S35/S37 still unavailable. The saved error detail is coarse (exception class); no exact HTTP/root-cause status is invented. These failures do not establish unavailable framework semantics generally.
2. The first formal run passed 65/66 labels. `binding-ambiguous` used dependency name `b` while one candidate was named `b`; the selected result `b` agrees with pinned Spring name fallback. The intended negative control was malformed. Its complete original cases and oracle are preserved in oracle-run-1. The corrected control uses `consumer`, and a separate `binding-name-fallback` case retains the named selection. No candidate algorithm change was needed for that behavior. A harmless dictionary-copy cleanup is also preserved by the old oracle source. The second run passes all 67 labels. Self-review then added explicit variable/world-budget and irrelevant-witness-dimension controls; the final specification passes 70 labels. Acquisition guards now enforce remaining-byte budgets before cache promotion, and runtime failure reasons avoid host-path-dependent text. These maintenance changes do not change any previously passing framework outcome.
3. No independent reviewer was available in this task; no delegation was requested. Author self-review is reported as such. No real-repository benchmark, root reactor build or analyzed-target lifecycle was run. No historical G2 raw result was modified.

## Capability-gap accounting

The authored study creates **452 actual `capability-gap-record-v1` records**, using the existing Java constructor and canonical serializer with a separate `evidence.spring-r0-research-gaps:1` catalog:

- 402 declaration-semantics adjudication gaps from the annotation census;
- 29 production mechanism rows not implemented in R0;
- 3 failed source acquisitions;
- 13 historical/version-validation groups; and
- 5 explicit oracle, loader, independent-review or solver-encoding limitations.

Each cites a stable provider observation and its exact payload digest, a complete authored-study snapshot, a typed subject, evidence requirement, satisfaction criterion and affected coverage output. Observations are raw TSV records with exact newline-free row payload hashes; the source-result hash covers the whole TSV file. The snapshot URI `urn:se121:authored-study:m4-r0` is an explicit local research namespace, not a purported external repository. This ledger must not be merged into a real application's analysis as if its research gaps were application facts.

M3's production enum set and normalizer remain unchanged. The formal oracle's U reasons remain in its result records and are covered by the corresponding study-level oracle/condition obligations; production occurrence-level mapping is M4A work. No unsupported phenomenon is removed to improve a score.

## Gate assessment and recommended next task

| R0 criterion | Assessment |
|---|---|
| Historical breadth and zero-omission strategy | Candidate matrix covers all requested eras; exact historical patch inventory still explicit gaps |
| Identities, relationships and acyclic preimages | Concrete candidate specified; future Java schema/golden tests and human acceptance pending |
| Closed mechanism catalog | 29 rows with explicit reasons; XML and catch-all changes require review of the frozen candidate |
| Configuration/phase/order/UNKNOWN contract | Specified with source evidence; partial-order/correlation/loader runtime work outside the executed oracle fragment |
| Exhaustive oracle and controlled fixtures | Executed bounded formal and actual-framework controls; no extractor equivalence claim |
| Solver strategies | Exhaustive/DPLL conformance and flat baseline executed; production Java solver/Spring encoding not selected |
| Baselines, corpus, budgets, kill criteria | Preregistered; real repository selection freeze and equivalent external tool experiments deferred to M4E |
| Independent adjudication / human acceptance | **REVIEW_REQUIRED**, not passed by this author |

The exact next task is an M4-R0 adjudication/acceptance review of the contract, labels, raw results and gap ledger. Accept or amend the bounded fragment, identities, XML/catch-all catalog and budgets explicitly. After that acceptance, M4A.1 may implement evidence-only mechanism inventory and normalization. G3 remains NOT STARTED; this research package does not certify comprehensive Spring support.
