# M4C.2 — Ordered Bean-Definition Registration and Endogenous Conditions

Date: 2026-09-17. Implements the normalized registration boundary under [M4C.1](m4c1-registration-plan-discovery.md), [M4-R0](m4-r0-semantics-gate.md) and [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md).

**CONFIRMED by implementation and independent behavioral verification (2026-09-17).** Production code is present in the neutral `analyzer` module. 28 focused behavioral tests in `BeanRegistrationTransitionsTest` verify all checklist items; regression checks across all 11 Spring suites confirm zero regressions. Injection binding, truth regions and G3 remain subsequent work.

## API and normalized input boundary

Entry point: `BeanRegistrationTransitions.evaluate(plan, conditionModel, assignment, exogenousLimits)`.

| Type | Responsibility |
|---|---|
| `BeanRegistrationPlan` | An evidenced definition-reader schedule, separate from discovery order; initial-registry and parent-context closure; matching/query evidence and budgets |
| `BeanRegistrationPlan.Step` | One condition gate, definition registration/removal, alias registration, or opaque mutation; an explicit reader decision and optional preceding gate |
| `BeanRegistrationEvidence.Query` | Whole-annotation refinement of an existing M4B occurrence; original condition identity/evidence remain intact |
| `BeanRegistrationEvidence.QueryType` | Exact query-type/annotation resolution: `AVAILABLE`, caught `ABSENT`, `UNKNOWN`, or operational `ERROR` |
| `BeanRegistrationEvidence.Definition` | Effective candidate flags and individually evidenced type/annotation match results |
| `BeanConditionLowering` | Bounded literal `name`, `type`, and `ignoredType` refinement, with a row for every original occurrence |
| `BeanDefinitionState` | Registry and alias maps at an identified processing prefix, with closure/execution qualifiers |
| `BeanRegistrationTransitions.Result` | Step/condition outcomes, snapshots, transitions, mutation history, candidate summaries and inherited/additive gaps |

The supplying adapter must prove the actual registration sequence, valid effective definitions, reader-specific decisions (including scan conflicts, method overload/deduplication and import relationships), complete condition attachment/order, and initial registry closure. `readerDecision=TRUE` is that normalized evidence, not permission to execute a target reader. `FALSE` skips the operation; missing evidence uses `UNKNOWN`. These requirements extend M4C.1's explicit normalized input boundary; this slice does not add a general source-to-container acquisition adapter.

`steps` is a canonical set keyed by a unique local key. Only `establishedOrderPrefix` is executable order. Its evidence and completeness are separate inputs. Unscheduled steps retain outcomes; canonical sorting never establishes precedence. Gate references in the prefix must point backward. A bean method or import registrar must retain its owner configuration's registration gate. A gate can also represent a method's condition invocation when its aliases precede its definition.

Aliases are individual steps: the candidate's canonical alias set has no execution order. Every declared alias needs a corresponding evidenced call. A false configuration gate can drive a `REMOVE_DEFINITION` step with `GateExpectation.NO_MATCH`, representing a previously registered configuration definition removed by its reader. This is explicit schedule evidence, not an inferred removal of every false candidate. Definition removal leaves aliases in place. Multiple importer paths and reader-specific special cases must be normalized by the supplier; an arbitrary importer is not automatically the owner gate.

A negative removal gate must be a `CONFIGURATION_GATE` with `readerDecision=TRUE` and an evaluated or inherited false condition. A reader/discovery skip alone cannot justify removal. Candidate `registrationSteps` are canonical references to result rows; the transition list supplies actual execution order.

## Discovery and phase handling

The existing `DiscoveryTransitions.evaluate` API retains its previous behavior and identity policy. The additive `prepareRegistration` mode has a distinct context policy and defers register-site component conditions and definition-reader/registry callbacks to the new schedule. `DISCOVERED` in that mode establishes membership, not condition acceptance or callback effects. Parse conditions still execute at their parse site; bean-method discovery remains independent of method-condition evaluation.

At a registration step, ordinary/register conditions execute in supplied order against the **input snapshot**. Parse-only conditions are recorded as not applicable. A successful gate can be inherited without reevaluating its stateful condition; the inherited invocation retains its original truth and input-state reference. A false guard suppresses the operation and later conditions. Missing phase/attachment evidence or an invoked opaque condition cannot be erased by a later Boolean false value.

Discovery/model/build mismatches, an invalid assignment, missing discovery obligations and a failed discovery pass prevent a complete registration result. Every supplied registration step still receives an outcome. Discovery errors remain available in the embedded discovery result; registration steps not reached after a known failure have no fabricated execution transitions.

## Bean-condition fragment

The evaluator is pinned to the already locked **Spring Framework 6.2.0 / Boot 3.4.0** artifact tuple. Older tuples accepted for M4B exogenous evaluation receive `VERSION_FRAGMENT_NOT_VALIDATED` here. This avoids projecting 3.4 candidate/fallback rules onto older versions.

