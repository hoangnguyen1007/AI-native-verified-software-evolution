package com.evolution.analysis.buildmodel;

/** Replaceable passive build adapter. Maven implementation types never cross this boundary. */
public interface BuildModelProvider {
    BuildModelResult build(BuildModelRequest request);
}
