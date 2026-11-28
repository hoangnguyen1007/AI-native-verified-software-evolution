package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.SourceInput;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Lossless canonical provider payload; its digest is distinct from provider semantic identity. */
public record GraphEvidence(ContentDigest identity, String family, String canonicalJson) {
    private static final Set<String> SEMANTIC_PROVENANCE = Set.of("repository.snapshot", "analysis.manifest",
            "source.request", "repository.acquisition", "evidence.attempt", "evidence.ledger", "evidence.ledger-index");
    private record Accessor(String name, String encodedKeyPrefix, Method method, java.lang.invoke.MethodHandle handle) {
        Object read(Object record) throws IllegalAccessException, InvocationTargetException {
            if (handle != null) {
                try {
                    return handle.invokeExact(record);
                } catch (RuntimeException | Error runtime) {
                    throw runtime;
                } catch (Throwable failure) {
                    throw new InvocationTargetException(failure);
                }
            }
            return method.invoke(record);
        }
    }
    // Cache sorted record accessors, pre-encoded JSON key prefixes, and MethodHandles per class.
    private static final ClassValue<List<Accessor>> RECORD_ACCESSORS = new ClassValue<>() {
        @Override protected List<Accessor> computeValue(Class<?> type) {
            var lookup = java.lang.invoke.MethodHandles.lookup();
            var sorted = Arrays.stream(type.getRecordComponents())
                    .sorted(Comparator.comparing(java.lang.reflect.RecordComponent::getName)).toList();
            var list = new ArrayList<Accessor>(sorted.size());
            for (int i = 0; i < sorted.size(); i++) {
                var c = sorted.get(i);
                Method m = c.getAccessor();
                m.trySetAccessible();
                java.lang.invoke.MethodHandle mh = null;
                try {
                    mh = lookup.unreflect(m)
                            .asType(java.lang.invoke.MethodType.methodType(Object.class, Object.class));
                } catch (IllegalAccessException ignored) {
                    // Non-public records fall back cleanly to accessible Method.invoke.
                }
                var prefix = new StringBuilder(c.getName().length() + 4);
                if (i > 0) prefix.append(',');
                try {
                    GraphCanonicalWriter.appendJsonString(c.getName(), prefix);
                } catch (java.io.IOException impossible) {
                    throw new IllegalStateException(impossible);
                }
                prefix.append(':');
                list.add(new Accessor(c.getName(), prefix.toString(), m, mh));
            }
            return List.copyOf(list);
        }
    };
    private static final ClassValue<Optional<Method>> CANONICAL_FORM_METHOD = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            try {
                Method m = type.getMethod("canonicalForm");
                if (m.getParameterCount() == 0) {
                    m.trySetAccessible();
                    return Optional.of(m);
                }
            } catch (NoSuchMethodException absent) {
                // Supported only when explicitly provided.
            }
            return Optional.empty();
        }
    };
    private static final ThreadLocal<ContentDigest> TRUSTED_CAPTURE = new ThreadLocal<>();
    public GraphEvidence {
        Objects.requireNonNull(identity);
        family = ContractChecks.token(family, "graph evidence family");
        Objects.requireNonNull(canonicalJson);
        if (TRUSTED_CAPTURE.get() != identity) {
            // Retain the legacy round trip for rejected fast-path inputs so the
            // decoder still classifies unusual spellings and limits precisely.
            if (!CanonicalEvidenceJson.isCanonical(canonicalJson)
                    && !canonicalJson.equals(CanonicalJson.write(GraphJson.read(canonicalJson, GraphJson.Limits.defaults()))))
                throw new IllegalArgumentException("Noncanonical graph evidence");
            if (!identity.equals(digest(family, canonicalJson))) throw new IllegalArgumentException("Graph evidence hash mismatch");
        }
    }

    /**
     * Reconstitutes a {@link GraphEvidence} record from a storage stream whose full-file
     * cryptographic SHA-256 checksum and canonical JSON binding were already authenticated.
     * Never call on unauthenticated or user-supplied inputs.
     */
    public static GraphEvidence fromAuthenticatedCache(ContentDigest identity, String family, String canonicalJson) {
        Objects.requireNonNull(identity);
        String checkedFamily = ContractChecks.token(family, "graph evidence family");
        Objects.requireNonNull(canonicalJson);
        ContentDigest previous = TRUSTED_CAPTURE.get();
        TRUSTED_CAPTURE.set(identity);
        try {
            return new GraphEvidence(identity, checkedFamily, canonicalJson);
        } finally {
            if (previous == null) TRUSTED_CAPTURE.remove();
            else TRUSTED_CAPTURE.set(previous);
        }
    }

    public static GraphEvidence capture(String family, Object payload) {
        String checkedFamily = ContractChecks.token(family, "graph evidence family");
        String json;
        if (GraphCanonicalWriter.isEnabled()) {
            var out = new StringBuilder(256);
            try {
                writeFormTo(payload, out, 0);
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            json = out.toString();
        } else {
            json = CanonicalJson.write(form(payload, 0));
        }
        ContentDigest id = digest(checkedFamily, json);
        ContentDigest previous = TRUSTED_CAPTURE.get();
        TRUSTED_CAPTURE.set(id);
        try {
            return new GraphEvidence(id, checkedFamily, json);
        } finally {
            if (previous == null) TRUSTED_CAPTURE.remove();
            else TRUSTED_CAPTURE.set(previous);
        }
    }
    /** Semantic replay excludes only known provenance timestamps/revision labels, never configuration values. */
    public ContentDigest semanticIdentity() {
        // Construction has already checked both the canonical preimage and the digest.
        if (!SEMANTIC_PROVENANCE.contains(family)) return identity;
        var semanticLimits = new GraphJson.Limits(
                Math.max(GraphJson.Limits.defaults().maxCharacters(), canonicalJson.length()),
                GraphJson.Limits.defaults().maxDepth(),
                Integer.MAX_VALUE - 8);
        var copy=new TreeMap<>(GraphJson.object(GraphJson.read(canonicalJson,semanticLimits)));
        switch(family) {
            case "repository.snapshot" -> removeSnapshotLabels(copy);
            case "analysis.manifest","repository.acquisition" -> snapshotMember(copy);
            case "source.request" -> {
                var manifest=new TreeMap<>(GraphJson.object(copy.get("manifest")));snapshotMember(manifest);copy.put("manifest",manifest);
            }
            case "evidence.attempt" -> removeTiming(copy);
            case "evidence.ledger","evidence.ledger-index" -> {
                if (copy.get("attempts") != null) {
                    copy.put("attempts", GraphJson.array(copy.get("attempts")).stream()
                            .map(a -> { var row = new TreeMap<>(GraphJson.object(a)); removeTiming(row); return row; })
                            .toList());
                }
            }
            default -> throw new IllegalStateException("Unknown semantic evidence family");
        }
        return digest(family,CanonicalJson.write(copy));
    }
    private static void removeSnapshotLabels(Map<String,Object> snapshot) {snapshot.remove("revision");snapshot.remove("dirty");}
    private static void removeTiming(Map<String,Object> attempt) {attempt.remove("startedAt");attempt.remove("completedAt");}
    private static void snapshotMember(Map<String,Object> value) {
        if(value.get("snapshot")!=null) {var snapshot=new TreeMap<>(GraphJson.object(value.get("snapshot")));removeSnapshotLabels(snapshot);value.put("snapshot",snapshot);}
    }
    private static ContentDigest digest(String family, String json) {
        if (GraphCanonicalWriter.isEnabled()) {
            var md = CanonicalJson.borrowSha256();
            try {
                try (var writer = CanonicalJson.newUtf8DigestWriter(md)) {
                    writer.write("[\"graph.evidence:m5.1-v1\",");
                    GraphCanonicalWriter.appendJsonString(family, writer);
                    writer.write(',');
                    GraphCanonicalWriter.appendJsonString(json, writer);
                    writer.write(']');
                } catch (java.io.IOException impossible) {
                    throw new IllegalStateException("Canonical digest unavailable", impossible);
                }
                return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
            } finally {
                CanonicalJson.releaseSha256(md);
            }
        }
        var preimage = List.of("graph.evidence:m5.1-v1", family, json);
        return json.length() < 8192 ? ContentDigest.sha256Utf8(CanonicalJson.write(preimage)) : CanonicalJson.sha256(preimage);
    }

    private static final class LimitReachedException extends RuntimeException {
        private static final LimitReachedException INSTANCE = new LimitReachedException();
        private LimitReachedException() { super(null, null, false, false); }
    }

    private static final class CountingUtf8Appendable implements Appendable {
        private final long cap;
        private long bytes;

        CountingUtf8Appendable(long cap) {
            this.cap = cap;
        }

        private void addChar(char c) {
            if (c < 0x80) bytes += 1;
            else if (c < 0x800) bytes += 2;
            else if (Character.isSurrogate(c)) bytes += 2;
            else bytes += 3;
            if (bytes > cap) throw LimitReachedException.INSTANCE;
        }

        @Override
        public Appendable append(CharSequence csq) {
            return append(csq, 0, csq.length());
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) {
            for (int i = start; i < end; i++) addChar(csq.charAt(i));
            return this;
        }

        @Override
        public Appendable append(char c) {
            addChar(c);
            return this;
        }
    }

    /** Computes exact UTF-8 byte length of canonical evidence JSON without materializing a monolithic String. */
    static long encodedUtf8Length(Object value) {
        return encodedUtf8LengthCapped(value, Long.MAX_VALUE - 1);
    }

    /** Computes exact UTF-8 byte length, returning {@code cap + 1} immediately once the cap is exceeded. */
    static long encodedUtf8LengthCapped(Object value, long cap) {
        var counter = new CountingUtf8Appendable(cap);
        try {
            writeFormTo(value, counter, 0);
            return counter.bytes;
        } catch (LimitReachedException exceeded) {
            return cap + 1;
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Streams canonical evidence JSON directly into SHA-256 without allocating a giant String on heap. */
    static ContentDigest canonicalSha256(Object value) {
        var md = CanonicalJson.borrowSha256();
        try {
            try (var writer = CanonicalJson.newUtf8DigestWriter(md)) {
                writeFormTo(value, writer, 0);
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException("Canonical digest unavailable", impossible);
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } finally {
            CanonicalJson.releaseSha256(md);
        }
    }

    /** Single-pass streaming canonical evidence encoder — zero intermediate TreeMaps or Lists. */
    private static void writeFormTo(Object value, Appendable out, int depth) throws java.io.IOException {
        if (depth > 128) throw new IllegalArgumentException("Provider evidence depth limit");
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String s) {
            GraphCanonicalWriter.appendJsonString(s, out);
            return;
        }
        if (value instanceof CanonicalIdentifier id) {
            GraphCanonicalWriter.appendJsonString(id.value(), out);
            return;
        }
        if (value instanceof Boolean b) {
            out.append(b.toString());
            return;
        }
        if (value instanceof Enum<?> e) {
            GraphCanonicalWriter.appendJsonString(e.name(), out);
            return;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            out.append(value.toString());
            return;
        }
        if (value instanceof java.math.BigDecimal decimal) {
            var normalized = decimal.signum() == 0 ? java.math.BigDecimal.ZERO : decimal.stripTrailingZeros();
            out.append(normalized.toPlainString());
            return;
        }
        if (value instanceof Float || value instanceof Double) {
            throw new IllegalArgumentException("Binary floating-point values are not canonical contract values");
        }
        if (value instanceof Instant instant) {
            GraphCanonicalWriter.appendJsonString(instant.toString(), out);
            return;
        }
        if (value instanceof URI uri) {
            GraphCanonicalWriter.appendJsonString(uri.toASCIIString(), out);
            return;
        }
        if (value instanceof Optional<?> optional) {
            if (optional.isPresent()) writeFormTo(optional.orElseThrow(), out, depth + 1);
            else out.append("null");
            return;
        }
        if (value instanceof byte[] bytes) {
            out.append("{\"bytes\":");
            GraphCanonicalWriter.appendJsonString(Base64.getEncoder().encodeToString(bytes), out);
            out.append(",\"contentDigest\":");
            GraphCanonicalWriter.appendJsonString(ContentDigest.sha256(bytes).value(), out);
            out.append(",\"encoding\":\"base64\"}");
            return;
        }
        if (value instanceof com.evolution.analysis.acquisition.AcquiredFile file) {
            out.append("{\"bytesBase64\":");
            GraphCanonicalWriter.appendJsonString(Base64.getEncoder().encodeToString(file.bytes()), out);
            out.append(",\"contentDigest\":");
            GraphCanonicalWriter.appendJsonString(file.contentDigest().value(), out);
            out.append(",\"path\":");
            GraphCanonicalWriter.appendJsonString(file.path(), out);
            out.append('}');
            return;
        }
        if (value instanceof SourceInput source) {
            out.append("{\"bytesBase64\":");
            GraphCanonicalWriter.appendJsonString(Base64.getEncoder().encodeToString(source.bytes()), out);
            out.append(",\"decoding\":");
            writeFormTo(source.decoding(), out, depth + 1);
            out.append(",\"document\":");
            writeFormTo(source.document(), out, depth + 1);
            out.append('}');
            return;
        }
        if (value instanceof com.evolution.analysis.spring.condition.ConditionExpression expression) {
            var nodes = new TreeMap<String, Object>();
            var pending = new ArrayDeque<com.evolution.analysis.spring.condition.ConditionExpression>();
            pending.add(expression);
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                if (nodes.putIfAbsent(node.identity().value(), node.view()) == null) pending.addAll(node.children());
            }
            out.append("{\"expressions\":");
            writeFormTo(List.copyOf(nodes.values()), out, depth + 2);
            out.append(",\"rootIdentity\":");
            GraphCanonicalWriter.appendJsonString(expression.identity().value(), out);
            out.append('}');
            return;
        }
        if (value instanceof com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula formula) {
            var nodes = new TreeMap<String, Object>();
            var pending = new ArrayDeque<com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula>();
            pending.add(formula);
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                if (nodes.putIfAbsent(node.identity().value(), node.view()) == null) pending.addAll(node.children());
            }
            out.append("{\"formulas\":");
            writeFormTo(List.copyOf(nodes.values()), out, depth + 2);
            out.append(",\"rootIdentity\":");
            GraphCanonicalWriter.appendJsonString(formula.identity().value(), out);
            out.append('}');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            boolean stringKeys = true;
            for (Object k : map.keySet()) {
                if (!(k instanceof String)) {
                    stringKeys = false;
                    break;
                }
            }
            if (stringKeys) {
                out.append('{');
                if (map instanceof SortedMap<?, ?> sorted && sorted.comparator() == null) {
                    boolean first = true;
                    for (var e : sorted.entrySet()) {
                        if (!first) out.append(',');
                        first = false;
                        GraphCanonicalWriter.appendJsonString((String) e.getKey(), out);
                        out.append(':');
                        writeFormTo(e.getValue(), out, depth + 1);
                    }
                } else if (map.size() == 1) {
                    var e = map.entrySet().iterator().next();
                    GraphCanonicalWriter.appendJsonString((String) e.getKey(), out);
                    out.append(':');
                    writeFormTo(e.getValue(), out, depth + 1);
                } else {
                    @SuppressWarnings("unchecked")
                    Map.Entry<String, Object>[] entries = map.entrySet().toArray(new Map.Entry[0]);
                    Arrays.sort(entries, Comparator.comparing(Map.Entry::getKey));
                    for (int i = 0; i < entries.length; i++) {
                        if (i > 0) out.append(',');
                        GraphCanonicalWriter.appendJsonString(entries[i].getKey(), out);
                        out.append(':');
                        writeFormTo(entries[i].getValue(), out, depth + 1);
                    }
                }
                out.append('}');
                return;
            }
            var encodedEntries = new ArrayList<String>(map.size());
            for (var e : map.entrySet()) {
                var pair = new StringBuilder(64);
                pair.append('[');
                writeFormTo(e.getKey(), pair, depth + 1);
                pair.append(',');
                writeFormTo(e.getValue(), pair, depth + 1);
                pair.append(']');
                encodedEntries.add(pair.toString());
            }
            encodedEntries.sort(String::compareTo);
            out.append('[');
            for (int i = 0, len = encodedEntries.size(); i < len; i++) {
                if (i > 0) out.append(',');
                out.append(encodedEntries.get(i));
            }
            out.append(']');
            return;
        }
        if (value instanceof Set<?> set) {
            if (set.isEmpty()) {
                out.append("[]");
                return;
            }
            var encoded = new ArrayList<String>(set.size());
            for (Object item : set) {
                var itemBuf = new StringBuilder(64);
                writeFormTo(item, itemBuf, depth + 1);
                encoded.add(itemBuf.toString());
            }
            encoded.sort(String::compareTo);
            out.append('[');
            for (int i = 0, len = encoded.size(); i < len; i++) {
                if (i > 0) out.append(',');
                out.append(encoded.get(i));
            }
            out.append(']');
            return;
        }
        if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) out.append(',');
                first = false;
                writeFormTo(item, out, depth + 1);
            }
            out.append(']');
            return;
        }
        Class<?> cls = value.getClass();
        if (cls.isRecord()) {
            List<Accessor> accessors = RECORD_ACCESSORS.get(cls);
            out.append('{');
            for (int i = 0, len = accessors.size(); i < len; i++) {
                Accessor component = accessors.get(i);
                out.append(component.encodedKeyPrefix());
                try {
                    writeFormTo(component.read(value), out, depth + 1);
                } catch (IllegalAccessException | InvocationTargetException failure) {
                    throw new IllegalArgumentException("Cannot encode provider record", failure);
                }
            }
            out.append('}');
            return;
        }
        var canonicalMethod = CANONICAL_FORM_METHOD.get(cls);
        if (canonicalMethod.isPresent()) {
            try {
                writeFormTo(canonicalMethod.orElseThrow().invoke(value), out, depth + 1);
                return;
            } catch (ReflectiveOperationException failed) {
                throw new IllegalArgumentException("Cannot encode provider canonical form", failed);
            }
        }
        throw new IllegalArgumentException("Provider has no supported neutral evidence form: " + cls.getSimpleName());
    }

    /** Records/canonical forms only; no arbitrary fields or polymorphic deserialization. */
    static Object form(Object value,int depth) {
        if(depth>128) throw new IllegalArgumentException("Provider evidence depth limit");
        if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof CanonicalIdentifier || value instanceof Instant || value instanceof URI)
            return value;
        if(value instanceof Optional<?> optional) return optional.isPresent()?form(optional.orElseThrow(),depth+1):null;
        if(value instanceof byte[] bytes) return Map.of("encoding","base64","contentDigest",ContentDigest.sha256(bytes),"bytes",Base64.getEncoder().encodeToString(bytes));
        if(value instanceof com.evolution.analysis.acquisition.AcquiredFile file) return Map.of("path",file.path(),"contentDigest",file.contentDigest(),"bytesBase64",Base64.getEncoder().encodeToString(file.bytes()));
        if(value instanceof SourceInput source) return Map.of("document",source.document(),"decoding",source.decoding(),
                "bytesBase64",Base64.getEncoder().encodeToString(source.bytes()));
        if(value instanceof com.evolution.analysis.spring.condition.ConditionExpression expression) {
            var nodes=new TreeMap<String,Object>();var pending=new ArrayDeque<com.evolution.analysis.spring.condition.ConditionExpression>();pending.add(expression);
            while(!pending.isEmpty()){var node=pending.removeFirst();if(nodes.putIfAbsent(node.identity().value(),node.view())==null)pending.addAll(node.children());}
            return form(Map.of("rootIdentity",expression.identity(),"expressions",List.copyOf(nodes.values())),depth+1);
        }
        if(value instanceof com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula formula) {
            var nodes=new TreeMap<String,Object>();var pending=new ArrayDeque<com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula>();pending.add(formula);
            while(!pending.isEmpty()){var node=pending.removeFirst();if(nodes.putIfAbsent(node.identity().value(),node.view())==null)pending.addAll(node.children());}
            return form(Map.of("rootIdentity",formula.identity(),"formulas",List.copyOf(nodes.values())),depth+1);
        }
        if(value instanceof Map<?,?> map) {
            boolean stringKeys = true;
            for (Object k : map.keySet()) {
                if (!(k instanceof String)) {
                    stringKeys = false;
                    break;
                }
            }
            if(stringKeys) {
                var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put((String)k,form(v,depth+1)));return result;
            }
            return map.entrySet().stream().map(e->Arrays.asList(form(e.getKey(),depth+1),form(e.getValue(),depth+1)))
                    .sorted(Comparator.comparing(CanonicalJson::write)).toList();
        }
        if(value instanceof Collection<?> collection) {
            var result=collection.stream().map(v->form(v,depth+1)).toList();
            return value instanceof Set<?> ? result.stream().sorted(Comparator.comparing(CanonicalJson::write)).toList():result;
        }
        if(value.getClass().isRecord()) {
            var result=new TreeMap<String,Object>();
            try {for(var component:RECORD_ACCESSORS.get(value.getClass()))
                result.put(component.name(),form(component.read(value),depth+1));}
            catch(IllegalAccessException|InvocationTargetException failure) {throw new IllegalArgumentException("Cannot encode provider record",failure);}
            return result;
        }
        var canonicalMethod = CANONICAL_FORM_METHOD.get(value.getClass());
        if (canonicalMethod.isPresent()) {
            try {
                return form(canonicalMethod.orElseThrow().invoke(value), depth + 1);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalArgumentException("Cannot encode provider canonical form", failed);
            }
        }
        throw new IllegalArgumentException("Provider has no supported neutral evidence form: "+value.getClass().getSimpleName());
    }
}
