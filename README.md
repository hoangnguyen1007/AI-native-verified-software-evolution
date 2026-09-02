# AI-Native Verified Software Evolution

This repository is the SE121 software architecture intelligence platform. The current codebase is an intentionally minimal Java 21 Maven reactor; production semantic analysis starts in later milestones.

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

## Reactor

The root reactor owns all shared build and test policy and builds in this order:

1. `software-evolution-platform` (root aggregator)
2. `analyzer` (placeholder JAR and module-local test boundary)
3. `backend` (placeholder JAR, depends on `analyzer`, and has its own test boundary)

No production parser, graph, Spring, backend API, CLI, or frontend feature is part of this foundation.

See [M0 foundation and reproducibility evidence](docs/reproducibility/m0-foundation.md) for pinned versions, checksum provenance, negative enforcement checks, exact environment evidence, and current limitations.
