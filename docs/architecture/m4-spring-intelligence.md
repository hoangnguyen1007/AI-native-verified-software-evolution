# M4 Spring Intelligence and Closed Mechanism Taxonomy

## Status and Scope

**ACCEPTED direction and gate contract.** [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md) changes M4 from one-context candidate enrichment to bounded, phase/order-aware conditional architecture semantics. Gate M4-R0 was formally accepted by the human supervisor on 2026-09-11. The [accepted R0 contract](m4-r0-semantics-gate.md) governs identities, the 29-family `spring-mechanisms:v2` catalog, the historical version matrix, the phase/order model, and capability-gap bounds. Production implementation begins with slice M4A.1.

The catalog is closed per version, not forever. `spring-mechanisms:v2` must account for every registered fixture/observation exactly once. A new mechanism or materially different framework behavior requires an explicit catalog/semantics-version change rather than silent omission.

## M4 Delivery Slices

1. **M4-R0 — Research/semantics gate:** approve identities, first framework/condition fragment, version matrix, exhaustive oracle, baselines, corpus protocol and limits.
2. **M4A — Mechanism ground truth:** detect/classify every `spring-mechanisms:v2` row and preserve real evidence/gaps.
3. **M4B — Configuration space and condition IR:** build finite evidence-backed spaces inside one exact M3 build context.
4. **M4C — Staged resolution:** evaluate phase/order-aware registration, activation, candidates and bindings.
5. **M4D — Truth regions and witnesses:** derive `MUST`/`MAY`/`NEVER`/`UNKNOWN` facts with reproducible witnesses.
6. **M4E — Evaluation/G3:** execute adjudicated fixtures, fair baselines and representative repository studies.

The detailed cross-milestone semantics live in [Conditional Architecture Semantics](conditional-architecture-semantics.md).

## Modeling Invariants

- Declarations, producers, bean-definition candidates, injection points, conditions, registration transitions, binding candidates and runtime observations are different concepts.
- A stereotype class or repository interface is not automatically an instantiated runtime bean.
- One existing `ConfigurationIdentity` identifies one realized configuration. `ConfigurationSpaceIdentity` identifies a finite modeled space and its interpretation inputs.
- A separate `ConditionalSemanticsContextIdentity` binds the space to framework/condition/registration semantics, registration plan, reasoner policy and result-affecting limits; identities must have non-circular preimages.
- Activation, registration, type compatibility, disambiguation, selection and runtime instantiation are separate axes.
- A direct bean-to-bean `INJECTS` edge is a bounded projection, not canonical source truth.
- Mutually infeasible conditional edges cannot be combined into a certain path, cycle or violation.
- `UNKNOWN` is preserved whenever missing configuration, order, classpath, generated members or dynamic behavior can change the conclusion.
- Provider/version provenance references immutable observations/artifacts; later evidence cannot silently rewrite earlier evidence.

## Canonical Domain Concepts

M4 owns storage-neutral meaning; M5 decides node/relationship/index mapping.

| Concept | Meaning and minimum evidence |
|---|---|
| `BeanProducer` | Source/configuration/artifact/runtime mechanism capable of declaring a definition, with producer kind, owner, origin, condition and real evidence |
| `BeanDefinitionCandidate` | Potential definition with names/aliases, exposed types, scope, producer, registration condition and uncertainty; not runtime-instantiation proof |
| `InjectionPoint` | Exact constructor parameter, field, method/setter parameter, `@Bean` parameter or registered injection site with requested shape, qualifiers, owner and source evidence where declared |
| `ConditionExpression` | Versioned normalized profile/property/classpath/resource/web/bean-state/opaque predicate |
| `RegistrationTransition` | Candidate registration step in a known phase/order with condition, input/output state references and uncertainty |
| `BindingCandidate` | Evidence that one registered candidate may satisfy an injection point, including compatibility, selection rationale, truth region and provenance |
| `ConditionalSpringFact` | Producer/registration/binding/endpoint fact with `T`/`F`/`U` region and `MUST`/`MAY`/`NEVER`/`UNKNOWN` classification |
| `ConfigurationWitness` | Deterministic assignment and material registration-order identity reproducing a fact/finding outcome |
| `FrameworkEntryPoint` | Endpoint/listener/scheduled/lifecycle callback invoked by the framework despite no application `CALLS` edge |

Suggested semantic relationships include `DECLARES_PRODUCER`, `PRODUCES_BEAN_CANDIDATE`, `DECLARES_INJECTION_POINT`, `HAS_CONDITION`, `PRECEDES_REGISTRATION`, `INJECTION_CANDIDATE`, `SELECTED_BINDING` and `OBSERVED_BINDING`. `SELECTED_BINDING` exists only where a specific world/region and supported versioned semantics justify selection. `OBSERVED_BINDING` is a separate evidence layer.

