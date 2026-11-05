# evo — architecture intelligence in your terminal

`evo` opens a scrollback-friendly command palette in a terminal. Direct commands work in scripts and CI. The owner selected short English commands and English/Vietnamese presentation. The original logo combines an interlaced hexagonal orbit and an eye aperture in emerald, mint and silver with a lime center; text uses soft blue and separators use quiet gray. This follows the latest request for a distinct design inspired by the supplied OpenAI, AGY and NVIDIA references. The header contains only the CLI name/version and language, with no slogan or Java runtime label. Navigation uses **Start / Bắt đầu** and **Commands**; the short descriptions are **Check rules / Kiểm tra quy tắc** and **Explain confidence / Giải thích độ tin cậy**. Terminal background colors are respected; status text remains readable with colors disabled.

## Build and launch

Captured Step-4 graphs distinguish active residuals from closed gap history. Use `evo gaps --gap-view CURRENT`, `evo gaps --gap-view HISTORY` or `evo gaps --gap-view ALL` with the usual graph/context/JSON options. The default is CURRENT. History preserves original gap identities and evidence; its presence does not increase the active gap count. These views describe the saved graph's policy/input revision. They do not re-certify an old capture against a changed authority registry.

Use JDK 21 and the platform's Maven Wrapper. These commands build trusted platform modules, never an analyzed repository. On a fresh checkout, install the required module artifacts individually, then package the CLI:

```powershell
.\mvnw.cmd install -pl analyzer "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-maven "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-filesystem "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-execution "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-javaparser "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-treesitter "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl backend "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package -pl cli "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/evo-build/evo.jar
Copy-Item cli/target/evo-build/evo.jar cli/target/evo.jar
.\evo.cmd --lang vi
```

The isolated build produces `cli/target/evo-build/evo.jar`; validate and copy it to the launcher path `cli/target/evo.jar` as above. It is a shaded JAR with its runtime dependencies, including the pinned Jansi 2.4.2 native console bridge. The separate output directory prevents IDE incremental compilation from replacing Maven-built classes. The packaged smoke rejects Java error stubs and exercises real source/query/report paths. The module-install commands skip tests and are not verification evidence. Only the single-module-inapplicable convergence rule is skipped; all other Maven enforcement remains active.

Windows launchers: `evo.cmd`, `evo.ps1`. POSIX: `sh ./evo`. Standalone: `java -jar /path/to/evo.jar`. Launchers use JVM heap ergonomics rather than forcing a fixed 4-GB heap. Operators can select a heap or other JVM settings through the standard `JDK_JAVA_OPTIONS` environment variable (for example, `-Xmx16g`); this is an allocation choice, not a repository-size limit. On Windows, the process prepares UTF-8 console output and enables/verifies native virtual-terminal processing before rendering. It restores the original output mode/code page on normal exit and JVM shutdown; launchers also restore their captured encoding. No persistent console setting is changed. Launchers preserve the caller's working directory and exit status. They do not auto-build or change PATH. Add the checkout directory to your shell's PATH to invoke `evo` by name from other directories. Use UTF-8 terminal input for the standalone JAR.

## Everyday workflow

```powershell
evo scan . --repo https://example.org/team/application.git
evo show
evo find Controller
evo deps 1
evo deps 1 --incoming
evo impact 1 --depth 3
evo check architecture.yaml
evo findings
evo score
evo export sarif -o findings.sarif
```

`scan` records repository acquisition, JDK symbol evidence, explicit Java-release/UTF-8 fallback policy, exclusions, pipeline gaps and a canonical graph snapshot. Default release is **21**, not an inferred repository fact; use `--jdk DIR --release N` for the fallback platform. Build-declared levels remain authoritative where modeled. Maven modules select their declared platform view from the chosen JDK's symbols/`ct.sym`, acquired once per distinct release; this does not download a JDK or execute a target compiler. Unavailable/incompatible views remain degraded. Full inventory precedes the separately identified bounded semantic view. Nested independent Java/POM/build roots are retained; every captured Maven root gets passive dependency planning. `--include-tests` admits tests to the semantic view; `--all-files` bypasses semantic filtering; `--exclude PATH` records an explicit narrower acquisition selection. `.gitignore` is not interpreted.

