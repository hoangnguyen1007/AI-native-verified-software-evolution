# M8.4.2 — Passive multi-root readiness planner

Date: 2026-10-19. The [M8.4 contract](m8-repository-readiness.md) and [six-step plan](../tasks/m8/04-comprehensive-repository-readiness.md) govern scope. This implementation plans native work from captured evidence; executor conformance and native observations remain step 3.

## Responsibilities and identity

`analyzer.readiness` owns `ReadinessPlanner`, `BuildContextSelection`, `ReadinessPlan`, dependency scheduling, ownership disposition and captured-manifest satisfaction. `PassiveReadinessDiscovery` projects non-Maven declarations; `analyzer-maven.MavenReadinessDiscovery` uses the existing secure Maven reader without invoking Maven. No parser, process, SQL or host filesystem object crosses the discovery port.

Schemas are `repository-readiness:m8.4-v1` and `build-context-selection:m8.4-v1`; provider is `build.readiness-planner:m8.4-v1`. A plan binds the sealed inventory, acquisition selection, requested/discovered/selected/unselected/unknown context partition, roots/modules, context input closures, requirements, recipes and ownership-ledger SHA-256. Context closures conservatively bind the full captured inventory, so an independent source change invalidates more cache entries than a future narrower proven footprint would. Revision UUIDs, host paths, retries and timing are excluded. Logical module/context IDs are local to the content-addressed plan; they do not replace M1 Java entity IDs.

When used by `scan` or backend analysis, `readiness.plan` is an explicit analysis-configuration input. Existing M1 preimages remain unchanged; result-affecting build/source providers are versioned as `build.universal-ingestion:m8.4-multi-root-v1` and `source.universal-ingestion:m8.4-readiness-scope-v1`. Consumers without the additional readiness policy retain the original constructor/API shape.

## Discovery and coverage

- Inventory is traversed with bounded pages. Descriptor bytes are verified against CAS before parsing. The configurable descriptor worker budget produces `DESCRIPTOR_WORKER_REQUIRED`; it never excludes the descriptor from the inventory or seals a smaller snapshot.
- Maven aggregation and relative-parent relations are distinct. Every POM remains a discovered candidate, including nested independent roots, malformed roots, missing declared siblings, duplicate coordinates and cyclic/shared aggregation. Profiles retain separate candidate contexts; a profile-only module is not assigned to the baseline reactor. Combinations, environment activation, unresolved expressions, inheritance and plugin effects require native-model evidence.
- Gradle literal `include`/`includeBuild` declarations, buildSrc builds and literal named source-set roots are retained. A lexical tree prevents comments/strings from inventing projects. Composite substitution, executable settings, variants, source-role and platform dimensions remain native-model obligations; passive declarations are not exact Gradle observations.
- IDE/plain Java, Ant, Bazel, sbt, Node, Python, Cargo, Go, .NET, Composer, Ruby, Dart, Swift, CMake and Make markers produce native-family roots and explicit target/source-plan discovery obligations. This is build discovery, not delivery of the step-5 language engines. A discovery port supports additional or captured native models without converting them into Maven projects.
- Main, test, custom and unclassified candidate source populations stay separate. Platform/compiler settings retain declared source, target, release, encoding and preview information separately from the build JDK and analyzed platform. Unobserved dimensions stay explicit. Android, JavaFX, enterprise/JSP, JPMS, OSGi, generators, weaving and native-tool declarations produce acquisition requirements when detected; Swing/AWT use the ordinary JDK platform requirement. Dependency names never establish framework runtime behavior.
- The externally written ownership ledger contains one JSON row for every inventory entry, including excluded, non-source, unavailable and raw non-UTF8 paths. Candidate ownership can be shared across exclusive variants; overlapping owners/source sets remain ambiguous. Unknown file classification is an obligation. Ownership counts accept only registered dispositions and nonnegative 64-bit values; their checked sum must equal the nonnegative inventory denominator, with a non-null ledger handle. Overflow or compensating negative counts cannot fabricate closure. Valid canonical bytes and provider identity are unchanged by these validation guards. Ownership is not semantic correctness.

The selection manifest partitions every discovered context. Requests that cannot be matched and dynamic dimensions remain discovery obligations. Selecting one finite variant/source-set domain never establishes all-context completion. All bytes and unselected contexts remain visible.

## Requirements, recipes and scheduling

Every selected buildable context has native build-validation requirements even when passive analysis returns no semantic gap. Model, toolchain, exact dependency closure, platform and generator acquisition are prerequisites. Script-only/non-compiling contexts can use native semantic validation; `NOT_APPLICABLE` for compilation requires independent no-compile-phase proof plus successful native semantic validation.

