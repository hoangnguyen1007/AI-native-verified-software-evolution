# M4B.1 verification — 2026-09-13

## Scope and outcome

**CONFIRMED by fresh execution:** M4B.1 delivers the immutable configuration-space
identity, typed condition IR, exact M3 build projection and bounded condition-row
validator described in the [architecture contract](../../architecture/m4b1-configuration-space-condition-ir.md).
The final six-module reactor (parent plus five test-bearing modules) passes
**271 tests in 47 suites, zero failures/errors/skips**. Its report files were
checked for freshness against this run; no stale suite reports were counted.

This is implementation self-verification. No independent reviewer or subagent
was used. No real-repository benchmark, Spring runtime oracle, target lifecycle,
network evidence acquisition or solver benchmark was run. G3 is unchanged.

## Commands and environment

Windows 11 amd64; Oracle JDK 21.0.12.1; repository wrapper Maven 3.9.16; UTF-8.
Explicit Maven home/cache settings avoid ambient cache discovery:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd --version

# Each focused suite was run separately; substitute one class name at a time.
.\mvnw.cmd test -pl analyzer -am "-Dtest=ConditionExpressionTest" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
.\mvnw.cmd test -pl analyzer -am "-Dtest=ConfigurationSpaceTest" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
.\mvnw.cmd test -pl analyzer -am "-Dtest=ConditionModelTest" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
.\mvnw.cmd test -pl analyzer -am "-Dtest=FrontendInputAssemblerTest" "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q

# Executed once, after the focused suites passed.
.\mvnw.cmd verify "-Dmaven.repo.local=C:\Users\Admin\.m2\repository" -q
```

All final commands exit 0. This was `verify`, not `clean verify`; no clean-build
claim is made. The default wrapper initially attempted an inaccessible `C:\.m2`;
explicit documented Maven settings resolved that configuration problem. A
module-only command without `-am` failed the existing reactor-convergence rule.
The `-am` focused commands include only the analyzer and its parent aggregator.
JUnit cache compilation required the host's supported execution approval; no
cache relocation or sandbox bypass was used. Focused test execution remained
under one second per class in the final run, but Maven startup/recompilation
made some complete command wall times exceed the preferred five-second inner
loop target. Output was captured to files and only compact failure excerpts read.

## Results and evidence

| Test class / scope | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| ConditionExpressionTest | 5 | 0 | 0 | 0 |
| ConfigurationSpaceTest | 8 | 0 | 0 | 0 |
| ConditionModelTest | 11 | 0 | 0 | 0 |
| FrontendInputAssemblerTest | 6 | 0 | 0 | 0 |
| Focused total | 30 | 0 | 0 | 0 |
| Entire final reactor | 271 | 0 | 0 | 0 |

Module totals: analyzer 119; analyzer-maven 67; analyzer-filesystem 12;
analyzer-javaparser 72; backend 1. The three new M4B.1 classes contain 24 tests;
the six existing assembler tests include extended real M3-to-M4 assertions.

- [test-suites.json](test-suites.json): mechanically extracted final Surefire
  suite names, counts and durations, with no raw system properties.
- [source-hashes.json](source-hashes.json): SHA-256 of the 18 relevant production,
  test and compact fixture files at verification time.
- [artifact-hashes.json](artifact-hashes.json): exact five built module JAR hashes.
- [reactor.log](reactor.log), [expression.log](expression.log), [space.log](space.log),
  [model.log](model.log), [integration.log](integration.log): preserved quiet logs;
  empty successful logs are expected and counts come from Surefire reports.
- [red.log](red.log): initial compile-time failure for the new, not-yet-implemented
  public contracts. This is not claimed as a behavioral assertion failure.
- [constraint-red.log](constraint-red.log): behavioral regression, 10 tests with
  one failure; a state-dependent constraint incorrectly left its row REPRESENTED.
- [provenance-red.log](provenance-red.log): behavioral regression, 11 tests with
  one failure; stale or foreign source evidence was not qualifying its row.

Both behavioral defects were repaired at their causes and the final suites pass.
The source/JAR hashes are historical checkpoint references, not an instruction
that future source files must remain byte-identical. Existing M4-R0 evidence was
not modified. The new compact Kleene TSV preserves 21 registered R0 expectations;
its README records the upstream SHA-256 and projection scope.

## Established boundaries

Tests establish exact Unicode identity behavior and an independently calculated
golden expression digest; classpath order and platform sensitivity with host-path
independence; distinct realized/space identities; typed finite domains and
missing/empty/whitespace differences; explicit deployment/source precedence and
policy sensitivity; exact limit boundaries and overflow-safe counts; opaque and
stateful ordering; closed shared-expression rows; typed gap provenance and replay;
and retention of a 10,001-depth formula under an explicit expansion-limit outcome.
No UNKNOWN or opaque condition is folded into false or erased by Boolean folding.

Input-contract mistakes such as duplicate normalized set members, invalid tags,
wrong envelope layers and malformed Unicode are rejected. Real evidence gaps,
unsupported semantics, proposed OTHER partitions and budget exhaustion are
retained as typed partial/invalid outcomes. Feasibility and activation are not
evaluated; successful IR representation proves neither complete deployment
coverage nor framework/runtime correctness.

## Handoff

Before: M4A.1–M4A.2 evidence acquisition delivered; M4B.1 was the exact next task.
Completed: 11 neutral production classes, three new test classes, a compact M3
fixture helper, two fixture files, assembler integration assertions, and the
architecture/current-state records. No milestone scope or gate changed, and no
new consequential approval is needed. No Git checks, commit or push were
performed, following the user's explicit instruction to skip Git checks.

Next: **M4B.2 — evidence-to-IR lowering and bounded exogenous evaluation**.
Use exact M4A condition occurrences and explicit configuration inputs; verify
profile/property/normalized source-precedence behavior against accepted R0
fixtures before M4C registration-order or binding work. No implementation blocker
remains for the delivered M4B.1 boundary.
