package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.UniversalBuildModel;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Passive operator registration. Only public verification keys are consumed. No signer,
 * target execution, ambient classpath discovery or serialized capability is accepted. */
public final class CapturedJavaReader {
    public record Source(SourceDocument document,ContentHandle content,SourceInput.Decoding decoding) {}
    public record Artifact(String name,ContentHandle content,PlatformInput.Format format) {}
    public record Platform(int release,String version,String vendor,List<Artifact> artifacts) {public Platform{artifacts=List.copyOf(artifacts);}}
    public record Ledger(ContentHandle census,ContentHandle ledger,ContentHandle projection,Set<ContentDigest> closedProducers,Set<ContentDigest> revoked) {public Ledger{closedProducers=Set.copyOf(closedProducers);revoked=Set.copyOf(revoked);}}
    /** Operational bootstrap envelope; canonical Java/M1 schemas are unchanged. */
    public record Capture(String schema,RepositorySnapshot snapshot,List<Source> sources,ReadinessPlan readiness,
            ContentDigest context,UniversalBuildModel model,ModuleDescriptor module,Platform platform,
            ContentHandle overlay,ContentHandle manifest,Ledger preAnalysis,Optional<Ledger> history,
            List<CapabilityGapRecord> gaps,JavaEvidenceTables.Table claims,ContentHandle semanticCensus,
            JavaEvidenceTables.Table correspondence,ContentDigest expectedView) {public Capture{sources=List.copyOf(sources);gaps=List.copyOf(gaps);}}
    /** This file is selected by the operator, outside the analyzed source snapshot.
     * Keys/methods and launch/profile/fence pins never come from captured source JSON. */
    public record Registration(String schema,String contentDirectory,ContentHandle capture,ContentDigest policy,
            Map<String,String> controllerKeys,Map<String,String> validatorKeys,List<EvidenceVerifier.Method> methods,
            ContentDigest launch,ContentDigest profile,long fence,String qualification) {public Registration{controllerKeys=Map.copyOf(controllerKeys);validatorKeys=Map.copyOf(validatorKeys);methods=List.copyOf(methods);}}
    public record Loaded(JavaEvidenceAssembler.Prepared prepared,JavaSemanticEvidence.Bundle semantic,
            Registration registration,RepositoryContentStore content,List<JavaEvidenceAuthority.Verified> readinessProofs) {
        public Loaded {readinessProofs=List.copyOf(readinessProofs);}
    }
    private final RepositoryContentStore content;private final JavaEvidenceTables tables;private final BooleanSupplier cancelled;
    public CapturedJavaReader(RepositoryContentStore content,BooleanSupplier cancelled){this.content=content;this.cancelled=cancelled;tables=new JavaEvidenceTables(content,2_000_000);}