Scan composes Maven effective-model and classpath providers against `$user.home/.m2/repository` or explicit `--maven-cache DIR`. Cache misses for exact release parent/BOM POMs and dependency POMs/JARs are fetched from Maven Central by default, validated, hashed and atomically published to that selected cache. `--offline` disables all metadata/artifact downloads. Repeat `--artifact-repository HTTPS_URI` to replace Central with explicit credential-free repositories; combining it with `--offline` is rejected. Target-declared repositories and ambient Maven settings remain inert. The cache may receive acquired artifacts; existing invalid bytes are reported rather than overwritten.

Dependency batches reuse HTTP connections and admit up to four downloads by default, subject to the shared byte budget. Per-artifact failures retain their outcomes while other artifacts continue. Lombok constructor patterns also retain ordered field/annotation evidence when classpath or generated-member proof is unavailable: reports label these sites `LOMBOK_CONSTRUCTOR_CANDIDATE`, and saved graphs retain their source spans. Verified constructors use the existing injection analysis; candidates do not certify generated members or bean bindings. Current capability gaps or a partial parser result withhold `COMPLETE` / `FULL` confidence.

Gradle Groovy/Kotlin discovery includes literal projects, intermediate project descriptors, `projectDir` remapping, included builds, source sets and version catalogs. Dynamic scripts/plugins retain native-model requirements. `SOURCE_RECOVERY` now attempts each relationship against available captured source, sibling source, hash-verified artifacts and a compatible platform view. An unrelated missing dependency no longer disables every call, inheritance, annotation or type binding. Recovery checks receiver/ancestor and nested-call evidence before accepting potentially ambiguous selections; missing targets remain unresolved and global build/Spring closure stays incomplete. Failed attribution retains missing-origin/mapping consequences in one root-cause gap rather than multiplying the same failure into three records. Known pinned Spring declaration rules can acquire component candidates and constructor sites without certifying a complete registry or runtime binding. Missing effective Maven models retain conventional source plans with unknown coordinates. Target lifecycles still execute only through the registered isolated lane.

Local scan drains every inventory page in one invocation. Its product capture also retries up to three settled filesystem checkpoints; cancellation, missing LFS bytes and genuine unavailable inputs stay visible. Pagination alone does not require a second invocation. The normal scan graph is assembled in a private SQLite workspace and published as both streaming canonical JSON and authenticated CAS pages, without constructing a repository-wide `KnowledgeGraph` or adjacency maps. Use `evo graph-pages partitions`, `nodes`, `adjacency` and `evidence SHA256` for bounded raw navigation and provider-record inspection. Completed Java units, report rows and aggregate gap sets now spill to a private SHA-256-checked workspace before publication. JavaParser retains declaration skeletons, preserves source ranges while detaching tokens and processes executable bodies one document at a time. SDK hashing and unused JAR-resource accounting use streaming reads. Source inputs, skeletons, one context’s neutral output, Spring domain plans, legacy capture and most legacy queries still require memory; this is not an all-repository OOM certificate. When the legacy Java view exceeds its worker budget, scan now publishes full inventory and available non-Java syntax/native facts with `JAVA_COMPATIBILITY_WORKER_REQUIRED` instead of discarding the graph.

Java modules run concurrently when their frontend declares independent state, subject to CPU, estimated AST/JDK/JAR memory and heap headroom. Oversized units run alone and results merge in input order. Resolver caches are released at idle boundaries, including serial/direct calls. Spring annotation/parameter/declaration indexes avoid repeated full-population scans. Source wiring is evaluated even with an open container: `sourceTypeCandidateRows` and `sourceEvaluatedContexts` report actual type-compatible source possibilities, including evidenced composed annotations. These retain `activation=UNKNOWN`, `selection=NOT_SELECTED` and unevaluated qualifier/generic compatibility. They are separate from selected/configuration-qualified binding counts; an open container is not reported as dependency-free.

Observed source wiring also triggers Spring plan/truth evaluation with open scan membership. Exact pinned framework semantics and local declaration metadata can remain usable despite unrelated classpath recovery. Selected bindings still require an evidenced scope/configuration. The repository retains every gap; automatic Spring contexts inherit global and their own analysis gaps, avoiding copies of unrelated modules’ failures in every plan.

