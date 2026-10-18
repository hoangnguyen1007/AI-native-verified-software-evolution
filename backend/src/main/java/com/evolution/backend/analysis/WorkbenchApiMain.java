package com.evolution.backend.analysis;

import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.query.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Loopback composition root with operator-enabled passive local/public-Git connections. */
public final class WorkbenchApiMain {
    public static void main(String[] args) {
        if (args.length < 6 || args.length > 8) {
            System.err.println("Usage: WorkbenchApiMain <source> <repository-coordinate> <jdk> <release> <maven-cache> <existing-graph-directory> [port] [policy.yaml]");
            System.exit(2);
        }
        try {
            var source = LocalAnalysisRunner.safe(Path.of(args[0])); var jdk = LocalAnalysisRunner.safe(Path.of(args[2]));
            var cache = LocalAnalysisRunner.safe(Path.of(args[4])); var directory = LocalAnalysisRunner.safe(Path.of(args[5]));
            if (!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(jdk,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Inputs unavailable");
            // Prevent output files from changing the next captured snapshot, even when the store lives inside the source tree.
            if (directory.equals(source)) throw new IllegalArgumentException("Store must have its own directory");
            var exclusions = directory.startsWith(source) ? List.of(source.relativize(directory).toString().replace('\\','/')) : List.<String>of();
            Optional<LocalAnalysisRunner.PolicyInput> policy = Optional.empty();
            if (args.length == 8) {
                var path = LocalAnalysisRunner.safe(Path.of(args[7]));
                if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Policy unavailable");
                try (var stream = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { policy = Optional.of(new LocalAnalysisRunner.PolicyInput(stream.readNBytes(262_145),Scope.TYPE)); }
            }
            var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,RepositoryIdentity.fromCanonicalCoordinate(args[1]),jdk,Integer.parseInt(args[3]),cache,
                    Optional.empty(),true,false,exclusions,policy,LocalAnalysisRunner.SpringInput.absent()),directory.resolve("acquisition"));
            var store = new FileGraphStore(directory);
            try (var reportDisk=new PersistentReports(directory.resolve("reports"));var disk = new AnalysisJobDiskStore(directory.resolve("jobs"))) {
            var reports=new PublishedReports(reportDisk);
            try(var jobs = new AnalysisJobs(List.of(runner.registration("local")),store,reports,AnalysisJobs.Limits.defaults(),disk)) {
                var connections=new RepositoryConnections(jobs,jdk,Integer.parseInt(args[3]),cache,directory,policy);
                connections.describe("local",source.getFileName().toString(),"LOCAL",RepositoryIdentity.fromCanonicalCoordinate(args[1]));
                try (var server = new ArchitectureHttpServer(new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs,connections),args.length >= 7 ? Integer.parseInt(args[6]) : 8080)) {
                var stopped = new CountDownLatch(1);
                var shutdown = new Thread(() -> { server.close(); jobs.close(); stopped.countDown(); },"architecture-workbench-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try { server.start(); System.out.println("Architecture workbench API: " + server.uri() + "/api/v1"); stopped.await(); }
                finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException stopping) { /* Shutdown hook owns cleanup. */ } }
                }
            }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { System.err.println("Unable to start local workbench API; check the explicit inputs, storage limits and port."); System.exit(1); }
    }
    private WorkbenchApiMain() {}
}
