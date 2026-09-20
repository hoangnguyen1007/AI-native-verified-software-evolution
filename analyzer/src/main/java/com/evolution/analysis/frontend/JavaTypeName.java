package com.evolution.analysis.frontend;
import java.util.*;
import java.util.regex.*;

/** Decoder for the existing percent-escaped java:v1 top-level/member-type key grammar. */
public record JavaTypeName(String packageName,String binarySimpleName) {
    private static final Pattern TOP=Pattern.compile("\\[\"type\",\\[((?:\"[A-Za-z0-9_%]+\"(?:,\"[A-Za-z0-9_%]+\")*)?)\\],\"([A-Za-z0-9_%]+)\"\\]");
    public static Optional<JavaTypeName> fromCanonical(String canonical) {
        if(!canonical.startsWith("java:v1:"))return Optional.empty();
        String key=canonical.substring(8);var nested=new ArrayList<String>();
        while(key.startsWith("[\"member-type\",")) {
            int split=key.lastIndexOf(",\"");if(split<0||!key.endsWith("\"]"))return Optional.empty();
            nested.add(decode(key.substring(split+2,key.length()-2)));key=key.substring(15,split);
        }
        var match=TOP.matcher(key);if(!match.matches())return Optional.empty();
        var pkg=new ArrayList<String>();if(!match.group(1).isEmpty())for(String s:match.group(1).split(","))pkg.add(decode(s.substring(1,s.length()-1)));
        Collections.reverse(nested);nested.addFirst(decode(match.group(2)));
        return Optional.of(new JavaTypeName(String.join(".",pkg),String.join("$",nested)));
    }
    private static String decode(String value) {
        StringBuilder result=new StringBuilder();for(int i=0;i<value.length();) {
            if(value.charAt(i)=='%'){if(i+7>value.length())throw new IllegalArgumentException("Invalid Java key");result.appendCodePoint(Integer.parseInt(value.substring(i+1,i+7),16));i+=7;}
            else result.append(value.charAt(i++));
        }return result.toString();
    }
    public String qualifiedName(){return packageName.isEmpty()?binarySimpleName:packageName+"."+binarySimpleName;}
}
