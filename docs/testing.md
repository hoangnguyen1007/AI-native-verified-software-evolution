# Platform test selection

The Maven Wrapper pins Maven 3.9.16; use JDK 21. These commands build/test trusted platform code. Native tests replay captured evidence; they never run a target lifecycle on the host or launch a worker to repair missing fixtures.

## Select the intended scope

| Command selection | Included | Excluded / not established |
|---|---|---|
| `test` or `verify`, without a test profile | All self-contained tests, including Spring integration tests | `native-campaign`; native acceptance is **NOT RUN** by this command |
| `-Pfast-tests` | Self-contained tests except those tagged `integration` | Spring integration suites and native campaign; insufficient alone for handoff |
| `-Pnative-campaign` | Only tests tagged `native-campaign`, on explicitly supplied captured inputs | Other unit/integration tests; insufficient alone for handoff |

The profiles are mutually exclusive. Missing native inputs fail in Maven `validate`, rather than producing skipped tests. The five native suites have no property-based JUnit disable conditions. A zero-test selection fails, including an explicitly selected native class without its profile. A green build proves only the selected scope; tag exclusions do not mean those tests passed. In an IDE, exclude the `native-campaign` tag for the self-contained selection; selecting native tests directly requires the same captured inputs and trusted fixture classpath.

The two expensive self-contained suites, `SourceToSpringPlanTest` (80 cases) and `ResourceFieldIngestionTest` (12), carry `integration`. They **still run by default**. `fast-tests` is a convenience selection, not a promise that every remaining class or Maven invocation finishes within five seconds. For the inner TDD loop, select one relevant method; the full bounded affected-module set belongs before handoff.

The JMOD adapter also avoids recompressing its temporary solver JAR: it uses compression level 0. Original artifact SHA256 verification, class bytes, duplicate checks, sorted entries and timestamps remain unchanged. This removes compression work without sharing mutable solver state or introducing a cross-analysis cache. The view is built in memory; JavaParser 3.28.2's `JarTypeSolver(InputStream)` also materializes it as a temporary file with `deleteOnExit`. The larger ZIP representation can therefore consume more memory **and temporary disk**, until the provider process exits. The captured native campaign uses an explicit 1,536 MiB test heap. This is not a general performance or resource-capacity certificate.

```powershell
# Inner loop: one relevant method, including a method in an integration class.
# Replace ClassName#methodName with the actual affected test; do not add fast-tests
# when selecting a method whose integration tag that profile excludes.
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=ClassName#methodName" -q

# Broader local iteration; this does not establish native/Spring acceptance.
.\mvnw.cmd test -pl analyzer-javaparser -Pfast-tests -q

# Before handoff: bounded affected self-contained classes, no fast-tests profile.
.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=SourceToSpringPlanTest,ResourceFieldIngestionTest" -q
```

Use `-Denforcer.skipRules=reactorModuleConvergence` for an intentional module-only invocation with current platform dependencies already installed; this leaves the other enforcement rules active. Do not use a full reactor merely to time a TDD method. `-Dplatform.build.directory=target/<name>` isolates outputs when an IDE writes the default `target/` directory.

## Captured native campaign

Prepare four explicit absolute paths, using forward slashes on Windows:

| Property | Required evidence |
|---|---|
| `native.fixture.root` | Original pipeline cohort: `native-context-check-v2.json`, `native-wiring-inputs-v1.json`, complete immutable CAS |
| `native.failure.root` | Actual failed-processor capture: `native-byte-import.json`, `native-input-import.json`, complete immutable CAS |
| `native.spring.root` | Native Spring cohort: context check, input binding and complete immutable CAS; exact framework artifacts |
| `native.coremvp.root` | Current operator-registered Core MVP publication: `setup.json`, `export-request.json`, publication CAS and matching private registration/controller identity |

File existence is a preflight check, not authenticity. The real readers still verify bytes, hashes, scope, lineage, registration and version identity. Merely having `.evo/executor/` is insufficient: it may contain multiple, failed or stale captures. No newest-directory heuristic, network acquisition or target-build fallback is used. CI needs provisioned matching evidence to claim this campaign; CI without it can run self-contained tests and must report native acceptance as NOT RUN.

With `$pipeline`, `$failure`, `$spring` and `$coreMvp` set to those absolute paths:

```powershell
.\mvnw.cmd test -pl analyzer-execution,analyzer-javaparser,backend -Pnative-campaign `
  "-Dnative.fixture.root=$pipeline" "-Dnative.failure.root=$failure" `
  "-Dnative.spring.root=$spring" "-Dnative.coremvp.root=$coreMvp" `
  "-Dplatform.build.directory=target/native-campaign" "-DargLine=-Xmx1536m" `
  "-Denforcer.skipRules=reactorModuleConvergence"
```

Run the three selected modules in one invocation, with current platform dependencies installed. Reactor order compiles the JavaParser test fixture before backend uses it. The backend profile binds `native.fixture.classes` to that invocation's JavaParser test output automatically; no production artifact includes those test classes. For an absolute/external `platform.build.directory`, override `native.fixture.classes` explicitly with the corresponding trusted JavaParser test-classes directory.

The current complete selection is **17 tests / 5 suites**:

- `NativePreparedPipelineIntegrationTest`: 10.
- `NativeSemanticProjectionTest`: 2.
- `NativeSpringContinuationTest`: 3.
- `PreparedJavaAnalysisRunnerTest`: 1.
- `NativeCoreMvpEvidenceReaderTest`: 1.

Check the current invocation's Surefire reports in each selected module's build directory: all five suites, 17 executed, zero failures/errors/skips. Use a dedicated output directory to distinguish this campaign from historical reports; on a reused directory check report timestamps against this invocation. `-Dtest`, `-Dtest.groups`, `-Dtest.excludedGroups` or other selection overrides change the evidence and must not be reported as the complete campaign without checking that denominator. When adding/removing native cases, update this denominator. Native replay under the public test policy still does not establish production semantic qualification or unrestricted executor certification.

The original Prompt 1 closeout used explicit fixture properties and recorded zero skips; it remains historical bounded evidence. Its historical commands predate these profiles. For current execution, use the selections above. The [execution record](tasks/m8/04-step4-phase2-execution.md) records both the closeout and this QC follow-up.

## Basis

JUnit 5.11 distinguishes [tag filtering from conditional disabling](https://junit.org/junit5/docs/5.11.0/user-guide/index.html#writing-tests-tagging-and-filtering). Surefire 3.5.5 supports [JUnit tag expressions through `groups` / `excludedGroups`](https://maven.apache.org/surefire-archives/surefire-3.5.5/maven-surefire-plugin/examples/junit-platform.html#filtering-by-tags). Native preflight uses Maven Enforcer's [required-file rule](https://maven.apache.org/enforcer/enforcer-rules/requireFilesExist.html); the native readers provide the authenticity checks. Java 21 defines [`Deflater.NO_COMPRESSION`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Deflater.html#NO_COMPRESSION) for the adapter's temporary ZIP representation.
