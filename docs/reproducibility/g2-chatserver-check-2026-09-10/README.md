# Gate G2 Checkpoint: ChatServerMicroservices

This report is mechanically rendered from the preserved pipeline output. It is coverage evidence, not independent semantic correctness adjudication.

## Pipeline summary

| Metric | Value |
| --- | ---: |
| assembly.assembled | 8 |
| assembly.withheld | 24 |
| build.effectiveModules | 16 |
| build.externalPomsAcquired | 73 |
| build.modules | 16 |
| build.problems | 70 |
| capabilityGaps | 442 |
| classpath.artifacts | 999 |
| classpath.problems | 2240 |
| decoding.decoded | 262 |
| dependency.acquired | 397 |
| dependency.acquisitionRounds | 17 |
| dependency.bytesConsumed | 217804394 |
| dependency.cached | 0 |
| dependency.classpathPasses | 18 |
| dependency.failed | 0 |
| dependency.requested | 944 |
| dependency.skipped_pom | 547 |
| frontend.observations | 587 |
| frontend.runs | 8 |
| ownership.owned | 262 |
| ownership.unowned | 0 |
| repository.files | 385 |
| repository.javaFiles | 262 |

## Closed semantic-category denominator

| CATEGORY | ATTEMPTED | EMITTED | UNMAPPED | RESOLVED | PARTIAL | UNRESOLVED | AMBIGUOUS | CONDITIONAL | UNSUPPORTED | ERROR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| java.annotated-with | 23 | 23 | 0 | 23 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.calls | 158 | 158 | 0 | 145 | 0 | 13 | 0 | 0 | 0 | 0 |
| java.constructor-calls | 6 | 6 | 0 | 6 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.declares | 110 | 103 | 7 | 103 | 0 | 0 | 0 | 0 | 7 | 0 |
| java.extends | 1 | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.field-type | 8 | 8 | 0 | 8 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.has-parameter | 31 | 31 | 0 | 31 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.implements | 3 | 3 | 0 | 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.method-references | 8 | 8 | 0 | 5 | 0 | 3 | 0 | 0 | 0 | 0 |
| java.parameter-type | 30 | 30 | 0 | 30 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.permits | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.reads-field | 38 | 28 | 10 | 28 | 0 | 10 | 0 | 0 | 0 | 0 |
| java.returns | 20 | 20 | 0 | 20 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.throws | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-argument | 5 | 5 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-parameter-bound | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.type-uses | 144 | 144 | 0 | 144 | 0 | 0 | 0 | 0 | 0 | 0 |
| java.writes-field | 2 | 2 | 0 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |

## Capability-gap reasons

| REASON | COUNT |
| --- | --- |
| build.configuration:UNSUPPORTED_ACTIVATION | 4 |
| build.configuration:UNSUPPORTED_PROFILE_ACTIVATION | 54 |
| build.dependency:DEPENDENCY_CYCLE | 2 |
| build.generated-source:GENERATED_SOURCES_NOT_ACQUIRED | 30 |
| build.plugin:EXTENSIONS_NOT_LOADED | 16 |
| build.plugin:PLUGIN_EFFECTS_NOT_EVALUATED | 28 |
| build.pom:POM_MODEL_WARNING | 9 |
| build.reactor-output:REACTOR_OUTPUT_NOT_ACQUIRED | 2 |
| build.source-plan:UNSUPPORTED_COMPILER_CONFIGURATION | 32 |
| frontend.input:MISSING_REACTOR_OUTPUT | 24 |
| java.annotated-with:CATEGORY_PARTIAL | 8 |
| java.calls:SEMANTIC_UNRESOLVED | 13 |
| java.constructor-calls:CATEGORY_PARTIAL | 8 |
| java.declares:CATEGORY_PARTIAL | 8 |
| java.declares:SEMANTIC_UNSUPPORTED | 7 |
| java.extends:CATEGORY_PARTIAL | 8 |
| java.field-type:CATEGORY_PARTIAL | 8 |
| java.frontend:FRONTEND_PARTIAL | 3 |
| java.has-parameter:CATEGORY_PARTIAL | 8 |
| java.implements:CATEGORY_PARTIAL | 8 |
| java.method-references:CATEGORY_PARTIAL | 8 |
| java.method-references:SEMANTIC_UNRESOLVED | 3 |
| java.observation:UNMAPPED_OBSERVATION | 17 |
| java.origin:MISSING_ORIGIN | 33 |
| java.parameter-type:CATEGORY_PARTIAL | 8 |
| java.permits:CATEGORY_PARTIAL | 8 |
| java.reads-field:CATEGORY_PARTIAL | 8 |
| java.reads-field:SEMANTIC_UNRESOLVED | 10 |
| java.returns:CATEGORY_PARTIAL | 8 |
| java.source:SOURCE_PARTIAL | 7 |
| java.throws:CATEGORY_PARTIAL | 8 |
| java.type-argument:CATEGORY_PARTIAL | 8 |
| java.type-parameter-bound:CATEGORY_PARTIAL | 8 |
| java.type-uses:CATEGORY_PARTIAL | 8 |
| java.writes-field:CATEGORY_PARTIAL | 8 |
| workspace.source-ownership:MISSING_SOURCE_ROOT | 12 |

## Gate decision

**WITHHELD.** Independent semantic correctness labels and adjudication are not yet recorded.
