package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.execution.javac.NativeContextValidator;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import java.io.IOException;
import java.util.*;

/** Ownership from the captured compiler command and its complete output tree, not package names. */
final class NativeFixtureOwnership {
    private record Owner(String directory,ModuleDescriptor module,Map<String,ContentHandle> files,ContentDigest stage,boolean successful){}
    private final Map<String,Owner> contexts=new TreeMap<>();private final ContentHandle cohort;private final String work;
    NativeFixtureOwnership(RepositoryIdentity repository,Map<String,Object> post,ContentHandle cohort,Map<String,Object> raw,RepositoryContentStore content)throws IOException {
        this.cohort=cohort;work="/work/"+GraphJson.string(raw.get("attempt"))+"/";
        if(!content.verify(cohort)||!Objects.equals(raw.get("attempt"),post.get("attempt")))throw new IOException("NATIVE_OWNERSHIP_COHORT");
        try(var in=content.open(cohort)){
            var captured=GraphJson.object(GraphJson.read(new String(in.readNBytes(2_000_001),java.nio.charset.StandardCharsets.UTF_8),new GraphJson.Limits(2_000_000,64,2_000_000)));
            if(!"m844-authored-native-cohort:v1".equals(raw.get("schema"))||!CanonicalJson.sha256(captured).equals(CanonicalJson.sha256(raw)))throw new IOException("NATIVE_OWNERSHIP_COHORT_CHANGED");
        }
        var outputs=new TreeMap<String,ContentHandle>();for(Object value:GraphJson.array(raw.get("outputs"))){var row=GraphJson.object(value);if(outputs.putIfAbsent(GraphJson.string(row.get("path")),JavaEvidenceRecord.handle(row))!=null)throw new IOException("NATIVE_OWNERSHIP_DUPLICATE_OUTPUT");}
        for(Object value:GraphJson.array(post.get("contexts"))){
            var context=GraphJson.object(value);String name=GraphJson.string(context.get("name"));
            var sourceInputs=GraphJson.object(context.get("sourceInputs"));
            var candidates=GraphJson.array(raw.get("stages")).stream().map(GraphJson::object).filter(s->{
                if(s.get("argv")==null)return false;var args=GraphJson.array(s.get("argv")).stream().map(GraphJson::string).toList();
                var sources=args.stream().filter(a->a.endsWith(".java")).toList();return !args.isEmpty()&&args.getFirst().equals("/usr/bin/javac")&&args.contains("-d")&&!sources.isEmpty()&&sourceInputs.keySet().containsAll(sources);
            }).toList();
            if(candidates.size()!=1)throw new IOException("NATIVE_OWNERSHIP_STAGE_CENSUS");var stage=candidates.getFirst();var argv=GraphJson.array(stage.get("argv")).stream().map(GraphJson::string).toList();
            int destination=argv.indexOf("-d");if(!argv.getFirst().equals("/usr/bin/javac")||destination<1||destination!=argv.lastIndexOf("-d")||destination+1>=argv.size())throw new IOException("NATIVE_OWNERSHIP_COMPILER");
            String directory=argv.get(destination+1);if(!directory.startsWith(work)||!directory.endsWith("/classes"))throw new IOException("NATIVE_OWNERSHIP_OUTPUT_SCOPE");
            String relative=directory.substring(work.length());LogicalPath.utf8(relative);String root=relative.substring(0,relative.length()-"/classes".length());
            var written=argv.stream().filter(a->a.endsWith(".java")).toList();if(written.isEmpty()||!sourceInputs.keySet().containsAll(written))throw new IOException("NATIVE_OWNERSHIP_SOURCE_CENSUS");
            for(var source:sourceInputs.entrySet())if(!written.contains(source.getKey())){
                String path=source.getKey();if(!path.startsWith(work)||!JavaEvidenceRecord.handle(source.getValue()).equals(outputs.get(path.substring(work.length()))))throw new IOException("NATIVE_OWNERSHIP_GENERATED_SOURCE");
            }
            var files=new TreeMap<String,ContentHandle>();for(var output:outputs.entrySet())if(output.getKey().startsWith(relative+"/")){
                if(!content.verify(output.getValue()))throw new IOException("NATIVE_OWNERSHIP_OUTPUT_CHANGED");files.put(output.getKey().substring(relative.length()+1),output.getValue());
            }
            boolean successful="COMPLETED".equals(stage.get("state"))&&((Number)stage.get("exit")).intValue()==0;
            if(successful&&files.isEmpty())throw new IOException("NATIVE_OWNERSHIP_EMPTY_OUTPUT");
            // Preserve this diagnostic's existing M1 repository-root module. Native
            // compilation contexts remain separate in the command/tree evidence.
            if(contexts.putIfAbsent(name,new Owner(directory,ModuleDescriptor.create(repository,".","captured compilation"),Collections.unmodifiableMap(files),CanonicalJson.sha256(stage),successful))!=null)throw new IOException("NATIVE_OWNERSHIP_DUPLICATE_CONTEXT");
        }
    }
    ModuleDescriptor module(String context)throws IOException {var owner=contexts.get(context);if(owner==null)throw new IOException("NATIVE_OWNERSHIP_MODULE_REQUIRED");return owner.module();}
    Optional<ModuleIdentity> owner(NativeContextValidator.Slot slot)throws IOException {
        if(slot.kind()!=NativeContextValidator.Slot.Kind.DIRECTORY)return Optional.empty();
        var owners=contexts.values().stream().filter(o->o.directory().equals(slot.path())).toList();if(owners.isEmpty())return Optional.empty();
        if(owners.size()!=1||!owners.getFirst().successful()||!owners.getFirst().files().equals(slot.files()))throw new IOException("NATIVE_OWNERSHIP_TREE_MISMATCH");
        return Optional.of(owners.getFirst().module().identity());
    }
    Map<String,Object> report(){return Map.of("schema","java-native-fixture-ownership:m8.4-v1","cohort",cohort,"contexts",contexts.entrySet().stream().map(e->Map.of("name",e.getKey(),"module",e.getValue().module(),"directory",e.getValue().directory(),"tree",e.getValue().files(),"stage",e.getValue().stage(),"successful",e.getValue().successful())).toList());}
    FrontendResult derived(FrontendResult primary,FrontendRequest request,Map<SourceDocumentIdentity,String> uris){
        var generated=new HashSet<SourceDocumentIdentity>();request.sources().stream().filter(s->s.document().classification()==SourceClassification.GENERATED_MAIN).forEach(s->generated.add(s.document().identity()));
        var declarations=primary.declarations().stream().map(d->{
            if(d.entity().origin()!=EntityOrigin.PROJECT||d.derivation().kind()!=DerivationKind.DIRECT)return d;
            var inputs=new ArrayList<String>();inputs.add(cohort.digest().value());
            if(d.entity().declaration().isPresent()){
                var document=d.entity().declaration().orElseThrow().document();if(!generated.contains(document))return d;
                request.sources().stream().filter(s->s.document().identity().equals(document)).findFirst().ifPresent(s->inputs.add(s.document().contentDigest().value()));
            }else{
                var binary=request.dependencies().stream().filter(b->b.reactorModule().map(m->m.value().equals(d.entity().stableScope().value())).orElse(false)).toList();if(binary.isEmpty())return d;
                // The context closure retains every ordered producer slot; do not
                // pick an arbitrary producer when they share the fixture's M1 scope.
                binary.forEach(b->inputs.add(b.entry().contentDigest().value()));
            }
            return new DeclarationRecord(d.entity(),d.spelling(),d.status(),new Derivation(DerivationKind.DERIVED,new VersionedIdentifier("fixture.native-output-import","1"),inputs),d.uncertainties(),d.diagnostics());
        }).toList();
        return new FrontendResult(primary.analysis(),primary.frontend(),primary.state(),declarations,primary.occurrences(),primary.observations(),primary.sources(),primary.coverage(),primary.diagnostics(),primary.types(),primary.annotations(),primary.derivedRelationships(),primary.typeDeclarations(),primary.memberDeclarations());
    }
}
