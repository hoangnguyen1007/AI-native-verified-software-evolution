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
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("spring.constructor-ingestion","m4uv2.2-injection-v1");
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
        var relations=java.util.stream.Stream.concat(frontend.occurrences().stream().filter(o->o.status()==SemanticStatus.RESOLVED).map(RelationshipOccurrence::relationship),
                frontend.derivedRelationships().stream().filter(o->o.status()==SemanticStatus.RESOLVED).map(DerivedRelationshipRecord::relationship)).toList();
        var issues=new ArrayList<IngestionEvidence.Issue>();var rows=new ArrayList<Row>();int parametersSeen=0;
        boolean frameworkKnown=framework.completeClasspath()&&framework.artifacts().stream().anyMatch(a->a.artifactId().equals("spring-beans")&&framework.verifiedArtifact(a));
        boolean providerKnown=Set.of(new VersionedIdentifier("frontend.javaparser","3.28.2-m4u.1"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m4uv2.2"),
                new VersionedIdentifier("frontend.javaparser","3.28.2-m4uv2.2-injection-v1")).contains(frontend.frontend());
        for(var component:discovery.rows()) {
            var evidence=new ConditionEvidence.Derived(List.of(identity,component.evidence().identity()),PROVIDER,"constructor-selection:"+component.type().value());
            if(component.status()!=ComponentScanIngestion.Status.INCLUDED) {
                rows.add(new Row(component.type(),component.status()==ComponentScanIngestion.Status.UNKNOWN?Status.UNKNOWN:Status.NOT_SELECTED,Optional.empty(),List.of(),evidence));continue;
            }
            var owner=declarations.get(component.type());
            var constructors=targets(relations,component.type(),"java.declares").stream().map(declarations::get).filter(Objects::nonNull)
                    .filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR).sorted().toList();
            boolean supportedShape=frontend.typeDeclarations().stream().anyMatch(t->t.type().equals(component.type())&&Set.of(TypeDeclarationRecord.Kind.CLASS,TypeDeclarationRecord.Kind.RECORD).contains(t.kind()));
            boolean incomplete=!frameworkKnown || !providerKnown || !supportedShape || owner==null || owner.status()!=SemanticStatus.RESOLVED
                    || owner.entity().declaration().isEmpty() || frontend.diagnostics().stream().anyMatch(d->d.span().isPresent()
                        && overlaps(owner.entity().declaration().orElseThrow(),d.span().orElseThrow()));
            if(constructors.size()!=1 || incomplete) {
                issue(issues,COMPONENT_ELIGIBILITY_UNKNOWN,component.type().value(),identity);
                rows.add(new Row(component.type(),Status.UNKNOWN,Optional.empty(),List.of(),evidence));continue;
            }
            var constructor=constructors.getFirst();var parameters=new ArrayList<Parameter>();
            if(constructor.status()!=SemanticStatus.RESOLVED)incomplete=true;
            var parameterEntities=targets(relations,constructor.entity().identity(),"java.has-parameter").stream().map(declarations::get).filter(Objects::nonNull).toList();
            for(var parameter:parameterEntities) {
                if(++parametersSeen>maxParameters){incomplete=true;issue(issues,INPUT_LIMIT,component.type().value(),identity);continue;}
                var types=frontend.types().stream().filter(t->t.owner().equals(Optional.of(parameter.entity().identity()))&&t.role().value().equals("java.parameter-type")).toList();
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
    private static List<EntityIdentity> targets(List<SemanticRelationship> relations,EntityIdentity owner,String kind) {
        return relations.stream().filter(r->r.source().equals(owner)&&r.kind().value().equals(kind))
                .flatMap(r->r.target() instanceof RelationshipTarget.Resolved t?java.util.stream.Stream.of(t.target()):java.util.stream.Stream.empty()).distinct().sorted().toList();
    }
    private static boolean overlaps(SourceSpan a,SourceSpan b) {
        return a.document().equals(b.document()) && before(a.startLine(),a.startColumn(),b.endLine(),b.endColumn())
                && before(b.startLine(),b.startColumn(),a.endLine(),a.endColumn());
    }
    private static boolean before(int line,int column,int otherLine,int otherColumn){return line<otherLine||line==otherLine&&column<otherColumn;}
    private static void issue(List<IngestionEvidence.Issue> issues,IngestionEvidence.Reason reason,String subject,ContentDigest identity){issues.add(new IngestionEvidence.Issue(reason,subject,List.of(identity)));}
}
