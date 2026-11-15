package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.EvolutionRequest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionCliTest {
    @TempDir Path directory;

    @Test void compareInspectAndBothExportsHaveSamePinnedDegradedResult() throws Exception {
        var workspace = directory.resolve("alternate");
        var fixture = new CliFixture().node("A").graph();
        new Workspace(workspace).publish(fixture);
        var missing = ContentDigest.sha256Utf8("target not captured");
        var request = new EvolutionRequest(EvolutionRequest.VERSION,new Request(
                RepositoryIdentity.fromCanonicalCoordinate("https://example.test/evo.git"),fixture.identity(),missing,
                Mode.STRICT,new Selection(Set.of("MAIN"),Set.of("TYPE"),Set.of())),List.of(),List.of(),Optional.empty());
        var file = Files.writeString(directory.resolve("request.json"), CanonicalJson.write(request));
        var compared = run("-w",workspace.toString(),"evolution","compare","--request",file.toString());
        var id = GraphJson.string(compared.get("result"));
        assertEquals(compared,run("-w",workspace.toString(),"evolution","inspect",id));
        var first = run("-w",workspace.toString(),"evolution","inspect",id,"--section","INPUTS","--size","1");
        var cursor = GraphJson.string(first.get("continuation"));
        var args = new String[]{"-w",workspace.toString(),"evolution","inspect",id,"--section","INPUTS","--size","1","--cursor",cursor};
        var second = run(args);
        args[3] = "export";
        assertEquals(second,run(args).get("page"));
        var out = directory.resolve("evolution.json");
        var receipt = run("-w",workspace.toString(),"evolution","export",id,"--out",out.toString());
        assertEquals(ContentDigest.sha256(Files.readAllBytes(out)).value(),receipt.get("exportDigest"));
        var exported = parse(Files.readString(out));
        assertEquals(compared,exported.get("summary"));
        var inputs = GraphJson.array(GraphJson.object(exported.get("sections")).get("INPUTS"));
        assertEquals("MISSING",GraphJson.object(inputs.getLast()).get("status"));
        assertEquals(CanonicalJson.write(exported),Files.readString(out));
    }

    private Map<String,Object> run(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        var cli = new EvoApp(directory,new StringReader(""),new PrintWriter(out),new PrintWriter(err),false,Map.of());
        assertEquals(3,cli.execute(args),err.toString());
        var envelope = parse(out.toString());
        assertEquals(CanonicalJson.sha256(envelope.get("data")).value(),envelope.get("resultIdentity"));
        return GraphJson.object(envelope.get("data"));
    }

    private static Map<String,Object> parse(String json) {
        return GraphJson.object(GraphJson.read(json,GraphJson.Limits.defaults()));
    }
}
