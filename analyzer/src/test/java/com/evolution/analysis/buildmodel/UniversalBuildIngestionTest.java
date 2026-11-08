package com.evolution.analysis.buildmodel;
import com.evolution.analysis.ingestion.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UniversalBuildIngestionTest {
    @Test void literalMapCoordinatesKeepBuildScriptEvidenceWhenSettingsArePresent() {
        var model=load(Map.of("settings.gradle","include ':api'\n","api/build.gradle","dependencies { implementation group: 'g', name: 'lib', version: '1'\n implementation(group: 'g', name: 'other', version: '2') }\n"));
        var module=model.modules().stream().filter(m->m.descriptor().path().equals("api")).findFirst().orElseThrow();
        assertEquals(List.of("g:lib:1","g:other:2"),module.dependencies().stream().map(d->d.coordinate().orElseThrow().notation()).toList());
        assertTrue(module.dependencies().stream().allMatch(d->d.evidence().stream().anyMatch(e->e.logicalId().equals("api/build.gradle"))));
    }
    @Test void bootBomExpressionNeedsTheActualImportAndUniqueFixedPluginDeclaration() {
        String script="import org.springframework.boot.gradle.plugin.SpringBootPlugin\nplugins { alias(libs.plugins.spring.boot) apply false }\ndependencies { api platform(SpringBootPlugin.BOM_COORDINATES) }\n";
        var files=Map.of("build.gradle",script,"gradle/libs.versions.toml","[versions]\nboot='3.4.1'\n[plugins]\nspring-boot={ id='org.springframework.boot', version.ref='boot' }\n");
        var dependencies=load(files).modules().getFirst().dependencies();
        assertEquals("org.springframework.boot:org.springframework.boot.gradle.plugin:3.4.1",dependencies.getFirst().coordinate().orElseThrow().notation());
        assertEquals("org.springframework.boot:spring-boot-dependencies:3.4.1",dependencies.getLast().coordinate().orElseThrow().notation());
        assertEquals("SpringBootPlugin.BOM_COORDINATES",dependencies.getLast().notation());
        assertTrue(load(Map.of("build.gradle",script.replace("import org.springframework.boot.gradle.plugin.SpringBootPlugin\n",""),"gradle/libs.versions.toml",files.get("gradle/libs.versions.toml"))).modules().getFirst().dependencies().getLast().coordinate().isEmpty());
        var malformed=assertDoesNotThrow(()->load(Map.of("build.gradle","import org.springframework.boot.gradle.plugin.SpringBootPlugin\nplugins { id 'org.springframework.boot' version 'bad version' }\ndependencies { api platform(SpringBootPlugin.BOM_COORDINATES) }\n")));
        assertTrue(malformed.modules().getFirst().dependencies().getLast().coordinate().isEmpty());
    }
    @Test void platformImportsAndConstraintsRemainDistinctFromRuntimeLibraries() {
        var model=load(Map.of("build.gradle.kts","dependencies { api(platform(\"g:bom:1\"))\n implementation(platform(project(\":platform\")))\n constraints { api(\"g:library:2\") }\n implementation(\"g:library\") }\n"));
        var deps=model.modules().getFirst().dependencies();
        assertEquals(List.of("api.platform","implementation.platform","api.constraint","implementation"),deps.stream().map(UniversalBuildModel.Dependency::configuration).toList());
        assertEquals("g:bom:1",deps.getFirst().coordinate().orElseThrow().notation());
        assertEquals(Optional.of(":platform"),deps.get(1).projectPath());
        assertEquals("g:library:2",deps.get(2).coordinate().orElseThrow().notation());
        assertEquals("g:library",deps.get(3).notation());assertTrue(deps.get(3).coordinate().isEmpty());
    }
    @Test void nestedGradleIncludesRetainImplicitParentProjectAndDynamicPathsRemainGaps(){
        var nested=load(Map.of("settings.gradle","include ':platform:api'\nproject(':platform:api').projectDir = new File(settingsDir, 'services/api')\n"));
        assertEquals(Set.of(".","platform","services/api"),nested.modules().stream().map(m->m.descriptor().path()).collect(java.util.stream.Collectors.toSet()));
        var dynamic=load(Map.of("settings.gradle.kts","include(\":api\")\nproject(\":api\").projectDir = file(\"$custom/api\")\n"));
        assertEquals(Set.of(".","api"),dynamic.modules().stream().map(m->m.descriptor().path()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(dynamic.issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.DYNAMIC_BUILD_LOGIC));
        var unsafe=load(Map.of("settings.gradle","include ':api'\nproject(':api').projectDir = file('../escape')\n"));
        assertTrue(unsafe.issues().stream().anyMatch(i->i.reason()==IngestionEvidence.Reason.UNSAFE_PATH));
        var parent=load(Map.of("settings.gradle","include ':platform:api'\nproject(':platform').projectDir = file('shared')\nincludeBuild \"$external/build\"\n"));
        assertEquals("platform",parent.modules().stream().filter(m->m.descriptor().path().equals("shared")).findFirst().orElseThrow().descriptor().displayName());
        assertTrue(parent.modules().stream().noneMatch(m->m.descriptor().path().contains("$")));
    }
    @Test void gradleProjectDirectoryRemappingAndIncludedBuildsKeepTheirOwnModules() {
        var r=load(Map.of("settings.gradle.kts","include(\":api\")\nproject(\":api\").projectDir = file(\"modules/http\")\nincludeBuild(\"build-logic\")\n",
                "build.gradle.kts","plugins { java }\n",
                "modules/http/src/main/java/Api.java","interface Api {}",
                "build-logic/settings.gradle.kts","rootProject.name = \"logic\"\n",
                "build-logic/src/main/java/Plugin.java","class Plugin {}"));
        assertEquals(Set.of(".","modules/http","build-logic"),r.modules().stream().map(m->m.descriptor().path()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(UniversalBuildModel.Tool.GRADLE_KOTLIN,r.modules().stream().filter(m->m.descriptor().path().equals("modules/http")).findFirst().orElseThrow().tool());
        assertEquals("api",r.modules().stream().filter(m->m.descriptor().path().equals("modules/http")).findFirst().orElseThrow().descriptor().displayName());
    }
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

    @Test void tripleQuotedMultilineStringsAndEscapesParseWithoutMalformedInputAndPreserveKmpSourceSets() {
        var r = load(Map.of(
                "settings.gradle.kts", "pluginManagement { repositories { google(); mavenCentral() } }\n"
                        + "dependencyResolutionManagement { repositories { google(); mavenCentral() } }\n"
                        + "enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")\n"
                        + "include(\":shared\", \":app\")\n",
                "shared/build.gradle.kts", "plugins { alias(libs.plugins.kotlin.multiplatform) }\n"
                        + "val generated = \"\"\"\n"
                        + "    package org.schabi.newpipe.shared\n"
                        + "    const val USER_AGENT = \"Mozilla/5.0 (Windows NT 10.0; rv:128.0)\"\n"
                        + "    fun block() { println(\"hello \\$world\") }\n"
                        + "\"\"\".trimIndent()\n"
                        + "kotlin {\n"
                        + "    jvmToolchain(21)\n"
                        + "    sourceSets {\n"
                        + "        commonMain {\n"
                        + "            dependencies {\n"
                        + "                implementation(libs.kotlin.stdlib)\n"
                        + "            }\n"
                        + "        }\n"
                        + "        commonTest.dependencies {\n"
                        + "            implementation(libs.kotlin.test)\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "shared/src/commonMain/kotlin/Shared.kt", "package org.schabi.newpipe.shared\nclass Shared\n",
                "app/build.gradle.kts", "plugins { id(\"com.android.application\") }\n"
                        + "val escaped = \"price: \\$100\\u0020ok\\b\\f\"\n"
                        + "android {\n"
                        + "    namespace = \"org.schabi.newpipe\"\n"
                        + "    compileSdk = 35\n"
                        + "    compileOptions {\n"
                        + "        sourceCompatibility = JavaVersion.VERSION_17\n"
                        + "        targetCompatibility = JavaVersion.VERSION_17\n"
                        + "        encoding = \"UTF-8\"\n"
                        + "    }\n"
                        + "}\n"
                        + "dependencies {\n"
                        + "    implementation(projects.shared)\n"
                        + "    ksp(libs.room.compiler)\n"
                        + "    coreLibraryDesugaring(\"com.android.tools:desugar_jdk_libs:2.1.2\")\n"
                        + "    debugImplementation(\"com.squareup.leakcanary:leakcanary-android:2.14\") {\n"
                        + "        exclude(group = \"org.jetbrains\", module = \"annotations\")\n"
                        + "    }\n"
                        + "}\n",
                "gradle/libs.versions.toml", "[versions]\nkotlin = \"2.1.0\"\nroom = \"2.6.1\"\n"
                        + "[libraries]\nkotlin-stdlib = { module = \"org.jetbrains.kotlin:kotlin-stdlib\", version.ref = \"kotlin\" }\n"
                        + "kotlin-test = { module = \"org.jetbrains.kotlin:kotlin-test\", version.ref = \"kotlin\" }\n"
                        + "room-compiler = { module = \"androidx.room:room-compiler\", version.ref = \"room\" }\n"
                        + "[plugins]\nkotlin-multiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }\n"));
        assertTrue(r.issues().stream().noneMatch(i -> i.reason() == IngestionEvidence.Reason.MALFORMED_INPUT));
        var shared = r.modules().stream().filter(m -> m.descriptor().path().equals("shared")).findFirst().orElseThrow();
        assertEquals("21", shared.sourcePlan().sourceSets().getFirst().syntaxLevel().value().orElseThrow());
        assertTrue(shared.sourcePlan().sourceSets().getFirst().sourceRoots().stream()
                .flatMap(s -> s.value().stream()).toList().contains("shared/src/commonMain/kotlin"));
        assertEquals(List.of("org.jetbrains.kotlin:kotlin-stdlib:2.1.0", "org.jetbrains.kotlin:kotlin-test:2.1.0"),
                shared.dependencies().stream().map(d -> d.coordinate().orElseThrow().notation()).toList());
        var app = r.modules().stream().filter(m -> m.descriptor().path().equals("app")).findFirst().orElseThrow();
        assertEquals("17", app.sourcePlan().sourceSets().getFirst().syntaxLevel().value().orElseThrow());
        assertEquals("UTF-8", app.sourcePlan().sourceSets().getFirst().encoding().value().orElseThrow());
        assertEquals(Optional.of(":shared"), app.dependencies().getFirst().projectPath());
        var debugDep = app.dependencies().stream().filter(d -> d.configuration().equals("debugImplementation")).findFirst().orElseThrow();
        assertEquals(List.of("org.jetbrains:annotations"), debugDep.exclusions());
    }

    @Test void settingsEachDirSubprojectsVariablesAndSpringBootDependencyManagementResolveDeterministically() {
        var r = load(Map.of(
                "settings.gradle", "include ':app', ':utils'\n"
                        + "project(':utils').projectDir = file('depends/utils')\n"
                        + "file('modules').eachDir {\n"
                        + "    def name = ':' + it.name\n"
                        + "    include name\n"
                        + "    project(name).projectDir = it\n"
                        + "}\n",
                "build.gradle", "subprojects {\n"
                        + "    group = 'me.aap.fermata'\n"
                        + "    version = '1.9.9'\n"
                        + "    android {\n"
                        + "        compileOptions {\n"
                        + "            sourceCompatibility = JavaVersion.VERSION_17\n"
                        + "            targetCompatibility = JavaVersion.VERSION_17\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n",
                "app/build.gradle", "plugins {\n"
                        + "    id 'org.springframework.boot' version '3.4.0'\n"
                        + "    id 'io.spring.dependency-management' version '1.1.6'\n"
                        + "}\n"
                        + "def boostVersion = '1.40.0'\n"
                        + "dependencies {\n"
                        + "    implementation 'androidx.appcompat:appcompat:1.7.0', 'com.google.android.material:material:1.12.0'\n"
                        + "    implementation(\"org.webjars.npm:boost:$boostVersion\") { exclude group: 'org.webjars' }\n"
                        + "    autoImplementation project(':exoplayer')\n"
                        + "}\n",
                "app/src/auto/java/AutoMain.java", "class AutoMain {}",
                "depends/utils/build.gradle", "plugins { id 'com.android.library' }\n",
                "modules/exoplayer/build.gradle", "plugins { id 'com.android.library' }\n",
                "modules/felex/build.gradle", "plugins { id 'com.android.library' }\n"));
        assertEquals(Set.of(".", "app", "depends/utils", "modules/exoplayer", "modules/felex"),
                r.modules().stream().map(m -> m.descriptor().path()).collect(java.util.stream.Collectors.toSet()));
        var app = r.modules().stream().filter(m -> m.descriptor().path().equals("app")).findFirst().orElseThrow();
        assertEquals(new MavenCoordinate("me.aap.fermata", "app", "1.9.9"), app.coordinate().orElseThrow());
        assertEquals("17", app.sourcePlan().sourceSets().getFirst().syntaxLevel().value().orElseThrow());
        assertTrue(app.sourcePlan().sourceSets().getFirst().sourceRoots().stream()
                .flatMap(s -> s.value().stream()).toList().contains("app/src/auto/java"));
        assertTrue(app.dependencies().stream().anyMatch(d -> d.configuration().equals("implementation.platform")
                && d.coordinate().equals(Optional.of(new MavenCoordinate("org.springframework.boot", "spring-boot-dependencies", "3.4.0")))));
        assertTrue(app.dependencies().stream().anyMatch(d -> d.coordinate().equals(Optional.of(new MavenCoordinate("org.webjars.npm", "boost", "1.40.0")))
                && d.exclusions().equals(List.of("org.webjars:*"))));
        assertTrue(app.dependencies().stream().anyMatch(d -> d.configuration().equals("autoImplementation")
                && d.projectPath().equals(Optional.of(":exoplayer"))));
    }
}
