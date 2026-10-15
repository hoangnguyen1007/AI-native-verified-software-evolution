package com.evolution.analysis.contract.serialization;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
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
    private record RecordField(String name,Method accessor) {}
    /** Cache class metadata only. Retaining accessors avoids repeated reflection inflation in sort comparisons. */
    private static final ClassValue<List<RecordField>> RECORD_FIELDS=new ClassValue<>() {
        @Override protected List<RecordField> computeValue(Class<?> type){
            return java.util.Arrays.stream(type.getRecordComponents()).sorted(Comparator.comparing(RecordComponent::getName)).map(component->{
                Method accessor=component.getAccessor();
                if(!accessor.trySetAccessible())throw new IllegalArgumentException("Cannot access canonical record component "+component.getName());
                return new RecordField(component.getName(),accessor);
            }).toList();
        }
    };

    private CanonicalJson() {}

    public static String write(Object value) {
        StringBuilder output = new StringBuilder();
        try{append(value, output);}catch(IOException impossible){throw new IllegalStateException(impossible);}
        return output.toString();
    }
    public static void writeTo(Object value,Writer writer)throws IOException {append(value,writer);}
    /** Count the exact JSON value/depth denominator without materializing a second object tree. */
    public static boolean fitsStructure(Object value,int maxDepth,int maxValues){
        if(maxDepth<1||maxValues<1)throw new IllegalArgumentException("Positive structural limits required");
        return fits(value,0,maxDepth,new long[]{maxValues});
    }
    private static boolean fits(Object value,int depth,int maxDepth,long[] remaining){
        if(value instanceof Optional<?> optional)return fits(optional.orElse(null),depth,maxDepth,remaining);
        if(depth>maxDepth||--remaining[0]<0)return false;
        if(value==null||value instanceof CanonicalIdentifier||value instanceof CharSequence||value instanceof Character||value instanceof Number||value instanceof Boolean||value instanceof Enum<?>||value instanceof URI||value instanceof Instant)return true;
        if(value instanceof Map<?,?> map){for(Object member:map.values())if(!fits(member,depth+1,maxDepth,remaining))return false;return true;}
        if(value instanceof Collection<?> collection){for(Object member:collection)if(!fits(member,depth+1,maxDepth,remaining))return false;return true;}
        if(value.getClass().isArray()){for(int i=0;i<Array.getLength(value);i++)if(!fits(Array.get(value,i),depth+1,maxDepth,remaining))return false;return true;}
        if(value.getClass().isRecord())try{for(var field:RECORD_FIELDS.get(value.getClass()))if(!fits(field.accessor().invoke(value),depth+1,maxDepth,remaining))return false;return true;}
        catch(IllegalAccessException|InvocationTargetException invalid){throw new IllegalArgumentException("Cannot inspect canonical structure",invalid);}
        throw new IllegalArgumentException("Unsupported canonical JSON type");
    }

    /** Same canonical UTF-8 preimage as write(...), without materializing a whole-document string. */
    public static ContentDigest sha256(Object value){
        try{
            var digest=MessageDigest.getInstance("SHA-256");
            try(var writer=new BufferedWriter(new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(),digest),StandardCharsets.UTF_8),8192)){append(value,writer);}
            return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));
        }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException("Canonical digest unavailable",impossible);}
    }

    private static void append(Object value, Appendable output) throws IOException {
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
            output.append(bool.toString());
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger) {
            output.append(value.toString());
        } else if (value instanceof BigDecimal decimal) {
            appendDecimal(decimal, output);
        } else if (value instanceof Float || value instanceof Double) {
            throw new IllegalArgumentException(
                    "Binary floating-point values are not canonical contract values");
        } else if (value instanceof Enum<?> enumValue) {
            appendString(enumValue.name(), output);
        } else if (value instanceof Instant instant) {
            appendString(instant.toString(), output);
        } else if (value instanceof URI uri) {
            appendString(uri.toASCIIString(), output);
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

    private static void appendDecimal(BigDecimal value, Appendable output) throws IOException {
        BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        output.append(normalized.toPlainString());
    }

    private static void appendMap(Map<?, ?> map, Appendable output) throws IOException {
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

    private static void appendCollection(Collection<?> values, Appendable output) throws IOException {
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

    private static void appendArray(Object array, Appendable output) throws IOException {
        output.append('[');
        for (int index = 0; index < Array.getLength(array); index++) {
            if (index > 0) {
                output.append(',');
            }
            append(Array.get(array, index), output);
        }
        output.append(']');
    }

    private static void appendRecord(Object record, Appendable output) throws IOException {
        List<RecordField> sorted = RECORD_FIELDS.get(record.getClass());
        output.append('{');
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            RecordField component = sorted.get(index);
            appendString(component.name(), output);
            output.append(':');
            try {
                append(component.accessor().invoke(record), output);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalArgumentException(
                        "Cannot read canonical record component " + component.name(), exception);
            }
        }
        output.append('}');
    }

    private static void appendString(String value, Appendable output) throws IOException {
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
