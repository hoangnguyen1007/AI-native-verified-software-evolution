package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Fenced immutable producer publications. Cold load replays captured results;
 * live withdrawals produce a new view without rewriting historical bytes. */
public final class BridgePublicationStore {
    private final Path root;private final RepositoryContentStore content;private final SemanticTables tables;private final Supplier<ContentDigest> policy;private final Supplier<Set<ContentDigest>> revoked;
    // One bounded process-local view. The transitive byte read set stays on disk;
    // cache hits rehash every captured/read/emitted blob and recheck live fences.
    private record Warm(BridgeQueryService.View view,List<SemanticTables.Table> readSets){}
    private record Evaluated(ArchitectureBridges.Result result,SemanticTables.Table readSet){}
    private volatile Warm warm;
    public BridgePublicationStore(Path root,RepositoryContentStore content,SemanticTables tables,Supplier<ContentDigest> policy,Supplier<Set<ContentDigest>> revoked)throws IOException{this.root=StreamingContentStore.directory(root);this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.policy=Objects.requireNonNull(policy);this.revoked=Objects.requireNonNull(revoked);}
    public BridgeQueryService queries(){return new BridgeQueryService(new BridgeQueryService.Repository(){
        public Optional<BridgeQueryService.View> load(ContentDigest scope,BooleanSupplier cancelled)throws IOException{return BridgePublicationStore.this.load(scope,cancelled);}
        public BridgeQueryService.ScopeKeys scopes(Optional<ContentDigest> after,int size,BooleanSupplier cancelled)throws IOException{
            var selected=new TreeSet<ContentDigest>();try(var entries=Files.newDirectoryStream(root)){for(var path:entries){if(cancelled.getAsBoolean())throw new IOException("BRIDGE_QUERY_CANCELLED");String name=path.getFileName().toString();if(!name.matches("[a-f0-9]{64}\\.json"))continue;var scope=new ContentDigest("sha256:"+name.substring(0,64));if(after.isPresent()&&scope.compareTo(after.orElseThrow())<=0)continue;selected.add(scope);if(selected.size()>size+1)selected.pollLast();}}
            boolean more=selected.size()>size;if(more)selected.pollLast();return new BridgeQueryService.ScopeKeys(List.copyOf(selected),more?Optional.of(selected.last()):Optional.empty());
        }
        public Optional<BridgeQueryService.View> navigation(ContentDigest scope,BooleanSupplier cancelled)throws IOException{return navigationView(scope,cancelled);}
        public void checkNavigation(BridgeQueryService.View view,BooleanSupplier cancelled)throws IOException{
            BridgePublicationStore.this.check(view.input(),cancelled);
            if(!head(view.input().scope()).map(ContentHandle::digest).equals(Optional.of(view.publication().digest())))throw new IOException("BRIDGE_QUERY_STALE_VIEW");
        }
        public void check(BridgeQueryService.View view,BooleanSupplier cancelled)throws IOException{
            BridgePublicationStore.this.check(view.input(),cancelled);
            if(!view.result().revocations().equals(ArchitectureBridges.revocationIdentity(Set.copyOf(revoked.get())))||!head(view.input().scope()).map(ContentHandle::digest).equals(Optional.of(view.publication().digest())))throw new IOException("BRIDGE_QUERY_STALE_VIEW");
        }
    },tables);}
    private Optional<BridgeQueryService.View> navigationView(ContentDigest scope,BooleanSupplier cancelled)throws IOException{
        try{var head=head(scope);if(head.isEmpty())return Optional.empty();var record=read(head.orElseThrow());if(!record.keySet().equals(Set.of("schema","evaluator","input","result","revoked","revision","parent"))||!"architecture-bridge-publication:m8.4-v1".equals(record.get("schema"))||!CanonicalJson.write(ArchitectureBridges.PROVIDER).equals(CanonicalJson.write(record.get("evaluator"))))throw new IOException("BRIDGE_PUBLICATION_SCHEMA_UNAVAILABLE");var input=ArchitectureBridges.input(record.get("input"));if(!input.scope().equals(scope))throw new IOException("BRIDGE_PUBLICATION_SCOPE");check(input,cancelled);long revision=number(record,"revision");if(revision<1)throw new IOException("BRIDGE_PUBLICATION_REVISION");return Optional.of(new BridgeQueryService.View(head.orElseThrow(),revision,input,ArchitectureBridges.result(record.get("result"))));}
        catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("BRIDGE_PUBLICATION_INVALID",invalid);}
    }
    public BridgeQueryService.View commit(ArchitectureBridges.Input input,Optional<ContentDigest> expected,BooleanSupplier cancelled)throws IOException{
        check(input,cancelled);Path lockPath=root.resolve(input.scope().value().substring(7)+".lock");StreamingContentStore.safe(lockPath);
        try(var channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("BRIDGE_PUBLICATION_BUSY",busy);}if(lock==null)throw new IOException("BRIDGE_PUBLICATION_BUSY");
            try(lock){var previous=head(input.scope());if(!expected.equals(previous.map(ContentHandle::digest)))throw new IOException("BRIDGE_PUBLICATION_FENCE_MISMATCH");
                long revision=previous.isEmpty()?1:Math.addExact(number(read(previous.orElseThrow()),"revision"),1);var withdrawn=Set.copyOf(revoked.get());var result=evaluate(input,withdrawn,cancelled);
                var metadata=new TreeMap<String,Object>();metadata.put("schema","architecture-bridge-publication:m8.4-v1");metadata.put("evaluator",ArchitectureBridges.PROVIDER);metadata.put("input",input);metadata.put("result",result);metadata.put("revoked",withdrawn.stream().sorted().toList());metadata.put("revision",revision);metadata.put("parent",expected.orElse(null));
                var document=put(metadata,cancelled);byte[] pointer=CanonicalJson.write(Map.of("document",document)).getBytes(StandardCharsets.UTF_8);Path temporary=Files.createTempFile(root,"bridge-",".pending");
                try{try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(pointer);while(buffer.hasRemaining())output.write(buffer);output.force(true);}check(input,cancelled);if(!withdrawn.equals(Set.copyOf(revoked.get())))throw new IOException("BRIDGE_PUBLICATION_REVOCATION_CHANGED");Files.move(temporary,path(input.scope()),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
                return new BridgeQueryService.View(document,revision,input,result);
            }
        }
    }
    public Optional<BridgeQueryService.View> load(ContentDigest scope,BooleanSupplier cancelled)throws IOException{
        try{var head=head(scope);if(head.isEmpty())return Optional.empty();var document=head.orElseThrow();var record=read(document);
            if(!record.keySet().equals(Set.of("schema","evaluator","input","result","revoked","revision","parent"))||!"architecture-bridge-publication:m8.4-v1".equals(record.get("schema"))||!CanonicalJson.write(ArchitectureBridges.PROVIDER).equals(CanonicalJson.write(record.get("evaluator"))))throw new IOException("BRIDGE_PUBLICATION_SCHEMA_UNAVAILABLE");
            var input=ArchitectureBridges.input(record.get("input"));if(!input.scope().equals(scope))throw new IOException("BRIDGE_PUBLICATION_SCOPE");check(input,cancelled);var captured=new TreeSet<ContentDigest>();for(Object value:GraphJson.array(record.get("revoked")))if(!captured.add(new ContentDigest(GraphJson.string(value))))throw new IOException("BRIDGE_PUBLICATION_DUPLICATE_REVOCATION");
            var current=Set.copyOf(revoked.get());var cached=warm;
            if(cached!=null&&cached.view().publication().equals(document)&&cached.view().result().revocations().equals(ArchitectureBridges.revocationIdentity(current))){
                for(var readSet:cached.readSets()){if(!content.verify(readSet.content()))throw new IOException("BRIDGE_READ_SET_UNAVAILABLE");try(var rows=tables.open(readSet,cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){check(input,cancelled);if(!content.verify(handle(row.fields().get("content"))))throw new IOException("BRIDGE_CAPTURED_BYTES_CHANGED");}}}
                check(input,cancelled);if(!current.equals(Set.copyOf(revoked.get())))throw new IOException("BRIDGE_PUBLICATION_REVOCATION_CHANGED");return Optional.of(cached.view());
            }
            var saved=ArchitectureBridges.result(record.get("result"));var verified=evaluateTracked(input,captured,cancelled);var replay=verified.result();if(!saved.equals(replay))throw new IOException("BRIDGE_PUBLICATION_REPLAY_MISMATCH");
            var revised=current.equals(captured)?verified:evaluateTracked(input,current,cancelled);var result=revised.result();check(input,cancelled);if(!current.equals(Set.copyOf(revoked.get())))throw new IOException("BRIDGE_PUBLICATION_REVOCATION_CHANGED");long revision=number(record,"revision");if(revision<1)throw new IOException("BRIDGE_PUBLICATION_REVISION");
            var view=new BridgeQueryService.View(document,revision,input,result);warm=new Warm(view,current.equals(captured)?List.of(verified.readSet()):List.of(verified.readSet(),revised.readSet()));return Optional.of(view);
        }catch(IllegalArgumentException|ClassCastException|ArithmeticException|NullPointerException invalid){throw new IOException("BRIDGE_PUBLICATION_INVALID",invalid);}
    }
    public Optional<ContentHandle> head(ContentDigest scope)throws IOException{
        Path pointer=path(scope);if(!Files.exists(pointer,LinkOption.NOFOLLOW_LINKS))return Optional.empty();if(!Files.isRegularFile(pointer,LinkOption.NOFOLLOW_LINKS)||Files.size(pointer)>4096)throw new IOException("BRIDGE_PUBLICATION_POINTER_INVALID");
        try{var value=GraphJson.object(GraphJson.read(Files.readString(pointer,StandardCharsets.UTF_8),new GraphJson.Limits(4096,8,128)));if(!value.keySet().equals(Set.of("document")))throw new IOException("BRIDGE_PUBLICATION_POINTER_INVALID");return Optional.of(handle(value.get("document")));}catch(IllegalArgumentException|ClassCastException|ArithmeticException invalid){throw new IOException("BRIDGE_PUBLICATION_POINTER_INVALID",invalid);}
    }
    private ArchitectureBridges.Result evaluate(ArchitectureBridges.Input input,Set<ContentDigest> withdrawn,BooleanSupplier cancelled)throws IOException{try(var index=SqliteSemanticCensusIndex.temporary(root.resolve("work"),65536)){return new ArchitectureBridges(content,tables,64_000_000,1024).reconcile(input,index,withdrawn,policy,cancelled);}}
    private Evaluated evaluateTracked(ArchitectureBridges.Input input,Set<ContentDigest> withdrawn,BooleanSupplier cancelled)throws IOException{
        try(var index=SqliteSemanticCensusIndex.temporary(root.resolve("work"),65536)){
            RepositoryContentStore tracked=new RepositoryContentStore(){
                private void remember(ContentHandle handle)throws IOException{if(index.get("verified.bytes",handle.digest().value()).isEmpty())index.put("verified.bytes",handle.digest().value(),new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,handle.digest().value(),input.identity(),Map.of("content",handle)));}
                public ContentHandle put(InputStream stream,BooleanSupplier stop)throws IOException{var handle=content.put(stream,stop);remember(handle);return handle;}
                public InputStream open(ContentHandle handle)throws IOException{var stream=content.open(handle);try{remember(handle);return stream;}catch(IOException failure){stream.close();throw failure;}}
                public InputStream range(ContentHandle handle,long offset,long bytes)throws IOException{var stream=content.range(handle,offset,bytes);try{remember(handle);return stream;}catch(IOException failure){stream.close();throw failure;}}
                public boolean verify(ContentHandle handle)throws IOException{boolean valid=content.verify(handle);if(valid)remember(handle);return valid;}
            };
            var result=new ArchitectureBridges(tracked,tables.withContent(tracked),64_000_000,1024).reconcile(input,index,withdrawn,policy,cancelled);
            try(var rows=index.rows("verified.bytes",false)){var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row next;boolean loaded;public boolean hasNext(){if(!loaded)try{next=rows.next();loaded=true;}catch(IOException failure){throw new UncheckedIOException(failure);}return next!=null;}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};
                try{return new Evaluated(result,tables.write(SemanticTables.Kind.DOCUMENT,iterator,cancelled));}catch(UncheckedIOException failure){throw failure.getCause();}
            }
        }
    }
    private void check(ArchitectureBridges.Input input,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("BRIDGE_PUBLICATION_CANCELLED");if(!input.policy().equals(policy.get()))throw new IOException("BRIDGE_PUBLICATION_POLICY_REVOKED");}
    private Path path(ContentDigest scope)throws IOException{Path path=root.resolve(scope.value().substring(7)+".json");StreamingContentStore.safe(path);return path;}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("BRIDGE_PUBLICATION_CONTENT_UNAVAILABLE");try(var stream=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(stream,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("BRIDGE_PUBLICATION_NONCANONICAL");return value;}}
    private static long number(Map<String,Object> map,String key){return ((java.math.BigDecimal)map.get(key)).longValueExact();}
    private static ContentHandle handle(Object value){var map=GraphJson.object(value);if(!map.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("BRIDGE_HANDLE_FIELDS");return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),number(map,"size"));}
}
