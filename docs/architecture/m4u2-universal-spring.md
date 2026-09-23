# M4-UNIVERSAL Slice 2 — Spring evidence and symbolic reasoning

**CONFIRMED by implementation:** Slice 2 supplies the four provider lanes in the
[approved M4-UNIVERSAL contract](m4-universal-engine.md). This document records
their executable boundaries, not a replacement or weakening of that contract.
The [verification package](../reproducibility/m4u2-spring-2026-09-23/README.md)
distinguishes tested behavior from the remaining M4E/G3 acceptance obligations.

## Entry points and integration

| Entry point | Input and output |
|---|---|
| `UniversalIngestionPipeline` | Existing exact source-set frontend request, configuration baseline and M4U.1 component/constructor results; each successful unit now also carries `UniversalSpringSemantics.Result` |
| `SpringDataRepositories.synthesize` | Exact source/interface ancestry and scan evidence to repository rows and named candidates; `Result.candidates(build, container)` exports existing M4C `BeanDefinitionCandidate` objects |
| `FrameworkGeneration.from` / `AutoConfigurationMetadata.read` | Exact classpath artifact/version evidence and bounded supplied resource bytes to namespace, override policy and ordered auto-configuration candidates |
| `TruthRegionEvaluation.evaluateSymbolic(model, ...)` | Finite-domain condition model to symbolic T/F/U regions, satisfiability queries and independently interpreted assignment witnesses |
| `TruthRegionEvaluation.evaluateSymbolic(request, ...)` | Existing normalized M4C binding request to SAT truth signatures, actual ordered registration/binding traces and conditional fact regions |
| `WebRouteMapper.map` / `SpringInjectionSites.acquire` | Proven direct annotations to structural routes and injection-site evidence; the unified result connects candidate dependency paths |
| `HierarchicalContexts.visible` / `select` | Normalized registries and whole-descriptor eligibility/primary evidence to visibility and bounded hierarchical selection |

Parser objects remain in the frontend adapter. Production providers are passive
and perform no target builds, target class loading, Spring startup, reflection,
network access or expression execution. The new Maven dependencies are test-only
Spring Data and injection APIs. The SAT backend uses only Java.

## Spring Data and source-to-architecture paths

Repository detection follows resolved source inheritance transitively into
evidenced Spring Data declarations. Common Repository/Crud/Paging/List/reactive
families and the explicitly cataloged JPA, MongoDB, Elasticsearch, Cassandra,
Neo4j and Couchbase families are recognized. Explicit JDBC/R2DBC/Redis enable
annotations can supply store scans for common repository interfaces. Every source
interface receives a row: `CANDIDATE`, `NO_REPOSITORY_BEAN`, `NOT_REPOSITORY`,
`OUTSIDE_SCAN` or `UNKNOWN`. `@NoRepositoryBean` suppresses the annotated interface
without suppressing its concrete source descendants.

