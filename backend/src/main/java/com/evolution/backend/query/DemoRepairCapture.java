package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Validates a local operator-pinned capture. This authority is only for the sealed authored VM recipe. */
final class DemoRepairCapture {
    private static final String VERSION = "demo-repair.vm-capture:v1";
    private static final String BASE = "eclipse-temurin@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3";
    private static final List<String> TREES = List.of("baseline","candidate","control");
    private static final Set<String> CONTROLS = Set.of("Dockerfile","guest-init.sh","run.py","ProfileCheck.java");
    private static final Set<String> LIBRARIES = Set.of("spring-aop","spring-beans","spring-context","spring-core","spring-expression","spring-jcl");

    record Cell(boolean dev,boolean baseline,boolean candidate,boolean control,String change) {}
    record Proof(ContentDigest capture,ContentDigest image,ContentDigest inputs,List<String> checks,List<Cell> cells) {}
    private final ContentDigest capture, plan, proposal, image, inputs;
    private final Path root;
    private final Map<String,ContentDigest> files;
    private final Map<String,Boolean> dependencies;

    DemoRepairCapture(Path path,ContentDigest expected) throws IOException {
        var bytes = read(path,65536);
        if (!ContentDigest.sha256(bytes).equals(expected)) throw new IOException("DEMO_CAPTURE_PIN_MISMATCH");
        var json = GraphJson.object(GraphJson.read(new String(bytes,StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
        if (!Arrays.equals(bytes,CanonicalJson.write(json).getBytes(StandardCharsets.UTF_8))
                || !json.keySet().equals(Set.of("version","proposal","plan","inputs","controls","base","image","container","observed","checks","serial"))
                || !VERSION.equals(json.get("version")) || !BASE.equals(json.get("base"))
                || !(json.get("container") instanceof String id) || !id.matches("[0-9a-f]{64}"))
            throw new IOException("DEMO_CAPTURE_FORMAT");
        capture=expected; plan=digest(json,"plan"); proposal=digest(json,"proposal"); image=digest(json,"image"); inputs=digest(json,"inputs");
        var controls=GraphJson.object(json.get("controls"));
        if (!controls.keySet().equals(CONTROLS)) throw new IOException("DEMO_CAPTURE_CONTROLS");
        for (var name : CONTROLS) try (var input=DemoRepairCapture.class.getResourceAsStream("/demo-repair/"+name)) {
            if (input==null || !ContentDigest.sha256Utf8(new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n")).equals(digest(controls,name)))
                throw new IOException("DEMO_CAPTURE_CONTROL_CHANGED");
        }
        root=path.toAbsolutePath().normalize().getParent();
        var manifest=read(root.resolve("context/inputs/manifest.sha256"),65536);
        if (!inputs.equals(ContentDigest.sha256(manifest))) throw new IOException("DEMO_INPUTS_CHANGED");
        var inventory=new TreeMap<String,ContentDigest>();
        for (var line : new String(manifest,StandardCharsets.UTF_8).lines().toList()) {
            if (!line.matches("[0-9a-f]{64}  [a-zA-Z0-9./_-]{1,180}")
                    || inventory.putIfAbsent(line.substring(66),new ContentDigest("sha256:"+line.substring(0,64)))!=null)
                throw new IOException("DEMO_INPUT_MANIFEST_INVALID");
        }
        if (!digest(controls,"ProfileCheck.java").equals(inventory.remove("ProfileCheck.java"))) throw new IOException("DEMO_ORACLE_CHANGED");
        for (var name : LIBRARIES) if (inventory.remove("lib/"+name+"-6.2.0.jar")==null) throw new IOException("DEMO_CLASSPATH_INCOMPLETE");
        files=Map.copyOf(inventory);

        var serial = new String(read(root.resolve("serial.txt"),65536),StandardCharsets.UTF_8).replace("\r\n","\n");
        if (!digest(json,"serial").equals(ContentDigest.sha256Utf8(serial))) throw new IOException("DEMO_SERIAL_CHANGED");
        var observed=serial.lines().filter(line->line.startsWith("EVO_")).toList();
        if (!GraphJson.array(json.get("observed")).equals(observed) || !GraphJson.array(json.get("checks")).equals(RepairVerification.RUNTIME_CHECKS))
            throw new IOException("DEMO_CHECKS_CHANGED");
        var expectedLines=new ArrayList<>(List.of("EVO_VM_INPUTS "+inputs.value().substring(7),"EVO_VM_ISOLATION network=loopback-only disks=none"));
        var results=new LinkedHashMap<String,Boolean>();
        for (var tree:TREES) {
            expectedLines.add("EVO_CHECK "+tree+".build"); expectedLines.add("EVO_CHECK "+tree+".dev-off");
            String present="EVO_DEPENDENCY "+tree+" true", absent="EVO_DEPENDENCY "+tree+" false";
            if (observed.contains(present)==observed.contains(absent)) throw new IOException("DEMO_PROFILE_RESULT_MISSING");
            boolean dependency=observed.contains(present); results.put(tree,dependency);
            expectedLines.add(dependency?present:absent); expectedLines.add("EVO_CHECK "+tree+".dev-on");
        }
        expectedLines.add("EVO_VM_COMPLETE");
        if (!expectedLines.equals(observed)) throw new IOException("DEMO_CHECK_DENOMINATOR_OPEN");
        dependencies=Map.copyOf(results);
    }

    Optional<Proof> prove(RepairVerification.Plan expected) {
        if (!plan.equals(expected.identity()) || !proposal.equals(expected.proposal())) return Optional.empty();
        var inventory=new TreeMap<String,ContentDigest>();
        Map.of("baseline",expected.baseline(),"candidate",expected.candidate(),"control",expected.control())
                .forEach((name,tree)->tree.files().forEach(file->inventory.put(name+"/"+file.path(),file.contentDigest())));
        if (!files.equals(inventory)) return Optional.empty();
        boolean before=dependencies.get("baseline"), after=dependencies.get("candidate"), control=dependencies.get("control");
        String change = before ? (after ? "PERSISTED" : "RESOLVED") : (after ? "INTRODUCED" : "CLEAN");
        return Optional.of(new Proof(capture,image,inputs,RepairVerification.RUNTIME_CHECKS,
                List.of(new Cell(false,false,false,false,"CLEAN"),new Cell(true,before,after,control,change))));
    }

    /** Reacquire candidate bytes for static coverage. The runtime receipt alone cannot supply this operand. */
    Optional<Map<String,byte[]>> candidateSources(RepairVerification.Tree candidate) {
        try {
            var base=root.resolve("context/inputs/candidate").toRealPath();
            if (!base.startsWith(root.toRealPath())) return Optional.empty();
            var sources=new TreeMap<String,byte[]>();
            for (var file:candidate.files()) {
                var path=base.resolve(file.path()).toRealPath();
                if (!path.startsWith(base)) return Optional.empty();
                var bytes=read(path,4096);
                if (!file.contentDigest().equals(files.get("candidate/"+file.path()))
                        || !file.contentDigest().equals(ContentDigest.sha256(bytes))) return Optional.empty();
                sources.put(file.path(),bytes);
            }
            return Optional.of(Map.copyOf(sources));
        } catch (IOException unavailable) {
            return Optional.empty();
        }
    }

    private static ContentDigest digest(Map<String,Object> json,String name) {
        return new ContentDigest(GraphJson.string(json.get(name)));
    }
    private static byte[] read(Path path,int limit) throws IOException {
        try (var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=stream.readNBytes(limit+1);
            if (bytes.length>limit) throw new IOException("DEMO_CAPTURE_LIMIT");
            return bytes;
        }
    }
}
