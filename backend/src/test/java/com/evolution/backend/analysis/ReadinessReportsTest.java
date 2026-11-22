package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.readiness.*;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReadinessReportsTest {
    @TempDir Path root;
    @Test void allRootsContextsAndOwnershipSurviveReopenAndSelection()throws Exception{
        Path source=Files.createDirectory(root.resolve("source"));
        var files=Map.of("pom.xml",pom("outer"),"nested/pom.xml",pom("nested"),"src/main/java/A.java","class A {}", "nested/src/main/java/B.java","class B {}", "nested/src/test/java/T.java","class T {}", "nested/src/integration/java/I.java","class I {}", "worker/pyproject.toml","[project]\nname='worker'", "worker/main.py","pass", "assets/data.unrecognized","retained");
        for(var entry:files.entrySet()){Path path=source.resolve(entry.getKey());Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());}
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:durable-readiness");ReadinessPlan first;
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            var receipt=intake.local(source,"capture",new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of()),()->false);
            try(var reader=new RepositoryIntake(root.resolve("state"),true)){
                assertThrows(java.io.IOException.class,()->reader.publishedOverview("capture"));
            }
            first=intake.readiness("capture",ReadinessPlanner.Policy.comprehensive(),()->false);
            assertEquals(receipt.manifest().orElseThrow().entries(),first.ownership().entries());
            assertTrue(first.roots().stream().map(Root::directory).toList().containsAll(List.of(".","nested","worker")));
            assertTrue(first.contexts().stream().anyMatch(context->context.sourceSet().equals("integration")));
            assertTrue(first.requirements().stream().anyMatch(requirement->requirement.kind()==Kind.BUILD_VALIDATION));
            assertTrue(first.requirements().stream().allMatch(requirement->requirement.state()==State.PENDING));
            // The Workbench can read this projection while the analysis still owns the writer.
            try(var reader=new RepositoryIntake(root.resolve("state"),true)){
                var overview=reader.publishedOverview("capture");
                assertEquals(first.identity(),overview.get("plan"));
                assertEquals(first.roots().size(),overview.get("rootCount"));
                assertEquals(first.modules().size(),overview.get("moduleCount"));
                assertEquals("NOT_EVALUATED",overview.get("semanticCoverage"));
            }
            var selected=intake.readiness("capture",new ReadinessPlanner.Policy(Map.of("sourceSet",List.of("main")),true,1_000_000),()->false);
            assertFalse(selected.selection().unselected().isEmpty());assertNotEquals(first.identity(),selected.identity());
            assertEquals(first.inventory(),selected.inventory());
            assertThrows(IllegalArgumentException.class,()->intake.readinessPage("capture","contexts",2,Optional.of("foreign:contexts:2")));
        }
        Files.writeString(source.resolve("nested/src/main/java/B.java"),"class Changed {}");
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            assertEquals(first.identity(),intake.readiness("capture",ReadinessPlanner.Policy.comprehensive(),()->false).identity());
            intake.local(source,"changed",new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of()),()->false);
            assertNotEquals(first.identity(),intake.readiness("changed",ReadinessPlanner.Policy.comprehensive(),()->false).identity());
        }
    }
    @Test void oversizedDescriptorWaitsWithoutShrinkingInventory()throws Exception{
        Path source=Files.createDirectory(root.resolve("large"));Files.writeString(source.resolve("pom.xml"),pom("large"));
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            intake.local(source,"large",new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:large-descriptor"),Optional.empty(),true,List.of()),()->false);
            var plan=intake.readiness("large",new ReadinessPlanner.Policy(Map.of(),true,8),()->false);
            assertEquals(1,plan.inventory().files());assertTrue(plan.requirements().stream().anyMatch(requirement->requirement.reason().equals("DESCRIPTOR_WORKER_REQUIRED")));
        }
    }
    @Test void quickReadinessRetainsInventoryAndRecordsInstalledDependencyDeferral()throws Exception{
        Path source=Files.createDirectory(root.resolve("workspace"));
        var files=Map.of("package.json","{\"name\":\"workspace\"}","src/index.js","export const service = 1;",
                "node_modules/helper/package.json","{\"name\":\"helper\"}",
                "node_modules/helper/index.js","export const helper = 2;",
                "fixtures/node_modules/example.js","export const example = 3;",
                "my_node_modules/example.js","export const example = 4;");
        for(var entry:files.entrySet()){
            Path path=source.resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path,entry.getValue());
        }
        var request=new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:quick-readiness"),
                Optional.empty(),true,List.of());
        var policy=ReadinessPlanner.Policy.comprehensive();
        Path state=root.resolve("state");
        try(var intake=new RepositoryIntake(state)){
            intake.localComplete(source,"workspace",request,()->false);
            var quick=intake.readiness("workspace",policy,()->false,true);
            assertEquals(2L,quick.ownership().counts().get(Disposition.DEPENDENCY_INPUT.name()));
            assertTrue(quick.requirements().stream().anyMatch(requirement->
                    requirement.reason().equals("INSTALLED_DEPENDENCY_SEMANTICS_DEFERRED")));
            try(var reader=new RepositoryIntake(state,true)){
                var coverage=(Map<?,?>)reader.publishedOverview("workspace").get("coverage");
                assertEquals(2L,coverage.get("dependencyInputs"));
            }
            var deep=intake.readiness("workspace",policy,()->false);
            assertEquals(deep.inventory(),quick.inventory());
            assertEquals(deep.ownership().entries(),quick.ownership().entries());
            assertFalse(deep.ownership().counts().containsKey(Disposition.DEPENDENCY_INPUT.name()));
            assertEquals(deep.modules().size()-1,quick.modules().size());
            assertEquals(quick.identity(),intake.readiness("workspace",policy,()->false,true).identity());
            var snapshot=RepositorySnapshot.create(request.repository(),Optional.empty(),true,List.of(),List.of());
            var scan=new PolyglotScan().run("workspace",intake,quick,
                    EvidenceContext.forSnapshot(snapshot.identity()),Optional.empty(),root.resolve("syntax"),()->false,
                    digest->null,true,intake.installedNodeDependencies("workspace"));
            assertEquals(4L,scan.languages().get("javascript"));
            assertEquals(3,scan.syntax().parsed());
        }
    }
    private static String pom(String artifact){return "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>"+artifact+"</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>";}
}
