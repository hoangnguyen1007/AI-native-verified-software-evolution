# M4-R0: Research and Semantics Gate

Date: 2026-09-11. Roles: Lead Architect and Semantic Analyst.

**CONFIRMED human scope:** execute M4-R0 and research the full historical mechanism envelope, from Spring 1.x XML through Spring Boot 3.4.x, including annotations and phase/order interactions. G2 is **recorded PASSED** in the supplied current state and adjudication record; this task does not independently re-certify G2.

**ACCEPTED gate contract (2026-09-11):** the contracts below have been reviewed and accepted by the human supervisor, supported by executable research evidence in the [R0 package](../../benchmarks/m4-r0/README.md). They refine ADR-004, establish the accepted `spring-mechanisms:v2` catalog and `m4-r0-identity-v1` preimages, and authorize the start of M4A production code. See the [research assessment](../research/2026-09-11-m4-r0-spring-semantics.md).

## 1. Scope and exit decision

The research envelope covers XML, annotations, mixed configuration, metadata and extension SPIs across every historical era through Boot 3.4. A finite catalog can close an observed denominator; it cannot enumerate all future/custom annotations or prove every patch release behaves identically. Unknown declarations, uses, namespace elements and registration extensions therefore have a mandatory catch-all obligation, with exact input provenance and a typed gap. No repository is rejected merely for using an older mechanism.

Three dimensions must remain separate:

1. **Historical research coverage:** mechanisms and version boundaries investigated or explicitly registered for investigation.
2. **Executable evidence:** exact fixture, framework artifact, JDK, assignment, event sequence and outcome actually observed.
3. **Production support:** the versioned M4 implementation fragment accepted after contract review and conformance. R0 implements no production extractor.

R0 exit requires reviewed identity preimages and relationship meanings, a frozen mechanism denominator, a declared first fragment and version matrix, configuration/order/UNKNOWN semantics, executable oracle controls, and preregistered solver/corpus/baseline limits. Independent adjudication and human acceptance remain distinct from this author's self-checks. G3 additionally requires production extraction and representative-repository correctness evidence.

## 2. Historical and framework-version matrix

This is an inclusive research map, not a range-based support switch. The framework version is obtained from the exact M3 classpath, separately from Boot. Dependency overrides and split/mixed Spring versions require their own matrix entry or an explicit gap. Boot-free applications have `boot = null`, not an invented Boot version. Every `x` row below requires exact artifact pins before a new empirical claim.

