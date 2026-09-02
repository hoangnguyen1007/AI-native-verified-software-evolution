package com.evolution.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class Java21BuildTest {

    @Test
    void compilesAndRunsBackendTestsWithJava21() {
        assertEquals("backend", List.of("backend").getFirst());
        assertEquals(21, Runtime.version().feature());
    }
}
