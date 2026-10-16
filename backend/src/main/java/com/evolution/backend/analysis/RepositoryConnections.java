package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in loopback connection boundary. Reads local sources or clones public HTTPS Git without target execution. */
public final class RepositoryConnections {
    public record Connection(String setup, String label, String kind, RepositoryIdentity repository) {}
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("source.public-git","m8.2-v1");
    private static final long MAX_BYTES = 128_000_000, TIMEOUT_MILLIS = 120_000;
    private final AnalysisJobs jobs;
    private final Path jdk, cache, directory;
    private final int release;
    private final String gitExecutable;
    private final Optional<LocalAnalysisRunner.PolicyInput> defaultPolicy;
    private final Map<String,Connection> connections = new LinkedHashMap<>();
    public RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory, Optional<LocalAnalysisRunner.PolicyInput> policy) {
        this(jobs,jdk,release,cache,directory,policy,"git");
    }
    /** Executable selection is trusted embedding configuration, never an HTTP operand. */
    RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory, Optional<LocalAnalysisRunner.PolicyInput> policy, String gitExecutable) {
        this.jobs=jobs; this.jdk=jdk; this.release=release; this.cache=cache; this.directory=directory; this.defaultPolicy=policy; this.gitExecutable=gitExecutable;
    }
    public synchronized void describe(String setup,String label,String kind,RepositoryIdentity repository) {
        connections.put(setup,new Connection(setup,label,kind,repository));
    }
    public synchronized List<Connection> list() { return List.copyOf(connections.values()); }
    public synchronized Connection connect(String source,Optional<String> policyText) {
        if (source == null || source.isBlank() || source.length()>4096 || source.indexOf('\n')>=0 || source.indexOf('\r')>=0) throw new IllegalArgumentException("Invalid source");
        var policy=policyText.map(text -> new LocalAnalysisRunner.PolicyInput(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),Scope.TYPE)).or(() -> defaultPolicy);
        boolean remote = source.contains("://") || source.startsWith("git@");
        String coordinate, label; Path local=null;
        if (remote) { coordinate=remote(source); label=URI.create(coordinate).getHost()+URI.create(coordinate).getPath().replaceFirst("\\.git$",""); }
        else {
            try {
                var requested=Path.of(source);
                if (!requested.isAbsolute() || source.startsWith("\\\\") || source.startsWith("//")) throw new IllegalArgumentException("Use an absolute local path");
                local=LocalAnalysisRunner.safe(requested); if (!Files.isDirectory(local,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            }
            catch (IOException | InvalidPathException invalid) { throw new IllegalArgumentException("Local directory unavailable"); }
            if (local.getFileName()==null || local.equals(directory.toAbsolutePath().normalize())) throw new IllegalArgumentException("Source must have its own directory");
            coordinate=local.toUri().toASCIIString(); label=local.getFileName().toString();
        }
        String id="repo-"+ContentDigest.sha256Utf8(CanonicalJson.write(List.of(coordinate,policy.map(p -> ContentDigest.sha256(p.bytes()).value()).orElse("none")))).value().substring(7,31);
        if (connections.containsKey(id)) return connections.get(id);
        var repository=RepositoryIdentity.fromCanonicalCoordinate(coordinate);
        var setup=new AnalysisJobs.Setup(id,repository,release,false,policy.map(p -> ContentDigest.sha256(p.bytes())),Optional.empty());
        var sourcePath=local;
        AnalysisJobs.Runner runner=remote ? control -> cloneAndRun(coordinate,repository,policy,control) : new LocalAnalysisRunner(input(sourcePath,repository,Optional.empty(),true,policy));
        jobs.register(new AnalysisJobs.Registration(setup,runner));
        var connection=new Connection(id,label,remote ? "GIT_HTTPS" : "LOCAL",repository); connections.put(id,connection); return connection;
    }
    static String remote(String source) {
        URI uri;
        try { uri=URI.create(source); } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid Git URL"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost()==null || !Set.of("github.com","gitlab.com","bitbucket.org").contains(uri.getHost())
                || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getQuery()!=null || uri.getFragment()!=null
                || !uri.normalize().equals(uri) || !uri.getRawPath().matches("/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+/?")
                || Arrays.stream(uri.getPath().split("/")).anyMatch(s -> s.equals(".") || s.equals(".."))) throw new IllegalArgumentException("Use a public HTTPS repository URL on GitHub, GitLab or Bitbucket");
        var path=uri.getPath().replaceFirst("/$","");
        if (!uri.getHost().equals("gitlab.com") && path.substring(1).split("/").length!=2) throw new IllegalArgumentException("Use a repository URL, not a file or branch URL");
        return "https://"+uri.getHost()+path+(path.endsWith(".git") ? "" : ".git");
    }
    private LocalAnalysisRunner.Input input(Path source,RepositoryIdentity repository,Optional<String> revision,boolean dirty,Optional<LocalAnalysisRunner.PolicyInput> policy) {
        var normalized=directory.toAbsolutePath().normalize();
        var exclusions=normalized.startsWith(source) ? List.of(source.relativize(normalized).toString().replace('\\','/')) : List.<String>of();
        return new LocalAnalysisRunner.Input(source,repository,jdk,release,cache,revision,dirty,false,exclusions,policy,LocalAnalysisRunner.SpringInput.absent());
    }
    private AnalysisJobs.Outcome cloneAndRun(String url,RepositoryIdentity repository,Optional<LocalAnalysisRunner.PolicyInput> policy,AnalysisJobs.Control control) throws Exception {
        control.stage(AnalysisJobs.Stage.ACQUISITION);
        // Each scan captures a fresh default-branch checkout. Retained inputs are bounded by job/history admission.
        Path checkout=Files.createTempDirectory(directory,"git-input-");
        Path templates=Files.createDirectory(checkout.resolve("empty-template"));
        Path target=checkout.resolve("source");
        String code=null, acquiredRevision=null;
        try {
            var command=new ArrayList<>(List.of(gitExecutable,"-c","credential.helper=","-c","core.hooksPath="+templates,
                    "-c","protocol.allow=never","-c","protocol.https.allow=always","-c","http.followRedirects=false",
                    "-c","http.sslVerify=true","-c","http.proxy=","-c","core.autocrlf=false","-c","core.symlinks=true",
                    "clone","--quiet","--depth=1","--single-branch","--no-tags","--no-recurse-submodules","--template="+templates,"--",url,target.toString()));
            var builder=new ProcessBuilder(command).directory(checkout.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            var inherited=new HashMap<>(builder.environment()); builder.environment().clear();
            for (var key : List.of("PATH","Path","SystemRoot","SYSTEMROOT","TEMP","TMP")) if (inherited.containsKey(key)) builder.environment().put(key,inherited.get(key));
            builder.environment().put("GIT_CONFIG_NOSYSTEM","1"); builder.environment().put("GIT_CONFIG_GLOBAL",templates.resolve("no-config").toString());
            builder.environment().put("GIT_TERMINAL_PROMPT","0"); builder.environment().put("GIT_ASKPASS","");
            builder.environment().put("GIT_LFS_SKIP_SMUDGE","1"); builder.environment().put("GIT_ALLOW_PROTOCOL","https");
            var process=builder.start(); long deadline=System.nanoTime()+TIMEOUT_MILLIS*1_000_000;
            try {
                while (!process.waitFor(150,TimeUnit.MILLISECONDS)) {
                    control.checkpoint();
                    if (System.nanoTime()>deadline) { code="REMOTE_TIMEOUT"; break; }
                    if (exceedsLimit(checkout)) { code="REMOTE_SIZE_LIMIT"; break; }
                }
                if (code==null && process.exitValue()!=0) code="REMOTE_FETCH_FAILED";
            } finally {
                if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
            }
            control.checkpoint();
            if (code==null && exceedsLimit(checkout)) code="REMOTE_SIZE_LIMIT";
            if (code==null) {
                // Clone uses the remote's default branch; record its exact acquired commit without executing repository commands.
                var head=Files.readString(target.resolve(".git/HEAD")).trim();
                String revision=head.startsWith("ref: ") ? Files.readString(target.resolve(".git").resolve(head.substring(5))).trim() : head;
                if (!revision.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid acquired revision");
                acquiredRevision=revision;
            }
        } catch (IOException unavailable) { code="REMOTE_FETCH_FAILED"; }
        if (code==null) return new LocalAnalysisRunner(input(target,repository,Optional.of(acquiredRevision),false,policy)).runAcquired(control);
        var digest=ContentDigest.sha256Utf8(CanonicalJson.write(List.of(PROVIDER,repository,code,MAX_BYTES,TIMEOUT_MILLIS)));
        var problem=new RepositoryAcquisitionResult.Problem(RepositoryAcquisitionResult.Reason.ROOT_READ_FAILED,code,RepositoryAcquisitionResult.Requirement.REPOSITORY_SELECTION);
        return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(digest),Optional.empty(),List.of(new AnalysisJobs.InputProblem(PROVIDER,digest,problem)));
    }
    private static boolean exceedsLimit(Path root) throws IOException {
        long[] bytes={0}; int[] entries={0}; boolean[] exceeded={false};
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) {
                bytes[0]+=attributes.size(); entries[0]++;
                if (bytes[0]>MAX_BYTES || entries[0]>20_000) { exceeded[0]=true; return FileVisitResult.TERMINATE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException failure) { return FileVisitResult.CONTINUE; }
        });
        return exceeded[0];
    }
}
