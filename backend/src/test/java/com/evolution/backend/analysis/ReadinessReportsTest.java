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
    @Test void overviewKeepsTopLevelModulesVisibleWhenDependencyRootsDominate() throws Exception {
        Path source=Files.createDirectory(root.resolve("preview"));
        Files.createDirectories(source.resolve("backend"));
        Files.writeString(source.resolve("backend/pom.xml"),pom("backend"));
        for (int i=0;i<30;i++) {
            Path module=source.resolve("mobile/node_modules/pkg"+i);
            Files.createDirectories(module);
            Files.writeString(module.resolve("CMakeLists.txt"),"cmake_minimum_required(VERSION 3.10)\nproject(pkg"+i+")\n");
        }
        try (var intake=new RepositoryIntake(root.resolve("state"))) {
            intake.local(source,"preview",new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:overview"),Optional.empty(),true,List.of()),()->false);
            intake.readiness("preview",ReadinessPlanner.Policy.comprehensive(),()->false);
            try (var reader=new RepositoryIntake(root.resolve("state"),true)) {
                var overview=reader.publishedOverview("preview");
                var roots=(List<?>)overview.get("roots");
                assertTrue(((Number)overview.get("rootCount")).intValue()>24);
                assertTrue(roots.stream().map(row -> (Map<?,?>)row).anyMatch(row -> row.get("directory").equals("backend")));
            }
        }
    }
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
            try(var reader=new RepositoryIntake(state,true)){
                var fastOverview=reader.publishedOverview("workspace",Optional.of(AnalysisMode.FAST_ARCHITECTURE));
                assertEquals(quick.identity(),fastOverview.get("plan"));
                var deepOverview=reader.publishedOverview("workspace",Optional.of(AnalysisMode.FULL));
                assertEquals(deep.identity(),deepOverview.get("plan"));
                assertEquals(deep.modules().size(),deepOverview.get("moduleCount"));
                assertEquals(1,deepOverview.get("installedDependencyModules"));
                assertFalse(deepOverview.get("modules").toString().contains("node_modules/helper"));
            }
            assertEquals(quick.identity(),intake.readiness("workspace",policy,()->false,true).identity());
            var snapshot=RepositorySnapshot.create(request.repository(),Optional.empty(),true,List.of(),List.of());
            var scan=new PolyglotScan().run("workspace",intake,quick,
                    EvidenceContext.forSnapshot(snapshot.identity()),Optional.empty(),root.resolve("syntax"),()->false,
                    digest->null,true,intake.installedNodeDependencies("workspace"));
            assertEquals(4L,scan.languages().get("javascript"));
            assertEquals(3,scan.syntax().parsed());
        }
    }
    @Test void compatibilityDoesNotMistakePnpmInstalledJavaPackagesForWorkspaceModules()throws Exception{
        Path source=Files.createDirectory(root.resolve("pnpm"));
        Files.writeString(source.resolve("pnpm-workspace.yaml"),"packages: ['apps/*']\n");
        Path installed=Files.createDirectories(source.resolve("node_modules/helper"));
        Path firstParty=Files.createDirectories(source.resolve("apps/backend"));
        Files.writeString(installed.resolve("pom.xml"),pom("installed"));
        Files.writeString(firstParty.resolve("pom.xml"),pom("service"));
        var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:test:pnpm-readiness");
        try(var intake=new RepositoryIntake(root.resolve("state"))){
            intake.localComplete(source,"capture",new StreamingAcquisitionRequest(repository,Optional.empty(),true,List.of()),()->false);
            var plan=intake.readiness("capture",ReadinessPlanner.Policy.comprehensive(),()->false,true);
            assertEquals(1L,plan.ownership().counts().get(Disposition.DEPENDENCY_INPUT.name()));
            var selected=intake.compatibility("capture",repository,Optional.empty(),true,false,false,List.of());
            assertEquals(Set.of("apps/backend/pom.xml"),selected.workspacePoms().keySet());
            var all=intake.compatibility("capture",repository,Optional.empty(),true,false,true,List.of());
            assertTrue(all.workspacePoms().containsKey("node_modules/helper/pom.xml"));
        }
    }
    @Test void satisfiedReadinessFilterDropsOnlyVerifiedCapabilitiesAndPreservesNativeObligations() throws Exception {
        Path source = Files.createDirectory(root.resolve("android-readiness"));
        Files.createDirectories(source.resolve("app/src/main/java/demo"));
        Files.writeString(source.resolve("settings.gradle"), "include ':app'\n");
        Files.writeString(source.resolve("app/build.gradle"), "plugins { id 'com.android.application' }\n");
        Files.writeString(source.resolve("app/src/main/AndroidManifest.xml"), "<manifest package=\"demo\"/>\n");
        Files.writeString(source.resolve("app/src/main/java/demo/App.java"), "package demo; public class App {}\n");
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:readiness-filter");
        try (var intake = new RepositoryIntake(root.resolve("filter-state"))) {
            var captured = intake.localComplete(source, "rev",
                    new StreamingAcquisitionRequest(repo, Optional.empty(), true, List.of()), () -> false);
            var plan = intake.readiness("rev", ReadinessPlanner.Policy.comprehensive(), () -> false);
            var snapshot = com.evolution.analysis.contract.identity.SnapshotIdentity.from(repo,
                    captured.manifest().orElseThrow().inventory());

            var unfiltered = plan.gaps(snapshot);
            assertTrue(unfiltered.stream().anyMatch(g -> g.reasonCode().equals("ANDROID_SDK_REQUIRED")));
            assertTrue(unfiltered.stream().anyMatch(g -> g.reasonCode().equals("NATIVE_BUILD_VALIDATION_REQUIRED")));

            // Without verified android.jar or platforms, nothing is suppressed.
            var withoutEvidence = LocalAnalysisRunner.filterSatisfiedReadinessGaps(
                    plan, snapshot, new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of()),
                    Map.of(), List.of(), false, null, null);
            assertEquals(unfiltered.size(), withoutEvidence.size());

            // With a verified android-sdk platform JAR attached, only ANDROID_SDK_REQUIRED is satisfied;
            // native build/model/toolchain obligations stay truthful and explicit.
            var platform = com.evolution.analysis.frontend.PlatformInput.create(21, "21", "test",
                    List.of(new com.evolution.analysis.frontend.PlatformInput.Artifact(
                            "java.base.jmod",
                            com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("base"),
                            root.resolve("java.base.jmod"),
                            com.evolution.analysis.frontend.PlatformInput.Format.JMOD)));
            var androidJar = new com.evolution.analysis.frontend.BinaryInput(
                    new com.evolution.analysis.contract.analysis.ClasspathEntry(
                            com.evolution.analysis.contract.analysis.ClasspathEntryKind.DEPENDENCY,
                            "android-sdk:platforms/android-36/android.jar",
                            com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("android-36")),
                    root.resolve("android.jar"));
            var resolution = new com.evolution.analysis.ingestion.UniversalSourceIngestion.Resolution(
                    platform, List.of(androidJar),
                    com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("cp"),
                    Optional.empty(), false, List.of());
            var depsWithAndroid = new MavenScan.Inputs(
                    Map.of(new com.evolution.analysis.ingestion.UniversalSourceIngestion.SourceSet(
                            com.evolution.analysis.contract.identity.ModuleIdentity.from(repo, "app"),
                            com.evolution.analysis.buildmodel.SourcePlanModel.Kind.MAIN), resolution),
                    Map.of(), List.of(), Map.of());
            var withAndroid = LocalAnalysisRunner.filterSatisfiedReadinessGaps(
                    plan, snapshot, depsWithAndroid, Map.of(), List.of(), false, null, null);
            assertTrue(withAndroid.stream().noneMatch(g -> g.reasonCode().equals("ANDROID_SDK_REQUIRED")));
            assertTrue(withAndroid.stream().anyMatch(g -> g.reasonCode().equals("NATIVE_BUILD_VALIDATION_REQUIRED")));
            assertTrue(withAndroid.stream().anyMatch(g -> g.reasonCode().equals("NATIVE_MODEL_REQUIRED")));
        }

        // Per-module scoping: when mod-a analyzes cleanly and mod-b has a broken POM (no analyzed Java unit),
        // mod-b's ANALYZED_PLATFORM_REQUIRED gap must NOT be suppressed.
        Path multi = Files.createDirectory(root.resolve("multi-mod-filter"));
        Files.createDirectories(multi.resolve("mod-a/src/main/java/a"));
        Files.createDirectories(multi.resolve("mod-b/src/main/java/b"));
        Files.writeString(multi.resolve("mod-a/pom.xml"), pom("mod-a"));
        Files.writeString(multi.resolve("mod-a/src/main/java/a/Good.java"), "package a; public class Good {}");
        Files.writeString(multi.resolve("mod-b/pom.xml"), "<project>");
        Files.writeString(multi.resolve("mod-b/src/main/java/b/Broken.java"), "package b; public class Broken {}");
        Path cache = Files.createDirectory(root.resolve("multi-mod-cache"));
        var multiRepo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:multi-mod-readiness-scope");
        var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(multi, multiRepo,
                Path.of(System.getProperty("java.home")), 21, cache, Optional.empty(), true, false,
                List.of(), Optional.empty(), LocalAnalysisRunner.SpringInput.absent()),
                root.resolve("multi-mod-intake"));
        var outcome = runner.analyzeDirect("multi-scope", () -> false);
        assertTrue(outcome.gaps().stream().anyMatch(g -> g.reasonCode().equals("ANALYZED_PLATFORM_REQUIRED")),
                "Unanalyzed broken module must retain its ANALYZED_PLATFORM_REQUIRED gap");
    }
    private static String pom(String artifact){return "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>"+artifact+"</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>";}
}
