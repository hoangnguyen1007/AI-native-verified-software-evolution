package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class JavaParserFrontend implements ConcurrentSemanticFrontend {
    private static final java.util.concurrent.locks.ReentrantReadWriteLock RESOLVERS =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private static final AtomicInteger LAST_REEXTRACTED_UNITS = new AtomicInteger(-1);
    private static final AtomicReference<ModuleIncrementalCache.IncrementalStats> LAST_INCREMENTAL_STATS =
            new AtomicReference<>();
    public static final com.evolution.analysis.contract.common.VersionedIdentifier PROVIDER =
            new com.evolution.analysis.contract.common.VersionedIdentifier(
                    "frontend.javaparser", "3.28.2-m9.2-evidenced-recovery-v13");

    /** Returns how many primary units were body-extracted on the most recent analyze() call (diagnostic/test hook). */
    public static int lastReextractedUnitCount() {
        return LAST_REEXTRACTED_UNITS.get();
    }

    /** Returns detailed incremental statistics from the most recent analyze() call. */
    public static ModuleIncrementalCache.IncrementalStats lastIncrementalStats() {
        return LAST_INCREMENTAL_STATS.get();
    }

    /** Opens a scoped multi-module artifact SHA-256 verification session. */
    public static ResolutionEnvironment.VerificationSession openVerificationSession() {
        return ResolutionEnvironment.openVerificationSession();
    }

    /** Configures the disk persistence directory for {@link ModuleIncrementalCache}. */
    public static void configureModuleCacheDirectory(Path directory) {
        ModuleIncrementalCache.configureDiskDirectory(directory);
    }

    @Override
    public long estimatedWorkingBytes(FrontendRequest request) {
        var paths = new java.util.HashSet<java.nio.file.Path>();
        request.platform().artifacts().forEach(a -> paths.add(a.path().toAbsolutePath().normalize()));
        request.dependencies().forEach(b -> paths.add(b.path().toAbsolutePath().normalize()));
        long symbols = 0;
        try {
            for (var path : paths) {
                long bytes;
                try {
                    bytes = java.nio.file.Files.size(path);
                } catch (java.io.IOException | SecurityException unavailable) {
                    bytes = 64L << 20;
                }
                symbols = Math.addExact(symbols, bytes);
            }
            long sharedSymbolOverhead = Math.min(Math.multiplyExact(symbols, 2), 32L << 20);
            return Math.addExact(ConcurrentSemanticFrontend.super.estimatedWorkingBytes(request), sharedSymbolOverhead);
        } catch (ArithmeticException enormous) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public FrontendResult analyze(FrontendRequest request) {
        return analyze(request, FrontendProgress.current());
    }

    public FrontendResult analyze(FrontendRequest request, FrontendProgress progress) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        try {
            RESOLVERS.readLock().lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        }
        FrontendProgress safeProgress = progress != null ? progress : FrontendProgress.NOOP;
        try {
            var extraction = new Extraction(request, true, safeProgress);
            var result = extraction.run().validateFor(request);
            LAST_REEXTRACTED_UNITS.set(extraction.reextractedUnitCount());
            LAST_INCREMENTAL_STATS.set(extraction.incrementalStats());
            return result;
        } finally {
            RESOLVERS.readLock().unlock();
            // JavaParser's process cache retains facades/solvers beyond analyze().
            // Never clear it under another active resolution, and never retain a
            // completed serial request merely because no scheduler owns the call.
            if (RESOLVERS.writeLock().tryLock()) {
                try {
                    clearResolvers();
                } finally {
                    RESOLVERS.writeLock().unlock();
                }
            }
        }
    }

    @Override
    public void releaseCompletedContexts() {
        RESOLVERS.writeLock().lock();
        try {
            clearResolvers();
        } finally {
            RESOLVERS.writeLock().unlock();
        }
    }

    private static void clearResolvers() {
        synchronized (com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class) {
            com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();
        }
    }
}
