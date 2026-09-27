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
    @Test void literalCustomSourceSetIsInventoriedWithUnknownSemanticRole() {
        var r = load(Map.of("build.gradle.kts",
                "plugins { java }\nsourceSets { integrationTest { java { srcDir(\"src/it/java\") } } }\n"));
        var plans = r.modules().getFirst().sourcePlan().sourceSets();
        assertEquals(3, plans.size());
        var custom = plans.get(2);
        assertEquals(SourcePlanModel.Kind.CUSTOM, custom.kind());
        assertEquals("integrationTest", custom.name());
        assertEquals(com.evolution.analysis.contract.source.SourceClassification.OTHER,
                custom.semanticRole());
        assertEquals(List.of("src/integrationTest/java", "src/it/java"), custom.sourceRoots()
                .stream().flatMap(s -> s.value().stream()).toList());
        assertTrue(custom.gaps().contains(SourcePlanModel.Gap.UNRESOLVED_SOURCE_ROLE));
        assertTrue(r.issues().stream().anyMatch(i -> i.reason()
                == IngestionEvidence.Reason.CUSTOM_SOURCE_SET_ROLE_UNRESOLVED));
        assertTrue(r.issues().stream().noneMatch(i -> i.reason()
                == IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }
    @Test void unqualifiedCustomSrcDirDoesNotBecomeJavaRoot() {
        var r = load(Map.of("build.gradle",
                "sourceSets { integrationTest { srcDir('unknown') } }\n"));
        var custom = r.modules().getFirst().sourcePlan().sourceSets().get(2);
        assertEquals(List.of("src/integrationTest/java"), custom.sourceRoots()
                .stream().flatMap(s -> s.value().stream()).toList());
        assertTrue(r.issues().stream().anyMatch(i -> i.reason()
                == IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }
    @Test void emptyDeclaredCustomSetStillHasASeparateInventoryRow() {
        var r = load(Map.of("build.gradle", "sourceSets { integrationTest {} }\n"));
        assertEquals(3, r.modules().getFirst().sourcePlan().sourceSets().size());
        assertEquals("integrationTest", r.modules().getFirst().sourcePlan().sourceSets().get(2).name());
        assertTrue(r.issues().stream().anyMatch(i -> i.reason()
                == IngestionEvidence.Reason.CUSTOM_SOURCE_SET_ROLE_UNRESOLVED));
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

    @Test void literalCatalogAliasesAndBundlesRemainDeclaredSelectors() {
        var r = load(Map.of(
                "build.gradle.kts", "plugins { java }\ndependencies { implementation(libs.core.api)\n testImplementation(libs.bundles.testing) }\n",
                "gradle/libs.versions.toml", "[versions]\ncore = \"1.2\"\n[libraries]\ncore-api = { module = \"g:core\", version.ref = \"core\" }\nunit = \"g:unit:2\"\nassertion = \"g:assertion:3\"\n[bundles]\ntesting = [\"unit\", \"assertion\"]\n"));
        var module = r.modules().getFirst();
        assertEquals(List.of("g:core:1.2", "g:unit:2", "g:assertion:3"),
                module.dependencies().stream().map(d -> d.coordinate().orElseThrow().notation()).toList());
        assertEquals(List.of("implementation", "testImplementation", "testImplementation"),
                module.dependencies().stream().map(UniversalBuildModel.Dependency::configuration).toList());
        assertTrue(module.evidence().stream().anyMatch(e -> e.logicalId().equals("gradle/libs.versions.toml")));
        assertEquals(2, module.dependencies().getFirst().evidence().size());
        assertTrue(r.issues().stream().noneMatch(i -> i.reason() == IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }

    @Test void unknownCatalogAliasRemainsAnUnresolvedDependency() {
        var r = load(Map.of("build.gradle", "dependencies { implementation libs.missing }\n",
                "gradle/libs.versions.toml", "[libraries]\nknown = \"g:known:1\"\n"));
        assertEquals(1, r.modules().getFirst().dependencies().size());
        assertTrue(r.modules().getFirst().dependencies().getFirst().coordinate().isEmpty());
        assertTrue(r.issues().stream().anyMatch(i -> i.reason() == IngestionEvidence.Reason.DEPENDENCY_VERSION_UNRESOLVED));
    }

    @Test void collidingCatalogAccessorsCannotSelectAnArbitraryCoordinate() {
        var r = load(Map.of("build.gradle", "dependencies { implementation libs.core.api }\n",
                "gradle/libs.versions.toml", "[libraries]\ncore-api = \"g:first:1\"\ncore_api = \"g:second:1\"\n"));
        assertTrue(r.modules().getFirst().dependencies().getFirst().coordinate().isEmpty());
        assertTrue(r.issues().stream().anyMatch(i -> i.reason() == IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }
    @Test void duplicateCatalogKeysCannotPickFirstVersionOrLibrary() {
        var libraries = load(Map.of("build.gradle", "dependencies { implementation libs.duplicate }\n",
                "gradle/libs.versions.toml", "[libraries]\nduplicate = \"g:first:1\"\n"
                        + "duplicate = \"g:second:2\"\n"));
        assertTrue(libraries.modules().getFirst().dependencies().getFirst().coordinate().isEmpty());
        var versions = load(Map.of("build.gradle", "dependencies { implementation libs.core }\n",
                "gradle/libs.versions.toml", "[versions]\nselected = \"1\"\nselected = \"2\"\n"
                        + "[libraries]\ncore = { module = \"g:core\", version.ref = \"selected\" }\n"));
        assertTrue(versions.modules().getFirst().dependencies().getFirst().coordinate().isEmpty());
        assertTrue(versions.issues().stream().anyMatch(i -> i.reason()
                == IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
    }

    @Test void rangeAndSnapshotSelectorsRemainUnresolvedWithoutCapturedSelection() {
        var r = load(Map.of("build.gradle", "dependencies { implementation 'g:range:[1,2]'\n"
                + "implementation 'g:snapshot:1.0-SNAPSHOT' }\n"));
        assertEquals(2, r.modules().getFirst().dependencies().size());
        assertTrue(r.modules().getFirst().dependencies().stream().allMatch(d -> d.coordinate().isEmpty()));
        assertTrue(r.issues().stream().anyMatch(i -> i.reason() == IngestionEvidence.Reason.DEPENDENCY_VERSION_UNRESOLVED));
        var catalog = load(Map.of("build.gradle", "dependencies { implementation libs.mutable }\n",
                "gradle/libs.versions.toml", "[libraries]\nmutable = \"g:mutable:1.0-SNAPSHOT\"\n"));
        assertTrue(catalog.modules().getFirst().dependencies().getFirst().coordinate().isEmpty());
    }
}