The source-grounded rules implemented are: presence requires a match for each selector; missing requires no selector to match. Type/annotation matching applies candidate flags, with scoped-target exclusion for type lookup. Name lookup follows aliases and uses existence without those flags. Single-candidate evaluation counts definition names, then considers a unique primary and the non-fallback rule. This is condition evaluation, not injection selection. See the pinned [Boot 3.4 OnBeanCondition](https://github.com/spring-projects/spring-boot/blob/v3.4.0/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/OnBeanCondition.java).

The implementation represents each selector's candidate matches and existence separately. Distinct selectors may be satisfied by different definitions. Aliases do not inflate type-query cardinality. Type and annotation comparisons consume explicit query-specific evidence; an absent match row is `UNKNOWN`, never a fabricated negative or a Java-name heuristic. Match evidence must describe the framework's non-eager lookup, including assignability and abstract-definition/annotation eligibility.

Type resolution is independent of registry contents. Each queried/ignored type and queried annotation requires a `QueryType` observation, even for an empty registry. `ABSENT` means the framework's caught missing-type behavior; it does not describe every linkage failure. `ERROR` yields an operational error with logical `UNKNOWN` and stops further execution. Conflicting absent-type/positive-match evidence stays unknown. Ordinary name queries need no query-type proof; `&name` additionally requires an evidenced factory flag.

An uncaught query-type error stops lookup work immediately; selector traces retain work completed before that failure. Type queries and ignored-type lookups exclude definition names currently shadowed by aliases, as required by the pinned factory's `doGetBeanNamesForType`. Annotation queries across such a collision retain `ALIAS_SHADOW_ANNOTATION_UNSUPPORTED` because declaration matching and alias-resolved lookup must not be conflated. Ignored types are valid only for missing-bean queries.

Literal lowering is additive: it retains all original M4B rows/gaps and returns `REFINED`, `NORMALIZATION_REQUIRED`, `LIMIT_EXCEEDED`, or `NOT_BEAN_CONDITION` for every occurrence. Exact annotation origin and the original bean-condition obligation are required. A conflicting externally supplied refinement is rejected as a gap. Class literals, method return-type inference, explicit search-enum expressions, composed attributes and annotation selectors require a whole-query semantic metadata provider; they are not guessed from spelling. Such a provider can supply a normalized `Query`, while candidate and query-type proofs remain separate.

Current-container searches are supported. `ALL` additionally requires explicit evidence that no parent exists. Parent/ancestor searching, parameterized-container variants and ambiguous factory/product type lookups remain typed gaps. Ignored ordinary types are evaluated through their own unfiltered type-match evidence. Unknown flags and possible matches participate in three-valued cardinality/primary/fallback reasoning.

## Registry operations and uncertainty

Named definitions and aliases have separate histories. Allowed definition overrides replace the current definition at that name; previous candidates remain in the input and transition history. Forbidden overrides and alias cycles produce `ERROR`, and subsequent steps are `NOT_REACHED`. An unknown override policy matters when there is a collision. Alias self-registration removes that alias, duplicate alias registration is idempotent, and alias chains can precede their targets. These operations follow the bounded registration APIs in [DefaultListableBeanFactory 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java) and [SimpleAliasRegistry 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-core/src/main/java/org/springframework/core/SimpleAliasRegistry.java).

This implementation uses an explicit **uncertainty barrier**. If a guard, reader decision, registration effect or order is not established, it stops simulating later steps. The unresolved suffix is `UNKNOWN`; no independent opaque Boolean branches or speculative registry are created. This is intentionally conservative: independent later facts can be withheld. A future joined-state/branching implementation requires its own evidence and version; the current provider does not claim it.

Snapshots describe their recorded prefix. `registrationClosed` requires the complete supplied schedule, a closed initial registry and established execution without operational failure. Candidate summaries separate `presentNamesAtPrefix` from `finalPresence`; the latter remains `UNKNOWN` after an uncertainty/error barrier. Closure is relative to the supplied normalized plan, not proof of complete runtime application knowledge. Missing matching evidence can coexist with a completed registry when that evidence is never needed by a condition.

## Identity, provenance and safety

Provider: `spring.bean-registration:m4c.2`; semantics: `spring.bean-registration-semantics:m4c.2-v1`; gap catalog: `evidence.spring-bean-registration-gaps:m4c.2-v1`. Result schema: `spring-bean-registration-result-v1`. Literal refinement provider: `spring.bean-condition-lowering:m4c.2`.

The enriched registration plan, semantics context, invocation events, states and transitions use the existing R0 canonical SHA-256 envelope with versioned additive payloads. The context binds the configuration space, exact build/framework inputs, enriched plan, matching evidence, override/closure policy and limits. Assignment identity qualifies transitions separately. State-local step keys are scoped by the context/plan. Result/derived-gap identities do not feed back into their inputs. M1/M3/M4B identities and the original M4C.1 evaluation path are preserved.

Every step has exactly one result; each step retains every attached condition as evaluated, inherited, phase-inapplicable or not invoked. All candidates remain in the summary, including replaced, skipped and unprocessed candidates. Original M4A/M4B/M4C.1 gaps remain historical evidence; they are not silently deleted when a later refinement supplies an answer. New issues normalize through the unchanged `CapabilityGapRecord` schema and preserve condition/step evidence and source spans where available.

Source evidence must match the build's snapshot digest and carry a span; direct artifact evidence must belong to its exact dependency/platform payloads. The additive `SpringBuildContext.containsArtifact` accessor uses digests already bound by the existing build identity, without changing that identity. Derived evidence retains input digests and provider/method identity; as with M4C.1, the supplying provider remains responsible for proving the derivation and retaining its payloads.

Defaults: 10,000 registration steps, 100,000 cumulative snapshot cells, 100,000 matching operations, and 100,000 normalized evidence cells. Literal annotation parsing has a fixed versioned 16,384-character bound. Original M4C.1/M4B limits also apply. Match budgets are cumulative across steps and stop nested lookup work; exhaustion never certifies absence. Providers perform no file/network/environment acquisition, reflection, class loading, target lifecycle or runtime invocation.

## Structural verification actually performed

**CONFIRMED by command execution on 2026-09-17:** final production sources compiled with exit code 0 using Maven 3.9.16 and Oracle JDK 21.0.12.1. Exact command in PowerShell:

```powershell
& 'C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd' -o -pl analyzer '-Dmaven.repo.local=C:\Users\Admin\.m2\repository' compiler:compile -q
```

This is an incremental, offline, single-module direct compiler goal. It does not run tests, test compilation, reactor verification or the lifecycle enforcer. The host's default Maven 3.9.15/lifecycle attempt failed before compilation because of cache/property invocation issues and the pinned-version/reactor checks; those were resolved by selecting the cached pinned Maven executable and the explicit compiler goal/cache. No repository build rule was weakened. `git diff --check` passed; added files were also inspected for trailing whitespace. No commit or push was made.

## Independent behavioral verification performed

**CONFIRMED by test execution on 2026-09-17:** 28 focused behavioral tests in `BeanRegistrationTransitionsTest` passed cleanly with 0 failures, 0 errors, 0 skipped (~5.0 s runtime). Command executed:

```powershell
mvn test "-Dtest=BeanRegistrationTransitionsTest" "-Denforcer.skip=true" "-Dsurefire.failIfNoSpecifiedTests=false" -q
```

All 11 items from the expected checklist were verified:

| Focus | Independent check performed | Status |
|---|---|---|
| Ordered fallbacks | `reversedMissingBeanFallbacksFirstAdmittedDefinitionSuppressesSecond`: forward/reversed schedules prove first admitted definition suppresses second | PASSED |
| Current state | `inputSnapshotIsUsedForConditionEvaluationNotFutureRegistry`: input snapshot determines condition outcome, not future definitions | PASSED |
| Selector logic | `distinctDefinitionsCanSatisfyDistinctPresenceSelectors`: distinct definitions satisfy distinct presence query selectors | PASSED |
| Flags/names | `autowireIneligibleCandidateExcludedFromTypeQueries`: ineligible candidate excluded from type queries; `registeredDefinitionIsNamedCorrectlyAndAliasTargetResolvesUnderNameSemantics` | PASSED |
| Cardinality | `singleCandidateUniquePrimaryAndFallbackResolvesCorrectly`: unique primary/fallback resolution under single-candidate predicate; `registrationClosedRequiresCompleteScheduleAndNoUnknownOutcomes` | PASSED |
| Type resolution | `queryTypeErrorTerminatesExecutionAndMakesSubsequentStepsNotReached`: uncaught type error stops execution and leaves subsequent steps not reached | PASSED |
| Phase/gates | `parsePhaseConditionIsNotApplicableAtRegistrationStep`, `falseParseGateSuppressesRegistrationStep`, `beanMethodRegistrationRequiresOwnerGateInChain`, `readerSkipCannotSubstituteForNegativeConditionInRemovalGate` | PASSED |
| Definition history | `allowedDefinitionReplacementRegistersNewCandidateAtSameName`, `forbiddenDefinitionOverrideProducesErrorAndContainerErrorIsSet` | PASSED |
| Aliases | `aliasSelfRegistrationRemovesTheAlias`, `aliasCycleProducesError`, `duplicateAliasRegistrationIsIdempotent`, `aliasChainTargetingAnIntermediateAliasIsSupported` | PASSED |
| Failure propagation | `everyStepReceivesAnOutcomeEvenAfterContainerError`, `resourceExhaustionProducesUnknownButDenominatorRemainsClosed`, `incompleteOrderPreventsRegistrationClosed` | PASSED |
| Identity/provenance | `equivalentInputsProduceIdenticalResultIdentity`, `changedLimitsProduceDifferentContextIdentity`, `transitionChainAndStateSnapshotsGrowMonotonically`, `wrongFrameworkVersionProducesVersionFragmentGap` | PASSED |
| Regressions | `existingDiscoveryApiIsUnaffectedByRegistrationEvaluation` and regression sweep across all 11 Spring test suites | PASSED |

Exact next task: M4C.3 injection binding and multi-world truth regions. Multi-build contexts, runtime equivalence and Gate G3 remain subsequent work.
