# ADR-009 — Segmented graph payloads and passive dependency/service recovery

Date: 2026-11-05. **CONFIRMED human authorization:** repair the reported SQLite
growth, Gradle dependency gap and disconnected client/server facts in active
M8.4 Step 6. The choices below are reversible adapter implementations. Their
broader repository performance and completeness remain **PROVISIONAL**.

The reported ThingsBoard scratch database size is 12.19 GB, with a 34.16-GB
workspace. These are human-supplied observations, not measurements repeated by
this implementation. A universal performance cliff at 10 GB or an NTFS-specific
root cause has not been established. SQLite instead documents that
[large rows often suit rowid tables better](https://www.sqlite.org/withoutrowid.html).
The verified defect here is storing large canonical JSON payloads directly in
the key B-tree, including repeated staging copies.

Keep the disposable SQLite index, with `(space,k)` ordering and uniqueness, but
store only SHA-256 payload references in its `payload` column. A second narrow
table maps each unique digest to a compressed/raw byte locator. Append payloads
to private 64-MiB segments, permitting one admitted large row alone. Deduplicate
identical canonical UTF-8 bytes across spaces; use bounded decoded/read-channel
caches, positional reads, bounded decompression and hash verification before
uncached decode. Sample/debit disk allowance with 64 MiB of physical headroom.
That reserve is a worker resource condition, not a repository admission ceiling.
Close statements/channels and remove only the newly owned scratch files on
success, failure or cancellation. Published v1 JSON, graph digests, authenticated
page trees and reference audits retain their existing formats.

Alternatives were a rowid-only conversion (smaller change but retains large,
repeated payloads), one SQLite database per shard (more merge/transaction
ownership without removing payload duplication), and replacing durable graph
storage (unnecessary compatibility risk). The selected change removes payloads
from B-tree rebalancing without changing domain/query semantics. It does not
eliminate the disk cost of the durable JSON snapshot and CAS pages. Scale timing,
peak storage and per-context heap still require actual measurements.

A narrow gap-node index makes publication totals include later HTTP/RPC/schema
contributions. Counts deduplicate gap identities across analysis/root references,
while exposing contextual node counts separately. This is the
`ALL_GRAPH_CAPABILITY_GAP_IDENTITIES` population, including retained history;
CURRENT/HISTORY qualification and selected-context completion stay in domain
queries. The input report count remains available as `inputTotal`.

Gradle scripts remain inert data. Extend the lexical declaration model with
literal map coordinates, catalog plugins, platform/enforced-platform imports and
constraints. Keep settings, script and catalog hashes on dependency records.
`SpringBootPlugin.BOM_COORDINATES` is a versioned recovery derivation requiring
the exact source import, a unique fixed Boot plugin declaration and a matching
captured plugin-marker descriptor. The corresponding BOM's actual POM hierarchy
must be acquired/validated before using its managed versions. The mapping uses
the [Boot plugin API contract](https://docs.spring.io/spring-boot/gradle-plugin/api/java/org/springframework/boot/gradle/plugin/SpringBootPlugin.html),
not evaluation or loading of the target plugin.

The Maven adapter supplies a neutral cache/descriptor reader to the new Gradle
recovery resolver; no project POM or project Maven coordinate is synthesized.
Acquire exact permitted POM/JAR bytes, preserve receipts, main/test scopes,
exported `api` dependencies, optional/exclusion controls and ordered evidence.
Conflicting versions are withheld rather than choosing an invented winner.
Dynamic selectors, malformed artifacts, custom/remapped project contexts and
worker exhaustion stay typed. CLI and local backend composition pass the
available verified binaries to source recovery and preserve acquisition ledgers.
All resulting resolutions have `complete=false` and retain
`GRADLE_EFFECTIVE_CONTEXT_REQUIRED`. This is necessary because
[Gradle metadata/variant selection](https://docs.gradle.org/current/userguide/supported_metadata_formats.html)
and conflict rules differ from a Maven POM traversal. Capturing the actual
effective context/generated output remains an isolated-executor operation.

Extend HTTP candidate intersection to literal single-segment client templates;
retain method/path boundaries, ambiguity, unsupported regex/glob/encoding cases
and original source/analysis contexts. Add separate gRPC source acquisition for
generated-client constructor/alias patterns, Go registrations/receiver methods,
Python Servicer bases, C# generated bases/overrides and JavaScript service maps.
Selective JavaParser acquisition also retains Java `*Grpc.*ImplBase` overrides,
typed stub/factory client calls and original BOM/CRLF/Unicode positions. It reads
captured Java bytes to identify candidates and parses only matching files, one
at a time. Unmapped Java Unicode escapes stay typed; this is not a second full
Java semantic pass or a generated-stub provider.
Comments, strings, parameter shadowing and recovered syntax have negative
controls. Match client/service/method spellings to every compatible Protobuf RPC
descriptor and source handler, preserving namespace ambiguity. Handler candidates
link back to their actual source declaration when the syntax provides one.

The additive `polyglot.grpc-contract.candidate`,
`polyglot.grpc-implements.candidate` and
`polyglot.grpc-service-call.candidate` vocabulary does not supply compiler,
generated-stub, address, activation or runtime evidence. Original contexts and
source hashes/spans remain inspectable through evidence/reference nodes. Missing
schema/handlers and missing routing each produce typed gaps. Qualified native
bridge publication, trust labels and accepted Step-3/Step-5 scopes are unchanged.
No deployment-world federation, production native certificate or milestone gate
is inferred. The canonical graph schema itself is unchanged.

The human follow-up explicitly requests typed architecture links. The additive
`architecture.service-edges:m8.4-v1` projection emits `CALLS_SERVICE` and
`INVOKES_RPC`, using actual caller/handler declarations when supported by Java
or syntax evidence. Original endpoint sites remain the fallback. Cross-context
references preserve context-qualified identity and the graph's same-context edge
rule. Ambiguity, candidate support and unestablished routing/runtime evidence
survive this projection; this is not deployment federation.

`scan --prepare-dependencies` separates passive cache hydration from an offline
semantic scan. It uses the existing exact-coordinate artifact policies and
verification, without JDK/semantic/graph work or target execution. Its receipt
retains unresolved acquisition/model obligations; `PREPARED` is a cache outcome,
never an effective Gradle classpath or semantic completion certificate.

Verification and the delivered JAR are recorded in [current state](../current-state.md).
The subsequent source-recovery repair passes evidenced Gradle project source
sets to the frontend (including a test set's own main sources), without exporting
private transitive implementation projects. In an already incomplete recovery
context, usable owned peer sources no longer require full plugin/generated
closure; exact closed contexts retain the original closure checks. Source bytes,
module identity, compatibility checks and a deterministic recovery order remain
hashed inputs. This does not establish an effective Gradle variant or authorize
target execution. New frontend inference and HTTP constant/method recovery are
adapter-local, compiler-controlled extensions described in the
[passive-ingestion contract](../architecture/m4u1-passive-ingestion.md).
Replace/extend these providers when native contexts contradict a passive
derivation, routing demands more than supported templates, or measured working
sets require another storage/worker partition.