Requirements form a validated DAG. Stable scheduling prioritizes prerequisites that directly unlock more work, then breaks ties by ID. Failed/uncompleted prerequisites do not erase independent runnable work. Module dependency SCCs are exposed as joint compilation groups. Recipes retain root-sized execution because passive evidence cannot prove away global plugin/configuration effects; a native adapter must decide permissible target decomposition.

Recipes contain native family, selected contexts, declared captured descriptor inputs, wrapper/tool-configuration digest pins, expected output categories, guest-only side effects, broker-only network policy, a configured worker resource policy and satisfaction checks. They are adapter-owned action descriptions, not executable repository suggestions. There is no host execution method in this planner. Actual native command lowering, resource enforcement and protected observation belong to step 3.

Product Maven dependency handling visits every captured root and uses the existing exact-coordinate acquisition fixpoint. CLI `--artifact-repository HTTPS_URI` explicitly enables its supported passive artifact lane; default operation retains cache evidence and isolated-resolution requirements. Version ranges, SNAPSHOTs, relocations, variants and executable model requests remain native resolver obligations, rather than fabricated versions or dependency supersets. Conflicting overlapping classpath/POM views are withheld with typed evidence; independent roots remain available.

## Captured evidence satisfaction

`CapturedBuildEvidence` compares context, selection, input closure, provider/version, toolchain digest, ordered classpath/module path, compiler arguments and generated inputs. Order is significant. Exit success, an artifact receipt, or target-produced assertions alone cannot satisfy validation. An injected protected-capture verifier must validate the exact observation and receipt; there is no production verifier that approves arbitrary captures in step 2.

Only a validated capture matching a selected context closes that context's native validation requirement. Root-level model/dependency obligations are not inferred complete from one compiled context. Conflicting captures leave requirements pending. The original plan/evidence remains immutable; a reconciled plan is a separate content revision. These checks exercise the promotion predicate using authored captured models, not a qualified real executor.

## Durability and product access

`RepositoryIntake.readiness` writes the canonical report to CAS and then publishes a separate durable journal marker keyed by inventory/provider/policy. Ownership rows stream into CAS without a whole-repository source list. Report reuse verifies report bytes, canonical identity and ownership-ledger bytes; source-tree changes cannot alter an already captured plan. Restart reconstructs the same plan without executing target content. Report decoding uses a bounded worker; an oversized report retains its bytes and requests a larger report worker. Physical partitioning of very large planning/semantic/graph workloads remains a step-6 integration qualification.

CLI:

```text
evo acquire PATH --resume captured --json
evo readiness captured --section roots --json
evo readiness captured --section requirements --limit 20 --json
evo readiness captured --source-set main --variant baseline --section contexts --json
```

`POST /api/v2/acquisitions/{revision}/readiness` accepts `{section,size,cursor}`. Sections: `roots`, `modules`, `contexts`, `requirements`, `recipes`, `compilation-groups`, `selection`, `unknown`. Cursors bind plan identity and section. Pages expose exact totals, scope identities and ownership counts, with `semanticCoverage: NOT_EVALUATED`. They never execute a recipe or accept an arbitrary command. Full workbench readiness views remain step 6.

## Verification boundary

The execution record in the [task plan](../tasks/m8/04-comprehensive-repository-readiness.md) owns commands and final totals. Tests cover nested roots and a broken independent sibling, aggregation versus parent, missing siblings, duplicate GAVs, profile-only modules, Gradle composite/custom sources/buildSrc, other build families, exclusive captured variants, ownership accounting, source changes, restart/cache identity, paging, cyclic module groups, requirement scheduling, exact-manifest negatives and protected-observation rejection.

No target build, Gradle configuration request, application, remote indexer or compiler hook is executed by this work. Live native manifest qualification, isolation conformance, generated output closure, language-engine accuracy, full planning/graph scale and integrated M8.4 acceptance remain subsequent steps. These limitations do not turn pending requirements into satisfied facts or reopen historical gates.

## Step-3 execution bridge

The [Phase-1 execution design](m8-isolated-execution.md) adds an execution graph without changing this passive schema. `runnable(completed)` remains a planning helper; actual completion must come from a verified decision ledger. Module SCCs are candidates, not permission to co-compile an illegal native cycle. The existing authored capture checker is not production trust qualification: Phase 2 supplies protected verification, exact zero-output/applicability handling, semantic-validation status distinct from compilation applicability, and conflict/revocation-aware reconciliation. Until then the implementation boundary above remains unchanged.
