package com.evolution.analysis.classpath;

/** Replaceable exact-classpath boundary; provider-specific machine locators stay in adapters. */
public interface ClasspathProvider {
    ExactClasspathResult resolve(ClasspathResolutionRequest request);
}
