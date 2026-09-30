package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.SemanticStatus;
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

/** Source acquisition for direct components and ordinary scalar bean methods. The caller supplies
 * evidenced container/order closure, never final definitions, dependency descriptors or matches.
 * An unproved scope stays open. This is not an application bootstrap detector. */
public final class SourceToSpringPlan {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("spring.source-to-plan","m4uv2.2-method-qualifiers-v1");

    public record Scope(String container, ContentDigest sourceEvidence, List<EntityIdentity> registrationOrder,
                        RegistrationEvent.Completeness order, RegistrationEvent.Completeness registry,
                        RegistrationEvent.Completeness noParent, RegistrationEvent.Completeness noResolvableDependencies,
                        RegistrationEvent.Completeness noPostRegistrationMutation,
                        RegistrationPlan.OverridePolicy overrides, ConditionEvidence evidence) {
        public Scope {
            ContractChecks.text(container,"container");Objects.requireNonNull(sourceEvidence);
            registrationOrder=ContractChecks.distinctInOrder(registrationOrder,"source registration order");
            Objects.requireNonNull(order);Objects.requireNonNull(registry);Objects.requireNonNull(noParent);
            Objects.requireNonNull(noResolvableDependencies);Objects.requireNonNull(noPostRegistrationMutation);
            Objects.requireNonNull(overrides);Objects.requireNonNull(evidence);
        }
    }
    public record Result(ContentDigest inputIdentity, SpringMechanismInventory inventory,
                         ConditionEvidenceLowering.Result conditions, InjectionBindingPlan binding,
                         List<CapabilityGapRecord> gaps) {
        public Result {gaps=List.copyOf(gaps);}
        public ContentDigest identity() {return IngestionEvidence.digest(List.of(inputIdentity,inventory.identity(),conditions.identity(),binding.identity(),gaps));}
        public Evaluation evaluate(ConfigurationSpace space,ConfigurationAssignment baseline,
                                                     TruthRegionEvaluation.Limits limits,ContentDigest evaluatorArtifact) {
            if(!space.buildContext().identity().equals(binding.registrationPlan().discoveryPlan().buildContext().identity()))
                throw new IllegalArgumentException("Configuration space belongs to another source context");
            return new Evaluation(this,TruthRegionEvaluation.evaluate(new TruthRegionEvaluation.Request(binding,
                    ConditionModel.create(space,conditions.occurrences()),conditions.semantics(),baseline,
                    ExhaustiveConfigurationReasoner.INSTANCE,limits,evaluatorArtifact)));
        }
        public SymbolicEvaluation evaluateSymbolic(ConfigurationSpace space,ConfigurationAssignment baseline,
                TruthRegionEvaluation.Limits limits,ContentDigest evaluatorArtifact,int maxSignatures,SymbolicConfiguration.Limits symbolicLimits) {
            if(!space.buildContext().identity().equals(binding.registrationPlan().discoveryPlan().buildContext().identity()))
                throw new IllegalArgumentException("Configuration space belongs to another source context");
            return new SymbolicEvaluation(this,TruthRegionEvaluation.evaluateSymbolic(new TruthRegionEvaluation.Request(binding,
                    ConditionModel.create(space,conditions.occurrences()),conditions.semantics(),baseline,
                    SatConfigurationReasoner.INSTANCE,limits,evaluatorArtifact),maxSignatures,symbolicLimits));
        }
    }
    /** Retain acquisition and downstream denominators together; evaluating a qualified plan
     * cannot erase a provider gap merely because it is outside the evaluator's own catalog. */
    public record Evaluation(Result acquisition,TruthRegionEvaluation.Result truth) {
        public Evaluation {Objects.requireNonNull(acquisition);Objects.requireNonNull(truth);}
        public List<CapabilityGapRecord> capabilityGaps() {return combined(acquisition.gaps(),truth.capabilityGaps());}
        public ContentDigest identity() {return IngestionEvidence.digest(List.of(acquisition.identity(),truth.identity(),capabilityGaps()));}
    }
    public record SymbolicEvaluation(Result acquisition,SymbolicTruthRegions.StagedResult truth) {
        public SymbolicEvaluation {Objects.requireNonNull(acquisition);Objects.requireNonNull(truth);}
        public List<CapabilityGapRecord> capabilityGaps() {return combined(acquisition.gaps(),truth.gaps());}
        public ContentDigest identity() {return IngestionEvidence.digest(List.of(acquisition.identity(),truth.identity(),capabilityGaps()));}
    }
    private static List<CapabilityGapRecord> combined(List<CapabilityGapRecord> acquired,List<CapabilityGapRecord> evaluated) {
        var result=new TreeSet<>(acquired);result.addAll(evaluated);return List.copyOf(result);
    }
    private static final Set<String> COMPONENT_METADATA=Set.of("org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service","org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller","org.springframework.context.annotation.Profile",
            "org.springframework.context.annotation.Primary","org.springframework.context.annotation.Fallback",
            "org.springframework.beans.factory.annotation.Qualifier","org.springframework.context.annotation.Configuration");
    private static final Set<String> SITE_METADATA=Set.of("org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Qualifier","jakarta.inject.Inject","jakarta.inject.Named");
    private static final Set<String> DEFERRED_TYPES=Set.of("java.util.Optional","org.springframework.beans.factory.ObjectProvider",
            "org.springframework.beans.factory.ObjectFactory","jakarta.inject.Provider","javax.inject.Provider");

