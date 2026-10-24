package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.ingestion.RepositoryInputs;
import java.io.*;
import java.util.*;
import javassist.bytecode.*;

/** Builds the fixed cohort's source/member census from actual, non-loaded class bytes. */
final class NativeSourceBinaryFixtureMapping {
    private final RepositoryContentStore content;private final JavaEvidenceTables tables;private final JavaEvidenceCodec codec=new JavaEvidenceCodec();
    NativeSourceBinaryFixtureMapping(RepositoryContentStore content){this.content=content;tables=new JavaEvidenceTables(content,2_000_000);}
    List<JavaEvidenceRecord> rows(RepositoryInputs base,ModuleDescriptor module,PlatformInput platform,List<NativeContextValidatorSlot> classpath,
            JavaEvidenceRecord witness,Map<String,ContentHandle> classes,ContentHandle transformation)throws IOException {
        var binaries=new ArrayList<BinaryInput>();for(var input:classpath)binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,input.logicalName(),input.content().digest()),input.path()));
        var sources=base.files().values().stream().filter(s->s.document().path().endsWith(".java")).toList();var docs=sources.stream().map(SourceInput::document).toList();var plan=FrontendPlan.legacy();
        var options=new TreeMap<>(FrontendRequest.options(plan,module.identity(),SourceClassification.MAIN,docs,platform));options.putAll(FrontendRequest.binaryOriginOptions(binaries));var entries=new ArrayList<ClasspathEntry>();entries.add(platform.entry());binaries.forEach(b->entries.add(b.entry()));
        var component=new ManifestComponent(JavaParserFrontend.PROVIDER,CanonicalJson.sha256(Map.of("provider",JavaParserFrontend.PROVIDER,"transformation",transformation)));
        var manifest=AnalysisManifest.create(new VersionedIdentifier("analysis.manifest","1"),base.snapshot(),List.of(module),entries,AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration","1"),options),component,component,component);
        var baseline=new JavaParserFrontend().analyze(new FrontendRequest(manifest,module.identity(),SourceClassification.MAIN,plan,sources,platform,binaries));
        var entities=new HashMap<String,Entity>();baseline.declarations().stream().map(DeclarationRecord::entity).filter(e->e.declaration().isPresent()).forEach(e->entities.put(e.canonicalName(),e));
        var rows=new ArrayList<JavaEvidenceRecord>();for(var input:classes.entrySet())if(input.getKey().endsWith(".class")){
            ClassFile type;try(var in=content.open(input.getValue())){type=new ClassFile(new DataInputStream(in));}
            String qualified=type.getName();int split=qualified.lastIndexOf('.');String pkg=split<0?"":qualified.substring(0,split);var owners=List.of(qualified.substring(split+1).split("\\$"));var symbol=symbol(qualified);
            var sourceOwner=entities.get(JavaSymbolName.topLevelType(pkg,owners.getFirst()).canonicalName());if(sourceOwner==null)throw new IOException("NATIVE_MEMBER_SOURCE_OWNER_REQUIRED");
            rows.add(row(witness,base,sourceOwner,entities.get(symbol.canonicalName()),pkg,owners,EntityKind.TYPE,owners.getLast(),"",transformation));
            for(var field:type.getFields()){var key=JavaSymbolName.field(symbol,field.getName());rows.add(row(witness,base,sourceOwner,entities.get(key.canonicalName()),pkg,owners,EntityKind.FIELD,field.getName(),field.getDescriptor(),transformation));}
            for(var method:type.getMethods()){
                if(method.isStaticInitializer())continue;var parameters=parameters(method.getDescriptor());var kind=method.isConstructor()?EntityKind.CONSTRUCTOR:EntityKind.METHOD;var key=method.isConstructor()?JavaSymbolName.constructor(symbol,parameters):JavaSymbolName.method(symbol,method.getName(),parameters);
                rows.add(row(witness,base,sourceOwner,entities.get(key.canonicalName()),pkg,owners,kind,method.getName(),method.getDescriptor(),transformation));
            }
        }return List.copyOf(rows);
    }
    record NativeContextValidatorSlot(String logicalName,ContentHandle content,java.nio.file.Path path){}
    private JavaEvidenceRecord row(JavaEvidenceRecord slot,RepositoryInputs base,Entity owner,Entity source,String pkg,List<String> owners,EntityKind kind,String name,String descriptor,ContentHandle transformation)throws IOException {
        var endpoint=Map.of("schema","java-binary-member:m8.4-v1","slot",slot.identity(),"package",pkg,"owners",owners,"kind",kind,"name",name,"descriptor",descriptor,"transformation",transformation);
        var selected=source==null?owner:source;var document=selected.declaration().orElseThrow().document();var sourceBytes=base.files().values().stream().filter(s->s.document().identity().equals(document)).findFirst().orElseThrow();
        var fields=new TreeMap<String,Object>();fields.put("schema","java-correspondence:m8.4-v1");fields.put("scope",slot.fields().get("scope"));fields.put("sourceEvidence",tables.putCanonical(selected,()->false));fields.put("binaryOrGeneratedEvidence",tables.putCanonical(endpoint,()->false));fields.put("sourceContent",new ContentHandle(sourceBytes.document().contentDigest(),sourceBytes.bytes().length));fields.put("outputContent",slot.fields().get("original"));fields.put("outcome",source==null?"AUGMENTS":"SAME_DECLARATION");fields.put("predicate",new VersionedIdentifier(JavaSourceCorrespondence.PREDICATE,JavaEvidenceAuthority.VERSION));fields.put("proof",slot.digest("proof"));fields.put("supportingSpans",List.of());fields.put("reason",null);return codec.create(fields);
    }
    private static JavaSymbolName symbol(String binary){int at=binary.lastIndexOf('.');String pkg=at<0?"":binary.substring(0,at);String[] owners=binary.substring(at+1).split("\\$");var result=JavaSymbolName.topLevelType(pkg,owners[0]);for(int i=1;i<owners.length;i++)result=JavaSymbolName.memberType(result,owners[i]);return result;}
    private static List<ErasedType> parameters(String descriptor)throws IOException {if(!descriptor.startsWith("("))throw new IOException("NATIVE_MEMBER_DESCRIPTOR");int[] at={1};var result=new ArrayList<ErasedType>();while(at[0]<descriptor.length()&&descriptor.charAt(at[0])!=')')result.add(type(descriptor,at));if(at[0]>=descriptor.length())throw new IOException("NATIVE_MEMBER_DESCRIPTOR");return result;}
    private static ErasedType type(String descriptor,int[] at)throws IOException {if(at[0]>=descriptor.length())throw new IOException("NATIVE_MEMBER_DESCRIPTOR");char c=descriptor.charAt(at[0]++);if(c=='[')return ErasedType.array(type(descriptor,at),1);if(c=='L'){int end=descriptor.indexOf(';',at[0]);if(end<0)throw new IOException("NATIVE_MEMBER_DESCRIPTOR");var result=ErasedType.declared(symbol(descriptor.substring(at[0],end).replace('/','.')));at[0]=end+1;return result;}String primitive=switch(c){case 'Z'->"boolean";case 'B'->"byte";case 'S'->"short";case 'C'->"char";case 'I'->"int";case 'J'->"long";case 'F'->"float";case 'D'->"double";default->throw new IOException("NATIVE_MEMBER_DESCRIPTOR");};return ErasedType.primitive(primitive);}
}
