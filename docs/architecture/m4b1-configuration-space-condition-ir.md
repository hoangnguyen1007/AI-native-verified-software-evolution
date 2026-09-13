# M4B.1 — Configuration-space identity and condition IR

Date: 2026-09-13. Implements the M4B.1 boundary under [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md) and the [accepted M4-R0 contract](m4-r0-semantics-gate.md). Package: `com.evolution.analysis.spring.condition`, in the neutral `analyzer` module.

## Delivered boundary

**CONFIRMED by implementation:** this slice represents explicitly supplied finite-domain declarations and typed condition IR in one M3 build context. It validates structural/evidence closure and emits immutable `CapabilityGapRecord` values. It performs no I/O, target lifecycle execution, class loading, ambient configuration discovery or solver selection.

`REPRESENTED` means that supplied IR passed this slice's representation checks. It does **not** mean that a condition is true, a Spring feature is implemented, the deployment envelope is complete, or the modeled space is feasible. `ConditionModel.feasibility()` is explicitly `NOT_EVALUATED`, except that an empty declared domain is `INVALID_MODEL`. Even a constant-false constraint is retained for later feasibility evaluation. No MUST/MAY/NEVER classification exists in this API.

## Formal invariants

Let distinct typed variables `v1,...,vn` have finite declared domains `D1,...,Dn`. The unconstrained Cartesian space is `P = D1 × ... × Dn`, whose cardinality is the product of domain sizes. The empty product is one. Any empty domain makes the model invalid. Cartesian cardinality is **not** the number of feasible worlds after constraints, profile rules, source activation or registration.

Counting uses `BigInteger` and saturates at `maxCartesianAssignments + 1` with `exact = false`. This is a lower bound, never an exact total. Empty domains are checked before saturation. Variable-count, value-count and product limits have separate typed gaps; original inputs remain retained.

The condition-occurrence partition is disjoint and closed:

```text
inputRows = representedRows + qualifiedRows + unexpandedRows
```

Input rows are the union of supplied occurrences and constraint occurrences by identity. Duplicate rows within either input list are invalid API inputs; referencing an existing occurrence as a constraint does not double-count it. Formula sharing never merges distinct occurrences. Each row is `NOT_EXPANDED` on traversal exhaustion; otherwise its own problems determine `QUALIFIED` versus `REPRESENTED`. Invalid constraint-role problems attach to their exact row. Space-level problems remain visible separately and qualify the complete model; consumers must retain both scopes.

The strong-Kleene algebra has TRUE/FALSE/UNKNOWN. FALSE absorbs conjunction; TRUE absorbs disjunction; negation preserves UNKNOWN. Thus `UNKNOWN OR NOT UNKNOWN = UNKNOWN`. Tests cover all 21 accepted R0 binary/unary table rows, involution, De Morgan laws, associativity and distributivity. These checks do not evaluate framework predicates.

## IR and provenance

`ConditionExpression` is an immutable DAG with sealed typed operands:

| Operand | Dependency and meaning |
|---|---|
| Constant; ALL/ANY/NOT | Three-valued Boolean content/composition |
| Profile | Exact, case-sensitive set-membership name |
| Property | Exact prefix, non-empty name set, havingValue and matchIfMissing |
| Web | Explicit NONE/SERVLET/REACTIVE domain |
| Build | Class/resource/Java/framework observation with exact build identity and evidence |
| Bean | Presence/missing/single-candidate query with container/selectors/search mode; no final-state identity |
| Opaque | Exact correlation key and custom/unresolved/unsupported/version reason |

Factories prevent invalid operator/operand combinations. Children must share the supplied framework semantics version and evidence digest. This pins interpretation input; it is not framework conformance proof. A build observation from another context or with UNKNOWN evidence gets a gap. Other atomic predicates remain unevaluated.

Pure ALL/ANY children are sorted by expression identity and duplicate-rejected as normalized set inputs. Any descendant with a bean-state or opaque dependency prevents sorting; those lists preserve order and repeated predicates. No flattening, constant folding, excluded-middle rewrite or opaque branch exploration occurs: `FALSE AND opaque` retains the opaque obligation. Empty ALL/ANY nodes are representable; their evaluation belongs to a later evaluator.

`ConditionOccurrence` separates formula identity from declaration evidence, metadata path, role and ordinal. Source evidence carries document identity, raw SHA-256, optional full span and ordinal. Document and span identities must agree. Repository source evidence must match both file membership and digest in the M3 snapshot, including inventoried resources. Missing spans and foreign/stale evidence get separate gaps. Artifact evidence carries artifact/entry hashes and a metadata pointer; derived evidence carries non-empty input hashes, method/version and slot. Explicit external deployment descriptors use artifact/derivation evidence rather than invented repository membership. Artifact entry content and derivation proof validation remain the supplying provider's responsibility; a hash alone does not prove a proposition.

Raw semantic strings preserve whitespace, empty property values, case and Unicode normalization form. Malformed UTF-16 is rejected. M1's existing normalization rules remain unchanged. Flat typed expression/occurrence views avoid recursive DAG serialization; view and row constructors validate identity/content and outcome consistency.

## Build, envelopes and identities

