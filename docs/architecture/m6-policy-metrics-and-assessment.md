# M6.3 — Policy metrics and explainable architecture assessment

**CONFIRMED by implementation and bounded tests (2026-10-15):** policy metrics,
conditional contributions, canonical structural inputs, score explanations,
deterministic identities and missing-evidence safeguards are implemented.
**PROVISIONAL:** all numerical score models, including the explicit reference
candidate. Arithmetic verification is separate from empirical calibration and
human approval under [ADR-002](../decisions/ADR-002-product-outcome-and-explainable-assessment.md).
This slice does not pass G5 or authorize M7.

## Boundaries and entry points

`PolicyMetricsService.compute(compilation, plan, evaluation, queries, limits)`
consumes the trusted M6.1 compiler/mapper and M6.2 evaluator results. It checks
the exact compilation, mapping, graph, context and complete declared rule list.
Foreign inputs and duplicate logical finding keys are caller contract errors.
Invalid external policy remains WITHHELD with its original typed gaps.

`ArchitectureAssessmentService.evaluate(metrics, evaluation, queries, model,
structuralInputs, limits)` computes health from those canonical outputs.
`assess(...)` obtains the required structural inputs through the existing
`ArchitectureMetricsService`, sharing the query service's operational guard.
Repeated uses of a metric share one canonical operand. One metric ID must use
one declared scope within a model. These are stable, storage-neutral domain
services for later CLI/API consumers; presentation does not recompute formulas.

The frozen 19-entry M5 catalog, its formulas, existing query identities, M1
serialization and graph snapshot schema remain unchanged. The new policy catalog
is additive. No parser/storage adapter, target lifecycle, network access,
runtime replay, REST/UI or snapshot-delta engine is introduced.

## Policy metric denominator and catalog

`policy.metrics:m6.3-v1` binds snapshot, optional exact analysis, graph/context,
semantic policy, raw source SHA-256, compilation/mapping/evaluation, configuration
spaces, rule requests and limits. Clean conditional rules retain their modeled
space even without findings: bounded canonical region evidence supplies missing
context metadata. Original upstream gap/conflict pointers remain available.

Every declared rule retains a coverage row with verdict, scope, selected and
unselected relationship kinds, query identity and per-selector YES/NO/UNKNOWN
counts. Unselected kinds mean not evaluated by this rule, including kinds
inapplicable to its scope; they are not a correctness claim. UNKNOWN membership
is never included among deliberate NO exclusions. Unsupported/canceled/withheld
rules stay in the declared denominator.

| Metric | Population and behavior |
|---|---|
| `metric.policy-findings` | Distinct M6 finding keys, grouped by rule, severity, scope, kind, semantic status and MUST/MAY/UNKNOWN. NEVER is only a clean rule result. Counts with open evidence are PARTIAL observed counts. |
| `metric.policy-density` | Rules with MUST or MAY violations / all declared rules. Any UNKNOWN or open evidence withholds the ratio; this is rule density, not a ratio of syntax occurrences. |
| `metric.policy-coverage` | Complete known-verdict rules / all declared rules. Incomplete rules remain in the denominator; a partial ratio describes completion, not accuracy. |
| `metric.policy-evidence` | Complete Evidence Bundles / observed distinct findings. No observed findings yields NOT_APPLICABLE with zero denominator. This observed-population fraction is not an accuracy rate. |
| `metric.policy-waivers` | NOT_APPLICABLE with typed `WAIVER_EVALUATION_UNSUPPORTED`; v1 has no waiver/suppression input. No invented zero waived count. |
| `metric.policy-audit` | WITHHELD with `ADJUDICATED_LABELS_UNAVAILABLE`; no independent labeled audit input is supplied. Formula replay cannot establish false-certainty rates, witness minimality or affected-region accuracy. |

All enum buckets are explicit, including zero counts. A finding with several
support statuses contributes once to each distinct status bucket, so status
buckets are not an additive partition. Missing status is `NOT_RECORDED`.
Core report completeness is independent of unavailable auxiliary waiver/audit
metrics; those metrics carry their own gaps and absent values.

