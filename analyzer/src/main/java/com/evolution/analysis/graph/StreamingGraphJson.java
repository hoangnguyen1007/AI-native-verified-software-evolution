package com.evolution.analysis.graph;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** One bounded parser for String and UTF-8 stream callers; never buffers the whole document. */
final class StreamingGraphJson {
    private final java.io.Reader input;private final GraphJson.Limits limits;
    private final char[] buffer=new char[8192];private int position,length,characters,values;
    StreamingGraphJson(java.io.Reader input,GraphJson.Limits limits){this.input=Objects.requireNonNull(input);this.limits=Objects.requireNonNull(limits);}
    private int peek()throws IOException {if(length<0)return -1;while(position==length){length=input.read(buffer);position=0;if(length<0)return -1;}return buffer[position];}
    private int next()throws IOException {int c=peek();if(c>=0){position++;if(++characters>limits.maxCharacters())throw new GraphJson.LimitExceededException("Graph JSON character limit");}return c;}
    private boolean take(char c)throws IOException {if(peek()==c){next();return true;}return false;}
    private void require(char c)throws IOException {if(!take(c))throw new IllegalArgumentException("Invalid graph JSON delimiter");}
    private void whitespace()throws IOException {while(peek()>=0&&" \t\r\n".indexOf(peek())>=0)next();}
    Object read()throws IOException {Object result=value(0);whitespace();if(peek()>=0)throw new IllegalArgumentException("Trailing graph JSON content");return result;}
    private Object value(int depth)throws IOException {
        if(depth>limits.maxDepth()||++values>limits.maxValues())throw new GraphJson.LimitExceededException("Graph JSON structural limit");
        whitespace();int c=peek();if(c<0)throw new IllegalArgumentException("Incomplete graph JSON");
        if(c=='"')return text();
        if(c=='{'){
            next();var result=new TreeMap<String,Object>();whitespace();if(take('}'))return result;
            do{whitespace();if(peek()!='"')throw new IllegalArgumentException("Object key required");String key=text();whitespace();require(':');
                if(result.containsKey(key))throw new IllegalArgumentException("Duplicate graph JSON key");result.put(key,value(depth+1));whitespace();if(take('}'))return result;require(',');}while(true);
        }
        if(c=='['){next();var result=new ArrayList<Object>();whitespace();if(take(']'))return result;
            do{result.add(value(depth+1));whitespace();if(take(']'))return result;require(',');}while(true);}
        if(c=='t'||c=='f'||c=='n'){
            String literal=c=='t'?"true":c=='f'?"false":"null";for(int i=0;i<literal.length();i++)if(next()!=literal.charAt(i))throw new IllegalArgumentException("Invalid graph JSON literal");
            return c=='n'?null:c=='t';
        }
        var numeric=new StringBuilder();if(take('-'))numeric.append('-');if(take('0'))numeric.append('0');else digits(numeric);
        if(take('.')){numeric.append('.');digits(numeric);}if(peek()=='e'||peek()=='E'){numeric.append((char)next());if(peek()=='+'||peek()=='-')numeric.append((char)next());digits(numeric);}
        try{var number=new BigDecimal(numeric.toString());int maximum=Math.min(4096,limits.maxCharacters());
            if(Math.abs((long)number.scale())>maximum||number.precision()>maximum)throw new GraphJson.LimitExceededException("Graph JSON number expansion limit");return number;
        }catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid graph JSON number");}
    }
    private void digits(StringBuilder value)throws IOException {int start=value.length();while(peek()>='0'&&peek()<='9')value.append((char)next());if(value.length()==start)throw new IllegalArgumentException("Graph JSON digits required");}
    private String text()throws IOException {
        require('"');var result=new StringBuilder();
        while(peek()>=0){int c=next();if(c=='"'){
                for(int i=0;i<result.length();i++){char unit=result.charAt(i);if(Character.isHighSurrogate(unit)){if(i+1>=result.length()||!Character.isLowSurrogate(result.charAt(++i)))throw new IllegalArgumentException("Unpaired JSON surrogate");}else if(Character.isLowSurrogate(unit))throw new IllegalArgumentException("Unpaired JSON surrogate");}
                return result.toString();}
            if(c<32)throw new IllegalArgumentException("Unescaped graph JSON control");if(c!='\\'){result.append((char)c);continue;}
            int escaped=next();switch(escaped){
                case '"','\\','/'->result.append((char)escaped);case 'b'->result.append('\b');case 'f'->result.append('\f');case 'n'->result.append('\n');case 'r'->result.append('\r');case 't'->result.append('\t');
                case 'u'->{int code=0;for(int i=0;i<4;i++){int digit=Character.digit(next(),16);if(digit<0)throw new IllegalArgumentException("Invalid Unicode escape");code=(code<<4)|digit;}result.append((char)code);}
                default->throw new IllegalArgumentException("Invalid graph JSON escape");}
        }throw new IllegalArgumentException("Unterminated graph JSON string");
    }
}
