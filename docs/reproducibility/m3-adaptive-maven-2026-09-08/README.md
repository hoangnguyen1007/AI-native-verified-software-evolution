# M3.7 Adaptive Maven Input Verification

Date: 2026-09-08  
Milestone: M3.7, with Gate G2 still withheld

## Scope

This record verifies the correction for external parent and imported BOM acquisition. The implementation must not require every external parent or imported BOM POM to be copied into the analyzed workspace, and host-dependent profile uncertainty must not erase unrelated effective-model facts. Acquisition remains explicit, finite, passive and provenance-preserving.

## Implemented and checked

- Supplied/workspace POMs are tried first, followed by one selected Maven2 cache and then optional configured credential-free HTTPS release-POM origins.
- POM count, per-POM bytes, aggregate bytes, passes, remote origins and connect/request times are bounded. Target-declared repositories, redirects, snapshots, settings, credentials, extensions, plugins and target lifecycles remain inert.
- External parent and imported-BOM acquisition iterates to a deterministic fixpoint and binds exact coordinates, origin, bytes and SHA-256 digest into the final build request.
- A coordinate-mismatched cached parent is rejected; absent root POM and genuinely unavailable parents remain explicit gaps.
- Undecidable JDK/OS/file profile activation preserves a qualified inactive baseline and its limitation instead of invalidating the full model. Explicit profile choices still win.
- A configured newer JDK can supply the requested older Java API through a verified `ct.sym` release view.
- Failed build reads retain observed input evidence without incorrectly claiming that evidence as a produced output artifact.
- The benchmark harness uses explicit repository identity/revision/cache/JDK inputs, excludes VCS/build-output paths, removes the dummy reactor artifact, reports all 18 registered semantic categories and always withholds G2 pending independent correctness evidence.

## Verification commands and results

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -o -B -ntp "-Dmaven.repo.local=C:/Users/Admin/.m2/repository" verify
```

Result: **PASS** — 197 tests, 0 failures, 0 errors, 0 skipped across all six reactor projects. The Maven adapter contributes 50 tests, including the shared local/remote aggregate-byte-budget regression.

```powershell
# Install platform artifacts to local cache first so standalone benchmark module resolves SNAPSHOT dependencies:
.\mvnw.cmd -o -B -ntp "-Dmaven.repo.local=C:/Users/Admin/.m2/repository" install -DskipTests
.\mvnw.cmd -o -B -ntp "-Dmaven.repo.local=C:/Users/Admin/.m2/repository" -f benchmarks/g2-pipeline/pom.xml test
```

Result: **PASS** — 3 tests, including a regression that requires the generated report to say when no source set reached the semantic frontend. The relevant production changes were developed through focused red-to-green tests before the full verification.

## Real-repository cross-checks

| Repository | Exact revision | External POMs acquired | Java owned / decoded | Frontend runs | Deterministic two-run digest |
| --- | --- | ---: | ---: | ---: | --- |
| Spring PetClinic | `818c4136ea971c21674525f9053de0d9c7ad8cfe` | 59 | 50 / 50 | 0 | `sha256:5d9ece7d8e12741a8b3ddfa0d5f62a1817a386f0d3a3694b1bc85adb49e3f657` |

The run emits neither `build.pom:MISSING_PARENT_POM` nor `build.source-ownership:UNOWNED_SOURCE_FILE`. This confirms external-parent and BOM acquisition works passively; it is not a universal repository-compatibility claim.

PetClinic still lacks 27 required dependency POM/JAR coordinates in the selected cache. The run therefore withholds both source sets with `frontend.input:CLASSPATH_PROBLEM`. The zero semantic-category counts prove that the report retains a closed denominator, not that semantic analysis succeeded. Exact normalized reasons and raw deterministic runs are preserved in the [PetClinic cross-check](../g2-petclinic-check-2026-09-08/README.md).

## Remaining boundary

Gate G2 remains **WITHHELD**. The next bounded capability is policy-selected dependency POM/JAR acquisition into an isolated content-addressed cache, followed by rerunning the repository and independent semantic adjudication. Multi-module, POM-less conventional Java layouts and non-Maven build systems remain separate provider shapes; they must be supported through neutral source-plan/build adapters rather than by weakening Maven correctness or fabricating evidence.
