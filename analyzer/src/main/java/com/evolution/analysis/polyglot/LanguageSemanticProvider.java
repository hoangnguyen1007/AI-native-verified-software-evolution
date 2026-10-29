package com.evolution.analysis.polyglot;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Execution-capable implementations belong to isolated workers; this port carries data only. */
public interface LanguageSemanticProvider {
    SemanticTables.Table analyze(LanguageSemanticRequest request,BooleanSupplier cancelled)throws IOException;
}
