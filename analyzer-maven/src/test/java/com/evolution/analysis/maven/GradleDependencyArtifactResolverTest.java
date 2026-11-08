package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GradleDependencyArtifactResolverTest {
    @TempDir Path temp;
    private final DependencyAcquisitionPolicy offline=new DependencyAcquisitionPolicy(100,1_000_000,10_000_000,16,List.of(),1000,1000,0);
    @Test void bootBomRecoveryRequiresMatchingCapturedPluginMarkerMetadata()throws Exception {
        var model=model(Map.of("build.gradle","import org.springframework.boot.gradle.plugin.SpringBootPlugin\nplugins { id 'org.springframework.boot' version '3.4.1' apply false }\ndependencies { api platform(SpringBootPlugin.BOM_COORDINATES)\n implementation 'g:library' }\n"));
        put("library","2",false,"");
        var missing=new GradleDependencyArtifactResolver(temp).resolve(model,offline).contexts().getFirst();assertTrue(missing.binaries().isEmpty());
        assertTrue(missing.problems().stream().anyMatch(p->p.reason().equals("GRADLE_PLUGIN_BOM_EVIDENCE_REQUIRED")));
        Path marker=Files.createDirectories(temp.resolve("org/springframework/boot/org.springframework.boot.gradle.plugin/3.4.1"));
        Path bom=Files.createDirectories(temp.resolve("org/springframework/boot/spring-boot-dependencies/3.4.1"));
        String implementation="<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-gradle-plugin</artifactId><version>3.4.1</version></dependency></dependencies>";
        Files.writeString(marker.resolve("org.springframework.boot.gradle.plugin-3.4.1.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>org.springframework.boot</groupId><artifactId>org.springframework.boot.gradle.plugin</artifactId><version>3.4.1</version><packaging>pom</packaging>"+implementation+"</project>");
        Files.writeString(bom.resolve("spring-boot-dependencies-3.4.1.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId><version>3.4.1</version><packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>library</artifactId><version>2</version></dependency></dependencies></dependencyManagement></project>");
        var captured=new GradleDependencyArtifactResolver(temp).resolve(model,offline).contexts().getFirst();assertEquals(List.of("g:library:2@jar"),captured.binaries().stream().map(b->b.entry().logicalName()).toList());
        assertTrue(captured.metadata().stream().anyMatch(e->e.logicalId().contains("org.springframework.boot.gradle.plugin-3.4.1.pom")));
        Files.writeString(marker.resolve("org.springframework.boot.gradle.plugin-3.4.1.pom"),Files.readString(marker.resolve("org.springframework.boot.gradle.plugin-3.4.1.pom")).replace("<artifactId>spring-boot-gradle-plugin</artifactId>","<artifactId>impostor</artifactId>"));
        assertTrue(new GradleDependencyArtifactResolver(temp).resolve(model,offline).contexts().getFirst().binaries().isEmpty());
    }
    @Test void offlinePlatformBomAndExportedProjectApiSupplyHashBoundRecoveryInputs()throws Exception {
        var model=model(Map.of("settings.gradle","include ':app', ':api', ':platform'\n",
                "app/build.gradle","dependencies { implementation project(':api')\n testImplementation 'g:test:1'\n runtimeOnly 'g:runtime:1'\n annotationProcessor 'g:processor:1' }\n",
                "api/build.gradle","dependencies { api platform(project(':platform'))\n api 'g:library'\n implementation 'g:private:1' }\n",
                "platform/build.gradle","plugins { id 'java-platform' }\ndependencies { api platform('g:bom:1')\n constraints { api 'g:local:2' } }\n"));
        put("bom","1",true,"<packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>g</groupId><artifactId>library</artifactId><version>2</version></dependency></dependencies></dependencyManagement>");
        put("library","2",false,"<dependencies><dependency><groupId>g</groupId><artifactId>child</artifactId><version>3</version></dependency><dependency><groupId>g</groupId><artifactId>optional</artifactId><version>1</version><optional>true</optional></dependency></dependencies>");
        put("child","3",false,"");put("private","1",false,"");put("test","1",false,"");
        var resolver=new GradleDependencyArtifactResolver(temp);var first=resolver.resolve(model,offline);
        var app=model.modules().stream().filter(m->m.descriptor().path().equals("app")).findFirst().orElseThrow();
        var main=first.contexts().stream().filter(c->c.sourceSet().module().equals(app.descriptor().identity())&&c.sourceSet().kind()==SourcePlanModel.Kind.MAIN).findFirst().orElseThrow();
        assertEquals(List.of("g:library:2@jar","g:child:3@jar"),main.binaries().stream().map(b->b.entry().logicalName()).toList());
        assertEquals(1,main.projects().size());assertTrue(main.metadata().stream().anyMatch(e->e.logicalId().contains("bom-1.pom")));
        assertEquals(List.of("GRADLE_EFFECTIVE_CONTEXT_REQUIRED"),main.problems().stream().map(GradleDependencyArtifactResolver.Problem::reason).toList());
        var test=first.contexts().stream().filter(c->c.sourceSet().module().equals(app.descriptor().identity())&&c.sourceSet().kind()==SourcePlanModel.Kind.TEST).findFirst().orElseThrow();
        assertTrue(test.binaries().stream().anyMatch(b->b.entry().logicalName().equals("g:test:1@jar")));
        assertTrue(first.acquisitions().stream().flatMap(a->a.attempts().stream()).noneMatch(a->a.origin()==DependencyAcquisitionResult.Origin.REMOTE_REPOSITORY));
        assertEquals(first.contexts(),resolver.resolve(model,offline).contexts());
    }
    @Test void versionConflictAndDynamicSelectorsNeverPickAnArbitraryJar()throws Exception {
        var model=model(Map.of("build.gradle","dependencies { implementation 'g:library:1'\n implementation 'g:library:2'\n implementation 'g:dynamic:1.+' }\n"));
        put("library","1",false,"");put("library","2",false,"");
        var context=new GradleDependencyArtifactResolver(temp).resolve(model,offline).contexts().getFirst();
        assertTrue(context.binaries().isEmpty());assertTrue(context.problems().stream().anyMatch(p->p.reason().equals("GRADLE_VERSION_CONFLICT")));
        assertTrue(context.problems().stream().anyMatch(p->p.subject().equals("g:dynamic:1.+")));
    }
    @Test void missingParentCorruptJarAndCoordinateBudgetRemainExplicit()throws Exception {
        var model=model(Map.of("build.gradle","dependencies { implementation 'g:library:1'\n implementation 'g:other:1' }\n"));
        put("library","1",false,"<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>");
        Files.writeString(temp.resolve("g/library/1/library-1.jar"),"not-a-jar");
        var result=new GradleDependencyArtifactResolver(temp).resolve(model,new DependencyAcquisitionPolicy(1,1_000_000,10_000_000,4,List.of(),1000,1000,0));
        assertTrue(result.contexts().getFirst().binaries().isEmpty());
        assertFalse(result.problems().isEmpty());assertTrue(result.contexts().getFirst().problems().stream().anyMatch(p->p.reason().contains("CORRUPT")||p.reason().contains("MISSING")));
    }
    private void put(String artifact,String version,boolean pomOnly,String body)throws Exception {
        Path root=Files.createDirectories(temp.resolve("g/"+artifact+"/"+version));
        Files.writeString(root.resolve(artifact+"-"+version+".pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"+artifact+"</artifactId><version>"+version+"</version>"+body+"</project>");
        if(!pomOnly)try(var jar=new JarOutputStream(Files.newOutputStream(root.resolve(artifact+"-"+version+".jar")))){jar.putNextEntry(new JarEntry("fixture.txt"));jar.write(artifact.getBytes(StandardCharsets.UTF_8));jar.closeEntry();}
    }
    @Test void runtimePomDependenciesCannotContaminateCompileContextsOrCreateFalseConflicts()throws Exception {
        assertEquals("m8.4-compile-variants-v2",GradleDependencyArtifactResolver.PROVIDER.version());
        var model=model(Map.of("build.gradle","dependencies { implementation 'g:library:1'\n implementation 'g:types:2'\n testImplementation 'g:test:1' }\n"));
        put("library","1",false,"<dependencies><dependency><groupId>g</groupId><artifactId>types</artifactId><version>1</version><scope>runtime</scope></dependency><dependency><groupId>g</groupId><artifactId>api</artifactId><version>3</version></dependency></dependencies>");
        put("types","1",false,"");put("types","2",false,"");put("api","3",false,"");put("test","1",false,"");
        var result=new GradleDependencyArtifactResolver(temp).resolve(model,offline);
        assertFalse(result.contexts().isEmpty());
        for(var context:result.contexts()) {
            assertTrue(context.binaries().stream().anyMatch(b->b.entry().logicalName().equals("g:types:2@jar")));
            assertTrue(context.binaries().stream().anyMatch(b->b.entry().logicalName().equals("g:api:3@jar")));
            assertTrue(context.binaries().stream().noneMatch(b->b.entry().logicalName().equals("g:types:1@jar")));
            assertTrue(context.problems().stream().noneMatch(p->p.reason().equals("GRADLE_VERSION_CONFLICT")));
            assertTrue(context.problems().stream().anyMatch(p->p.reason().equals("GRADLE_EFFECTIVE_CONTEXT_REQUIRED")));
        }
    }
    @Test void androidAarDependenciesExtractEmbeddedClassesJarEnforceExclusionsAndResolveRemappedProjects()throws Exception {
        var model=model(Map.of(
                "settings.gradle","include ':app', ':utils'\nproject(':utils').projectDir = file('depends/utils')\nfile('modules').eachDir { def name = ':' + it.name; include name; project(name).projectDir = it }\n",
                "app/build.gradle","dependencies {\n implementation(projects.utils)\n autoImplementation project(':exoplayer')\n implementation('g:android-lib:1') { exclude group: 'g', module: 'banned' }\n}\n",
                "depends/utils/build.gradle","plugins { id 'java-library' }\n",
                "modules/exoplayer/build.gradle","plugins { id 'java-library' }\n"));
        putAar("android-lib","1","<packaging>aar</packaging><dependencies><dependency><groupId>g</groupId><artifactId>allowed</artifactId><version>1</version></dependency><dependency><groupId>g</groupId><artifactId>banned</artifactId><version>1</version></dependency></dependencies>");
        put("allowed","1",false,"");
        put("banned","1",false,"");
        var resolver=new GradleDependencyArtifactResolver(temp);
        var result=resolver.resolve(model,offline);
        var app=model.modules().stream().filter(m->m.descriptor().path().equals("app")).findFirst().orElseThrow();
        var main=result.contexts().stream().filter(c->c.sourceSet().module().equals(app.descriptor().identity())&&c.sourceSet().kind()==SourcePlanModel.Kind.MAIN).findFirst().orElseThrow();
        assertEquals(2,main.projects().size());
        assertEquals(List.of("g:android-lib:1@aar","g:allowed:1@jar"),main.binaries().stream().map(b->b.entry().logicalName()).toList());
        var aarBinary=main.binaries().getFirst();
        byte[] extractedBytes=Files.readAllBytes(aarBinary.path());
        assertEquals(aarBinary.entry().contentDigest(),ContentDigest.sha256(extractedBytes));
    }
    private void putAar(String artifact,String version,String body)throws Exception {
        Path root=Files.createDirectories(temp.resolve("g/"+artifact+"/"+version));
        Files.writeString(root.resolve(artifact+"-"+version+".pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"+artifact+"</artifactId><version>"+version+"</version>"+body+"</project>");
        var innerJar=new ByteArrayOutputStream();
        try(var jar=new JarOutputStream(innerJar)){jar.putNextEntry(new JarEntry("fixture.txt"));jar.write(artifact.getBytes(StandardCharsets.UTF_8));jar.closeEntry();}
        try(var aar=new JarOutputStream(Files.newOutputStream(root.resolve(artifact+"-"+version+".aar")))){
            aar.putNextEntry(new JarEntry("AndroidManifest.xml"));aar.write("<manifest/>".getBytes(StandardCharsets.UTF_8));aar.closeEntry();
            aar.putNextEntry(new JarEntry("classes.jar"));aar.write(innerJar.toByteArray());aar.closeEntry();
        }
    }
    private static UniversalBuildModel model(Map<String,String> text) {
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:gradle-recovery");var module=ModuleDescriptor.create(repo,".","fixture");var files=new TreeMap<String,SourceInput>();
        text.forEach((path,value)->{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);files.put(path,new SourceInput(SourceDocument.create(repo,module,path,ContentDigest.sha256(bytes),SourceClassification.OTHER),bytes));});
        var docs=files.values().stream().map(SourceInput::document).toList();var inputs=new RepositoryInputs(RepositorySnapshot.create(repo,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
        return new UniversalBuildIngestion().ingest(inputs,new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),1000,1_000_000,10000,64),Optional.empty(),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),1_000_000,1000,64));
    }
}
