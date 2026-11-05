package com.evolution.analysis.treesitter;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.polyglot.SourceCoordinates;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Passive schema declarations, not generated stubs or compiler/typechecker bindings.
 * Unknown syntax and malformed input retain recovered rows and an explicit document outcome. */
final class ProtobufSourceFrontend {
    static final String PROVIDER="source.protobuf-schema:m8.4-v1";
    private record Token(String text,int start,int end) {}
    private record Draft(String category,String kind,String name,int start,int end,int owner,Map<String,String> attributes) {}
    private final SourceCoordinates coordinates;
    private final String text;
    private final BooleanSupplier cancelled;
    private final List<Draft> rows=new ArrayList<>();
    private Token look;
    private int offset,nodes,errors;
    private String namespace="";
    private ProtobufSourceFrontend(byte[] bytes,BooleanSupplier cancelled) {
        coordinates=new SourceCoordinates(bytes,"UTF-8");text=coordinates.text();this.cancelled=cancelled;
    }
    static TreeSitterSourceFrontend.Summary parse(byte[] bytes,TreeSitterSourceFrontend.Sink sink,BooleanSupplier cancelled)throws IOException {
        var parser=new ProtobufSourceFrontend(bytes,cancelled);
        try{parser.scope("",-1,"file",0);}catch(IllegalArgumentException malformed){parser.errors++;}
        var owners=new HashMap<Integer,String>();
        for(var row:parser.rows)if(row.category().equals("DECLARATION"))owners.put(row.start(),row.start()+":"+row.end());
        for(var row:parser.rows) {
            parser.check();var span=parser.coordinates.range(row.start(),row.end(),SourceCoordinates.Encoding.UTF16);
            String written=parser.text.substring(row.start(),Math.min(row.end(),row.start()+4096));
            sink.accept(new TreeSitterSourceFrontend.Fact(row.category(),row.kind(),row.name(),written,span,
                    owners.getOrDefault(row.owner(),""),parser.errors>0,row.attributes()));
        }
        String tool;
        try(var code=ProtobufSourceFrontend.class.getResourceAsStream("ProtobufSourceFrontend.class")){if(code==null)throw new IOException("PROTOBUF_PARSER_IDENTITY_UNAVAILABLE");tool=ContentDigest.sha256(code.readAllBytes()).value();}
        return new TreeSitterSourceFrontend.Summary(parser.nodes,parser.rows.size(),parser.errors,PROVIDER,tool);
    }
    private int scope(String prefix,int owner,String kind,int depth)throws IOException {
        if(depth>=128)throw new IOException("PROTOBUF_SCHEMA_WORKER_REQUIRED");
        while(!peek().text().isEmpty()) {
            check();var first=peek();String keyword=first.text();
            if(keyword.equals("}")){if(kind.equals("file"))throw malformed();return take().end();}
            if(keyword.equals(";")){take();continue;}
            if(keyword.equals("package")&&kind.equals("file")) {
                take();namespace=qualified();expect(";");continue;
            }
            if(Set.of("message","enum","service","oneof").contains(keyword)) {
                take();String name=identifier();String qualified=join(prefix.isEmpty()?namespace:prefix,name);expect("{");
                int end=scope(keyword.equals("oneof")?prefix:qualified,first.start(),keyword,depth+1);
                add("DECLARATION",keyword,name,first.start(),end,owner,Map.of("qualifiedName",qualified));continue;
            }
            if(keyword.equals("rpc")&&kind.equals("service")) {rpc(prefix,owner);continue;}
            if(keyword.equals("import")&&kind.equals("file")) {
                take();String mode="normal";if(Set.of("public","weak").contains(peek().text()))mode=take().text();
                var path=take();if(path.text().length()<2||path.text().charAt(0)!='"'&&path.text().charAt(0)!='\'')throw malformed();
                int end=expect(";").end();add("IMPORT","import",path.text(),first.start(),end,owner,Map.of("mode",mode));continue;
            }
            boolean metadata=Set.of("syntax","edition","option","reserved","extensions").contains(keyword);
            var tokens=statement();
            if(metadata){add("SCHEMA_METADATA",keyword,keyword,first.start(),tokens.getLast().end(),owner,Map.of());continue;}
            int equals=-1;for(int i=0;i<tokens.size();i++)if(tokens.get(i).text().equals("=")){equals=i;break;}
            if((kind.equals("message")||kind.equals("oneof")||kind.equals("enum"))&&equals>=1&&equals+1<tokens.size()) {
                String name=tokens.get(equals-1).text();
                if(!name.matches("[A-Za-z_][A-Za-z0-9_]*"))throw malformed();
                var attributes=new TreeMap<String,String>();attributes.put("qualifiedName",join(prefix,name));
                String number=tokens.get(equals+1).text();if(number.equals("-")&&equals+2<tokens.size())number+=tokens.get(equals+2).text();
                long value;try{value=Long.decode(number);}catch(NumberFormatException invalid){throw malformed();}
                if(kind.equals("enum")?value<Integer.MIN_VALUE||value>Integer.MAX_VALUE:value<1||value>536870911L||value>=19000&&value<=19999)throw malformed();
                if(tokens.subList(0,equals).stream().anyMatch(t->t.text().equals("group"))){errors++;add("ERROR","unsupported_schema_syntax",keyword,first.start(),tokens.getLast().end(),owner,Map.of());continue;}
                attributes.put("numberSyntax",number);
                attributes.put("typeSyntax",text.substring(first.start(),tokens.get(equals-1).start()).strip());
                add("SCHEMA_FIELD",kind.equals("enum")?"enum_value":"field",name,first.start(),tokens.getLast().end(),owner,attributes);
            } else {errors++;add("ERROR","unsupported_schema_syntax",keyword,first.start(),tokens.getLast().end(),owner,Map.of());}
        }
        if(!kind.equals("file"))throw malformed();return offset;
    }
    private void rpc(String service,int owner)throws IOException {
        var first=expect("rpc");String name=identifier();expect("(");boolean client=accept("stream");String input=qualified();expect(")");
        expect("returns");expect("(");boolean server=accept("stream");String output=qualified();expect(")");
        var ending=statement();
        add("RPC_METHOD","rpc",name,first.start(),ending.getLast().end(),owner,Map.of("qualifiedName",service+"."+name,
                "grpcPath","/"+service+"/"+name,"requestType",input,"responseType",output,
                "clientStreaming",Boolean.toString(client),"serverStreaming",Boolean.toString(server)));
    }
    private List<Token> statement()throws IOException {
        var tokens=new ArrayList<Token>();int braces=0,brackets=0,parentheses=0;
        while(true) {
            var token=take();String value=token.text();if(value.isEmpty())throw malformed();
            tokens.add(token);
            switch(value) {
                case "{" -> braces++;
                case "}" -> {if(--braces<0)throw malformed();if(braces==0&&brackets==0&&parentheses==0)return tokens;}
                case "[" -> brackets++;case "]" -> {if(--brackets<0)throw malformed();}
                case "(" -> parentheses++;case ")" -> {if(--parentheses<0)throw malformed();}
                case ";" -> {if(braces==0&&brackets==0&&parentheses==0)return tokens;}
                default -> {}
            }
            if(braces+brackets+parentheses>128)throw new IOException("PROTOBUF_SCHEMA_WORKER_REQUIRED");
        }
    }
    private void add(String category,String kind,String name,int start,int end,int owner,Map<String,String> attributes) {
        rows.add(new Draft(category,kind,name,start,end,owner,attributes));
    }
    private String identifier()throws IOException {var token=take();if(!token.text().matches("[A-Za-z_][A-Za-z0-9_]*"))throw malformed();return token.text();}
    private String qualified()throws IOException {var result=new StringBuilder();if(accept("."))result.append('.');result.append(identifier());while(accept("."))result.append('.').append(identifier());return result.toString();}
    private static String join(String prefix,String name){return prefix.isEmpty()?name:prefix+"."+name;}
    private boolean accept(String value)throws IOException {if(!peek().text().equals(value))return false;take();return true;}
    private Token expect(String value)throws IOException {var token=take();if(!token.text().equals(value))throw malformed();return token;}
    private Token peek()throws IOException {if(look==null)look=token();return look;}
    private Token take()throws IOException {var token=peek();look=null;return token;}
    private Token token()throws IOException {
        check();nodes++;
        while(offset<text.length()) {
            char c=text.charAt(offset);
            if(Character.isWhitespace(c)||c=='\ufeff'){offset++;continue;}
            if(text.startsWith("//",offset)){offset+=2;while(offset<text.length()&&text.charAt(offset)!='\n'&&text.charAt(offset)!='\r')offset++;continue;}
            if(text.startsWith("/*",offset)){int end=text.indexOf("*/",offset+2);if(end<0)throw malformed();offset=end+2;continue;}
            break;
        }
        int start=offset;if(offset==text.length())return new Token("",offset,offset);
        char c=text.charAt(offset++);
        if(c=='\''||c=='"') {
            boolean closed=false;
            while(offset<text.length()){char next=text.charAt(offset++);if(next=='\\'){if(offset==text.length())throw malformed();offset++;}else if(next==c){closed=true;break;}else if(next=='\n'||next=='\r')throw malformed();}
            if(!closed)throw malformed();
        } else if(Character.isLetterOrDigit(c)||c=='_')while(offset<text.length()&&(Character.isLetterOrDigit(text.charAt(offset))||text.charAt(offset)=='_'))offset++;
        return new Token(text.substring(start,offset),start,offset);
    }
    private void check()throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");}
    private static IllegalArgumentException malformed(){return new IllegalArgumentException("PROTOBUF_SCHEMA_MALFORMED");}
}
