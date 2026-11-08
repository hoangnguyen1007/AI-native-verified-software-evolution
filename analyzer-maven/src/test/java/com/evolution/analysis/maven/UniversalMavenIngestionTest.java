package com.evolution.analysis.maven;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UniversalMavenIngestionTest {
    @Test void routerRetainsMavenInheritanceAndBomEvidenceAcrossModules() {
        String root="<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>app</module></modules>"
                +"<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
                +"<dependencyManagement><dependencies><dependency><groupId>demo</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>";
        String child="<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>app</artifactId>"
                +"<dependencies><dependency><groupId>demo</groupId><artifactId>lib</artifactId></dependency></dependencies></project>";
        String bom="<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>bom</artifactId><version>1</version><packaging>pom</packaging>"
                +"<dependencyManagement><dependencies><dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>2.0</version></dependency></dependencies></dependencyManagement></project>";
        var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/universal-maven.git");var module=ModuleDescriptor.create(repo,".","root");
        Map<String,SourceInput> files=new TreeMap<>();Map.of("pom.xml",root,"app/pom.xml",child).forEach((p,t)->{
            byte[] bytes=t.getBytes(StandardCharsets.UTF_8);files.put(p,new SourceInput(SourceDocument.create(repo,module,p,ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));});
        var docs=files.values().stream().map(SourceInput::document).toList();
        var input=new RepositoryInputs(RepositorySnapshot.create(repo,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
        var result=new UniversalBuildIngestion().ingest(input,UniversalBuildIngestion.Policy.defaults(),Optional.of(new MavenBuildModelProvider()),
                Map.of(new MavenCoordinate("demo","bom","1"),new PomInput(bom.getBytes(StandardCharsets.UTF_8))),new BuildModelPolicy(List.of(),List.of(),Map.of(),100000,100,32));
        assertEquals(2,result.modules().size());assertEquals(1,result.mavenModels().size());
        var app=result.mavenModels().getFirst().modules().stream().filter(m->m.pomPath().equals("app/pom.xml")).findFirst().orElseThrow().effectivePom().orElseThrow();
        assertEquals("2.0",app.dependencies().getFirst().version());assertEquals("21",app.sourcePlan().sourceSets().getFirst().syntaxLevel().value().orElseThrow());
        assertTrue(app.inputs().size()>=2);
    }
    @Test void workspaceImportedBomWithRevisionPropertyResolvesWithoutExternalArtifactBundle() {
        String root="<project><modelVersion>4.0.0</modelVersion><groupId>io.demo</groupId><artifactId>demo-parent</artifactId><version>${revision}</version><packaging>pom</packaging>"
                +"<properties><revision>2.1.0-SNAPSHOT</revision><java.version>17</java.version><maven.compiler.source>${java.version}</maven.compiler.source><maven.compiler.target>${java.version}</maven.compiler.target><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
                +"<modules><module>demo-bom</module><module>demo-core</module><module>demo-app</module></modules>"
                +"<dependencyManagement><dependencies><dependency><groupId>io.demo</groupId><artifactId>demo-bom</artifactId><version>${revision}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>";
        String bom="<project><modelVersion>4.0.0</modelVersion><groupId>io.demo</groupId><artifactId>demo-bom</artifactId><version>${revision}</version><packaging>pom</packaging>"
                +"<properties><revision>2.1.0-SNAPSHOT</revision></properties>"
                +"<dependencyManagement><dependencies><dependency><groupId>io.demo</groupId><artifactId>demo-core</artifactId><version>${project.version}</version></dependency></dependencies></dependencyManagement></project>";
        String core="<project><modelVersion>4.0.0</modelVersion><parent><groupId>io.demo</groupId><artifactId>demo-parent</artifactId><version>${revision}</version><relativePath>../pom.xml</relativePath></parent><artifactId>demo-core</artifactId></project>";
        String app="<project><modelVersion>4.0.0</modelVersion><parent><groupId>io.demo</groupId><artifactId>demo-parent</artifactId><version>${revision}</version><relativePath>../pom.xml</relativePath></parent><artifactId>demo-app</artifactId>"
                +"<dependencies><dependency><groupId>io.demo</groupId><artifactId>demo-core</artifactId></dependency></dependencies></project>";
        var repo=RepositoryIdentity.fromCanonicalCoordinate("https://example.test/revision-bom.git");var module=ModuleDescriptor.create(repo,".","demo-parent");
        Map<String,SourceInput> files=new TreeMap<>();
        Map.of("pom.xml",root,"demo-bom/pom.xml",bom,"demo-core/pom.xml",core,"demo-app/pom.xml",app).forEach((p,t)->{
            byte[] bytes=t.getBytes(StandardCharsets.UTF_8);files.put(p,new SourceInput(SourceDocument.create(repo,module,p,ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));});
        var docs=files.values().stream().map(SourceInput::document).toList();
        var input=new RepositoryInputs(RepositorySnapshot.create(repo,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
        var result=new UniversalBuildIngestion().ingest(input,UniversalBuildIngestion.Policy.defaults(),Optional.of(new MavenBuildModelProvider()),
                Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),100000,100,32));
        assertTrue(result.mavenModels().getFirst().problems().isEmpty(),()->result.mavenModels().getFirst().problems().toString());
        var appPom=result.mavenModels().getFirst().modules().stream().filter(m->m.pomPath().equals("demo-app/pom.xml")).findFirst().orElseThrow().effectivePom().orElseThrow();
        assertEquals("2.1.0-SNAPSHOT",appPom.coordinate().version());
        assertEquals("2.1.0-SNAPSHOT",appPom.dependencies().getFirst().version());
        assertTrue(appPom.inputs().stream().anyMatch(e->e.logicalId().equals("workspace:demo-bom/pom.xml")));
    }
}
