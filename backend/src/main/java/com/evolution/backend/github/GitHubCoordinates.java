package com.evolution.backend.github;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser, normalizer, and allowlist validator for GitHub repository coordinates,
 * Git refs, branch names, and Pull Request numbers.
 */
public final class GitHubCoordinates {
    public static final String DEFAULT_HOST = "github.com";
    public static final String DEFAULT_API_HOST = "api.github.com";

    private static final Pattern OWNER_PATTERN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$");
    private static final Pattern REPO_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Pattern REF_PATTERN = Pattern.compile("^[A-Za-z0-9._/@+-]{1,256}$");
    private static final Pattern BRANCH_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$");
    private static final Pattern SCP_SSH_PATTERN = Pattern.compile(
            "^git@([A-Za-z0-9.-]+):([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");
    private static final Pattern SHORTHAND_PATTERN = Pattern.compile(
            "^(?:github:)?([A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?)/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?$");
    private static final Set<String> RESERVED_PROTECTED_BRANCHES = Set.of("main", "master", "HEAD");

    public record Coordinates(
            String host,
            String owner,
            String repo,
            Optional<String> ref,
            Optional<Integer> pullNumber) {
        public Coordinates {
            host = normalizeHost(Objects.requireNonNull(host, "host"));
            owner = validateOwner(owner);
            repo = validateRepo(repo);
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(pullNumber, "pullNumber");
            ref.ifPresent(GitHubCoordinates::validateRef);
            pullNumber.ifPresent(GitHubCoordinates::validatePullNumber);
        }

        public String fullName() {
            return owner + "/" + repo;
        }

        public String canonicalCloneUrl() {
            return "https://" + host + "/" + owner + "/" + repo;
        }

        public String htmlUrl() {
            return "https://" + host + "/" + owner + "/" + repo;
        }

        public URI apiBaseUri() {
            return DEFAULT_HOST.equals(host)
                    ? URI.create("https://" + DEFAULT_API_HOST)
                    : URI.create("https://" + host + "/api/v3");
        }

        public String apiHost() {
            return DEFAULT_HOST.equals(host) ? DEFAULT_API_HOST : host;
        }
    }

    private GitHubCoordinates() {
    }

