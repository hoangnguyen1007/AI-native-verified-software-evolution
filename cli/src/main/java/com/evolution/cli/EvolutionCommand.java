package com.evolution.cli;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.backend.query.*;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.*;

final class EvolutionCommand {
    private EvolutionCommand() {}

    @Command(name="evolution", mixinStandardHelpOptions=true, description="Compare pinned architecture snapshots")
    static final class Group {}

    private abstract static class Local implements Callable<Integer> {
        @Option(names="--store", description="Workbench data directory or .evo workspace") Path store;
        final Path directory;
        final PrintWriter out;
        final Supplier<Path> workspace;
        Local(Path directory, PrintWriter out, Supplier<Path> workspace) {
            this.directory = directory; this.out = out; this.workspace = workspace;
        }
        Path resolve(Path path) { return directory.resolve(path).toAbsolutePath().normalize(); }
        Path root() throws IOException { return Workspace.safe(store == null ? workspace.get() : resolve(store)); }
        PartitionedGraphStore graphs(Path root) throws IOException { return new PartitionedGraphStore(root.resolve("graph-pages")); }
        void emit(Object data) {
            out.println(CanonicalJson.write(Map.of("apiVersion", EvolutionQueries.VERSION, "data", data,
                    "resultIdentity", CanonicalJson.sha256(data))));
        }
        static int exit(Status status) { return status == Status.COMPLETE ? 0 : 3; }
        static boolean interrupted() { return Thread.currentThread().isInterrupted(); }
    }

    @Command(name="compare", mixinStandardHelpOptions=true, description="Run or resume a durable comparison")
    static final class Compare extends Local {
        final Supplier<Path> request;
        @Option(names="--idempotency-key") String key;
        @Option(names="--bridge-store") Path bridgeStore;
        @Option(names="--bridge-read-policy") Path bridgePolicy;
        Compare(Path directory, PrintWriter out, Supplier<Path> workspace, Supplier<Path> request) {
            super(directory, out, workspace);
            this.request = request;
        }

        @Override public Integer call() throws Exception {
            var file = request.get();
            if (file == null) throw new IllegalArgumentException("A comparison request file is required");
            var input = EvolutionRequest.decode(Workspace.json(resolve(file), 65_536));
            var root = root();
            var graphs = graphs(root);
            if ((bridgeStore == null) != (bridgePolicy == null)) throw new IllegalArgumentException("Bridge store and read policy are paired");
            BridgeReader bridges = (ref,snapshot,observe,stop) -> ReportStatus.UNAVAILABLE;
            if (bridgeStore != null) {
                var selected = Workspace.safe(resolve(bridgeStore));
                var policy = new BridgeReadPolicy(Workspace.safe(resolve(bridgePolicy)));
                var content = new StreamingContentStore(selected.resolve("cas"));
                var bridge = new BridgePublicationStore(selected.resolve("bridges"),content,
                        new com.evolution.analysis.polyglot.SemanticTables(content,65_536),policy::policy,policy::revoked);
                bridges = bridge::readEvolution;
            }
            try (var disk = Files.isRegularFile(root.resolve("reports/metadata/index.sqlite"), LinkOption.NOFOLLOW_LINKS)
                    ? new PersistentReports(root.resolve("reports"),true) : null;
                 var jobs = bridgeStore == null ? new EvolutionJobs(root.resolve("evolution"),graphs,new PublishedReports(disk))
                         : new EvolutionJobs(root.resolve("evolution"),graphs,new PublishedReports(disk).evolutionReader(),
                                    bridges, new EvolutionHistoryProofs(root.resolve("evolution")).reader())) {
                EvolutionJobs.View job;
                try {
                    job = jobs.submit(input, Optional.ofNullable(key)).job();
                } catch (EvolutionJobs.Conflict invalid) {
                    throw new IllegalArgumentException("Conflicting comparison idempotency key");
                }
                var id = job.job();
                try {
                    while (Set.of("QUEUED","RUNNING").contains(job.state())) {
                        Thread.sleep(100);
                        job = jobs.get(id).orElseThrow();
                    }
                } catch (InterruptedException stopped) {
                    jobs.cancel(id);
                    Thread.currentThread().interrupt();
                    return 130;
                }
                if (job.result().isPresent()) {
                    var summary = new EvolutionQueries(jobs.results(),graphs).summary(job.result().orElseThrow(),Local::interrupted);
                    emit(summary);
                    return exit(summary.status());
                }
                emit(job);
                return job.state().equals("CANCELLED") ? 130 : 1;
            }
        }
    }

