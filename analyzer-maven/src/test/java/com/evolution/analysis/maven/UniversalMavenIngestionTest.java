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
}
