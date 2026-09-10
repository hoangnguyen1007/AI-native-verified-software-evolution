# Gate G2 Checkpoint: spring-petclinic

This report is mechanically rendered from the preserved pipeline output. It is coverage evidence, not independent semantic correctness adjudication.

## Pipeline summary

| Metric | Value |
| --- | ---: |
| assembly.assembled | 2 |
| assembly.withheld | 0 |
| build.effectiveModules | 1 |
| build.externalPomsAcquired | 59 |
| build.modules | 1 |
| build.problems | 12 |
| capabilityGaps | 1635 |
| classpath.artifacts | 648 |
| classpath.problems | 213 |
| decoding.decoded | 50 |
| dependency.acquired | 266 |
| dependency.acquisitionRounds | 18 |
| dependency.bytesConsumed | 171800181 |
| dependency.cached | 0 |
| dependency.classpathPasses | 19 |
| dependency.failed | 0 |
| dependency.requested | 629 |
| dependency.skipped_pom | 363 |
| frontend.observations | 4250 |
| frontend.runs | 2 |
| ownership.owned | 50 |
| ownership.unowned | 0 |
| repository.files | 132 |
| repository.javaFiles | 50 |

## Closed semantic-category denominator

| CATEGORY | ATTEMPTED | EMITTED | UNMAPPED | RESOLVED | PARTIAL | UNRESOLVED | AMBIGUOUS | CONDITIONAL | UNSUPPORTED | ERROR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| java.annotated-with | 288 | 288 | 0 | 288 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.calls | 1514 | 1511 | 3 | 1078 | 0 | 434 | 0 | 0 | 0 | 2 |
| java.constructor-calls | 96 | 96 | 0 | 51 | 0 | 45 | 0 | 0 | 0 | 0 |
| java.declares | 462 | 445 | 17 | 445 | 0 | 0 | 0 | 0 | 15 | 2 |
| java.extends | 11 | 11 | 0 | 11 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.field-type | 70 | 68 | 2 | 50 | 0 | 18 | 0 | 0 | 0 | 2 |
| java.has-parameter | 101 | 101 | 0 | 101 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.implements | 6 | 6 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.method-references | 2 | 2 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 |
| java.parameter-type | 94 | 94 | 0 | 94 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.permits | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.reads-field | 319 | 306 | 13 | 304 | 0 | 0 | 0 | 0 | 13 | 2 |
| java.returns | 86 | 86 | 0 | 82 | 0 | 4 | 0 | 0 | 0 | 0 |
| java.throws | 45 | 45 | 0 | 45 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-argument | 107 | 107 | 0 | 77 | 0 | 30 | 0 | 0 | 0 | 0 |
| java.type-parameter-bound | 1 | 1 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 |
| java.type-uses | 1021 | 1019 | 2 | 857 | 0 | 161 | 0 | 0 | 1 | 2 |
| java.writes-field | 27 | 27 | 0 | 27 | 0 | 0 | 0 | 0 | 0 | 0 |

## Capability-gap reasons

| REASON | COUNT |
| --- | --- |
| build.configuration:UNSUPPORTED_ACTIVATION | 4 |
| build.configuration:UNSUPPORTED_PROFILE_ACTIVATION | 39 |
| build.dependency:DEPENDENCY_CYCLE | 2 |
| build.generated-source:GENERATED_SOURCES_NOT_ACQUIRED | 2 |
| build.model:UNRESOLVED_EXPRESSION | 1 |
| build.plugin:EXTENSIONS_NOT_LOADED | 1 |
| build.plugin:PLUGIN_EFFECTS_NOT_EVALUATED | 2 |
| build.pom:POM_MODEL_WARNING | 9 |
| build.source-plan:UNSUPPORTED_COMPILER_CONFIGURATION | 2 |
| java.annotated-with:CATEGORY_PARTIAL | 2 |
| java.calls:SEMANTIC_ERROR | 2 |
| java.calls:SEMANTIC_UNRESOLVED | 434 |
| java.constructor-calls:CATEGORY_PARTIAL | 2 |
| java.constructor-calls:SEMANTIC_UNRESOLVED | 45 |
| java.declares:CATEGORY_PARTIAL | 2 |
| java.declares:SEMANTIC_ERROR | 2 |
| java.declares:SEMANTIC_UNSUPPORTED | 15 |
| java.extends:CATEGORY_PARTIAL | 2 |
| java.field-type:CATEGORY_PARTIAL | 2 |
| java.field-type:SEMANTIC_ERROR | 2 |
| java.field-type:SEMANTIC_UNRESOLVED | 18 |
| java.frontend:FRONTEND_PARTIAL | 2 |
| java.has-parameter:CATEGORY_PARTIAL | 2 |
| java.implements:CATEGORY_PARTIAL | 2 |
| java.method-references:CATEGORY_PARTIAL | 2 |
| java.method-references:SEMANTIC_UNRESOLVED | 2 |
| java.observation:UNMAPPED_OBSERVATION | 37 |
| java.origin:MISSING_ORIGIN | 734 |
| java.parameter-type:CATEGORY_PARTIAL | 2 |
| java.permits:CATEGORY_PARTIAL | 2 |
| java.provenance:MISSING_PROVENANCE | 5 |
| java.reads-field:CATEGORY_PARTIAL | 2 |
| java.reads-field:SEMANTIC_ERROR | 2 |
| java.reads-field:SEMANTIC_UNSUPPORTED | 13 |
| java.returns:CATEGORY_PARTIAL | 2 |
| java.returns:SEMANTIC_UNRESOLVED | 4 |
| java.source:SOURCE_PARTIAL | 27 |
| java.throws:CATEGORY_PARTIAL | 2 |
| java.type-argument:CATEGORY_PARTIAL | 2 |
| java.type-argument:SEMANTIC_UNRESOLVED | 30 |
| java.type-parameter-bound:CATEGORY_PARTIAL | 2 |
| java.type-parameter-bound:SEMANTIC_UNRESOLVED | 1 |
| java.type-uses:CATEGORY_PARTIAL | 2 |
| java.type-uses:SEMANTIC_ERROR | 2 |
| java.type-uses:SEMANTIC_UNRESOLVED | 161 |
| java.type-uses:SEMANTIC_UNSUPPORTED | 1 |
| java.writes-field:CATEGORY_PARTIAL | 2 |

## Gate decision

**WITHHELD.** Independent semantic correctness labels and adjudication are not yet recorded.
