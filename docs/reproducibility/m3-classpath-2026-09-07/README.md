# M3.4 Passive Exact Classpath Manifests — Verification

Date: 2026-09-07. Initial state: clean workspace at `0fefb8bc5a9ed0c637b29f1af8e879201c87a82e`, with M3.1–M3.3 committed and 149 root tests. Scope: the authorized M3.4 vertical slice; no G2 promotion, external-repository acceptance, commit or push.

## Delivered and verified

Neutral contracts in `analyzer` define the content-addressed `ClasspathResolutionRequest`, finite `ClasspathResolutionPolicy`, exact artifact coordinates, replaceable `ClasspathProvider`, and per-module/source-set `ExactClasspathResult`. Manifest identity binds ordered entries, scopes, direct/depth metadata, reactor requirements, mediation decisions, typed problems and exact POM/JAR evidence. The selected absolute cache root is not persisted or hashed; cache-relative paths and acquired content digests are.

The isolated `MavenLocalClasspathProvider` reads one explicitly selected canonical Maven cache without settings, network, transport, plugins or target lifecycle execution. It securely constructs external effective POM descriptors from bounded bytes; resolves nearest/first-declaration conflicts, root dependency management, Maven scope propagation, exclusions and optional dependencies; maps supported JAR types/classifiers; keeps POM dependencies descriptor-only; and produces independent `MAIN`/`TEST` manifests. Reactor GAVs take precedence over cache copies. Missing compiled outputs and duplicate reactor coordinates are explicit, and ambiguous reactor coordinates cannot fall back to cache artifacts.

The cache reader verifies real-path containment, rejects symbolic links/junction aliases and non-regular inputs, opens final files without following links, checks attributes around reads, and enforces finite coordinate, file, POM-byte, JAR-byte, aggregate-byte, external-model-read and dependency-depth limits. Exact SHA-256 evidence and sanitized attempts remain available for successful and failed reads.

| Check actually run | Result |
|---|---|
| Initial specification against absent implementation | Expected compilation failure for missing M3.4 contract/provider types |
| Duplicate reactor-coordinate regression | Expected red because cache fallback was attempted; green after ambiguous reactor nodes were withheld |
| Repeated over-limit-coordinate regression | Expected red because a repeated coordinate could bypass the cap; green after admission was checked before insertion |
| Reactor POM descriptor-only regression | Expected red because a POM dependency created a reactor output entry; green after descriptor-only handling preceded reactor binary handling |
| Focused M3.4 specification | 3 neutral contract + 8 Maven adapter cases passed; zero failures, errors or skips |
| Full affected reactor | 54 analyzer + 41 Maven tests passed; zero failures, errors or skips |
| Final root `verify` | **160 tests: 54 analyzer + 41 Maven + 8 filesystem + 56 JavaParser + 1 backend; zero failures, errors or skips; exit 0** |

Environment: Windows 11 amd64, Oracle JDK 21.0.12.1, pinned Maven 3.9.16, explicitly configured local Maven repository. The first sandboxed Maven attempt could not read the user cache; all reported test evidence comes from the supported approved execution path. Production M3.4 code starts no process and performs no network operation.

Final commands:

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' '-Dtest=ClasspathResolutionContractTest,MavenLocalClasspathProviderTest' '-Dsurefire.failIfNoSpecifiedTests=false' -pl analyzer-maven -am test
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer-maven -am test
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

## Fixture coverage and evidence boundary

Fixtures cover module/main/test isolation; compile/provided/runtime/test propagation; dependency-management version replacement; nearest and equal-depth selection; scope conflict normalization; declaration order; exclusions and optionality; ordinary, classified and `test-jar` artifacts; external parent/imported-BOM models; inert repository declarations; reactor precedence and descriptor-only POMs; missing POM/JAR, unsafe XML, non-exact versions, ambient profile activation, coordinate mismatch, system paths, unsupported types and invalid JARs; all resource-limit dimensions; cache-root/path link denial; non-directory/missing roots; cache-location-independent identity; and content-change identity sensitivity.

This is controlled fixture evidence plus implementer self-review. It does not establish full Maven CLI equivalence, real-repository classpath accuracy, independent acceptance or G2 completion. Version ranges, relocations, system paths and non-JAR classpath artifacts remain unsupported; reactor binaries are not acquired; archive structure, JAR manifest classpaths and multi-release views remain downstream checks. M3.4 observations have not yet been normalized into the cross-provider `CapabilityGapRecord` contract.

Exact next task: M3.5, deterministic source decoding plus analyzed-platform and frontend-input assembly using M3.3 candidates and M3.4 verified dependency evidence. Normalized gaps and representative external coverage follow before G2 acceptance.
