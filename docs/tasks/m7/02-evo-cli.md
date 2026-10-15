# M7.2 — evo CLI implementation and bounded verification

Date: 2026-10-16. **CONFIRMED implementation and executed checks below; PROVISIONAL product acceptance.** M7 remains active. This is M7.2 delivery, not M7/G6 closure.

## State and completed scope

Started from clean commit `86f6de5` (`done task M7.1`). G3/G4/G5 remain recorded PASSED. The owner approved `evo`, a scrollback command palette plus direct commands, short English names and English/Vietnamese presentation. Later corrections supersede the initial neon/rainbow palette: an original interlaced hexagonal eye in emerald/mint/silver with lime center, soft blue text and gray rules. No slogan, architecture subtitle or Java runtime label. Navigation uses `Bắt đầu`, `Commands`, `Kiểm tra quy tắc`, `Giải thích độ tin cậy`.

Implemented a standalone shaded Java CLI and Windows/POSIX launchers; all 22 M7.1 canonical query operations and six M6 report operations; passive source/build/platform scan; verified immutable snapshot and exact context selection; policy/assessment/bundle/witness/formula replay; canonical JSON, SARIF and bounded GraphML; shell completion; explicit partial/withheld states and CI exits. No backend server or Neo4j is needed. [Guide](../../../cli/README.md), [contract](../../architecture/m7-evo-cli.md).

Reports now use compact result headers, observed inventory bars, numbered symbol/gap/configuration rows, canonical directed dependency/path diagrams, rule/finding summaries, metric operands, separate evidence-sufficiency bars and bundle/witness details. `--details` expands exact fields; `--json` preserves canonical machine values. Palette accepts both `show` and `evo show`, including launcher prefixes, without shell evaluation. `next` uses the exact saved request/cursor for pagination, avoids long token copying, rejects query overrides and invalidates on context/command changes. Four regressions cover final-page handling, stale continuation, request-file use/changes and canonical JSON next-page output. Persisted numbered references bind graph/context/scope; incomplete searches cannot guess a unique name. Failed scans preserve the previous selection. Selection changes do not rewrite large graphs; the palette reuses one verified immutable graph between commands.

## Debugging and domain repairs

The owner’s disabled-VT CMD screenshot reproduced raw RGB SGR output. Pinned Jansi 2.4.2 now prepares and verifies native processed-output/VT flags, UTF-8 output and actual window width; rejection falls back to plain output even with forced color. Native mode/code page are restored on exit. [Primary-source research](../../research/m7-windows-terminal-rendering.md) distinguishes AGY’s docs-only public repository from Gemini’s published renderer. Prior ConPTY/PNG checks did not cover this native boundary.

The owner’s root scan reproduced failures beyond presentation. Focused regressions established and repaired valid descriptor-only JMOD handling, incorrect compiler-property binding, shared local-variable type-site duplication and JavaParser Unicode/backslash coordinate drift. JLS 3.3 translation now maps parser coordinates to exact original UTF-16 spans. Malformed source remains typed, and valid sibling sources survive. Definite captured handwritten input is analyzable while missing generated/plugin/annotation evidence remains qualified. No target Maven/Gradle lifecycle, script or application runs.

The scan composes existing passive Maven effective-model/local-classpath providers with exact available cache bytes and recorded source exclusions. Missing reactor binaries, generated outputs and unproved Spring membership remain gaps. Public queries expose validated relative module/source labels and manifest partition metadata while withholding declaration bodies, host paths and arbitrary configuration values. Context provenance no longer materializes an unrelated entity inventory.

Large-graph repairs stream canonical hashes, strict JSON parsing, UTF-8 snapshot writes/loads and canonical verification rather than retaining duplicate full-document strings. Versioned auxiliary frontend/ingestion/ledger indexes preserve complete individual records, identity/order references and all gaps; frontend reconstruction is tested against the original canonical result. Snapshot schema and M1 preimages/formulas are unchanged. CLI ceilings are 1,000,000,000 bytes/characters, depth 128, 16,000,000 JSON values, with a 4 GiB heap; query/export output remains 32 MB.

