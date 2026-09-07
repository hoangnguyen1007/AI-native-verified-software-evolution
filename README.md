# AI-Native Verified Software Evolution

This repository is the SE121 software architecture intelligence platform. The Java 21 Maven reactor contains parser-neutral contracts, the M2 JavaParser frontend, passive effective-POM/source-plan modeling, and bounded filesystem acquisition with explicit candidate source ownership. M3 is in progress; G2 is not passed. See [current state](docs/current-state.md) for verified scope and limitations.

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
3. `analyzer-maven` (isolated Maven Model Builder adapter; no target lifecycle, filesystem discovery or network resolution)
4. `analyzer-filesystem` (isolated passive filesystem acquisition; no link following, execution or network access)
5. `analyzer-javaparser` (isolated JavaParser/SymbolSolver adapter and semantic fixtures)
6. `backend` (placeholder JAR, depends on `analyzer`, and has its own test boundary)

The adapter accepts exact supplied source bytes and verified resolution inputs; it does not discover or execute target builds. Graph, Spring, backend API, CLI and visual workbench implementation remain later work.

The Maven adapter implements `BuildModelProvider` in the neutral `com.evolution.analysis.buildmodel` package. It models root/nested modules, relative parents, supplied artifact parents/BOMs, inheritance, properties, explicit/property/default profiles, dependency management, scopes, optional flags and exclusions. M3.2 adds per-module main/test candidate source plans with inherited directories, separate syntax/bytecode/API requirements, encoding declarations, plugin configuration and provenance. Result schema `build-model-result-v2` includes source-plan gaps in `hasGaps()`.

M3.3 adds neutral immutable acquisition/ownership contracts and the isolated `analyzer-filesystem` adapter. It reads an explicitly selected directory under finite file/directory/byte/depth limits, preserves exact bytes and digests, withholds incomplete snapshots, rejects links/escapes, and records exclusions or failures without raw host paths. Acquired POM bytes feed M3.1/M3.2; `.java` files remain candidates classified as owned, overlapping or unowned until later decoding. Dependency cache/JAR/classpath resolution, source decoding, platform views and generated-source acquisition remain open. See the [M3.3 contract](docs/architecture/m3-workspace-build-model.md#implemented-slice-m33--safe-filesystem-acquisition-and-candidate-ownership) and [verification evidence](docs/reproducibility/m3-filesystem-acquisition-2026-09-07/README.md).

For a Windows sandbox with the user Maven cache, explicitly configure `MAVEN_USER_HOME` and pass `-Dmaven.repo.local=C:/Users/Admin/.m2/repository`. If recompilation reports cache access denial, use the host's supported execution approval. Do not copy caches around a denial. Focused reactor builds use `-pl analyzer-javaparser -am test`; the parent is required by reactor-convergence enforcement.

See [M0 foundation and reproducibility evidence](docs/reproducibility/m0-foundation.md) for pinned versions, checksum provenance, negative enforcement checks, exact environment evidence, and current limitations.