| Era / research row | Mechanisms and discriminating boundary | Evidence / validation state |
|---|---|---|
| Framework 1.0–1.2; anchor 1.2.9 | DTD XML, bean definitions, aliases, constructor/property refs, parent/abstract definitions, autowire modes, factories, lookup/replacement, BeanFactory/ApplicationContext hierarchy, processors | S01 manual inspected; compact XML case executes on modern pins only; no 1.x runtime equivalence claim |
| Framework 2.0 | XSD namespaces and namespace handler/parser extension boundary; XML remains first-class | Historical research row; exact 2.0 patch/API inventory and runtime labels pending |
| Framework 2.5; anchor 2.5.6 | Component scanning/stereotypes, annotation DI, qualifiers, JSR-250 and mixed XML/annotation contexts | S02 inspected; version-specific extraction fixtures pending |
| Framework 3.0 | Core JavaConfig, `@Configuration`/`@Bean`/`@Import`, JSR-330 and SpEL | Historical research row; exact patch and javax API pins pending |
| Framework 3.1; anchor 3.1.4.RELEASE | Environment/property sources, profiles and XML profile activation | S03 inspected; do not apply modern profile-expression grammar retrospectively |
| Framework 3.2 | Refinements of JavaConfig/container semantics | Separate unvalidated patch family, no assumed equivalence |
| Framework 4.0–4.2 | `@Conditional`/configuration phases, richer metadata, composed annotations/aliases, event listeners | Separate research rows; exact introduced-version claims require matching source/API, not annotation-name guessing |
| Framework 4.3; anchor 4.3.30.RELEASE | Single unannotated constructor injection; changes to aggregate matching and injection forms | S04 documents the constructor boundary; old runtime not executed |
| Framework 5.0 | Reactive/functional registration and WebFlux entry-point families | Registered mechanism obligations; exact patch/runtime labels pending |
| Framework 5.1 | Profile expression grammar and registration behavior are versioned | S20 verifies expression parser on 5.3.31; 5.1 patch not certified by this |
| Framework 5.2 | Full/lite configuration and `proxyBeanMethods` boundary | Registered; no blanket behavioral equivalence to 5.3 |
| Framework 5.3 / Boot 2.7.18 | Dual auto-configuration discovery, exact selected Framework 5.3.31 | Source S17/S24 and controlled execution |
| Boot 1.0–1.5 | Auto-configuration/`spring.factories`, condition families, legacy external configuration | All minor lines remain research rows; no invented exact Framework pairing; 1.5/4.x data not silently mapped to Boot 2 |
| Boot 2.0–2.3 | Pre-Config-Data loading, overrides/circular-reference policies and version-specific defaults | Registered legacy loading branch; exact dependencies/configuration policy required |
| Boot 2.4–2.6 | Config Data, imports/config trees, document activation and profile groups; still legacy auto-configuration discovery | S08 migration evidence; loader/import evaluation not implemented in the small oracle |
| Boot 2.7.x | Both discovery files read and duplicate entries removed | S05 and pinned S24; patch claims bounded to selected artifacts |
| Boot 3.0.x–3.1.x / Framework 6.0 family | Java 17 baseline and Jakarta changes; `EnableAutoConfiguration` entries in `spring.factories` no longer discover auto-configurations | S06; other `spring.factories` keys remain relevant; exact patch behavior not runtime-tested here |
| Boot 3.2.x–3.3.x / Framework 6.1 family | Parameter-name evidence and injection selection remain versioned | Selected 3.3.5/6.1.14 executed; 3.2 is an unvalidated research row |
| Boot 3.4.x / Framework 6.2 family | Fallback/default-candidate semantics and name versus priority change | Selected 3.4.0/6.2.0 executed; S15/S23; later 3.4 patches require conformance |

Sources S01–S37, exact URLs and acquired-byte SHA-256 values are in [the source list](../../benchmarks/m4-r0/sources.json) and [acquisition lock](../../benchmarks/m4-r0/sources-acquired.lock.json). The three executable framework tuples are controlled experiment inputs, not a claim that arbitrary Boot/Framework overrides are supported. Java 21 runs these fixtures compiled with release 17; a Java 8/17 host matrix is still unmeasured.

Lombok, Spring Data stores, Cloud, Security, Batch, Integration, AMQP/Kafka and third-party starters are separately versioned evidence-provider inputs. No inferred compatibility from a shared Spring package prefix. Their declaration and mechanism obligations remain in the denominator even where production semantics are not yet supported.

## 3. Closed catalog and annotation obligations

The machine-readable [catalog candidate](../../benchmarks/m4-r0/mechanisms.json) refines the existing 28 families and adds `spring.mechanism.unclassified`: **29 primary families**, revision `r0-candidate-1` of the as-yet-unapproved `spring-mechanisms:v2`. Existing IDs remain stable. XML is included in the candidate bounded semantics; the former first-implementation `OUT_OF_SCOPE` target is superseded by this proposal, subject to gate review.

An annotation is evidence, not a bean. The same `@Autowired` can induce constructor, field or method obligations. Each obligation has its own occurrence/role key and exactly one primary family; secondary tags do not inflate that denominator. A separate raw-input inventory counts every annotation use/declaration and XML/metadata occurrence exactly once. Every raw row maps to one or more obligations, a positively established non-semantic marker role, or an unclassified gap. Missing annotation declaration bytes, meta-annotation cycles, alias conflicts, repeatable containers, inherited search strategies and default values cannot become certain inferred semantics.

