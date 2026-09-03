# M2 modern Java implementation — 2026-09-03

## State and result boundary

Started from clean `dae013c` (77 reactor tests). The human authorized continued M2 production implementation using Codex only, without a design/research expansion. No Antigravity, commit, push, target application execution or M3 work was performed.

**CONFIRMED by focused tests:** method references, expression type uses, annotation uses, records, constant-specific enum bodies and bounded implicit-member evidence are implemented. The neutral port now includes annotation-use metadata and derived relationship records. JavaParser/SymbolSolver is pinned to **3.27.1**, adapter `3.27.1-m2.4`, catalog `m2-java-4`; Java identity and coordinate versions remain unchanged.

M2 acceptance is **OPEN**. This is implementation and bounded correctness evidence, not a full independently adjudicated semantic denominator or a G2 pass. G2 additionally requires M3. Final independent review could not finish because the Codex reviewer exhausted its usage allowance. The review findings delivered before that failure were repaired and checked by the implementer; that is not independent approval of the final revision.

## Implemented behavior

- Method references select method/constructor declarations, preserve original complete spans and lexical execution owners, and do not become immediate calls. Static, bound/unbound instance, overload, explicit/default constructor, receiver generic substitution and bounded direct generic inference are covered. Arity, argument/return compatibility and checked exceptions are checked. Bound receiver field reads remain separate.
- Written type uses now include locals, construction, casts, `instanceof`/record patterns, class literals, arrays, union/intersection types and explicit invocation type arguments. Bound value receivers written with `::` are not misreported as type names. Invalid type-variable arguments produce explicit error evidence instead of crashing recursive type construction.
- Annotation uses preserve owner, source spelling, whole span and syntax-site identity. JDK annotation identities use verified declaration names despite the resolver's missing `containerType()` implementation. Nested/default annotation values produce type-use metadata without falsely annotating the enclosing declaration. Annotation retention, `@Target` propagation and runtime interpretation are not inferred.
- Records expose distinct component, field, accessor, constructor and parameter identities. Implicit declarations keep project origin and have no declaration span. Compact-constructor component names are parameters, not field reads. Explicit constructors/accessors suppress corresponding automatic body facts. Generated `equals`, `hashCode` and `toString` calls select the source record rather than `java.lang.Record`.
- Enum constant bodies use anonymous-type owners, including their methods. Enum construction and anonymous-constructor delegation are derived evidence, not fabricated `new` occurrences. Ordinary source default constructors and enum `values`/`valueOf` declarations are also represented.
- `DerivedRelationshipRecord` shares M1 target/status/uncertainty validation, requires versioned non-direct derivation with inputs, and keeps supporting spans separate from explicit occurrences. Result validation checks category, entities, inputs and documents. Record field/accessor/parameter type links and automatic field reads/writes use component evidence. Explicit coverage excludes derived facts.

The dependency upgrade addressed a concrete blocker: 3.26.1 lacked source-record indexing support. The narrower 3.27.1 update was selected and verified against existing adapter tests; this does not reopen the approved parser choice. Upstream's [3.27.1 record declaration API](https://www.javadoc.io/static/com.github.javaparser/javaparser-symbol-solver-core/3.27.1/com/github/javaparser/symbolsolver/javaparsermodel/declarations/JavaParserRecordDeclaration.html) and the downloaded class APIs were inspected. No broad technology comparison was run.

## Fixtures and independent evidence

`analyzer-javaparser/src/test/resources/m2/modern/Modern.java` was written before inspecting its candidate adapter output. Its 15 explicit invocations comprise 12 calls, one constructor call and two method references. `ModernFixtureTest` first parses with JDK 21, captures original written nodes, then attributes with a diagnostic collector, an empty application/source classpath, `--release 21`, `-proc:none` and `-implicit:none`. No classes are generated or run.

The compiler initially exposed 16 invocation-shaped trees: javac represents an enum constant as a `NewClassTree` even before attribution. The test excludes that enum-initializer tree according to the existing explicit/derived contract; it still visits the constant body. The source and expected explicit denominator stayed unchanged. All 15 remaining spans, categories, selected declaration signatures and source/JDK origins match the adapter; compiler diagnostics contain no errors. Raw compiler owner/name/erased-parameter data are retained separately from canonical target formatting in `modern-oracle.json`. Origin is checked from the compiler owner's source-tree membership, not package-name guessing.

