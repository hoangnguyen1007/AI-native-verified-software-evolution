package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import java.util.*;

/** Bounded Java language rules. Generated AST nodes never become source declarations. */
final class ImplicitExtraction {
    private final Extraction context;
    ImplicitExtraction(Extraction context) { this.context=context; }
    void declarations(CompilationUnit unit) {
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            try {
                var symbol=context.name(type);
                if (type instanceof RecordDeclaration record) record(record);
                else if (type instanceof ClassOrInterfaceDeclaration c && !c.isInterface() && c.getConstructors().isEmpty()
                        || type instanceof EnumDeclaration e && e.getConstructors().isEmpty())
                    context.implicit(JavaSymbolName.constructor(symbol,List.of()),EntityKind.CONSTRUCTOR,type,"java.default-constructor");
                if (type instanceof EnumDeclaration) {
                    context.implicit(JavaSymbolName.method(symbol,"values",List.of()),EntityKind.METHOD,type,"java.enum-members");
                    context.implicit(JavaSymbolName.method(symbol,"valueOf",List.of(ErasedType.declared(JavaSymbolName.topLevelType("java.lang","String")))),EntityKind.METHOD,type,"java.enum-members");
                }
            } catch (RuntimeException failure) { context.implicitFailure(type,failure); }
        }
    }
    void relationships(CompilationUnit unit) {
        for (var constant : unit.findAll(EnumConstantDeclaration.class)) {
            try {
                if (!constant.getClassBody().isEmpty()) {
                    context.observe(constant,"declares",() -> context.enumBody(constant),() -> context.entity(constant));
                    context.derive(context.enumBody(constant),context.entity(constant.getParentNode().orElseThrow()),"extends",constant,"java.enum-constant-subclass");
                }
                context.derive(context.enumInitializer(constant),context.enumConstructor(constant),"constructor-calls",constant,"java.enum-constant-construction");
            } catch (RuntimeException failure) { context.implicitFailure(constant,failure); }
        }
        for (var record : unit.findAll(RecordDeclaration.class)) {
            try {
                var symbol=context.name(record);
                var constructorName=JavaSymbolName.constructor(symbol,context.parameters(record.getParameters()));
                var constructor=context.existing(constructorName);
                for (int i=0;i<record.getParameters().size();i++) {
                    var component=record.getParameter(i);
                    var field=context.existing(JavaSymbolName.field(symbol,component.getNameAsString()));
                    var accessor=context.existing(JavaSymbolName.method(symbol,component.getNameAsString(),List.of()));
                    var parameter=context.existing(JavaSymbolName.parameter(constructorName,i));
                    context.derivedComponentTypes(component,field,"field-type");
                    if (context.isImplicit(accessor)) {
                        context.derivedComponentTypes(component,accessor,"returns");
                        context.derive(accessor,field,"reads-field",component,"java.record-component-accessor");
                    }
                    if (context.isImplicit(parameter)) context.derivedComponentTypes(component,parameter,"parameter-type");
                    if (context.isImplicit(constructor) || !record.getCompactConstructors().isEmpty())
                        context.derive(constructor,field,"writes-field",component,"java.record-component-initialization");
                }
            } catch (RuntimeException failure) { context.implicitFailure(record,failure); }
        }
    }
    private void record(RecordDeclaration record) {
        var symbol=context.name(record);
        var constructorName=JavaSymbolName.constructor(symbol,context.parameters(record.getParameters()));
        var constructor=context.implicit(constructorName,EntityKind.CONSTRUCTOR,record,"java.record-canonical-constructor");
        for (int i=0;i<record.getParameters().size();i++) {
            var component=record.getParameter(i);
            context.implicit(JavaSymbolName.field(symbol,component.getNameAsString()),EntityKind.FIELD,context.entity(record),component,"java.record-component-field");
            context.implicit(JavaSymbolName.method(symbol,component.getNameAsString(),List.of()),EntityKind.METHOD,context.entity(record),component,"java.record-component-accessor");
            var parameter=context.implicit(JavaSymbolName.parameter(constructorName,i),EntityKind.PARAMETER,constructor,component,"java.record-constructor-parameter");
            if (context.isImplicit(parameter)) context.derive(constructor,parameter,"has-parameter",component,"java.record-constructor-parameter");
        }
        context.implicit(JavaSymbolName.method(symbol,"equals",List.of(ErasedType.declared(JavaSymbolName.topLevelType("java.lang","Object")))),EntityKind.METHOD,record,"java.record-object-methods");
        context.implicit(JavaSymbolName.method(symbol,"hashCode",List.of()),EntityKind.METHOD,record,"java.record-object-methods");
        context.implicit(JavaSymbolName.method(symbol,"toString",List.of()),EntityKind.METHOD,record,"java.record-object-methods");
    }
}
