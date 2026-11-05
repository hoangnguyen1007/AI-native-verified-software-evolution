package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.binding.InjectionPoint;
import java.util.*;
import static com.evolution.analysis.ingestion.IngestionEvidence.Reason.*;

/** Constructor-site acquisition. Candidate matching and conditional activation remain downstream. */
public final class ConstructorInjectionIngestion {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("spring.constructor-ingestion","m8.4-local-fragment-v3");
    public enum Status { SELECTED, NOT_SELECTED, UNKNOWN }
    public record Parameter(EntityIdentity constructor,EntityIdentity parameter,int index,JavaType type,ConditionEvidence evidence) {
        public Parameter {Objects.requireNonNull(constructor);Objects.requireNonNull(parameter);Objects.requireNonNull(type);Objects.requireNonNull(evidence);if(index<0)throw new IllegalArgumentException("Negative parameter index");}
        InjectionPoint point(SpringBuildContext build) {
            return new InjectionPoint(build.identity(),constructor.value(),InjectionPoint.SiteKind.CONSTRUCTOR_PARAMETER,Integer.toString(index),evidence);
        }
    }
    public record Row(EntityIdentity type,Status status,Optional<EntityIdentity> constructor,List<Parameter> parameters,ConditionEvidence evidence) {
        public Row {parameters=List.copyOf(parameters);}
    }
    public record Result(ContentDigest inputIdentity,AnalysisIdentity analysis,SnapshotIdentity snapshot,List<Row> rows,
                         List<IngestionEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {rows=List.copyOf(rows);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
        public List<InjectionPoint> points(SpringBuildContext build) {
            if(!analysis.equals(build.analysisIdentity())||!snapshot.equals(build.snapshotIdentity()))throw new IllegalArgumentException("Foreign injection build context");
            return rows.stream().filter(r->r.status()==Status.SELECTED).flatMap(r->r.parameters().stream()).map(p->p.point(build)).toList();
        }
    }
    public Result ingest(FrontendResult frontend,ComponentScanIngestion.Result discovery,SpringFrameworkEvidence framework,int maxParameters) {
        if(maxParameters<1)throw new IllegalArgumentException("Positive parameter limit required");
        if(!frontend.analysis().equals(discovery.analysis()) || !IngestionEvidence.digest(frontend).equals(discovery.frontendEvidence())||!framework.identity().equals(discovery.frameworkEvidence()))
            throw new IllegalArgumentException("Constructor and component evidence differ");
        var identity=IngestionEvidence.digest(List.of(PROVIDER,discovery.frontendEvidence(),discovery.identity(),framework.identity(),maxParameters));
        Map<EntityIdentity,DeclarationRecord> declarations=new HashMap<>();frontend.declarations().forEach(d->declarations.put(d.entity().identity(),d));
        var unrelatedCallDiagnostics=new HashSet<Diagnostic>();
        for(var occurrence:frontend.occurrences()){
            var owner=declarations.get(occurrence.relationship().source());
            if(occurrence.status()==SemanticStatus.UNRESOLVED&&occurrence.relationship().kind().value().equals("java.calls")
                    &&owner!=null&&owner.entity().origin()==EntityOrigin.PROJECT
                    &&Set.of(EntityKind.METHOD,EntityKind.CONSTRUCTOR,EntityKind.LAMBDA,EntityKind.INITIALIZER).contains(owner.entity().kind()))
                unrelatedCallDiagnostics.addAll(occurrence.diagnostics());
        }
        var relations=java.util.stream.Stream.concat(frontend.occurrences().stream().filter(o->o.status()==SemanticStatus.RESOLVED).map(RelationshipOccurrence::relationship),
                frontend.derivedRelationships().stream().filter(o->o.status()==SemanticStatus.RESOLVED).map(DerivedRelationshipRecord::relationship)).toList();
        var issues=new ArrayList<IngestionEvidence.Issue>();var rows=new ArrayList<Row>();int parametersSeen=0;
        var targets=new HashMap<EntityIdentity,Map<String,Set<EntityIdentity>>>();
        for(var relation:relations)if(relation.target() instanceof RelationshipTarget.Resolved target)
            targets.computeIfAbsent(relation.source(),key->new HashMap<>()).computeIfAbsent(relation.kind().value(),key->new TreeSet<>()).add(target.target());
        var parameterTypes=new HashMap<EntityIdentity,List<TypeUseRecord>>();
        for(var type:frontend.types())if(type.owner().isPresent()&&type.role().value().equals("java.parameter-type"))parameterTypes.computeIfAbsent(type.owner().orElseThrow(),key->new ArrayList<>()).add(type);
        var supportedShapes=new HashSet<EntityIdentity>();frontend.typeDeclarations().stream().filter(t->Set.of(TypeDeclarationRecord.Kind.CLASS,TypeDeclarationRecord.Kind.RECORD).contains(t.kind())).forEach(t->supportedShapes.add(t.type()));
        boolean frameworkKnown=framework.declarationFragmentKnown()&&framework.artifacts().stream().anyMatch(a->a.artifactId().equals("spring-beans")&&framework.verifiedArtifact(a));
        boolean providerKnown=Set.of(new VersionedIdentifier("frontend.javaparser","3.28.2-m4u.1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m4uv2.2"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m4uv2.2-injection-v1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m7.2-platform-v1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m7.2-written-types-v2"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-captured-input-v1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-expression-types-v1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-expression-types-v2"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-expression-types-v3"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-native-constructors-v1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-native-wiring-v1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-source-recovery-v2"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-lombok-candidates-v3"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-recovery-v4"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-bounded-bodies-v5"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-generic-recovery-v6"),
                        new VersionedIdentifier("frontend.javaparser","3.28.2-m8.4-lombok-http-v7")).contains(frontend.frontend());
        for(var component:discovery.rows()) {
            var evidence=new ConditionEvidence.Derived(List.of(identity,component.evidence().identity()),PROVIDER,"constructor-selection:"+component.type().value());
            if(component.status()!=ComponentScanIngestion.Status.INCLUDED) {
                rows.add(new Row(component.type(),component.status()==ComponentScanIngestion.Status.UNKNOWN?Status.UNKNOWN:Status.NOT_SELECTED,Optional.empty(),List.of(),evidence));continue;
            }
            var owner=declarations.get(component.type());
            var constructors=targets.getOrDefault(component.type(),Map.of()).getOrDefault("java.declares",Set.of()).stream().map(declarations::get).filter(Objects::nonNull)
                    .filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR).sorted().toList();
            boolean supportedShape=supportedShapes.contains(component.type());
            boolean incomplete=!frameworkKnown || !providerKnown || !supportedShape || owner==null || owner.status()!=SemanticStatus.RESOLVED
                    || owner.entity().declaration().isEmpty() || frontend.diagnostics().stream().anyMatch(d->!unrelatedCallDiagnostics.contains(d)&&d.span().isPresent()
                        && overlaps(owner.entity().declaration().orElseThrow(),d.span().orElseThrow()));
            if(constructors.size()!=1 || incomplete) {
                issue(issues,COMPONENT_ELIGIBILITY_UNKNOWN,component.type().value(),identity);
                rows.add(new Row(component.type(),Status.UNKNOWN,Optional.empty(),List.of(),evidence));continue;
            }
            var constructor=constructors.getFirst();var parameters=new ArrayList<Parameter>();
            if(constructor.status()!=SemanticStatus.RESOLVED)incomplete=true;
            var parameterEntities=targets.getOrDefault(constructor.entity().identity(),Map.of()).getOrDefault("java.has-parameter",Set.of()).stream().map(declarations::get).filter(Objects::nonNull).toList();
            for(var parameter:parameterEntities) {
                if(++parametersSeen>maxParameters){incomplete=true;issue(issues,INPUT_LIMIT,component.type().value(),identity);continue;}
                var types=parameterTypes.getOrDefault(parameter.entity().identity(),List.of());
                try {
                    String key=parameter.entity().canonicalName();int index=Integer.parseInt(key.substring(key.lastIndexOf(',')+1,key.length()-1));
                    if(types.size()!=1 || types.getFirst().type().status()!=SemanticStatus.RESOLVED)throw new IllegalArgumentException();
                    var parameterEvidence=new ConditionEvidence.Derived(List.of(evidence.identity(),IngestionEvidence.digest(parameter),IngestionEvidence.digest(types.getFirst())),PROVIDER,"parameter:"+index);
                    parameters.add(new Parameter(constructor.entity().identity(),parameter.entity().identity(),index,types.getFirst().type(),parameterEvidence));
                }catch(IllegalArgumentException failure){incomplete=true;issue(issues,SOURCE_INCOMPLETE,parameter.entity().identity().value(),identity);}
            }
            parameters.sort(Comparator.comparingInt(Parameter::index));
            for(int i=0;i<parameters.size();i++)if(parameters.get(i).index()!=i)incomplete=true;
            if(incomplete)issue(issues,SOURCE_INCOMPLETE,constructor.entity().identity().value(),identity);
            rows.add(new Row(component.type(),incomplete?Status.UNKNOWN:Status.SELECTED,Optional.of(constructor.entity().identity()),parameters,evidence));
        }
        var sorted=issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
        var gaps=new TreeSet<>(discovery.gaps());gaps.addAll(IngestionEvidence.gaps(discovery.snapshot(),PROVIDER,identity,sorted));
        return new Result(identity,frontend.analysis(),discovery.snapshot(),rows,sorted,List.copyOf(gaps));
    }
    private static boolean overlaps(SourceSpan a,SourceSpan b) {
        return a.document().equals(b.document()) && before(a.startLine(),a.startColumn(),b.endLine(),b.endColumn())
                && before(b.startLine(),b.startColumn(),a.endLine(),a.endColumn());
    }
    private static boolean before(int line,int column,int otherLine,int otherColumn){return line<otherLine||line==otherLine&&column<otherColumn;}
    private static void issue(List<IngestionEvidence.Issue> issues,IngestionEvidence.Reason reason,String subject,ContentDigest identity){issues.add(new IngestionEvidence.Issue(reason,subject,List.of(identity)));}
}
