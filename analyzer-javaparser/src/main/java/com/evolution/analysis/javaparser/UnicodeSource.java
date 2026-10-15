package com.evolution.analysis.javaparser;
import java.util.*;

/** JLS 3.3 translation with an exact UTF-16 boundary map into immutable raw input. */
final class UnicodeSource {
    final String text;
    private final int[] raw;
    private final List<Integer> lines=new ArrayList<>();
    private UnicodeSource(String text,int[] raw){
        this.text=text;this.raw=raw;lines.add(0);
        for(int i=0;i<text.length();i++){char c=text.charAt(i);
            if(c=='\r'){if(i+1<text.length()&&text.charAt(i+1)=='\n')i++;lines.add(i+1);}
            else if(c=='\n')lines.add(i+1);}
    }
    static UnicodeSource translate(String input){
        var text=new StringBuilder(input.length());int[] raw=new int[input.length()+1];
        int backslashes=0;boolean previousEscape=false;
        for(int i=0;i<input.length();){
            int start=i;char c=input.charAt(i++);boolean escaped=false;
            if(c=='\\'&&(previousEscape||backslashes%2==0)&&i<input.length()&&input.charAt(i)=='u'){
                while(i<input.length()&&input.charAt(i)=='u')i++;
                if(i+4>input.length())throw new IllegalArgumentException("Invalid Unicode escape");
                int code=0;for(int j=0;j<4;j++){char digit=input.charAt(i++);int value=digit>='0'&&digit<='9'?digit-'0':digit>='a'&&digit<='f'?digit-'a'+10:digit>='A'&&digit<='F'?digit-'A'+10:-1;
                    if(value<0)throw new IllegalArgumentException("Invalid Unicode escape");code=code*16+value;}
                c=(char)code;escaped=true;
            }
            raw[text.length()]=start;text.append(c);backslashes=c=='\\'?backslashes+1:0;previousEscape=escaped;
        }
        raw[text.length()]=input.length();return new UnicodeSource(text.toString(),Arrays.copyOf(raw,text.length()+1));
    }
    int offset(int line,int column){
        if(line<1||line>lines.size()||column<1)throw new IllegalArgumentException("Translated position outside input");
        int value=lines.get(line-1)+column-1,limit=line==lines.size()?text.length():lines.get(line)-1;
        if(value>limit)throw new IllegalArgumentException("Translated column outside line");return value;
    }
    int rawBoundary(int offset){if(offset<0||offset>=raw.length)throw new IllegalArgumentException("Translated boundary outside input");return raw[offset];}
}