The research binary census reads `ACC_ANNOTATION` from every classfile in the 26 locked JARs without loading classes. Its 402 annotation declarations are an exact artifact-level inventory, not a full historical annotation-use inventory. Simple names only nominate candidate families for later declaration review; all rows still require semantic adjudication. The 192 declarations without nominated families remain explicit. Extending the corpus or resolving additional declarations yields a separately versioned inventory, never deletion of difficult rows.

Required family subcases include:

| Obligation group | Mandatory discriminating forms |
|---|---|
| XML | DTD/XSD, local imports, namespaces, aliases, parent merge/abstract templates, inner beans, `ref` vs `idref`, collections and merge, factory-bean/method, autowire modes, defaults, scopes, init/destroy, lookup/replaced-method; custom namespace code remains opaque |
| Discovery | Direct/composed stereotypes, JSR-330 names, scan roots/filters, inherited/nested configuration, component index, bean naming, import cycles, duplicate discovery paths |
| Java producers | Full/lite `@Configuration`, static/instance `@Bean`, return-type visibility, aliases, inherited/overloaded methods, factory self-invocation, `proxyBeanMethods`, Spring Data and generated members |
| Injection | Explicit/implicit/generated constructors, field/method/factory parameters, required/optional, javax/jakarta injection and Resource, generic shape, collections/maps/arrays, providers/lazy, self-reference and resolvable framework dependencies |
| Selection | Qualifier attributes/meta-qualifiers, primary conflicts, fallback markers, candidate flags, dependency name/alias and parameter metadata, priority ties, hierarchy and multiple contexts |
| Conditions | Profiles, property presence/value, class/resource/Java/web/war/cloud/JNDI, bean names/types/annotations and search strategies, single-candidate, nested all/any/none/custom/SpEL |
| Registration | XML readers, selectors/registrars, deferred groups, metadata files, Boot imports/exclusions/filters/before-after, programmatic singleton/definition/supplier registration, registry/factory processors |
| Runtime-facing effects | FactoryBean product versus factory, scopes/proxies/AOP, transaction/security/cache/async interception, lifecycle/events/MVC/WebFlux/messaging/scheduling, generated/AOT/test bootstrap differences |

Arbitrary `@Enable…` names, third-party annotations, functional router definitions, XML namespaces or Spring SPI implementations are not assumed understood. Preserve their exact declarations/uses and register a capability gap for the requested output. A known marker annotation may be classified as no architecture effect only with declaration and use-role evidence; it is not thrown away because its name looks unimportant.

## 4. Canonical relationship semantics

These are proposed M4 relationship kinds; do not modify M1's existing Java relationship catalog in R0. Endpoints refer to typed Spring records, with origin and evidence references; M5 later chooses storage nodes/records.

| Kind | Source → target | Required qualification |
|---|---|---|
| `spring.declares-producer` | source/configuration declaration → producer | exact source or artifact occurrence |
| `spring.produces-bean-candidate` | producer → candidate definition | container/discovery path, producer slot, names/types |
| `spring.declares-injection-point` | declaration/candidate owner → injection point | exact site or explicit generated derivation |
| `spring.has-condition` | producer/registration/site → condition occurrence | occurrence evidence plus expression identity |
| `spring.precedes-registration` | registration event → registration event | ordering domain, phase and authoritative constraint |
| `spring.registers-definition` | transition → candidate | state before/after, logical outcome and truth region |
| `spring.injection-candidate` | injection point → candidate | type/qualifier compatibility and active region |
| `spring.selected-binding` | injection point → candidate or explicit aggregate | versioned selection and non-ambiguous region; no null fabricated target |
| `spring.observed-binding` | runtime site observation → observed object/definition | separate run identity and observation provenance |
| `spring.framework-entrypoint` | framework registration → callback/route | activation and registration evidence, not ordinary Java caller evidence |

Zero candidates, ambiguity and unknown selection are result records with candidates/reasons, not fake relationship targets. A convenience bean-to-bean dependency is derived from these records and always retains the injection-site and region references. Bean definitions with identical Java types can be distinct candidates; bean names are container-scoped, not global identities.

## 5. Exact proposed identity preimages

**PROVISIONAL schema candidate `m4-r0-identity-v1`.** Use the existing M1 canonical JSON rules and SHA-256 domain envelope:

