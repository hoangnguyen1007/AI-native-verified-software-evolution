# AI-Native Verified Software Evolution
## Research Redirection V2 — Implementation-Aligned, 2026–2028
### Conditional Architecture Assurance for Framework-Managed Software Evolution

> **REVIEW STATUS — 2026-09-09:** This file is preserved as a research/design input, not the canonical decision. Its core conditional-architecture direction was accepted with material corrections. Generic fixpoint semantics, the unsupported 3–15-variable assumption, broad Lombok/Spring Data synthesis, the ArchUnit/Spring Modulith characterization, and unvalidated numeric/novelty claims are superseded by [ADR-004](docs/decisions/ADR-004-staged-conditional-architecture-semantics.md), the [canonical semantics contract](docs/architecture/conditional-architecture-semantics.md), and the [evidence review](docs/research/2026-09-09-conditional-architecture-redirection-review.md).

**Baseline date:** 2026-09-08  
**Current implementation baseline:** M1 + M2 + M3.1–M3.7 delivered; M3.8 active; M4 not started.  
**Purpose:** update the previous research-redirection proposal after reconciling it against the actual project design/implementation and a deeper 2026 literature + Spring Boot 4.1 research pass.

---

# 0. Executive decision

Do **not** restart the project.

The current codebase is much closer to the new research direction than the old high-level proposal suggested. The correct move is:

> **preserve M1–M3.8 almost entirely, pivot M4 before implementation, reinterpret M5–M6 around conditional architecture facts, make Track B a conditional architecture-delta study, and postpone AI/refactoring until the deterministic research result exists.**

The revised intellectual identity is:

> **How can we reconstruct, compare, and assure software architecture when framework-managed dependencies are conditional, staged, partially unknown, and different across valid Spring Boot environments?**

The system should no longer assume that a Spring application has exactly one architecture graph.

Instead, it should represent:

> **which architectural facts hold, under what conditions, at which framework-resolution stage, supported by what evidence, and how those facts change across a PR.**

---

# 1. How much of the new direction is already covered?

These percentages are **engineering estimates**, not measured scientific results. They are based on a weighted capability mapping between the current design documents and the previous research-redirection proposal.

## 1.1 Overall overlap

| View | Estimated coverage |
|---|---:|
| Existing architectural/foundation concepts needed by the new direction | **~88%** |
| Existing docs vs the complete previous redirection proposal | **~62–65%** |
| Existing docs vs the *new signature research novelty* | **~20–25%** |
| Existing implementation M1–M3.7 reusable under V2 | **~95–98%** |
| Existing implementation likely needing destructive rewrite | **~2–5%** |
| Future roadmap/research framing that should change | **~35–45%** |

The apparent contradiction is important:

- the **foundation is already excellent**;
- the **new science is mostly not implemented yet**;
- therefore this is an unusually good time to pivot.

---

## 1.2 Capability mapping

| Research capability | Current state | Coverage |
|---|---|---:|
| Deterministic, evidence-first analysis | deeply embedded | 100% |
| Explicit uncertainty / ambiguity / conditional outcomes | implemented contracts | 100% |
| Content-addressed identities and reproducibility | implemented | 100% |
| Parser/storage-neutral semantic contracts | implemented | 100% |
| Exact module/build/classpath/platform context | M3 strong | 90–95% |
| Progressive evidence acquisition | M3.6–M3.7 implemented | 90% |
| Spring producers/candidates/injection points/conditions | M4 designed, not implemented | 65–75% design |
| Configuration-aware path feasibility | already in M4 design | ~55% conceptual |
| Symbolic configuration-space semantics | not present | 10–15% |
| Presence conditions over architectural facts | not present | ~10% |
| MUST/MAY/NEVER conformance | not present | ~5% |
| Counterexample/witness configuration generation | absent | 0% |
| Staged `OnBean` / `MissingBean` semantics | partially anticipated | 15–25% |
| Selective runtime evidence escalation | architecture supports it | ~50% design |
| Generic snapshot evolution | Track B designed | 50–60% |
| **conditional** architecture delta | absent | ~10% |
| Change-localized assurance obligations | absent | ~5–10% |
| Ground-truth / benchmark discipline | already strong | 80–90% |
| Practical architecture workbench | strongly specified | 80–90% |

---

# 2. What must **not** be thrown away

The strongest feature of the current system is not JavaParser or Neo4j. It is the epistemic architecture already implemented.

Keep these as protected assets:

1. `RepositorySnapshot`, `AnalysisIdentity`, configuration identity, exact provenance.
2. parser-neutral and storage-neutral domain contracts.
3. resolved/candidate/unresolved relationship targets.
4. semantic status, derivation and uncertainty as separate dimensions.
5. exact source evidence and deterministic serialization.
6. M3 safe workspace/build-model intelligence.
7. exact classpath/platform evidence.
8. capability gaps, acquisition requirements, attempts and provider conflicts.
9. provider ladder based on the least invasive sufficient evidence.
10. future runtime evidence as an additive observation rather than rewriting old facts.
11. Track A / Track B separation.
12. policy findings traceable to graph/path/source evidence.

These are exactly the kinds of foundations a serious assurance-oriented research system needs.

---

# 3. Does V2 break M3.7?

## Short answer

> **No. M3.7 should be preserved.**

Estimated implementation reuse: **95–98%**.

M3.7 is not sunk cost. It becomes *more valuable* because exact build/classpath/platform evidence is required to interpret Spring conditions correctly.

Examples:

- `@ConditionalOnClass` needs authoritative classpath evidence.
- auto-configuration discovery depends on exact dependency artifacts.
- Java-version conditions depend on exact platform identity.
- dependency/auto-configuration differences must not be confused with application configuration differences.

## 3.1 M3.8 should continue

Do **not** stop M3.8.

Exact dependency JAR acquisition is needed before M4 can make credible claims about:

- class conditions,
- external annotation metadata,
- auto-configuration,
- assignability,
- actual Spring dependencies.

So the current order remains:

```text
finish M3.8
-> pass G2
-> pivot M4 semantics
```

## 3.2 Only one subtle change is required later

M3 currently computes a deterministic result for an **explicit analysis context** and preserves undecided profile/environment activation as gaps/qualifiers.

That remains valid.

But the research layer must stop interpreting one explicit M3 configuration as:

> “the architecture of the repository.”

Instead:

> M3 provides **one exact build-analysis context**.

The new M4 layer derives a **configuration-space model inside that build context**.

### Important scoping decision

For the CORE research scope:

