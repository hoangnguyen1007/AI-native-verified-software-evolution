# Gate G2 Checkpoint: PC-Shop

This report is mechanically rendered from the preserved pipeline output. It is coverage evidence, not independent semantic correctness adjudication.

## Pipeline summary

| Metric | Value |
| --- | ---: |
| assembly.assembled | 0 |
| assembly.withheld | 2 |
| build.effectiveModules | 1 |
| build.externalPomsAcquired | 56 |
| build.modules | 1 |
| build.problems | 9 |
| capabilityGaps | 82 |
| classpath.artifacts | 257 |
| classpath.problems | 139 |
| decoding.decoded | 44 |
| frontend.observations | 0 |
| frontend.runs | 0 |
| ownership.owned | 44 |
| ownership.unowned | 0 |
| repository.files | 52 |
| repository.javaFiles | 44 |

> **Coverage boundary:** No source set reached the semantic frontend. The zero semantic-category counts below demonstrate a closed reporting denominator, not successful semantic analysis.

## Closed semantic-category denominator

| CATEGORY | ATTEMPTED | EMITTED | UNMAPPED | RESOLVED | PARTIAL | UNRESOLVED | AMBIGUOUS | CONDITIONAL | UNSUPPORTED | ERROR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| java.annotated-with | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.calls | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.constructor-calls | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.declares | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.extends | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.field-type | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.has-parameter | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.implements | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.method-references | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.parameter-type | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.permits | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.reads-field | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.returns | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.throws | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-argument | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-parameter-bound | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-uses | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.writes-field | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Capability-gap reasons

| REASON | COUNT |
| --- | --- |
| build.configuration:UNSUPPORTED_ACTIVATION | 3 |
| build.configuration:UNSUPPORTED_PROFILE_ACTIVATION | 13 |
| build.dependency:DEPENDENCY_CYCLE | 1 |
| build.dependency:MISSING_ARTIFACT | 26 |
| build.generated-source:GENERATED_SOURCES_NOT_ACQUIRED | 2 |
| build.model:UNRESOLVED_EXPRESSION | 1 |
| build.plugin:ADDITIONAL_COMPILER_EXECUTION | 2 |
| build.plugin:EXTENSIONS_NOT_LOADED | 1 |
| build.plugin:PLUGIN_EFFECTS_NOT_EVALUATED | 2 |
| build.pom:MISSING_POM | 26 |
| build.pom:POM_MODEL_WARNING | 1 |
| build.source-plan:UNSUPPORTED_COMPILER_CONFIGURATION | 2 |
| frontend.input:CLASSPATH_PROBLEM | 2 |

## Gate decision

**WITHHELD.** Independent semantic correctness labels and adjudication are not yet recorded.
