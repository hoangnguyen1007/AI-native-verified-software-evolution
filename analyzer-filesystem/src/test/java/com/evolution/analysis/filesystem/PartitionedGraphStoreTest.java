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
    @Test void typedPublicationKeepsTheSameRootWithoutRebuildingADiskProjection()throws Exception {
        var base=FileGraphStoreTest.graph();
        var proof=GraphEvidence.capture("fixture.utf8",Map.of("text","Tiếng Việt \uD83D\uDCFA \"quote\" \\ slash\n"));
        var proofs=new ArrayList<>(base.evidence());proofs.add(proof);
        var graph=new KnowledgeGraph(base.snapshot(),base.contexts(),base.nodes(),base.edges(),proofs);
        Path direct=directory.resolve("direct"),legacy=directory.resolve("legacy"),snapshots=directory.resolve("snapshots");
        var store=new PartitionedGraphStore(direct,64_000_000,1,channel->{
            try(var paths=Files.list(direct)) {
                assertFalse(paths.anyMatch(p->p.getFileName().toString().endsWith(".sqlite")),
                        "Publishing an already validated graph must not spool it through SQLite");
            }
            channel.force(true);
        });
        store.save(graph,()->false);
        new PartitionedGraphStore(legacy).build(graph,snapshots,()->false,workspace->{});
        String pointer=graph.identity().value().substring(7)+".partitions.json";
        assertArrayEquals(Files.readAllBytes(legacy.resolve(pointer)),Files.readAllBytes(direct.resolve(pointer)));
        var reopened=new PartitionedGraphStore(direct);
        assertEquals(Optional.of(proof),reopened.evidence(graph.identity(),proof.identity()));
        assertTrue(reopened.verifyPublished(graph.identity(),()->false));
    }
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
        assertTrue(store.verifyPublished(graph.identity(), () -> false));
        String hex=partition.segment().digest().value().substring(7);
        Files.writeString(directory.resolve("content/sha256/"+hex.substring(0,2)+"/"+hex.substring(2)),"corrupt");
        assertThrows(java.io.IOException.class,()->store.verifyPublished(graph.identity(), () -> false));
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

    @Test void localPageCacheAcceleratesHotReadPathsWithoutLeakingThreadLocalState() throws Exception {
        var base = fixture();
        var extraEvidence = new ArrayList<>(base.evidence());
        for (int i = 0; i < 8; i++) {
            extraEvidence.add(GraphEvidence.capture("fixture.page-cache", Map.of("index", i)));
        }
        String ctx0 = base.contexts().getFirst().identity();
        var enrichedNodes = new ArrayList<>(base.nodes());
        var enrichedEdges = new ArrayList<>(base.edges());
        String hubId = KnowledgeGraph.derivedIdentity("page-cache-hub", 0);
        enrichedNodes.add(new KnowledgeGraph.Node(hubId, ctx0, "TYPE",
                extraEvidence.stream().map(GraphEvidence::identity).toList()));
        for (int i = 1; i <= 5; i++) {
            String leafId = KnowledgeGraph.derivedIdentity("page-cache-leaf", i);
            enrichedNodes.add(new KnowledgeGraph.Node(leafId, ctx0, "TYPE", List.of(extraEvidence.get(i).identity())));
            enrichedEdges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("page-cache-edge", i), ctx0, "graph.depends-on", hubId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(leafId), Optional.empty()),
                    List.of(extraEvidence.get(i).identity()), Optional.empty()));
        }
        var graph = new KnowledgeGraph(base.snapshot(), base.contexts(), enrichedNodes, enrichedEdges, extraEvidence);
        Path target = directory.resolve("page-cache-test");
        var store = new PartitionedGraphStore(target);
        store.save(graph, () -> false);

        var cold = new PartitionedGraphStore(target);
        var partitions = cold.partitions(graph.identity(), 10, Optional.empty()).items();
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive());
        var firstPartition = partitions.stream().filter(p -> p.context().equals(ctx0)).findFirst().orElseThrow();

        long hitsBefore = PartitionedGraphStore.LOCAL_PAGE_CACHE_HITS.get();
        var ctxGraph = cold.contextGraph(graph.identity(), firstPartition.context(), () -> false).orElseThrow();
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "contextGraph must release ThreadLocal page cache");
        assertEquals(firstPartition.nodes(), ctxGraph.nodes().size());
        assertEquals(firstPartition.edges(), ctxGraph.edges().size());
        assertTrue(PartitionedGraphStore.LOCAL_PAGE_CACHE_HITS.get() > hitsBefore,
                "contextGraph must get page cache hits across repeated evidence-tree lookups");

        var expectedNode = ctxGraph.nodes().getFirst();
        assertEquals(Optional.of(expectedNode), cold.node(graph.identity(), expectedNode.context(), expectedNode.identity()));
        assertEquals(Optional.empty(), cold.node(graph.identity(), expectedNode.context(), KnowledgeGraph.derivedIdentity("missing", 0)));
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "node() must release ThreadLocal page cache");

        var nodesPage = cold.nodes(firstPartition, 64, Optional.empty());
        assertEquals(64, nodesPage.items().size());
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "nodes() must release ThreadLocal page cache");

        long adjHitsBefore = PartitionedGraphStore.LOCAL_PAGE_CACHE_HITS.get();
        var adjPage = cold.adjacency(firstPartition, hubId, false, 32, Optional.empty());
        assertEquals(5, adjPage.items().size());
        assertTrue(PartitionedGraphStore.LOCAL_PAGE_CACHE_HITS.get() > adjHitsBefore,
                "adjacency() must get page cache hits when resolving multiple edges on shared pages");
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "adjacency() must release ThreadLocal page cache");

        var ev = extraEvidence.getFirst();
        assertEquals(Optional.of(ev), cold.evidence(graph.identity(), ev.identity()));
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "evidence() must release ThreadLocal page cache");

        var evolution = cold.openEvolution(graph.identity(), () -> false);
        long evoHitsBefore = PartitionedGraphStore.LOCAL_PAGE_CACHE_HITS.get();
        assertEquals(2, evolution.contexts(10, Optional.empty()).items().size());
        assertTrue(PartitionedGraphStore.LOCAL_PAGE_CACHE_HITS.get() > evoHitsBefore,
                "openEvolution.contexts() must get page cache hits on partitions tree");
        assertFalse(evolution.nodes(firstPartition.context(), 32, Optional.empty()).items().isEmpty());
        assertFalse(evolution.edges(firstPartition.context(), 32, Optional.empty()).items().isEmpty());
        assertFalse(evolution.evidence(10, Optional.empty()).items().isEmpty());
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "evolution reads must release ThreadLocal page cache");

        // Error path must also clean up ThreadLocal cleanly
        assertThrows(IllegalArgumentException.class,
                () -> cold.adjacency(firstPartition, "not-a-valid-node-id", false, 10, Optional.empty()));
        assertFalse(PartitionedGraphStore.isLocalPageCacheActive(), "Exception path must never leak ThreadLocal page cache");
    }
    @Test void internalPublishAuditsInMemoryWithoutSpoolingAuditSqliteAndHasPublishedRootReflectsState() throws Exception {
        var graph = fixture();
        Path pages = directory.resolve("no-audit-pages");
        Path snapshots = directory.resolve("no-audit-snapshots");
        var store = new PartitionedGraphStore(pages);
        assertFalse(store.hasPublishedRoot(graph.identity()));
        store.build(graph, snapshots, () -> false, workspace -> {});
        assertTrue(store.hasPublishedRoot(graph.identity()));
        try (var leftover = Files.list(pages)) {
            assertTrue(leftover.noneMatch(p -> p.getFileName().toString().startsWith("graph-audit-")),
                    "Default in-process publish must not create graph-audit-*.sqlite");
        }
        // Dangling contribution still fails cleanly via in-process database audit.
        Path badSnapshots = directory.resolve("bad-audit-snapshots");
        var badStore = new PartitionedGraphStore(directory.resolve("bad-audit-pages"));
        assertThrows(java.io.IOException.class, () -> badStore.build(graph, badSnapshots, () -> false, workspace -> {
            var edge = graph.edges().getFirst();
            String id = KnowledgeGraph.derivedIdentity("bad-dangling", 1);
            workspace.records("edges", GraphSnapshotCodec::edge).put(edge.context() + "|" + id,
                    new KnowledgeGraph.Edge(id, edge.context(), "fixture.dangling", edge.source(),
                            new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,
                                    List.of(KnowledgeGraph.derivedIdentity("missing-target", 1)), Optional.empty()),
                            edge.evidence(), Optional.empty()));
        }));
    }
    @Test void nearBudgetRowAndSmallBudgetMultiRowLeavesSurviveFramingAndColdRead() throws Exception {
        var base = FileGraphStoreTest.graph();
        var evidence = new ArrayList<>(base.evidence());
        // Single row ~4040 bytes: admitted under rowBytes=4096, but leaf JSON framing pushes the leaf blob > 4096 bytes.
        var nearLimit = GraphEvidence.capture("fixture.near-budget", Map.of("text", "a".repeat(3880)));
        int encodedLen = GraphCanonicalWriter.encodeValue(nearLimit).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(encodedLen > 4000 && encodedLen <= 4096, "Expected encoded row near 4096 bytes, got " + encodedLen);
        evidence.add(nearLimit);
        var multiRows = new ArrayList<GraphEvidence>();
        for (int i = 0; i < 6; i++) {
            var row = GraphEvidence.capture("fixture.multi-small", Map.of("index", i, "text", "b".repeat(1200)));
            multiRows.add(row);
            evidence.add(row);
        }
        var graph = new KnowledgeGraph(base.snapshot(), base.contexts(), base.nodes(), base.edges(), evidence);

        Path buildPages = directory.resolve("tight-build-pages");
        Path buildSnaps = directory.resolve("tight-build-snaps");
        var published = new PartitionedGraphStore(buildPages, 4096, 1).build(graph, buildSnaps, () -> false, ws -> {});
        assertEquals(graph.identity(), published.graph());
        var coldBuild = new PartitionedGraphStore(buildPages, 4096, 1);
        assertTrue(coldBuild.verifyPublished(graph.identity(), () -> false));
        assertEquals(Optional.of(nearLimit), coldBuild.evidence(graph.identity(), nearLimit.identity()));
        for (var row : multiRows) {
            assertEquals(Optional.of(row), coldBuild.evidence(graph.identity(), row.identity()));
        }

        Path savePages = directory.resolve("tight-save-pages");
        new PartitionedGraphStore(savePages, 4096, 2).save(graph, () -> false);
        var coldSave = new PartitionedGraphStore(savePages, 4096, 1);
        assertTrue(coldSave.verifyPublished(graph.identity(), () -> false));
        assertEquals(Optional.of(nearLimit), coldSave.evidence(graph.identity(), nearLimit.identity()));

        // An explicit rowBytes budget still rejects a row that genuinely exceeds it without publishing.
        var overBudget = GraphEvidence.capture("fixture.over-budget", Map.of("text", "c".repeat(4200)));
        var overEvidence = new ArrayList<>(base.evidence());
        overEvidence.add(overBudget);
        var overGraph = new KnowledgeGraph(base.snapshot(), base.contexts(), base.nodes(), base.edges(), overEvidence);
        Path overPages = directory.resolve("over-pages");
        Path overSnaps = directory.resolve("over-snaps");
        var failure = assertThrows(java.io.IOException.class,
                () -> new PartitionedGraphStore(overPages, 4096, 1).build(overGraph, overSnaps, () -> false, ws -> {}));
        assertEquals("GRAPH_PARTITION_ROW_WORKER_REQUIRED", failure.getMessage());
        assertFalse(Files.exists(overPages.resolve(overGraph.identity().value().substring(7) + ".partitions.json")));
    }

    @Test void largeRowExceedingStreamThresholdRoundTripsThroughDefaultStore() throws Exception {
        assertTrue(PartitionedGraphStore.DEFAULT_ROW_BYTES >= 256 * 1024 * 1024,
                "Default store must not retain the legacy 64 MB single-row ceiling");
        var base = FileGraphStoreTest.graph();
        var large = GraphEvidence.capture("fixture.stream-large-row", Map.of("text", "z".repeat(5_200_000)));
        var evidence = new ArrayList<>(base.evidence());
        evidence.add(large);
        var graph = new KnowledgeGraph(base.snapshot(), base.contexts(), base.nodes(), base.edges(), evidence);
        Path pages = directory.resolve("large-default-pages");
        Path snaps = directory.resolve("large-default-snaps");
        var published = new PartitionedGraphStore(pages).build(graph, snaps, () -> false, ws -> {});
        assertEquals(graph.identity(), published.graph());
        var cold = new PartitionedGraphStore(pages);
        assertTrue(cold.verifyPublished(graph.identity(), () -> false));
        assertEquals(Optional.of(large), cold.evidence(graph.identity(), large.identity()));
    }
    @Test void singlePassAssembleMatchesAuditOnPublishAndImportSnapshotAndRejectsDanglingEdges()throws Exception {
        var graph = fixture();
        Path fastPages = directory.resolve("singlepass-pages"), fastSnaps = directory.resolve("singlepass-snaps");
        Path auditPages = directory.resolve("audit-pages"), auditSnaps = directory.resolve("audit-snaps");
        Path importPages = directory.resolve("import-pages");
        var fastPub = new PartitionedGraphStore(fastPages).build(graph, fastSnaps, () -> false, ws -> {});
        String prev = System.getProperty("evo.graph.auditOnPublish");
        PartitionedGraphStore.Publication auditPub;
        try {
            System.setProperty("evo.graph.auditOnPublish", "true");
            auditPub = new PartitionedGraphStore(auditPages).build(graph, auditSnaps, () -> false, ws -> {});
        } finally {
            if (prev == null) System.clearProperty("evo.graph.auditOnPublish");
            else System.setProperty("evo.graph.auditOnPublish", prev);
        }
        assertEquals(graph.identity(), fastPub.graph());
        assertEquals(auditPub.graph(), fastPub.graph());
        assertEquals(auditPub.edgeKinds(), fastPub.edgeKinds());
        assertEquals(auditPub.nodes(), fastPub.nodes());
        assertEquals(auditPub.edges(), fastPub.edges());
        assertEquals(auditPub.evidence(), fastPub.evidence());
        String snapFile = graph.identity().value().substring(7) + ".graph.json";
        assertArrayEquals(Files.readAllBytes(auditSnaps.resolve(snapFile)), Files.readAllBytes(fastSnaps.resolve(snapFile)));
        new PartitionedGraphStore(importPages).importSnapshot(fastSnaps.resolve(snapFile), graph.identity(), () -> false);
        assertTrue(new PartitionedGraphStore(fastPages).verifyPublished(graph.identity(), () -> false));
        assertTrue(new PartitionedGraphStore(importPages).verifyPublished(graph.identity(), () -> false));
        // Dangling edge target must fail closed before snapshot or root publication.
        Path badPages = directory.resolve("dangling-pages"), badSnaps = directory.resolve("dangling-snaps");
        var ctx = graph.contexts().getFirst();
        var ev = graph.evidence().getFirst();
        var badEdge = new KnowledgeGraph.Edge(
                KnowledgeGraph.derivedIdentity("bad-edge", List.of(1)), ctx.identity(), "graph.depends-on",
                KnowledgeGraph.derivedIdentity("missing-node", List.of(1)),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,
                        List.of(KnowledgeGraph.derivedIdentity("missing-node", List.of(2))), Optional.empty()),
                List.of(ev.identity()), Optional.empty());
        assertEquals("GRAPH_PARTITION_DANGLING_REFERENCE", assertThrows(java.io.IOException.class, () ->
                new PartitionedGraphStore(badPages).build(graph.snapshot(), badSnaps, () -> false, ws -> {
                    ws.records("evidence", GraphSnapshotCodec::evidence).put(ev.identity().value(), ev);
                    ws.records("contexts", GraphSnapshotCodec::context).put(ctx.identity(), ctx);
                    ws.records("edges", GraphSnapshotCodec::edge).put(ctx.identity() + "|" + badEdge.identity(), badEdge);
                })).getMessage());
        assertFalse(Files.exists(badSnaps.resolve(snapFile)));
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