## Score model and conditional semantics

The versioned formula is `architecture.score-formula:m6.3-exposure-v1`.
`ArchitectureScoreModel` explicitly supplies dimensions, weights, caps, severity
penalties and structural thresholds. No model is selected implicitly. Every
supplied model is currently PROVISIONAL; no boolean can self-certify approval.
The full model and its SHA-256 identity accompany the result.

The candidate measures **potential architecture exposure across the modeled
space**. It is neither expected runtime health nor a score for one realizable
joint world. MUST and MAY findings receive their full severity penalty; no
probability is assigned to configurations. NEVER contributes no penalty. UNKNOWN
or an open rule/finding denominator withholds the overall score, including when
a retained MUST finding already proves a violation.

Within each dimension, the root key includes scope, directed source/target/kind
tuples and dependency-versus-cycle structure. It excludes rule names, severity,
raw formatting and evidence representation. Repeated symptoms of the same root
receive the maximum severity penalty once, preserving references to every
contributing finding, rule, bundle and affected region. Distinct directed cycles
remain distinct. Shared policy/boundary dimensions intentionally observe the
same root through declared weights; this is visible, capped and bounded.

For dimension d:

```text
policy contribution = maximum severity penalty for each distinct root
metric contribution = maximumPenalty * clamp((n - threshold*d) /
                                            (d*(saturation-threshold)), 0, 1)
appliedPenalty = min(sum(contributions), dimensionCap)
dimensionScore = 100 - appliedPenalty
overall = sum(weight * dimensionScore) / sum(applicableWeights)
```

The metric formula uses exact supplied rational operands before rounding.
Decimal results use scale 12 and HALF_EVEN, matching M5. Parameters are bounded,
severity penalties positive and monotone, and thresholds strictly ordered.
There is no size/LOC penalty.

Structural inputs are exactly versioned M5 density/concentration results over
the full context, declared scope, production population and OUTGOING
BOUNDED_UNION projection. Exploratory structural exposure remains explicitly
exploratory. A MUST/REALIZED/filtered metric cannot silently substitute a healthier
population. Definition hashes, versions, units, rational operands, graph/context,
analysis and subject are checked. Missing, partial, unsupported or incompatible
operands withhold the score. Martin metrics remain diagnostic lenses and are not
automatically converted to health.

Verified zero denominators and dimensions with no applicable declared rule kind
are NOT_APPLICABLE. Their weights are explicitly excluded, and the resulting
applicable-weight denominator is exposed. This differs from missing evidence:
missing dimensions never cause a published score to be renormalized over the
remaining dimensions. No applicable dimensions means no overall score.

## Reviewable candidate, labels and sensitivity

`ArchitectureScoreModel.candidate(scope)` is an explicit **CANDIDATE IDEA** for
human review, implemented as a PROVISIONAL reference model:

| Dimension | Candidate weight | Input |
|---|---:|---|
| Boundaries/direction | 25 | Forbidden dependency, boundary and layer rules |
| Cyclicity | 20 | Acyclic rules |
| Coupling | 20 | M5 density, threshold 0, saturation 1 |
| Policy conformance | 25 | All declared rule kinds |
| Hotspot concentration | 10 | M5 concentration, threshold 0, saturation 1 |

Each dimension has cap 100; INFO/WARNING/ERROR penalties are 2/10/25, and the
structural maximum penalty is 100. These numbers are examples for reproducible
review, not empirically established quality thresholds. Density/concentration
can be appropriate architecture choices; their interpretation needs owner labels.

The following **implementation-authored labels** have independently hand-computed
expectations. They validate arithmetic/ordering and safety, not human calibration:

