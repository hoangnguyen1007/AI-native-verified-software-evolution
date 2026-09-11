# M4-R0 preregistered experiment protocol

Registered 2026-09-11 before fixture execution. Status: PROVISIONAL research protocol, not production semantics approval.

## Question and authority
Can a finite staged model preserve exact conditional outcomes and expose version/order changes without promoting unknown evidence? The framework is a behavioral comparator for controlled fixtures; manually specified labels are written before execution. Neither the experimental evaluator nor this author's labels constitute independent human adjudication.

## Experiments
1. Execute only authored passive object-container fixtures on Framework/Boot pairs 5.3.31/2.7.18, 6.1.14/3.3.5 and 6.2.0/3.4.0, with explicit exact classpaths from artifacts.lock.json. Compile with javac, -proc:none, -parameters, --release 17. No target builds, external applications, server, database, annotation processors, target scans, custom loaders, shell callbacks, environment values or remote XML entities.
2. Exhaustively enumerate the finite IR cases in cases.json; compare with labels stored before evaluator execution. Persist every outcome including failures. The IR is manually translated, not an extraction test.
3. Compare a naive flat registration baseline with staged evaluation on the same order fixtures. Do not characterize this intentionally limited baseline as every flat analysis.
4. Compare independent exhaustive satisfiability and a small DPLL research implementation on explicitly generated CNF formula families, sizes 2, 4, 8, 12 variables. This is Boolean-engine conformance, not a Spring SAT encoding or a benchmark of a Java library.
5. Verify deterministic semantic bytes by two runs; timings and host runtime provenance are separate. Use fixed Python hash seeds in replay. Baseline result labels and oracle expectations never derive from measured candidate output.

## Closed denominators
All registered fixture IDs and framework variants are counted: pass, disagree, unsupported/not-run, compile failure, runtime failure, timeout. Mechanism catalog rows are separately counted; one passing condition test cannot establish all 29 mechanism rows. Runtime facts are observations of these fixtures, not universal source analysis.

The first formal oracle includes Boolean truth tables, property values/missing, set-valued profiles, ordered bean presence/absence, binding ambiguity/primary/qualifier/name/version behavior, three-region classifications and limit/opaque controls. Unmodeled forms retain gap expectations.

## Limits and stopping conditions
At most 12 Boolean variables / 4096 valuations, 32 transitions, 64 legal orders, 256 symbolic branches and 100000 evaluator steps per fixture. These are conservative experimental budgets, not measured repository distributions. Stop an individual compile/runtime process at 30 seconds; jar acquisition is <=40 MB total / <=8 MB each / 15 seconds per request, exact HTTPS Maven Central only, no redirects/credentials/transitive execution. Source retrieval <=20 MB / <=4 MB each / 15 seconds. No hidden retries.
A semantic limit yields UNKNOWN with its operational reason. Wall-time failure invalidates complete-result claims and is not an identity-dependent semantic prefix; deterministic step/branch budgets determine repeatable semantic limits.

## Oracle and witness acceptance
No mismatch on registered hand labels and controlled framework observations may be waived silently. Keep disagreements, repair a supported specification/runner defect with a recorded deviation, then create a separately identified run. A true and false witness are required to claim variation. Replay fixes build, complete assignment and material legal order. Minimality means subset-minimal relative to a fixed baseline, not minimum cardinality unless exhaustively proved.

## Comparators and corpus before M4E
Java-static, one declared realized configuration, flat conditions, and staged semantics use the same inputs/queries. ArchUnit and Spring Modulith apply only to equivalent static/module rules with artifact pins, JDK/classpath and explicit conversion losses. Jasmine requires a reproduced author artifact; otherwise report NOT_RUN, not zero precision.
Use the existing pinned PetClinic and ChatServer inputs as candidate strata only. Add an XML-era application, a plain annotation/JavaConfig application and a Boot 2.4+ config-data/auto-configuration application through a frozen selection manifest. Require immutable commit/license/source and exact build/evidence, no target execution, declared exclusions and an UNKNOWN denominator. Do not invent repository pins or run real repositories in R0.
Historical pairs belong to Track B after its gates.

## Metrics and kill criteria
Report per-version/per-mechanism candidate recall and selected-binding precision with TP/FP/FN counts; include unresolved/unmapped/error independently. Report T/F/U confusion, false certainty (definite claim contradicted or unproved over the modeled space), false unconditional warnings, feasible-world region error, witness validity, subset minimality and identical-byte replay.
Zero admissible worlds is INVALID_MODEL, never MUST/NEVER. Any false universal claim, omitted observation, provenance mismatch, invalid witness or divergent digest blocks promotion of that fragment. Solver replacement requires conformance first and repeated measured improvement on registered spaces; timings here select no production backend.
