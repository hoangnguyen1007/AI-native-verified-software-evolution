package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.concurrent.BoundedTasks;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Immutable, authenticated CAS page trees. SQLite is an external-sort scratch index only;
 * queries follow content hashes from a verified root, never trust mutable SQL rows.
 * Cold verification streams the original v1 graph digest without constructing KnowledgeGraph. */
public final class PartitionedGraphStore implements PartitionedGraphReader {
    private static final String FORMAT="graph.partition-store:m8.4-v1", LEAF="graph.page-leaf:m8.4-v1", BRANCH="graph.page-branch:m8.4-v1";
    private static final int FANOUT=128,PAGE_ROWS=256,PAGE_CHARACTERS=1_048_576;
    private final Path directory;
    private final StreamingContentStore content;
    private final int rowBytes;
    private final int pageWorkers;
    private final Map<ContentDigest,Root> verified=Collections.synchronizedMap(new LinkedHashMap<>(16,.75f,true){
        protected boolean removeEldestEntry(Map.Entry<ContentDigest,Root> entry){return size()>16;}
    });
    private record Tree(ContentHandle content,String first,String last,long rows) {}
    private record Entry(String key,Object row) {}
    private record EncodedEntry(String key,String payload) {}
    private record Root(ContentHandle document,ContentDigest graph,String snapshot,Map<String,Tree> trees) {
        Root{new com.evolution.analysis.contract.identity.SnapshotIdentity(snapshot);}
    }
    public record Publication(ContentDigest graph,com.evolution.analysis.contract.identity.SnapshotIdentity snapshot,
            List<KnowledgeGraph.Context> contexts,long nodes,long edges,long evidence,Map<String,Long> edgeKinds) {
        public Publication {contexts=List.copyOf(contexts);edgeKinds=Map.copyOf(edgeKinds);}
    }
    @FunctionalInterface private interface RowConsumer {void accept(Entry entry)throws IOException;}
    /** Own only these generated flat scratch files. Try-with-resources retains
     * the original failure and records cleanup failures as suppressed causes. */
    private static final class TemporaryFiles implements AutoCloseable {
        private final List<Path> paths=new ArrayList<>();
        TemporaryFiles(Path... created){Collections.addAll(paths,created);}
        Path add(Path path){paths.add(path);return path;}
        public void close()throws IOException {IOException failed=null;for(var path:paths)try{Files.deleteIfExists(path);}catch(IOException problem){if(failed==null)failed=problem;else if(failed!=problem)failed.addSuppressed(problem);}if(failed!=null)throw failed;}
    }
    private static IOException unwrap(UncheckedIOException failure){var cause=failure.getCause();for(var suppressed:failure.getSuppressed())if(suppressed!=cause)cause.addSuppressed(suppressed);return cause;}

    public PartitionedGraphStore(Path directory)throws IOException{this(directory,64_000_000);}
    public PartitionedGraphStore(Path directory,int rowBytes)throws IOException{
        this(directory,rowBytes,BoundedTasks.automaticWorkers());
    }
    public PartitionedGraphStore(Path directory,int rowBytes,int pageWorkers)throws IOException{
        this(directory,rowBytes,pageWorkers,channel->channel.force(true));
    }
    PartitionedGraphStore(Path directory,int rowBytes,int pageWorkers,StreamingContentStore.Force force)throws IOException{
        if(rowBytes<1024||rowBytes==Integer.MAX_VALUE)throw new IllegalArgumentException("GRAPH_PAGE_WORKER_BUDGET");
        if(pageWorkers<1||pageWorkers>8)throw new IllegalArgumentException("GRAPH_PAGE_WORKERS_REQUIRED");
        this.directory=StreamingContentStore.directory(directory);this.rowBytes=rowBytes;
        this.pageWorkers=pageWorkers;content=new StreamingContentStore(this.directory.resolve("content"),0,force);
    }
    /** Request-local cancellation; no mutable cancellation state is shared across callers. */
    public PartitionedGraphReader reader(BooleanSupplier cancelled){
        Objects.requireNonNull(cancelled);
        return new PartitionedGraphReader(){
            public Page<Partition> partitions(ContentDigest graph,int size,Optional<String> cursor)throws IOException{return partitionPage(graph,size,cursor,cancelled);}
            public Page<KnowledgeGraph.Node> nodes(Partition partition,int size,Optional<String> cursor)throws IOException{return nodePage(partition,size,cursor,cancelled);}
            public Page<KnowledgeGraph.Edge> adjacency(Partition partition,String node,boolean incoming,int size,Optional<String> cursor)throws IOException{return adjacencyPage(partition,node,incoming,size,cursor,cancelled);}
        };
    }
    public Page<Partition> partitions(ContentDigest graph,int size,Optional<String> cursor)throws IOException{return partitionPage(graph,size,cursor,()->false);}
    public Page<KnowledgeGraph.Node> nodes(Partition partition,int size,Optional<String> cursor)throws IOException{return nodePage(partition,size,cursor,()->false);}
    public Page<KnowledgeGraph.Edge> adjacency(Partition partition,String node,boolean incoming,int size,Optional<String> cursor)throws IOException{return adjacencyPage(partition,node,incoming,size,cursor,()->false);}
    public Optional<Partition> partition(ContentDigest graph,String context)throws IOException{
        var root=root(graph,()->false);var found=new ArrayList<Entry>();
        visit(root.trees().get("partitions"),context,context+"\0",null,found::add,()->false,0);
        return found.isEmpty()?Optional.empty():Optional.of(partition(found.getFirst().row()));
    }
    /** Read one authenticated provider record without loading the legacy snapshot. */
    public Optional<GraphEvidence> evidence(ContentDigest graph,ContentDigest identity)throws IOException {
        BooleanSupplier cancelled=()->Thread.currentThread().isInterrupted();var root=root(graph,cancelled);var found=new ArrayList<Entry>();
        visit(root.trees().get("evidence"),identity.value(),identity.value()+"\0",null,found::add,cancelled,0);
        if(found.size()>1)throw new IOException("GRAPH_PARTITION_DUPLICATE_EVIDENCE");
        return found.isEmpty()?Optional.empty():Optional.of(GraphSnapshotCodec.evidence(found.getFirst().row()));
    }

