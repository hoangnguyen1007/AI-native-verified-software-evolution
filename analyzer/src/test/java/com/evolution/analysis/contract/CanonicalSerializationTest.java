package com.evolution.analysis.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class CanonicalSerializationTest {

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