- **fix the Maven classpath/build context**;
- vary Spring profiles/properties and supported Spring condition dimensions;
- treat Maven-profile-dependent classpath variation as a *different Analysis Context*.

This avoids exploding the problem into “all Maven × all Spring × all runtime environments”.

Later PEAK work may lift build-profile variability too.

---

# 4. Why the previous redirection proposal still needed another correction

The previous redirection proposed:

> “put presence conditions on graph edges, use SAT/SMT/BDD, classify violations MUST/MAY/NEVER.”

That was a good direction but still too simplistic for Spring.

A real Spring Boot context is not just a set of independent Boolean feature flags.

Spring Boot 4.1 conditions include:

- profiles and profile expressions;
- properties and `matchIfMissing`;
- classpath presence;
- resources;
- web application type;
- Java/runtime properties;
- bean presence / absence;
- single-candidate checks;
- parent context search strategies;
- auto-configuration ordering;
- user bean override/back-off;
- custom `Condition`;
- SpEL;
- programmatic registration;
- AOT-time condition evaluation.

Spring's own documentation explicitly warns that `@ConditionalOnBean` and `@ConditionalOnMissingBean` are evaluated against **bean definitions processed so far**, so ordering matters.

Therefore a flat propositional formula is not enough.

---

# 5. New signature contribution: **Staged Conditional Architecture Semantics**

This is the most important V2 change.

## 5.1 The core research object is not a graph database

Do not make Neo4j or “Software Knowledge Graph” the semantic truth.

Define a **storage-neutral conditional fact model**.

Graph storage becomes one projection.

### Proposed logical form

```text
ConditionalArchitectureFact {
    subject
    relation
    target
    activationSemantics
    evidence
    derivation
    semanticStatus
    analysisContext
    frameworkVersion
}
```

`activationSemantics` is not necessarily just one Boolean formula.

---

## 5.2 Stratify Spring conditions into four semantic classes

### Class A — Build-context constants

Known from M3:

- classpath membership;
- target Java platform;
- dependency artifacts;
- module/source-set;
- resource existence if authoritative and bounded.

Example:

```text
HAS_CLASS(javax.sql.DataSource) = TRUE
```

within one M3 analysis context.

---

### Class B — Exogenous configuration predicates

Can be reasoned about symbolically:

- active profiles;
- profile expressions;
- `@ConditionalOnProperty`;
- finite property domains;
- selected environment dimensions;
- explicit web-application mode where modeled.

These are candidates for SAT/BDD/constraint reasoning.

Example:

```text
prod && payments.legacy=true
```

---

### Class C — Endogenous staged container predicates

Depend on what Spring has registered **so far**:

- `@ConditionalOnBean`;
- `@ConditionalOnMissingBean`;
- `@ConditionalOnSingleCandidate`;
- user-bean back-off;
- auto-configuration ordering.

These require staged/fixpoint-style reasoning, not naïve independent Boolean variables.

Conceptually:

```text
S0 = user-defined bean-definition state

AutoConfig_1:
    evaluate conditions against S0
    produce S1

AutoConfig_2:
    evaluate conditions against S1
    produce S2
...
```

Architectural truth may depend on:

```text
(configuration, build context, registration stage)
```

---

### Class D — Opaque / dynamic predicates

Examples:

- arbitrary custom `Condition`;
- complex SpEL;
- programmatic bean registration;
- `BeanDefinitionRegistryPostProcessor`;
- dynamic factories;
- runtime proxy/AOP behavior where source evidence is insufficient.

Do **not** fake symbolic support.

Represent them as:

```text
UNKNOWN / DYNAMIC
+ explicit evidence requirement
```

Then selectively escalate to controlled runtime evidence.

---

## 5.3 [AI ASSISTANT PROPOSAL / ĐỀ XUẤT BỔ SUNG TỪ AI ASSISTANT] Real-World Enterprise Code Coverage: Lombok & Spring Data Synthesizers

To achieve the platform mission of analyzing real-world enterprise Java codebases without blind spots, M4 must resolve two massive implicit conventions used in >90% of Spring Boot repositories:

### 5.3.1 Lombok Synthetic Constructor Injection
In modern Spring Boot, field injection (`@Autowired`) is discouraged; enterprise code overwhelmingly uses constructor injection via Lombok:
```java
@Service
@RequiredArgsConstructor // Lombok generates constructor at compile time
public class OrderService {
    private final OrderRepository orderRepository; // Implicit injection point!
    private final PaymentClient paymentClient;     // Implicit injection point!
    private String runtimeCacheKey;                // Non-final: excluded from constructor
}
```
* **The Parser Trap:** `JavaParser` reads raw source AST where no constructor exists. Standard AST walkers report zero injection points, rendering the bean completely disconnected from its dependencies.
* **The Delombok Trap:** Running external `delombok` disrupts original UTF-16 source spans, breaks content-addressed caching, and introduces external build tool dependencies.
* **The Proposed Solution — Synthetic Injection Synthesizer:**
  During M4 AST inspection of `ClassOrInterfaceDeclaration`:
  1. Detect `@RequiredArgsConstructor` or `@AllArgsConstructor`.
  2. Filter fields:
     - For `@RequiredArgsConstructor`: pick `private final` fields without explicit initializers.
     - For `@AllArgsConstructor`: pick all non-static fields.
  3. Emit a `SyntheticInjectionPointRecord` for each field:
     - `requestedType`: exact resolved field type.
     - `injectionSite`: method parameter index mapping.
     - `sourceSpan`: original UTF-16 span of the `@RequiredArgsConstructor` annotation line.
     - `derivation`: `SYNTHETIC_LOMBOK_CONSTRUCTOR`.

### 5.3.2 Spring Data Dynamic Proxy Synthesizer
Spring Data repositories define interfaces without any concrete class implementation in source code:
```java
public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByCustomerId(Long customerId);
}
```
* **The Parser Trap:** A naive symbol solver searches for classes implementing `OrderRepository` and reports `UNRESOLVED_BEAN_CANDIDATE`.
* **The Proposed Solution — Framework-Synthesized Bean Producer:**
  During M4 type inspection:
  1. Check if interface directly or transitively extends `org.springframework.data.repository.Repository` (or `CrudRepository`, `JpaRepository`).
  2. Automatically register a `FrameworkSynthesizedProducer`:
     - `beanName`: uncapitalized interface name (e.g. `orderRepository`).
     - `exposedTypes`: [interface type, repository hierarchy].
     - `domainEntity`: extracted from the first generic type argument (e.g. `Order`).
     - `origin`: `SPRING_DATA_PROXY`.
  3. Any injection point requesting `OrderRepository` successfully resolves to this synthesized producer, and captures the architectural link to the domain Entity.

