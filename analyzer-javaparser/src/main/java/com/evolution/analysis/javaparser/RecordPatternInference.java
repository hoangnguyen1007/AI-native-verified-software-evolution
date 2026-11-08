package com.evolution.analysis.javaparser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import java.util.*;

/** Adapter-local materialization of source-proven record component types. The
 * parser cannot infer VarType inside a record pattern. Original bytes and spans
 * remain authoritative; this never supplies a missing record or generated stub. */
final class RecordPatternInference {
    static final DataKey<Boolean> INFERRED=new DataKey<>() {};
    private RecordPatternInference() {}
    static void prepare(CompilationUnit unit) {
        for(var pattern:unit.findAll(RecordPatternExpr.class)) {
            try {
                var view=pattern.getType().resolve().asReferenceType();
                var declaration=view.getTypeDeclaration().flatMap(d->d.toAst()).orElse(null);
                if(!(declaration instanceof RecordDeclaration record)||record.getParameters().size()!=pattern.getPatternList().size())continue;
                // Raw/inferred generic record patterns need selector capture inference.
                // Explicit type arguments can be substituted from captured declarations.
                if(!record.getTypeParameters().isEmpty()&&pattern.getType().asClassOrInterfaceType().getTypeArguments().map(List::size).orElse(0)!=record.getTypeParameters().size())continue;
                var replacements=new LinkedHashMap<TypePatternExpr,Type>();
                for(int index=0;index<record.getParameters().size();index++) {
                    if(!(pattern.getPatternList().get(index) instanceof TypePatternExpr binding)||!binding.getType().isVarType())continue;
                    var component=record.getParameter(index);var resolved=view.typeParametersMap().replaceAll(component.getType().resolve());
                    if(component.isVarArgs())resolved=new ResolvedArrayType(resolved);
                    if(resolved.isTypeVariable()||resolved.isWildcard()||resolved.isConstraint())continue;
                    var inferred=StaticJavaParser.parseType(resolved.describe());
                    var range=binding.getType().getRange().orElseThrow();inferred.walk(n->n.setRange(range));
                    inferred.setData(INFERRED,true);replacements.put(binding,inferred);
                }
                replacements.forEach(TypePatternExpr::setType);
            }catch(RuntimeException unavailable) {
                if(unavailable instanceof java.util.concurrent.CancellationException)throw unavailable;
                // Original VarType remains, with its original typed observation outcome.
            }
        }
    }
}
