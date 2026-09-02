package com.evolution.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class Java21BuildTest {

    @Test
    void compilesAndRunsAnalyzerTestsWithJava21() {
        assertEquals("analyzer", List.of("analyzer").getFirst());
        assertEquals(21, Runtime.version().feature());
    }
}
