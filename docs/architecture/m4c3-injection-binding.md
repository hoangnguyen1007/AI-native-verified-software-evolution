# M4C.3 — Injection Binding and Dependency Resolution

**CONFIRMED by implementation and verification, 2026-09-18:** the passive `spring.binding` package supplies definition selection for one exact, completed [M4C.2 registration](m4c2-ordered-bean-registration.md) and configuration assignment. The supported algorithm is pinned to **Spring Framework 6.2.0 / Boot 3.4.0**, under the [accepted M4-R0 contract](m4-r0-semantics-gate.md). This completes the normalized M4C.3 slice. [Verification and handoff](../reproducibility/m4c3-injection-binding-2026-09-18/README.md) distinguish specification tests, the actual Spring oracle, and reactor regression evidence.

Selection is a projection over evidenced definitions before object creation. A selected definition does not establish successful `getBean`, constructor invocation, lifecycle completion, runtime object identity, or a configuration-independent architecture fact. M4D owns truth regions and witnesses; M4E owns G3 evaluation. Neither gate is advanced here.

## Entry point and acquisition boundary

```java
InjectionBindings.Result result = InjectionBindings.evaluate(
        bindingPlan, conditionModel, assignment, exogenousLimits);
```

`InjectionBindingPlan` includes the M4C.2 plan, immutable descriptors, definition metadata, descriptor-specific matching proofs, optional-method groups, M4A obligation mappings, explicit environment closure, and deterministic limits. Evaluation first runs the existing registration engine and retains its complete result and historical capability gaps.

| Input | Contract |
|---|---|
| `InjectionPoint` | Exact build, declaration key, constructor/field/method/factory/XML/generated site kind, site slot and declaration evidence; independent of configuration and chosen target |
| `Dependency` | Point and optional owning candidate, resolved Java type, wrapper/container shape, autowire/resource/reference mode, requiredness, dependency and qualifier-suggested names, qualifier presence, lookup policy, normalization status and evidence |
| `Definition` | Autowire/default/primary/fallback flags, factory-method owner, ordinary/factory/proxy kind, effective priority and order, `PriorityOrdered` membership and provenance |
| `Match` | Whole-descriptor raw type, enumeration membership, strict/fallback generic compatibility, merged qualifier compatibility, operation status and evidence, separately for direct and element lanes |
| `Group` | Ordered parameter descriptors of one optional autowired method, with explicit absent-parameter skip triggers |
| `ObligationBinding` | Proven mapping of an existing M4A injection obligation to one or more descriptors |
| `Environment` | Descriptor inventory completeness; absence of special resolvable dependencies and later definition mutation; comparator policy; evidenced candidate enumeration order |

The descriptor provider is responsible for resolving constructor selection, generic substitutions, merged qualifier attributes/defaults/meta-qualifiers, parameter-name metadata, javax/jakarta semantics, and complete inherited/generated injection sites. It must supply evidence or an incomplete/unsupported descriptor. The engine does not infer Java assignability from names or manufacture generated members. Normalized Java, XML and generated sites share selection semantics only after their distinct acquisition/normalization obligations are satisfied.

`rawType` represents `isTypeMatch(name, requiredClass)`. `typeLookup` separately represents membership in `beanNamesForTypeIncludingAncestors` under the descriptor's eager/non-eager policy. Abstract definitions and non-eager type prediction can distinguish these questions. The convenience `Match` constructor equates them only for a supplying provider's proven common case. `typeLookup=TRUE` with `rawType=FALSE` is a conflict. Unknown/error evidence never becomes a negative match.

`strictGeneric` and `fallbackGeneric` refer to the whole Spring descriptor (including nested types and matching policy). They are not Java-type strings or the bean's `@Fallback` flag. `qualifierMatch` means the result of the full merged qualifier evaluation, not equality of one annotation spelling. `hasQualifier=FALSE` has the resolver's no-qualifier meaning; candidate `defaultCandidate` then matters. Each external proof is scoped to the content-addressed descriptor and candidate.

## Framework 6.2.0 selection order

The implementation follows the versioned decision points in [DefaultListableBeanFactory 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java), [GenericTypeAwareAutowireCandidateResolver 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/support/GenericTypeAwareAutowireCandidateResolver.java), and [QualifierAnnotationAutowireCandidateResolver 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/annotation/QualifierAnnotationAutowireCandidateResolver.java).

