package com.evolution.analysis.polyglot;

import java.nio.*;
import java.nio.charset.*;
import java.util.Arrays;

/** Full positions in the original decoded document; BOM and CRLF are never normalized away. */
public final class SourceCoordinates {
    public enum Encoding { UTF8, UTF16, UTF32 }
    public record Range(int start,int end,int startLine,int startColumn,int endLine,int endColumn){}
    public record Point(int offset,int line,int column){}
    private final String text;
    private static final int STRIDE=256;
    private final int[] characterCheckpoints,codePointCheckpoints,lineStarts;
    private final long[] byteCheckpoints;
    private final int codePoints;
    public SourceCoordinates(byte[] bytes,String charset){
        Charset selected=Charset.forName(charset);
        boolean little=bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254;
        boolean big=bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255;
        if(selected.equals(StandardCharsets.UTF_16))selected=little?StandardCharsets.UTF_16LE:StandardCharsets.UTF_16BE;
        if(little&&selected.equals(StandardCharsets.UTF_16BE)||big&&selected.equals(StandardCharsets.UTF_16LE))throw new IllegalArgumentException("SOURCE_BOM_CONFLICT");
        try{text=selected.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new IllegalArgumentException("SOURCE_DECODING_FAILED",invalid);}
        // A sparse index bounds each conversion by one checkpoint interval. Per-site
        // scans from the start of a document otherwise make large censuses quadratic.
        int lines=1;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isSurrogate(c)){if(!Character.isHighSurrogate(c)||i+1==text.length()||!Character.isLowSurrogate(text.charAt(++i)))throw new IllegalArgumentException("SOURCE_SURROGATE_INVALID");}
            else if(c=='\n'||c=='\r'&&(i+1==text.length()||text.charAt(i+1)!='\n'))lines++;
        }
        int capacity=text.length()/STRIDE+2,count=1,points=0,line=1;
        int[] characters=new int[capacity],pointIndex=new int[capacity];long[] bytesIndex=new long[capacity];long utf8=0;
        lineStarts=new int[lines];
        for(int i=0;i<text.length();){
            int cp=text.codePointAt(i);i+=Character.charCount(cp);points++;utf8+=utf8Width(cp);
            if(cp=='\n'||cp=='\r'&&(i==text.length()||text.charAt(i)!='\n'))lineStarts[line++]=i;
            if(i-characters[count-1]>=STRIDE){characters[count]=i;pointIndex[count]=points;bytesIndex[count++]=utf8;}
        }
        codePoints=points;characterCheckpoints=Arrays.copyOf(characters,count);codePointCheckpoints=Arrays.copyOf(pointIndex,count);byteCheckpoints=Arrays.copyOf(bytesIndex,count);
    }
    public String text(){return text;}
    public Point point(long value,Encoding encoding){int offset=offset(value,encoding);int[] position=position(offset);return new Point(offset,position[0],position[1]);}
    public Point at(int line,int column){
        if(line<1||line>lineStarts.length||column<1)throw new IllegalArgumentException("SOURCE_LINE_COLUMN_OUTSIDE_DOCUMENT");
        var point=point((long)lineStarts[line-1]+column-1,Encoding.UTF16);
        if(point.line()!=line||point.column()!=column)throw new IllegalArgumentException("SOURCE_LINE_COLUMN_OUTSIDE_DOCUMENT");return point;
    }
    public Range range(long start,long end,Encoding encoding){
        if(start<0||end<=start)throw new IllegalArgumentException("INVALID_SOURCE_RANGE");
        int a=offset(start,encoding),b=offset(end,encoding);int[] first=position(a),last=position(b);
        return new Range(a,b,first[0],first[1],last[0],last[1]);
    }
    private int offset(long wanted,Encoding encoding){
        if(wanted<0)throw new IllegalArgumentException("SOURCE_RANGE_SPLITS_CODEPOINT_OR_EXCEEDS_DOCUMENT");
        if(encoding==Encoding.UTF16){
            if(wanted>text.length()||wanted<text.length()&&Character.isLowSurrogate(text.charAt((int)wanted)))throw new IllegalArgumentException("SOURCE_RANGE_SPLITS_CODEPOINT_OR_EXCEEDS_DOCUMENT");
            return (int)wanted;
        }
        int checkpoint;
        if(encoding==Encoding.UTF32){
            if(wanted>codePoints)throw new IllegalArgumentException("SOURCE_RANGE_SPLITS_CODEPOINT_OR_EXCEEDS_DOCUMENT");
            checkpoint=Arrays.binarySearch(codePointCheckpoints,(int)wanted);
        }else checkpoint=Arrays.binarySearch(byteCheckpoints,wanted);
        if(checkpoint<0)checkpoint=-checkpoint-2;
        long current=encoding==Encoding.UTF32?codePointCheckpoints[checkpoint]:byteCheckpoints[checkpoint];int i=characterCheckpoints[checkpoint];
        while(current<wanted&&i<text.length()){
            int cp=text.codePointAt(i),chars=Character.charCount(cp);
            current+=encoding==Encoding.UTF32?1:utf8Width(cp);i+=chars;
        }
        if(current!=wanted)throw new IllegalArgumentException("SOURCE_RANGE_SPLITS_CODEPOINT_OR_EXCEEDS_DOCUMENT");
        return i;
    }
    private static int utf8Width(int cp){return cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;}
    private int[] position(int end){
        int line=Arrays.binarySearch(lineStarts,end);if(line<0)line=-line-2;
        return new int[]{line+1,end-lineStarts[line]+1};
    }
}
