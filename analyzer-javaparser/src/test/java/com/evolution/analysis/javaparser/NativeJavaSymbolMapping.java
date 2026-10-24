package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.execution.javac.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.TypeParameter;
import java.io.IOException;
import java.util.*;

/** Fixed native cohort's source/lexical and exact-artifact mapping. No package heuristics.
 * Test scope only: this does not register a production semantic validation method.
 */
final class NativeJavaSymbolMapping {
    record Result(Map<String,Object> report,List<Map<String,Object>> rows){}
    private record Parsed(SourceInput input,CompilationUnit ast,OriginalSource source,Map<SourceSpan,List<Node>> positions){}
    private final FrontendRequest request;private final FrontendResult primary;private final CapturedJavacReader.Capture capture;
    private final Map<SourceDocumentIdentity,String> uris;private final List<NativeContextValidator.Slot> slots;
    private final Map<String,Parsed> parsed=new TreeMap<>();private final Map<EntityIdentity,Entity> entities=new HashMap<>();
    private final Map<EntityIdentity,Node> entityNodes=new HashMap<>();private final Map<ContentDigest,Entity> nativeSource=new HashMap<>();
    private final Map<String,Entity> nativeTypes=new HashMap<>();private final Map<String,RelationshipOccurrence> explicit=new HashMap<>();
    private final Set<String> duplicate=new HashSet<>();private final Map<String,List<Map<String,Object>>> nativeSites=new HashMap<>();
    NativeJavaSymbolMapping(CapturedJavacReader.Capture capture,FrontendRequest request,FrontendResult primary,
            Map<SourceDocumentIdentity,String> uris,List<NativeContextValidator.Slot> slots)throws IOException {
        this.capture=capture;this.request=request;this.primary=primary;this.uris=Map.copyOf(uris);this.slots=List.copyOf(slots);
        new OccurrenceCensusVerifier().verify(capture,primary,uris,request.sources());
        for(var input:request.sources()){
            var translated=UnicodeSource.translate(input.text());var parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21).setTabSize(1).setPreprocessUnicodeEscapes(false));
            var result=parser.parse(translated.text);if(!result.isSuccessful()||result.getResult().isEmpty())throw new IOException("NATIVE_MAPPING_SOURCE_PARSE");
            var source=new OriginalSource(input.document().identity(),input.text(),translated);var positions=new HashMap<SourceSpan,List<Node>>();var ast=result.getResult().orElseThrow();
            for(var node:ast.stream().filter(n->n.getRange().isPresent()).toList())positions.computeIfAbsent(source.span(node),k->new ArrayList<>()).add(node);
            parsed.put(uris.get(input.document().identity()),new Parsed(input,ast,source,positions));
        }
        primary.declarations().forEach(d->{entities.put(d.entity().identity(),d.entity());var entity=d.entity();if(entity.declaration().isPresent()){
            var span=entity.declaration().orElseThrow();var unit=parsed.get(uris.get(span.document()));if(unit!=null){var nodes=unit.positions().getOrDefault(span,List.of()).stream().filter(n->kind(n,entity.kind())).toList();if(nodes.size()==1)entityNodes.put(entity.identity(),nodes.getFirst());}
        }});
        for(var occurrence:primary.occurrences()){String key=site(uris.get(occurrence.span().document()),occurrence.relationship().kind().value(),occurrence.span());if(explicit.putIfAbsent(key,occurrence)!=null)duplicate.add(key);}
        for(var row:capture.observations())nativeSites.computeIfAbsent(site(row),k->new ArrayList<>()).add(row);
        for(var row:capture.observations()){
            if(!"java.declares".equals(row.get("category"))||row.get("target")==null||row.get("span")==null||duplicate.contains(site(row)))continue;
            var occurrence=explicit.get(site(row));if(occurrence==null||occurrence.status()!=SemanticStatus.RESOLVED||!(occurrence.relationship().target() instanceof RelationshipTarget.Resolved resolved))continue;
            var entity=entities.get(resolved.target());var nativeElement=GraphJson.object(row.get("target"));var unit=parsed.get(row.get("source"));
            if(entity.origin()!=EntityOrigin.PROJECT||!entity.stableScope().equals(EntityScope.project(unit.input().document().module()))||!Objects.equals(nativeElement.get("artifact"),row.get("source")))continue;
            var node=entityNodes.get(entity.identity());if(node==null||!matchesSourceDeclaration(nativeElement,entity,node,unit.input(),unit.source()))continue;
            var key=CanonicalJson.sha256(nativeElement);var old=nativeSource.putIfAbsent(key,entity);if(old!=null&&!old.equals(entity))throw new IOException("NATIVE_MAPPING_CONFLICT");
            if(entity.kind()==EntityKind.TYPE){var prior=nativeTypes.putIfAbsent(GraphJson.string(nativeElement.get("owner")),entity);if(prior!=null&&!prior.equals(entity))throw new IOException("NATIVE_MAPPING_TYPE_CONFLICT");}
        }
    }
    Result compare()throws IOException {
        var base=GraphJson.object(new OccurrenceCensusVerifier().verify(capture,primary,uris,request.sources()).report().get("endpointComparison"));
        var named=new HashMap<String,Map<String,Object>>();for(Object value:GraphJson.array(base.get("sites"))){var row=GraphJson.object(value);named.put(GraphJson.string(row.get("site")),row);}
        var rows=new ArrayList<Map<String,Object>>();long pendingTargets=0,pendingCallers=0,pendingOrigins=0,compared=0,errors=0;
        for(var observation:primary.observations()){
            if(observation.span().isEmpty()||observation.mappedOccurrence().isEmpty())continue;
            var span=observation.span().orElseThrow();String key=site(uris.get(span.document()),observation.category().value(),span);
            var nativeRows=nativeSites.getOrDefault(key,List.of());var occurrence=explicit.get(key);
            if(nativeRows.size()!=1||duplicate.contains(key)||occurrence==null)throw new IOException("NATIVE_MAPPING_SITE_NOT_UNIQUE");
            var raw=nativeRows.getFirst();var source=entities.get(occurrence.relationship().source());Entity target=occurrence.relationship().target() instanceof RelationshipTarget.Resolved r?entities.get(r.target()):null;
            boolean error=capture.state().equals("ERROR")||!Set.of("RESOLVED","SYNTAX_ONLY").contains(raw.get("binding"))||occurrence.status()!=SemanticStatus.RESOLVED;
            var nTarget=raw.get("target")==null?null:GraphJson.object(raw.get("target"));var nCaller=raw.get("caller")==null?null:GraphJson.object(raw.get("caller"));
            var namedRow=named.getOrDefault(key,Map.of());
            boolean targetMapped=!error&&(sameSource(nTarget,target)||constructor(nTarget,target)||"AGREES_NAMED_JVM_SIGNATURE".equals(namedRow.get("targetSignature"))||syntax(raw,target));
            boolean callerMapped=!error&&(sameSource(nCaller,source)||lexical(raw,source,nCaller)||"AGREES_NAMED_JVM_SIGNATURE".equals(namedRow.get("callerSignature")));
            boolean origins=!error&&origin(nTarget,target,raw)&&origin(nCaller,source,raw);
            if(error)errors++;else{compared++;if(!targetMapped)pendingTargets++;if(!callerMapped)pendingCallers++;if(!origins)pendingOrigins++;}
            var out=new TreeMap<String,Object>();out.put("schema","java-native-symbol-mapping-row:m8.4-v1");out.put("category",observation.category().value());out.put("span",span);out.put("source",raw.get("source"));out.put("sourceContent",capture.context().sources().get(raw.get("source")));
            out.put("nativeObservation",CanonicalJson.sha256(raw));out.put("caller",source.identity());out.put("target",target==null?null:target.identity());out.put("callerMapped",callerMapped);out.put("targetMapped",targetMapped);out.put("originMapped",origins);out.put("provenanceValid",observation.provenance()==ObservationRecord.EvidenceState.VERIFIED);
            out.put("disposition",error?"ERROR_OR_NONRESOLVED":targetMapped&&callerMapped&&origins?"MAPPED":"MAPPING_REQUIRED");rows.add(Collections.unmodifiableMap(out));
        }
        var report=new TreeMap<String,Object>();report.put("schema","java-native-symbol-mapping:m8.4-v1");report.put("capture",capture.document());report.put("frontendResult",CanonicalJson.sha256(primary));report.put("sourceDeclarationMappings",nativeSource.size());report.put("compared",compared);report.put("pendingTargets",pendingTargets);report.put("pendingCallers",pendingCallers);report.put("pendingOrigins",pendingOrigins);report.put("errorOrNonresolved",errors);report.put("rows",List.copyOf(rows));report.put("qualifiedProductionMethod",false);
        return new Result(Collections.unmodifiableMap(report),List.copyOf(rows));
    }
    private boolean sameSource(Map<String,Object> element,Entity entity){return element!=null&&entity!=null&&entity.equals(nativeSource.get(CanonicalJson.sha256(element)));}
    private boolean constructor(Map<String,Object> element,Entity target){
        if(element==null||target==null||target.kind()!=EntityKind.CONSTRUCTOR||!"CONSTRUCTOR".equals(element.get("kind"))||!"()V".equals(element.get("descriptor")))return false;
        var owner=nativeTypes.get(element.get("owner"));if(owner==null)return false;var tuple=tuple(target);
        return tuple.getFirst().equals("constructor")&&tuple.get(1).equals(tuple(owner))&&GraphJson.array(tuple.get(2)).isEmpty()
                &&primary.declarations().stream().anyMatch(d->d.entity().equals(target)&&d.derivation().kind()==DerivationKind.DERIVED);
    }
    private boolean lexical(Map<String,Object> raw,Entity caller,Map<String,Object> nativeCaller){
        var node=entityNodes.get(caller.identity());var unit=parsed.get(raw.get("source"));if(node==null||unit==null)return false;
        if(caller.kind()==EntityKind.PACKAGE)return packageOwner(nativeCaller,caller,unit);
        var parent=nativeCaller==null?null:nativeSource.get(CanonicalJson.sha256(nativeCaller));var key=tuple(caller);
        if(caller.kind()==EntityKind.PARAMETER||caller.kind()==EntityKind.TYPE_PARAMETER){
            if(parent==null||!key.get(1).equals(tuple(parent)))return false;
            return contains(unit.source().span(node),GraphJson.object(raw.get("span")));
        }
        if(caller.kind()==EntityKind.INITIALIZER||caller.kind()==EntityKind.LAMBDA){
            if(key.size()<5||!key.get(2).equals(unit.input().document().identity().value())||number(key.get(3))!=unit.source().start(node))return false;
            if(!contains(unit.source().span(node),GraphJson.object(raw.get("span"))))return false;
            if(parent!=null&&key.get(1).equals(tuple(parent)))return true;
            for(Object value:GraphJson.array(raw.get("enclosingScopes"))){var scope=GraphJson.object(value);if(number(scope.get("startOffset"))==unit.source().start(node)&&number(scope.get("endOffset"))==unit.source().start(node)+unit.source().slice(unit.source().span(node)).length())return true;}
        }
        return false;
    }
    private boolean syntax(Map<String,Object> row,Entity target){
        if(target==null||!"SYNTAX_ONLY".equals(row.get("binding"))||!"java.declares".equals(row.get("category")))return false;
        var node=entityNodes.get(target.identity());var unit=parsed.get(row.get("source"));if(node==null||!Set.of(EntityKind.LAMBDA,EntityKind.INITIALIZER).contains(target.kind()))return false;
        var t=tuple(target);return t.get(2).equals(unit.input().document().identity().value())&&number(t.get(3))==unit.source().start(node)&&sameSpan(unit.source().span(node),GraphJson.object(row.get("span")));
    }
    private boolean origin(Map<String,Object> element,Entity entity,Map<String,Object> row){
        if(entity==null)return false;var unit=parsed.get(row.get("source"));
        if(element==null)return syntax(row,entity)&&entity.origin()==EntityOrigin.PROJECT&&entity.stableScope().equals(EntityScope.project(unit.input().document().module()));
        if("PACKAGE".equals(element.get("kind")))return packageOwner(element,entity,unit);
        String uri=(String)element.get("artifact");var source=parsed.get(uri);
        if(source!=null)return entity.origin()==EntityOrigin.PROJECT&&entity.stableScope().equals(EntityScope.project(source.input().document().module()));
        var artifact=uri==null?null:capture.artifacts().get(uri);if(artifact==null||!artifact.bytesBound())return false;
        if(uri.startsWith("jrt:/")){
            int slash=uri.indexOf('/',5);if(slash<0)return false;String module=uri.substring(5,slash);
            return request.platform().artifacts().stream().anyMatch(a->a.logicalName().equals(module))&&entity.origin()==EntityOrigin.JDK&&entity.stableScope().equals(EntityScope.external(EntityOrigin.JDK,request.platform().entry().logicalName(),request.platform().entry().contentDigest()));
        }
        if(slots.size()!=request.dependencies().size())return false;
        for(int i=0;i<slots.size();i++){
            var slot=slots.get(i);String prefix=slot.kind()==NativeContextValidator.Slot.Kind.JAR?"jar:file://"+slot.path()+"!/":"file://"+slot.path()+"/";
            if(!uri.startsWith(prefix))continue;var input=request.dependencies().get(i);
            if(input.reactorModule().isPresent())return entity.origin()==EntityOrigin.PROJECT&&entity.stableScope().equals(EntityScope.project(input.reactorModule().orElseThrow()));
            return entity.origin()==EntityOrigin.DEPENDENCY&&entity.stableScope().equals(EntityScope.external(EntityOrigin.DEPENDENCY,input.originEntry().logicalName(),input.originEntry().contentDigest()));
        }
        return false;
    }
    private static boolean packageOwner(Map<String,Object> element,Entity entity,Parsed unit){
        if(element==null||entity.kind()!=EntityKind.PACKAGE)return false;String pkg=unit.ast().getPackageDeclaration().map(p->p.getNameAsString()).orElse("");
        return pkg.equals(element.get("owner"))&&entity.canonicalName().equals(JavaSymbolName.packageName(pkg).canonicalName())&&entity.origin()==EntityOrigin.PROJECT&&entity.stableScope().equals(EntityScope.project(unit.input().document().module()));
    }
    static boolean matchesSourceDeclaration(Map<String,Object> element,Entity entity,Node node,SourceInput input,OriginalSource source){
        var t=tuple(entity);String name=(String)element.get("name");
        if(node instanceof Parameter parameter){
            if(!Set.of("PARAMETER","EXCEPTION_PARAMETER").contains(element.get("kind")))return false;
            if(!parameter.getNameAsString().equals(name))return false;var parent=parameter.getParentNode().orElseThrow();
            if(parent instanceof CallableDeclaration<?> callable)return t.getFirst().equals("parameter")&&number(t.get(2))==callable.getParameters().indexOf(parameter);
            if(parent instanceof com.github.javaparser.ast.stmt.CatchClause)return t.getFirst().equals("catch-parameter")&&t.get(2).equals(input.document().identity().value())&&number(t.get(3))==source.start(parameter);
            if(parent instanceof LambdaExpr lambda)return t.getFirst().equals("parameter")&&number(t.get(2))==lambda.getParameters().indexOf(parameter);return false;
        }
        if(node instanceof TypeParameter parameter)return "TYPE_PARAMETER".equals(element.get("kind"))&&parameter.getNameAsString().equals(name)&&t.getFirst().equals("type-parameter")&&number(t.get(2))==parameter.getParentNode().orElseThrow().getChildNodes().stream().filter(n->n instanceof TypeParameter).toList().indexOf(parameter);
        if(node instanceof TypeDeclaration<?> type){String expected=type instanceof ClassOrInterfaceDeclaration c?(c.isInterface()?"INTERFACE":"CLASS"):type instanceof EnumDeclaration?"ENUM":type instanceof RecordDeclaration?"RECORD":"ANNOTATION_TYPE";return expected.equals(element.get("kind"))&&type.getNameAsString().equals(name);}
        if(node instanceof ObjectCreationExpr)return "CLASS".equals(element.get("kind"))&&t.getFirst().equals("local-type")&&t.get(2).equals(input.document().identity().value())&&number(t.get(3))==source.start(node)&&t.get(4).equals("anonymous");
        if(node instanceof MethodDeclaration method)return element.get("kind").equals("METHOD")&&method.getNameAsString().equals(name);
        if(node instanceof ConstructorDeclaration)return element.get("kind").equals("CONSTRUCTOR");
        if(node instanceof VariableDeclarator variable)return Set.of("FIELD","ENUM_CONSTANT").contains(element.get("kind"))&&variable.getNameAsString().equals(name);
        return false;
    }
    private static boolean kind(Node node,EntityKind kind){return switch(kind){case TYPE->node instanceof TypeDeclaration<?>||node instanceof ObjectCreationExpr c&&c.getAnonymousClassBody().isPresent();case METHOD->node instanceof MethodDeclaration;case CONSTRUCTOR->node instanceof ConstructorDeclaration;case FIELD->node instanceof VariableDeclarator;case PARAMETER->node instanceof Parameter;case TYPE_PARAMETER->node instanceof TypeParameter;case LAMBDA->node instanceof LambdaExpr;case INITIALIZER->node instanceof InitializerDeclaration||node instanceof Expression;case PACKAGE->node instanceof CompilationUnit;default->false;};}
    private static List<Object> tuple(Entity entity){return GraphJson.array(GraphJson.read(entity.canonicalName().substring("java:v1:".length()),new GraphJson.Limits(100000,64,100000)));}
    private static long number(Object value){if(value instanceof java.math.BigDecimal number)return number.longValueExact();if(value instanceof Integer||value instanceof Long)return ((Number)value).longValue();throw new IllegalArgumentException("NATIVE_MAPPING_INTEGER_REQUIRED");}
    private static boolean contains(SourceSpan outer,Map<String,Object> inner){return (outer.startLine()<number(inner.get("startLine"))||outer.startLine()==number(inner.get("startLine"))&&outer.startColumn()<=number(inner.get("startColumn")))&&(outer.endLine()>number(inner.get("endLine"))||outer.endLine()==number(inner.get("endLine"))&&outer.endColumn()>=number(inner.get("endColumn")));}
    private static boolean sameSpan(SourceSpan span,Map<String,Object> value){return span.startLine()==number(value.get("startLine"))&&span.startColumn()==number(value.get("startColumn"))&&span.endLine()==number(value.get("endLine"))&&span.endColumn()==number(value.get("endColumn"));}
    private static String site(Map<String,Object> row){var value=GraphJson.object(row.get("span"));return CanonicalJson.write(Map.of("source",row.get("source"),"category",row.get("category"),"startLine",value.get("startLine"),"startColumn",value.get("startColumn"),"endLine",value.get("endLine"),"endColumn",value.get("endColumn")));}
    private static String site(String uri,String category,SourceSpan span){return CanonicalJson.write(Map.of("source",uri,"category",category,"startLine",span.startLine(),"startColumn",span.startColumn(),"endLine",span.endLine(),"endColumn",span.endColumn()));}
}
