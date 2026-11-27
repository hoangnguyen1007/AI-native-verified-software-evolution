package com.evolution.backend.auth;

import com.evolution.backend.github.GitHubTokenBroker;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Request-scoped multi-user security context bound for the duration of an HTTP or CLI invocation.
 * Always resets in a {@code finally} block to prevent tenant leakage across pooled threads.
 */
public final class RequestContext {

    @FunctionalInterface
    public interface TokenResolver {
        Optional<GitHubTokenBroker.ResolvedToken> resolve(String host);
    }

    public record ActiveRequest(
            Optional<String> sessionId,
            AuthenticatedUser user,
            boolean cookieAuthenticated,
            boolean multiUserMode,
            TokenResolver tokenResolver) {
        public ActiveRequest {
            sessionId = sessionId == null ? Optional.empty() : sessionId;
            user = Objects.requireNonNull(user, "user");
            tokenResolver = tokenResolver == null ? host -> Optional.empty() : tokenResolver;
        }
    }

    private static final ThreadLocal<ActiveRequest> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> SERVER_ORIGIN = new ThreadLocal<>();

    private RequestContext() {
    }

    public static Optional<ActiveRequest> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String currentServerOrigin() {
        String origin = SERVER_ORIGIN.get();
        return origin == null ? "" : origin;
    }

    public static AuthenticatedUser currentUser() {
        ActiveRequest active = CURRENT.get();
        return active == null ? AuthenticatedUser.localOperator() : active.user();
    }

    public static String currentUserId() {
        return currentUser().userId();
    }

    public static boolean isMultiUserMode() {
        ActiveRequest active = CURRENT.get();
        return active != null && active.multiUserMode();
    }

    public static boolean isAuthenticatedSession() {
        ActiveRequest active = CURRENT.get();
        return active != null && active.user().isAuthenticated();
    }

    public static Optional<GitHubTokenBroker.ResolvedToken> resolveSessionToken(String host) {
        ActiveRequest active = CURRENT.get();
        if (active == null) {
            return Optional.empty();
        }
        return active.tokenResolver().resolve(host);
    }

    public static <T> T runWith(ActiveRequest context, Supplier<T> action) {
        return runWith(context, null, action);
    }

    public static <T> T runWith(ActiveRequest context, String serverOrigin, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        ActiveRequest previous = CURRENT.get();
        String previousOrigin = SERVER_ORIGIN.get();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        if (serverOrigin == null || serverOrigin.isBlank()) {
            SERVER_ORIGIN.remove();
        } else {
            SERVER_ORIGIN.set(serverOrigin.strip());
        }
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousOrigin == null) {
                SERVER_ORIGIN.remove();
            } else {
                SERVER_ORIGIN.set(previousOrigin);
            }
        }
    }

    public static void runWithVoid(ActiveRequest context, Runnable action) {
        runWith(context, () -> {
            action.run();
            return null;
        });
    }
}