---

# 6. Three-valued semantic evaluation

A condition evaluator should be allowed to return:

```text
TRUE
FALSE
UNKNOWN
```

rather than inventing a value.

Let:

- `B` = exact M3 build context;
- `x` = supported exogenous Spring configuration;
- `S_i` = bean-definition state at registration stage `i`;
- `c` = a Spring condition.

Then:

```text
Eval(c, B, x, S_i) ∈ {TRUE, FALSE, UNKNOWN}
```

A candidate architectural fact inherits this state.

This naturally matches the project's existing evidence-first philosophy.

---

# 7. New key research problem: **configuration-hidden architecture drift**

Generic architecture diff is already researched.

The interesting case is narrower:

> A PR changes architectural conformance only in a configuration region that the default/test configuration does not activate.

Example:

```text
before:
Controller -> Repository
presence: FALSE

after:
Controller -> Repository
presence: prod && payments.legacy
```

Default tests:

```text
dev
payments.legacy=false
```

Everything looks safe.

Production:

```text
prod
payments.legacy=true
```

architecture violation appears.

This is the practical “killer” use case.

---

# 8. Counterexample-carrying architecture findings

A finding should not just say:

```text
Possible layer violation.
```

It should return:

```text
Classification: MAY
Rule: Controller must not depend on Repository

Activation region:
    profile=prod
    AND payments.legacy=true

Witness:
    spring.profiles.active=prod
    payments.legacy=true

Evidence path:
    OrderController
      -> legacyRepository injection point
      -> LegacyOrderRepository

Condition path:
    LegacyRepositoryConfiguration
      @Profile("prod")
      @ConditionalOnProperty(name="payments.legacy", havingValue="true")
```

This is much more useful than a graph edge with `status=CONDITIONAL`.

It also creates a clean CI artifact.

---

# 9. MUST / MAY / NEVER / UNKNOWN architecture semantics

For supported symbolic conditions:

### MUST

Violation exists in every feasible modeled configuration.

```text
K => V
```

### MAY

At least one valid configuration violates and at least one does not.

```text
SAT(K && V)
SAT(K && !V)
```

### NEVER

No modeled valid configuration violates.

```text
UNSAT(K && V)
```

### UNKNOWN

The decision depends on an unsupported/dynamic condition or missing evidence.

This fourth category is essential.

Do not collapse UNKNOWN into MAY.

---

# 10. The truly interesting novelty is not SAT

Do not claim novelty for:

- SAT solving;
- BDD;
- presence conditions;
- family-based analysis;
- feature models;
- configuration sampling.

Those are mature ideas.

The research hypothesis is narrower:

> **Framework-managed architecture needs a staged conditional semantics because some architectural facts are controlled by exogenous configuration while others emerge from ordered bean-definition state.**

Potential claim, only after evaluation:

> A single-world or flat-propositional model systematically misclassifies a measurable class of Spring architecture facts/violations; staged semantics produces more accurate and actionable classifications.

That is a falsifiable contribution.

---

## 10.1 [AI ASSISTANT PROPOSAL / ĐỀ XUẤT BỔ SUNG TỪ AI ASSISTANT] Theoretical Foundations: Variational Call Graphs & Anti-Overengineering Principle for Solvers

### 10.1.1 Academic Provenance (Not an AI Invention)
The 4-valued classification (MUST, MAY, NEVER, UNKNOWN) is grounded in over 30 years of established formal methods and compiler theory:
1. **Compiler Optimization & Alias Analysis:** Industrial compilers like **LLVM** categorize memory aliases into `NoAlias` (NEVER), `MayAlias` (MAY), and `MustAlias` (MUST).
2. **Modal Transition Systems (MTS):** Introduced by Larsen et al. (1988), dividing state transitions into required (*must*) and permitted (*may*).
3. **Variational Static Analysis & Software Product Lines (SPL):** Pioneered by Kästner, Apel, and Thüm, extracting variational call graphs across multi-dimensional feature spaces without combinatorial explosion.

Applying this formal foundation to Spring Boot transforms an ad-hoc static checker into a mathematically rigorous **Variational Architecture Assurance Platform**.

### 10.1.2 The Anti-Overengineering Principle for Solvers
* **The SMT Trap:** A common overengineering instinct is to integrate heavy external solvers like Microsoft Z3 or CVC5 via JNI. This introduces native platform dependencies, breaks Docker/cross-platform hermeticity, and inflates analysis overhead.
* **The Reality of Spring Configuration:** Unlike millions of `#ifdef` combinations in the Linux kernel, typical enterprise Spring Boot repositories have **3 to 15 discrete configuration variables** (profiles + condition properties) per module.
* **The Recommended Lightweight Architecture:**
  Implement a deterministic, zero-dependency, pure-Java **Bounded Boolean Evaluator**:
  - Represent configuration spaces using disjoint set operations (`Set<String> requiredProfiles`, `Set<String> excludedProfiles`, `Map<String, String> propertyValues`).
  - Compute satisfiability, witnesses, and MUST/MAY/NEVER verdicts in $<1\text{ ms}$ in-memory.
  - SMT/BDD remains a theoretical baseline or future PEAK extension, not a prerequisite for SE121 success.

---

# 11. Practical configuration-space mining

Traditional SPL analysis often assumes an explicit feature model.

Typical enterprise Spring repositories do not have one.

A useful subsystem is therefore:

> **derive a bounded analysis configuration model from framework artifacts.**

Sources:

- `@Profile` expressions;
- `application*.yaml/properties`;
- profile groups;
- `spring.config.activate.on-profile`;
- `@ConditionalOnProperty`;
- condition metadata;
- relevant typed `@ConfigurationProperties`;
- explicit user analysis constraints.

## Do not infer arbitrary domains

For a property:

```text
@ConditionalOnProperty(
    name="payments.provider",
    havingValue="stripe"
)
```

a useful finite abstraction can contain:

```text
MISSING
"stripe"
OTHER
```

For boolean conditions:

```text
MISSING
true
false
```

Every abstraction must state exactly what distinctions it preserves.

---

# 12. Selective runtime evidence — use the architecture you already built

The current capability-gap/evidence-acquisition design is almost perfect for this.

When Class D or unresolved Class C semantics block a decision:

```text
conditional fact
-> capability gap
-> RUNTIME_OBSERVATION requirement
-> selected witness config
-> controlled Spring context
-> ConditionEvaluationReport / bean-definition observation
-> additive evidence
```

Runtime evidence must **not** rewrite a static fact into source truth.

It becomes another provider observation.

## Runtime source candidates

- `ApplicationContext`;
- `ConditionEvaluationReport`;
- Actuator `conditions` endpoint when explicitly available;
- `ApplicationContextRunner` for bounded auto-configuration experiments;
- AOT processing evidence in a later scope.

Spring Boot documentation itself recommends `ApplicationContextRunner` for testing different auto-configuration combinations.

---

# 13. Research question: selective evidence acquisition

A useful secondary contribution:

> Can architecture-directed witness selection resolve uncertainty with fewer context executions than generic configuration sampling?

Baselines:

- default config only;
- random valid configs;
- pairwise/t-wise;
- all configurations for small fixtures;
- proposed architecture-relevant witness selection.

Metrics:

- uncertainty resolved;
- architecture facts adjudicated;
- violations adjudicated;
- contexts launched;
- wall-clock cost.

Do not make this the main novelty unless results are strong; configuration sampling itself is mature.

---

# 14. Track B becomes **Conditional Architecture Delta**

Current Track B already plans entity/relationship/Spring-binding/policy deltas.

Keep that.

Add semantics:

```text
Fact before: φ0
Fact after:  φ1
```

Affected configuration region:

```text
A = φ0 XOR φ1
```

For staged facts, `A` is derived from the supported staged model rather than pure propositional syntax.

Output:

```text
ADDED_FOR_ALL
REMOVED_FOR_ALL
ADDED_IN_REGION
REMOVED_IN_REGION
REGION_EXPANDED
REGION_NARROWED
UNKNOWN_CHANGE
```

This is more meaningful than simple added/removed edges.

---

# 15. Stronger practical product: PR Architecture Assurance

The best end-user demo is not:

> “look at this graph.”

It is:

```text
PR #123 Architecture Assurance

2 architecture facts changed
1 configuration-hidden violation introduced

Affected configurations:
  prod && payments.legacy=true

Reproduce:
  SPRING_PROFILES_ACTIVE=prod
  PAYMENTS_LEGACY=true

Rule:
  web -> repository forbidden

Evidence:
  Controller.java:...
  LegacyRepositoryConfig.java:...

Confidence:
  static symbolic evidence sufficient

Suggested next evidence:
  none
```

Or:

```text
Decision: UNKNOWN

Reason:
  Custom Condition com.acme.RegionCondition

Next permitted evidence:
  controlled Spring context

Affected output:
  module boundary rule
```

This is academically interesting and immediately useful.

---

# 16. KLTN: condition-aware evolution contracts

The previous proposal's fixed:

```text
build
-> tests
-> coverage
-> mutation
-> security
-> architecture
```

remains useful as evidence collectors.

But the research model becomes:

```text
change
-> conditional architecture delta
-> affected configuration region
-> affected artifacts
-> obligations
-> required evidence
-> decision
```

Example:

```text
CHANGE_CONDITIONAL_BEAN
```

may require:

- activation region matches expected delta;
- no new ambiguous binding in affected region;
- no new architecture violation;
- fallback/default behavior preserved;
- selected witness contexts pass.

This is more targeted and cheaper than indiscriminately running every heavy gate.

---

# 17. Incremental assurance: practical, but not a novelty claim by itself

2026 work already studies lifted assurance cases and assurance regression for evolving product lines.

Therefore do not claim:

> “we invented continuous assurance.”

Instead use the project's provenance graph to build an application:

```text
change
-> invalidates facts
-> invalidates findings
-> invalidates evidence claims
-> selectively re-run providers/checks
```

The potentially novel part remains:

> **which claims are invalidated under which Spring configuration region.**

---

# 18. Revised milestone plan

## M3.8 — unchanged

Finish dependency artifact acquisition and G2.

---

## M4A — Spring Mechanism Ground Truth

Keep most of current M4:

- producers;
- bean candidates;
- injection points;
- qualifiers;
- primary/fallback;
- framework entry points;
- closed mechanism taxonomy;
- capability gaps.

Add versioned condition taxonomy.

### [AI ASSISTANT PROPOSAL / ĐỀ XUẤT BỔ SUNG TỪ AI ASSISTANT] M4A Enterprise Synthesizers:
- **`LombokSyntheticSynthesizer`:** Detect `@RequiredArgsConstructor` / `@AllArgsConstructor` on classes and synthesize explicit `SyntheticInjectionPointRecord` for `final` uninitialized fields. Ensures zero missing injection points in modern enterprise codebases.
- **`SpringDataProxySynthesizer`:** Detect interfaces extending `Repository`/`CrudRepository`/`JpaRepository` and synthesize `FrameworkSynthesizedProducer` with entity-binding to resolve repository injection sites without requiring concrete implementing classes.

---

## M4B — Conditional Semantics IR

New storage-neutral contracts:

```text
ConditionIdentity
ConditionKind
ActivationPredicate
EvaluationDependency
ConfigurationVariable
ConfigurationDomain
ConfigurationConstraint
ConditionEvaluation
```

Do not leak SAT solver types into the domain.

---

## M4C — Staged Spring Resolution

Implement first for a bounded set:

CORE:

- `@Profile`;
- profile expressions;
- `@ConditionalOnProperty`;
- `@ConditionalOnClass` as build-context constant;
- user bean definitions;
- `@ConditionalOnBean`;
- `@ConditionalOnMissingBean`;
- `@ConditionalOnSingleCandidate`;
- explicit auto-config ordering subset.

Keep custom/SpEL/programmatic registration dynamic.

---

## M4D — Witness and MUST/MAY/NEVER

For policy-relevant paths:

- derive feasibility;
- classify;
- generate witness;
- retain UNKNOWN.

### New G3

G3 should not merely say:

> “Spring inference works.”

It should require:

1. mechanism accounting;
2. candidate/binding precision;
3. condition semantic correctness;
4. MUST/MAY/NEVER correctness;
5. witness validity;
6. honest UNKNOWN behavior.

---

## M5 — Canonical graph as projection

Change the design assumption:

Old:

```text
graph = semantic core
```

New:

```text
conditional facts = semantic core
graph = deterministic query/storage projection
```

Graph edges may carry:

```text
conditionIdentity
classification
evidence refs
```

but policy semantics cannot depend on Neo4j-specific behavior.

---

## M6 — Conditional Policy Engine

Rules evaluate over conditional facts.