`H(kind, payload) = kind + ':' + SHA256_UTF8(CanonicalJson({components:[CanonicalJson(payload)],kind:kind,version:1}))`.

The digest text includes `sha256:` as in M1. New kind names below are distinct from all existing M1 kinds. `payload.schema` is required. Object keys use Java UTF-16 natural ordering, not locale or Python code-point ordering. Preserve Unicode without normalization, reject unpaired surrogates and binary floating point. Lists described as sets are sorted and duplicate-rejected; classpath, source precedence, argument positions and actual execution orders remain ordered. A later Java implementation must execute Unicode/canonicalization golden tests; the ASCII research evaluator is not their proof.

| Kind / typed concept | Exact payload fields besides `schema` |
|---|---|
| `spring-build-context` / BuildContextIdentity | `snapshotIdentity`, `sourcePlanIdentity`, `moduleSourceSet`, `frontendAssemblyIdentity`, `orderedResolutionInputs`, `platformViewIdentity`, `buildPolicyIdentity` |
| `spring-envelope` / ConfigurationEnvelopeIdentity | `layer` (repository/deployment), `documents` (ordered digest/activation/import records), `declaredSources` (ordered source descriptors), `acquisitionPolicyIdentity` |
| `spring-condition-expression` / ConditionExpressionIdentity | `irVersion`, `frameworkSemantics`, `operator`, `typedOperands`, `childExpressionIdentities` |
| `spring-condition-occurrence` / ConditionOccurrenceIdentity | `expressionIdentity`, `declarationEvidenceKey`, `metadataPath`, `role`, `ordinal` |
| `spring-producer` / BeanProducerIdentity | `buildContextIdentity`, `containerKey`, `declarationEvidenceKey`, `producerKind`, `declarationSlot`, `discoveryPathKey` |
| `spring-bean-candidate` / BeanDefinitionCandidateIdentity | `producerIdentity`, `containerKey`, `definitionSlot`, `declaredNameKey` |
| `spring-injection-point` / InjectionPointIdentity | `buildContextIdentity`, `ownerDeclarationKey`, `siteKind`, `siteSlot`, `declarationEvidenceKey` |
| `spring-framework-entrypoint` / FrameworkEntryPointIdentity | `buildContextIdentity`, `containerKey`, `triggerProducerIdentity`, `callbackDeclarationKey`, `entrypointKind`, `declarationSlot`, `entrypointSemanticsVersion` |
| `spring-registration-event` / RegistrationEventIdentity | `buildContextIdentity`, `triggerOccurrenceIdentity`, `parentInvocationPath`, `phase`, `eventSlot`, `frameworkSemantics` |
| `spring-configuration-space` / ConfigurationSpaceIdentity | `buildContextIdentity`, `repositoryEnvelopeIdentity`, `deploymentEnvelopeIdentity` (nullable), `domains`, `constraints`, `precedencePolicy`, `profilePolicy`, `abstractionVersion`, `feasibilityPolicy` |
| `spring-registration-plan` / RegistrationPlanIdentity | `buildContextIdentity`, `frameworkSemantics`, `eventDescriptors`, `precedenceConstraints`, `initialDefinitions`, `containerHierarchy`, `orderCompleteness`, `overridePolicy` |
| `spring-semantics-context` / ConditionalSemanticsContextIdentity | `buildContextIdentity`, `configurationSpaceIdentity`, `registrationPlanIdentity`, `mechanismCatalog`, `conditionIrVersion`, `frameworkSemantics`, `registrationSemantics`, `bindingSemantics`, `reasonerPolicy`, `deterministicLimits` |
| `spring-definition-state` / BeanDefinitionStateIdentity | `semanticsContextIdentity`, `containerKey`, `processedEventPrefix`, `definitions`, `aliases`, `completeness` |
| `spring-registration-transition` / RegistrationTransitionIdentity | `semanticsContextIdentity`, `eventIdentity`, `inputStateIdentity`, `outputStateIdentity`, `conditionTruth`, `operation`, `branchKey` |
| `spring-binding-candidate` / BindingCandidateIdentity | `semanticsContextIdentity`, `injectionPointIdentity`, `beanCandidateIdentity`, `bindingSemanticsVersion` |
| `spring-legal-order` / LegalOrderIdentity | `registrationPlanIdentity`, `orderedEventIdentities`, `realizabilityEvidence`, `orderInterpretation` |
| `spring-world` / ModeledWorldIdentity | `semanticsContextIdentity`, `fullAssignment`, `legalOrderIdentity`, `branchEvidence` |
| `spring-fact-key` / ConditionalFactKey | `kind`, `typedSource`, `typedTargetOrCandidates`, `siteIdentity`, `factSemanticsVersion` |
| `spring-fact-region` / ConditionalFactRegionIdentity | `factKey`, `semanticsContextIdentity`, `regionEncodingVersion`, `trueRegion`, `falseRegion`, `unknownRegion`, `feasibilityStatus` |
| `spring-witness` / ConfigurationWitnessIdentity | `factRegionIdentity`, `baselineConfigurationIdentity`, `fullAssignment`, `deltaAssignment`, `legalOrderIdentity`, `branchEvidence`, `expectedTruth`, `witnessPolicyVersion` |
| `spring-evaluation-result` / EvaluationResultIdentity | `semanticsContextIdentity`, `regionIdentities`, `witnessIdentities`, `operationalOutcomeDigest`, `evidenceLedgerDigest`, `evaluatorArtifactDigest` |

