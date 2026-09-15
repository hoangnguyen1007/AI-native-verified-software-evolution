# M4C.1 — Registration Plan and Discovery Transitions

Date: 2026-09-15. Implements the discovery boundary under [M4-R0](m4-r0-semantics-gate.md), [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md), and [M4B.2](m4b2-evidence-lowering-exogenous-evaluation.md). Package: `com.evolution.analysis.spring.registration`, neutral `analyzer` module.

## Delivered boundary

**CONFIRMED by implementation and focused verification:** immutable producer/candidate/event contracts, an evidence-normalized registration plan, bounded candidate discovery transitions, and bounded Boot 3.4.0 auto-configuration metadata ordering. The providers are passive; they neither load target classes nor execute target containers, build lifecycles, selectors, registrars, or processors.

The input boundary is explicit. A supplying provider must establish bootstrap/container scope, exact declaration evidence, scan/import membership, complete condition attachments, and effective ordering metadata. M4A annotation inventory alone cannot establish these facts. `RegistrationPlan.create` consumes the inventory, M4B lowering, and those normalized event/precedence inputs; it retains missing evidence as gaps. This slice does not implement a general scan-filter interpreter, automatic import-selector execution, dependency metadata acquisition, or a complete source-to-container bootstrap adapter.

`DiscoveryTransitions.evaluate` additionally consumes the M4B condition model, one configuration assignment, and deterministic evaluation limits. Discovery is separate from definition registration, activation, type compatibility, injection selection, and runtime instantiation. Candidate registration stays `NOT_EVALUATED`, except definitions explicitly supplied as initial input retain `SUPPLIED_INITIAL_DEFINITION`. Initial observations are not claims about a final registry after arbitrary mutation.

## Components and ownership

| Component | Responsibility |
|---|---|
| `BeanProducer` | Context/container-scoped declaration evidence, producer kind, declaration slot, discovery path |
| `BeanDefinitionCandidate` | Producer-owned candidate, exact or unresolved names, aliases and evidenced exposed type identities |
| `RegistrationEvent` | Invocation identity/path, kind, phase, actual condition call site, parent gate, conditions, membership/metadata completeness, inventory references |
| `RegistrationPlan` | Immutable normalized inputs, evidence validation, closed inventory partition, typed precedence and uniquely established order prefix |
| `DiscoveryTransitions` | Per-event outcomes, condition invocation/defer records, candidate histories, state/transition identities and gaps |
| `AutoConfigurationOrdering` | Ordering selected auto-configurations from supplied effective Boot 3.4.0 metadata, including available metadata-only intermediate classes |
| `RegistrationProcessing` | Additive `evidence.spring-registration-gaps:m4c.1-v1` catalog using unchanged M3 `CapabilityGapRecord` |

## Phase semantics

Phases are invocation domains, not a global integer sort. Configuration parsing can nest scans and direct imports. Deferred import selection has its own ordering domain; registry callbacks and definition reading are represented separately.

| Site | M4C.1 behavior |
|---|---|
| Configuration/direct/deferred/auto-configuration discovery | Evaluate applicable parse conditions; an ordinary condition is applicable at the parse site, while register-only conditions are deferred |
| Plain scanned component | Evaluate applicable exogenous conditions at its supplied `REGISTER_BEAN` condition site; stateful bean conditions remain unknown |
| Scanned configuration candidate | Supplying provider specifies the parse condition site explicitly |
| `@Bean` method discovery | Occurs during configuration parsing; method conditions are retained for later definition reading, never used to suppress discovery prematurely |
| Component scan driver | Parent configuration gates discovery; Spring 6.2.0's register-phase-condition prohibition is checked separately |
| Unknown selector/registrar/registry processor/environment mutation/XML reader | Retain the invocation and typed gap; do not invent child definitions or treat the callback as an inert no-op |

Known profile/property condition families cannot be relabeled as register-only. Known bean-state condition families cannot be relabeled ordinary. Unknown phase evidence remains unknown. Ordinary `@Order`, injection priority, lifecycle phase and `@DependsOn` are not synthesized into registration precedence.

A false parse guard suppresses its descendants without invoking their conditions or callbacks. An unknown guard leaves its descendants unknown. Invoked opaque conditions have no established purity guarantee, so their possible environment effects also qualify later condition evaluations; callbacks skipped by a false parent do not introduce that taint. Multiple discovery paths retain separate event histories; paths may reference one canonical candidate without manufacturing duplicate final beans. Actual direct-import cycles have an error outcome. Subsequent events are `NOT_REACHED` without fabricated execution transitions.

Spring 6.2.0 collects component-scan restrictions from the scan owner and its actual enclosing configuration. An arbitrary importer is not an enclosing configuration. The normalized scan site must carry any relevant enclosing-condition evidence. Earlier accepted framework tuples retain an explicit gap for this version-sensitive scan restriction.

## Ordering

