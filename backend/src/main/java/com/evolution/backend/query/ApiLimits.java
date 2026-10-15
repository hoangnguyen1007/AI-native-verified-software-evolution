package com.evolution.backend.query;

/** Transport safety ceilings, separate from canonical query budgets and product latency claims. */
public record ApiLimits(int requestBytes, int responseBytes, int jsonDepth, int jsonValues,
                        int concurrentRequests, long queryTimeoutMillis) {
    public ApiLimits {
        if (requestBytes < 256 || requestBytes > 1_048_576 || responseBytes < 1024 || responseBytes > 16_777_216
                || jsonDepth < 2 || jsonDepth > 64 || jsonValues < 10 || jsonValues > 100_000
                || concurrentRequests < 1 || concurrentRequests > 16 || queryTimeoutMillis < 1 || queryTimeoutMillis > 60_000)
            throw new IllegalArgumentException("Invalid HTTP safety limits");
    }
    public static ApiLimits defaults() { return new ApiLimits(65_536,2_097_152,32,10_000,4,5_000); }
}
