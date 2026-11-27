package com.evolution.analysis.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
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
    private static final ThreadLocal<JavaParser> TYPE_PARSER = ThreadLocal.withInitial(() ->
            new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(false).setAttributeComments(false)));
    private RecordPatternInference() {}
    static void prepare(CompilationUnit unit) {
        for(var pattern:unit.findAll(RecordPatternExpr.class)) {
            try {
                var view=pattern.getType().resolve().asReferenceType();
                var typeDeclOpt=view.getTypeDeclaration();
                if(typeDeclOpt.isEmpty())continue;
                var typeDecl=typeDeclOpt.orElseThrow();
                var declaration=typeDecl.toAst().orElse(null);
                List<com.github.javaparser.resolution.types.ResolvedType> componentTypes;
                if(declaration instanceof RecordDeclaration record) {
                    if(record.getParameters().size()!=pattern.getPatternList().size())continue;
                    // Raw/inferred generic record patterns need selector capture inference.
                    // Explicit type arguments can be substituted from captured declarations.
                    if(!record.getTypeParameters().isEmpty()&&pattern.getType().asClassOrInterfaceType().getTypeArguments().map(List::size).orElse(0)!=record.getTypeParameters().size())continue;
                    componentTypes=new ArrayList<>(record.getParameters().size());
                    for(int index=0;index<record.getParameters().size();index++) {
                        var component=record.getParameter(index);
                        var resolved=view.typeParametersMap().replaceAll(component.getType().resolve());
                        if(component.isVarArgs())resolved=new ResolvedArrayType(resolved);
                        componentTypes.add(resolved);
                    }
                } else {
                    // Bytecode record component types directly from ASM RecordAttribute (no AST needed).
                    var bytecodeComponents=com.evolution.analysis.javaparser.asm.AsmTypeSolver.recordComponentTypes(typeDecl);
                    if(bytecodeComponents.isEmpty())continue;
                    var rawTypes=bytecodeComponents.orElseThrow();
                    if(rawTypes.size()!=pattern.getPatternList().size())continue;
                    if(!typeDecl.getTypeParameters().isEmpty()&&pattern.getType().asClassOrInterfaceType().getTypeArguments().map(List::size).orElse(0)!=typeDecl.getTypeParameters().size())continue;
                    componentTypes=new ArrayList<>(rawTypes.size());
                    for(var raw:rawTypes) {
                        componentTypes.add(view.typeParametersMap().replaceAll(raw));
                    }
                }
                var replacements=new LinkedHashMap<TypePatternExpr,Type>();
                for(int index=0;index<componentTypes.size();index++) {
                    if(!(pattern.getPatternList().get(index) instanceof TypePatternExpr binding)||!binding.getType().isVarType())continue;
                    var resolved=componentTypes.get(index);
                    if(resolved.isTypeVariable()||resolved.isWildcard()||resolved.isConstraint())continue;
                    var parsed=TYPE_PARSER.get().parseType(resolved.describe());
                    if(!parsed.isSuccessful()||parsed.getResult().isEmpty())continue;
                    var inferred=parsed.getResult().orElseThrow();
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
