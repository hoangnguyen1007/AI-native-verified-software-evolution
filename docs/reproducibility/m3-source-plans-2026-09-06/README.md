# M3.2 Declarative Source Plans — Verification

Date: 2026-09-06. Initial state: clean workspace at `5c2c7d4` with M3.1 committed and 123 root tests. Scope: the authorized M3.2 slice; no milestone transition, G2 acceptance, commit or push.

## Delivered and verified

`SourcePlanModel` in the neutral analyzer module and `SourcePlanProjection` in the Maven adapter add main/test candidate plans, inherited directories, independently retained compiler declarations, syntax/bytecode/API requirements, portable charset declarations, generated-directory hints, plugin/execution configuration trees and POM derivation evidence. Model output is now `build-model-result-v2`, provider `3.9.16-m3.2`; request and module identities are unchanged. `hasGaps()` includes source-plan gaps. The M3 contract documents this API change and the refined encoding precedence.

Tests add 12 projection cases and 3 neutral contract cases. The existing five-POM platform smoke test now checks actual per-module main/test paths and inherited Java 21/UTF-8 settings. Existing model tests still assert successful model construction through `problems()`, independently of expected source-plan gaps. All affected Java files and platform POM inputs are hashed in [verification-summary.json](verification-summary.json).

Environment: Windows 11 amd64, Oracle JDK 21.0.12.1, pinned Maven 3.9.16; see [toolchain.txt](toolchain.txt).

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer-maven -am test
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

| Check actually run | Result | Evidence |
|---|---|---|
| Initial specification | 5 errors at the explicit unimplemented projection | [Initial red](initial-specification-red.txt) |
| Edge-case regressions | 11 tests, 3 failures and 1 missing-setting error: portable charset handling, nested-root overlap, result gap aggregation and unknown compiler properties | [Edge red](edge-cases-red.txt) |
| Neutral contract regression | 3 tests, 1 failed rejection of a declared value with absent origin | [Contract red](contract-red.txt) |
| Focused analyzer/Maven suite | 48 + 32 passed before the final managed-plugin test; the subsequent 12-case projection run also passed | Observed tool executions; final suite below supersedes these counts |
| Final root `verify` | **138 tests: 48 analyzer + 33 Maven + 56 JavaParser + 1 backend; zero failures, errors or skips; exit 0** | [Raw reactor output](reactor-verify.txt), [report totals](verification-summary.json) |

The initial specification was refined where Maven's super-POM supplies managed plugins: it counts two explicitly active plugins rather than assuming no inherited managed defaults. Charset tests distinguish malformed syntax from a legal but unsupported name. A test-method typo failed compilation before the recorded contract red run; that setup failure is not regression evidence. Final verification is a regular reactor build, not a claimed clean/environment-matrix run. Existing Maven/JavaParser deprecated-API compiler notes remain non-failing.

## Evidence boundaries and handoff

Literal fixture expectations cover independent test overrides, plugin-management/default-goal merging, explicit configuration/property precedence, parent provenance, active profiles and sibling isolation, repeatable result identity, immutable collections, aggregator conventions, path containment/overlap, malformed/unresolved settings and retained toolchain/generator/unknown-option declarations. Official pinned compiler references are linked in the [M3.2 contract](../../architecture/m3-workspace-build-model.md#implemented-slice-m32--declarative-source-plans).

These are declaration plans. No target plugins, lifecycle, annotation processors, host property discovery or installed charset-provider lookup are run by the provider. Files have not been acquired or assigned ownership; nonstandard charset decoding, alternate platform views, dependency/classpath acquisition and broader plugin interpretation remain open. The six standard charset families and documented aliases form a deterministic declaration table, not a restriction on future decoding-provider capability. A declared compiler requirement is not proof of compiler/parser/API availability or complete Maven CLI equivalence.

Review was implementer self-review; independent review and external-repository coverage were not performed. No execution blocker or new human decision is required for the next bounded slice. README, current state and the M3 contract record delivery; roadmap and milestone/gate scope are unchanged. Prior M3.1 evidence was preserved.

Exact next task: M3.3, safe filesystem acquisition of an explicitly selected repository into immutable snapshot/POM inputs and candidate source ownership. First protect containment, symlinks, byte/file limits and missing/overlapping-file evidence; retain main/test/module separation and prohibit target lifecycle execution.
