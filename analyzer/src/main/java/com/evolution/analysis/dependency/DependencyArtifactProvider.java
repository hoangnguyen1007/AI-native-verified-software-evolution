package com.evolution.analysis.dependency;

/** Replaceable boundary for bounded exact dependency artifact acquisition. */
public interface DependencyArtifactProvider {
    DependencyAcquisitionResult acquire(DependencyAcquisitionRequest request);
}
