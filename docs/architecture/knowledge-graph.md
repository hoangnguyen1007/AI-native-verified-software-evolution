# Knowledge Graph Schema

> **Epistemic status: HYPOTHESIS**
>
> This entire schema is a starting proposal. It requires validation
> via the R2 experiment (tracing 3 core architecture rules through the
> schema against a real Spring Boot project).
>
> Nothing here should be treated as decided until R2 completes.

The parser-neutral identity, source-evidence, semantic-status, uncertainty, derivation, manifest, metric-envelope, and assessment-status contracts are no longer hypotheses in this document. Their implemented authority is [M1 Semantic, Identity, Uncertainty, and Provenance Contracts](m1-contracts.md). The Spring producer/candidate/injection-point/condition concepts are a [provisional M4 baseline](m4-spring-intelligence.md); their exact graph node/edge shape and the Neo4j mapping remain hypotheses.

## Purpose

The Software Knowledge Graph represents the evidence-backed semantic structure of a
Java/Spring Boot repository as a labeled property graph. Source semantics are the current baseline, not the exclusive future evidence source. It must support:

1. Architecture rule evaluation
2. Dependency analysis
3. Impact analysis
4. Evidence-backed violation reporting
5. Source traceability
6. Versioned structural metrics and bounded visualization projections
7. Evidence-provider provenance, conflicts, and unresolved capability gaps without treating missing evidence as absence

**[CONFIRMED]** — from AGENTS.md.

## Graph Database

**Neo4j Community** [PROVISIONAL — requires R2 validation]

Alternatives if Neo4j proves inadequate:
- In-memory graph model (for development/testing)
- JanusGraph (if distributed scale needed — unlikely for SE121)

## Evidence, Status, and Gap Classification

Every graph statement keeps three orthogonal dimensions rather than collapsing them into one “evidence type”:

| Dimension | Values/examples | Meaning |
|---|---|---|
| Derivation | `DIRECT`, `DERIVED`, `INFERRED` | How the statement was produced |
| Semantic status | `RESOLVED`, `PARTIAL`, `UNRESOLVED`, `AMBIGUOUS`, `CONDITIONAL`, `UNSUPPORTED`, `ERROR` | What the provider established |
| Provenance/gap | Observation, input and provider references; optional capability-gap reference | Which evidence supports the statement and what evidence is still missing |

There is no `UNKNOWN` derivation kind in the implemented M1 contract. Insufficient evidence remains an explicit semantic/provenance state and, where a stable reason/evidence need exists, a [capability gap](evidence-acquisition.md). Do not silently convert `INFERRED` to `DIRECT`, an unresolved candidate to an absent edge, or a runtime observation to source-direct evidence.

## Node Types [HYPOTHESIS EXCEPT WHERE NOTED]

### Package

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String | e.g. `com.example.service` |
| `sourceFile` | String | Directory path |

**Identity:** `qualifiedName`

### Class

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String | e.g. `com.example.service.UserService` |
| `simpleName` | String | e.g. `UserService` |
| `sourceFile` | String | File path |
| `startLine` | Integer | Start line in source |
| `endLine` | Integer | End line in source |
| `isAbstract` | Boolean | |
| `isInterface` | Boolean | |

**Identity:** `qualifiedName`

Represents classes, interfaces, enums, records, and annotation types.

### Method

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String | e.g. `com.example.service.UserService.findById` |
| `signature` | String | Method signature including parameter types |
| `returnType` | String | Return type |
| `sourceFile` | String | File path |
| `startLine` | Integer | Start line |
| `endLine` | Integer | End line |
| `visibility` | String | public/protected/private/package |

**Identity:** `qualifiedName` + `signature`

### Field

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String | e.g. `com.example.service.UserService.userRepository` |
| `type` | String | Field type |
| `sourceFile` | String | File path |
| `startLine` | Integer | Start line |
| `endLine` | Integer | End line |
| `visibility` | String | public/protected/private/package |

