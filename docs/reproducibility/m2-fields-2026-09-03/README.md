# M2 field read/write slice — 2026-09-03

**CONFIRMED by execution:** final root `verify` passed **77 tests**, zero failures/errors/skips (analyzer 39, adapter 37, backend 1). The new source fixture checks **25 exact field occurrences: 16 reads and 9 writes**, including source owners, targets, origins, full spans, negative controls and deterministic reruns. M2 remains incomplete; G2 did not advance.

## Change and evidence

Started clean at `5c1c415`, with the previous declarations/calls and declared-type slices committed. The human authorized the next field-access task using Codex only and proportionate verification. No new API design, dependency upgrade, research or independent-review campaign was needed.

`FieldExtraction` now identifies field/enum-constant bindings and separately classifies access mode. Simple assignment writes; compound assignment/increment reads and writes. Receiver expressions are visited separately, and an array-element update reads its array field. Parentheses preserve the target's access mode. The existing mapper supplies project identities, execution owners and exact spans; external fields use the actual declaring JAR/JDK type and verified artifact scope.

Locals/parameters and known type/package qualifiers do not produce field facts. Unresolved explicit accesses retain degraded occurrences. A bare name whose field-vs-local classification is unknown produces an unmapped `java.unclassified-name` ledger entry. An unresolved member chain rooted in a known value is retained rather than discarded as a package qualifier. Array `length`, annotation-value field accesses and unsupported record contexts have explicit unsupported handling without invented field entities or type callers.

Version changes: catalog `m2-java-3`, adapter `3.26.1-m2.3`; canonical Java identity and coordinate versions are unchanged. Both field categories remain `PARTIAL`.

Changed code: `Extraction`, new `FieldExtraction`, `FrontendRequest` catalog version, new `FieldAccessTest`, `ResolutionInputsTest` and [Accesses.java](../../../analyzer-javaparser/src/test/resources/m2/fields/Accesses.java). Seven field tests plus dependency tests cover the 25-label fixture, initializers/lambdas/constructors, missing names/chains, arrays, enum constants, unsupported contexts, hidden/inherited fields, multiple declarators, exact JAR origins, removed dependencies and classpath order.

Preserved outputs: [fixture result](fields.json), [input manifest](fields-manifest.json), [tested file hashes](source-hashes.json), [root console output](verify.txt). JSON outputs were copied byte-for-byte from the test generator. Labels were authored from source; the fixture also compiled successfully with JDK 21. This is not a new compiler-binding oracle or full-field-accuracy claim.

## Commands and results

All Maven commands used `MAVEN_USER_HOME=C:\Users\Admin\.m2`, the existing local dependency cache and the supplied unrestricted execution profile. No Maven permission blocker occurred.

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

Exit 0; full reactor tests and packaging passed on the final production/test state. This was `verify`, not a clean build. Earlier clean-build archives remain unchanged.

Focused `test` runs used `-pl analyzer-javaparser -am`, `-Dsurefire.failIfNoSpecifiedTests=false` and `-Dtest=FieldAccessTest`, `FieldAccessTest,ResolutionInputsTest`, or the individual missing-receiver test. Initial four specification tests failed on missing extraction (zero field occurrences), then passed. The later receiver regression failed with 4 instead of 6 occurrences; the fix and final seven-case field suite passed. Full verification also reran the earlier frozen declarations/calls, Unicode, types, contract and build tests.

The controlled fixture compiled with exit 0 using:

```powershell
javac --release 21 -proc:none -encoding UTF-8 -classpath analyzer-javaparser/target/m2-fixture-classes -sourcepath analyzer-javaparser/target/m2-fixture-classes -d analyzer-javaparser/target/m2-fixture-classes analyzer-javaparser/src/test/resources/m2/fields/Accesses.java
```

The output directory was created beforehand. No target lifecycle or annotation processor ran. Final code/docs diff and whitespace inspection passed.

## Boundaries and handoff

This slice does not establish general compiler-invalid/ambiguous-field handling, Java access-control validation, exhaustive pattern/switch/modern-Java coverage or runtime field access behavior. Existing record/constant-specific enum-body and implicit-member limitations remain. Array length needs an explicitly supported non-declaration representation in a later slice. No independent review or scale/performance claim is made.

No new human decision or execution blocker remains. No Antigravity, commit, push or M3 work occurred. Updated durable state: current-state, M2 implementation notes and root README. Earlier raw evidence was preserved.

**Exact next task:** method-reference extraction with static, bound/unbound instance, overload and constructor fixtures; preserve receiver field reads, distinct reference semantics and honest unsupported handling for array/implicit constructors.
