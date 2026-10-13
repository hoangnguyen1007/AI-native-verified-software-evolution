package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.JavaType;
import com.evolution.analysis.spring.binding.InjectionBindingPlan.Shape;
import java.util.*;

/** Passive wrapper/element normalization. Names are accepted only with exact origin evidence.
 * Nested wrappers, generic element substitutions and custom containers stay incomplete. */
final class SourceDependencyShape {
    record Descriptor(Shape shape,JavaType direct,Optional<JavaType> element,boolean complete) {}
    static Descriptor describe(SpringSourceEvidence source,JavaType type,boolean resource) {
        if(resource)return new Descriptor(Shape.SINGLE,type,Optional.empty(),scalar(type));
        if(type.kind()==JavaType.Kind.ARRAY) {
            var element=type.components().getFirst();
            return new Descriptor(Shape.ARRAY,type,Optional.of(element),scalar(element));
        }
        if(type.kind()!=JavaType.Kind.DECLARED||type.status()!=SemanticStatus.RESOLVED)
            return new Descriptor(Shape.UNSUPPORTED,type,Optional.empty(),false);
        var target=type.target().orElseThrow();String name=source.typeName(target);
        Shape shape=switch(name==null?"":name) {
            case "java.util.Optional" -> Shape.OPTIONAL;
            case "java.util.List" -> Shape.LIST;
            case "java.util.Collection" -> Shape.COLLECTION;
            case "java.util.Set" -> Shape.SET;
            case "java.util.Map" -> Shape.STRING_MAP;
            case "org.springframework.beans.factory.ObjectProvider" -> Shape.OBJECT_PROVIDER;
            case "org.springframework.beans.factory.ObjectFactory" -> Shape.OBJECT_FACTORY;
            case "jakarta.inject.Provider" -> Shape.JAKARTA_PROVIDER;
            case "javax.inject.Provider" -> Shape.UNSUPPORTED;
            default -> Shape.SINGLE;
        };
        if(shape==Shape.SINGLE)return new Descriptor(shape,type,Optional.empty(),scalar(type));
        var declaration=source.declarations().get(target);
        boolean identity=declaration!=null&&(name.startsWith("java.")?declaration.entity().origin()==EntityOrigin.JDK:
                source.artifact(target).filter(a -> name.startsWith("org.springframework.")?
                        a.coordinate().equals("org.springframework:spring-beans:6.2.0")&&source.framework().verifiedArtifact(a):
                        a.coordinate().equals("jakarta.inject:jakarta.inject-api:2.0.1")&&a.contentDigest().value()
                                .equals("sha256:f7dc98062fccf14126abb751b64fab12c312566e8cbdc8483598bffcea93af7c")).isPresent());
        int count=shape==Shape.STRING_MAP?2:1;
        if(!identity||type.components().size()!=count)return new Descriptor(shape,type,Optional.empty(),false);
        var element=type.components().getLast();boolean complete=scalar(element);
        if(shape==Shape.STRING_MAP) {
            var key=type.components().getFirst();
            complete&=scalar(key)&&key.target().map(source.declarations()::get).filter(Objects::nonNull)
                    .filter(d -> d.entity().origin()==EntityOrigin.JDK&&"java.lang.String".equals(source.typeName(d.entity().identity()))).isPresent();
        }
        boolean nested=shape==Shape.OPTIONAL||shape==Shape.OBJECT_PROVIDER||shape==Shape.OBJECT_FACTORY||shape==Shape.JAKARTA_PROVIDER;
        return new Descriptor(shape,nested?element:type,nested?Optional.empty():Optional.of(element),complete);
    }
    private static boolean scalar(JavaType type) {
        return type.kind()==JavaType.Kind.DECLARED&&type.status()==SemanticStatus.RESOLVED&&type.components().isEmpty()
                &&type.qualifier().map(SourceDependencyShape::scalar).orElse(true);
    }
    private SourceDependencyShape() {}
}