Outputs:

```text
MUST violation
MAY violation + witness
NEVER / clean within modeled space
UNKNOWN + missing evidence
```

Do not feed MAY and UNKNOWN indiscriminately into the same architecture score.

Suggested health behavior:

- MUST = definite penalty;
- MAY = separate conditional risk;
- UNKNOWN = reduces analysis confidence, not architecture health;
- NEVER = no penalty.

This preserves the existing “health != confidence” principle.

---

## M7/M8 — Practical product changes

Add:

- configuration-space summary;
- filter architecture by witness/config;
- “why active?” path;
- MUST/MAY/UNKNOWN finding filters;
- one-click witness export;
- side-by-side architecture under two configurations;
- affected configuration region view.

Avoid building a huge feature-model editor.

---

## M9 — Evaluation

Add configuration-focused fixture families and real-project studies.

---

## M11 — Conditional evolution

Replace plain edge diff as the research core with:

- condition-region changes;
- hidden violations;
- witness generation;
- config/analyzer drift separation.

---

# 19. SpringConditionArchBench V2

Do not build a huge random benchmark.

Build a **semantics-focused benchmark**.

## Tier 1 — exact microfixtures

Each fixture isolates one semantic question.

Examples:

```text
profile-and
profile-or
profile-negation
property-missing
property-match-if-missing
class-present
class-absent
user-bean-backs-off-autoconfig
on-bean-before-after
missing-bean-before-after
single-candidate-primary
parent-context-search
autoconfig-order
composed-condition
custom-condition-unknown
```

Each has explicit ground truth.

Target: **40–80 high-quality microfixtures**, not 500 shallow cases.

---

## Tier 2 — interaction fixtures

Combine 2–4 mechanisms:

```text
profile + property
property + missing-bean
class + auto-config order
primary + single-candidate
profile + architecture layer rule
```

These are important because bugs often come from interactions.

---

## Tier 3 — real Spring projects

Start with 5–8 curated projects.

Selection criteria:

- reproducible pinned commit;
- Maven first;
- real profiles/properties;
- nontrivial auto-configuration;
- not just toy demo;
- manageable runtime.

PetClinic is useful as a pipeline checkpoint but may not contain enough condition complexity to be the only evaluation repository.

---

## Tier 4 — historical PR/commit cases

Mine commits that change:

- `@Profile`;
- `@ConditionalOn*`;
- configuration properties;
- auto-configuration imports/order;
- bean declarations;
- package/module boundaries.

Manual labeling can establish whether the architecture region changed.

This is the strongest Track B evaluation.

---

# 20. Controlled mutation operators

Keep current generic mutations but add:

```text
PROFILE_GUARD_BROADEN
PROFILE_GUARD_NARROW
PROPERTY_GUARD_REMOVE
MATCH_IF_MISSING_FLIP
AUTO_CONFIG_BACKOFF_REMOVED
ON_BEAN_ORDER_DRIFT
MISSING_BEAN_ORDER_DRIFT
SINGLE_CANDIDATE_AMBIGUITY
CONFIG_HIDDEN_LAYER_BYPASS
CONFIG_HIDDEN_CYCLE
CONFIG_HIDDEN_MODULE_LEAK
```

Each mutation must declare expected configuration region.

---

# 21. Research questions V2

## RQ1 — SE121 primary

> How often and in what ways does single-configuration Spring architecture analysis misclassify framework-managed dependencies and architecture violations?

This first establishes that the problem is real.

---

## RQ2 — SE121 primary

> Does staged conditional architecture semantics improve fact/violation classification compared with single-world analysis and flat configuration-oblivious baselines?

This evaluates the actual contribution.

---

## RQ3 — SE121 / Track B

> Can counterexample-carrying findings reproduce configuration-hidden architecture violations accurately and with practical analysis cost?

This connects science to usability.

---

## RQ4 — Track B

> How frequently do real Spring changes alter architectural conformance only within a subset of modeled configurations?

This can produce genuinely new empirical knowledge.

---

## RQ5 — SE122/KLTN

> Does condition-aware change-localized assurance reduce inconsistent AI-assisted changes or unnecessary verification work compared with a fixed verification pipeline?

---

# 22. Baselines

## For Spring semantics

1. Java source dependency graph.
2. existing JavaParser semantic graph without Spring.
3. current-config Spring analyzer.
4. Spring Modulith / ArchUnit where applicable.
5. Jasmine-style Spring static analysis if artifact integration is practical.
6. proposed staged conditional model.

Do not fake a baseline if no reproducible artifact exists.

---

## For configuration execution

1. default config;
2. random;
3. pairwise/t-wise;
4. exhaustive on small fixtures;
5. proposed architecture-directed witnesses.

---

## For Track B

1. raw graph diff;
2. Spring binding diff under one fixed config;
3. conditional architecture delta.

---

# 23. Metrics

## Semantic

- candidate precision/recall;
- selected-binding precision;
- condition evaluation correctness;
- MUST/MAY/NEVER/UNKNOWN classification accuracy;
- witness validity;
- false-certainty rate.

### New metric worth emphasizing

**False Certainty Rate**

```text
# facts classified definite but contradicted by ground truth
------------------------------------------------------------
# facts classified definite
```

This aligns perfectly with the platform mission.

---

## Architecture

- violation precision/recall;
- hidden-violation recall;
- false unconditional warning rate;
- evidence-path correctness.

---

## Evolution

- affected configuration region precision/recall;
- hidden-drift detection;
- changed-fact classification;
- historical-case reproduction.

---

## Cost

- condition solving time;
- context launches;
- total runtime;
- peak memory;
- incremental/delta analysis cost.

---

# 24. Research claim hierarchy

Do not overclaim.

### Safe claim level A

> “The tool models a bounded subset of Spring configuration semantics.”

### Level B

> “It correctly classifies supported conditional architecture facts on benchmark X.”

### Level C

> “It identifies configuration-hidden architecture violations missed by baseline Y.”

### Level D

> “Across curated real-world projects/commits, such hidden drift occurs with measurable frequency.”

Only Level D creates strong empirical knowledge about practice.

---

# 25. Core / Strong / Peak scope

## CORE — should be finishable

- fixed M3 build/classpath context;
- profiles;
- profile expressions;
- finite property predicates;
- classpath conditions as constants;
- core bean presence/missing/single-candidate semantics;
- MUST/MAY/NEVER/UNKNOWN;
- witnesses;
- architecture rules;
- benchmark;
- 3–5 real repos.

No AI.

---

