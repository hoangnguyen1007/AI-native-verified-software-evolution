# M4 Universal v2 — evidence review and design rationale

Date: 2026-09-23. Question: what implementation strategy materially reduces missing architectural evidence across difficult Java/Spring repositories without trading correctness for a lower UNKNOWN count?

## Method and limits

Read repository contracts, current source entry points and current official documentation. No new external repository run, target build, performance test or market comparison was executed. Observations below distinguish source inspection from proposed designs. Online references are documentation consulted on this date; implementation packs must pin exact versions/source revisions and artifact digests before semantic promotion.

## Repository evidence

| Verified observation | Evidence | Design implication |
|---|---|---|
| Source assembly needs caller-supplied exact resolution and has a global build-closure flag | [UniversalSourceIngestion](../../analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalSourceIngestion.java) | Acquire prerequisites and prove affected scopes; do not simply remove guards |
| Pipeline isolates ordinary per-unit exceptions, retains frontend/component evidence, then continues | [UniversalIngestionPipeline](../../analyzer/src/main/java/com/evolution/analysis/ingestion/UniversalIngestionPipeline.java) | Build on this behavior with stage checkpoints and worker supervision |
| Ledger already distinguishes attempts, conflicts and narrowed/satisfied resolutions | [Evidence contract](../architecture/evidence-acquisition.md) | Reuse the schema; add orchestration, not a second gap ontology |
| Generated-call resolution, dependency resources and complete descriptors remain obligations | [Slice 1](../architecture/m4u1-passive-ingestion.md), [Slice 2](../architecture/m4u2-universal-spring.md) | Address acquisition and normalization before claiming complete Spring architecture |
| Historical 504-test checkpoint and 98–99% target are separate | [Current state](../current-state.md) | Passing tests does not measure worldwide coverage |

## Primary external evidence

1. Gradle distinguishes a dependency catalog from actual selected versions; constraints/platforms can affect resolution. A catalog is useful declaration evidence, not an exact resolved classpath. [Gradle catalogs and platforms](https://docs.gradle.org/current/userguide/centralizing_catalog_platform.html).
2. Gradle locking captures resolved dependency versions. Imported locks help stabilize acquisition, but v2 must still establish selected variant, configuration, artifact bytes and classpath order. The second sentence is a design inference, not a promise made by the Gradle documentation. [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html).
3. CodeQL supports Java analysis without a full build. Its documentation describes querying Maven/Gradle for dependencies and accuracy boundaries involving generated sources, dependencies, multiple versions and JDKs. We must compare input modes explicitly, not claim competitors cannot analyze unbuilt Java. [CodeQL Java build options](https://docs.github.com/en/code-security/reference/code-scanning/codeql/build-options-for-compiled-languages#no-build-for-java).
4. SonarQube documents compiled class requirements for Java projects with multiple files. This is an input-model difference to register in a benchmark, not evidence of poorer architecture or security accuracy. [SonarQube Java analysis](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/languages/java).
5. ArchUnit imports class structures and can resolve missing classes from the classpath or use configured alternatives. Missing-dependency policy and input bytes affect a fair comparison. [ArchUnit missing classes](https://www.archunit.org/userguide/html/000_Index.html#_dealing_with_missing_classes).
6. Spring Boot AOT preparation fixes aspects of bean configuration and has limitations around profile/property-driven changes. Imported AOT output must therefore retain its build/configuration context; it cannot prove all possible runtime configurations. [Spring Boot AOT](https://docs.spring.io/spring-boot/reference/packaging/aot.html).

## Alternatives

**Baseline:** current v1 with the same supplied inputs. **Recommended design:** progressive passive evidence acquisition plus automatic source-to-framework normalization, dependency-local recovery and deterministic reconciliation. **Strongest more invasive alternative:** execution-backed build/runtime collection, which remains outside this task's execution authority; an externally produced bundle can still be imported as qualified evidence.

**HYPOTHESIS H1:** missing exact build/generated/library evidence explains a substantial, measurable share of avoidable unresolved architecture facts. Test by paired input ablation, recording the same occurrence denominator before/after each evidence class.

**HYPOTHESIS H2:** closing source-to-M4C normalization yields more correct architecture answers than only adding syntactic annotations. Test complete route/component/conditional-binding journeys against independent fixtures and labels.

**HYPOTHESIS H3:** scoped recovery and one coordinator increase useful repository completion without increasing false certainty. Test sibling failures, unknown-effect propagation, crash/restart and adversarial partial inputs.

**HYPOTHESIS H4:** version packs reduce hash-whitelist-driven rejection while preserving framework correctness. Test exact positive tuples, neighboring patch controls and incompatible-version negatives; no same-major generalization by default.

## What the evidence does not establish

No claim of market superiority, prevalence of a mechanism, universal no-crash behavior, zero uncertainty, arbitrary-language semantics or constant-time SAT is established. Missing private code/configuration and unconstrained runtime computation can remain unanswerable with available evidence. The engineering obligation is to try justified permitted providers, preserve verified partial architecture and measure the residual causes.

The [evaluation protocol](m4-universal-v2-evaluation.md) registers how to test these hypotheses. The [architecture contract](../architecture/m4-universal-v2.md) and [ADR-005](../decisions/ADR-005-m4-universal-v2.md) translate them into a reversible implementation plan.
