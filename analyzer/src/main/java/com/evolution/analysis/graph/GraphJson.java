package com.evolution.analysis.graph;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.math.BigDecimal;
import java.util.*;

/** Strict bounded JSON reader for the graph format, without polymorphic class loading. */
public final class GraphJson {
    public static final class LimitExceededException extends IllegalArgumentException {
        public LimitExceededException(String message) {super(message);}
    }
    public record Limits(int maxCharacters, int maxDepth, int maxValues) {
        public Limits {
            if (maxCharacters < 1 || maxDepth < 1 || maxDepth > 256 || maxValues < 1)
                throw new IllegalArgumentException("Invalid graph JSON limits");
        }
        public static Limits defaults() { return new Limits(64_000_000, 128, 2_000_000); }
    }
    public static Object read(String json, Limits limits) {
        Objects.requireNonNull(json); Objects.requireNonNull(limits);
        if (json.length() > limits.maxCharacters()) throw new LimitExceededException("Graph JSON character limit");
        var reader = new Reader(json, limits);
        var result = reader.value(0); reader.whitespace();
        if (reader.at != json.length()) throw new IllegalArgumentException("Trailing graph JSON content");
        CanonicalJson.write(result);
        return result;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>) map;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) list;
    }
    public static String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected JSON string");
        return text;
    }
    static void fields(Map<String,Object> object, String... names) {
        if (!object.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Unknown or missing graph fields");
    }
    private static final class Reader {
        final String json; final Limits limits; int at; int values;
        Reader(String json, Limits limits) { this.json=json; this.limits=limits; }
        void whitespace() { while(at<json.length() && " \t\r\n".indexOf(json.charAt(at))>=0) at++; }
        Object value(int depth) {
            if (depth > limits.maxDepth() || ++values > limits.maxValues())
                throw new LimitExceededException("Graph JSON structural limit");
            whitespace(); if(at>=json.length()) throw new IllegalArgumentException("Incomplete graph JSON");
            char c=json.charAt(at);
            if(c=='"') return text();
            if(c=='{') {
                at++; var result=new TreeMap<String,Object>(); whitespace();
                if(take('}')) return result;
                do {
                    whitespace(); if(at>=json.length() || json.charAt(at)!='"') throw new IllegalArgumentException("Object key required");
                    String key=text(); whitespace(); require(':');
                    if(result.containsKey(key)) throw new IllegalArgumentException("Duplicate graph JSON key");
                    result.put(key,value(depth+1)); whitespace();
                    if(take('}')) return result;
                    require(',');
                } while(true);
            }
            if(c=='[') {
                at++; var result=new ArrayList<Object>(); whitespace();
                if(take(']')) return result;
                do { result.add(value(depth+1)); whitespace(); if(take(']')) return result; require(','); } while(true);
            }
            for(String literal:List.of("true","false","null")) if(json.startsWith(literal,at)) {
                at+=literal.length(); return literal.equals("null")?null:Boolean.valueOf(literal);
            }
            int start=at; take('-');
            if(!take('0')) digits();
            if(take('.')) digits();
            if(take('e') || take('E')) { if(!take('+')) take('-'); digits(); }
            try {
                var number=new BigDecimal(json.substring(start,at));
                int numericLimit=Math.min(4096,limits.maxCharacters());
                if(Math.abs((long)number.scale())>numericLimit || number.precision()>numericLimit)
                    throw new LimitExceededException("Graph JSON number expansion limit");
                return number;
            } catch(NumberFormatException invalid) { throw new IllegalArgumentException("Invalid graph JSON number"); }
        }
        void digits() {
            int start=at; while(at<json.length() && json.charAt(at)>='0' && json.charAt(at)<='9') at++;
            if(start==at) throw new IllegalArgumentException("Graph JSON digits required");
        }
        boolean take(char c) { if(at<json.length()&&json.charAt(at)==c) {at++;return true;}return false; }
        void require(char c) { if(!take(c)) throw new IllegalArgumentException("Invalid graph JSON delimiter"); }
        String text() {
            require('"'); var result=new StringBuilder();
            while(at<json.length()) {
                char c=json.charAt(at++);
                if(c=='"') return result.toString();
                if(c<32) throw new IllegalArgumentException("Unescaped graph JSON control");
                if(c!='\\') {result.append(c);continue;}
                if(at>=json.length()) throw new IllegalArgumentException("Incomplete graph JSON escape");
                char escaped=json.charAt(at++);
                switch(escaped) {
                    case '"','\\','/' -> result.append(escaped);
                    case 'b' -> result.append('\b'); case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                    case 'u' -> {
                        if(at+4>json.length()) throw new IllegalArgumentException("Incomplete Unicode escape");
                        int code=0;
                        for(int i=0;i<4;i++) {int digit=Character.digit(json.charAt(at++),16);if(digit<0)throw new IllegalArgumentException("Invalid Unicode escape");code=(code<<4)|digit;}
                        result.append((char)code);
                    }
                    default -> throw new IllegalArgumentException("Invalid graph JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated graph JSON string");
        }
    }
    private GraphJson() {}
}
