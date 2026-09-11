# AI-Native Verified Software Evolution

This repository is the SE121 software architecture intelligence platform. The Java 21 Maven reactor contains parser-neutral contracts, the M2 JavaParser frontend, progressive effective-POM/source-plan modeling, bounded filesystem and dependency-artifact acquisition, exact Maven classpaths, deterministic source decoding, explicit analyzed-JDK symbol views, per-source-set frontend-input assembly, and normalized capability-gap/acquisition records. Gate G2 is formally PASSED following independent semantic adjudication across representative archetypes. Gate M4-R0 (Research and Semantics Gate) is PASSED following human review and acceptance. See [current state](docs/current-state.md) for verified scope and limitations.

The active milestone is **Milestone 4: Spring Semantics Enrichment & Truth-Region Intelligence**, executing starting with slice **M4A.1: evidence-only mechanism inventory and CapabilityGapRecord normalization** based on the accepted [M4-R0 semantics gate contract](docs/architecture/m4-r0-semantics-gate.md) and [ADR-004](docs/decisions/ADR-004-staged-conditional-architecture-semantics.md).

## Build prerequisites

- A Java Development Kit (JDK), version 21.
- `JAVA_HOME` set to that JDK. A JRE is not sufficient.
- Network access on the first wrapper/dependency download, or a previously populated Maven cache.
- No system Maven installation is required or supported for the canonical build.

The repository Maven Wrapper pins Maven 3.9.16 and verifies its binary distribution before execution.

## Clean verification

