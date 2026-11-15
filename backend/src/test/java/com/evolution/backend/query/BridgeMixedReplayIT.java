package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.evolution.EvolutionIndex;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Data-only direct consumer of the real authored mixed capture. No SDK, build or source execution. */
class BridgeMixedReplayIT {
    @Test void evolutionKeepsNativeTierDriftAndLostEndpointEvidenceUnknown(@TempDir Path scratch) throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("bridge.fixtureRoot"))).toRealPath();
        var capture = new StreamingContentStore(root.resolve("cas"));
        var tables = new SemanticTables(capture,4_000_000);
        var policy = new BridgeReadPolicy(root.resolve("read-policy.json"));
        ContentDigest scope;
        try (var heads = Files.list(root.resolve("bridges"))) {
            var name = heads.map(p -> p.getFileName().toString()).filter(n -> n.matches("[a-f0-9]{64}\\.json"))
                    .findFirst().orElseThrow();
            scope = new ContentDigest("sha256:"+name.substring(0,64));
        }
        var original = new BridgePublicationStore(root.resolve("bridges"),capture,tables,policy::policy,policy::revoked);
        var publication = original.head(scope).orElseThrow();
        var record = object(capture,publication);
        var input = ArchitectureBridges.input(record.get("input"));
        var bound = ArchitectureBridges.result(record.get("result"));
        assertTrue(bound.counts().getOrDefault("BOUND",0L)>0);

        Map<String,Object> snapshot = null;
        ArchitectureBridges.Partition worker = null;
        try (var rows = tables.open(input.partitions(),()->false)) {
            for (var entry = rows.next(); entry != null; entry = rows.next()) {
                var part = ArchitectureBridges.partition(entry.fields());
                if (entry.key().equals("backend")) {
                    var nativeCapture = object(capture,part.capture().orElseThrow());
                    snapshot = GraphJson.object(object(capture,handle(nativeCapture.get("manifest"))).get("snapshot"));
                }
                if (entry.key().equals("worker")) worker=part;
            }
        }
        assertNotNull(snapshot);
        assertNotNull(worker);
        var child = worker.child().orElseThrow();
        var nativeRequest = LanguageSemanticRequest.from(object(capture,child.request()));
        SemanticTables.Row call = null;
        try (var rows = tables.open(child.facts(),()->false)) {
            for (var entry = rows.next(); entry != null; entry = rows.next()) {
                var fields = entry.fields();
                if ("call".equals(fields.get("role")) && "RESOLVED".equals(fields.get("status"))
                        && "worker/remote.py".equals(GraphJson.object(fields.get("span")).get("path"))) {
                    call = entry;
                    break;
                }
            }
        }
        assertNotNull(call);
        assertEquals(1,GraphJson.array(call.fields().get("targets")).size());
        var bytes = sourceBytes(capture,tables,worker.documents(),"worker/remote.py");
        var syntaxCalls = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var syntax = new TreeSitterSourceFrontend().parse("python",bytes,fact -> {
            if ("CALL".equals(fact.category()) && "decorate".equals(fact.written())) syntaxCalls.add(fact);
        },()->false);
        assertEquals(1,syntaxCalls.size());
        var nativeSpan = GraphJson.object(call.fields().get("span"));
        assertEquals(((Number)nativeSpan.get("start")).longValue(),syntaxCalls.getFirst().span().start());
        assertEquals(((Number)nativeSpan.get("end")).longValue(),syntaxCalls.getFirst().span().end());
        assertNotEquals(nativeRequest.tool().toString(),syntax.toolIdentity());
        assertFalse(TreeSitterSourceFrontend.provider().startsWith(nativeRequest.provider().id()+":"));

        var repository = new RepositoryIdentity(GraphJson.string(snapshot.get("repository")));
        var identity = new SnapshotIdentity(GraphJson.string(snapshot.get("identity")));
        assertEquals(input.root().snapshotIdentity(),identity);
        var rootEvidence = GraphEvidence.capture("repository.snapshot",snapshot);
        var context = new KnowledgeGraph.Context(identity.value(),identity,Optional.empty(),rootEvidence.identity());
        var syntaxEvidence = GraphEvidence.capture("polyglot.source-syntax",Map.of("fact",syntaxCalls.getFirst(),
                "provider",TreeSitterSourceFrontend.provider(),"tool",syntax.toolIdentity(),"trust","SYNTAX_ONLY"));
        var capturedCall = GraphEvidence.capture("polyglot.observations",call);
        var nativeEvidence = GraphEvidence.capture("polyglot.context",Map.of("request",nativeRequest,"trust","PRODUCER_ASSERTION"));
        var nativeContext = new KnowledgeGraph.Context(nativeRequest.scope().value(),identity,Optional.empty(),nativeEvidence.identity());
        var left = new KnowledgeGraph(identity,List.of(context),List.of(),List.of(),List.of(rootEvidence,syntaxEvidence));
        var right = new KnowledgeGraph(identity,List.of(context,nativeContext),List.of(),List.of(),
                List.of(rootEvidence,capturedCall,nativeEvidence));
        var selection = new SnapshotComparison.Selection(Set.of(),Set.of(),Set.of(),false,true);
        var request = new SnapshotComparison.Request(repository,left.identity(),right.identity(),SnapshotComparison.Mode.OBSERVATION,selection);
        var hidden = input.endpoints().content().digest();
        var complete = bridgeOverlay(scratch.resolve("complete"),capture,policy,Optional.empty());
        var degraded = bridgeOverlay(scratch.resolve("without-endpoints"),capture,policy,Optional.of(hidden));
        var refs = List.of(new SnapshotComparison.BridgeRef(SnapshotComparison.Side.LEFT,publication),
                new SnapshotComparison.BridgeRef(SnapshotComparison.Side.RIGHT,publication));
        var result = SnapshotComparison.compare(request,EvolutionSnapshot.memory(left,right),List.of(),
                (g,r,c)->SnapshotComparison.ReportStatus.UNAVAILABLE,refs,
                (ref,s,o,c)->(ref.side()==SnapshotComparison.Side.LEFT ? complete : degraded).readEvolution(ref,s,o,c),
                EvolutionIndex.memory(),10,()->false);
        assertEquals(SnapshotComparison.Status.PARTIAL,result.summary().status());
        assertEquals(0,result.summary().census(SnapshotComparison.Section.EVENTS).rows());
        var inputs = result.page(SnapshotComparison.Section.INPUTS,20,Optional.empty()).items();
        assertTrue(inputs.stream().anyMatch(r -> "BRIDGE_PUBLICATION".equals(r.get("kind")) && "RIGHT".equals(r.get("side"))
                && "UNAVAILABLE".equals(r.get("status"))));
        var compatibility = result.page(SnapshotComparison.Section.COMPATIBILITY,20,Optional.empty()).items();
        assertTrue(compatibility.stream().anyMatch(r -> GraphJson.array(r.get("reasons")).contains("BRIDGE_OPERAND_UNAVAILABLE")));
        var contexts = result.page(SnapshotComparison.Section.CONTEXTS,20,Optional.empty()).items();
        assertTrue(contexts.stream().anyMatch(r -> nativeContext.identity().equals(r.get("context"))
                && "RIGHT_ONLY".equals(r.get("disposition")) && "python".equals(r.get("language"))));
        var evidence = new ArrayList<Map<String,Object>>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = result.page(SnapshotComparison.Section.EVIDENCE,20,cursor);
            evidence.addAll(page.items());
            cursor = page.continuation();
        } while(cursor.isPresent());
        var canonical = CanonicalJson.write(evidence);
        assertTrue(canonical.contains(syntaxEvidence.identity().value()));
        assertTrue(canonical.contains(capturedCall.identity().value()));
        assertTrue(canonical.contains(nativeEvidence.identity().value()));
        assertTrue(canonical.contains("\"runtimeObserved\":false"));
        assertFalse(canonical.contains("\"runtimeObserved\":true"));
        assertTrue(evidence.stream().allMatch(r -> Boolean.FALSE.equals(r.get("semanticAbsenceProved"))));
        assertTrue(evidence.stream().anyMatch(r -> "OPPOSITE_BRIDGE_NOT_VERIFIED".equals(r.get("disposition"))));
    }

    private static byte[] sourceBytes(RepositoryContentStore content, SemanticTables tables, SemanticTables.Table source,
                                      String path) throws IOException {
        try (var rows = tables.open(source,()->false)) {
            for (var row = rows.next(); row != null; row = rows.next()) {
                if (!path.equals(row.key())) continue;
                var bytes = handle(row.fields().get("content"));
                assertTrue(content.verify(bytes));
                try (var stream = content.open(bytes)) { return stream.readAllBytes(); }
            }
        }
        throw new IOException("Missing captured source");
    }

    private static BridgePublicationStore bridgeOverlay(Path path, RepositoryContentStore capture,
                                                        BridgeReadPolicy policy, Optional<ContentDigest> hidden) throws IOException {
        Files.createDirectory(path);
        var writable = new StreamingContentStore(path.resolve("cas"));
        RepositoryContentStore overlay = new RepositoryContentStore() {
            private RepositoryContentStore selected(ContentHandle handle) throws IOException {
                if (hidden.filter(handle.digest()::equals).isPresent()) throw new NoSuchFileException("Hidden endpoint table");
                return capture.verify(handle) ? capture : writable;
            }
            public boolean verify(ContentHandle handle) throws IOException {
                return hidden.filter(handle.digest()::equals).isEmpty() && (capture.verify(handle) || writable.verify(handle));
            }
            public InputStream open(ContentHandle handle) throws IOException { return selected(handle).open(handle); }
            public InputStream range(ContentHandle handle,long offset,long length) throws IOException {
                return selected(handle).range(handle,offset,length);
            }
            public ContentHandle put(InputStream input,BooleanSupplier cancelled) throws IOException {
                return writable.put(input,cancelled);
            }
        };
        return new BridgePublicationStore(path.resolve("bridges"),overlay,new SemanticTables(overlay,4_000_000),
                policy::policy,policy::revoked);
    }

    @Test void evolutionReplaysPinnedNativeOperandsFromExistingCapture()throws Exception {
        String configured=System.getProperty("bridge.fixtureRoot");assertNotNull(configured,"Explicit existing mixed capture required");
        Path root=Path.of(configured).toRealPath();
        var content=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(content,4_000_000);
        var policy=new BridgeReadPolicy(root.resolve("read-policy.json"));
        List<Path> heads;try(var files=Files.list(root.resolve("bridges"))){
            heads=files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList();
        }
        assertEquals(1,heads.size());
        var scope=new ContentDigest("sha256:"+heads.getFirst().getFileName().toString().substring(0,64));
        var store=new BridgePublicationStore(root.resolve("bridges"),content,tables,policy::policy,policy::revoked);
        var ref=new SnapshotComparison.BridgeRef(SnapshotComparison.Side.LEFT,store.head(scope).orElseThrow());
        com.evolution.analysis.contract.identity.SnapshotIdentity snapshot;
        try(var stream=content.open(ref.publication())) {
            var record=GraphJson.object(GraphJson.read(new InputStreamReader(stream,StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
            snapshot=ArchitectureBridges.input(record.get("input")).root().snapshotIdentity();
        }
        var counts=new TreeMap<String,Long>();var qualification=new ArrayList<Object>();
        assertEquals(SnapshotComparison.ReportStatus.AVAILABLE,store.readEvolution(ref,snapshot,(family,id,fields)->{
            counts.merge(family,1L,Long::sum);
            if(family.equals("evaluation"))qualification.add(fields.get("qualified"));
        },()->false));
        assertEquals(8L,counts.get("partitions"));
        assertTrue(counts.get("endpoints")>0);
        assertTrue(counts.get("relations")>0);
        assertEquals(List.of(false),qualification);
    }
    @Test void wholeSnapshotCoverageRecoveryWithdrawalAndTopologySurviveColdReplay()throws Exception{
        String configured=System.getProperty("bridge.fixtureRoot");assertNotNull(configured,"Explicit existing mixed capture required");Path root=Path.of(configured).toRealPath();var content=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(content,4_000_000);var policy=new BridgeReadPolicy(root.resolve("read-policy.json"));
        List<Path> heads;try(var files=Files.list(root.resolve("bridges"))){heads=files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList();}assertEquals(1,heads.size());var scope=new ContentDigest("sha256:"+heads.getFirst().getFileName().toString().substring(0,64));
        var store=new BridgePublicationStore(root.resolve("bridges"),content,tables,policy::policy,policy::revoked);var view=store.load(scope,()->false).orElseThrow();assertEquals(8,view.result().partitions());assertEquals(0,view.result().missingCensus());assertEquals(2L,view.result().counts().get("REOPENED"));assertFalse(view.result().qualified());
        var actual=new TreeMap<String,String>();Map<String,Object> snapshot=null;
        try(var rows=tables.open(view.input().partitions(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null){var part=ArchitectureBridges.partition(row.fields());try(var documents=tables.open(part.documents(),()->false)){SemanticTables.Row document;while((document=documents.next())!=null){String digest=handle(document.fields().get("content")).digest().value();String previous=actual.putIfAbsent(document.key(),digest);assertTrue(previous==null||previous.equals(digest),"Contradictory physical bytes across contexts");}}
            if(row.key().equals("backend")){var capture=object(content,part.capture().orElseThrow());snapshot=GraphJson.object(object(content,handle(capture.get("manifest"))).get("snapshot"));}
        }}assertNotNull(snapshot);assertEquals(view.input().root().snapshotIdentity().value(),snapshot.get("identity"));var expected=new TreeMap<String,String>();for(Object value:GraphJson.array(snapshot.get("documents"))){var doc=GraphJson.object(value);expected.put(GraphJson.string(doc.get("path")),GraphJson.string(doc.get("contentDigest")));}
        assertEquals(Integer.getInteger("bridge.expectedSources",32).intValue(),expected.size());for(var entry:expected.entrySet())assertEquals(entry.getValue(),actual.get(entry.getKey()),"Missing/changed inventoried source "+entry.getKey());
        assertEquals("SAVED_UNVERIFIED",store.queries().catalog(Optional.empty(),5,()->false).items().getFirst().availability());
        var named=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("library"),Optional.empty(),Optional.empty(),Optional.of("upper")),50,Optional.empty(),()->false);assertFalse(named.items().isEmpty(),"Displayed native symbol names must be searchable");
        var calls=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.of("A06"),Optional.of("worker"),Optional.empty(),Optional.of("worker/remote.py")),50,Optional.empty(),()->false);var resolvedCall=calls.items().stream().filter(row->"call".equals(row.fields().get("role"))&&"RESOLVED".equals(row.fields().get("status"))).findFirst().orElseThrow();
        var gaps=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.NATIVE_GAPS,Optional.of("A06"),Optional.of("worker"),Optional.empty(),Optional.of("worker/remote.py")),50,Optional.empty(),()->false);assertTrue(gaps.items().stream().noneMatch(row->resolvedCall.fields().get("obligation").equals(GraphJson.object(GraphJson.object(row.fields().get("gap")).get("subject")).get("identity"))),"Only the recovered call obligation closes; unrelated native uncertainties stay visible");
        var broken=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("broken"),Optional.empty()),50,Optional.empty(),()->false);assertTrue(broken.items().stream().anyMatch(row->"ERROR".equals(row.fields().get("status"))));
        Map<String,Object> contract=null;try(var rows=tables.open(view.result().relations(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null){var fields=row.fields();if("EXPOSES_CONTRACT".equals(fields.get("relation"))&&!GraphJson.array(fields.get("targets")).isEmpty())contract=GraphJson.object(GraphJson.array(fields.get("targets")).getFirst());}}assertNotNull(contract);
        var topology=store.queries().topology(scope,new ContentDigest(GraphJson.string(contract.get("identity"))),3,20,1000,()->false);assertTrue(topology.boundaries().stream().anyMatch(boundary->"backend".equals(boundary.from().get("partition"))));assertFalse(topology.exhaustive());assertFalse(topology.impact().runtimeBreakageProven());
        System.out.println("Mixed cold replay: "+expected.size()+"/"+expected.size()+" M1 sources, 8 contexts, native recovery closed, broken sibling retained, 2 reopened bindings, qualified topology PASS");
    }
    private static ContentHandle handle(Object value){var map=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),((java.math.BigDecimal)map.get("size")).longValueExact());}
    private static Map<String,Object> object(RepositoryContentStore content,ContentHandle handle)throws IOException{assertTrue(content.verify(handle));try(var input=content.open(handle)){return GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(64_000_000,64,64_000_000)));}}
}
