package com.evolution.analysis.acquisition.streaming;

import java.io.*;
import java.util.*;

/** Bounded pages; callers cannot accidentally request a whole repository. */
public interface InventoryReader {
    record Page(List<InventoryEntry> entries, Optional<String> continuation) {
        public Page { entries = List.copyOf(entries); Objects.requireNonNull(continuation); }
    }
    Page page(String revision, int size, Optional<String> cursor) throws IOException;
    Optional<InventoryManifest> manifest(String revision) throws IOException;
}