Every sealed inventory entry and discovered build component is retained in the graph. Scan automatically parses captured non-Java source with bundled Tree-sitter grammars: Go, Python, C#, JavaScript, TypeScript/TSX, C/C++, Rust, Kotlin, Scala, PHP, Ruby, Dart, Swift and Objective-C. It extracts declarations, call sites, imports, annotation/type/heritage syntax and literal route candidates without running project code. `polyglot.sourceSyntax` reports parsed files, categories, failures and worker policy. `syntax.call` links a lexical owner to an actual call site, not a guessed callee; source facts carry `semanticBinding=NOT_ESTABLISHED`. Groovy/Visual Basic and missing native bindings remain explicit obligations. `polyglot.filesByLanguage` reports language hints; `withoutNativeContextByLanguage` identifies missing native contexts. These are not semantic accuracy or deployed-service counts. To attach stronger existing evidence from the language adapters:

```powershell
evo scan . --offline --build off --semantic-store D:/captures/semantic --semantic-plan sha256:<plan-digest> --semantic-policy sha256:<policy-digest>
```

Native observations are joined to their captured census and projected into symbol references plus import/type/heritage/call/state/annotation bindings. `polyglot.asserted.*` preserves producer assertions; `polyglot.native.*` uses validated-observation publication trust. These are compile-time bindings with `runtimeObserved=false`; syntax call sites alone do not identify callees. Missing optional caller/symbol metadata retains the native occurrence and a typed projection gap.

Use actual digests. The publication must match the full captured inventory and source bytes. Qualified publications additionally require `--semantic-authority DIR` (the policy may then be derived from that operator directory). Stores/authority must be outside the target tree. Native context/census/observation/gap rows retain their scope and trust in the graph; `evo semantic` supplies the existing native query views. Passive parsing does not provision every native SDK, convert native references to Java metrics or certify complete polyglot call graphs. Missing contexts and unqualified methods remain explicit gaps. [ADR-008](../docs/decisions/ADR-008-passive-polyglot-syntax-and-source-wiring.md) records the provider choice and evidence boundaries.

`scan DIR --java-capture OPERATOR_DIRECTORY` selects an authenticated native Java context for the exact newly captured repository, source snapshot and readiness plan. The operator registration must live outside the analyzed tree and use `REGISTERED_VALIDATION`; public test-policy captures cannot authorize this scan mode. Captured generated sources, ordered classpath and semantic evidence retain their lineage. Other pending contexts remain gaps. Explicit `--jdk`, `--release`, `--maven-cache` and `--artifact-repository` overrides are rejected with a capture. This mode reuses previously produced native evidence and reports `isolatedBuildExecuted=false`; it skips fresh worker dispatch. The automatic build lane below is separate from validated native-model/semantic closure.

Automatic Maven/Gradle dispatch is implemented but **runtime verification is deferred at the human's request**. `scan` defaults to `--build auto`: it uses a registered native profile when available, otherwise records the waiting reason and continues source analysis. `--build required` waits/stops before semantic analysis when the selected build scope is not complete; `--build off` explicitly disables dispatch. `build DIR` performs acquisition, readiness and native build without starting semantic analysis. Current native transport is **offline**, with explicitly sealed Maven/Gradle cache materials; passive `--maven-cache` and repository flags do not expose host caches to the VM.

```powershell
evo build-register default D:/operator/hyperv-build.json
evo build-profile default --json
evo build D:/repositories/app --build-profile default --json
evo scan D:/repositories/app --build required --build-profile default --json
evo build-recover ATTEMPT_UUID --build-profile default --json
```

`build-register` pins a profile under the user's `.evo/native-build/registrations`; names are immutable, so use another name for a revised profile. The profile file and private worker state must stay outside the analyzed repository. Registration does not qualify the executor. `build-profile` describes the exact driver/image/policy inputs for the existing signed Hyper-V qualification gate; absent, expired or revoked qualification returns `WAITING_FOR_EXECUTOR`, never a fixture certificate or host fallback. This implementation does not create a qualification certificate.

The versioned operator record is defined by `NativeBuildProfile` in `analyzer-execution`. It contains an existing private `stateDirectory` under the registered host-storage receipt; pinned Python/PowerShell files; a pinned complete Python-module manifest; immutable read-only VHDX chain; internal-switch/MAC/IP settings; explicit per-worker CPU/RAM/work/disk/log/time/export budgets; Linux Maven/Gradle executable pins; optional sealed cache manifests; task mappings for nonbaseline contexts; and trusted harness keys, certificate/signature and live revocations. Guest executable paths must be canonical paths in the pinned image. Manifest rows are NDJSON `{ "path": "relative/path", "digest": "sha256:...", "size": N }`; cache rows may also carry an octal `mode`. These are runtime byte inputs, not generated acceptance evidence. Existing Core-MVP or authored-SDK registrations are not automatically reinterpreted as this profile.

