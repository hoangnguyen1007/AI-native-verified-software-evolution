package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PartitionedGraphStoreTest {
    @TempDir Path directory;
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
