# M3.1 Passive Effective-POM Model — Verification

Date: 2026-09-06. Scope: the first bounded M3 implementation slice, after checkpoint `cc5395e`. This record does not pass G2, independently accept M2, or claim full Maven CLI/workspace/classpath equivalence.

## State before and changes

The initial reactor had 98 tests and no build-model implementation. The workspace already contained human documentation/governance changes and the untracked provisional M3 contract plus Gemini proposal audit. Those changes were preserved; only the M3 contract received this task's implementation refinement. No commit, push or history operation was performed.

Delivered:

- Neutral immutable `BuildModelProvider` input/output contracts under `analyzer/.../buildmodel/`; input and result schemas `build-model-input-v1` / `build-model-result-v1`.
- An isolated `analyzer-maven` reactor module using Maven Model Builder 3.9.16, provider version `3.9.16-m3.1`.
- Passive root/nested aggregation, parent inheritance, explicitly supplied parent/BOM inputs, property interpolation, controlled profiles and ordered direct/managed dependency projections.
- A real root-module `.` case, preserving all existing non-root M1 identity preimages; its golden hash was computed independently from the documented JSON tuple using .NET SHA-256.
- Typed missing/unsafe/invalid/unsupported/limited outcomes, immutable POM evidence, read-attempt history, explicit projection limitations, XML safeguards and isolation from host properties/settings/lifecycle execution.

Changed production groups are the root POM, the new Maven adapter, neutral build-model contracts, and three existing module-path contract files. Tests add root/immutable-input/invariant cases, 20 model fixture tests and one actual-platform-POM smoke test. README, current state, M1/M2 root-boundary notes and the M3 contract reflect the delivered scope. Exact source and artifact hashes are in [verification summary](verification-summary.json).

## Verification actually performed

Environment: Windows 11 amd64, Oracle JDK 21.0.12.1, Maven Wrapper's pinned Maven 3.9.16. See [toolchain output](toolchain.txt).

PowerShell command context:

```powershell
$env:MAVEN_USER_HOME = 'C:\Users\Admin\.m2'
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer-maven -am test
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' clean verify
```

| Check | Observed result | Evidence |
|---|---|---|
| Initial root `verify` | 98 tests passed, 0 failures/errors/skips | Observed tool execution before production edits; not the final-state claim |
| Root-module regression against baseline | 1 test errored at the unsupported `.` module path | Observed tool execution before the additive root implementation |
| Initial model specification run | 11 tests errored at the explicit not-yet-implemented provider | Observed before implementing the provider |
| Self-review regression run | 15 tests executed; 3 failures exposed BOM profile propagation, false missing-parent reporting and unresolved-version handling | [Raw red run](self-review-regressions-red.txt) |
| Focused model/contract suite | 45 analyzer + 21 Maven tests passed, 0 failures/errors/skips | [Focused run](focused-model-tests.txt); this precedes the final package relocation and explicit result-limitations field |
| Final root `clean verify` | **123 tests: 45 analyzer + 21 Maven + 56 JavaParser + 1 backend; 0 failures/errors/skips** | [Raw final reactor output](reactor-clean-verify.txt), [selected report counts and hashes](verification-summary.json) |
| Final diff/visibility checks | Whitespace check passed; new source files are visible to Git | Source package uses `buildmodel`, avoiding the existing `**/build/` ignore rule |

An initial PowerShell invocation with an unquoted dotted `-D` option failed before tests and was corrected. It is not counted as a regression. Maven's invalid unresolved dependency version correctly withholds the effective model; the corresponding new test was corrected to assert withheld output plus typed failure/evidence, rather than demanding successful recovered output. Production retained this strict behavior.

All final tests ran after a clean rebuild of the renamed package; stale compiled classes cannot explain the pass. No standalone semantic benchmark, Docker/Linux repeat, external-repository coverage campaign or independent review was performed in this slice. Existing JavaParser and Maven API deprecation compiler notes remain non-failing build observations.

## New evidence and limits

The fixtures use literal independently specified expectations for effective versions, scopes, ordering, exclusions, root/aggregator paths and failure outcomes. Negative cases cover missing module/parent/BOM, nonmatching artifact GAV, unresolved expressions, host-dependent profiles, DTD/XXE (including UTF-16), excessive XML depth, module/parent/BOM cycles, duplicate module identity, byte/count/read bounds and extension declarations. Deterministic reruns vary input map order, ambient system properties, locale and timezone. Raw bytes and policy collections are defensive copies; inventory digest mismatch is rejected.

The POM smoke test supplies the five actual platform POMs as an explicitly scoped fixture. It checks all five models, inherited Java 21/UTF-8 properties and managed JUnit version. It is **not** a full repository snapshot/acquisition or G2 representative-repository checkpoint. No target lifecycle is invoked by the provider; the ordinary platform Maven test/build command is separate from passive target model evaluation.

Profile activation/deactivation IDs are applied consistently across reactor, parent and imported POMs as declared analysis policy. Undecided JDK/OS/file activation is withheld. Relative-parent misses/denials preserve attempts; a successful supplied-artifact alternative does not leave a false missing-parent problem. Invalid models never become apparently valid effective output.

This slice does not implement filesystem/cache acquisition, automatic reactor-coordinate indexing, source-root/compiler configuration projection, source decoding, alternate platform views, dependency mediation/JAR manifests, generated-source provenance, or the full normalized capability-gap/acquisition/conflict model. Result limitations carry these boundaries. Model declarations are not proof of acquired dependencies or runtime build behavior.

## Decisions and handoff

Implementation choices within authorized M3: isolated pinned Maven adapter, passive immutable-input boundary, versioned projection schemas, additive root marker, explicit activation policy and conservative resource bounds. These have bounded implementation evidence; they are not a new human-approved gate. No further human decision or execution blocker is required for the next bounded implementation slice. Review here was implementer self-review, not independent review.

Durable state: [current state](../../current-state.md) and the [M3 contract](../../architecture/m3-workspace-build-model.md) identify delivery and remaining work; M1/M2 documentation only updates the now-implemented root case. Roadmap scope/gates are unchanged by this task.

Exact next task: M3.2 — project inherited effective build/compiler declarations into per-module `main`/`test` source plans with provenance. Keep syntax level, platform requirement and charset evidence separate; retain unresolved/plugin/generated-source effects explicitly. Acquisition/classpath/platform integration and G2 acceptance follow their own tests and gates.
