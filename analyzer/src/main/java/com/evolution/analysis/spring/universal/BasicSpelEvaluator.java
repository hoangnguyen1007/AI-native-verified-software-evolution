package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import java.math.BigDecimal;
import java.util.*;

/** Passive expression interpreter: no class loading, bean lookup, reflection, method invocation or I/O. */
public final class BasicSpelEvaluator {
    /** Literal property selectors only; dynamic selector names cannot silently shrink the dependency footprint. */
    public static Optional<Set<String>> propertyKeys(String expression) {
        var keys=new TreeSet<String>();var matcher=java.util.regex.Pattern.compile("\\$\\{([^{}]+)}").matcher(expression);int count=0;
        while(matcher.find()) {String body=matcher.group(1);int colon=body.indexOf(':');String key=colon<0?body:body.substring(0,colon);
            if(key.isBlank()||key.contains("${"))return Optional.empty();keys.add(key);count++;}
        int starts=0;for(int p=expression.indexOf("${");p>=0;p=expression.indexOf("${",p+2))starts++;
        return starts==count?Optional.of(Collections.unmodifiableSet(keys)):Optional.empty();
    }
    public record Limits(int maxCharacters,int maxTokens,int maxDepth) {
        public Limits {if(maxCharacters<1||maxTokens<1||maxDepth<1||maxDepth>128)throw new IllegalArgumentException("Invalid expression limits");}
        public static Limits defaults(){return new Limits(16384,4096,64);}
    }
    public record Result(ContentDigest inputIdentity,LogicalValue value,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public static Result evaluate(String expression,Map<String,String> properties,ConditionEvidence evidence,
                                  SnapshotIdentity snapshot,Limits limits) {
        var input=IngestionEvidence.digest(List.of("spring.basic-spel:m4u.2-v1",expression,new TreeMap<>(properties),evidence,limits));
        var issues=new ArrayList<UniversalSpringEvidence.Issue>();LogicalValue truth=LogicalValue.UNKNOWN;
        try {
            String resolved=placeholders(expression,properties,limits,new HashSet<>(),0);
            if(resolved.startsWith("#{")&&resolved.endsWith("}"))resolved=resolved.substring(2,resolved.length()-1);
            var parser=new Parser(resolved,limits);Object value=parser.expression(0,0);parser.skip();
            if(parser.offset!=resolved.length())throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
            if(!(value instanceof Boolean))throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);
            truth=(boolean)value?LogicalValue.TRUE:LogicalValue.FALSE;
        } catch(Failure failure) {issues.add(new UniversalSpringEvidence.Issue(failure.reason,"expression:"+input.value(),List.of(evidence)));}
        return new Result(input,truth,issues,UniversalSpringEvidence.gaps(snapshot,input,issues));
    }
    private static String placeholders(String text,Map<String,String> properties,Limits limits,Set<String> visiting,int depth) {
        if(text.length()>limits.maxCharacters()||depth>=limits.maxDepth())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
        var output=new StringBuilder();
        for(int i=0;i<text.length();) {
            if(!text.startsWith("${",i)){output.append(text.charAt(i++));continue;}
            int start=i+2,end=start,nesting=1;
            for(;end<text.length();end++) {if(text.startsWith("${",end)){nesting++;end++;}else if(text.charAt(end)=='}'&&--nesting==0)break;}
            if(end==text.length())throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);
            String body=text.substring(start,end);int colon=-1,level=0;
            for(int j=0;j<body.length();j++){if(body.startsWith("${",j)){level++;j++;}else if(body.charAt(j)=='}')level--;else if(body.charAt(j)==':'&&level==0){colon=j;break;}}
            String key=placeholders(colon<0?body:body.substring(0,colon),properties,limits,visiting,depth+1);
            if(!visiting.add(key))throw new Failure(UniversalSpringEvidence.Reason.PROPERTY_UNRESOLVED);
            String value=properties.get(key);if(value==null&&colon>=0)value=body.substring(colon+1);
            if(value==null)throw new Failure(UniversalSpringEvidence.Reason.PROPERTY_UNRESOLVED);
            output.append(placeholders(value,properties,limits,visiting,depth+1));visiting.remove(key);i=end+1;
            if(output.length()>limits.maxCharacters())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
        }
        return output.toString();
    }
    private static final class Failure extends RuntimeException {
        final UniversalSpringEvidence.Reason reason;Failure(UniversalSpringEvidence.Reason reason){this.reason=reason;}
    }
    private static final class Parser {
        final String text;final Limits limits;int offset,tokens;
        Parser(String text,Limits limits){this.text=text;this.limits=limits;}
        void skip(){while(offset<text.length()&&Character.isWhitespace(text.charAt(offset)))offset++;}
        void tick(int depth){if(++tokens>limits.maxTokens()||depth>=limits.maxDepth())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);}
        Object expression(int min,int depth) {
            tick(depth);Object left=primary(depth+1);
            while(true) {
                skip();int save=offset;String op=operator();int precedence=precedence(op);
                if(precedence<min||precedence==0){offset=save;return left;}
                Object right=expression(precedence+1,depth+1);left=apply(op,left,right);
            }
        }
        Object primary(int depth) {
            tick(depth);skip();if(offset==text.length())throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);
            char c=text.charAt(offset++);
            if(c=='('){Object value=expression(0,depth+1);skip();if(offset==text.length()||text.charAt(offset++)!=')')throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);return value;}
            if(c=='!')return !bool(primary(depth+1));
            if(c=='-')return checked(number(primary(depth+1)).negate());
            if(c=='+')return number(primary(depth+1));
            if(c=='\''||c=='"') {
                var value=new StringBuilder();
                while(offset<text.length()) {char next=text.charAt(offset++);if(next==c){if(offset<text.length()&&text.charAt(offset)==c){offset++;value.append(c);}else return value.toString();}else value.append(next);}
                throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);
            }
            if(Character.isDigit(c)) {
                int start=offset-1;while(offset<text.length()&&(Character.isDigit(text.charAt(offset))||text.charAt(offset)=='.'))offset++;
                try{String token=text.substring(start,offset);if(token.contains("."))throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
                    return checked(new BigDecimal(token));}catch(NumberFormatException invalid){throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);}
            }
            if(Character.isJavaIdentifierStart(c)) {
                int start=offset-1;while(offset<text.length()&&Character.isJavaIdentifierPart(text.charAt(offset)))offset++;
                return switch(text.substring(start,offset).toLowerCase(Locale.ROOT)) {
                    case "true"->true;case "false"->false;case "null"->null;case "not"->!bool(primary(depth+1));
                    default->throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
                };
            }
            throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
        }
        String operator() {
            for(String op:List.of("&&","||","==","!=","<=",">=","<",">","+","-","*","/","%"))if(text.startsWith(op,offset)){offset+=op.length();return op;}
            int start=offset;while(offset<text.length()&&Character.isLetter(text.charAt(offset)))offset++;
            return text.substring(start,offset).toLowerCase(Locale.ROOT);
        }
        int precedence(String op){return switch(op){case "||","or"->1;case "&&","and"->2;case "==","!=","eq","ne","<",">","<=",">=","lt","gt","le","ge"->3;case "+","-"->4;case "*","/","%","div","mod"->5;default->0;};}
        boolean bool(Object value){if(value instanceof Boolean b)return b;throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);}
        BigDecimal number(Object value){if(value instanceof BigDecimal n)return n;throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);}
        Object apply(String op,Object left,Object right) {
            return switch(op) {
                case "||","or" -> bool(left)|bool(right);case "&&","and" -> bool(left)&bool(right);
                case "==","eq" -> equal(left,right);case "!=","ne" -> !equal(left,right);
                case "<","lt" -> compare(left,right)<0;case ">","gt" -> compare(left,right)>0;
                case "<=","le" -> compare(left,right)<=0;case ">=","ge" -> compare(left,right)>=0;
                case "+" -> left instanceof String&&right instanceof String?(String)left+right:checked(number(left).add(number(right)));
                case "-" -> checked(number(left).subtract(number(right)));case "*" -> checked(number(left).multiply(number(right)));
                // Integer/floating coercion differs across SpEL numeric types. Avoid fabricating division results.
                case "/","div","%","mod" -> throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
                default -> throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
            };
        }
        BigDecimal checked(BigDecimal number){if(number.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE))<0||number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE))>0)
            throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);return number;}
        boolean equal(Object a,Object b){return a instanceof BigDecimal x&&b instanceof BigDecimal y?x.compareTo(y)==0:Objects.equals(a,b);}
        int compare(Object a,Object b){if(a instanceof BigDecimal x&&b instanceof BigDecimal y)return x.compareTo(y);if(a instanceof String x&&b instanceof String y)return x.compareTo(y);throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);}
    }
    private BasicSpelEvaluator() {}
}
