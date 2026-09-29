# V2.2 semantic closure — implemented boundary

Status: **PROVISIONAL, partial V2.2 implementation**, 2026-09-29.
This document describes the implemented contracts, not acceptance of the entire
[V2.2 task](../tasks/m4-universal-v2/02-java-spring-semantic-closure.md).
The [coverage catalog](m4-universal-v2-coverage.md) and its denominator remain unchanged.

## Java evidence

`JavaParserFrontend.PROVIDER` is `frontend.javaparser:3.28.2-m4uv2.2`.
Existing method/constructor/type identity tuples remain unchanged. Lambda
parameters use the positional parameter tuple under their source-anchored lambda;
catch parameters use `catch-parameter`, lexical owner, source document, offset and
identifier. Source spans belong to the original captured source.

Annotation arguments and annotation-member defaults can produce exact constant
field-read targets. Their owners are the annotated declaration and annotation
member respectively; these reads do not assert runtime execution. `var` produces
a neutral inferred `JavaType`, including generic arguments and arrays, when the
primary frontend can attribute it. This is not a source span for an unwritten
generic argument. Failed inference remains an explicit observation.

The existing SHA-256-bound Lombok 1.18.46 synthesis now participates in direct
constructor/getter/setter call resolution. For the supported ordinary source
class, exact argument types select an already evidenced generated declaration
before native resolution can choose a wider handwritten constructor. Generated
targets keep derivation inputs and no fabricated declaration span. Explicit
accessor suppression remains authoritative. Unproved accessibility, conversions,
null overload selection, generics and inherited contexts retain unresolved
observations; a generated override cannot silently fall back to its ancestor.
Neither AST mutation nor target annotation-processor execution is introduced.

This resolver is deliberately not advertised for builder chains, general
generated return-type inference, generated method references, arbitrary Lombok
configuration, general overload conversion, or all generated-code frameworks.
Array constructor references/array-length semantics and complete functional-target
contexts still require further V2.2 work. Existing 18-family coverage accounting
is retained, rather than treating these changes as Java-wide closure.

## Captured Config Data

`spring.config-data-ingestion:m4uv2.2` follows relative `.properties`, `.yml` and
`.yaml` imports using only `RepositoryInputs` bytes. Imports may reference a parent
directory only while remaining in an explicitly selected location. No host file,
environment, URL, script or loader is accessed.

The expansion respects importer/import precedence, later declared imports,
profile variants and import-once discovery at the highest-precedence position.
A non-profile pass acquires profile declarations/groups before the selected-profile
pass. Conditional imported documents cannot set the unconditional active profiles.
Each imported document retains its captured digest and source-document evidence.

The two passes independently bound imported bytes, documents, nesting and import
edges. Cycles, malformed optional inputs and exhausted limits produce typed gaps.
Optional absence is relative to the supplied snapshot; a snapshot entry whose
bytes were not supplied is unavailable, not absent. Independent captured values
remain inspectable, while unresolved obligations withhold the complete assignment.

Absolute paths, URI/classpath/configtree imports, placeholders in import locations,
custom loaders and externally supplied import directives remain obligations.
Configtree ingestion, a distinct deployment-envelope acquisition layer, and
automatic proven finite-domain abstraction are not implemented by this change.
Repository properties are not universal deployment truth. Three fixed authored
fixtures independently compare import precedence/groups with Boot 3.4.0; no
arbitrary target Config Data is loaded by Boot.

## Source to registration, binding and truth

`UniversalIngestionPipeline.prepareSpring` continues one analyzed exact source set
through `SourceToSpringPlan` (`spring.source-to-plan:m4uv2.2-beans-v1`).
`SpringBuildContext.fromExactRequest` retains the existing content preimage:
snapshot, source plan, module/source set, assembly identity, ordered classpath,
platform and policy. Legacy requests and foreign evidence are rejected.

The bridge consumes actual frontend/component/constructor/bean-method evidence and invokes
the existing mechanism scanner, condition lowering and injection-site acquisition.
It creates actual `RegistrationPlan`, `BeanRegistrationPlan` and
`InjectionBindingPlan` instances, including condition uses, producer membership,
definitions, dependency descriptors, matches and obligation mappings.

The caller supplies a `Scope` tied to the exact source evidence. Container identity,
registration order, override policy, closed registry, parent absence, resolvable
dependency absence and post-registration mutation absence are explicit evidence
inputs. The bridge does not infer them from alphabetical sorting or pretend to
discover the application's bootstrap. A complete order must contain exactly the
acquired component and direct bean-method declaration inventory. Method identities
are schedule entries independently of their return-type identities. An owner must
precede its bean methods; malformed complete schedules are rejected. A missing
order or open registry remains unknown.
These supplied closure proofs qualify every result; tests use authored scope
evidence and do not pass handmade final definitions, descriptors or matches.

The currently complete descriptor fragment is direct unconditional scan membership,
ordinary direct component metadata, `@Profile`, primary/fallback/qualifier metadata,
and selected constructors' and bean methods' resolved non-generic scalar dependencies. The tested
exact execution tuple is Framework 6.2.0 / Boot 3.4.0. Unknown/composed annotations,
inherited component metadata, unsupported scan drivers, missing constructor
selection, lazy/provider/generic and field/method descriptors are not silently
promoted. Empty qualifiers remain an incomplete descriptor with an explicit gap.
Limits preserve inventory and qualify incomplete matching rather than inventing
negative matches.