1. Require the supported framework tuple, closed registration, proven single-container boundary, valid provenance and applicable descriptor. An inactive owning definition yields `NOT_ACTIVE`. A prior definite container error yields `NOT_REACHED`.
2. Resolve explicit references and the separate Resource name path. Deferred autowire wrappers then yield `DEFERRED` without an immediate selected target.
3. When the descriptor permits the standard lookup shortcut, try its existing dependency name, otherwise the qualifier-suggested name. The candidate must match raw type and strict generic/qualifier eligibility, be non-fallback and non-self, and have no conflicting raw-type primary. The primary-conflict check can include an autowire-ineligible primary. This shortcut precedes aggregate resolution, so a directly named collection bean can win. An existing incompatible dependency name does not immediately switch this shortcut to the suggested name.
4. For standard arrays/collections/maps, find element candidates. Otherwise, or when no elements are found, search candidates for the complete requested type.
5. Candidate search uses strict generic matching first. Only an empty strict set enables Spring's generic fallback pass; multiple-type requests require a qualifier for that pass. Self references are considered last, under the searched type's multiple-value rule. Element descriptors carry their own `elementIndicatesMultiple` evidence.
6. For a scalar candidate set, select the unique candidate; otherwise check primary, unique non-fallback, dependency name/alias, qualifier-suggested name/alias, then highest priority. Conflicting primaries or equal highest priorities are definite ambiguity errors. A qualifier-excluded candidate is never rescued by primary.

Spring 6.2.0 does **not** have the later `determineDefaultCandidate` selection step. The default-candidate flag instead participates in qualifier eligibility: a matching qualifier can admit `defaultCandidate=false`; it cannot admit `autowireCandidate=false`. `@Priority` contributes only through the configured comparator's effective priority result. `@Order` alone does not select a scalar dependency.

The engine uses an evidence barrier when a potentially relevant candidate, flag, name or operation is unknown. It deliberately withholds a selection instead of treating an incomplete candidate universe as unique or absent. A supplied operation error remains `ERROR`, independently of logical matching values.

## Shapes, optionality, aliases and self references

| Form | Established behavior and boundary |
|---|---|
| Scalar / `Optional<T>` | Zero required candidates is `UNSATISFIED`; optional absence is `ABSENT_OPTIONAL`. Multiple unresolved scalar candidates remain `AMBIGUOUS`, including optional/non-required requests. The `Optional` descriptor is normalized to its nested type semantics. |
| Arrays, `List`, `Collection`, `Set`, `Map<String,T>` | All eligible elements participate, including primary and fallback definitions. Primary does not reduce the aggregate. The map retains actual bean names as keys. Unknown membership withholds the aggregate. |
| Aggregate ordering | Arrays/lists/collections use evidenced Spring comparator order when configured: `PriorityOrdered`, then effective order, with stable enumeration order for ties. Sets/maps retain enumeration order. Unknown order preserves certain membership, returns `INCOMPLETE`, sets `orderEstablished=false`, and emits a gap; lexical output order is only canonical serialization. |
| Empty aggregate | A required empty field/method aggregate is normally unsatisfied. Constructor/factory empty-container adaptation requires explicit `emptyAggregateFallback` evidence from descriptor normalization. Optional no-winner collection fallback can be absent; primary/priority conflicts remain errors. |
| Custom collection/map | A directly matching bean can be selected. Unsupported element conversion remains a typed shape gap. Streams and unsupported wrapper compositions require another descriptor provider/version. |
| ObjectProvider / ObjectFactory / Jakarta Provider / lazy | Autowire requests are `DEFERRED`, with a runtime-target obligation and no immediate edge. Later `getObject`, streams, optional callbacks, prototype creation and lazy proxy invocation are outside this fragment. An explicit named reference to a provider bean is ordinary named selection. |
| Optional autowired method | Parameters are evaluated in supplied declaration order. An absent skip-trigger parameter skips the whole invocation and clears previous tentative selections. Nullable/Optional parameters need not be skip triggers. Errors/unknowns stop the group; later parameters remain counted and uninvoked. |
| Self references | Owning candidate and instance `@Bean` factory ownership are separate evidence. Self selection is a last resort; the same bean is excluded from the aggregate self pass while a sibling factory product can qualify. |
| Aliases | Normal aliases resolve through the final registration state. Alias-shadowed definitions are excluded from type enumeration. A name shortcut with alias-shadowed primary metadata remains `ALIAS_SHADOW_SHORTCUT_UNSUPPORTED`; no guessed shortcut result is emitted. |

`@Resource` follows [CommonAnnotationBeanPostProcessor 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-context/src/main/java/org/springframework/context/annotation/CommonAnnotationBeanPostProcessor.java). An existing resource name uses raw name/type resolution and bypasses autowire, default-candidate, qualifier and primary filters. An existing wrong type is an error and does not fall back to another bean. Only an absent default name with enabled fallback delegates to type-based resolution. An absent explicit name remains unsatisfied. JNDI, `mappedName`, custom resource namespaces and unproven namespace/version normalization retain typed gaps.

## Closed outcomes and evidence

Every supplied dependency has exactly one row, including limits, inactive owners, skipped groups and prior errors. `Coverage` verifies that the sum of every outcome equals the supplied dependency count. Every M4A obligation also has a row: `NORMALIZED`, `NORMALIZATION_REQUIRED`, or `NOT_INJECTION`. Missing or invalid injection mappings create a typed gap with original observation/source provenance, including when no descriptor was supplied. `NORMALIZED` proves a descriptor mapping; it does not mean successful binding. An explicitly open descriptor inventory additionally creates `DESCRIPTOR_INVENTORY_OPEN` without erasing independently resolved rows.