`declarationEvidenceKey` is a tagged union: source document identity plus raw digest/full span/stable role ordinal; artifact digest plus entry digest/metadata pointer; or generated derivation input identities plus method/version and slot. A missing span stays absent with a provenance gap. An annotation span never becomes a generated constructor span. `containerKey` is derived from an evidenced bootstrap/parent path, not a guessed universal application context. `definitionSlot`/`discoveryPathKey` distinguish repeated producer use without relying on evaluated registrations. Runtime object identities are run-scoped and never used as static definition identity.

Normalized expression identity separates reusable formula content from source occurrences. Pure commutative ALL/ANY operands may be sorted; evaluation-event order of custom/stateful conditions belongs in occurrence/plan records and is never sorted away. Fact-region identity excludes witness and final result; witness excludes its later replay result. Replay evidence is a separate record keyed by witness and evaluator/build inputs, preventing a witness↔replay digest cycle.

The dependency graph is acyclic: M1/M3 inputs → evidence/producers/conditions/candidates/sites/envelopes → space and registration plan → semantics context → fact region → witness → evaluation result. Regions store **world keys derived from assignments/order**, never witness IDs. The plan cannot contain semantics-context or result IDs; a condition cannot depend on final bean-state identity. M1 `ConfigurationIdentity` remains untouched and identifies a concrete option map. Canonical manifest integration is a later additive versioned M4 change.

Input changes that alter identity: property-source order, domain partition, framework artifact/semantics pin, legal registration order, override policy and deterministic limits. Non-identity metadata: timings, localized descriptions, cache paths, timestamps and host traversal order. Environment/JDK artifact versions still belong to evidence where they affect execution. Unknown feasibility or mismatched exact inputs withholds equivalence/compatibility claims.

## 6. First semantic fragment and configuration envelope

The recommended initial implementation sequence accepts observed mechanisms from every era, while evaluating only the following **evidence-complete fragment**. Historical rows outside a validated patch remain visible with gaps, never silently treated as a modern container.

