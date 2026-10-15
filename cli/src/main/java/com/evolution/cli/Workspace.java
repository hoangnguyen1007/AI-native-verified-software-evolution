package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Explicit, bounded local session storage. Snapshots and policy inputs are immutable. */
final class Workspace {
    static final int GRAPH_BYTES=1_000_000_000;
    static final GraphJson.Limits GRAPH_LIMITS=new GraphJson.Limits(GRAPH_BYTES,128,16_000_000);
    final Path root;
    Workspace(Path directory) { root=directory.toAbsolutePath().normalize().resolve(".evo"); }
    static Path safe(Path path) throws IOException {
        Path result=path.toAbsolutePath().normalize();
        for(Path p=result;p!=null;p=p.getParent()) if(Files.isSymbolicLink(p)) throw new IOException("UNSAFE_PATH");
        return result;
    }
    static byte[] read(Path path,int limit) throws IOException {
        path=safe(path);
        if(!Files.isRegularFile(path,NOFOLLOW_LINKS)) throw new IOException("INPUT_UNAVAILABLE");
        try(var in=Files.newInputStream(path,StandardOpenOption.READ,NOFOLLOW_LINKS)) {
            byte[] bytes=in.readNBytes(limit+1);
            if(bytes.length>limit) throw new IOException("INPUT_LIMIT");
            return bytes;
        }
    }
    static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static Object json(Path file,int limit) throws IOException {
        return GraphJson.read(utf8(read(file,limit)),new GraphJson.Limits(limit,128,2_000_000));
    }
    static KnowledgeGraph graph(Path file) throws IOException {
        return FileGraphStore.readSnapshot(safe(file),GRAPH_BYTES,GRAPH_LIMITS);
    }
    private void create() throws IOException {
        safe(root); Files.createDirectories(root); safe(root);
    }
    Map<String,Object> selection() throws IOException {
        return GraphJson.object(json(root.resolve("selection.json"),16_384));
    }
    KnowledgeGraph selectedGraph() throws IOException {
        var id=new ContentDigest(GraphJson.string(selection().get("graph")));
        return new FileGraphStore(root,GRAPH_BYTES,GRAPH_LIMITS).load(id).orElseThrow(()->new IOException("GRAPH_UNAVAILABLE"));
    }
    void select(KnowledgeGraph graph,Optional<String> context) throws IOException {
        if(context.isPresent()&&graph.contexts().stream().noneMatch(c->c.identity().equals(context.orElseThrow()))) throw new IllegalArgumentException("Foreign context");
        publish(graph);
        var data=new TreeMap<String,Object>();data.put("graph",graph.identity());data.put("context",context);
        atomicSelection(CanonicalJson.write(data).getBytes(StandardCharsets.UTF_8));
    }
    void publish(KnowledgeGraph graph)throws IOException {create();new FileGraphStore(root,GRAPH_BYTES,GRAPH_LIMITS).save(graph);}
    void rememberContext(KnowledgeGraph verified,String context)throws IOException {
        if(verified.contexts().stream().noneMatch(c->c.identity().equals(context)))throw new IllegalArgumentException("Foreign context");
        if(!verified.identity().value().equals(selection().get("graph")))throw new IOException("SELECTION_CHANGED");
        atomicSelection(CanonicalJson.write(Map.of("graph",verified.identity(),"context",context)).getBytes(StandardCharsets.UTF_8));
    }
    private void atomicSelection(byte[] bytes) throws IOException {
        var target=safe(root.resolve("selection.json"));
        if(Files.exists(target,NOFOLLOW_LINKS)) {
            var old=selection();
            if(!old.keySet().equals(Set.of("graph","context"))) throw new IOException("INVALID_SELECTION");
            new ContentDigest(GraphJson.string(old.get("graph")));
        }
        Path pending=Files.createTempFile(root,"selection-",".tmp");
        try {
            try(var channel=FileChannel.open(pending,StandardOpenOption.WRITE,NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            safe(target);
            Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }
    void policy(byte[] bytes) throws IOException {
        create();var id=ContentDigest.sha256(bytes);var file=root.resolve(id.value().substring(7)+".policy.yaml");
        if(!Files.exists(safe(file),NOFOLLOW_LINKS)) writeNew(file,bytes);
        else if(!Arrays.equals(bytes,read(file,262_144))) throw new IOException("POLICY_CONFLICT");
        // Policy selection is content addressed; no source path or secret is echoed.
        Path pointer=root.resolve("policy.txt");
        if(Files.exists(safe(pointer),NOFOLLOW_LINKS)) new ContentDigest(utf8(read(pointer,128)).strip());
        Path pending=Files.createTempFile(root,"policy-",".tmp");
        try {Files.writeString(pending,id.value(),StandardCharsets.UTF_8);Files.move(pending,pointer,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(pending);}
    }
    byte[] policy() throws IOException {
        var id=new ContentDigest(utf8(read(root.resolve("policy.txt"),128)).strip());
        byte[] bytes=read(root.resolve(id.value().substring(7)+".policy.yaml"),262_144);
        if(!ContentDigest.sha256(bytes).equals(id)) throw new IOException("POLICY_CONFLICT");
        return bytes;
    }
    void references(com.evolution.analysis.query.ArchitectureQuery.Request request,List<String> ids)throws IOException {
        if(ids.size()>50)throw new IllegalArgumentException("Reference limit");
        create();Path target=safe(root.resolve("references.json"));
        byte[] bytes=CanonicalJson.write(Map.of("graph",request.graph(),"context",request.context(),"scope",request.scope(),"ids",ids)).getBytes(StandardCharsets.UTF_8);
        Path pending=Files.createTempFile(root,"references-",".tmp");
        try{Files.write(pending,bytes);safe(target);Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(pending);}
    }
    Optional<String> reference(com.evolution.analysis.query.ArchitectureQuery.Request request,int index)throws IOException {
        if(!Files.exists(root.resolve("references.json"),NOFOLLOW_LINKS))return Optional.empty();
        var data=GraphJson.object(json(root.resolve("references.json"),32_768));
        if(!request.graph().value().equals(data.get("graph"))||!request.context().equals(data.get("context"))||!request.scope().name().equals(data.get("scope")))return Optional.empty();
        var ids=GraphJson.array(data.get("ids"));
        return index>=1&&index<=ids.size()?Optional.of(GraphJson.string(ids.get(index-1))):Optional.empty();
    }
    static void writeNew(Path target,byte[] bytes) throws IOException {
        target=safe(target);
        if(!Files.isDirectory(target.getParent(),NOFOLLOW_LINKS)) throw new IOException("OUTPUT_PARENT_UNAVAILABLE");
        // CREATE_NEW refuses symlinks and never overwrites user output.
        try(var channel=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW_LINKS)) {
            var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
        }
    }
}
