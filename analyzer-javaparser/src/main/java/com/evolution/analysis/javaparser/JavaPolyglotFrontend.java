package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A compatibility projection, never a rewrite of Java M1 identities or its eighteen families.
 * Syntax obligations are captured before attribution; the exact original neutral result stays in CAS.
 */
public final class JavaPolyglotFrontend {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("frontend.java-polyglot-bridge","m8.4-v3");
    public record Context(ContentDigest inventory,String root,String sourceSet,String variant,ContentDigest policy,ContentDigest workPolicy){
        public Context{Objects.requireNonNull(inventory);Objects.requireNonNull(policy);Objects.requireNonNull(workPolicy);for(String text:List.of(root,sourceSet,variant))if(text.isBlank())throw new IllegalArgumentException("JAVA_BRIDGE_CONTEXT_REQUIRED");}
    }
    private record Site(String document,String category,String role,Optional<SourceSpan> span,String anchor,boolean opaque){}
    public static final class Prepared {
        private final FrontendRequest java;private final LanguageSemanticRequest request;private final List<Site> sites;private final Map<String,Integer> prefixes;
        private Prepared(FrontendRequest java,LanguageSemanticRequest request,List<Site> sites,Map<String,Integer> prefixes){this.java=java;this.request=request;this.sites=List.copyOf(sites);this.prefixes=Map.copyOf(prefixes);}
        public LanguageSemanticRequest request(){return request;}
    }
    public record Result(FrontendResult legacy,ContentHandle legacyContent,LanguageSemanticRequest request,SemanticTables.Table observations){}
    private final RepositoryContentStore content;private final SemanticTables tables;
    public JavaPolyglotFrontend(RepositoryContentStore content,SemanticTables tables){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);}

    public Prepared prepare(FrontendRequest input,Context context,BooleanSupplier cancelled)throws IOException{
        if(input.plan().preview())throw new FrontendInputException("frontend.preview","Preview syntax requires a separately qualified provider");
        String named=input.manifest().configuration().values().getOrDefault("ingestion.source-set-name",input.sourceSet().name().toLowerCase(Locale.ROOT));
        if(!named.equals(context.sourceSet()))throw new IllegalArgumentException("JAVA_BRIDGE_SOURCE_SET_MISMATCH");
        var scope=CanonicalJson.sha256(List.of("language-context:m8.4-v1",context.inventory(),context.root(),input.module().value(),context.sourceSet(),context.variant(),"java",Integer.toString(input.plan().syntaxLevel().orElseThrow())));
        var documents=new ArrayList<SemanticTables.Row>();var sites=new ArrayList<Site>();
        var sources=new ArrayList<>(input.sources());input.reactorSources().forEach(r->sources.addAll(r.sources()));
        var prefixes=new HashMap<String,Integer>();sources.forEach(s->prefixes.put(s.document().path(),s.decoding().bom()==SourceInput.Bom.NONE?0:1));
        var primary=input.sources().stream().map(s->s.document().identity()).collect(java.util.stream.Collectors.toSet());
        var configuration=new ParserConfiguration().setLanguageLevel(level(input.plan().syntaxLevel().orElseThrow())).setTabSize(1).setPreprocessUnicodeEscapes(false);
        var parser=new JavaParser(configuration);
        for(var source:sources){
            int firstSite=sites.size();
            check(cancelled);var document=source.document();var captured=content.put(new ByteArrayInputStream(source.bytes()),cancelled);
            if(!captured.digest().equals(document.contentDigest()))throw new IOException("JAVA_BRIDGE_SOURCE_CHANGED");
            documents.add(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,document.path(),scope,Map.of("content",captured,"language","java","role",document.classification().name(),"legacyDocument",document.identity().value(),"module",document.module().value(),"membership",primary.contains(document.identity())?"PRIMARY":"RESOLUTION_ONLY","decoding",neutral(source.decoding()))));
            sites.add(new Site(document.path(),"A01","membership",Optional.empty(),"",false));sites.add(new Site(document.path(),"A11","diagnostics",Optional.empty(),"",false));sites.add(new Site(document.path(),"A12","accounting",Optional.empty(),"",false));
            boolean successful=false;
            if(primary.contains(document.identity())){
                try{
                    var translated=UnicodeSource.translate(source.text());var parsed=parser.parse(translated.text);
                    if(parsed.isSuccessful()&&parsed.getResult().isPresent()){
                        successful=true;var original=new OriginalSource(document.identity(),source.text(),translated);
                        for(Node node:parsed.getResult().orElseThrow().stream().toList()){
                            check(cancelled);String owner=ownerAnchor(node);
                            if(declaration(node))add(sites,document.path(),"A02","declares",node,original,owner);
                            if(node instanceof ImportDeclaration){add(sites,document.path(),"A03","imports",node,original,owner);add(sites,document.path(),"A09","import-boundary",node,original,owner);}
                            if(node instanceof Type&&!(node instanceof UnknownType)&&!(node instanceof TypeParameter))add(sites,document.path(),"A04","written-type",node,original,owner);
                            if(node instanceof ClassOrInterfaceDeclaration type){for(var t:type.getExtendedTypes())add(sites,document.path(),"A05","extends",t,original,owner);for(var t:type.getImplementedTypes())add(sites,document.path(),"A05","implements",t,original,owner);for(var t:type.getPermittedTypes())add(sites,document.path(),"A05","permits",t,original,owner);}
                            if(node instanceof EnumDeclaration type)for(var t:type.getImplementedTypes())add(sites,document.path(),"A05","implements",t,original,owner);
                            if(node instanceof RecordDeclaration type)for(var t:type.getImplementedTypes())add(sites,document.path(),"A05","implements",t,original,owner);
                            if(node instanceof MethodCallExpr)add(sites,document.path(),"A06","calls",node,original,owner);
                            if(node instanceof ObjectCreationExpr||node instanceof ExplicitConstructorInvocationStmt)add(sites,document.path(),"A06","constructor-calls",node,original,owner);
                            if(node instanceof MethodReferenceExpr)add(sites,document.path(),"A06","method-references",node,original,owner);
                            if(node instanceof FieldAccessExpr||node instanceof NameExpr)add(sites,document.path(),"A07","state-access",node,original,owner);
                            if(node instanceof AnnotationExpr)add(sites,document.path(),"A08","annotation",node,original,owner);
                            if(node instanceof IfStmt conditional)add(sites,document.path(),"A10","runtime-guard",conditional.getCondition(),original,owner);
                            if(node instanceof ConditionalExpr conditional)add(sites,document.path(),"A10","runtime-guard",conditional.getCondition(),original,owner);
                        }
                    }
                }catch(IllegalArgumentException malformed){successful=false;}
            }
            if(successful&&primary.contains(document.identity())&&sites.subList(firstSite,sites.size()).stream().anyMatch(s->s.category().equals("A08")))sites.add(new Site(document.path(),"A08","implicit-generation-census",Optional.empty(),"",false));
            for(String category:LanguageProfile.requiredCategories()){
                boolean opaque=primary.contains(document.identity())&&!successful&&!Set.of("A01","A11","A12").contains(category);
                if(opaque||sites.subList(firstSite,sites.size()).stream().noneMatch(s->s.category().equals(category)))sites.add(new Site(document.path(),category,opaque?"opaque-syntax":primary.contains(document.identity())?"syntax-absent":"resolution-only",Optional.empty(),"",opaque));
            }
        }
        var ordered=new TreeMap<String,Site>();for(var site:sites)ordered.putIfAbsent(key(site),site);
        documents.sort(Comparator.comparing(SemanticTables.Row::key));
        var documentTable=tables.write(SemanticTables.Kind.DOCUMENT,documents.iterator(),cancelled);
        var census=tables.write(SemanticTables.Kind.CENSUS,ordered.values().stream().map(site->obligation(scope,site,prefixes)).iterator(),cancelled);
        // Entire M1 request meaning is retained; path-based binary inputs are represented by exact ordered origin slots.
        var resolution=put(Map.of("schema","java-polyglot-resolution:m8.4-v1","manifest",input.manifest(),"catalog",FrontendRequest.CATALOG,"plan",input.plan(),"origins",input.originClasspath(),"reactorSources",input.reactorSources().stream().map(r->Map.of("order",r.order(),"documents",r.sources().stream().map(SourceInput::document).toList())).toList()),cancelled);
        var tool=CanonicalJson.sha256(Map.of("provider",PROVIDER,"legacyProvider",JavaParserFrontend.PROVIDER,"catalog",FrontendRequest.CATALOG,"components",List.of(component(JavaPolyglotFrontend.class),component(JavaParser.class),component(com.github.javaparser.symbolsolver.JavaSymbolSolver.class))));
        var request=new LanguageSemanticRequest(EvidenceContext.forAnalysis(input.manifest()),context.inventory(),context.root(),input.module().value(),context.sourceSet(),context.variant(),"java",Integer.toString(input.plan().syntaxLevel().orElseThrow()),documentTable,census,resolution,PROVIDER,tool,context.policy(),context.workPolicy());
        return new Prepared(input,request,List.copyOf(ordered.values()),prefixes);
    }

    public Result project(Prepared prepared,FrontendResult legacy,BooleanSupplier cancelled)throws IOException{
        check(cancelled);legacy.validateFor(prepared.java);
        if(!legacy.frontend().equals(JavaParserFrontend.PROVIDER))throw new IllegalArgumentException("JAVA_BRIDGE_PROVIDER_MISMATCH");
        if(!content.verify(prepared.request.resolution()))throw new IOException("JAVA_BRIDGE_RESOLUTION_UNAVAILABLE");
        var sourceById=new HashMap<SourceDocumentIdentity,SourceInput>();prepared.java.sources().forEach(s->sourceById.put(s.document().identity(),s));prepared.java.reactorSources().forEach(r->r.sources().forEach(s->sourceById.put(s.document().identity(),s)));
        for(var source:sourceById.values())if(!content.verify(new ContentHandle(source.document().contentDigest(),source.bytes().length)))throw new IOException("JAVA_BRIDGE_SOURCE_UNAVAILABLE");
        var occurrenceById=new HashMap<OccurrenceIdentity,RelationshipOccurrence>();legacy.occurrences().forEach(o->occurrenceById.put(o.identity(),o));
        var sourceByPath=new HashMap<String,SourceInput>();sourceById.values().forEach(s->sourceByPath.put(s.document().path(),s));
        var outcomeByDocument=new HashMap<SourceDocumentIdentity,SourceOutcome>();legacy.sources().forEach(s->outcomeByDocument.put(s.document(),s));
        var observationsBySpan=legacy.observations().stream().filter(o->o.span().isPresent()).collect(java.util.stream.Collectors.groupingBy(o->o.span().orElseThrow()));
        var typesByDocument=legacy.types().stream().collect(java.util.stream.Collectors.groupingBy(t->t.span().document()));
        var legacyContent=put(legacy,cancelled);
        var output=new ArrayList<SemanticTables.Row>();
        for(var site:prepared.sites){
            check(cancelled);var fields=new TreeMap<String,Object>();var expected=obligation(prepared.request.scope(),site,prepared.prefixes);fields.put("obligation",expected.identity().value());fields.put("role",site.role());fields.put("span",fullSpan(site,prepared.prefixes));fields.put("legacySpan",site.span().map(JavaPolyglotFrontend::neutral).orElse(null));fields.put("targets",List.of());fields.put("legacyOccurrences",List.of());fields.put("uncertainty",List.of());
            String state="RESOLVED";
            var document=sourceByPath.get(site.document()).document().identity();
            var outcome=outcomeByDocument.get(document);
            if(site.opaque())state="ERROR";
            else if(Set.of("syntax-absent","implicit-generation-census").contains(site.role())&&site.category().equals("A08")){state="UNSUPPORTED";fields.put("reason","JAVA_IMPLICIT_GENERATION_CENSUS_REQUIRED");}
            else if(site.role().equals("syntax-absent")||site.role().equals("resolution-only"))state="NOT_APPLICABLE";
            else if(site.category().equals("A01")){fields.put("membership",neutral(sourceById.get(document).document()));}
            else if(site.category().equals("A11")){fields.put("sourceOutcome",neutral(outcome));fields.put("analysisDiagnosticsIn",legacyContent);if(outcome.state()!=SourceOutcome.State.PROCESSED)state=outcome.state()==SourceOutcome.State.ERROR?"ERROR":"PARTIAL";}
            else if(site.category().equals("A12")){fields.put("legacyCatalog",FrontendRequest.CATALOG);fields.put("legacyAnalysisResult",legacyContent);}
            else if(site.category().equals("A10")){state="UNSUPPORTED";fields.put("reason","JAVA_RUNTIME_GUARD_TRUTH_NOT_EVALUATED");}
            else if(site.category().equals("A03")||site.category().equals("A09")){state="UNSUPPORTED";fields.put("reason","JAVA_IMPORT_BINDING_SUPPLEMENT_REQUIRED");}
            else if(site.category().equals("A04")){
                var types=typesByDocument.getOrDefault(document,List.of()).stream().filter(t->contains(t.span(),site.span().orElseThrow())).toList();
                if(types.isEmpty()){state="UNSUPPORTED";fields.put("reason","JAVA_WRITTEN_TYPE_MAPPING_UNAVAILABLE");}
                else{fields.put("types",neutral(types));var nativeStatus=types.stream().map(t->t.type().status()).filter(s->s!=SemanticStatus.RESOLVED).findFirst().orElse(SemanticStatus.RESOLVED);state=status(nativeStatus);if(state.equals("RESOLVED")||Set.of("PARTIAL","CONDITIONAL","AMBIGUOUS").contains(state))fields.put("targets",types.stream().flatMap(t->t.type().referencedEntities().stream()).map(JavaPolyglotFrontend::symbol).distinct().sorted().toList());}
            }else{
                var observations=observationsBySpan.getOrDefault(site.span().orElseThrow(),List.of()).stream().filter(o->matches(site,o.category().value())).toList();
                if(observations.isEmpty()){
                    if(site.category().equals("A07")){state="UNSUPPORTED";fields.put("reason","JAVA_VALUE_CLASSIFICATION_SUPPLEMENT_REQUIRED");}
                    else continue; // A missing expected declaration/call/heritage remains an omitted obligation.
                }else{
                    fields.put("legacyObservations",neutral(observations));var occurrences=observations.stream().flatMap(o->o.mappedOccurrence().stream()).map(occurrenceById::get).toList();
                    fields.put("legacyOccurrences",occurrences.stream().map(o->o.identity().value()).toList());fields.put("nativeFacts",neutral(occurrences));fields.put("callers",occurrences.stream().map(o->o.relationship().source().value()).distinct().sorted().toList());
                    state=status(observations.stream().map(ObservationRecord::attribution).filter(s->s!=SemanticStatus.RESOLVED).findFirst().orElse(SemanticStatus.RESOLVED));
                    if(state.equals("RESOLVED")||Set.of("PARTIAL","CONDITIONAL","AMBIGUOUS").contains(state))fields.put("targets",occurrences.stream().flatMap(o->targets(o.relationship().target()).stream()).map(JavaPolyglotFrontend::symbol).distinct().sorted().toList());
                    if(state.equals("AMBIGUOUS")&&!GraphJson.array(fields.get("targets")).isEmpty())state="CANDIDATES";
                    // Unmapped origin/provenance cannot support a resolved neutral target.
                    if(state.equals("RESOLVED")&&GraphJson.array(fields.get("targets")).isEmpty())state="PARTIAL";
                    if(site.category().equals("A08"))fields.put("annotations",neutral(legacy.annotations().stream().filter(a->a.span().equals(site.span().orElseThrow())).toList()));
                }
            }
            if(Set.of("PARTIAL","CONDITIONAL","AMBIGUOUS").contains(state))fields.put("uncertainty",List.of("JAVA_NATIVE_STATUS_OR_PROVENANCE_RETAINS_UNCERTAINTY"));
            fields.put("status",state);output.add(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,key(site),prepared.request.scope(),fields));
        }
        var table=tables.write(SemanticTables.Kind.OBSERVATION,output.iterator(),cancelled);
        return new Result(legacy,legacyContent,prepared.request,table);
    }
    private static String status(SemanticStatus status){return status.name();}
    private static List<EntityIdentity> targets(RelationshipTarget target){return target instanceof RelationshipTarget.Resolved resolved?List.of(resolved.target()):target instanceof RelationshipTarget.Candidates candidates?candidates.candidates():List.of();}
    /** A new projection locator preserves the full legacy ID in nativeFacts; no M1 preimage is edited. */
    private static String symbol(EntityIdentity id){return CanonicalJson.sha256(List.of("java-legacy-symbol-projection:m8.4-v1",id)).value();}
    private static boolean matches(Site site,String category){return switch(site.category()){case "A02","A05","A06"->category.equals("java."+site.role());case "A07"->Set.of("java.reads-field","java.writes-field").contains(category);case "A08"->category.equals("java.annotated-with");default->false;};}
    private static boolean contains(SourceSpan outer,SourceSpan inner){return outer.document().equals(inner.document())&&compare(outer.startLine(),outer.startColumn(),inner.startLine(),inner.startColumn())<=0&&compare(outer.endLine(),outer.endColumn(),inner.endLine(),inner.endColumn())>=0;}
    private static int compare(int line,int column,int otherLine,int otherColumn){return line!=otherLine?Integer.compare(line,otherLine):Integer.compare(column,otherColumn);}
    private static Object neutral(Object value){return GraphJson.read(CanonicalJson.write(value),new GraphJson.Limits(64000000,128,64000000));}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{
        Path temporary=Files.createTempFile("evo-java-neutral-",".json");
        try{try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){CanonicalJson.writeTo(value,writer);}try(var input=Files.newInputStream(temporary)){return content.put(input,cancelled);}}
        finally{Files.deleteIfExists(temporary);}
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("JAVA_BRIDGE_CANCELLED");}
    private static String key(Site site){return site.document()+":"+site.category()+":"+site.span().map(s->String.format(Locale.ROOT,"%010d:%010d:%010d:%010d",s.startLine(),s.startColumn(),s.endLine(),s.endColumn())).orElse("scope")+":"+site.role()+":"+site.anchor();}
    private static Object fullSpan(Site site,Map<String,Integer> prefixes){if(site.span().isEmpty())return null;var span=site.span().orElseThrow();int prefix=prefixes.getOrDefault(site.document(),0);return Map.of("document",span.document().value(),"startLine",span.startLine(),"startColumn",span.startColumn()+(span.startLine()==1?prefix:0),"endLine",span.endLine(),"endColumn",span.endColumn()+(span.endLine()==1?prefix:0));}
    private static SemanticTables.Row obligation(ContentDigest scope,Site site,Map<String,Integer> prefixes){var fields=new TreeMap<String,Object>();fields.put("document",site.document());fields.put("category",site.category());fields.put("role",site.role());fields.put("span",fullSpan(site,prefixes));fields.put("opaque",site.opaque());fields.put("applicability",site.role().equals("resolution-only")?"CONTEXT_RESOLUTION_INPUT":site.role().equals("syntax-absent")?"COMPLETE_SYNTAX_TRAVERSAL":"APPLICABLE_OR_OPAQUE");return new SemanticTables.Row(SemanticTables.Kind.CENSUS,key(site),scope,fields);}
    private static void add(List<Site> sites,String path,String category,String role,Node node,OriginalSource source,String owner){sites.add(new Site(path,category,role,Optional.of(source.span(node)),owner,false));}
    private static boolean declaration(Node node){return node instanceof TypeDeclaration<?>||node instanceof CallableDeclaration<?>||node instanceof CompactConstructorDeclaration||node instanceof AnnotationMemberDeclaration||node instanceof Parameter||node instanceof TypeParameter||node instanceof EnumConstantDeclaration||node instanceof InitializerDeclaration||node instanceof LambdaExpr||node instanceof VariableDeclarator v&&v.getParentNode().filter(FieldDeclaration.class::isInstance).isPresent()||node instanceof ObjectCreationExpr o&&o.getAnonymousClassBody().isPresent();}
    private static String ownerAnchor(Node node){for(Node parent=node.getParentNode().orElse(null);parent!=null;parent=parent.getParentNode().orElse(null))if(declaration(parent)||parent instanceof VariableDeclarator)return parent.getClass().getSimpleName()+parent.getRange().map(Object::toString).orElse("");return "document";}
    private static ParserConfiguration.LanguageLevel level(int version){String value=version<5?"JAVA_1_"+version:"JAVA_"+version;try{return ParserConfiguration.LanguageLevel.valueOf(value);}catch(IllegalArgumentException unsupported){throw new FrontendInputException("frontend.syntax-level","No syntax census provider for selected Java version");}}
    private static ContentDigest component(Class<?> type)throws IOException{
        try{
            Path location=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isRegularFile(location)){try(var in=Files.newInputStream(location)){return hash(in);}}
            var files=new TreeMap<String,ContentDigest>();try(var paths=Files.walk(location)){for(var file:paths.filter(p->p.toString().endsWith(".class")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){files.put(location.relativize(file).toString().replace('\\','/'),hash(in));}}
            return CanonicalJson.sha256(files);
        }catch(java.net.URISyntaxException invalid){throw new IOException("JAVA_TOOL_LOCATION_INVALID",invalid);}
    }
    private static ContentDigest hash(InputStream input)throws IOException{try{var hash=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);return new ContentDigest("sha256:"+HexFormat.of().formatHex(hash.digest()));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
