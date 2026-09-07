# M3.3 Safe Filesystem Acquisition and Candidate Ownership — Verification

Date: 2026-09-07. Initial state: clean workspace at `8b17554`, with M3.1/M3.2 committed and 138 root tests. Scope: the authorized M3.3 vertical slice; no G2 promotion, external-repository acceptance, commit or push.

## Delivered and verified

Neutral acquisition contracts in `analyzer` preserve exact immutable bytes/digests, explicit selection policy, complete/partial/failed outcomes, bounded read attempts and sanitized typed problems. The isolated `analyzer-filesystem` adapter scans one caller-selected local directory using real-path containment, link/junction refusal, final-component no-follow reads, file/directory mutation checks, and finite file, directory, combined-entry, per-file byte, total-byte and depth limits. The combined entry limit has a one-million hard ceiling and is enforced while enumerating, before deterministic child sorting. Explicit exclusions are part of the request and remain visible; no ambient ignore rule is inferred. A partial inventory cannot create an M1 `RepositorySnapshot` or M3 `BuildModelRequest`.

The candidate ownership resolver consumes only a complete acquisition plus its exact M3 build request/result. It classifies every acquired `.java` path as `OWNED`, `OVERLAPPING` or `UNOWNED`, preserving module, `MAIN`/`TEST`, source-root and POM evidence. Missing source roots and exact overlapping/unowned files remain typed problems. It does not decode bytes, create `SourceDocument` values, evaluate plugins/generated outputs, or execute any target content.

| Check actually run | Result |
|---|---|
| Initial specification against absent implementation | Expected compilation failure for missing acquisition contracts/provider |
| Focused M3.3 specification | 8 filesystem integration/security cases passed; zero failures, errors or skips |
| Duplicate missing-root regression | Expected red: `ownership problems must not contain duplicates`; green after resolver deduplication |
| Focused affected reactor | 51 analyzer + 33 Maven + 8 filesystem tests passed; zero failures, errors or skips |
| Final root `verify` | **149 tests: 51 analyzer + 33 Maven + 8 filesystem + 56 JavaParser + 1 backend; zero failures, errors or skips; exit 0** |

The initial sandbox run failed before specification evaluation because Maven could not read the configured local JUnit cache. The supported elevated run then produced the intended missing-type red; this environment denial is not counted as regression evidence. Windows symbolic-link creation was unavailable to the test process, so the containment test uses a test-only directory junction fallback and verifies that the production provider does not traverse its target. Production code invokes no process.

Environment: Windows 11 amd64, Oracle JDK 21.0.12.1, pinned Maven 3.9.16, explicitly configured local Maven repository. Final commands:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' '-Dtest=FilesystemRepositoryAcquirerTest' '-Dsurefire.failIfNoSpecifiedTests=false' -pl analyzer-filesystem -am test
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

## Evidence boundary and next task

Fixtures establish immutable content, deterministic repeated identities, explicit exclusions, missing entry POM behavior, root/entry containment, link/junction refusal, all six resource-limit dimensions, complete-snapshot withholding, exact POM handoff, module/main/test ownership, repository-root source declarations, duplicate missing-root deduplication, missing roots, overlaps and unowned Java files. This is fixture evidence and implementer self-review, not independent acceptance or a representative external-repository checkpoint.

M3.3 does not acquire local-cache artifacts, resolve dependency closure, produce JAR/classpath manifests, decode source encodings, acquire platform symbols/generated sources, or implement normalized capability gaps. G2 remains open.

Exact next task: M3.4, passive explicitly allowed local-cache artifact acquisition and deterministic per-module/per-source-set dependency mediation/JAR manifests, including digest, scope, ordering, reactor isolation and unresolved-artifact evidence without network or target lifecycle execution.
