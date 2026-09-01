# Knowledge Graph Schema

> **Epistemic status: HYPOTHESIS**
>
> This entire schema is a starting proposal. It requires validation
> via the R2 experiment (tracing 3 core architecture rules through the
> schema against a real Spring Boot project).
>
> Nothing here should be treated as decided until R2 completes.

## Purpose

The Software Knowledge Graph represents the semantic structure of a
Java/Spring Boot repository as a labeled property graph. It must support:

1. Architecture rule evaluation
2. Dependency analysis
3. Impact analysis
4. Evidence-backed violation reporting
5. Source traceability
6. Versioned structural metrics and bounded visualization projections

**[CONFIRMED]** — from AGENTS.md.

## Graph Database

**Neo4j Community** [PROVISIONAL — requires R2 validation]

Alternatives if Neo4j proves inadequate:
- In-memory graph model (for development/testing)
- JanusGraph (if distributed scale needed — unlikely for SE121)

## Evidence Classification

Every graph relationship carries an evidence type:

| Type | Meaning | Example |
|---|---|---|
| **DIRECT** | Directly observed from source semantics | `class A extends B` → EXTENDS relationship |
| **DERIVED** | Deterministically derived from known relationships | Transitive dependency |
| **INFERRED** | Produced by heuristic or framework-specific inference | Spring bean wiring from `@Autowired` |
| **UNKNOWN** | Insufficient evidence to classify | Unresolved external dependency |

**[CONFIRMED]** — from `.agents/rules/20-evidence-first.md`.

Do not silently convert INFERRED to DIRECT.

## Node Types [HYPOTHESIS]

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

### SpringBean

| Property | Type | Description |
|---|---|---|
| `beanName` | String | Spring bean name |
| `scope` | String | singleton/prototype/request/session |
| `qualifiedName` | String | Implementing class |
| `stereotype` | String | @Service/@Repository/@Controller/@Component |

**Identity:** `qualifiedName`

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
| `INJECTS` | SpringBean | SpringBean | DERIVED | `injectionType` (constructor/field/setter) |
| `READS_PROPERTY` | SpringBean | ConfigProperty | DERIVED | — |
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

## Identity Strategy [HYPOTHESIS]

- Use fully qualified names as stable identifiers
- Method identity includes signature to distinguish overloads
- Annotation identity is composite (annotation type + annotated element)
- Spring bean identity is the implementing class qualified name

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

3. **Spring bean scope violation:** Singleton injecting Prototype
   - Requires: INJECTS + SpringBean.scope
   - Graph path: SpringBean(singleton) → INJECTS → SpringBean(prototype)

If any rule's graph path cannot be expressed with the current schema,
the schema must be revised before implementation.

## Known Gaps [OPEN QUESTION]

- Generic type parameters: not represented in current schema
- Lambda expressions: method call targets unclear
- Reflection-based dependencies: undetectable by static analysis
- Conditional beans (`@ConditionalOn*`): may produce false INJECTS edges
- AOP advice: may create invisible dependencies
- Exact graph projections and aggregation identities for large workbench views
- Exact structural metric formulas and score inputs pending M5/M6 validation

## Related Documents

- [Architecture Overview](architecture.md)
- [Project Context](../project-context.md)
