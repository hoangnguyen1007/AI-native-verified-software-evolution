# Universal v2 L2 public cohort registration

Status: **PROVISIONAL cohort, no scored runs** (2026-09-27). The owner authorized selection of public repositories and fixed the eligible-cohort correct evidenced coverage target at **at least 99%**. Seven bounded source exports and their entry hashes are captured. Independent labels, input modes, inclusion decisions and hardware remain unregistered, so this is not an eligible scored corpus or a measured claim.

## Candidate snapshots and expected strata

The full commit IDs below were observed with read-only `git ls-remote` on 2026-09-27. Each license file was read from `raw.githubusercontent.com` at that exact commit with a 1 MiB per-file bound; its SHA-256 was matched to the same file inside the captured archive. Additional third-party notices and component terms need separate review where applicable.

| Candidate repository | Pinned Git revision | Expected L2 strata to label independently | Pinned license file and SHA-256 |
|---|---|---|---|---|
| [TheAlgorithms/Java](https://github.com/TheAlgorithms/Java) | `743ff5eb0944d8a3ed25d73fc992f5a69a36134d` | Student/educational Java; mixed self-contained algorithms | `LICENSE` (MIT), `d8499582b215ce90b33be4736260b1bb2e2dc312f71c1b29d19b8fc6d2dcdc3f` |
| [apache/roller](https://github.com/apache/roller) | `a944dcb5ecc82bbad8f9509fea6bcfe3f13007c4` | Legacy servlet/JSP/XML web application; Maven | `LICENSE.txt` (Apache-2.0), `a049d277836f396559e0c684f55d106718a86ec5c125465c120b04add7ffd1d4` |
| [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) | `818c4136ea971c21674525f9053de0d9c7ad8cfe` | Modern Spring Boot; Maven | `LICENSE.txt` (Apache-2.0), `56dfc19e0dc836e30177332f73e8e6fbc297941acf3d906eec6eaaa46c2c452a` |
| [spotbugs/spotbugs](https://github.com/spotbugs/spotbugs) | `feb289bd216f442957ac0414d5f7c9edbe163c24` | Gradle Groovy; modular Java tooling | `LICENSE` (LGPL-2.1), `9b872a8a070b8ad329c4bd380fb1bf0000f564c75023ec8e1e6803f15364b9e9` |
| [junit-team/junit-framework](https://github.com/junit-team/junit-framework) | `6c8ca116941611df2d4e2e8319ab8cb3e520afe7` | Gradle Kotlin DSL; large modular codebase | `LICENSE.md` (EPL-2.0), `5aa4cd44c111add178d1c2e2fe36d58a484012c80167df925f826cd64d411bf0` |
| [apache/poi](https://github.com/apache/poi) | `942d95d85b15d0dfdb3bc9ba1b4f273f277757c8` | Large modular codebase; binary-heavy/offline dependencies | `legal/LICENSE` (Apache-2.0 project file), `6e7c918e5a49f677c1b85a9eb2d035cd85c217fc42af6b4a336964a434001a9c` |
| [thingsboard/thingsboard](https://github.com/thingsboard/thingsboard) | `ced7d9bd5e7dd9759a0fe912b142b1f4dd4361b8` | Enterprise-like monorepo; Maven reactor; generated-heavy; mixed frontend/backend | `LICENSE` (Apache-2.0), `c1b9df1275e769f3dbab000d1e457a2d4b0f28eb5da6c77e48dc37eeba202ed7` |

## Captured source-export identity

`benchmarks/freeze_l2_source_snapshot.py` fetched the GitHub Codeload ZIP for each full commit without target execution or extraction. It bounded compressed bytes, entry count, individual entry expansion and total expansion; SHA-256 covers the ZIP bytes and every entry. The tree digest is SHA-256 over sorted canonical entry records (`path`, `kind`, raw-byte length, raw-byte SHA-256), prefixed by schema `m4uv2-l2-source-export-v1`. The detailed JSON input-hash manifests are local immutable artifacts under `benchmarks/results/raw_dumps/`; that directory is intentionally Git-ignored, so the compact identities below are preserved here. Re-fetching TheAlgorithms produced identical archive, tree and manifest bytes.

| Repository | Captured entries / files | ZIP SHA-256 | Tree SHA-256 | Detailed manifest SHA-256 |
|---|---:|---|---|---|
| TheAlgorithms/Java | 1,786 / 1,663 | `51e136409d17117a81d6f2729a5b155e48156d53f163556a72c10f50fc5abaad` | `47000196cd6399a561452fbd01e1ffb93253789ec33fc64dad0c44b7d125be03` | `565d4cebe48673cedbf876f112d438afe49397fd22f35cea5c46fd869d12132c` |
| apache/roller | 1,600 / 1,314 | `bc6e6c2e7ca7cdc0811dd99dbca3279a816815981133f12b09b54bf0d8fedad4` | `8d5fef4c84856966d3eddf6c21a4aa8ccd3bda4a5cc65f52047b74ff11f848f0` | `7a497d849353547a1dccc793f5a518fca236bb1aa206d49b391608d725a0f8f6` |
| spring-projects/spring-petclinic | 181 / 132 | `eada03c079fac83cda12a173da77871efcf5935b2586e6097bc0220c632a87f9` | `7e9b850dbb0a066ed5b7b67b67c4daee3d145e0f5efe234fb990a5b74e696554` | `5db130cf38037cc0ded3b50d268e7dcb99ffcdc261148014f8a1380cc58fbb08` |
| spotbugs/spotbugs | 4,605 / 4,095 | `c873696fc97833b39c9dbd6cf92cfd6afd9f465986858322571e0480f5166f43` | `712ab857e36a4b05aa7f82d02e14c0027b3007d4c63e5acb76adb8675d218eed` | `80cb8268677ce72fe4aee993dd77e84223035d5ddfd53dd98212113b55947070` |
| junit-team/junit-framework | 3,207 / 2,425 | `7e7b899a93271dffc4c9780a567a238261061ed2447b1426a74004af3e7f002e` | `9f8ba0c00960b2192bd8e49936b2df9c99f7e012200b12f58739b81be8df35ff` | `1387311dda75bde451ad876ca960c0ea71b0e070a7c0081365b27e3152b98323` |
| apache/poi | 6,517 / 5,879 | `86051bc7a5e0677298be19049c1a04665bc3f8cf2d3de8cfc548d6c3289d0089` | `aac801852c78f617a87164480670ad46e0e3ca5b83c64e1c8146dbefc45b4e44` | `ed260054d0b447ac86f03be4e11e9bb5882b60be3d0f7a6bded2e3c31d0191e5` |
| thingsboard/thingsboard | 12,039 / 10,090 | `e2db1e9d5762ede560ccf9e88f581b361984c26a288b4f39089feb51497a93b6` | `e2d0beeb81f3384d0e92ac9da713d2ea2507671622b21b403a36f9d7d11752c0` | `1882b39e944edeb4c8cfe14b07a656345f74be087e2a3999b65c3df4d84afe04` |

The strata are **expected sampling reasons**, not independently adjudicated labels. Several features overlap; no row represents all repositories of its type. The cohort contains no private enterprise repository, so it cannot support a private-enterprise population claim. TheAlgorithms has a Maven descriptor, so it is not a build-free/plain-Java control; compact L0 fixtures cover build-free Java separately.

Passive filename counts from the frozen exports support initial sampling: Roller has 93 JSP/JSPX files and 5 `pom.xml` files; JUnit has 79 `.gradle.kts` files; SpotBugs has 23 `.gradle` files; POI has 1,404 files with common binary fixture extensions (JAR/WAR/class/Office/PDF); ThingsBoard has 60 `pom.xml`, 12 `.proto` and 1,745 TypeScript/TSX files. These counts establish observed paths only. In particular, ThingsBoard's generation-heavy classification still needs independent recipe/output labels.

## Freeze work before scoring

The bounded Codeload exports and per-entry raw SHA-256 hashes are captured; all seven pinned license-file digests match their corresponding export entries. No `.gitmodules` path or ZIP symlink entry was present in these exports. Codeload archives do not themselves prove Git object ancestry or availability of LFS payloads, so record any LFS pointer and missing-content finding during the repository intake run. Before scoring, freeze exact analyzed path inclusion, source/build/configuration selection, platform policy, imported artifacts and their hashes. Do not run target Maven/Gradle lifecycles or scripts. If any required content is unavailable, keep that fact in the input denominator; do not silently replace a repository after seeing results.

Before the first scored run, publish independent semantic and architecture-question labels with protocol/adjudicator identity, a fixed eligible-obligation denominator, mandatory questions, input modes A–D per repository, evidence setup costs, weights, hardware and resource budgets. Pin the frozen Universal v1 implementation commit `e118e6c264a0343f04c507ac294b4c31ae85aee0` for paired comparisons. Keep historical G2 PetClinic evidence immutable; a V2 run is separately identified. The 99% threshold is applied only to registered eligible obligations and cannot override correctness, safety, inventory or per-cohort blockers.

This candidate roster is **not yet an eligible scored L2 corpus**. Source-export and license bytes are now content-addressed, while independent labels, final analyzed-path inclusion, input-mode assignments, weights, hardware, third-party license review and any LFS-content findings remain open. No L2 benchmark has run.
