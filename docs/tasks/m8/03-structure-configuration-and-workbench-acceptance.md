# M8.3 — Structure, configurations and workbench acceptance

Status: **IMPLEMENTED — bounded verification and workbench acceptance passed (2026-10-16).** M8.4 remains mandatory before M9; M10 human Track A product acceptance is separate.

## Scope and state before

The owner requested complete, accurate, usable and modern M8.3, then reaffirmed working behavior and evidence as mandatory and requested online UX research. English UI and local/public-Git sources remain approved. M8.2 supplied real jobs and the basic views; deep ownership/configuration/replay/metric navigation was incomplete. Entry Git HEAD was `03d94d6`. During work the owner's checkpoint `b0cecfd` incorporated the initial changes; preserve it and all later user instructions. The agent has performed no commit or push.

The [M8.3 contract](../../architecture/m8-structure-and-configurations.md) records boundaries, query/projection versions, privacy and UX sources. Existing acquisition/provider limitations are retained honestly, not declared solved by UI changes. Full repository builds/target scripts are never executed.

## Registered acceptance

Reference host: Windows 10 Pro 10.0.19045, Intel Core i5-8250U, 8 logical processors, 11.9 GiB visible RAM; JDK 21.0.12.1. Target viewports: desktop 1440×1000 and narrow 390×844 plus 320 px reflow. Existing approved query quotas apply; browser graph input is bounded to 1,500 vertices/4,000 edges/20,000 auxiliary states. Measure warm primary-view loading and report observations rather than inventing an approved latency SLA.

| Scenario | Required observable result |
|---|---|
| External Java | MiniChatServer captured commit `2eea33ba8ea91595b8cfd7ee1cd06f708236041a`; actual source → job → context → hierarchy/member/source evidence → metrics → graph/path → JSON |
| Conditional Spring | Authored actual source with `@Profile("dev")`, client constructor injection and store; existing pinned Framework 6.2.0/Boot 3.4.0 providers produce explicit and symbolic snapshots; view two configurations, inspect conditional binding and replay witness/counterexample |
| Degraded evidence | Absent policy, partial classpath/unsupported prototype, unknown feasibility, missing/stale witness, operational stop and bounded/paged results never become a healthy score or proven absence |
| Interaction | Keyboard/manual tab activation, dialog return/Escape, breadcrumbs/Back, filters, pagination, graph controls/path/source evidence, both exports, narrow layout and stale-request protection |
| Parity | API data/result identities agree with canonical services; exported JSON retains the exact displayed result requests and qualifications; packaged CLI direct-consumer/smoke checks preserve the same contracts |

The Spring fixtures are source-generated test inputs, not a production fallback. `M6PolicyIntegrationTest` can export its verified snapshots/policy with `-Devo.workbench.exportFixtures=true` to its ignored `target/evo-build/workbench-fixtures/`. `CapturedWorkbenchAcceptance` in backend test sources serves only those hash-validated snapshots on an isolated test port and recomputes reports through normal domain services. Ordinary production connections never receive these fixtures.

## Work completed

Canonical ownership/realization/metric-definition queries and published-finding model replay; context-qualified explorer with member and source navigation; comparison with conservative absence semantics and baseline profile labels; metric definitions/applicable scopes/score explanations; truthful scoring setup/example; graph focus-depth repair, parent closure, status/evidence retention, keyboard node/path controls and exports; async/error/layout/status/focus improvements. No score formula, M1 preimage, snapshot schema or target execution change.

## Verification and result

**CONFIRMED:** 263 Java cases plus 7 Node presentation-contract cases pass against affected implementations. This is a selected analyzer/direct-consumer set, not the full reactor. Surefire reports live under each module's ignored `target/evo-build/surefire-reports/`; unrelated historical analyzer reports are excluded from the fresh denominator.

| Executed scope | Command (PowerShell) | Result |
|---|---|---|
| Analyzer, 11 suites | `.\mvnw.cmd install -pl analyzer "-Dtest=WorkbenchQueryTest,GraphProjectionServiceTest,ArchitectureQueryContractTest,ConditionalPathCycleTest,ArchitectureConditionTest,PolicyEvaluatorTest,ArchitectureAssessmentTest,StructuralMetricsTest,ConditionalMetricContractTest,PolicyMetricsTest,SatConfigurationReasonerTest" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q` | 120 cases, zero failures/errors/skips |
| Source/Spring direct integration | `.\mvnw.cmd test -pl analyzer-javaparser "-Dtest=M6PolicyIntegrationTest,ArchitectureQueryPipelineIntegrationTest" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q` | 6 cases / 2 suites, zero failures/errors/skips; includes Framework 6.2.0/Boot 3.4.0, explicit/symbolic profiles, inactive node selection and replay |
| Complete backend | `.\mvnw.cmd test -pl backend "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q` | 79 cases / 13 suites; original run had one Windows temp-root cleanup lock error, no assertion failure. Unchanged `-Dtest=WorkbenchApiMainTest` rerun: 2/2 pass; other 77 remain passing |
| Complete CLI consumer | `.\mvnw.cmd package -pl cli "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q` | 58 cases / 8 suites, zero failures/errors/skips; all 26 generic query operations, focused graph and published-finding replay |
| Browser model | `node --test backend/src/test/js/workbench-model.test.cjs` and `node --check backend/src/main/resources/web/app.js` | 7 pass; syntax valid |
| Final resource packaging | backend `install` and CLI `package` with the same directory/enforcer flags plus `-DskipTests`; copy resulting shaded JAR to `cli/target/evo.jar` | Both succeed; these commands package resources, not additional test evidence |
| Distributable | `python cli/src/test/python/packaged_jar_smoke.py cli/target/evo.jar` | 7 actual scan/find/show/deps/impact/policy/assessment journeys pass; 1,151 platform classes, zero error stubs; each degraded journey correctly exits 3 |
| Diff | `git diff --check` | Pass |

