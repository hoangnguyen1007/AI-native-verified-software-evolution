package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Parses supplied resource bytes without opening a JAR, loading a class, or executing a factory. */
public final class AutoConfigurationMetadata {
    private static final String KEY="org.springframework.boot.autoconfigure.EnableAutoConfiguration";
    public record Resource(String path,byte[] bytes,ConditionEvidence evidence) {
        public Resource {ContractChecks.repositoryRelativePath(path,"metadata resource");bytes=bytes.clone();Objects.requireNonNull(evidence);
            var digest=ContentDigest.sha256(bytes);
            if(evidence instanceof ConditionEvidence.Source s&&!digest.equals(s.rawDigest())||evidence instanceof ConditionEvidence.Artifact a&&!digest.equals(a.entryDigest()))
                throw new IllegalArgumentException("Metadata bytes do not match their provenance");}
        @Override public byte[] bytes(){return bytes.clone();}
        public ContentDigest identity(){return IngestionEvidence.digest(List.of(path,ContentDigest.sha256(bytes),evidence));}
    }
    public enum Status { READ, IGNORED_VERSION, NOT_AUTO_CONFIGURATION, INVALID, LIMITED }
    public record Row(ContentDigest resource,Status status,List<String> classes,ConditionEvidence evidence) {public Row{classes=List.copyOf(classes);}}
    public record Result(ContentDigest inputIdentity,List<Row> rows,List<String> candidates,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {rows=List.copyOf(rows);candidates=List.copyOf(candidates);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public static Result read(List<Resource> resources,FrameworkGeneration generation,SnapshotIdentity snapshot,int maxBytes,int maxEntries) {
        if(maxBytes<1||maxEntries<1)throw new IllegalArgumentException("Positive metadata limits required");
        var input=IngestionEvidence.digest(List.of("spring.auto-metadata:m4u.2-v1",resources.stream().map(Resource::identity).toList(),generation,maxBytes,maxEntries));
        var rows=new ArrayList<Row>();var issues=new ArrayList<UniversalSpringEvidence.Issue>();var candidates=new LinkedHashSet<String>();long bytes=0,entries=0;
        for(var resource:resources) {
            boolean factories=resource.path().equals("META-INF/spring.factories")||resource.path().endsWith("/META-INF/spring.factories");
            boolean imports=resource.path().endsWith("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            Status status;var classes=new ArrayList<String>();
            if(!factories&&!imports)status=Status.NOT_AUTO_CONFIGURATION;
            else if((bytes+=resource.bytes.length)>maxBytes) {status=Status.LIMITED;issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,resource.path(),List.of(resource.evidence())));}
            else if(generation.metadataPolicy()==FrameworkGeneration.MetadataPolicy.UNKNOWN) {status=Status.INVALID;issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.FRAMEWORK_VERSION_UNKNOWN,resource.path(),List.of(resource.evidence())));}
            else if(generation.metadataPolicy()==FrameworkGeneration.MetadataPolicy.NONE||factories&&generation.metadataPolicy()==FrameworkGeneration.MetadataPolicy.IMPORTS
                    ||imports&&generation.metadataPolicy()==FrameworkGeneration.MetadataPolicy.FACTORIES)status=Status.IGNORED_VERSION;
            else try {
                if(factories){var properties=new Properties();properties.load(new ByteArrayInputStream(resource.bytes));String value=properties.getProperty(KEY,"");for(String part:value.split(","))if(!part.isBlank())classes.add(part.trim());}
                else {String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(resource.bytes)).toString();
                    for(String line:text.split("\\R")){int comment=line.indexOf('#');String name=(comment>=0?line.substring(0,comment):line).trim();if(!name.isEmpty())classes.add(name);}}
                for(String name:classes)if(!name.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(?:\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*"))throw new IllegalArgumentException();
                entries+=classes.size();
                if(entries>maxEntries){status=Status.LIMITED;classes.clear();issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,resource.path(),List.of(resource.evidence())));}
                else {status=Status.READ;candidates.addAll(classes);}
            }catch(IOException|IllegalArgumentException invalid){status=Status.INVALID;classes.clear();issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.METADATA_INVALID,resource.path(),List.of(resource.evidence())));}
            rows.add(new Row(resource.identity(),status,classes,resource.evidence()));
        }
        return new Result(input,rows,List.copyOf(candidates),issues,UniversalSpringEvidence.gaps(snapshot,input,issues));
    }
    private AutoConfigurationMetadata() {}
}
