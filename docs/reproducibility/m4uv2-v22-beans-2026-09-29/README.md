# V2.2 direct bean producers — verification, 2026-09-29

Scope: the exact next slice recorded at clean base `ecb2301` (`done half V2.2`):
source-to-M4C acquisition of bean producers and constructor/bean-parameter
dependencies under evidenced scheduling. The owner requested completion of this
slice and reiterated correctness, robustness and consistency. This package does
not close V2.2, V2.1 acceptance, the 99% cohort target, V2.3 or M4E/G3.

## Implementation and controls

Production changes are confined to neutral `spring.universal`:
`BeanMethodIngestion.java`, `SourceToSpringPlan.java` and `SpringInjectionSites.java`.
`SourceToSpringPlanTest.java` exercises real frontend/acquisition/pipeline inputs;
it does not supply final candidate definitions, dependency descriptors or matches.
The [implemented boundary](../../architecture/m4uv2-v22-implementation-boundary.md)
defines the supported fragment and remaining obligations.

| Contract | Source-driven evidence |
|---|---|
| Producer identity and metadata | Distinct methods returning the same type; exact single names, Java identifier-ignorable characters in default names, primary and candidate flags, static/instance factory ownership and declaration source evidence |
| Actual binding | Constructor consumes a produced bean; factory parameters belong to their product; correct/wrong qualifiers; class profile activation |
| Registration phase/order | Parent gates, complete schedule validation, missing order, named missing-bean predicates and reversed registration prefix |
| Closed degraded outcomes | Unsupported metadata/proxy configuration, missing return type, aliases, producer name collisions, project annotation impostor, unproved runtime parameter names |
| Selection integrity | Duplicate primary remains an explicit conflict; no fabricated selected fact; names unavailable from bytecode remain UNKNOWN |
| Conditional output | Exhaustive/SAT classification agreement, deterministic replay, valid witness replays and retained acquisition/evaluation gaps |
| Framework oracle | Fixed authored Framework 6.2.0 / Boot 3.4.0 classes: active/inactive injection, candidate flags, duplicate primary and reversed named missing-bean order |

Only trusted repository-authored fixture classes run in the container. Analyzed
source, build scripts, processors and application lifecycles are never executed.
The oracle and analyzer use separate authored representations; the expected
selection/presence is checked against the actual container. This is bounded
implementation evidence, not historical-version or arbitrary-startup equivalence.

Relevant primary-source checks were the pinned
[Spring bean-definition reader](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.0/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassBeanDefinitionReader.java)
and [qualifier resolver](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/annotation/QualifierAnnotationAutowireCandidateResolver.java).
Reader-specific name collisions and alias ordering remain open; the implementation
does not replace them with generic override behavior.

## Commands and evidence

Focused development (one test class per invocation):

```powershell
.\mvnw.cmd --version
.\mvnw.cmd -q -pl analyzer '-Denforcer.skip=true' '-DskipTests' install
.\mvnw.cmd -q -pl analyzer-javaparser '-Denforcer.skip=true' '-Dtest=SourceToSpringPlanTest' test
```

The analyzer install refreshes the focused adapter classpath and is not a test
pass. Enforcer is skipped only for single-module runs because reactor convergence
requires the root reactor; final root verification enables it.

Observed RED controls used the same focused command with these method selectors:

- `SourceToSpringPlanTest#beanMethodsProduceConditionalBindingsFromActualSource`:
  rejected method entries because the old order contract only knew components.
- `SourceToSpringPlanTest#producerNameCollisionCannotInventReaderOverrideBehavior`:
  duplicate enumeration names threw instead of retaining qualified producers.
- `SourceToSpringPlanTest#unprovedRuntimeParameterNameRemainsUnknownForMultipleFactoryCandidates`:
  expected UNKNOWN but received ABSENT for unproved runtime metadata.