## Condition Classes

| Class | Examples | Evaluation input |
|---|---|---|
| Build-context constants | class/resource present, Java/Spring version | exact M3 context |
| Exogenous configuration | profile, property, web mode | realized assignment in the modeled space |
| Endogenous bean state | bean present/missing, single candidate | definitions processed before the transition |
| Opaque/dynamic | custom `Condition`, unresolved SpEL, registrar/post-processor computation | additional configuration/build/runtime evidence |

These classes may compose in one expression, but their provenance and uncertainty remain visible. Unsupported or unrecognized nodes become opaque conditions plus capability gaps; they are not evaluated false.

## Configuration Context and Space

1. **Profiles are sets and expressions:** multiple profiles may be active. Profile groups and `!`, `&`, `|` semantics are versioned and tested.
2. **Property sources retain precedence:** repository documents, imports and user-supplied deployment envelopes remain distinct with exact origin/digest. Ambient host environment is never read implicitly.
3. **Finite domains are explicit:** observed values, `MISSING` and a sound `OTHER` abstraction may be used. Empty and missing values remain distinct where Spring distinguishes them.
4. **External configuration may be absent:** repository files are not called the complete production environment. Missing imports/config trees/custom loaders become `CONFIGURATION` gaps.
5. **Path feasibility is mandatory:** incompatible profiles/conditions never form one certain path or cycle. Union views are labeled exploratory projections.

## Phase/Order-Aware Registration and Binding

Resolution does not use a rigid shortcut such as “Qualifier > Primary > Profile” and does not use an unordered generic fixpoint.

