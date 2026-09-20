package com.evolution.analysis.buildmodel;
import com.evolution.analysis.ingestion.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UniversalBuildIngestionTest {
    @Test void plainJavaGetsSourcePlansWithoutInventedCoordinates() {
        var r=load(Map.of("src/example/C.java","package example; class C {}"));
        assertEquals(UniversalBuildModel.Tool.PLAIN_JAVA,r.modules().getFirst().tool());
        assertTrue(r.modules().getFirst().coordinate().isEmpty());
        assertEquals("src",r.modules().getFirst().sourcePlan().sourceSets().getFirst().sourceRoots().getFirst().value().orElseThrow());
        assertFalse(r.gaps().isEmpty());
    }
    @Test void commentsStringsAndConditionalBlocksCannotInventDependencies() {
        var r=load(Map.of("build.gradle","// dependencies { implementation 'bad:fake:1' }\nif (false) { dependencies { implementation 'bad:fake:2' } }\ndependencies { implementation 'good:lib:1' }\n"));
        assertEquals(1,r.modules().getFirst().dependencies().size());
        assertEquals("good",r.modules().getFirst().dependencies().getFirst().coordinate().orElseThrow().groupId());
        assertTrue(r.issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }
    @Test void dynamicVersionsPathEscapesAndMalformedScriptsStayExplicit() {
        var r=load(Map.of("settings.gradle","include '../escape'\n", "build.gradle","dependencies { implementation 'g:a:1.+' }"));
        assertEquals(1,r.modules().size());assertTrue(r.modules().getFirst().dependencies().getFirst().coordinate().isEmpty());
        assertTrue(r.issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.UNSAFE_PATH));
        assertTrue(load(Map.of("build.gradle","dependencies { implementation 'x" )).issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.MALFORMED_INPUT));
    }
    @Test void configuredSourceSetsAndToolchainHaveExactEvidence() {
        var r=load(Map.of("build.gradle.kts","plugins { java }\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\ntasks.withType<JavaCompile>().configureEach { options.encoding = \"UTF-8\" }\nsourceSets { main { java { srcDirs(\"code\") } resources { srcDir(\"conf\") } } }\n"));
        var source=r.modules().getFirst().sourcePlan().sourceSets().getFirst();
        assertEquals("21",source.syntaxLevel().value().orElseThrow());assertEquals("UTF-8",source.encoding().value().orElseThrow());
        assertEquals(List.of("src/main/java","code"),source.sourceRoots().stream().flatMap(s->s.value().stream()).toList());
        assertEquals(List.of("src/main/resources","conf"),source.resourceRoots().stream().flatMap(s->s.value().stream()).toList());
        assertEquals(SourcePlanModel.Origin.EFFECTIVE_MODEL,source.syntaxLevel().origin());
        assertEquals(SourcePlanModel.Origin.EFFECTIVE_MODEL,source.sourceRoots().getLast().origin());
        assertFalse(source.syntaxLevel().inputs().isEmpty());
    }
    @Test void customSetsAndLookalikeBlocksDoNotChangeMainCompilerFacts() {
        var r=load(Map.of("build.gradle.kts","other { java { sourceCompatibility = 21 } }\nsourceSets { integrationTest { java { srcDir(\"integration\") } } }\n"));
        assertTrue(r.modules().getFirst().sourcePlan().sourceSets().getFirst().syntaxLevel().value().isEmpty());
        assertTrue(r.issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }
    @Test void scriptAndIncludedProjectLimitsRemainExplicit() {
        var input=IngestionFixtures.inputs(Map.of("settings.gradle","include ':a', ':b', ':c'\n","build.gradle","plugins { id 'java' }\n"));
        var r=new UniversalBuildIngestion().ingest(input,new UniversalBuildIngestion.Policy(Optional.empty(),Optional.empty(),Optional.empty(),2,1000,100,8),Optional.empty(),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),1000,100,16));
        assertTrue(r.modules().size()<=2);assertTrue(r.issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.INPUT_LIMIT));
    }
    @Test void unresolvedAssignmentsAreGapsRatherThanCrashesOrFakeCoordinates() {
        var r=load(Map.of("build.gradle","group = 'org.${organization}'\nversion = '1.0'\nsourceCompatibility = obtainFromNetwork(21)\n"));
        assertTrue(r.modules().getFirst().coordinate().isEmpty());assertTrue(r.modules().getFirst().sourcePlan().sourceSets().getFirst().syntaxLevel().value().isEmpty());
    }
    private UniversalBuildModel load(Map<String,String> files) {
        return new UniversalBuildIngestion().ingest(IngestionFixtures.inputs(files),UniversalBuildIngestion.Policy.defaults(),Optional.empty(),Map.of(),
                new BuildModelPolicy(List.of(),List.of(),Map.of(),1_000_000,100,64));
    }
    @Test void discoversKotlinMultiProjectAndLiteralDependenciesWithoutExecution() {
        var r=load(Map.of("settings.gradle.kts","rootProject.name = \"demo\"\ninclude(\":api\",\":service\")\n",
                "build.gradle.kts","plugins { java }\ngroup = \"org.example\"\nversion = \"1.0\"\n",
                "api/build.gradle.kts","plugins { `java-library` }\ndependencies { api(\"org.example:lib:2.0\") }\n",
                "service/build.gradle","plugins { id 'java' }\ndependencies { implementation project(':api') }\n"));
        assertEquals(List.of(".","api","service"),r.modules().stream().map(m -> m.descriptor().path()).toList());
        assertEquals(new MavenCoordinate("org.example","lib","2.0"),r.modules().get(1).dependencies().getFirst().coordinate().orElseThrow());
        assertEquals(":api",r.modules().get(2).dependencies().getFirst().projectPath().orElseThrow());
    }
}
