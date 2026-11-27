package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.JavaType;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import java.util.*;
import static com.evolution.analysis.spring.condition.LogicalValue.TRUE;
import static com.evolution.analysis.spring.condition.LogicalValue.FALSE;
import static com.evolution.analysis.spring.registration.RegistrationEvent.Completeness.*;

/**
 * Source acquisition for components, ordinary bean methods and evidenced
 * dependency shapes. The caller supplies
 * evidenced container/order closure, never final definitions, dependency
 * descriptors or matches.
 * An unproved scope stays open. This is not an application bootstrap detector.
 */
public final class SourceToSpringPlan {
        public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.source-to-plan",
                        "m8.4-local-metadata-closure-v2");

        /**
         * Evidence that the standard Framework 6.2.0 CommonAnnotationBeanPostProcessor
         * is
         * enabled for this container with default type fallback, local resource
         * factory,
         * no ignored resource types, no JNDI override and no later processor mutation.
         */
        public record ResourcePolicy(ConditionEvidence evidence) {
                public ResourcePolicy {
                        Objects.requireNonNull(evidence);
                }
        }

        public record Scope(String container, ContentDigest sourceEvidence, List<EntityIdentity> registrationOrder,
                        RegistrationEvent.Completeness order, RegistrationEvent.Completeness registry,
                        RegistrationEvent.Completeness noParent,
                        RegistrationEvent.Completeness noResolvableDependencies,
                        RegistrationEvent.Completeness noPostRegistrationMutation,
                        RegistrationPlan.OverridePolicy overrides, ConditionEvidence evidence,
                        Optional<ResourcePolicy> resourcePolicy) {
                public Scope {
                        ContractChecks.text(container, "container");
                        Objects.requireNonNull(sourceEvidence);
                        registrationOrder = ContractChecks.distinctInOrder(registrationOrder,
                                        "source registration order");
                        Objects.requireNonNull(order);
                        Objects.requireNonNull(registry);
                        Objects.requireNonNull(noParent);
                        Objects.requireNonNull(noResolvableDependencies);
                        Objects.requireNonNull(noPostRegistrationMutation);
                        Objects.requireNonNull(overrides);
                        Objects.requireNonNull(evidence);
                        Objects.requireNonNull(resourcePolicy);
                }

                public Scope(String container, ContentDigest sourceEvidence, List<EntityIdentity> registrationOrder,
                                RegistrationEvent.Completeness order, RegistrationEvent.Completeness registry,
                                RegistrationEvent.Completeness noParent,
                                RegistrationEvent.Completeness noResolvableDependencies,
                                RegistrationEvent.Completeness noPostRegistrationMutation,
                                RegistrationPlan.OverridePolicy overrides, ConditionEvidence evidence) {
                        this(container, sourceEvidence, registrationOrder, order, registry, noParent,
                                        noResolvableDependencies, noPostRegistrationMutation,
                                        overrides, evidence, Optional.empty());
                }
        }

        public record Result(ContentDigest inputIdentity, SpringMechanismInventory inventory,
                        ConditionEvidenceLowering.Result conditions, InjectionBindingPlan binding,
                        List<CapabilityGapRecord> gaps, List<SourceMethodSelection.Decision> methodSelections) {
                public Result {
                        gaps = com.evolution.analysis.ingestion.AnalysisWorkspace.ReplayList.copy(gaps);
                        methodSelections = List.copyOf(methodSelections);
                }

                public Result(ContentDigest inputIdentity, SpringMechanismInventory inventory,
                                ConditionEvidenceLowering.Result conditions,
                                InjectionBindingPlan binding, List<CapabilityGapRecord> gaps) {
                        this(inputIdentity, inventory, conditions, binding, gaps, List.of());
                }

                public ContentDigest identity() {
                        return memoizedIdentity(this, () -> IngestionEvidence.digest(List.of(inputIdentity,
                                        inventory.identity(), conditions.identity(), binding.identity(), gaps,
                                        methodSelections)));
                }

                public Evaluation evaluate(ConfigurationSpace space, ConfigurationAssignment baseline,
                                TruthRegionEvaluation.Limits limits, ContentDigest evaluatorArtifact) {
                        if (!space.buildContext().identity()
                                        .equals(binding.registrationPlan().discoveryPlan().buildContext().identity()))
                                throw new IllegalArgumentException(
                                                "Configuration space belongs to another source context");
                        return new Evaluation(this,
                                        TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(binding,
                                                        ConditionModel.create(space, conditions.occurrences()),
                                                        conditions.semantics(), baseline,
                                                        ExhaustiveConfigurationReasoner.INSTANCE, limits,
                                                        evaluatorArtifact)));
                }

                public SymbolicEvaluation evaluateSymbolic(ConfigurationSpace space, ConfigurationAssignment baseline,
                                TruthRegionEvaluation.Limits limits, ContentDigest evaluatorArtifact, int maxSignatures,
                                SymbolicConfiguration.Limits symbolicLimits) {
                        if (!space.buildContext().identity()
                                        .equals(binding.registrationPlan().discoveryPlan().buildContext().identity()))
                                throw new IllegalArgumentException(
                                                "Configuration space belongs to another source context");
                        return new SymbolicEvaluation(this,
                                        TruthRegionEvaluation.evaluateSymbolic(
                                                        new TruthRegionEvaluation.Request(binding,
                                                                        ConditionModel.create(space,
                                                                                        conditions.occurrences()),
                                                                        conditions.semantics(), baseline,
                                                                        SatConfigurationReasoner.INSTANCE, limits,
                                                                        evaluatorArtifact),
                                                        maxSignatures, symbolicLimits));
                }
        }

        /**
         * Retain acquisition and downstream denominators together; evaluating a
         * qualified plan
         * cannot erase a provider gap merely because it is outside the evaluator's own
         * catalog.
         */
        public record Evaluation(Result acquisition, TruthRegionEvaluation.Result truth) {
                public Evaluation {
                        Objects.requireNonNull(acquisition);
                        Objects.requireNonNull(truth);
                }

                public List<CapabilityGapRecord> capabilityGaps() {
                        return combined(acquisition.gaps(), truth.capabilityGaps());
                }

                public ContentDigest identity() {
                        return memoizedIdentity(this, () -> IngestionEvidence
                                        .digest(List.of(acquisition.identity(), truth.identity(), capabilityGaps())));
                }
        }

        public record SymbolicEvaluation(Result acquisition, SymbolicTruthRegions.StagedResult truth) {
                public SymbolicEvaluation {
                        Objects.requireNonNull(acquisition);
                        Objects.requireNonNull(truth);
                }

                public List<CapabilityGapRecord> capabilityGaps() {
                        return combined(acquisition.gaps(), truth.gaps());
                }

                public ContentDigest identity() {
                        return memoizedIdentity(this, () -> IngestionEvidence
                                        .digest(List.of(acquisition.identity(), truth.identity(), capabilityGaps())));
                }
        }

        private static final int IDENTITY_CACHE_SLOTS = 64;
        private static final java.lang.ref.WeakReference<?>[] IDENTITY_KEYS = new java.lang.ref.WeakReference<?>[IDENTITY_CACHE_SLOTS];
        private static final ContentDigest[] IDENTITY_VALUES = new ContentDigest[IDENTITY_CACHE_SLOTS];
        private static final Object IDENTITY_LOCK = new Object();

        private static ContentDigest memoizedIdentity(Object key, java.util.function.Supplier<ContentDigest> compute) {
                int slot = (System.identityHashCode(key) & 0x7fffffff) % IDENTITY_CACHE_SLOTS;
                synchronized (IDENTITY_LOCK) {
                        var ref = IDENTITY_KEYS[slot];
                        if (ref != null && ref.get() == key) {
                                return IDENTITY_VALUES[slot];
                        }
                }
                ContentDigest digest = compute.get();
                synchronized (IDENTITY_LOCK) {
                        IDENTITY_KEYS[slot] = new java.lang.ref.WeakReference<>(key);
                        IDENTITY_VALUES[slot] = digest;
                }
                return digest;
        }

        private static List<CapabilityGapRecord> combined(List<CapabilityGapRecord> acquired,
                        List<CapabilityGapRecord> evaluated) {
                var result = new TreeSet<>(acquired);
                result.addAll(evaluated);
                return List.copyOf(result);
        }

        private static final Set<String> COMPONENT_METADATA = Set.of("org.springframework.stereotype.Component",
                        "org.springframework.stereotype.Service", "org.springframework.stereotype.Repository",
                        "org.springframework.stereotype.Controller", "org.springframework.context.annotation.Profile",
                        "org.springframework.context.annotation.Primary",
                        "org.springframework.context.annotation.Fallback",
                        "org.springframework.beans.factory.annotation.Qualifier",
                        "org.springframework.context.annotation.Configuration");
        private static final Set<String> COMPAT_COMPONENT_METADATA = Set.of("org.springframework.stereotype.Component",
                        "org.springframework.stereotype.Service", "org.springframework.stereotype.Repository",
                        "org.springframework.stereotype.Controller",
                        "org.springframework.web.bind.annotation.RestController",
                        "org.springframework.context.annotation.Profile",
                        "org.springframework.context.annotation.Primary",
                        "org.springframework.context.annotation.Fallback",
                        "org.springframework.beans.factory.annotation.Qualifier",
                        "org.springframework.context.annotation.Configuration",
                        "org.springframework.boot.autoconfigure.SpringBootApplication",
                        "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                        "org.springframework.context.annotation.ComponentScan",
                        "org.springframework.core.annotation.Order",
                        "org.springframework.context.annotation.Lazy", "org.springframework.context.annotation.Scope",
                        "org.springframework.context.annotation.DependsOn",
                        "org.springframework.context.annotation.Import",
                        "org.springframework.context.annotation.ImportResource",
                        "org.springframework.boot.context.properties.ConfigurationProperties",
                        "org.springframework.boot.context.properties.EnableConfigurationProperties",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnExpression",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnResource",
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnJava");
        private static final Set<String> SITE_METADATA = Set.of(
                        "org.springframework.beans.factory.annotation.Autowired",
                        "org.springframework.beans.factory.annotation.Qualifier", "jakarta.inject.Inject",
                        "jakarta.inject.Named",
                        "javax.inject.Inject", "javax.inject.Named");

        public static Result normalize(SpringBuildContext build, SpringSourceEvidence source,
                        ComponentScanIngestion.Result components, ConstructorInjectionIngestion.Result constructors,
                        Scope scope, int limit) {
                return normalize(build, source, components, constructors, scope, limit,
                                com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
        }

        public static Result normalize(SpringBuildContext build, SpringSourceEvidence source,
                        ComponentScanIngestion.Result components, ConstructorInjectionIngestion.Result constructors,
                        Scope scope, int limit,
                        com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode validationMode) {
                return normalizeInternal(build, source, components, constructors, Objects.requireNonNull(scope), null,
                                null, validationMode, limit);
        }

        public static Result normalizeAutoScoped(SpringBuildContext build, SpringSourceEvidence source,
                        ComponentScanIngestion.Result components, ConstructorInjectionIngestion.Result constructors,
                        String container, ConditionEvidence evidence,
                        com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode validationMode,
                        int limit) {
                return normalizeInternal(build, source, components, constructors, null,
                                ContractChecks.text(container, "container"),
                                Objects.requireNonNull(evidence), Objects.requireNonNull(validationMode), limit);
        }

        private static Result normalizeInternal(SpringBuildContext build, SpringSourceEvidence source,
                        ComponentScanIngestion.Result components, ConstructorInjectionIngestion.Result constructors,
                        Scope explicitScope, String autoContainer, ConditionEvidence autoEvidence,
                        com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode validationMode,
                        int limit) {
                if (limit < 1 || limit > 50_000)
                        throw new IllegalArgumentException("Plan acquisition limit must be in [1,50000]");
                long squareBudget = Math.min(100_000_000L, (long) limit * limit);
                int intSquareBudget = (int) squareBudget;
                boolean autoScoped = explicitScope == null;
                if ((!autoScoped && !explicitScope.sourceEvidence().equals(source.identity()))
                                || !build.analysisIdentity().equals(source.frontend().analysis())
                                || !components.frontendEvidence().equals(IngestionEvidence.digest(source.frontend()))
                                || !components.frameworkEvidence().equals(source.framework().identity()))
                        throw new IllegalArgumentException("Foreign source-to-plan evidence");
                ConditionEvidence checkEvidence = autoScoped ? autoEvidence : explicitScope.evidence();
                if (checkEvidence instanceof ConditionEvidence.Source evidence
                                && (!build.containsSource(evidence) || evidence.span().isEmpty())
                                || checkEvidence instanceof ConditionEvidence.Artifact artifact
                                                && !build.containsArtifact(artifact.artifactDigest()))
                        throw new IllegalArgumentException("Foreign scope proof");
                if (!autoScoped)
                        explicitScope.resourcePolicy().ifPresent(policy -> {
                                if (policy.evidence() instanceof ConditionEvidence.Source evidence
                                                && (!build.containsSource(evidence) || evidence.span().isEmpty())
                                                || policy.evidence() instanceof ConditionEvidence.Artifact artifact
                                                                && !build.containsArtifact(artifact.artifactDigest()))
                                        throw new IllegalArgumentException("Foreign resource processor proof");
                        });
                var inventory = SpringMechanismScanner.scan(new SpringMechanismScanRequest(source.frontend(),
                                source.framework(), List.of(), List.of()));
                var buildObservations = deriveBuildObservations(build, source, inventory, validationMode);
                var lowering = ConditionEvidenceLowering.lower(build, inventory, buildObservations,
                                ConditionEvidenceLowering.Limits.conservative(), validationMode);
                var generation = FrameworkGeneration.from(source.framework(), Optional.empty(), validationMode,
                                source.observedNamespaceHint());
                var sites = SpringInjectionSites.acquire(source, constructors, generation, limit);
                var containerKey = autoScoped ? autoContainer : explicitScope.container();
                var candidates = components.candidates(build, containerKey);
                var byDeclaration = new TreeMap<EntityIdentity, BeanDefinitionCandidate>();
                for (var candidate : candidates)
                        byDeclaration.put(candidate.exposedTypes().getFirst(), candidate);
                var acquisitionIssues = new ArrayList<UniversalSpringEvidence.Issue>();
                var componentHierarchies = new TreeMap<EntityIdentity, SpringSourceEvidence.SourceHierarchy>();
                var componentMetadata = new TreeMap<EntityIdentity, Boolean>();
                var membersByType = new TreeMap<EntityIdentity, List<BeanDefinitionCandidate>>();
                for (var entry : byDeclaration.entrySet()) {
                        var hierarchy = source.sourceHierarchy(entry.getKey(), Math.min(limit, 64));
                        componentHierarchies.put(entry.getKey(), hierarchy);
                        componentMetadata.put(entry.getKey(),
                                        metadataComplete(source, entry.getKey(), hierarchy, validationMode));
                        for (var declaring : hierarchy.types())
                                membersByType.computeIfAbsent(declaring, k -> new ArrayList<>()).add(entry.getValue());
                        // A composed/impostor member annotation can conceal an injection site which
                        // the direct-site provider cannot normalize. Keep the member footprint open.
                        for (int hIdx = 1; hIdx < hierarchy.types().size(); hIdx++) {
                                var declaring = hierarchy.types().get(hIdx);
                                for (var member : source.declaredMembers(declaring)) {
                                        if (source.annotations(member).stream().anyMatch(a -> a.name()
                                                        .filter(n -> SITE_METADATA.contains(n) || n
                                                                        .equals("jakarta.annotation.Resource"))
                                                        .isEmpty() && !source.javaOverride(a)))
                                                acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                                UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,
                                                                "inherited-member-metadata:" + member.value(),
                                                                List.of(source.evidence(member),
                                                                                source.evidence(entry.getKey()))));
                                }
                        }
                        if (!hierarchy.complete())
                                acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                switch (hierarchy.problem()) {
                                                        case LIMIT -> UniversalSpringEvidence.Reason.RESOURCE_LIMIT;
                                                        case SOURCE_SHAPE_UNSUPPORTED ->
                                                                UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED;
                                                        case CYCLE, PARENT_EVIDENCE_CONFLICT ->
                                                                UniversalSpringEvidence.Reason.EVIDENCE_CONFLICT;
                                                        default -> UniversalSpringEvidence.Reason.EVIDENCE_MISSING;
                                                },
                                                "component-source-hierarchy:" + hierarchy.problem() + ":"
                                                                + entry.getKey().value(),
                                                hierarchy.types().isEmpty() ? List.of(source.evidence(entry.getKey()))
                                                                : hierarchy.types().stream().map(source::evidence)
                                                                                .toList()));
                }
                var methods = BeanMethodIngestion.acquire(build, source, containerKey, byDeclaration,
                                acquisitionIssues);
                methods.forEach((id, method) -> byDeclaration.put(id, method.candidate()));
                Scope scope = autoScoped
                                ? new Scope(autoContainer, source.identity(),
                                                deriveSourceScopedOrder(source, byDeclaration, methods),
                                                COMPLETE, COMPLETE, COMPLETE, COMPLETE, COMPLETE,
                                                generation.overridePolicy() == FrameworkGeneration.OverridePolicy.ALLOW
                                                                ? RegistrationPlan.OverridePolicy.ALLOW
                                                                : RegistrationPlan.OverridePolicy.FORBID,
                                                autoEvidence, Optional.empty())
                                : explicitScope;
                var input = IngestionEvidence.digest(List.of(PROVIDER, build.identity(), source.identity(),
                                components.identity(), constructors.identity(), scope, limit));
                var proof = new ConditionEvidence.Derived(List.of(input, scope.evidence().identity()), PROVIDER,
                                "source-descriptors");
                var productsByType = new TreeMap<EntityIdentity, List<BeanMethodIngestion.Method>>();
                var productsByCandidate = new HashMap<BeanDefinitionCandidate.Identity, BeanMethodIngestion.Method>();
                var productMemberClosure = new HashMap<BeanDefinitionCandidate.Identity, Boolean>();
                for (var method : methods.values()) {
                        for (var type : method.candidate().exposedTypes())
                                productsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(method);
                        productsByCandidate.put(method.candidate().identity(), method);
                        boolean complete = BeanMethodIngestion.productMembersComplete(source, method);
                        productMemberClosure.put(method.candidate().identity(), complete);
                        // A wider type may hide additional runtime members even when it declares no
                        // sites.
                        if (!complete)
                                acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                UniversalSpringEvidence.Reason.EVIDENCE_MISSING,
                                                "bean-product-runtime-type:" + method.candidate().identity().value(),
                                                List.of(source.evidence(method.declaration()))));
                }
                if (!byDeclaration.keySet().containsAll(scope.registrationOrder()) || scope.order() == COMPLETE
                                && !new HashSet<>(scope.registrationOrder()).equals(byDeclaration.keySet()))
                        throw new IllegalArgumentException(
                                        "Order must reference exactly the acquired component/bean-method inventory when complete");
                var raw = new HashMap<ContentDigest, SpringMechanismInventory.RawObservation>();
                inventory.rawObservations().forEach(r -> raw.put(r.identity(), r));
                var attached = new HashMap<EntityIdentity, List<RegistrationEvent.ConditionUse>>();
                for (var row : lowering.rows())
                        row.occurrence().ifPresent(occurrence -> raw.get(row.rawObservation()).owner()
                                        .ifPresent(owner -> attached.computeIfAbsent(owner, k -> new ArrayList<>())
                                                        .add(new RegistrationEvent.ConditionUse(occurrence.identity(),
                                                                        BeanConditionLowering.predicate(inventory,
                                                                                        lowering, occurrence.identity())
                                                                                        .isPresent()
                                                                                                        ? RegistrationEvent.RequiredPhase.REGISTER_BEAN
                                                                                                        : RegistrationEvent.RequiredPhase.ORDINARY,
                                                                        occurrence.declarationEvidenceKey()))));
                // A direct, unconditional scan obligation is realized by the component
                // membership
                // evidence already acquired above. Conditional/custom scan drivers stay
                // unplanned.
                var scans = inventory.obligations().stream()
                                .filter(o -> o.primaryMechanism().equals("spring.discovery.component-scan"))
                                .filter(o -> components.issues().stream().allMatch(i -> i
                                                .reason() == com.evolution.analysis.ingestion.IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED
                                                && i.subject().equals("framework-context"))
                                                && raw.get(o.rawObservationIdentity()).owner().filter(owner -> source
                                                                .ancestry(owner, 2).size() == 1
                                                                && source.annotations(owner).stream().allMatch(a -> a
                                                                                .name()
                                                                                .filter("org.springframework.context.annotation.ComponentScan"::equals)
                                                                                .isPresent())
                                                                && !source.annotations(owner).isEmpty()).isPresent())
                                .map(SpringMechanismInventory.SemanticObligation::identity).toList();
                var events = new ArrayList<RegistrationEvent>();
                var byEvent = new HashMap<EntityIdentity, RegistrationEvent>();
                var definitions = new ArrayList<BeanRegistrationEvidence.Definition>();
                var bindingDefinitions = new ArrayList<BindingEvidence.Definition>();
                long registrationCells = byDeclaration.size() + byDeclaration.values().stream()
                                .mapToLong(c -> c.declaredNameKey().aliases().isEmpty() ? 0
                                                : 1L + c.declaredNameKey().aliases().size())
                                .sum();
                boolean bounded = registrationCells <= limit;
                // Parents are constructed before methods solely to bind event identities. The
                // supplied
                // schedule, not this construction order, drives discovery and registration.
                var entries = new ArrayList<>(byDeclaration.entrySet());
                entries.sort(Comparator.comparing(e -> methods.containsKey(e.getKey())));
                var nonConditionObligationsByOwner = new HashMap<EntityIdentity, List<ContentDigest>>();
                for (var obligation : inventory.obligations()) {
                        if (obligation.primaryMechanism().startsWith("spring.condition.")) continue;
                        var rawObs = raw.get(obligation.rawObservationIdentity());
                        if (rawObs != null) {
                                rawObs.owner().ifPresent(ownerId -> nonConditionObligationsByOwner
                                                .computeIfAbsent(ownerId, k -> new ArrayList<>())
                                                .add(obligation.identity()));
                        }
                }
                for (var entry : entries) {
                        var type = entry.getKey();
                        var candidate = entry.getValue();
                        var evidence = source.evidence(type);
                        var method = methods.get(type);
                        var parent = method == null ? null : byEvent.get(method.owner());
                        boolean metadata = bounded && (method == null ? componentMetadata.get(type)
                                        : method.complete() && componentMetadata.get(method.owner()));
                        if (!metadata)
                                acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED, type.value(),
                                                List.of(evidence)));
                        var obligations = new ArrayList<ContentDigest>(
                                        nonConditionObligationsByOwner.getOrDefault(type, List.of()));
                        obligations.addAll(scans);
                        var event = new RegistrationEvent(build.identity(), lowering.semantics(),
                                        IngestionEvidence.digest(type),
                                        parent == null ? List.of() : parent.invocationPath(),
                                        method == null ? RegistrationEvent.Phase.COMPONENT_SCAN
                                                        : RegistrationEvent.Phase.CONFIGURATION_PARSE,
                                        type.value(), scope.container(),
                                        method == null ? RegistrationEvent.Kind.COMPONENT
                                                        : RegistrationEvent.Kind.BEAN_METHOD,
                                        method == null ? RegistrationEvent.ConditionSite.REGISTER_BEAN
                                                        : RegistrationEvent.ConditionSite.DISCOVERY_ONLY,
                                        Optional.ofNullable(parent).map(RegistrationEvent::identity),
                                        Optional.of(candidate), attached.getOrDefault(type, List.of()),
                                        COMPLETE, metadata ? COMPLETE : UNKNOWN, obligations, evidence);
                        events.add(event);
                        byEvent.put(type, event);
                        var primary = metadata ? flag(source, type, "org.springframework.context.annotation.Primary")
                                        : LogicalValue.UNKNOWN;
                        var fallback = metadata ? flag(source, type, "org.springframework.context.annotation.Fallback")
                                        : LogicalValue.UNKNOWN;
                        var autowire = method == null ? TRUE : method.autowire();
                        var defaultCandidate = method == null ? TRUE : method.defaultCandidate();
                        definitions.add(new BeanRegistrationEvidence.Definition(candidate.identity(), List.of(),
                                        autowire, defaultCandidate, primary, fallback,
                                        metadata ? FALSE : LogicalValue.UNKNOWN, evidence));
                        bindingDefinitions.add(new BindingEvidence.Definition(candidate.identity(), autowire,
                                        defaultCandidate, primary, fallback,
                                        method == null || method.staticMethod() ? Optional.empty()
                                                        : Optional.of(byDeclaration.get(method.owner()).identity()),
                                        metadata ? BindingEvidence.RuntimeKind.ORDINARY
                                                        : BindingEvidence.RuntimeKind.UNKNOWN,
                                        metadata ? BindingEvidence.Rank.absent() : BindingEvidence.Rank.unknown(),
                                        BindingEvidence.Rank.unknown(), FALSE, evidence));
                }
                var precedences = new ArrayList<RegistrationPlan.Precedence>();
                for (int i = 1; i < scope.registrationOrder().size(); i++)
                        precedences.add(new RegistrationPlan.Precedence(
                                        byEvent.get(scope.registrationOrder().get(i - 1)).identity(),
                                        byEvent.get(scope.registrationOrder().get(i)).identity(),
                                        RegistrationPlan.OrderDomain.EXPLICIT_SEQUENCE, scope.evidence()));
                var registrationLimits = limit <= 1000 ? RegistrationPlan.Limits.conservative()
                                : new RegistrationPlan.Limits(Math.max(10_000, limit), Math.max(50_000, limit * 5), 256,
                                                Math.max(10_000, limit), Math.max(100_000, intSquareBudget));
                var discovery = RegistrationPlan.create(build, inventory, lowering, events, precedences,
                                new RegistrationPlan.Container(scope.container(), scope.evidence()), List.of(),
                                scope.overrides(), registrationLimits);
                var steps = new ArrayList<BeanRegistrationPlan.Step>();
                var stepOrder = new HashMap<String, List<String>>();
                var eventByIdentity = new HashMap<RegistrationEvent.Identity, RegistrationEvent>(events.size() * 2);
                for (var evItem : events) {
                        eventByIdentity.put(evItem.identity(), evItem);
                }
                for (var e : events) {
                        var gate = e.parent().map(p -> Objects.requireNonNull(eventByIdentity.get(p)).eventSlot());
                        var ordered = new ArrayList<String>();
                        var names = e.candidate().orElseThrow().declaredNameKey();
                        if (!names.aliases().isEmpty()) {
                                // The pinned @Bean reader tests the condition, registers aliases, then
                                // registers the definition. Collision-free alias calls commute; metadata
                                // collisions remain incomplete instead of inventing reader precedence.
                                String guard = "bean-alias-guard:" + e.eventSlot();
                                steps.add(new BeanRegistrationPlan.Step(guard, e.identity(),
                                                BeanRegistrationPlan.Operation.CONFIGURATION_GATE,
                                                gate, TRUE, Optional.empty(), e.evidence()));
                                ordered.add(guard);
                                gate = Optional.of(guard);
                                for (String alias : names.aliases()) {
                                        String key = "bean-alias:" + e.eventSlot() + ":"
                                                        + IngestionEvidence.digest(alias).value();
                                        steps.add(new BeanRegistrationPlan.Step(key, e.identity(),
                                                        BeanRegistrationPlan.Operation.REGISTER_ALIAS, gate, TRUE,
                                                        Optional.of(new BeanRegistrationPlan.Alias(alias,
                                                                        names.primary().orElseThrow())),
                                                        e.evidence()));
                                        ordered.add(key);
                                }
                        }
                        steps.add(new BeanRegistrationPlan.Step(e.eventSlot(), e.identity(),
                                        BeanRegistrationPlan.Operation.REGISTER_DEFINITION,
                                        gate, TRUE, Optional.empty(), e.evidence()));
                        ordered.add(e.eventSlot());
                        stepOrder.put(e.eventSlot(), List.copyOf(ordered));
                }
                var beanRegLimits = limit <= 1000 ? BeanRegistrationPlan.Limits.conservative()
                                : new BeanRegistrationPlan.Limits(Math.max(10_000, limit * 2),
                                                Math.max(100_000, intSquareBudget), Math.max(100_000, intSquareBudget),
                                                Math.max(100_000, intSquareBudget));
                var registration = new BeanRegistrationPlan(discovery, steps,
                                scope.registrationOrder().stream().flatMap(id -> stepOrder.get(id.value()).stream())
                                                .toList(),
                                scope.order(), scope.evidence(), scope.registry(), scope.noParent(), scope.evidence(),
                                definitions, List.of(), beanRegLimits);
                var dependencies = new ArrayList<InjectionBindingPlan.Dependency>();
                var matches = new ArrayList<BindingEvidence.Match>();
                var acquiredDependencies = new HashMap<EntityIdentity, List<ContentDigest>>();
                var suppressedSites = new HashMap<EntityIdentity, Boolean>();
                var methodDependencies = new TreeMap<String, Map<EntityIdentity, InjectionBindingPlan.Dependency>>();
                var groupDeclarations = new TreeMap<String, EntityIdentity>();
                var selectedParameters = new HashSet<EntityIdentity>();
                var methodSelector = new SourceMethodSelection(source, intSquareBudget);
                var methodSelections = new TreeMap<String, SourceMethodSelection.Decision>();
                constructors.rows().stream().filter(r -> r.status() == ConstructorInjectionIngestion.Status.SELECTED)
                                .forEach(r -> r.parameters().forEach(p -> selectedParameters.add(p.parameter())));
                for (var site : sites.sites()) {
                        var owners = new LinkedHashSet<BeanDefinitionCandidate>();
                        var directOwner = byDeclaration.get(site.owner());
                        if (directOwner != null)
                                owners.add(directOwner);
                        // Constructor selection applies to scanned objects only. Factory parameters are
                        // already owned by the method candidate; only member sites fan out to products.
                        boolean member = site.kind() != SpringInjectionSites.Kind.CONSTRUCTOR
                                        && site.kind() != SpringInjectionSites.Kind.BEAN_PARAMETER;
                        if (member)
                                owners.addAll(membersByType.getOrDefault(site.owner(), List.of()));
                        if (member)
                                productsByType.getOrDefault(site.owner(), List.of()).stream()
                                                .map(BeanMethodIngestion.Method::candidate).forEach(owners::add);
                        boolean allSuppressed = !owners.isEmpty()
                                        && site.status() == SpringInjectionSites.Status.ACQUIRED
                                        && scope.registry() == COMPLETE && scope.noParent() == COMPLETE
                                        && scope.noPostRegistrationMutation() == COMPLETE;
                        for (var owner : owners) {
                                var product = member ? productsByCandidate.get(owner.identity()) : null;
                                boolean productComplete = product == null || productMemberClosure.get(owner.identity());
                                var componentType = owner.exposedTypes().size() == 1 ? owner.exposedTypes().getFirst()
                                                : null;
                                var hierarchy = componentHierarchies.get(componentType);
                                boolean inherited = member && product == null && !site.owner().equals(componentType);
                                boolean resource = site.kind() == SpringInjectionSites.Kind.RESOURCE;
                                boolean resourceMethod = resource && source.declarations().get(site.element()).entity()
                                                .kind() == EntityKind.PARAMETER;
                                boolean resourceComplete = !resource || scope.resourcePolicy().isPresent();
                                if (resource && !resourceComplete)
                                        acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                        UniversalSpringEvidence.Reason.EVIDENCE_MISSING,
                                                        "resource-processor-policy:" + site.element().value(),
                                                        List.of(site.evidence())));
                                var method = site.kind() == SpringInjectionSites.Kind.METHOD || resourceMethod
                                                ? source.owner(site.element())
                                                : Optional.<EntityIdentity>empty();
                                SourceMethodSelection.Decision selection = null;
                                if (method.isPresent() && product == null && hierarchy != null) {
                                        var methodId = method.orElseThrow();
                                        String selectionKey = owner.identity().value() + ":" + methodId.value();
                                        selection = methodSelections.computeIfAbsent(selectionKey,
                                                        k -> methodSelector.select(componentType, methodId, hierarchy));
                                        if (selection.status() == SourceMethodSelection.Status.SUPPRESSED) {
                                                allSuppressed &= resourceComplete
                                                                && componentMetadata.get(componentType);
                                                continue;
                                        }
                                        if (selection.status() == SourceMethodSelection.Status.UNKNOWN)
                                                acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                                selection.problem() == SourceMethodSelection.Problem.COMPARISON_LIMIT
                                                                                ? UniversalSpringEvidence.Reason.RESOURCE_LIMIT
                                                                                : UniversalSpringEvidence.Reason.EVIDENCE_MISSING,
                                                                "source-method-selection:" + selection.problem() + ":"
                                                                                + selectionKey,
                                                                selection.evidence()));
                                }
                                allSuppressed = false;
                                boolean memberComplete = !member || product != null || hierarchy != null
                                                && hierarchy.complete()
                                                && (method.isEmpty() || selection != null && selection
                                                                .status() == SourceMethodSelection.Status.INCLUDED);
                                if (site.type().isEmpty()) {
                                        acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                        UniversalSpringEvidence.Reason.EVIDENCE_MISSING,
                                                        site.element().value(), List.of(site.evidence())));
                                }
                                var type = site.type()
                                                .orElseGet(() -> new JavaType(JavaType.Kind.UNKNOWN, "<unavailable>",
                                                                Optional.empty(), List.of(), Optional.empty(),
                                                                SemanticStatus.UNRESOLVED));
                                var shape = SourceDependencyShape.describe(source, type, resource);
                                boolean descriptorComplete = productComplete && memberComplete && resourceComplete
                                                && site.status() == SpringInjectionSites.Status.ACQUIRED
                                                && shape.complete()
                                                && (site.kind() == SpringInjectionSites.Kind.CONSTRUCTOR
                                                                && selectedParameters.contains(site.element())
                                                                || site.kind() == SpringInjectionSites.Kind.BEAN_PARAMETER
                                                                                && methods.containsKey(site.owner())
                                                                                && methods.get(site.owner()).complete()
                                                                || site.kind() == SpringInjectionSites.Kind.FIELD
                                                                || site.kind() == SpringInjectionSites.Kind.METHOD
                                                                || resource)
                                                && source.annotations(site.element()).stream().allMatch(a -> a.name()
                                                                .filter(n -> SITE_METADATA.contains(n) || resource && n
                                                                                .equals("jakarta.annotation.Resource"))
                                                                .isPresent())
                                                && site.qualifier().filter(String::isBlank).isEmpty();
                                if (!descriptorComplete)
                                        acquisitionIssues.add(new UniversalSpringEvidence.Issue(
                                                        UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,
                                                        site.element().value(), List.of(site.evidence())));
                                var kind = switch (site.kind()) {
                                        case CONSTRUCTOR -> InjectionPoint.SiteKind.CONSTRUCTOR_PARAMETER;
                                        case FIELD -> InjectionPoint.SiteKind.FIELD;
                                        case RESOURCE -> resourceMethod ? InjectionPoint.SiteKind.METHOD_PARAMETER
                                                        : InjectionPoint.SiteKind.FIELD;
                                        case METHOD -> InjectionPoint.SiteKind.METHOD_PARAMETER;
                                        case BEAN_PARAMETER -> InjectionPoint.SiteKind.BEAN_PARAMETER;
                                        default -> InjectionPoint.SiteKind.OTHER;
                                };
                                var point = new InjectionPoint(build.identity(), method.orElse(site.owner()).value(),
                                                kind, site.element().value(), site.evidence());
                                var dependency = new InjectionBindingPlan.Dependency(point,
                                                Optional.of(owner.identity()), shape.direct(), shape.shape(),
                                                resource ? InjectionBindingPlan.Mode.RESOURCE
                                                                : InjectionBindingPlan.Mode.AUTOWIRE,
                                                site.required() ? InjectionBindingPlan.Required.REQUIRED
                                                                : InjectionBindingPlan.Required.OPTIONAL,
                                                // Source spelling does not establish reflection parameter-name
                                                // availability.
                                                site.kind() == SpringInjectionSites.Kind.FIELD
                                                                || resource ? site.name().map(InjectionBindingPlan.Name::of).orElseGet(InjectionBindingPlan.Name::unknown) : InjectionBindingPlan.Name.unknown(),
                                                site.qualifier().map(
                                                                q -> q.isBlank() ? InjectionBindingPlan.Name.unknown()
                                                                                : InjectionBindingPlan.Name.of(q))
                                                                .orElseGet(InjectionBindingPlan.Name::absent),
                                                site.qualifier().isPresent() ? TRUE : FALSE, false,
                                                resource && site.resourceDefaultName(),
                                                resource && scope.resourcePolicy().isPresent(),
                                                descriptorComplete && shape.element().isPresent() && (site
                                                                .kind() == SpringInjectionSites.Kind.CONSTRUCTOR
                                                                || site.kind() == SpringInjectionSites.Kind.BEAN_PARAMETER),
                                                FALSE,
                                                descriptorComplete ? InjectionBindingPlan.Normalization.COMPLETE
                                                                : InjectionBindingPlan.Normalization.INCOMPLETE,
                                                site.evidence());
                                dependencies.add(dependency);
                                method.ifPresent(m -> {
                                        String key = product == null && !inherited ? m.value()
                                                        : m.value() + ":" + owner.identity().value();
                                        methodDependencies.computeIfAbsent(key, k -> new HashMap<>())
                                                        .put(site.element(), dependency);
                                        groupDeclarations.put(key, m);
                                });
                                acquiredDependencies.computeIfAbsent(site.element(), k -> new ArrayList<>())
                                                .add(dependency.identity());
                                source.owner(site.element())
                                                .ifPresent(ownerElement -> acquiredDependencies
                                                                .computeIfAbsent(ownerElement, k -> new ArrayList<>())
                                                                .add(dependency.identity()));
                                for (var entry : byDeclaration.entrySet()) {
                                        if (matches.size() >= intSquareBudget)
                                                break; // Missing match rows retain UNKNOWN in M4C.
                                        var candidateMethod = methods.get(entry.getKey());
                                        boolean complete = descriptorComplete && (candidateMethod == null
                                                        ? componentMetadata.get(entry.getKey())
                                                        : candidateMethod.complete() && componentMetadata
                                                                        .get(candidateMethod.owner()));
                                        var compatible = typeMatch(source, entry.getValue(), shape.direct(), complete,
                                                        limit);
                                        var qualifier = site.qualifier().isEmpty() ? TRUE
                                                        : qualifier(source, entry.getKey(), entry.getValue(),
                                                                        site.qualifier().orElseThrow(), complete);
                                        matches.add(new BindingEvidence.Match(dependency.identity(),
                                                        entry.getValue().identity(), BindingEvidence.Lane.DIRECT,
                                                        compatible, compatible, compatible, qualifier,
                                                        BindingEvidence.Knowledge.KNOWN, proof));
                                        if (shape.element().isPresent() && matches.size() < intSquareBudget) {
                                                var elementMatch = typeMatch(source, entry.getValue(),
                                                                shape.element().orElseThrow(), complete, limit);
                                                matches.add(new BindingEvidence.Match(dependency.identity(),
                                                                entry.getValue().identity(),
                                                                BindingEvidence.Lane.ELEMENT,
                                                                elementMatch, elementMatch, elementMatch, qualifier,
                                                                BindingEvidence.Knowledge.KNOWN, proof));
                                        }
                                }
                        }
                        // A method observation may represent several parameters. Every site and owner
                        // must have proved suppression; unknown/absent owners cannot close the
                        // obligation.
                        suppressedSites.merge(site.element(), allSuppressed, (a, b) -> a && b);
                        var declaring = source.owner(site.element());
                        if (declaring.isPresent())
                                suppressedSites.merge(declaring.orElseThrow(), allSuppressed, (a, b) -> a && b);
                }
                var groups = new ArrayList<InjectionBindingPlan.Group>();
                for (var entry : methodDependencies.entrySet()) {
                        // Declaration coordinates establish parameter order; hash/relationship order
                        // does not.
                        var ordered = entry.getValue().entrySet().stream()
                                        .sorted(Comparator.comparing(e -> source.declarations().get(e.getKey()).entity()
                                                        .declaration().orElseThrow()))
                                        .map(Map.Entry::getValue).toList();
                        groups.add(new InjectionBindingPlan.Group(entry.getKey(),
                                        ordered.stream().map(InjectionBindingPlan.Dependency::identity).toList(),
                                        ordered.stream().filter(
                                                        d -> d.required() == InjectionBindingPlan.Required.OPTIONAL && d
                                                                        .shape() != InjectionBindingPlan.Shape.OPTIONAL)
                                                        .map(InjectionBindingPlan.Dependency::identity).toList(),
                                        source.evidence(groupDeclarations.get(entry.getKey()))));
                }
                long matchCells = dependencies.stream().mapToLong(d -> d.aggregate() ? 2 : 1).sum()
                                * byDeclaration.size();
                boolean withinBudget = dependencies.size() <= limit && matchCells <= squareBudget;
                boolean constructorClosure = constructors.rows().stream()
                                .filter(r -> byDeclaration.containsKey(r.type()))
                                .noneMatch(r -> r.status() == ConstructorInjectionIngestion.Status.UNKNOWN);
                var environment = new InjectionBindingPlan.Environment(
                                sites.issues().isEmpty() && acquisitionIssues.isEmpty() && bounded && withinBudget
                                                && constructorClosure ? COMPLETE : UNKNOWN,
                                scope.noResolvableDependencies(), scope.noPostRegistrationMutation(),
                                InjectionBindingPlan.ComparatorPolicy.NONE,
                                scope.registrationOrder().stream().map(byDeclaration::get)
                                                .flatMap(c -> c.declaredNameKey().primary().stream()).distinct()
                                                .toList(),
                                scope.order(), scope.evidence());
                var obligations = new ArrayList<InjectionBindingPlan.ObligationBinding>();
                for (var obligation : inventory.obligations())
                        if (obligation.primaryMechanism().startsWith("spring.injection."))
                                raw.get(obligation.rawObservationIdentity()).owner().ifPresent(owner -> {
                                        var acquired = acquiredDependencies.getOrDefault(owner, List.of()).stream()
                                                        .distinct().toList();
                                        if (!acquired.isEmpty())
                                                obligations.add(new InjectionBindingPlan.ObligationBinding(
                                                                obligation.identity(), acquired, proof));
                                        else if (bounded && withinBudget
                                                        && Boolean.TRUE.equals(suppressedSites.get(owner))) {
                                                var selectionProofs = new ArrayList<ContentDigest>();
                                                selectionProofs.add(proof.identity());
                                                methodSelections.values().stream()
                                                                .filter(d -> d.method().equals(owner) || source
                                                                                .owner(owner).filter(d.method()::equals)
                                                                                .isPresent())
                                                                .map(SourceMethodSelection.Decision::identity).sorted()
                                                                .forEach(selectionProofs::add);
                                                if (selectionProofs.size() > 1)
                                                        obligations.add(new InjectionBindingPlan.ObligationBinding(
                                                                        obligation.identity(), List.of(),
                                                                        new ConditionEvidence.Derived(selectionProofs,
                                                                                        PROVIDER,
                                                                                        "closed-owner-method-suppression"),
                                                                        InjectionBindingPlan.Disposition.SUPPRESSED));
                                        }
                                });
                var binding = new InjectionBindingPlan(registration, dependencies, bindingDefinitions, matches, groups,
                                obligations, environment,
                                new InjectionBindingPlan.Limits(limit, intSquareBudget, intSquareBudget,
                                                intSquareBudget));
                var gaps = new TreeSet<>(components.gaps());
                gaps.addAll(constructors.gaps());
                gaps.addAll(sites.gaps());
                gaps.addAll(lowering.capabilityGaps());
                gaps.addAll(discovery.capabilityGaps());
                gaps.addAll(UniversalSpringEvidence.gaps(build.snapshotIdentity(), input, acquisitionIssues));
                if (autoScoped) {
                        gaps.add(autoScopedClosureGap(build.snapshotIdentity(), input));
                }
                if (!bounded || !withinBudget)
                        gaps.addAll(UniversalSpringEvidence.gaps(build.snapshotIdentity(), input, List.of(
                                        new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,
                                                        "source-to-plan", List.of(proof)))));
                return new Result(input, inventory, lowering, binding, List.copyOf(gaps),
                                List.copyOf(methodSelections.values()));
        }

        private static List<EntityIdentity> deriveSourceScopedOrder(
                        SpringSourceEvidence source,
                        Map<EntityIdentity, BeanDefinitionCandidate> byDeclaration,
                        Map<EntityIdentity, BeanMethodIngestion.Method> methods) {
                return byDeclaration.keySet().stream()
                                .sorted(Comparator
                                                .comparingInt((EntityIdentity id) -> methods.containsKey(id) ? 1 : 0)
                                                .thenComparingInt(id -> annotationOrder(source, id))
                                                .thenComparing(EntityIdentity::value))
                                .toList();
        }

        private static int annotationOrder(SpringSourceEvidence source, EntityIdentity id) {
                for (var ann : source.annotations(id)) {
                        if (ann.name().filter("org.springframework.core.annotation.Order"::equals).isPresent()) {
                                try {
                                        var attrs = LiteralConditionAnnotation.parse(ann.use().spelling());
                                        String raw = LiteralConditionAnnotation.string(attrs, "value", "");
                                        if (!raw.isBlank())
                                                return Integer.parseInt(raw.trim());
                                } catch (IllegalArgumentException ignored) {
                                }
                        }
                }
                return Integer.MAX_VALUE;
        }

        private static CapabilityGapRecord autoScopedClosureGap(
                        com.evolution.analysis.contract.identity.SnapshotIdentity snapshot, ContentDigest input) {
                var obs = com.evolution.analysis.evidence.ProviderObservationReference.create(
                                PROVIDER, "spring.source-to-plan.closure", input,
                                ContentDigest.sha256Utf8("SOURCE_SCOPED_CONTAINER_CLOSURE"));
                var subject = com.evolution.analysis.evidence.EvidenceSubject.observation(obs.identity());
                var req = new com.evolution.analysis.evidence.EvidenceRequirement(
                                com.evolution.analysis.evidence.EvidenceRequirement.Kind.CONFIGURATION,
                                "spring.source-to-plan.source-scoped-container-closure",
                                List.of(subject),
                                com.evolution.analysis.evidence.EvidenceRequirement.AuthorizationClass.PASSIVE,
                                List.of("Supply an explicit runtime container registration-order trace to certify external/non-source bean registration order."));
                return CapabilityGapRecord.create(
                                ConditionProcessing.GAP_CATALOG,
                                com.evolution.analysis.evidence.EvidenceContext.forSnapshot(snapshot),
                                PROVIDER,
                                "spring.source-to-plan",
                                "SOURCE_SCOPED_CONTAINER_CLOSURE",
                                subject,
                                List.of(),
                                List.of(obs),
                                List.of(req),
                                List.of(),
                                List.of(new com.evolution.analysis.evidence.AffectedOutput(
                                                com.evolution.analysis.evidence.AffectedOutput.Kind.ANALYSIS_COVERAGE,
                                                "spring.source-to-plan")),
                                List.of(),
                                List.of(),
                                List.of("Registration order and container closure were derived from static source declarations (@Order + canonical identity)."));
        }

        private static List<ConditionEvidenceLowering.BuildObservation> deriveBuildObservations(
                        SpringBuildContext build,
                        SpringSourceEvidence source,
                        SpringMechanismInventory inventory,
                        SpringFrameworkEvidence.ValidationMode validationMode) {
                var rawById = new HashMap<ContentDigest, SpringMechanismInventory.RawObservation>(
                                inventory.rawObservations().size() * 2);
                for (var r : inventory.rawObservations()) rawById.put(r.identity(), r);

                Set<String> knownPresentTypes = null;
                Set<String> snapshotResourceSuffixes = null;
                var derived = new ArrayList<ConditionEvidenceLowering.BuildObservation>();
                var seenRaw = new HashSet<ContentDigest>();

                for (var obligation : inventory.obligations()) {
                        if (obligation.secondaryTags().contains("spring.annotation.composed")) continue;
                        var raw = rawById.get(obligation.rawObservationIdentity());
                        if (raw == null || raw.kind() != SpringMechanismInventory.RawKind.ANNOTATION_USE
                                        || raw.span().isEmpty() || !seenRaw.add(raw.identity())) {
                                continue;
                        }
                        String annType = resolveBuildConditionType(raw, source, validationMode);
                        if (annType == null) continue;
                        if (!source.framework().allows(true, validationMode)) continue;

                        Map<String, LiteralConditionAnnotation.Value> attrs;
                        try {
                                attrs = LiteralConditionAnnotation.parse(raw.spelling());
                        } catch (IllegalArgumentException unsupported) {
                                continue;
                        }

                        switch (annType) {
                                case "org.springframework.boot.autoconfigure.condition.ConditionalOnJava" -> {
                                        if (!Set.of("value", "range").containsAll(attrs.keySet())) continue;
                                        Integer targetJava = parseJavaVersion(attrs.get("value"));
                                        String range = parseEnumSimpleName(attrs.get("range"), "EQUAL_OR_NEWER");
                                        if (targetJava == null || range == null) continue;
                                        int release = build.platformRelease();
                                        LogicalValue verdict = switch (range) {
                                                case "EQUAL_OR_NEWER" -> release >= targetJava ? TRUE : FALSE;
                                                case "OLDER_THAN" -> release < targetJava ? TRUE : FALSE;
                                                default -> null;
                                        };
                                        if (verdict != null) {
                                                var ev = new ConditionEvidence.Derived(
                                                                List.of(IngestionEvidence.digest(build.identity()), source.identity()),
                                                                PROVIDER, "platform-release-java-" + release);
                                                derived.add(new ConditionEvidenceLowering.BuildObservation(
                                                                raw.identity(),
                                                                ConditionExpression.BuildPredicate.JAVA_VERSION,
                                                                build.identity(), verdict, ev));
                                        }
                                }
                                case "org.springframework.boot.autoconfigure.condition.ConditionalOnResource" -> {
                                        if (!Set.of("resources").containsAll(attrs.keySet())) continue;
                                        List<String> resources;
                                        try {
                                                resources = LiteralConditionAnnotation.strings(attrs, "resources", List.of());
                                        } catch (IllegalArgumentException unsupported) {
                                                continue;
                                        }
                                        if (resources.isEmpty()) continue;
                                        if (snapshotResourceSuffixes == null) {
                                                snapshotResourceSuffixes = indexSnapshotClasspathResources(source);
                                        }
                                        boolean allClasspathAndPresent = true;
                                        for (String res : resources) {
                                                if (!res.startsWith("classpath:")) {
                                                        allClasspathAndPresent = false;
                                                        break;
                                                }
                                                String rel = res.substring("classpath:".length());
                                                while (rel.startsWith("/")) rel = rel.substring(1);
                                                if (rel.isEmpty() || !snapshotResourceSuffixes.contains(rel)) {
                                                        allClasspathAndPresent = false;
                                                        break;
                                                }
                                        }
                                        // Only emit positive TRUE proof when all classpath resources exist in captured snapshot files.
                                        // Absence from snapshot files cannot disprove a resource bundled inside a dependency JAR.
                                        if (allClasspathAndPresent) {
                                                var ev = new ConditionEvidence.Derived(
                                                                List.of(IngestionEvidence.digest(build.identity()), source.identity()),
                                                                PROVIDER, "snapshot-classpath-resource-present");
                                                derived.add(new ConditionEvidenceLowering.BuildObservation(
                                                                raw.identity(),
                                                                ConditionExpression.BuildPredicate.RESOURCE_PRESENT,
                                                                build.identity(), TRUE, ev));
                                        }
                                }
                                case "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
                                     "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass" -> {
                                        if (!Set.of("value", "name").containsAll(attrs.keySet())) continue;
                                        List<String> classTokens;
                                        List<String> nameTokens;
                                        try {
                                                classTokens = LiteralConditionAnnotation.classes(attrs, "value");
                                                nameTokens = LiteralConditionAnnotation.strings(attrs, "name", List.of());
                                        } catch (IllegalArgumentException unsupported) {
                                                continue;
                                        }
                                        if (classTokens.isEmpty() && nameTokens.isEmpty()) continue;
                                        if (knownPresentTypes == null) {
                                                knownPresentTypes = indexKnownPresentTypes(source);
                                        }
                                        var resolvedNames = new ArrayList<String>(classTokens.size() + nameTokens.size());
                                        boolean allTokensResolved = true;
                                        for (String token : classTokens) {
                                                var resolved = source.resolveImportedTypeName(raw.document(), token, knownPresentTypes);
                                                if (resolved.isPresent()) {
                                                        resolvedNames.add(resolved.orElseThrow());
                                                } else {
                                                        allTokensResolved = false;
                                                }
                                        }
                                        resolvedNames.addAll(nameTokens);
                                        boolean isOnClass = annType.endsWith(".ConditionalOnClass");
                                        if (isOnClass) {
                                                if (allTokensResolved && !resolvedNames.isEmpty()
                                                                && knownPresentTypes.containsAll(resolvedNames)) {
                                                        var ev = new ConditionEvidence.Derived(
                                                                        List.of(IngestionEvidence.digest(build.identity()), source.identity()),
                                                                        PROVIDER, "frontend-resolved-class-present");
                                                        derived.add(new ConditionEvidenceLowering.BuildObservation(
                                                                        raw.identity(),
                                                                        ConditionExpression.BuildPredicate.CLASS_PRESENT,
                                                                        build.identity(), TRUE, ev));
                                                }
                                        } else {
                                                // @ConditionalOnMissingClass is definitely FALSE if any referenced class is positively present.
                                                boolean anyPresent = resolvedNames.stream().anyMatch(knownPresentTypes::contains);
                                                if (anyPresent) {
                                                        var ev = new ConditionEvidence.Derived(
                                                                        List.of(IngestionEvidence.digest(build.identity()), source.identity()),
                                                                        PROVIDER, "frontend-resolved-class-present");
                                                        derived.add(new ConditionEvidenceLowering.BuildObservation(
                                                                        raw.identity(),
                                                                        ConditionExpression.BuildPredicate.CLASS_PRESENT,
                                                                        build.identity(), FALSE, ev));
                                                }
                                        }
                                }
                                default -> {}
                        }
                }
                return derived;
        }

        private static String resolveBuildConditionType(
                        SpringMechanismInventory.RawObservation raw,
                        SpringSourceEvidence source,
                        SpringFrameworkEvidence.ValidationMode validationMode) {
                if (raw.owner().isPresent()) {
                        for (var ann : source.annotations(raw.owner().orElseThrow())) {
                                if (raw.span().equals(Optional.of(ann.use().span())) && ann.name().isPresent()) {
                                        String n = ann.name().orElseThrow();
                                        if (n.startsWith("org.springframework.boot.autoconfigure.condition.ConditionalOn")) {
                                                return n;
                                        }
                                }
                        }
                }
                if (validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED
                                && source.framework().artifacts().isEmpty()) {
                        String sp = raw.spelling().stripLeading();
                        if (sp.startsWith("@ConditionalOnJava")) return "org.springframework.boot.autoconfigure.condition.ConditionalOnJava";
                        if (sp.startsWith("@ConditionalOnResource")) return "org.springframework.boot.autoconfigure.condition.ConditionalOnResource";
                        if (sp.startsWith("@ConditionalOnClass")) return "org.springframework.boot.autoconfigure.condition.ConditionalOnClass";
                        if (sp.startsWith("@ConditionalOnMissingClass")) return "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass";
                }
                return null;
        }

        private static Set<String> indexKnownPresentTypes(SpringSourceEvidence source) {
                var set = new HashSet<String>(source.declarations().size() * 2);
                for (var decl : source.declarations().values()) {
                        if (decl.status() != SemanticStatus.RESOLVED || decl.entity().kind() != EntityKind.TYPE) continue;
                        String qn = source.typeName(decl.entity().identity());
                        if (qn != null && !qn.isEmpty()) set.add(qn);
                }
                return set;
        }

        private static Set<String> indexSnapshotClasspathResources(SpringSourceEvidence source) {
                var set = new HashSet<String>();
                for (var file : source.manifest().snapshot().files()) {
                        String path = file.path().replace('\\', '/');
                        int idx = path.indexOf("/resources/");
                        if (idx >= 0 && idx + "/resources/".length() < path.length()) {
                                set.add(path.substring(idx + "/resources/".length()));
                        } else if (path.startsWith("resources/") && path.length() > "resources/".length()) {
                                set.add(path.substring("resources/".length()));
                        }
                }
                return set;
        }

        private static String parseEnumSimpleName(LiteralConditionAnnotation.Value value, String defaultValue) {
                if (value == null) return defaultValue;
                if (value instanceof LiteralConditionAnnotation.Enums enums && enums.names().size() == 1) {
                        String raw = enums.names().getFirst();
                        int dot = raw.lastIndexOf('.');
                        return dot >= 0 ? raw.substring(dot + 1) : raw;
                }
                return null;
        }

        private static Integer parseJavaVersion(LiteralConditionAnnotation.Value value) {
                String name = parseEnumSimpleName(value, null);
                if (name == null) return null;
                return switch (name) {
                        case "EIGHT" -> 8;
                        case "NINE" -> 9;
                        case "TEN" -> 10;
                        case "ELEVEN" -> 11;
                        case "TWELVE" -> 12;
                        case "THIRTEEN" -> 13;
                        case "FOURTEEN" -> 14;
                        case "FIFTEEN" -> 15;
                        case "SIXTEEN" -> 16;
                        case "SEVENTEEN" -> 17;
                        case "EIGHTEEN" -> 18;
                        case "NINETEEN" -> 19;
                        case "TWENTY" -> 20;
                        case "TWENTY_ONE" -> 21;
                        case "TWENTY_TWO" -> 22;
                        case "TWENTY_THREE" -> 23;
                        case "TWENTY_FOUR" -> 24;
                        case "TWENTY_FIVE" -> 25;
                        default -> null;
                };
        }

        private static boolean metadataComplete(SpringSourceEvidence source, EntityIdentity type,
                        SpringSourceEvidence.SourceHierarchy hierarchy,
                        com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode validationMode) {
                if (validationMode == com.evolution.analysis.spring.SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED) {
                        return source.declarationParsed(type)
                                        && source.annotations(type).stream().allMatch(a -> a.name()
                                                        .filter(n -> COMPAT_COMPONENT_METADATA.contains(n)
                                                                        || BeanMethodIngestion.CONDITIONS.contains(n))
                                                        .isPresent())
                                        && hierarchy.complete();
                }
                return source.declarationParsed(type)
                                && BeanMethodIngestion.configurationMetadata(source, type)
                                && source.annotations(type).stream()
                                                .allMatch(a -> a.name().filter(n -> COMPONENT_METADATA.contains(n)
                                                                || BeanMethodIngestion.CONDITIONS.contains(n))
                                                                .isPresent())
                                && hierarchy.complete()
                                // Inherited class-level flags/conditions/composition need their own provider.
                                && hierarchy.types().stream().skip(1)
                                                .allMatch(parent -> source.annotations(parent).isEmpty());
        }

        private static LogicalValue typeMatch(SpringSourceEvidence source, BeanDefinitionCandidate candidate,
                        JavaType requested, boolean complete, int limit) {
                if (!complete)
                        return LogicalValue.UNKNOWN;
                // Current complete candidates expose ordinary non-generic classes, never arrays
                // or container implementations. Unsupported candidate metadata stays UNKNOWN
                // above.
                if (requested.kind() == JavaType.Kind.ARRAY)
                        return FALSE;
                if (requested.target().isEmpty())
                        return LogicalValue.UNKNOWN;
                var declaration = source.declarations().get(requested.target().orElseThrow());
                if (declaration != null && declaration.entity().origin() == EntityOrigin.JDK
                                && "java.lang.Object".equals(source.typeName(requested.target().orElseThrow()))
                                && requested.components().isEmpty())
                        return TRUE;
                boolean raw = candidate.exposedTypes().stream()
                                .anyMatch(t -> source.ancestry(t, limit).contains(requested.target().orElseThrow()));
                if (!raw)
                        return FALSE;
                return requested.components().isEmpty() ? TRUE : LogicalValue.UNKNOWN;
        }

        private static LogicalValue flag(SpringSourceEvidence source, EntityIdentity type, String name) {
                return source.annotations(type).stream().anyMatch(a -> a.name().filter(name::equals).isPresent()) ? TRUE
                                : FALSE;
        }

        private static LogicalValue qualifier(SpringSourceEvidence source, EntityIdentity type,
                        BeanDefinitionCandidate candidate, String expected, boolean complete) {
                if (candidate.declaredNameKey().primary().filter(expected::equals).isPresent()
                                || candidate.declaredNameKey().aliases().contains(expected))
                        return TRUE;
                for (var annotation : source.annotations(type))
                        if (annotation.name().filter("org.springframework.beans.factory.annotation.Qualifier"::equals)
                                        .isPresent()) {
                                try {
                                        if (expected.equals(LiteralConditionAnnotation.string(
                                                        LiteralConditionAnnotation.parse(annotation.use().spelling()),
                                                        "value", "")))
                                                return TRUE;
                                } catch (IllegalArgumentException unknown) {
                                        return LogicalValue.UNKNOWN;
                                }
                        }
                return candidate.producer().producerKind() == BeanProducer.Kind.BEAN_METHOD || complete ? FALSE
                                : LogicalValue.UNKNOWN;
        }

        private SourceToSpringPlan() {
        }
}
