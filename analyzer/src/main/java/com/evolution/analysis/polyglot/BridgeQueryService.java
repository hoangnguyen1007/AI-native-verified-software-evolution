package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One domain projection shared by HTTP, CLI and exports. Filtering scans a
 * bounded prefix and never changes the full immutable result denominator. */
public final class BridgeQueryService {
    public static final String VERSION="architecture.bridge-query:m8.4-v1";
    public enum Section { PARTITIONS, ENDPOINTS, REQUIREMENTS, RELATIONS, GAPS, FACTS, NATIVE_GAPS, ARTIFACTS, DOCUMENTS }
    public record ScopeKeys(List<ContentDigest> items,Optional<ContentDigest> continuation){public ScopeKeys{items=List.copyOf(items);}}
    public record CatalogEntry(ContentDigest scope,String name,String revision,String partitions,String availability,String reason){}
    public record Catalog(List<CatalogEntry> items,Optional<ContentDigest> continuation){public Catalog{items=List.copyOf(items);}}
    public record View(ContentHandle publication,long revision,ArchitectureBridges.Input input,ArchitectureBridges.Result result){
        public ContentDigest identity(){return CanonicalJson.sha256(List.of(VERSION,publication,result.identity()));}
    }
    @FunctionalInterface public interface Repository {
        Optional<View> load(ContentDigest scope,BooleanSupplier cancelled)throws IOException;
        default ScopeKeys scopes(Optional<ContentDigest> after,int size,BooleanSupplier cancelled)throws IOException{return new ScopeKeys(List.of(),Optional.empty());}
        /** Navigation labels only; selecting a scope still performs full replay. */
        default Optional<View> navigation(ContentDigest scope,BooleanSupplier cancelled)throws IOException{return load(scope,cancelled);}
        /** Navigation never returns semantic outcomes or authority. */
        default void checkNavigation(View view,BooleanSupplier cancelled)throws IOException{check(view,cancelled);}
        /** Recheck live policy/withdrawals and publication fence before return. */
        default void check(View view,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean())throw new IOException("BRIDGE_QUERY_CANCELLED");}
    }
    public record Selection(Section section,Optional<String> category,Optional<String> partition,Optional<String> symbol,Optional<String> document,Optional<String> search){
        public Selection(Section section,Optional<String> category,Optional<String> partition,Optional<String> symbol){this(section,category,partition,symbol,Optional.empty(),Optional.empty());}
        public Selection(Section section,Optional<String> category,Optional<String> partition,Optional<String> symbol,Optional<String> document){this(section,category,partition,symbol,document,Optional.empty());}
        public Selection{Objects.requireNonNull(section);Objects.requireNonNull(category);Objects.requireNonNull(partition);Objects.requireNonNull(symbol);Objects.requireNonNull(document);Objects.requireNonNull(search);category.ifPresent(v->{if(child(section)){if(Set.of(Section.ARTIFACTS,Section.DOCUMENTS).contains(section)||!(ArchitectureCapture.CATEGORIES.contains(v)||LanguageProfile.requiredCategories().contains(v)))throw new IllegalArgumentException("BRIDGE_QUERY_CHILD_CATEGORY");}else ArchitectureBridges.Profile.valueOf(v);});symbol.ifPresent(ContentDigest::new);if(child(section)&&partition.isEmpty())throw new IllegalArgumentException("BRIDGE_QUERY_PARTITION_REQUIRED");if(document.isPresent()){com.evolution.analysis.acquisition.streaming.LogicalPath.utf8(document.orElseThrow());if(!child(section)&&section!=Section.ENDPOINTS)throw new IllegalArgumentException("BRIDGE_QUERY_DOCUMENT_SECTION");}search.ifPresent(v->{if(v.isBlank()||v.length()>256||v.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("BRIDGE_QUERY_SEARCH");});}
    }
    public record Cursor(ContentDigest request,SemanticQueryService.Position position){
        public Cursor(ContentDigest request,SemanticTables.Cursor position){this(request,new SemanticQueryService.Position(position.table(),Long.toString(position.rows()),position.previousKey()));}
    }
    public record Summary(ContentDigest scope,ContentDigest publication,ContentDigest input,ContentDigest result,ContentDigest semanticIdentity,String revision,String partitions,String endpoints,String requirements,String missingCensus,String gaps,
            Map<String,String> outcomes,String trust,boolean qualified,boolean complete,String assessment,Accounting accounting){public Summary{outcomes=Collections.unmodifiableMap(new TreeMap<>(outcomes));}}
    public record Accounting(String bindings,String evidenceObligations,Map<String,String> bindingOutcomes,Map<String,String> evidenceOutcomes,Map<String,String> contextStates){public Accounting{bindingOutcomes=Collections.unmodifiableMap(new TreeMap<>(bindingOutcomes));evidenceOutcomes=Collections.unmodifiableMap(new TreeMap<>(evidenceOutcomes));contextStates=Collections.unmodifiableMap(new TreeMap<>(contextStates));}}
    private record AccountingCache(ContentDigest view,Accounting accounting){}
    private volatile AccountingCache accountingCache;
    public record Page(Summary summary,List<SemanticTables.Row> items,Optional<Cursor> continuation,boolean cancelled){public Page{items=List.copyOf(items);}}
    public record ImpactPath(String dependent,List<String> relations,List<String> states){public ImpactPath{relations=List.copyOf(relations);states=List.copyOf(states);}}
    public record Impact(Summary summary,String target,List<ImpactPath> paths,List<SemanticTables.Row> evidence,List<String> frontier,String stop,String scanned,boolean runtimeBreakageProven){public Impact{paths=List.copyOf(paths);evidence=List.copyOf(evidence);frontier=List.copyOf(frontier);}}
    private final Repository repository;private final SemanticTables tables;
    public BridgeQueryService(Repository repository,SemanticTables tables){this.repository=Objects.requireNonNull(repository);this.tables=Objects.requireNonNull(tables);}
    public Summary summary(ContentDigest scope,BooleanSupplier cancelled)throws IOException{var view=view(scope,cancelled);var summary=summarize(view,cancelled);repository.check(view,cancelled);return summary;}
    public Catalog catalog(Optional<ContentDigest> after,int size,BooleanSupplier cancelled)throws IOException{
        if(size<1||size>20)throw new IllegalArgumentException("BRIDGE_CATALOG_SIZE");var keys=repository.scopes(after,size,cancelled);var items=new ArrayList<CatalogEntry>();
        for(var scope:keys.items()){
            if(cancelled.getAsBoolean())throw new IOException("BRIDGE_QUERY_CANCELLED");
            try{var view=repository.navigation(scope,cancelled).orElseThrow(()->new IOException("BRIDGE_QUERY_NOT_FOUND"));if(!view.input().scope().equals(scope)||!view.result().input().equals(view.input().identity()))throw new IOException("BRIDGE_QUERY_VIEW_BINDING");var names=new ArrayList<String>();try(var rows=tables.open(view.input().partitions(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null&&names.size()<3){var part=ArchitectureBridges.partition(row.fields());String name=!part.module().equals("NOT_RECORDED")?part.module():row.key().matches("(?:sha256:)?[a-fA-F0-9-]{32,}")?part.language():row.key();names.add(name.substring(0,name.offsetByCodePoints(0,Math.min(100,name.codePointCount(0,name.length())))));}}repository.checkNavigation(view,cancelled);items.add(new CatalogEntry(scope,names.isEmpty()?"Analysis with no contexts":String.join(" · ",names),Long.toString(view.revision()),Long.toString(view.input().partitions().rows()),"SAVED_UNVERIFIED",""));}
            catch(IOException unavailable){if(cancelled.getAsBoolean())throw unavailable;items.add(new CatalogEntry(scope,"Unreadable analysis","","","UNAVAILABLE","BRIDGE_NAVIGATION_UNAVAILABLE"));}
        }
        return new Catalog(items,keys.continuation());
    }
    public Page page(ContentDigest scope,Selection selection,int size,Optional<Cursor> cursor,BooleanSupplier cancelled)throws IOException{
        if(size<1||size>50)throw new IllegalArgumentException("BRIDGE_QUERY_PAGE_SIZE");var view=view(scope,cancelled);var request=CanonicalJson.sha256(List.of(VERSION,view.identity(),selection));
        if(cursor.isPresent()&&!cursor.orElseThrow().request().equals(request))throw new IOException("BRIDGE_QUERY_STALE_OR_FOREIGN_CURSOR");
        var table=switch(selection.section()){case PARTITIONS->view.input().partitions();case ENDPOINTS->view.input().endpoints();case REQUIREMENTS->view.input().requirements();case RELATIONS->view.result().relations();case GAPS->view.result().gaps();case FACTS,NATIVE_GAPS,ARTIFACTS,DOCUMENTS->childTable(view,selection,cancelled);};
        var items=new ArrayList<SemanticTables.Row>();var position=cursor.map(c->c.position().nativePosition()).orElse(new SemanticTables.Cursor(table.identity(),0,Optional.empty()));if(!position.table().equals(table.identity()))throw new IOException("BRIDGE_QUERY_CURSOR_TABLE");long consumed=position.rows();String previous=null;boolean end=false;
        try(var rows=tables.open(table,cancelled)){for(long i=0;i<position.rows();i++){var row=rows.next();if(row==null)throw new IOException("BRIDGE_QUERY_CURSOR_PREFIX");previous=row.key();}if(!Objects.equals(Optional.ofNullable(previous),position.previousKey()))throw new IOException("BRIDGE_QUERY_CURSOR_PREFIX");
            int budget=selection.search().isPresent()||selection.document().isPresent()?500:size;for(int scanned=0;scanned<budget&&items.size()<size;scanned++){var row=rows.next();if(row==null){end=true;break;}consumed=Math.addExact(consumed,1);previous=row.key();if(matches(row,selection))items.add(row);}}
        var summary=summarize(view,cancelled);repository.check(view,cancelled);var next=new SemanticTables.Cursor(table.identity(),consumed,Optional.ofNullable(previous));return new Page(summary,items,end||consumed==table.rows()?Optional.empty():Optional.of(new Cursor(request,next)),false);
    }
    /** Bounded incoming structural/contract impact. Paths are independently
     * scoped hops, never a proof of a jointly executable runtime world. */
    public Impact impact(ContentDigest scope,ContentDigest target,int depth,int nodes,int scanBudget,BooleanSupplier cancelled)throws IOException{
        if(depth<1||depth>20||nodes<1||nodes>50||scanBudget<1||scanBudget>1000)throw new IllegalArgumentException("BRIDGE_IMPACT_BUDGET");var view=view(scope,cancelled);
        var queue=new ArrayDeque<ImpactPath>();queue.add(new ImpactPath(target.value(),List.of(),List.of()));var visited=new HashSet<String>();visited.add(target.value());var paths=new ArrayList<ImpactPath>();var evidence=new TreeMap<String,SemanticTables.Row>();var frontier=new TreeSet<String>();long scanned=0;String stop="EXHAUSTED_CAPTURED_RELATIONS";
        outer:while(!queue.isEmpty()){
            if(cancelled.getAsBoolean()){stop="CANCELLED";break;}var current=queue.removeFirst();if(current.relations().size()>=depth){frontier.add(current.dependent());stop="DEPTH_LIMIT";continue;}
            try(var rows=tables.open(view.result().relations(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null){
                if(scanned==scanBudget){frontier.add(current.dependent());stop="SCAN_LIMIT";break outer;}scanned++;
                var fields=row.fields();if(Set.of("INFEASIBLE","NOT_APPLICABLE").contains(fields.get("state")))continue;
                if(!(fields.get("from") instanceof Map<?,?> from)||!(fields.get("targets") instanceof List<?> targets))continue;
                boolean incoming=targets.stream().anyMatch(value->value instanceof Map<?,?> ref&&current.dependent().equals(ref.get("identity")));if(!incoming)continue;
                evidence.put(row.key(),row);String dependent=GraphJson.string(from.get("identity"));var steps=new ArrayList<>(current.relations());steps.add(row.key());var states=new ArrayList<>(current.states());states.add(GraphJson.string(fields.get("state")));var path=new ImpactPath(dependent,steps,states);
                // Keep the bounded cycle-closing hop even when its node was seen.
                paths.add(path);if(!visited.contains(dependent)){if(visited.size()==nodes){frontier.add(dependent);stop="NODE_LIMIT";break outer;}visited.add(dependent);queue.addLast(path);}
                if(paths.size()==nodes){frontier.add(dependent);stop="PATH_LIMIT";break outer;}
            }}
        }
        queue.forEach(path->frontier.add(path.dependent()));if(view.result().missingCensus()>0||view.result().counts().entrySet().stream().anyMatch(e->!Set.of("BOUND","NOT_APPLICABLE").contains(e.getKey())&&e.getValue()>0))frontier.add("UNKNOWN_BOUNDARIES:"+view.result().gaps().identity().value());
        var summary=summarize(view,cancelled);repository.check(view,cancelled);return new Impact(summary,target.value(),paths,List.copyOf(evidence.values()),List.copyOf(frontier),stop,Long.toString(scanned),false);
    }
    public record Cycle(List<String> relations,List<String> states,String classification,List<String> reasons){public Cycle{relations=List.copyOf(relations);states=List.copyOf(states);reasons=List.copyOf(reasons);}}
    public record Boundary(String relation,Map<String,Object> from,Map<String,Object> target,String state,String feasibility,List<String> reasons){public Boundary{from=Map.copyOf(from);target=Map.copyOf(target);reasons=List.copyOf(reasons);}}
    public record Topology(Impact impact,List<Cycle> cycles,List<Boundary> boundaries,boolean exhaustive){public Topology{cycles=List.copyOf(cycles);boundaries=List.copyOf(boundaries);}}
    /** Bounded structural answers over the same incoming evidence. Joint feature
     * feasibility is checked across the chosen path; no runtime cycle is asserted. */
    public Topology topology(ContentDigest scope,ContentDigest target,int depth,int nodes,int scanBudget,BooleanSupplier cancelled)throws IOException{
        var impact=impact(scope,target,depth,nodes,scanBudget,cancelled);var edges=new TreeMap<String,SemanticTables.Row>();impact.evidence().forEach(row->edges.put(row.key(),row));var cycles=new ArrayList<Cycle>();var boundaries=new TreeMap<String,Boundary>();
        for(var path:impact.paths()){
            String selected=target.value();var conditions=new Conditions();boolean certain=true;
            for(String key:path.relations()){
                if(cancelled.getAsBoolean())throw new IOException("BRIDGE_QUERY_CANCELLED");var row=edges.get(key);if(row==null){conditions.reasons.add("PATH_EVIDENCE_MISSING");certain=false;continue;}var f=row.fields();var from=GraphJson.object(f.get("from"));String state=GraphJson.string(f.get("state"));if(!state.equals("BOUND"))certain=false;
                String wanted=selected;var candidates=GraphJson.array(f.get("targets")).stream().map(GraphJson::object).filter(ref->wanted.equals(ref.get("identity"))).toList();
                conditions.add(from);if(candidates.size()!=1){conditions.reasons.add("TARGET_CONDITION_ALTERNATIVES");certain=false;}else{
                    var to=candidates.getFirst();conditions.add(to);
                    if(!Objects.equals(from.get("partition"),to.get("partition"))){var local=new Conditions();local.add(from);local.add(to);boundaries.put(key+":"+wanted,new Boundary(key,from,to,state,local.state(),List.copyOf(local.reasons)));}
                }
                selected=GraphJson.string(from.get("identity"));
            }
            if(path.dependent().equals(target.value())){String classification=conditions.conflict?"INFEASIBLE":certain&&conditions.reasons.isEmpty()?"DECLARED_STRUCTURAL_CYCLE":"POTENTIAL_CYCLE";cycles.add(new Cycle(path.relations(),path.states(),classification,List.copyOf(conditions.reasons)));}
        }
        // impact() checked its publication fence after traversal. Recheck after
        // deriving topology so withdrawal during classification cannot return it.
        var view=view(scope,cancelled);if(!summarize(view,cancelled).equals(impact.summary()))throw new IOException("BRIDGE_QUERY_STALE_VIEW");repository.check(view,cancelled);
        return new Topology(impact,cycles,List.copyOf(boundaries.values()),false);
    }
    private static final class Conditions{
        final Map<String,Map<String,String>> selections=new TreeMap<>();final SortedSet<String> reasons=new TreeSet<>();boolean conflict;
        void add(Map<String,Object> ref){if(!(ref.get("condition") instanceof Map<?,?> value)||!(value.get("domain") instanceof String domain)){reasons.add("CONDITION_DOMAIN_UNPROVEN");return;}if(Boolean.TRUE.equals(value.get("unknown")))reasons.add("CONDITION_SELECTION_UNKNOWN");if(!selections.isEmpty()&&!selections.containsKey(domain))reasons.add("CONDITION_DOMAINS_DIFFER");var selected=selections.computeIfAbsent(domain,key->new TreeMap<>());if(!(value.get("selection") instanceof Map<?,?> fields)){reasons.add("CONDITION_SELECTION_UNAVAILABLE");return;}for(var entry:fields.entrySet()){String key=GraphJson.string(entry.getKey()),choice=GraphJson.string(entry.getValue()),old=selected.putIfAbsent(key,choice);if(old!=null&&!old.equals(choice)){conflict=true;reasons.add("INCOMPATIBLE_PATH_SELECTION");}}}
        String state(){return conflict?"INFEASIBLE":reasons.isEmpty()?"COMPATIBLE_DECLARED_SELECTION":"UNKNOWN";}
    }
    /** Incoming/outgoing one-hop potential impact pages retain candidate and
     * unresolved frontiers. Consumers follow continuation before counting. */
    private static boolean matches(SemanticTables.Row row,Selection selection){
        var fields=row.fields();Object category=fields.getOrDefault("category",fields.get("profile"));if(selection.category().isPresent()&&selection.section()!=Section.PARTITIONS&&!(selection.section()==Section.GAPS&&category==null)&&!selection.category().orElseThrow().equals(category))return false;
        if(selection.search().isPresent()&&!searchable(row).toLowerCase(Locale.ROOT).contains(selection.search().orElseThrow().toLowerCase(Locale.ROOT)))return false;
        if(selection.document().isPresent()){Object path=selection.section()==Section.DOCUMENTS?row.key():fields.get("path");if(path==null&&fields.get("source") instanceof Map<?,?> source)path=source.get("path");if(path==null&&fields.get("source") instanceof String source)path=source;if(!selection.document().orElseThrow().equals(path))return false;}
        if(selection.partition().isPresent()&&!child(selection.section())){
            String wanted=selection.partition().orElseThrow();if(selection.section()==Section.PARTITIONS){if(!row.key().equals(wanted))return false;}
            else if(!(selection.section()==Section.GAPS&&fields.get("partition")==null)&&!wanted.equals(fields.get("partition"))&&!references(fields,"partition",wanted))return false;
        }
        if(selection.symbol().isEmpty())return true;String symbol=selection.symbol().orElseThrow();return symbol.equals(row.key())||symbol.equals(fields.get("symbol"))||symbol.equals(fields.get("owner"))||symbol.equals(fields.get("target"))||fields.get("attributes") instanceof Map<?,?> attributes&&symbol.equals(attributes.get("owner"))||references(fields,"symbol",symbol)||references(fields,"identity",symbol);
    }
    private SemanticTables.Table childTable(View view,Selection selection,BooleanSupplier cancelled)throws IOException{
        try(var rows=tables.open(view.input().partitions(),cancelled)){SemanticTables.Row row;String wanted=selection.partition().orElseThrow();while((row=rows.next())!=null){if(row.key().compareTo(wanted)>0)break;if(!row.key().equals(wanted))continue;var partition=ArchitectureBridges.partition(row.fields());if(selection.section()==Section.DOCUMENTS)return partition.documents();var child=partition.child().orElseThrow(()->new IOException("BRIDGE_QUERY_CHILD_NOT_AVAILABLE"));return switch(selection.section()){case FACTS->child.facts();case NATIVE_GAPS->child.obligations();case ARTIFACTS->child.artifacts();default->throw new IllegalArgumentException("BRIDGE_QUERY_CHILD_SECTION");};}}
        throw new IOException("BRIDGE_QUERY_PARTITION_NOT_FOUND");
    }
    private static boolean child(Section section){return Set.of(Section.FACTS,Section.NATIVE_GAPS,Section.ARTIFACTS,Section.DOCUMENTS).contains(section);}
    private static String searchable(SemanticTables.Row row){var f=row.fields();var text=new StringBuilder(row.key());for(String key:List.of("path","kind","module","root","language","reasonCode","reason","relation"))if(f.get(key) instanceof String value)text.append(' ').append(value);for(String collection:List.of("symbols","targets"))if(f.get(collection) instanceof List<?> values)for(Object value:values)if(value instanceof Map<?,?> symbol)for(String key:List.of("name","owner","signature"))if(symbol.get(key) instanceof String name&&!name.startsWith("sha256:"))text.append(' ').append(name);for(String key:List.of("attributes","descriptor","source"))if(f.get(key) instanceof Map<?,?> values)for(Object value:values.values())if(value instanceof String s&&!s.startsWith("sha256:"))text.append(' ').append(s);if(f.get("target") instanceof String target&&!target.startsWith("sha256:"))text.append(' ').append(target);if(f.get("from") instanceof Map<?,?> from&&from.get("source") instanceof Map<?,?> source)text.append(' ').append(source.get("path"));return text.toString();}
    private static boolean references(Map<String,Object> fields,String key,String wanted){
        if(fields.get("from") instanceof Map<?,?> from&&wanted.equals(from.get(key)))return true;
        if(fields.get("targets") instanceof List<?> targets)for(Object value:targets)if(value instanceof Map<?,?> target&&wanted.equals(target.get(key)))return true;
        return false;
    }
    private View view(ContentDigest scope,BooleanSupplier cancelled)throws IOException{
        if(cancelled.getAsBoolean())throw new IOException("BRIDGE_QUERY_CANCELLED");var view=repository.load(scope,cancelled).orElseThrow(()->new IOException("BRIDGE_QUERY_NOT_FOUND"));if(!view.input().scope().equals(scope)||!view.result().input().equals(view.input().identity()))throw new IOException("BRIDGE_QUERY_SCOPE");return view;
    }
    private Summary summarize(View view,BooleanSupplier cancelled)throws IOException{var result=view.result();var counts=new TreeMap<String,String>();result.counts().forEach((key,n)->counts.put(key,Long.toString(n)));return new Summary(view.input().scope(),view.publication().digest(),view.input().identity(),result.identity(),result.semanticIdentity(),Long.toString(view.revision()),Long.toString(result.partitions()),Long.toString(result.endpoints()),Long.toString(view.input().requirements().rows()),Long.toString(result.missingCensus()),Long.toString(result.gaps().rows()),counts,"PRODUCER_ASSERTION",false,false,"WITHHELD",accounting(view,cancelled));}
    private Accounting accounting(View view,BooleanSupplier cancelled)throws IOException{
        var cached=accountingCache;var key=view.identity();if(cached!=null&&cached.view().equals(key))return cached.accounting();var concrete=new TreeMap<String,Long>();var obligations=new TreeMap<String,Long>();for(var state:ArchitectureBridges.State.values()){concrete.put(state.name(),0L);obligations.put(state.name(),0L);}long bindings=0,evidence=0;
        try(var required=tables.open(view.input().requirements(),cancelled);var evaluated=tables.open(view.result().relations(),cancelled)){SemanticTables.Row need;while((need=required.next())!=null){var actual=evaluated.next();if(actual==null||!need.key().equals(actual.key())||!need.identity().value().equals(actual.fields().get("requirement")))throw new IOException("BRIDGE_QUERY_ACCOUNTING_BINDING");boolean endpoint=ArchitectureBridges.requirement(need.fields()).endpoint().isPresent();String state=GraphJson.string(actual.fields().get("state"));var selected=endpoint?concrete:obligations;if(!selected.containsKey(state))throw new IOException("BRIDGE_QUERY_ACCOUNTING_STATE");selected.put(state,Math.addExact(selected.get(state),1));if(endpoint)bindings++;else evidence++;}if(evaluated.next()!=null)throw new IOException("BRIDGE_QUERY_ACCOUNTING_EXTRA");}
        for(String state:concrete.keySet())if(Math.addExact(concrete.get(state),obligations.get(state))!=view.result().counts().getOrDefault(state,-1L))throw new IOException("BRIDGE_QUERY_ACCOUNTING_COUNTS");var contexts=new TreeMap<String,Long>();try(var rows=tables.open(view.input().partitions(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null)contexts.merge(ArchitectureBridges.partition(row.fields()).state(),1L,Math::addExact);}
        var result=new Accounting(Long.toString(bindings),Long.toString(evidence),decimals(concrete),decimals(obligations),decimals(contexts));accountingCache=new AccountingCache(key,result);return result;
    }
    private static Map<String,String> decimals(Map<String,Long> values){var result=new TreeMap<String,String>();values.forEach((key,value)->result.put(key,Long.toString(value)));return result;}
    public static Cursor cursor(Object value){var m=GraphJson.object(value);var p=GraphJson.object(m.get("position"));if(!m.keySet().equals(Set.of("request","position"))||!p.keySet().equals(Set.of("table","rows","previousKey")))throw new IllegalArgumentException("BRIDGE_QUERY_CURSOR_FIELDS");return new Cursor(new ContentDigest(GraphJson.string(m.get("request"))),new SemanticQueryService.Position(new ContentDigest(GraphJson.string(p.get("table"))),GraphJson.string(p.get("rows")),Optional.ofNullable(p.get("previousKey")).map(GraphJson::string)));}
}
