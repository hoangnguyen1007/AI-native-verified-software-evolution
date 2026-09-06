package com.evolution.analysis.maven;

import com.evolution.analysis.contract.common.ContractChecks;
import java.util.ArrayDeque;

/** Lexical paths inside the supplied inventory; never host filesystem paths. */
final class PomPaths {
    private PomPaths() {}

    static String directory(String pom) {
        int slash = pom.lastIndexOf('/');
        return slash < 0 ? "." : pom.substring(0, slash);
    }

    static String resolve(String pom, String reference) {
        if (reference == null || reference.isBlank() || !reference.equals(reference.strip())
                || reference.indexOf(':') >= 0 || reference.startsWith("/") || reference.startsWith("\\")) {
            throw new IllegalArgumentException("Non-relative model reference");
        }
        var parts = new ArrayDeque<String>();
        String base = directory(pom);
        if (!base.equals(".")) parts.addAll(java.util.List.of(base.split("/")));
        for (String part : reference.replace('\\', '/').split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalArgumentException("Model reference leaves inventory");
                parts.removeLast();
            } else {
                ContractChecks.repositoryRelativePath(part, "path segment");
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }

    static String asPom(String path, java.util.Map<String, ?> inventory) {
        if (inventory.containsKey(path) || path.endsWith(".xml")) return path;
        return path.equals(".") ? "pom.xml" : path + "/pom.xml";
    }
}
