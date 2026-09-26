# M4-UNIVERSAL Slice 1: passive ingestion

Status: implemented bounded provider contract, 2026-09-20. The human-approved
[M4-UNIVERSAL direction](m4-universal-engine.md) remains the target. This document
describes actual interfaces and evidence boundaries; it does not certify global
repository coverage, compiler equivalence, complete Spring runtime behavior or G3.

## Composition and identity

The supported sequence is:

1. `RepositoryInputs` binds acquired bytes, decoding evidence and source-document
   identities to a full `RepositorySnapshot` inventory. Missing acquired files do
   not disappear from that inventory.
2. `UniversalBuildIngestion` projects passive Maven, Gradle or conventional Java
   evidence into `UniversalBuildModel`. Existing Maven `BuildModelResult` objects
   remain intact inside this additive envelope. Gradle and plain Java therefore
   need neither a fabricated POM nor fabricated Maven coordinates.
3. `UniversalSourceIngestion` closes source ownership, consumes explicitly supplied
   exact platform/classpath evidence and produces existing `FrontendRequest`
   objects plus per-source-set configuration results. Main/test and module
   boundaries are retained; test configuration includes main then test resources.
4. `UniversalIngestionPipeline` invokes the replaceable `SemanticFrontend`,
   normalizes its gaps, discovers source component candidates, and acquires
   constructor injection sites. Framework evidence is derived from the exact
   manifest artifact entries. Results retain configuration, source rows, frontend
   output, component rows, constructor rows and their upstream gaps together.

Provider identifiers use `m4u.1`; the additive gap catalog is
`evidence.ingestion-gaps:m4u.1-v1`. Identities use canonical serialization and
SHA-256 over input snapshots, document/decoding identities, explicit policies,
ordered resolution inputs and provider versions. Local binary paths are not
semantic identity inputs. Repeated identical inputs must produce identical
identities and gap records. `ANALYZED` means a provider returned an output; inspect
its semantic states and gaps before making a completeness claim.

Input rejection and provider failure are distinct pipeline outcomes. A failed
source set retains its obligation and a typed gap; unrelated sets can continue.
Already acquired frontend/component evidence is retained if later enrichment
fails. Diagnostic codes are retained, while exception messages are not copied
into ingestion issues. Configuration values belong to explicit configuration
results, not diagnostic messages.

## Build and source acquisition

| Provider | Implemented projection | Explicit evidence boundary |
|---|---|---|
| Maven | Delegation to the existing passive Maven provider, including acquired parents, BOMs, dependency management and main/test source plans | Existing Maven acquisition, profile, dependency and generated-output gaps remain authoritative |
| Gradle Groovy/Kotlin | Literal settings includes and project names, project/exact GAV dependency declarations, basic Java plugins, conventional/custom source and resource directories, Java toolchain/source/target/release/encoding settings in recognized blocks | Arbitrary scripts/plugins, interpolation, custom source sets, catalogs, inherited build logic, dynamic selectors and unmodeled expressions produce gaps; no script is executed |
| Plain Java | Conventional `src/main/java`, `src/test/java`, resource directories, with `src` or repository-root fallback | Compiler/encoding/platform policy must be supplied; unusual or overlapping ownership remains explicit |

Lexing separates strings/comments from syntax and bounds characters, tokens,
nesting and build files/projects. `srcDir`/`srcDirs(...)` add roots; replacement
forms replace them. Declared settings retain build-file hashes and an effective
model origin; explicit caller defaults retain policy origin. Bytecode target and
the platform API release are separate. Source/target declarations alone do not
prove the JDK API view. A dependency declaration is never proof of acquired JAR
contents or Gradle conflict/variant resolution.

Every inventoried Java path receives an owned, unowned, overlapping or
unavailable row. Incomplete build closure, decoding/plan disagreement, ambiguous
ownership or absent exact resolution withhold affected frontend requests. The
assembly stage performs no network, filesystem traversal or target build.

## Java and generated members

JavaParser/SymbolSolver is pinned to **3.28.2**, provider
`frontend.javaparser:3.28.2-m4u.1`. Exact non-preview parser-level selection now
extends through Java 26. Controls exercise records/sealed types at 17, 21–26 and
a Java 25 constructor prologue; these are syntax/fixture checks, not general
Java 26 semantic or platform-library certification. Preview and later unknown
levels remain rejected inputs, which the composed pipeline records as gaps.

`TypeDeclarationRecord` adds parser-neutral kind, abstractness and independence
metadata tied to an existing source declaration. Its frontend constructor
overloads preserve existing callers. Component eligibility does not reparse a
class body or infer modifiers by matching arbitrary source strings.

The Lombok provider synthesizes structural declarations for the six requested
annotations: `RequiredArgsConstructor`, `AllArgsConstructor`, `NoArgsConstructor`,
`Data`, `Value`, and default non-generic `Builder`. It models constructor field
order, initialized/static field exclusion, explicit-constructor suppression,
forced no-arg validity, common getters/setters, object methods and default builder
type/factory/build/setter methods. Derived entities have no invented declaration
span; their derivations cite original owner/field/component entities. Derived
parameters/accessors retain source type evidence. Record canonical parameters
now expose `TypeUseRecord` evidence for DI as well.

