package com.evolution.analysis.frontend;

/**
 * Operational progress observer for semantic frontends and ingestion pipelines.
 * Progress reporting is strictly operational and never enters canonical identities or outputs.
 */
public interface FrontendProgress {
    FrontendProgress NOOP = new FrontendProgress() {};

    /** Called when a module/source-set begins semantic analysis. */
    default void onModuleStarted(String moduleId, String sourceSet, int totalFiles) {}

    /** Called when an individual source file completes extraction. */
    default void onFileCompleted(String modulePath, String filePath, boolean cacheHit) {}

    /** Called when a batch of source files completes extraction. */
    default void onFilesCompleted(
            String modulePath, int deltaFiles, int completedFiles, int totalFiles, boolean cacheHit) {}

    /** Called when a module/source-set completes semantic analysis. */
    default void onModuleCompleted(
            String moduleId,
            String sourceSet,
            int filesCount,
            int typesCount,
            long durationMillis,
            boolean cacheHit) {}

    /**
     * Scoped thread-local binding allowing progress callbacks to cross the stable
     * {@link SemanticFrontend#analyze(FrontendRequest)} boundary without changing its signature.
     */
    static Scope bind(FrontendProgress progress) {
        return Scope.open(progress);
    }

    /** Returns the active thread-bound observer, or {@link #NOOP} when unbound. */
    static FrontendProgress current() {
        return Scope.current();
    }

    final class Scope implements AutoCloseable {
        private static final ThreadLocal<FrontendProgress> CURRENT = new ThreadLocal<>();
        private final FrontendProgress previous;
        private boolean closed;

        private Scope(FrontendProgress previous) {
            this.previous = previous;
        }

        static Scope open(FrontendProgress progress) {
            FrontendProgress prior = CURRENT.get();
            if (progress == null || progress == NOOP) {
                CURRENT.remove();
            } else {
                CURRENT.set(progress);
            }
            return new Scope(prior);
        }

        static FrontendProgress current() {
            FrontendProgress active = CURRENT.get();
            return active != null ? active : NOOP;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
