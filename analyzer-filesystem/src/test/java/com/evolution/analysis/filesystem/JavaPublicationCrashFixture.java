package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Trusted platform fault worker. It never executes target code or imports target classes. */
public final class JavaPublicationCrashFixture {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);String mode=args[1];var delegate=new StreamingContentStore(root.resolve("cas"));var key=ContentDigest.sha256Utf8("crash control");
        if(mode.equals("inspect")){
            var publication=new JavaEvidencePublicationStore(root.resolve("publications"),delegate).load(key).orElseThrow();
            var tables=new JavaEvidenceTables(delegate,100000);
            var ledger=tables.read(publication.artifacts().get("ledger"));
            var projection=tables.read(publication.artifacts().get("projection"));
            var requirements=new ArrayList<JavaEvidenceRecord>();var decisions=new ArrayList<JavaEvidenceRecord>();
            tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("requirements")),requirements::add,()->false);
            tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("decisions")),decisions::add,()->false);
            tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("bindings")),row->{},()->false);
            tables.read(JavaEvidenceTables.Table.from(projection.fields().get("current")),row->{},()->false);
            if(requirements.size()!=1||new JavaEvidenceLedger().fold(requirements,decisions,Map.of(),Set.of(),Set.of()).active()!=1
                    ||((Number)projection.object("counts").get("active")).longValue()!=1)throw new IOException("CRASH_REPLAY_CENSUS_CHANGED");
            System.out.print(publication.document().digest().value());return;
        }
        if(!Set.of("before-cas","before-pointer","after-pointer","before-table","after-table","before-ledger","after-ledger").contains(mode))throw new IllegalArgumentException("Unknown crash boundary");
        var next=delegate.put(new ByteArrayInputStream(new byte[]{3,4}),()->false);
        RepositoryContentStore interrupted=new RepositoryContentStore(){
            public ContentHandle put(InputStream input,BooleanSupplier cancelled)throws IOException{
                byte[] bytes=input.readAllBytes();String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                boolean table=text.endsWith("\n")&&text.contains("\"schema\":\"java-closure-requirement:m8.4-v1\"");
                boolean ledger=text.contains("\"schema\":\"java-closure-ledger:m8.4-v1\"");
                boolean publication=text.contains("\"schema\":\"java-evidence-publication:m8.4-v1\"");
                if(publication&&mode.equals("before-cas"))Runtime.getRuntime().halt(90);
                if(table&&mode.equals("before-table"))Runtime.getRuntime().halt(93);
                if(ledger&&mode.equals("before-ledger"))Runtime.getRuntime().halt(95);
                var handle=delegate.put(new ByteArrayInputStream(bytes),cancelled);
                if(publication&&mode.equals("before-pointer"))Runtime.getRuntime().halt(91);
                if(table&&mode.equals("after-table"))Runtime.getRuntime().halt(94);
                if(ledger&&mode.equals("after-ledger"))Runtime.getRuntime().halt(96);
                return handle;
            }
            public InputStream open(ContentHandle h)throws IOException{return delegate.open(h);}
            public InputStream range(ContentHandle h,long offset,long length)throws IOException{return delegate.range(h,offset,length);}
            public boolean verify(ContentHandle h)throws IOException{return delegate.verify(h);}
        };
        var store=new JavaEvidencePublicationStore(root.resolve("publications"),interrupted);var prior=store.load(key).orElseThrow();
        var tables=new JavaEvidenceTables(interrupted,100000);String digest=ContentDigest.sha256Utf8("captured crash scope").value();
        var scope=Map.<String,Object>of("inventory",digest,"baseSnapshot","snapshot:"+digest,"readiness",digest,"selection",digest,"context",digest,
                "logicalScope",digest,"module","module:"+digest,"sourceSet","main","variant","baseline");
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-closure-requirement:m8.4-v1");fields.put("scope",scope);
        fields.put("question","java.output-bytes");fields.put("subject","captured output");fields.put("category","generation");
        fields.put("predicate",Map.of("id","java.output-bytes","version","m8.4-step4-v1"));
        for(String name:List.of("gapRefs","occurrences","readinessRefs","prerequisites"))fields.put(name,List.of());
        var requirement=new JavaEvidenceCodec().create(fields);var census=tables.putCanonical(List.of(requirement.fields()),()->false);
        var published=new JavaEvidenceLedger().publish(scope,census,new ContentDigest(digest),List.of(requirement),List.of(),List.of(),
                Optional.empty(),Map.of(),Set.of(),Set.of(),tables,()->false);
        store.commit(key,Optional.of(prior.document().digest()),Map.of("graph",next,"ledger",tables.put(published.ledger(),()->false),"projection",tables.put(published.projection(),()->false)));
        Runtime.getRuntime().halt(92);
    }
}
