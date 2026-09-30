package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;
import java.util.regex.Pattern;

/** Passive direct factory-method metadata. Unsupported syntax/metadata retains a candidate
 * and a gap; neither method bodies nor annotation processors are executed. */
final class BeanMethodIngestion {
    static final String BEAN="org.springframework.context.annotation.Bean";
    static final String CONFIGURATION="org.springframework.context.annotation.Configuration";
    static final Set<String> CONDITIONS=Set.of("org.springframework.context.annotation.Profile",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate");
    private static final Set<String> METADATA=Set.of(BEAN,"org.springframework.context.annotation.Primary",
            "org.springframework.context.annotation.Fallback","org.springframework.beans.factory.annotation.Qualifier");
    // Only a declaration prefix is inspected, after removing captured annotation spans and
    // comments. Resolved return types come exclusively from the neutral frontend, never this grammar.
    private static final Pattern HEADER=Pattern.compile("^\\s*((?:(?:public|protected|private|static|final|synchronized|strictfp)\\s+)*)"
            +"[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*\\s+"
            +"([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)\\s*\\(");
    private static final Pattern FINAL_PRODUCT=Pattern.compile("^\\s*((?:(?:public|protected|private|static|final|strictfp)\\s+)*)"
            +"class\\s+[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*\\s*\\{");
    record Method(EntityIdentity declaration, EntityIdentity owner, BeanDefinitionCandidate candidate,
                  boolean complete, boolean staticMethod, LogicalValue autowire, LogicalValue defaultCandidate) {}

    static Map<EntityIdentity,Method> acquire(SpringBuildContext build,SpringSourceEvidence source,String container,
            Map<EntityIdentity,BeanDefinitionCandidate> components,List<UniversalSpringEvidence.Issue> issues) {
        var result=new TreeMap<EntityIdentity,Method>();
        for(var declaration:source.declarations().values()) {
            var id=declaration.entity().identity();
            if(declaration.entity().origin()!=EntityOrigin.PROJECT||declaration.entity().kind()!=EntityKind.METHOD)continue;
            var beans=source.annotations(id).stream().filter(a -> a.name().filter(BEAN::equals).isPresent()).toList();
            if(beans.isEmpty())continue;
            var owner=source.owner(id);
            if(owner.isEmpty()||!components.containsKey(owner.orElseThrow())) {
                issue(issues,source,id);continue; // Unplanned inventory obligation remains open.
            }
            var returns=source.frontend().types().stream().filter(t -> t.owner().equals(Optional.of(id))&&t.role().value().equals("java.returns"))
                    .map(TypeUseRecord::type).toList();
            boolean complete=beans.size()==1&&declaration.status()==SemanticStatus.RESOLVED
                    &&source.frontend().state()==FrontendResult.State.COMPLETED
                    &&source.annotations(id).stream().allMatch(a -> a.name().filter(n -> METADATA.contains(n)||CONDITIONS.contains(n)).isPresent());
            String header=declaration.spelling();
            for(var annotation:source.annotations(id))header=header.replace(annotation.use().spelling()," ");
            header=header.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," ").stripLeading();
            var matcher=HEADER.matcher(header);boolean syntax=!declaration.spelling().contains("\\u")&&matcher.find();
            boolean staticMethod=syntax&&Arrays.asList(matcher.group(1).strip().split("\\s+")).contains("static");
            complete&=syntax;
            var names=BeanDefinitionCandidate.Names.unresolved();
            LogicalValue autowire=LogicalValue.UNKNOWN,defaultCandidate=LogicalValue.UNKNOWN;
            try {
                var attributes=LiteralConditionAnnotation.parse(beans.getFirst().use().spelling());
                complete&=Set.of("value","name","autowireCandidate","defaultCandidate","initMethod","destroyMethod").containsAll(attributes.keySet());
                var value=LiteralConditionAnnotation.strings(attributes,"value",List.of());
                var named=LiteralConditionAnnotation.strings(attributes,"name",List.of());
                if(!value.isEmpty()&&!named.isEmpty()&&!value.equals(named))throw new IllegalArgumentException();
                var supplied=value.isEmpty()?named:value;
                // Alias ordering is a separate reader contract; keep multi-name declarations open.
                if(supplied.size()>1||supplied.stream().anyMatch(String::isBlank))throw new IllegalArgumentException();
                if(!supplied.isEmpty())names=BeanDefinitionCandidate.Names.exact(supplied.getFirst());
                else if(syntax) {
                    // Match the frontend's Java identifier equality (JLS 3.8). Annotation
                    // string names remain literal; only the default method-derived name changes.
                    var semanticName=new StringBuilder();
                    matcher.group(2).codePoints().filter(c -> !Character.isIdentifierIgnorable(c)).forEach(semanticName::appendCodePoint);
                    names=BeanDefinitionCandidate.Names.exact(semanticName.toString());
                }
                autowire=LiteralConditionAnnotation.bool(attributes,"autowireCandidate",true)?LogicalValue.TRUE:LogicalValue.FALSE;
                defaultCandidate=LiteralConditionAnnotation.bool(attributes,"defaultCandidate",true)?LogicalValue.TRUE:LogicalValue.FALSE;
                LiteralConditionAnnotation.string(attributes,"initMethod","");
                LiteralConditionAnnotation.string(attributes,"destroyMethod","(inferred)");
            }catch(IllegalArgumentException unsupported){complete=false;}
            // No factory/product, inherited, generic or proxy prediction without metadata evidence.
            complete&=returns.size()==1&&ordinaryReturn(source,returns.getFirst());
            var exposed=returns.stream().flatMap(t -> t.target().stream()).distinct().toList();
            var candidate=new BeanDefinitionCandidate(new BeanProducer(build.identity(),container,source.evidence(id),
                    BeanProducer.Kind.BEAN_METHOD,id.value(),owner.orElseThrow().value()),id.value(),names,exposed);
            result.put(id,new Method(id,owner.orElseThrow(),candidate,complete,staticMethod,autowire,defaultCandidate));
            if(!complete)issue(issues,source,id);
        }
        // Overloaded reader selection is not inferred from method source order.
        var counts=new HashMap<String,Integer>();
        source.declarations().values().stream().filter(d -> d.entity().kind()==EntityKind.METHOD)
                .forEach(d -> source.owner(d.entity().identity()).filter(components::containsKey).ifPresent(owner ->
                        counts.merge(owner.value()+":"+methodName(source,d.entity().identity()),1,Integer::sum)));
        var nameCounts=new HashMap<String,Integer>();
        components.values().forEach(c -> c.declaredNameKey().primary().ifPresent(n -> nameCounts.merge(n,1,Integer::sum)));
        result.values().forEach(m -> m.candidate().declaredNameKey().primary().ifPresent(n -> nameCounts.merge(n,1,Integer::sum)));
        result.replaceAll((id,m) -> {
            boolean collision=m.candidate().declaredNameKey().primary().filter(n -> nameCounts.get(n)>1).isPresent();
            if(!collision&&counts.get(m.owner().value()+":"+methodName(source,id))<2)return m;
            issue(issues,source,id);return new Method(id,m.owner(),m.candidate(),false,m.staticMethod(),m.autowire(),m.defaultCandidate());
        });
        return Collections.unmodifiableMap(result);
    }
    private static String methodName(SpringSourceEvidence source,EntityIdentity id) {
        String name=source.declarations().get(id).entity().canonicalName();
        // Method key ends in the parameter tuple; the owner tuple precedes the method name.
        int depth=0;for(int i="java:v1:[\"method\",".length();i<name.length();i++) {
            char c=name.charAt(i);if(c=='[')depth++;else if(c==']'&&--depth==0)return name.substring(i+1,name.indexOf(",[",i+1));
        }
        return name;
    }
    private static boolean ordinaryReturn(SpringSourceEvidence source,JavaType type) {
        if(type.status()!=SemanticStatus.RESOLVED||type.kind()!=JavaType.Kind.DECLARED||type.target().isEmpty()||!type.components().isEmpty())return false;
        var target=type.target().orElseThrow();
        return source.frontend().typeDeclarations().stream().anyMatch(t -> t.type().equals(target)&&t.kind()==TypeDeclarationRecord.Kind.CLASS)
                &&source.ancestry(target,2).size()==1&&source.annotations(target).isEmpty();
    }
    /** Final ordinary source types close the declared member footprint without evaluating
     * factory bodies. A wider return type cannot prove runtime overrides or additional sites. */
    static boolean productMembersComplete(SpringSourceEvidence source,Method method) {
        if(!method.complete()||method.candidate().exposedTypes().size()!=1)return false;
        var target=method.candidate().exposedTypes().getFirst();
        var declaration=source.declarations().get(target);
        if(declaration==null||declaration.status()!=SemanticStatus.RESOLVED
                ||source.frontend().typeDeclarations().stream().noneMatch(t -> t.type().equals(target)
                    &&t.kind()==TypeDeclarationRecord.Kind.CLASS&&!t.abstractType()))return false;
        var spelling=declaration.spelling();
        if(spelling.contains("\\u"))return false;
        var header=FINAL_PRODUCT.matcher(spelling.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*"," "));
        return header.find()&&Arrays.asList(header.group(1).strip().split("\\s+")).contains("final");
    }
    static boolean configurationMetadata(SpringSourceEvidence source,EntityIdentity type) {
        try {
            for(var annotation:source.annotations(type))if(annotation.name().filter(CONFIGURATION::equals).isPresent()) {
                var attrs=LiteralConditionAnnotation.parse(annotation.use().spelling());
                if(!Set.of("value","proxyBeanMethods","enforceUniqueMethods").containsAll(attrs.keySet()))return false;
                LiteralConditionAnnotation.string(attrs,"value","");
                // Enhanced configuration classes need a separate runtime-type proof.
                if(LiteralConditionAnnotation.bool(attrs,"proxyBeanMethods",true))return false;
                if(!LiteralConditionAnnotation.bool(attrs,"enforceUniqueMethods",true))return false;
            }
            return true;
        }catch(IllegalArgumentException unknown){return false;}
    }
    private static void issue(List<UniversalSpringEvidence.Issue> issues,SpringSourceEvidence source,EntityIdentity id) {
        issues.add(new UniversalSpringEvidence.Issue(UniversalSpringEvidence.Reason.ANNOTATION_UNSUPPORTED,id.value(),List.of(source.evidence(id))));
    }
    private BeanMethodIngestion() {}
}