    public static Optional<String> configuredEnterpriseHost() {
        String configured = System.getProperty("evo.github.enterpriseHost");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("EVO_GITHUB_ENTERPRISE_HOST");
        }
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        String host = configured.strip().toLowerCase(Locale.ROOT);
        if (!isValidHostSyntax(host)) {
            return Optional.empty();
        }
        return Optional.of(host);
    }

    public static boolean isAllowedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.strip().toLowerCase(Locale.ROOT);
        if (DEFAULT_HOST.equals(lower) || DEFAULT_API_HOST.equals(lower)) {
            return true;
        }
        return configuredEnterpriseHost().filter(lower::equals).isPresent();
    }

    public static Set<String> allowedApiHosts() {
        var enterprise = configuredEnterpriseHost();
        return enterprise.isPresent()
                ? Set.of(DEFAULT_API_HOST, enterprise.orElseThrow())
                : Set.of(DEFAULT_API_HOST);
    }

    public static Set<String> allowedWebHosts() {
        var enterprise = configuredEnterpriseHost();
        return enterprise.isPresent()
                ? Set.of(DEFAULT_HOST, enterprise.orElseThrow())
                : Set.of(DEFAULT_HOST);
    }

    public static Optional<Coordinates> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException notGitHub) {
            return Optional.empty();
        }
    }

    /**
     * Parses any supported GitHub repository reference:
     * <ul>
     *   <li>{@code https://github.com/owner/repo[.git][/tree/ref|/pull/123]}</li>
     *   <li>{@code git@github.com:owner/repo.git} or {@code ssh://git@github.com/owner/repo.git}</li>
     *   <li>{@code github:owner/repo} or {@code owner/repo}</li>
     * </ul>
     */
    public static Coordinates parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("GitHub repository coordinate is required");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > 1024
                || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid GitHub repository coordinate");
        }

        Matcher scp = SCP_SSH_PATTERN.matcher(trimmed);
        if (scp.matches()) {
            String host = normalizeHost(scp.group(1));
            requireAllowedWebHost(host);
            return new Coordinates(host, scp.group(2), stripDotGit(scp.group(3)), Optional.empty(), Optional.empty());
        }

        if (trimmed.contains("://")) {
            URI uri;
            try {
                uri = URI.create(trimmed);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid GitHub URL");
            }
            String scheme = uri.getScheme();
            if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"ssh".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Unsupported GitHub URL scheme");
            }
            if ("https".equalsIgnoreCase(scheme) && uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("Credentials in GitHub URL are forbidden");
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Query strings and fragments are not permitted in repository URLs");
            }
            if (uri.getPort() != -1 && uri.getPort() != ("https".equalsIgnoreCase(scheme) ? 443 : 22)) {
                throw new IllegalArgumentException("Non-standard port denied for GitHub URL");
            }
            String host = normalizeHost(uri.getHost());
            requireAllowedWebHost(host);
            String path = uri.getRawPath();
            if (path == null || path.isBlank() || path.contains("..") || path.contains("//")) {
                throw new IllegalArgumentException("Invalid GitHub repository path");
            }
            String cleaned = path.replaceFirst("^/+", "").replaceFirst("/+$", "");
            String[] segs = cleaned.split("/", -1);
            if (segs.length < 2) {
                throw new IllegalArgumentException("GitHub URL must include owner and repository");
            }
            String owner = validateOwner(segs[0]);
            String repo = validateRepo(stripDotGit(segs[1]));
            Optional<String> ref = Optional.empty();
            Optional<Integer> pr = Optional.empty();
            if (segs.length >= 4 && "tree".equals(segs[2])) {
                StringBuilder refBuf = new StringBuilder();
                for (int i = 3; i < segs.length; i++) {
                    if (i > 3) refBuf.append('/');
                    refBuf.append(segs[i]);
                }
                ref = Optional.of(validateRef(refBuf.toString()));
            } else if (segs.length >= 4 && ("pull".equals(segs[2]) || "pulls".equals(segs[2]))) {
                int num;
                try {
                    num = Integer.parseInt(segs[3]);
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Invalid GitHub pull request number");
                }
                pr = Optional.of(validatePullNumber(num));
            } else if (segs.length > 2) {
                throw new IllegalArgumentException("Unsupported GitHub URL path segments");
            }
            return new Coordinates(host, owner, repo, ref, pr);
        }

        Matcher shortMatch = SHORTHAND_PATTERN.matcher(trimmed);
        if (shortMatch.matches()) {
            return new Coordinates(DEFAULT_HOST, shortMatch.group(1), stripDotGit(shortMatch.group(2)),
                    Optional.empty(), Optional.empty());
        }
        throw new IllegalArgumentException("Not a valid GitHub repository coordinate");
    }

    public static String validateOwner(String owner) {
        if (owner == null || !OWNER_PATTERN.matcher(owner).matches()) {
            throw new IllegalArgumentException("Invalid GitHub owner");
        }
        return owner;
    }

    public static String validateRepo(String repo) {
        if (repo == null || repo.equals(".") || repo.equals("..") || !REPO_PATTERN.matcher(repo).matches()) {
            throw new IllegalArgumentException("Invalid GitHub repository name");
        }
        return repo;
    }

    public static String validateRef(String ref) {
        if (ref == null) {
            throw new IllegalArgumentException("Git ref is required");
        }
        String trimmed = ref.strip();
        if (trimmed.isEmpty()
                || trimmed.startsWith("-")
                || trimmed.contains("..")
                || trimmed.contains("@{")
                || trimmed.endsWith(".lock")
                || trimmed.endsWith("/")
                || !REF_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid Git ref");
        }
        return trimmed;
    }

    public static String validateBranchName(String branch) {
        if (branch == null) {
            throw new IllegalArgumentException("Branch name is required");
        }
        String trimmed = branch.strip();
        if (trimmed.isEmpty()
                || trimmed.startsWith("-")
                || trimmed.contains("..")
                || trimmed.contains("//")
                || trimmed.contains("@{")
                || trimmed.endsWith(".lock")
                || trimmed.endsWith("/")
                || trimmed.endsWith(".")
                || !BRANCH_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid Git branch name");
        }
        return trimmed;
    }

    public static int validatePullNumber(int pullNumber) {
        if (pullNumber < 1 || pullNumber > 100_000_000) {
            throw new IllegalArgumentException("Invalid GitHub pull request number");
        }
        return pullNumber;
    }

    public static boolean isProtectedDefaultBranch(String branch, String defaultBranch) {
        if (branch == null) {
            return true;
        }
        String trimmed = branch.strip();
        if (RESERVED_PROTECTED_BRANCHES.contains(trimmed)) {
            return true;
        }
        return defaultBranch != null && !defaultBranch.isBlank() && trimmed.equals(defaultBranch.strip());
    }

    private static String stripDotGit(String repo) {
        return repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("GitHub host is required");
        }
        String lower = host.strip().toLowerCase(Locale.ROOT);
        if (!isValidHostSyntax(lower)) {
            throw new IllegalArgumentException("Invalid GitHub host");
        }
        return lower;
    }

    private static boolean isValidHostSyntax(String lower) {
        if (lower.isEmpty() || lower.length() > 253) {
            return false;
        }
        if (lower.matches("[0-9.]+") || lower.contains(":") || lower.equals("localhost") || lower.endsWith(".localhost")) {
            return false;
        }
        return lower.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    }

    private static void requireAllowedWebHost(String host) {
        if (!allowedWebHosts().contains(host)) {
            throw new IllegalArgumentException("Host not permitted by GitHub host allowlist");
        }
    }
}
