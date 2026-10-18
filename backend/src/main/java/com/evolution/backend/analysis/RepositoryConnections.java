package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.filesystem.GitHostPolicy;
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
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("source.git-cas","m8.4-v2");
    private final AnalysisJobs jobs;
    private final Path jdk, cache, directory;
    private final int release;
    private final String gitExecutable;
    private final Optional<LocalAnalysisRunner.PolicyInput> defaultPolicy;
    private final GitHostPolicy hostPolicy;
    private final Map<String,Connection> connections = new LinkedHashMap<>();
    public RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory, Optional<LocalAnalysisRunner.PolicyInput> policy) {
        this(jobs,jdk,release,cache,directory,policy,"git");
    }
    /** Executable selection is trusted embedding configuration, never an HTTP operand. */
    RepositoryConnections(AnalysisJobs jobs, Path jdk, int release, Path cache, Path directory, Optional<LocalAnalysisRunner.PolicyInput> policy, String gitExecutable) {
        this(jobs,jdk,release,cache,directory,policy,gitExecutable,new GitHostPolicy());
    }
    public RepositoryConnections(AnalysisJobs jobs,Path jdk,int release,Path cache,Path directory,Optional<LocalAnalysisRunner.PolicyInput> policy,String gitExecutable,GitHostPolicy hostPolicy){
        this.jobs=jobs; this.jdk=jdk; this.release=release; this.cache=cache; this.directory=directory; this.defaultPolicy=policy; this.gitExecutable=gitExecutable;
        this.hostPolicy=Objects.requireNonNull(hostPolicy);
        try(var catalog=new ConnectionCatalog(directory.resolve("connections"))){for(var saved:catalog.load())connect(saved.source(),saved.policy(),false);}
        catch(IOException unavailable){throw new IllegalStateException("Connection catalog unavailable",unavailable);}
    }
    public synchronized void describe(String setup,String label,String kind,RepositoryIdentity repository) {
        connections.put(setup,new Connection(setup,label,kind,repository));
    }
    public synchronized List<Connection> list() { return List.copyOf(connections.values()); }
    public synchronized Connection connect(String source,Optional<String> policyText) {
        return connect(source,policyText,true);
    }
    private synchronized Connection connect(String source,Optional<String> policyText,boolean persist) {
        if (source == null || source.isBlank() || source.length()>4096 || source.indexOf('\n')>=0 || source.indexOf('\r')>=0) throw new IllegalArgumentException("Invalid source");
        var policy=policyText.map(text -> new LocalAnalysisRunner.PolicyInput(text.getBytes(java.nio.charset.StandardCharsets.UTF_8),Scope.TYPE)).or(() -> defaultPolicy);
        boolean remote = source.contains("://") || source.startsWith("git@");
        String coordinate, label; Path local=null;
        if (remote) { var uri=GitHostPolicy.syntax(source);if(uri.getScheme().equals("ssh")){if(hostPolicy.ssh(uri.getHost()).isEmpty())throw new IllegalArgumentException("SSH identity required");coordinate=uri.toASCIIString();}else coordinate=configuredRemote(uri); label=URI.create(coordinate).getHost()+URI.create(coordinate).getPath().replaceFirst("\\.git$",""); }
        else {
            try {
                var requested=Path.of(source);
                if (!requested.isAbsolute() || source.startsWith("\\\\") || source.startsWith("//")) throw new IllegalArgumentException("Use an absolute local path");
                local=persist?LocalAnalysisRunner.safe(requested):requested.toAbsolutePath().normalize(); if (persist&&!Files.isDirectory(local,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
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
        AnalysisJobs.Runner runner=remote ? control -> cloneAndRun(coordinate,repository,policy,control) : new LocalAnalysisRunner(input(sourcePath,repository,Optional.empty(),true,policy),directory.resolve("acquisition"));
        jobs.register(new AnalysisJobs.Registration(setup,runner));
        if(persist)try(var catalog=new ConnectionCatalog(directory.resolve("connections"))){catalog.save(id,source,policyText);}catch(IOException unavailable){throw new IllegalStateException("Connection catalog unavailable",unavailable);}
        var connection=new Connection(id,label,remote ? URI.create(coordinate).getScheme().equals("ssh")?"GIT_SSH":"GIT_HTTPS" : "LOCAL",repository); connections.put(id,connection); return connection;
    }
    private String configuredRemote(URI uri){
        try{return remote(uri.toASCIIString());}
        catch(IllegalArgumentException restricted){
            try{hostPolicy.endpoint(uri);return uri.toASCIIString();}
            catch(IOException denied){throw new IllegalArgumentException("Operator endpoint policy required");}
        }
    }
    static String remote(String source) {
        URI uri=GitHostPolicy.syntax(source);
        if(uri.getPort()!=-1&&uri.getPort()!=443)throw new IllegalArgumentException("Operator endpoint policy required");
        if(!uri.getScheme().equals("https"))throw new IllegalArgumentException("SSH requires operator-configured identity and strict host keys");
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        if(host.matches("[0-9.]+")||host.equals("localhost")||host.endsWith(".localhost"))throw new IllegalArgumentException("Endpoint denied");
        String path=uri.getPath().replaceFirst("/$", "");
        return "https://"+host+(uri.getPort()==-1?"":":"+uri.getPort())+path;
    }
    public Object acquisition(String revision,int size,Optional<String> cursor)throws IOException{
        if(!revision.matches("[A-Za-z0-9._-]{1,256}"))throw new IllegalArgumentException("Invalid acquisition revision");
        try(var intake=new RepositoryIntake(directory.resolve("acquisition"),true)){
            return Map.of("version","repository.acquisition-api:m8.4-v2","revision",revision,"job",intake.journal().get(revision),"manifest",intake.inventory().manifest(revision),"inventory",intake.inventory().page(revision,size,cursor),"semanticCoverage","NOT_EVALUATED");
        }
    }
    public Object acquisitionHistory(long after,int size)throws IOException{
        try(var intake=new RepositoryIntake(directory.resolve("acquisition"),true)){return intake.journal().historyPage(after,size);}
    }
    private LocalAnalysisRunner.Input input(Path source,RepositoryIdentity repository,Optional<String> revision,boolean dirty,Optional<LocalAnalysisRunner.PolicyInput> policy) {
        var normalized=directory.toAbsolutePath().normalize();
        var exclusions=normalized.startsWith(source) ? List.of(source.relativize(normalized).toString().replace('\\','/')) : List.<String>of();
        return new LocalAnalysisRunner.Input(source,repository,jdk,release,cache,revision,dirty,false,exclusions,policy,LocalAnalysisRunner.SpringInput.absent());
    }
    private AnalysisJobs.Outcome cloneAndRun(String url,RepositoryIdentity repository,Optional<LocalAnalysisRunner.PolicyInput> policy,AnalysisJobs.Control control) throws Exception {
        control.stage(AnalysisJobs.Stage.ACQUISITION);
        String code;
        try(var intake=new RepositoryIntake(directory.resolve("acquisition"))) {
            var request=new StreamingAcquisitionRequest(repository,Optional.empty(),false,List.of());
            var receipt=intake.git(URI.create(url),"HEAD",control.id(),request,hostPolicy,gitExecutable,control);
            control.checkpoint();
            if(receipt.manifest().isPresent()) {
                var pin=intake.pinnedCommit(control.id());
                var acquisition=intake.compatibility(control.id(),repository,pin,false,false,false,List.of());
                return new LocalAnalysisRunner(input(directory,repository,pin,false,policy)).runCaptured(acquisition,control);
            }
            code=receipt.reason();
        } catch(IOException unavailable) { code="REMOTE_FETCH_FAILED"; }
        var digest=ContentDigest.sha256Utf8(CanonicalJson.write(List.of(PROVIDER,repository,code)));
        var problem=new RepositoryAcquisitionResult.Problem(RepositoryAcquisitionResult.Reason.ROOT_READ_FAILED,code,RepositoryAcquisitionResult.Requirement.REPOSITORY_SELECTION);
        return new AnalysisJobs.Outcome(AnalysisJobs.Completion.FAILED,Optional.empty(),List.of(),List.of(),Optional.of(digest),Optional.empty(),List.of(new AnalysisJobs.InputProblem(PROVIDER,digest,problem)));
    }
}