    public static Result normalize(SpringBuildContext build,SpringSourceEvidence source,
                                   ComponentScanIngestion.Result components,ConstructorInjectionIngestion.Result constructors,
                                   Scope scope,int limit) {
        if(limit<1||limit>1000)throw new IllegalArgumentException("Plan acquisition limit must be in [1,1000]");
        if(!scope.sourceEvidence().equals(source.identity()) || !build.analysisIdentity().equals(source.frontend().analysis())
                ||!components.frontendEvidence().equals(IngestionEvidence.digest(source.frontend()))
                ||!components.frameworkEvidence().equals(source.framework().identity()))
            throw new IllegalArgumentException("Foreign source-to-plan evidence");
        if(scope.evidence() instanceof ConditionEvidence.Source evidence && (!build.containsSource(evidence)||evidence.span().isEmpty())
                ||scope.evidence() instanceof ConditionEvidence.Artifact artifact&&!build.containsArtifact(artifact.artifactDigest()))
            throw new IllegalArgumentException("Foreign scope proof");
        var inventory=SpringMechanismScanner.scan(new SpringMechanismScanRequest(source.frontend(),source.framework(),List.of(),List.of()));
        var lowering=ConditionEvidenceLowering.lower(build,inventory,List.of(),ConditionEvidenceLowering.Limits.conservative());
        var sites=SpringInjectionSites.acquire(source,constructors,FrameworkGeneration.from(source.framework(),Optional.empty()),limit);
        var input=IngestionEvidence.digest(List.of(PROVIDER,build.identity(),source.identity(),components.identity(),constructors.identity(),scope,limit));
        var proof=new ConditionEvidence.Derived(List.of(input,scope.evidence().identity()),PROVIDER,"source-descriptors");
        var candidates=components.candidates(build,scope.container());
        var byDeclaration=new TreeMap<EntityIdentity,BeanDefinitionCandidate>();
        for(var candidate:candidates)byDeclaration.put(candidate.exposedTypes().getFirst(),candidate);
        var acquisitionIssues=new ArrayList<UniversalSpringEvidence.Issue>();
        var methods=BeanMethodIngestion.acquire(build,source,scope.container(),byDeclaration,acquisitionIssues);
        methods.forEach((id,method) -> byDeclaration.put(id,method.candidate()));
        var productsByType=new TreeMap<EntityIdentity,List<BeanMethodIngestion.Method>>();
        var productsByCandidate=new HashMap<BeanDefinitionCandidate.Identity,BeanMethodIngestion.Method>();
        var productMemberClosure=new HashMap<BeanDefinitionCandidate.Identity,Boolean>();
        for(var method:methods.values()) {
            for(var type:method.candidate().exposedTypes())productsByType.computeIfAbsent(type,k -> new ArrayList<>()).add(method);
            productsByCandidate.put(method.candidate().identity(),method);
            boolean complete=BeanMethodIngestion.productMembersComplete(source,method);
            productMemberClosure.put(method.candidate().identity(),complete);
            // A wider type may hide additional runtime members even when it declares no sites.
            if(!complete)acquisitionIssues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EVIDENCE_MISSING,
                    "bean-product-runtime-type:"+method.candidate().identity().value(),List.of(source.evidence(method.declaration()))));
        }
        if(!byDeclaration.keySet().containsAll(scope.registrationOrder()) || scope.order()==COMPLETE
                &&!new HashSet<>(scope.registrationOrder()).equals(byDeclaration.keySet()))
            throw new IllegalArgumentException("Order must reference exactly the acquired component/bean-method inventory when complete");
        var raw=new HashMap<ContentDigest,SpringMechanismInventory.RawObservation>();inventory.rawObservations().forEach(r -> raw.put(r.identity(),r));
        var attached=new HashMap<EntityIdentity,List<RegistrationEvent.ConditionUse>>();
        for(var row:lowering.rows())row.occurrence().ifPresent(occurrence -> raw.get(row.rawObservation()).owner().ifPresent(owner ->
                attached.computeIfAbsent(owner,k -> new ArrayList<>()).add(new RegistrationEvent.ConditionUse(occurrence.identity(),
                        BeanConditionLowering.predicate(inventory,lowering,occurrence.identity()).isPresent()
                                ?RegistrationEvent.RequiredPhase.REGISTER_BEAN:RegistrationEvent.RequiredPhase.ORDINARY,occurrence.declarationEvidenceKey()))));
        // A direct, unconditional scan obligation is realized by the component membership
        // evidence already acquired above. Conditional/custom scan drivers stay unplanned.
        var scans=inventory.obligations().stream().filter(o -> o.primaryMechanism().equals("spring.discovery.component-scan"))
                .filter(o -> components.issues().isEmpty() && raw.get(o.rawObservationIdentity()).owner().filter(owner ->
                        source.ancestry(owner,2).size()==1
                                &&source.annotations(owner).stream().allMatch(a -> a.name().filter("org.springframework.context.annotation.ComponentScan"::equals).isPresent())
                                &&!source.annotations(owner).isEmpty()).isPresent())
                .map(SpringMechanismInventory.SemanticObligation::identity).toList();
        var events=new ArrayList<RegistrationEvent>();var byEvent=new HashMap<EntityIdentity,RegistrationEvent>();
        var definitions=new ArrayList<BeanRegistrationEvidence.Definition>();var bindingDefinitions=new ArrayList<BindingEvidence.Definition>();
        boolean bounded=byDeclaration.size()<=limit;
        // Parents are constructed before methods solely to bind event identities. The supplied
        // schedule, not this construction order, drives discovery and registration.
        var entries=new ArrayList<>(byDeclaration.entrySet());
        entries.sort(Comparator.comparing(e -> methods.containsKey(e.getKey())));
        for(var entry:entries) {
            var type=entry.getKey();var candidate=entry.getValue();var evidence=source.evidence(type);
            var method=methods.get(type);var parent=method==null?null:byEvent.get(method.owner());
            boolean metadata=bounded&&(method==null?metadataComplete(source,type):method.complete()&&metadataComplete(source,method.owner()));
            if(!metadata)acquisitionIssues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,type.value(),List.of(evidence)));
            var obligations=new ArrayList<>(inventory.obligations().stream().filter(o -> raw.get(o.rawObservationIdentity()).owner().filter(type::equals).isPresent())
                    .filter(o -> !o.primaryMechanism().startsWith("spring.condition.")).map(SpringMechanismInventory.SemanticObligation::identity).toList());
            obligations.addAll(scans);
            var event=new RegistrationEvent(build.identity(),lowering.semantics(),IngestionEvidence.digest(type),parent==null?List.of():parent.invocationPath(),
                    method==null?RegistrationEvent.Phase.COMPONENT_SCAN:RegistrationEvent.Phase.CONFIGURATION_PARSE,
                    type.value(),scope.container(),method==null?RegistrationEvent.Kind.COMPONENT:RegistrationEvent.Kind.BEAN_METHOD,
                    method==null?RegistrationEvent.ConditionSite.REGISTER_BEAN:RegistrationEvent.ConditionSite.DISCOVERY_ONLY,
                    Optional.ofNullable(parent).map(RegistrationEvent::identity),Optional.of(candidate),attached.getOrDefault(type,List.of()),
                    COMPLETE,metadata?COMPLETE:UNKNOWN,obligations,evidence);
            events.add(event);byEvent.put(type,event);
            var primary=metadata?flag(source,type,"org.springframework.context.annotation.Primary"):LogicalValue.UNKNOWN;
            var fallback=metadata?flag(source,type,"org.springframework.context.annotation.Fallback"):LogicalValue.UNKNOWN;
            var autowire=method==null?TRUE:method.autowire();var defaultCandidate=method==null?TRUE:method.defaultCandidate();
            definitions.add(new BeanRegistrationEvidence.Definition(candidate.identity(),List.of(),autowire,defaultCandidate,primary,fallback,
                    metadata?FALSE:LogicalValue.UNKNOWN,evidence));
            bindingDefinitions.add(new BindingEvidence.Definition(candidate.identity(),autowire,defaultCandidate,primary,fallback,
                    method==null||method.staticMethod()?Optional.empty():Optional.of(byDeclaration.get(method.owner()).identity()),
                    metadata?BindingEvidence.RuntimeKind.ORDINARY:BindingEvidence.RuntimeKind.UNKNOWN,
                    metadata?BindingEvidence.Rank.absent():BindingEvidence.Rank.unknown(),BindingEvidence.Rank.unknown(),FALSE,evidence));
        }
        var precedences=new ArrayList<RegistrationPlan.Precedence>();
        for(int i=1;i<scope.registrationOrder().size();i++)precedences.add(new RegistrationPlan.Precedence(
                byEvent.get(scope.registrationOrder().get(i-1)).identity(),byEvent.get(scope.registrationOrder().get(i)).identity(),
                RegistrationPlan.OrderDomain.EXPLICIT_SEQUENCE,scope.evidence()));
        var discovery=RegistrationPlan.create(build,inventory,lowering,events,precedences,
                new RegistrationPlan.Container(scope.container(),scope.evidence()),List.of(),scope.overrides(),RegistrationPlan.Limits.conservative());
        var steps=events.stream().map(e -> new BeanRegistrationPlan.Step(e.eventSlot(),e.identity(),BeanRegistrationPlan.Operation.REGISTER_DEFINITION,
                e.parent().map(p -> events.stream().filter(parent -> parent.identity().equals(p)).findFirst().orElseThrow().eventSlot()),
                TRUE,Optional.empty(),e.evidence())).toList();
        var registration=new BeanRegistrationPlan(discovery,steps,scope.registrationOrder().stream().map(EntityIdentity::value).toList(),
                scope.order(),scope.evidence(),scope.registry(),scope.noParent(),scope.evidence(),definitions,List.of(),BeanRegistrationPlan.Limits.conservative());
        var dependencies=new ArrayList<InjectionBindingPlan.Dependency>();var matches=new ArrayList<BindingEvidence.Match>();
        var acquiredDependencies=new HashMap<EntityIdentity,List<ContentDigest>>();
        var methodDependencies=new TreeMap<String,Map<EntityIdentity,InjectionBindingPlan.Dependency>>();
        var groupDeclarations=new TreeMap<String,EntityIdentity>();
        var selectedParameters=new HashSet<EntityIdentity>();
        constructors.rows().stream().filter(r -> r.status()==ConstructorInjectionIngestion.Status.SELECTED)
                .forEach(r -> r.parameters().forEach(p -> selectedParameters.add(p.parameter())));
        for(var site:sites.sites()) {
            var owners=new ArrayList<BeanDefinitionCandidate>();
            var directOwner=byDeclaration.get(site.owner());
            if(directOwner!=null)owners.add(directOwner);
            // Constructor selection applies to scanned objects only. Factory parameters are
            // already owned by the method candidate; only member sites fan out to products.
            boolean member=site.kind()!=SpringInjectionSites.Kind.CONSTRUCTOR&&site.kind()!=SpringInjectionSites.Kind.BEAN_PARAMETER;
            if(member)productsByType.getOrDefault(site.owner(),List.of()).stream()
                    .map(BeanMethodIngestion.Method::candidate).forEach(owners::add);
            for(var owner:owners) {
                var product=member?productsByCandidate.get(owner.identity()):null;
                boolean productComplete=product==null||productMemberClosure.get(owner.identity());
                if(site.type().isEmpty()) {
                    acquisitionIssues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EVIDENCE_MISSING,
                            site.element().value(),List.of(site.evidence())));
                }
                var type=site.type().orElseGet(() -> new JavaType(JavaType.Kind.UNKNOWN,"<unavailable>",Optional.empty(),List.of(),Optional.empty(),SemanticStatus.UNRESOLVED));
                boolean scalar=productComplete&&site.status()==SpringInjectionSites.Status.ACQUIRED&&type.kind()==JavaType.Kind.DECLARED
                        &&type.status()==SemanticStatus.RESOLVED&&type.target().isPresent()&&type.components().isEmpty()
                        &&(site.kind()==SpringInjectionSites.Kind.CONSTRUCTOR&&selectedParameters.contains(site.element())
                           ||site.kind()==SpringInjectionSites.Kind.BEAN_PARAMETER&&methods.containsKey(site.owner())&&methods.get(site.owner()).complete()
                           ||site.kind()==SpringInjectionSites.Kind.FIELD||site.kind()==SpringInjectionSites.Kind.METHOD)
                        &&source.annotations(site.element()).stream().allMatch(a -> a.name().filter(SITE_METADATA::contains).isPresent())
                        &&site.qualifier().filter(String::isBlank).isEmpty()
                        &&Optional.ofNullable(source.typeName(type.target().orElseThrow())).filter(n -> !DEFERRED_TYPES.contains(n)).isPresent();
                if(!scalar)acquisitionIssues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,
                        site.element().value(),List.of(site.evidence())));
                var kind=switch(site.kind()) {
                    case CONSTRUCTOR -> InjectionPoint.SiteKind.CONSTRUCTOR_PARAMETER;
                    case FIELD -> InjectionPoint.SiteKind.FIELD;
                    case METHOD -> InjectionPoint.SiteKind.METHOD_PARAMETER;
                    case BEAN_PARAMETER -> InjectionPoint.SiteKind.BEAN_PARAMETER;
                    default -> InjectionPoint.SiteKind.OTHER;
                };
                var method=site.kind()==SpringInjectionSites.Kind.METHOD?source.owner(site.element()):Optional.<EntityIdentity>empty();
                var point=new InjectionPoint(build.identity(),method.orElse(site.owner()).value(),kind,site.element().value(),site.evidence());
                var dependency=new InjectionBindingPlan.Dependency(point,Optional.of(owner.identity()),type,InjectionBindingPlan.Shape.SINGLE,
                        InjectionBindingPlan.Mode.AUTOWIRE,site.required()?InjectionBindingPlan.Required.REQUIRED:InjectionBindingPlan.Required.OPTIONAL,
                        // Source spelling does not establish reflection parameter-name availability.
                        site.kind()==SpringInjectionSites.Kind.FIELD?site.name().map(InjectionBindingPlan.Name::of).orElseGet(InjectionBindingPlan.Name::unknown):InjectionBindingPlan.Name.unknown(),
                        site.qualifier().map(q -> q.isBlank()?InjectionBindingPlan.Name.unknown():InjectionBindingPlan.Name.of(q))
                                .orElseGet(InjectionBindingPlan.Name::absent),
                        site.qualifier().isPresent()?TRUE:FALSE,false,false,false,false,FALSE,
                        scalar?InjectionBindingPlan.Normalization.COMPLETE:InjectionBindingPlan.Normalization.INCOMPLETE,site.evidence());
                dependencies.add(dependency);
                method.ifPresent(m -> {
                    String key=product==null?m.value():m.value()+":"+owner.identity().value();
                    methodDependencies.computeIfAbsent(key,k -> new HashMap<>()).put(site.element(),dependency);
                    groupDeclarations.put(key,m);
                });
                acquiredDependencies.computeIfAbsent(site.element(),k -> new ArrayList<>()).add(dependency.identity());
                source.owner(site.element()).ifPresent(ownerElement -> acquiredDependencies.computeIfAbsent(ownerElement,k -> new ArrayList<>()).add(dependency.identity()));
                for(var entry:byDeclaration.entrySet()) {
                    if(matches.size()>=limit*limit)break; // Missing match rows retain UNKNOWN in M4C.
                    var candidateMethod=methods.get(entry.getKey());
                    boolean complete=scalar&&(candidateMethod==null?metadataComplete(source,entry.getKey()):candidateMethod.complete()&&metadataComplete(source,candidateMethod.owner()));
                    var compatible=complete?(entry.getValue().exposedTypes().stream().anyMatch(exposed -> source.ancestry(exposed,limit).contains(type.target().orElseThrow()))?TRUE:FALSE):LogicalValue.UNKNOWN;
                    var qualifier=site.qualifier().isEmpty()?TRUE:qualifier(source,entry.getKey(),entry.getValue(),site.qualifier().orElseThrow());
                    matches.add(new BindingEvidence.Match(dependency.identity(),entry.getValue().identity(),BindingEvidence.Lane.DIRECT,
                            compatible,compatible,compatible,qualifier,BindingEvidence.Knowledge.KNOWN,proof));
                }
            }
        }
        var groups=new ArrayList<InjectionBindingPlan.Group>();
        for(var entry:methodDependencies.entrySet()) {
            // Declaration coordinates establish parameter order; hash/relationship order does not.
            var ordered=entry.getValue().entrySet().stream().sorted(Comparator.comparing(e ->
                    source.declarations().get(e.getKey()).entity().declaration().orElseThrow())).map(Map.Entry::getValue).toList();
            groups.add(new InjectionBindingPlan.Group(entry.getKey(),ordered.stream().map(InjectionBindingPlan.Dependency::identity).toList(),
                    ordered.stream().filter(d -> d.required()==InjectionBindingPlan.Required.OPTIONAL).map(InjectionBindingPlan.Dependency::identity).toList(),
                    source.evidence(groupDeclarations.get(entry.getKey()))));
        }
        boolean withinBudget=dependencies.size()<=limit&&(long)dependencies.size()*byDeclaration.size()<=limit*limit;
        boolean constructorClosure=constructors.rows().stream().filter(r -> byDeclaration.containsKey(r.type()))
                .noneMatch(r -> r.status()==ConstructorInjectionIngestion.Status.UNKNOWN);
        var environment=new InjectionBindingPlan.Environment(sites.issues().isEmpty()&&acquisitionIssues.isEmpty()&&bounded&&withinBudget&&constructorClosure?COMPLETE:UNKNOWN,
                scope.noResolvableDependencies(),scope.noPostRegistrationMutation(),InjectionBindingPlan.ComparatorPolicy.NONE,
                scope.registrationOrder().stream().map(byDeclaration::get).flatMap(c -> c.declaredNameKey().primary().stream()).distinct().toList(),scope.order(),scope.evidence());
        var obligations=new ArrayList<InjectionBindingPlan.ObligationBinding>();
        for(var obligation:inventory.obligations())if(obligation.primaryMechanism().startsWith("spring.injection."))
            raw.get(obligation.rawObservationIdentity()).owner().ifPresent(owner -> {
                var acquired=acquiredDependencies.getOrDefault(owner,List.of()).stream().distinct().toList();
                if(!acquired.isEmpty())obligations.add(new InjectionBindingPlan.ObligationBinding(obligation.identity(),acquired,proof));
            });
        var binding=new InjectionBindingPlan(registration,dependencies,bindingDefinitions,matches,groups,obligations,environment,
                new InjectionBindingPlan.Limits(limit,limit*limit,limit*limit,limit*limit));
        var gaps=new TreeSet<>(components.gaps());gaps.addAll(constructors.gaps());gaps.addAll(sites.gaps());gaps.addAll(lowering.capabilityGaps());gaps.addAll(discovery.capabilityGaps());
        gaps.addAll(UniversalSpringEvidence.gaps(build.snapshotIdentity(),input,acquisitionIssues));
        if(!bounded||!withinBudget)gaps.addAll(UniversalSpringEvidence.gaps(build.snapshotIdentity(),input,List.of(
                new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"source-to-plan",List.of(proof)))));
        return new Result(input,inventory,lowering,binding,List.copyOf(gaps));
    }
    private static boolean metadataComplete(SpringSourceEvidence source,EntityIdentity type) {
        return source.frontend().state()==com.evolution.analysis.frontend.FrontendResult.State.COMPLETED
                &&BeanMethodIngestion.configurationMetadata(source,type)
                &&source.annotations(type).stream().allMatch(a -> a.name().filter(n -> COMPONENT_METADATA.contains(n)||BeanMethodIngestion.CONDITIONS.contains(n)).isPresent())
                &&source.ancestry(type,2).size()==1;
    }
    private static LogicalValue flag(SpringSourceEvidence source,EntityIdentity type,String name) {
        return source.annotations(type).stream().anyMatch(a -> a.name().filter(name::equals).isPresent())?TRUE:FALSE;
    }
    private static LogicalValue qualifier(SpringSourceEvidence source,EntityIdentity type,BeanDefinitionCandidate candidate,String expected) {
        if(candidate.declaredNameKey().primary().filter(expected::equals).isPresent())return TRUE;
        for(var annotation:source.annotations(type))if(annotation.name().filter("org.springframework.beans.factory.annotation.Qualifier"::equals).isPresent()) {
            try {if(expected.equals(LiteralConditionAnnotation.string(LiteralConditionAnnotation.parse(annotation.use().spelling()),"value","")))return TRUE;}
            catch(IllegalArgumentException unknown) {return LogicalValue.UNKNOWN;}
        }
        return candidate.producer().producerKind()==BeanProducer.Kind.BEAN_METHOD||metadataComplete(source,type)?FALSE:LogicalValue.UNKNOWN;
    }
    private SourceToSpringPlan() {}
}
