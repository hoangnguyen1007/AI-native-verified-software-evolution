package com.evolution.analysis.execution.polyglot;

import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Precommitted direct architectural relation labels, distinct from category presence. */
final class NativeRelationControls {
    static void verify(String name,Object raw,SemanticTables.Table census,SemanticTables.Table output,SemanticTables tables,Map<String,String> sources)throws IOException{
        var facts=new ArrayList<Map<String,Object>>();var symbols=new HashMap<String,Map<String,Object>>();
        try(var expected=tables.open(census,()->false);var observed=tables.open(output,()->false)){
            SemanticTables.Row question;while((question=expected.next())!=null){var row=observed.next();assertNotNull(row);assertEquals(question.key(),row.key());var fields=new TreeMap<>(row.fields());fields.put("category",question.fields().get("category"));facts.add(fields);for(var value:GraphJson.array(fields.get("symbols"))){var symbol=GraphJson.object(value);symbols.put(GraphJson.string(symbol.get("id")),symbol);}}
            assertNull(observed.next());
        }
        var matched=new HashSet<Map<String,Object>>();for(var value:GraphJson.array(raw)){
            var label=GraphJson.object(value);var candidates=facts.stream().filter(f->Objects.equals(label.get("category"),f.get("category"))&&Objects.equals(label.get("role"),f.get("role"))&&Objects.equals(label.get("written"),written(f.get("span"),sources))&&(!label.containsKey("occurrenceStart")||Objects.equals(label.get("occurrenceStart"),GraphJson.object(f.get("span")).get("start")))).toList();assertEquals(1,candidates.size(),name+": independently located relation "+label);var fact=candidates.getFirst();assertTrue(matched.add(fact),name+": duplicated expectation");
            assertEquals(label.get("status"),fact.get("status"),name);assertEquals(label.get("relation"),fact.get("relation"),name);
            var targets=GraphJson.array(fact.get("symbols")).stream().map(GraphJson::object).toList();assertEquals(GraphJson.array(label.get("targetNames")),targets.stream().map(s->s.get("name")).toList(),name);assertEquals(GraphJson.array(fact.get("targets")),targets.stream().map(s->s.get("id")).toList(),name);
            if(label.containsKey("ownerName")){var owner=symbols.get(fact.get("owner"));assertNotNull(owner,name+": owner must be a captured native declaration");assertEquals(label.get("ownerName"),owner.get("name"),name);}
            if(label.containsKey("targetDeclaration")){assertEquals(1,targets.size());assertEquals(label.get("targetDeclaration"),written(targets.getFirst().get("span"),sources));assertEquals("FULL_NATIVE_DECLARATION",targets.getFirst().get("locationPrecision"));}
            if(label.containsKey("callerName")){var caller=symbols.get(fact.get("caller"));assertNotNull(caller);assertEquals(label.get("callerName"),caller.get("name"));if(label.containsKey("callerDeclaration")){assertEquals(label.get("callerDeclaration"),written(caller.get("span"),sources));var outer=GraphJson.object(caller.get("span"));var inner=GraphJson.object(fact.get("span"));assertEquals(outer.get("path"),inner.get("path"));assertTrue(((BigDecimal)outer.get("start")).compareTo((BigDecimal)inner.get("start"))<=0&&((BigDecimal)inner.get("end")).compareTo((BigDecimal)outer.get("end"))<=0);}}
            if(label.containsKey("generationMechanism")){assertEquals(1,targets.size());var symbol=targets.getFirst();assertEquals("GENERATED",symbol.get("origin"));assertNull(symbol.get("span"));assertEquals("PROMOTION_PARAMETER_ORIGIN",symbol.get("locationPrecision"));var lineage=GraphJson.object(symbol.get("generationLineage"));assertEquals(label.get("generationMechanism"),lineage.get("mechanism"));assertEquals(label.get("written"),written(lineage.get("sourceSpan"),sources));}
        }
    }
    private static String written(Object raw,Map<String,String> sources){if(raw==null)return null;var span=GraphJson.object(raw);return sources.get(GraphJson.string(span.get("path"))).substring(((BigDecimal)span.get("start")).intValueExact(),((BigDecimal)span.get("end")).intValueExact());}
}
