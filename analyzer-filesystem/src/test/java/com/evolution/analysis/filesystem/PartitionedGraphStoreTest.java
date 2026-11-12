package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PartitionedGraphStoreTest {
    @Test void oversizedCanonicalRowProceedsAloneAndRetainsAuthenticatedEvidence()throws Exception {
        var base=FileGraphStoreTest.graph();var large=GraphEvidence.capture("fixture.large-row",Map.of("text","x".repeat(1_060_000)));
        var evidence=new ArrayList<>(base.evidence());evidence.add(large);
        var graph=new KnowledgeGraph(base.snapshot(),base.contexts(),base.nodes(),base.edges(),evidence);
        var largeWritten=new java.util.concurrent.atomic.AtomicBoolean();
        var store=new PartitionedGraphStore(directory,64_000_000,2,channel->{if(channel.size()>1_048_576){largeWritten.set(true);assertFalse(Thread.currentThread().getName().startsWith("evo-graph-page"),"An oversized row must be isolated from page workers");}channel.force(true);});
        store.save(graph,()->false);assertTrue(largeWritten.get());
        assertEquals(large,new PartitionedGraphStore(directory).evidence(graph.identity(),large.identity()).orElseThrow());
    }
    @Test void boundedPageWorkersOverlapAndProduceIdenticalAuthenticatedRoots()throws Exception {
        var graph=fixture();Path serial=directory.resolve("serial"),parallel=directory.resolve("parallel");
        new PartitionedGraphStore(serial,64_000_000,1).save(graph,()->false);
        var gate=new java.util.concurrent.CountDownLatch(2);var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();var pages=new java.util.concurrent.atomic.AtomicInteger();
        var store=new PartitionedGraphStore(parallel,64_000_000,2,channel->{
            if(Thread.currentThread().getName().startsWith("evo-graph-page")&&pages.getAndIncrement()>0) {
                int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);gate.countDown();
                try{assertTrue(gate.await(2,java.util.concurrent.TimeUnit.SECONDS),"Independent page writes must overlap");channel.force(true);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                finally{active.decrementAndGet();}
            }else channel.force(true);
        });
        store.save(graph,()->false);assertEquals(2,peak.get());assertEquals(0,active.get());
        String pointer=graph.identity().value().substring(7)+".partitions.json";
        assertEquals(Files.readString(serial.resolve(pointer)),Files.readString(parallel.resolve(pointer)));
        assertEquals(new PartitionedGraphStore(serial).partitions(graph.identity(),10,Optional.empty()),new PartitionedGraphStore(parallel).partitions(graph.identity(),10,Optional.empty()));
    }
    @Test void pageWorkerFailureAndCancellationPublishNeitherRootNorSnapshot()throws Exception {
        var graph=fixture();var cause=new java.io.IOException("GRAPH_PARTITION_STORAGE_UNAVAILABLE",new java.sql.SQLException("private","HY000",13));
        Path failed=directory.resolve("worker-failure"),snapshots=directory.resolve("worker-snapshots");
        var store=new PartitionedGraphStore(failed,64_000_000,2,channel->{if(Thread.currentThread().getName().startsWith("evo-graph-page"))throw cause;channel.force(true);});
        assertSame(cause,assertThrows(java.io.IOException.class,()->store.build(graph,snapshots,()->false,workspace->{})));
        assertFalse(Files.exists(failed.resolve(graph.identity().value().substring(7)+".partitions.json")));
        try(var files=Files.list(snapshots)){assertEquals(0,files.count());}
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();Path aborted=directory.resolve("worker-cancel");
        var cancelling=new PartitionedGraphStore(aborted,64_000_000,2,channel->{if(Thread.currentThread().getName().startsWith("evo-graph-page"))cancelled.set(true);channel.force(true);});
        assertThrows(java.util.concurrent.CancellationException.class,()->cancelling.save(graph,cancelled::get));
        assertFalse(Files.exists(aborted.resolve(graph.identity().value().substring(7)+".partitions.json")));
    }
    @TempDir Path directory;
    @Test void legacyGraphAssemblyRetainsEdgesAndNeverPublishesDanglingContributions()throws Exception {
        var graph=fixture();Path snapshots=directory.resolve("snapshots");var store=new PartitionedGraphStore(directory.resolve("pages"));
        var result=store.build(graph,snapshots,()->false,workspace->{});assertEquals(graph.identity(),result.graph());
        assertEquals(new GraphSnapshotCodec().encode(graph),Files.readString(snapshots.resolve(result.graph().value().substring(7)+".graph.json")));
        Path invalid=directory.resolve("invalid-snapshots");var failed=new PartitionedGraphStore(directory.resolve("invalid-pages"));
        assertThrows(java.io.IOException.class,()->failed.build(graph,invalid,()->false,workspace->{
            var edge=graph.edges().getFirst();String id=KnowledgeGraph.derivedIdentity("invalid-contribution",1);
            workspace.records("edges",GraphSnapshotCodec::edge).put(edge.context()+"|"+id,new KnowledgeGraph.Edge(id,edge.context(),"fixture.dangling",edge.source(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(KnowledgeGraph.derivedIdentity("absent-node",1)),Optional.empty()),edge.evidence(),Optional.empty()));
        }));
        try(var files=Files.list(invalid)){assertEquals(0,files.count());}
    }
    @Test void diskProjectionMatchesCanonicalSnapshotAndColdPages()throws Exception {
        var repo=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:disk-projection");
        var snapshot=com.evolution.analysis.contract.source.RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());
        var input=new CanonicalGraphInput(new com.evolution.analysis.ingestion.RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),List.of());
        var expected=new CanonicalGraphBuilder().build(input);
        Path snapshots=Files.createDirectory(directory.resolve("snapshots"));var store=new PartitionedGraphStore(directory.resolve("pages"));
        var published=store.build(input,snapshots,()->false);
        assertEquals(expected.identity(),published.graph());assertEquals(expected.nodes().size(),published.nodes());
        assertEquals(new GraphSnapshotCodec().encode(expected),Files.readString(snapshots.resolve(expected.identity().value().substring(7)+".graph.json")));
        var cold=new PartitionedGraphStore(directory.resolve("pages"));var partition=cold.partitions(published.graph(),1,Optional.empty()).items().getFirst();
        assertEquals(expected.nodes(),cold.nodes(partition,10,Optional.empty()).items());
        assertEquals(expected.evidence().getFirst(),cold.evidence(published.graph(),expected.evidence().getFirst().identity()).orElseThrow());
        assertTrue(cold.evidence(published.graph(),ContentDigest.sha256Utf8("not-in-this-graph")).isEmpty());
        assertEquals(published,store.build(input,snapshots,()->false));
        var changed=new CanonicalGraphInput(input.repository(),Optional.empty(),Optional.empty(),List.of(),List.of(new com.evolution.analysis.ingestion.IngestionEvidence.Issue(com.evolution.analysis.ingestion.IngestionEvidence.Reason.INPUT_UNAVAILABLE,"absent",List.of(ContentDigest.sha256Utf8("absent")))),List.of());
        assertThrows(java.util.concurrent.CancellationException.class,()->store.build(changed,snapshots,()->true));
    }
    @Test void canonicalSnapshotImportsByRowsAndRejectsNoncanonicalOrDanglingInputBeforePublication()throws Exception{
        var graph=fixture();Path source=directory.resolve("input.json");Files.writeString(source,new GraphSnapshotCodec().encode(graph));
        var imported=new PartitionedGraphStore(directory.resolve("imported"));imported.importSnapshot(source,graph.identity(),()->false);
        assertEquals(graph.nodes().size(),imported.partitions(graph.identity(),10,Optional.empty()).items().stream().mapToLong(PartitionedGraphReader.Partition::nodes).sum());
        Files.writeString(source," "+new GraphSnapshotCodec().encode(graph));var noncanonical=new PartitionedGraphStore(directory.resolve("noncanonical"));
        assertThrows(java.io.IOException.class,()->noncanonical.importSnapshot(source,graph.identity(),()->false));
        assertFalse(Files.exists(directory.resolve("noncanonical/"+graph.identity().value().substring(7)+".partitions.json")));
        var form=new TreeMap<>(GraphJson.object(graph.canonicalForm()));var nodes=new ArrayList<>(graph.nodes());nodes.removeFirst();form.put("nodes",nodes);
        var digest=com.evolution.analysis.contract.serialization.CanonicalJson.sha256(form);
        Files.writeString(source,com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("format",GraphSnapshotCodec.FORMAT,"identity",digest,"graph",form)));
        var dangling=new PartitionedGraphStore(directory.resolve("dangling"));assertThrows(java.io.IOException.class,()->dangling.importSnapshot(source,digest,()->false));
        assertFalse(Files.exists(directory.resolve("dangling/"+digest.value().substring(7)+".partitions.json")));
    }
    @Test void singlePassBytesAndColdPartitionsMatchTheSameIndependentContextRows() throws Exception {
        var snapshot = FileGraphStoreTest.graph().snapshot();
        var root = GraphEvidence.capture("repository.snapshot", Map.of("fixture", "one physical source"));
        var main = GraphEvidence.capture("analysis.manifest", Map.of("sourceSet", "main", "variant", "debug"));
        var test = GraphEvidence.capture("analysis.manifest", Map.of("sourceSet", "test", "variant", "release"));
        var debug = AnalysisIdentity.fromCanonicalManifestInputs(main.canonicalJson());
        var release = AnalysisIdentity.fromCanonicalManifestInputs(test.canonicalJson());
        var contexts = List.of(new KnowledgeGraph.Context(snapshot.value(), snapshot, Optional.empty(), root.identity()),
                new KnowledgeGraph.Context(debug.value(), snapshot, Optional.of(debug), main.identity()),
                new KnowledgeGraph.Context(release.value(), snapshot, Optional.of(release), test.identity()));
        String document = KnowledgeGraph.derivedIdentity("fixture-source", "Shared.java");
        String a = KnowledgeGraph.derivedIdentity("fixture-type", "A"), b = KnowledgeGraph.derivedIdentity("fixture-type", "B");
        var nodes = new ArrayList<KnowledgeGraph.Node>();
        for (var context : contexts) {
            nodes.add(new KnowledgeGraph.Node(document, context.identity(), "SOURCE_DOCUMENT", List.of(root.identity())));
            if (context.analysis().isEmpty()) continue;
            for (String type : List.of(a, b)) nodes.add(new KnowledgeGraph.Node(type, context.identity(), "TYPE", List.of(context.evidence())));
        }
        var edges = List.of(scopedDependency(debug, a, b, main), scopedDependency(release, b, a, test));
        var proofs = List.of(root, main, test);
        var expectedPayload = CanonicalJson.write(Map.of("schema", "knowledge-graph:m5.1-v1", "snapshot", snapshot,
                "contexts", contexts.stream().sorted(Comparator.comparing(KnowledgeGraph.Context::identity)).toList(),
                "nodes", nodes.stream().sorted(Comparator.comparing(KnowledgeGraph.Node::context).thenComparing(KnowledgeGraph.Node::identity)).toList(),
                "edges", edges.stream().sorted(Comparator.comparing(KnowledgeGraph.Edge::context).thenComparing(KnowledgeGraph.Edge::identity)).toList(),
                "evidence", proofs.stream().sorted(Comparator.comparing(GraphEvidence::identity)).toList()));
        var digest = ContentDigest.sha256Utf8(expectedPayload);
        var repeated = new ArrayList<>(nodes.reversed()); repeated.add(nodes.getFirst());
        var memory = new KnowledgeGraph(snapshot, contexts.reversed(), repeated, edges.reversed(), proofs.reversed());
        assertEquals(digest, memory.identity());

        Path snapshots = directory.resolve("multi-context-snapshots"), pages = directory.resolve("multi-context-pages");
        var published = new PartitionedGraphStore(pages).build(memory, snapshots, () -> false, ignored -> {});
        assertEquals(digest, published.graph());
        assertEquals(7, published.nodes()); assertEquals(2, published.edges()); assertEquals(3, published.evidence());
        var stored = snapshots.resolve(digest.value().substring(7) + ".graph.json");
        assertEquals("{\"format\":\"" + GraphSnapshotCodec.FORMAT + "\",\"graph\":" + expectedPayload + ",\"identity\":\"" + digest.value() + "\"}", Files.readString(stored));
        assertEquals(digest, new FileGraphStore(snapshots).load(digest).orElseThrow().identity());

        var cold = new PartitionedGraphStore(pages);
        var partitions = cold.partitions(digest, 3, Optional.empty());
        assertEquals(3, partitions.items().size()); assertTrue(partitions.continuation().isEmpty());
        var byContext = new HashMap<String, PartitionedGraphReader.Partition>();
        for (var partition : partitions.items()) {
            byContext.put(partition.context(), partition);
            var actual = new ArrayList<KnowledgeGraph.Node>(); Optional<String> cursor = Optional.empty();
            do {
                var page = cold.nodes(partition, 1, cursor);
                actual.addAll(page.items()); cursor = page.continuation();
            } while (cursor.isPresent());
            assertEquals(memory.nodes().stream().filter(node -> node.context().equals(partition.context())).toList(), actual);
            var request = new ArchitectureQuery.Request(digest, partition.context(), ArchitectureQuery.Scope.ENTITY,
                    ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING, Set.of("fixture.dependency"),
                    ArchitectureQuery.Population.all(), false, Optional.empty(), ArchitectureQuery.Limits.defaults());
            assertEquals(Optional.of(0L), new ArchitectureQueryService(memory).findCycles(request).exactTotal());
        }
        assertEquals(1, byContext.get(snapshot.value()).nodes());
        var debugRows = byContext.get(debug.value()); var releaseRows = byContext.get(release.value());
        assertEquals(List.of(edges.getFirst()), cold.adjacency(debugRows, a, false, 1, Optional.empty()).items());
        assertEquals(List.of(edges.getLast()), cold.adjacency(releaseRows, a, true, 1, Optional.empty()).items());
        assertTrue(cold.adjacency(debugRows, a, true, 1, Optional.empty()).items().isEmpty());
        var cursor = cold.nodes(debugRows, 1, Optional.empty()).continuation();
        assertTrue(cursor.isPresent());
        assertThrows(IllegalArgumentException.class, () -> cold.nodes(releaseRows, 1, cursor));
        for (var proof : proofs) assertEquals(Optional.of(proof), cold.evidence(digest, proof.identity()));
    }

    private static KnowledgeGraph.Edge scopedDependency(AnalysisIdentity analysis, String source, String target, GraphEvidence proof) {
        return new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("fixture-dependency", List.of(source, target)), analysis.value(), "fixture.dependency", source,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(target), Optional.empty()), List.of(proof.identity()), Optional.empty());
    }
    @Test void aRehashedDerivedIndexCannotSilentlyOmitOrRedirectCanonicalEdges()throws Exception{
        var graph=fixture();new PartitionedGraphStore(directory).save(graph,()->false);
        var pointer=directory.resolve(graph.identity().value().substring(7)+".partitions.json");
        var reference=new TreeMap<String,Object>(GraphJson.object(GraphJson.read(Files.readString(pointer),GraphJson.Limits.defaults())));
        var raw=GraphJson.object(reference.get("document"));var handle=new com.evolution.analysis.acquisition.streaming.ContentHandle(new ContentDigest(GraphJson.string(raw.get("digest"))),((Number)raw.get("size")).longValue());
        var content=new StreamingContentStore(directory.resolve("content"));Map<String,Object> document;
        try(var input=content.open(handle)){document=new TreeMap<>(GraphJson.object(GraphJson.read(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),GraphJson.Limits.defaults())));}
        var trees=new TreeMap<>(GraphJson.object(document.get("trees")));trees.put("outgoing",trees.get("incoming"));document.put("trees",trees);
        var replacement=com.evolution.analysis.contract.serialization.CanonicalJson.write(document).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        reference.put("document",content.put(new java.io.ByteArrayInputStream(replacement),()->false));
        Files.writeString(pointer,com.evolution.analysis.contract.serialization.CanonicalJson.write(reference));
        assertThrows(java.io.IOException.class,()->new PartitionedGraphStore(directory).partitions(graph.identity(),10,Optional.empty()));
    }
    @Test void indexSpansMultipleDirectoryLevelsWithoutARepositoryRowCeiling()throws Exception{
        var base=FileGraphStoreTest.graph();String context=base.contexts().getFirst().identity();var evidence=base.evidence().getFirst();var nodes=new ArrayList<KnowledgeGraph.Node>();
        for(int i=0;i<33_000;i++)nodes.add(new KnowledgeGraph.Node(KnowledgeGraph.derivedIdentity("large-partition",i),context,"TYPE",List.of(evidence.identity())));
        var graph=new KnowledgeGraph(base.snapshot(),base.contexts(),nodes,List.of(),base.evidence());new PartitionedGraphStore(directory).save(graph,()->false);
        var cold=new PartitionedGraphStore(directory);var partition=cold.partitions(graph.identity(),1,Optional.empty()).items().getFirst();
        int count=0;Optional<String> cursor=Optional.empty();KnowledgeGraph.Node last=null;
        do{var page=cold.nodes(partition,512,cursor);count+=page.items().size();last=page.items().getLast();cursor=page.continuation();}while(cursor.isPresent());
        assertEquals(33_000,count);assertEquals(graph.nodes().getLast(),last);
    }
    @Test void coldPagesAndBothAdjacencyDirectionsMatchCanonicalGraphWithoutLoadingIt()throws Exception{
        var graph=fixture();var store=new PartitionedGraphStore(directory);store.save(graph,()->false);
        var cold=new PartitionedGraphStore(directory);var partitions=cold.partitions(graph.identity(),1,Optional.empty());
        assertEquals(1,partitions.items().size());assertTrue(partitions.continuation().isPresent());
        var second=cold.partitions(graph.identity(),1,partitions.continuation());assertEquals(1,second.items().size());assertTrue(second.continuation().isEmpty());
        var found=new ArrayList<KnowledgeGraph.Node>();Optional<String> cursor=Optional.empty();var partition=partitions.items().getFirst();
        do{var page=cold.nodes(partition,37,cursor);found.addAll(page.items());cursor=page.continuation();}while(cursor.isPresent());
        assertEquals(graph.nodes().stream().filter(n->n.context().equals(partition.context())).toList(),found);
        var edge=graph.edges().stream().filter(e->e.context().equals(partition.context())).findFirst().orElseThrow();
        assertEquals(graph.outgoing(partition.context(),edge.source()),cold.adjacency(partition,edge.source(),false,50,Optional.empty()).items());
        String target=edge.target().identities().getFirst();
        assertEquals(graph.incoming(partition.context(),target),cold.adjacency(partition,target,true,50,Optional.empty()).items());
        var nodePage=cold.nodes(partition,1,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->cold.adjacency(partition,edge.source(),false,1,nodePage.continuation()));
        var forged=new PartitionedGraphReader.Partition(partition.schema(),partition.graph(),second.items().getFirst().context(),partition.segment(),partition.nodes(),partition.edges());
        assertThrows(java.io.IOException.class,()->cold.nodes(forged,10,Optional.empty()));
    }
    @Test void cancelledPublicationHasNoRootAndCorruptContentCannotBecomeQueryRows()throws Exception{
        var graph=fixture();var store=new PartitionedGraphStore(directory);
        assertThrows(java.util.concurrent.CancellationException.class,()->store.save(graph,()->true));
        assertThrows(java.io.IOException.class,()->store.partitions(graph.identity(),10,Optional.empty()));
        store.save(graph,()->false);var partition=store.partitions(graph.identity(),10,Optional.empty()).items().getFirst();
        String hex=partition.segment().digest().value().substring(7);
        Files.writeString(directory.resolve("content/sha256/"+hex.substring(0,2)+"/"+hex.substring(2)),"corrupt");
        assertThrows(java.io.IOException.class,()->new PartitionedGraphStore(directory).nodes(partition,10,Optional.empty()));
    }
    @Test void publicStoreCoalescesIntermediateLeafForcesAndPreservesColdIntegrity()throws Exception{
        var graph=fixture();Path target=directory.resolve("coalesced");
        var store=new PartitionedGraphStore(target,64_000_000,2);
        store.save(graph,()->false);
        // fixture() writes ~20 intermediate leaf blobs across 7 trees, 5 multi-leaf branches, 2 partition segments, and 1 root document.
        assertTrue(store.forcedBlobWrites()<=8,"Intermediate leaf blobs must coalesce force(true) to branch/segment/root boundaries, got: "+store.forcedBlobWrites());
        assertTrue(store.forcedBlobWrites()>=3,"Branch, partition-segment and root-document durability boundaries must still force(true)");
        var cold=new PartitionedGraphStore(target);
        var partitions=cold.partitions(graph.identity(),10,Optional.empty()).items();
        assertEquals(2,partitions.size());
        assertEquals(graph.nodes().size(),partitions.stream().mapToLong(PartitionedGraphReader.Partition::nodes).sum());
    }
    static KnowledgeGraph fixture(){
        var base=FileGraphStoreTest.graph();var evidence=base.evidence().getFirst();var contexts=new ArrayList<>(base.contexts());
        String other=KnowledgeGraph.derivedIdentity("partition-context",List.of("other"));contexts.add(new KnowledgeGraph.Context(other,base.snapshot(),Optional.empty(),evidence.identity()));
        var nodes=new ArrayList<KnowledgeGraph.Node>();var edges=new ArrayList<KnowledgeGraph.Edge>();
        for(var context:contexts)for(int i=0;i<600;i++){
            String id=KnowledgeGraph.derivedIdentity("partition-node",List.of(i));nodes.add(new KnowledgeGraph.Node(id,context.identity(),"TYPE",List.of(evidence.identity())));
            if(i>0)edges.add(new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("partition-edge",List.of(i)),context.identity(),"graph.depends-on",id,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(KnowledgeGraph.derivedIdentity("partition-node",List.of(i-1))),Optional.empty()),List.of(evidence.identity()),Optional.empty()));
        }
        return new KnowledgeGraph(base.snapshot(),contexts,nodes,edges,base.evidence());
    }
}
