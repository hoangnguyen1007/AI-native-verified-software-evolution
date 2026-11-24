package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.input.PlatformSymbolRequest;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GradleScanTest {
    @TempDir Path directory;
    @Test void literalProjectDependenciesSupplyOwnedSourceWithoutLeakingPrivateTransitiveProjects()throws Exception {
        Path source=Files.createDirectory(directory.resolve("projects")),cache=Files.createDirectory(directory.resolve("project-cache"));
        var files=Map.of("settings.gradle","include ':app', ':api', ':privateLib'\n",
                "app/build.gradle","plugins { id 'java' }\ndependencies { implementation project(':api') }\n",
                "api/build.gradle","plugins { id 'java-library' }\ndependencies { implementation project(':privateLib') }\n",
                "privateLib/build.gradle","plugins { id 'java-library' }\n",
                "api/src/main/java/external/Api.java","package external; public class Api { public static int id() { return 42; } }",
                "api/src/test/java/ApiTest.java","class ApiTest { int test() { return external.Api.id(); } }",
                "privateLib/src/main/java/hidden/Secret.java","package hidden; public class Secret { public static int id() { return 1; } }",
                "app/src/main/java/Client.java","class Client { int call() { return external.Api.id(); } int forbidden() { return hidden.Secret.id(); } }");
        for(var file:files.entrySet()){Path path=source.resolve(file.getKey());Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());}
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-project-product"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,1_000_000,10_000_000,32,List.of())));
        var buildPolicy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),2048,1_000_000,200_000,64);var decoding=new RepositoryInputDecoder.Policy("UTF-8",100,1_000_000);
        var model=GradleScan.model(acquisition,buildPolicy,decoding,Map.of());
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform();assertTrue(platform.isPresent());
        var inputs=GradleScan.augment(new MavenScan.Inputs(Map.of(),Map.of(),List.of(),Map.of()),acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of());
        var policy=new UniversalArchitecturePipeline.Policy(buildPolicy,decoding,new JavaParserFrontend(),JavaParserFrontend.PROVIDER,inputs.resolutions(),platform,Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),Map.of(),MavenScan.POLICY,1000,100,UniversalArchitecturePipeline.TruthEvaluationMode.NONE,Map.of(),Optional.empty(),Optional.empty(),com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        var calls=result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).flatMap(f->f.observations().stream()).filter(o->o.category().value().equals("java.calls")).toList();
        assertEquals(2,calls.stream().filter(o->o.reference().equals("external.Api.id()")&&o.attribution()==SemanticStatus.RESOLVED).count());
        var forbidden=calls.stream().filter(o->o.reference().equals("hidden.Secret.id()")).toList();assertEquals(1,forbidden.size());assertNotEquals(SemanticStatus.RESOLVED,forbidden.getFirst().attribution());
        assertTrue(inputs.resolutions().values().stream().noneMatch(UniversalSourceIngestion.Resolution::complete));
        assertTrue(inputs.gaps().stream().anyMatch(g->g.reasonCode().equals("GRADLE_EFFECTIVE_CONTEXT_REQUIRED")));
    }
    @Test void cachedGradleJarReachesTheSemanticFrontendAndDurableLedgerWithoutTargetExecution()throws Exception {
        Path source=Files.createDirectory(directory.resolve("source")),cache=Files.createDirectory(directory.resolve("cache"));
        Files.writeString(source.resolve("build.gradle"),"plugins { id 'java' }\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\ndependencies { implementation 'external:library:1' }\n");
        Path code=Files.createDirectories(source.resolve("src/main/java"));Files.writeString(code.resolve("Client.java"),"class Client { int call() { return external.Library.id(); } }");
        Path authored=Files.createDirectory(directory.resolve("authored")),classes=Files.createDirectory(directory.resolve("classes"));
        Path fixture=authored.resolve("Library.java");Files.writeString(fixture,"package external; public class Library { public static int id() { return 42; } }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),fixture.toString()));
        Path library=Files.createDirectories(cache.resolve("external/library/1"));
        try(var jar=new JarOutputStream(Files.newOutputStream(library.resolve("library-1.jar")))){jar.putNextEntry(new JarEntry("external/Library.class"));jar.write(Files.readAllBytes(classes.resolve("external/Library.class")));jar.closeEntry();}
        Files.writeString(library.resolve("library-1.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId><artifactId>library</artifactId><version>1</version></project>");
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-product"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,1_000_000,10_000_000,32,List.of())));
        var buildPolicy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),2048,1_000_000,200_000,64);
        var decoding=new RepositoryInputDecoder.Policy("UTF-8",100,1_000_000);var model=GradleScan.model(acquisition,buildPolicy,decoding,Map.of());
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform();assertTrue(platform.isPresent());
        var empty=new MavenScan.Inputs(Map.of(),Map.of(),List.of(),Map.of("mode","NOT_APPLICABLE"));
        var inputs=GradleScan.augment(empty,acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of(21,platform.orElseThrow()));
        assertEquals(1,inputs.resolutions().values().iterator().next().binaries().size());assertFalse(inputs.resolutions().values().iterator().next().complete());assertFalse(inputs.ledgers().isEmpty());
        var policy=new UniversalArchitecturePipeline.Policy(buildPolicy,decoding,new JavaParserFrontend(),JavaParserFrontend.PROVIDER,inputs.resolutions(),platform,Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),Map.of(),MavenScan.POLICY,1000,100,UniversalArchitecturePipeline.TruthEvaluationMode.NONE,Map.of(),Optional.empty(),Optional.empty(),com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        var calls=result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).flatMap(f->f.occurrences().stream()).filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(1,calls.size());assertEquals(SemanticStatus.RESOLVED,calls.getFirst().status());
        assertTrue(inputs.gaps().stream().anyMatch(g->g.reasonCode().equals("GRADLE_EFFECTIVE_CONTEXT_REQUIRED")));
        assertEquals(inputs.resolutions(),GradleScan.augment(empty,acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of(21,platform.orElseThrow())).resolutions());
    }
    @Test void androidAarClassesJarAndRemappedVariantProjectsResolveInSemanticFrontend()throws Exception {
        assertTrue(MavenScan.DEFAULT_REPOSITORIES.contains(java.net.URI.create("https://dl.google.com/dl/android/maven2/")));
        Path source=Files.createDirectory(directory.resolve("android-source")),cache=Files.createDirectory(directory.resolve("android-cache"));
        var files=Map.of(
                "settings.gradle","include ':app', ':utils'\nproject(':utils').projectDir = file('depends/utils')\n",
                "build.gradle","subprojects {\n android {\n compileOptions {\n sourceCompatibility = JavaVersion.VERSION_21\n targetCompatibility = JavaVersion.VERSION_21\n encoding = 'UTF-8'\n }\n }\n}\n",
                "depends/utils/build.gradle","plugins { id 'com.android.library' }\n",
                "depends/utils/src/main/java/util/Helper.java","package util; public class Helper { public static int help() { return 7; } }",
                "app/build.gradle.kts","plugins { id(\"com.android.application\") }\ndependencies {\n implementation(projects.utils)\n autoImplementation(\"external:aarlib:1\")\n}\n",
                "app/src/auto/java/Client.java","class Client { int a() { return util.Helper.help(); } int b() { return external.AarWidget.render(); } }");
        for(var file:files.entrySet()){Path path=source.resolve(file.getKey());Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());}
        Path authored=Files.createDirectory(directory.resolve("aar-authored")),classes=Files.createDirectory(directory.resolve("aar-classes"));
        Path fixture=authored.resolve("AarWidget.java");Files.writeString(fixture,"package external; public class AarWidget { public static int render() { return 99; } }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",classes.toString(),fixture.toString()));
        var innerJar=new java.io.ByteArrayOutputStream();
        try(var jar=new JarOutputStream(innerJar)){jar.putNextEntry(new JarEntry("external/AarWidget.class"));jar.write(Files.readAllBytes(classes.resolve("external/AarWidget.class")));jar.closeEntry();}
        Path aarDir=Files.createDirectories(cache.resolve("external/aarlib/1"));
        try(var aar=new JarOutputStream(Files.newOutputStream(aarDir.resolve("aarlib-1.aar")))){
            aar.putNextEntry(new JarEntry("AndroidManifest.xml"));aar.write("<manifest/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));aar.closeEntry();
            aar.putNextEntry(new JarEntry("classes.jar"));aar.write(innerJar.toByteArray());aar.closeEntry();
        }
        Files.writeString(aarDir.resolve("aarlib-1.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>external</groupId><artifactId>aarlib</artifactId><version>1</version><packaging>aar</packaging></project>");
        var acquisition=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-android-product"),Optional.empty(),false,"pom.xml",new RepositoryAcquisitionPolicy(100,100,1_000_000,10_000_000,32,List.of())));
        var buildPolicy=new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),2048,1_000_000,200_000,64);
        var decoding=new RepositoryInputDecoder.Policy("UTF-8",100,1_000_000);
        var model=GradleScan.model(acquisition,buildPolicy,decoding,Map.of());
        var platform=new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform();assertTrue(platform.isPresent());
        var empty=new MavenScan.Inputs(Map.of(),Map.of(),List.of(),Map.of("mode","NOT_APPLICABLE"));
        var inputs=GradleScan.augment(empty,acquisition,model,cache,platform,new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0),Map.of(21,platform.orElseThrow()));
        var policy=new UniversalArchitecturePipeline.Policy(buildPolicy,decoding,new JavaParserFrontend(),JavaParserFrontend.PROVIDER,inputs.resolutions(),platform,Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),Map.of(),MavenScan.POLICY,1000,100,UniversalArchitecturePipeline.TruthEvaluationMode.NONE,Map.of(),Optional.empty(),Optional.empty(),com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result=new UniversalArchitecturePipeline().runWithEvidence(acquisition,policy);
        var calls=result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).flatMap(f->f.observations().stream()).filter(o->o.category().value().equals("java.calls")).toList();
        assertEquals(1,calls.stream().filter(o->o.reference().equals("util.Helper.help()")&&o.attribution()==SemanticStatus.RESOLVED).count());
        assertEquals(1,calls.stream().filter(o->o.reference().equals("external.AarWidget.render()")&&o.attribution()==SemanticStatus.RESOLVED).count());
    }

    @Test void localAarAndProjectBomSkipFalseClasspathResolutionRequired() throws Exception {
        Path source = Files.createDirectory(directory.resolve("local-aar-source"));
        Path cache = Files.createDirectory(directory.resolve("local-aar-cache"));
        Path authored = Files.createDirectory(directory.resolve("local-aar-authored"));
        Path classes = Files.createDirectory(directory.resolve("local-aar-classes"));
        Path widgetSrc = authored.resolve("LocalEditor.java");
        Files.writeString(widgetSrc, "package external; public class LocalEditor { public static int version() { return 12; } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", classes.toString(), widgetSrc.toString()));
        var innerJar = new java.io.ByteArrayOutputStream();
        try (var jar = new JarOutputStream(innerJar)) {
            jar.putNextEntry(new JarEntry("external/LocalEditor.class"));
            jar.write(Files.readAllBytes(classes.resolve("external/LocalEditor.class")));
            jar.closeEntry();
        }
        Path libsDir = Files.createDirectories(source.resolve("app/libs"));
        try (var aar = new JarOutputStream(Files.newOutputStream(libsDir.resolve("editor-debug.aar")))) {
            aar.putNextEntry(new JarEntry("AndroidManifest.xml"));
            aar.write("<manifest/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            aar.closeEntry();
            aar.putNextEntry(new JarEntry("classes.jar"));
            aar.write(innerJar.toByteArray());
            aar.closeEntry();
        }
        var files = Map.of(
                "settings.gradle", "include ':app', ':sdk'\n",
                "sdk/build.gradle", "plugins { id 'java-library' }\n",
                "sdk/src/main/java/sdk/Api.java", "package sdk; public class Api { public static int id() { return 1; } }\n",
                "app/build.gradle", "plugins { id 'java' }\n"
                        + "dependencies {\n"
                        + "    implementation project(':sdk')\n"
                        + "    implementation platform('org.example:bom:1.0.0')\n"
                        + "    implementation files('libs/editor-debug.aar')\n"
                        + "    testImplementation 'junit:junit:4.13.2'\n"
                        + "}\n",
                "app/src/main/java/app/Main.java", "package app; class Main { int go() { return external.LocalEditor.version() + sdk.Api.id(); } }\n");
        for (var file : files.entrySet()) {
            Path path = source.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        var acquisition = new FilesystemRepositoryAcquirer().acquire(source,
                new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:local-aar"),
                        Optional.empty(), false, "pom.xml",
                        new RepositoryAcquisitionPolicy(100, 100, 5_000_000, 10_000_000, 32, List.of())));
        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"), 2048, 1_000_000, 200_000, 64);
        var decoding = new RepositoryInputDecoder.Policy("UTF-8", 100, 1_000_000);
        var model = GradleScan.model(acquisition, buildPolicy, decoding, Map.of());
        var platform = new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),
                new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000)).platform().orElseThrow();
        var empty = new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of("mode", "NOT_APPLICABLE"));
        var inputs = GradleScan.augment(empty, acquisition, model, cache, Optional.of(platform),
                new DependencyAcquisitionPolicy(100, 5_000_000, 10_000_000, 16, List.of(), 1000, 1000, 0),
                Map.of(21, platform));
        var policy = new UniversalArchitecturePipeline.Policy(buildPolicy, decoding, new JavaParserFrontend(),
                JavaParserFrontend.PROVIDER, inputs.resolutions(), Optional.of(platform),
                Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()), Map.of(), MavenScan.POLICY,
                1000, 100, UniversalArchitecturePipeline.TruthEvaluationMode.NONE, Map.of(), Optional.empty(),
                Optional.empty(), com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result = new UniversalArchitecturePipeline().runWithEvidence(acquisition, policy);
        var calls = result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream()
                .flatMap(u -> u.frontend().stream()).flatMap(f -> f.observations().stream())
                .filter(o -> o.category().value().equals("java.calls")).toList();
        assertEquals(1, calls.stream().filter(o -> o.reference().equals("external.LocalEditor.version()")
                && o.attribution() == SemanticStatus.RESOLVED).count());
        assertEquals(1, calls.stream().filter(o -> o.reference().equals("sdk.Api.id()")
                && o.attribution() == SemanticStatus.RESOLVED).count());
        var falseClasspathIssues = result.graphInput().orElseThrow().issues().stream()
                .filter(i -> i.reason() == IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED)
                .filter(i -> i.subject().contains(":sdk") || i.subject().contains("org.example:bom")
                        || i.subject().contains("editor-debug.aar") || i.subject().contains("junit"))
                .toList();
        assertTrue(falseClasspathIssues.isEmpty(), () -> "Unexpected false classpath issues: " + falseClasspathIssues);
    }

    @Test void androidSdkPlatformJarResolvesAndroidApiCallsWithoutDeclarationGaps() throws Exception {
        Path sdkRoot = Files.createDirectories(directory.resolve("android-sdk"));
        Path platformDir = Files.createDirectories(sdkRoot.resolve("platforms/android-36"));
        Path stubSrc = Files.createDirectories(directory.resolve("android-stub-src"));
        Path stubOut = Files.createDirectories(directory.resolve("android-stub-out"));
        Path bundleJava = stubSrc.resolve("android/os/Bundle.java");
        Path activityJava = stubSrc.resolve("android/app/Activity.java");
        Files.createDirectories(bundleJava.getParent());
        Files.createDirectories(activityJava.getParent());
        Files.writeString(bundleJava, "package android.os; public class Bundle {}");
        Files.writeString(activityJava, "package android.app; public class Activity { public void onCreate(android.os.Bundle savedInstanceState) {} }");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler required for test stub");
        assertEquals(0, compiler.run(null, null, null, "-d", stubOut.toString(), bundleJava.toString(), activityJava.toString()));
        Path androidJar = platformDir.resolve("android.jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(androidJar));
             var walk = Files.walk(stubOut)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String entryName = stubOut.relativize(p).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }

        Path source = Files.createDirectory(directory.resolve("android-app"));
        Path cache = Files.createDirectory(directory.resolve("android-cache"));
        var files = Map.of(
                "settings.gradle", "include ':app'\n",
                "app/build.gradle", """
                        plugins { id 'com.android.application' }
                        android { compileSdk = 36 }
                        """,
                "app/src/main/java/demo/MainActivity.java", """
                        package demo;
                        import android.app.Activity;
                        import android.os.Bundle;
                        public class MainActivity extends Activity {
                            @Override public void onCreate(Bundle savedInstanceState) {
                                super.onCreate(savedInstanceState);
                            }
                        }
                        """);
        for (var file : files.entrySet()) {
            Path path = source.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        var acquisition = new FilesystemRepositoryAcquirer().acquire(source,
                new RepositoryAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:android-sdk-app"),
                        Optional.empty(), false, "pom.xml",
                        new RepositoryAcquisitionPolicy(100, 100, 1_000_000, 10_000_000, 32, List.of())));
        var buildPolicy = new UniversalBuildIngestion.Policy(Optional.of(21), Optional.of(21), Optional.of("UTF-8"), 2048, 1_000_000, 200_000, 64);
        var decoding = new RepositoryInputDecoder.Policy("UTF-8", 100, 1_000_000);
        var model = GradleScan.model(acquisition, buildPolicy, decoding, Map.of());
        var platform = new FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),
                new PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000)).platform();
        assertTrue(platform.isPresent());
        var depPolicy = new DependencyAcquisitionPolicy(100, 10_000_000, 50_000_000, 16, List.of(), 1000, 1000, 0);
        var inputs = GradleScan.augment(new MavenScan.Inputs(Map.of(), Map.of(), List.of(), Map.of()),
                acquisition, model, cache, platform, depPolicy, Map.of(), Optional.of(sdkRoot));
        assertTrue(inputs.resolutions().values().stream()
                .flatMap(r -> r.binaries().stream())
                .anyMatch(b -> b.entry().logicalName().equals("android-sdk:platforms/android-36/android.jar")),
                () -> "Expected android.jar in resolutions: " + inputs.resolutions());

        var policy = new UniversalArchitecturePipeline.Policy(
                buildPolicy, decoding, new JavaParserFrontend(), JavaParserFrontend.PROVIDER,
                inputs.resolutions(), platform, Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()),
                Map.of(), MavenScan.POLICY, 1000, 100, UniversalArchitecturePipeline.TruthEvaluationMode.NONE,
                Map.of(), Optional.empty(), Optional.empty(),
                com.evolution.analysis.spring.truth.TruthRegionEvaluation.Limits.conservative());
        var result = new UniversalArchitecturePipeline().runWithEvidence(acquisition, policy);
        var frontends = result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream()
                .flatMap(u -> u.frontend().stream()).toList();
        var calls = frontends.stream()
                .flatMap(f -> f.observations().stream())
                .filter(o -> o.category().value().equals("java.calls"))
                .toList();
        assertTrue(calls.stream().anyMatch(o -> o.reference().contains("onCreate") && o.attribution() == SemanticStatus.RESOLVED),
                () -> "Expected resolved super.onCreate call, got: " + calls);
        var missingDeclDiags = frontends.stream()
                .flatMap(f -> f.diagnostics().stream())
                .filter(d -> d.code().equals("java.declaration-evidence-required"))
                .toList();
        assertTrue(missingDeclDiags.isEmpty(), () -> "Unexpected java.declaration-evidence-required diagnostics: " + missingDeclDiags);
    }
}
