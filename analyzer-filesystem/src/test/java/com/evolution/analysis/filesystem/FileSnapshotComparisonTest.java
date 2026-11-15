package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evolution.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileSnapshotComparisonTest {
    @TempDir Path directory;
    private static final RepositoryIdentity REPOSITORY=RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:evolution-disk");
    private static final VersionedIdentifier PROVIDER=new VersionedIdentifier("java.fixture","1");

    @Test void coldPagesMatchTypedComparisonAndScratchIsOwnedByTheSession()throws Exception {
        var left=graph(false);var right=graph(true);var request=request(left,right);
        var archive=directory.resolve("pages");var scratch=directory.resolve("comparison");
        var writer=new PartitionedGraphStore(archive);writer.save(left,()->false);writer.save(right,()->false);
        var typed=SnapshotComparison.compare(request,EvolutionSnapshot.memory(left,right),EvolutionIndex.memory(),1,()->false);
        try(var session=FileSnapshotComparison.compare(new PartitionedGraphStore(archive),scratch,request,2,()->false)){
            var disk=session.result();assertEquals(typed.summary(),disk.summary());
            assertEquals(SnapshotComparison.Status.COMPLETE,disk.summary().status());
            for(var section:SnapshotComparison.Section.values())assertEquals(typed.page(section,10,Optional.empty()),disk.page(section,10,Optional.empty()));
            var events=disk.page(SnapshotComparison.Section.EVENTS,10,Optional.empty()).items();
            assertEquals(1,events.size());assertEquals("RELATION_ADDED",events.getFirst().get("kind"));
        }
        try(var files=Files.list(scratch)){assertEquals(0,files.count());}
    }

    @Test void missingAndCorruptPublicationsCannotProveRemoval()throws Exception {
        var a=graph(true);var b=graph(false);var archive=directory.resolve("pages");
        var writer=new PartitionedGraphStore(archive);writer.save(a,()->false);
        assertUnavailable(archive,request(a,b),"MISSING");
        writer.save(b,()->false);
        Path pointer=archive.resolve(b.identity().value().substring(7)+".partitions.json");
        Files.writeString(pointer,"{}");
        assertUnavailable(archive,request(a,b),"CORRUPT");
    }

    @Test void cancellationDoesNotLeaveAnOpenComparison()throws Exception {
        var a=graph(false);var scratch=directory.resolve("scratch");
        var store=new PartitionedGraphStore(directory.resolve("pages"));store.save(a,()->false);
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(CancellationException.class,()->FileSnapshotComparison.compare(store,scratch,request(a,a),1,
                ()->checks.incrementAndGet()>8));
        try(var paths=Files.list(scratch)){assertEquals(0,paths.count());}
    }

    @Test void durableResultReopensWithBoundCursorsAndRejectsDamagedRows() throws Exception {
        var left=graph(false);var right=graph(true);
        var request=request(left,right);
        var archive=new PartitionedGraphStore(directory.resolve("graphs"));
        archive.save(left,()->false);archive.save(right,()->false);
        var root=directory.resolve("results");
        var publication=new EvolutionResultStore(root);
        try(var session=FileSnapshotComparison.compare(archive,directory.resolve("scratch"),request,2,()->false)) {
            var result=session.result();
            var id=publication.publish(result,()->false).identity();
            assertEquals(id,publication.publish(result,()->false).identity());
            var persisted=new EvolutionResultStore(root).open(id,()->false);
            assertEquals(result.summary(),persisted.summary());
            var inputs=persisted.rows(SnapshotComparison.Section.INPUTS,0,1,()->false);
            assertEquals("LEFT",inputs.getFirst().get("side"));
            assertEquals("RIGHT",persisted.rows(SnapshotComparison.Section.INPUTS,1,1,()->false).getFirst().get("side"));
            assertEquals("RELATION_ADDED",persisted.rows(SnapshotComparison.Section.EVENTS,0,1,()->false).getFirst().get("kind"));
            assertThrows(CancellationException.class,()->publication.publish(result,()->true));
            try(var blobs=Files.walk(root.resolve("cas/sha256"))) {
                var leaf=blobs.filter(Files::isRegularFile).filter(path->{
                    try{return Files.readString(path).contains("RELATION_ADDED");}
                    catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
                }).findFirst().orElseThrow();
                Files.writeString(leaf,"{}");
            }
            assertThrows(java.io.IOException.class,()->persisted.rows(SnapshotComparison.Section.EVENTS,0,1,()->false));
        }
    }

    @Test void stagedArchiveHasNoReferenceUntilCommitAndDiskPressureCanRetry() throws Exception {
        var a = graph(false); var b = graph(true);
        var result = SnapshotComparison.compare(request(a,b),EvolutionSnapshot.memory(a,b),EvolutionIndex.memory(),20,()->false);
        var root = directory.resolve("durable");
        var space = new java.util.concurrent.atomic.AtomicLong(0);
        var content = new StreamingContentStore(root.resolve("cas"),1,
                channel->channel.force(true),space::get);
        var publication = new EvolutionResultStore(root,content);
        assertEquals("WAITING_FOR_DISK",assertThrows(java.io.IOException.class,
                ()->publication.prepare(result,Map.of(),()->false)).getMessage());
        try (var references = Files.list(root.resolve("results"))) { assertEquals(0,references.count()); }
        space.set(Long.MAX_VALUE);
        var checkpoint = publication.prepare(result,Map.of(),()->false);
        assertThrows(NoSuchFileException.class,()->publication.open(checkpoint.digest(),()->false));
        var fresh = new EvolutionResultStore(root);
        var prepared = fresh.verifyPrepared(checkpoint,()->false);
        assertThrows(CancellationException.class,()->fresh.commit(prepared,()->true));
        assertThrows(NoSuchFileException.class,()->fresh.open(checkpoint.digest(),()->false));
        try (var consumers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = consumers.submit(()->fresh.commit(prepared,()->false).identity());
            var second = consumers.submit(()->fresh.commit(prepared,()->false).identity());
            assertEquals(checkpoint.digest(),first.get());
            assertEquals(checkpoint.digest(),second.get());
        }
        assertEquals(result.summary(),fresh.open(checkpoint.digest(),()->false).summary());
    }

    @Test void reversedPublishedPairSwapsDefiniteAdditionAndRemoval() throws Exception {
        var a=graph(false);var b=graph(true);
        var input=EvolutionSnapshot.memory(a,b);
        var store=new EvolutionResultStore(directory.resolve("reverse"));
        var forward=store.publish(SnapshotComparison.compare(request(a,b),input,EvolutionIndex.memory(),1,()->false),()->false);
        var reverse=store.publish(SnapshotComparison.compare(request(b,a),input,EvolutionIndex.memory(),50,()->false),()->false);
        assertNotEquals(forward.identity(),reverse.identity());
        assertEquals("RELATION_ADDED",forward.rows(SnapshotComparison.Section.EVENTS,0,1,()->false).getFirst().get("kind"));
        assertEquals("RELATION_REMOVED",reverse.rows(SnapshotComparison.Section.EVENTS,0,1,()->false).getFirst().get("kind"));
    }

    @Test void conflictingRootsAreRetainedAndDisableAutomaticReuse() throws Exception {
        var a=graph(false);var b=graph(true);
        var request=request(a,b);
        var result=SnapshotComparison.compare(request,EvolutionSnapshot.memory(a,b),EvolutionIndex.memory(),20,()->false);
        var store=new EvolutionResultStore(directory.resolve("conflict"));
        var first=store.publish(result,()->false);
        // Simulate a conflicting publisher for the same realized comparison tuple.
        var other=store.prepare(result,Map.of("mode","STRICT"),()->false);
        assertNotEquals(first.identity(),other.digest());
        assertEquals("EVOLUTION_RESULT_CONFLICT",assertThrows(java.io.IOException.class,
                ()->store.commit(store.verifyPrepared(other,()->false),()->false)).getMessage());
        assertEquals(first.summary(),store.open(other.digest(),()->false).summary());
        assertTrue(store.findReusable(result.summary().request(),
                first.rows(SnapshotComparison.Section.INPUTS,0,2,()->false),()->false).isEmpty());
    }

    @Test void resumedScopeProducesTheUninterruptedArchive() throws Exception {
        var left=graph(false);var right=graph(true);
        var req=request(left,right);
        var graphs=new PartitionedGraphStore(directory.resolve("graphs"));
        graphs.save(left,()->false);graphs.save(right,()->false);
        var content=new StreamingContentStore(directory.resolve("checkpoints"));
        var scratch=directory.resolve("scratch");
        var saved=new java.util.concurrent.atomic.AtomicReference<com.evolution.analysis.acquisition.streaming.ContentHandle>();
        var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var reportRefs=List.of(new SnapshotComparison.ReportRef(SnapshotComparison.Side.LEFT,
                ContentDigest.sha256Utf8("pinned missing report"),left.contexts().stream()
                .filter(context->context.analysis().isPresent()).findFirst().orElseThrow().identity()));
        var reportReads=new java.util.concurrent.atomic.AtomicInteger();
        SnapshotComparison.ReportReader reports=(g,r,c)->{reportReads.incrementAndGet();return SnapshotComparison.ReportStatus.MISSING;};
        SnapshotComparison.BridgeReader bridges=(b,s,o,c)->SnapshotComparison.ReportStatus.UNAVAILABLE;
        SnapshotComparison.HistoryReader history=(p,c)->Optional.empty();
        assertThrows(CancellationException.class,()->FileSnapshotComparison.withCheckpoint(graphs::openEvolution,
                scratch,req,reportRefs,reports,List.of(),bridges,Optional.empty(),history,content,Optional.empty(),
                handle->{saved.set(handle);interrupted.set(true);},1,interrupted::get));
        assertNotNull(saved.get());
        assertEquals(0,reportReads.get());
        var archive=new EvolutionResultStore(directory.resolve("results"));
        try (var fresh=FileSnapshotComparison.compare(graphs,scratch,req,reportRefs,reports,50,()->false);
             var resumed=FileSnapshotComparison.withCheckpoint(graphs::openEvolution,scratch,req,
                     reportRefs,reports,List.of(),bridges,Optional.empty(),history,content,Optional.of(saved.get()),handle->{},2,()->false)) {
            assertEquals(2,reportReads.get());
            assertEquals(fresh.result().summary(),resumed.result().summary());
            assertEquals(archive.publish(fresh.result(),()->false).identity(),archive.publish(resumed.result(),()->false).identity());
        }
    }

    private void assertUnavailable(Path archive,SnapshotComparison.Request request,String reason)throws Exception {
        try(var session=FileSnapshotComparison.compare(new PartitionedGraphStore(archive),directory.resolve("scratch"),request,2,()->false)){
            var result=session.result();assertNotEquals(SnapshotComparison.Status.COMPLETE,result.summary().status());
            assertEquals(0,result.summary().census(SnapshotComparison.Section.EVENTS).rows());
            var rows=result.page(SnapshotComparison.Section.CONTEXTS,10,Optional.empty()).items();
            assertTrue(rows.stream().anyMatch(row->"RIGHT".equals(row.get("side"))&&reason.equals(row.get("reason"))));
        }
    }

    static SnapshotComparison.Request request(KnowledgeGraph left,KnowledgeGraph right) {
        return new SnapshotComparison.Request(REPOSITORY,left.identity(),right.identity(),SnapshotComparison.Mode.OBSERVATION,
                new SnapshotComparison.Selection(Set.of("MAIN"),Set.of("TYPE"),Set.of("graph.depends-on")));
    }

    private static KnowledgeGraph graph(boolean relation) {
        return graph(relation, 2);
    }

    static KnowledgeGraph graph(boolean relation, int typeCount) {
        var module=ModuleDescriptor.create(REPOSITORY,"fixture",".");
        String src=relation?"class A { B field; }\nclass B {}\n":"class A {}\nclass B {}\n";
        var doc=SourceDocument.create(REPOSITORY,module,"A.java",ContentDigest.sha256Utf8(src),SourceClassification.MAIN);
        var documents = new ArrayList<>(List.of(doc));
        // Keep authored source spans real and place the additional types in bounded packages.
        final int typesPerPackage = 128;
        for (int start = 2; start < typeCount; start += typesPerPackage) {
            var packageName = "fixture" + (start - 2) / typesPerPackage;
            var source = new StringBuilder("package ").append(packageName).append(";\n");
            for (int i = start; i < Math.min(typeCount, start + typesPerPackage); i++)
                source.append("class T").append(i).append(" {}\n");
            documents.add(SourceDocument.create(REPOSITORY, module, packageName + "/Types.java",
                    ContentDigest.sha256Utf8(source.toString()), SourceClassification.MAIN));
        }
        var snapshot=RepositorySnapshot.create(REPOSITORY,Optional.empty(),false,
                documents.stream().map(SnapshotFile::from).toList(),documents);
        var jdk=ContentDigest.sha256Utf8("fixture platform");var tool=new ManifestComponent(PROVIDER,ContentDigest.sha256Utf8("fixture tool"));
        var config=AnalysisConfiguration.create(PROVIDER,Map.of("java.module",module.identity().value(),"java.source-set","MAIN",
                "java.platform.symbol-digest",jdk.value(),"java.source-plan",CanonicalJson.sha256(documents.stream().sorted().toList()).value()));
        var manifest=AnalysisManifest.create(PROVIDER,snapshot,List.of(module),List.of(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,"java.base",jdk)),
                config,tool,tool,KnowledgeGraph.schemaComponent());
        var derivation=new Derivation(DerivationKind.DIRECT,PROVIDER,List.of());
        var declarations=new ArrayList<DeclarationRecord>();
        for(int i=0;i<typeCount;i++){
            String name=i==0?"A":i==1?"B":"T"+i;
            int group = i < 2 ? -1 : (i - 2) / typesPerPackage;
            var source = documents.get(group + 1);
            int line = i < 2 ? i + 1 : 2 + (i - 2) % typesPerPackage;
            var entity=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE,
                    JavaSymbolName.topLevelType(group < 0 ? "" : "fixture" + group,name).canonicalName(),
                    Optional.of(new SourceSpan(source.identity(),line,7,line,7+name.length())));
            declarations.add(new DeclarationRecord(entity,name,SemanticStatus.RESOLVED,derivation,List.of(),List.of()));
        }
        var kind=new RelationshipKind("java.field-type");var a=declarations.getFirst().entity();var b=declarations.getLast().entity();
        var span=new SourceSpan(doc.identity(),1,11,1,12);
        var occurrence=RelationshipOccurrence.create(SemanticRelationship.create(a.identity(),kind,new RelationshipTarget.Resolved(b.identity())),
                span,0,SemanticStatus.RESOLVED,derivation,List.of(),List.of());
        var observation=new ObservationRecord(doc.identity(),kind,Optional.of(span),SemanticStatus.RESOLVED,
                ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(occurrence.identity()),"B",List.of());
        var coverage=FrontendRequest.CATEGORIES.stream().map(c->{var key=new RelationshipKind("java."+c);
            long n=relation&&key.equals(kind)?1:0;return new CategoryCoverage(key,CategoryCoverage.Support.IMPLEMENTED,n,n,0);}).toList();
        var result=new FrontendResult(manifest.identity(),PROVIDER,FrontendResult.State.COMPLETED,declarations,
                relation?List.of(occurrence):List.of(),relation?List.of(observation):List.of(),
                documents.stream().map(d->new SourceOutcome(d.identity(),SourceOutcome.State.PROCESSED,List.of())).toList(),
                coverage,List.of());
        return new CanonicalGraphBuilder().build(manifest,result);
    }
}