    /** Build directly into a bounded disk workspace. No repository-wide graph objects or
     * adjacency maps are constructed. Both the original snapshot and authenticated pages
     * retain the exact v1 digest and undergo the same reference audit as imported snapshots. */
    public Publication build(CanonicalGraphInput input,Path snapshots,BooleanSupplier cancelled)throws IOException {
        return build(input,snapshots,cancelled,workspace->{});
    }
    @FunctionalInterface public interface Contribution {void project(GraphProjectionStore workspace)throws IOException;}
    /** Neutral inventory/native roots need not fabricate a legacy Java RepositorySnapshot. */
    public Publication build(com.evolution.analysis.contract.identity.SnapshotIdentity snapshot,Path snapshots,BooleanSupplier cancelled,Contribution contribution)throws IOException {
        return assemble(snapshot,snapshots,cancelled,contribution);
    }
    public Publication build(CanonicalGraphInput input,Path snapshots,BooleanSupplier cancelled,Contribution contribution)throws IOException {
        return build(input,snapshots,cancelled,List.of(),contribution);
    }
    public Publication build(CanonicalGraphInput input,Path snapshots,BooleanSupplier cancelled,Collection<com.evolution.analysis.evidence.EvidenceAcquisitionLedger> ledgers,Contribution contribution)throws IOException {
        return assemble(input.repository().snapshot().identity(),snapshots,cancelled,workspace->{new CanonicalGraphBuilder().project(input,workspace,ledgers);contribution.project(workspace);});
    }
    /** Compatibility entry for an already validated graph; additive providers still spill to disk. */
    public Publication build(KnowledgeGraph graph,Path snapshots,BooleanSupplier cancelled,Contribution contribution)throws IOException {
        return assemble(graph.snapshot(),snapshots,cancelled,workspace->{
            var contexts=workspace.records("contexts",GraphSnapshotCodec::context);for(var row:graph.contexts())contexts.put(row.identity(),row);
            var nodes=workspace.records("nodes",GraphSnapshotCodec::node);for(var row:graph.nodes())nodes.put(key(row.context(),row.identity()),row);
            var edges=workspace.records("edges",GraphSnapshotCodec::edge);for(var row:graph.edges())edges.put(key(row.context(),row.identity()),row);
            var evidence=workspace.records("evidence",GraphSnapshotCodec::evidence);for(var row:graph.evidence())evidence.put(row.identity().value(),row);
            contribution.project(workspace);
        });
    }
    private Publication assemble(com.evolution.analysis.contract.identity.SnapshotIdentity snapshot,Path snapshots,BooleanSupplier cancelled,Contribution contribution)throws IOException {
        check(cancelled);snapshots=StreamingContentStore.directory(snapshots);
        Path scratch=Files.createTempFile(directory,"graph-projection-",".sqlite");
        try(var cleanup=new TemporaryFiles(scratch)) {
        Path pending=cleanup.add(Files.createTempFile(snapshots,"graph-snapshot-",".part"));
        try(Connection database=DriverManager.getConnection("jdbc:sqlite:"+scratch)) {
            try(var sql=database.createStatement()) {
                sql.execute("PRAGMA journal_mode=DELETE");sql.execute("PRAGMA temp_store=FILE");sql.execute("PRAGMA cache_size=-2048");
                sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            database.setAutoCommit(false);
            var workspace=new SqliteGraphProjection(database,rowBytes,cancelled);
            contribution.project(workspace);
            var contexts=new ArrayList<KnowledgeGraph.Context>(workspace.records("contexts",GraphSnapshotCodec::context).values());
            var kinds=new TreeMap<String,Long>();
            try(var insert=database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)")) {
                for(var edge:workspace.records("edges",GraphSnapshotCodec::edge).values()) {
                    kinds.merge(edge.kind(),1L,Long::sum);String id=key(edge.context(),edge.identity());
                    insert(insert,"outgoing",key(edge.context(),edge.source(),edge.identity()),id,cancelled);
                    for(String target:edge.target().identities())insert(insert,"incoming",key(edge.context(),target,edge.identity()),id,cancelled);
                }
            }
            database.commit();
            var digest=StreamingContentStore.sha256();ContentDigest identity;
            var frame=StreamingContentStore.sha256();
            try(var channel=java.nio.channels.FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                var destination=new DigestOutputStream(java.nio.channels.Channels.newOutputStream(channel),frame);
                var writer=new BufferedWriter(new OutputStreamWriter(destination,StandardCharsets.UTF_8));
                writer.write("{\"format\":");CanonicalJson.writeTo(GraphSnapshotCodec.FORMAT,writer);writer.write(",\"graph\":");
                writer.flush();
                var graphWriter=new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(destination,digest),StandardCharsets.UTF_8));
                writeGraph(database,snapshot.value(),graphWriter,cancelled);graphWriter.flush();
                identity=new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));
                writer.write(",\"identity\":");CanonicalJson.writeTo(identity,writer);writer.write("}");writer.flush();channel.force(true);
            }
            // Audits endpoints, evidence, contexts and both adjacency indexes before publication.
            publish(database,identity,snapshot.value(),cancelled,true,Optional.of(new ContentDigest("sha256:"+HexFormat.of().formatHex(frame.digest()))));
            Path target=snapshots.resolve(identity.value().substring(7)+".graph.json");StreamingContentStore.safe(target);check(cancelled);
            try{Files.createLink(target,pending);}catch(FileAlreadyExistsException existing){
                if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)!=Files.size(pending)||!hash(target).equals(hash(pending)))throw new IOException("GRAPH_SNAPSHOT_CONFLICT");
            }
            return new Publication(identity,snapshot,contexts,total(database,"nodes"),total(database,"edges"),total(database,"evidence"),kinds);
        }
        }catch(UncheckedIOException failure){throw unwrap(failure);}
        catch(SQLException failure){throw new IOException("GRAPH_PROJECTION_STORAGE_UNAVAILABLE",failure);}
    }
    private static ContentDigest hash(Path file)throws IOException {
        var digest=StreamingContentStore.sha256();try(var in=new DigestInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),digest)){in.transferTo(OutputStream.nullOutputStream());}
        return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));
    }
    private static long total(Connection database,String space)throws SQLException {
        try(var query=database.prepareStatement("SELECT COUNT(*) FROM rows WHERE space=?")){query.setString(1,space);try(var rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}}
    }
    private void writeGraph(Connection database,String snapshot,Writer writer,BooleanSupplier cancelled)throws SQLException,IOException {
        writer.write("{");boolean first=true;
        for(String space:List.of("contexts","edges","evidence","nodes")) {
            if(!first)writer.write(",");first=false;CanonicalJson.writeTo(space,writer);writer.write(":[");boolean member=false;
            try(var query=database.prepareStatement("SELECT payload FROM rows WHERE space=? ORDER BY k")) {
                query.setString(1,space);try(var rows=query.executeQuery()){while(rows.next()){check(cancelled);if(member)writer.write(",");member=true;writer.write(rows.getString(1));}}
            }writer.write("]");
        }
        writer.write(",\"schema\":");CanonicalJson.writeTo(KnowledgeGraph.SCHEMA,writer);writer.write(",\"snapshot\":");CanonicalJson.writeTo(snapshot,writer);writer.write("}");
    }

    public void save(KnowledgeGraph graph,BooleanSupplier cancelled)throws IOException{
        Objects.requireNonNull(graph);check(cancelled);var pointer=pointer(graph.identity());
        if(Files.exists(pointer,LinkOption.NOFOLLOW_LINKS)){root(graph.identity(),cancelled);return;}
        Path scratch=Files.createTempFile(directory,"graph-index-",".sqlite");
        try(var cleanup=new TemporaryFiles(scratch);Connection database=DriverManager.getConnection("jdbc:sqlite:"+scratch)){
            try(var sql=database.createStatement()){
                sql.execute("PRAGMA journal_mode=DELETE");sql.execute("PRAGMA temp_store=FILE");sql.execute("PRAGMA cache_size=-2048");
                sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            database.setAutoCommit(false);
            try(var insert=database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)")){
                for(var value:graph.contexts())insert(insert,"contexts",value.identity(),value,cancelled);
                for(var value:graph.nodes())insert(insert,"nodes",key(value.context(),value.identity()),value,cancelled);
                for(var value:graph.edges()){
                    String id=key(value.context(),value.identity());insert(insert,"edges",id,value,cancelled);
                    insert(insert,"outgoing",key(value.context(),value.source(),value.identity()),id,cancelled);
                    for(String target:value.target().identities())insert(insert,"incoming",key(value.context(),target,value.identity()),id,cancelled);
                }
                for(var value:graph.evidence())insert(insert,"evidence",value.identity().value(),value,cancelled);
            }
            database.commit();
            publish(database,graph.identity(),graph.snapshot().value(),cancelled,false,Optional.empty());
        }catch(UncheckedIOException failure){throw unwrap(failure);}
        catch(SQLException failure){throw new IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE",failure);}
    }
    /** Disk migration of the canonical v1 frame. Neither JSON document nor KnowledgeGraph is
     * retained; the declared identity, exact canonical bytes and structural references must agree. */
    public void importSnapshot(Path source,ContentDigest expected,BooleanSupplier cancelled)throws IOException{
        check(cancelled);StreamingContentStore.safe(source);long bytes=Files.size(source);
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||bytes<1)throw new IOException("GRAPH_PARTITION_INPUT_UNAVAILABLE");
        if(Files.exists(pointer(expected),LinkOption.NOFOLLOW_LINKS)){root(expected,cancelled);return;}
        Path scratch=Files.createTempFile(directory,"graph-import-",".sqlite");
        try(var cleanup=new TemporaryFiles(scratch);Connection database=DriverManager.getConnection("jdbc:sqlite:"+scratch)){
            try(var sql=database.createStatement()){
                sql.execute("PRAGMA journal_mode=DELETE");sql.execute("PRAGMA temp_store=FILE");sql.execute("PRAGMA cache_size=-2048");
                sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");
            }
            database.setAutoCommit(false);var header=new TreeMap<String,Object>();var body=new TreeMap<String,Object>();var members=new HashSet<String>();
            var digest=MessageDigest.getInstance("SHA-256");
            try(var insert=database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)");
                var input=new DigestInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS),digest);
                var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))){
                GraphJson.streamObject(reader,new GraphJson.StreamLimits(bytes,128,bytes,Math.toIntExact(Math.min(bytes,rowBytes))),(name,member)->{
                    check(cancelled);
                    if(name.equals("format")||name.equals("identity"))header.put(name,member.read());
                    else if(name.equals("graph"))member.object((space,values)->{
                        members.add(space);if(space.equals("schema")||space.equals("snapshot")){body.put(space,values.read());return;}
                        if(!Set.of("contexts","nodes","edges","evidence").contains(space))throw new IOException("GRAPH_PARTITION_INPUT_FIELDS");
                        values.array(raw->{
                            check(cancelled);Object value=switch(space){case "contexts"->GraphSnapshotCodec.context(raw);case "nodes"->GraphSnapshotCodec.node(raw);case "edges"->GraphSnapshotCodec.edge(raw);default->GraphSnapshotCodec.evidence(raw);};
                            String key=switch(value){case KnowledgeGraph.Context c->c.identity();case KnowledgeGraph.Node n->key(n.context(),n.identity());case KnowledgeGraph.Edge e->key(e.context(),e.identity());case GraphEvidence e->e.identity().value();default->throw new AssertionError();};
                            try{
                                insert(insert,space,key,value,cancelled);
                                if(value instanceof KnowledgeGraph.Edge edge){insert(insert,"outgoing",key(edge.context(),edge.source(),edge.identity()),key,cancelled);for(String target:edge.target().identities())insert(insert,"incoming",key(edge.context(),target,edge.identity()),key,cancelled);}
                            }catch(SQLException failure){throw new IOException("GRAPH_PARTITION_DUPLICATE_OR_INVALID_ROW",failure);}
                        });
                    });else throw new IOException("GRAPH_PARTITION_INPUT_FIELDS");
                });
            }
            if(!header.keySet().equals(Set.of("format","identity"))||!GraphSnapshotCodec.FORMAT.equals(header.get("format"))||!expected.value().equals(header.get("identity"))
                    ||!members.equals(Set.of("schema","snapshot","contexts","nodes","edges","evidence"))||!KnowledgeGraph.SCHEMA.equals(body.get("schema")))throw new IOException("GRAPH_PARTITION_INPUT_SCOPE");
            database.commit();publish(database,expected,text(body.get("snapshot")),cancelled,true,Optional.of(new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()))));
        }catch(UncheckedIOException failure){throw unwrap(failure);}
        catch(SQLException failure){throw new IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE",failure);}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private void publish(Connection database,ContentDigest graph,String snapshot,BooleanSupplier cancelled,boolean audit,Optional<ContentDigest> frame)throws SQLException,IOException{
        try(var workers=new BoundedTasks(pageWorkers,pageWorkers,"evo-graph-page",cancelled)) {
            var trees=new TreeMap<String,Tree>();
            for(String space:List.of("contexts","nodes","edges","outgoing","incoming","evidence"))trees.put(space,build(database,space,cancelled,workers));
            try(var query=database.prepareStatement("SELECT payload FROM rows WHERE space='contexts' ORDER BY k");var contexts=query.executeQuery();var insert=database.prepareStatement("INSERT INTO rows(space,k,payload) VALUES(?,?,?)")){
                while(contexts.next()){
                    var context=GraphSnapshotCodec.context(parse(contexts.getString(1)));long nodes=count(database,"nodes",context.identity()),edges=count(database,"edges",context.identity());
                    var segment=put(Map.of("schema","graph.partition-segment:m8.4-v1","graph",graph,"context",context.identity(),
                            "nodes",nodes,"edges",edges,"indexes",Map.of("nodes",trees.get("nodes"),"edges",trees.get("edges"),"incoming",trees.get("incoming"),"outgoing",trees.get("outgoing"))),cancelled);
                    insert(insert,"partitions",context.identity(),new Partition(SCHEMA,graph,context.identity(),segment,nodes,edges),cancelled);
                }
            }
            database.commit();trees.put("partitions",build(database,"partitions",cancelled,workers));
            var document=put(Map.of("format",FORMAT,"graph",graph,"snapshot",snapshot,"trees",trees),cancelled);
            var root=new Root(document,graph,snapshot,Map.copyOf(trees));
            if(!graph.equals(canonicalDigest(root,cancelled,audit,frame)))throw new IOException("GRAPH_PARTITION_DIGEST_MISMATCH");
            check(cancelled);byte[] bytes=CanonicalJson.write(Map.of("format",FORMAT,"graph",graph,"document",document)).getBytes(StandardCharsets.UTF_8);
            Path pending=Files.createTempFile(directory,"graph-root-",".part");
            try(var cleanup=new TemporaryFiles(pending)){
                try(var channel=java.nio.channels.FileChannel.open(pending,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
                Path pointer=pointer(graph);
                StreamingContentStore.safe(pointer);check(cancelled);
                try{Files.createLink(pointer,pending);}catch(FileAlreadyExistsException raced){root(graph,cancelled);return;}
                verified.put(graph,root);
            }
        }
    }
    private long count(Connection database,String space,String context)throws SQLException{
        try(var query=database.prepareStatement("SELECT COUNT(*) FROM rows WHERE space=? AND k>=? AND k<?")){
            String prefix=context+"|";query.setString(1,space);query.setString(2,prefix);query.setString(3,upper(prefix));try(var result=query.executeQuery()){return result.next()?result.getLong(1):0;}
        }
    }
    private void insert(PreparedStatement statement,String space,String key,Object value,BooleanSupplier cancelled)throws SQLException,IOException{
        check(cancelled);String encoded=CanonicalJson.write(value);
        if(encoded.getBytes(StandardCharsets.UTF_8).length>rowBytes)throw new IOException("GRAPH_PARTITION_ROW_WORKER_REQUIRED");
        statement.setString(1,space);statement.setString(2,key);statement.setString(3,encoded);statement.executeUpdate();
    }
    private Tree build(Connection database,String space,BooleanSupplier cancelled,BoundedTasks workers)throws SQLException,IOException{
        var builder=new TreeBuilder(cancelled);var batch=new ArrayList<EncodedEntry>();long characters=0;
        var pending=new ArrayDeque<BoundedTasks.Task<Tree>>();
        try(var query=database.prepareStatement("SELECT k,payload FROM rows WHERE space=? ORDER BY k")){
            query.setString(1,space);
            try(var rows=query.executeQuery()){
                while(rows.next()){
                    check(cancelled);String encoded=rows.getString(2);
                    if(!batch.isEmpty()&&(batch.size()==PAGE_ROWS||characters+encoded.length()>PAGE_CHARACTERS)){enqueueLeaf(batch,characters,builder,pending,workers,cancelled);batch.clear();characters=0;}
                    batch.add(new EncodedEntry(rows.getString(1),encoded));characters+=encoded.length();
                }
            }
        }
        if(!batch.isEmpty())enqueueLeaf(batch,characters,builder,pending,workers,cancelled);
        while(!pending.isEmpty())builder.add(pending.removeFirst().await(),0);
        return builder.finish();
    }
    private void enqueueLeaf(List<EncodedEntry> batch,long characters,TreeBuilder builder,Deque<BoundedTasks.Task<Tree>> pending,BoundedTasks workers,BooleanSupplier cancelled)throws IOException {
        var captured=List.copyOf(batch);
        if(characters>PAGE_CHARACTERS) {
            // One admitted large row proceeds alone, without multiplying worker heap.
            while(!pending.isEmpty())builder.add(pending.removeFirst().await(),0);
            builder.add(encodedLeaf(captured,cancelled),0);return;
        }
        if(pending.size()>=pageWorkers)builder.add(pending.removeFirst().await(),0);
        pending.addLast(workers.submit(()->encodedLeaf(captured,cancelled)));
    }
    private Tree encodedLeaf(List<EncodedEntry> encoded,BooleanSupplier cancelled)throws IOException {
        var rows=new ArrayList<Entry>(encoded.size());for(var row:encoded){check(cancelled);rows.add(new Entry(row.key(),parse(row.payload())));}
        return leaf(rows,cancelled);
    }
    private Tree leaf(List<Entry> entries,BooleanSupplier cancelled)throws IOException{
        var handle=put(Map.of("schema",LEAF,"rows",entries),cancelled);
        return new Tree(handle,entries.isEmpty()?"":entries.getFirst().key(),entries.isEmpty()?"":entries.getLast().key(),entries.size());
    }
    private Tree branch(List<Tree> children,BooleanSupplier cancelled)throws IOException{
        long rows=0;for(var child:children)rows=Math.addExact(rows,child.rows());
        return new Tree(put(Map.of("schema",BRANCH,"children",children),cancelled),children.getFirst().first(),children.getLast().last(),rows);
    }
    private final class TreeBuilder{
        final List<List<Tree>> levels=new ArrayList<>();final BooleanSupplier cancelled;
        TreeBuilder(BooleanSupplier cancelled){this.cancelled=cancelled;}
        void add(Tree tree,int level)throws IOException{
            while(levels.size()<=level)levels.add(new ArrayList<>());
            var values=levels.get(level);values.add(tree);
            if(values.size()==FANOUT){var parent=branch(values,cancelled);values.clear();add(parent,level+1);}
        }
        Tree finish()throws IOException{
            Tree carry=null;
            for(int level=0;level<levels.size();level++){
                var values=levels.get(level);if(carry!=null){values.add(carry);carry=null;}if(values.isEmpty())continue;
                boolean higher=false;for(int i=level+1;i<levels.size();i++)higher|=!levels.get(i).isEmpty();
                if(!higher)return values.size()==1?values.getFirst():branch(values,cancelled);
                carry=branch(values,cancelled);
            }
            return carry==null?leaf(List.of(),cancelled):carry;
        }
    }
    private Root root(ContentDigest graph,BooleanSupplier cancelled)throws IOException{
        try{return loadRoot(graph,cancelled);}catch(IllegalArgumentException|ArithmeticException corrupt){throw new IOException("GRAPH_PARTITION_CORRUPT",corrupt);}
    }
    private Root loadRoot(ContentDigest graph,BooleanSupplier cancelled)throws IOException{
        check(cancelled);Path pointer=pointer(graph);Map<String,Object> reference;
        try(var input=Files.newInputStream(pointer,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(16385);if(bytes.length>16384)throw new IOException("GRAPH_PARTITION_ROOT_INVALID");reference=object(parse(utf8(bytes)));}
        fields(reference,"format","graph","document");if(!FORMAT.equals(reference.get("format"))||!graph.value().equals(reference.get("graph")))throw new IOException("GRAPH_PARTITION_ROOT_SCOPE");
        var document=handle(reference.get("document"));var cached=verified.get(graph);if(cached!=null&&cached.document().equals(document))return cached;
        var value=object(read(document,cancelled));fields(value,"format","graph","snapshot","trees");
        if(!FORMAT.equals(value.get("format"))||!graph.value().equals(value.get("graph")))throw new IOException("GRAPH_PARTITION_ROOT_SCOPE");
        var trees=new TreeMap<String,Tree>();object(value.get("trees")).forEach((name,tree)->trees.put(name,tree(tree)));
        if(!trees.keySet().equals(Set.of("contexts","nodes","edges","evidence","outgoing","incoming","partitions")))throw new IOException("GRAPH_PARTITION_INDEX_MISSING");
        var root=new Root(document,graph,text(value.get("snapshot")),Map.copyOf(trees));
        if(!graph.equals(canonicalDigest(root,cancelled,true)))throw new IOException("GRAPH_PARTITION_DIGEST_MISMATCH");
        verified.put(graph,root);return root;
    }
    private ContentDigest canonicalDigest(Root root,BooleanSupplier cancelled,boolean auditIndexes)throws IOException{
        return canonicalDigest(root,cancelled,auditIndexes,Optional.empty());
    }
    private ContentDigest canonicalDigest(Root root,BooleanSupplier cancelled,boolean auditIndexes,Optional<ContentDigest> expectedFrame)throws IOException{
        try(var audit=auditIndexes?new Audit(root,cancelled):null){
            var digest=MessageDigest.getInstance("SHA-256");
            var frame=expectedFrame.isPresent()?MessageDigest.getInstance("SHA-256"):null;
            if(frame!=null)frame.update(("{\"format\":"+CanonicalJson.write(GraphSnapshotCodec.FORMAT)+",\"graph\":").getBytes(StandardCharsets.UTF_8));
            var output=new OutputStream(){public void write(int value){digest.update((byte)value);if(frame!=null)frame.update((byte)value);}public void write(byte[] values,int offset,int length){digest.update(values,offset,length);if(frame!=null)frame.update(values,offset,length);}};
            try(var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8))){
                writer.write("{");boolean first=true;
                for(String name:List.of("contexts","edges","evidence","nodes")){
                    if(!first)writer.write(",");first=false;CanonicalJson.writeTo(name,writer);writer.write(":");writer.write("[");boolean[] member={false};
                    visit(root.trees().get(name),"","\uffff",null,entry->{
                        check(cancelled);Object row=switch(name){case "contexts"->GraphSnapshotCodec.context(entry.row());case "nodes"->GraphSnapshotCodec.node(entry.row());case "edges"->GraphSnapshotCodec.edge(entry.row());default->GraphSnapshotCodec.evidence(entry.row());};
                        String expectedKey=switch(name){case "contexts"->((KnowledgeGraph.Context)row).identity();case "nodes"->{var n=(KnowledgeGraph.Node)row;yield key(n.context(),n.identity());}case "edges"->{var e=(KnowledgeGraph.Edge)row;yield key(e.context(),e.identity());}default->((GraphEvidence)row).identity().value();};
                        if(!entry.key().equals(expectedKey))throw new IOException("GRAPH_PARTITION_CANONICAL_KEY");
                        if(audit!=null)audit.accept(row);
                        if(member[0])writer.write(",");member[0]=true;CanonicalJson.writeTo(row,writer);
                    },cancelled,0);writer.write("]");
                }
                writer.write(",\"schema\":");CanonicalJson.writeTo(KnowledgeGraph.SCHEMA,writer);writer.write(",\"snapshot\":");CanonicalJson.writeTo(root.snapshot(),writer);writer.write("}");
            }
            if(audit!=null)audit.finish();
            if(frame!=null){frame.update((",\"identity\":"+CanonicalJson.write(root.graph())+"}").getBytes(StandardCharsets.UTF_8));if(!expectedFrame.orElseThrow().equals(new ContentDigest("sha256:"+HexFormat.of().formatHex(frame.digest()))))throw new IOException("GRAPH_PARTITION_NONCANONICAL_INPUT");}
            return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    /** Verify derived lookup completeness and canonical graph references on disk.
     * A valid v1 digest alone does not authenticate a separately supplied adjacency index. */
    private final class Audit implements AutoCloseable{
        final Root root;final BooleanSupplier cancelled;final Path file;final Connection database;final PreparedStatement insert;
        Audit(Root root,BooleanSupplier cancelled)throws IOException{
            this.root=root;this.cancelled=cancelled;file=Files.createTempFile(directory,"graph-audit-",".sqlite");
            Connection opened=null;PreparedStatement prepared=null;
            try{
                opened=DriverManager.getConnection("jdbc:sqlite:"+file);
                try(var sql=opened.createStatement()){sql.execute("PRAGMA journal_mode=DELETE");sql.execute("PRAGMA temp_store=FILE");sql.execute("PRAGMA cache_size=-2048");sql.execute("CREATE TABLE audit(space TEXT,k TEXT,payload TEXT,PRIMARY KEY(space,k)) WITHOUT ROWID");}
                opened.setAutoCommit(false);prepared=opened.prepareStatement("INSERT OR IGNORE INTO audit(space,k,payload) VALUES(?,?,?)");
            }catch(SQLException failure){if(prepared!=null)try{prepared.close();}catch(SQLException close){failure.addSuppressed(close);}if(opened!=null)try{opened.close();}catch(SQLException close){failure.addSuppressed(close);}try{Files.deleteIfExists(file);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw new IOException("GRAPH_PARTITION_AUDIT_UNAVAILABLE",failure);}
            database=opened;insert=prepared;
        }
        void add(String space,String key,String value)throws IOException{
            check(cancelled);try{insert.setString(1,space);insert.setString(2,key);insert.setString(3,value);insert.executeUpdate();}
            catch(SQLException failure){throw new IOException("GRAPH_PARTITION_AUDIT_WRITE",failure);}
        }
        void accept(Object value)throws IOException{
            if(value instanceof KnowledgeGraph.Context context){
                if(!context.snapshot().value().equals(root.snapshot()))throw new IOException("GRAPH_PARTITION_FOREIGN_SNAPSHOT");
                add("contexts",context.identity(),"");add("evidence-refs",context.evidence().value(),"");
            }else if(value instanceof KnowledgeGraph.Node node){
                add("nodes",key(node.context(),node.identity()),"");add("context-refs",node.context(),"");
                for(var evidence:node.evidence())add("evidence-refs",evidence.value(),"");
            }else if(value instanceof KnowledgeGraph.Edge edge){
                String id=key(edge.context(),edge.identity());add("edges",id,"");add("context-refs",edge.context(),"");add("node-refs",key(edge.context(),edge.source()),"");
                add("expected-outgoing",key(edge.context(),edge.source(),edge.identity()),id);
                for(String target:edge.target().identities()){add("node-refs",key(edge.context(),target),"");add("expected-incoming",key(edge.context(),target,edge.identity()),id);}
                if(edge.region().isPresent())add("node-refs",key(edge.context(),edge.region().orElseThrow()),"");
                for(var evidence:edge.evidence())add("evidence-refs",evidence.value(),"");
            }else if(value instanceof GraphEvidence evidence)add("evidence",evidence.identity().value(),"");
        }
        void finish()throws IOException{
            try{
                for(String[] pair:new String[][]{{"contexts","context-refs"},{"nodes","node-refs"},{"evidence","evidence-refs"}})
                    try(var query=database.prepareStatement("SELECT 1 FROM audit r LEFT JOIN audit a ON a.space=? AND a.k=r.k WHERE r.space=? AND a.k IS NULL LIMIT 1")){
                        query.setString(1,pair[0]);query.setString(2,pair[1]);try(var result=query.executeQuery()){if(result.next())throw new IOException("GRAPH_PARTITION_DANGLING_REFERENCE");}
                    }
                for(String space:List.of("incoming","outgoing")){
                    try(var query=database.prepareStatement("SELECT k,payload FROM audit WHERE space=? ORDER BY k")){
                        query.setString(1,"expected-"+space);
                        try(var expected=query.executeQuery()){
                            visit(root.trees().get(space),"","\uffff",null,entry->{
                                try{if(!expected.next()||!expected.getString(1).equals(entry.key())||!expected.getString(2).equals(text(entry.row())))throw new IOException("GRAPH_PARTITION_ADJACENCY_MISMATCH");}
                                catch(SQLException failure){throw new IOException("GRAPH_PARTITION_AUDIT_READ",failure);}
                            },cancelled,0);
                            if(expected.next())throw new IOException("GRAPH_PARTITION_ADJACENCY_OMITTED");
                        }
                    }
                }
                try(var query=database.prepareStatement("SELECT k FROM audit WHERE space='contexts' ORDER BY k");var contexts=query.executeQuery()){
                    visit(root.trees().get("partitions"),"","\uffff",null,entry->{
                        try{
                            if(!contexts.next()||!contexts.getString(1).equals(entry.key()))throw new IOException("GRAPH_PARTITION_CONTEXT_CENSUS");
                            var partition=partition(entry.row());if(!partition.context().equals(entry.key())||!partition.graph().equals(root.graph()))throw new IOException("GRAPH_PARTITION_CONTEXT_SCOPE");
                            for(String space:List.of("nodes","edges"))try(var count=database.prepareStatement("SELECT COUNT(*) FROM audit WHERE space=? AND k>=? AND k<?")){
                                String prefix=partition.context()+"|";count.setString(1,space);count.setString(2,prefix);count.setString(3,upper(prefix));
                                try(var result=count.executeQuery()){long actual=result.next()?result.getLong(1):0;if(actual!=(space.equals("nodes")?partition.nodes():partition.edges()))throw new IOException("GRAPH_PARTITION_CONTEXT_COUNT");}
                            }
                            var segment=object(read(partition.segment(),cancelled));
                            if(!partition.context().equals(segment.get("context"))||!partition.graph().value().equals(segment.get("graph")))throw new IOException("GRAPH_PARTITION_SEGMENT_SCOPE");
                            var indexes=object(segment.get("indexes"));for(String space:List.of("nodes","edges","incoming","outgoing"))if(!root.trees().get(space).equals(tree(indexes.get(space))))throw new IOException("GRAPH_PARTITION_INDEX_SCOPE");
                        }catch(SQLException failure){throw new IOException("GRAPH_PARTITION_AUDIT_READ",failure);}
                    },cancelled,0);
                    if(contexts.next())throw new IOException("GRAPH_PARTITION_CONTEXT_OMITTED");
                }
            }catch(SQLException failure){throw new IOException("GRAPH_PARTITION_AUDIT_READ",failure);}
        }
        public void close()throws IOException{
            SQLException failed=null;try{insert.close();}catch(SQLException failure){failed=failure;}
            try{database.close();}catch(SQLException failure){if(failed==null)failed=failure;else failed.addSuppressed(failure);}
            IOException problem=failed==null?null:new IOException("GRAPH_PARTITION_AUDIT_CLOSE",failed);
            try{Files.deleteIfExists(file);}catch(IOException cleanup){if(problem==null)problem=cleanup;else problem.addSuppressed(cleanup);}
            if(problem!=null)throw problem;
        }
    }
    private Page<Partition> partitionPage(ContentDigest graph,int size,Optional<String> cursor,BooleanSupplier cancelled)throws IOException{
        var root=root(graph,cancelled);String query=query(graph,"partitions",size,root.document());
        var page=page(root.trees().get("partitions"),"","\uffff",query,size,cursor,cancelled);
        var values=new ArrayList<Partition>();for(var entry:page.items())values.add(partition(entry.row()));
        return new Page<>(values,page.continuation());
    }
    private Page<KnowledgeGraph.Node> nodePage(Partition partition,int size,Optional<String> cursor,BooleanSupplier cancelled)throws IOException{
        var root=partitionRoot(partition,cancelled);String prefix=partition.context()+"|";
        var page=page(root.trees().get("nodes"),prefix,upper(prefix),query(partition.graph(),"nodes",size,partition),size,cursor,cancelled);
        var values=new ArrayList<KnowledgeGraph.Node>();for(var entry:page.items()){var node=GraphSnapshotCodec.node(entry.row());if(!node.context().equals(partition.context()))throw new IOException("GRAPH_PARTITION_NODE_SCOPE");values.add(node);}
        return new Page<>(values,page.continuation());
    }
    private Page<KnowledgeGraph.Edge> adjacencyPage(Partition partition,String node,boolean incoming,int size,Optional<String> cursor,BooleanSupplier cancelled)throws IOException{
        if(node==null||!node.matches("(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("GRAPH_PARTITION_NODE_ID");
        var root=partitionRoot(partition,cancelled);String space=incoming?"incoming":"outgoing",prefix=key(partition.context(),node)+"|";
        var page=page(root.trees().get(space),prefix,upper(prefix),query(partition.graph(),space,size,List.of(partition,node)),size,cursor,cancelled);
        var values=new ArrayList<KnowledgeGraph.Edge>();for(var entry:page.items()){
            String id=text(entry.row());var exact=new ArrayList<Entry>();visit(root.trees().get("edges"),id,id+"\0",null,exact::add,cancelled,0);
            if(exact.size()!=1)throw new IOException("GRAPH_PARTITION_EDGE_MISSING");var edge=GraphSnapshotCodec.edge(exact.getFirst().row());
            if(!edge.context().equals(partition.context())||!(incoming?edge.target().identities().contains(node):edge.source().equals(node)))throw new IOException("GRAPH_PARTITION_EDGE_SCOPE");values.add(edge);
        }
        return new Page<>(values,page.continuation());
    }
    private Root partitionRoot(Partition partition,BooleanSupplier cancelled)throws IOException{
        var root=root(partition.graph(),cancelled);var found=new ArrayList<Entry>();
        visit(root.trees().get("partitions"),partition.context(),partition.context()+"\0",null,found::add,cancelled,0);
        if(found.size()!=1||!partition.equals(partition(found.getFirst().row())))throw new IOException("GRAPH_PARTITION_REFERENCE_MISMATCH");
        var segment=object(read(partition.segment(),cancelled));fields(segment,"schema","graph","context","nodes","edges","indexes");
        if(!"graph.partition-segment:m8.4-v1".equals(segment.get("schema"))||!partition.graph().value().equals(segment.get("graph"))||!partition.context().equals(segment.get("context"))
                ||number(segment.get("nodes"))!=partition.nodes()||number(segment.get("edges"))!=partition.edges())throw new IOException("GRAPH_PARTITION_SEGMENT_SCOPE");
        var indexes=object(segment.get("indexes"));
        for(String name:List.of("nodes","edges","incoming","outgoing"))if(!root.trees().get(name).equals(tree(indexes.get(name))))throw new IOException("GRAPH_PARTITION_INDEX_SCOPE");
        return root;
    }
    private Page<Entry> page(Tree tree,String lower,String upper,String query,int size,Optional<String> cursor,BooleanSupplier cancelled)throws IOException{
        if(size<1||size>512)throw new IllegalArgumentException("GRAPH_PARTITION_PAGE_SIZE");
        String after=null;
        if(cursor.isPresent()){
            String token=cursor.orElseThrow();if(token.length()>2048)throw new IllegalArgumentException("GRAPH_PARTITION_CURSOR");
            var value=object(parse(utf8(Base64.getUrlDecoder().decode(token))));
            if(!value.keySet().equals(Set.of("query","after"))||!query.equals(value.get("query")))throw new IllegalArgumentException("GRAPH_PARTITION_CURSOR_SCOPE");
            after=text(value.get("after"));if(after.compareTo(lower)<0||after.compareTo(upper)>=0)throw new IllegalArgumentException("GRAPH_PARTITION_CURSOR_RANGE");
        }
        var rows=new ArrayList<Entry>();String boundary=after;
        try{visit(tree,lower,upper,boundary,entry->{rows.add(entry);if(rows.size()>size)throw new PageFull();},cancelled,0);}catch(PageFull complete){}
        boolean more=rows.size()>size;if(more)rows.removeLast();
        var continuation=more?Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.write(Map.of("query",query,"after",rows.getLast().key())).getBytes(StandardCharsets.UTF_8))):Optional.<String>empty();
        return new Page<>(rows,continuation);
    }
    private static final class PageFull extends IOException {}
    private void visit(Tree tree,String lower,String upper,String after,RowConsumer consumer,BooleanSupplier cancelled,int depth)throws IOException{
        check(cancelled);if(tree.rows()==0||tree.last().compareTo(lower)<0||tree.first().compareTo(upper)>=0||after!=null&&tree.last().compareTo(after)<=0)return;
        if(depth>128)throw new IOException("GRAPH_PARTITION_TREE_DEPTH");
        var value=object(read(tree.content(),cancelled));
        if(LEAF.equals(value.get("schema"))){
            fields(value,"schema","rows");var rows=GraphJson.array(value.get("rows"));if(rows.size()!=tree.rows())throw new IOException("GRAPH_PARTITION_LEAF_COUNT");
            String previous=null;
            for(Object raw:rows){var row=object(raw);fields(row,"key","row");String key=text(row.get("key"));if(previous!=null&&previous.compareTo(key)>=0)throw new IOException("GRAPH_PARTITION_KEY_ORDER");previous=key;
                if(key.compareTo(lower)>=0&&key.compareTo(upper)<0&&(after==null||key.compareTo(after)>0))consumer.accept(new Entry(key,row.get("row")));}
            if(!rows.isEmpty()&&(!tree.first().equals(object(rows.getFirst()).get("key"))||!tree.last().equals(previous)))throw new IOException("GRAPH_PARTITION_KEY_RANGE");
        }else if(BRANCH.equals(value.get("schema"))){
            fields(value,"schema","children");var children=GraphJson.array(value.get("children"));if(children.isEmpty()||children.size()>FANOUT)throw new IOException("GRAPH_PARTITION_BRANCH_SIZE");
            long count=0;String previous=null;var parsed=new ArrayList<Tree>();
            for(Object child:children){var next=tree(child);if(previous!=null&&previous.compareTo(next.first())>=0)throw new IOException("GRAPH_PARTITION_CHILD_ORDER");count=Math.addExact(count,next.rows());previous=next.last();parsed.add(next);}
            if(count!=tree.rows()||!tree.first().equals(parsed.getFirst().first())||!tree.last().equals(parsed.getLast().last()))throw new IOException("GRAPH_PARTITION_BRANCH_RANGE");
            for(var child:parsed)visit(child,lower,upper,after,consumer,cancelled,depth+1);
        }else throw new IOException("GRAPH_PARTITION_TREE_FORMAT");
    }
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{
        return content.write(output->{var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));CanonicalJson.writeTo(value,writer);writer.flush();},cancelled);
    }
    private Object read(ContentHandle handle,BooleanSupplier cancelled)throws IOException{
        check(cancelled);if(handle.size()>rowBytes)throw new IOException("GRAPH_PARTITION_PAGE_WORKER_REQUIRED");
        try(var input=content.open(handle)){
            byte[] bytes=input.readNBytes(Math.addExact(rowBytes,1));
            if(bytes.length!=handle.size()||!ContentDigest.sha256(bytes).equals(handle.digest()))throw new IOException("GRAPH_PARTITION_CONTENT_CORRUPT");
            return parse(utf8(bytes));
        }
    }
    private Object parse(String encoded){return GraphJson.read(encoded,new GraphJson.Limits(rowBytes,128,rowBytes));}
    private Path pointer(ContentDigest graph)throws IOException{var path=directory.resolve(graph.value().substring(7)+".partitions.json");StreamingContentStore.safe(path);return path;}
    private static Map<String,Object> object(Object value){return GraphJson.object(value);}
    private static String text(Object value){return GraphJson.string(value);}
    private static long number(Object value){return ((java.math.BigDecimal)value).longValueExact();}
    private static ContentHandle handle(Object value){var row=object(value);if(!row.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("GRAPH_CONTENT_HANDLE");return new ContentHandle(new ContentDigest(text(row.get("digest"))),number(row.get("size")));}
    private static Tree tree(Object value){var row=object(value);if(!row.keySet().equals(Set.of("content","first","last","rows")))throw new IllegalArgumentException("GRAPH_TREE_FIELDS");var result=new Tree(handle(row.get("content")),text(row.get("first")),text(row.get("last")),number(row.get("rows")));if(result.rows()<0||result.rows()>0&&result.first().compareTo(result.last())>0)throw new IllegalArgumentException("GRAPH_TREE_RANGE");return result;}
    private static Partition partition(Object value){var row=object(value);if(!row.keySet().equals(Set.of("schema","graph","context","segment","nodes","edges")))throw new IllegalArgumentException("GRAPH_PARTITION_FIELDS");return new Partition(text(row.get("schema")),new ContentDigest(text(row.get("graph"))),text(row.get("context")),handle(row.get("segment")),number(row.get("nodes")),number(row.get("edges")));}
    private static void fields(Map<String,Object> value,String... names)throws IOException{if(!value.keySet().equals(Set.of(names)))throw new IOException("GRAPH_PARTITION_RECORD_FIELDS");}
    private static String utf8(byte[] bytes)throws CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    private static String key(String... values){return String.join("|",values);}
    private static String upper(String prefix){return prefix.substring(0,prefix.length()-1)+"}";}
    private static String query(ContentDigest graph,String operation,int size,Object scope){return CanonicalJson.sha256(List.of(FORMAT,graph,operation,size,scope)).value();}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
}