Rows distinguish semantic outcomes (`SELECTED`, `AGGREGATE`, `ABSENT_OPTIONAL`, `UNSATISFIED`, `AMBIGUOUS`, `DEFERRED`, `NOT_ACTIVE`, `GROUP_SKIPPED`, `UNKNOWN`, `ERROR`, `NOT_REACHED`) from operational status (`COMPLETE`, `INCOMPLETE`, `LIMIT_EXCEEDED`, `ERROR`, `NOT_INVOKED`, `DEFERRED`). Unknown, ambiguous and failed rows contain no fabricated selected target. A certain aggregate with unknown order retains its membership and an explicit order gap.

Candidate traces retain lane, stage, tri-valued eligibility and directly used descriptor/definition/match evidence. The embedded registration result and input plan retain candidates excluded before a matching stage or not visited after a barrier; a trace is an execution explanation, not an exhaustive cross-product of every hypothetical resolution stage. Method-group traces may contain tentative selections whose effective edges were subsequently cleared.

All upstream gaps remain present. `BindingProcessing` adds typed reasons through the unchanged M3 `CapabilityGapRecord` schema, with observation identities, source spans, requirements and limitations. Definite dependency failures also receive reason records; a reason record is not itself a declaration that the answer is unknown. Missing generic/qualifier proofs remain explicit `MATCH_EVIDENCE_MISSING`; type/flag/source failures retain their more specific causes when known.

Source evidence requires a full span and the exact M3 snapshot/source digest. Artifact evidence must belong to the exact build. Derived evidence binds provider, method and input digests; the supplying provider must retain and justify those inputs. A content digest is integrity/provenance binding, not proof that an arbitrary external claim is semantically true. Conflicting M4C.2/M4C.3 candidate flags are retained as `METADATA_CONFLICT` instead of being silently overwritten.

## Identity, determinism and limits

Provider: `spring.injection-binding:m4c.3`; binding semantics: `spring.binding-semantics:6.2.0-m4c.3-v1`; reason catalog: `evidence.spring-binding-gaps:m4c.3-v1`. Plan/result schemas are `spring-injection-binding-plan-v1` and `spring-injection-binding-result-v1`.

Injection-point and binding-candidate identities use the accepted R0 SHA-256 envelope. The additive `spring-binding-context-v1` context binds exact build, finite configuration space, enriched registration plan, mechanism catalog, condition IR, framework/registration/binding versions, reasoner policy, all deterministic limits, and the binding-plan identity. Input/result identities additionally bind the realized registration result, which includes the assignment. Candidate identities describe a contextual possible relationship; membership/selection status remains in assignment-qualified result rows. Existing M1/M3/M4A/M4B/M4C.1/M4C.2 identities and schemas are unchanged.

Unordered inputs are canonicalized; parameter evaluation and evidenced candidate order remain ordered. Duplicate/conflicting descriptors, foreign references and multiply grouped dependencies are rejected as malformed API inputs. Unsupported semantic evidence is reported through typed results. Reordering equivalent evidence tables preserves replay identity. Changing matching proof, environment, assignment or limit changes its relevant identity.

Default deterministic budgets are 10,000 evaluated dependencies, 100,000 cumulative candidate operations, 100,000 trace rows and 200,000 normalized input cells. Registration/discovery/exogenous budgets remain active. Counters and aggregate cell arithmetic use `long`; budget exhaustion preserves every request and M4A obligation row and cannot certify absence. These are deterministic work/evidence budgets, not a claim of a hard process-memory sandbox; input and closed-denominator output size still scale with the supplied inventory.

Production code performs no I/O, reflection, class loading, bean creation, network access, or target lifecycle execution. The separate opt-in oracle compiles only repository-authored controls with annotation processing disabled, uses seven SHA-256-verified R0 JARs, and never executes an analyzed repository.

## Coverage and remaining provider obligations

The completed slice covers the normalized definition-selection behavior above. It deliberately exposes rather than silently implements unsupported parent/child searches, arbitrary resolvable framework dependencies, custom resolvers, post-registration mutations, FactoryBean factory/product distinctions, scoped/AOP proxies, generated constructors, constructor overload choice, `@Value`/SpEL, JNDI and arbitrary runtime callbacks. An irrelevant excluded factory does not block a proven ordinary selection; a potentially participating unsupported factory does.

There is no automatic M4A-to-complete-descriptor/type/qualifier provider in this slice. M4A obligations and their typed normalization gaps remain visible until stronger authorized evidence is supplied. The real Spring controls validate selected algorithm branches, not arbitrary annotation acquisition, full startup equivalence, broad repository accuracy, historical-version generality or G3. Framework 5.3.31, 6.1.14 and other patch versions are not silently assigned the 6.2.0 algorithm.

**Exact next recommended slice:** M4D.1 finite-world truth-region aggregation over evidenced per-assignment M4C results, with separate T/F/U and operational errors, feasibility/non-vacuity checks and deterministic replay. This task does not select a SAT solver, start a benchmark, or establish a multi-world fact from a single assignment.
