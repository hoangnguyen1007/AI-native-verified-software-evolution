package com.evolution.analysis.acquisition.streaming;

import java.io.*;
import java.util.function.BooleanSupplier;

public interface RepositoryContentStore {
    @FunctionalInterface interface ContentWriter { void write(OutputStream output) throws IOException; }
    ContentHandle put(InputStream input, BooleanSupplier cancelled) throws IOException;
    /** Producer-side streaming. Stores can override to write directly to their atomic staging area.
     * The compatibility path spools on disk, never in a whole-content byte array. */
    default ContentHandle write(ContentWriter writer, BooleanSupplier cancelled) throws IOException {
        java.util.Objects.requireNonNull(writer); java.util.Objects.requireNonNull(cancelled);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        var pending = java.nio.file.Files.createTempFile("evo-content-", ".part");
        try {
            try (var output = java.nio.file.Files.newOutputStream(pending)) {
                writer.write(new FilterOutputStream(output) {
                    private void check() { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
                    @Override public void write(int value) throws IOException { check(); out.write(value); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException { check(); out.write(bytes, offset, length); }
                    @Override public void close() throws IOException { flush(); }
                });
            }
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            try (var input = java.nio.file.Files.newInputStream(pending)) { return put(input, cancelled); }
        } finally { java.nio.file.Files.deleteIfExists(pending); }
    }
    InputStream open(ContentHandle content) throws IOException;
    InputStream range(ContentHandle content, long offset, long length) throws IOException;
    boolean verify(ContentHandle content) throws IOException;
}