- `SourceToSpringPlanTest#defaultBeanNameUsesJavaIdentifierEqualityRatherThanRawSpelling`:
  the default name incorrectly retained U+200B, which Java ignores in identifiers.
  The regression uses both actual frontend input and a separately compiled
  fixed container fixture with that identifier.

A test accessor typo caused a separate compilation failure and was corrected;
it is not semantic RED evidence. Initial focused execution passed **17 tests**
(12 added), with zero failures, errors or skips. See the generated
[initial focused result](focused-verification.json). That suite took 120.887 seconds;
the local <5-second iteration target was not met. Invocations stayed isolated,
quiet, and compact in reported output; no repository benchmark was run.

The [capture script](capture-verification.ps1) runs root `verify -q` by default and
captures hashes before/after, fresh Surefire counts and selected report digests.
It refuses existing output directories to protect prior evidence. Replay into
a new directory from the repository root:

```powershell
& ./docs/reproducibility/m4uv2-v22-beans-2026-09-29/capture-verification.ps1 `
  -OutputDirectory './docs/reproducibility/m4uv2-v22-beans-2026-09-29/run-new'
```

The one root invocation used `run-1`. See its generated
[verification record](run-1/verification.json) and [input hashes](run-1/input-hashes.json).
It passed **609 tests / 88 fresh suites**, zero failures/errors/skips, with Enforcer
enabled and all 351 recorded inputs unchanged during execution. The initial
PowerShell recorder kept correct per-suite counts but emitted null aggregate
counts for ordered dictionaries. The recorder is corrected; the original record
is preserved, and [summary.json](run-1/summary.json) is reproducibly derived by
[summarize-verification.ps1](summarize-verification.ps1) from its original suite rows.
Hashes cover reactor source/resources and POM/wrapper inputs. Raw Surefire XML
is not copied because it contains host properties; only selected counts and
digests are retained. Ignored logs remain local. The separate
[semantic artifact hashes](semantic-artifact-hashes.json) were generated by
selecting Spring JAR paths from the actual focused Surefire `java.class.path`
and hashing their bytes in classpath order. This is artifact identity evidence,
not a declaration of validated support for every library on that classpath.

Toolchain observed through the wrapper: Maven 3.9.16, Oracle JDK 21.0.12.1,
Windows amd64, UTF-8.

The later Unicode regression failed after this root checkpoint. The correction
only strips Java identifier-ignorable code points from method-derived default
bean names, consistent with [JLS 21 §3.8](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html#jls-3.8);
explicit annotation string names remain literal. The full affected
source-to-Spring integration class is rerun against the corrected analyzer using:

```powershell
& ./docs/reproducibility/m4uv2-v22-beans-2026-09-29/capture-verification.ps1 `
  -OutputDirectory './docs/reproducibility/m4uv2-v22-beans-2026-09-29/run-2-focused' -Focused
```

Its [final focused record](run-2-focused/verification.json) and
[final input hashes](run-2-focused/input-hashes.json) identify the corrected state.
**CONFIRMED final affected-state verification:** 18 tests passed (13 additions),
zero failures/errors/skips, exit 0, and all 351 recorded inputs remained unchanged.
This reran every control in the affected integration class, including the pinned
container and exhaustive/SAT controls. Only `BeanMethodIngestion.java` and its
integration test changed after the root checkpoint.
The earlier 609-test root result is a checkpoint before that final correction;
no second reactor or clean-build claim is made.

## Handoff

Implementation self-review and independent framework oracles were used; no
independent agent review was performed. No commit, push, target execution or
external corpus campaign was performed. Existing historical evidence is unchanged.

Exact next slice: source-to-M4C scalar field/method injection acquisition,
including required/optional method groups, with source-driven conditional binding
and pinned-container controls. Preserve runtime-name uncertainty and all current
scope/order/provenance contracts. Bean alias/reader-collision/type-prediction
extensions and the remaining V2.2 A–E obligations stay open.
