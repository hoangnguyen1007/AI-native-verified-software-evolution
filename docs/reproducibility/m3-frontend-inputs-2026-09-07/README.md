# M3.5 Decoded Source, Platform Views and Frontend Inputs — Verification

Date: 2026-09-07. Initial state: clean workspace at `99ae9cf`, with M3.1–M3.4 committed and 160 root tests. Scope: the authorized M3.5 vertical slice; no normalized capability-gap implementation, external-repository checkpoint, G2 promotion, commit or push.

## Delivered and verified

Neutral contracts in `analyzer` add a strict source-decoding ledger, explicit analyzed-platform acquisition request/result, exact frontend plan, reactor-output binding and per-module/source-set assembly result. Stable identities bind the complete acquisition/build/ownership/classpath chain, raw content digests, charset policy/evidence, platform release/vendor/version/artifact hashes, external/reactor classpath order and component versions. Physical repository, JDK and JAR paths remain runtime handles rather than identity inputs.

`SourceDecoder` covers every acquired Java candidate exactly. It accepts a usable M3.2 build declaration or an explicit analysis fallback only when the declaration is absent. The portable catalog is UTF-8, UTF-16, UTF-16BE, UTF-16LE, ISO-8859-1 and US-ASCII. Strict decoders report malformed/unmappable input; UTF-8 and UTF-16 BOMs are recorded and stripped from parser text; incompatible BOM/declaration pairs are withheld; raw bytes and SHA-256 remain immutable; LF, CRLF and lone CR are counted without text normalization. Unowned and overlapping candidates never become semantic documents.

`FilesystemJdkPlatformProvider` passively reads one explicit canonical JDK home under positive artifact/per-file/aggregate limits. Strict `release` metadata must match the requested feature. Java 8 uses `jre/lib/rt.jar` or `lib/rt.jar`; Java 9+ uses a sorted, non-empty `jmods/*.jmod` set. Root and nested link/alias, containment, regular-file, archive-shape, byte-limit and mutation checks are enforced. No toolchain search, environment-selected target, user settings, network, process or target lifecycle exists.

JavaParser adapter `frontend.javaparser:3.27.1-m3.5` now accepts explicit platform JAR/JMOD artifacts. JMOD `classes/*.class` entries are normalized in memory for `JarTypeSolver`; module descriptors are excluded. Platform artifact digests are rechecked immediately before use. Parser language level is selected independently for non-preview Java 8–21, and unsupported/preview plans fail explicitly. Reactor-output JARs carry project module scope rather than dependency scope.

`FrontendInputAssembler` emits a request only when all candidates for that source set are decoded and the matching source plan, exact platform, dependency JARs and reactor-output JARs are available. M3.4's result/provider advance to `exact-classpath-result-v2` / `classpath.maven-local:3.9.16-m3.5`, adding one contiguous ordinal shared by external and reactor entries without changing mediation. Missing or extra binaries, unsatisfied reactor outputs, platform release mismatch, invalid source plans and any non-reactor classpath problem withhold the request; no ambient or union classpath is substituted.

| Check actually run | Result |
|---|---|
| Initial compile after new contracts | Expected red: one lambda capture error in the new identity validation; corrected before behavior tests |
| First decoding specification | Expected red: all five cases exposed unsorted limitation input in identity construction; canonical sorting fixed |
| Source decoding fixtures | 5 cases passed: UTF-8 BOM/CRLF, ISO-8859-1, UTF-16LE BOM, malformed UTF-8, BOM conflict, absent-policy behavior and invalid-declaration non-fallback |
| Frontend assembly fixtures | 2 cases passed: interleaved reactor/external order and provenance; missing reactor, extra binary and platform mismatch withholding |
| Platform filesystem fixtures | 3 cases passed: Java 8 `rt.jar`, Java 9+ sorted JMODs, path-independent identity, release mismatch and finite limits |
| JavaParser resolution-input fixtures | 9 cases passed, including explicit non-host platform JAR, JMOD prefix normalization, mutation rejection and Java 8 syntax enforcement |
| Final root `verify` | **172 tests: 61 analyzer + 41 Maven + 11 filesystem + 58 JavaParser + 1 backend; zero failures, errors or skips; exit 0** |

Final verification command:

```powershell
$env:MAVEN_USER_HOME='C:/Users/Admin/.m2'
.\mvnw.cmd -q '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

Environment: Windows 11 amd64, Oracle JDK 21.0.12.1, pinned Maven 3.9.16, explicitly configured local Maven repository. The first sandboxed wrapper invocation could not create `C:\.m2`; all reported evidence comes from the approved execution path with explicit `MAVEN_USER_HOME` and `maven.repo.local`. Production M3.5 code starts no process and performs no network operation.

## Evidence boundary

The fixtures prove deterministic contracts and bounded behavior for synthetic Java 8/17 platform archive shapes and a verified running Java 21 compatibility path. They do not prove every vendor JDK layout, all Java 8/11/17/21 real installations, full JavaParser syntax equivalence, external-repository classpath/semantic accuracy or G2 acceptance. Cross-release `ct.sym`, preview syntax, class-directory module outputs, generated sources, target toolchain discovery, multi-release archives, JAR manifest classpaths and normalized `CapabilityGapRecord` remain explicit later work.

Exact next task: M3.6 normalized capability gaps and acquisition/conflict provenance, followed by the representative real-repository category/reason-level checkpoint and independent acceptance needed for G2.