Each selected Maven/Gradle root gets a fresh VM/SSH identity and isolated writable state. Maven uses compile/test-compile with fail-at-end; Gradle's protected init script resolves real `SourceSet.classesTaskName` tasks by module/source-set correspondence, with explicit mappings retained for custom variants. This scope compiles selected tests without selecting test-execution tasks. Captured input bytes are streamed to the guest; output objects and their census enter CAS only after authoritative VM termination and byte checks. Build exit zero establishes only an operational build result. `semanticCoverage=NOT_EVALUATED` remains explicit; compiler-model/classpath validation and semantic promotion are deferred. Receipts distinguish requests issued, protected guest launcher returns and `UNKNOWN` launch disposition after lost communication. Recovery fences the recorded worker Off and never reruns the target.

AUTO Spring evaluation derives a named repository-input baseline when no configuration space was supplied. Referenced property/profile inputs come from captured configuration; unknown external overrides and unproved container registration/order/mutation closure remain unknown. Evaluating this baseline does not certify a complete deployment world or the absence of DI relationships.

The scan receipt contains exact issue/root-cause/gap category counts and the verified graph identity. Every full typed gap remains in that canonical snapshot. `gaps --json` returns a bounded inventory; `explain <number>` and `evidence DIGEST --json` expose safe metadata and provenance. Select the receipt’s Build / acquisition context to inspect acquisition gaps. The receipt avoids repeating a potentially huge ledger. Failed scans preserve the previous selected snapshot and may save an unselected diagnostic graph. Snapshot I/O uses long counters and streaming canonical encoding/hashing; decoding constructs one typed record at a time instead of retaining a second JSON document tree. CLI writes use the available storage byte budget and reads use the actual frame byte count, preserving depth 128 and bounded tokens/payloads. This removes the former fixed 1-GB snapshot admission ceiling. Typed semantic queries still require an appropriately sized JVM worker; the raw page routes described below avoid that materialization. Query/export output retains its separate 32 MB ceiling. VCS is not inspected: revision/dirty labels are caller-supplied provenance. This is a scoped CLI implementation, not certification of the entire M7 evidence workflow.

New CLI/workbench publications also create a content-addressed page archive under `graph-pages`. `graph-pages build` explicitly indexes an older selected snapshot once, streaming its rows to disk without constructing the legacy graph. Subsequent `graph-pages` reads use authenticated disk trees and scoped continuation tokens. A cold reader verifies the original canonical graph digest, references, population counts and derived adjacency on disk; warm reads reuse the verified root and check each accessed page hash. These are raw context facts. Existing semantic metrics/policy/projection queries still use the typed in-memory graph.

The HTTP equivalent is `POST /api/v1/graph-pages/{graph-id}/partitions` with `{ "size": 50 }`, then `/nodes` with the returned `partition` object and `/adjacency` with `partition`, `node` and optional `incoming`. `size` / `cursor` page each operation. Workbench publication enables these routes. `QueryApiMain --partitions DIRECTORY GRAPH_ID [PORT]` serves an existing page archive without opening a legacy snapshot. Transport/page budgets bound one response; they do not truncate the population.

Session snapshots and selected policy bytes are stored under `.evo` in the current working directory. `-w DIR` chooses another session directory. `-g FILE` reads a verified canonical snapshot without selecting it. `open FILE` selects one. When several analysis contexts exist, `show` lists numbered contexts and `use N` remembers the exact selection. CLI never silently fuses main/test/modules/Spring contexts or picks an arbitrary first analysis. In the palette an already verified immutable graph is reused between commands; direct invocations verify the snapshot anew. `use N` changes only the small selection pointer.

Human `find`, `gaps`, `spring` and `config` save numbered references to their displayed page. Use `deps 1`, `impact 1`, `explain 1` or `paths 1 2` in the same graph/context/scope, including in a later process. A new human inventory replaces that page; stale/foreign references are rejected. JSON inventories do not change these references. Full symbol IDs always work. Names/digest prefixes still require a complete unique bounded lookup; numeric references point to exact observed rows even when the search denominator is incomplete.

## Commands

