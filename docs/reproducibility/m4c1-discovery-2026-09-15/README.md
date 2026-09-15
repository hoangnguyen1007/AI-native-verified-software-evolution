# M4C.1 focused verification

Date: 2026-09-15. Scope: [registration plan and discovery contract](../../architecture/m4c1-registration-plan-discovery.md).

The implementation began from the clean M4B.2 checkpoint `67fcf42`. Final verification was narrowed by the owner's 2026-09-15 instruction to conserve usage, avoid lengthy testing, and omit Git checks. No full reactor, external repository benchmark, runtime-container replay or independent review was performed for this slice. The supplied Gemini observation concerns the predecessor comparator; it is not recorded as an independent audit of the entire slice.

## Focused checks

The three focused suites account for **32 tests**, with zero failures, errors or skips in their saved reports:

| Suite | Tests | Evidence boundary |
|---|---:|---|
| `RegistrationDiscoveryTest` | 18 | Parse/register call sites, subtree guards, missing order, opaque callbacks, candidate histories, content identity, state chaining, cycles and limits |
| `RegistrationPlanTest` | 8 | Phase/ordering provenance, invalid edges, names/container scope, missing sources/parents and canonical identities |
| `AutoConfigurationOrderingTest` | 6 | Before/after traversal, metadata-only intermediates, ties, missing versus absent metadata, cycles and deterministic limits |

Each suite was run separately. The final comparator change was checked with `AutoConfigurationOrderingTest`; the final opaque-condition safeguard was checked with `RegistrationDiscoveryTest`. Changes outside those suites' affected inputs do not imply a fresh reactor check. Reports and selected console logs are retained under `raw/`; `summary.json` is generated from those reports, with exact file SHA-256 values in `checksums.json`.

Commands, from the repository root on Windows:

```powershell
$env:MAVEN_USER_HOME = 'C:\Users\Admin\.m2'
.\mvnw.cmd test -pl analyzer '-Dtest=RegistrationDiscoveryTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:\Users\Admin\.m2\repository' -q
.\mvnw.cmd test -pl analyzer '-Dtest=RegistrationPlanTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:\Users\Admin\.m2\repository' -q
.\mvnw.cmd test -pl analyzer '-Dtest=AutoConfigurationOrderingTest' '-Denforcer.skip=true' '-Dmaven.repo.local=C:\Users\Admin\.m2\repository' -q
```

The wrapper reported Maven 3.9.16 and Oracle JDK 21.0.12.1. Enforcer is skipped only for isolated module checks because `ReactorModuleConvergence` rejects the parent-less selected reactor. Root verification was not run. Sandbox access failures for the existing Maven/JUnit cache were resolved through the supported escalation flow. Maven startup/compilation took longer than the repository's five-second iteration preference; recorded JUnit suite execution stayed below five seconds. This is no performance benchmark.

## Observed red/green evidence

Initial discovery tests failed against the empty interpreter; the Boot ordering tests failed against the unimplemented sorter. Additional focused tests caught importer-condition scope leakage, Java object-reference comparison in content-identity validation, invalid ordering-domain edges, stale ordering proof, and incorrect profile phase labeling. Their fixes passed the relevant suites. Only compact logs were inspected; existing R0 evidence was not modified.

The comparator audit identified that equal pending-index keys can collapse distinct strings in a `TreeSet`. The final comparator uses pending index followed by natural string order, preserving all predecessors. Existing ordering tests passed after this change; no separate exhaustive runtime differential claim is made.

The R0 S11/S12/S13/S24/S25/S26 cached sources matched their accepted SHA-256 locks before inspection. Tests consume authored normalized membership/order evidence and real M4A/M4B contracts. They establish bounded behavior and replay invariants; they do not establish automatic scan/import extraction, complete framework equivalence or G3 acceptance.

## Handoff

Changed files are the new `analyzer/.../spring/registration/` package, three focused test classes, the M4C.1 architecture contract and Spring overview link, this evidence package, and `docs/current-state.md`. Existing identity schemas and upstream evidence remain unchanged. No commit or push was requested or performed. No final Git inspection was run, as explicitly requested.

The normalized M4C.1 slice is delivered. Remaining boundaries include final registration, endogenous bean-state evaluation, alias/override resolution, injection binding, general membership/metadata acquisition, opaque code effects and wider framework conformance. Exact next task: **M4C.2 — Ordered Bean-Definition Registration and Endogenous Condition Evaluation**. No gate is advanced.
