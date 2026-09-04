# M4 Spring Intelligence and Closed Mechanism Taxonomy

## Status and Scope

**PROVISIONAL pre-implementation baseline.** This document narrows OQ-3 and makes the M4 denominator reviewable. It does not start M4, pass G3, claim runtime-container equivalence, or claim that any listed mechanism is implemented.

The catalog is closed per version, not closed forever. `spring-mechanisms:v1` must account for every registered fixture/observation exactly once; a newly discovered mechanism requires an explicit catalog-version change rather than silent omission.

## Modeling Principles

- Model declarations, producers, bean-definition candidates, injection points, conditions and binding candidates separately.
- Do not equate a class annotated with a stereotype, a bean-definition candidate and an instantiated runtime bean.
- Preserve the exact injection site and producer source evidence where it exists.
- Candidate compatibility, selection, activation and runtime observation are separate dimensions.
- A direct bean-to-bean `INJECTS` edge is not canonical source truth. It may be a bounded projection derived from injection-point/candidate evidence with status and provenance.
- Provider provenance is a reference to a versioned observation/evidence artifact, not a lossy string such as `SOURCE_ANALYSIS`.

## Provisional Canonical Concepts

M4 owns the storage-neutral domain meaning; M5 decides which concepts become graph nodes, relationships or indexed records.

| Concept | Meaning and minimum evidence |
|---|---|
| `BeanProducer` | Source/configuration/artifact/runtime mechanism capable of declaring a bean definition: stereotype class, `@Bean` method, factory, auto-configuration, XML or programmatic registration. Carries producer kind, owner, origin, status and real declaration evidence when available |
| `BeanDefinitionCandidate` | A candidate bean definition produced under a specific analysis/configuration context; includes names/aliases, exposed types, scope when known, producer reference, conditions and uncertainty. It is not proof of runtime instantiation |
| `InjectionPoint` | Exact constructor parameter, field, method/setter parameter, `@Bean` parameter or other registered injection site; includes requested type/shape, name, qualifiers, owning bean candidate where known and complete source span when source-declared |
| `ConfigurationCondition` | Profile/property/classpath/bean/expression or other activation predicate with normalized operands, evaluation status, evidence and missing inputs |
| `BindingCandidate` | Evidence that one bean-definition candidate may satisfy one injection point, including assignability, name/qualifier reasoning, selection rank, semantic status, derivation, conditions and provider references |

Suggested semantic relationships are `DECLARES_PRODUCER`, `PRODUCES_BEAN_CANDIDATE`, `DECLARES_INJECTION_POINT`, `HAS_CONDITION`, `INJECTION_CANDIDATE`, and `SELECTED_BINDING`. `SELECTED_BINDING` is emitted only when the registered static/configuration semantics justify one selection; future runtime evidence uses a distinct observation/derivation rather than rewriting it as source-direct.

## Orthogonal Status Axes

The model must not collapse these axes into one `status` property:

| Axis | Example values | Question answered |
|---|---|---|
| Mechanism handling | `SUPPORTED`, `CONDITIONAL`, `DYNAMIC`, `UNSUPPORTED`, `OUT_OF_SCOPE` | What does this provider/catalog promise to do with this mechanism? |
| Semantic attribution | M1 `RESOLVED`, `PARTIAL`, `UNRESOLVED`, `AMBIGUOUS`, `CONDITIONAL`, `UNSUPPORTED`, `ERROR` | What target/candidate result did this analysis establish? |
| Derivation | `DIRECT`, `DERIVED`, `INFERRED` | How was the observation produced? |
| Activation/selection | active, inactive, conditional, candidate, selected, runtime-observed, unknown | What is known about configuration activation or binding selection? |
| Evidence provenance | provider/version plus observation/artifact IDs | Which evidence supports the statement? |

`SUPPORTED` does not guarantee a resolved binding. `DYNAMIC` does not mean ignored. `OUT_OF_SCOPE` means outside the current catalog/provider delivery contract, not permanently excluded from the platform.

## `spring-mechanisms:v1` Closed Denominator Matrix

The “M4 target” column is a provisional gate target, not current implementation status.

