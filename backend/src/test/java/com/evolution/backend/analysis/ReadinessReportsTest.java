package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
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
            first=intake.readiness("capture",ReadinessPlanner.Policy.comprehensive(),()->false);
            assertEquals(receipt.manifest().orElseThrow().entries(),first.ownership().entries());
            assertTrue(first.roots().stream().map(Root::directory).toList().containsAll(List.of(".","nested","worker")));
            assertTrue(first.contexts().stream().anyMatch(context->context.sourceSet().equals("integration")));
            assertTrue(first.requirements().stream().anyMatch(requirement->requirement.kind()==Kind.BUILD_VALIDATION));
            assertTrue(first.requirements().stream().allMatch(requirement->requirement.state()==State.PENDING));
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
    private static String pom(String artifact){return "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>"+artifact+"</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>";}
}