    private abstract static class Read extends Local {
        @Parameters(index="0", paramLabel="RESULT") String result;
        @Option(names="--section") Section section;
        @Option(names="--size", defaultValue="20") int size;
        final Supplier<String> continuation;
        @Option(names="--side") Side side;
        @Option(names="--kind") String kind;
        @Option(names="--scope-id") String scope;
        @Option(names="--subject") String subject;
        Read(Path directory, PrintWriter out, Supplier<Path> workspace, Supplier<String> continuation) {
            super(directory, out, workspace);
            this.continuation = continuation;
        }
        ContentDigest result() { return new ContentDigest(result); }
        EvolutionQueries queries() throws IOException {
            var root = root();
            return new EvolutionQueries(new EvolutionResultStore(root.resolve("evolution")), graphs(root));
        }
        EvolutionQueries.Query query() {
            return new EvolutionQueries.Query(section == null ? Section.EVENTS : section, size, Optional.ofNullable(continuation.get()),
                    new EvolutionQueries.Filter(Optional.ofNullable(side), Optional.ofNullable(kind),
                            Optional.ofNullable(scope), Optional.ofNullable(subject)));
        }
        void requireSectionWhenFiltering() {
            if (section == null && (continuation.get() != null || side != null || kind != null || scope != null || subject != null))
                throw new IllegalArgumentException("Select a section before filtering");
        }
    }

    @Command(name="inspect", mixinStandardHelpOptions=true, description="Read a comparison summary or canonical page")
    static final class Inspect extends Read {
        @Option(names="--evidence") String evidence;
        Inspect(Path directory, PrintWriter out, Supplier<Path> workspace, Supplier<String> continuation) {
            super(directory, out, workspace, continuation);
        }
        @Override public Integer call() throws IOException {
            var q = queries();
            if (evidence != null) {
                if (side == null || section != null || continuation.get() != null || kind != null || scope != null || subject != null)
                    throw new IllegalArgumentException("Evidence requires one side");
                emit(q.evidence(result(),side,new ContentDigest(evidence),Local::interrupted));
                return exit(q.summary(result(),Local::interrupted).status());
            }
            requireSectionWhenFiltering();
            if (section != null) {
                var page = q.page(result(),query(),Local::interrupted);
                emit(page);
                return exit(page.status());
            }
            var summary = q.summary(result(),Local::interrupted);
            emit(summary);
            return exit(summary.status());
        }
    }

    @Command(name="export", mixinStandardHelpOptions=true, description="Export a redacted JSON chunk or a full local JSON file")
    static final class Export extends Read {
        @Option(names="--out") Path path;
        Export(Path directory, PrintWriter out, Supplier<Path> workspace, Supplier<String> continuation) {
            super(directory, out, workspace, continuation);
        }
        @Override public Integer call() throws IOException {
            var q = queries();
            requireSectionWhenFiltering();
            if (path == null) {
                var data = q.export(result(),query(),Local::interrupted);
                emit(data);
                return exit(data.summary().status());
            }
            if (section != null) throw new IllegalArgumentException("Full local export exports every section");
            var summary = q.summary(result(),Local::interrupted);
            var target = Workspace.safe(resolve(path));
            var temp = Files.createTempFile(target.getParent(),"evolution-", ".pending");
            try {
                try (var channel = FileChannel.open(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                     var writer = new BufferedWriter(Channels.newWriter(channel,StandardCharsets.UTF_8))) {
                    q.writeExport(result(),writer,Local::interrupted);
                    writer.flush();
                    channel.force(true);
                }
                if (interrupted()) throw new java.util.concurrent.CancellationException();
                Files.createLink(target,temp);
            } finally {
                Files.deleteIfExists(temp);
            }
            emit(Map.of("result",summary.result(),"status",summary.status(),"exportDigest",digest(target)));
            return exit(summary.status());
        }

        private static ContentDigest digest(Path path) throws IOException {
            try (var file = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                var hash = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = new java.security.DigestInputStream(file,hash)) { input.transferTo(OutputStream.nullOutputStream()); }
                return new ContentDigest("sha256:" + HexFormat.of().formatHex(hash.digest()));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
}