| Mechanism ID | Mechanism | M4 target | Minimum source/static evidence | Candidate next evidence when insufficient |
|---|---|---|---|---|
| `spring.bean.stereotype.direct` | Direct `@Component`/`@Service`/`@Repository`/`@Controller` | `SUPPORTED` | Annotation identity, class declaration, component-scan scope, compile classpath | Configuration/component-scan metadata |
| `spring.bean.stereotype.composed` | Meta/composed stereotype | `SUPPORTED` | Annotation declaration graph and usage | Dependency bytecode/annotation metadata |
| `spring.bean.factory-method` | `@Configuration` + `@Bean` producer | `SUPPORTED` | Method, return/exposed types, bean names, parameters and declaration span | Configuration/property evidence |
| `spring.injection.constructor` | Explicit/implicit constructor injection | `SUPPORTED` | Constructor rules, parameter types/qualifiers, candidate set | Generated source, bytecode or configuration |
| `spring.injection.field` | `@Autowired`/`@Inject` field | `SUPPORTED` | Field type/name/qualifiers and candidates | Dependency metadata or runtime observation |
| `spring.injection.method` | Setter or arbitrary method injection | `SUPPORTED` | Method/parameter annotations, signatures and candidates | Dependency metadata or runtime observation |
| `spring.injection.bean-parameter` | `@Bean` method parameter | `SUPPORTED` | Producer method parameter and candidate set | Configuration/runtime observation |
| `spring.injection.resource` | JSR-250 `@Resource` name/type semantics | `SUPPORTED` | Annotation attributes, field/method name, requested type and candidates | Configuration/runtime observation |
| `spring.disambiguation.qualifier` | `@Qualifier` and composed qualifier | `SUPPORTED` | Qualifier identities/values on point and candidate | Dependency annotation metadata |
| `spring.disambiguation.priority` | `@Primary`, `@Fallback`, ordered/priority semantics where applicable | `SUPPORTED` | Candidate annotations and registered selection rules | Runtime/container observation for unsupported combinations |
| `spring.injection.aggregate` | Collection/array/map/optional/provider/lazy injection | `SUPPORTED` | Requested container shape, generic element/key/value types and ordered candidates | Runtime observation for dynamic provider behavior |
| `spring.condition.profile` | `@Profile` | `CONDITIONAL` | Normalized profile expression and explicit active-profile configuration | Configuration set or runtime environment |
| `spring.condition.registered` | `@ConditionalOnProperty`, class, bean, expression and other registered conditions | `CONDITIONAL` | Condition kind/operands plus available property/classpath/bean evidence | Additional configuration, dependency artifacts or controlled runtime |
| `spring.registration.auto-configuration` | Imports, selectors and auto-configuration metadata | `CONDITIONAL` | Import metadata, candidate declarations, classpath and conditions | Dependency metadata/bytecode, configuration or controlled runtime |
| `spring.registration.factory` | `FactoryBean` and factory-produced definitions | `CONDITIONAL` | Factory type/generic/product metadata and conditions | Bytecode or runtime product-type observation |
| `spring.registration.xml` | Legacy XML `<bean>`/context wiring | `OUT_OF_SCOPE` for M4 implementation; detected/accounted | XML resource presence/reference and source location | Future XML/configuration provider |
| `spring.registration.programmatic` | `registerBean`, registrars, post-processors and computed definitions | `DYNAMIC` | Call/type/mechanism presence and arguments that are statically available | Isolated build or controlled runtime observation |
| `spring.lookup.container` | `ApplicationContext.getBean`, service locator and `@Lookup` | `DYNAMIC` | Call site, constant names/types and owner evidence | Configuration or runtime observation |
| `spring.expression.value` | `@Value` and SpEL-derived dependencies | `DYNAMIC` | Expression text, source span and statically referenced property names/types | Configuration/expression evaluator or controlled runtime |
| `spring.runtime.proxy-aop` | Proxies, advisors and runtime-created dependencies affecting architecture | `DYNAMIC` | Enabling annotations/configuration and relevant declarations | Configuration, bytecode or runtime observation |

`UNSUPPORTED` remains available for a registered construct that the current provider can detect but cannot yet interpret safely. It must carry a reason code and [capability gap](evidence-acquisition.md), not disappear from the denominator.

## Ground-Truth and Gate Requirements

For every catalog row, register positive cases where applicable plus negative controls, missing-classpath/configuration cases, ambiguity, conditional activation, unsupported forms and provider faults. Ground truth distinguishes:

- mechanism detection from binding correctness;
- candidate recall from selected-binding precision;
- semantic attribution from source/provenance completeness;
- static/configuration conclusions from runtime observations; and
- expected omissions from unexpected omissions.

G3 requires all `spring-mechanisms:v1` rows to have reviewed handling expectations and reconciled denominators. A row may intentionally remain `DYNAMIC` or `OUT_OF_SCOPE`; gate acceptance requires truthful detection/accounting and bounded claims, not fabricated runtime equivalence.

## Graph and Product Projections

The graph should retain producer, candidate, injection-point, condition and evidence identities. Useful projections include:

- bean-definition candidate → owned injection points;
- injection point → all compatible candidates with disambiguation reasoning;
- condition → affected producer/candidate/binding;
- selected binding only where justified, visibly distinct from candidate edges; and
- runtime-observed binding as a separate evidence layer.

The workbench should show zero/one/many candidates, selection rationale, conditions, unresolved capability gaps and provider provenance. A convenience bean-to-bean dependency view must link back to the injection point and supporting candidate/selection evidence.

## Remaining Decisions

- Exact canonical identities for producer, bean-definition candidate, injection point and condition.
- Condition expression normalization and configuration-set identity.
- Candidate ranking semantics for every qualifier/priority combination.
- Graph node-versus-record mapping and storage-neutral query contracts.
- Which `v1` mechanisms are required as full M4 implementations versus detected/accounted boundaries after fixture evidence.

## Related Documents

- [Roadmap](../roadmap.md)
- [Knowledge Graph](knowledge-graph.md)
- [Progressive Evidence Acquisition Contract](evidence-acquisition.md)
- [Product Outcome Contract](product-outcome.md)
- [Current State](../current-state.md)
