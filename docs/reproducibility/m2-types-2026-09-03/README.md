# M2 declared-type implementation — 2026-09-03

**CONFIRMED by execution:** root `verify` passed **69 tests** (analyzer 39, JavaParser adapter 29, backend 1), with zero failures/errors/skips. A final correction uses complete written type spans for inheritance/permits/throws occurrences; the subsequent focused `verify` passed all **6 type tests** and rebuilt the adapter JAR. The full reactor was not repeated after that localized correction. M2 remains incomplete; no gate advanced.

## Work and state

This continuation started with the uncommitted initial declarations/calls slice already present on checkpoint `8f7582e`. The human asked for implementation with proportionate verification, using Codex only. The existing provisional M2 contract supplied the design; no research, comparator or independent review campaign was added.

Implemented:

- Neutral immutable `JavaType` and `TypeUseRecord` output, with original spelling, complete syntax span, owner, role, varargs and recursive arguments/array/wildcard/member-qualifier structure. Result validation rejects references to missing source/entity evidence and impossible resolved type structures.
- `has-parameter`, parameter/return/field types, explicit extends/implements/permits, throws, bounds and overlapping named type-use/type-argument occurrences. Inheritance and throws target the root type, never its generic arguments. Primitive/void detail has no fabricated entity edge.
- Known generic containers and member qualifiers survive unresolved leaves. Callable identity uses the known declaration erasure even when generic arguments are missing; incomplete declaration types remain partial.
- Configuration catalog `m2-java-2` and adapter `3.26.1-m2.2`. Existing M1 identity preimages, `java:v1` tuples and coordinate version are unchanged.

Production files: `analyzer/.../frontend/{JavaType,TypeUseRecord,FrontendResult,FrontendRequest}.java` and `analyzer-javaparser/.../{TypeExtraction,Extraction}.java`. Tests: `FrontendContractTest` and six inline source cases in `TypeRelationshipsTest`. Expectations cover exact owners/targets/spans, generic structure, missing arguments/member types, erased signatures, primitive/void negative controls, sealed permits and deterministic reruns. Existing frozen declaration/call fixtures are unchanged and passed in root verification.

## Checks actually run

All wrapper invocations used `MAVEN_USER_HOME=C:\Users\Admin\.m2` and the supplied unrestricted execution profile. No permission prompt, cache relocation or security-setting change was needed.

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
.\mvnw.cmd -o '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer-javaparser -am '-Dtest=TypeRelationshipsTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

Raw output: [root verification](verify.txt), [final focused verification](final-types-verify.txt). Both exited 0. These were `verify`, not `clean verify`; the earlier slice's clean-build archive is preserved separately.

Focused `test` runs were also used during implementation. Observed regressions before their fixes: the initial three type cases had missing edges; `Base<String>` incorrectly added a second extends edge; a missing generic argument removed the callable identity; a missing member type omitted its known qualifier. The corrected six-case suite passed. `SemanticBoundariesTest` also passed alongside the erasure change. Final diff/whitespace inspection was performed; no broader optional test campaign was run.

## Limits and continuation

Coverage for the new families remains `PARTIAL`. Type uses currently cover declaration signatures/hierarchy, not local variables, casts, construction expressions, pattern types or explicit invocation type arguments. Records, constant-specific enum bodies, implicit source callables, derived relationships, field accesses, method references and annotations retain their existing unsupported/pending boundaries. General compiler-invalid programs and full modern-Java coverage are not established by these cases. No new independent review or compiler-oracle validation is claimed.

Routine implementation decisions used the existing authorization; no new human decision or execution blocker remains. No Antigravity, commit, push or M3 work occurred. Updated durable state: `docs/current-state.md`, M2 architecture implementation notes and root README. Earlier raw archives are unchanged.

**Exact next task:** implement field read/write extraction using the existing entity/origin/span/execution-owner helpers, with focused cases for assignment, compound assignment/increment, receiver reads, inheritance/static imports, unresolved access and local-variable negative controls.