**Identity:** `qualifiedName`

### Annotation

| Property | Type | Description |
|---|---|---|
| `qualifiedName` | String | e.g. `org.springframework.stereotype.Service` |
| `sourceFile` | String | Where used |
| `startLine` | Integer | Start line |

**Identity:** composite (annotation type + annotated element)

### Spring semantic concepts [PROVISIONAL]

The earlier flat `SpringBean --INJECTS--> SpringBean` sketch is superseded. A stereotype class, bean producer, bean-definition candidate, injection point and runtime bean are not interchangeable identities.

| Concept | Key graph responsibility |
|---|---|
| `BeanProducer` | Source/configuration/artifact/runtime mechanism that can produce a bean definition |
| `BeanDefinitionCandidate` | Context-specific candidate with names, exposed types, scope, conditions and producer provenance; not proof of runtime instantiation |
| `InjectionPoint` | Exact constructor/field/method/`@Bean` parameter or other registered injection site with requested type/qualifier evidence and source span where available |
| `ConfigurationCondition` | Profile/property/classpath/bean/expression predicate with evaluation status and missing inputs |

Exact canonical identities and node-versus-record mapping remain M4/M5 decisions. See [M4 Spring Intelligence and Closed Mechanism Taxonomy](m4-spring-intelligence.md).

### ConfigProperty

| Property | Type | Description |
|---|---|---|
| `key` | String | Property key (e.g. `spring.datasource.url`) |
| `sourceFile` | String | Properties/YAML file |
| `startLine` | Integer | Start line |

**Identity:** `key`

## Relationship Types [HYPOTHESIS]

| Relationship | Source | Target | Evidence | Properties |
|---|---|---|---|---|
| `CONTAINS` | Package | Class / Package | DIRECT | — |
| `DECLARES` | Class | Method / Field | DIRECT | — |
| `EXTENDS` | Class | Class | DIRECT | — |
| `IMPLEMENTS` | Class | Class (interface) | DIRECT | — |
| `CALLS` | Method | Method | DIRECT | `sourceFile`, `line`, `column` |
| `DEPENDS_ON` | Class | Class | DIRECT / DERIVED | `dependencyType` |
| `IMPORTS` | Class | Class | DIRECT | — |
| `ANNOTATED_WITH` | Any | Annotation | DIRECT | `params` (annotation parameters) |
| `PRODUCES_BEAN_CANDIDATE` | BeanProducer | BeanDefinitionCandidate | DIRECT / DERIVED / INFERRED | producer kind, status, evidence references |
| `DECLARES_INJECTION_POINT` | BeanDefinitionCandidate / producer owner | InjectionPoint | DIRECT / DERIVED | injection kind, source evidence |
| `HAS_CONDITION` | Producer / candidate / binding candidate | ConfigurationCondition | DIRECT / DERIVED | activation/evaluation status |
| `INJECTION_CANDIDATE` | InjectionPoint | BeanDefinitionCandidate | DERIVED / INFERRED | compatibility and disambiguation reasoning, semantic status, evidence references |
| `SELECTED_BINDING` | InjectionPoint | BeanDefinitionCandidate | DERIVED / INFERRED | only when selection is justified; conditions and evidence references required |
| `READS_PROPERTY` | InjectionPoint / BeanProducer / BeanDefinitionCandidate | ConfigProperty | DIRECT / DERIVED / INFERRED | expression/condition role and evidence references |
| `THROWS` | Method | Class (exception) | DIRECT | — |
| `RETURNS` | Method | Class | DIRECT | — |
| `HAS_PARAMETER` | Method | Class | DIRECT | `paramName`, `position` |

## Provenance Strategy [HYPOTHESIS]

Each relationship should be traceable to answer:

1. **What** is the source node?
2. **What** is the target node?
3. **Why** does this relationship exist?
4. **Where** in the source code is the evidence?
5. **How** was it discovered? (DIRECT / DERIVED / INFERRED)