The phases below are a conceptual projection; the [R0 nested event contract](m4-r0-semantics-gate.md#7-phase-and-order-model) defines their actual interleaving inside registry processing. Environment construction precedes condition evaluation.

### Phase 1 — Parse and discover

- discover configuration classes/imports, component-scan scopes, producers and injection sites;
- evaluate parse-phase/build-context conditions where supported;
- retain opaque/dynamic mechanisms and missing metadata as candidates/gaps.

### Phase 2 — Ordered bean-definition registration

- establish the supported framework-version registration plan;
- evaluate each transition against the definitions processed so far;
- honor supported user-definition/auto-configuration and before/after ordering rules;
- treat missing-bean and single-candidate predicates as state-dependent;
- explore alternative legal order only within explicit deterministic bounds; otherwise mark affected results `UNKNOWN`.

### Phase 3 — Activation and type compatibility

- use the realized-world environment already evaluated at the applicable parse/registration events; do not postpone profile/property conditions until after registration;
- filter registered candidates by type/generic/container compatibility;
- retain zero/one/many candidate evidence.

### Phase 4 — Disambiguation and verdict

- apply qualifiers and composed qualifiers;
- apply name fallback, `@Primary`, `@Fallback` and supported ordering semantics for the pinned framework version;
- emit selected, ambiguous or unsatisfied results with truth regions and evidence;
- never interpret “not established” as “no bean exists.”

## Generated and Framework-Synthesized Semantics

### Lombok

A derived constructor/injection point is permitted only for a supported Lombok fragment with exact annotation identity, relevant configuration/version, field order, field initialization, `@NonNull`, static/excluded fields and explicit-member interactions. `@RequiredArgsConstructor`, `@AllArgsConstructor`, `@Data` or a final field alone is insufficient.

Generated members retain `EntityOrigin.PROJECT`. The annotation span is supporting derivation evidence, not a generated declaration span. Insufficient evidence creates `GENERATED_SOURCE`/`BYTECODE` requirements.

### Spring Data

A repository interface becomes a candidate only when enablement/scanning, base package/filter, store binding and `@NoRepositoryBean` evidence establish eligibility. Multi-store ambiguity, custom fragments, repository base classes/factories and naming remain visible. Extending `Repository` alone does not establish a bean or selected binding.

### Other generators and factories

MapStruct, QueryDSL, factory methods, `FactoryBean` and proxy product types use the same evidence rule: synthesize only a versioned supported semantic fact; otherwise preserve a candidate and request generated-source, bytecode, configuration or runtime evidence.

## Orthogonal Status Axes

| Axis | Example values | Question |
|---|---|---|
| Mechanism handling | `SUPPORTED`, `CONDITIONAL`, `DYNAMIC`, `UNSUPPORTED`, `OUT_OF_SCOPE` | What does the current catalog/provider promise? |
| Semantic attribution | M1 `RESOLVED`, `PARTIAL`, `UNRESOLVED`, `AMBIGUOUS`, `CONDITIONAL`, `UNSUPPORTED`, `ERROR` | What target/candidate result was established? |
| Logical condition | `TRUE`, `FALSE`, `UNKNOWN` | What is true for this exact state/configuration? |
| Region quantifier | `MUST`, `MAY`, `NEVER`, `UNKNOWN` | How does truth vary over the modeled feasible space? |
| Derivation | `DIRECT`, `DERIVED`, `INFERRED` | How was the observation produced? |
| Registration/selection | discovered, active, inactive, registered, candidate, selected, ambiguous, observed | Which framework step is established? |
| Operational outcome | success, unsupported, error, timeout, denied, limit exceeded | Did the bounded method complete? |
| Evidence provenance | provider/version, observation/artifact/gap IDs | What supports or limits the statement? |

`SUPPORTED` does not guarantee a resolved binding. `DYNAMIC` does not mean ignored. `OUT_OF_SCOPE` is a versioned delivery boundary, not a permanent prohibition.

## `spring-mechanisms:v2` Closed Denominator

The target column is a gate objective, not current implementation status. The [machine-readable R0 candidate](../../benchmarks/m4-r0/mechanisms.json) freezes 29 proposed families, adding the unclassified catch-all and proposing bounded XML inclusion for review.

| Mechanism ID | Mechanism | M4 target | Required evidence/qualification |
|---|---|---|---|
| `spring.discovery.component-scan` | Scan roots, base packages and include/exclude filters | `CONDITIONAL` R0 candidate | Configuration declaration, package/type evidence, condition region |
| `spring.bean.stereotype.direct` | Direct component/service/repository/controller | `SUPPORTED` R0 candidate | Annotation identity plus proven scan/import eligibility |
| `spring.bean.stereotype.composed` | Meta/composed stereotype | `CONDITIONAL` R0 candidate | Annotation declaration graph and dependency metadata where needed |
| `spring.bean.factory-method` | `@Configuration` + `@Bean` | `CONDITIONAL` R0 candidate | Producer signature/name/types, phase/order and conditions |
| `spring.injection.constructor.explicit` | Explicit constructor injection | `SUPPORTED` R0 candidate | Constructor/parameter types, qualifiers and candidates |
| `spring.injection.constructor.implicit` | Single unannotated constructor | `SUPPORTED` R0 candidate | Framework-version rule and exact constructors |
| `spring.injection.constructor.generated` | Lombok/generated constructor | `CONDITIONAL` R0 candidate | Versioned generator semantics or generated-source/bytecode evidence |
| `spring.injection.field` | `@Autowired`/`@Inject` field | `SUPPORTED` R0 candidate | Field shape/qualifiers and registered candidates |
| `spring.injection.method` | Setter/arbitrary method injection | `SUPPORTED` R0 candidate | Method/parameter annotations and candidates |
| `spring.injection.bean-parameter` | `@Bean` method parameter | `SUPPORTED` R0 candidate | Producer transition, parameter and candidate state |
| `spring.injection.resource` | JSR-250 `@Resource` | `CONDITIONAL` R0 candidate | Name/type/version semantics and candidates |
| `spring.disambiguation.qualifier` | `@Qualifier` and composed qualifier | `SUPPORTED` R0 candidate | Qualifier identities/values on point/candidate |
| `spring.disambiguation.priority` | `@Primary`, `@Fallback`, name/order rules | `CONDITIONAL` R0 candidate | Pinned framework-version selection semantics |
| `spring.injection.aggregate` | Collection/array/map/optional/provider/lazy | `CONDITIONAL` R0 candidate | Container/generic shape and ordered candidates; dynamic behavior qualified |
| `spring.condition.profile` | `@Profile` and profile expressions/groups | `CONDITIONAL` R0 candidate | Normalized expression and configuration-space evidence |
| `spring.condition.property` | `@ConditionalOnProperty` and property predicates | `CONDITIONAL` R0 candidate | Key/value/missing semantics, precedence and finite domain |
| `spring.condition.build-context` | Class/resource/web-mode conditions | `CONDITIONAL` R0 candidate | Exact build/resource/web-mode evidence |
| `spring.condition.bean-state` | On-bean/missing-bean/single-candidate | `CONDITIONAL` R0 candidate | Ordered bean-definition state and framework version |
| `spring.condition.custom-expression` | Custom condition and SpEL | `DYNAMIC` R0 candidate | Detect/retain expression; additional evaluator/runtime evidence when needed |
| `spring.registration.auto-configuration` | Imports/selectors/metadata and ordering | `CONDITIONAL` R0 candidate | Dependency metadata, condition IR and registration plan |
| `spring.registration.spring-data` | Repository proxies and fragments | `CONDITIONAL` R0 candidate | Enablement/scan/store/exclusion/factory evidence |
| `spring.registration.factory` | `FactoryBean`/factory-produced definitions | `CONDITIONAL` R0 candidate | Factory product metadata and condition region |
| `spring.registration.xml` | XML bean/context wiring from 1.x onward | `CONDITIONAL` R0 candidate | Exact document/version, references and bounded XML semantics; unmodeled forms retain gaps |
| `spring.registration.programmatic` | `registerBean`, registrars, post-processors | `DYNAMIC` R0 candidate | Call/mechanism evidence plus configuration/build/runtime requirement |
| `spring.lookup.container` | `getBean`, service locator, `@Lookup` | `DYNAMIC` R0 candidate | Call/name/type evidence and unresolved target region |
| `spring.expression.value` | `@Value` and dependency-bearing SpEL | `DYNAMIC` R0 candidate | Expression/property evidence and explicit evaluator boundary |
| `spring.runtime.proxy-aop` | Proxies/advisors/runtime dependencies | `DYNAMIC` R0 candidate | Enabling declarations plus bytecode/runtime evidence when needed |
| `spring.entrypoint.framework` | MVC endpoints, listeners, scheduled/lifecycle callbacks | `CONDITIONAL` R0 candidate | Registration/condition evidence; never inferred dead from absent callers |
| `spring.mechanism.unclassified` | Unrecognized annotation/use, namespace, metadata or extension | `UNSUPPORTED` R0 candidate | Exact occurrence/artifact, unresolved role and typed gap; never silently omitted |

Every `UNSUPPORTED`, `DYNAMIC` and `OUT_OF_SCOPE` case has a stable reason and capability-gap/denominator entry where it affects requested outputs.

## Ground Truth and Evaluation

For every catalog row, register positive cases where applicable plus negative controls, missing classpath/configuration, conflicting property precedence, ambiguity, inactive conditions, alternative registration orders, unsupported forms and provider faults.

Ground truth separates:

- mechanism detection from activation/registration/binding correctness;
- candidate recall from selected-binding precision;
- exact realized-world results from truth-region classification;
- semantic attribution from span/provenance completeness;
- logical `UNKNOWN` from operational failure;
- expected catalog exclusions from unexpected omissions.

Required measurements include closed-denominator precision/recall, false-certainty, false-unconditional warnings, quantifier confusion, witness validity/minimality, affected-region accuracy, deterministic digest agreement and bounded time/memory.

Applicable baselines are Java-static graph, one realized Spring configuration, flat presence conditions, ArchUnit, Spring Modulith and Jasmine where artifact/version/rule equivalence can be reproduced. ArchUnit and Spring Modulith are not characterized as only runtime-`ApplicationContext` analyzers.

## Graph and Product Projections

The graph preserves producer, candidate, injection-point, condition, transition, fact-region, witness and evidence identities. Product/query views support:

- one realized configuration;
- `MUST`-only architecture;
- condition-labeled `MAY` facts;
- bounded union exploration with infeasibility warnings;
- unknown/gap overlays;
- “why active/inactive/registered/selected?” explanation;
- witness replay input and affected configuration region.

A convenience bean-to-bean dependency always links back to the injection point, region and supporting selection evidence.

## G3 Acceptance

G3 requires:

1. approved identities, framework-version matrix and v1 semantic fragment;
2. every `spring-mechanisms:v2` row reconciled in a closed denominator;
3. no silent omissions or false universal promotions in adjudicated fixtures;
4. phase/order behavior verified, including missing-bean non-monotonic cases;
5. deterministic valid witnesses and explicit unresolved residue;
6. fair baseline and representative repository results with failures included;
7. explicit limits and no complete-container-equivalence claim.

## Remaining Decisions

The [M4-R0 candidate](m4-r0-semantics-gate.md) now makes the following proposals concrete. They remain subject to its recorded review/acceptance gate rather than unscoped design work.

- Exact Java schemas/preimages for M4 concepts and `ConfigurationSpaceIdentity`.
- First supported Spring Boot/Framework/Lombok/Spring Data version matrix.
- Exact v1 condition/registration fragment and unsupported boundaries.
- Property-source/import abstraction and sound `OTHER` domains.
- Solver/branch limits and benchmark-backed backend selection.
- Graph node-versus-record representation and query payload budgets.
- Corpus repositories, historical pairs and registered baselines.

## Related Documents

- [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md)
- [Conditional Architecture Semantics](conditional-architecture-semantics.md)
- [Research Review](../research/2026-09-09-conditional-architecture-redirection-review.md)
- [Roadmap](../roadmap.md)
- [Knowledge Graph](knowledge-graph.md)
- [M3 Workspace and Build-Model Contract](m3-workspace-build-model.md)
- [Progressive Evidence Acquisition Contract](evidence-acquisition.md)
- [Product Outcome Contract](product-outcome.md)
- [Current State](../current-state.md)