| Input / operation | First evaluable fragment | Explicit remainder |
|---|---|---|
| XML | Safely acquired exact local documents; direct bean/alias/constructor/property refs and statically typed factories when source/type evidence is complete | parent/merge/autodetect/custom namespaces and dynamic imports need dedicated conformance; DTD/XSD resolution uses pinned local schema evidence, never remote entity expansion |
| Annotations | Proven scan/import membership, exact annotation declarations, supported producer/injection shapes | incomplete meta-graphs, synthetic constructors, unresolved types and custom registrars remain gaps |
| Profiles | Set-valued active/default profiles, versioned OR-array/expression semantics, evidenced group/include closure | arbitrary host/environment profile decisions and unmodeled loaders remain UNKNOWN |
| Properties | Exact key/prefix, all-name conjunction, `havingValue` and `matchIfMissing`; declared finite values with MISSING distinct from empty | binder-wide relaxed naming, conversion, Unicode folding, collection indexing and unresolved placeholders need their own semantics evidence |
| Build constants | Class/resource/Java predicates proven from complete exact inputs | class presence does not prove successful linking; incomplete classpath cannot prove absence; resource/provider faults remain explicit |
| Web/deployment | Explicitly evidenced servlet/reactive/non-web or deployment descriptor domains | never guess web mode solely from a starter name; cloud/JNDI and ambient deployment are opaque unless supplied |
| Bean state | Single exact container, complete types and explicitly ordered presence/missing/single-candidate predicates | hierarchy/search modes, factory product types, ignored and parameterized-container variants require explicit matching implementation evidence |
| Binding | Complete single-context candidates, proven type/qualifier compatibility, version-specific primary/name/priority/fallback rules, explicit required/optional shape | complex generics, providers/scoped proxies, early references and custom resolvers remain separate obligations |

The executable R0 oracle is intentionally smaller than this implementation proposal: it consumes manually authored IR, an ASCII property partition, explicit Boolean profile atoms, a single undifferentiated bean type and supplied total orders. It does not prove extraction, external configuration loading, partial-order exploration or all first-fragment rows.

### Configuration inputs and precedence

Environment construction precedes condition evaluation. Conditions are evaluated at their actual parse/registration invocation with that world's environment; profiles/properties must not first be applied after registration. Pure conditions can be precomputed per world, but the semantic evaluation point remains recorded.

Represent each property source by name/kind, artifact/document digest, activation guard, import ancestry, ordered precedence, raw value/domain and value-conversion policy. Repository declarations and explicit deployment values stay separate. There is no implicit read from the analyzer's environment, system properties, home directory, working-directory configuration or network.

For the first normalized-envelope input, precedence is explicitly supplied low-to-high and the last active definition wins, including an empty string. Boot adapters must later prove how they derived that order. Do not apply a single loader table to Boot 1.x/2.3 and Boot 2.4+: Config Data changed document/import behavior (S08). Within the Boot 3.4 reference, defaults, configuration annotations/data, random/environment/system/JNDI/servlet/JSON/command-line and test/devtools sources have distinct positions and availability phases (S09). Test contexts are separate launch modes, never production defaults. `@PropertySource` timing cannot establish properties consumed before context refresh.

Missing optional location, missing required location, failed read, denied read and unrecognized loader are different outcomes. Missing required Config Data can make a world fail startup; it is not a healthy world with zero beans. External unobserved sources that could override a queried value create U for that predicate. Proven local values may still support unrelated facts.

Finite domains include explicit exact values, MISSING and optionally OTHER only with a proof that every supported predicate is uniform over that partition. `false` ignoring case, empty, whitespace-bearing strings and unknown values need distinct treatment. Boolean case matching is not trimming. Profile names are case-sensitive sets; default profiles apply when no active profile is declared. Do not invent dev/prod mutual exclusion. Feasibility constraints and group closure require evidence. No feasible worlds is INVALID_MODEL, never a vacuous certainty.

## 7. Phase and order model

Use **nested, versioned event traces with explicit ordering domains**, not a global sort of annotations. The following domains can interleave or nest, particularly configuration processing within a registry processor:

