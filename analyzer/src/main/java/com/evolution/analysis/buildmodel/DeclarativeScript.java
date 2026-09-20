package com.evolution.analysis.buildmodel;

import java.util.*;

/** Bounded lexical tree, deliberately not a Groovy/Kotlin evaluator. Strings/comments cannot create blocks. */
final class DeclarativeScript {
    record Token(String value, boolean literal, int offset) {}
    record Statement(List<Token> head,List<Statement> body,int offset) {
        String key(){return head.isEmpty()?"":head.getFirst().value();}
        List<String> strings(){return head.stream().filter(Token::literal).map(Token::value).toList();}
        String syntax(){return head.stream().map(t -> t.literal()?"#":t.value()).collect(java.util.stream.Collectors.joining());}
    }
    private final List<Token> tokens; private int index; private final int depthLimit;
    private DeclarativeScript(List<Token> tokens,int depthLimit){this.tokens=tokens;this.depthLimit=depthLimit;}
    static List<Statement> parse(String source,int maxTokens,int maxDepth) {
        List<Token> tokens=new ArrayList<>();
        for(int i=0;i<source.length();) {
            int start=i; char c=source.charAt(i++);
            if(c=='\r'||c==' '||c=='\t')continue;
            if(c=='/' && i<source.length() && source.charAt(i)=='/') {while(i<source.length()&&source.charAt(i)!='\n')i++;continue;}
            if(c=='/' && i<source.length() && source.charAt(i)=='*') {int end=source.indexOf("*/",i+1);if(end<0)throw new IllegalArgumentException();i=end+2;continue;}
            if(c=='\''||c=='"') {
                char quote=c; StringBuilder value=new StringBuilder(); boolean closed=false;
                while(i<source.length()) {
                    c=source.charAt(i++); if(c==quote){closed=true;break;}
                    if(c=='\n'||c=='\r')throw new IllegalArgumentException();
                    if(c=='\\') {if(i==source.length())throw new IllegalArgumentException();c=source.charAt(i++);
                        c=switch(c){case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case '\\','\'','"'->c;default->throw new IllegalArgumentException();};}
                    value.append(c);
                }
                if(!closed)throw new IllegalArgumentException();tokens.add(new Token(value.toString(),true,start));
            } else if(Character.isJavaIdentifierStart(c)||Character.isDigit(c)||c=='`') {
                if(c=='`'){int end=source.indexOf('`',i);if(end<0)throw new IllegalArgumentException();tokens.add(new Token(source.substring(i,end),false,start));i=end+1;}
                else {while(i<source.length()&&(Character.isJavaIdentifierPart(source.charAt(i))||source.charAt(i)=='-'))i++;tokens.add(new Token(source.substring(start,i),false,start));}
            } else tokens.add(new Token(String.valueOf(c),false,start));
            if(tokens.size()>maxTokens)throw new Limit();
        }
        return new DeclarativeScript(tokens,maxDepth).block(0,false);
    }
    private List<Statement> block(int depth,boolean nested) {
        if(depth>depthLimit)throw new Limit();var result=new ArrayList<Statement>();var head=new ArrayList<Token>();int parens=0,brackets=0;
        while(index<tokens.size()) {
            Token t=tokens.get(index++);String v=t.value();
            if(!t.literal()) {
                if(v.equals("("))parens++;if(v.equals(")"))parens--;if(v.equals("["))brackets++;if(v.equals("]"))brackets--;
                if(parens<0||brackets<0)throw new IllegalArgumentException();
                if(v.equals("{")) {if(head.isEmpty())throw new IllegalArgumentException();result.add(new Statement(List.copyOf(head),block(depth+1,true),head.getFirst().offset()));head.clear();continue;}
                if(v.equals("}")){if(!nested||parens!=0||brackets!=0)throw new IllegalArgumentException();if(!head.isEmpty())result.add(new Statement(List.copyOf(head),List.of(),head.getFirst().offset()));return result;}
                if((v.equals("\n")||v.equals(";"))&&parens==0&&brackets==0){if(!head.isEmpty())result.add(new Statement(List.copyOf(head),List.of(),head.getFirst().offset()));head.clear();continue;}
                if(v.equals("\n"))continue;
            }
            head.add(t);
        }
        if(nested||parens!=0||brackets!=0)throw new IllegalArgumentException();
        if(!head.isEmpty())result.add(new Statement(List.copyOf(head),List.of(),head.getFirst().offset()));return result;
    }
    static final class Limit extends IllegalArgumentException {}
}