## STRONG

Add:

- auto-config ordering;
- profile groups/config activation documents;
- selective runtime context;
- Spring Modulith architecture intents;
- Track B conditional delta;
- 5–8 real repos;
- real commits.

---

## PEAK

Add:

- AOT/build-time condition comparison;
- configuration-aware evidence invalidation;
- conditional evolution contracts;
- JPA/migration cross-artifact obligations;
- AI planning grounded in conditional delta;
- larger empirical study.

---

# 26. Why AOT is a valuable PEAK direction

Spring Boot 4.1 AOT prepares the `BeanFactory` at build time and evaluates conditions during AOT processing.

Therefore:

> build-time configuration can freeze decisions that ordinary JVM runtime execution would otherwise make later.

This enables a strong future question:

> Do architecture facts differ between ordinary JVM bootstrap and AOT-prepared configurations, and can the analyzer explain the divergence?

This is modern, practical and directly tied to Spring 4.x.

Do not make it CORE.

---

# 27. Version-aware semantics

Spring Boot is now 4.1.x, while much of the ecosystem remains 3.5.x.

The condition engine must be versioned.

Examples:

```text
spring-condition-semantics:3.5
spring-condition-semantics:4.1
```

Do not assume qualifier/fallback/condition behavior is timeless.

Benchmark at least:

- one supported 3.5.x line;
- one supported 4.1.x line

if schedule permits.

---

# 28. Practical application beyond thesis

Potential product users:

### A. PR reviewers

Detect conditional architecture regressions before merge.

### B. platform/architecture teams

Enforce:

```text
module boundaries
layer constraints
dependency rules
```

across environment-specific Spring wiring.

### C. framework/starter maintainers

Test auto-configuration behavior across property/classpath/user-bean combinations.

### D. modernization teams

Compare architecture before/after:

- Spring Boot upgrade;
- module extraction;
- starter changes;
- Java upgrade.

### E. AI coding agents

Use analyzer output as an authoritative guardrail:

```text
not “read more context”
but “these are the modeled architectural constraints and witnesses”
```

This remains valuable even if 2028 models can read the entire repository.

---

# 29. What to delete/demote from the previous research proposal

## Delete as “core novelty”

- generic Graph-RAG;
- PPR;
- model routing;
- multi-agent;
- MCP;
- generic evidence bundle;
- generic bounded repair loop;
- generic Software Knowledge Graph;
- fixed verification pipeline as novelty.

## Keep as implementation option

- Neo4j;
- JavaParser;
- OpenRewrite;
- Docker;
- CI;
- PIT/JaCoCo;
- LLM;
- LangGraph if genuinely needed later.

---

# 30. What changes in the current design documents

## `AGENTS.md`

Very small change.

Add a research north-star note:

> M4+ must distinguish single-context facts from configuration-space claims; no conditional fact may be promoted to universal truth without proof/evidence.

Estimated change: **<5%**.

---

## `architecture.md`

Add:

```text
Conditional Semantics Service
Configuration-Space Model
Witness Service
Conditional Policy Evaluation
```

and clarify:

> graph is a projection of canonical facts.

Estimated conceptual change: **10–15%**.

---

## `m1-contracts.md`

Do not rewrite existing identities.

Add later versioned concepts:

```text
ConfigurationSpaceIdentity
ConditionIdentity
```

Existing `ConfigurationIdentity` remains one realized configuration.

Estimated destructive change: **0%**.

---

## `m2-semantic-frontend.md`

No meaningful redesign.

M2 supplies Java facts.

Estimated destructive change: **0%**.

---

## `m3-workspace-build-model.md`

M3.1–M3.8 stay.

Clarify:

> M3 Analysis Context is one exact build/classpath/platform world; symbolic Spring variability belongs to M4.

Optional later addition:

> preserve build-profile activation predicates for PEAK cross-build-context analysis.

Estimated existing-code change: **0–3%**.

---

## `evidence-acquisition.md`

Excellent fit.

Add candidate requirement kinds:

```text
CONFIGURATION_WITNESS
SPRING_CONTEXT_OBSERVATION
AOT_CONDITION_REPORT
```

No redesign.

Estimated change: **5–10% additive**.

---

## `m4-spring-intelligence.md`

This is where the pivot happens.

Current taxonomy is retained.

Change the resolution model from:

```text
evaluate candidates under explicit context
```

to:

```text
support explicit-context evaluation
PLUS
derive bounded conditional semantics across supported config space
```

Add staged bean-state evaluation and UNKNOWN.

Estimated design change: **50–65%**.

Because M4 is not implemented, this is cheap now.

---

## `knowledge-graph.md`

Keep schema hypothesis status.

Change:

> `ConfigurationCondition` node is not sufficient semantic truth.

Graph projection must reference canonical `ConditionIdentity` / activation semantics.

Estimated design change: **30–40%**.

No code loss because M5 not implemented.

---

## `roadmap.md`

Main changes:

- M4 split into condition semantics sub-slices;
- M6 conditional policy;
- M11 conditional architecture delta;
- Track C becomes AOT / selective runtime / advanced assurance.

Estimated roadmap text change: **25–35%**.

---

## `product-outcome.md`

Existing product remains.

Add:

- MUST/MAY/UNKNOWN;
- witness;
- configuration filter;
- affected config region.

Estimated change: **10–15%**.

---

# 31. Implementation-risk table

| Risk | Why it matters | Control |
|---|---|---|
| configuration explosion | too many worlds | finite abstraction + solver |
| incorrect Spring semantics | worse than UNKNOWN | versioned taxonomy + fixtures |
| OnBean order complexity | not pure Boolean | staged semantics |
| custom conditions | arbitrary code | UNKNOWN + evidence gap |
| runtime context expensive | CI cost | selective witnesses |
| benchmark author bias | inflated results | independent labels / hold-out |
| PetClinic too simple | weak evaluation | multiple condition-rich repos |
| “novelty by integration” | weak paper | test explicit hypotheses |
| solver leakage into domain | architecture debt | solver-neutral condition IR |
| overclaim “verified” | scientifically unsafe | modeled-obligation wording |

---

# 32. Kill criteria

The project must be allowed to discover that the research hypothesis is weak.

## Kill A

If real repositories contain almost no relevant conditional architecture variation:

> narrow contribution to framework-condition correctness/benchmark, or pivot domain.

## Kill B

If single-config analysis is already nearly equivalent:

> do not build full configuration-space machinery.

## Kill C

If staged semantics does not improve over flat supported conditions:

> report negative result; simplify.

