package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import java.util.*;
import org.springframework.expression.*;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.SpelCompilerMode;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.ast.*;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/** Passive expression interpreter: no class loading, bean lookup, reflection, method invocation or I/O. */
public final class BasicSpelEvaluator {
    public static final VersionedIdentifier POLICY=new VersionedIdentifier("spring.basic-spel","m8.4-native-literals-v2");
    public static final VersionedIdentifier ENGINE=new VersionedIdentifier("spring-expression","6.2.0");
    /** Literal property selectors only; dynamic selector names cannot silently shrink the dependency footprint. */
    public static Optional<Set<String>> propertyKeys(String expression) {
        var keys=new TreeSet<String>();
        return expression.length()<=Limits.defaults().maxCharacters()&&propertyKeys(expression,keys,0)
                ?Optional.of(Collections.unmodifiableSet(keys)):Optional.empty();
    }
    private static boolean propertyKeys(String text,Set<String> keys,int depth) {
        if(depth>=Limits.defaults().maxDepth())return false;
        for(int i=0;i<text.length();) {
            int start=text.indexOf("${",i);if(start<0)return true;
            int end=start+2,level=1,colon=-1;
            for(;end<text.length();end++) {
                if(text.startsWith("${",end)){level++;end++;}
                else if(text.charAt(end)=='}'&&--level==0)break;
                else if(text.charAt(end)==':'&&level==1&&colon<0)colon=end;
            }
            if(end==text.length())return false;
            String key=text.substring(start+2,colon<0?end:colon);
            if(key.isBlank()||key.contains("${"))return false;
            keys.add(key);
            // Include every possible default dependency, retaining the finite-space denominator.
            if(colon>=0&&!propertyKeys(text.substring(colon+1,end),keys,depth+1))return false;
            i=end+1;
        }
        return true;
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
        var input=IngestionEvidence.digest(List.of(POLICY,ENGINE,expression,new TreeMap<>(properties),evidence,limits));
        var issues=new ArrayList<UniversalSpringEvidence.Issue>();LogicalValue truth=LogicalValue.UNKNOWN;
        try {
            String resolved=placeholders(expression,properties,limits,new HashSet<>(),0);
            if(resolved.startsWith("#{")&&resolved.endsWith("}"))resolved=resolved.substring(2,resolved.length()-1);
            preflight(resolved,limits);
            var parsed=new SpelExpressionParser(new SpelParserConfiguration(SpelCompilerMode.OFF,null,false,false,0,limits.maxCharacters())).parseRaw(resolved);
            validate(parsed.getAST(),limits);
            Object value=parsed.getValue(SimpleEvaluationContext.forReadOnlyDataBinding().build());
            if(!(value instanceof Boolean))throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_INVALID);
            truth=(boolean)value?LogicalValue.TRUE:LogicalValue.FALSE;
        } catch(Failure failure) {issues.add(new UniversalSpringEvidence.Issue(failure.reason,"expression:"+input.value(),List.of(evidence)));}
        catch(ExpressionException invalid) {issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EXPRESSION_INVALID,"expression:"+input.value(),List.of(evidence)));}
        catch(ArithmeticException invalid) {issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.EXPRESSION_INVALID,"expression:"+input.value(),List.of(evidence)));}
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
        if(output.length()>limits.maxCharacters())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
        return output.toString();
    }
    private static final class Failure extends RuntimeException {
        final UniversalSpringEvidence.Reason reason;Failure(UniversalSpringEvidence.Reason reason){this.reason=reason;}
    }
    /** Bound native parser recursion before AST construction (parentheses are not AST nodes). */
    private static void preflight(String text,Limits limits) {
        int nesting=0,conditionals=0,unary=0,tokens=0;
        for(int i=0;i<text.length();) {
            char c=text.charAt(i++);if(Character.isWhitespace(c))continue;
            if(++tokens>limits.maxTokens())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
            if(c=='\''||c=='"') {
                while(i<text.length()){if(text.charAt(i++)==c){if(i<text.length()&&text.charAt(i)==c)i++;else break;}}
                unary=0;continue;
            }
            if(c=='('||c=='['||c=='{')nesting++;
            else if(c==')'||c==']'||c=='}')nesting--;
            if(c=='?')conditionals++;
            if(c=='!'||c=='+'||c=='-')unary++;
            else if(Character.isJavaIdentifierStart(c)) {
                int start=i-1;while(i<text.length()&&Character.isJavaIdentifierPart(text.charAt(i)))i++;
                unary=text.substring(start,i).equalsIgnoreCase("not")?unary+1:0;
            }else unary=0;
            if(nesting>=limits.maxDepth()||conditionals>=limits.maxDepth()||unary>=limits.maxDepth())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
        }
    }
    /** Validate the entire AST before evaluating any branch. Only scalar literal
     * operations are admitted: no target classes, beans, properties, methods,
     * constructors, variables, collections, regex, assignment or reflection. */
    private static void validate(SpelNode root,Limits limits) {
        record Pending(SpelNode node,int depth) {}
        var pending=new ArrayDeque<Pending>();pending.push(new Pending(root,0));int count=0;
        while(!pending.isEmpty()) {
            var next=pending.pop();var node=next.node();
            if(++count>limits.maxTokens()||next.depth()>=limits.maxDepth())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
            if(!(node instanceof BooleanLiteral||node instanceof NullLiteral||node instanceof StringLiteral
                    ||node instanceof IntLiteral||node instanceof LongLiteral||node instanceof FloatLiteral||node instanceof RealLiteral
                    ||node instanceof OpAnd||node instanceof OpOr||node instanceof OperatorNot
                    ||node instanceof OpEQ||node instanceof OpNE||node instanceof OpLT||node instanceof OpGT||node instanceof OpLE||node instanceof OpGE
                    ||node instanceof OpPlus||node instanceof OpMinus||node instanceof OpMultiply||node instanceof OpDivide||node instanceof OpModulus
                    ||node instanceof Ternary||node instanceof Elvis))throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
            // SpEL also defines string repetition. Do not allocate attacker-sized strings.
            if(node instanceof OpMultiply&&(!numeric(node.getChild(0),0,limits)||!numeric(node.getChild(1),0,limits)))
                throw new Failure(UniversalSpringEvidence.Reason.EXPRESSION_UNSUPPORTED);
            for(int i=0;i<node.getChildCount();i++)pending.push(new Pending(node.getChild(i),next.depth()+1));
        }
    }
    private static boolean numeric(SpelNode node,int depth,Limits limits) {
        if(depth>=limits.maxDepth())throw new Failure(UniversalSpringEvidence.Reason.RESOURCE_LIMIT);
        if(node instanceof IntLiteral||node instanceof LongLiteral||node instanceof FloatLiteral||node instanceof RealLiteral)return true;
        if(!(node instanceof OpPlus||node instanceof OpMinus||node instanceof OpMultiply||node instanceof OpDivide||node instanceof OpModulus))return false;
        for(int i=0;i<node.getChildCount();i++)if(!numeric(node.getChild(i),depth+1,limits))return false;
        return true;
    }
    private BasicSpelEvaluator() {}
}
