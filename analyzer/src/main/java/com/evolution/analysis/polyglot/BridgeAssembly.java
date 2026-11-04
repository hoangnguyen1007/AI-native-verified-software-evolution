package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.EvidenceContext;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Joins captured partitions without merging their identities or qualifying candidates. */
public final class BridgeAssembly {
    public record Fragment(SemanticTables.Row partition,SemanticTables.Table endpoints,SemanticTables.Table requirements){}
    private final SemanticTables tables;private final int candidateBudget;
    public BridgeAssembly(SemanticTables tables,int candidateBudget){this.tables=Objects.requireNonNull(tables);if(candidateBudget<1)throw new IllegalArgumentException("BRIDGE_ASSEMBLY_WORKER_BUDGET");this.candidateBudget=candidateBudget;}
    public ArchitectureBridges.Input assemble(EvidenceContext root,ContentDigest inventory,ContentDigest policy,Iterator<Fragment> fragments,SemanticRootCensus.Index index,BooleanSupplier cancelled)throws IOException{
        var scope=ArchitectureBridges.scope(root,inventory);
        for(String space:List.of("assembly.partitions","assembly.endpoints","assembly.http","assembly.protocols","assembly.requirements","assembly.joined"))try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("BRIDGE_ASSEMBLY_INDEX_NOT_EMPTY");}
        while(fragments.hasNext()){
            check(cancelled);var fragment=fragments.next();var row=fragment.partition();var partition=ArchitectureBridges.partition(row.fields());String owner=row.key();
            if(row.kind()!=SemanticTables.Kind.CONTEXT||!row.scope().equals(scope)||!partition.snapshot().equals(root.snapshotIdentity().value()))throw new IOException("BRIDGE_ASSEMBLY_FOREIGN_CONTEXT");
            if(index.get("assembly.partitions",owner).isPresent())throw new IOException("BRIDGE_ASSEMBLY_DUPLICATE_PARTITION");index.put("assembly.partitions",owner,row);
            if(fragment.endpoints().kind()!=SemanticTables.Kind.OBSERVATION||fragment.requirements().kind()!=SemanticTables.Kind.CENSUS)throw new IOException("BRIDGE_ASSEMBLY_TABLE_KIND");
            try(var rows=tables.open(fragment.endpoints(),cancelled)){SemanticTables.Row endpoint;while((endpoint=rows.next())!=null){
                check(cancelled);if(!endpoint.scope().equals(scope)||!ArchitectureBridges.endpoint(endpoint.fields()).partition().equals(owner))throw new IOException("BRIDGE_ASSEMBLY_ENDPOINT_SCOPE");
                String key=key(owner,endpoint.key());index.put("assembly.endpoints",key,new SemanticTables.Row(endpoint.kind(),key,scope,endpoint.fields()));
                var value=ArchitectureBridges.endpoint(endpoint.fields());String path=value.descriptor().get("path"),method=value.descriptor().get("method");
                if(value.profile()==ArchitectureBridges.Profile.B02&&value.direction().equals("PROVIDES")&&path!=null&&method!=null){String lookup=ContentDigest.sha256Utf8(path).value()+":"+ContentDigest.sha256Utf8(method).value()+":"+key;index.put("assembly.http",lookup,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,lookup,scope,Map.of("endpoint",key)));}
                String protocolPrefix=protocolPrefix(value);
                if(value.direction().equals("PROVIDES")&&protocolPrefix!=null){String lookup=protocolPrefix+key;index.put("assembly.protocols",lookup,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,lookup,scope,Map.of("endpoint",key)));}
            }}
            try(var rows=tables.open(fragment.requirements(),cancelled)){SemanticTables.Row requirement;while((requirement=rows.next())!=null){
                check(cancelled);var original=ArchitectureBridges.requirement(requirement.fields());if(!requirement.scope().equals(scope)||!original.partition().equals(owner))throw new IOException("BRIDGE_ASSEMBLY_REQUIREMENT_SCOPE");
                String key=key(owner,requirement.key());var remapped=new ArchitectureBridges.Requirement(owner,original.profile(),original.applicability(),original.endpoint().map(value->key(owner,value)),original.candidates().stream().map(value->key(owner,value)).toList(),original.evidence(),original.reason());
                index.put("assembly.requirements",key,ArchitectureBridges.row(SemanticTables.Kind.CENSUS,key,scope,remapped));
            }}
        }
        // Exact literal/path-digest agreement enumerates candidates only. No
        // normalization, method default, contract identity or routing is supplied.
        try(var rows=index.rows("assembly.requirements",false)){SemanticTables.Row row;while((row=rows.next())!=null){
            check(cancelled);var requirement=ArchitectureBridges.requirement(row.fields());var candidates=new TreeSet<>(requirement.candidates());
            if(requirement.profile()==ArchitectureBridges.Profile.B02&&requirement.endpoint().isPresent()){
                var source=index.get("assembly.endpoints",requirement.endpoint().orElseThrow());
                if(source.isPresent()){
                    var endpoint=ArchitectureBridges.endpoint(source.orElseThrow().fields());
                    String prefix=httpPrefix(endpoint);
                    if(endpoint.direction().equals("REQUIRES")&&prefix!=null)try(var providers=index.range("assembly.http",prefix,prefix.substring(0,prefix.length()-1)+";")){SemanticTables.Row lookup;while((lookup=providers.next())!=null){
                        check(cancelled);String targetKey=(String)lookup.fields().get("endpoint");var target=index.get("assembly.endpoints",targetKey).orElseThrow(()->new IOException("BRIDGE_ASSEMBLY_INDEX_BINDING"));
                        if(literalCandidate(endpoint,ArchitectureBridges.endpoint(target.fields())))candidates.add(targetKey);if(candidates.size()>candidateBudget)throw new IOException("BRIDGE_ASSEMBLY_CANDIDATE_WORKER_REQUIRED");
                    }}
                }
            }
            if((requirement.profile()==ArchitectureBridges.Profile.B03||requirement.profile()==ArchitectureBridges.Profile.B04)&&requirement.endpoint().isPresent()){
                var source=index.get("assembly.endpoints",requirement.endpoint().orElseThrow());
                if(source.isPresent()){
                    var endpoint=ArchitectureBridges.endpoint(source.orElseThrow().fields());String prefix=protocolPrefix(endpoint);
                    if(endpoint.direction().equals("REQUIRES")&&prefix!=null)try(var providers=index.range("assembly.protocols",prefix,prefix.substring(0,prefix.length()-1)+";")){SemanticTables.Row lookup;while((lookup=providers.next())!=null){
                        check(cancelled);String targetKey=(String)lookup.fields().get("endpoint");var target=index.get("assembly.endpoints",targetKey).orElseThrow(()->new IOException("BRIDGE_ASSEMBLY_INDEX_BINDING"));
                        if(protocolCandidate(endpoint,ArchitectureBridges.endpoint(target.fields())))candidates.add(targetKey);
                        if(candidates.size()>candidateBudget)throw new IOException("BRIDGE_ASSEMBLY_CANDIDATE_WORKER_REQUIRED");
                    }}
                }
            }
            if(candidates.size()>candidateBudget)throw new IOException("BRIDGE_ASSEMBLY_CANDIDATE_WORKER_REQUIRED");
            index.put("assembly.joined",row.key(),ArchitectureBridges.row(SemanticTables.Kind.CENSUS,row.key(),scope,new ArchitectureBridges.Requirement(requirement.partition(),requirement.profile(),requirement.applicability(),requirement.endpoint(),List.copyOf(candidates),requirement.evidence(),requirement.reason())));
        }}
        return new ArchitectureBridges.Input(root,inventory,policy,write(index,"assembly.partitions",SemanticTables.Kind.CONTEXT,cancelled),write(index,"assembly.endpoints",SemanticTables.Kind.OBSERVATION,cancelled),write(index,"assembly.joined",SemanticTables.Kind.CENSUS,cancelled));
    }
    private static String httpPrefix(ArchitectureBridges.Endpoint endpoint){
        String path=endpoint.descriptor().get("path"),digest=endpoint.descriptor().get("pathDigest"),method=endpoint.descriptor().get("method");
        if(path==null&&digest==null)return null;
        // Hashes select a bucket only. The literal predicate still checks every operand.
        String key=path!=null?ContentDigest.sha256Utf8(path).value():digest;
        return key+":"+(method==null?"":ContentDigest.sha256Utf8(method).value()+":");
    }
    private static String protocolPrefix(ArchitectureBridges.Endpoint endpoint){
        List<String> keys=switch(endpoint.profile()){
            case B03->List.of("namespace","service","operation");
            case B04->List.of("protocol","namespace","channel");
            default->List.of();
        };
        if(keys.isEmpty()||keys.stream().anyMatch(key->endpoint.descriptor().getOrDefault(key,"").isBlank()))return null;
        return endpoint.profile()+":"+com.evolution.analysis.contract.serialization.CanonicalJson.sha256(keys.stream().map(endpoint.descriptor()::get).toList()).value()+":";
    }
    private static boolean protocolCandidate(ArchitectureBridges.Endpoint source,ArchitectureBridges.Endpoint target){
        if(source.profile()!=target.profile()||!target.direction().equals("PROVIDES"))return false;
        for(String operand:source.profile().operands){String a=source.descriptor().get(operand),b=target.descriptor().get(operand);if(a!=null&&b!=null&&!a.equals(b))return false;}
        if(source.profile()==ArchitectureBridges.Profile.B04){String a=source.descriptor().get("routing"),b=target.descriptor().get("routing");if(a!=null&&b!=null&&!a.equals(b))return false;}
        return true;
    }
    private static boolean literalCandidate(ArchitectureBridges.Endpoint source,ArchitectureBridges.Endpoint target){
        if(target.profile()!=ArchitectureBridges.Profile.B02||!target.direction().equals("PROVIDES"))return false;
        if("EXPOSES".equals(source.descriptor().get("bindingRole"))&&!target.descriptor().containsKey("contract"))return false;
        String path=target.descriptor().get("path"),method=target.descriptor().get("method"),literal=source.descriptor().get("path"),digest=source.descriptor().get("pathDigest"),sourceMethod=source.descriptor().get("method");
        return path!=null&&method!=null&&(literal!=null||digest!=null)&&(literal==null||literal.equals(path))&&(digest==null||digest.equals(ContentDigest.sha256Utf8(path).value()))&&(sourceMethod==null||sourceMethod.equals(method));
    }
    private static String key(String partition,String local){return partition.length()+":"+partition+":"+local;}
    private SemanticTables.Table write(SemanticRootCensus.Index index,String space,SemanticTables.Kind kind,BooleanSupplier cancelled)throws IOException{
        try(var rows=index.rows(space,false)){var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row next;boolean loaded;public boolean hasNext(){if(!loaded)try{next=rows.next();loaded=true;}catch(IOException failure){throw new UncheckedIOException(failure);}return next!=null;}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};
            try{return tables.write(kind,iterator,cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}}
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("BRIDGE_ASSEMBLY_CANCELLED");}
}