Precedence edges name their domain and carry evidence. Foreign endpoints are invalid API inputs. Domain-invalid or source-mismatched edges remain in the input/evidence record but cannot establish execution order. Parent invocation paths are validated and contribute causal discovery edges.

The planner computes only a uniquely evidenced prefix. At an ambiguous frontier it stops claiming an execution sequence. Lexical traversal is used internally only to detect cycles; it is never exported as a legal Spring order. Cycles, missing order and exhausted budgets have distinct outcomes. M4C.1 does not enumerate hypothetical orders or turn epistemic uncertainty into `MAY`.

Boot 3.4.0 ordering starts with alphabetical order, applies `AutoConfigureOrder`, then traverses before/after dependencies using Boot's pending-list priority. A generic priority-based topological sort is not substituted. Effective metadata must already include selection, exclusions, filters and replacement handling; unavailable external targets need an explicit `ABSENT` observation, while missing or unknown evidence withholds the order. Metadata-only classes can constrain selected classes without appearing as selected outputs.

**Audit hardening (2026-09-15):** the predecessor comparator adds a natural-order secondary key when pending-list positions tie. This preserves all distinct predecessors instead of collapsing the `-1` group in a `TreeSet`. Pending predecessors keep Boot's priority; already completed predecessors do not change the selected order. Cycle checks retain every constraint. This is a deliberate conservative refinement of the inspected source, not a claim of byte-for-byte runtime-algorithm equivalence on malformed graphs.

## Evidence, identity and closure

The implementation uses the accepted `m4-r0-identity-v1` envelope for producer, candidate, registration event, registration plan, semantics context, definition state and transition identities. Existing M1/M3/M4B identities remain unchanged. The plan binds exact build/framework/inventory/lowering inputs, event descriptors, precedence, initial definitions, container, override policy and limits. The later semantics context binds configuration space, plan, condition/registration semantics and evaluation budgets. Assignment identity qualifies transitions separately; there is no plan/context/result digest cycle.

Equal condition content reconstructed into different Java objects is matched by occurrence identity. Unicode is preserved without normalization. Candidate names are container-scoped; unresolved names never receive guessed defaults. Types remain supplied evidence, with missing type information explicitly recorded. Alias resolution and overriding are not performed; the policy and declarations remain inputs for the next slice.

Every M4A obligation belongs to exactly one plan row: `PLANNED`, `CONDITION_RETAINED`, `NOT_DISCOVERY`, or `GAP`. Unattached conditions and unmodeled discovery mechanisms receive gaps with their source evidence. Historical M4A gaps, lowering gaps and evaluation gaps remain available; later discovery does not silently rewrite them as resolved.

Every input event receives exactly one outcome: `DISCOVERED`, `SKIPPED`, `UNKNOWN`, `ERROR`, or `NOT_REACHED`. Resource exhaustion retains all input events and candidate metadata. State snapshots describe the evidenced traversal prefix; result candidate summaries additionally account for the unresolved remainder. `discoveryClosed` is closure of the supplied discovery plan, not complete application/container knowledge. Registration and aliases remain explicitly unevaluated.

## Bounds and safety

Defaults are 10,000 events, 50,000 precedence edges, nesting depth 256, 10,000 transitions and 100,000 cumulative state/prefix cells. Auto-configuration ordering defaults to 10,000 metadata classes and 100,000 steps. M4B's separate expression/environment limits remain in force. Budgets participate in identity; they are defensive bounds, not measured scale claims. Traversal uses explicit queues/stacks. No host environment/configuration discovery, filesystem acquisition, network access or target execution occurs in these providers.

## Source grounding and verification

The existing R0 cached S11/S12/S13/S24/S25/S26 sources were verified against `sources-acquired.lock.json` before inspection. Relevant primary sources are [ConditionEvaluator 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-context/src/main/java/org/springframework/context/annotation/ConditionEvaluator.java), [ConfigurationClassParser 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassParser.java), [ConfigurationClassBeanDefinitionReader 6.2.0](https://github.com/spring-projects/spring-framework/blob/v6.2.0/spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassBeanDefinitionReader.java), and [AutoConfigurationSorter 3.4.0](https://github.com/spring-projects/spring-boot/blob/v3.4.0/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationSorter.java).

Tests use compact authored, evidence-normalized inputs through the real M4A/M4B contracts; they do not independently prove extraction of scan/import membership or runtime-container equivalence. Accepted R0 constraints are preserved: no final-state evaluation of missing-bean conditions, no arbitrary legal-order claim, and no universal configuration promotion. See the [verification record](../reproducibility/m4c1-discovery-2026-09-15/README.md). G3, runtime/corpus validation, registration activation and binding remain pending.

Exact next slice: **M4C.2 — Ordered Bean-Definition Registration and Endogenous Condition Evaluation**. Consume this plan/discovery evidence, establish register-phase guards against definitions processed so far, and resolve definition-name/alias/override history under the explicit policy. Preserve opaque mutation and order gaps; do not advance G3 or injection binding implicitly.
