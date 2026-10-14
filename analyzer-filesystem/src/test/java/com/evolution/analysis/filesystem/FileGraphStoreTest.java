package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.graph.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileGraphStoreTest {
    @TempDir Path directory;
    static KnowledgeGraph graph() {
        var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/file-graph.git");
        var snapshot=SnapshotIdentity.from(repo,ContentDigest.sha256Utf8("snapshot"));
        var payload=GraphEvidence.capture("fixture.evidence",Map.of("span",List.of(1,2,3,4),"derivation","DERIVED",
                "status","CONDITIONAL","region",Map.of("T",List.of("dev"),"F",List.of("prod"),"U",List.of("missing")),
                "witness",Map.of("assignment",Map.of("profile","dev"),"replay",ContentDigest.sha256Utf8("replayed"))));
        String context=snapshot.value(),source=KnowledgeGraph.derivedIdentity("fixture-source",List.of("a")),target=KnowledgeGraph.derivedIdentity("fixture-target",List.of("b"));
        var edge=new KnowledgeGraph.Edge(KnowledgeGraph.derivedIdentity("fixture-edge",List.of(source,target)),context,"fixture.dependency",source,
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(target),Optional.empty()),List.of(payload.identity()),Optional.empty());
        return new KnowledgeGraph(snapshot,List.of(new KnowledgeGraph.Context(context,snapshot,Optional.empty(),payload.identity())),
                List.of(new KnowledgeGraph.Node(source,context,"TYPE",List.of(payload.identity())),new KnowledgeGraph.Node(target,context,"TYPE",List.of(payload.identity()))),
                List.of(edge),List.of(payload));
    }
    Path file(KnowledgeGraph graph) {return directory.resolve(graph.identity().value().substring(7)+".graph.json");}
    @Test void freshInstanceRestoresAllEvidenceAndMatchesInMemoryStore() {
        var graph=graph();var store=new FileGraphStore(directory);var memory=new InMemoryGraphStore();
        assertFalse(store.available(graph.identity()));store.save(graph);store.save(graph);memory.save(graph);
        var restored=new FileGraphStore(directory).load(graph.identity()).orElseThrow();
        assertNotSame(graph,restored);assertEquals(graph.identity(),restored.identity());
        assertEquals(graph.manifest(),new FileGraphStore(directory).manifest(graph.identity()).orElseThrow());
        assertEquals(new GraphSnapshotCodec().encode(memory.load(graph.identity()).orElseThrow()),new GraphSnapshotCodec().encode(restored));
        assertEquals(graph.edges(),restored.edges());assertEquals(graph.evidence(),restored.evidence());
        assertEquals(graph.outgoing(graph.contexts().getFirst().identity(),graph.edges().getFirst().source()),
                restored.outgoing(graph.contexts().getFirst().identity(),graph.edges().getFirst().source()));
    }
    @Test void corruptExistingSnapshotIsRejectedAndNeverOverwritten() throws Exception {
        var graph=graph();var store=new FileGraphStore(directory);store.save(graph);
        byte[] corrupt="{\"format\":\"unknown\"}".getBytes(StandardCharsets.UTF_8);Files.write(file(graph),corrupt);
        assertEquals(GraphStoreException.Reason.CORRUPT_SNAPSHOT,assertThrows(GraphStoreException.class,()->store.load(graph.identity())).reason());
        assertThrows(GraphStoreException.class,()->store.save(graph));assertArrayEquals(corrupt,Files.readAllBytes(file(graph)));
    }
    @Test void cancellationBeforeAndDuringWritePublishesNothingAndKeepsEarlierSnapshot() throws Exception {
        var graph=graph();var store=new FileGraphStore(directory);
        assertEquals(GraphStoreException.Reason.CANCELLED,assertThrows(GraphStoreException.class,()->store.save(graph,()->true)).reason());
        var calls=new AtomicInteger();
        assertEquals(GraphStoreException.Reason.CANCELLED,assertThrows(GraphStoreException.class,()->store.save(graph,()->calls.incrementAndGet()>=2)).reason());
        assertFalse(Files.exists(file(graph)));try(var files=Files.list(directory)){assertEquals(0,files.count());}
        store.save(graph);var bytes=Files.readAllBytes(file(graph));
        assertThrows(GraphStoreException.class,()->store.save(graph,()->true));assertArrayEquals(bytes,Files.readAllBytes(file(graph)));
    }
    @Test void boundedReadsWritesMalformedUtf8AndNonregularPathsAreExplicit() throws Exception {
        var graph=graph();var small=new FileGraphStore(directory,10,new GraphJson.Limits(10,10,10));
        assertEquals(GraphStoreException.Reason.LIMIT_EXCEEDED,assertThrows(GraphStoreException.class,()->small.save(graph)).reason());
        Files.write(file(graph),new byte[11]);assertEquals(GraphStoreException.Reason.LIMIT_EXCEEDED,assertThrows(GraphStoreException.class,()->small.load(graph.identity())).reason());
        Files.write(file(graph),new byte[]{(byte)0xc3,(byte)0x28});
        assertEquals(GraphStoreException.Reason.CORRUPT_SNAPSHOT,assertThrows(GraphStoreException.class,()->new FileGraphStore(directory).load(graph.identity())).reason());
        Files.delete(file(graph));Files.createDirectory(file(graph));
        assertEquals(GraphStoreException.Reason.UNSAFE_PATH,assertThrows(GraphStoreException.class,()->new FileGraphStore(directory).load(graph.identity())).reason());
        assertThrows(IllegalArgumentException.class,()->new ContentDigest("../../escape"));
        assertEquals(GraphStoreException.Reason.UNSAFE_PATH,assertThrows(GraphStoreException.class,()->new FileGraphStore(directory.resolve("absent"))).reason());
    }
    @Test void wrongDigestUnknownVersionAndTruncationFailClosed() throws Exception {
        var graph=graph();String valid=new GraphSnapshotCodec().encode(graph);var store=new FileGraphStore(directory);
        for(String content:List.of(valid.replace(GraphSnapshotCodec.FORMAT,"graph-snapshot:future"),valid.substring(0,valid.length()-1),
                valid.replace(graph.identity().value(),ContentDigest.sha256Utf8("wrong").value()))) {
            Files.writeString(file(graph),content,StandardCharsets.UTF_8);
            assertEquals(GraphStoreException.Reason.CORRUPT_SNAPSHOT,assertThrows(GraphStoreException.class,()->store.load(graph.identity())).reason());
        }
    }
    @Test void structuralDecodeLimitPreventsPublication() {
        var store=new FileGraphStore(directory,1_000_000,new GraphJson.Limits(1_000_000,2,10));var graph=graph();
        assertEquals(GraphStoreException.Reason.LIMIT_EXCEEDED,assertThrows(GraphStoreException.class,()->store.save(graph)).reason());
        assertFalse(Files.exists(file(graph)));
    }
}
