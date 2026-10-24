package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fenced CAS publication of evidence handles, not a semantic acceptance authority. */
public final class JavaEvidencePublicationStore {
    public record Publication(ContentHandle document,Optional<ContentDigest> parent,Map<String,ContentHandle> artifacts){public Publication{artifacts=Collections.unmodifiableMap(new TreeMap<>(artifacts));}}
    private final Path root;private final RepositoryContentStore content;
    public JavaEvidencePublicationStore(Path root,RepositoryContentStore content)throws IOException{this.root=StreamingContentStore.directory(root);this.content=Objects.requireNonNull(content);}
    public Publication commit(ContentDigest registration,Optional<ContentDigest> expected,Map<String,ContentHandle> artifacts)throws IOException{
        if(artifacts.isEmpty()||artifacts.keySet().stream().anyMatch(k->!k.matches("[a-z][a-z0-9-]*")))throw new IllegalArgumentException("JAVA_PUBLICATION_ROLES_INVALID");
        Path key=root.resolve(registration.value().substring(7)+".lock");StreamingContentStore.safe(key);
        try(var channel=FileChannel.open(key,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("JAVA_PUBLICATION_BUSY",busy);}if(lock==null)throw new IOException("JAVA_PUBLICATION_BUSY");
            try(lock){
                var previous=load(registration);var current=previous.map(p->p.document().digest());if(!expected.equals(current))throw new IOException("JAVA_PUBLICATION_FENCE_MISMATCH");
                for(var handle:artifacts.values())if(!content.verify(handle))throw new IOException("JAVA_PUBLICATION_CONTENT_UNAVAILABLE");
                if(previous.isPresent()&&previous.orElseThrow().artifacts().equals(artifacts))return previous.orElseThrow();
                var fields=new TreeMap<String,Object>();fields.put("schema","java-evidence-publication:m8.4-v1");fields.put("registration",registration);fields.put("parent",current.orElse(null));fields.put("artifacts",new TreeMap<>(artifacts));fields.put("trust","PRODUCER_ASSERTION");
                byte[] raw=CanonicalJson.write(fields).getBytes(StandardCharsets.UTF_8);if(raw.length>2_000_000)throw new IOException("JAVA_PUBLICATION_WORKER_CAPACITY_REQUIRED");var document=content.put(new ByteArrayInputStream(raw),()->false);
                Path temporary=Files.createTempFile(root,"publication-",".part");
                try{
                    byte[] pointer=CanonicalJson.write(Map.of("document",document)).getBytes(StandardCharsets.UTF_8);
                    try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(pointer);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
                    Files.move(temporary,pointer(registration),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temporary);}
                return new Publication(document,current,artifacts);
            }
        }
    }
    public Optional<Publication> load(ContentDigest registration)throws IOException{
        Path path=pointer(registration);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>4096)throw new IOException("JAVA_PUBLICATION_POINTER_INVALID");
        Map<String,Object> pointer;try(var in=Files.newBufferedReader(path,StandardCharsets.UTF_8)){pointer=GraphJson.object(GraphJson.read(in,new GraphJson.Limits(4096,8,100)));}
        if(!pointer.keySet().equals(Set.of("document")))throw new IOException("JAVA_PUBLICATION_POINTER_INVALID");var handle=JavaEvidenceRecord.handle(pointer.get("document"));
        if(handle.size()>2_000_000||!content.verify(handle))throw new IOException("JAVA_PUBLICATION_DOCUMENT_UNAVAILABLE");
        Map<String,Object> fields;try(var in=content.open(handle);var reader=new InputStreamReader(in,StandardCharsets.UTF_8)){fields=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(2_000_000,16,100_000)));}
        if(!fields.keySet().equals(Set.of("schema","registration","parent","artifacts","trust"))||!"java-evidence-publication:m8.4-v1".equals(fields.get("schema"))||!registration.value().equals(fields.get("registration"))||!"PRODUCER_ASSERTION".equals(fields.get("trust")))throw new IOException("JAVA_PUBLICATION_SCOPE_MISMATCH");
        var artifacts=new TreeMap<String,ContentHandle>();for(var entry:GraphJson.object(fields.get("artifacts")).entrySet()){var artifact=JavaEvidenceRecord.handle(entry.getValue());if(!content.verify(artifact))throw new IOException("JAVA_PUBLICATION_CONTENT_UNAVAILABLE");artifacts.put(entry.getKey(),artifact);}
        return Optional.of(new Publication(handle,fields.get("parent")==null?Optional.empty():Optional.of(new ContentDigest(GraphJson.string(fields.get("parent")))),artifacts));
    }
    private Path pointer(ContentDigest registration)throws IOException{Path path=root.resolve(registration.value().substring(7)+".json");StreamingContentStore.safe(path);return path;}
}
