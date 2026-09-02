# Java/Spring Semantic and Benchmark Evidence

Read for semantic extraction, ground truth, frontend comparisons or semantic gate reviews. Use the relevant architecture/ADR as the contract; this checklist does not define new canonical schemas or authorize production features.

## Before an experiment

- Pin repository snapshot, source inventory/hashes, Java release/runtime, source roots/module model and ordered exact classpath. Both compared frontends consume the same inputs.
- Register relationship categories and independently labeled occurrences before scoring. Define supported/unsupported boundaries and positive, negative, unresolved, ambiguous, malformed/error and modern-Java cases.
- Establish independent expectations: compiler bindings where applicable, hand-reviewed source/build evidence and reviewed labels. Neither candidate frontend is its own oracle. Capture oracle diagnostics; compiler recovery after an error is not automatically a valid binding.
- Inspect runner, POM and test side effects. Root reactor verification excludes standalone benchmarks. Execute only controlled/trusted fixture builds; normal target analysis must not run arbitrary lifecycle plugins.

## Check each observation

| Dimension | Check |
|---|---|
| Denominator | Category and occurrence belong to the registered corpus; omitted facts stay counted |
| Caller/target | Canonical kind/signature/scope match independent expectations, including overload/generic/local/anonymous cases |
| Origin | Derive PROJECT/JDK/DEPENDENCY from declaration/module/artifact evidence, never package-prefix heuristics alone |
| Resolution | Separate resolved, unresolved, ambiguous, unsupported and error; preserve candidates and exception evidence |
| Provenance | Snapshot/path/full span identify the actual source occurrence; absent positions remain absent, never placeholders |
| Mapping | Parser objects do not escape; M1 conversion preserves status, diagnostics, derivation and uncertainty |
| Identity | Stable inputs and exact artifact hashes; distinguish source occurrence from target declaration |
| Determinism | Same stable inputs yield identical canonical identities/order; runtime timing is separate |

M1 source spans use one-based line/column and an exclusive end. Test duplicate text, multiline calls, tabs/Unicode and nested expressions where supported. A semantically resolved target can still have unusable provenance; track both dimensions and prevent a mapper from silently converting it into fully evidenced output. If the canonical contract cannot represent the observation, report/withhold the mapping rather than manufacture evidence.

Do not convert every RuntimeException into UNRESOLVED. Missing symbols, malformed input, unsupported constructs and adapter defects require distinct outcomes and diagnostics.

## Aggregate without hiding failures

Report independent correct/incorrect judgments separately from attempted/resolved coverage. Account for unresolved, ambiguous, omitted, unsupported, errors and provenance failures. Define overlapping dimensions explicitly; totals must reconcile within each partition.

For full/partial classpath comparisons, preserve the exact removed artifacts and classpath order. Check whether a change affects targets, origins, statuses or merely evidence extraction. A CALLS-only result cannot pass the full M2/M3 gate.

For performance claims, use registered environments, fixed resource settings, repeated isolated cold/warm runs, phase time, peak memory and output counts. Record failures/timeouts. One elapsed duration is not a resource comparison.

## Preserve results and decisions

Store exact commands, corpus/configuration identifiers, tool versions, manifests, raw output and deviations. Derived tables must reference their source run. Keep existing raw artifacts immutable; fix a generator and rerun to a new identified output when necessary.

Technology approval and empirical gate acceptance are different decisions. Apply approved JavaParser direction and registered replacement triggers from ADR-001. OpenRewrite remains an independent comparator; transformation execution is outside SE121.