| Command | Purpose |
|---|---|
| `scan [DIR] [--build auto\|required\|off] [--java-capture DIR]`, `open FILE`, `use ID\|N` | Analyze source/captured evidence, optionally dispatch isolated builds, import a snapshot, select exact context |
| `build DIR [--build-profile NAME]`, `build-register NAME FILE`, `build-profile [NAME]`, `build-recover UUID` | Automatic isolated Maven/Gradle build, pinned operator registration, qualification inputs and recovery; runtime verification deferred |
| `graph-pages partitions`, `graph-pages nodes --partition CONTEXT`, `graph-pages adjacency NODE --partition CONTEXT [--incoming]` | Page authenticated raw graph rows without loading the whole snapshot; `--limit` / `--cursor` continue over the full population |
| `show`, `find [TEXT]` | Canonical overview and symbol search |
| `deps SYMBOL [--incoming]`, `impact SYMBOL`, `reach SYMBOL` | Direct dependencies, bounded potential structural impact and reachability |
| `paths FROM TO`, `cycles` | Configuration-feasible paths and cycles |
| `metrics [METRIC [SUBJECT]]` | Catalog or canonical metric with operands and qualification |
| `spring`, `config`, `gaps`, `provenance` | Spring/configuration inventories, explicit gaps and provenance |
| `evidence DIGEST`, `explain SYMBOL` | Redacted exact evidence projections |
| `check [FILE]`, `rules`, `findings`, `score` | M6 policy, closed rule denominator, observed findings and explainable assessment |
| `bundle ID`, `witness ID`, `replay ID` | Evidence Bundle, witness inputs and canonical formula re-evaluation |
| `query OPERATION --request FILE` | All 22 M7.1 operations and six report operations |
| `export json\|sarif\|graphml [-o FILE]` | Canonical result export |
| `next` (palette), `completion [powershell\|bash]`, `help`, `--version` | Shell completion, command guide and version |

Global options can appear before or after commands. `evo COMMAND --help` documents every parameter. Help includes both languages; `--lang en|vi` selects presentation, navigation and operational messages. Domain keys, statuses, identifiers and canonical JSON are never translated.

The logo appears on entry/help. Results use compact headers, observed inventory bars, numbered symbol cards, directed dependency/path diagrams, readable rule/finding rows and evidence-sufficiency bars. Arrows always use canonical edge direction, including incoming impact traversal. Unknown feasibility and incomplete denominators remain visible. `--details` expands exact fields; `--json` retains canonical machine values. In the palette, `next` fetches the next page using the exact saved request/cursor; no token copying is needed. Another query, scan/open/use or leaving the palette clears that continuation. Language/help keep it; query overrides are rejected. Direct processes use the canonical `--cursor` flag. `--debug` reports exception classes and code locations without exception messages or source/configuration values. In the palette both `show` and `evo show` work; quoted operands remain literal and no shell expressions run.

`--view union|must|may|unknown|realized` chooses a canonical projection. Union is exploratory and never one runtime world. Realized requires `--world ID` or `--assignment FILE` containing an exact opaque-variable-to-Boolean JSON object, never both. `--scope entity|type|package|module|spring-component`, `--kinds KIND,...`, `--depth 1..20`, `-n 1..50`, and `--cursor TOKEN` retain upstream bounds and filter-bound cursors. `--request FILE` accepts the exact M7.1 body, including population filters and all canonical budgets; conflicting direct query overrides are rejected. `--args JSON` supports generic operation arguments, and `--other FILE` supplies a second graph for compatibility preparation only. There are no Track B deltas.

Policy commands explicitly evaluate the selected policy against the selected graph/context through M6 services. `check FILE` remembers content-addressed policy bytes. `--policy FILE` overrides that input for report commands. Later report commands reconstruct canonical results, never reuse an unrelated score. `witness` exposes replay inputs; `replay` re-evaluates the recorded rule predicate in its original request. It does not start Spring or claim fresh semantic/runtime witness replay. Health and evidence sufficiency stay separate, and the existing M6 PROVISIONAL calibration marker remains unchanged.

## Output and automation

M8.3 adds `evo query children ID`, `evo query realizations` and `evo query metric-definition metric.instability`. `evo query replay --request request.json` accepts the published-finding report envelope (`arguments.identity`, optional Boolean `arguments.counterWitness`); legacy `evo replay BUNDLE` keeps its original bundle-condition behavior. Focused visual graphs interpret `--depth` as 1–19 dependency steps and translate to the canonical traversal vertex bound (maximum 20). The exact request/results remain in JSON, including partial/bounded qualifications.

