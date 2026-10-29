package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Classifies every sealed inventory row, including inaccessible/unknown/raw paths. No host materialization. */
public final class PolyglotInventory {
    public record Report(ContentDigest inventory,SemanticTables.Table entries,long files,long unresolvedClassification,VersionedIdentifier classifier){
        public Report{Objects.requireNonNull(inventory);Objects.requireNonNull(entries);Objects.requireNonNull(classifier);if(entries.kind()!=SemanticTables.Kind.DOCUMENT||files<0||files>entries.rows()||unresolvedClassification<0||unresolvedClassification>files)throw new IllegalArgumentException("POLYGLOT_INVENTORY_REPORT_COUNTS");}
        public Report(ContentDigest inventory,SemanticTables.Table entries,long files,long unresolvedClassification){this(inventory,entries,files,unresolvedClassification,new VersionedIdentifier("language.discovery",LanguageDiscovery.VERSION));}
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("polyglot-inventory:m8.4-v2",classifier,inventory,entries,files,unresolvedClassification));}
    }
    public Report scan(String revision,InventoryReader inventory,RepositoryContentStore content,SemanticTables tables,BooleanSupplier cancelled)throws IOException{
        var manifest=inventory.manifest(revision).orElseThrow(()->new IOException("SEALED_INVENTORY_REQUIRED"));
        var iterator=new ClassifiedRows(revision,inventory,content,manifest.inventory(),cancelled);
        SemanticTables.Table table;
        try{table=tables.write(SemanticTables.Kind.DOCUMENT,iterator,cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}
        if(table.rows()!=manifest.entries()||!inventory.manifest(revision).filter(manifest::equals).isPresent())throw new IOException("POLYGLOT_INVENTORY_DENOMINATOR_CHANGED");
        return new Report(manifest.inventory(),table,iterator.files,iterator.unknown);
    }
    private static final class ClassifiedRows implements Iterator<SemanticTables.Row> {
        final String revision;final InventoryReader inventory;final RepositoryContentStore content;final ContentDigest scope;final BooleanSupplier cancelled;
        Iterator<InventoryEntry> page=Collections.emptyIterator();Optional<String> cursor=Optional.empty();boolean ended;long files,unknown;
        ClassifiedRows(String revision,InventoryReader inventory,RepositoryContentStore content,ContentDigest scope,BooleanSupplier cancelled){this.revision=revision;this.inventory=inventory;this.content=content;this.scope=scope;this.cancelled=cancelled;}
        @Override public boolean hasNext(){
            try{
                while(!page.hasNext()&&!ended){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("POLYGLOT_INVENTORY_CANCELLED");
                    var result=inventory.page(revision,256,cursor);if(result.entries().size()>256)throw new IOException("INVENTORY_PAGE_CONTRACT");
                    if(result.continuation().isPresent()&&result.continuation().equals(cursor))throw new IOException("INVENTORY_CURSOR_DID_NOT_ADVANCE");
                    page=result.entries().iterator();cursor=result.continuation();ended=cursor.isEmpty();}
                return page.hasNext();
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }
        @Override public SemanticTables.Row next(){
            if(!hasNext())throw new NoSuchElementException();var entry=page.next();var fields=new TreeMap<String,Object>();
            fields.put("rawPath",entry.path().rawBase64());fields.put("kind",entry.kind().name());fields.put("population",entry.population().name());fields.put("disposition",entry.disposition().name());fields.put("content",entry.content().orElse(null));
            var detection=LanguageDiscovery.detect(entry.path().utf8().orElse("raw-path"),new byte[0],Optional.empty());String reason="INVENTORIED_NON_FILE";
            if(entry.kind()==InventoryEntry.Kind.FILE){files=Math.addExact(files,1);reason="CLASSIFICATION_HINT_ONLY";
                if(entry.path().utf8().isEmpty())reason="RAW_PATH_CLASSIFIER_REQUIRED";
                else if(entry.disposition()!=InventoryEntry.Disposition.ACQUIRED)reason="INPUT_UNAVAILABLE_OR_EXCLUDED";
                else try{
                    var handle=entry.content().orElseThrow();if(!content.verify(handle))throw new IOException("CONTENT_UNAVAILABLE");
                    try(var stream=content.range(handle,0,Math.min(handle.size(),8192))){detection=LanguageDiscovery.detect(entry.path().utf8().orElseThrow(),stream.readNBytes(8192),Optional.empty());}
                }catch(IOException failure){reason="CLASSIFICATION_INPUT_UNAVAILABLE";}
                if(detection.state()==LanguageDiscovery.State.UNKNOWN||detection.state()==LanguageDiscovery.State.AMBIGUOUS||detection.state()==LanguageDiscovery.State.CONFLICTED||!reason.equals("CLASSIFICATION_HINT_ONLY"))unknown=Math.addExact(unknown,1);
            }
            fields.put("classification",detection.state().name());fields.put("languageCandidates",detection.candidates());fields.put("classificationEvidence",detection.evidence());fields.put("reason",reason);
            return new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,entry.path().key(),scope,fields);
        }
    }
}
