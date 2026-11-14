package com.evolution.analysis.graph;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.evidence.*;
import java.util.*;
import static com.evolution.analysis.graph.KnowledgeGraph.*;

/** Projects neutral provider outputs; never parses source or runs target code. */
public final class CanonicalGraphBuilder {
    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend) {
        return build(manifest,frontend,List.of());
    }
    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,List<CanonicalGraphInput.SpringSlice> spring) {
        return build(manifest,frontend,spring,List.of());
    }
    public KnowledgeGraph build(AnalysisManifest manifest, FrontendResult frontend,List<CanonicalGraphInput.SpringSlice> spring,
            Collection<EvidenceAcquisitionLedger> ledgers) {
        var projection=new Projection(manifest.snapshot().identity());
        projection.frontend(manifest,frontend);
        spring.forEach(projection::spring);
        ledgers.forEach(projection::ledger);
        return projection.finish();
    }

    public KnowledgeGraph build(CanonicalGraphInput input) {
        return build(input,List.of());
    }
    public KnowledgeGraph build(CanonicalGraphInput input,Collection<EvidenceAcquisitionLedger> ledgers) {
        return project(input,ledgers,GraphProjectionStore.memory()).finish();
    }
    public KnowledgeGraph build(CanonicalGraphInput input,Collection<EvidenceAcquisitionLedger> ledgers,GraphProjectionStore.Contribution contribution) throws java.io.IOException {
        var store=GraphProjectionStore.memory();
        var p=project(input,ledgers,store);
        contribution.project(store);
        return p.finish();
    }
    /** Assemble identical canonical rows without constructing or indexing a KnowledgeGraph. */
    public void project(CanonicalGraphInput input,GraphProjectionStore store) {
        project(input,store,List.of());
    }
    public void project(CanonicalGraphInput input,GraphProjectionStore store,Collection<EvidenceAcquisitionLedger> ledgers) {
        project(input,ledgers,store).complete();
    }
    private Projection project(CanonicalGraphInput input,Collection<EvidenceAcquisitionLedger> ledgers,GraphProjectionStore store) {
        Objects.requireNonNull(input);
        var p=new Projection(input.repository().snapshot().identity(),store);
        input.javaEvidence().ifPresent(binding->{try{binding.requireCurrent();}catch(java.io.IOException unavailable){throw new IllegalArgumentException("Unavailable post-analysis Java evidence");}p.javaBase=Optional.of(binding.base());p.historicalGaps=binding.historicalGapIds();binding.evidence().forEach(p::nativeEvidence);});
        String repository=p.repository(input);
        input.javaEvidence().ifPresent(binding->{
            for(var evidence:binding.evidence())if(evidence.family().equals("java.evidence.gap-disposition")){
                var row=GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));
                p.gapDispositions.put(GraphJson.string(row.get("gap")),evidence.identity());
            }
            for(var evidence:binding.evidence())if(evidence.family().equals("java.evidence.source-map")){
                var row=GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));
                String document=GraphJson.string(row.get("projectedDocument"));
                var source=input.repository().snapshot().documents().stream().filter(d->d.identity().value().equals(document)).findFirst()
                        .orElseThrow(()->new IllegalArgumentException("Foreign generated source map"));
                p.node(document,repository,"SOURCE_DOCUMENT",p.documentArtifact(source));
                p.node(document,repository,"SOURCE_DOCUMENT",evidence.identity());
            }
            binding.gapHistory().forEach(g->p.gapRecord(repository,g));
        });
        input.ingestion().ifPresent(ingestion->{
            p.artifact("source.ownership",Map.of("inputIdentity",ingestion.sourceIngestion().inputIdentity(),
                    "sources",ingestion.sourceIngestion().sources(),"issues",ingestion.sourceIngestion().issues(),
                    "gaps",ingestion.sourceIngestion().gaps(),"providerResultIdentity",ingestion.sourceIngestion().identity()));
            for(var outcome:ingestion.sourceIngestion().outcomes()) {
                p.artifact("source.configuration",outcome.configuration());
                outcome.metadata().forEach(m->p.artifact("source.metadata",m));
                outcome.request().ifPresent(request->{
                    if(!request.manifest().snapshot().identity().equals(p.snapshot))throw new IllegalArgumentException("Foreign frontend snapshot");
                    p.artifact("source.request",Map.of("manifest",compactRequestManifest(request.manifest()),"module",request.module(),"sourceSet",request.sourceSet(),"plan",request.plan()));
                    request.sources().forEach(p::sourceBytes);
                    request.originClasspath(); // Revalidate native capabilities before persisting their provenance.
                    request.dependencies().forEach(binary->binary.nativeOrigin().ifPresent(origin->{p.nativeEvidence(GraphEvidence.capture("java.evidence.binary-origin",origin.canonicalForm()));origin.proof().ifPresent(proof->proof.evidence().forEach(p::nativeEvidence));}));
                    request.correspondence().ifPresent(binding->{try{if(!binding.current())throw new IllegalArgumentException("Revoked source/native correspondence");}catch(java.io.IOException unavailable){throw new IllegalArgumentException("Unavailable source/native correspondence");}binding.evidence().forEach(p::nativeEvidence);});
                });
            }
            for(var unit:ingestion.units()) {
                p.artifact("ingestion.unit",Map.of("sourceSet",unit.sourceSet(),"status",unit.status()));
                var outcome=ingestion.sourceIngestion().outcomes().stream().filter(o->o.sourceSet().equals(unit.sourceSet())).findFirst().orElseThrow();
                unit.frontend().ifPresent(frontend->p.frontend(outcome.request().orElseThrow().manifest(),frontend));
                unit.sourceWiring().ifPresent(wiring->p.sourceWiring(outcome.request().orElseThrow(),wiring));
                unit.components().ifPresent(c->p.artifact("spring.components",c));
                unit.constructors().ifPresent(c->p.artifact("spring.constructors",c));
                unit.spring().ifPresent(s->{
                    p.artifact("spring.universal",s);
                    var request=outcome.request().orElseThrow();
                    for(var route:s.web().routes()) {
                        var handler=unit.frontend().orElseThrow().declarations().stream().map(DeclarationRecord::entity)
                                .filter(e->e.identity().equals(route.handler())&&e.origin()==EntityOrigin.PROJECT).findFirst().orElseThrow(()->new IllegalArgumentException("Foreign endpoint declaration"));
                        String context=request.manifest().identity().value();
                        var payload=p.artifact("spring.endpoint-declaration",Map.of("schema","spring.endpoint-declaration:m8.4-v1",
                                "route",route,"module",handler.stableScope().value(),"sourceSet",request.sourceSet(),
                                "scope","DECLARATION_ONLY","activation","UNKNOWN","runtimeObserved",false));
                        String endpoint=derivedIdentity("endpoint-declaration",List.of("m8.4-v1",context,route.identity()));
                        p.node(endpoint,context,"ENDPOINT",payload);
                        p.link(context,"graph.contains",route.handler().value(),endpoint,payload);
                    }
                });
            }
            // Index the lossless records instead of nesting the full repeated ledger in one giant payload.
            // Individual issues/gaps retain their own canonical evidence and original provider identity.
            ingestion.issues().forEach(i->p.artifact("ingestion.issue",i));
            ingestion.gaps().forEach(g->p.gapRecord(repository,g));
            p.artifact("ingestion.outcome-index",Map.of("schema","ingestion.outcome-index:m8.4-paged-v2","identity",ingestion.identity(),"inputIdentity",ingestion.inputIdentity(),
                    "issues",p.indexPages("ingestion.issue-index-page",ingestion.issues(),i->i.identity().value()),
                    "gaps",p.indexPages("ingestion.gap-index-page",ingestion.gaps(),g->g.gapIdentity().value())));
        });
        for(var spring:input.spring())p.spring(spring);
        input.issues().forEach(i->p.node(i.identity().value(),repository,"ACQUISITION_ISSUE",p.artifact("ingestion.issue",i)));
        p.gapRecords(repository, input.gaps(), input.ingestion()
                .map(UniversalIngestionPipeline.Result::gaps).orElseGet(List::of));
        ledgers.forEach(p::ledger);
        return p;
    }

    private static final class Projection {
        final SnapshotIdentity snapshot;
        final Map<String,Context> contexts;
        final Map<String,Node> nodes;
        final Map<String,Edge> edges;
        final Map<String,GraphEvidence> evidence;
        final Map<String,ContentDigest> statements;
        final Map<String,String> injectionUses=new HashMap<>();
        final Map<String,ContentDigest> requiredAttempts;
        // Scope the cache to one projection and keep at most one variant per stable gap.
        // The full record decides reuse. The stable ID excludes diagnostics and history.
        final Map<CapabilityGapIdentity, EncodedGap> gapEvidence = new LinkedHashMap<>(256, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<CapabilityGapIdentity, EncodedGap> eldest) {
                return size() > 4096;
            }
        };
        record EncodedGap(CapabilityGapRecord record, ContentDigest evidence) {}
        Optional<SnapshotIdentity> javaBase=Optional.empty();
        Set<String> historicalGaps=Set.of();
        final Map<String,ContentDigest> gapDispositions=new HashMap<>();
        final Map<String,Set<ContentDigest>> nativeSupports=new HashMap<>();
        final Map<ContentDigest,ContentDigest> contentArtifacts=new HashMap<>();
        final Map<ModuleIdentity,ContentDigest> moduleArtifacts=new HashMap<>();
        final Map<SourceDocumentIdentity,ContentDigest> documentArtifacts=new HashMap<>();
        Projection(SnapshotIdentity snapshot) {this(snapshot,GraphProjectionStore.memory());}
        Projection(SnapshotIdentity snapshot,GraphProjectionStore store) {
            this.snapshot=snapshot;
            contexts=store.records("contexts",GraphSnapshotCodec::context);
            nodes=store.records("nodes",GraphSnapshotCodec::node);
            edges=store.records("edges",GraphSnapshotCodec::edge);
            evidence=store.records("evidence",GraphSnapshotCodec::evidence);
            statements=store.records("statements",v->new ContentDigest(GraphJson.string(v)));
            requiredAttempts=store.records("required-attempts",v->new ContentDigest(GraphJson.string(v)));
        }
        void nativeEvidence(GraphEvidence value){
            evidence.put(value.identity().value(),value);
            nativeSupports.computeIfAbsent(ContentDigest.sha256Utf8(value.canonicalJson()).value(),key->new TreeSet<>()).add(value.identity());
        }
        void sourceWiring(com.evolution.analysis.frontend.FrontendRequest request,com.evolution.analysis.spring.universal.SourceWiringCandidates.Result result) {
            if(!request.manifest().identity().equals(result.analysis()))throw new IllegalArgumentException("Foreign source wiring context");
            if(!result.observed())return;
            String context=request.manifest().identity().value();
            var summary=artifact("spring.source-wiring",Map.of("provider",com.evolution.analysis.spring.universal.SourceWiringCandidates.PROVIDER,"input",result.input(),
                    "beans",result.beans().size(),"sites",result.sites().size(),"typeCandidates",result.matches().size(),"activation","UNKNOWN","selection","NOT_SELECTED","qualifierCompatibility","NOT_EVALUATED"));
            String evaluation=derivedIdentity("spring-source-wiring",List.of(context,result.input()));node(evaluation,context,"SPRING_SOURCE_WIRING",summary);
            link(context,"spring.source-wiring-evaluated",request.module().value(),evaluation,summary);
            for(var bean:result.beans()) {
                var support=artifact("spring.source-bean-candidate",bean);String id=derivedIdentity("spring-source-bean",List.of(context,bean));
                node(id,context,"SOURCE_BEAN_CANDIDATE",support);link(context,"spring.source-bean-declaration",id,bean.declaration().value(),support);
            }
            for(var site:result.sites()) {
                var support=artifact("spring.source-injection-site",site);String id=derivedIdentity("spring-source-injection",List.of(context,site));
                node(id,context,"SOURCE_INJECTION_SITE",support);link(context,"spring.source-injection-owner",id,site.owner().value(),support);
            }
            for(var match:result.matches()) {
                var support=artifact("spring.source-type-candidate",match);
                link(context,"spring.source-type-candidate",derivedIdentity("spring-source-injection",List.of(context,match.site())),derivedIdentity("spring-source-bean",List.of(context,match.bean())),support);
            }
        }
        ContentDigest artifact(String family,Object value) {
            var artifact=GraphEvidence.capture(family,value);var old=evidence.putIfAbsent(artifact.identity().value(),artifact);
            if(old!=null&&!old.equals(artifact))throw new IllegalArgumentException("Conflicting provider artifact");return artifact.identity();
        }
        ContentDigest sourceBytes(SourceInput s) {
            var digest=s.document().contentDigest();
            var contentRef=contentArtifacts.computeIfAbsent(digest,d->{
                byte[] raw=s.bytes();
                return artifact("source.content",Map.of("contentDigest",d,"size",raw.length,"bytesBase64",Base64.getEncoder().encodeToString(raw)));
            });
            return artifact("source.bytes",Map.of("document",s.document(),"decoding",s.decoding(),"contentDigest",digest,"contentEvidence",contentRef));
        }
        ContentDigest moduleArtifact(ModuleDescriptor m) {
            return moduleArtifacts.computeIfAbsent(m.identity(),k->artifact("module.descriptor",m));
        }
        ContentDigest documentArtifact(SourceDocument d) {
            return documentArtifacts.computeIfAbsent(d.identity(),k->artifact("source.document",d));
        }
        <T> List<ContentDigest> indexPages(String family,Iterable<T> rows,java.util.function.Function<T,String> identity) {
            var pages=new ArrayList<ContentDigest>();var page=new ArrayList<String>(1024);
            for(var row:rows){page.add(identity.apply(row));if(page.size()==1024){pages.add(artifact(family,Map.of("page",pages.size(),"records",List.copyOf(page))));page.clear();}}
            if(!page.isEmpty())pages.add(artifact(family,Map.of("page",pages.size(),"records",page)));
            return List.copyOf(pages);
        }
        String repository(CanonicalGraphInput input) {
            String context=snapshot.value();
            var snapshotPayload=artifact("repository.snapshot",input.repository().snapshot());
            contexts.put(context,new Context(context,snapshot,Optional.empty(),snapshotPayload));
            node(input.repository().snapshot().repository().value(),context,"REPOSITORY",snapshotPayload);
            input.acquisition().ifPresent(a->artifact("repository.acquisition",a));
            input.repository().files().values().forEach(s->{var payload=sourceBytes(s);node(s.document().identity().value(),context,"SOURCE_DOCUMENT",payload);});
            input.build().ifPresent(build->{
                artifact("build.model",build);
                for(var module:build.modules()) {
                    var payload=artifact("build.module",module);String id=module.descriptor().identity().value();node(id,context,"MODULE",payload);
                    link(context,"graph.contains",input.repository().snapshot().repository().value(),id,payload);
                    for(var set:module.sourcePlan().sourceSets()) for(var root:set.sourceRoots())root.value().ifPresent(path->{
                        String rootId=derivedIdentity("source-root",List.of(id,set.kind(),set.name(),path));
                        node(rootId,context,"SOURCE_ROOT",payload);link(context,"graph.contains",id,rootId,payload);
                        input.ingestion().ifPresent(ingestion->ingestion.sourceIngestion().sources().stream()
                                .filter(row->row.status()==UniversalSourceIngestion.Status.OWNED)
                                .filter(row->row.claims().contains(new UniversalSourceIngestion.SourceSet(module.descriptor().identity(),set.kind(),set.name())))
                                .filter(row->path.equals(".")||row.path().startsWith(path+"/"))
                                .filter(row->input.repository().files().containsKey(row.path()))
                                .forEach(row->link(context,"graph.owns-source",rootId,input.repository().files().get(row.path()).document().identity().value(),artifact("source.ownership-row",row))));
                    });
                }
            });return context;
        }
        void frontend(AnalysisManifest manifest,FrontendResult frontend) {
            if(!manifest.identity().equals(frontend.analysis())||!manifest.snapshot().identity().equals(snapshot))throw new IllegalArgumentException("Foreign frontend context");
            String context=manifest.identity().value();var manifestPayload=artifact("analysis.manifest",compactManifest(manifest));
            var snapshotDocs=new LinkedHashMap<String,SourceDocument>(Math.max(16,manifest.snapshot().documents().size()*2));
            for(var d:manifest.snapshot().documents())snapshotDocs.put(d.identity().value(),d);
            var documents=snapshotDocs.keySet();
            var referencedDocs=new HashSet<String>();
            frontend.sources().forEach(s->{String id=s.document().value();document(documents,id);referencedDocs.add(id);});
            frontend.observations().forEach(o->{String id=o.document().value();document(documents,id);referencedDocs.add(id);});
            frontend.types().forEach(t->{String id=t.span().document().value();document(documents,id);referencedDocs.add(id);});
            frontend.annotations().forEach(a->{String id=a.span().document().value();document(documents,id);referencedDocs.add(id);});
            frontend.derivedRelationships().forEach(r->r.supportingSpans().forEach(s->{String id=s.document().value();document(documents,id);referencedDocs.add(id);}));
            frontend.declarations().forEach(d->d.entity().declaration().ifPresent(s->referencedDocs.add(s.document().value())));
            frontend.occurrences().forEach(o->referencedDocs.add(o.span().document().value()));
            frontend.httpClientCalls().forEach(c->{String id=c.span().document().value();document(documents,id);referencedDocs.add(id);});
            var prior=contexts.putIfAbsent(context,new Context(context,snapshot,Optional.of(manifest.identity()),manifestPayload));
            if(prior!=null&&!prior.evidence().equals(manifestPayload))throw new IllegalArgumentException("Conflicting manifest");
            ledger(CapabilityGapNormalizer.normalize(EvidenceContext.forAnalysis(manifest),EvidenceNormalizationInput.builder().frontendResults(List.of(frontend)).build()));
            String unitModule=manifest.configuration().values().get("java.module");
            String unitSourceSet=manifest.configuration().values().get("java.source-set");
            var knownModules=new HashSet<String>(Math.max(16,manifest.modules().size()*2));
            for(var m:manifest.modules())knownModules.add(m.identity().value());
            List<SourceDocument> contextDocs;
            List<ModuleDescriptor> contextModules;
            if(unitModule==null) {
                contextDocs=manifest.snapshot().documents();
                contextModules=manifest.modules();
            } else {
                contextDocs=manifest.snapshot().documents().stream()
                        .filter(d->referencedDocs.contains(d.identity().value())
                                ||(d.module().value().equals(unitModule)&&matchesSourceSet(d.classification(),unitSourceSet)))
                        .toList();
                var activeModules=new HashSet<String>();activeModules.add(unitModule);
                contextDocs.forEach(d->activeModules.add(d.module().value()));
                for(var declaration:frontend.declarations())if(declaration.entity().origin()==EntityOrigin.PROJECT)
                    activeModules.add(declaration.entity().stableScope().value());
                contextModules=manifest.modules().stream().filter(m->activeModules.contains(m.identity().value())).toList();
            }
            for(var module:contextModules)node(module.identity().value(),context,"MODULE",moduleArtifact(module));
            for(var document:contextDocs)node(document.identity().value(),context,"SOURCE_DOCUMENT",documentArtifact(document));
            var byCanonical=new HashMap<String,Entity>();var byId=new HashMap<String,Entity>();
            var declarationPayloads=new ArrayList<ContentDigest>(frontend.declarations().size());
            for(var declaration:frontend.declarations()) {
                var entity=declaration.entity();var payload=artifact("java.declaration",declaration);
                declarationPayloads.add(payload);
                if(entity.origin()==EntityOrigin.PROJECT&&!knownModules.contains(entity.stableScope().value()))
                    throw new IllegalArgumentException("Foreign project entity scope");
                statement(context,"java.declaration",entity.identity().value(),payload);
                node(entity.identity().value(),context,entity.kind().name(),payload);
                for(String support:declaration.derivation().inputIdentities())for(var reference:nativeSupports.getOrDefault(support,Set.of()))
                    node(entity.identity().value(),context,entity.kind().name(),reference);
                byCanonical.put(entity.stableScope().value()+"|"+entity.canonicalName(),entity);byId.put(entity.identity().value(),entity);
                entity.declaration().ifPresent(span->{if(!nodes.containsKey(key(context,span.document().value())))throw new IllegalArgumentException("Declaration source absent from manifest");});
            }
            for(int i=0;i<frontend.declarations().size();i++) {
                var declaration=frontend.declarations().get(i);
                var entity=declaration.entity();var payload=declarationPayloads.get(i);
                ownerTuple(entity.canonicalName()).ifPresent(owner->{
                    var parent=byCanonical.get(entity.stableScope().value()+"|java:v1:"+CanonicalJson.write(owner));
                    if(parent!=null)link(context,"graph.declares",parent.identity().value(),entity.identity().value(),payload);
                });
                if(entity.kind()==EntityKind.TYPE)packageName(entity.canonicalName()).ifPresent(name->{
                    var pkg=EntityIdentity.from(entity.origin(),entity.stableScope(),EntityKind.PACKAGE,JavaSymbolName.packageName(name).canonicalName());
                    node(pkg.value(),context,"PACKAGE",payload);link(context,"graph.contains",pkg.value(),entity.identity().value(),payload);
                    if(entity.origin()==EntityOrigin.PROJECT)link(context,"graph.contains",entity.stableScope().value(),pkg.value(),payload);
                });
            }
            var occurrencePayloads=new ArrayList<ContentDigest>(frontend.occurrences().size());
            for(var occurrence:frontend.occurrences()) {
                if(!nodes.containsKey(key(context,occurrence.span().document().value())))throw new IllegalArgumentException("Occurrence source absent from manifest");
                var payload=artifact("java.occurrence",occurrence);occurrencePayloads.add(payload);
                node(occurrence.identity().value(),context,"OCCURRENCE",payload);
                statement(context,"java.occurrence",occurrence.identity().value(),payload);
                relationship(context,occurrence.relationship(),payload);
                if(occurrence.status()==SemanticStatus.RESOLVED)dependency(context,occurrence.relationship(),byCanonical,byId,payload);
            }
            var derivedPayloads=new ArrayList<ContentDigest>(frontend.derivedRelationships().size());
            for(var derived:frontend.derivedRelationships()) {
                var payload=artifact("java.derived-relationship",derived);derivedPayloads.add(payload);
                relationship(context,derived.relationship(),payload);
                if(derived.status()==SemanticStatus.RESOLVED)dependency(context,derived.relationship(),byCanonical,byId,payload);
            }
            var httpClientCallPayloads=new ArrayList<ContentDigest>(frontend.httpClientCalls().size());
            for(var call:frontend.httpClientCalls()) {
                var document=Objects.requireNonNull(snapshotDocs.get(call.span().document().value()));
                var payload=artifact("java.http-client-call",Map.of("call",call,"analysis",manifest.identity(),"sourceContent",document.contentDigest(),
                        "binding","COMPILE_TIME","runtimeObserved",false));
                httpClientCallPayloads.add(payload);
                String site=derivedIdentity("http-client-call",payload);
                node(site,context,"SOURCE_HTTP_CALL_CANDIDATE",payload);
                link(context,"graph.has-http-client-call",call.caller().value(),site,payload);
            }
            var observationPayloads=new ArrayList<ContentDigest>(frontend.observations().size());
            for(var observation:frontend.observations()) {
                var payload=artifact("java.observation",observation);observationPayloads.add(payload);
                node(derivedIdentity("observation",payload),context,"OBSERVATION",payload);
            }
            var sourceSummaries=new ArrayList<Map<String,Object>>(frontend.sources().size());
            for(var s:frontend.sources()) {
                var payload=artifact("java.source-outcome",s);
                sourceSummaries.add(Map.of("document",s.document(),"state",s.state(),"evidence",payload));
            }
            var typeUsePayloads=new ArrayList<ContentDigest>(frontend.types().size());
            for(var t:frontend.types()) {
                var payload=artifact("java.type-use",t);typeUsePayloads.add(payload);
                String id=derivedIdentity("type-use",payload);
                node(id,context,"TYPE_USE",payload);t.owner().ifPresent(owner->link(context,"graph.has-type-use",owner.value(),id,payload));
            }
            var annotationPayloads=new ArrayList<ContentDigest>(frontend.annotations().size());
            for(var a:frontend.annotations()) {
                var payload=artifact("java.annotation-use",a);annotationPayloads.add(payload);
                var owner=byId.get(a.owner().value());
                if(owner==null)throw new IllegalArgumentException("Annotation owner is absent");
                String id=EntityIdentity.from(owner.origin(),owner.stableScope(),EntityKind.ANNOTATION_USE,a.canonicalName()).value();
                node(id,context,"ANNOTATION_USE",payload);link(context,"graph.has-annotation-use",owner.identity().value(),id,payload);
            }
            var typeShapePayloads=frontend.typeDeclarations().stream().map(t->artifact("java.type-shape",t)).toList();
            var memberShapePayloads=frontend.memberDeclarations().stream().map(m->artifact("java.member-shape",m)).toList();
            var ctorCandidatePayloads=new ArrayList<ContentDigest>(frontend.constructorCandidates().size());
            for(var c:frontend.constructorCandidates()) {
                var payload=artifact("java.lombok-constructor-candidate",c);ctorCandidatePayloads.add(payload);
                String id=derivedIdentity("constructor-candidate",payload);node(id,context,"CONSTRUCTOR_INJECTION_CANDIDATE",payload);
                link(context,"graph.has-constructor-candidate",c.owner().value(),id,payload);
                link(context,"graph.candidate-field",id,c.field().value(),payload);
            }
            var index=new TreeMap<String,Object>();index.put("indexSchema","frontend.result-index:m8.4-v2");index.put("providerResultIdentity",CanonicalJson.sha256(frontend));
            index.put("analysis",frontend.analysis());index.put("frontend",frontend.frontend());index.put("state",frontend.state());
            index.put("coverage",frontend.coverage());
            index.put("sources",List.copyOf(sourceSummaries));
            index.put("diagnostics",frontend.diagnostics().stream().map(d->artifact("java.diagnostic",d)).toList());
            index.put("declarations",List.copyOf(declarationPayloads));
            index.put("occurrences",List.copyOf(occurrencePayloads));
            index.put("observations",List.copyOf(observationPayloads));
            index.put("types",List.copyOf(typeUsePayloads));
            index.put("annotations",List.copyOf(annotationPayloads));
            index.put("derivedRelationships",List.copyOf(derivedPayloads));
            index.put("typeDeclarations",typeShapePayloads);
            index.put("memberDeclarations",memberShapePayloads);
            index.put("constructorCandidates",List.copyOf(ctorCandidatePayloads));
            index.put("httpClientCalls",List.copyOf(httpClientCallPayloads));
            artifact("frontend.result-index",index);
        }
        void relationship(String context,SemanticRelationship relation,ContentDigest payload) {
            Target target=switch(relation.target()) {
                case RelationshipTarget.Resolved r->new Target(TargetState.RESOLVED,List.of(r.target().value()),Optional.empty());
                case RelationshipTarget.Candidates c->new Target(TargetState.CANDIDATES,c.candidates().stream().map(EntityIdentity::value).toList(),Optional.empty());
                case RelationshipTarget.Unresolved u->new Target(TargetState.UNRESOLVED,List.of(),Optional.of(u.stableReference()));
            };
            edge(new Edge(relation.identity().value(),context,relation.kind().value(),relation.source().value(),target,List.of(payload),Optional.empty()));
        }
        void dependency(String context,SemanticRelationship relation,Map<String,Entity> byCanonical,Map<String,Entity> byId,ContentDigest payload) {
            if(Set.of("java.declares","java.has-parameter").contains(relation.kind().value()) || !(relation.target() instanceof RelationshipTarget.Resolved target))return;
            Entity from=typeOwner(byId.get(relation.source().value()),byCanonical),to=typeOwner(byId.get(target.target().value()),byCanonical);
            if(from!=null&&to!=null&&!from.identity().equals(to.identity()))link(context,"graph.depends-on",from.identity().value(),to.identity().value(),payload);
            else if(from==null||to==null) {
                var source=byId.get(relation.source().value());
                if(source!=null&&source.kind()!=EntityKind.PACKAGE)projectionGap(context,relation.identity().value(),payload,"TYPE_OWNER_UNAVAILABLE");
            }
        }
        Entity typeOwner(Entity entity,Map<String,Entity> entities) {
            var seen=new HashSet<String>();
            while(entity!=null&&entity.kind()!=EntityKind.TYPE&&seen.add(entity.identity().value())) {
                var owner=ownerTuple(entity.canonicalName());if(owner.isEmpty())return null;
                entity=entities.get(entity.stableScope().value()+"|java:v1:"+CanonicalJson.write(owner.orElseThrow()));
            }return entity!=null&&entity.kind()==EntityKind.TYPE?entity:null;
        }
        void spring(CanonicalGraphInput.SpringSlice slice) {
            var plan=slice.plan();var discovery=plan.binding().registrationPlan().discoveryPlan();
            String analysis=discovery.buildContext().analysisIdentity().value();
            if(!contexts.containsKey(analysis)||!discovery.buildContext().snapshotIdentity().equals(snapshot))throw new IllegalArgumentException("Foreign Spring build context");
            String context=slice.explicitTruth().map(t->t.semanticsContextIdentity().value()).orElseGet(()->derivedIdentity("spring-context",List.of(
                    analysis,plan.identity(),slice.space().map(s->s.identity()),slice.symbolicTruth().map(SymbolicTruthRegions.StagedResult::inputIdentity),slice.parameters())));
            var contextPayload=artifact("spring.context",Map.of("analysis",analysis,"build",discovery.buildContext(),"planIdentity",plan.identity(),
                    "space",slice.space().map(s->s.identity()),"parameters",slice.parameters()));
            var oldContext=contexts.putIfAbsent(context,new Context(context,snapshot,Optional.of(discovery.buildContext().analysisIdentity()),contextPayload));
            if(oldContext!=null&&!oldContext.evidence().equals(contextPayload))throw new IllegalArgumentException("Conflicting Spring context");
            // Embedded plan/result gaps must also participate in the graph's searchable denominator.
            plan.gaps().forEach(g->gapRecord(context,g));
            var planPayload=artifact("spring.plan",Map.of("inputIdentity",plan.inputIdentity(),"inventory",plan.inventory(),
                    "conditions",plan.conditions(),"binding",plan.binding(),"discovery",Map.of("canonical",discovery.canonicalForm(),"build",discovery.buildContext(),
                    "events",discovery.events(),"precedence",discovery.precedence(),"initialDefinitions",discovery.initialDefinitions()),"gaps",plan.gaps(),"methodSelections",plan.methodSelections()));
            var candidates=new TreeMap<String,BeanDefinitionCandidate>();
            discovery.initialDefinitions().forEach(c->candidate(candidates,c));
            discovery.events().forEach(e->e.candidate().ifPresent(c->candidate(candidates,c)));
            for(var candidate:candidates.values()) {
                statement(context,"spring.candidate",candidate.identity().value(),artifact("spring.candidate",candidate));
                node(candidate.producer().identity().value(),context,"BEAN_PRODUCER",artifact("spring.producer",candidate.producer()));
                node(candidate.identity().value(),context,"BEAN_DEFINITION_CANDIDATE",artifact("spring.candidate",candidate));
                link(context,"spring.produces-candidate",candidate.producer().identity().value(),candidate.identity().value(),planPayload);
            }
            for(var dep:plan.binding().dependencies()) {
                var payload=artifact("spring.injection-dependency",dep);node(dep.point().identity().value(),context,"INJECTION_POINT",payload);
                String use=derivedIdentity("injection-use",List.of(dep.point().identity(),dep.owner(),dep.identity()));
                node(use,context,"INJECTION_USE",payload);link(context,"spring.has-site",use,dep.point().identity().value(),payload);
                String siteKey=key(context,dep.point().identity().value())+"|"+dep.owner();
                var previousUse=injectionUses.putIfAbsent(siteKey,use);
                if(previousUse!=null&&!previousUse.equals(use))throw new IllegalArgumentException("Conflicting contextual injection descriptor");
                dep.owner().ifPresent(owner->link(context,"spring.declares-injection",owner.value(),use,payload));
            }
            for(var event:discovery.events())node(event.identity().value(),context,"REGISTRATION_EVENT",artifact("spring.registration-event",event));
            for(var precedence:discovery.precedence())link(context,"spring.precedes-registration",precedence.before().value(),precedence.after().value(),artifact("spring.registration-precedence",precedence));
            var transitions=new TreeMap<String,String>();
            for(var step:plan.binding().registrationPlan().steps()) {
                var payload=artifact("spring.registration-step",step);String id=derivedIdentity("registration-transition",List.of(plan.binding().registrationPlan().identity(),step.identity()));
                transitions.put(step.key(),id);node(id,context,"REGISTRATION_TRANSITION",payload);
                link(context,"spring.invokes-event",id,step.discoveryEvent().value(),payload);
            }
            var prefix=plan.binding().registrationPlan().establishedOrderPrefix();
            for(int i=1;i<prefix.size();i++)link(context,"spring.precedes-transition",transitions.get(prefix.get(i-1)),transitions.get(prefix.get(i)),planPayload);
            var expressions=new HashSet<String>();
            var conditionRows=new ArrayList<>(plan.conditions().occurrences());slice.space().ifPresent(s->conditionRows.addAll(s.constraints()));
            for(var condition:conditionRows) {
                var payload=artifact("spring.condition",condition);node(condition.identity().value(),context,"CONFIGURATION_CONDITION",payload);
                var pending=new ArrayDeque<com.evolution.analysis.spring.condition.ConditionExpression>();pending.add(condition.expression());
                while(!pending.isEmpty()) {
                    var expression=pending.removeFirst();if(!expressions.add(expression.identity().value()))continue;
                    var expressionPayload=artifact("spring.condition-expression",expression.view());node(expression.identity().value(),context,"CONDITION_EXPRESSION",expressionPayload);
                    for(var child:expression.children()){pending.add(child);link(context,"spring.expression-input",expression.identity().value(),child.identity().value(),expressionPayload);}
                }
                link(context,"spring.condition-expression",condition.identity().value(),condition.expression().identity().value(),payload);
            }
            for(var event:discovery.events())for(var condition:event.conditions())link(context,"spring.has-condition",event.identity().value(),condition.occurrence().value(),planPayload);
            slice.space().ifPresent(s->{artifact("spring.space",s);s.envelopes().forEach(e->artifact("spring.configuration-envelope",e));});
            slice.baseline().ifPresent(b->artifact("spring.baseline",b));
            slice.parameters().ifPresent(p->artifact("spring.evaluation-parameters",p));
            slice.explicitTruth().ifPresent(truth->{
                truth.capabilityGaps().forEach(g->gapRecord(context,g));
                artifact("spring.explicit-result",truth);
                truth.worlds().forEach(w->node(w.identity().value(),context,"CONFIGURATION_WORLD",artifact("spring.world",w)));
                for(var region:truth.regions()) {
                    var payload=artifact("spring.explicit-region",Map.of("encoding","explicit-worlds-m4d-v1","space",slice.space().orElseThrow().identity(),"semanticsContext",truth.semanticsContextIdentity(),"region",region));node(region.identity().value(),context,"TRUTH_REGION",payload);
                    fact(context,region.fact(),region.identity().value(),payload);
                    region.trueWorlds().forEach(w->link(context,"spring.region-true-world",region.identity().value(),w.value(),payload));
                    region.falseWorlds().forEach(w->link(context,"spring.region-false-world",region.identity().value(),w.value(),payload));
                    region.unknownWorlds().forEach(w->link(context,"spring.region-unknown-world",region.identity().value(),w.value(),payload));
                }
                truth.witnesses().forEach(w->{var payload=artifact("spring.witness",w);node(w.identity().value(),context,"CONFIGURATION_WITNESS",payload);link(context,"spring.has-witness",w.factRegionIdentity().value(),w.identity().value(),payload);});
                truth.replays().forEach(r->{var payload=artifact("spring.witness-replay",r);String id=derivedIdentity("witness-replay",payload);node(id,context,"WITNESS_REPLAY",payload);
                    if(nodes.containsKey(key(context,r.witness().value())))link(context,"spring.has-replay",r.witness().value(),id,payload);});
            });
            slice.symbolicTruth().ifPresent(truth->{
                truth.gaps().forEach(g->gapRecord(context,g));
                artifact("spring.symbolic-result",truth);
                truth.formulas().forEach(f->{var payload=artifact("spring.formula",f);node(f.identity().value(),context,"REGION_FORMULA",payload);
                    f.children().forEach(child->link(context,"spring.formula-input",f.identity().value(),child.value(),payload));});
                for(var region:truth.regions()) {
                    var payload=artifact("spring.symbolic-region",Map.of("encoding","symbolic-dag-m4u.2-v1","space",slice.space().orElseThrow().identity(),"evaluationInput",truth.inputIdentity(),"region",region));String id=derivedIdentity("symbolic-region",List.of(truth.inputIdentity(),payload));
                    node(id,context,"TRUTH_REGION",payload);fact(context,region.fact(),id,payload);
                    link(context,"spring.true-formula",id,region.trueRegion().value(),payload);link(context,"spring.false-formula",id,region.falseRegion().value(),payload);link(context,"spring.unknown-formula",id,region.unknownRegion().value(),payload);
                }
                truth.traces().forEach(t->artifact("spring.symbolic-trace",t));
            });
        }
        void fact(String context,ConditionalFactKey fact,String region,ContentDigest payload) {
            node(fact.identity().value(),context,"CONDITIONAL_FACT",artifact("spring.fact-key",fact));
            link(context,"spring.has-truth-region",fact.identity().value(),region,payload);
            fact.definition().ifPresent(d->link(context,"spring.definition-fact",fact.identity().value(),d.value(),payload));
            if(fact.kind()!=ConditionalFactKey.Kind.DEFINITION_PRESENT) {
                String id=derivedIdentity("conditional-edge",List.of(fact.identity(),region));
                edge(new Edge(id,context,"spring."+fact.kind().name().toLowerCase(Locale.ROOT).replace('_','-'),
                        Optional.ofNullable(injectionUses.get(key(context,fact.injectionPoint().orElseThrow().value())+"|"+fact.bindingOwner()))
                                .orElseThrow(()->new IllegalArgumentException("Conditional fact has no contextual injection use")),
                        new Target(TargetState.RESOLVED,List.of(fact.target().orElseThrow().value()),Optional.empty()),List.of(payload),Optional.of(region)));
            }
        }
        void candidate(Map<String,BeanDefinitionCandidate> candidates,BeanDefinitionCandidate candidate) {
            var old=candidates.putIfAbsent(candidate.identity().value(),candidate);
            if(old!=null&&!old.equals(candidate))throw new IllegalArgumentException("Conflicting candidate payload");
        }
        void node(String identity,String context,String kind,ContentDigest payload) {
            String key=key(context,identity);var old=nodes.get(key);
            if(old!=null&&!old.kind().equals(kind))throw new IllegalArgumentException("Conflicting graph node kind");
            if(old!=null&&old.evidence().contains(payload))return;
            var support=new TreeSet<ContentDigest>();if(old!=null)support.addAll(old.evidence());support.add(payload);
            nodes.put(key,new Node(identity,context,kind,List.copyOf(support)));
        }
        void statement(String context,String family,String identity,ContentDigest payload) {
            var prior=statements.putIfAbsent(context+"|"+family+"|"+identity,payload);
            if(prior!=null&&!prior.equals(payload))throw new IllegalArgumentException("Conflicting canonical provider statement");
        }
        void document(Set<String> documents,String identity) {if(!documents.contains(identity))throw new IllegalArgumentException("Foreign provider source document");}
        void ledger(EvidenceAcquisitionLedger ledger) {
            if(!ledger.context().snapshotIdentity().equals(snapshot))throw new IllegalArgumentException("Foreign evidence ledger snapshot");
            String context=ledger.context().analysisIdentity().map(AnalysisIdentity::value).orElse(snapshot.value());
            if(!contexts.containsKey(context))throw new IllegalArgumentException("Foreign evidence ledger analysis");
            artifact("evidence.ledger-index",Map.of("indexSchema","evidence.ledger-index:m7.2-v1","identity",ledger.identity(),"schemaVersion",ledger.schemaVersion(),"normalizer",ledger.normalizer(),
                    "context",ledger.context(),"gaps",ledger.gaps().stream().map(g->g.gapIdentity()).toList(),"attempts",ledger.attempts(),"conflicts",ledger.conflicts(),"resolutions",ledger.resolutions()));
            ledger.gaps().forEach(g->gapRecord(context,g));
            ledger.attempts().forEach(a->node(a.attemptIdentity().value(),context,"ACQUISITION_ATTEMPT",artifact("evidence.attempt",a)));
            ledger.conflicts().forEach(c->node(c.conflictIdentity().value(),context,"PROVIDER_CONFLICT",artifact("evidence.conflict",c)));
            ledger.resolutions().forEach(r->artifact("evidence.resolution",r));
        }
        void link(String context,String kind,String source,String target,ContentDigest payload) {
            String id=derivedIdentity("graph-relation",List.of("projection:m5.1-v1",kind,source,target));
            edge(new Edge(id,context,kind,source,new Target(TargetState.RESOLVED,List.of(target),Optional.empty()),List.of(payload),Optional.empty()));
        }
        void edge(Edge edge) {
            String key=key(edge.context(),edge.identity());var old=edges.get(key);
            if(old==null){edges.put(key,edge);return;}
            if(!old.kind().equals(edge.kind())||!old.source().equals(edge.source())||!old.target().equals(edge.target())||!old.region().equals(edge.region()))throw new IllegalArgumentException("Conflicting graph relationship");
            if(old.evidence().containsAll(edge.evidence()))return;
            var supports=new TreeSet<>(old.evidence());supports.addAll(edge.evidence());
            edges.put(key,new Edge(old.identity(),old.context(),old.kind(),old.source(),old.target(),List.copyOf(supports),old.region()));
        }
        void gapRecord(String context,CapabilityGapRecord gap) {
            if(!gap.context().snapshotIdentity().equals(snapshot)&&!javaBase.filter(gap.context().snapshotIdentity()::equals).isPresent())throw new IllegalArgumentException("Foreign graph capability gap snapshot");
            var id = gap.gapIdentity();
            var encoded = gapEvidence.get(id);
            if (encoded == null || !encoded.record().equals(gap)) {
                encoded = new EncodedGap(gap, artifact("evidence.gap", gap));
                gapEvidence.put(id, encoded);
            }
            var payload = encoded.evidence();
            String kind = historicalGaps.contains(id.value()) ? "HISTORICAL_CAPABILITY_GAP" : "CAPABILITY_GAP";
            node(id.value(), context, kind, payload);
            var disposition=gapDispositions.get(id.value());
            if(disposition!=null)node(id.value(),context,kind,disposition);
            String expectedContext=gap.context().analysisIdentity().map(AnalysisIdentity::value).orElse(snapshot.value());
            gap.acquisitionAttemptReferences().forEach(ref->requiredAttempts.put(key(expectedContext,ref.value()),payload));
        }
        void gapRecords(String context, List<CapabilityGapRecord> gaps, List<CapabilityGapRecord> projected) {
            // The ingestion and final lists are usually sorted by gap ID. Walk the
            // earlier list once and use the node's existing evidence only on exact
            // record equality. No index proportional to the gap population is needed.
            var previous = projected.iterator();
            CapabilityGapRecord prior = previous.hasNext() ? previous.next() : null;
            for (var gap : gaps) {
                while (prior != null && prior.compareTo(gap) < 0)
                    prior = previous.hasNext() ? previous.next() : null;
                if (gap.equals(prior) && gap.acquisitionAttemptReferences().isEmpty()) {
                    // Same repository node, kind, evidence and disposition already written.
                    continue;
                }
                gapRecord(context, gap);
            }
        }
        void projectionGap(String context,String subjectIdentity,ContentDigest support,String reason) {
            var provider=new VersionedIdentifier("graph.canonical-builder","m5.1-v1");
            var observation=ProviderObservationReference.create(provider,"graph.projection",support,ContentDigest.sha256Utf8(subjectIdentity));
            var subject=EvidenceSubject.observation(observation.identity());
            var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,"graph.resolve-supporting-evidence",List.of(subject),
                    EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Supply the exact missing referenced evidence or neutral owner declaration."));
            var gap=CapabilityGapRecord.create(new VersionedIdentifier("evidence.graph-gaps","m5.1-v1"),new EvidenceContext(snapshot,contexts.get(context).analysis()),provider,
                    "graph.projection",reason,subject,List.of(),List.of(observation),List.of(requirement),List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION,"graph.canonical")),List.of(),List.of(),List.of("Retained upstream facts are not proof of the missing projection evidence."));
            node(gap.gapIdentity().value(),context,"CAPABILITY_GAP",artifact("evidence.gap",gap));
            artifact("graph.unavailable-reference",Map.of("subject",subjectIdentity,"availability","UNAVAILABLE","support",support,"gap",gap.gapIdentity()));
        }
        void complete() {
            for(var reference:requiredAttempts.entrySet())if(!nodes.containsKey(reference.getKey())) {
                String context=reference.getKey().substring(0,reference.getKey().indexOf('|'));
                if(!contexts.containsKey(context))context=contexts.containsKey(snapshot.value())?snapshot.value():contexts.keySet().iterator().next();
                projectionGap(context,reference.getKey(),reference.getValue(),"REFERENCED_EVIDENCE_UNAVAILABLE");
            }
        }
        KnowledgeGraph finish() {
            complete();
            return new KnowledgeGraph(snapshot,List.copyOf(contexts.values()),List.copyOf(nodes.values()),List.copyOf(edges.values()),List.copyOf(evidence.values()));
        }
    }
    private static Map<String,Object> compactSnapshot(RepositorySnapshot s) {
        var map=new TreeMap<String,Object>();
        map.put("identity",s.identity());map.put("repository",s.repository());map.put("revision",s.revision());
        map.put("dirty",s.dirty());map.put("contentDigest",s.contentDigest());
        return Collections.unmodifiableMap(map);
    }
    private static Map<String,Object> compactManifest(AnalysisManifest m) {
        var map=new TreeMap<String,Object>();
        map.put("identity",m.identity());map.put("manifestVersion",m.manifestVersion());map.put("snapshot",compactSnapshot(m.snapshot()));
        map.put("modules",m.modules());map.put("classpath",m.classpath());map.put("configuration",m.configuration());
        map.put("analyzer",m.analyzer());map.put("ruleSet",m.ruleSet());map.put("graphSchema",m.graphSchema());
        return Collections.unmodifiableMap(map);
    }
    private static Map<String,Object> compactRequestManifest(AnalysisManifest m) {
        var map=new TreeMap<String,Object>();
        map.put("identity",m.identity());map.put("manifestVersion",m.manifestVersion());map.put("snapshot",compactSnapshot(m.snapshot()));
        map.put("configuration",m.configuration());map.put("analyzer",m.analyzer());map.put("ruleSet",m.ruleSet());map.put("graphSchema",m.graphSchema());
        return Collections.unmodifiableMap(map);
    }
    private static boolean matchesSourceSet(SourceClassification docRole,String unitSourceSet) {
        if("MAIN".equals(unitSourceSet))return docRole==SourceClassification.MAIN||docRole==SourceClassification.GENERATED_MAIN;
        if("TEST".equals(unitSourceSet))return docRole==SourceClassification.TEST||docRole==SourceClassification.GENERATED_TEST;
        return unitSourceSet==null||docRole.name().equals(unitSourceSet);
    }
    private static Optional<List<Object>> ownerTuple(String name) {
        var tuple=tuple(name);if(tuple.isEmpty())return Optional.empty();var parts=tuple.orElseThrow();
        if(parts.size()<2 || !(parts.get(1) instanceof List<?> owner) || Set.of("package","type").contains(parts.getFirst()))return Optional.empty();
        return Optional.of(new ArrayList<>(owner));
    }
    private static Optional<List<Object>> tuple(String name) {
        if(!name.startsWith("java:v1:"))return Optional.empty();
        try{return Optional.of(GraphJson.array(GraphJson.read(name.substring(8),new GraphJson.Limits(100000,64,10000))));}
        catch(IllegalArgumentException unknown){return Optional.empty();}
    }
    private static Optional<String> packageName(String name) {
        var tuple=tuple(name);if(tuple.isEmpty())return Optional.empty();var parts=tuple.orElseThrow();
        for(int depth=0;depth<64;depth++) {
            if(parts.isEmpty())return Optional.empty();
            if(parts.getFirst().equals("type"))return Optional.of(String.join(".",GraphJson.array(parts.get(1)).stream().map(GraphJson::string).toList()));
            if(parts.size()<2||!(parts.get(1) instanceof List<?>))return Optional.empty();parts=GraphJson.array(parts.get(1));
        }return Optional.empty();
    }
}
