package com.evolution.analysis.polyglot;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceCoordinatesTest {
    @Test void pointsKeepTheirOwnPrecisionAcrossUnicodeAndEof(){
        var source=new SourceCoordinates("\uFEFFA\r\n😀B".getBytes(StandardCharsets.UTF_8),"UTF-8");var point=new SourceCoordinates.Point(4,2,1);assertEquals(point,source.point(4,SourceCoordinates.Encoding.UTF16));assertEquals(point,source.point(6,SourceCoordinates.Encoding.UTF8));assertEquals(point,source.point(4,SourceCoordinates.Encoding.UTF32));assertEquals(new SourceCoordinates.Point(7,2,4),source.point(11,SourceCoordinates.Encoding.UTF8));assertThrows(IllegalArgumentException.class,()->source.point(5,SourceCoordinates.Encoding.UTF16));assertThrows(IllegalArgumentException.class,()->source.point(7,SourceCoordinates.Encoding.UTF8));assertThrows(IllegalArgumentException.class,()->source.range(4,4,SourceCoordinates.Encoding.UTF16));
    }
    @Test void utf16BomIsPreservedAndContradictingDeclaredByteOrderIsRejected(){
        byte[] bytes="\uFEFFa();".getBytes(StandardCharsets.UTF_16LE);
        var source=new SourceCoordinates(bytes,"UTF-16");
        assertEquals("\uFEFFa();",source.text());
        assertEquals(new SourceCoordinates.Range(1,2,1,2,1,3),source.range(1,2,SourceCoordinates.Encoding.UTF16));
        assertThrows(IllegalArgumentException.class,()->new SourceCoordinates(bytes,"UTF-16BE"));
    }
    @Test void utf8Utf16AndUtf32LocationsReferToTheSameOriginalText(){
        var source=new SourceCoordinates("\uFEFFa😀\r\nb();".getBytes(StandardCharsets.UTF_8),"UTF-8");
        var utf16=source.range(7,8,SourceCoordinates.Encoding.UTF16);
        assertEquals(new SourceCoordinates.Range(7,8,2,2,2,3),utf16);
        assertEquals(utf16,source.range(6,7,SourceCoordinates.Encoding.UTF32));
        assertEquals(utf16,source.range(11,12,SourceCoordinates.Encoding.UTF8));
        assertEquals(new SourceCoordinates.Range(4,5,1,5,1,6),source.range(4,5,SourceCoordinates.Encoding.UTF16));
        assertThrows(IllegalArgumentException.class,()->source.range(3,4,SourceCoordinates.Encoding.UTF16));
        assertThrows(IllegalArgumentException.class,()->source.range(5,6,SourceCoordinates.Encoding.UTF8));
    }
    @Test void malformedOrZeroWidthRangesCannotBecomeFullSourceEvidence(){
        assertThrows(IllegalArgumentException.class,()->new SourceCoordinates(new byte[]{(byte)0xff},"UTF-8"));
        var source=new SourceCoordinates("x".getBytes(StandardCharsets.UTF_8),"UTF-8");
        assertThrows(IllegalArgumentException.class,()->source.range(0,0,SourceCoordinates.Encoding.UTF16));
        assertThrows(IllegalArgumentException.class,()->source.range(0,2,SourceCoordinates.Encoding.UTF16));
    }
    @Test void largeOriginalDocumentsRetainExactCoordinatesAcrossSparseBoundaries(){
        String line="a😀é\r\n";int count=100_000;
        var source=new SourceCoordinates(line.repeat(count).getBytes(StandardCharsets.UTF_8),"UTF-8");
        for(int i=0;i<count;i+=7){
            var expected=new SourceCoordinates.Range(i*6+1,i*6+3,i+1,2,i+1,4);
            assertEquals(expected,source.range(i*9L+1,i*9L+5,SourceCoordinates.Encoding.UTF8));
            assertEquals(expected,source.range(i*5L+1,i*5L+2,SourceCoordinates.Encoding.UTF32));
            assertEquals(expected,source.range(i*6L+1,i*6L+3,SourceCoordinates.Encoding.UTF16));
            assertThrows(IllegalArgumentException.class,()->source.range(Long.MAX_VALUE-1,Long.MAX_VALUE,SourceCoordinates.Encoding.UTF8));
        }
    }
}
