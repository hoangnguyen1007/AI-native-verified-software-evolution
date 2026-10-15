package com.evolution.analysis.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.net.URI;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class CanonicalSerializationTest {
    @Test void structuralPreflightMatchesTheDecoderDenominator(){
        record Nested(java.util.Optional<String> empty,Object value){}
        Object value=Map.of("array",Arrays.asList(new Nested(java.util.Optional.empty(),List.of(1,true)),"[{,]}",null),"id",ContractFixtures.sourceA().identity());
        String encoded=CanonicalJson.write(value);
        for(int depth=1;depth<8;depth++)for(int values=1;values<24;values++){
            boolean readable;try{com.evolution.analysis.graph.GraphJson.read(encoded,new com.evolution.analysis.graph.GraphJson.Limits(100_000,depth,values));readable=true;}catch(com.evolution.analysis.graph.GraphJson.LimitExceededException limit){readable=false;}
            assertEquals(readable,CanonicalJson.fitsStructure(value,depth,values),depth+" / "+values);
        }
    }
    @Test void streamingDigestPreservesExactCanonicalUtf8PreimagesIncludingSurrogates(){
        var manifest=ContractFixtures.manifest(List.of(ContractFixtures.sourceA()),List.of(),Map.of("release","21"));
        var values=Arrays.asList(null,true,17,new java.math.BigDecimal("1.2300"),"Tiếng Việt 😀",Map.of("z",List.of(1,2),"a","\u001b\n"),java.util.Set.of("b","a"),manifest);
        for(Object value:values)assertEquals(com.evolution.analysis.contract.common.ContentDigest.sha256Utf8(CanonicalJson.write(value)),CanonicalJson.sha256(value));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.sha256(0.1d));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.sha256("\ud800"));
        assertThrows(IllegalArgumentException.class,()->CanonicalJson.sha256("\udc00"));
    }

    @Test
    void canonicalJsonHasGoldenLexicographicKeysAndEscaping() {
        assertEquals(
                "{\"a\":[true,null,\"line\\nfeed\"],\"z\":7}",
                CanonicalJson.write(
                        Map.of("z", 7, "a", Arrays.asList(true, null, "line\nfeed"))));
    }

    @Test
    void canonicalJsonRejectsBinaryFloatingPointAndNonStringMapKeys() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(0.1d));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of(1, "value")));
    }

    @Test
    void canonicalJsonUsesTheAsciiFormOfExplicitRepositoryUris() {
        assertEquals("\"https://example.test/maven%20repo/\"",
                CanonicalJson.write(URI.create("https://example.test/maven%20repo/")));
    }

    @Test
    void manifestGoldenSerializationIsIndependentOfLocaleAndTimezone() {
        AnalysisManifest manifest = ContractFixtures.manifest(
                List.of(ContractFixtures.sourceB(), ContractFixtures.sourceA()),
                List.of(
                        ContractFixtures.dependency("z:dep:1", "z"),
                        ContractFixtures.dependency("a:dep:1", "a")),
                Map.of("release", "21", "language", "java"));
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Pacific/Honolulu")));
            String first = CanonicalJson.write(manifest);
            Locale.setDefault(Locale.JAPAN);
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Tokyo")));
            String second = CanonicalJson.write(manifest);

            assertEquals(first, second);
            assertEquals(
                    "analysis:sha256:1920a08b08b2b362d5666f95e8847d2eb882bd447e4662b8ff28e3cda55143a5",
                    manifest.identity().value());
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }
}