Literal `@Enable*Repositories` packages, resolved class markers and nested-type
flags are supported. Boot's auto-configuration package is used for automatic
repository scans; `scanBasePackages` does not redefine that package. Literal
repository-disable properties suppress automatic scans. Unknown auto-config
exclusions, unsupported scan attributes, unresolved external ancestry and strict
multi-store ambiguity remain typed gaps. Custom names preserve annotation
evidence and namespace compatibility. No proxy implementation class is invented.
These rules follow the primary [Spring Data repository definition contract](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html)
and [Boot data-access guidance](https://docs.spring.io/spring-boot/how-to/data-access.html).

The exported bean candidate is structural evidence. Its actual activation still
depends on the evidenced M4C schedule, conditions and factory/descriptor inputs.
The unified source view matches resolved scalar type identities and literal
qualifiers/names against acquired components and repositories. It reports
`UNIQUE_CANDIDATE`, `MULTIPLE_CANDIDATES` or `UNKNOWN`; it does not equate a unique
structural candidate with successful runtime instantiation. Generic assignability,
custom qualifiers and missing type evidence remain descriptor obligations.

Routes carry controller/method identity, path, method, params, headers, media and
source evidence. Direct RequestMapping/Get/Post/Put/Delete/Patch mappings support
literal path cross-products and bounded property substitution. Class/method HTTP
methods combine by union, method media conditions override class media conditions,
and GET records implicit HEAD support. Inherited/ambiguous direct mappings,
recognizable unsupported composed mappings and unsupported path composition retain
explicit rows and gaps. Arbitrary custom meta-annotation expansion remains a
provider obligation. Combination follows
the pinned [Spring 6.2 request-method condition implementation](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.0/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/condition/RequestMethodsRequestCondition.java).

Route → controller → service → repository paths are potential structural
dependencies, not inferred method-call traces or unconditional endpoint activation.
Cycle exclusion, path-count limits and depth 16 bound traversal; truncation emits
a typed resource gap.

## Generation, metadata and hierarchy

The generation policy requires a complete exact classpath, one coherent Framework
version and at most one Boot version. Framework 1–5 selects `javax`; Framework 6
selects `jakarta`. Compatible Boot families are 1 with Framework 3–4, 2 with 5,
and 3 with 6. Unknown or contradictory versions withhold the policy. This is a
version-qualified namespace/metadata policy, not historical runtime certification.
The existing M4C execution semantics remain pinned to Framework 6.2.0 / Boot 3.4.0.
Unified ingestion also retains M4U.1's exact tuple qualification for Framework/Boot
5.3.31/2.7.18, 6.1.14/3.3.5 and 6.2.0/3.4.0. The standalone generation policy accepts
additional explicitly supplied coherent evidence; that does not automatically
qualify those tuples throughout component acquisition or the staged executor.

Plain Framework and Boot before 2.1 default to allowing bean-definition override;
Boot 2.1 onward defaults to denying it. An evidenced explicit Boolean overrides
that default. See the [Boot 2.1 release notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-2.1-Release-Notes).
Boot 1 and Boot 2 before 2.7 consume the EnableAutoConfiguration entry in
`META-INF/spring.factories`; 2.7 consumes that entry plus
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
Boot 3 consumes the imports form. The coexistence policy is confirmed in
[Boot 2.7's selector](https://raw.githubusercontent.com/spring-projects/spring-boot/v2.7.18/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationImportSelector.java).

Repository resource roots are acquired automatically by `UniversalSourceIngestion`.
Library resources enter through supplied, digest-verified `Resource` bytes; this
slice does not add a general JAR-resource acquisition provider. Other `.imports`
registries are not silently promoted to Boot auto-configurations. Parsing retains
resource rows, order, deduplication, continuations/comments, malformed names and
byte/entry limits. Candidate class names are metadata, not proof of registration.

Hierarchy lookup supports CURRENT, ANCESTORS and ALL over normalized completed
registries, including child name/alias shadowing. Missing parents, cycles, duplicate
contexts, invalid aliases, incomplete registries and depth exhaustion withhold
closure. Selection needs supplied whole-descriptor matches; missing or conflicting
proof cannot establish absence. A local primary supersedes inherited primaries;
multiple inherited primaries remain ambiguous even at different ancestor depths,
as specified by [Spring 6.2's primary selection](https://raw.githubusercontent.com/spring-projects/spring-framework/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java).
This API does not infer arbitrary runtime context construction, factory behavior,
custom resolution, priority or fallback flags from incomplete source evidence.

## Symbolic configuration and staged execution

`SatConfigurationReasoner` adds a deterministic DPLL backend to the existing
solver-neutral port. Hash-addressed Boolean DAGs compile to Tseitin CNF with
bounded nodes, clauses, depth and deterministic work steps. An explicit search
stack avoids recursion proportional to the number of variables. Every SAT model
is replayed through the original Boolean DAG. Exhaustion yields `UNKNOWN` and
`SOLVER_LIMIT`, never a fabricated UNSAT result.

`SymbolicTruthRegions` represents each condition with disjoint TRUE and FALSE
formulae; UNKNOWN is their complement. It preserves finite-domain choices,
declared-source choices, last-active-source precedence, MISSING fall-through,
profile defaults/includes/groups, web modes, build evidence and feasibility
constraints. Unknown policy, OTHER partitions and missing provenance cannot prove
an empty universe or a universal fact. Formula nodes and query answers are exported
so region hashes are inspectable. Witness assignments replay through the original
M4B evaluator; an unsuccessful replay withholds classification.

For normalized registration/binding requests, SAT partitions the truth signatures
of all configuration-dependent leaves. A representative of each feasible signature
runs the unchanged M4C ordered registration and injection engine. Bean-state
conditions are evaluated inside that engine, never encoded as independent free
Boolean variables. Definition-presence, injection-candidate and selected-binding
facts are aggregated into T/F/U regions. Traces record actual binding digests,
operational status and replay status. Signature, fact-cell and witness-replay
budgets retain explicit residual unknown regions or unreplayed trace status.

This eliminates Cartesian-world enumeration for the supported symbolic path,
including the tested 56-profile case (more than 2^50 assignments). The legacy
explicit-world `evaluate`/`enumerate` contract remains available and retains its
enumeration limits. Selecting the SAT backend alone does not silently change that
legacy method's contract; callers use `evaluateSymbolic` for symbolic regions.
Witnesses are deterministic satisfying representatives, not promised minimum
baseline changes. Existing exhaustive M4D minimization remains separate.

**OPEN acceptance obligation:** finite Boolean SAT does not imply solving arbitrary
infinite configuration domains or polynomial/millisecond worst-case behavior.
Signature diversity and general SAT can still exhaust deterministic budgets.
No universal latency claim follows from passing bounded controls. The overarching
performance/coverage goals require a registered M4E workload and measurements.

## Basic SpEL and evidence closure

`@ConditionalOnExpression` lowers into an additive `BASIC_SPEL` IR operand and
flows through both M4B concrete evaluation and symbolic compilation. The passive
parser supports Boolean/null/string literals, bounded signed integer arithmetic
and comparison, Boolean operators, parentheses and bounded literal property
placeholders/defaults. The grammar's policy identity is included in the operand.
Decimals, division/modulo, overflow, dynamic property keys, bean references, type
references, calls and code execution remain UNKNOWN with typed gaps. This avoids
inventing Spring coercion or arbitrary expression semantics. Symbolic property
tables are bounded locally to the properties used by one expression atom.

The shared `spring.universal:m4u.2` provider and
`evidence.spring-universal-gaps:m4u.2-v1` catalog preserve snapshot, exact artifact,
source span, derivation, input identity and reason provenance. Upstream gaps are
retained rather than erased by a successful downstream projection. Inputs and
results are SHA-256 addressed; repeatability assertions cover both identities and
unknown outcomes. Deterministic identities do not substitute for empirical accuracy.

**Exact next task:** M4E validation over the closed denominator of supported and
unresolved obligations from both universal slices, with registered baselines and
representative/version evidence before G3 adjudication. Global 98–99% coverage,
all-generation container equivalence and G3 acceptance remain unmeasured/unpassed.