| Domain | Required distinctions and causal effects |
|---|---|
| Environment/bootstrap | Property-source preparation, Config Data/imports, context initializers and parent contexts; startup mode is an input |
| Definition readers/discovery | XML and scan/import entry points; custom namespace and registrar invocation sites; full/lite configuration discovery |
| Configuration conditions | `PARSE_CONFIGURATION` vs `REGISTER_BEAN`; ordinary Condition may be invoked at either applicable call site, ConfigurationCondition declares its phase (S11) |
| Registry post-processors | Programmatically supplied order differs from autodetection; detected PriorityOrdered, Ordered, then remaining processors; additional discoveries trigger further registry passes (S14) |
| Auto-configuration | Deferred selection/groups, candidate deduplication, exclusions, filters, metadata, before/after constraints and AutoConfigureOrder, with source-backed tie rules (S24–S26) |
| Factory post-processors | Registry post-processors' factory callbacks, supplied regular processors and detected ordering; definition mutation precedes normal bean creation |
| Bean post-processors | Registration order, priority/ordered groups and special internal processors; arbitrary processor effects can alter types/injection/proxies |
| Injection/instantiation | Dependency/type/name resolution, constructors/factory calls, scopes, FactoryBean products, circular/early references and dependency initialization |
| Initialization/destruction | Property population, before/after initialization processors, init/destroy callbacks and dependency constraints; separate from registration order |
| Lifecycle/events | SmartLifecycle phase and dependency start/stop behavior, event listeners, runners and scheduling; callback order is not bean-definition order (S19) |

`@Order`/Ordered, PriorityOrdered, `@Priority`, `@DependsOn`, AutoConfigureBefore/After/Order and SmartLifecycle.getPhase have different consumers. Auto-configuration before/after affects definitions, not a blanket instantiation sequence. Collection order does not select a single bean. `depends-on` concerns creation/destruction dependencies and must not be treated as a parse-condition precedence edge.

At each registration event evaluate against definitions already established at that event, not the final container. Missing-bean conditions are non-monotone: two fallbacks can choose different definitions under reversed supplied registration sequences. Known programmatic or custom processors may add, remove or replace definitions; an unsupported mutation invalidates affected later state closure. It cannot be modeled as an inert no-op. Opaque parse conditions can hide an entire import subtree; the unknown effect is not confined to one visible bean.

Legal partial orders are constraints backed by framework/input evidence. A lexical order may canonicalize exploration, never fabricate framework precedence. Exhaustive exploration of **explicitly modeled nondeterministic legal orders** may yield MAY over `(configuration, order)` worlds. When the actual order is merely missing evidence, retain epistemic U at that configuration and request order provenance; two hypothetical permutations do not prove two realizable deployments. Record world-space interpretation in identity.

Duplicate names require explicit overriding policy, parent/child shadowing and declaration history. An override is not two simultaneously active beans; a forbidden override is a container-error outcome. Alias cycles, unknown FactoryBean products, scan ambiguity and early instantiation remain typed. No universal least-fixpoint replacement of Spring semantics is permitted.

### Versioned binding algorithm

After type/qualifier eligibility and required/container-shape rules, selection follows the pinned container algorithm. S15–S17 establish that 5.3.31 and 6.1.14 check primary then highest priority before dependency-name fallback, whereas 6.2.0 checks primary/unique non-fallback, then dependency/qualifier-suggested name before priority. Local/parent primary conflicts and special resolvable dependencies have additional rules. The compact R0 formal binding function covers complete, already-filtered, single-context candidates only.

`@Resource` is separately modeled using actual javax/jakarta namespace, explicit versus default name and fallback policy (S18). `@Primary` does not override a qualifier that excludes that bean. `@Fallback` is not optional injection; `@Bean(defaultCandidate=false)` is not `autowireCandidate=false`. Boot 3.4's bean-condition matching filters candidate flags (S23), so a simple count of all definitions is insufficient.

## 8. Regions, UNKNOWN and witnesses

World results have separate logical, semantic and operational axes. For known feasible K, T/F/U are disjoint and exhaust K. MUST requires non-empty K and T=K; NEVER requires non-empty K and F=K; MAY requires at least one evidenced true and false world, retaining any U residue. Otherwise UNKNOWN. Unknown feasibility never certifies universal facts.

An operational ERROR/TIMEOUT/DENIED/UNAVAILABLE/LIMIT_EXCEEDED retains its own cause, affected output and attempted method. Deterministic node/step/order/branch caps participate in semantics-context identity. Wall-clock aborts produce a separate incomplete attempt; elapsed timing must not determine a falsely stable partial semantics digest. Same complete inputs and deterministic budgets must replay identically; timed-out attempts never claim complete canonical equivalence.

