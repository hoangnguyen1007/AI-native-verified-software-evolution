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
        var codec=new GraphSnapshotCodec();
        Path pending=null;
        try {
            pending=Files.createTempFile(directory,"graph-pending-",".tmp");
            try(var channel=FileChannel.open(pending,StandardOpenOption.WRITE,NOFOLLOW_LINKS)) {
                var stream=new LimitedBytes(java.nio.channels.Channels.newOutputStream(channel),maxBytes,cancelled);
                // Typed graph/evidence invariants were checked by construction. Exact structural,
                // character and UTF-8 byte bounds are checked without decoding a duplicate graph.
                var writer=new LimitedCharacters(new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8)),jsonLimits.maxCharacters());
                codec.encodeTo(graph,writer,jsonLimits);writer.flush();
                channel.force(true);
            }
            if(cancelled.getAsBoolean())throw new GraphStoreException(CANCELLED);
            checkDirectory();path(graph.identity());
            // Never replace an already published snapshot, including from another store instance.
            if(Files.exists(destination,NOFOLLOW_LINKS)){verifyExisting(graph);return;}
            // Atomic create-if-absent publication: unlike ATOMIC_MOVE, cannot replace a racing destination.
            try{Files.createLink(destination,pending);}
            catch(FileAlreadyExistsException raced){verifyExisting(graph);}
        } catch(GraphStoreException failure){throw failure;}
        catch(GraphJson.LimitExceededException|StorageLimit limit){var failure=new GraphStoreException(LIMIT_EXCEEDED);failure.initCause(limit);throw failure;}
        catch(IOException failure){throw new GraphStoreException(IO_FAILURE);}
        finally {if(pending!=null)try{Files.deleteIfExists(pending);}catch(IOException ignored){/* Not published; existing snapshots remain intact. */}}
    }
    private void verifyExisting(KnowledgeGraph graph) {
        var existing=load(graph.identity()).orElseThrow(()->new GraphStoreException(CORRUPT_SNAPSHOT));
        if(!existing.canonicalForm().equals(graph.canonicalForm()))throw new GraphStoreException(CORRUPT_SNAPSHOT);
    }
    private static class StorageLimit extends IOException {}
    private static final class ByteLimit extends StorageLimit {}
    private static final class CharacterLimit extends StorageLimit {}
    private static final class LimitedBytes extends FilterOutputStream {
        private long remaining;private final BooleanSupplier cancelled;
        LimitedBytes(OutputStream out,long maximum,BooleanSupplier cancelled){super(out);remaining=maximum;this.cancelled=cancelled;}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {if(cancelled.getAsBoolean())throw new GraphStoreException(CANCELLED);if((remaining-=length)<0)throw new ByteLimit();out.write(bytes,offset,length);}
        @Override public void write(int value)throws IOException {if(cancelled.getAsBoolean())throw new GraphStoreException(CANCELLED);if(--remaining<0)throw new ByteLimit();out.write(value);}
    }
    private static final class LimitedCharacters extends FilterWriter {
        private long remaining;LimitedCharacters(Writer out,long maximum){super(out);remaining=maximum;}
        private void spend(int n)throws StorageLimit{if((remaining-=n)<0)throw new CharacterLimit();}
        @Override public void write(int value)throws IOException {spend(1);out.write(value);}
        @Override public void write(char[] value,int offset,int length)throws IOException {spend(length);out.write(value,offset,length);}
        @Override public void write(String value,int offset,int length)throws IOException {spend(length);out.write(value,offset,length);}
    }
    @Override public Optional<KnowledgeGraph> load(ContentDigest identity) {
        var source=path(identity);
        if(!Files.exists(source,NOFOLLOW_LINKS))return Optional.empty();
        var graph=readPath(source);if(!graph.identity().equals(identity))throw new GraphStoreException(CORRUPT_SNAPSHOT);return Optional.of(graph);
    }
    public static KnowledgeGraph readSnapshot(Path file,int maximum,GraphJson.Limits limits){
        Path source=file.toAbsolutePath().normalize();return new FileGraphStore(source.getParent(),maximum,limits).readPath(source);
    }
    private KnowledgeGraph readPath(Path source){
        if(!Files.isRegularFile(source,NOFOLLOW_LINKS))throw new GraphStoreException(UNSAFE_PATH);
        try(var stream=new LimitedInput(Files.newInputStream(source,StandardOpenOption.READ,NOFOLLOW_LINKS),maxBytes)) {
            if(Files.size(source)>maxBytes)throw new GraphStoreException(LIMIT_EXCEEDED);
            try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))){return new GraphSnapshotCodec().decode(reader,jsonLimits);}
        } catch(GraphStoreException failure){throw failure;}
        catch(GraphJson.LimitExceededException limit){throw new GraphStoreException(LIMIT_EXCEEDED);}
        catch(CharacterCodingException|IllegalArgumentException corrupt){throw new GraphStoreException(CORRUPT_SNAPSHOT);}
        catch(IOException failure){throw new GraphStoreException(IO_FAILURE);}
    }
    private static final class LimitedInput extends FilterInputStream {
        private long remaining;LimitedInput(InputStream in,long maximum){super(in);remaining=maximum;}
        @Override public int read()throws IOException {int value=in.read();if(value>=0&&--remaining<0)throw new GraphStoreException(LIMIT_EXCEEDED);return value;}
        @Override public int read(byte[] value,int offset,int length)throws IOException {int count=in.read(value,offset,(int)Math.min(length,remaining+1));if(count>0&&(remaining-=count)<0)throw new GraphStoreException(LIMIT_EXCEEDED);return count;}
    }
}
