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

## Python worker/SDK controls

Install the host test dependencies from the checked-in [requirements file](../analyzer-execution/src/test/python/requirements.txt) into an explicit virtual environment. From the repository root in PowerShell:

```powershell
python -m venv .venv
$workerPython = (Resolve-Path '.venv/Scripts/python.exe').Path
& $workerPython -m pip install -r analyzer-execution/src/test/python/requirements.txt
& $workerPython -m unittest discover -s analyzer-execution/src/test/python -p 'test_polyglot_sdk*.py' -q
```

On POSIX use `.venv/bin/python`. This setup gives the tests `pycdlib`, `cryptography`, `paramiko` and `zstandard` through the selected interpreter; no machine-specific cache path is required. For an already provisioned `pip --target` directory, set `PYTHONPATH` explicitly to that directory instead. Tests do not discover or inject another user's cache into `sys.path`.

These controls do not provision a VM or qualify a native language pack. The separate `test_*_native.py` suites require their real pinned SDKs. In particular, `test_python_native.py` needs Node on the host PATH and Pyright 1.1.396 at `.evo/tools/m845-pyright-api-1.1.396/package`; missing prerequisites fail with a named error. They analyze fixed first-party fixture strings only. Analyzed repository code/builds/index requests still run exclusively in the registered isolated lane.

## Frontend architecture MVP

This is the explicit M8.4 Step-5/Task-2 captured-context lane, separate from the campaign below. [The execution record](tasks/m8/04-step5-step2-execution.md) owns actual passes/remaining exits; [the contract](architecture/m8-frontend-architecture.md) describes requests and query semantics. Source → capture → `ArchitectureCapture.read` → `ArchitectureCaptureQuery.page` is usable; automatic whole-repository HTTP/UI/federation is not claimed by this lane.

Authored host controls require Node **24.22.1 on Windows** and provisioned exact SDK bytes at `.evo/tools/m845-architecture`; routing controls additionally require `.evo/tools/m845-native-routing-v2`. The [base pin](../analyzer-execution/src/main/resources/polyglot/architecture/toolchain.json) and [routing pin](../analyzer-execution/src/main/resources/polyglot/architecture/routing-toolchain.json), with adjacent lockfiles, define runtime/lock/installed-tree identities. Vue declaration controls also require their checked-in fixture pin and matching external SDK. Full SFC controls additionally use `.evo/tools/m845-vue-language-3.3.13`; native Nuxt controls use the compact `.evo/tools/m845-nuxt-schema-v1` closure. Their adjacent `vue-toolchain.json` and `nuxt-toolchain.json` pin exact bytes. The Nuxt closure preserves 16 resolved packages from the verified existing SDK (3,377,526 bytes / 222 files), rather than transferring the unrelated 279-MB tool tree. Use the verified operator bundles; an arbitrary fresh npm installation/OS may have a different tree and is not interchangeable. SDKs, package caches and raw captures stay outside Git. Missing/changed prerequisites fail explicitly; there is no host target-build fallback.

```powershell
# One affected data-only/native-parser selection on fixed platform-authored inputs.
node --test "--test-name-pattern=literal template fragments|native source-map chains|Vue native SFC/script/template compilation|invalid checker context cannot|mutually exclusive browser and server" analyzer-execution/src/test/resources/polyglot/architecture-worker.test.cjs
python -m unittest discover -s analyzer-execution/src/test/python -p test_architecture_guest.py -q

# Reader unit checks. No SDK or VM is needed for these immutable compact fixtures.
.\mvnw.cmd -q -pl analyzer "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=ArchitectureCaptureTest,ArchitectureCaptureAuditTest" test
```

`ArchitectureNativeCaptureIT` is selected explicitly, with **both** `architecture.captureRoot` and `architecture.labels` pointing to a completed immutable worker root and its precommitted independent labels. It verifies output census, source bytes (including binary), frozen controls, VM Off identity, CAS import, bounded navigation and cold reopen. If the sealed label file registers `occurrences`, it also checks the independently source-bound predicates, complete outcome partition, audit paging/cold replay and the declared determinism pair. Predicate and label counts are separate from JUnit counts. It does not start a worker or obtain missing SDKs. The exact two-module command and current receipt are in the execution record. Reuse captures for data-only consumer changes; a new VM is needed only for changed native behavior that existing evidence cannot establish. Native guest Node is separately pinned **22.23.4 on Linux**. `qualified=false` and method gaps remain expected and must not be removed to make a check green.

