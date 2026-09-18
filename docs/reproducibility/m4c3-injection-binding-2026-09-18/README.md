# M4C.3 verification and handoff — 2026-09-18

**CONFIRMED by execution:** M4C.3 normalized injection binding is implemented; **406 tests across 56 suites** pass in the final six-project reactor (root plus five modules), with **0 failures, 0 errors, 0 skipped**, exit 0. These include **52 new specification/boundary tests**. A separately invoked oracle integration test passes **23/23 differential observations** against actual Spring Framework 6.2.0, using seven SHA-256-verified JARs. The 23 observations belong to one additional JUnit test and are not included in the reactor's 406-test denominator.

See the [architecture contract](../../architecture/m4c3-injection-binding.md) for exact supported semantics and typed capability boundaries. These results establish bounded selection behavior and regression checks, not complete source acquisition, arbitrary container startup equivalence, real-repository accuracy or Gate G3.

## Work and state

| Handoff field | Result |
|---|---|
| State before | SE121 Track A+B, accepted M4-R0; M4C.2 registration implemented and behaviorally tested. User requested complete M4C.3 and explicitly omitted Git checks. |
| Work completed | Neutral descriptor/metadata/evidence/plan/result contracts; pinned definition-selection engine; closed M4A/request outcomes; optional-method grouping; generic/qualifier/priority/name/aggregate/Resource/self behavior; limits, provenance, replay and typed gaps. |
| Files changed | Seven new production files in `analyzer/src/main/java/com/evolution/analysis/spring/binding/`; two new specification test classes and one opt-in oracle IT in `analyzer/src/test/java/com/evolution/analysis/spring/condition/`; test-only `spring/BindingInventoryFixtures.java`; trusted `src/test/resources/spring-binding-oracle/Spring620Probe.java`; the M4C.3 contract and this package. |
| Existing files updated | `docs/current-state.md`, `docs/architecture/m4-spring-intelligence.md`, `conditional-architecture-semantics.md`, and the final successor reference in `m4c2-ordered-bean-registration.md`. Existing production sources, POMs and upstream identity schemas were not edited for this slice. |
| New evidence | 52 specification/boundary tests and 23 actual Spring differential observations; final 406-test reactor; source/JAR/input hashes and exact report denominators below. |
| Decisions | Routine reversible implementation of the already accepted normalized-provider architecture. Pin 6.2.0 selection rather than applying later patch semantics; preserve unsupported behavior as typed gaps. No new technology, solver, phase, graph schema or external execution provider was selected. |
| Approval/blockers | No unresolved approval or blocker within the normalized M4C.3 contract. No commit, push or Git inspection performed. |
| Review | Implementer self-review, source-backed specification checks and an independent Spring framework oracle. No separate independent reviewer participated. The oracle inputs/adapter were authored in this task, so common input-normalization errors remain a validation limit. |
| Durable state | Current state reconciled to M4C.3 delivered; stale M4C.2-untested/binding-pending text corrected. Roadmap scope and gate statuses unchanged. |
| Exact next recommended slice | M4D.1 finite-world truth-region aggregation, preserving T/F/U, errors, feasibility/non-vacuity and deterministic replay; no implicit SAT selection or G3 promotion. |

## Commands and executed denominators

Working directory: repository root. Host: Windows, Oracle JDK 21.0.12.1, pinned Apache Maven 3.9.16; see [execution.json](execution.json). Commands use the already cached Maven distribution and dependency cache. The host's unpinned default Maven is not used.

```powershell
$m4c3Maven = 'C:/Users/Admin/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'
& $m4c3Maven test -o -pl analyzer '-Dtest=InjectionBindingsTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -q
& $m4c3Maven test -o -pl analyzer '-Dtest=InjectionBindingBoundaryTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -q
& $m4c3Maven test -o -pl analyzer '-Dtest=SpringBindingOracleIT' '-Denforcer.skip=true' '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -q
& $m4c3Maven -o -B -ntp verify '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -q
```

Each development/TDD invocation selected one test class. `enforcer.skip=true` is confined to focused single-module invocations because the reactor-convergence rule expects the root reactor; no POM rule was weakened. The **one final reactor invocation ran with the enforcer enabled**, offline, and succeeded. It was incremental `verify`, not a clean build. Maven startup/compilation overhead is separate from reported JUnit runtimes; no claim is made that every complete invocation took under five seconds. No analyzed repository Maven/Gradle lifecycle or external benchmark was run.

