package com.evolution.benchmark.frontend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JavacOracleTest {

    @Test
    void compilerBindsTrimAfterGenericListGetToJavaLangString() throws URISyntaxException {
        Path sourceRoot = Path.of(getClass().getResource("/fixtures/project").toURI());

        var bindings = new JavacOracle().analyze(sourceRoot, java.util.List.of());

        assertTrue(bindings.stream().anyMatch(binding -> binding.invocationText().equals("values.get(0).trim()")
                && binding.targetIdentity().equals("java.lang.String.trim()")));
    }
}
