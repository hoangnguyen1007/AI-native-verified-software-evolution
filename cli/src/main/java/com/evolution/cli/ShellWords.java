package com.evolution.cli;
import java.util.*;
/** Only tokenization: preserves Windows backslashes, never expands or executes anything. */
final class ShellWords {
    static List<String> parse(String line){
        var result=new ArrayList<String>();var word=new StringBuilder();char quote=0;boolean started=false;
        for(int i=0;i<line.length();i++){char c=line.charAt(i);
            if(c=='\''||c=='"'){if(quote==0){quote=c;started=true;}else if(quote==c)quote=0;else word.append(c);}
            else if(Character.isWhitespace(c)&&quote==0){if(started){result.add(word.toString());word.setLength(0);started=false;}}
            else{word.append(c);started=true;}}
        if(quote!=0)throw new IllegalArgumentException("Unclosed quote");if(started)result.add(word.toString());return result;
    }
    private ShellWords(){}
}
