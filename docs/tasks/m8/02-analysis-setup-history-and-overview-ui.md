# M8.2 — Live workbench and repository connections

**CONFIRMED owner request, 2026-10-16:** audit/repair the completed UI so every exposed action works, use English, remove fabricated metrics and decorative/internal milestone/social/account controls, support local and Git repositories, repair tab/card collisions and improve guidance and usability. M8 remains in progress; no gate promotion is requested.

## Audit and implementation

The original UI contained hard-coded repo names, history, 78/100 score, seven invented findings/digests, false Spring annotations on platform classes, API routes presented as analyzed repository endpoints, fixed counts/profiles and simulated impact. Its successful static/visual checks established asset delivery and appearance only. The earlier task description's claim of verified fallback data is withdrawn.

The replacement preserves the dark sidebar/topbar/tab base and consumes real M8.1 services: connections/setup selection, idempotent analysis, observed progress, cancellation, paginated history, exact graph/context/report discovery, canonical inventory and assessment, explicit project/referenced type search, bounded graph scopes/projections, report findings/rules, Spring/configuration inventories, incoming structural impact, gaps, provenance/evidence/receipt inspection and real JSON downloads. Missing/partial/withheld data is visible and never replaced with mock values. Internal version/hash details remain in inspectable evidence rather than primary product marketing.

`RepositoryConnections` is enabled only by the local launcher or an explicit embedding. It safely registers actual absolute local folders or public HTTPS GitHub/GitLab/Bitbucket selections. Git acquisition is isolated from ambient credentials/configuration/hooks/templates, shallow/default-branch, cancellable and monitored for time/size. It records actual revision/source hashes, retains pre-snapshot failures with typed provider diagnostics and never executes the target build/application. Existing registration-only constructors remain compatible. [UI/input contract](../../architecture/m8-workbench-ui.md), [ADR-006](../../decisions/ADR-006-loopback-repository-connections.md).

Semantic hidden panels, wrapping layouts, breakable long labels, native focus-trapping dialogs, keyboard tab navigation and view/poll cancellation protect tab/context/repository switching. Cytoscape.js 3.30.2 and its MIT license are vendored with a SHA-256 pin; all runtime assets are local. System font fallback removes font-network dependency. CSP permits only same-origin scripts/connections.

## Bounded verification

Commands run from the project root with JDK 21; each command selects a trusted platform module, never an analyzed target lifecycle:

```powershell
node --check backend/src/main/resources/web/app.js
node --test backend/src/test/js/workbench-model.test.cjs
.\mvnw.cmd test -pl backend "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd install -pl backend "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd test -pl cli "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
.\mvnw.cmd package -pl cli "-DskipTests" "-Dplatform.build.directory=target/evo-build" "-Denforcer.skipRules=reactorModuleConvergence" -q
python cli/src/test/python/packaged_jar_smoke.py cli/target/evo-build/evo.jar
```

Fresh results: **75 backend tests / 13 suites**, **58 CLI direct-consumer tests / 8 suites**, zero failures/errors/skips; **4 Node tests** pass. Only the single-module-inapplicable Enforcer convergence rule is skipped. Installation skips tests and is not counted as verification. These commands exceeded the five-second inner-loop target; no sub-five-second claim is made.

Focused controls include local connection/reuse, credential/SSRF/URL/path rejection, unavailable Git with deterministic pre-snapshot diagnostics and exact provider version, strict/disabled connection routes, host-path redaction, local static assets/HEAD/CSP and canonical source/Maven/policy/degraded/cancellation/publication behavior. The OpenAPI control was observed failing at 12 versus required 13 routes, then passed after documenting the additive route. The initial connection control could not compile before its new API existed; that is not claimed as assertion-level regression evidence.

The published-report presentation regression was observed failing when `evaluationStatus: WITHHELD` was ignored, then passed after preserving that qualifier and the observed `returnedTotal` denominator. Packaged smoke passes seven real CLI journeys, with **1,147 platform classes and zero error stubs**. Final runtime HTML/CSS/JavaScript/graph-asset SHA-256 values are compared directly against the source assets; the shaded JAR is used to launch the actual browser server. This is a packaged-runtime claim, not a clean-reactor build claim.

Browser checks on the actual loopback server:

- Compact real Java source analysis produces PARTIAL with actual gaps, and no fabricated score. Search resolves `demo.Greeting`; evidence and dependencies come from canonical responses.
- The owner's `https://github.com/hoangnguyen1007/MiniChatServer` is actually fetched and analyzed, yielding PARTIAL with **46 unique recorded limitations** in the observed run. Project-type filtering returns **Room, Server, ClientServer, Client**. JDK references are available separately. This is a functional source-acquisition/UI journey, not a benchmark or correctness certification of the repository.
- Real graph scope selection, zoom-in/out/fit and JSON download work. Search resolves an exact `Server` candidate; incoming impact returns two recorded paths. Spring endpoints/configuration and policy absence use honest empty/partial states.
- Cancel transitions through CANCELLING rather than claiming immediate termination. Evidence dialogs, source metadata and download actions use actual backend data.
- Optional policy YAML on the compact local source produces a genuine `demo.no-cross-type` finding and a qualified assessment; both findings and evaluated-rule inventories open actual Evidence Bundle data.
- A real server stop/restart reproduced stale repository/job selection. The final refresh path clears unavailable process-local selections and reconnects to the registered local repository; offline state disables analysis and never inserts fixture results.
- Supplemental limitation pagination/download returns **27/27 distinct records**, separately from the observed 46-record union in the job receipt. Human-readable reason/category labels use exact matching typed gap identities where available; bulk records collapse into expandable entries, and opaque continuation tokens remain in downloaded/raw data rather than the reading surface.
- At a 390×844 viewport, DOM geometry checks show no horizontal overflow, zero text/badge intersections on project rows and exactly one visible tab panel. Desktop checks at 1440×1000 also show one visible panel and navigation below the search bar. Broader accessibility/performance acceptance is not inferred from these checks.

## Limits and exact next slice

Changed files are the backend connection/job/runner/launcher/API/static-serving adapters and OpenAPI, local web HTML/CSS/JS/model/vendor assets, backend/Node regression tests, backend guide, M8 contracts/task index, ADR-006 and `docs/current-state.md`. Pre-existing Cline/Jules reference exports are preserved. The local standalone runtime and live browser are left available on port 8080 with the actual platform checkout registered and MiniChatServer analyzed. Screenshot: `backend/target/evo-build/workbench-final.jpg` (normal ignored UI verification output). No unresolved human decision or operational blocker remains for this bounded repair; the limitations below remain explicit.

Private authentication, SSH/arbitrary Git hosts, branch/tag selection, submodule acquisition, network dependency fetching, durable connection/job/report indexing and hard OS resource isolation are not delivered. Public Git needs an installed trusted Git executable. Input monitoring may overshoot between checks; retained checkouts have no automatic destructive cleanup. Backend scores/formulas and upstream semantic gaps are preserved. Configuration inventories are not a witnessed profile comparison workflow.

Verification is implementer self-review; no independent reviewer, full reactor, corpus benchmark, formal reproducibility package, commit or push. **Exact next slice: M8.3 — module/package/member explorer, witnessed Spring configuration comparison and registered end-to-end workbench acceptance.** Track B stays gated.
