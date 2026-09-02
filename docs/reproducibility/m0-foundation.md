# M0 Foundation and Reproducibility Evidence

Last verified: 2026-09-02

## Scope and source state

M0 was verified from `HEAD` `4cbcd1211d3abf9cc5ccbd5bcd975b9050e907ae` plus the uncommitted M0 diff. The human-approved M-1 baseline `86c4ca29fb747797df3e489d978804644a34f1ce` is an ancestor of that source state. No commit was created for M0.

The foundation retains the pre-existing root, `analyzer`, and `backend` reactor. It adds no production feature or production dependency. The tracked R1 benchmark evidence was not changed.

## Toolchain contract

| Item | Pinned or allowed value |
|---|---|
| Maven Wrapper | 3.3.4, `bin` distribution with repository JAR |
| Maven distribution | exactly 3.9.16 |
| Build JDK | `[21,22)` |
| Compiler `release` | exactly 21 |
| Source/report/resource encoding | UTF-8 |
| Build output timestamp | `2026-09-01T00:00:00Z` |
| JUnit | 5.11.0 |
| Clean plugin | 3.5.0 |
| Compiler plugin | 3.15.0 |
| Deploy plugin | 3.1.4 |
| Enforcer plugin | 3.6.3 |
| Install plugin | 3.1.4 |
| JAR plugin | 3.5.1 |
| Resources plugin | 3.5.0 |
| Site plugin | 3.22.0 |
| Surefire plugin | 3.5.5 |

Enforcer also checks duplicate dependency declarations, dependency convergence, upper dependency bounds, reactor module convergence, release dependencies for release builds, and explicit versions for lifecycle plugins.

The JAR configuration omits the normally embedded Maven descriptor. During cross-OS verification, the embedded source `pom.xml` was the only nondeterministic entry: Maven Archiver copied different external file-mode metadata from NTFS and the Linux bind mount. The POM and repository metadata remain the authority for coordinates and dependency resolution.

## Wrapper provenance

- Distribution URL: `https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip`
- Apache-published ZIP SHA-512: `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3`
- Locally computed ZIP SHA-256 after the SHA-512 comparison passed: `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`
- Repository wrapper JAR SHA-256: `4e2fbf6554bc8a4702cdfdd3bef464f423393d784ddbb037216320ce55d5e4e1`

The distribution SHA-256 is stored in `.mvn/wrapper/maven-wrapper.properties` and is checked before Maven executes. The wrapper JAR checksum is stored beside it and was also verified against the repository JAR with `Get-FileHash`.

The wrapper uses its repository JAR rather than the lighter native-only script path. The native-only Unix script selects a tarball when `unzip` is absent, while Windows downloads the configured ZIP; one checksum cannot validate both byte streams. The repository wrapper JAR consistently downloads the configured ZIP on both platforms and avoids an undocumented `unzip` dependency.

Primary provenance references:

- [Apache Maven release history](https://maven.apache.org/docs/history.html)
- [Apache Maven Wrapper checksum documentation](https://maven.apache.org/tools/wrapper/)
- [Maven Wrapper Plugin 3.3.4 parameters](https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html)

## Reproduction commands

The canonical commands are documented in the root [README](../../README.md). The clean verification command is:

```text
mvnw -B -ntp clean verify
```

Use `mvnw.cmd` on Windows and `sh ./mvnw` on POSIX. Set `JAVA_HOME` to a JDK 21 installation first.

The independent container command used for G0 was:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace `
  maven@sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8 `
  sh -c 'sh ./mvnw -B -ntp clean verify && sha256sum analyzer/target/*.jar backend/target/*.jar'
```

## Verified environments

### Environment A — local Windows

- OS reported by Maven: Windows 10 `10.0`, amd64
- JDK: Oracle JDK `21.0.12.1+1-LTS-4`
- Maven: wrapper-managed Apache Maven 3.9.16
- Locale/encoding: `en_US` / UTF-8
- Command: `.\mvnw.cmd -B -ntp clean verify`
- Result: reactor success; one JUnit test passed in each module
- Empty-cache result: wrapper distribution, plugins, and dependencies downloaded into new empty directories; clean verification passed

Two consecutive clean local builds produced these SHA-256 values:

| Artifact | SHA-256 |
|---|---|
| `analyzer-0.1.0-SNAPSHOT.jar` | `02a0444ff1abefa808a6ae25f3f0644fc226b839af81da8474a59f4a24ebe26d` |
| `backend-0.1.0-SNAPSHOT.jar` | `02a0444ff1abefa808a6ae25f3f0644fc226b839af81da8474a59f4a24ebe26d` |

### Environment B — Docker Linux

- Image: `maven:3.9.16-eclipse-temurin-21`
- Pulled image digest: `sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8`
- OS reported by Maven: Linux `6.18.33.2-microsoft-standard-wsl2`, amd64
- JDK: Eclipse Temurin `21.0.12+8-LTS`
- Maven: wrapper-managed Apache Maven 3.9.16
- Locale/encoding: `en_US` / UTF-8
- Result: clean reactor success; one JUnit test passed in each module
- Artifact SHA-256 values: identical to Environment A

This is a genuinely independent OS/JDK-vendor environment, although it runs through Docker Desktop on the same physical host.

## Negative enforcement evidence

| Check | Observed result |
|---|---|
| System Maven 3.9.15, `mvn -B -ntp validate` | Failed: allowed range is exactly 3.9.16 |
| Wrapper with `-Dmaven.compiler.release=17` | Failed: release must remain 21 |
| Temurin JDK 17.0.20 in Docker | Failed: allowed JDK range is `[21,22)` |
| Java 21 module tests | Compiler reported `release 21`; tests used the Java 21 `List.getFirst()` API and passed at runtime |

## Limitations

- Both production source trees remain intentionally empty, so Maven reports that the `analyzer` JAR is empty. Adding placeholder production types only to silence that accurate warning would exceed M0 scope.
- The two placeholder JARs are therefore byte-identical. Cross-environment hash equality proves the current archive foundation, not future production-output equality; reproducibility must remain a continuous check as source and resources are added.
- Docker provides an independent Linux/JDK-vendor environment but not an independent physical machine or CI runner.
- JavaParser remains PROVISIONAL; M0 adds no parser integration or new parser evidence.
