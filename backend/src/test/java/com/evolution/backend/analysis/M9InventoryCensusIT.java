package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit, data-only M9.2 census checkpoint. Never builds or executes analyzed repositories. */
class M9InventoryCensusIT {
    @Test void everyFrozenPhysicalEntryAndDescriptorOwnerHasAnExplicitDisposition() throws Exception {
        Path registry = required("m9.registry"), output = required("m9.censusOutput");
        Path corpus = required("m9.corpusRoot").toRealPath(), evidence = required("m9.inventoryRoot").toRealPath();
        var registration = object(registry);
        var cases = GraphJson.array(registration.get("corpus"));
        assertEquals(16,cases.size());
        Files.createDirectory(output); // Never overwrite an earlier checkpoint.
        assertAll("All frozen repositories, including failed cases", cases.stream().map(raw -> (Executable) () ->
                verify(GraphJson.object(raw),corpus,evidence,output)));
    }

    private static void verify(Map<String,Object> item,Path corpus,Path evidence,Path output) throws Exception {
        String id = GraphJson.string(item.get("id"));
        Path directory = Files.createDirectory(output.resolve(id));
        Path source = locate(item.get("root"),corpus,evidence);
        var inventoryPin = GraphJson.object(item.get("inventory"));
        Path inventory = locate(inventoryPin.get("path"),corpus,evidence);
        assertEquals(inventoryPin.get("sha256"),sha(inventory));
        var contextPin = GraphJson.object(item.get("contexts"));
        Path contexts = locate(contextPin.get("path"),corpus,evidence);
        assertEquals(contextPin.get("sha256"),sha(contexts));
        var expected = new TreeMap<String,Map<String,Object>>();
        try (var reader = Files.newBufferedReader(inventory,StandardCharsets.UTF_8)) {
            for (String line;(line=reader.readLine())!=null;) {
                var entry = GraphJson.object(GraphJson.read(line,GraphJson.Limits.defaults()));
                assertNull(expected.put(GraphJson.string(entry.get("path")),entry));
            }
        }
        assertEquals(((java.math.BigDecimal)item.get("physicalEntries")).intValueExact(),expected.size());
        try (var intake = new RepositoryIntake(directory.resolve("acquisition"))) {
            var repository = RepositoryIdentity.fromCanonicalCoordinate(GraphJson.string(item.get("repository")));
            var receipt = intake.localComplete(source,id,new StreamingAcquisitionRequest(repository,Optional.empty(),
                    Boolean.TRUE.equals(item.get("dirty")),List.of(".git")),()->false);
            assertTrue(receipt.manifest().isPresent(),receipt.reason());
            var actual = new TreeMap<String,InventoryEntry>(); var allPaths = new TreeSet<String>();
            Optional<String> cursor = Optional.empty();
            do {
                var page = intake.inventory().page(id,256,cursor);
                for (var entry : page.entries()) {
                    String path = entry.path().utf8().orElseThrow();
                    assertTrue(allPaths.add(path),"Duplicate inventory path");
                    if (entry.kind()!=InventoryEntry.Kind.DIRECTORY) {
                        assertEquals(InventoryEntry.Disposition.ACQUIRED,entry.disposition(),path);
                        assertNull(actual.put(path,entry));
                    }
                }
                cursor = page.continuation();
            } while (cursor.isPresent());
            assertEquals(expected.keySet(),actual.keySet(),"Omitted and extra physical entries both fail");
            for (var label : expected.entrySet()) {
                var entry = actual.get(label.getKey());
                assertEquals("sha256:"+label.getValue().get("sha256"),entry.content().orElseThrow().digest().value(),label.getKey());
                assertEquals(((java.math.BigDecimal)label.getValue().get("bytes")).longValueExact(),entry.content().orElseThrow().size());
            }
            var plan = intake.readiness(id,ReadinessPlanner.Policy.comprehensive(),()->false);
            var ownershipPaths = new TreeSet<String>();
            try (var reader = new BufferedReader(new InputStreamReader(intake.content().open(plan.ownership().ledger()),StandardCharsets.UTF_8))) {
                for (String line;(line=reader.readLine())!=null;) {
                    var row = GraphJson.object(GraphJson.read(line,GraphJson.Limits.defaults()));
                    var path = new LogicalPath(GraphJson.string(GraphJson.object(row.get("path")).get("rawBase64"))).utf8().orElseThrow();
                    assertTrue(ownershipPaths.add(path));
                    assertNotNull(ReadinessPlan.Disposition.valueOf(GraphJson.string(row.get("disposition"))));
                }
            }
            assertEquals(allPaths,ownershipPaths,"Every source, resource, vendor, generated and unknown entry has disposition");
            var represented = new TreeSet<String>();
            plan.roots().forEach(root -> root.evidence().forEach(pin -> represented.add(pin.path())));
            plan.modules().forEach(module -> module.evidence().forEach(pin -> represented.add(pin.path())));
            var descriptors = new ArrayList<Map<String,Object>>();
            for (var raw : GraphJson.array(read(contexts))) {
                var owner = GraphJson.object(raw);
                for (var descriptor : GraphJson.array(owner.get("descriptors")))
                    descriptors.add(Map.of("path",descriptor,"disposition",represented.contains(descriptor)?"PASSIVE_MODEL_EVIDENCE":"UNREALIZED_DESCRIPTOR",
                            "effectiveContextCompleteness","UNPROVEN","generatorClosure","NOT_ESTABLISHED_BY_INVENTORY"));
            }
            var result = new TreeMap<String,Object>();
            result.put("repository",id);result.put("status","PASS_INVENTORY_ACCOUNTING_ONLY");
            result.put("physicalEntries",expected.size());result.put("accountedEntries",actual.size());
            result.put("allInventoryEntries",allPaths.size());result.put("ownership",plan.ownership());
            result.put("inventory",receipt.manifest().orElseThrow());result.put("readiness",plan.identity());
            result.put("roots",plan.roots().size());result.put("modules",plan.modules().size());result.put("contexts",plan.contexts().size());
            result.put("unknownDimensions",plan.selection().unknown().size());result.put("descriptors",descriptors);
            result.put("pendingRequirements",plan.requirements().stream().filter(r -> r.state()==ReadinessPlan.State.PENDING)
                    .map(r -> Map.of("kind",r.kind(),"subject",r.subject(),"reason",r.reason())).toList());
            result.put("semanticCorrectness","NOT_EVALUATED");
            Files.writeString(directory.resolve("reconciliation.json"),CanonicalJson.write(result),StandardOpenOption.CREATE_NEW);
            System.out.println("M9 census "+id+": "+actual.size()+"/"+expected.size()+" physical entries; "+descriptors.size()+" descriptors; "+plan.contexts().size()+" passive contexts");
        }
    }
    private static Path required(String property) {
        String value=System.getProperty(property);assertNotNull(value,"Explicit "+property+" required");return Path.of(value).toAbsolutePath().normalize();
    }
    private static Path locate(Object locator,Path corpus,Path evidence) throws IOException {
        String value=GraphJson.string(locator);int separator=value.indexOf(':');assertTrue(separator>0);
        Path root=switch(value.substring(0,separator)){case "corpus"->corpus;case "evidence"->evidence;default->throw new AssertionError("Unregistered external root");};
        Path path=root.resolve(value.substring(separator+1)).toRealPath();assertTrue(path.startsWith(root));return path;
    }
    private static String sha(Path file)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=new java.security.DigestInputStream(Files.newInputStream(file),digest)){input.transferTo(OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static Object read(Path path)throws IOException {return GraphJson.read(Files.readString(path),new GraphJson.Limits(4_000_000,64,4_000_000));}
    private static Map<String,Object> object(Path path)throws IOException {return GraphJson.object(read(path));}
}
