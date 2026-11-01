package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded evidence navigation over an imported architecture capture, with exact continuation. */
public final class ArchitectureCaptureQuery {
    public enum Section { FACTS, OBLIGATIONS, GENERATED_ARTIFACTS }
    public record Filter(Section section,Optional<String> category,Optional<String> document) {
        public Filter {Objects.requireNonNull(section);Objects.requireNonNull(category);Objects.requireNonNull(document);if(category.isPresent()&&!ArchitectureCapture.CATEGORIES.contains(category.get()))throw new IllegalArgumentException("ARCHITECTURE_QUERY_CATEGORY");}
    }
    public record Cursor(ContentDigest request,SemanticTables.Cursor position) {}
    public record Page(List<SemanticTables.Row> rows,Optional<Cursor> next,boolean cancelled) {public Page {rows=List.copyOf(rows);Objects.requireNonNull(next);}}
    private final SemanticTables tables;
    public ArchitectureCaptureQuery(SemanticTables tables){this.tables=Objects.requireNonNull(tables);}
    public Page occurrences(ArchitectureCaptureAudit.Result audit,Optional<String> category,Optional<String> document,Optional<Cursor> cursor,int scanLimit,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(category);Objects.requireNonNull(document);if(category.isPresent()&&!ArchitectureCapture.CATEGORIES.contains(category.get()))throw new IllegalArgumentException("ARCHITECTURE_QUERY_CATEGORY");
        var request=CanonicalJson.sha256(List.of("architecture-audit-query:m8.4-v1",audit.identity(),category,document));
        return scan(audit.dispositions(),request,category,document,"path",cursor,scanLimit,cancelled);
    }
    public Page page(ArchitectureCapture.Result capture,Filter filter,Optional<Cursor> cursor,int scanLimit,BooleanSupplier cancelled)throws IOException {
        var request=CanonicalJson.sha256(List.of("architecture-query:m8.4-v1",capture.identity(),filter));
        var table=switch(filter.section()){case FACTS->capture.facts();case OBLIGATIONS->capture.obligations();case GENERATED_ARTIFACTS->capture.artifacts();};
        return scan(table,request,filter.category(),filter.document(),filter.section()==Section.GENERATED_ARTIFACTS?"source":"path",cursor,scanLimit,cancelled);
    }
    private Page scan(SemanticTables.Table table,ContentDigest request,Optional<String> category,Optional<String> document,String documentField,Optional<Cursor> cursor,int scanLimit,BooleanSupplier cancelled)throws IOException{
        if(scanLimit<1||scanLimit>1000)throw new IllegalArgumentException("ARCHITECTURE_QUERY_PAGE_LIMIT");
        if(cursor.isPresent()&&!cursor.get().request().equals(request))throw new IOException("ARCHITECTURE_QUERY_CURSOR_SCOPE");
        var rows=new ArrayList<SemanticTables.Row>();
        var scan=tables.scan(table,cursor.map(Cursor::position),scanLimit,row->{
            var fields=row.fields();if(category.isPresent()&&!category.get().equals(fields.get("category")))return;
            if(document.isPresent()&&!document.get().equals(fields.get(documentField)))return;
            rows.add(row);
        },cancelled);
        return new Page(rows,scan.end()?Optional.empty():Optional.of(new Cursor(request,scan.cursor())),scan.cancelled());
    }
}
