# JavaParser vs OpenRewrite Semantic Frontend Evaluation

## Status

**OPEN TECHNOLOGY GATE — preliminary controlled evidence only.** This package does not authorize M2 production implementation or a change to ADR-001. Its present conclusion is **MORE EVIDENCE REQUIRED**.

## Protocol (pre-registered)

Both frontends must consume the same immutable snapshot, Java 21 runtime assumption, source roots, ordered classpath manifest, ground-truth denominator, relation names, normalized identity convention, and M1 mapping/provenance requirements. A frontend is not its own oracle.

| Configuration | Inputs | Purpose |
|---|---|---|
| A | source + JDK | Missing project dependencies |
| B | source + exact compile classpath | Best static environment |
| C | source + deliberately incomplete compile classpath | Controlled degradation |

Correctness is reported separately from attempted coverage. Each labeled case records category, caller, target/candidate set, origin, state, file, complete span, and source text. Outcomes are `CORRECT`, `INCORRECT`, `UNRESOLVED`, `AMBIGUOUS`, `OMITTED`, `UNSUPPORTED`, or `ERROR`.

The independent semantic oracle must be `javac` compiler bindings (and Eclipse JDT bindings where setup is practical), supplemented by source review. Neither adapter validates itself.

## Implemented preliminary corpus and adapters

`benchmarks/semantic-frontend-evaluation/` is intentionally separate from production M2 code. It contains:

- a Java 21 microfixture with a sealed interface, nested record/class, annotation, generic JDK call, external and optional dependency calls, lambda, method reference, constructor call, field update, return and throws declaration;
- two fixture JARs built by the JDK compiler to produce exactly the same controlled B and C classpaths for both frontends;
- parser-neutral `Observation` records and experimental JavaParser/SymbolSolver and OpenRewrite LST adapters;
- an M1 adapter test that proves an unresolved fact maps to the contract's explicit `UNRESOLVED` target/state rather than an invented entity;
- one parity test for a JDK overload and complete source span; and
- machine-readable raw output from a single cold-process controlled run.

The adapters currently extract **method calls only**. Other listed semantic categories are deliberately not represented as success; they remain unimplemented in this experimental slice. The OpenRewrite adapter reconstructs a span by unique lossless text matching because the LST visitor API used here does not expose a native per-node source range. That is an adapter limitation to be tested, not evidence that exact provenance is impossible.

## Preliminary raw results

Raw output: [`controlled-microfixture-results.json`](../../benchmarks/semantic-frontend-evaluation/results/controlled-microfixture-results.json). Environment: Oracle JDK 21.0.12.1, Windows; JavaParser 3.26.1; OpenRewrite 8.87.7 plus its required Java-21 parser artifact.

For seven extracted call occurrences:

| Config | JavaParser resolved | OpenRewrite resolved | Honest missing dependency outcome |
|---|---:|---:|---|
| A | 3/7 | 4/7 | Both report unresolved external calls; neither guessed a target |
| B | 6/7 | 7/7 | Both resolve supplied external dependencies; JavaParser did not resolve chained generic `values.get(0).trim()` |
| C | 5/7 | 6/7 | Both retain the supplied dependency and report the removed optional target unresolved |

The preliminary B difference is independently inspectable in source: `List<String>.get(0)` has static type `String`, so the `trim` target is expected to resolve. This needs confirmation with javac output before being counted as a final correctness result.

No performance or memory conclusion follows: this is one machine, one cold run, a one-file fixture, no controlled process isolation, and no peak-memory sampler. The observed elapsed times are retained in raw data only.

## Continuation evidence: independent compiler oracle and PetClinic call parity

`JavacOracle` uses the JDK 21 compiler API (`JavacTask`, `Trees`, and compiler elements) rather than either candidate. Its executable test confirms that `values.get(0).trim()` has compiler target `java.lang.String.trim()`. In the original modern microfixture Config B, OpenRewrite emits that target while the JavaParser adapter emits an `UnsolvedSymbolException` for the chained call. This is therefore not a target-normalization or manually labelled-ground-truth error. It is nevertheless one case only; it does not establish a general correctness ranking.

The pinned R1 PetClinic snapshot (`818c4136ea971c21674525f9053de0d9c7ad8cfe`) was re-cloned and given one generated 89-JAR compile-classpath manifest. The current shared denominator remains **method calls only** (220 occurrences); results are therefore comparable to each other but not a replacement for the full M2 denominator.

| Configuration | JavaParser semantic result | OpenRewrite semantic result | OpenRewrite exact-span reconstruction failures |
|---|---:|---:|---:|
| A source + JDK | 116 resolved / 104 unresolved | 124 resolved / 96 unresolved | 57 |
| B exact 89-JAR compile classpath | 220 resolved / 0 unresolved | 220 resolved / 0 unresolved | 89 |
| C classpath with `spring-web` deliberately removed | 215 resolved / 5 unresolved | 215 resolved / 5 unresolved | 87 |