### Direct bean-method acquisition (2026-09-29)

`BeanMethodIngestion` supplies direct, artifact-resolved `@Bean` methods on acquired
ordinary components or `@Configuration(proxyBeanMethods=false)` classes. It uses
resolved `java.returns` evidence for the exposed type, declaration/parameter spans
for provenance, and a conservative source-header grammar for method names and
static ownership. Parser objects do not cross the adapter boundary. Method bodies
are never evaluated. Supported return types are unannotated source classes without
generic arguments or inherited types; broader return-type/factory/proxy prediction
remains explicit rather than guessed.

Default bean names follow Java identifier equality, including identifier-ignorable
characters; explicitly supplied string names retain their literal contents.
Single literal `name`/`value`, primary/fallback/qualifier and
`autowireCandidate`/`defaultCandidate` flags are acquired. Instance factory products
retain their factory owner; static products do not manufacture that dependency.
Multiple producers returning the same Java type retain distinct candidate identities.
Bean parameter descriptors belong to the produced bean, not the configuration bean.
`SpringInjectionSites` versions this acquisition as `m4uv2.2-beans-v1`.

Method discovery retains conditions for registration. Parent registration gates
propagate class conditions to products. Existing M4C literal whole-annotation
refinement handles named bean predicates against the actual prefix, including
reversed missing-bean order; inferred/class-literal type predicates remain open.
The caller still supplies the explicit container and order proofs. This slice
does not establish automatic application bootstrap or reader order from source order.

Source spelling does not prove runtime parameter-name availability. Dependencies
retain `Name.UNKNOWN`; unique or primary selection can still be established, while
selection that needs a runtime name retains the existing `DEPENDENCY_NAME_UNKNOWN`
gap. Standard name shortcuts are not enabled without their complete evidence.

Unknown returns, composed/unknown metadata, enhanced configurations, overloaded
methods, aliases and producer-name collisions retain candidate/inventory evidence
and typed gaps. Duplicate candidate enumeration names are deduplicated in the
environment only; producer identities and unsupported reader decisions remain
distinct. No generic override rule is substituted for Spring's reader-specific
collision rules. Lifecycle method strings do not prove successful initialization.

The source integration controls cover both constructor-to-produced-bean and
bean-parameter binding, profile activation, primary selection/conflict, name/flag
metadata, static ownership, missing/wrong schedules, unsupported metadata, missing
types, name collisions and unproved parameter names. Fixed authored Framework
6.2.0 / Boot 3.4.0 container controls corroborate positive selection, eligibility
flags, primary conflict and reversed named missing-bean registration. See the
[bean-producer verification package](../reproducibility/m4uv2-v22-beans-2026-09-29/README.md).

`Result.evaluate` returns an `Evaluation` retaining acquisition plus the existing
exhaustive M4D result. `Result.evaluateSymbolic` retains acquisition plus the
existing SAT signature-partition result. Both expose the sorted union of
acquisition and evaluation capability gaps and content-address the combined
result. The pipeline continuation also retains the upstream acquisition/frontend
ledger, including obligations outside the normalizer's catalog. Configuration
spaces must belong to the same build context. Domains and
baseline remain explicitly supplied; there is no automatic deployment closure.

The source integration control produces conditional selected bindings, checks
small-space exhaustive/SAT agreement, deterministic replay and valid witnesses,
and compares active/inactive selection with an actual pinned Spring container
running only fixed authored test classes. Negative controls cover missing order,
open registry, wrong/empty qualifiers and exhausted acquisition budgets. This
does not certify arbitrary application startup or instantiate analyzed source.

## Remaining V2.2 obligations

No A–E checklist section is accepted wholesale by these controls. In particular:

| Task area | Still required before claiming full closure |
|---|---|
| A / J01–J12 | Remaining Java attribution controls; full generated resolver integration and imported-generator client journeys; compiler-adjudicated coverage across declared variants |
| B / S01–S05 | Configtree/classpath and supplied external envelopes; predicate-derived finite domains with proven OTHER uniformity, correlation and coercion controls |
| C / S06–S12, S21 | Complete binary/source annotation composition; automatic evidenced bootstrap/scheduling; remaining bean/alias/reader-collision/type-prediction cases and import/XML/auto-configuration producers; full generic/field/method/provider/aggregate/Resource/hierarchy descriptors |
| D / S13–S19, S23 | Exact version-pack registry and complete historical/modern/data/proxy/route/event/client journeys with positive and negative independent oracles |
| E / J12, S15, S20, Q01–Q02 | Passive effect summaries, dependency-local opaque refinement, correlated residuals, order/saturation controls and source-integrated high-signature cases |

Existing M4B/C/D engines and passive universal helpers are reused; their historical
unit evidence does not prove that all source acquisition journeys are connected.
No external cohort benchmark, measured 99% coverage, V2.3 handoff, M4E acceptance
or G3 promotion is claimed. V2.1 acceptance obligations also remain open.

Verification commands, results and changed-source hashes are recorded in the
[initial verification package](../reproducibility/m4uv2-v22-2026-09-28/README.md) and
[bean-producer slice package](../reproducibility/m4uv2-v22-beans-2026-09-29/README.md).
