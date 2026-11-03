package com.evolution.cli;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BridgeCliTest {
    @TempDir Path directory;
    record Output(int code,String out,String err){}
    Output run(String... args){var out=new StringWriter();var err=new StringWriter();int code=new EvoApp(directory,new StringReader(""),new PrintWriter(out,true),new PrintWriter(err,true),false,Map.of()).execute(args);return new Output(code,out.toString(),err.toString());}
    @Test void cliPublishSummaryAndExportUseTheSameImmutableServiceAndFence()throws Exception{
        var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,65536);var inventory=ContentDigest.sha256Utf8("empty authored cli inventory");var root=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:bridge-cli"),inventory));var policy=ContentDigest.sha256Utf8("read policy");var input=new ArchitectureBridges.Input(root,inventory,policy,tables.write(SemanticTables.Kind.CONTEXT,Collections.emptyIterator(),()->false),tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),()->false),tables.write(SemanticTables.Kind.CENSUS,Collections.emptyIterator(),()->false));var descriptor=directory.resolve("input.json");Files.writeString(descriptor,CanonicalJson.write(input));var readPolicy=directory.resolve("read-policy.json");Files.writeString(readPolicy,CanonicalJson.write(Map.of("schema","architecture-bridge-read-policy:m8.4-v1","policy",policy,"revoked",List.of())));
        var publish=run("bridges","publish","--bridge-store",directory.toString(),"--read-policy",readPolicy.toString(),"--bridge-input",descriptor.toString(),"--json");assertEquals(3,publish.code(),publish.err());assertTrue(publish.err().isEmpty());assertEquals(1,run("bridges","publish","--bridge-store",directory.toString(),"--read-policy",readPolicy.toString(),"--bridge-input",descriptor.toString(),"--json").code());
        var policyView=new BridgeReadPolicy(readPolicy);var store=new BridgePublicationStore(directory.resolve("bridges"),content,tables,policyView::policy,policyView::revoked);var summary=run("bridges","summary","--bridge-store",directory.toString(),"--read-policy",readPolicy.toString(),"--bridge-scope",input.scope().value(),"--json");assertEquals(3,summary.code(),summary.err());var envelope=GraphJson.object(GraphJson.read(summary.out(),GraphJson.Limits.defaults()));assertEquals(CanonicalJson.write(store.queries().summary(input.scope(),()->false)),CanonicalJson.write(envelope.get("data")));assertTrue(summary.out().contains("WITHHELD"));assertTrue(summary.out().contains("\"qualified\":false"));
        var topology=run("bridges","topology","--bridge-store",directory.toString(),"--read-policy",readPolicy.toString(),"--bridge-scope",input.scope().value(),"--target-symbol",ContentDigest.sha256Utf8("scoped symbol").value(),"--json");assertEquals(3,topology.code(),topology.err());assertTrue(topology.out().contains("\"boundaries\":[]"));assertTrue(topology.out().contains("\"exhaustive\":false"));
        var page=run("bridges","page","--bridge-store",directory.toString(),"--read-policy",readPolicy.toString(),"--bridge-scope",input.scope().value(),"--section","GAPS","--json");assertEquals(3,page.code(),page.err());assertTrue(page.out().contains("BRIDGE_METHOD_UNQUALIFIED"));
    }
    @Test void readOnlyMissingStoreAndHelpNeverCreateEvidenceOrStartAWorker()throws Exception{
        assertEquals(0,run("bridges","--help").code());var missing=directory.resolve("missing");assertEquals(1,run("bridges","summary","--bridge-store",missing.toString(),"--read-policy",directory.resolve("no-policy").toString(),"--bridge-scope",ContentDigest.sha256Utf8("scope").value(),"--json").code());assertFalse(Files.exists(missing));
        assertEquals(2,run("bridges","summary","--bridge-store",missing.toString(),"--read-policy","missing","--bridge-scope",ContentDigest.sha256Utf8("scope").value(),"--category","B06","--json").code());
    }
}
