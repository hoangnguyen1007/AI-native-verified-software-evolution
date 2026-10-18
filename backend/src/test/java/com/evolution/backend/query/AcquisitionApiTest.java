package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.graph.*;
import com.evolution.backend.analysis.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AcquisitionApiTest {
    @TempDir Path root;
    @Test void v2PagesFullInventoryIndependentlyOfSemanticViews()throws Exception{
        Path source=Files.createDirectory(root.resolve("source"));Files.writeString(source.resolve("main.py"),"pass");Files.writeString(source.resolve("schema.proto"),"message X {}");
        try(var intake=new RepositoryIntake(root.resolve("acquisition"))){assertTrue(intake.local(source,"captured",new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:api"),Optional.empty(),true,List.of()),()->false).manifest().isPresent());}
        var store=new InMemoryGraphStore();var reports=new PublishedReports();try(var jobs=new AnalysisJobs(List.of(),store,reports,AnalysisJobs.Limits.defaults())){
            var connections=new RepositoryConnections(jobs,Path.of(System.getProperty("java.home")),21,root,root,Optional.empty());var api=new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs,connections);
            var response=api.handle("POST","/api/v2/acquisitions/captured","application/json","{\"size\":1}".getBytes(StandardCharsets.UTF_8),()->false);assertEquals(200,response.status(),new String(response.body(),StandardCharsets.UTF_8));
            var body=GraphJson.object(GraphJson.read(new String(response.body(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));assertEquals("repository.acquisition-api:m8.4-v2",body.get("apiVersion"));
            var data=GraphJson.object(body.get("data"));assertEquals("NOT_EVALUATED",data.get("semanticCoverage"));var page=GraphJson.object(data.get("inventory"));assertEquals(1,GraphJson.array(page.get("entries")).size());assertNotNull(page.get("continuation"));
            assertEquals(400,api.handle("POST","/api/v2/acquisitions/captured","application/json","{\"size\":1,\"extra\":true}".getBytes(StandardCharsets.UTF_8),()->false).status());
        }
    }
}
