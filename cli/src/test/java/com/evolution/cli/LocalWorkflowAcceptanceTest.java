package com.evolution.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.evolution.analysis.graph.*;
import com.evolution.backend.query.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate G6 / M7.3 Acceptance Test:
 * Offline, deterministic local workflow acceptance suite.
 * Demonstrates the complete architecture intelligence lifecycle without any external
 * network connectivity, database daemon, or HTTP server:
 *
 * 1. Passive scan on actual source repository fixture -> verified graph snapshot
 * 2. Navigation & inspection (show, find, deps)
 * 3. Rule evaluation & closed-denominator findings (check, findings)
 * 4. Policy-qualified impact analysis (impact --policy)
 * 5. Visual workbench projections with compound hierarchy, cycle and violation badges (visual)
 * 6. Multi-format interoperability exports (Cytoscape.js, GraphML, SARIF, canonical JSON)
 * 7. Cyclic dependency detection & offline replay in visual workbench
 */
public class LocalWorkflowAcceptanceTest {
    @TempDir Path workspace;

    record Run(int code, String out, String err) {
        Map<String, Object> data() {
            return GraphJson.object(GraphJson.object(GraphJson.read(out.strip(), GraphJson.Limits.defaults())).get("data"));
        }
    }

    Run run(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int code = new EvoApp(workspace, new StringReader(""), new PrintWriter(out), new PrintWriter(err), false, Map.of("NO_COLOR", "1")).execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    void endToEndLocalWorkflowWithActualSourceAndPolicy() throws Exception {
        Path source = Path.of("src/test/resources/fixtures/tiny").toAbsolutePath();
        Path policyPath = source.resolve("policy.yaml");

        // Step 1: Passive scan on source repository -> verified graph snapshot
        var scan = run("scan", source.toString(), "--repo", "https://example.test/tiny.git", "--json");
        assertTrue(scan.code == 0 || scan.code == 3, scan.err);
        assertTrue(scan.data().containsKey("graph"), scan.out);
        String graphId = GraphJson.string(scan.data().get("graph"));
        assertNotNull(graphId);

        // Step 2: Show snapshot overview
        var show = run("show", "--json");
        assertTrue(show.code == 0 || show.code == 3, show.err);
        assertTrue(show.data().containsKey("items"));

        // Step 3: Find symbol
        var findRepo = run("find", "data.Repository", "--json");
        assertTrue(findRepo.code == 0 || findRepo.code == 3, findRepo.err);
        var repoItems = GraphJson.array(findRepo.data().get("items"));
        assertFalse(repoItems.isEmpty(), "Should find data.Repository");
        String repoId = GraphJson.string(GraphJson.object(repoItems.getFirst()).get("identity"));

        // Step 4: Dependencies query
        var deps = run("deps", repoId, "--incoming", "--json");
        assertTrue(deps.code == 0 || deps.code == 3, deps.err);
        assertFalse(GraphJson.array(deps.data().get("items")).isEmpty());

        // Step 5: Check policy rules
        var check = run("check", policyPath.toString(), "--json");
        assertTrue(check.code == 0 || check.code == 3, check.err);
        assertFalse(GraphJson.array(check.data().get("items")).isEmpty());

        // Step 6: Query findings
        var findings = run("findings", "--json");
        assertTrue(findings.code == 0 || findings.code == 3, findings.err);
        var findingItems = GraphJson.array(findings.data().get("items"));
        assertFalse(findingItems.isEmpty(), "Should observe architecture findings");

        // Step 7: Policy-qualified impact analysis associating incoming reachability with violated rules
        var polImpact = run("impact", repoId, "--policy", policyPath.toString(), "--json");
        assertTrue(polImpact.code == 0 || polImpact.code == 3, polImpact.err);
        assertEquals(repoId, polImpact.data().get("changedEntity"));
        var affectedRules = GraphJson.array(polImpact.data().get("affectedRules"));
        assertFalse(affectedRules.isEmpty(), "Should identify affected policy rules");
        var directFindings = GraphJson.array(polImpact.data().get("directFindings"));
        var transFindings = GraphJson.array(polImpact.data().get("transitiveFindings"));
        assertTrue(!directFindings.isEmpty() || !transFindings.isEmpty(), "Impact should associate with direct or transitive findings");

        // Step 8: Terminal report for policy impact
        var polImpactText = run("impact", repoId, "--policy", policyPath.toString());
        assertTrue(polImpactText.out.contains("Policy-qualified") || polImpactText.out.contains("Ảnh hưởng"));
        assertTrue(polImpactText.out.contains("controller.no-repository"));

        // Step 9: Visual workbench projection with policy violation badges
        var visual = run("visual", "--policy", policyPath.toString(), "--json");
        assertTrue(visual.code == 0 || visual.code == 3, visual.err);
        assertTrue(visual.data().containsKey("nodes"));
        assertTrue(visual.data().containsKey("edges"));
        var visualNodes = GraphJson.array(visual.data().get("nodes"));
        assertTrue(visualNodes.stream().map(GraphJson::object).anyMatch(n -> n.get("violationCount") != null && ((Number) n.get("violationCount")).intValue() > 0),
                "Should mark violationCount on nodes");

        // Step 10: Terminal report for visual graph
        var visualText = run("visual", "--policy", policyPath.toString());
        assertTrue(visualText.out.contains("Visual workbench") || visualText.out.contains("Phép chiếu"));
        assertTrue(visualText.out.contains("cytoscape"));

        // Step 11: Focused ego-network visual projection with depth limit
        var focused = run("visual", repoId, "--depth", "2", "--json");
        assertTrue(focused.code == 0 || focused.code == 3, focused.err);
        assertTrue(focused.data().containsKey("nodes"));
        var focusedNodes = GraphJson.array(focused.data().get("nodes"));
        assertTrue(focusedNodes.stream().map(GraphJson::object).anyMatch(n -> repoId.equals(n.get("id"))));

        // Step 12: Export Cytoscape.js format with policy badges to file
        Path cyFile = workspace.resolve("workbench-graph.cyjs.json");
        var cyExport = run("export", "cytoscape", "--policy", policyPath.toString(), "-o", cyFile.toString());
        assertTrue(cyExport.code == 0 || cyExport.code == 3, cyExport.err);
        assertTrue(Files.exists(cyFile));
        var cyTree = GraphJson.object(GraphJson.read(Files.readString(cyFile), GraphJson.Limits.defaults()));
        assertEquals("cytoscape.js", cyTree.get("format"));
        assertTrue(cyTree.containsKey("elements"));
        var elements = GraphJson.object(cyTree.get("elements"));
        assertFalse(GraphJson.array(elements.get("nodes")).isEmpty());
        assertFalse(GraphJson.array(elements.get("edges")).isEmpty());

        // Step 13: Export GraphML format to file
        Path graphmlFile = workspace.resolve("workbench-graph.graphml");
        var graphmlExport = run("export", "graphml", "-o", graphmlFile.toString());
        assertTrue(graphmlExport.code == 0 || graphmlExport.code == 3, graphmlExport.err);
        assertTrue(Files.exists(graphmlFile));
        String graphmlXml = Files.readString(graphmlFile);
        assertTrue(graphmlXml.contains("<graphml"));
        assertTrue(graphmlXml.contains("<node"));

        // Step 14: Export SARIF format to file
        Path sarifFile = workspace.resolve("findings.sarif");
        var sarifExport = run("export", "sarif", "--policy", policyPath.toString(), "-o", sarifFile.toString());
        assertTrue(sarifExport.code == 0 || sarifExport.code == 3, sarifExport.err);
        assertTrue(Files.exists(sarifFile));
        var sarifTree = GraphJson.object(GraphJson.read(Files.readString(sarifFile), GraphJson.Limits.defaults()));
        assertEquals("2.1.0", sarifTree.get("version"));
        assertFalse(GraphJson.array(GraphJson.object(GraphJson.array(sarifTree.get("runs")).getFirst()).get("results")).isEmpty());

        // Step 15: Export canonical JSON to file
        Path jsonFile = workspace.resolve("snapshot-export.json");
        var jsonExport = run("export", "json", "-o", jsonFile.toString());
        assertTrue(jsonExport.code == 0 || jsonExport.code == 3, jsonExport.err);
        assertTrue(Files.exists(jsonFile));
        assertTrue(Files.readString(jsonFile).contains("\"resultIdentity\""));
    }

    @Test
    void localWorkflowCycleDetectionAndOfflineVisualProjection() throws Exception {
        // Build an offline cyclic fixture
        var f = new CliFixture().node("ServiceA").node("ServiceB").edge("ServiceA", "ServiceB").edge("ServiceB", "ServiceA");
        Path snapshotPath = workspace.resolve("cyclic-snapshot.json");
        Files.writeString(snapshotPath, new GraphSnapshotCodec().encode(f.graph()));

        // Step 1: Open verified snapshot
        var open = run("open", snapshotPath.toString(), "--json");
        assertEquals(0, open.code, open.err);

        // Step 2: Query cycles
        var cycles = run("cycles", "--scope", "entity", "--json");
        assertEquals(0, cycles.code, cycles.err);
        var cycleItems = GraphJson.array(cycles.data().get("items"));
        assertFalse(cycleItems.isEmpty(), "Cycle should be detected");

        // Step 3: Visual workbench projection with cycle participant badges
        var visual = run("visual", "--scope", "entity", "--json");
        assertEquals(0, visual.code, visual.err);
        var nodes = GraphJson.array(visual.data().get("nodes"));
        assertTrue(nodes.stream().map(GraphJson::object).anyMatch(n -> Boolean.TRUE.equals(n.get("isCycleParticipant"))),
                "Nodes in cycle must be badged with isCycleParticipant=true");
        var edges = GraphJson.array(visual.data().get("edges"));
        assertTrue(edges.stream().map(GraphJson::object).anyMatch(e -> Boolean.TRUE.equals(e.get("isCycle"))),
                "Edges in cycle must be badged with isCycle=true");

        // Step 4: Export Cytoscape with cycle attributes preserved
        var cy = run("export", "cytoscape", "--scope", "entity");
        assertEquals(0, cy.code, cy.err);
        assertTrue(cy.out.contains("\"isCycle\":true"));
        assertTrue(cy.out.contains("cytoscape.js"));
    }
}
