package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpringBridgeImportTest {
    @TempDir Path directory;
    @Test void realFrameworkRouteOwnershipReachesNeutralEndpointsWithoutInventingActivationOrContract()throws Exception{
        var text="package app; @org.springframework.web.bind.annotation.RestController class Users { @org.springframework.web.bind.annotation.GetMapping(\"/users\") String users(){return \"ok\";} }";
        var fixture=UniversalSpringSemanticsTest.fixture(Map.of("fixture/Users.java",text),false);var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,1_000_000);var bytes=content.put(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false);
        var result=new SpringBridgeImport(content,tables).read("backend",fixture.source().manifest().snapshot().contentDigest(),fixture.source(),Map.of("fixture/Users.java",new SpringBridgeImport.CapturedSource(bytes,"UTF-8")),Map.of(),100,1_000_000,()->false);
        var endpoints=new ArrayList<ArchitectureBridges.Endpoint>();try(var rows=tables.open(result.endpoints(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null)endpoints.add(ArchitectureBridges.endpoint(row.fields()));}
        assertEquals(Set.of("GET","HEAD"),new TreeSet<>(endpoints.stream().map(endpoint->endpoint.descriptor().get("method")).toList()));assertTrue(endpoints.stream().allMatch(endpoint->endpoint.descriptor().get("path").equals("/users")));assertTrue(endpoints.stream().allMatch(endpoint->endpoint.condition().unknown()));assertTrue(endpoints.stream().allMatch(endpoint->!endpoint.descriptor().containsKey("contract")&&!endpoint.descriptor().containsKey("service")));assertTrue(endpoints.stream().allMatch(endpoint->endpoint.source().range().isPresent()));
        assertEquals(4,endpoints.size());assertEquals(2,endpoints.stream().filter(endpoint->"EXPOSES".equals(endpoint.descriptor().get("bindingRole"))&&endpoint.direction().equals("REQUIRES")).count());
        assertTrue(result.requirements().rows()>=8);assertEquals(fixture.source().manifest().snapshot().identity().value(),ArchitectureBridges.partition(result.partition().fields()).snapshot());
        var inventory=fixture.source().manifest().snapshot().contentDigest();var root=com.evolution.analysis.evidence.EvidenceContext.forSnapshot(fixture.source().manifest().snapshot().identity());var policy=ContentDigest.sha256Utf8("scoped Spring producer fixture");ArchitectureBridges.Input input;try(var index=new SqliteSemanticCensusIndex(directory.resolve("assembly.sqlite"),1_000_000)){input=new BridgeAssembly(tables,100).assemble(root,inventory,policy,List.of(new BridgeAssembly.Fragment(result.partition(),result.endpoints(),result.requirements())).iterator(),index,()->false);}var store=new BridgePublicationStore(directory.resolve("bridges"),content,tables,()->policy,Set::of);var published=store.commit(input,Optional.empty(),()->false);assertEquals(0L,published.result().counts().get("BOUND"));assertEquals(0,published.result().missingCensus());assertEquals(published,store.load(input.scope(),()->false).orElseThrow());
        var missing=new SpringBridgeImport(content,tables).read("backend",inventory,fixture.source(),Map.of(),Map.of(),100,1_000_000,()->false);assertEquals(0,missing.endpoints().rows());assertTrue(missing.requirements().rows()>8);
    }
    @Test void lookalikeMappingAnnotationsNeverBecomeSpringEndpoints()throws Exception{
        var text="package app; @interface GetMapping { String value(); } @org.springframework.web.bind.annotation.RestController class Users { @GetMapping(\"/users\") String users(){return \"ok\";} }";var fixture=UniversalSpringSemanticsTest.fixture(Map.of("fixture/Users.java",text),false);var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,1_000_000);var bytes=content.put(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false);var imported=new SpringBridgeImport(content,tables).read("backend",fixture.source().manifest().snapshot().contentDigest(),fixture.source(),Map.of("fixture/Users.java",new SpringBridgeImport.CapturedSource(bytes,"UTF-8")),Map.of(),100,1_000_000,()->false);assertEquals(0,imported.endpoints().rows());assertTrue(imported.requirements().rows()>8);
    }
}
