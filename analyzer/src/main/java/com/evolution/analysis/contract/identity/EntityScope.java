package com.evolution.analysis.contract.identity;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Content-addressed namespace that prevents entity identities from using unstable labels. */
public record EntityScope(String value) implements CanonicalIdentifier, Comparable<EntityScope> {

    private static final Pattern FORMAT =
            Pattern.compile("(?:module|dependency|jdk|synthetic):sha256:[0-9a-f]{64}");

    public EntityScope {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "entity scope must be a typed content-addressed scope");
        }
    }

    public static EntityScope project(ModuleIdentity module) {
        ContractChecks.notNull(module, "project module scope");
        return new EntityScope(module.value());
    }

    public static EntityScope external(
            EntityOrigin origin, String canonicalOrigin, ContentDigest contentDigest) {
        ContractChecks.notNull(origin, "entity origin");
        if (origin == EntityOrigin.PROJECT) {
            throw new IllegalArgumentException("project entity scopes require a module identity");
        }
        ContractChecks.text(canonicalOrigin, "canonical entity origin");
        ContractChecks.notNull(contentDigest, "entity origin content digest");
        String kind = origin.name().toLowerCase(Locale.ROOT);
        return new EntityScope(IdentitySupport.derive(
                kind, List.of(canonicalOrigin, contentDigest.value())));
    }

    public void validateOrigin(EntityOrigin origin) {
        String expectedPrefix = switch (origin) {
            case PROJECT -> "module:";
            case DEPENDENCY -> "dependency:";
            case JDK -> "jdk:";
            case SYNTHETIC -> "synthetic:";
        };
        if (!value.startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("entity scope does not match entity origin");
        }
    }

    @Override
    public int compareTo(EntityScope other) {
        return value.compareTo(other.value);
    }
}
