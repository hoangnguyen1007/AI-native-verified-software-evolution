package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Replays a captured language engine inside its original compilation context. */
public final class LanguageBridgeImport {
    private final RepositoryContentStore content;private final SemanticTables tables;private final int workerBytes;
    public LanguageBridgeImport(RepositoryContentStore content,SemanticTables tables,int workerBytes){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);if(workerBytes<1)throw new IllegalArgumentException("BRIDGE_LANGUAGE_WORKER_BUDGET");this.workerBytes=workerBytes;}
    public record Imported(BridgeAssembly.Fragment fragment,ContentDigest semanticIdentity){}
    public BridgeAssembly.Fragment read(String key,LanguageSemanticRequest request,NativeSemanticCapture capture,SemanticRootCensus.Index index,BooleanSupplier cancelled)throws IOException{
        return readDetailed(key,request,capture,index,cancelled).fragment();
    }
    public Imported readDetailed(String key,LanguageSemanticRequest request,NativeSemanticCapture capture,SemanticRootCensus.Index index,BooleanSupplier cancelled)throws IOException{
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("BRIDGE_LANGUAGE_CANCELLED");
        var actual=NativeSemanticCapture.read(capture.export(),tables,content,workerBytes,cancelled);if(!actual.equals(capture))throw new IOException("BRIDGE_LANGUAGE_CAPTURE_BINDING");
        var observations=actual.asProvider(request,tables,content).analyze(request,cancelled);String space="language.bridge.gaps."+request.identity().value();try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("BRIDGE_LANGUAGE_INDEX_NOT_EMPTY");}
        try(var rows=tables.open(request.census(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)index.put(space+".sites",row.identity().value(),row);}
        SemanticReconciler.Coverage coverage;
        try{coverage=new SemanticReconciler().reconcile(request,observations,tables,content,Map.of(),Set.of(),request::policy,gap->{String id=gap.gapIdentity().value();try{if(index.get(space,id).isEmpty()){var fields=new TreeMap<String,Object>();fields.put("reasonCode",gap.reasonCode());fields.put("gap",GraphJson.read(CanonicalJson.write(gap),new GraphJson.Limits(workerBytes,64,workerBytes)));var site=index.get(space+".sites",gap.subject().identity());if(site.isPresent()){fields.put("category",site.orElseThrow().fields().get("category"));fields.put("path",site.orElseThrow().fields().get("document"));}index.put(space,id,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,id,request.scope(),fields));}}catch(IOException failure){throw new UncheckedIOException(failure);}},cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}
        SemanticTables.Table gapTable;try(var rows=index.rows(space,false)){gapTable=writeRows(rows,cancelled);}var artifacts=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),cancelled);
        var requestBytes=content.put(new ByteArrayInputStream(CanonicalJson.write(request).getBytes(StandardCharsets.UTF_8)),cancelled);
        var identity=CanonicalJson.sha256(List.of("language-bridge-child:m8.4-v1",request.identity(),actual.export(),observations,gapTable,coverage.semanticIdentity()));
        var child=new ArchitectureBridges.Child(requestBytes,identity,view(request,capture,observations,cancelled),gapTable,artifacts);var scope=ArchitectureBridges.scope(request.context(),request.inventory());
        boolean semanticGaps=false;try(var rows=tables.open(gapTable,cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)if(!Set.of("CENSUS_METHOD_UNQUALIFIED","SEMANTIC_METHOD_UNQUALIFIED").contains(row.fields().get("reasonCode")))semanticGaps=true;}
        var partition=new ArchitectureBridges.Partition(request.scope(),request.context().snapshotIdentity().value(),request.root(),request.module(),request.sourceSet(),request.variant(),request.language(),semanticGaps?"PARTIAL":"COMPLETE",request.documents(),Optional.of(actual.export()),Optional.of(child));
        var needs=new ArrayList<SemanticTables.Row>();for(var profile:ArchitectureBridges.Profile.values())needs.add(ArchitectureBridges.row(SemanticTables.Kind.CENSUS,"category."+profile,scope,new ArchitectureBridges.Requirement(key,profile,"UNKNOWN",Optional.empty(),List.of(),actual.export(),"Independent interop occurrence/absence census is required; language-engine coverage is separate.")));
        return new Imported(new BridgeAssembly.Fragment(ArchitectureBridges.row(SemanticTables.Kind.CONTEXT,key,scope,partition),tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),cancelled),tables.write(SemanticTables.Kind.CENSUS,needs.iterator(),cancelled)),coverage.semanticIdentity());
    }
    private SemanticTables.Table view(LanguageSemanticRequest request,NativeSemanticCapture capture,SemanticTables.Table observations,BooleanSupplier cancelled)throws IOException{
        try(var census=tables.open(capture.census(),cancelled);var facts=tables.open(observations,cancelled)){
            var rows=new SemanticRootCensus.Rows(){SemanticTables.Row expected;public SemanticTables.Row next()throws IOException{var observed=facts.next();if(observed==null)return null;if(expected==null)expected=census.next();while(expected!=null&&expected.key().compareTo(observed.key())<0)expected=census.next();if(expected==null||!expected.key().equals(observed.key())||!expected.identity().value().equals(observed.fields().get("obligation")))throw new IOException("BRIDGE_LANGUAGE_CENSUS_BINDING");var fields=new TreeMap<String,Object>(observed.fields());fields.put("category",expected.fields().get("category"));fields.put("path",expected.fields().get("document"));fields.put("nativeObservation",observed.identity());return new SemanticTables.Row(observed.kind(),observed.key(),observed.scope(),fields);}public void close(){}};
            return writeRows(rows,cancelled);
        }
    }
    private SemanticTables.Table writeRows(SemanticRootCensus.Rows rows,BooleanSupplier cancelled)throws IOException{
        var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row next;boolean loaded;public boolean hasNext(){if(!loaded)try{next=rows.next();loaded=true;}catch(IOException failure){throw new UncheckedIOException(failure);}return next!=null;}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();loaded=false;return next;}};
        try{return tables.write(SemanticTables.Kind.OBSERVATION,iterator,cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}
    }

}
