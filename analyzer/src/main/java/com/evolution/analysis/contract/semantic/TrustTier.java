package com.evolution.analysis.contract.semantic;

/**
 * Canonical epistemic trust taxonomy shared across analyzer contracts, REST/SSE APIs,
 * SQLite stores, MCP responses, and Workbench UI badges.
 *
 * <p>Strictly orthogonal to {@link DerivationKind}, Spring {@code Classification}
 * ({@code MUST|MAY|NEVER|UNKNOWN}), repair verification {@code TrustLabel}, and
 * behavioral differential {@code BehavioralVerdict}.
 */
public enum TrustTier {
    /** Compiler-typed resolution with exact classpath, bytecode, AST+symbol solver, or verified SCIP capture. */
    COMPILER,
    /** Bounded phase/order-aware Spring configuration-space & SAT truth-region facts. */
    SPRING_SAT,
    /** Passive syntax-only CST/AST extraction without compiler/typechecker binding. */
    SOURCE_SYNTAX,
    /** Quarantined deterministic non-code & heuristic cross-layer overlays (never enters canonical KnowledgeGraph). */
    ADVISORY_UNVERIFIED
}