A separate packaging failure was traced to the IDE’s Java language server overwriting Maven output with error stubs. No user IDE process/settings were changed. Builds use `-Dplatform.build.directory=target/evo-build`; the JAR plugin forces regeneration before shading. The packaged regression rejects error stubs and runs actual source/query/report paths before publishing `cli/target/evo.jar`.

Current version identifiers: `architecture.cli:m7.2-v1`, `frontend.javaparser:3.28.2-m7.2-written-types-v2`, `spring.constructor-ingestion:m7.2-written-types-compat-v2`, `source.universal-ingestion:m7.2-qualified-sources-v1`, `build.maven-model:3.9.16-m7.2-compiler-bindings-v1`, `architecture.query:m7.2-v2`. OpenAPI/provider assertions agree. Historical graph decoding remains supported; newly constructed content and old query cursors naturally have different identities.

## Executed verification

Every Maven command below also supplied `"-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q`. Only the single-module-inapplicable convergence rule is skipped. No full reactor or external repository benchmark ran.

| Module | Executed scope | Tests / suites | Result |
|---|---|---:|---|
| analyzer | 16 selected serialization/identity/semantic/graph/query/frontend/ingestion/metric/assessment/policy consumers | 103 / 16 | PASS |
| analyzer-maven | SourcePlanProjectionTest, MavenBuildModelProviderTest, MavenLocalClasspathProviderTest | 44 / 3 | PASS |
| analyzer-filesystem | FileGraphStoreTest, FilesystemJdkPlatformProviderTest, FilesystemRepositoryAcquirerTest | 21 / 3 | PASS |
| analyzer-javaparser | Complete module test set | 270 / 38 | PASS |
| backend | Complete module test set | 42 / 8 | PASS |
| cli | Complete module test set | 53 / 7 | PASS |
| **Total** | | **533 / 75** | **0 failures, errors or skips** |

Exact commands:

```powershell
.\mvnw.cmd test -pl analyzer "-Dtest=CanonicalSerializationTest,IdentityAndSourceContractTest,SemanticContractTest,AnalysisAndAssessmentContractTest,CanonicalGraphContractTest,CanonicalGraphBuilderTest,StreamingGraphJsonTest,ArchitectureQueryContractTest,ArchitectureProjectionTest,ConditionalPathCycleTest,FrontendContractTest,ScopedIngestionRecoveryTest,RepositoryIntakeDecoderTest,InventoryQualityMetricsTest,AssessmentMetricsIntegrationTest,PolicyConditionalEvaluationTest" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-maven "-Dtest=SourcePlanProjectionTest,MavenBuildModelProviderTest,MavenLocalClasspathProviderTest" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-filesystem "-Dtest=FileGraphStoreTest,FilesystemJdkPlatformProviderTest,FilesystemRepositoryAcquirerTest" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl analyzer-javaparser "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl backend "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl cli "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package -pl cli "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/evo-build/evo.jar
python cli/src/test/python/windows_console_smoke.py
```

Surefire reports remain in ordinary ignored `module/target/evo-build/surefire-reports`. Inner-loop regressions were observed RED before repairs, including shared type/Unicode failures, descriptor-only JMOD, compiler metadata, canonical structural bounds, packaged error stubs, disabled-VT native rendering and one-vertex context provenance. Final affected query tests and backend/CLI consumers were rerun after metadata/path redaction and pagination repairs. Module descriptor host paths/display text have a negative redaction regression; visible module labels use only validated relative paths. The parser full module is a pre-handoff check, not a five-second inner loop: 356.21 aggregate suite seconds. Final core selected suites total 3.72 suite seconds; Maven startup/compilation can exceed the five-second target. No startup/corpus latency gate claim is made.

