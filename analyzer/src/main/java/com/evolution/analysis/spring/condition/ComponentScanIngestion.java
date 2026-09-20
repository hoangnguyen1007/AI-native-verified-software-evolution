package com.evolution.analysis.spring.condition;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import static com.evolution.analysis.ingestion.IngestionEvidence.Reason.*;

/** Source-evidenced package membership, separate from activation, definition order and bean creation. */
public final class ComponentScanIngestion {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("spring.component-scan-ingestion","m4u.1");
    public enum Status { INCLUDED, OUTSIDE_ROOT, NOT_COMPONENT, INELIGIBLE, UNKNOWN }
    public record Row(EntityIdentity type,Status status,List<String> roots,Optional<String> beanName,ConditionEvidence evidence) {
        public Row { roots=List.copyOf(roots);Objects.requireNonNull(beanName); }
    }
    public record Result(ContentDigest inputIdentity,AnalysisIdentity analysis,SnapshotIdentity snapshot,ContentDigest frontendEvidence,ContentDigest frameworkEvidence,List<Row> rows,
                         List<IngestionEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result { rows=List.copyOf(rows);issues=List.copyOf(issues);gaps=List.copyOf(gaps); }
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
        public List<BeanDefinitionCandidate> candidates(SpringBuildContext build,String container) {
            if(!build.snapshotIdentity().equals(snapshot)||!build.analysisIdentity().equals(analysis))throw new IllegalArgumentException("Component evidence belongs to another build");
            return rows.stream().filter(r->r.status()==Status.INCLUDED).map(r->new BeanDefinitionCandidate(
                    new BeanProducer(build.identity(),container,r.evidence(),BeanProducer.Kind.COMPONENT_CLASS,r.type().value(),"source-package-scan"),
                    r.type().value(),r.beanName().map(BeanDefinitionCandidate.Names::exact).orElseGet(BeanDefinitionCandidate.Names::unresolved),List.of(r.type()))).toList();
        }
    }
    private static final Set<String> STEREOTYPES=Set.of("org.springframework.stereotype.Component","org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository","org.springframework.stereotype.Controller","org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration","org.springframework.boot.autoconfigure.SpringBootApplication");
    private static final String BOOT="org.springframework.boot.autoconfigure.SpringBootApplication",SCAN="org.springframework.context.annotation.ComponentScan";
    private record Annotation(AnnotationUseRecord use,Optional<EntityIdentity> target,Optional<String> trustedName){}
    public Result scan(AnalysisManifest manifest,FrontendResult frontend,SpringFrameworkEvidence framework,int maxTypes) {
        if(maxTypes<1)throw new IllegalArgumentException("Positive component limit required");
        if(!manifest.identity().equals(frontend.analysis()))throw new IllegalArgumentException("Frontend belongs to another analysis");
        var snapshot=manifest.snapshot();
        for(var artifact:framework.artifacts())if(manifest.classpath().stream().noneMatch(c->c.logicalName().equals(artifact.classpathLogicalName())&&c.contentDigest().equals(artifact.contentDigest())))
            throw new IllegalArgumentException("Framework evidence is not in the exact analysis classpath");
        var identity=IngestionEvidence.digest(List.of(PROVIDER,manifest.identity(),IngestionEvidence.digest(frontend),framework.identity(),maxTypes));
        Map<EntityIdentity,DeclarationRecord> declarations=new HashMap<>();frontend.declarations().forEach(d->declarations.put(d.entity().identity(),d));
        Map<EntityIdentity,TypeDeclarationRecord> shapes=new HashMap<>();frontend.typeDeclarations().forEach(d->shapes.put(d.type(),d));
        Map<EntityIdentity,List<Annotation>> annotations=new HashMap<>();var issues=new ArrayList<IngestionEvidence.Issue>();
        if(!framework.completeClasspath())issue(issues,ANNOTATION_EVIDENCE_MISSING,"framework-context",identity);
        for(var use:frontend.annotations()) {
            var edges=frontend.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.annotated-with")&&o.span().equals(use.span())
                    &&o.relationship().source().equals(use.owner())&&o.status()==SemanticStatus.RESOLVED&&o.relationship().target() instanceof RelationshipTarget.Resolved).toList();
            Optional<EntityIdentity> target=edges.size()==1?Optional.of(((RelationshipTarget.Resolved)edges.getFirst().relationship().target()).target()):Optional.empty();
            Optional<String> trusted=target.map(declarations::get).filter(Objects::nonNull).map(DeclarationRecord::entity)
                    .filter(e->e.origin()==EntityOrigin.DEPENDENCY&&framework.artifacts().stream().anyMatch(a->a.entityScope().equals(e.stableScope())
                            &&framework.verifiedArtifact(a)))
                    .flatMap(e->JavaTypeName.fromCanonical(e.canonicalName())).map(JavaTypeName::qualifiedName);
            annotations.computeIfAbsent(use.owner(),k->new ArrayList<>()).add(new Annotation(use,target,trusted));
        }
        Set<String> roots=new TreeSet<>();Set<EntityIdentity> bootstraps=new HashSet<>();boolean unknownScan=!framework.completeClasspath();
        for(var entry:annotations.entrySet()) {
            boolean directScan=entry.getValue().stream().anyMatch(a->a.trustedName().filter(SCAN::equals).isPresent());
            for(var annotation:entry.getValue()) {
                String name=annotation.trustedName().orElse("");
                if(!name.equals(SCAN)&&!name.equals(BOOT))continue;
                if(!shapes.containsKey(entry.getKey()) || shapes.get(entry.getKey()).kind()==TypeDeclarationRecord.Kind.ANNOTATION) {
                    unknownScan=true;issue(issues,SCAN_ROOT_UNRESOLVED,annotation.use().canonicalName(),identity);continue;
                }
                bootstraps.add(entry.getKey());if(name.equals(BOOT)&&directScan)continue;
                try {
                    var attributes=LiteralConditionAnnotation.parse(annotation.use().spelling());
                    Set<String> allowed=name.equals(BOOT)?Set.of("scanBasePackages","scanBasePackageClasses","proxyBeanMethods"):Set.of("value","basePackages","basePackageClasses","useDefaultFilters","lazyInit");
                    if(!allowed.containsAll(attributes.keySet())||!LiteralConditionAnnotation.bool(attributes,"useDefaultFilters",true))throw new IllegalArgumentException();
                    var packages=new ArrayList<String>();packages.addAll(LiteralConditionAnnotation.strings(attributes,name.equals(BOOT)?"scanBasePackages":"basePackages",List.of()));
                    if(name.equals(SCAN)) {
                        var value=LiteralConditionAnnotation.strings(attributes,"value",List.of());
                        if(!value.isEmpty()&&!packages.isEmpty()&&!value.equals(packages))throw new IllegalArgumentException();
                        packages.addAll(value);
                    }
                    for(String marker:LiteralConditionAnnotation.classes(attributes,name.equals(BOOT)?"scanBasePackageClasses":"basePackageClasses")) {
                        var matches=frontend.types().stream().filter(t->contains(annotation.use().span(),t.span())&&t.type().spelling().equals(marker)
                                &&t.type().status()==SemanticStatus.RESOLVED&&t.type().kind()==JavaType.Kind.DECLARED).map(t->t.type().target().orElseThrow()).distinct().toList();
                        if(matches.size()!=1)throw new IllegalArgumentException();
                        packages.add(JavaTypeName.fromCanonical(declarations.get(matches.getFirst()).entity().canonicalName()).orElseThrow().packageName());
                    }
                    if(packages.isEmpty())packages.add(JavaTypeName.fromCanonical(declarations.get(entry.getKey()).entity().canonicalName()).orElseThrow().packageName());
                    for(String pack:packages) {
                        if(pack.contains("$")||pack.contains("*")||pack.contains("?"))throw new IllegalArgumentException();
                        if(pack.isEmpty())roots.add("");else for(String value:pack.split("[,;\\s]+")) {JavaSymbolName.packageName(value);roots.add(value);}
                    }
                }catch(IllegalArgumentException|NoSuchElementException failure){unknownScan=true;issue(issues,SCAN_FILTER_UNSUPPORTED,annotation.use().canonicalName(),identity);}
            }
        }
        if(bootstraps.size()>1) {unknownScan=true;issue(issues,SCAN_ROOT_UNRESOLVED,"multiple-bootstrap-contexts",identity);}
        var stereotypes=new HashSet<EntityIdentity>();var incompleteMetadata=new HashSet<EntityIdentity>();
        for(var entry:annotations.entrySet())for(var annotation:entry.getValue()) {
            if(annotation.trustedName().filter(STEREOTYPES::contains).isPresent())annotation.target().ifPresent(stereotypes::add);
            if(incompleteLeaf(annotation,declarations))incompleteMetadata.add(entry.getKey());
        }
        stereotypes=propagate(annotations,stereotypes);incompleteMetadata=propagate(annotations,incompleteMetadata);
        var types=frontend.declarations().stream().filter(d->d.entity().origin()==EntityOrigin.PROJECT&&d.entity().kind()==EntityKind.TYPE).toList();
        var rows=new ArrayList<Row>();
        for(int index=0;index<types.size();index++) {
            var type=types.get(index);var entity=type.entity();var typeName=JavaTypeName.fromCanonical(entity.canonicalName());
            var evidence=new ConditionEvidence.Derived(List.of(identity,IngestionEvidence.digest(type)),PROVIDER,"component-type:"+entity.identity().value());
            var matched=typeName.map(n->roots.stream().filter(p->p.isEmpty()||n.packageName().equals(p)||n.packageName().startsWith(p+".")).toList()).orElse(List.of());
            Status status;Optional<String> beanName=Optional.empty();
            var onType=annotations.getOrDefault(entity.identity(),List.of());
            boolean component=stereotypes.contains(entity.identity());
            boolean uncertainAnnotation=incompleteMetadata.contains(entity.identity());
            var shape=shapes.get(entity.identity());
            if(index>=maxTypes){status=Status.UNKNOWN;issue(issues,INPUT_LIMIT,entity.identity().value(),identity);}
            else if(uncertainAnnotation){status=Status.UNKNOWN;issue(issues,ANNOTATION_EVIDENCE_MISSING,entity.identity().value(),identity);}
            else if(!component)status=Status.NOT_COMPONENT;
            else if(shape==null){status=Status.UNKNOWN;issue(issues,COMPONENT_ELIGIBILITY_UNKNOWN,entity.identity().value(),identity);}
            else if(shape.abstractType()&&type.entity().declaration().isPresent()&&annotations.values().stream().flatMap(Collection::stream)
                    .anyMatch(a->a.trustedName().filter("org.springframework.beans.factory.annotation.Lookup"::equals).isPresent()&&contains(type.entity().declaration().orElseThrow(),a.use().span()))) {
                status=Status.UNKNOWN;issue(issues,COMPONENT_ELIGIBILITY_UNKNOWN,entity.identity().value(),identity);
            }
            else if(typeName.isEmpty() || !shape.independent() || shape.abstractType()
                    || Set.of(TypeDeclarationRecord.Kind.INTERFACE,TypeDeclarationRecord.Kind.ANNOTATION).contains(shape.kind()))status=Status.INELIGIBLE;
            else if(matched.isEmpty()&&!bootstraps.contains(entity.identity()))status=unknownScan?Status.UNKNOWN:Status.OUTSIDE_ROOT;
            else if(unknownScan){status=Status.UNKNOWN;issue(issues,SCAN_ROOT_UNRESOLVED,entity.identity().value(),identity);}
            else {
                status=Status.INCLUDED;var explicitNames=new TreeSet<String>();boolean nameUnknown=false;
                for(var annotation:onType)if(annotation.target().isPresent()&&stereotypes.contains(annotation.target().orElseThrow()))try {
                    var values=LiteralConditionAnnotation.parse(annotation.use().spelling());
                    if(annotation.trustedName().isEmpty()) {
                        // Attribute defaults and AliasFor require a separate semantic metadata provider.
                        nameUnknown=true;continue;
                    }
                    String supplied=LiteralConditionAnnotation.string(values,"value","");if(!supplied.isBlank())explicitNames.add(supplied);
                }catch(IllegalArgumentException failure){nameUnknown=true;}
                if(explicitNames.size()>1||nameUnknown)issue(issues,BEAN_NAME_UNRESOLVED,entity.identity().value(),identity);
                else if(explicitNames.size()==1)beanName=Optional.of(explicitNames.first());
                else {String simple=typeName.orElseThrow().binarySimpleName().replace('$','.');beanName=Optional.of(simple.length()>1&&Character.isUpperCase(simple.charAt(0))&&Character.isUpperCase(simple.charAt(1))?simple:Character.toLowerCase(simple.charAt(0))+simple.substring(1));}
            }
            rows.add(new Row(entity.identity(),status,matched,beanName,evidence));
        }
        if(frontend.state()!=FrontendResult.State.COMPLETED)issue(issues,SOURCE_INCOMPLETE,"frontend",identity);
        var sorted=issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
        return new Result(identity,frontend.analysis(),snapshot.identity(),IngestionEvidence.digest(frontend),framework.identity(),rows,sorted,IngestionEvidence.gaps(snapshot.identity(),PROVIDER,identity,sorted));
    }
    private static HashSet<EntityIdentity> propagate(Map<EntityIdentity,List<Annotation>> annotations,Set<EntityIdentity> initial) {
        Map<EntityIdentity,List<EntityIdentity>> users=new HashMap<>();
        annotations.forEach((owner,uses)->uses.forEach(a->a.target().ifPresent(t->users.computeIfAbsent(t,k->new ArrayList<>()).add(owner))));
        var result=new HashSet<>(initial);var queue=new ArrayDeque<>(initial);
        while(!queue.isEmpty())for(var owner:users.getOrDefault(queue.removeFirst(),List.of()))if(result.add(owner))queue.addLast(owner);
        return result;
    }
    private static boolean incompleteLeaf(Annotation annotation,Map<EntityIdentity,DeclarationRecord> declarations) {
        if(annotation.trustedName().filter(STEREOTYPES::contains).isPresent())return false;
        if(annotation.target().isEmpty())return true;
        var target=annotation.target().orElseThrow();
        var declaration=declarations.get(target);
        if(declaration==null)return true;
        if(declaration.entity().origin()!=EntityOrigin.PROJECT) {
            String name=JavaTypeName.fromCanonical(declaration.entity().canonicalName()).map(JavaTypeName::qualifiedName).orElse("");
            if(declaration.entity().stableScope().equals(EntityScope.external(EntityOrigin.DEPENDENCY,"org.projectlombok:lombok:1.18.46@jar",
                    new ContentDigest("sha256:01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126"))) && name.startsWith("lombok."))return false;
            return !name.startsWith("java.lang.") && !annotation.trustedName().filter(n -> Set.of(SCAN,
                    "org.springframework.context.annotation.Lazy","org.springframework.context.annotation.Primary",
                    "org.springframework.context.annotation.Profile","org.springframework.context.annotation.DependsOn",
                    "org.springframework.context.annotation.Scope","org.springframework.beans.factory.annotation.Qualifier").contains(n)).isPresent();
        }
        return false;
    }
    private static void issue(List<IngestionEvidence.Issue> issues,IngestionEvidence.Reason reason,String subject,ContentDigest input){issues.add(new IngestionEvidence.Issue(reason,subject,List.of(input)));}
    private static boolean contains(SourceSpan outer,SourceSpan inner) {
        return outer.document().equals(inner.document())&&(outer.startLine()<inner.startLine()||outer.startLine()==inner.startLine()&&outer.startColumn()<=inner.startColumn())
                &&(outer.endLine()>inner.endLine()||outer.endLine()==inner.endLine()&&outer.endColumn()>=inner.endColumn());
    }
}
