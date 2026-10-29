package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact captured text segments; wrappers, escapes and unmapped regions stay explicit.
 * Framework extractors provide maps through this boundary. A point or line-only
 * mapping cannot manufacture a full source span. All offsets are original UTF-16.
 */
public record EmbeddedSourceMap(Document generated,Map<String,Document> originals,List<Segment> segments,String method) {
    public record Document(String path,ContentHandle content,String charset) {
        public Document{Objects.requireNonNull(content);Objects.requireNonNull(charset);if(path==null||path.isBlank())throw new IllegalArgumentException("EMBEDDED_DOCUMENT_PATH");}
    }
    public record Segment(int generatedStart,int generatedEnd,String original,int originalStart,int originalEnd) {
        public Segment{if(generatedStart<0||generatedEnd<=generatedStart||originalStart<0||originalEnd<=originalStart||generatedEnd-generatedStart!=originalEnd-originalStart)throw new IllegalArgumentException("EMBEDDED_EXACT_SEGMENT_RANGE");Objects.requireNonNull(original);}
    }
    public record Piece(Document original,SourceCoordinates.Range range,int generatedStart,int generatedEnd){}
    public record PointPiece(Document original,SourceCoordinates.Point point){}
    public record PointMapping(List<PointPiece> points,ContentDigest map,int generatedOffset){public PointMapping{points=List.copyOf(points);}}
    public enum Precision { FULL, PIECEWISE, UNMAPPED }
    public record Mapping(Precision precision,List<Piece> pieces,long mappedUnits,long requestedUnits,ContentDigest map) {
        public Mapping{pieces=List.copyOf(pieces);}
    }
    public EmbeddedSourceMap {
        Objects.requireNonNull(generated);originals=Collections.unmodifiableMap(new TreeMap<>(originals));segments=List.copyOf(segments);
        if(method==null||method.isBlank())throw new IllegalArgumentException("EMBEDDED_MAPPING_METHOD_REQUIRED");
        originals.forEach((path,document)->{if(!path.equals(document.path()))throw new IllegalArgumentException("EMBEDDED_ORIGINAL_MEMBERSHIP");});
        int previous=0;
        for(var segment:segments){if(segment.generatedStart()<previous||!originals.containsKey(segment.original()))throw new IllegalArgumentException("EMBEDDED_MAP_OVERLAP_OR_MEMBERSHIP");previous=segment.generatedEnd();}
    }
    public ContentDigest identity(){return CanonicalJson.sha256(List.of("embedded-source-map:m8.4-v1",generated,originals,segments,method));}
    /** Recheck all captured bytes and exact segment text before every use. No saved
     * validation boolean or target-written source map confers semantic authority. */
    public Mapping map(int start,int end,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException{
        return prepare(content,cancelled).map(start,end,cancelled);
    }
    /** A compilation-unit view, not a verification capability. Callers retaining
     * it within a batch recheck captured input handles before publishing. */
    public Prepared prepare(RepositoryContentStore content,BooleanSupplier cancelled)throws IOException{
        var generatedText=read(generated,content,cancelled);
        var sourceTexts=new HashMap<String,SourceCoordinates>();
        for(var entry:originals.entrySet())sourceTexts.put(entry.getKey(),read(entry.getValue(),content,cancelled));
        for(var segment:segments){check(cancelled);var original=sourceTexts.get(segment.original());generatedText.range(segment.generatedStart(),segment.generatedEnd(),SourceCoordinates.Encoding.UTF16);original.range(segment.originalStart(),segment.originalEnd(),SourceCoordinates.Encoding.UTF16);if(!generatedText.text().substring(segment.generatedStart(),segment.generatedEnd()).equals(original.text().substring(segment.originalStart(),segment.originalEnd())))throw new IOException("EMBEDDED_MAP_TEXT_MISMATCH");}
        return new Prepared(generatedText,Map.copyOf(sourceTexts));
    }
    public final class Prepared {
        private final SourceCoordinates generatedText;private final Map<String,SourceCoordinates> sourceTexts;private final int[] ends;
        private Prepared(SourceCoordinates generatedText,Map<String,SourceCoordinates> sourceTexts){this.generatedText=generatedText;this.sourceTexts=sourceTexts;this.ends=segments.stream().mapToInt(Segment::generatedEnd).toArray();}
        public PointMapping point(int offset,BooleanSupplier cancelled)throws IOException {
            check(cancelled);generatedText.point(offset,SourceCoordinates.Encoding.UTF16);var points=new ArrayList<PointPiece>();
            int first=Arrays.binarySearch(ends,offset);if(first<0)first=-first-1;
            for(int i=first;i<segments.size()&&segments.get(i).generatedStart()<=offset;i++){var segment=segments.get(i);check(cancelled);int originalOffset=segment.originalStart()+offset-segment.generatedStart();var piece=new PointPiece(originals.get(segment.original()),sourceTexts.get(segment.original()).point(originalOffset,SourceCoordinates.Encoding.UTF16));if(!points.contains(piece))points.add(piece);}
            return new PointMapping(points,identity(),offset);
        }
        public Mapping map(int start,int end,BooleanSupplier cancelled)throws IOException{
        check(cancelled);generatedText.range(start,end,SourceCoordinates.Encoding.UTF16);
        var pieces=new ArrayList<Piece>();long mapped=0;
        int first=Arrays.binarySearch(ends,start);first=first<0?-first-1:first+1;
        for(int i=first;i<segments.size()&&segments.get(i).generatedStart()<end;i++){
            var segment=segments.get(i);
            check(cancelled);var original=sourceTexts.get(segment.original());
            int a=Math.max(start,segment.generatedStart()),b=Math.min(end,segment.generatedEnd());
            if(a>=b)continue;
            int originalStart=segment.originalStart()+a-segment.generatedStart(),originalEnd=originalStart+b-a;
            var range=original.range(originalStart,originalEnd,SourceCoordinates.Encoding.UTF16);var document=originals.get(segment.original());
            if(!pieces.isEmpty()){
                var last=pieces.getLast();
                if(last.original().equals(document)&&last.generatedEnd()==a&&last.range().end()==originalStart){pieces.set(pieces.size()-1,new Piece(document,original.range(last.range().start(),originalEnd,SourceCoordinates.Encoding.UTF16),last.generatedStart(),b));mapped+=b-a;continue;}
            }
            pieces.add(new Piece(document,range,a,b));mapped+=b-a;
        }
        var precision=mapped==end-(long)start&&pieces.size()==1?Precision.FULL:pieces.isEmpty()?Precision.UNMAPPED:Precision.PIECEWISE;
        return new Mapping(precision,pieces,mapped,end-(long)start,identity());
        }
    }
    public static EmbeddedSourceMap from(Object value){
        var fields=GraphJson.object(value);if(!fields.keySet().equals(Set.of("generated","originals","segments","method")))throw new IllegalArgumentException("EMBEDDED_MAP_FIELDS");var originals=new TreeMap<String,Document>();GraphJson.object(fields.get("originals")).forEach((path,document)->originals.put(path,document(document)));var segments=new ArrayList<Segment>();
        for(Object raw:GraphJson.array(fields.get("segments"))){var segment=GraphJson.object(raw);if(!segment.keySet().equals(Set.of("generatedStart","generatedEnd","original","originalStart","originalEnd")))throw new IllegalArgumentException("EMBEDDED_SEGMENT_FIELDS");segments.add(new Segment(integer(segment.get("generatedStart")),integer(segment.get("generatedEnd")),GraphJson.string(segment.get("original")),integer(segment.get("originalStart")),integer(segment.get("originalEnd"))));}
        return new EmbeddedSourceMap(document(fields.get("generated")),originals,segments,GraphJson.string(fields.get("method")));
    }
    private static Document document(Object value){var fields=GraphJson.object(value);if(!fields.keySet().equals(Set.of("path","content","charset")))throw new IllegalArgumentException("EMBEDDED_DOCUMENT_FIELDS");return new Document(GraphJson.string(fields.get("path")),LanguageSemanticRequest.handle(fields.get("content")),GraphJson.string(fields.get("charset")));}
    private static int integer(Object value){return ((java.math.BigDecimal)value).intValueExact();}
    private static SourceCoordinates read(Document document,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException{
        check(cancelled);if(!content.verify(document.content()))throw new IOException("EMBEDDED_MAP_CONTENT_UNAVAILABLE");
        if(document.content().size()>Integer.MAX_VALUE)throw new IOException("EMBEDDED_DOCUMENT_REQUIRES_STREAMING_TEXT_PROVIDER");
        try(var input=content.open(document.content())){var source=new SourceCoordinates(input.readAllBytes(),document.charset());check(cancelled);return source;}
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("EMBEDDED_MAPPING_CANCELLED");}
}