## Metric and Product Query Boundary [CONFIRMED DIRECTION]

The canonical graph supports metrics and visualization, but neither metric semantics nor UI behavior belongs to a Neo4j-specific query.

- Structural metrics are versioned domain results derived from canonical graph/query contracts.
- A metric records its scope, formula version, inputs, denominator where applicable, status, analysis identity, and uncertainty.
- Inventory counters are not necessarily graph nodes.
- Architecture score results are assessment-domain outputs, not intrinsic node properties.
- Analysis confidence remains separate from architecture health.
- The workbench requests focused, bounded projections by module, package, configured layer, type neighborhood, cycle, violation path, or impact path.
- The UI must not request the complete repository graph by default.
- Storage adapters must pass the same projection, path, cycle, and metric contract tests.

See [Product Outcome, Metrics, Scoring, and Workbench Contract](product-outcome.md).

## Identity Strategy

Canonical identities follow the implemented [M1 identity contract](m1-contracts.md#stable-identity-contract): repository and typed scope prevent fully qualified names from colliding across modules or dependency origins; entity kind and canonical name/signature distinguish language-level symbols; relationship targets preserve resolved, candidate, or unresolved state; and occurrence identities include complete source evidence.

The exact graph mapping of those canonical identities remains **HYPOTHESIS** until M5. Fully qualified name alone is not a canonical entity identity.

## Schema Evolution

Schema changes require:

1. Schema impact analysis
2. Migration/update strategy
3. Test coverage update
4. Documentation update

**[CONFIRMED]** — from `.agents/skills/se-project-engineering/SKILL.md`.

## Validation Plan (R2 Experiment)

To validate this schema, define 3 core architecture rules and trace the
graph path each rule requires:

1. **Layered violation:** Controller → Repository (skipping Service)
   - Requires: DEPENDS_ON or CALLS between controller and repository classes
   - Graph path: Class(Controller) → CALLS → Method → CALLS → Method(Repository)

2. **Circular dependency:** Package A → Package B → Package A
   - Requires: DEPENDS_ON aggregated at package level
   - Graph path: Package → CONTAINS → Class → DEPENDS_ON → Class → CONTAINS ← Package

3. **Spring bean scope violation:** Singleton candidate selecting Prototype candidate
   - Requires: bean-definition scopes, an exact injection point, candidate evidence and a justified selected binding (or an explicitly conditional finding)
   - Graph path: BeanDefinitionCandidate(singleton) → DECLARES_INJECTION_POINT → InjectionPoint → SELECTED_BINDING → BeanDefinitionCandidate(prototype)

If any rule's graph path cannot be expressed with the current schema,
the schema must be revised before implementation.

## Known Gaps [OPEN QUESTION]

- Generic type parameters: not represented in current schema
- Lambda expressions: method call targets unclear
- Reflection-based dependencies: not generally attributable from source-only static evidence; retain candidate/configuration evidence and an acquisition gap for possible bytecode, configuration, controlled runtime, or other providers
- Conditional beans (`@ConditionalOn*`): source/configuration evidence may be insufficient to select one active binding; retain conditional candidates and the missing evidence rather than emitting a certain selected-binding projection
- AOP advice: source evidence may not expose woven or runtime dependencies; retain the mechanism/gap for possible configuration, bytecode or runtime enrichment
- Provider precedence/conflict semantics and graph representation for build, generated-source, bytecode, sandbox and runtime observations
- Exact graph projections and aggregation identities for large workbench views
- Exact structural metric formulas and score inputs pending M5/M6 validation

## Related Documents

- [Architecture Overview](architecture.md)
- [Project Context](../project-context.md)
- [M1 Contracts](m1-contracts.md)
- [Progressive Evidence Acquisition Contract](evidence-acquisition.md)
- [M4 Spring Intelligence and Closed Mechanism Taxonomy](m4-spring-intelligence.md)