For command results, `--json` emits only canonical JSON on stdout with M7.1 envelopes/digests and query version `architecture.query:m8.3-v1`; failures go to stderr. No spinner, ANSI, timestamp or locale-dependent number contaminates machine output. Export stdout follows its explicitly selected format. Completion produces shell source and rejects `--json`. `NO_COLOR` or `TERM=dumb` disables automatic ANSI; `--color always|never` overrides automatic color selection after console preparation. If a real Windows console cannot enable/verify VT or native access is unavailable, it falls back to plain output even with `--color always`, so SGR codes cannot fill the screen. Explicit color remains available in redirected output. Color output uses 24-bit RGB on compatible terminals. The pixel mark uses ordinary full blocks, without a Nerd Font or emoji; `--ascii` uses `##` pixels and plain decoration. Default width uses the native Windows window width at startup, then `COLUMNS` or 96 as fallback, clamped to 32..160; `--width` explicitly overrides it. Layout stacks below 48 columns. Human reports condense evidence-reference lists; `--json` retains the full canonical data, typed gaps, bounds and continuation. Declaration bodies are withheld from public spelling while original snapshot evidence is preserved.

| Exit | Meaning |
|---:|---|
| 0 | Operation completed within its declared result contract |
| 1 | Operational/I/O failure |
| 2 | Invalid input, missing selection/policy or ambiguous symbol |
| 3 | Partial, withheld, unknown or bounded/truncated result |
| 4 | Complete policy evaluation exceeds explicit `--fail-on info\|warning\|error` threshold |
| 130 | Canonical query cancellation |

Incomplete policy evidence takes precedence over the CI threshold: exit 3 cannot establish conformance. Default `--fail-on none` leaves policy decisions to the caller. Ctrl+C terminates the process; hard interruption may prevent delivery of a JSON envelope. The palette does not change to a raw/full-screen terminal mode. It accepts numbered actions, ordinary commands, quoted paths, `lang en|vi`, `help`, `quit` and EOF. It never evaluates shell expressions.