## Kill D

If runtime witness selection provides no cost benefit:

> retain runtime validation only as oracle.

This prevents months of engineering from being used to protect a bad hypothesis.

---

# 33. 2026 evidence supporting this pivot

## Spring framework reality

Spring Boot 4.1.1 documentation confirms:

- auto-configuration depends on classpath and user-defined beans;
- auto-config can back away when user beans exist;
- `@ConditionalOnBean`/`MissingBean` are sensitive to definitions processed so far;
- profiles/profile groups/config documents alter effective configuration;
- AOT evaluates conditions at build time;
- `ConditionEvaluationReport` exposes condition outcomes.

This means “one repository = one architecture graph” is an unsafe abstraction for nontrivial Spring applications.

---

## Research landscape

### Jasmine — ASE 2022
Static framework-aware call graph support for Spring DI/AOP already exists.

**Therefore:** Spring-aware static analysis alone is not novelty.

### [AI ASSISTANT PROPOSAL / ĐỀ XUẤT BỔ SUNG TỪ AI ASSISTANT] Spring Modulith 1.4 GA & ArchUnit 1.3 — Industry State of the Art 2026
In 2025–2026, the industrial standard for modular architecture verification in enterprise Java is **Spring Modulith** backed by **ArchUnit**.
* **How it works:** Automated build tests execute `ApplicationModules.of(Application.class).verify()` to enforce module DAGs and package encapsulation.
* **The Critical Blind Spot:** Modulith and ArchUnit verify architecture *at test-runtime* inside a single active test `ApplicationContext`. They are fundamentally blind to dependencies, bean bindings, or layer violations that only activate under alternative deployment profiles or production feature flags (`Configuration-Hidden Drift`).
* **Therefore:** Static, variational architecture verification across Spring's *entire latent configuration space* directly solves the blind spot of the current industry standard.

### SSAR — ICSE 2026
Modern software architecture recovery is already an active strong research area.

**Therefore:** generic architecture recovery is not novelty.

### Beyond Lexical — SANER 2026
Semantic architecture recovery continues to improve.

**Therefore:** semantic graph recovery alone is not novelty.

### CausalRepair — ISSTA 2026
Causal/static+dynamic context selection for LLM repair is already active.

**Therefore:** Graph-RAG/context selection is a poor signature contribution.

### Hydra — FSE 2026
Dependency-aware repository context is already state of the art.

**Therefore:** “use structure instead of text chunks” is not enough.

### RepoProbe — ASE 2026
Architecture-aware repository comprehension already has dedicated evaluation.

**Therefore:** architecture comprehension alone is becoming benchmarked/commoditized.

### Requirements-driven variability — IST 2026
Presence conditions, variability consistency and minimal configuration sets are active research.

**Therefore:** witnesses/minimal configuration sets are not inherently novel.

### Evolving SPL assurance cases — Formal Aspects of Computing 2026
Variability-aware assurance regression already exists.

**Therefore:** continuous/lifted assurance is not novelty by itself.

The safe gap is the **framework-specific staged conditional architecture semantics + empirical hidden-drift problem**.

---

# 34. 2027 signals

These are **calls for research**, not future results.

## ICSE 2027

Research areas explicitly include:

- trustworthy AI for SE;
- software architecture modeling/analysis/recovery;
- dependency and complexity analysis;
- architecture refactoring;
- evolution/program differencing;
- variability and product lines.

This validates the intersection, but does not prove novelty.

## SANER 2027

Strong emphasis on:

- software analysis/evolution/reengineering;
- rigorous AI4SE;
- reliability/cost/reproducibility;
- open science;
- agent governance.

## AGENT 2027

The call explicitly states that many current agents are assembled from:

```text
prompts + tools + memory + orchestration + MCP + ad-hoc evaluation
```

without systematic:

```text
requirements + architecture + verification + oversight
```

This is a strong reason not to optimize the project around “more agent”.

## SEAMS 2027

Explicit topics include:

- uncertainty;
- partial knowledge;
- runtime models;
- variability;
- testing/verification/runtime assurance;
- adaptive safety cases;
- continuous assurance;
- evolution/self-evolution.

This supports the long-term assurance direction.

---

# 35. 2028 forecast — explicitly not fact

## Likely commoditized

- long-context repository reading;
- basic RAG;
- agent orchestration;
- MCP tool calls;
- model routing;
- generic code repair.

## Likely more valuable

- machine-checkable architecture intent;
- authoritative framework semantics;
- exact provenance;
- counterexamples;
- environment/configuration reasoning;
- selective verification;
- change-impact on guarantees;
- assurance evidence.

The proposal should therefore invest in the latter.

---

# 36. Recommended research identity

## Full umbrella

# **AI-Native Verified Software Evolution**
### **Staged Conditional Architecture Semantics and Evidence-Driven Assurance for Framework-Managed Systems**

## SE121 research subtitle

### **Configuration-Hidden Architecture Drift in Spring Boot: Staged Conditional Semantics, Counterexample-Carrying Findings, and Empirical Evaluation**

This is sharper than:

> “Software Architecture Intelligence Platform.”

The platform can keep its product name while the research has a precise problem.

---

# 36.1 [AI ASSISTANT PROPOSAL / ĐỀ XUẤT BỔ SUNG TỪ AI ASSISTANT] The Neurosymbolic Evolution Bridge: Grounding AI Optimization (ĐA1 -> ĐA2 -> KLTN)

This section directly reconciles the deterministic platform with the department-assigned research title:
> **"Nghiên cứu phương pháp tối ưu hóa kiến trúc phần mềm dựa vào AI" (AI-Driven Software Architecture Optimization)**

In classic Software Engineering and Mathematical Optimization, an **Optimization Problem** requires four components:
1. **State Space Representation ($\mathcal{S}$):** The Software Knowledge Graph (SKG) and Variational Call Graph.
2. **Objective / Fitness Function ($f(x)$):** The Architecture Conformance & Health Scoring Engine (measuring cyclic dependencies, layer violations, coupling metrics $Ca, Ce$, instability $I$).
3. **Optimization Search / Planning Algorithm:** The Graph-Grounded AI Reasoning Agent (LLM).
4. **Transformation Operators & Verification ($\mathcal{T}$):** OpenRewrite recipes + Multi-tier Sandbox Verification.

