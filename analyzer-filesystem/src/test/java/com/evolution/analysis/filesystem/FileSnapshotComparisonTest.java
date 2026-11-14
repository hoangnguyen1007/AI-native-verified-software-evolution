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

    private void assertUnavailable(Path archive,SnapshotComparison.Request request,String reason)throws Exception {
        try(var session=FileSnapshotComparison.compare(new PartitionedGraphStore(archive),directory.resolve("scratch"),request,2,()->false)){
            var result=session.result();assertNotEquals(SnapshotComparison.Status.COMPLETE,result.summary().status());
            assertEquals(0,result.summary().census(SnapshotComparison.Section.EVENTS).rows());
            var rows=result.page(SnapshotComparison.Section.CONTEXTS,10,Optional.empty()).items();
            assertTrue(rows.stream().anyMatch(row->"RIGHT".equals(row.get("side"))&&reason.equals(row.get("reason"))));
        }
    }

    private static SnapshotComparison.Request request(KnowledgeGraph left,KnowledgeGraph right) {
        return new SnapshotComparison.Request(REPOSITORY,left.identity(),right.identity(),SnapshotComparison.Mode.OBSERVATION,
                new SnapshotComparison.Selection(Set.of("MAIN"),Set.of("TYPE"),Set.of("graph.depends-on")));
    }

    private static KnowledgeGraph graph(boolean relation) {
        var module=ModuleDescriptor.create(REPOSITORY,"fixture",".");
        String src=relation?"class A { B field; }\nclass B {}\n":"class A {}\nclass B {}\n";
        var doc=SourceDocument.create(REPOSITORY,module,"A.java",ContentDigest.sha256Utf8(src),SourceClassification.MAIN);
        var snapshot=RepositorySnapshot.create(REPOSITORY,Optional.empty(),false,List.of(SnapshotFile.from(doc)),List.of(doc));
        var jdk=ContentDigest.sha256Utf8("fixture platform");var tool=new ManifestComponent(PROVIDER,ContentDigest.sha256Utf8("fixture tool"));
        var config=AnalysisConfiguration.create(PROVIDER,Map.of("java.module",module.identity().value(),"java.source-set","MAIN",
                "java.platform.symbol-digest",jdk.value(),"java.source-plan",CanonicalJson.sha256(List.of(doc)).value()));
        var manifest=AnalysisManifest.create(PROVIDER,snapshot,List.of(module),List.of(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,"java.base",jdk)),
                config,tool,tool,KnowledgeGraph.schemaComponent());
        var derivation=new Derivation(DerivationKind.DIRECT,PROVIDER,List.of());
        var declarations=new ArrayList<DeclarationRecord>();
        for(int i=0;i<2;i++){
            String name=i==0?"A":"B";
            var entity=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE,
                    JavaSymbolName.topLevelType("",name).canonicalName(),Optional.of(new SourceSpan(doc.identity(),i+1,7,i+1,8)));
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
                List.of(new SourceOutcome(doc.identity(),SourceOutcome.State.PROCESSED,List.of())),coverage,List.of());
        return new CanonicalGraphBuilder().build(manifest,result);
    }
}
