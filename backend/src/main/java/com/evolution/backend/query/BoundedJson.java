package com.evolution.backend.query;

import com.evolution.analysis.contract.common.CanonicalIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Preflight bounds canonical serialization allocation; final UTF-8 bytes have an exact ceiling. */
public final class BoundedJson {
    public static final class TooLarge extends RuntimeException {}
    private long remaining;
    private BoundedJson(int maximum) { remaining = maximum; }
    /** Shared by in-process presentation adapters; canonical byte semantics are unchanged. */
    public static byte[] encode(Object value, int maximum) {
        new BoundedJson(maximum).visit(value,0);
        // The lower-bound preflight caps characters/values. JSON escaping expands at most sixfold.
        byte[] bytes = CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new TooLarge();
        return bytes;
    }
    private void spend(long count) { remaining -= count; if (remaining < 0) throw new TooLarge(); }
    private void visit(Object value, int depth) {
        if (depth > 128) throw new TooLarge();
        spend(1);
        if (value == null) return;
        if (value instanceof Optional<?> optional) { if (optional.isPresent()) visit(optional.orElseThrow(),depth+1); return; }
        if (value instanceof CanonicalIdentifier id) { spend(id.value().length()); return; }
        if (value instanceof String text) { spend(text.length()); return; }
        if (value instanceof BigDecimal decimal) {
            spend(Math.max(decimal.precision(), (long)decimal.precision()-decimal.scale()) + Math.max(0L,decimal.scale()) + 3); return;
        }
        if (value instanceof BigInteger integer) {
            if ((long)integer.bitLength()>remaining*4) throw new TooLarge();
            spend(integer.toString().length()); return;
        }
        if (value instanceof Number || value instanceof Enum<?> || value instanceof Boolean
                || value instanceof java.time.Instant || value instanceof java.net.URI) { spend(value.toString().length()); return; }
        if (value instanceof Map<?,?> map) {
            for (var entry : map.entrySet()) { visit(entry.getKey(),depth+1); visit(entry.getValue(),depth+1); }
        } else if (value instanceof Collection<?> collection) {
            for (Object child : collection) visit(child,depth+1);
        } else if (value.getClass().isArray()) {
            int length=java.lang.reflect.Array.getLength(value);
            if (length>remaining) throw new TooLarge();
            for (int i=0;i<length;i++) visit(java.lang.reflect.Array.get(value,i),depth+1);
        } else if (value.getClass().isRecord()) {
            for (var component : value.getClass().getRecordComponents()) {
                spend(component.getName().length());
                try {
                    if (!component.getAccessor().trySetAccessible()) throw new IllegalStateException("Cannot serialize contract");
                    visit(component.getAccessor().invoke(value),depth+1);
                }
                catch (IllegalAccessException | InvocationTargetException failed) { throw new IllegalStateException("Cannot serialize contract"); }
            }
        }
    }
}