Observed red/green controls: missing new operations; wrong default-package label and source-root path; focus depth reaching too far; actual explicit-world pair serialization losing profile labels; inactive profile bean remaining in realized graphs; fractional/ignored HTTP depth; CLI focus bounds and operation operands; outdated packaged query-version assertion; PNG data-URL download not creating a file. The production PNG path now uses nonblocking Blob export and creates a valid PNG. No tests or cleanup assertions were disabled to make checks pass. Inner-loop timing was not uniformly below five seconds: strict-depth red command 8.437 s and actual Spring regression suite 5.803 s; final launcher rerun 11.32 s suite time. No universal latency claim follows.

### Browser observations

Real public GitHub connection → passive analysis → captured contexts → Java class/member/source inspection works. MiniChatServer produces **PARTIAL, 46 unique recorded limitations**, four project classes (`ClientServer`, `Server`, `Client`, `Room`), actual member children and no fabricated score. Observed public-source analyses took **15.1 s** and **13.5 s**. One warm package-navigation click through the returned four-class accessibility state took **396 ms**, including browser automation/observation overhead; this is not an isolated network timing or latency SLA. Dependency hotspots expose five recorded directed pairs and four ranked project classes with canonical incoming/outgoing degrees. Exact original pinned checkout revision remains recorded above; subsequent public runs record their own acquired revision in receipts.

Source-generated Spring acceptance shows `baseline dev: on/off`, actual `client → store`, two nodes/one edge in the on realization and one node/no edges in off. Missing rows under partial evidence remain **Not established**. View selection survives navigation to the graph and back. Finding source evidence resolves `src/main/java/app/Client.java` and `Store.java` with exact spans; model witness SAT and counterexample UNSAT replay match, `formulaReplayed=true`, `semanticReplayed=false`. Shortest path returns one jointly feasible dependency step and highlights available nodes/edges. Zoom/fit/focus/depth and canonical JSON/PNG export work. Downloaded files are under the browser's Downloads directory; these are captured test artifacts, not production fallback inputs.

At measured **320×844** and **390×844**, ordinary content stays within document width, tab rows wrap, controls/dialogs remain reachable and comparison cells wrap without page overflow. Desktop **1440×1000** retains bounded cards/graph and separated actions. ArrowRight moves focus from Configurations to Metrics & score without activation; Enter activates; Escape closes the inspector and restores its initiating button. Configure scoring pre-fills the actual public source, inserts editable valid cycle rules, and refuses to overwrite a nonempty policy. Absent-policy and withheld-score states remain explicit. Final served resource hashes match source; the packaged-only production launcher serves port 8080.

### Changes, decisions and continuation

Changed groups: analyzer canonical query/metric/projection contracts and tests; compiler-owned baseline-choice helper preserving its original preimage; actual JavaParser integration assertions; backend API/OpenAPI, test-only captured-source harness and English web assets/tests; CLI operation/depth/replay consumers and packaged smoke version; backend/CLI guides, M8.3 contract/task record, M8 index and current state. The owner's `b0cecfd` checkpoint contains the initial task changes; the final working diff contains subsequent refinements. No owner AGENTS/coverage-mandate changes are claimed as agent-authored.

Routine reversible decisions: retain native accessible controls and pinned local Cytoscape instead of a framework migration; canonical ownership/replay remain backend operations; private properties/source contents stay redacted; incomplete data cannot create a score or an absence conclusion. No new consequential human decision is required to use this slice. No current M8.3 implementation blocker is known.

Limits: self-review only; no independent review, screen-reader audit, broad scale/performance certification, full reactor, external corpus benchmark or formal reproducibility package. Ordinary repo connections need registered Spring evidence for configuration choices. Process-local jobs/connections/reports, private authentication and existing acquisition/generated-source/provider limitations remain. Complete repository coverage and all PARTIAL/FAILED causes are not claimed solved. No commit/push or gate promotion occurred.

Durable state: [current state](../../current-state.md), [M8 index](README.md), [M8.3 contract](../../architecture/m8-structure-and-configurations.md), historical M8.2 successor note and user guides reconciled. **Exact next task: M8.4 — Comprehensive Repository Readiness Upgrade**: register bounded readiness expectations and implementation slices for the mandatory coverage problems before M9. Do not start Track B or treat this as M10 human approval.