`ArchitectureNativeReplayIT` is a separate, explicitly selected recovery check. Its required properties are `architecture.replayRoot`, `architecture.replayLabels` and `architecture.replayedOutput`. It admits reproduced fixed-authored bytes only when their SHA-256 **and length** equal both authenticated VM-reported native outputs, checks frozen controls/Off/input binding, and runs the independent label/audit/CAS checks. It rejects changed bytes and reports `INCOMPLETE_NATIVE_DIGEST_MATCHED_REPLAY`; it cannot certify or replace the original full output census. The execution record identifies the one recovered fixture and its immutable producer revision. Ordinary analyzed repositories still have no host execution fallback.

Temporary SDK uploads are removed from the guest work tree only after the installed root-owned destination hash is verified. The installed input and original host cache remain; a hash failure keeps staging. This avoids re-exporting temporary tool transport data. The corresponding full VM export is a separate check from semantic replay.

Worker setup reuses existing hash-frozen SDK archives and streams the required `package/` envelope/expansion preflight before creating a VM. The current 1-GiB authored scratch class admits `expanded SDK bytes + 2 × archive bytes + 3 × captured source bytes + 256 MiB` for OS/metadata/output headroom. This planning allowance does not replace the live overlay supervisor or certify arbitrary workload capacity; a larger tool bundle requires an appropriate registered profile/media strategy, not a smaller source denominator. A stopped scoped supervisor aborts management waiting instead of spending the remaining SSH watchdog interval.

Pass `--evidence-root <existing private-cache parent>` to `sdk_worker.py register` to create an owner/System-only capture child on D:. Its path is pinned in setup; SDKs and raw captures stay outside Git. Z: contains only necessary registered worker state. Never repeat an unchanged environmental failure or start all language profiles for a frontend slice. A data-only replay needs neither a VM nor Z: writable space.

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

The current complete selection is **22 tests / 5 suites**:

- `NativePreparedPipelineIntegrationTest`: 10.
- `NativeSemanticProjectionTest`: 2.
- `NativeSpringContinuationTest`: 3.
- `PreparedJavaAnalysisRunnerTest`: 6 (one existing registered runner, four parameterized fresh-JVM captures and the dedicated custom readiness/R03 product control).
- `NativeCoreMvpEvidenceReaderTest`: 1.

Check the current invocation's Surefire reports in each selected module's build directory: all five suites, 22 executed, zero failures/errors/skips. Use a dedicated output directory to distinguish this campaign from historical reports; on a reused directory check report timestamps against this invocation. `-Dtest`, `-Dtest.groups`, `-Dtest.excludedGroups` or other selection overrides change the evidence and must not be reported as the complete campaign without checking that denominator. When adding/removing native cases, update this denominator. Native replay under the public test policy still does not establish production semantic qualification or unrestricted executor certification.

The original Prompt 1 closeout used explicit fixture properties and recorded zero skips; it remains historical bounded evidence. Its historical commands predate these profiles. For current execution, use the selections above. The [execution record](tasks/m8/04-step4-phase2-execution.md) records both the closeout and this QC follow-up.

## M8.4 Step 5 / Bước 3 — bounded bridge checks

