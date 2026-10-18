package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Local operator selections only. Authentication stays in the configured broker, never in this catalog. */
final class ConnectionCatalog implements AutoCloseable {
    record Selection(String schema,String source,Optional<String> policy){Selection{if(!schema.equals("repository-connection:m8.4-v2"))throw new IllegalArgumentException("Unknown connection schema");}}
    private final StreamingContentStore content;private final SqliteInventoryStore index;
    ConnectionCatalog(Path root)throws IOException{content=new StreamingContentStore(root.resolve("content"));index=new SqliteInventoryStore(root.resolve("metadata"),content);}
    void save(String id,String source,Optional<String> policy)throws IOException{
        var selection=new Selection("repository-connection:m8.4-v2",source,policy);var handle=content.put(new ByteArrayInputStream(CanonicalJson.write(selection).getBytes(StandardCharsets.UTF_8)),()->false);
        index.entries("connections",List.of(new SqliteInventoryStore.Observation(new InventoryEntry(LogicalPath.utf8(id),InventoryEntry.Kind.FILE,"100644",InventoryEntry.Population.UNKNOWN,InventoryEntry.Disposition.ACQUIRED,Optional.of(handle),Optional.empty(),Optional.of("UTF-8"),List.of(),Optional.empty()),"")));
    }
    List<Selection> load()throws IOException{
        // Connection admission remains an explicit bounded operator allocation; job history has its own disk denominator.
        var page=index.page("connections",33,Optional.empty());if(page.continuation().isPresent()||page.entries().size()>32)throw new IOException("CONNECTION_ALLOCATION_REQUIRED");var result=new ArrayList<Selection>();
        for(var entry:page.entries()){var handle=entry.content().orElseThrow();if(handle.size()>1_048_576||!content.verify(handle))throw new IOException("CONNECTION_CATALOG_CORRUPT");try(var input=content.open(handle)){
            var selection=CanonicalRecordReader.read(GraphJson.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),new GraphJson.Limits(1_048_576,16,100)),Selection.class);
            if(!CanonicalJson.sha256(selection).equals(handle.digest()))throw new IOException("CONNECTION_CATALOG_CORRUPT");result.add(selection);
        }}return List.copyOf(result);
    }
    @Override public void close()throws IOException{index.close();}
}
