package com.evolution.benchmark.frontend;

/** Shared summary so configurations retain the same attempted-fact denominator. */
final class RepositoryEvaluation {
    record Summary(int attempted, int resolved, int unresolved, int ambiguous, int unsupported, int errors) { }

    private RepositoryEvaluation() { }

    static Summary summarize(FrontendResult result) {
        int resolved = 0, unresolved = 0, ambiguous = 0, unsupported = 0, errors = 0;
        for (Observation observation : result.observations()) {
            switch (observation.state()) {
                case RESOLVED -> resolved++;
                case UNRESOLVED -> unresolved++;
                case AMBIGUOUS -> ambiguous++;
                case UNSUPPORTED -> unsupported++;
                case ERROR -> errors++;
            }
        }
        return new Summary(result.observations().size(), resolved, unresolved, ambiguous, unsupported, errors);
    }
}
