# Evidence-First Software Intelligence Rule

Use for semantic analysis, build/workspace modeling, graph construction, architecture rules, violations, impact/evolution analysis, benchmarks, and reports.

## Evidence Chain

Prefer:

`source/build evidence -> semantic fact -> graph relationship -> rule evaluation -> violation`

Every important conclusion must be traceable through that chain. Never fabricate a relationship to make analysis appear complete.

## Required Relationship Evidence

Preserve, where applicable:

- relationship category
- source/caller and target/candidate identity
- repository-relative source file
- complete source span
- resolution status and derivation
- configuration and analysis identity
- diagnostic exception type/message for failures

## Uncertainty

Distinguish declared or resolved facts from framework inference. Represent unresolved, ambiguous, partial, conditional, unsupported, and error states explicitly. Numeric confidence is forbidden unless it has a defined and validated interpretation.

## Violations

A violation must identify its rule/version, involved symbols, supporting relationships, minimal graph path, source evidence, semantic status, snapshot/configuration, and limitations. A result relying on prohibited uncertainty cannot be presented as certain.

## Benchmarks and Claims

Record exact corpus commits, analyzer/rule/schema versions, configuration, classpath manifest, environment, command, runtime, raw outcomes, failures, and exclusions. Preserve raw output. Separate coverage from correctness and facts from inference. Never improve metrics by changing or hiding the denominator.
