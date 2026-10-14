package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static com.evolution.analysis.graph.GraphStoreException.Reason.*;

/** Bounded canonical snapshot I/O in an explicitly supplied existing directory. No database needed. */
public final class FileGraphStore implements GraphStore {
    private final Path directory;
    private final int maxBytes;
    private final GraphJson.Limits jsonLimits;
    public FileGraphStore(Path directory) {this(directory,64_000_000,GraphJson.Limits.defaults());}
    public FileGraphStore(Path directory,int maxBytes,GraphJson.Limits jsonLimits) {
        Objects.requireNonNull(directory);Objects.requireNonNull(jsonLimits);
        if(maxBytes<1 || maxBytes==Integer.MAX_VALUE)throw new IllegalArgumentException("Bounded positive graph store byte limit required");
        this.directory=directory.toAbsolutePath().normalize();this.maxBytes=maxBytes;this.jsonLimits=jsonLimits;
        checkDirectory();
    }
    private void checkDirectory() {
        for(Path part=directory;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new GraphStoreException(UNSAFE_PATH);
        if(!Files.isDirectory(directory,NOFOLLOW_LINKS))throw new GraphStoreException(UNSAFE_PATH);
    }
    private Path path(ContentDigest identity) {
        Objects.requireNonNull(identity);checkDirectory();
        Path path=directory.resolve(identity.value().substring(7)+".graph.json").normalize();
        if(!path.getParent().equals(directory))throw new GraphStoreException(UNSAFE_PATH);
        if(Files.isSymbolicLink(path))throw new GraphStoreException(UNSAFE_PATH);
        return path;
    }
    @Override public synchronized void save(KnowledgeGraph graph,BooleanSupplier cancelled) {
        Objects.requireNonNull(graph);Objects.requireNonNull(cancelled);
        var destination=path(graph.identity());
        if(cancelled.getAsBoolean())throw new GraphStoreException(CANCELLED);
        var codec=new GraphSnapshotCodec();String encoded=codec.encode(graph);
        if(encoded.length()>jsonLimits.maxCharacters())throw new GraphStoreException(LIMIT_EXCEEDED);
        byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>maxBytes)throw new GraphStoreException(LIMIT_EXCEEDED);
        // Refuse to publish snapshots this store cannot decode with its configured structural bounds.
        try{codec.decode(encoded,jsonLimits);}
        catch(GraphJson.LimitExceededException limit){throw new GraphStoreException(LIMIT_EXCEEDED);}
        catch(IllegalArgumentException invalid){throw new GraphStoreException(CORRUPT_SNAPSHOT);}
        if(Files.exists(destination,NOFOLLOW_LINKS)) {verifyExisting(graph,encoded);return;}
        Path pending=null;
        try {
            pending=Files.createTempFile(directory,"graph-pending-",".tmp");
            try(var channel=FileChannel.open(pending,StandardOpenOption.WRITE,NOFOLLOW_LINKS)) {
                var buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) {if(cancelled.getAsBoolean())throw new GraphStoreException(CANCELLED);channel.write(buffer);}
                channel.force(true);
            }
            if(cancelled.getAsBoolean())throw new GraphStoreException(CANCELLED);
            checkDirectory();path(graph.identity());
            // Never replace an already published snapshot, including from another store instance.
            if(Files.exists(destination,NOFOLLOW_LINKS)){verifyExisting(graph,encoded);return;}
            // Atomic create-if-absent publication: unlike ATOMIC_MOVE, cannot replace a racing destination.
            try{Files.createLink(destination,pending);}
            catch(FileAlreadyExistsException raced){verifyExisting(graph,encoded);}
        } catch(GraphStoreException failure){throw failure;}
        catch(IOException failure){throw new GraphStoreException(IO_FAILURE);}
        finally {if(pending!=null)try{Files.deleteIfExists(pending);}catch(IOException ignored){/* Not published; existing snapshots remain intact. */}}
    }
    private void verifyExisting(KnowledgeGraph graph,String encoded) {
        var existing=load(graph.identity()).orElseThrow(()->new GraphStoreException(CORRUPT_SNAPSHOT));
        if(!new GraphSnapshotCodec().encode(existing).equals(encoded))throw new GraphStoreException(CORRUPT_SNAPSHOT);
    }
    @Override public Optional<KnowledgeGraph> load(ContentDigest identity) {
        var source=path(identity);
        if(!Files.exists(source,NOFOLLOW_LINKS))return Optional.empty();
        if(!Files.isRegularFile(source,NOFOLLOW_LINKS))throw new GraphStoreException(UNSAFE_PATH);
        try(var stream=Files.newInputStream(source,StandardOpenOption.READ,NOFOLLOW_LINKS)) {
            if(Files.size(source)>maxBytes)throw new GraphStoreException(LIMIT_EXCEEDED);
            byte[] bytes=stream.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes)throw new GraphStoreException(LIMIT_EXCEEDED);
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var graph=new GraphSnapshotCodec().decode(json,jsonLimits);
            if(!graph.identity().equals(identity))throw new GraphStoreException(CORRUPT_SNAPSHOT);
            return Optional.of(graph);
        } catch(GraphStoreException failure){throw failure;}
        catch(GraphJson.LimitExceededException limit){throw new GraphStoreException(LIMIT_EXCEEDED);}
        catch(CharacterCodingException|IllegalArgumentException corrupt){throw new GraphStoreException(CORRUPT_SNAPSHOT);}
        catch(IOException failure){throw new GraphStoreException(IO_FAILURE);}
    }
}
