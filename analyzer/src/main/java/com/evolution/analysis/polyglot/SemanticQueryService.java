package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Bounded product views over a revalidated immutable publication. Available
 * producer data never becomes semantic authority through a query or a cursor.
 */
public final class SemanticQueryService {
    public static final String VERSION="semantic.query:m8.4-v1";
    public record Publication(ContentHandle document,ContentDigest plan,ContentDigest policy,ContentHandle report,SemanticTables.Table contexts,long revision,Optional<SemanticPublicationValidation.Validated> authority){
        public Publication{Objects.requireNonNull(authority);}
        public Publication(ContentHandle document,ContentDigest plan,ContentDigest policy,ContentHandle report,SemanticTables.Table contexts,long revision){this(document,plan,policy,report,contexts,revision,Optional.empty());}
    }
    /** Implementations revalidate the publication, selected-context bindings,
     * referenced bytes and live policy on every call, including continuations. */
    @FunctionalInterface public interface Repository {Optional<Publication> load(ContentDigest plan,BooleanSupplier cancelled)throws IOException;}
    public record Summary(ContentDigest plan,ContentDigest publication,String revision,String selected,String processed,String failed,String missingProviders,String incomplete,
            String trust,String qualification,boolean complete,String rootGap,Map<String,Object> accounting){public Summary{accounting=Collections.unmodifiableMap(new TreeMap<>(accounting));}}
    public record Position(ContentDigest table,String rows,Optional<String> previousKey){
        public Position{Objects.requireNonNull(table);Objects.requireNonNull(previousKey);if(rows==null||!rows.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("SEMANTIC_QUERY_CURSOR_COUNT");new SemanticTables.Cursor(table,Long.parseLong(rows),previousKey);}
        SemanticTables.Cursor nativePosition(){return new SemanticTables.Cursor(table,Long.parseLong(rows),previousKey);}
    }
    public record Cursor(ContentDigest publication,Position table) {
        public Cursor{Objects.requireNonNull(publication);Objects.requireNonNull(table);}
        public Cursor(ContentDigest publication,SemanticTables.Cursor cursor){this(publication,new Position(cursor.table(),Long.toString(cursor.rows()),cursor.previousKey()));}
    }
    public static Cursor cursor(Object value){
        var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("publication","table")))throw new IllegalArgumentException("SEMANTIC_QUERY_CURSOR_FIELDS");var table=GraphJson.object(row.get("table"));if(!table.keySet().equals(Set.of("table","rows","previousKey")))throw new IllegalArgumentException("SEMANTIC_QUERY_CURSOR_FIELDS");
        return new Cursor(new ContentDigest(GraphJson.string(row.get("publication"))),new Position(new ContentDigest(GraphJson.string(table.get("table"))),GraphJson.string(table.get("rows")),Optional.ofNullable(table.get("previousKey")).map(GraphJson::string)));
    }
    public record Context(String key,String state,ContentDigest request,ContentDigest scope,String language,String dialect,String root,String module,String sourceSet,String variant,
            VersionedIdentifier provider,ContentDigest tool,Map<String,Object> coverage,String gaps,Map<String,Object> answers) {public Context{coverage=Collections.unmodifiableMap(new TreeMap<>(coverage));answers=Collections.unmodifiableMap(new TreeMap<>(answers));}}
    public record Page<T>(Summary summary,List<T> items,Optional<Cursor> continuation){public Page{items=List.copyOf(items);Objects.requireNonNull(continuation);}}
    private final Repository repository;private final RepositoryContentStore content;private final SemanticTables tables;private final Supplier<ContentDigest> livePolicy;
    public SemanticQueryService(Repository repository,RepositoryContentStore content,SemanticTables tables,Supplier<ContentDigest> livePolicy){this.repository=Objects.requireNonNull(repository);this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.livePolicy=Objects.requireNonNull(livePolicy);}
    public Summary summary(ContentDigest plan,BooleanSupplier cancelled)throws IOException{var publication=publication(plan,cancelled);var result=summary(publication);check(publication,cancelled);return result;}
    public Page<Context> contexts(ContentDigest plan,int size,Optional<Cursor> cursor,BooleanSupplier cancelled)throws IOException{
        var publication=publication(plan,cancelled);var summary=summary(publication);var items=new ArrayList<Context>();
        var scan=scan(publication,publication.contexts(),size,cursor,row->{
            var receipt=receipt(publication,row);var request=LanguageSemanticRequest.from(read(handle(receipt.get("request"))));
            if(!request.identity().value().equals(row.fields().get("request")))throw new IOException("SEMANTIC_QUERY_REQUEST_BINDING");
            var coverage=new TreeMap<String,Object>();if(receipt.get("coverage")!=null)GraphJson.object(receipt.get("coverage")).forEach((key,value)->coverage.put(key,value instanceof java.math.BigDecimal decimal?decimal.toBigIntegerExact().toString():value));
            var answers=new TreeMap<String,Object>();answers.put("availability","UNAVAILABLE");if(receipt.get("answers")!=null){var answer=read(handle(receipt.get("answers")));var history=SemanticContextCoordinator.history(answer);answers.put("availability",publication.authority().isPresent()?"LIVE_VALIDATED_CURRENT":"CAPTURED_PRODUCER_VIEW");answers.put("complete",answer.get("answersComplete"));answers.put("questions",count(answer,"questions"));answers.put("unavailableRevisions",count(answer,"unavailableRevisions"));answers.put("historyRevisions",Long.toString(history.revisions().rows()));answers.put("conflicts",Long.toString(table(answer.get("conflicts")).rows()));var counts=new TreeMap<String,String>();GraphJson.object(answer.get("counts")).forEach((key,value)->counts.put(key,Long.toString(number(value))));answers.put("counts",counts);}
            if(receipt.get("answers")==null&&receipt.get("history")!=null){var history=SemanticAnswerLedger.History.from(receipt.get("history"));answers.put("availability","RETAINED_HISTORY_WITHOUT_CURRENT");answers.put("historyRevisions",Long.toString(history.revisions().rows()));}
            items.add(new Context(row.key(),GraphJson.string(receipt.get("state")),request.identity(),request.scope(),request.language(),request.dialect(),request.root(),request.module(),request.sourceSet(),request.variant(),request.provider(),request.tool(),coverage,Long.toString(table(receipt.get("gaps")).rows()),answers));
        },cancelled);check(publication,cancelled);return new Page<>(summary,items,next(publication,publication.contexts(),scan));
    }
    public Page<Map<String,Object>> gaps(ContentDigest plan,String contextKey,int size,Optional<Cursor> cursor,BooleanSupplier cancelled)throws IOException{
        if(contextKey==null||contextKey.isEmpty())throw new IllegalArgumentException("SEMANTIC_QUERY_CONTEXT_REQUIRED");
        var publication=publication(plan,cancelled);var summary=summary(publication);var gaps=table(contextReceipt(publication,contextKey,cancelled).get("gaps"));if(gaps.kind()!=SemanticTables.Kind.CAPABILITY_GAP)throw new IOException("SEMANTIC_QUERY_GAP_KIND");
        var items=new ArrayList<Map<String,Object>>();var scan=scan(publication,gaps,size,cursor,row->items.add(row.fields()),cancelled);check(publication,cancelled);return new Page<>(summary,items,next(publication,gaps,scan));
    }
    public Page<Map<String,Object>> rootGaps(ContentDigest plan,int size,Optional<Cursor> cursor,BooleanSupplier cancelled)throws IOException{
        var publication=publication(plan,cancelled);var summary=summary(publication);var report=read(publication.report());var items=new ArrayList<Map<String,Object>>();
        if("semantic-root-report:m8.4-v2".equals(report.get("schema"))){
            var gaps=table(GraphJson.object(report.get("rootCensus")).get("gaps"));if(gaps.kind()!=SemanticTables.Kind.CAPABILITY_GAP)throw new IOException("SEMANTIC_QUERY_ROOT_GAP_KIND");var scan=scan(publication,gaps,size,cursor,row->items.add(row.fields()),cancelled);check(publication,cancelled);return new Page<>(summary,items,next(publication,gaps,scan));
        }
        // Legacy roots have one explicit method gap and no independent census.
        if(size<1||size>50)throw new IllegalArgumentException("SEMANTIC_QUERY_PAGE_SIZE");if(cursor.isPresent())throw new IOException("SEMANTIC_QUERY_ROOT_GAP_CURSOR_SCOPE");items.add(GraphJson.object(report.get("rootGap")));check(publication,cancelled);return new Page<>(summary,items,Optional.empty());
    }
    public Page<Map<String,Object>> answers(ContentDigest plan,String contextKey,String view,int size,Optional<Cursor> cursor,BooleanSupplier cancelled)throws IOException{
        if(!Set.of("CURRENT","HISTORY","CONFLICTS").contains(view))throw new IllegalArgumentException("SEMANTIC_QUERY_ANSWER_VIEW");var publication=publication(plan,cancelled);var summary=summary(publication);var receipt=contextReceipt(publication,contextKey,cancelled);SemanticTables.Table selected;
        if(receipt.get("answers")==null){if(!view.equals("HISTORY")||receipt.get("history")==null)throw new IOException("SEMANTIC_QUERY_ANSWERS_NOT_AVAILABLE");selected=SemanticAnswerLedger.History.from(receipt.get("history")).revisions();}
        else{var answer=read(handle(receipt.get("answers")));var history=SemanticContextCoordinator.history(answer);selected=view.equals("HISTORY")?history.revisions():table(answer.get(view.equals("CURRENT")?"current":"conflicts"));}
        var expected=view.equals("HISTORY")?SemanticTables.Kind.REVISION:view.equals("CURRENT")?SemanticTables.Kind.CURRENT:SemanticTables.Kind.CONFLICT;if(selected.kind()!=expected)throw new IOException("SEMANTIC_QUERY_ANSWER_KIND");var items=new ArrayList<Map<String,Object>>();var scan=scan(publication,selected,size,cursor,row->items.add(Map.of("key",row.key(),"scope",row.scope(),"fields",row.fields())),cancelled);check(publication,cancelled);return new Page<>(summary,items,next(publication,selected,scan));
    }
    private Map<String,Object> contextReceipt(Publication publication,String key,BooleanSupplier cancelled)throws IOException{
        if(key==null||key.isEmpty())throw new IllegalArgumentException("SEMANTIC_QUERY_CONTEXT_REQUIRED");try(var reader=tables.open(publication.contexts(),cancelled)){SemanticTables.Row row;while((row=reader.next())!=null){if(row.key().equals(key))return receipt(publication,row);if(row.key().compareTo(key)>0)break;}}throw new IOException("SEMANTIC_QUERY_CONTEXT_NOT_FOUND");
    }
    private Publication publication(ContentDigest plan,BooleanSupplier cancelled)throws IOException{var publication=repository.load(plan,cancelled).orElseThrow(()->new IOException("SEMANTIC_QUERY_NOT_FOUND"));if(!publication.plan().equals(plan)||publication.contexts().kind()!=SemanticTables.Kind.CONTEXT)throw new IOException("SEMANTIC_QUERY_PUBLICATION_SCOPE");check(publication,cancelled);return publication;}
    private Summary summary(Publication publication)throws IOException{
        boolean validated=publication.authority().isPresent(),complete=validated&&publication.authority().orElseThrow().complete();var report=read(publication.report());if(!Set.of("semantic-root-report:m8.4-v1","semantic-root-report:m8.4-v2").contains(report.get("schema"))||!Boolean.valueOf(complete).equals(report.get("complete"))||!table(report.get("contexts")).equals(publication.contexts()))throw new IOException("SEMANTIC_QUERY_REPORT_BINDING");
        var accounting=new TreeMap<String,Object>();accounting.put("availability","UNAVAILABLE");
        if(report.get("rootCensus")!=null){var census=GraphJson.object(report.get("rootCensus"));accounting.put("availability",validated?"LIVE_VALIDATED_ACCOUNTING":"REPLAYED_PRODUCER_ACCOUNTING");accounting.put("structurallyClosed",census.get("structurallyClosed"));accounting.put("qualification",validated?"CURRENT_POLICY_VALIDATED":"UNQUALIFIED");accounting.put("input",SemanticRootCensus.input(census.get("input")).identity());accounting.put("gaps",Long.toString(table(census.get("gaps")).rows()));GraphJson.object(census.get("accounting")).forEach((key,value)->accounting.put(key,Long.toString(number(value))));}
        return new Summary(publication.plan(),publication.document().digest(),Long.toString(publication.revision()),count(report,"selected"),count(report,"processed"),count(report,"failed"),count(report,"missingProviders"),count(report,"incomplete"),validated?"VALIDATED_OBSERVATION":"PRODUCER_ASSERTION",validated?"CURRENT_POLICY_VALIDATED":"UNQUALIFIED",complete,report.get("rootGap")==null?"":GraphJson.string(GraphJson.object(report.get("rootGap")).get("reasonCode")),accounting);
    }
    private Map<String,Object> receipt(Publication publication,SemanticTables.Row row)throws IOException{var receipt=read(handle(row.fields().get("receipt")));if(!row.scope().equals(publication.plan())||!publication.plan().value().equals(receipt.get("plan"))||!row.key().equals(receipt.get("key"))||!row.fields().get("state").equals(receipt.get("state")))throw new IOException("SEMANTIC_QUERY_RECEIPT_BINDING");return receipt;}
    private SemanticTables.Scan scan(Publication publication,SemanticTables.Table table,int size,Optional<Cursor> cursor,SemanticTables.Consumer consumer,BooleanSupplier cancelled)throws IOException{
        if(size<1||size>50)throw new IllegalArgumentException("SEMANTIC_QUERY_PAGE_SIZE");if(cursor.isPresent()&&!cursor.orElseThrow().publication().equals(publication.document().digest()))throw new IOException("SEMANTIC_QUERY_STALE_CURSOR");
        var scan=tables.scan(table,cursor.map(value->value.table().nativePosition()),size,consumer,cancelled);if(scan.cancelled())throw new IOException("SEMANTIC_QUERY_CANCELLED");return scan;
    }
    private Optional<Cursor> next(Publication publication,SemanticTables.Table table,SemanticTables.Scan scan){return scan.end()||scan.cursor().rows()==table.rows()?Optional.empty():Optional.of(new Cursor(publication.document().digest(),scan.cursor()));}
    private void check(Publication publication,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_QUERY_CANCELLED");if(!publication.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_QUERY_POLICY_REVOKED");if(publication.authority().isPresent()&&!publication.authority().orElseThrow().matches(publication.plan(),publication.policy(),publication.report()))throw new IOException("SEMANTIC_QUERY_AUTHORITY_REVOKED");}
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_QUERY_CONTENT_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_QUERY_NONCANONICAL_RECORD");return value;}}
    private static long number(Object value){long result=((java.math.BigDecimal)value).longValueExact();if(result<0)throw new IllegalArgumentException("SEMANTIC_QUERY_NEGATIVE_COUNT");return result;}
    private static String count(Map<String,Object> value,String key){return Long.toString(number(value.get(key)));}
    private static ContentHandle handle(Object value){return LanguageSemanticRequest.handle(value);}
    private static SemanticTables.Table table(Object value){return LanguageSemanticRequest.table(value);}
}
