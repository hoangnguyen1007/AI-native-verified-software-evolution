package com.evolution.analysis.execution.javac;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure coordinate/guard tests. No target compiler or annotation processor runs on the host. */
class CapturedJavacIndexTest {
    @Test void coordinatesUseOriginalUtf16WithTabsCrLfAndSupplementaryCharacters(){
        String source="a\r\n\t\uD83D\uDE00 call()\n";
        var span=CapturedJavacIndex.span(source,7,13);
        assertEquals(2L,span.get("startLine"));assertEquals(5L,span.get("startColumn"));
        assertEquals(2L,span.get("endLine"));assertEquals(11L,span.get("endColumn"));
        assertEquals("call()",source.substring(7,13));
    }
    @Test void missingOrUnrepresentablePositionsRemainAbsent(){
        assertNull(CapturedJavacIndex.span("a\r\nb",-1,4));
        assertNull(CapturedJavacIndex.span("a\r\nb",0,20));
        assertNull(CapturedJavacIndex.span("a\r\nb",3,2));
        assertNull(CapturedJavacIndex.span("a\r\nb",0,2)); // Between CR and LF has no ordinary M1 column.
    }
    @Test void terminalTypeSpansPreserveRawUnicodeEscapesAndOriginalCoordinates(){
        String escaped="pkg.N"+"\\u"+"0061me";
        assertEquals(CapturedJavacIndex.span(escaped,4,escaped.length()),CapturedJavacIndex.terminalSpan(escaped,0,escaped.length(),"Name"));
        String source="//\r\n\tfoo.\uD801\uDC00Type";
        assertEquals(CapturedJavacIndex.span(source,9,source.length()),CapturedJavacIndex.terminalSpan(source,5,source.length(),"\uD801\uDC00Type"));
        String digit="pkg.N"+"\\uuu"+"0031";
        assertEquals(CapturedJavacIndex.span(digit,4,digit.length()),CapturedJavacIndex.terminalSpan(digit,0,digit.length(),"N1"));
        assertNull(CapturedJavacIndex.terminalSpan("pkg.Other",0,9,"Name"));
        assertNull(CapturedJavacIndex.terminalSpan("pkg.LongName",0,12,"Name"));
    }
    @Test void writtenFieldDeclarationSpanDoesNotBorrowModifiersTypeCommentsOrSemicolon(){
        String plain="private final String name;";
        assertEquals(CapturedJavacIndex.span(plain,21,25),CapturedJavacIndex.fieldSpan(plain,20,26,-1,-1,"name"));
        String initialized="long /* x */ x = 0L;";
        assertEquals(CapturedJavacIndex.span(initialized,13,19),CapturedJavacIndex.fieldSpan(initialized,4,20,17,19,"x"));
        String escaped="String /* n */ n"+"\\u"+"0061me;";
        assertEquals(CapturedJavacIndex.span(escaped,15,escaped.length()-1),CapturedJavacIndex.fieldSpan(escaped,6,escaped.length(),-1,-1,"name"));
        assertNull(CapturedJavacIndex.fieldSpan("int field[];",11,12,-1,-1,"field")); // Native post-name dimensions need a separate mapping.
        assertNull(CapturedJavacIndex.fieldSpan("int /*unterminated",3,18,-1,-1,"field"));
        assertNull(CapturedJavacIndex.fieldSpan(plain,20,26,-1,-1,"other"));
    }
    @Test void aHostOrAnUnboundGuestCannotAcquireTheNativeCompilerLane(){
        Path work=Path.of("/work/"+"a".repeat(32));
        assertFalse(CapturedJavacIndex.guestEnvironment("Windows 11","Virtual Machine","evobuild",work.resolve("home").toString(),work));
        assertFalse(CapturedJavacIndex.guestEnvironment("Linux","Virtual Machine","root",work.resolve("home").toString(),work));
        assertFalse(CapturedJavacIndex.guestEnvironment("Linux","Virtual Machine","evobuild","/home/evobuild",work));
        assertFalse(CapturedJavacIndex.guestEnvironment("Linux","Physical Machine","evobuild",work.resolve("home").toString(),work));
    }
    @Test void nativeJsonKeepsQuotesControlsAndUtf16TextWithoutEvaluation(){
        String text="quote\" backslash\\ tab\t newline\n supplementary \uD83D\uDE00";
        var encoded=CapturedJavacIndex.json(java.util.Map.of("source",text));
        var decoded=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(encoded,com.evolution.analysis.graph.GraphJson.Limits.defaults()));
        assertEquals(text,decoded.get("source"));
    }
    @Test void reusingAnOriginalTypeNodeOnACompilerInsertedParentDoesNotInventAWrittenOccurrence(){
        var original=new CapturedJavacIndex.OriginalEdges<Object>();var unit=new Object();var creation=new Object();var type=new Object();var anonymous=new Object();
        original.add(null,unit);original.add(unit,creation);original.add(creation,type);original.add(creation,anonymous);
        assertTrue(original.contains(creation,type));assertFalse(original.contains(anonymous,type));
        assertTrue(original.contains(null,unit));assertFalse(original.contains(null,creation));
    }
}
