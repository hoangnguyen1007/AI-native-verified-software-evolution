package com.evolution.analysis.contract.common;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared fail-fast validation used by the immutable contract types. */
public final class ContractChecks {

    private static final Pattern NAMESPACED_ID =
            Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/+~-]*");

    private ContractChecks() {}

    public static <T> T notNull(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    public static String text(String value, String name) {
        notNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(name + " must use Unicode NFC normalization");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " must not contain NUL");
        }
        return value;
    }

    public static String namespacedId(String value, String name) {
        text(value, name);
        if (!NAMESPACED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase namespaced identifier");
        }
        return value;
    }

    public static String token(String value, String name) {
        text(value, name);
        if (!TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return value;
    }

    public static String repositoryRelativePath(String value, String name) {
        text(value, name);
        if (value.startsWith("/") || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException(name + " must be a portable repository-relative path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(name + " contains an invalid path segment");
            }
        }
        return value;
    }

    public static <T> List<T> sortedDistinct(
            Collection<T> values, Comparator<? super T> comparator, String name) {
        notNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(notNull(value, name + " element"));
        }
        copy.sort(comparator);
        for (int index = 1; index < copy.size(); index++) {
            if (comparator.compare(copy.get(index - 1), copy.get(index)) == 0) {
                throw new IllegalArgumentException(name + " must not contain duplicates");
            }
        }
        return List.copyOf(copy);
    }

    public static <T> List<T> distinctInOrder(Collection<T> values, String name) {
        notNull(values, name);
        ArrayList<T> copy = new ArrayList<>(values.size());
        HashSet<T> seen = new HashSet<>();
        for (T value : values) {
            T checked = notNull(value, name + " element");
            if (!seen.add(checked)) {
                throw new IllegalArgumentException(name + " must not contain duplicates");
            }
            copy.add(checked);
        }
        return List.copyOf(copy);
    }

    public static List<String> sortedStrings(Collection<String> values, String name) {
        notNull(values, name);
        List<String> checked = values.stream()
                .map(value -> text(value, name + " element"))
                .toList();
        return sortedDistinct(checked, Comparator.naturalOrder(), name);
    }

    public static Map<String, String> sortedStringMap(Map<String, String> values, String name) {
        notNull(values, name);
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            String checkedKey = token(key, name + " key");
            String checkedValue = text(value, name + " value");
            if (sorted.put(checkedKey, checkedValue) != null) {
                throw new IllegalArgumentException(name + " contains duplicate keys");
            }
        });
        return Map.copyOf(new LinkedHashMap<>(sorted));
    }
}