Packaged smoke: **1,106 platform classes, zero error stubs, seven journeys PASS** (scan/find/show/dependencies/impact/policy/assessment). Their exit 3 values correctly preserve partial evidence; score remains WITHHELD with no invented value. Final published JAR SHA-256: `94d0881c0d792a0908ee5a5f6192c3f8ea055ac007a6c17d1ea850d985834a43`.

Native Windows build 26100: **six process cases PASS**: standalone, CMD launcher, native 48-column width against stale COLUMNS, language switch, actual `evo show`/`evo find Controller`/numbered deps/impact/`next` palette journey and redirected JSON. VT starts disabled, CP850 is exercised, actual Unicode screen cells/color attributes are inspected and exact restoration is checked. A no-Java CMD control separates shell-owned mode changes. The harness maps characters through User32 `VkKeyScanW`; treating a hyphen as virtual-key 45 had injected Insert rather than `-`, which was a harness defect repaired before the final six-case pass. This is current-host evidence, not a terminal/font/platform matrix.

A fresh preview is rendered from actual packaged Vietnamese help and a passive fixture dependency report at `cli/target/evo-preview.png`; this is a rendered transcript, not a native screenshot. Native screen-cell tests supply the separate CMD evidence.

Earlier unchanged checks in this task also passed: actual SARIF against the official OASIS 2.1.0 schema with URI format checks; GraphML XML parsing (no XSD claim); PowerShell completion syntax; POSIX shell syntax/version from another directory using the bundled Windows Git shell (no native Linux/macOS claim); widths 32/48/80/160, combining/wide characters, ASCII/no-color, bidi/control sanitization and canonical JSON parity.

## Owner-requested repository self-scan

The final packaged scan of this original workspace completed **PARTIAL, exit 3**, about **209.99 seconds** on this host. It is the owner-requested debugging reproduction, not an external corpus benchmark or gate measurement. Captured 317 selected files, including 298 Java files; 7 build modules, 6 analyzed main units, 1,062 types, 89,769 dependencies and 25 entry points. No Spring scope/configuration was fabricated. The receipt retains exactly 44,539 merged gap records/categories; full typed records live in the canonical graph, while query inventories expose bounded safe metadata.

Snapshot: `snapshot:sha256:8b60a0a4c2ec307f104b1a271c8c66d19b4e22507ce8ffeaeb455cdb4757aba3`. Graph: `sha256:89f8368928853ba29bd5524c8cf416f4a6fdf94954da8f8e30f1c494b605c97a`. Stored file: `.evo/89f8368928853ba29bd5524c8cf416f4a6fdf94954da8f8e30f1c494b605c97a.graph.json`, **720,411,524 bytes**. Root scan updated the selection only after saving successfully and retained older snapshots. `show` now names all six MAIN contexts plus Build / acquisition; context 3 (`cli / MAIN`) is selected for the follow-up journey. A single-process palette journey on this saved root snapshot passed `evo show`, `evo find EvoApp -n 3`, `evo next`, `deps 1 -n 3` and `impact 1 -n 3 --depth 2`, displaying full canonical names and directed relationships without usage/operational/reference failures; about 37.14 seconds on this host including first verification/load. These are observed snapshot facts, not an assertion that all repository semantics resolved.

## Limits, durable state and exact next slice

No implementation blocker or consequential approval is pending for M7.2. Product acceptance and remaining M7/G6 workflow checks stay separate. Passive local-cache reading does not fetch network dependencies or prove missing reactor outputs, plugin behavior or Spring membership. Formula replay is not fresh Spring runtime execution. Palette history/fuzzy completion, native installers, a broad terminal/performance matrix and policy-qualified impact associations remain outside this slice. Large snapshots still incur substantial initial verification/load cost; palette graph reuse avoids repeating that work per command.

Implementation self-review only; no independent review, formal reproducibility package, commit or push. Durable state and contracts have been updated without promoting milestones. **Exact next slice:** complete the remaining bounded M7 local workflow with explicitly acquired exact dependency/platform/configuration evidence and configuration-qualified structural/policy impact, witness inputs and exports. M8/backend/workbench and Track B remain gated later work.
