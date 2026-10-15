package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedJsonTest {
    private record Envelope(String text,Optional<ContentDigest> identity,Set<Long> numbers,BigDecimal decimal) {}
    @Test void boundedWriterRetainsExactM1BytesAndUtf8Escaping() {
        var value=new Envelope("Tiếng Việt 🔎\n\u0001",Optional.of(ContentDigest.sha256Utf8("proof")),Set.of(12L,3L),new BigDecimal("1.2500"));
        assertArrayEquals(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8),BoundedJson.encode(value,4096));
        assertThrows(BoundedJson.TooLarge.class,() -> BoundedJson.encode("\u0001".repeat(200),1024));
        assertThrows(BoundedJson.TooLarge.class,() -> BoundedJson.encode("界".repeat(400),1024));
        assertThrows(BoundedJson.TooLarge.class,() -> BoundedJson.encode(new BigDecimal("1e100000000"),1024));
        assertThrows(BoundedJson.TooLarge.class,() -> BoundedJson.encode(BigInteger.ONE.shiftLeft(10000),1024));
    }
}
