package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessPlanTest {
    @Test void selectionCannotHideOrDuplicateDiscoveredContexts(){
        assertThrows(IllegalArgumentException.class,()->new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of("a","b"),List.of("a"),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of("a"),List.of("a"),List.of("a"),List.of()));
    }
    @Test void schedulingKeepsIndependentRootRunnableAndRejectsCycles(){
        var first=ReadinessPlanner.requirement(Kind.MODEL,"root-a","MODEL_REQUIRED",List.of(),List.of());
        var dependent=ReadinessPlanner.requirement(Kind.BUILD_VALIDATION,"a","BUILD_REQUIRED",List.of(first.id()),List.of());
        var independent=ReadinessPlanner.requirement(Kind.MODEL,"root-b","MODEL_REQUIRED",List.of(),List.of());
        var plan=empty(List.of(dependent,first,independent));
        assertEquals(Set.of(first.id(),independent.id()),new HashSet<>(plan.runnable(Set.of()).stream().map(Requirement::id).toList()));
        assertEquals(Set.of(dependent.id(),independent.id()),new HashSet<>(plan.runnable(Set.of(first.id())).stream().map(Requirement::id).toList()));
        var cyclic=new Requirement(first.id(),first.kind(),first.subject(),first.reason(),List.of(dependent.id()),List.of(),State.PENDING,Optional.empty());
        assertThrows(IllegalArgumentException.class,()->empty(List.of(cyclic,dependent)));
    }
    @Test void everySourceAndUnknownRawPathHasDispositionAndBuildObligation()throws Exception{
        var memory=new Memory(List.of(file("plain/src/main/java/A.java"),file("plain/src/test/java/T.java"),file("plain/src/custom/java/C.java"),file("schema.proto"),file("README.md"),file("Unknown.JAVA")));
        var planner=new ReadinessPlanner(List.of());
        var plan=planner.plan("rev",memory,memory,ReadinessPlanner.Policy.comprehensive(),()->false);
        assertEquals(6,plan.ownership().entries());assertEquals(1,plan.ownership().counts().get("UNAVAILABLE"));
        assertEquals(4,plan.ownership().counts().get("OWNED"));
        assertEquals(Set.of("main","test","custom","unclassified"),new HashSet<>(plan.contexts().stream().map(Context::sourceSet).toList()));
        assertEquals(4,plan.requirements().stream().filter(requirement->requirement.kind()==Kind.BUILD_VALIDATION).count());
        assertEquals(plan.identity(),planner.plan("rev",memory,memory,ReadinessPlanner.Policy.comprehensive(),()->false).identity());
        assertTrue(plan.gaps(SnapshotIdentity.from(memory.manifest.repository(),memory.manifest.inventory())).stream().allMatch(gap->!gap.observationReferences().isEmpty()));
        assertTrue(plan.gaps(SnapshotIdentity.from(memory.manifest.repository(),memory.manifest.inventory())).stream().filter(gap->gap.reasonCode().equals("CONTEXT_DISCOVERY_OPEN")).flatMap(gap->gap.evidenceRequirements().stream()).allMatch(requirement->requirement.authorizationClass()==com.evolution.analysis.evidence.EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION));
    }
    @Test void plannerRequiresSealAndHonorsCancellation()throws Exception{
        var memory=new Memory(List.of());var planner=new ReadinessPlanner(List.of());
        assertThrows(IOException.class,()->planner.plan("rev",memory,memory,ReadinessPlanner.Policy.comprehensive(),()->true));
        InventoryReader open=new InventoryReader(){public Page page(String revision,int size,Optional<String> cursor){throw new AssertionError();}public Optional<InventoryManifest> manifest(String revision){return Optional.empty();}};
        assertThrows(IOException.class,()->planner.plan("rev",open,memory,ReadinessPlanner.Policy.comprehensive(),()->false));
    }
    @Test void capturedExclusiveVariantsRetainBothInventoriesAndSeparateSelection()throws Exception{
        var memory=new Memory(List.of(file("android/src/free/java/F.java"),file("android/src/paid/java/P.java")));
        ReadinessDiscovery captured=(descriptors,inventory)->{
            var root=new Root("gradle:android","android",Family.GRADLE,List.of(),List.of());
            var module=new ReadinessPlan.Module("android",root.id(),"android",":app",List.of("java"),List.of(),List.of());
            var contexts=List.of("free","paid").stream().map(variant->ReadinessPlanner.context(module.id(),root.id(),"main",variant,List.of("android/src/"+variant+"/java"),Map.of("platform","android"),List.of(),inventory,true)).toList();
            return new ReadinessDiscovery.Projection(List.of(root),List.of(module),contexts,List.of(),List.of("dynamic-variants"));
        };
        var plan=new ReadinessPlanner(List.of(captured)).plan("rev",memory,memory,new ReadinessPlanner.Policy(Map.of("variant",List.of("free")),true,1024),()->false);
        assertEquals(2,plan.contexts().size());assertEquals(1,plan.selection().selected().size());assertEquals(1,plan.selection().unselected().size());
        assertEquals(2,plan.ownership().counts().get("OWNED"));assertFalse(plan.selection().unknown().isEmpty());
        assertEquals("m8.4-v1",ReadinessPlan.PROVIDER.version());
    }
    @Test void cyclicNativeModulesRemainOneJointCompilationGroup(){
        var base=empty(List.of());
        var modules=List.of(new ReadinessPlan.Module("a","root","a","a",List.of("java"),List.of("b"),List.of()),new ReadinessPlan.Module("b","root","b","b",List.of("java"),List.of("a"),List.of()),new ReadinessPlan.Module("c","other","c","c",List.of("python"),List.of(),List.of()));
        var plan=new ReadinessPlan(base.schema(),base.inventory(),base.selection(),base.roots(),modules,base.contexts(),base.requirements(),base.recipes(),base.ownership());
        assertEquals(List.of(List.of("a","b"),List.of("c")),plan.compilationGroups());
    }
    private static ReadinessPlan empty(List<Requirement> requirements){
        var memory=new Memory(List.of());var handle=new ContentHandle(ContentDigest.sha256(new byte[0]),0);
        return new ReadinessPlan(ReadinessPlan.SCHEMA,memory.manifest,new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of(),List.of(),List.of(),List.of()),List.of(),List.of(),List.of(),requirements,List.of(),new OwnershipSummary(0,Map.of(),handle));
    }
    private static InventoryEntry file(String path){return new InventoryEntry(LogicalPath.utf8(path),InventoryEntry.Kind.FILE,"100644",InventoryEntry.classify(path),InventoryEntry.Disposition.ACQUIRED,Optional.of(new ContentHandle(ContentDigest.sha256Utf8(path),path.length())),Optional.empty(),Optional.empty(),List.of(),Optional.empty());}
    private static final class Memory implements InventoryReader,RepositoryContentStore {
        final List<InventoryEntry> entries;final InventoryManifest manifest;final Map<ContentDigest,byte[]> blobs=new HashMap<>();
        Memory(List<InventoryEntry> entries){this.entries=entries;manifest=new InventoryManifest(InventoryManifest.SCHEMA,RepositoryIdentity.fromCanonicalCoordinate("urn:readiness:test"),ContentDigest.sha256Utf8("selection"),ContentDigest.sha256Utf8("inventory"),entries.size(),entries.size(),0,0,Optional.empty(),Optional.empty());}
        public Page page(String revision,int size,Optional<String> cursor){int offset=cursor.map(Integer::parseInt).orElse(0),end=Math.min(entries.size(),offset+2);return new Page(entries.subList(offset,end),end<entries.size()?Optional.of(""+end):Optional.empty());}
        public Optional<InventoryManifest> manifest(String revision){return Optional.of(manifest);}
        public ContentHandle put(InputStream input,java.util.function.BooleanSupplier cancelled)throws IOException{byte[] bytes=input.readAllBytes();var digest=ContentDigest.sha256(bytes);blobs.put(digest,bytes);return new ContentHandle(digest,bytes.length);}
        public InputStream open(ContentHandle handle){return new ByteArrayInputStream(blobs.get(handle.digest()));}
        public InputStream range(ContentHandle handle,long offset,long length){throw new UnsupportedOperationException();}
        public boolean verify(ContentHandle handle){return blobs.containsKey(handle.digest());}
    }
}
