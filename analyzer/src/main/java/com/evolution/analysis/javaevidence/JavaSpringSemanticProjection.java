package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.universal.SourceToSpringPlan;
import com.evolution.analysis.spring.universal.SpringSourceEvidence;
import java.io.IOException;
import java.util.*;

/** Additive default-scope Spring semantic replay over verified Java facts. The original
 * graph and every captured provenance byte remain unchanged. This never invokes a build
 * or frontend. Explicit scopes/configuration truth require their own projection contract. */
public final class JavaSpringSemanticProjection {
    public static final VersionedIdentifier VERSION=new VersionedIdentifier("spring.captured-semantic-projection","m8.4-default-scope-v1");
    public GraphEvidence project(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.DetailedResult detailed,
            UniversalArchitecturePipeline.Policy policy,JavaSemanticProjection.Result java)throws IOException{
        if(!policy.springScopes().isEmpty()||policy.configurationSpace().isPresent()
                ||policy.truthEvaluationMode()!=UniversalArchitecturePipeline.TruthEvaluationMode.NONE)throw new IOException("JAVA_SPRING_PROJECTION_SCOPE");
        var input=detailed.graphInput().orElseThrow();
        var ingestion=input.ingestion().orElseThrow();
        var actual=ingestion.sourceIngestion().outcomes().stream().flatMap(o->o.request().stream()).findFirst().orElseThrow();
        if(!actual.reactorSources().isEmpty())throw new IOException("JAVA_SPRING_PROJECTION_REACTOR_SOURCE");
        var sources=actual.sources().stream().map(s->{var d=s.decoding();return new SourceInput(s.document(),s.bytes(),new SourceInput.Decoding(d.schemaVersion(),d.charset(),d.origin(),List.of(),d.bom(),d.lineEndings()));}).toList();
        var binaries=new ArrayList<BinaryInput>();
        for(var binary:actual.dependencies()){
            var entry=binary.originEntry();
            // Directory descriptors bind an operational capture; the already verified
            // selected tree bytes are their separate, stable semantic content.
            if(binary.nativeOrigin().isPresent()&&entry.kind()==ClasspathEntryKind.MODULE_OUTPUT)
                entry=new ClasspathEntry(entry.kind(),"semantic-module-output:"+binaries.size(),binary.entry().contentDigest());
            binaries.add(new BinaryInput(entry,binary.path(),binary.reactorModule()));
        }
        var entries=new ArrayList<ClasspathEntry>();entries.add(actual.platform().entry());binaries.forEach(b->entries.add(b.entry()));
        var plan=new FrontendPlan(actual.plan().syntaxLevel(),actual.plan().bytecodeTarget(),actual.plan().preview(),CanonicalJson.sha256(entries),CanonicalJson.sha256(sources.stream().map(SourceInput::decoding).toList()));
        var options=new TreeMap<>(JavaSemanticProjection.semanticOptions(actual.manifest().configuration().values()));
        options.putAll(FrontendRequest.options(plan,actual.module(),actual.sourceSet(),sources.stream().map(SourceInput::document).toList(),actual.platform()));
        var old=actual.manifest();var manifest=AnalysisManifest.create(old.manifestVersion(),old.snapshot(),old.modules(),entries,AnalysisConfiguration.create(old.configuration().schema(),options),old.analyzer(),old.ruleSet(),old.graphSchema());
        var request=new FrontendRequest(manifest,actual.module(),actual.sourceSet(),plan,sources,actual.platform(),binaries,List.of());
        var frontend=new JavaSemanticProjection().replayFrontend(prepared,detailed,manifest.identity()).validateFor(request);
        var framework=SpringFrameworkEvidence.fromRequest(request);
        var components=new ComponentScanIngestion().scan(request,frontend,framework,policy.maxTypes());
        var constructors=new ConstructorInjectionIngestion().ingest(frontend,components,framework,policy.maxParameters());
        var body=new TreeMap<String,Object>();body.put("version",VERSION);body.put("java",java.semanticIdentity());
        try(var bytes=JavaSpringSemanticProjection.class.getResourceAsStream("JavaSpringSemanticProjection.class")){
            if(bytes==null)throw new IOException("JAVA_SPRING_PROJECTION_COMPONENT");body.put("component",ContentDigest.sha256(bytes.readAllBytes()));
        }
        body.put("workPolicy",Map.of("maxTypes",policy.maxTypes(),"maxParameters",policy.maxParameters()));
        body.put("input",java.inputsIdentity());body.put("framework",framework);body.put("components",components);body.put("constructors",constructors);
        boolean included=components.rows().stream().anyMatch(r->r.status()==ComponentScanIngestion.Status.INCLUDED);
        if(included&&!sources.isEmpty()){
            var source=new SpringSourceEvidence(request,frontend,framework);var first=sources.getFirst().document();
            var evidence=new ConditionEvidence.Source(first.identity(),first.contentDigest(),Optional.of(new SourceSpan(first.identity(),1,1,1,2)),0);
            var resource=new SourceToSpringPlan.ResourcePolicy(new ConditionEvidence.Derived(List.of(CanonicalJson.sha256(frontend)),new VersionedIdentifier("architecture.resource-policy","1"),"pipeline-default"));
            var scope=new SourceToSpringPlan.Scope("architecture-scope",source.identity(),List.of(),RegistrationEvent.Completeness.UNKNOWN,RegistrationEvent.Completeness.COMPLETE,RegistrationEvent.Completeness.COMPLETE,RegistrationEvent.Completeness.COMPLETE,RegistrationEvent.Completeness.COMPLETE,RegistrationPlan.OverridePolicy.FORBID,evidence,Optional.of(resource));
            var normalized=SourceToSpringPlan.normalize(SpringBuildContext.fromExactRequest(request,java.inputsIdentity()),source,components,constructors,scope,policy.maxTypes());
            body.put("plan",normalized);
        }else body.put("plan",Optional.empty());
        // Full canonical replay bodies (including uncertainty/gaps/order/version), not
        // arbitrary field removal from an audit-bound Spring plan.
        var canonical=GraphEvidence.capture("java.evidence.spring-semantic-body",body);
        return GraphEvidence.capture("java.evidence.spring-semantic-projection",Map.of("scope","DEFAULT_SCOPE_TRUTH_NONE","semanticIdentity",canonical.identity(),"body",GraphJson.read(canonical.canonicalJson(),GraphJson.Limits.defaults())));
    }
}
