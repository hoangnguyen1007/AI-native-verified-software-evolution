package com.evolution.analysis.acquisition.streaming;

import java.io.*;
import java.util.function.BooleanSupplier;

public interface RepositoryContentStore {
    ContentHandle put(InputStream input, BooleanSupplier cancelled) throws IOException;
    InputStream open(ContentHandle content) throws IOException;
    InputStream range(ContentHandle content, long offset, long length) throws IOException;
    boolean verify(ContentHandle content) throws IOException;
}