The same fixture exercises all 18 registered categories. That is coverage evidence, **not** independent exact-label adjudication of every non-invocation fact. Detailed field/type/owner regressions and the original frozen fixture checks run alongside it. Original historical archives were not modified. The original 27 explicit declaration labels now explicitly filter `DIRECT` records so newly derived declarations cannot inflate that denominator.

| Family | Relevant verification and remaining boundary |
|---|---|
| declares | Frozen 27 explicit declarations, record components/implicit declarations, enum anonymous owner; typed lambda/catch parameters still have an explicit unsupported declaration boundary |
| extends, implements, permits | Type relationship tests and modern sealed interface/record/explicit superclass; no claim of a complete implicit superclass graph |
| type-uses, type-argument, type-parameter-bound | Type/expression tests, recursive structure, modern generic bounds/arguments/patterns; inferred `var` detail remains unsupported |
| calls, constructor-calls | Frozen 23 invocations plus modern compiler comparison; no whole-program Java type-checker equivalence |
| reads-field, writes-field | Frozen 25 field accesses, receivers, compound writes, record component/compact-constructor controls; array length and annotation-value field accesses remain explicit unsupported cases |
| method-references | Static/bound/unbound/overloaded/constructor/generic controls, checked-exception and incompatible-return negatives; array constructors, unsupported target contexts and joint generic inference remain explicit degraded outcomes |
| has-parameter, parameter-type, returns, field-type, throws | Existing signature tests and modern compiler-backed fixture; implicit record member links retain component support separately |
| annotated-with | Source/JDK, fields, parameters/type syntax, unknown annotation, nested/default negative controls; no annotation propagation or runtime semantics |

Modern-language cases include records (implicit and explicit canonical/compact constructors and accessors), enums with bodies, sealed types, record/type patterns, switch expressions, lambdas and the earlier local/anonymous/initializer/Unicode fixtures. Preview/newer syntax, reflection-computed targets, proxy/DI targets, unsupported binary/platform inputs and M3 build acquisition retain the previously documented boundaries.

Derived evidence in the modern output includes default constructors, enum members/construction/subclass delegation, record component fields/accessors/parameters, record object methods and component type/initialization links. Their saved counts are observational; a fully independently reviewed exact derived denominator remains an acceptance task.

## Verification actually performed

Commands used PowerShell with `MAVEN_USER_HOME=C:\Users\Admin\.m2` and `-Dmaven.repo.local=C:/Users/Admin/.m2/repository`. The supplied unrestricted execution profile resolved Maven permissions; no privilege workaround was used. The dependency upgrade was downloaded once online; subsequent verification was offline.

Focused commands used:

```powershell
.\mvnw.cmd -o -q '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer-javaparser -am '-Dtest=<affected test classes>' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Affected classes: `MethodReferencesTest`, `ExpressionTypesTest`, `AnnotationsTest`, `RecordsTest`, `ModernFixtureTest`, `DerivedEvidenceTest`, `FrontendContractTest`, plus existing field/type/boundary/frozen integration checks. Intended red failures were observed for unsupported references/constructors, incorrect receiver reads, record/default construction, missing derived record facts/enum owners, wrong record object-method origin, and invalid recursive type structure. Subsequent focused runs passed. Some later controls were added during repairs; no claim that every new assertion independently observed red is made.

A root `test` run passed during integration. The final command is captured without hand-editing in [verify.log](verify.log):

```powershell
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

**CONFIRMED:** final root verification passed **98 tests** (analyzer 41, adapter 56, backend 1), with zero failures/errors/skips. This is `verify`, not a clean-build or cross-platform claim. `verification-summary.json` records final test totals; `inputs.json` hashes the implementation/test inputs and parser JARs. Saved `*-manifest.json` files bind fixture source/configuration/platform inputs. Raw JSON files are copies of outputs generated by the final tests, not manually improved facts.

Independent Codex feedback identified missing generic receiver substitution, checked-exception validation and nested annotation negative controls. Those repairs are included. Final source/contract/ground-truth review and a complete independently adjudicated non-invocation/derived denominator remain outstanding. No consequential human decision is currently required to continue that work.

## Exact next task

Complete one read-only Codex review of the final adapter/neutral contracts and new fixture evidence, then finish exact non-invocation/derived labels only where the existing tests do not establish the registered M2 handling expectations. Repair concrete findings and rerun affected checks. Close M2 only after that evidence meets the M2 acceptance contract; do not silently enter M3 or label G2 passed.
