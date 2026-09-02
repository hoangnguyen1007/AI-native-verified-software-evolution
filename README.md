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
2. `analyzer` (placeholder JAR and module-local test boundary)
3. `backend` (placeholder JAR, depends on `analyzer`, and has its own test boundary)

No production parser, graph, Spring, backend API, CLI, or frontend feature is part of this foundation.

See [M0 foundation and reproducibility evidence](docs/reproducibility/m0-foundation.md) for pinned versions, checksum provenance, negative enforcement checks, exact environment evidence, and current limitations.