The [Step-3 execution record](tasks/m8/04-step5-step3-execution.md#verification) owns the exact selected Maven commands, counts and retained fixture paths. Use `target/step5-bridges`; run changed-module checks and direct consumers, not the full reactor. `BridgeAssemblyTest`, `ArchitectureBridgesTest`, `OpenApiBridgeDescriptorsTest`, `SpringBridgeImportTest`, `BridgePublicationStoreTest`, `BridgeQueryApiTest` and `BridgeCliTest` cover the new boundary. The two explicit execution ITs only read existing native captures. The ABI IT executes the checked-in trusted platform fixture; it is not a target-build fallback.

```powershell
$bridgeNodeCommand = Get-Command node -ErrorAction SilentlyContinue
$bridgeNode = if ($bridgeNodeCommand) { $bridgeNodeCommand.Source } else {
  Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe'
}
if (-not (Test-Path -LiteralPath $bridgeNode -PathType Leaf)) {
  throw 'Provide an absolute Node.js executable path in $bridgeNode.'
}
& $bridgeNode --version
& $bridgeNode --check backend/src/main/resources/web/bridges.js
& $bridgeNode --test backend/src/test/js/bridge-model.test.cjs backend/src/test/js/workbench-model.test.cjs
```

Windows: Node may be present only in the Codex terminal's process PATH. On the verified workstation, Node v24.22.1 resolves from the Codex runtime directory while the saved user/machine PATHs have no Node entry. The commands above invoke the resolved/fallback executable directly; they do not change system PATH or install packages. Set `$bridgeNode` to another verified absolute runtime when using a different workstation.

Inner loops select one method or a small pure class. The retained `ArchitectureNativeCaptureIT` (~34 seconds) is a pre-handoff integration consumer, not a mandatory inner-loop command. Select `SpringBridgeImportTest#lookalikeMappingAnnotationsNeverBecomeSpringEndpoints` for its mapping defect, and run both Spring cases before handoff. The five-second target is not a correctness ceiling; report actual Maven wall time and separate it from Surefire body time. Reuse verified SDK inputs, retain fresh analysis state and keep production byte validation unchanged.

`BridgeJourneyIT` is explicitly selected with pinned Node/architecture/routing SDK paths; its exact command and candidate/qualification limits are in the Step-3 record. It verifies native route/component/client → OpenAPI ← Spring ownership through real loopback HTTP, with actual Kotlin compiler and Python/Pyright acquisition, an actually attempted malformed sibling, nested roots, test/custom contexts, real generated SDK/server outputs and native B03/B04/B05/B07 projections. Its scoped native result is distinct from production semantic qualification.

`BridgeWorkbenchPreview` is a test-only real-store/API preview, optionally taking the portable native fixture directory and a loopback port. Its intentionally missing provider/census demonstrates withheld assessment. It is not the required Java/Kotlin/TS/Python mixed-repository journey. All bridge evidence remains unqualified producer evidence; Task-1/Task-2 acceptance is unchanged.

`BridgeMixedReplayIT` is a data-only consumer of the same retained fixture: it checks every M1 source hash across eight contexts, the exact recovered call obligation, withdrawal, native-name search and qualified topology without rerunning any SDK or generator. Specify `-Dbridge.fixtureRoot=<existing authored fixture>` explicitly. The historical v1 has 32 sources; the quality follow-up v3 includes captured tsconfig and requires `-Dbridge.expectedSources=33` (default remains 32 for historical evidence). A new `BridgeJourneyIT` run may use `-Dbridge.fixture=<new exact directory>` when native inputs/behavior change and a persistent fixture is needed for API/CLI/UI/export parity; it refuses an existing directory. Do not create duplicate captures to repeat unchanged checks.

The native journey requires the fixed SDK roots in addition to `$bridgeNode`: `bridge.tools` (existing architecture SDK), `bridge.routing` (existing native-routing SDK), `bridge.languages` (Kotlin/Pyright plus protoc/gRPC binaries), `bridge.native` (the pinned AsyncAPI/protobufjs-cli SDK), and `bridge.python` (an absolute Python executable). Compact pins live in `bridge-language-tools.json`, `bridge-generator-tools.json` and `architecture/bridge-toolchain.json`; tools/caches and the authored fixture stay outside Git. Acquisition uses official Maven Central/npm artifacts, no install hooks, and SHA-256 pins; this is not a restored worker or a production method certificate.

```powershell
.\mvnw.cmd -q -pl backend "-Dplatform.build.directory=target/step5-bridges" "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=BridgeJourneyIT" "-Dbridge.node=$bridgeNode" "-Dbridge.python=$bridgePython" "-Dbridge.tools=$bridgeTools" "-Dbridge.routing=$bridgeRouting" "-Dbridge.languages=$bridgeLanguages" "-Dbridge.native=$bridgeNative" test
.\mvnw.cmd -q -pl backend "-Dplatform.build.directory=target/step5-bridges" "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=BridgeMixedReplayIT" "-Dbridge.fixtureRoot=$bridgeFixture" test
& $bridgeNode --test analyzer-execution/src/test/resources/polyglot/architecture-bridges.test.cjs
```

The final native journey body took 114.848 seconds; it is a pre-handoff integration, not a five-second inner loop. Narrow pure tests such as `BridgeTopologyTest` and `LanguageBridgeImportTest` are used for query/import iterations. `BridgeApiMain` accepts an optional fourth `warm-scope` argument to finish cold replay before starting the loopback server. Warm queries still rehash the complete transitive read set. The `topology` API/CLI retains bounded paths, conditional cycles and boundary crossings; its result is explicitly non-exhaustive.

The [quality follow-up](tasks/m8/04-step5-step3-execution.md#quality-follow-up--2026-11-02) supersedes the blanket exit claim and owns the newer 51-case selection, native config/SDK controls, fixture v3 and its timings. `PythonBridgeNativeIT` uses the same `bridge.node`, `bridge.python` and `bridge.languages` properties; it checks real SDK declaration targets, local builtin shadowing, changed support-byte rejection and core import. Existing Python native controls can reuse a verified SDK via process-local `EVO_PYRIGHT_SDK` (pinned Pyright 1.1.396), avoiding their historical cache path; Node must still resolve in that test process. These controls do not run target applications or establish production method qualification. Bridge stats distinguish concrete bindings from evidence obligations; always verify both denominators and raw outcomes when comparing captures.

## M9.2 explicit evidence checks

The [Task-2 controls](tasks/m9/02-cases.json) register the semantic checks; the execution record separates bounded passes from unavailable native cells. Ordinary tests do not recreate deleted workers or captures. No test run below certifies production native methods or whole-repository semantic correctness.

The existing Spring binding oracle can reuse an explicitly selected Maven2 cache, without copying an SDK or weakening its seven original SHA-256 pins:

```powershell
.\mvnw.cmd -q -pl analyzer "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=SpringBindingOracleIT" "-Dspring.oracle.repository=C:/Users/Admin/.m2/repository" test
```

`M9InventoryCensusIT` is selected explicitly. Supply the frozen registry, corpus and independent inventory roots, and a **new, nonexistent** external output directory. It compares every physical path/hash/length, pages the actual product inventory, checks the complete ownership ledger, and records passive descriptor/context dispositions. Its census of 16 repositories is independent of semantic scoring; unknown effective contexts and generators remain pending. It never evaluates build scripts or runs target code.

```powershell
.\mvnw.cmd -q -pl backend "-Denforcer.skipRules=reactorModuleConvergence" "-Dtest=M9InventoryCensusIT" "-Dm9.registry=<absolute-checkout>/docs/tasks/m9/registry.json" "-Dm9.corpusRoot=<explicit-corpus-root>" "-Dm9.inventoryRoot=<registered-inventory-root>" "-Dm9.censusOutput=<new-external-output-directory>" test
```

Native TypeScript fixture source/NDJSON files must retain their checked-in LF bytes. Their source hashes and UTF offsets are evidence; converting them to CRLF is an input change, not a reason to rewrite expected hashes or silently normalize production inputs. `.gitattributes` pins this checkout behavior.

## Basis

JUnit 5.11 distinguishes [tag filtering from conditional disabling](https://junit.org/junit5/docs/5.11.0/user-guide/index.html#writing-tests-tagging-and-filtering). Surefire 3.5.5 supports [JUnit tag expressions through `groups` / `excludedGroups`](https://maven.apache.org/surefire-archives/surefire-3.5.5/maven-surefire-plugin/examples/junit-platform.html#filtering-by-tags). Native preflight uses Maven Enforcer's [required-file rule](https://maven.apache.org/enforcer/enforcer-rules/requireFilesExist.html); the native readers provide the authenticity checks. Java 21 defines [`Deflater.NO_COMPRESSION`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Deflater.html#NO_COMPRESSION) for the adapter's temporary ZIP representation.


The five product captures are Protobuf caller/producer, Lombok native member augmentation, native Spring MapStruct and custom generated Java/resources. Setup prepares existing signed public-test evidence outside product processes; every product replay excludes test-classes and tests JARs. Product code verifies the external registry/pins/signatures and never loads a signer or issues a claim. Repeating the same capture checks complete graph/root/gap/publication equality with distinct job UUID/time, as a bounded subset of R03.

The dedicated custom case additionally registers six readiness obligations and checks both child/root receive authenticated proofs, then verifies withdrawal reopens a requirement and prevents success. It changes attempt/time bindings inside a second capture and reverses registry arrival. Source/Java and DEFAULT_SCOPE_TRUTH_NONE Spring semantic projections agree; actual source-anchored Spring outputs, exact root coverage and current-gap meaning agree; full child/root graphs and semantic-audit bindings differ. Unsupported matcher shapes fail explicitly. Explicit configuration truth is not claimed by that additive projection version; native truth-region/witness/version/order/UNKNOWN suites remain separate checks. Causal closure-event permutation is covered by JavaEvidenceLedgerTest. Qualification remains PUBLIC_TEST_POLICY_ONLY. [Review follow-up](tasks/m8/04-step4-phase2-execution.md#review-follow-up--2026-10-26) records the final 605-case/70-suite refresh and its limits.
