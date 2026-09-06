package com.evolution.analysis.maven;

import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.maven.MavenBuildModelProviderTest.*;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.buildmodel.SourcePlanModel.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourcePlanProjectionTest {
    private static final BuildModelPolicy POLICY = new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 100, 32);

    private SourcePlanModel plans(String body) {
        return effective(new MavenBuildModelProvider().build(request(Map.of("pom.xml", pom("app", body)), Map.of(), POLICY)), "pom.xml").sourcePlan();
    }
    private static String plugin(String configuration) {
        return "<plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration>"
                + configuration + "</configuration></plugin>";
    }
    private static String value(Setting setting) { return setting.value().orElseThrow(() -> new AssertionError(setting)); }

    @Test void defaultRootsAreExplicitCandidatesAndNoHostCompilerOrEncodingIsAssumed() {
        var plan = plans("");
        assertEquals(List.of(Kind.MAIN, Kind.TEST), plan.sourceSets().stream().map(SourceSetPlan::kind).toList());
        var main = plan.sourceSets().getFirst();
        var test = plan.sourceSets().getLast();
        assertEquals("src/main/java", value(main.sourceRoots().getFirst()));
        assertEquals("src/test/java", value(test.sourceRoots().getFirst()));
        assertEquals("target/classes", value(main.outputDirectory()));
        assertEquals("target/test-classes", value(test.outputDirectory()));
        assertEquals(Status.UNSPECIFIED, main.encoding().status());
        assertEquals(Status.UNSPECIFIED, main.syntaxLevel().status());
        assertTrue(main.gaps().containsAll(List.of(Gap.MISSING_ENCODING, Gap.MISSING_SOURCE_LEVEL, Gap.MISSING_PLATFORM_RELEASE)));
    }

    @Test void inheritedSettingsRemainPerModuleAndTestOverridesMainWithoutChangingItsPlan() {
        String root = pom("root", "<packaging>pom</packaging><modules><module>child</module></modules>"
                + "<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
                + "<build><pluginManagement><plugins>" + plugin("<release>${maven.compiler.release}</release><testRelease>21</testRelease>") + "</plugins></pluginManagement></build>");
        var result = new MavenBuildModelProvider().build(request(Map.of("pom.xml", root, "child/pom.xml", child("child", "")), Map.of(), POLICY));
        assertTrue(effective(result, "pom.xml").sourcePlan().sourceSets().getFirst().sourceRoots().isEmpty());
        var sets = effective(result, "child/pom.xml").sourcePlan().sourceSets();
        assertEquals("child/src/main/java", value(sets.getFirst().sourceRoots().getFirst()));
        assertEquals("17", value(sets.getFirst().platformRelease()));
        assertEquals("21", value(sets.getLast().syntaxLevel()));
        assertEquals("UTF-8", value(sets.getLast().encoding()));
        assertTrue(sets.getFirst().compilerSettings().get("release").inputs().stream().anyMatch(e -> e.logicalId().equals("workspace:pom.xml")));
    }

    @Test void customDirectoriesAndDefaultExecutionEncodingAreProjectedWithoutReadingFiles() {
        var sets = plans("<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><build>"
                + "<directory>output</directory><sourceDirectory>${project.basedir}/java</sourceDirectory><testSourceDirectory>checks</testSourceDirectory>"
                + "<plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version><configuration><source>1.8</source><target>11</target></configuration>"
                + "<executions><execution><id>default-testCompile</id><configuration><encoding>UTF-16LE</encoding><testSource>17</testSource></configuration></execution></executions>"
                + "</plugin></plugins></build>").sourceSets();
        assertEquals("java", value(sets.getFirst().sourceRoots().getFirst()));
        assertEquals("output/classes", value(sets.getFirst().outputDirectory()));
        assertEquals("8", value(sets.getFirst().syntaxLevel()));
        assertEquals("11", value(sets.getFirst().bytecodeTarget()));
        assertEquals("11", value(sets.getLast().bytecodeTarget()));
        assertEquals(Status.UNSPECIFIED, sets.getFirst().platformRelease().status()); // -target is not an API view.
        assertEquals("UTF-8", value(sets.getFirst().encoding()));
        assertEquals("UTF-16LE", value(sets.getLast().encoding()));
        assertEquals("17", value(sets.getLast().syntaxLevel()));
    }

    @Test void unsafeAndUnknownPathsAndInvalidCharsetsRemainExplicit() {
        var main = plans("<build><sourceDirectory>../outside</sourceDirectory><testSourceDirectory>${missing}/test</testSourceDirectory>"
                + "<plugins>" + plugin("<encoding>bad charset!</encoding><release>oops</release>") + "</plugins></build>").sourceSets();
        assertEquals(Status.INVALID, main.getFirst().sourceRoots().getFirst().status());
        assertTrue(main.getFirst().gaps().contains(Gap.UNSAFE_PATH));
        assertEquals(Status.UNRESOLVED, main.getLast().sourceRoots().getFirst().status());
        assertEquals(Status.INVALID, main.getFirst().encoding().status());
        assertEquals(Status.INVALID, main.getFirst().syntaxLevel().status());
    }

    @Test void customExecutionsAndGeneratorsArePreservedAndQualifyThePlan() {
        var plan = plans("<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>"
                + "<executions><execution><id>extra</id><goals><goal>compile</goal></goals><configuration><release>11</release></configuration></execution></executions></plugin>"
                + "<plugin><groupId>org.example</groupId><artifactId>generator</artifactId><version>1</version><configuration><output>somewhere</output></configuration></plugin></plugins></build>");
        assertEquals(2, plan.plugins().stream().filter(p -> !p.managementOnly()).count());
        assertTrue(plan.plugins().stream().anyMatch(p -> p.executions().stream().anyMatch(e -> e.id().equals("extra"))));
        assertTrue(plan.sourceSets().getFirst().gaps().containsAll(List.of(Gap.ADDITIONAL_COMPILER_EXECUTION, Gap.PLUGIN_EFFECTS_NOT_EVALUATED)));
        assertTrue(plan.sourceSets().getFirst().sourceRoots().stream().noneMatch(s -> s.value().equals(Optional.of("somewhere"))));
    }

    @Test void explicitConfigurationWinsWhileUserPropertiesOverridePropertyDefaults() {
        String body = "<properties><maven.compiler.release>11</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>";
        var policy = new BuildModelPolicy(List.of(), List.of(), Map.of("maven.compiler.release", "17", "encoding", "UTF-16LE"), 100_000, 100, 32);
        var provider = new MavenBuildModelProvider();
        var inherited = effective(provider.build(request(Map.of("pom.xml", pom("app", body)), Map.of(), policy)), "pom.xml").sourcePlan().sourceSets().getFirst();
        assertEquals("17", value(inherited.syntaxLevel()));
        assertEquals(Origin.USER_PROPERTY, inherited.syntaxLevel().origin());
        assertEquals("UTF-16LE", value(inherited.encoding()));
        var explicit = effective(provider.build(request(Map.of("pom.xml", pom("app", body + "<build><plugins>"
                + plugin("<release>21</release><encoding>ISO-8859-1</encoding>") + "</plugins></build>")), Map.of(), policy)), "pom.xml").sourcePlan().sourceSets().getFirst();
        assertEquals("21", value(explicit.syntaxLevel()));
        assertEquals("ISO-8859-1", value(explicit.encoding()));
    }

    @Test void profileAndSiblingPlansStayIsolatedAndResultIdentityIsRepeatable() {
        String root = pom("root", "<packaging>pom</packaging><modules><module>a</module><module>b</module></modules>");
        String a = child("a", "<profiles><profile><id>selected</id><properties><maven.compiler.release>17</maven.compiler.release></properties>"
                + "<build><plugins>" + plugin("<encoding>UTF-16BE</encoding>") + "</plugins></build></profile></profiles>");
        var policy = new BuildModelPolicy(List.of("selected"), List.of(), Map.of(), 100_000, 100, 32);
        var provider = new MavenBuildModelProvider();
        var input = request(Map.of("pom.xml", root, "a/pom.xml", a, "b/pom.xml", child("b", "")), Map.of(), policy);
        var result = provider.build(input);
        assertEquals(result.identity(), provider.build(input).identity());
        assertEquals("17", value(effective(result, "a/pom.xml").sourcePlan().sourceSets().getFirst().syntaxLevel()));
        assertEquals(Status.UNSPECIFIED, effective(result, "b/pom.xml").sourcePlan().sourceSets().getFirst().syntaxLevel().status());
        assertThrows(UnsupportedOperationException.class, () -> effective(result, "a/pom.xml").sourcePlan().sourceSets().clear());
    }

    @Test void toolchainAndJavaVersionAreNotSyntaxOrApiEvidenceAndUnknownOptionsAreVisible() {
        var sets = plans("<properties><java.version>17</java.version><maven.compiler.compilerId>eclipse</maven.compiler.compilerId></properties>"
                + "<build><plugins>" + plugin("<jdkToolchain><version>21</version></jdkToolchain><compilerArgs><arg>--release</arg><arg>11</arg></compilerArgs>")
                + "</plugins></build>").sourceSets();
        assertEquals(Status.UNSPECIFIED, sets.getFirst().syntaxLevel().status());
        assertEquals(Status.UNSPECIFIED, sets.getFirst().platformRelease().status());
        assertTrue(sets.getFirst().gaps().contains(Gap.UNSUPPORTED_COMPILER_CONFIGURATION));
        assertEquals(Status.UNSUPPORTED, sets.getFirst().compilerSettings().get("property:maven.compiler.compilerId").status());
    }

    @Test void charsetValidationIsPortableAndDoesNotConsultInstalledCharsetProviders() {
        var unsupported = plans("<build><plugins>" + plugin("<encoding>windows-1252</encoding>") + "</plugins></build>").sourceSets().getFirst();
        assertEquals(Status.UNSUPPORTED, unsupported.encoding().status());
        assertEquals(Optional.of("windows-1252"), unsupported.encoding().expression());
        var invalid = plans("<build><plugins>" + plugin("<encoding>bad charset!</encoding>") + "</plugins></build>").sourceSets().getFirst();
        assertEquals(Status.INVALID, invalid.encoding().status());
        assertEquals("UTF-8", value(plans("<build><plugins>" + plugin("<encoding>utf8</encoding>") + "</plugins></build>").sourceSets().getFirst().encoding()));
    }

    @Test void explicitAggregatorDirectoriesAreKeptAndNestedMainTestRootsAreQualified() {
        var sets = plans("<packaging>pom</packaging><build><sourceDirectory>sources</sourceDirectory><testSourceDirectory>sources/tests</testSourceDirectory></build>").sourceSets();
        assertEquals("sources", value(sets.getFirst().sourceRoots().getFirst()));
        assertTrue(sets.getFirst().gaps().contains(Gap.OVERLAPPING_SOURCE_ROOTS));
        assertTrue(sets.getLast().gaps().contains(Gap.OVERLAPPING_SOURCE_ROOTS));
    }

    @Test void planGapsAreVisibleAtTheResultBoundaryEvenWhenModelConstructionSucceeded() {
        var result = new MavenBuildModelProvider().build(request(Map.of("pom.xml", pom("app", "")), Map.of(), POLICY));
        assertTrue(result.problems().isEmpty());
        assertTrue(result.hasGaps());
        assertEquals(Origin.MAVEN_CONVENTION, effective(result, "pom.xml").sourcePlan().sourceSets().getFirst().sourceRoots().getFirst().origin());
    }

    @Test void managedDefaultsMergeWithActivePluginAndGoalOverridesAndPreservePathEvidence() {
        var sets = plans("<build><directory>out</directory><resources><resource><directory>assets</directory></resource></resources>"
                + "<pluginManagement><plugins>" + plugin("<release>17</release><encoding>UTF-8</encoding>") + "</plugins></pluginManagement>"
                + "<plugins><plugin><artifactId>maven-compiler-plugin</artifactId><executions><execution><id>default-testCompile</id>"
                + "<configuration><testRelease>21</testRelease><generatedTestSourcesDirectory>${project.build.directory}/generated-checks</generatedTestSourcesDirectory></configuration>"
                + "</execution></executions></plugin></plugins></build>").sourceSets();
        assertEquals("17", value(sets.getFirst().syntaxLevel()));
        assertEquals("21", value(sets.getLast().syntaxLevel()));
        assertEquals("UTF-8", value(sets.getLast().encoding()));
        assertEquals("assets", value(sets.getFirst().resourceRoots().getFirst()));
        assertEquals("out/generated-checks", value(sets.getLast().generatedSourceHints().getFirst()));
        assertFalse(sets.getFirst().outputDirectory().inputs().isEmpty());
    }
}
