package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.*;

public final class JavaParserFrontend implements SemanticFrontend {
    public static final com.evolution.analysis.contract.common.VersionedIdentifier PROVIDER =
            new com.evolution.analysis.contract.common.VersionedIdentifier("frontend.javaparser", "3.28.2-m8.4-lombok-candidates-v3");
    @Override public FrontendResult analyze(FrontendRequest request) { return new Extraction(request).run().validateFor(request); }
}
