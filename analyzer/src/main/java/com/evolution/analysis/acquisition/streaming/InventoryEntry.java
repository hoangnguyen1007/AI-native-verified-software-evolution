package com.evolution.analysis.acquisition.streaming;

import java.util.*;

/** Acquisition facts only. Classification does not certify decoding, ownership or semantics. */
public record InventoryEntry(LogicalPath path, Kind kind, String mode, Population population,
        Disposition disposition, Optional<ContentHandle> content, Optional<String> target,
        Optional<String> encoding, List<String> ownershipCandidates, Optional<String> problem) {
    public enum Kind { FILE, DIRECTORY, SYMBOLIC_LINK, GITLINK, SPECIAL }
    public enum Population { SOURCE, TEST, GENERATED, VENDORED, CACHE, BUILD, RESOURCE, DOCUMENTATION, UNKNOWN }
    public enum Disposition { ACQUIRED, EXCLUDED, PENDING, GAP }
    public InventoryEntry {
        Objects.requireNonNull(path); Objects.requireNonNull(kind); Objects.requireNonNull(mode); Objects.requireNonNull(population);
        Objects.requireNonNull(disposition); Objects.requireNonNull(content); Objects.requireNonNull(target); Objects.requireNonNull(encoding);
        ownershipCandidates = List.copyOf(ownershipCandidates); Objects.requireNonNull(problem);
        if (disposition == Disposition.ACQUIRED && kind != Kind.DIRECTORY && content.isEmpty()) throw new IllegalArgumentException("Acquired entry needs captured bytes");
        if (disposition == Disposition.GAP && problem.isEmpty()) throw new IllegalArgumentException("Gap needs reason");
        if (kind == Kind.GITLINK && disposition == Disposition.ACQUIRED && (target.isEmpty() || !target.orElseThrow().startsWith("closure:sha256:")))
            throw new IllegalArgumentException("Gitlink needs closure proof");
    }
    public static Population classify(String path) {
        String p = "/" + path.toLowerCase(Locale.ROOT), name = p.substring(p.lastIndexOf('/') + 1);
        int source=p.indexOf("/src/");String enclosing=source<0?p:p.substring(0,source+1);
        if (enclosing.contains("/node_modules/") || enclosing.contains("/vendor/")) return Population.VENDORED;
        if (enclosing.contains("/.gradle/") || enclosing.contains("/.cache/")) return Population.CACHE;
        if (enclosing.contains("/target/") || enclosing.contains("/build/") || enclosing.contains("/generated/")) return Population.GENERATED;
        if (p.contains("/src/test/") || p.contains("/src/integrationtest/") || p.startsWith("/tests/")) return Population.TEST;
        if (name.equals("pom.xml") || name.contains("lock") || name.equals("package.json") || name.startsWith("build.gradle") || name.startsWith("settings.gradle")) return Population.BUILD;
        if (name.matches(".*\\.(java|kt|scala|groovy|proto|py|rs|go|js|jsx|ts|tsx|c|cpp|h|cs|rb|php|swift|dart|sql)$")) return Population.SOURCE;
        if (name.matches(".*\\.(md|txt|rst|adoc)$")) return Population.DOCUMENTATION;
        if (p.contains("/resources/") || p.contains("/assets/")) return Population.RESOURCE;
        return Population.UNKNOWN;
    }
}
