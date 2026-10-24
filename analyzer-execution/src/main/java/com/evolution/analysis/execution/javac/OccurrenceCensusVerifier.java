package com.evolution.analysis.execution.javac;

import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.IOException;
import java.util.*;

/** Compares an independently produced canonical occurrence inventory with a native capture.
 * Category accounting is separate from target/origin validation and authenticated promotion. */
public final class OccurrenceCensusVerifier {
    public record Result(boolean censusClosed,Map<String,Object> report){public Result{report=Collections.unmodifiableMap(new TreeMap<>(report));}}
    public Result verify(CapturedJavacReader.Capture capture,FrontendResult independent,
            Map<SourceDocumentIdentity,String> sourceUris,List<SourceInput> independentSources)throws IOException {
        if(!capture.current())throw invalid("CAPTURE_CHANGED");
        if(independent.frontend().id().equals("isolated.javac-index"))throw invalid("SELF_CERTIFICATION");
        if(!sourceUris.keySet().equals(new HashSet<>(independent.sources().stream().map(SourceOutcome::document).toList()))
                ||sourceUris.size()!=new HashSet<>(sourceUris.values()).size()||!new HashSet<>(sourceUris.values()).equals(capture.context().sources().keySet()))throw invalid("SOURCE_DENOMINATOR");
        if(independentSources.size()!=sourceUris.size()||!new HashSet<>(independentSources.stream().map(s->s.document().identity()).toList()).equals(sourceUris.keySet()))throw invalid("SOURCE_INPUT_DENOMINATOR");
        for(var source:independentSources){var expected=capture.context().sources().get(sourceUris.get(source.document().identity()));
            if(!source.document().contentDigest().equals(expected.digest())||source.bytes().length!=expected.size()
                    ||!com.evolution.analysis.contract.common.ContentDigest.sha256(source.bytes()).equals(expected.digest()))throw invalid("SOURCE_INPUT_CHANGED");}
        var expectedCategories=new TreeSet<>(CapturedJavacReader.CATEGORIES);
        if(!expectedCategories.equals(new HashSet<>(independent.coverage().stream().map(c->c.category().value()).toList())))throw invalid("CATEGORY_DENOMINATOR");
        var nativeSites=new TreeMap<String,List<String>>();var canonicalSites=new TreeMap<String,List<String>>();
        for(String category:expectedCategories){nativeSites.put(category,new ArrayList<>());canonicalSites.put(category,new ArrayList<>());}
        for(var row:capture.observations()){
            var location=new TreeMap<String,Object>();location.put("source",row.get("source"));
            if(row.get("span")!=null){var span=GraphJson.object(row.get("span"));for(String field:List.of("startLine","startColumn","endLine","endColumn"))location.put(field,span.get(field));}
            else location.put("span",null);
            nativeSites.get(GraphJson.string(row.get("category"))).add(CanonicalJson.write(location));
        }
        for(var row:independent.observations()){
            if(!canonicalSites.containsKey(row.category().value())||!sourceUris.containsKey(row.document()))throw invalid("FOREIGN_OCCURRENCE");
            var location=new TreeMap<String,Object>();location.put("source",sourceUris.get(row.document()));
            if(row.span().isPresent()){var span=row.span().orElseThrow();location.put("startLine",span.startLine());location.put("startColumn",span.startColumn());location.put("endLine",span.endLine());location.put("endColumn",span.endColumn());}
            else location.put("span",null);
            canonicalSites.get(row.category().value()).add(CanonicalJson.write(location));
        }
        boolean closed=capture.traversalComplete()&&capture.state().equals("PARTIAL")&&independent.state()==FrontendResult.State.COMPLETED
                &&independent.sources().stream().allMatch(s->s.state()==SourceOutcome.State.PROCESSED);
        var categories=new TreeMap<String,Object>();int unavailable=0;
        for(String category:expectedCategories){
            var nativeCoverage=GraphJson.object(capture.categories().get(category));var nativeRows=nativeSites.get(category);var canonicalRows=canonicalSites.get(category);
            var coverage=independent.coverage().stream().filter(c->c.category().value().equals(category)).findFirst().orElseThrow();
            if(coverage.attempted()!=canonicalRows.size())throw invalid("INDEPENDENT_COUNT");
            boolean available=capture.observedCategories().contains(category)&&nativeCoverage.get("observed")!=null;
            if(!available)unavailable++;
            var nativeSet=new TreeSet<>(nativeRows);var canonicalSet=new TreeSet<>(canonicalRows);var nativeOnly=new TreeSet<>(nativeSet);nativeOnly.removeAll(canonicalSet);
            var canonicalOnly=new TreeSet<>(canonicalSet);canonicalOnly.removeAll(nativeSet);
            boolean unique=nativeSet.size()==nativeRows.size()&&canonicalSet.size()==canonicalRows.size();
            boolean positioned=nativeRows.stream().noneMatch(s->s.contains("\"span\":null"))&&canonicalRows.stream().noneMatch(s->s.contains("\"span\":null"));
            boolean equal=available&&unique&&positioned&&nativeOnly.isEmpty()&&canonicalOnly.isEmpty()&&coverage.support()!=CategoryCoverage.Support.UNSUPPORTED;
            closed&=equal;
            var result=new TreeMap<String,Object>();result.put("nativeState",nativeCoverage.get("state"));result.put("nativeCount",nativeCoverage.get("observed"));
            result.put("independentCount",canonicalRows.size());result.put("matchedSites",nativeSet.stream().filter(canonicalSet::contains).count());
            result.put("nativeOnly",List.copyOf(nativeOnly));result.put("independentOnly",List.copyOf(canonicalOnly));
            result.put("nativeDuplicates",nativeRows.size()-nativeSet.size());result.put("independentDuplicates",canonicalRows.size()-canonicalSet.size());
            result.put("disposition",equal?"MATCHED_LOCATIONS":available?"LOCATION_DISAGREEMENT":"NATIVE_CATEGORY_UNAVAILABLE");categories.put(category,Collections.unmodifiableMap(result));
        }
        var report=new TreeMap<String,Object>();report.put("schema","java-occurrence-census-comparison:m8.4-v2");report.put("capture",capture.document());
        report.put("independentProvider",independent.frontend());report.put("independentResult",CanonicalJson.sha256(independent));report.put("sourceUris",sourceUris.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.of("document",e.getKey(),"uri",e.getValue())).toList());
        report.put("categories",Collections.unmodifiableMap(categories));report.put("unavailableNativeCategories",unavailable);report.put("censusClosed",closed);
        report.put("endpointComparison",NativeEndpointComparison.compare(capture,independent,sourceUris,independentSources));
        report.put("semanticAttestation",false);return new Result(closed,report);
    }
    private static IOException invalid(String code){return new IOException("OCCURRENCE_CENSUS_"+code);}
}
