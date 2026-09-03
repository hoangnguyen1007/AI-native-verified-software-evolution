package com.evolution.analysis.frontend;

/** Replaceable semantic boundary. Parser and resolver objects never cross this port. */
public interface SemanticFrontend {
    FrontendResult analyze(FrontendRequest request);
}