| Authored case | Model | Expected score/status |
|---|---|---|
| Clean declared rule | Policy-only | 100, PARTIAL because model provisional |
| One ERROR root, MUST or MAY | Policy-only | 75 |
| Two complementary MAY roots | Policy-only | 50; no simultaneous-world assertion |
| Duplicate ERROR/WARNING symptoms | Policy-only | 75 |
| Four or more distinct ERROR roots | Policy-only | 0, with visible uncapped penalty/cap |
| Unknown, missing provenance, partial denominator | Either | WITHHELD, no score |
| One ERROR, clean cycle rule, density 1/4, concentration 1/2 | Five dimensions | 77.5 |
| Real canonical two-package graph, one directed edge, same rule kinds | Five dimensions | 67.5 |

Measured test expectations for the 77.5 case: policy weight 25→50 gives 77;
coupling weight 20→10 gives 77.777777777778, 20→30 gives 77.272727272727;
hotspot weight 10→20 gives 75. Policy-only severity INFO/WARNING/ERROR yields
98/90/75; cap 100→10 yields 90 for one ERROR. A structural threshold/saturation
of 1/4 and 3/4 yields 100/100/50/0/0 for operands 0,1/4,1/2,3/4,1.
Sensitivity variants have distinct content identities. Published model changes
must receive a new model version; a shared version label alone never establishes
compatibility when parameter digests differ.

Simple counts remain the baseline alternative without an ordinal health claim.
The candidate exposure model preserves symbolic bounds and monotone penalties.
Full per-world expected/minimum scores are a stronger alternative only with an
approved aggregation semantics and bounded correlated-region evaluation; assuming
uniform configuration probability or expanding a large symbolic space is avoided.
Replacement is triggered by contradictory owner labels, unacceptable sensitivity
or an approved need for realized/per-world scoring. No such replacement is adopted
by this slice.

## Evidence quality, bounds and compatibility

`architecture.assessment:m6.3-v1` contains raw operands, positive baseline,
dimension/overall weights, penalties, caps, known partial contributions, source
metric identities, finding/bundle/region references, model/formula, context,
limits, gaps and reasons. Critical evidence failure removes the overall number;
known contributions remain inspectable. The model-approval gap qualifies every
otherwise computable score as PARTIAL.

Separate `EvidenceConfidence` reports sufficiency of the declared assessment
inputs and raw completion counts. It is not a numeric probability, semantic
accuracy estimate or global repository confidence. It can be sufficient while
the model remains provisional; calibration is a separate qualification. M1's
numeric `AnalysisConfidence` contract is unchanged and no unvalidated probability
formula is introduced.

| Bound | Default / maximum |
|---|---|
| Policy metadata node/selector items | 200,000 / 500,000 |
| Fallback canonical region evidence reads | 256 / 1,024 |
| Fallback decoded region evidence | 8,000,000 UTF-16 characters; one payload 1,000,000, depth 64, 20,000 JSON values |
| Model dimensions | 1–8 |
| Structural inputs | 16 / 16 |
| Emitted score contributions across dimensions | 400 / 400 |

M6.2's rule/finding/query bounds also apply. Cancellation, memory/time guards,
metadata/contribution exhaustion and partial inputs produce deterministic typed
`evidence.assessment-gaps:m6.3-v1` records and WITHHELD health. Bounds are not SLAs.
Computed timestamps and metric telemetry are excluded from semantic identities.

Cross-snapshot comparisons require compatible analyzer/build/framework/configuration
semantics, policy, full model/formula/parameter digest, populations/projections,
applicability and result-affecting limits. Exact upstream graph/context/manifests
and request identities remain available. This slice provides provenance and
compatibility inputs, not M11 correspondence or an improvement/delta claim.
Changing policy or losing evidence cannot be called a resolved violation by this
service.

Verification commands, scope, failures and limitations are in the
[M6.3 execution record](../tasks/m6/03-policy-metrics-and-assessment.md).
Next: human review of candidate labels, formula, weights, caps and comparison
semantics, then separate G5 acceptance. Independent review and empirical
calibration remain open; bounded implementation tests do not replace them.