`SpringBuildContext.from` selects one ASSEMBLED M3 module/source-set outcome, rejects withheld/absent outcomes and legacy M2 plans, and checks request scope. It retains exact binary/reactor-source interleaving. Cache/JDK physical locators are excluded.

The implementation retains the R0 `m4-r0-identity-v1` envelope:

```text
kind + ':' + SHA256_UTF8(CanonicalJson({
  components: [CanonicalJson({schema: 'm4-r0-identity-v1', ...fields})],
  kind: kind, version: 1
}))
```

| Kind | Implemented R0 fields |
|---|---|
| spring-build-context | snapshotIdentity; sourcePlanIdentity; moduleSourceSet; frontendAssemblyIdentity; orderedResolutionInputs; platformViewIdentity; buildPolicyIdentity |
| spring-envelope | layer; ordered documents; ordered declaredSources; acquisitionPolicyIdentity |
| spring-condition-expression | irVersion; frameworkSemantics; operator; typedOperands; childExpressionIdentities |
| spring-condition-occurrence | expressionIdentity; declarationEvidenceKey; metadataPath; role; ordinal |
| spring-configuration-space | buildContextIdentity; repositoryEnvelopeIdentity; nullable deploymentEnvelopeIdentity; domains; constraints; precedencePolicy; profilePolicy; abstractionVersion; feasibilityPolicy |

The build source-plan digest covers the applied frontend plan and selected source documents; platform digest covers entry/release/version/vendor; build policy uses the applied manifest configuration identity. The assembly identity also binds upstream build/source/decoding/policy inputs. No M1 preimage, M3 schema, mechanism inventory or gap schema changes. `ConfigurationSpaceIdentity` is a different Java type and hash domain from realized `ConfigurationIdentity`.

Envelopes separate repository and supplied deployment layers. Document/source descriptors preserve evidence, ordered import ancestry, activation references and availability. Missing optional, missing required, failed, denied, unrecognized-loader and unobserved-external inputs remain distinct; non-optional unavailability gets gaps. Source alternatives must lie inside declared domains. Empty source domains, unresolved activation references and incomplete/extraneous precedence references remain explicit.

Precedence is every source reference exactly once, low-to-high, with its layer. Its order is never sorted away. Profile policy records explicit defaults, groups and includes; it does not infer ambient profiles, exclusion, group closure or loading behavior. Later evaluation must establish last-active-definition-wins, default-profile selection and feasibility against versioned evidence.

PROPERTY domains distinguish exact strings, MISSING and proposed OTHER. PROFILE domains contain Boolean membership values; WEB_MODE uses typed modes. OTHER always receives `OTHER_ABSTRACTION_NOT_VALIDATED` in this slice, even with a supplied proof hash. No unchecked proof hash licenses universal abstraction.

## Bounds, gaps and persistence

`FeasibilityPolicy` binds all M4B.1 deterministic limits into the space preimage: variables, values/domain, Cartesian assignments, condition rows, expression visits and depth. Conservative defaults are 12 / 4096 / 4096 / 10000 / 100000 / 256. R0 variable/world/step budgets are retained; value/row/depth guards are local defensive limits, not measured repository distributions or solver acceptance thresholds. A later `ConditionalSemanticsContextIdentity` must also bind its evaluator's result-affecting limits.

Validation uses canonical occurrence order and iterative iterator frames. Traversal working memory scales with depth rather than width; no recursive stack walk is required. Shared nodes are deduplicated per row, while the global visit budget counts traversal work. Row-limit exhaustion never truncates the denominator. `sourceRows()` retains all immutable input roots; `inspectedExpressions()` is an explicitly bounded partial table. Persist the supplied DAG and input space/envelopes alongside result identities. The result's canonical form exposes input identity, rows, inspected nodes, problems, gaps, coverage, status, feasibility and bounded count. Wall-clock aborts are not canonical semantic prefixes.

Provider `spring.condition-model:m4b.1`, result `spring-condition-model-v1`, and gap catalog `evidence.spring-condition-gaps:m4b.1-v1` are additive. Every problem has a content-addressed provider observation tied to input identity and one M3 `CapabilityGapRecord`, with snapshot, supporting spans and typed evidence requirement. Opaque execution requirements retain RUNTIME_ACCESS; no execution provider is selected or authorized. Gap construction never consumes the final result identity, avoiding a digest cycle.

## Verification and next boundary

Tests cover identity/domain/row invariants, malformed/tampered input, Unicode golden serialization, accepted Kleene fixtures, opaque/stateful controls, stale or foreign sources, replay, every configured budget, deep inputs and actual M3 assembler projections for binary/source reactor inputs. See the [verification record](../reproducibility/m4b1-condition-foundation-2026-09-13/README.md).

M4B.2 should lower exact M4A condition obligations and supplied configuration evidence into this IR, then validate bounded exogenous/profile/property and normalized-envelope evaluation against accepted fixtures. Boot Config Data, arbitrary profile loaders, OTHER proof checking, registration effects/order, binding, opaque correlations, truth regions/witnesses and solver selection are not implemented by M4B.1. G3 remains pending. This slice makes no independent-review or universal Spring/runtime correctness claim.
