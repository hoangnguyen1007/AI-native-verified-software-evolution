package com.evolution.analysis.spring.condition;

import java.util.*;

/** A deliberately bounded literal metadata decoder; never resolves Java names or evaluates Java code. */
public final class LiteralConditionAnnotation {
    public sealed interface Value permits Strings, Bool, Classes, Enums {}
    public record Strings(List<String> values) implements Value { public Strings { values = List.copyOf(values); } }
    public record Bool(boolean value) implements Value {}
    public record Classes(List<String> names) implements Value {public Classes{names=List.copyOf(names);}}
    public record Enums(List<String> names) implements Value {public Enums{names=List.copyOf(names);}}
    private final String text;
    private int offset;
    private LiteralConditionAnnotation(String text) { this.text = text; }
    public static Map<String, Value> parse(String text) {
        if (text.contains("\\u")) throw unsupported();
        var parser = new LiteralConditionAnnotation(text);
        return parser.annotation();
    }
    Map<String, Value> annotation() {
        skip(); require('@'); identifier();
        while (take('.')) identifier();
        Map<String, Value> attributes = new TreeMap<>();
        if (take('(') && !take(')')) {
            boolean named = false, positional = false;
            do {
                skip(); String name;
                if (peek() == '"' || peek() == '{') { name = "value"; positional = true; }
                else { name = identifier(); require('='); named = true; }
                if (named && positional || attributes.putIfAbsent(name, value()) != null) throw unsupported();
            } while (take(','));
            require(')');
        }
        skip(); if (offset != text.length()) throw unsupported();
        return Collections.unmodifiableMap(attributes);
    }
    Value value() {
        skip();
        if (peek() == '"') return new Strings(List.of(string()));
        if (take('{')) {
            skip();List<String> values = new ArrayList<>();boolean classes=peek()!='"'&&peek()!='}';
            if (!take('}')) {
                values.add(classes?qualifiedValue():string());
                while (take(',')) { if (take('}')) return classes?qualifiedValues(values):new Strings(values); values.add(classes?qualifiedValue():string()); }
                require('}');
            }
            return classes?qualifiedValues(values):new Strings(values);
        }
        int start=offset;String identifier=identifier();
        if(identifier.equals("true"))return new Bool(true);
        if(identifier.equals("false"))return new Bool(false);
        offset=start;return qualifiedValues(List.of(qualifiedValue()));
    }
    String qualifiedValue() {
        StringBuilder name=new StringBuilder(identifier());
        while(take('.'))name.append('.').append(identifier());
        return name.toString();
    }
    Value qualifiedValues(List<String> values) {
        if(values.stream().allMatch(v->v.endsWith(".class")))return new Classes(values.stream().map(v->v.substring(0,v.length()-6)).toList());
        if(values.stream().anyMatch(v->v.endsWith(".class")))throw unsupported();
        return new Enums(values);
    }
    String className() {
        StringBuilder name=new StringBuilder(identifier());
        while(take('.')) {
            String part=identifier();if(part.equals("class"))return name.toString();name.append('.').append(part);
        }
        throw unsupported();
    }
    String string() {
        skip(); require('"'); StringBuilder value = new StringBuilder();
        while (offset < text.length()) {
            char c = text.charAt(offset++);
            if (c == '"') return ConditionIdentitySupport.raw(value.toString());
            if (c == '\n' || c == '\r') throw unsupported();
            if (c == '\\') {
                if (offset == text.length()) throw unsupported();
                char escape = text.charAt(offset++);
                if (escape >= '0' && escape <= '7') {
                    int code = escape - '0', remaining = escape <= '3' ? 2 : 1;
                    while (remaining-- > 0 && offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '7') code = code * 8 + text.charAt(offset++) - '0';
                    c = (char) code;
                } else c = switch (escape) {
                    case 'b' -> '\b'; case 't' -> '\t'; case 'n' -> '\n'; case 'f' -> '\f'; case 'r' -> '\r';
                    case 's' -> ' '; case '"' -> '"'; case '\'' -> '\''; case '\\' -> '\\';
                    // Java Unicode pretranslation and text blocks belong to a semantic metadata provider.
                    default -> throw unsupported();
                };
            }
            value.append(c);
        }
        throw unsupported();
    }
    String identifier() {
        skip(); int start = offset;
        if (offset == text.length() || !Character.isJavaIdentifierStart(text.charAt(offset))) throw unsupported();
        offset++; while (offset < text.length() && Character.isJavaIdentifierPart(text.charAt(offset))) offset++;
        return text.substring(start, offset);
    }
    void skip() {
        while (offset < text.length()) {
            if (Character.isWhitespace(text.charAt(offset))) { offset++; continue; }
            if (text.startsWith("/*", offset)) { int end = text.indexOf("*/", offset + 2); if (end < 0) throw unsupported(); offset = end + 2; continue; }
            if (text.startsWith("//", offset)) { int end = text.indexOf('\n', offset + 2); offset = end < 0 ? text.length() : end + 1; continue; }
            break;
        }
    }
    char peek() { return offset == text.length() ? '\0' : text.charAt(offset); }
    boolean take(char c) { skip(); if (peek() != c) return false; offset++; return true; }
    void require(char c) { if (!take(c)) throw unsupported(); }
    static IllegalArgumentException unsupported() { return new IllegalArgumentException("Metadata is outside the bounded literal annotation fragment"); }
    public static List<String> strings(Map<String, Value> attributes, String name, List<String> fallback) {
        var value = attributes.get(name); if (value == null) return fallback;
        if (value instanceof Strings strings) return strings.values(); throw unsupported();
    }
    public static String string(Map<String, Value> attributes, String name, String fallback) {
        var values = strings(attributes, name, List.of(fallback)); if (values.size() != 1) throw unsupported(); return values.getFirst();
    }
    public static boolean bool(Map<String, Value> attributes, String name, boolean fallback) {
        var value = attributes.get(name); if (value == null) return fallback;
        if (value instanceof Bool bool) return bool.value(); throw unsupported();
    }
    public static List<String> classes(Map<String,Value> attributes,String name) {
        var value=attributes.get(name);if(value==null)return List.of();
        if(value instanceof Classes classes)return classes.names();
        if(value instanceof Strings strings&&strings.values().isEmpty())return List.of();
        throw unsupported();
    }
}
