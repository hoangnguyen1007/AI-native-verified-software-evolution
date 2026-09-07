package com.evolution.analysis.filesystem;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.maven.MavenBuildModelProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemRepositoryAcquirerTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/filesystem-fixture.git");
    private static final RepositoryAcquisitionPolicy POLICY = new RepositoryAcquisitionPolicy(
            100, 100, 100_000, 100_000, 20, List.of(".git"));

    @TempDir Path temporary;

    @Test
    void acquiresACompleteDeterministicInventoryAndExactPomBytes() throws Exception {
        write("pom.xml", "<project/>");
        write("module/pom.xml", "<project><artifactId>module</artifactId></project>");
        write("module/src/main/java/App.java", "class App {}\r\n");
        write(".git/config", "repository metadata is outside the explicit selection");

        var result = acquire(POLICY);
        var snapshot = result.snapshot().orElseThrow();
        assertEquals(List.of("module/pom.xml", "module/src/main/java/App.java", "pom.xml"),
                snapshot.files().stream().map(file -> file.path()).toList());
        assertTrue(snapshot.documents().isEmpty(), "M3.3 must not decode candidate sources");
        assertEquals(Set.of("pom.xml", "module/pom.xml"), result.workspacePoms().keySet());
        assertEquals(Set.of(".", "module", "module/src", "module/src/main", "module/src/main/java"),
                new HashSet<>(result.directories()));
        assertTrue(result.problems().isEmpty(), () -> result.problems().toString());
        assertEquals(RepositoryAcquisitionResult.Completion.COMPLETE, result.completion());
        assertTrue(result.attempts().stream().anyMatch(attempt -> attempt.outcome()
                == RepositoryAcquisitionResult.Outcome.EXCLUDED && attempt.subject().equals(".git")));

        var repeated = acquire(POLICY);
        assertEquals(result.identity(), repeated.identity());
        assertEquals(snapshot.identity(), repeated.snapshot().orElseThrow().identity());

        Files.writeString(repositoryRoot().resolve("pom.xml"), "changed", StandardCharsets.UTF_8);
        assertEquals("<project/>", new String(result.workspacePoms().get("pom.xml").bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void refusesLinkedDirectoriesWithoutReadingTheirTargets() throws Exception {
        Path outside = temporary.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("Outside.java"), "class Outside {}", StandardCharsets.UTF_8);
        Files.createDirectories(repositoryRoot());
        write("pom.xml", "<project/>");
        Path link = repositoryRoot().resolve("linked");
        createDirectoryLink(link, outside);
        try {
            var nested = acquire(POLICY);
            assertTrue(nested.snapshot().isEmpty());
            assertEquals(RepositoryAcquisitionResult.Completion.PARTIAL, nested.completion());
            assertTrue(nested.problems().stream().anyMatch(problem -> problem.reason()
                    == RepositoryAcquisitionResult.Reason.SYMBOLIC_LINK
                            || problem.reason() == RepositoryAcquisitionResult.Reason.PATH_OUTSIDE_ROOT));
            assertFalse(nested.files().stream().anyMatch(file -> file.path().contains("Outside.java")));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void rejectsUnavailableNonDirectoryAndLinkedRoots() throws Exception {
        var acquirer = new FilesystemRepositoryAcquirer();
        var missing = acquirer.acquire(temporary.resolve("missing"), request(POLICY));
        assertEquals(RepositoryAcquisitionResult.Completion.FAILED, missing.completion());
        assertEquals(RepositoryAcquisitionResult.Reason.ROOT_NOT_FOUND, missing.problems().getFirst().reason());

        Path file = temporary.resolve("ordinary-file");
        Files.writeString(file, "not a repository", StandardCharsets.UTF_8);
        var nonDirectory = acquirer.acquire(file, request(POLICY));
        assertEquals(RepositoryAcquisitionResult.Reason.ROOT_NOT_DIRECTORY,
                nonDirectory.problems().getFirst().reason());

        Files.createDirectories(repositoryRoot());
        Path link = temporary.resolve("root-link");
        createDirectoryLink(link, repositoryRoot());
        try {
            var linked = acquirer.acquire(link, request(POLICY));
            assertEquals(RepositoryAcquisitionResult.Completion.FAILED, linked.completion());
            assertEquals(RepositoryAcquisitionResult.Reason.ROOT_SYMBOLIC_LINK,
                    linked.problems().getFirst().reason());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void withholdsSnapshotsWhenEveryBoundedResourceLimitIsExceeded() throws Exception {
        write("pom.xml", "12345");
        write("a.txt", "a");
        write("deep/one/two/value.txt", "v");

        assertLimit(new RepositoryAcquisitionPolicy(1, 100, 100, 100, 20, List.of()),
                RepositoryAcquisitionResult.Reason.FILE_COUNT_LIMIT);
        assertLimit(new RepositoryAcquisitionPolicy(100, 100, 4, 100, 20, List.of()),
                RepositoryAcquisitionResult.Reason.FILE_BYTE_LIMIT);
        assertLimit(new RepositoryAcquisitionPolicy(100, 100, 100, 5, 20, List.of()),
                RepositoryAcquisitionResult.Reason.TOTAL_BYTE_LIMIT);
        assertLimit(new RepositoryAcquisitionPolicy(1, 1, 100, 100, 20, List.of()),
                RepositoryAcquisitionResult.Reason.ENTRY_COUNT_LIMIT);
        assertLimit(new RepositoryAcquisitionPolicy(100, 1, 100, 100, 20, List.of()),
                RepositoryAcquisitionResult.Reason.DIRECTORY_COUNT_LIMIT);
        assertLimit(new RepositoryAcquisitionPolicy(100, 100, 100, 100, 1, List.of()),
                RepositoryAcquisitionResult.Reason.DEPTH_LIMIT);
    }

    @Test
    void aMissingEntryPomIsVisibleButDoesNotInvalidateACompleteFilesystemSnapshot() throws Exception {
        Files.createDirectories(repositoryRoot());
        var result = acquire(POLICY);
        assertTrue(result.snapshot().isPresent());
        assertEquals(RepositoryAcquisitionResult.Completion.COMPLETE, result.completion());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.reason()
                == RepositoryAcquisitionResult.Reason.MISSING_ROOT_POM));
        assertTrue(result.buildModelRequest(new BuildModelPolicy(List.of(), List.of(), Map.of(), 100, 10, 10)).isEmpty());
    }

    @Test
    void assignsCandidateFilesWithoutHidingMissingOverlappingOrUnownedSources() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>root</artifactId>
                <version>1</version><packaging>pom</packaging><modules><module>app</module><module>missing</module></modules></project>
                """);
        write("app/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><parent><groupId>x</groupId><artifactId>root</artifactId>
                <version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>app</artifactId>
                <build><sourceDirectory>src</sourceDirectory><testSourceDirectory>src/test/java</testSourceDirectory></build></project>
                """);
        write("missing/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><parent><groupId>x</groupId><artifactId>root</artifactId>
                <version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>missing</artifactId></project>
                """);
        write("app/src/main/java/App.java", "class App {}");
        write("app/src/test/java/AppTest.java", "class AppTest {}");
        write("orphan/Loose.java", "class Loose {}");

        var acquisition = acquire(POLICY);
        var modelPolicy = new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 20, 32);
        var request = acquisition.buildModelRequest(modelPolicy).orElseThrow();
        var build = new MavenBuildModelProvider().build(request);
        assertTrue(build.problems().isEmpty(), () -> build.problems().toString());

        var ownership = CandidateSourceOwnershipResolver.resolve(acquisition, request, build);
        assertEquals(3, ownership.candidates().size());
        var app = ownership.candidates().stream().filter(file -> file.path().endsWith("App.java")).findFirst().orElseThrow();
        var test = ownership.candidates().stream().filter(file -> file.path().endsWith("AppTest.java")).findFirst().orElseThrow();
        var orphan = ownership.candidates().stream().filter(file -> file.path().endsWith("Loose.java")).findFirst().orElseThrow();
        assertEquals(CandidateSourceOwnership.Status.OWNED, app.status());
        assertEquals(CandidateSourceOwnership.Status.OVERLAPPING, test.status());
        assertEquals(List.of(SourcePlanModel.Kind.MAIN, SourcePlanModel.Kind.TEST),
                test.claims().stream().map(CandidateSourceOwnership.Claim::sourceSet).toList());
        assertEquals(CandidateSourceOwnership.Status.UNOWNED, orphan.status());
        assertTrue(ownership.problems().stream().anyMatch(problem -> problem.reason()
                == CandidateSourceOwnership.Reason.MISSING_SOURCE_ROOT));
        assertTrue(ownership.problems().stream().anyMatch(problem -> problem.reason()
                == CandidateSourceOwnership.Reason.OVERLAPPING_FILE_OWNERSHIP
                && problem.subject().equals("app/src/test/java/AppTest.java")));
        assertTrue(ownership.problems().stream().anyMatch(problem -> problem.reason()
                == CandidateSourceOwnership.Reason.UNOWNED_SOURCE_FILE
                && problem.subject().equals("orphan/Loose.java")));
        assertEquals(ownership.identity(), CandidateSourceOwnershipResolver.resolve(acquisition, request, build).identity());
    }

    @Test
    void duplicateMissingSourceRootDeclarationsProduceOneOwnershipProblem() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>root</artifactId>
                <version>1</version><build><sourceDirectory>missing</sourceDirectory></build></project>
                """);

        var acquisition = acquire(POLICY);
        var request = acquisition.buildModelRequest(
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 10, 16)).orElseThrow();
        var build = new MavenBuildModelProvider().build(request);
        var module = build.modules().getFirst();
        var pom = module.effectivePom().orElseThrow();
        var main = pom.sourcePlan().sourceSets().getFirst();
        var duplicateMain = new SourcePlanModel.SourceSetPlan(
                main.module(),
                main.kind(),
                List.of(main.sourceRoots().getFirst(), main.sourceRoots().getFirst()),
                main.resourceRoots(),
                main.outputDirectory(),
                main.compilerSettings(),
                main.syntaxLevel(),
                main.bytecodeTarget(),
                main.platformRelease(),
                main.encoding(),
                main.generatedSourceHints(),
                main.gaps());
        var duplicatePlan = new SourcePlanModel(
                List.of(duplicateMain, pom.sourcePlan().sourceSets().getLast()),
                pom.sourcePlan().plugins());
        var duplicatePom = new BuildModelResult.EffectivePom(
                pom.coordinate(),
                pom.packaging(),
                pom.declaredModules(),
                pom.dependencies(),
                pom.managedDependencies(),
                pom.properties(),
                pom.activeProfiles(),
                pom.inputs(),
                duplicatePlan);
        var duplicateBuild = new BuildModelResult(
                build.schemaVersion(),
                build.requestIdentity(),
                build.provider(),
                List.of(new BuildModelResult.ModuleModel(
                        module.module(), module.pomPath(), module.aggregatorPom(), Optional.of(duplicatePom))),
                build.problems(),
                build.attempts(),
                build.limitations());

        var ownership = CandidateSourceOwnershipResolver.resolve(acquisition, request, duplicateBuild);

        assertEquals(1, ownership.problems().stream()
                .filter(problem -> problem.reason() == CandidateSourceOwnership.Reason.MISSING_SOURCE_ROOT)
                .filter(problem -> problem.subject().equals("missing"))
                .count());
    }

    @Test
    void repositoryRootCanBeTheDeclaredMainSourceRoot() throws Exception {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>root-source</artifactId>
                <version>1</version><build><sourceDirectory>${project.basedir}</sourceDirectory></build></project>
                """);
        write("TopLevel.java", "class TopLevel {}");

        var acquisition = acquire(POLICY);
        var request = acquisition.buildModelRequest(
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 100_000, 10, 16)).orElseThrow();
        var build = new MavenBuildModelProvider().build(request);
        var ownership = CandidateSourceOwnershipResolver.resolve(acquisition, request, build);

        assertEquals(CandidateSourceOwnership.Status.OWNED, ownership.candidates().getFirst().status());
        assertEquals(".", ownership.candidates().getFirst().claims().getFirst().sourceRoot());
    }

    private void assertLimit(RepositoryAcquisitionPolicy policy, RepositoryAcquisitionResult.Reason reason) {
        var result = acquire(policy);
        assertTrue(result.snapshot().isEmpty(), () -> "snapshot retained for " + reason);
        assertTrue(result.problems().stream().anyMatch(problem -> problem.reason() == reason),
                () -> reason + " absent from " + result.problems());
    }

    private RepositoryAcquisitionResult acquire(RepositoryAcquisitionPolicy policy) {
        return new FilesystemRepositoryAcquirer().acquire(repositoryRoot(), request(policy));
    }

    private RepositoryAcquisitionRequest request(RepositoryAcquisitionPolicy policy) {
        return new RepositoryAcquisitionRequest(REPOSITORY, Optional.empty(), false, "pom.xml", policy);
    }

    private void write(String relative, String content) throws IOException {
        Path path = repositoryRoot().resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private Path repositoryRoot() {
        return temporary.resolve("repository");
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException exception) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) throw exception;
            Process process = new ProcessBuilder(
                    "cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor() != 0) {
                fail("Windows junction fixture could not be created");
            }
        }
    }
}
