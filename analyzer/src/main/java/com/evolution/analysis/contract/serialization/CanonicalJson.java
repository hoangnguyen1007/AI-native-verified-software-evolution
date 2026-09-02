package com.evolution.analysis.contract.serialization;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic UTF-8 JSON form for M1 contract values.
 *
 * <p>Object keys are lexicographic, record components are serialized by name, identifiers are
 * scalar strings, decimal numbers use normalized plain notation, and binary floating point is
 * rejected. Collections retain their contract-defined order; sets are sorted by encoded value.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.toString();
    }

    private static void append(Object value, StringBuilder output) {
        if (value == null || value instanceof Optional<?> optional && optional.isEmpty()) {
            output.append("null");
        } else if (value instanceof Optional<?> optional) {
            append(optional.orElseThrow(), output);
        } else if (value instanceof CanonicalIdentifier identifier) {
            appendString(identifier.value(), output);
        } else if (value instanceof String string) {
            appendString(string, output);
        } else if (value instanceof Character character) {
            appendString(character.toString(), output);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger) {
            output.append(value);
        } else if (value instanceof BigDecimal decimal) {
            appendDecimal(decimal, output);
        } else if (value instanceof Float || value instanceof Double) {
            throw new IllegalArgumentException(
                    "Binary floating-point values are not canonical contract values");
        } else if (value instanceof Enum<?> enumValue) {
            appendString(enumValue.name(), output);
        } else if (value instanceof Instant instant) {
            appendString(instant.toString(), output);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(map, output);
        } else if (value instanceof Set<?> set) {
            List<Object> sorted = new ArrayList<>(set);
            sorted.sort(Comparator.comparing(CanonicalJson::write));
            appendCollection(sorted, output);
        } else if (value instanceof Collection<?> collection) {
            appendCollection(collection, output);
        } else if (value.getClass().isArray()) {
            appendArray(value, output);
        } else if (value.getClass().isRecord()) {
            appendRecord(value, output);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported canonical JSON type: " + value.getClass().getName());
        }
    }

    private static void appendDecimal(BigDecimal value, StringBuilder output) {
        BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        output.append(normalized.toPlainString());
    }

    private static void appendMap(Map<?, ?> map, StringBuilder output) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException("Canonical JSON map keys must be strings");
            }
            sorted.put(stringKey, value);
        });
        output.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                output.append(',');
            }
            first = false;
            appendString(entry.getKey(), output);
            output.append(':');
            append(entry.getValue(), output);
        }
        output.append('}');
    }

    private static void appendCollection(Collection<?> values, StringBuilder output) {
        output.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                output.append(',');
            }
            first = false;
            append(value, output);
        }
        output.append(']');
    }

    private static void appendArray(Object array, StringBuilder output) {
        output.append('[');
        for (int index = 0; index < Array.getLength(array); index++) {
            if (index > 0) {
                output.append(',');
            }
            append(Array.get(array, index), output);
        }
        output.append(']');
    }

    private static void appendRecord(Object record, StringBuilder output) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        List<RecordComponent> sorted = List.of(components).stream()
                .sorted(Comparator.comparing(RecordComponent::getName))
                .toList();
        output.append('{');
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            RecordComponent component = sorted.get(index);
            appendString(component.getName(), output);
            output.append(':');
            try {
                if (!component.getAccessor().trySetAccessible()) {
                    throw new IllegalArgumentException(
                            "Cannot access canonical record component " + component.getName());
                }
                append(component.getAccessor().invoke(record), output);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalArgumentException(
                        "Cannot read canonical record component " + component.getName(), exception);
            }
        }
        output.append('}');
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length()
                                || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("String contains an unpaired surrogate");
                        }
                        output.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("String contains an unpaired surrogate");
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