### How the Three-Stage Roadmap Realizes "Architecture Optimization":
* **Đồ án 1 (Current - SE121): The Objective Function & State Space Foundation**
  - AI is intentionally NOT a dependency of ĐA1 (as officially stated in Section 7.1 of the proposal).
  - ĐA1 constructs the ground-truth state space ($\mathcal{S}$) and the exact fitness function ($f(x)$). You cannot optimize what you cannot measure deterministically.
  - Features like *What-if Simulation* (e.g., evaluating how removing an illegal Controller $\to$ Repository edge improves the Health Score from 65 to 82) serve as the formal evaluation baseline for optimization.
* **Đồ án 2 (SE122): AI-Assisted Optimization Planning**
  - When ĐA1 detects an architecture violation (e.g., a MAY-violation under `prod`), the graph extracts the 2-hop affected neighborhood.
  - The AI Agent receives the *Witness Configuration* and *minimal subgraph* (Graph-Guided Context Pruning) to propose an optimal refactoring strategy (e.g., introducing a mediator Service, splitting interfaces, or decoupling modules).
  - Graph grounding eliminates hallucination and token bloat.
* **Khóa luận Tốt nghiệp (KLTN): Closed-Loop Verified Optimization Execution**
  - Executes OpenRewrite transformation recipes derived from AI plans.
  - Evaluates Before vs. After optimization metrics:
    - Violations: $N \to 0$
    - Health Score: $S_{\text{initial}} \to S_{\text{optimized}}$
    - Test regressions: $0\%$ (guaranteed by differential testing sandbox).

---

# 37. One-sentence thesis

> **A Spring Boot repository does not have one architecture: it has framework-resolved architectural facts whose truth depends on build context, environment configuration, bean-definition state and dynamic evidence; this work makes those conditions explicit so architecture violations and evolution can be classified, reproduced and assured rather than guessed.**

---

# 38. The strongest demo

1. Analyze a real project.
2. Default/dev configuration appears clean.
3. Tool reports:

```text
MAY architecture violation
```

4. It generates the exact witness configuration.
5. Launch witness Spring context.
6. Runtime evidence confirms the bean topology.
7. Show a PR where the violation was introduced only in this region.
8. Compare with ArchUnit/current-config baseline that misses or overstates it.
9. Show exact source + condition + evidence chain.

That is:

- understandable to a lecturer;
- technically difficult;
- research-evaluable;
- useful to industry;
- visually impressive;
- not dependent on an LLM demo.

---

# 39. Immediate action plan

## Now

**Do not change M3.7.**

Finish M3.8 and G2.

## Before M4 code

Run a dedicated **M4 Research Gate R4-0**:

1. literature lock for Spring/static analysis/SPL variability;
2. select supported Spring condition fragment;
3. define staged semantics;
4. define ground-truth fixture protocol;
5. define exact baselines;
6. review whether the hypothesis remains defensible.

## Then

Implement:

```text
M4A taxonomy
-> M4B condition IR
-> M4C staged semantics
-> M4D witnesses
-> M4E benchmark
```

Do not implement Neo4j/dashboard/AI first.

---

# 40. Primary sources used in this V2 pass

## Spring official documentation

- Spring Boot 4.1.1 — Auto-configuration  
  https://docs.spring.io/spring-boot/reference/using/auto-configuration.html

- Spring Boot — Creating Your Own Auto-configuration / condition semantics  
  https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html

- Spring Boot — Profiles  
  https://docs.spring.io/spring-boot/reference/features/profiles.html

- Spring Boot — Properties and Configuration  
  https://docs.spring.io/spring-boot/how-to/properties-and-configuration.html

- Spring Boot 4.1.1 — Ahead-of-Time Processing  
  https://docs.spring.io/spring-boot/gradle-plugin/aot.html

- Spring Boot — `ConditionalOnBean` API  
  https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnBean.html

- Spring Boot — condition package / `ConditionEvaluationReport`  
  https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/package-summary.html

- Spring Modulith — Verifying Application Module Structure  
  https://docs.spring.io/spring-modulith/reference/verification.html

## Research

- Jasmine: A Static Analysis Framework for Spring Core Technologies, ASE 2022  
  https://doi.org/10.1145/3551349.3556910

- SSAR: A Novel Software Architecture Recovery Approach Enhancing Accuracy and Scalability, ICSE 2026  
  https://conf.researchr.org/details/icse-2026/icse-2026-research-track/221/

- Beyond Lexical: Functional Semantics and Fusion for Precise Architecture Recovery, SANER 2026  
  https://doi.org/10.1109/SANER67736.2026.00123

- CausalRepair, ISSTA 2026  
  https://conf.researchr.org/details/issta-2026/issta-2026-research-papers/134/

- Do Not Treat Code as Natural Language (Hydra), FSE 2026  
  https://arxiv.org/abs/2602.11671

- RepoProbe: Benchmarking Architecture-Aware Repository Comprehension with Checklists, ASE 2026  
  https://arxiv.org/abs/2608.04783

- Requirements-driven analysis of variability in configurable software, Information and Software Technology 2026  
  https://doi.org/10.1016/j.infsof.2026.108017

- Analysing Self-Adaptive Systems as Software Product Lines, Journal of Systems and Software 2025  
  https://doi.org/10.1016/j.jss.2024.112324

- Change Impact Analysis for Maintenance and Evolution of Variable Software Systems, Automated Software Engineering  
  https://doi.org/10.1007/s10515-019-00253-7

- Assurance Case Development for Evolving Software Product Lines: A Formal Approach, Formal Aspects of Computing 2026  
  https://doi.org/10.1145/3796233

## 2027 research-direction signals

- ICSE 2027 Research Track  
  https://conf.researchr.org/track/icse-2027/icse-2027-research-track

- SANER 2027 Research Track  
  https://conf.researchr.org/track/saner-2027/saner-2027-papers

- SANER 2027 Agentic AI4SE  
  https://conf.researchr.org/track/saner-2027/saner-2027-agentic-ai4se-track

- AGENT 2027 @ ICSE  
  https://conf.researchr.org/home/icse-2027/agent-2027

- SEAMS 2027 Research Track  
  https://conf.researchr.org/track/seams-2027/seams-2027-research-track

---

# 41. Final decision

The project should **not** become bigger.

It should become **sharper**.

The best 2026–2028 version is not:

```text
more graph
+ more AI
+ more agents
+ more verification tools
```

It is:

```text
exact repository/build evidence
+ staged Spring conditional semantics
+ explicit uncertainty
+ architecture truth regions
+ counterexample configurations
+ conditional evolution delta
+ selective assurance evidence
```

The current M1–M3.7 implementation is not in the way of this direction.

It is the reason this direction is feasible.