Pure Boolean IR uses versioned strong-Kleene composition. Repeated opaque predicates with a known shared identity must retain correlation when exploring branches; independently flipping each occurrence is unsound. Unknown custom code is not nondeterminism. Branch only on a finite, evidenced abstraction; otherwise keep affected U. Definite support for an unrelated fact remains usable.

Witnesses carry full assignment, explicit baseline/delta, exact build/constants, material order/branch and expected value. A baseline-delta witness means omitted fields inherit baseline values; it does not assert all omitted assignments are arbitrary. A partial implicant witness would instead require all legal completions to preserve truth and is a separate proof mode. Minimum cardinality and subset-minimality are distinct promises. Replay checks provenance and feasible assignments, then independently re-evaluates the claim; its record cannot feed back into witness identity.

## 9. Gap mapping and safe evidence acquisition

Reuse M3's `CapabilityGapRecord` schema and existing EvidenceRequirement kinds, with a separate versioned research/M4 reason catalog. Never add enum values by assumption. Research gap export calls the actual immutable Java constructor, including content-addressed provider observation references; it is scoped to the authored study snapshot, not a fabricated analyzed-application snapshot.

| Root cause | Existing requirement kind | Effect |
|---|---|---|
| Missing source/XML/annotation declaration or framework semantics evidence | REPOSITORY_CONTENT / DEPENDENCY_ARTIFACT | retain occurrence/category and qualified interpretation |
| Incomplete class/type/resource closure | EXACT_CLASSPATH / BYTECODE | no closed-world absence or certain binding |
| Missing profiles/properties/import/precedence/order policy | CONFIGURATION | affected predicate/region U |
| Generated constructor/product | GENERATED_SOURCE / BYTECODE | no invented entity/span/origin |
| Arbitrary condition/registrar/processor/proxy | RUNTIME_OBSERVATION / ISOLATED_BUILD_OUTPUT | provider recommendation only, not permission to execute target code |
| Unrecognized extension, version or annotation role | DEPENDENCY_ARTIFACT / ALTERNATE_FRONTEND | unclassified obligation remains counted |
| Reasoning bound | CONFIGURATION with the explicit bounded-world/order question | retain limit outcome and affected U; no guessed result |

XML acquisition disables network entities and never invokes target namespace handlers. Dependency metadata reading never loads target classes. Controlled R0 fixture execution is a research comparator over authored sources and explicitly pinned Spring libraries; it does not authorize a general runtime provider or execution of analyzed repositories.

## 10. Alternatives, verification and next gate

The simple baseline is deterministic exhaustive enumeration for small closed spaces. The proposed architecture adds explicit transition state and typed uncertainty. The strongest viable optimization is a versioned bounded transition encoding behind ConfigurationReasoner with a Java SAT backend, or BDD for repeated region operations. A Boolean SAT conformance probe alone does not validate Spring transition encoding or select a Java library. Preserve the port; benchmark equivalent world spaces and normalize witnesses before adoption.

Flat presence evaluation and one realized configuration remain named baselines, not canonical truth. A union graph is exploratory. Re-running every target application is neither the normal analyzer nor an automatic oracle strategy. The [preregistered protocol](../../benchmarks/m4-r0/PROTOCOL.md) defines comparison fairness, corpus inclusion, counts, negative/limit controls and kill criteria.

**CONFIRMED human decision (2026-09-11):** gate review accepted the additive identities, 29-row denominator/catch-all, bounded XML inclusion, first verified fragment/patch matrix and numerical budgets. This resolves the authorization gate; it does not manufacture independent empirical replication or close the 13 recorded `VERSION_FRAGMENT_NOT_VALIDATED` rows. M4A.1 subsequently delivered evidence-only mechanism inventory and gap normalization over the accepted catalog with closed raw-observation/semantic-obligation denominators. The exact next production slice is **M4A.2: remaining evidence-only mechanism acquisition**. Configuration solving and Spring binding production code wait for their later slices and accepted semantics.
