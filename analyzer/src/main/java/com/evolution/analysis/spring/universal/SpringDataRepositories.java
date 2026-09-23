package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;

/** Spring Data interface synthesis preserves source identity and exposes candidates, never invented proxy classes. */
public final class SpringDataRepositories {
    public enum Status { CANDIDATE, NO_REPOSITORY_BEAN, NOT_REPOSITORY, OUTSIDE_SCAN, UNKNOWN }
    public record Scan(String store,List<String> packages,boolean nested,ConditionEvidence evidence) {
        public Scan {ContractChecks.text(store,"repository store");packages=packages.stream().distinct().sorted().toList();Objects.requireNonNull(evidence);}
    }
    public record Row(EntityIdentity repository,Status status,Optional<String> store,Optional<String> beanName,
                      List<EntityIdentity> exposedTypes,ConditionEvidence evidence) {
        public Row {exposedTypes=exposedTypes.stream().distinct().sorted().toList();}
    }
    public record Result(ContentDigest inputIdentity,AnalysisIdentity analysis,SnapshotIdentity snapshot,List<Scan> scans,List<Row> rows,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {scans=List.copyOf(scans);rows=List.copyOf(rows);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
        public List<BeanDefinitionCandidate> candidates(SpringBuildContext build,String container) {
            if(!build.analysisIdentity().equals(analysis)||!build.snapshotIdentity().equals(snapshot))throw new IllegalArgumentException("Foreign repository build context");
            return rows.stream().filter(r->r.status()==Status.CANDIDATE).map(r->new BeanDefinitionCandidate(
                    new BeanProducer(build.identity(),container,r.evidence(),BeanProducer.Kind.SUPPLIED_DEFINITION,
                            r.repository().value(),"spring-data:"+r.store().orElseThrow()),r.repository().value(),
                    r.beanName().map(BeanDefinitionCandidate.Names::exact).orElseGet(BeanDefinitionCandidate.Names::unresolved),r.exposedTypes())).toList();
        }
    }
    private static final Map<String,String> STORES=Map.ofEntries(
            Map.entry("jpa","org.springframework.data.jpa.repository.JpaRepository"),Map.entry("mongo","org.springframework.data.mongodb.repository.MongoRepository"),
            Map.entry("reactive-mongo","org.springframework.data.mongodb.repository.ReactiveMongoRepository"),Map.entry("elasticsearch","org.springframework.data.elasticsearch.repository.ElasticsearchRepository"),
            Map.entry("reactive-elasticsearch","org.springframework.data.elasticsearch.repository.ReactiveElasticsearchRepository"),Map.entry("cassandra","org.springframework.data.cassandra.repository.CassandraRepository"),
            Map.entry("reactive-cassandra","org.springframework.data.cassandra.repository.ReactiveCassandraRepository"),Map.entry("neo4j","org.springframework.data.neo4j.repository.Neo4jRepository"),
            Map.entry("reactive-neo4j","org.springframework.data.neo4j.repository.ReactiveNeo4jRepository"),Map.entry("couchbase","org.springframework.data.couchbase.repository.CouchbaseRepository"),
            Map.entry("reactive-couchbase","org.springframework.data.couchbase.repository.ReactiveCouchbaseRepository"));
    private static final Set<String> COMMON=Set.of("org.springframework.data.repository.Repository","org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository","org.springframework.data.repository.PagingAndSortingRepository","org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.repository.reactive.ReactiveCrudRepository","org.springframework.data.repository.reactive.ReactiveSortingRepository",
            "org.springframework.data.repository.reactive.RxJava3CrudRepository","org.springframework.data.repository.reactive.RxJava3SortingRepository");
    private static final Map<String,String> ENABLE=Map.ofEntries(
            Map.entry("org.springframework.data.jpa.repository.config.EnableJpaRepositories","jpa"),Map.entry("org.springframework.data.mongodb.repository.config.EnableMongoRepositories","mongo"),
            Map.entry("org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories","reactive-mongo"),Map.entry("org.springframework.data.jdbc.repository.config.EnableJdbcRepositories","jdbc"),
            Map.entry("org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories","r2dbc"),Map.entry("org.springframework.data.redis.repository.configuration.EnableRedisRepositories","redis"),
            Map.entry("org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories","elasticsearch"),Map.entry("org.springframework.data.cassandra.repository.config.EnableCassandraRepositories","cassandra"),
            Map.entry("org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories","neo4j"),Map.entry("org.springframework.data.couchbase.repository.config.EnableCouchbaseRepositories","couchbase"));
    public static Result synthesize(SpringSourceEvidence source,int maxTypes) {
        return synthesize(source,Map.of(),maxTypes);
    }
    public static Result synthesize(SpringSourceEvidence source,Map<String,String> properties,int maxTypes) {
        if(maxTypes<1)throw new IllegalArgumentException("Positive repository limit required");
        var input=IngestionEvidence.digest(List.of("spring.data-repositories:m4u.2-v1",source.identity(),new TreeMap<>(properties),maxTypes));
        var issues=new ArrayList<UniversalSpringEvidence.Issue>();var scans=new ArrayList<Scan>();
        var namespace=FrameworkGeneration.from(source.framework(),Optional.empty()).namespace();
        var available=new TreeSet<String>();
        for(var entry:source.manifest().classpath())for(String store:List.of("jpa","mongodb","jdbc","r2dbc","redis","elasticsearch","cassandra","neo4j","couchbase"))
            if(entry.logicalName().startsWith("org.springframework.data:spring-data-"+store+":"))available.add(store.equals("mongodb")?"mongo":store);
        // Explicit repository scans own their package roots; component scan packages are not repository roots.
        for(var declaration:source.frontend().typeDeclarations())for(var annotation:source.annotations(declaration.type())) {
            String name=annotation.name().orElse("");String store=ENABLE.get(name);
            if(store==null)continue;
            try {
                var values=LiteralConditionAnnotation.parse(annotation.use().spelling());
                if(!Set.of("value","basePackages","basePackageClasses","considerNestedRepositories").containsAll(values.keySet()))throw new IllegalArgumentException();
                var packages=new ArrayList<>(LiteralConditionAnnotation.strings(values,"basePackages",List.of()));
                var aliases=LiteralConditionAnnotation.strings(values,"value",List.of());
                if(!aliases.isEmpty()&&!packages.isEmpty()&&!aliases.equals(packages))throw new IllegalArgumentException();packages.addAll(aliases);
                for(String marker:LiteralConditionAnnotation.classes(values,"basePackageClasses")) {
                    var types=source.frontend().types().stream().filter(t->t.span().document().equals(annotation.use().span().document())&&t.type().spelling().equals(marker)
                            &&t.span().startLine()>=annotation.use().span().startLine()&&t.span().endLine()<=annotation.use().span().endLine()
                            &&t.type().status()==SemanticStatus.RESOLVED&&t.type().target().isPresent()).map(t->t.type().target().orElseThrow()).distinct().toList();
                    if(types.size()!=1)throw new IllegalArgumentException();packages.add(packageName(source.typeName(types.getFirst())));
                }
                if(packages.isEmpty())packages.add(packageName(source.typeName(declaration.type())));
                for(String p:packages)if(p.contains("$")||p.contains("*"))throw new IllegalArgumentException();
                scans.add(new Scan(store,packages,LiteralConditionAnnotation.bool(values,"considerNestedRepositories",false),annotation.evidence()));
            }catch(IllegalArgumentException invalid){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.REPOSITORY_SCAN_UNKNOWN,annotation.use().canonicalName(),List.of(annotation.evidence())));}
        }
        var explicitlyEnabled=scans.stream().map(Scan::store).collect(java.util.stream.Collectors.toSet());
        boolean bootstrap=false;
        for(var declaration:source.frontend().typeDeclarations())for(var annotation:source.annotations(declaration.type()))
            if(annotation.name().filter(n->n.equals("org.springframework.boot.autoconfigure.SpringBootApplication")||n.equals("org.springframework.boot.autoconfigure.EnableAutoConfiguration")).isPresent())
                {bootstrap=true;for(String store:available)if(!explicitlyEnabled.contains(store)) {
                    String key="spring.data."+(store.equals("mongo")?"mongodb":store)+".repositories.enabled";
                    if("false".equalsIgnoreCase(properties.get(key)))continue;
                    boolean excluded=properties.containsKey("spring.autoconfigure.exclude");
                    try {var attrs=LiteralConditionAnnotation.parse(annotation.use().spelling());excluded|=attrs.containsKey("exclude")||attrs.containsKey("excludeName");}
                    catch(IllegalArgumentException invalid){excluded=true;}
                    if(excluded){issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.REPOSITORY_SCAN_UNKNOWN,"auto-configuration-exclusions",List.of(annotation.evidence())));continue;}
                    scans.add(new Scan(store,List.of(packageName(source.typeName(declaration.type()))),false,annotation.evidence()));
                }}
        scans=scans.stream().distinct().sorted(Comparator.comparing(IngestionEvidence::digest)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var rows=new ArrayList<Row>();int seen=0;
        for(var shape:source.frontend().typeDeclarations()) {
            if(shape.kind()!=TypeDeclarationRecord.Kind.INTERFACE)continue;
            EntityIdentity type=shape.type();ConditionEvidence evidence=source.evidence(type);
            var ancestry=source.ancestry(type,maxTypes);var stores=new TreeSet<String>();boolean repository=false;
            for(var parent:ancestry)if(source.artifact(parent).filter(a->a.groupId().equals("org.springframework.data")).isPresent()) {
                String name=source.typeName(parent);repository|=COMMON.contains(name)||STORES.containsValue(name);
                STORES.forEach((store,base)->{if(base.equals(name))stores.add(store);});
            }
            boolean marker=source.annotations(type).stream().anyMatch(a->a.name().filter("org.springframework.data.repository.RepositoryDefinition"::equals).isPresent());
            repository|=marker;
            boolean noBean=source.annotations(type).stream().anyMatch(a->a.name().filter("org.springframework.data.repository.NoRepositoryBean"::equals).isPresent());
            Status status;Optional<String> store=Optional.empty();Optional<String> beanName=Optional.empty();
            if(++seen>maxTypes||ancestry.size()>=maxTypes){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.RESOURCE_LIMIT,type,evidence);}
            else if(noBean)status=Status.NO_REPOSITORY_BEAN;
            else if(!repository) {
                boolean unresolved=source.frontend().types().stream().anyMatch(t->t.owner().equals(Optional.of(type))&&t.role().value().contains("extends")&&t.type().status()!=SemanticStatus.RESOLVED)
                        ||ancestry.stream().anyMatch(t->source.artifact(t).isPresent());
                status=unresolved?Status.UNKNOWN:Status.NOT_REPOSITORY;
                if(unresolved)issue(issues,UniversalSpringEvidence.Reason.REPOSITORY_ANCESTRY_UNKNOWN,type,evidence);
            }else {
                String name=source.typeName(type),pack=packageName(name);
                var matching=scans.stream().filter(s->s.packages().stream().anyMatch(p->p.isEmpty()||p.equals(pack)||pack.startsWith(p+".")))
                        .filter(s->shape.independent()&&(s.nested()||source.owner(type).map(o->source.declarations().get(o).entity().kind()!=EntityKind.TYPE).orElse(true)))
                        .filter(s->stores.isEmpty()||stores.contains(s.store())).toList();
                var enabled=matching.stream().map(Scan::store).distinct().toList();
                if(issues.stream().anyMatch(i->i.reason()==UniversalSpringEvidence.Reason.REPOSITORY_SCAN_UNKNOWN)){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.REPOSITORY_SCAN_UNKNOWN,type,evidence);}
                else if(scans.isEmpty()&&!bootstrap){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.REPOSITORY_SCAN_UNKNOWN,type,evidence);}
                else if(enabled.isEmpty())status=Status.OUTSIDE_SCAN;
                else if(enabled.size()!=1||stores.isEmpty()&&available.size()>1){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.REPOSITORY_STORE_AMBIGUOUS,type,evidence);}
                else if(source.annotations(type).stream().anyMatch(a->a.name().isEmpty())){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,type,evidence);}
                else {status=Status.CANDIDATE;store=Optional.of(enabled.getFirst());beanName=Optional.of(beanName(name));
                    var explicitNames=new TreeSet<String>();
                    for(var annotation:source.annotations(type))if(annotation.name().filter(n->Set.of("org.springframework.stereotype.Repository","org.springframework.stereotype.Component","javax.inject.Named","jakarta.inject.Named").contains(n)).isPresent())try {
                        String annotationName=annotation.name().orElseThrow();
                        if(annotationName.equals("javax.inject.Named")&&namespace!=FrameworkGeneration.Namespace.JAVAX
                                ||annotationName.equals("jakarta.inject.Named")&&namespace!=FrameworkGeneration.Namespace.JAKARTA) {
                            status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.NAMESPACE_INCOMPATIBLE,type,annotation.evidence());continue;
                        }
                        String explicit=LiteralConditionAnnotation.string(LiteralConditionAnnotation.parse(annotation.use().spelling()),"value","");if(!explicit.isBlank())explicitNames.add(explicit);
                    }catch(IllegalArgumentException invalid){status=Status.UNKNOWN;issue(issues,UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,type,evidence);}
                    if(explicitNames.size()==1)beanName=Optional.of(explicitNames.first());
                    else if(explicitNames.size()>1){status=Status.UNKNOWN;beanName=Optional.empty();issue(issues,UniversalSpringEvidence.Reason.EVIDENCE_CONFLICT,type,evidence);}
                    evidence=new ConditionEvidence.Derived(java.util.stream.Stream.concat(java.util.stream.Stream.of(input,evidence.identity()),matching.stream().map(s->s.evidence().identity())).toList(),UniversalSpringEvidence.PROVIDER,"spring-data:"+type.value());}
            }
            rows.add(new Row(type,status,store,beanName,List.copyOf(ancestry),evidence));
        }
        var sorted=issues.stream().distinct().sorted(Comparator.comparing(UniversalSpringEvidence.Issue::identity)).toList();
        return new Result(input,source.frontend().analysis(),source.manifest().snapshot().identity(),scans,rows,sorted,UniversalSpringEvidence.gaps(source.manifest().snapshot().identity(),input,sorted));
    }
    static String packageName(String name){if(name==null)throw new IllegalArgumentException("Missing type name");int dot=name.lastIndexOf('.');return dot<0?"":name.substring(0,dot);}
    static String beanName(String name){String simple=name.substring(name.lastIndexOf('.')+1).replace('$','.');return simple.length()>1&&Character.isUpperCase(simple.charAt(0))&&Character.isUpperCase(simple.charAt(1))?simple:Character.toLowerCase(simple.charAt(0))+simple.substring(1);}
    private static void issue(List<UniversalSpringEvidence.Issue> issues,UniversalSpringEvidence.Reason reason,EntityIdentity type,ConditionEvidence evidence){issues.add(new UniversalSpringEvidence.Issue(reason,type.value(),List.of(evidence)));}
    private SpringDataRepositories() {}
}