    /** Persist exact reconstruction inputs. Existing attestations are referenced, never issued. */
    public ContentHandle capture(JavaEvidenceAssembler.Prepared prepared,JavaSemanticEvidence.Bundle semantic,JavaEvidenceTables.Table claims)throws IOException{
        var replay=prepared.replayInputs();var sources=new ArrayList<Source>();
        for(var source:prepared.base().files().values())sources.add(new Source(source.document(),content.put(new ByteArrayInputStream(source.bytes()),cancelled),source.decoding()));
        var artifacts=new ArrayList<Artifact>();for(var artifact:replay.platform().artifacts()){
            ContentHandle handle;try(var input=Files.newInputStream(artifact.path(),LinkOption.NOFOLLOW_LINKS)){handle=content.put(input,cancelled);}
            if(!handle.digest().equals(artifact.contentDigest()))throw invalid("PLATFORM_CHANGED");artifacts.add(new Artifact(artifact.logicalName(),handle,artifact.format()));
        }
        var history=prepared.graph().gapClosure();
        var capture=new Capture("captured-java-input:m8.4-v1",prepared.base().snapshot(),sources,prepared.readiness(),
                new ContentDigest(prepared.view().object("scope").get("context").toString()),replay.model(),replay.module(),
                new Platform(replay.platform().release(),replay.platform().version(),replay.platform().vendor(),artifacts),
                tables.put(prepared.overlay(),cancelled),tables.put(replay.manifest(),cancelled),ledger(replay.closure()),
                history.isPresent()?Optional.of(ledger(history.orElseThrow().publication())):Optional.empty(),prepared.graph().gapHistory(),claims,
                semantic.census(),replay.correspondence(),prepared.view().identity());
        return tables.putCanonical(capture,cancelled);
    }
    private Ledger ledger(JavaEvidenceLedger.Published published)throws IOException{
        return new Ledger(published.census(),tables.put(published.ledger(),cancelled),tables.put(published.projection(),cancelled),published.closedProducers(),published.revoked());
    }
    public Loaded load(Registration registration,Path views)throws IOException{
        try{return loadChecked(registration,views);}catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("CAPTURED_JAVA_RECORD_INVALID",invalid);}
    }
    private Loaded loadChecked(Registration registration,Path views)throws IOException{
        if(!registration.schema().equals("captured-java-registration:m8.4-v1")||registration.fence()<1
                ||!Set.of("PUBLIC_TEST_POLICY_ONLY","REGISTERED_VALIDATION").contains(registration.qualification()))throw invalid("REGISTRATION");
        var verifier=new EvidenceVerifier(keys(registration.controllerKeys()),keys(registration.validatorKeys()),registration.methods());
        if(!verifier.policyIdentity().equals(registration.policy()))throw invalid("POLICY");
        var capture=record(registration.capture(),Capture.class);
        if(!capture.schema().equals("captured-java-input:m8.4-v1"))throw invalid("SCHEMA");
        var overlay=tables.read(capture.overlay());
        if(!capture.context().value().equals(overlay.object("scope").get("context")))throw invalid("CONTEXT");
        var proofs=new TreeMap<ContentDigest,JavaEvidenceAuthority.Verified>();
        tables.read(capture.claims(),row->{
            check();if(!row.text("role").equals("CORRESPONDENCE"))throw invalid("CLAIM_ROLE");
            var raw=object(JavaEvidenceRecord.handle(row.fields().get("content")));
            if(!raw.keySet().equals(Set.of("schema","claim","inputClosure","receipt","controllerSignature","attestation","validatorSignature","publicTestAuthority")))throw invalid("ENVELOPE_FIELDS");
            boolean fixture=Boolean.TRUE.equals(raw.get("publicTestAuthority"));
            if(!Set.of("java-native-fixture-signed-claim:m8.4-v1","java-signed-claim:m8.4-v1").contains(raw.get("schema"))
                    ||fixture&&!registration.qualification().equals("PUBLIC_TEST_POLICY_ONLY"))throw invalid("QUALIFICATION");
            var claim=JavaEvidenceRecord.handle(raw.get("claim"));var closure=JavaEvidenceRecord.handle(raw.get("inputClosure"));require(closure);
            var proof=tables.read(claim);
            if(!proofScope(capture,overlay,proof)||!proof.digest("inputClosure").equals(closure.digest()))throw invalid("PROOF_SCOPE");
            if(GraphJson.array(proof.fields().get("limitations")).stream().anyMatch(v->v.toString().contains("TEST"))&&!registration.qualification().equals("PUBLIC_TEST_POLICY_ONLY"))throw invalid("QUALIFICATION");
            var receipt=CanonicalRecordReader.read(raw.get("receipt"),EvidenceVerifier.Receipt.class);
            var attestation=CanonicalRecordReader.read(raw.get("attestation"),EvidenceVerifier.Attestation.class);
            if((attestation.method().id().startsWith("fixture.")||proof.object("validator").get("id").toString().startsWith("fixture."))
                    &&!registration.qualification().equals("PUBLIC_TEST_POLICY_ONLY"))throw invalid("QUALIFICATION");
            var expected=new EvidenceVerifier.Expected(registration.launch(),registration.profile(),registration.fence(),new ContentDigest(proof.object("scope").get("context").toString()),closure.digest(),claim,attestation.method(),attestation.predicate(),attestation.tool());
            var token=verifier.verifyAccepted(expected,receipt,CanonicalRecordReader.read(raw.get("controllerSignature"),EvidenceVerifier.SignatureEnvelope.class),attestation,
                    CanonicalRecordReader.read(raw.get("validatorSignature"),EvidenceVerifier.SignatureEnvelope.class),content).orElseThrow(()->invalid("AUTHENTICATION"));
            var verified=new JavaEvidenceAuthority(registration.policy(),content).accept(proof,token).orElseThrow(()->invalid("PROOF"));
            if(proofs.putIfAbsent(verified.identity(),verified)!=null)throw invalid("DUPLICATE_PROOF");
        },cancelled);
        var files=new TreeMap<String,SourceInput>();for(var source:capture.sources()){
            var input=new SourceInput(source.document(),bytes(source.content(),4_000_000),source.decoding());
            if(files.putIfAbsent(input.document().path(),input)!=null)throw invalid("DUPLICATE_SOURCE");
        }
        var base=new RepositoryInputs(capture.snapshot(),files);var context=capture.readiness().contexts().stream().filter(c->c.identity().equals(capture.context())).findFirst().orElseThrow(()->invalid("CONTEXT"));
        var artifacts=new ArrayList<PlatformInput.Artifact>();
        for(Path ancestor=views.toAbsolutePath().normalize();ancestor!=null;ancestor=ancestor.getParent())if(Files.isSymbolicLink(ancestor))throw invalid("UNSAFE_VIEW_PATH");
        Files.createDirectories(views);
        for(var artifact:capture.platform().artifacts()){
            require(artifact.content());Path file=views.resolve("platform-"+artifact.content().digest().value().substring(7)+".bin");
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                Path temporary=Files.createTempFile(views,"platform-",".part");
                try{try(var in=content.open(artifact.content());var out=Files.newOutputStream(temporary,LinkOption.NOFOLLOW_LINKS)){in.transferTo(out);}
                    try{Files.createLink(file,temporary);}catch(FileAlreadyExistsException raced){/* verify the winning bytes below */}
                }finally{Files.deleteIfExists(temporary);}
            }
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw invalid("UNSAFE_VIEW_PATH");
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){if(!content.put(in,cancelled).equals(artifact.content()))throw invalid("PLATFORM_CHANGED");}
            artifacts.add(new PlatformInput.Artifact(artifact.name(),artifact.content().digest(),file,artifact.format()));
        }
        var platform=PlatformInput.create(capture.platform().release(),capture.platform().version(),capture.platform().vendor(),artifacts);
        var pre=restore(capture.preAnalysis(),proofs,registration.policy());
        var prepared=new JavaEvidenceAssembler(content,2_000_000).prepare(base,capture.readiness(),context,capture.model(),capture.module(),overlay,tables.read(capture.manifest()),platform,proofs,
                new JavaBinaryViews(content,views.resolve("binaries"),new JavaBinaryViews.Budget(20000,128000000,32000000)),pre,capture.correspondence(),20000,4000000,cancelled);
        if(!prepared.view().identity().equals(capture.expectedView()))throw invalid("VIEW_CHANGED");
        if(capture.history().isPresent())prepared=prepared.withGapClosure(new JavaEvidenceGapClosure(restore(capture.history().orElseThrow(),proofs,registration.policy()),capture.gaps(),proofs.values().stream().filter(p->p.record().text("question").equals(JavaEvidenceGapClosure.QUESTION)).toList()));
        else if(!capture.gaps().isEmpty())throw invalid("HISTORY_MISSING");
        require(capture.semanticCensus());return new Loaded(prepared,new JavaSemanticEvidence.Bundle(content,capture.semanticCensus(),proofs,20000,cancelled),registration,content,
                proofs.values().stream().filter(p->p.record().text("question").equals(JavaReadinessClosure.QUESTION)).toList());
    }
    private static boolean proofScope(Capture capture,JavaEvidenceRecord overlay,JavaEvidenceRecord proof)throws IOException{
        if(!proof.text("question").equals(JavaReadinessClosure.QUESTION))return proof.object("scope").equals(overlay.object("scope"));
        for(var context:capture.readiness().contexts())if(context.identity().value().equals(proof.object("scope").get("context"))){
            var module=capture.readiness().modules().stream().filter(m->m.id().equals(context.module())).findFirst().orElseThrow(()->invalid("PROOF_SCOPE"));
            return JavaEvidenceScopes.scope(capture.readiness(),context,ModuleDescriptor.create(capture.readiness().inventory().repository(),module.directory(),module.id())).equals(proof.object("scope"));
        }
        return false;
    }
    private JavaEvidenceLedger.Published restore(Ledger refs,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,ContentDigest policy)throws IOException{
        var ledger=tables.read(refs.ledger());var projection=tables.read(refs.projection());
        if(!ledger.digest("census").equals(refs.census().digest()))throw invalid("CENSUS");
        var requirements=new ArrayList<JavaEvidenceRecord>();var decisions=new ArrayList<JavaEvidenceRecord>();var bindings=new ArrayList<JavaEvidenceRecord>();
        tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("requirements")),requirements::add,cancelled);
        tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("decisions")),decisions::add,cancelled);
        tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("bindings")),bindings::add,cancelled);
        // A replay is an explicit append-only revision, retaining its complete old head.
        var restored=new JavaEvidenceLedger().publish(ledger.object("scope"),refs.census(),policy,requirements,decisions,bindings,Optional.of(ledger),proofs,refs.revoked(),refs.closedProducers(),tables,cancelled);
        if(!restored.projection().identity().equals(projection.identity()))throw invalid("LEDGER_REPLAY");return restored;
    }
    private Map<String,Object> object(ContentHandle handle)throws IOException{
        var raw=bytes(handle,8_000_000);var value=GraphJson.object(GraphJson.read(new String(raw,StandardCharsets.UTF_8),new GraphJson.Limits(8_000_000,128,8_000_000)));
        if(!CanonicalJson.sha256(value).equals(handle.digest()))throw invalid("NONCANONICAL");return value;
    }
    private <T>T record(ContentHandle handle,Class<T> type)throws IOException{
        var value=object(handle);
        var result=CanonicalRecordReader.read(value,type);if(!CanonicalJson.sha256(result).equals(handle.digest()))throw invalid("NONCANONICAL");return result;
    }
    private void require(ContentHandle handle)throws IOException{check();try{if(!content.verify(handle))throw invalid("CONTENT_UNAVAILABLE");}catch(IOException unavailable){throw new IOException("CAPTURED_JAVA_CONTENT_UNAVAILABLE",unavailable);}}
    private byte[] bytes(ContentHandle handle,int limit)throws IOException{require(handle);if(handle.size()>limit)throw invalid("WORKER_REQUIRED");try(var input=content.open(handle)){var bytes=input.readNBytes(limit+1);if(bytes.length!=handle.size())throw invalid("CONTENT_CHANGED");return bytes;}}
    private void check(){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
    private static Map<String,PublicKey> keys(Map<String,String> encoded)throws IOException{
        var keys=new TreeMap<String,PublicKey>();try{var factory=KeyFactory.getInstance("Ed25519");for(var entry:encoded.entrySet())keys.put(entry.getKey(),factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue()))));}
        catch(GeneralSecurityException|IllegalArgumentException invalid){throw new IOException("CAPTURED_JAVA_PUBLIC_KEY_INVALID",invalid);}return keys;
    }
    public static Registration registration(Path root)throws IOException{
        Path file=root.resolve("registration.json");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>2_000_000)throw invalid("REGISTRATION");
        try{String raw=Files.readString(file,StandardCharsets.UTF_8);var registration=CanonicalRecordReader.read(GraphJson.read(raw,new GraphJson.Limits(2_000_000,64,2_000_000)),Registration.class);
            if(!CanonicalJson.write(registration).equals(raw))throw invalid("NONCANONICAL_REGISTRATION");return registration;
        }catch(IllegalArgumentException|ArithmeticException invalid){throw new IOException("CAPTURED_JAVA_REGISTRATION_INVALID",invalid);}
    }
    private static IOException invalid(String code){return new IOException("CAPTURED_JAVA_"+code);}
}
