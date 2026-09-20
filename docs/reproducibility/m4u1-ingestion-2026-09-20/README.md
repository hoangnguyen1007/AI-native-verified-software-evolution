# M4-UNIVERSAL Slice 1 verification — 2026-09-20

**CONFIRMED by this execution:** the current root reactor passes **472 tests in
67 suites**, with **0 failures, 0 errors, 0 skips**. Every JUnit report was written
after the invocation started. Maven Enforcer was enabled. The run took about
81 seconds on Windows 11 / Oracle JDK 21.0.12.1 / Maven 3.9.16.

The implementation is described in the [M4U.1 contract](../../architecture/m4u1-passive-ingestion.md).
It connects passive Maven/Gradle/plain-Java plans, exact source assembly,
Java/Lombok frontend evidence, configuration baselines, component acquisition and
constructor injection sites. It preserves incomplete obligations and never runs
analyzed repositories. The global 98–99% coverage target remains unmeasured;
M4E and G3 are not passed by this package.

## Final command and evidence

```powershell
.\mvnw.cmd -B -ntp verify -q
```

Exit code: **0**. An earlier full-reactor run passed 471 tests. Final self-review
then found an unreported unsupported Lombok generator/target case; a regression
test first failed, the gap handling was repaired, and the affected suite passed.
The reactor was rerun to verify that material change, producing the final 472
passing tests. The earlier execution, summary and input hashes are preserved as
`execution-before-audit-fix.json`, `verification-before-audit-fix.json` and
`source-hashes-before-audit-fix.json`. Both runs used `verify`, not `clean verify`;
no clean-build claim is made.
Quiet stdout/stderr was empty. The authoritative test evidence is the fresh
Surefire XML set, summarized with each report's SHA-256 in
[verification.json](verification.json). [execution.json](execution.json) records
the command, timestamps and exit code. [toolchain.txt](toolchain.txt) records the
fresh wrapper/toolchain output. [source-hashes.json](source-hashes.json) identifies
the affected implementation/test inputs that were verified.

| Module | Fresh suites | Tests |
|---|---:|---:|
| analyzer | 35 | 297 |
| analyzer-maven | 8 | 68 |
| analyzer-filesystem | 2 | 12 |
| analyzer-javaparser | 21 | 94 |
| backend | 1 | 1 |
| Total | 67 | 472 |

Report paths are repository-relative. JUnit report hashes identify the local
execution artifacts; their timings and JVM properties are not portable semantic
identities. Historical totals in earlier milestone packages describe their own
executions and were not used to calculate this result.

## Focused verification and TDD

All ten new suites ran again in the final reactor. They contain **43 new tests**:

| Suite | Tests | Main obligations |
|---|---:|---|
| `UniversalBuildIngestionTest` | 8 | Gradle DSLs, literal modules/dependencies, plain Java, additive roots, compiler provenance, malicious/dynamic inputs and bounds |
| `UniversalMavenIngestionTest` | 1 | Existing Maven inheritance, module/BOM/dependency-management and source-plan evidence through the neutral router |
| `ConfigDataIngestionTest` | 12 | YAML/properties/profiles, precedence, lists/groups/defaults, activation, placeholders, separators/continuation, missing/unsafe/limited input and replay |
| `LombokIngestionTest` | 6 | Six generators, constructor rules, source types/provenance, explicit members, impostors, unresolved types and unsupported generator targets |
| `LombokSynthesisOracleTest` | 1 | Constructor and method identity sets against real pinned Lombok compilation on seven controlled types |
| `ModernLanguageIngestionTest` | 2 | Records/sealed types at exact levels 17 and 21–26, a Java 25 constructor prologue, old-level rejection and unknown future-level rejection |
| `ComponentIngestionTest` | 8 | All six stereotypes, default/literal/class-marker roots, package boundaries, composed metadata, independent nested types, names and unsupported filters |
| `ConstructorInjectionIngestionTest` | 2 | Explicit/Lombok/record ordered parameter sites, replay, overloaded or incomplete constructor sets |
| `ConfigDataOracleTest` | 1 | Nine authored properties/YAML document controls against Boot 3.4.0's real loaders |
| `UniversalIngestionPipelineTest` | 2 | Unified plain/Gradle and Spring/Lombok/record flows, identity replay, exact-input withholding and sanitized provider rejection |

The existing `SpringMechanismM4A2IntegrationTest` also ran in isolation. Its authored
API JAR contains a Lombok annotation stub; the test now requires `PARTIAL` and the
specific `lombok.artifact-unverified` diagnostic. It retains the original mechanism
and gap assertions rather than treating a lookalike JAR as verified Lombok.

Focused command form (one class per invocation):

```powershell
.\mvnw.cmd test -pl analyzer '-Dtest=ConfigDataIngestionTest' '-Denforcer.skip=true' -q
.\mvnw.cmd test -pl analyzer-javaparser '-Dtest=ComponentIngestionTest' '-Denforcer.skip=true' -q
.\mvnw.cmd test -pl analyzer-maven '-Dtest=UniversalMavenIngestionTest' '-Denforcer.skip=true' -q
```

`ReactorModuleConvergence` rejects isolated child-module invocations because the
parent is absent from that selected reactor. Enforcer was skipped only for the
focused development runs; the final full-reactor run passed with it enabled.
Local installation of the analyzer module refreshed the adapter's development
dependency after neutral API changes. No external repository build was involved.
Some compile-plus-test invocations exceeded five seconds; no claim that every
development iteration met a five-second wall-clock budget is made.

Observed red-to-green controls included missing config assignment, absent Gradle
dependency rows, incorrect default Lombok constructors, absent component roots,
properties continuation/separator confusion, retained lower-priority profile-list
elements, replacement instead of addition of Gradle roots, lost project limits,
body-text-based type eligibility, unknown composed annotations, missing resolved
class-marker roots, missing `RestController` artifact recognition, missing
Lombok `canEqual`, missing pipeline/constructor outputs, and unreported method/
constructor `Builder` or `SuperBuilder` obligations. Final assertions
cover both supported results and explicit unresolved outcomes.

## Independent semantic controls and limits

The Lombok oracle invokes the JDK compiler with the exact **1.18.46** processor
only for fixed, authored test strings. It loads the emitted classes without
initialization and compares constructor/method signature sets, including the
default builder type. It does not execute an analyzed repository or establish
generated-body, generic-builder, alternate-version or Lombok-configuration parity.

The configuration oracle compares passive document loading with real Boot
**3.4.0** properties/YAML loaders, including multi-document delimiters,
continuation, encoding, indexed values, null/empty values and bracketed keys.
It is not a full application-startup, Config Data import or relaxed-binding oracle.
The component/constructor controls use exact Framework **6.2.0** / Boot **3.4.0**
artifact evidence. Older catalog tuples do not gain new empirical certification
from these controls.

No representative external-repository benchmark, independent agent code review,
cross-OS build or global coverage measurement was performed. Implementation
self-review and independent compiler/library comparisons are distinct forms of
evidence. Git was not inspected, and nothing was committed or pushed, as directed.

## Handoff

The [current state](../../current-state.md) records the delivered bounded
interfaces and **M4-UNIVERSAL Slice 2** as the exact next implementation task:
Spring Data synthesis, generation/namespace-aware registration, a pure-Java
reasoning backend and web/context enrichment. Consume the new ingestion evidence
without turning acquisition gaps into absent beans or unconditional architecture
facts. M4E must establish the corpus denominator and measure the remaining
provider boundaries before any 98% coverage or G3 acceptance claim.
