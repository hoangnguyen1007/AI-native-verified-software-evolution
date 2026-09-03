package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.*;

public final class JavaParserFrontend implements SemanticFrontend {
    @Override public FrontendResult analyze(FrontendRequest request) { return new Extraction(request).run().validateFor(request); }
}