| Final reactor module | Suites | Tests | Failures / errors / skips |
|---|---:|---:|---|
| analyzer | 32 | 254 | 0 / 0 / 0 |
| analyzer-maven | 7 | 67 | 0 / 0 / 0 |
| analyzer-filesystem | 2 | 12 | 0 / 0 / 0 |
| analyzer-javaparser | 14 | 72 | 0 / 0 / 0 |
| backend | 1 | 1 | 0 / 0 / 0 |
| **Total** | **56** | **406** | **0 / 0 / 0** |

[reactor-suites.json](reactor-suites.json) is extracted from Surefire XML reports written after the recorded reactor start, excluding the earlier opt-in IT. Individual raw Surefire text reports are preserved under `reports/`; their runtimes are those of the final reactor, or the separate oracle invocation for `SpringBindingOracleIT`. The quiet successful reactor stdout/stderr log is empty; exit status and nonzero test/report counts provide the completion evidence.

## Semantic checks

`InjectionBindingsTest` has 30 tests covering primary/non-fallback/name/priority ordering and conflicts, qualifier/default/autowire flags, required/optional absence and ambiguity, strict versus fallback generic matching, self references, Resource name/type rules, provider/lazy deferral, explicit closure, aggregate/direct collection behavior, replay, changed limits, operation errors and invalid provenance.

`InjectionBindingBoundaryTest` has 22 tests covering incomplete registration, inactive owners, aliases, hierarchy gaps, conflicting metadata, type-name versus enumeration proofs, unknown priority/name/flags, factory/proxy and unsupported descriptor obligations, comparator/tie ordering, self factory products, optional-method atomicity, malformed references, stable site/contextual candidate identities, and M4A obligations without descriptors. Its synthetic inventory helper is an explicit taxonomy-denominator control, not an annotation-extraction oracle.

The explicit `SpringBindingOracleIT` loads SHA-256-verified Spring 6.2.0 core/jcl/beans/aop/expression/context and Jakarta Annotation 2.1.1 JARs from the existing R0 cache. It compiles only the trusted authored probe with `--release 21 -proc:none`, isolates it under a platform-parent `URLClassLoader`, and calls real `DefaultListableBeanFactory` / `CommonAnnotationBeanPostProcessor`. See [oracle-observations.json](oracle-observations.json) for all 23 named outcomes and [external-inputs.json](external-inputs.json) for artifact/source hashes.

The oracle checks name-before-priority, primary-before-name, unique non-fallback, primary fallback, two primaries, priority/tie/disabled comparator, optional/non-required ambiguity, required/optional emptiness, qualifier-excluded primary, qualified nondefault and autowire-ineligible beans, all-fallback priority, aggregate membership/order/empty-required behavior, strict generic over raw primary, and three Resource paths. It compares outcomes and selected bean names; it does not compare every internal trace, generic proof acquisition, lifecycle side effect or runtime object graph.

## TDD and final inspection

The first behavior test failed on the explicit unimplemented binding engine with `UnsupportedOperationException: M4C.3 resolution not implemented`; [tdd-red.log](tdd-red.log) preserves that failure. This was a missing-feature error rather than an assertion mismatch. An earlier JUnit-cache sandbox failure was environmental and is not counted as semantic red evidence. The final implementation removes the stub. Development also exposed and repaired canonical coverage serialization, refined empty-registry fixture closure, and fixed an oracle-adapter null-unboxing error before the final passing runs.

Final inspection covered all seven production files, tests/probe, contract/status edits, typed gaps, identities, absence of production I/O/reflection, and the exact report denominator. Git commands were omitted as requested; no Git-diff claim is made. [verify.py](verify.py) checks the closed package manifest and, with `--workspace`, the recorded build/test input inventory. Package text uses explicit LF-canonical identity; raw reports/logs use byte identity. Existing historical R0 evidence was not regenerated or edited.

```powershell
python docs/reproducibility/m4c3-injection-binding-2026-09-18/verify.py
python docs/reproducibility/m4c3-injection-binding-2026-09-18/verify.py --workspace
```

Workspace verification compares the explicit five modules' `src/` trees, module/root POMs and Maven wrapper properties to the checkpoint. A later legitimate code change can fail that optional comparison without invalidating the saved package. Default verification does not need Spring caches, Maven, Java, network access or target execution. The SHA-256 manifest detects changed payloads; it is not a signature or proof of independent adjudication.

## Limits carried forward

Complete source-to-descriptor/qualifier/type acquisition is still a replaceable evidence-provider obligation. Parent contexts, special resolvable dependencies, arbitrary processors/resolvers, constructor overload choice, FactoryBean/scoped/AOP products, generated members, Value/SpEL/JNDI and unsupported historical/patch versions retain gaps. A selected definition is not a successful instantiated object. Multi-world facts and witnesses are M4D; comprehensive empirical adjudication remains M4E/G3. No universal coverage or safety guarantee is inferred from the passing authored controls.