Synthesis requires exact Lombok **1.18.46** artifact bytes
(`sha256:01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126`).
Unverified artifacts, ancestor `lombok.config`, unresolved nullness/type evidence,
member customization, inner-class constructor complications, generic/existing
builders and unsupported options produce diagnostics/normalized gaps. Uses on
unsupported method/constructor/record targets and other recognized Lombok
generators (such as `SuperBuilder`) are also explicitly diagnosed; known
unsupported constructor transformations suppress an invented default constructor.
Structural member acquisition does not make SymbolSolver resolve all calls to generated
members, acquire generated bytecode, reproduce every generated method body or
close the old M4A generated-member obligations automatically.

## Configuration baseline

`ConfigDataIngestion` uses explicitly selected ordered resource locations. It
reads `application.properties`, `.yml`, `.yaml` and profile variants, flattening
maps and indexed lists; empty lists/null scalar values are retained. Properties
use Boot-compatible ISO-8859-1 decoding and YAML uses BOM-aware Unicode decoding.
Properties continuation/escapes and bounded multi-document separators are
handled separately. YAML uses SnakeYAML **2.6** `SafeConstructor`, rejects duplicate
keys, bounds aliases/depth and never constructs application classes.

The implemented baseline includes location/profile precedence, properties over
YAML, active/include/default profiles, profile groups, `default=none`, scalar/list
profile activation expressions, explicit override properties and bounded
placeholder/default expansion. Profile lists replace lower-priority lists;
sparse/malformed lists cannot create a trusted assignment. Every loaded document
retains its input hash, ordinal, activation result and evidence identity.

Any unresolved obligation withholds `ConfigurationAssignment`, while partial
documents and properties remain inspectable. External imports/locations,
environment-dependent placeholders, cloud/legacy activation, multiple-source
profile-include precedence, ambiguous YAML suffix precedence, unsupported
properties list shortcuts, unsupported YAML shapes/scalars and exhausted limits
remain typed gaps. This is an exact modeled repository baseline with supplied
overrides, not proof of all deployment environments or the full relaxed-binding,
Config Data import and property-source lifecycle.

## Component and constructor acquisition

Roots come from resolved `SpringBootApplication` and `ComponentScan` annotations,
including literal package lists and resolved class-literal package markers.
Direct scan declarations supersede a Boot default on the same owner. Package
matching respects segment boundaries. Component, Service, Repository,
Controller, RestController and Configuration are recognized from verified
framework artifact scopes; project-defined impostors cannot cross that boundary.
Source composed stereotypes use finite graph propagation, including cycles and
shared metadata paths, with incomplete annotation evidence propagated separately.

Every source type gets an included/outside-root/noncomponent/ineligible/unknown
row. Ordinary annotation types, interfaces and non-independent members are not
component candidates. Unknown metadata, multiple bootstrap contexts, custom
filters/naming, abstract lookup-method cases and missing type shape evidence are
qualified. Composed annotation attribute defaults/aliases and unacquired binary
meta-annotations do not yield guessed bean names. Known standard artifact tuples
reuse the existing pin catalog; fresh Spring controls cover Framework **6.2.0** /
Boot **3.4.0**. The additional `spring-web:6.2.0` pin is
`sha256:24bd75b1049104699e4f57f3a70fc2ba41943d667d5d1a3b24ed1b70c3582020`.

`ConstructorInjectionIngestion` binds the exact frontend and discovery outputs
and acquires the unique constructor plus ordered resolved parameter types for
eligible classes/records. It produces existing neutral `InjectionPoint` objects
for a matching `SpringBuildContext`. Overloaded/incomplete constructor sets,
unverified provider/framework evidence and missing parameter types stay unknown.
These are acquisition facts. Activation, parameter qualifiers/names, constructor
overload choice, candidate matching, registration order and runtime creation
continue through the existing M4B/M4C contracts; this slice does not invent them.

## Verification and remaining acceptance

See the [verification package](../reproducibility/m4u1-ingestion-2026-09-20/README.md)
for exact commands, fresh totals, signature/configuration oracle controls,
replay checks and limitations. Trusted test fixtures may compile with the pinned
Lombok processor; analyzed repositories never execute a processor, script,
Maven/Gradle lifecycle or application.

The requested **at least 98% worldwide repository coverage is unmeasured**. No
representative denominator, stratified corpus or repository-level success
measurement was established by these tests. Recognizing a build format, reporting
a gap and completing a repository analysis are different outcomes. M4E/G3 must
evaluate the actual support matrix and unresolved providers before promoting a
coverage claim. Slice 2 was the next task at this checkpoint and is now delivered.
The current sequence is [Universal v2 V2.1–V2.3](../tasks/m4-universal-v2/README.md),
then M4E/G3. This update changes planning, not the historical verification above.

Primary implementation references: [Lombok constructors](https://projectlombok.org/features/constructor),
[Data](https://projectlombok.org/features/Data), [Value](https://projectlombok.org/features/Value),
[Builder](https://projectlombok.org/features/Builder),
[Boot 3.4 Config Data](https://docs.spring.io/spring-boot/3.4/reference/features/external-config.html),
[pinned Boot properties loader](https://github.com/spring-projects/spring-boot/blob/v3.4.0/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/env/OriginTrackedPropertiesLoader.java),
[pinned Spring YAML flattening](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-beans/src/main/java/org/springframework/beans/factory/config/YamlProcessor.java),
and [JavaParser 3.28.2 language levels](https://github.com/javaparser/javaparser/blob/javaparser-parent-3.28.2/javaparser-core/src/main/java/com/github/javaparser/ParserConfiguration.java).
