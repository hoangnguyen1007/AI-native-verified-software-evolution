package com.evolution.analysis.frontend;

/** Opt-in contract: analyze invocations own independent parser/resolver state and
 * may overlap. Non-participating providers retain sequential execution. */
public interface ConcurrentSemanticFrontend extends SemanticFrontend {
    /** Conservative operational estimate, not a hard RSS certificate or semantic input. */
    default long estimatedWorkingBytes(FrontendRequest request) {
        long characters=request.sources().stream().mapToLong(s->s.text().length()).sum()
                +request.reactorSources().stream().flatMap(r->r.sources().stream()).mapToLong(s->s.text().length()).sum();
        return (32L<<20)+Math.min((Long.MAX_VALUE-(32L<<20))/80,characters)*80;
    }
    /** Called only after this scheduler has drained its active tasks. Adapter-owned
     * parser caches may be released; neutral results must remain usable. */
    default void releaseCompletedContexts() {}
}
