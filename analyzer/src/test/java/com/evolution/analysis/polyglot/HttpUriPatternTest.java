package com.evolution.analysis.polyglot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.polyglot.HttpUriPattern.Match.*;

class HttpUriPatternTest {
    @Test void literalAndSingleSegmentParametersRetainPathBoundaries() {
        assertEquals(MATCH,HttpUriPattern.match("/pets/42?view=full#top","/pets/{id}"));
        assertEquals(MATCH,HttpUriPattern.match("https://example.test/cart/7","/cart/:id"));
        assertEquals(MATCH,HttpUriPattern.match("/cart","/cart"));
        for(String path:new String[]{"/api/pets/42","/Pets/42","/pets/42/","/pets/42/items","/pets/"})
            assertEquals(NO_MATCH,HttpUriPattern.match(path,"/pets/{id}"),path);
    }
    @Test void dynamicPatternsUnsafeEncodingAndUnprovedRewritesCannotInventMatches() {
        for(String path:new String[]{"/pets/%2f","/pets/%2e%2e","/pets/../42","//other/pets","/pets/{id}","https://user:pass@example.test/pets","/pets/42;x=1"})
            assertEquals(UNSUPPORTED,HttpUriPattern.match(path,"/pets/{id}"),path);
        assertEquals(UNSUPPORTED,HttpUriPattern.match("/pets/42","/pets/{id:[0-9]+}"));
        assertEquals(UNSUPPORTED,HttpUriPattern.match("/pets/42","/pets/**"));
        assertEquals(NO_MATCH,HttpUriPattern.match("/api/pets","/pets"));
    }
}
