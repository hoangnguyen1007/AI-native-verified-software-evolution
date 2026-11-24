package com.evolution.analysis.graph;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** One bounded parser for String and UTF-8 stream callers; never buffers the whole document. */
final class StreamingGraphJson {
    private final java.io.Reader input;private final String source;private final GraphJson.StreamLimits limits;
    private final char[] buffer;private int position,length;private long characters,values;
    StreamingGraphJson(String source,GraphJson.Limits limits){
        this.input=null;this.source=Objects.requireNonNull(source);this.limits=GraphJson.StreamLimits.from(Objects.requireNonNull(limits));
        this.buffer=null;this.length=source.length();
    }
    StreamingGraphJson(java.io.Reader input,GraphJson.Limits limits){this(input,GraphJson.StreamLimits.from(limits));}
    StreamingGraphJson(java.io.Reader input,GraphJson.StreamLimits limits){
        this.input=Objects.requireNonNull(input);this.source=null;this.limits=Objects.requireNonNull(limits);
        this.buffer=new char[8192];
    }
    private int peek()throws IOException {
        if(source!=null)return position<length?source.charAt(position):-1;
        if(length<0)return -1;
        while(position==length){length=input.read(buffer);position=0;if(length<0)return -1;}
        return buffer[position];
    }
    private int next()throws IOException {int c=peek();if(c>=0){position++;if(++characters>limits.maxCharacters())throw new GraphJson.LimitExceededException("Graph JSON character limit");}return c;}
    private boolean take(char c)throws IOException {if(peek()==c){next();return true;}return false;}
    private void require(char c)throws IOException {if(!take(c))throw new IllegalArgumentException("Invalid graph JSON delimiter");}
    private void whitespace()throws IOException {while(peek()>=0&&" \t\r\n".indexOf(peek())>=0)next();}
    Object read()throws IOException {Object result=value(0);whitespace();if(peek()>=0)throw new IllegalArgumentException("Trailing graph JSON content");return result;}
    void readObject(GraphJson.ObjectConsumer consumer)throws IOException {
        object(consumer,0);whitespace();if(peek()>=0)throw new IllegalArgumentException("Trailing graph JSON content");
    }
    private void spend(int depth){if(depth>limits.maxDepth()||++values>limits.maxValues())throw new GraphJson.LimitExceededException("Graph JSON structural limit");}
    private void object(GraphJson.ObjectConsumer consumer,int depth)throws IOException {
        spend(depth);whitespace();require('{');var seen=new HashSet<String>();whitespace();if(take('}'))return;
        do{whitespace();if(peek()!='"')throw new IllegalArgumentException("Object key required");String key=text();
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate graph JSON key");whitespace();require(':');
            var member=new Member(depth+1);consumer.accept(key,member);if(!member.consumed)throw new IllegalArgumentException("Unconsumed graph member");
            whitespace();if(take('}'))return;require(',');}while(true);
    }
    private void array(GraphJson.ValueConsumer consumer,int depth)throws IOException {
        spend(depth);whitespace();require('[');whitespace();if(take(']'))return;
        do{consumer.accept(value(depth+1));whitespace();if(take(']'))return;require(',');}while(true);
    }
    private final class Member implements GraphJson.MemberReader {
        private final int depth;private boolean consumed;
        Member(int depth){this.depth=depth;}
        private void consume(){if(consumed)throw new IllegalArgumentException("Graph member already consumed");consumed=true;}
        public Object read()throws IOException{consume();return value(depth);}
        public void skip()throws IOException{consume();skipValue(depth);}
        public void object(GraphJson.ObjectConsumer consumer)throws IOException{consume();StreamingGraphJson.this.object(consumer,depth);}
        public void array(GraphJson.ValueConsumer consumer)throws IOException{consume();StreamingGraphJson.this.array(consumer,depth);}
    }
    private void skipValue(int depth)throws IOException {
        whitespace();int c=peek();
        if(c=='{'){object((key,member)->member.skip(),depth);return;}
        if(c=='['){spend(depth);require('[');whitespace();if(take(']'))return;
            do{skipValue(depth+1);whitespace();if(take(']'))return;require(',');}while(true);}
        value(depth);
    }
    private Object value(int depth)throws IOException {
        spend(depth);
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
        try{var number=new BigDecimal(numeric.toString());int maximum=(int)Math.min(4096,limits.maxCharacters());
            if(Math.abs((long)number.scale())>maximum||number.precision()>maximum)throw new GraphJson.LimitExceededException("Graph JSON number expansion limit");return number;
        }catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid graph JSON number");}
    }
    private void digits(StringBuilder value)throws IOException {int start=value.length();while(peek()>='0'&&peek()<='9')value.append((char)next());if(value.length()==start)throw new IllegalArgumentException("Graph JSON digits required");}
    private String text()throws IOException {
        require('"');
        var result=new StringBuilder();
        boolean hasNonAscii=false;
        int maxString=limits.maxStringCharacters();
        while(true){
            if(source!=null){
                int start=position;
                int limit=Math.min(length,start+(maxString-result.length()));
                while(position<limit){
                    char c=source.charAt(position);
                    if(c=='"'||c=='\\'||c<0x20||c>=0x80)break;
                    position++;
                }
                int run=position-start;
                if(run>0){
                    if((characters+=run)>limits.maxCharacters())throw new GraphJson.LimitExceededException("Graph JSON character limit");
                    result.append(source,start,position);
                }
            }else if(position<length){
                int start=position;
                int limit=Math.min(length,start+(maxString-result.length()));
                while(position<limit){
                    char c=buffer[position];
                    if(c=='"'||c=='\\'||c<0x20||c>=0x80)break;
                    position++;
                }
                int run=position-start;
                if(run>0){
                    if((characters+=run)>limits.maxCharacters())throw new GraphJson.LimitExceededException("Graph JSON character limit");
                    result.append(buffer,start,run);
                }
            }
            if(peek()<0)throw new IllegalArgumentException("Unterminated graph JSON string");
            if(result.length()>maxString)throw new GraphJson.LimitExceededException("Graph JSON token limit");
            int c=next();
            if(c=='"'){
                if(hasNonAscii){
                    for(int i=0;i<result.length();i++){char unit=result.charAt(i);if(Character.isHighSurrogate(unit)){if(i+1>=result.length()||!Character.isLowSurrogate(result.charAt(++i)))throw new IllegalArgumentException("Unpaired JSON surrogate");}else if(Character.isLowSurrogate(unit))throw new IllegalArgumentException("Unpaired JSON surrogate");}
                }
                return result.toString();
            }
            if(c<32)throw new IllegalArgumentException("Unescaped graph JSON control");
            if(c!='\\'){if(c>=0x80)hasNonAscii=true;result.append((char)c);continue;}
            int escaped=next();switch(escaped){
                case '"','\\','/'->result.append((char)escaped);case 'b'->result.append('\b');case 'f'->result.append('\f');case 'n'->result.append('\n');case 'r'->result.append('\r');case 't'->result.append('\t');
                case 'u'->{int code=0;for(int i=0;i<4;i++){int digit=Character.digit(next(),16);if(digit<0)throw new IllegalArgumentException("Invalid Unicode escape");code=(code<<4)|digit;}if(code>=0x80)hasNonAscii=true;result.append((char)code);}
                default->throw new IllegalArgumentException("Invalid graph JSON escape");}
        }
    }
}