Raw result: [`petclinic-preliminary-call-results.json`](../../benchmarks/semantic-frontend-evaluation/results/petclinic-preliminary-call-results.json). The initially reported OpenRewrite Config-B 131/220 figure was an **adapter accounting error**: correctly attributed calls were incorrectly counted as unresolved when repeated or multiline printed text could not be uniquely located. A regression test now keeps semantic state separate from that provenance failure. The corrected semantic result is parity for this call-only PetClinic denominator.

This result changes the claims only as follows: Claim C remains unsupported (the two tools have equivalent C behavior on this denominator); Claim D remains unsupported (these single in-process elapsed times are not a resource benchmark); and Claim B remains unsupported because method calls alone cannot decide M2. Exact source provenance is presently a material implementation advantage for JavaParser, but OpenRewrite must first be tested for a direct position API or another reliable offset strategy before it is scored as an intrinsic technology limitation.

## M1 contract fit

Both adapters can map a resolved target or unresolved stable reference into M1 without parser classes escaping. JavaParser supplies node ranges directly. The present OpenRewrite adapter needs a separate deterministic span-reconstruction layer and must normalize its source paths; its method signature string also requires canonical normalization before it can be an M1 entity name. These are concrete adaptation tasks, not reasons to alter M1: M1's explicit resolved/candidate/unresolved target model naturally accepts both frontend outcomes.

## Future transformation implications

If JavaParser stays for SE121, later transformation remains: canonical identity/evidence (snapshot, path, span, canonical signature) → parse the target snapshot with OpenRewrite → locate/revalidate the selected LST → apply a recipe. This mapping is necessary even with one library because transformations run against a later snapshot and need formatting, recipe context, and fresh attribution.

If OpenRewrite becomes the analysis frontend, its LST/type objects still cannot become persistent canonical facts: they are snapshot- and parser-version-specific, while M1 identity/evidence must be deterministic and storage-neutral. A shared library may reduce adapter familiarity, but it does not eliminate transformation-time parsing, re-attribution, target re-location, or recipe validation.

## Hostile-claim verdicts

| Claim | Verdict | Current evidence |
|---|---|---|
| A. Two frontends are unnecessary duplication | **PARTLY TRUE** | Two adapters create maintenance work, but canonical-to-LST re-location remains for later snapshots even with OpenRewrite. Material cost not measured. |
| B. OpenRewrite should replace JavaParser now because it has attribution | **UNSUPPORTED** | The small B slice favors OpenRewrite for one chained generic call; corpus, oracle, provenance, and multi-module evidence are missing. |
| C. JavaParser is more tolerant of incomplete classpaths | **UNSUPPORTED** | Both frontends visibly returned unresolved external calls in A/C in the preliminary slice. |
| D. JavaParser is significantly lighter/faster | **UNSUPPORTED** | No fair repeated isolated performance/memory benchmark exists. |
| E. One OpenRewrite stack significantly reduces transformation complexity | **PARTLY TRUE** | It may remove one library boundary, but it does not eliminate target-snapshot parsing or canonical identity-to-LST re-location. |

## Required completion evidence before a decision

1. Add the full labeled denominator: declarations, relationships, generic bounds/parameterized types, constructors, fields reads/writes, type uses, annotations, method references, lambdas, records, sealed permits, nested/local/anonymous cases, collision/overload/inheritance cases, ambiguity and unsupported cases.
2. Implement both adapters against that denominator and normalize every identity/signature/path/span into M1.
3. Execute javac binding oracle checks and manually review labels; add Eclipse JDT where feasible.
4. Re-run A/B/C on the pinned PetClinic snapshot using one generated exact classpath manifest for both tools, and add a compact modern/multi-module fixture if it exposes a difference.
5. Run repeated cold and warm isolated JVM measurements with fixed heap, process peak-memory capture, phase timings, classpath manifests, source hashes, file/LOC counts, and output fact counts.
6. Test duplicate textual calls and multiline constructs to establish whether OpenRewrite provenance reconstruction is reliable or needs a different position strategy.

## Decision and approval follow-up

**Recommendation: MORE EVIDENCE REQUIRED. Epistemic status: OPEN QUESTION.** The only comparative implementation result is too narrow to support either a migration or a renewed JavaParser endorsement.

If human evidence later approves **KEEP JAVAPARSER**, update ADR-001 with the comparative protocol/raw-results references, retain OpenRewrite only as a future transformation dependency, and record explicit M2 acceptance thresholds. If it approves **SWITCH TO OPENREWRITE**, supersede ADR-001, add the OpenRewrite adapter dependency/ownership boundary, define a verified source-span strategy, and re-baseline M2 contracts/tests. Neither change is authorized by this preliminary package.

## Threats to validity

The result is a one-file microfixture, not PetClinic; it has one relationship extractor, no ground-truth JSON yet, no javac/JDT executable oracle output, no multi-module fixture, one Windows/JDK environment, no repeated process-isolated timing, no peak-memory measurement, and an intentionally provisional OpenRewrite span strategy. It must not be used to claim semantic superiority, general incomplete-classpath behavior, or performance.
