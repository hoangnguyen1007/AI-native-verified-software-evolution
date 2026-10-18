package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** One journal transaction publishes the whole report batch. Bodies remain in CAS and are decoded on demand. */
public final class PersistentReports implements AutoCloseable {
    public record Envelope(String schema,PublishedReports.Report report){public Envelope{if(!schema.equals("published-report:m8.4-v2"))throw new IllegalArgumentException("Unknown report schema");}}
    private final StreamingContentStore content;private final SqliteInventoryStore index;
    public PersistentReports(Path root)throws IOException{content=new StreamingContentStore(root.resolve("content"));index=new SqliteInventoryStore(root.resolve("metadata"),content);}
    synchronized List<ContentDigest> publish(List<PublishedReports.Report> batch)throws IOException{
        if(batch.size()>32)throw new IllegalArgumentException("Invalid report batch");var entries=new ArrayList<SqliteInventoryStore.Observation>();var identities=new ArrayList<ContentDigest>();
        for(var report:batch){var id=report.identity();identities.add(id);byte[] bytes=BoundedJson.encode(new Envelope("published-report:m8.4-v2",report),16_777_216);var handle=content.put(new ByteArrayInputStream(bytes),()->false);
            var entry=new InventoryEntry(LogicalPath.utf8(id.value().substring(7)),InventoryEntry.Kind.FILE,"100644",InventoryEntry.Population.UNKNOWN,InventoryEntry.Disposition.ACQUIRED,Optional.of(handle),Optional.of(report.evaluation().graph().value()),Optional.of("UTF-8"),List.of(),Optional.empty());
            var previous=index.observation("reports",entry.path());if(previous.isPresent()&&!previous.orElseThrow().entry().equals(entry))throw new IOException("REPORT_IDENTITY_CONFLICT");if(previous.isEmpty())entries.add(new SqliteInventoryStore.Observation(entry,""));
        }
        if(!entries.isEmpty())index.entries("reports",entries);return List.copyOf(identities);
    }
    synchronized Optional<PublishedReports.Report> get(ContentDigest identity)throws IOException{
        var found=index.observation("reports",LogicalPath.utf8(identity.value().substring(7)));if(found.isEmpty())return Optional.empty();var handle=found.orElseThrow().entry().content().orElseThrow();
        if(handle.size()>16_777_216||!content.verify(handle))throw new IOException("REPORT_CORRUPT");
        try(var reader=new InputStreamReader(content.open(handle),StandardCharsets.UTF_8)){
            var report=CanonicalRecordReader.read(GraphJson.read(reader,new GraphJson.Limits(16_777_216,128,2_000_000)),Envelope.class).report();
            if(!report.identity().equals(identity)||!CanonicalJson.sha256(new Envelope("published-report:m8.4-v2",report)).equals(handle.digest()))throw new IOException("REPORT_NONCANONICAL");return Optional.of(report);
        }catch(IllegalArgumentException invalid){throw new IOException("REPORT_CORRUPT",invalid);}
    }
    synchronized List<ContentDigest> forGraph(ContentDigest graph)throws IOException{
        var ids=new ArrayList<ContentDigest>();Optional<String> cursor=Optional.empty();do{var page=index.page("reports",256,cursor);for(var entry:page.entries())if(entry.target().filter(graph.value()::equals).isPresent()){
            if(ids.size()>=32)throw new IOException("REPORT_QUERY_PAGING_REQUIRED");ids.add(new ContentDigest("sha256:"+entry.path().utf8().orElseThrow()));
        }cursor=page.continuation();}while(cursor.isPresent());return ids.stream().sorted().toList();
    }
    @Override public void close()throws IOException{index.close();}
}
