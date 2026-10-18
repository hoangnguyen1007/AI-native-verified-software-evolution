# evo — architecture intelligence in your terminal

`evo` opens a scrollback-friendly command palette in a terminal. Direct commands work in scripts and CI. The owner selected short English commands and English/Vietnamese presentation. The original logo combines an interlaced hexagonal orbit and an eye aperture in emerald, mint and silver with a lime center; text uses soft blue and separators use quiet gray. This follows the latest request for a distinct design inspired by the supplied OpenAI, AGY and NVIDIA references. The header contains only the CLI name/version and language, with no slogan or Java runtime label. Navigation uses **Start / Bắt đầu** and **Commands**; the short descriptions are **Check rules / Kiểm tra quy tắc** and **Explain confidence / Giải thích độ tin cậy**. Terminal background colors are respected; status text remains readable with colors disabled.

## Build and launch

Use JDK 21 and the platform's Maven Wrapper. These commands build trusted platform modules, never an analyzed repository. On a fresh checkout, install the required module artifacts individually, then package the CLI:

```powershell
.\mvnw.cmd install -pl analyzer "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-maven "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-filesystem "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl analyzer-javaparser "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl backend "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package -pl cli "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/evo-build/evo.jar
Copy-Item cli/target/evo-build/evo.jar cli/target/evo.jar
.\evo.cmd --lang vi
```

The isolated build produces `cli/target/evo-build/evo.jar`; validate and copy it to the launcher path `cli/target/evo.jar` as above. It is a shaded JAR with its runtime dependencies, including the pinned Jansi 2.4.2 native console bridge. The separate output directory prevents IDE incremental compilation from replacing Maven-built classes. The packaged smoke rejects Java error stubs and exercises real source/query/report paths. The module-install commands skip tests and are not verification evidence. Only the single-module-inapplicable convergence rule is skipped; all other Maven enforcement remains active.

Windows launchers: `evo.cmd`, `evo.ps1`. POSIX: `sh ./evo`. Standalone: `java -Xmx4g -jar /path/to/evo.jar`. On Windows, the process prepares UTF-8 console output and enables/verifies native virtual-terminal processing before rendering. It restores the original output mode/code page on normal exit and JVM shutdown; launchers also restore their captured encoding. No persistent console setting is changed. Launchers preserve the caller's working directory and exit status. They do not auto-build or change PATH. Add the checkout directory to your shell's PATH to invoke `evo` by name from other directories. Use UTF-8 terminal input for the standalone JAR.

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

`scan` records repository acquisition, JDK symbol evidence, explicit Java-release/UTF-8 fallback policy, exclusions, pipeline gaps and a canonical graph snapshot. Default release is **21**, not an inferred repository fact; use `--jdk DIR --release N` for the intended analyzed platform. Build-declared levels remain authoritative where modeled; incompatible/missing evidence remains degraded. The source-selection policy records exclusions for `.git`/`.evo`, Maven `target`, Gradle output/cache, Node dependencies, test trees and unrelated assets. For a successfully modeled root Maven build, unrelated Java/POM fixtures outside its declared module source roots are also excluded. `--include-tests` admits tests; `--all-files` bypasses source filtering; `--exclude PATH` adds explicit exclusions. Every exclusion remains in acquisition provenance. `.gitignore` is not interpreted.

Scan passively composes existing Maven effective-model and classpath providers against `$user.home/.m2/repository` or explicit `--maven-cache DIR`. It captures exact available artifact hashes and ordered classpaths; it reads no ambient Maven settings and makes no network request. It never executes target Maven/Gradle/plugins/applications or imports unproved reactor outputs. Missing dependencies, unsupported compiler settings, plugin effects and unproved Spring scopes remain typed gaps. Definite captured handwritten sources can be analyzed while plugin effects remain qualified. Richer verified snapshots remain importable with `open`.

The scan receipt contains exact issue/root-cause/gap category counts and the verified graph identity. Every full typed gap remains in that canonical snapshot. `gaps --json` returns a bounded inventory; `explain <number>` and `evidence DIGEST --json` expose safe metadata and provenance. Select the receipt’s Build / acquisition context to inspect acquisition gaps. The receipt avoids repeating a potentially huge ledger. Failed scans preserve the previous selected snapshot and may save an unselected diagnostic graph. Snapshot save/load uses streaming canonical encoding/decoding and hashing instead of materializing duplicate whole documents. Snapshot storage/import is bounded to 1,000,000,000 UTF-8 bytes/characters, depth 128 and sixteen million JSON values. Query/export output retains its separate 32 MB ceiling. VCS is not inspected: revision/dirty labels are caller-supplied provenance. This is a bounded CLI slice, not certification of the entire M7 evidence workflow.

Session snapshots and selected policy bytes are stored under `.evo` in the current working directory. `-w DIR` chooses another session directory. `-g FILE` reads a verified canonical snapshot without selecting it. `open FILE` selects one. When several analysis contexts exist, `show` lists numbered contexts and `use N` remembers the exact selection. CLI never silently fuses main/test/modules/Spring contexts or picks an arbitrary first analysis. In the palette an already verified immutable graph is reused between commands; direct invocations verify the snapshot anew. `use N` changes only the small selection pointer.

Human `find`, `gaps`, `spring` and `config` save numbered references to their displayed page. Use `deps 1`, `impact 1`, `explain 1` or `paths 1 2` in the same graph/context/scope, including in a later process. A new human inventory replaces that page; stale/foreign references are rejected. JSON inventories do not change these references. Full symbol IDs always work. Names/digest prefixes still require a complete unique bounded lookup; numeric references point to exact observed rows even when the search denominator is incomplete.

## Commands

| Command | Purpose |
|---|---|
| `scan [DIR]`, `open FILE`, `use ID\|N` | Analyze passive inputs, import a snapshot, select exact context |
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

It creates an isolated hidden console with VT initially off, reads actual screen cells/color attributes and checks UTF-8, width, cleanup, actual full-prefix palette commands, numbered dependencies/impact, language switching and redirected JSON. See the [Windows rendering investigation](../docs/research/m7-windows-terminal-rendering.md), [M7.2 contract](../docs/architecture/m7-evo-cli.md) and [verification record](../docs/tasks/m7/02-evo-cli.md). External-corpus acceptance, native executable packaging and the M8 visual workbench remain separate.

## Durable full acquisition

`evo acquire <directory|HTTPS-URL|ZIP> --json` captures all selected bytes and inventory on disk. Use the returned revision with `evo inventory <revision> --limit 20 --json` and its cursor. `evo acquire <same-input> --resume <revision> --json` continues interrupted work. `--exclude` records a narrower selection; `--ref` pins the requested Git ref before hydration. Acquisition does not imply semantic coverage.

`scan` now captures full inventory before creating the bounded Java compatibility view. A larger input is retained for an appropriate semantic worker rather than truncated to that view. See the [M8.4.1 contract](../docs/architecture/m8-streaming-acquisition.md) for identities, authentication policy, archive support, resource waiting and migration boundaries.