From the repository root on Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --version
.\mvnw.cmd -B -ntp clean verify
```

From the repository root on a POSIX shell:

```sh
export JAVA_HOME=/path/to/jdk-21
sh ./mvnw --version
sh ./mvnw -B -ntp clean verify
```

Do not replace the wrapper commands with `mvn`; the build intentionally enforces the wrapper's exact Maven version.

## Working with multiple JDKs

JDK 17, 21, and 25 can coexist on one machine. This repository must be opened and built with JDK 21; other repositories can select another installed JDK independently.

- Set the project SDK and Maven runner JDK to 21 in the IDE. Do not rely only on the IDE's global default.
- Keep each JDK in its own installation directory; do not uninstall another JDK merely to build this project.
- For a temporary PowerShell session, select JDK 21 with `$env:JAVA_HOME = 'C:\path\to\jdk-21'` and prepend `$env:JAVA_HOME\bin` to that session's `Path`.
- For a temporary POSIX session, use `export JAVA_HOME=/path/to/jdk-21` and `export PATH="$JAVA_HOME/bin:$PATH"`.
- Confirm the selected runtime with the wrapper's `--version` command before `clean verify`.

The Maven Enforcer configuration deliberately rejects a build JVM outside Java 21. This protects project reproducibility without preventing JDK 17 or 25 from being used by other projects.

## Reactor

The root reactor owns all shared build and test policy and builds in this order:

1. `software-evolution-platform` (root aggregator)
2. `analyzer` (M1 contracts, Unicode-safe Java identities and the neutral semantic frontend port)
3. `analyzer-maven` (isolated Maven Model Builder/classpath adapters and bounded exact release-POM/JAR acquisition from explicitly configured credential-free HTTPS repositories; no target lifecycle or ambient discovery)
4. `analyzer-filesystem` (isolated passive repository and configured-JDK acquisition; no link following, execution or network access)
5. `analyzer-javaparser` (isolated JavaParser/SymbolSolver adapter and semantic fixtures)
6. `backend` (placeholder JAR, depends on `analyzer`, and has its own test boundary)

The adapter accepts exact supplied source bytes and verified resolution inputs; it does not discover or execute target builds. Graph, Spring, backend API, CLI and visual workbench implementation remain later work.

The Maven adapter implements `BuildModelProvider` in the neutral `com.evolution.analysis.buildmodel` package. It models root/nested modules, relative parents, supplied artifact parents/BOMs, inheritance, properties, explicit/property/default profiles, dependency management, scopes, optional flags and exclusions. M3.2 adds per-module main/test candidate source plans with inherited directories, separate syntax/bytecode/API requirements, encoding declarations, plugin configuration and provenance. Result schema `build-model-result-v2` includes source-plan gaps in `hasGaps()`.

M3.3 adds neutral immutable acquisition/ownership contracts and the isolated `analyzer-filesystem` adapter. It reads an explicitly selected directory under finite file/directory/byte/depth limits, preserves exact bytes and digests, withholds incomplete snapshots, rejects links/escapes, and records exclusions or failures without raw host paths. Acquired POM bytes feed M3.1/M3.2; `.java` files remain candidates classified as owned, overlapping or unowned until later decoding. See the [M3.3 contract](docs/architecture/m3-workspace-build-model.md#implemented-slice-m33--safe-filesystem-acquisition-and-candidate-ownership) and [verification evidence](docs/reproducibility/m3-filesystem-acquisition-2026-09-07/README.md).

M3.4 adds neutral exact-classpath contracts and `MavenLocalClasspathProvider`. It resolves independent main/test dependency closures using Maven mediation, scope, exclusion, optional and reactor precedence rules; binds every acquired POM/JAR to exact bytes, digest and portable cache-relative evidence; and preserves missing, unsafe, unsupported, ambiguous or bounded outcomes. The cache reader accepts one explicitly selected standard Maven2 layout and rejects links/escapes and mutable/non-regular inputs, while the embedded model builder has no network, settings, transport, plugin processing or lifecycle execution. Reactor outputs remain explicit requirements rather than fabricated binaries. Dynamic/range versions, relocation and Gradle/split/custom caches remain open. See the [M3.4 contract](docs/architecture/m3-workspace-build-model.md#implemented-slice-m34--passive-exact-classpath-manifests) and [verification evidence](docs/reproducibility/m3-classpath-2026-09-07/README.md).

M3.5 decodes the complete candidate ledger under strict declared or explicit-policy charsets, preserving raw bytes/digests, BOM and LF/CRLF/CR provenance. It passively acquires an explicitly configured Java 8 `rt.jar` or Java 9+ JMOD symbol view with release/vendor/version/digest evidence, and JavaParser can resolve those symbols independently of its Java 21 process. The assembler binds each module/source-set to exactly its decoded sources, analyzed platform, ordered external JARs and explicit reactor-output JARs; missing, mismatched, partial or extra inputs withhold the request. Generated sources and class-directory reactor outputs remain open; the former cross-release `ct.sym` limitation is superseded by M3.7. See the [M3.5 contract](docs/architecture/m3-workspace-build-model.md#implemented-slice-m35--decoded-source-platform-views-and-frontend-inputs) and [verification evidence](docs/reproducibility/m3-frontend-inputs-2026-09-07/README.md).

M3.6 adds the storage-neutral `com.evolution.analysis.evidence` core. It content-addresses capability gaps without circular analysis identity, preserves exact provider-observation references, maps every registered degraded M2/M3 outcome through a versioned mechanism/reason catalog, retains typed evidence requirements and acquisition outcomes, represents provider conflicts explicitly, and adds later evidence through immutable resolution records. Candidate providers never imply authorization and the normalizer performs no I/O or provider execution. M3.8 extends the current catalog/normalizer to `m3.8-v2` / `m3.8` for dependency acquisition while preserving the M3.6 checkpoint identifiers as historical evidence. See the [evidence contract](docs/architecture/evidence-acquisition.md) and [M3.6 verification evidence](docs/reproducibility/m3-capability-gaps-2026-09-08/README.md).

M3.7 adds progressive exact parent/BOM POM resolution in the order supplied/workspace → one selected Maven2 cache → explicitly configured credential-free HTTPS release repositories, with finite passes, counts, bytes and timeouts. Target-declared repositories, redirects, snapshots, settings, credentials, plugins and lifecycles remain inert. Undecided environment profiles now qualify a deterministic inactive baseline instead of erasing unrelated model facts. A newer configured JDK can supply an older requested release through its verified `ct.sym` view.

M3.8 adds `dependency-acquisition-request-v1` / `dependency-acquisition-result-v1`, the `dependency.artifact-cache:m3.8` provider and a bounded Maven classpath-acquisition fixpoint. It resolves exact release POM/JAR requirements through one explicit cache and authorized credential-free HTTPS repositories, validates digests plus secure POM or complete archive content, rejects links/escapes/mutation/redirects/authentication/snapshots, and atomically promotes only verified bytes. Multi-release JARs receive a deterministic target-release class view; manifest `Class-Path` is diagnosed but not expanded. Two isolated-cache Spring PetClinic runs produced byte-identical 20,150,259-byte outputs at `sha256:8806702323d9bc307aef7795bee0b10bdf15e43da65353f75015517b46eb2796`, with all 629 dependency requests accounted for and both source sets analyzed. This completes M3 implementation; [Gate G2 remains withheld](docs/reproducibility/g2-petclinic-check-2026-09-09/README.md) pending independent semantic adjudication. POM-less/non-Maven plans, version ranges, relocation, snapshots, manifest-classpath expansion, generated sources and reactor-output acquisition remain explicit boundaries.

For a Windows sandbox with the user Maven cache, explicitly configure `MAVEN_USER_HOME` and pass `-Dmaven.repo.local=C:/Users/Admin/.m2/repository`. If recompilation reports cache access denial, use the host's supported execution approval. Do not copy caches around a denial. Focused reactor builds use `-pl analyzer-javaparser -am test`; the parent is required by reactor-convergence enforcement.

See [M0 foundation and reproducibility evidence](docs/reproducibility/m0-foundation.md) for pinned versions, checksum provenance, negative enforcement checks, exact environment evidence, and current limitations.
