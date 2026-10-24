package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.execution.javac.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.util.*;

/** Exercises real capability issuance against frozen native inputs, under a public TEST policy.
 * Nothing here registers a production validator or closes a production ledger obligation.
 */
final class NativeFixtureClaims {
    private static final VersionedIdentifier PROVIDER=new VersionedIdentifier("fixture.native-validator","1");
    private final RepositoryContentStore content;private final NativeEvidenceSigningFixture signer;private final JavaEvidenceTables tables;
    NativeFixtureClaims(RepositoryContentStore content)throws IOException {this.content=content;signer=new NativeEvidenceSigningFixture(content);tables=new JavaEvidenceTables(content,2_000_000);}
    Map<String,Object> issue(ContentHandle binding,ContentHandle ownership,Map<String,Object> validation,
            CapturedJavacReader.Capture capture,FrontendRequest request,FrontendResult frontend,
            Map<SourceDocumentIdentity,String> uris,List<NativeContextValidator.Slot> slots,
            Collection<ContentHandle> platformFiles)throws IOException {
        if(!capture.current()||!capture.traversalComplete()||!capture.document().equals(JavaEvidenceRecord.handle(validation.get("capture"))))throw new IOException("FIXTURE_CAPTURE_NOT_CURRENT");
        // Recompute checks; a persisted report containing true booleans is not authority.
        var mapped=new NativeJavaSymbolMapping(capture,request,frontend,uris,slots).compare();
        var census=new OccurrenceCensusVerifier().verify(capture,frontend,uris,request.sources());
        if(((Number)census.report().get("unavailableNativeCategories")).longValue()!=0)throw new IOException("FIXTURE_CENSUS_UNAVAILABLE");
        var scope=new TreeMap<String,Object>();String context=CanonicalJson.sha256(Map.of("validation",validation,"ownership",ownership,"tool",signer.tool())).value();
        scope.put("inventory",CanonicalJson.sha256(request.sources().stream().map(SourceInput::document).toList()).value());scope.put("baseSnapshot",request.manifest().snapshot().identity().value());scope.put("readiness",binding.digest().value());
        scope.put("selection",CanonicalJson.sha256(Map.of("sources",capture.context().sources(),"slots",slots)).value());scope.put("context",context);
        scope.put("logicalScope",CanonicalJson.sha256(Map.of("module",request.module(),"sourceSet","main")).value());scope.put("module",request.module().value());scope.put("sourceSet","main");scope.put("variant","native-fixture-v1");
        var closure=put(Map.of("schema","java-native-fixture-input-closure:m8.4-v1","scope",scope,"binding",binding,"ownership",ownership,"validation",validation,"capture",capture.document(),"census",put(census.report()),"tool",signer.tool()));
        var inputRows=new TreeMap<String,JavaEvidenceRecord>();
        add(inputRows,"CONFIG",closure);add(inputRows,"TOOL",signer.tool());add(inputRows,"CORRESPONDENCE",binding);add(inputRows,"CORRESPONDENCE",ownership);add(inputRows,"CORRESPONDENCE",capture.document());
        for(var file:platformFiles)add(inputRows,"PLATFORM",file);
        for(var file:capture.context().sources().values())add(inputRows,"SOURCE",file);
        for(var slot:slots)for(var file:slot.files().values())add(inputRows,"MATERIAL",file);
        var contextAnswer=put(Map.of("schema","java-native-fixture-resolution-answer:m8.4-v1","scope",scope,"validation",validation,"compilerState",capture.state(),"publicTestAuthority",true));
        var resolution=signer.issue(scope,"java.resolution-context",context,closure,contextAnswer,table(inputRows.values()),checks("java.resolution-context",List.of(contextAnswer,closure,signer.tool())));
        var resolutionProof=put(resolution.verified().record().fields());
        var view=CanonicalJson.sha256(Map.of("closure",closure,"resolutionProof",resolutionProof,"frontend",CanonicalJson.sha256(frontend)));
        var entities=new HashMap<EntityIdentity,Entity>();frontend.declarations().forEach(d->entities.put(d.entity().identity(),d.entity()));
        var observations=new HashMap<ContentDigest,Map<String,Object>>();capture.observations().forEach(row->observations.put(CanonicalJson.sha256(row),row));
        var occurrences=new HashMap<String,RelationshipOccurrence>();for(var occurrence:frontend.occurrences())occurrences.put(key(occurrence.relationship().kind().value(),occurrence.span()),occurrence);
        var refs=new ArrayList<JavaEvidenceRecord>();var subjects=new HashSet<ContentDigest>();long withheld=0;
        for(var row:mapped.rows()){
            if(!"MAPPED".equals(row.get("disposition"))){withheld++;continue;}
            if(!Boolean.TRUE.equals(row.get("callerMapped"))||!Boolean.TRUE.equals(row.get("targetMapped"))||!Boolean.TRUE.equals(row.get("originMapped"))||!Boolean.TRUE.equals(row.get("provenanceValid")))throw new IOException("FIXTURE_UNPROVEN_OCCURRENCE");
            var span=(SourceSpan)row.get("span");String category=GraphJson.string(row.get("category"));var occurrence=occurrences.get(key(category,span));
            if(occurrence==null||occurrence.status()!=SemanticStatus.RESOLVED||!(occurrence.relationship().target() instanceof RelationshipTarget.Resolved target))throw new IOException("FIXTURE_UNRESOLVED_OCCURRENCE");
            var caller=entities.get(occurrence.relationship().source());var endpoint=entities.get(target.target());if(caller==null||endpoint==null||!caller.identity().equals(row.get("caller"))||!endpoint.identity().equals(row.get("target")))throw new IOException("FIXTURE_ENTITY_MISMATCH");
            var nativeRow=observations.get(row.get("nativeObservation"));if(nativeRow==null)throw new IOException("FIXTURE_NATIVE_ROW_REQUIRED");
            var answerFields=new TreeMap<String,Object>();answerFields.put("schema","java-compiler-attribution-row:m8.4-v1");answerFields.put("scope",scope);answerFields.put("inputView",view);answerFields.put("primary",frontend.frontend());answerFields.put("provider",PROVIDER);answerFields.put("document",span.document());answerFields.put("sourceContent",row.get("sourceContent"));answerFields.put("category",category);answerFields.put("span",span);answerFields.put("caller",put(caller));answerFields.put("targets",List.of(put(endpoint)));answerFields.put("status","RESOLVED");answerFields.put("recovery",false);
            var answer=put(answerFields);var mapping=put(row);var nativeEvidence=put(nativeRow);var inputs=new TreeMap<String,JavaEvidenceRecord>();add(inputs,"CONFIG",closure);add(inputs,"CORRESPONDENCE",resolutionProof);add(inputs,"CORRESPONDENCE",nativeEvidence);add(inputs,"SOURCE",(ContentHandle)row.get("sourceContent"));
            var subject=JavaCompilerAttribution.subject(scope,view,span.document(),category,span);if(!subjects.add(subject))throw new IOException("FIXTURE_DUPLICATE_SUBJECT");
            // OCCURRENCE_CENSUS is scoped to this unique, requested location. Raw
            // imports and error sites remain in the complete captured denominator.
            var issued=signer.issue(scope,"java.semantic-occurrence",subject.value(),closure,answer,table(inputs.values()),checks("java.semantic-occurrence",List.of(answer,mapping,nativeEvidence,resolutionProof)));
            refs.add(input("CORRESPONDENCE",issued.envelope()));
        }
        return Map.of("schema","java-native-fixture-claims:m8.4-v1","policy",signer.policy(),"tool",signer.tool(),"scope",scope,"inputClosure",closure,"resolution",resolution.envelope(),"semantic",table(refs).wire(),"issued",refs.size(),"withheld",withheld,"publicTestAuthority",true);
    }
    long replay(Map<String,Object> report)throws IOException {
        if(!"java-native-fixture-claims:m8.4-v1".equals(report.get("schema"))||!Boolean.TRUE.equals(report.get("publicTestAuthority"))||!signer.policy().value().equals(report.get("policy")))throw new IOException("FIXTURE_REPLAY_POLICY");
        var scope=GraphJson.object(report.get("scope"));var closure=JavaEvidenceRecord.handle(report.get("inputClosure"));
        var resolution=signer.replay(JavaEvidenceRecord.handle(report.get("resolution")));require(resolution,"java.resolution-context",scope,closure);
        var subjects=new HashSet<String>();long[] count={0};
        tables.read(JavaEvidenceTables.Table.from(report.get("semantic")),row->{
            if(!row.text("role").equals("CORRESPONDENCE"))throw new IOException("FIXTURE_REPLAY_ROLE");
            var claim=signer.replay(JavaEvidenceRecord.handle(row.fields().get("content")));require(claim,"java.semantic-occurrence",scope,closure);
            if(!subjects.add(claim.verified().record().text("subject")))throw new IOException("FIXTURE_REPLAY_DUPLICATE");count[0]++;
        },()->false);
        if(count[0]!=((Number)report.get("issued")).longValue())throw new IOException("FIXTURE_REPLAY_COUNT");return count[0];
    }
    private void require(NativeEvidenceSigningFixture.Issued issued,String question,Map<String,Object> scope,ContentHandle closure)throws IOException {
        var record=issued.verified().record();if(!question.equals(record.text("question"))||!scope.equals(record.object("scope"))||!record.digest("inputClosure").equals(closure.digest()))throw new IOException("FIXTURE_REPLAY_CLAIM_SCOPE");
    }
    private ContentHandle put(Object value)throws IOException{return tables.putCanonical(value,()->false);}
    private JavaEvidenceRecord input(String role,ContentHandle ref){var row=new LinkedHashMap<String,Object>();row.put("schema","java-evidence-input:m8.4-v1");row.put("role",role);row.put("content",ref);row.put("locator",null);row.put("provider",PROVIDER);return new JavaEvidenceCodec().create(row);}
    private void add(Map<String,JavaEvidenceRecord> rows,String role,ContentHandle ref){rows.put(role+":"+ref.digest().value(),input(role,ref));}
    private JavaEvidenceTables.Table table(Collection<JavaEvidenceRecord> rows)throws IOException{return tables.write("java-evidence-input",rows.stream().sorted(JavaEvidenceTables::compare).iterator(),()->false);}
    private static Map<String,List<ContentHandle>> checks(String question,List<ContentHandle> evidence){var checks=new TreeMap<String,List<ContentHandle>>();JavaEvidenceAuthority.requiredChecks(question).forEach(name->checks.put(name,evidence));return checks;}
    private static String key(String category,SourceSpan span){return CanonicalJson.write(Map.of("category",category,"span",span));}
}