Storage failures retain a structured diagnostic instead of the generic
`OPERATION_FAILED`: `STORAGE_BUSY`, `STORAGE_FULL`, `STORAGE_PERMISSION_DENIED`,
`STORAGE_NOT_FOUND`, `STORAGE_CORRUPT`, `STORAGE_OPEN_FAILED` or
`STORAGE_IO_FAILED`. Existing snapshot-specific codes remain. Diagnostics include
the failing stage, exception types, known operation codes, numeric SQLite result
codes/SQL state and suppressed cleanup failures. Primary and extended
[SQLite result codes](https://www.sqlite.org/rescode.html) distinguish locking,
capacity, access, corruption and memory exhaustion. `--debug` adds bounded source
frames throughout the cause chain. `--json --debug` emits one error document on
stderr, with diagnostics under `error.diagnostics`; it adds no plain debug lines.
Raw exception messages, paths, SQL and source text are not printed. Cancellation
returns 130 and memory exhaustion returns 3; other storage failures return 1.

Exports default to stdout. `-o FILE` creates a new file and refuses existing output. JSON uses `--operation NAME`, optionally `--request FILE`/`--args JSON`, for inventory, metrics, findings, assessment, evidence or provenance. SARIF 2.1.0 preserves rule versions, qualifiers, exact source-document URIs/spans/hashes, bundles and the entire observed rule denominator in properties. `evo-source:` URIs identify immutable documents rather than invented local file paths. GraphML exports one bounded edge page; graph metadata contains the complete canonical envelope, including gaps, exact totals and continuation. It never claims to be the whole repository graph. Raw snapshot export is provided by the selected `.evo/<digest>.graph.json`, which may contain captured source/configuration bytes; query/export projections remain redacted.

PowerShell completion (session-local):

```powershell
evo completion powershell > evo-completion.ps1
. ./evo-completion.ps1
```

Bash: `evo completion bash > evo-completion.bash`, then `source ./evo-completion.bash`. The PowerShell script completes commands and option names. The palette itself uses normal console line input; history/fuzzy Tab completion is not implemented.

Native Windows regression (Python standard library, after packaging):

```powershell
python cli/src/test/python/packaged_jar_smoke.py
python cli/src/test/python/windows_console_smoke.py
```

The packaged smoke includes passive four-language parsing and authenticated syntax-evidence reads. Its optional `EVO_SMOKE_ONLINE_FIXTURE=1` mode additionally uses a fresh cache to fetch SLF4J 2.0.16 from Maven Central, checks the JAR SHA-256 against the captured classpath and requires a resolved `Logger.info()` call in an authored source fixture. Target builds remain off. Without that explicit test flag the smoke stays offline. This is a small integration check, not an external-repository benchmark.

The 2026-11-05 follow-up adds passive Protobuf schema/RPC nodes, HTTP candidates
from additional languages and bound Java client APIs, expanded pinned Lombok
accessor/Builder support, restricted native scalar SpEL and lazy nested config
defaults. Generated stubs, custom Java predicates and deployment routing still
require captured evidence. `scan DIR --offline --build off --json` keeps the
passive cohort scenario explicit; use a separate workspace for new measurements
and retain the JAR SHA-256. Historical gap/timing tables are not results for the
new artifact. The current artifact and focused verification are recorded in
[current state](../docs/current-state.md#linear-validation-bounded-aggregation-and-storage-diagnostics--2026-11-05).

It creates an isolated hidden console with VT initially off, reads actual screen cells/color attributes and checks UTF-8, width, cleanup, actual full-prefix palette commands, numbered dependencies/impact, language switching and redirected JSON. See the [Windows rendering investigation](../docs/research/m7-windows-terminal-rendering.md), [M7.2 contract](../docs/architecture/m7-evo-cli.md) and [verification record](../docs/tasks/m7/02-evo-cli.md). External-corpus acceptance, native executable packaging and the M8 visual workbench remain separate.

## Durable full acquisition

`evo acquire <directory|HTTPS-URL|ZIP> --json` captures all selected bytes and inventory on disk. Use the returned revision with `evo inventory <revision> --limit 20 --json` and its cursor. `evo acquire <same-input> --resume <revision> --json` continues interrupted work. `--exclude` records a narrower selection; `--ref` pins the requested Git ref before hydration. Acquisition does not imply semantic coverage.

`scan` now captures full inventory before creating the bounded Java compatibility view. A larger input is retained for an appropriate semantic worker rather than truncated to that view. See the [M8.4.1 contract](../docs/architecture/m8-streaming-acquisition.md) for identities, authentication policy, archive support, resource waiting and migration boundaries.

`evo readiness <revision> --section requirements --limit 20 --json` plans every captured build root and candidate context, with a durable ownership ledger and native validation requirements. Sections also include `roots`, `modules`, `contexts`, `recipes`, `compilation-groups`, `selection` and `unknown`. `--source-set main` and `--variant baseline` record explicit context selection. `scan` binds the plan digest into analysis identity and reports pending native build validation; it does not execute target builds. Exact release acquisition uses Central by default, explicit `--artifact-repository` overrides, or `--offline`. Step 3 has a registered Hyper-V Core MVP execution lane; general dynamic dependency/native semantic qualification and automatic captured-context composition remain separate obligations. See the [readiness contract](../docs/architecture/m8-build-readiness-planner.md).


`evo captured-java <registered-directory> --json` passively reconstructs an operator-registered captured Java context, verifies its public trust registry/attestations and exact bytes, then runs Java/Spring analysis, persists child/root graphs and returns separate semantic/audit summaries. PARTIAL returns exit 3 and retains genuine gaps; successful replay does not mean runtime-container or semantic accuracy certification. `registration.json` must be selected outside analyzed source snapshots; no target build, signer, private key or test classpath is used. Existing `gaps --gap-view CURRENT|HISTORY|ALL --graph <saved-graph> --context <id>` queries retain their per-context scope. See the backend registration contract in [backend README](../backend/README.md#registered-captured-java-replay).

Registered Java replay forwards authenticated readiness proofs to both context and root; six closed readiness requirements do not force semantic COMPLETE. Full graph/publication references retain captured audit bindings and may change between semantically equivalent captures. The additive Spring semantic projection is explicitly DEFAULT_SCOPE_TRUTH_NONE; unsupported projection scopes remain visible. See the [review closeout](../docs/tasks/m8/04-step4-phase2-execution.md#review-follow-up--2026-10-26) for the changed-capture determinism control and final packaged verification.
