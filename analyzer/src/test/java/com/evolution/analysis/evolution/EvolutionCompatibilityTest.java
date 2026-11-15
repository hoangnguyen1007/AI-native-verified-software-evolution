package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvolutionCompatibilityTest {
    static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("urn:fixture:m11");
    static final VersionedIdentifier VERSION = new VersionedIdentifier("java.fixture", "1");
    static final String PATH = "src/main/java/p/A.java";

    @Test void exactEvidenceLossAndRestorationDoNotProveSemanticAbsence() throws Exception {
        var empty=graph(0,"tool","r1");
        // These are authored graph observations, not a native authority or a tool execution.
        var lineage=GraphEvidence.capture("java.evidence.source-map",Map.of("projectedPath","generated/A.java",
                "lineage",ContentDigest.sha256Utf8("authored generator lineage"),"content",ContentDigest.sha256Utf8("authored bytes")));
        var proof=GraphEvidence.capture("java.evidence.proof",Map.of("trust","PRODUCER_ASSERTED","outcome","UNQUALIFIED"));
        var conflict=GraphEvidence.capture("java.evidence.post-conflict",Map.of("state","UNRESOLVED","reason","authored observation conflict"));
        var present=withEvidence(empty,lineage,proof,conflict);
        var missing=withEvidence(empty,conflict);
        for(var pair:List.of(List.of(present,missing),List.of(missing,present))) {
            var a=pair.getFirst();var b=pair.getLast();
            var req=new SnapshotComparison.Request(REPO,a.identity(),b.identity(),SnapshotComparison.Mode.STRICT,
                    new SnapshotComparison.Selection(Set.of(),Set.of(),Set.of(),false,true));
            var result=SnapshotComparison.compare(req,EvolutionSnapshot.memory(a,b),EvolutionIndex.memory(),1,()->false);
            assertTrue(kinds(result).isEmpty());
            var evidence=items(result,SnapshotComparison.Section.EVIDENCE);
            assertEquals(3,evidence.size());
            assertTrue(evidence.stream().allMatch(e->Boolean.FALSE.equals(e.get("semanticAbsenceProved"))));
            var retained=evidence.stream().filter(e->"LEDGER_OBSERVATION_RETAINED".equals(e.get("kind"))).findFirst().orElseThrow();
            assertEquals(conflict.identity().value(),GraphJson.object(retained.get("left")).get("digest"));
            assertEquals(conflict.identity().value(),GraphJson.object(retained.get("right")).get("digest"));
            var side=a==present?"left":"right";
            var generated=evidence.stream().filter(e->"GENERATED_LINEAGE_OBSERVATION_CHANGED".equals(e.get("kind"))).findFirst().orElseThrow();
            assertEquals(lineage.identity().value(),GraphJson.object(generated.get(side)).get("digest"));
            var tool=evidence.stream().filter(e->"java.evidence.proof".equals(e.get("family"))).findFirst().orElseThrow();
            assertEquals("PRODUCER_ASSERTED",GraphJson.object(GraphJson.object(tool.get(side)).get("metadata")).get("trust"));
        }
    }

    private static KnowledgeGraph withEvidence(KnowledgeGraph graph,GraphEvidence... more) {
        var evidence=new ArrayList<>(graph.evidence());evidence.addAll(Arrays.asList(more));
        return new KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),evidence);
    }

    @Test void sourceEditKeepsComparablePolicy() throws Exception {
        var left = graph(0, "tool", "r1");
        var right = graph(1, "tool", "r2");
        var result = compare(left, right, 1);
        assertEquals(SnapshotComparison.Status.COMPLETE, result.summary().status());
        var events = result.page(SnapshotComparison.Section.EVENTS, 10, Optional.empty()).items();
        assertEquals(List.of("RELATION_ADDED"), events.stream().map(e -> e.get("kind")).toList());
        assertEquals("ABSENT", GraphJson.object(events.getFirst().get("before")).get("truth"));
        assertEquals("PRESENT", GraphJson.object(events.getFirst().get("after")).get("truth"));
    }

    @Test void reverseSelfAndRevisionOnlyHaveIndependentSemantics() throws Exception {
        var a=graph(0,"tool","r1");var b=graph(1,"tool","r2");
        assertEquals(List.of("RELATION_REMOVED"),kinds(compare(b,a,1)));
        assertEquals(List.of(),kinds(compare(a,a,1)));
        var tag=graph(0,"tool","other-ref");
        assertNotEquals(a.identity(),tag.identity());
        assertEquals(List.of(),kinds(compare(a,tag,2)));
        assertNotEquals(compare(a,b,1).summary().request(),compare(b,a,1).summary().request());
    }

    @Test void evidenceLossRetainsUnknownAndTypedOriginalGap() throws Exception {
        var left=graph(1,"tool","r1");var degraded=graph(-1,"tool","r2");
        var result=compare(left,degraded,2);
        assertEquals(SnapshotComparison.Status.PARTIAL,result.summary().status());
        assertEquals(List.of("OBSERVATION_CHANGED"),kinds(result));
        var event=items(result,SnapshotComparison.Section.EVENTS).getFirst();
        assertEquals("UNKNOWN",GraphJson.object(event.get("after")).get("truth"));
        assertTrue(items(result,SnapshotComparison.Section.GAPS).stream().anyMatch(g->g.get("evidence")!=null));
    }

    @Test void binaryToolAndClasspathOrderDoNotEstablishARepositoryChange() throws Exception {
        var before=graph(0,"tool","r1");var changed=graph(1,"other tool bytes","r2");
        var result=compare(before,changed,2);
        assertEquals(List.of("OBSERVATION_CHANGED"),kinds(result));
        assertTrue(items(result,SnapshotComparison.Section.COMPATIBILITY).stream().anyMatch(row->
                "QUALIFIED".equals(row.get("verdict"))&&GraphJson.array(row.get("reasons")).contains("TOOL_DRIFT")));
        var reversed=compare(before,graph(1,"tool","r2",true,false),3);
        assertTrue(items(reversed,SnapshotComparison.Section.COMPATIBILITY).stream().anyMatch(row->
                GraphJson.array(row.get("reasons")).contains("CLASSPATH_DRIFT")));
        assertFalse(kinds(reversed).contains("RELATION_ADDED"));
    }

    @Test void exactContextCensusPreservesUnselectedAndAmbiguousRows() throws Exception {
        var a=graph(0,"tool","r1");var b=graph(1,"tool","r2");
        var result=compare(a,b,1);
        var rows=items(result,SnapshotComparison.Section.CONTEXTS);
        for(String side:List.of("LEFT","RIGHT")){
            assertEquals(2,rows.stream().filter(r->side.equals(r.get("side"))&&"MATCHED".equals(r.get("disposition"))).count());
            assertEquals(1,rows.stream().filter(r->side.equals(r.get("side"))&&"NOT_SELECTED".equals(r.get("disposition"))).count());
        }
        var fork=graph(1,"tool","r2",false,true);
        var ambiguous=compare(a,fork,1);
        assertTrue(items(ambiguous,SnapshotComparison.Section.CONTEXTS).stream().anyMatch(row->"AMBIGUOUS".equals(row.get("disposition"))));
        assertTrue(kinds(ambiguous).isEmpty());
    }

    @Test void occurrenceMultiplicityAndEvidenceAreSeparateFromLogicalRelation() throws Exception {
        var result=compare(graph(1,"tool","r1"),graph(2,"tool","r2"),1);
        assertEquals(List.of("SUPPORT_CHANGED"),kinds(result));
        var event=items(result,SnapshotComparison.Section.EVENTS).getFirst();
        assertEquals(Set.of("LOCATION","MULTIPLICITY","EVIDENCE"),new HashSet<>(GraphJson.array(event.get("axes"))));
    }

    @Test void matchingSourceCannotBecomeARemovalAndAuthenticShapeChangesRemainVisible() throws Exception {
        var a=graph(0,"tool","r1");var b=graph(1,"tool","r2");
        var sameSourceConflict=graph(1,"tool","r3",false,false,"class",false);
        var conflict=compare(sameSourceConflict,b,2);
        assertEquals(List.of("OBSERVATION_CHANGED"),kinds(conflict));
        assertEquals("SAME_SOURCE_OBSERVATION",items(conflict,SnapshotComparison.Section.EVENTS).getFirst().get("attribution"));
        var shape=compare(a,graph(0,"tool","r4",false,false,"abstract class",true),2);
        assertTrue(kinds(shape).contains("ENTITY_CHANGED"));
    }

    @Test void pendingDeclaredModuleAndMissingReportStayInTheirOwnDenominators() throws Exception {
        var left=graph(0,"tool","r1");var right=graph(1,"tool","r2");
        var proof=GraphEvidence.capture("build.module",Map.of("descriptor",ModuleDescriptor.create(REPO,"modules/other","other"),
                "sourcePlan",Map.of("sourceSets",List.of(Map.of("name","main","semanticRole","MAIN")))));
        var evidence=new ArrayList<>(right.evidence());evidence.add(proof);
        var b=new KnowledgeGraph(right.snapshot(),right.contexts(),right.nodes(),right.edges(),evidence);
        String context=b.contexts().stream().filter(c->c.analysis().isPresent()).findFirst().orElseThrow().identity();
        var report=new SnapshotComparison.ReportRef(SnapshotComparison.Side.RIGHT,ContentDigest.sha256Utf8("report"),context);
        var result=SnapshotComparison.compare(request(left,b),EvolutionSnapshot.memory(left,b),List.of(report),
                (graph,ref,stop)->SnapshotComparison.ReportStatus.MISSING,EvolutionIndex.memory(),2,()->false);
        var census=items(result,SnapshotComparison.Section.CONTEXTS);
        assertTrue(census.stream().anyMatch(row->
                "modules/other".equals(row.get("path"))&&"UNAVAILABLE".equals(row.get("disposition"))),
                ()->census.stream().map(row->Arrays.asList(row.get("path"),row.get("disposition"),row.get("reason"))).toList().toString());
        assertTrue(items(result,SnapshotComparison.Section.INPUTS).stream().anyMatch(row->
                "REPORT".equals(row.get("kind"))&&"MISSING".equals(row.get("status"))));
        assertEquals(SnapshotComparison.Status.PARTIAL,result.summary().status());
        assertEquals(List.of("RELATION_ADDED"),kinds(result));
    }

    @Test void multiRootCensusDistinguishesMissingSelectedAndUnselectedVariants() throws Exception {
        var left = multiRootGraph(false);
        var right = multiRootGraph(true);
        var result = compare(left,right,2);
        var contexts = items(result,SnapshotComparison.Section.CONTEXTS).stream()
                .filter(row -> String.valueOf(row.get("path")).startsWith("roots/")).toList();
        for (var side : List.of("LEFT","RIGHT")) {
            var rows = contexts.stream().filter(row -> side.equals(row.get("side"))).toList();
            var actual = rows.stream().map(row -> row.get("path")+":"+row.get("disposition")).sorted().toList();
            var expected = side.equals("LEFT") ? List.of("roots/base:MATCHED","roots/base:NOT_SELECTED",
                    "roots/pending:UNAVAILABLE") : List.of("roots/base:MATCHED","roots/new:RIGHT_ONLY");
            assertEquals(expected, actual);
        }
        assertTrue(contexts.stream().anyMatch(row -> "extra-reactor".equals(row.get("root"))
                && "RIGHT_ONLY".equals(row.get("disposition"))));
        assertTrue(contexts.stream().anyMatch(row -> "ci".equals(row.get("variant"))
                && "NOT_SELECTED".equals(row.get("disposition"))));
        assertTrue(kinds(result).isEmpty());
    }

    private static KnowledgeGraph multiRootGraph(boolean target) {
        var repository = RepositorySnapshot.create(REPO,Optional.empty(),false,List.of(),List.of());
        var base = ModuleDescriptor.create(REPO,"roots/base","base");
        var extra = ModuleDescriptor.create(REPO,"roots/new","extra");
        var graphBuilder = new CanonicalGraphBuilder();
        var baseGraph = emptyRoot(graphBuilder,repository,base,"base-reactor","MAIN","default");
        var sibling = target ? emptyRoot(graphBuilder,repository,extra,"extra-reactor","MAIN","default") :
                emptyRoot(graphBuilder,repository,base,"base-reactor","TEST","ci");
        var contexts = new ArrayList<>(baseGraph.contexts());
        contexts.addAll(sibling.contexts());
        var nodes = new ArrayList<>(baseGraph.nodes());
        nodes.addAll(sibling.nodes());
        var edges = new ArrayList<>(baseGraph.edges());
        edges.addAll(sibling.edges());
        var evidence = new TreeMap<ContentDigest,GraphEvidence>();
        for (var graph : List.of(baseGraph,sibling)) graph.evidence().forEach(e -> evidence.put(e.identity(),e));
        if (!target) {
            var pending = GraphEvidence.capture("build.module",Map.of("descriptor",ModuleDescriptor.create(REPO,"roots/pending","pending"),
                    "sourcePlan",Map.of("sourceSets",List.of(Map.of("name","main","semanticRole","MAIN")))));
            evidence.put(pending.identity(),pending);
        }
        return new KnowledgeGraph(repository.identity(),contexts,nodes,edges,List.copyOf(evidence.values()));
    }

    private static KnowledgeGraph emptyRoot(CanonicalGraphBuilder builder, RepositorySnapshot snapshot,
                                            ModuleDescriptor module, String root, String role, String variant) {
        var component = new ManifestComponent(VERSION,ContentDigest.sha256Utf8("multi-root fixture"));
        var jdk = ContentDigest.sha256Utf8("fixture JDK");
        var config = AnalysisConfiguration.create(VERSION,Map.of("java.module",module.identity().value(),
                "java.source-set",role,"java.platform.symbol-digest",jdk.value(),"readiness.build-root",root,
                "readiness.context-variant",variant,"java.source-plan",CanonicalJson.sha256(List.of()).value()));
        var manifest = AnalysisManifest.create(VERSION,snapshot,List.of(module),
                List.of(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,"java.base",jdk)),config,component,
                component,KnowledgeGraph.schemaComponent());
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(new RelationshipKind("java."+c),
                CategoryCoverage.Support.IMPLEMENTED,0,0,0)).toList();
        return builder.build(manifest,new FrontendResult(manifest.identity(),VERSION,FrontendResult.State.COMPLETED,
                List.of(),List.of(),List.of(),List.of(),coverage,List.of()));
    }

    @Test void strictModeWithholdsOnProviderDriftAndRejectsReusingScratch() throws Exception {
        var a=graph(0,"tool","r1");var b=graph(1,"other tool","r2");
        var base=request(a,b);
        var strict=new SnapshotComparison.Request(base.repository(),base.left(),base.right(),SnapshotComparison.Mode.STRICT,base.selection());
        var reader=EvolutionSnapshot.memory(a,b);var scratch=EvolutionIndex.memory();
        var result=SnapshotComparison.compare(strict,reader,scratch,2,()->false);
        assertEquals(SnapshotComparison.Status.WITHHELD,result.summary().status());
        assertEquals(List.of(),kinds(result));
        assertThrows(IllegalArgumentException.class,()->SnapshotComparison.compare(base,reader,scratch,2,()->false));
    }

    @Test void mismatchedDependencySupportCannotBecomeAProvedAddition() throws Exception {
        var a=graph(0,"tool","r1");var b=graph(1,"tool","r2");
        var edges=b.edges().stream().map(edge->{
            if(!edge.kind().equals("graph.depends-on"))return edge;
            return new KnowledgeGraph.Edge(edge.identity(),edge.context(),edge.kind(),edge.target().identities().getFirst(),
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(edge.source()),Optional.empty()),edge.evidence(),edge.region());
        }).toList();
        var forged=new KnowledgeGraph(b.snapshot(),b.contexts(),b.nodes(),edges,b.evidence());
        var result=compare(a,forged,1);
        assertEquals(SnapshotComparison.Status.PARTIAL,result.summary().status());
        assertTrue(kinds(result).isEmpty());
        assertTrue(items(result,SnapshotComparison.Section.COMPATIBILITY).stream().anyMatch(row->
                GraphJson.array(row.get("reasons")).contains("OPERAND_CORRUPT")));
    }

    @Test void missingDerivedEdgeCannotProveAbsenceWhenRawOccurrenceStillExists() throws Exception {
        var a=graph(1,"tool","r1");var b=graph(2,"tool","r2");
        var edges=b.edges().stream().filter(edge->!edge.kind().equals("graph.depends-on")).toList();
        var incomplete=new KnowledgeGraph(b.snapshot(),b.contexts(),b.nodes(),edges,b.evidence());
        var result=compare(a,incomplete,2);
        assertEquals(SnapshotComparison.Status.PARTIAL,result.summary().status());
        assertFalse(kinds(result).contains("RELATION_REMOVED"));
        assertTrue(items(result,SnapshotComparison.Section.COMPATIBILITY).stream().anyMatch(row->
                GraphJson.array(row.get("reasons")).contains("OPERAND_CORRUPT")));
    }

    @Test void sameSpellingInDistinctModulesAndOverloadsKeepSeparateIdentities() throws Exception {
        var first=ModuleDescriptor.create(REPO,"modules/a","a");var second=ModuleDescriptor.create(REPO,"modules/b","b");
        String firstSource = """
                package p;
                class A {
                    class Nested {}
                    void f(int x) { class Local {} }
                    void f(String x) {}
                }
                """;
        String secondSource = "package p; class A {}";
        String generatedSource = "package p; class Generated {}";
        var primary = SourceDocument.create(REPO,first,"modules/a/A.java",ContentDigest.sha256Utf8(firstSource),SourceClassification.MAIN);
        var sibling = SourceDocument.create(REPO,second,"modules/b/A.java",ContentDigest.sha256Utf8(secondSource),SourceClassification.MAIN);
        var generated = SourceDocument.create(REPO,first,"modules/a/generated/Generated.java",
                ContentDigest.sha256Utf8(generatedSource),SourceClassification.GENERATED_MAIN);
        var type = JavaSymbolName.topLevelType("p","A");
        var integer = JavaSymbolName.method(type,"f",List.of(ErasedType.primitive("int")));
        var string = JavaSymbolName.method(type,"f",List.of(ErasedType.declared(JavaSymbolName.topLevelType("java.lang","String"))));
        var declarations = List.of(
                authoredDeclaration(primary, firstSource, "A {", "A", EntityKind.TYPE, type),
                authoredDeclaration(primary, firstSource, "Nested", "Nested", EntityKind.TYPE, JavaSymbolName.memberType(type,"Nested")),
                authoredDeclaration(primary, firstSource, "Local", "Local", EntityKind.TYPE,
                        JavaSymbolName.localType(integer,primary.identity(),firstSource.indexOf("Local"),"Local",false)),
                authoredDeclaration(primary, firstSource, "f(int", "f", EntityKind.METHOD, integer),
                authoredDeclaration(primary, firstSource, "f(String", "f", EntityKind.METHOD, string),
                authoredDeclaration(sibling, secondSource, "A {", "A", EntityKind.TYPE, type),
                authoredDeclaration(generated, generatedSource, "Generated", "Generated", EntityKind.TYPE,
                        JavaSymbolName.topLevelType("p","Generated")));
        var docs = List.of(primary,sibling,generated);
        var left=identityGraph(List.of(first,second),docs,declarations);
        var right=identityGraph(List.of(first,second),docs,declarations.reversed());
        var selection=new SnapshotComparison.Selection(Set.of("MAIN"),Set.of("TYPE","METHOD"),Set.of());
        var request=new SnapshotComparison.Request(REPO,left.identity(),right.identity(),SnapshotComparison.Mode.STRICT,selection);
        var result=SnapshotComparison.compare(request,EvolutionSnapshot.memory(left,right),EvolutionIndex.memory(),2,()->false);
        var matches=items(result,SnapshotComparison.Section.CORRESPONDENCE);
        assertEquals(7,matches.size());
        assertEquals(6,matches.stream().filter(r->"MATCHED".equals(r.get("disposition"))).count());
        var ids=matches.stream().map(r->GraphJson.object(r.get("left")).get("identity")).collect(java.util.stream.Collectors.toSet());
        assertEquals(7,ids.size());
        var withheld = matches.stream().filter(r->"UNAVAILABLE".equals(r.get("disposition"))).findFirst().orElseThrow();
        assertEquals(declarations.getLast().entity().identity().value(),GraphJson.object(withheld.get("left")).get("identity"));
        assertTrue(kinds(result).isEmpty());
    }

    private static DeclarationRecord authoredDeclaration(SourceDocument doc,String source,String marker,String name,
                                                         EntityKind kind,JavaSymbolName symbol) {
        int offset = source.indexOf(marker);
        int line = 1 + (int)source.substring(0,offset).chars().filter(c -> c == '\n').count();
        int column = offset - source.lastIndexOf('\n',offset);
        var span = new SourceSpan(doc.identity(),line,column,line,column+name.length());
        return declaration(Entity.create(EntityOrigin.PROJECT,EntityScope.project(doc.module()),kind,symbol.canonicalName(),Optional.of(span)));
    }

    @Test void possibleMoveWithTwoCandidatesAndChangedLineageStaysUnmatched() throws Exception {
        var original = ModuleDescriptor.create(REPO,"modules/original","original");
        var candidateA = ModuleDescriptor.create(REPO,"modules/candidate-a","candidate-a");
        var candidateB = ModuleDescriptor.create(REPO,"modules/candidate-b","candidate-b");
        var source = DocumentedCandidate.create(original,"authored lineage before");
        var a = DocumentedCandidate.create(candidateA,"authored lineage after A");
        var b = DocumentedCandidate.create(candidateB,"authored lineage after B");
        var left = withEvidence(identityGraph(List.of(original),List.of(source.document()),false),source.lineage());
        var right = withEvidence(identityGraph(List.of(candidateA,candidateB),List.of(a.document(),b.document()),false),
                a.lineage(),b.lineage());
        var selection = new SnapshotComparison.Selection(Set.of("MAIN"),Set.of("TYPE"),Set.of(),false,true);
        var request = new SnapshotComparison.Request(REPO,left.identity(),right.identity(),SnapshotComparison.Mode.OBSERVATION,selection);
        var result = SnapshotComparison.compare(request,EvolutionSnapshot.memory(left,right),EvolutionIndex.memory(),2,()->false);
        var newScopes = items(result,SnapshotComparison.Section.CONTEXTS).stream()
                .filter(row -> "RIGHT_ONLY".equals(row.get("disposition")))
                .map(row -> row.get("module")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(candidateA.identity().value(),candidateB.identity().value()),newScopes);
        assertTrue(items(result,SnapshotComparison.Section.CORRESPONDENCE).stream()
                .noneMatch(row -> "MATCHED".equals(row.get("disposition"))));
        assertTrue(kinds(result).isEmpty());
        var candidates = items(result,SnapshotComparison.Section.EVIDENCE).stream()
                .filter(row -> "GENERATED_LINEAGE_OBSERVATION_CHANGED".equals(row.get("kind")) && row.get("right") != null)
                .map(row -> GraphJson.object(row.get("right")).get("digest")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(a.lineage().identity().value(),b.lineage().identity().value()),candidates);
    }

    private record DocumentedCandidate(SourceDocument document,GraphEvidence lineage) {
        static DocumentedCandidate create(ModuleDescriptor module,String authoredLineage) {
            String path = module.path()+"/generated/Same.java";
            var digest = ContentDigest.sha256Utf8("package p; class Same {}");
            var source = SourceDocument.create(REPO,module,path,digest,SourceClassification.GENERATED_MAIN);
            var evidence = GraphEvidence.capture("java.evidence.source-map",Map.of("projectedPath",path,
                    "lineage",ContentDigest.sha256Utf8(authoredLineage),"content",digest,"trust","PRODUCER_ASSERTED"));
            return new DocumentedCandidate(source,evidence);
        }
    }

    private static KnowledgeGraph identityGraph(List<ModuleDescriptor> modules,List<SourceDocument> docs,boolean reverse) {
        var first=modules.getFirst();
        var decl=new ArrayList<DeclarationRecord>();
        var type=JavaSymbolName.topLevelType("p","Same");
        for(var doc:docs){
            var origin=EntityScope.project(doc.module());var span=Optional.of(new SourceSpan(doc.identity(),1,1,1,2));
            decl.add(declaration(Entity.create(EntityOrigin.PROJECT,origin,EntityKind.TYPE,type.canonicalName(),span)));
            if(doc.module().equals(first.identity()))for(String p:List.of("int","long"))decl.add(declaration(Entity.create(EntityOrigin.PROJECT,origin,
                    EntityKind.METHOD,JavaSymbolName.method(type,"f",List.of(ErasedType.primitive(p))).canonicalName(),span)));
        }
        if(reverse)Collections.reverse(decl);
        return identityGraph(modules,docs,decl);
    }

    private static KnowledgeGraph identityGraph(List<ModuleDescriptor> modules,List<SourceDocument> docs,List<DeclarationRecord> decl) {
        var snapshot=RepositorySnapshot.create(REPO,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs);
        var component=new ManifestComponent(VERSION,ContentDigest.sha256Utf8("identity fixture tool"));
        var jdk=ContentDigest.sha256Utf8("identity fixture JDK");
        var coverage=FrontendRequest.CATEGORIES.stream().map(c->new CategoryCoverage(new RelationshipKind("java."+c),CategoryCoverage.Support.IMPLEMENTED,0,0,0)).toList();
        var outcomes=docs.stream().map(d->new SourceOutcome(d.identity(),SourceOutcome.State.PROCESSED,List.of())).toList();
        var contexts=new ArrayList<KnowledgeGraph.Context>();var nodes=new ArrayList<KnowledgeGraph.Node>();var edges=new ArrayList<KnowledgeGraph.Edge>();
        var evidence=new TreeMap<ContentDigest,GraphEvidence>();
        for(var module:modules) {
            var scoped=docs.stream().filter(d->d.module().equals(module.identity())).sorted().toList();
            var scopedIds=scoped.stream().map(SourceDocument::identity).collect(java.util.stream.Collectors.toSet());
            var selected=decl.stream().filter(d->d.entity().stableScope().value().equals(module.identity().value())).toList();
            var config=AnalysisConfiguration.create(VERSION,Map.of("java.module",module.identity().value(),"java.source-set","MAIN",
                    "java.platform.symbol-digest",jdk.value(),"java.source-plan",CanonicalJson.sha256(scoped).value()));
            var manifest=AnalysisManifest.create(VERSION,snapshot,modules,List.of(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,"java.base",jdk)),
                    config,component,component,KnowledgeGraph.schemaComponent());
            var result=new FrontendResult(manifest.identity(),VERSION,FrontendResult.State.COMPLETED,selected,List.of(),List.of(),
                    outcomes.stream().filter(o->scopedIds.contains(o.document())).toList(),coverage,List.of());
            var graph=new CanonicalGraphBuilder().build(manifest,result);
            contexts.addAll(graph.contexts());nodes.addAll(graph.nodes());edges.addAll(graph.edges());graph.evidence().forEach(e->evidence.put(e.identity(),e));
        }
        return new KnowledgeGraph(snapshot.identity(),contexts,nodes,edges,List.copyOf(evidence.values()));
    }

    private static DeclarationRecord declaration(Entity entity) {
        return new DeclarationRecord(entity,"fixture",SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,VERSION,List.of()),List.of(),List.of());
    }

    @Test void pageBoundariesAndReversedEnumerationDoNotChangeIdentity() throws Exception {
        var a=graph(0,"tool","r1");var b=graph(1,"tool","r2");
        var expected=compare(a,b,1);var wider=compare(a,b,50);
        var reversed=SnapshotComparison.compare(request(a,b),reordered(EvolutionSnapshot.memory(a,b)),EvolutionIndex.memory(),2,()->false);
        assertEquals(expected.summary(),wider.summary());assertEquals(expected.summary(),reversed.summary());
        var section=SnapshotComparison.Section.CONTEXTS;
        var first=expected.page(section,1,Optional.empty());
        assertTrue(first.continuation().isPresent());
        assertThrows(IllegalArgumentException.class,()->expected.page(section,2,first.continuation()));
        assertThrows(IllegalArgumentException.class,()->compare(b,a,1).page(section,1,first.continuation()));
    }

    private static EvolutionSnapshot.Reader reordered(EvolutionSnapshot.Reader source) {
        return (id,cancelled)->{
            var graph=source.open(id,cancelled);
            return new EvolutionSnapshot(){
                public Header header(){return graph.header();}
                public PartitionedGraphReader.Page<Partition> contexts(int size,Optional<String> cursor)throws java.io.IOException{return reversed(graph.contexts(size,cursor));}
                public PartitionedGraphReader.Page<KnowledgeGraph.Node> nodes(String c,int size,Optional<String> cursor)throws java.io.IOException{return reversed(graph.nodes(c,size,cursor));}
                public PartitionedGraphReader.Page<KnowledgeGraph.Edge> edges(String c,int size,Optional<String> cursor)throws java.io.IOException{return reversed(graph.edges(c,size,cursor));}
                public PartitionedGraphReader.Page<GraphEvidence> evidence(int size,Optional<String> cursor)throws java.io.IOException{return reversed(graph.evidence(size,cursor));}
                public Optional<GraphEvidence> evidence(ContentDigest ref)throws java.io.IOException{return graph.evidence(ref);}
            };
        };
    }
    private static <T> PartitionedGraphReader.Page<T> reversed(PartitionedGraphReader.Page<T> page){
        return new PartitionedGraphReader.Page<>(page.items().reversed(),page.continuation());
    }
    static List<String> kinds(SnapshotComparison.Result result)throws Exception {
        return items(result,SnapshotComparison.Section.EVENTS).stream().map(row->(String)row.get("kind")).toList();
    }
    static List<Map<String,Object>> items(SnapshotComparison.Result result,SnapshotComparison.Section section)throws Exception {
        var items=new ArrayList<Map<String,Object>>();Optional<String> cursor=Optional.empty();
        do {var page=result.page(section,3,cursor);items.addAll(page.items());cursor=page.continuation();}while(cursor.isPresent());
        return items;
    }

    static SnapshotComparison.Result compare(KnowledgeGraph left, KnowledgeGraph right, int size) throws Exception {
        return SnapshotComparison.compare(request(left,right), EvolutionSnapshot.memory(left,right),
                EvolutionIndex.memory(), size, () -> false);
    }

    static SnapshotComparison.Request request(KnowledgeGraph left, KnowledgeGraph right) {
        return new SnapshotComparison.Request(REPO,left.identity(),right.identity(),SnapshotComparison.Mode.OBSERVATION,
                new SnapshotComparison.Selection(Set.of("MAIN"), Set.of("TYPE"), Set.of("graph.depends-on")));
    }

    static KnowledgeGraph graph(int occurrences, String tool, String revision) {
        return graph(occurrences,tool,revision,false,false);
    }

    @Test void unresolvedSpellingAndResolvedTargetRemainSeparateObservations() throws Exception {
        var left = graph(1,"tool","r1",false,false,"class",true,false);
        var right = graph(1,"tool","r2",false,false,"class",true,true);
        var selection = new SnapshotComparison.Selection(Set.of("MAIN"),Set.of("TYPE","FIELD"),Set.of("java.field-type"));
        var request = new SnapshotComparison.Request(REPO,left.identity(),right.identity(),SnapshotComparison.Mode.OBSERVATION,selection);
        var result = SnapshotComparison.compare(request,EvolutionSnapshot.memory(left,right),EvolutionIndex.memory(),3,()->false);
        var relationships = items(result,SnapshotComparison.Section.CORRESPONDENCE).stream()
                .filter(r -> "RELATION".equals(r.get("family"))).toList();
        assertEquals(2, relationships.size());
        var stages = new TreeSet<String>();
        for (var row : relationships) {
            assertNotEquals("MATCHED", row.get("disposition"));
            var operand = GraphJson.object(row.get(row.get("left") != null ? "left" : "right"));
            assertFalse(GraphJson.array(operand.get("evidence")).isEmpty());
            stages.add((String) GraphJson.object(operand.get("target")).get("state"));
        }
        assertEquals(Set.of("RESOLVED","UNRESOLVED"), stages);
        assertEquals(List.of("OBSERVATION_CHANGED","OBSERVATION_CHANGED"), kinds(result));
    }

    private static KnowledgeGraph graph(int occurrences,String tool,String revision,boolean reverseClasspath,boolean duplicateMain) {
        return graph(occurrences,tool,revision,reverseClasspath,duplicateMain,"class",true);
    }

    private static KnowledgeGraph graph(int occurrences,String tool,String revision,boolean reverseClasspath,boolean duplicateMain,
                                        String kindOfA,boolean publishRelation) {
        return graph(occurrences,tool,revision,reverseClasspath,duplicateMain,kindOfA,publishRelation,true);
    }

    private static KnowledgeGraph graph(int occurrences,String tool,String revision,boolean reverseClasspath,boolean duplicateMain,
                                        String kindOfA,boolean publishRelation,boolean resolveTarget) {
        var module = ModuleDescriptor.create(REPO,"fixture",".");
        var fields=new StringBuilder();for(int i=0;i<occurrences;i++)fields.append("B peer").append(i).append("; ");
        var java = "package p;\n"+kindOfA+" A { " + fields + "}\nclass B { }\n";
        var doc = SourceDocument.create(REPO,module,PATH,ContentDigest.sha256Utf8(java),SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(REPO,Optional.of(revision),false,List.of(SnapshotFile.from(doc)),List.of(doc));
        var component = new ManifestComponent(VERSION,ContentDigest.sha256Utf8(tool));
        var policy = new ManifestComponent(VERSION,ContentDigest.sha256Utf8("empty policy"));
        var platform=ContentDigest.sha256Utf8("fixture JDK 21");
        var configuration=AnalysisConfiguration.create(VERSION,Map.of("java.module",module.identity().value(),
                "java.source-set","MAIN","java.release","21","java.platform.symbol-digest",platform.value(),
                "java.source-plan",CanonicalJson.sha256(List.of(doc)).value()));
        var classpath=new ArrayList<>(List.of(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,"java.base",platform),
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"fixture.jar",ContentDigest.sha256Utf8("fixture dependency"))));
        if(reverseClasspath)Collections.reverse(classpath);
        var manifest=AnalysisManifest.create(VERSION,snapshot,List.of(module),
                classpath,configuration,component,policy,KnowledgeGraph.schemaComponent());
        var derivation=new Derivation(DerivationKind.DIRECT,VERSION,List.of());
        var declarations=new ArrayList<DeclarationRecord>();
        for(String name:List.of("A","B")){
            int line=name.equals("A")?2:3;
            var type=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.TYPE,
                    JavaSymbolName.topLevelType("p",name).canonicalName(),Optional.of(new SourceSpan(doc.identity(),line,7,line,8)));
            declarations.add(new DeclarationRecord(type,name,SemanticStatus.RESOLVED,derivation,List.of(),List.of()));
        }
        var b=declarations.getLast().entity();
        var kind=new RelationshipKind("java.field-type");
        var uses=new ArrayList<RelationshipOccurrence>();var observations=new ArrayList<ObservationRecord>();
        for(int i=0;i<occurrences;i++){
            var field=Entity.create(EntityOrigin.PROJECT,EntityScope.project(module.identity()),EntityKind.FIELD,
                    JavaSymbolName.field(JavaSymbolName.topLevelType("p","A"),"peer"+i).canonicalName(),
                    Optional.of(new SourceSpan(doc.identity(),2,13+9*i,2,18+9*i)));
            declarations.add(new DeclarationRecord(field,"peer"+i,SemanticStatus.RESOLVED,derivation,List.of(),List.of()));
            RelationshipTarget target = resolveTarget ? new RelationshipTarget.Resolved(b.identity()) : new RelationshipTarget.Unresolved("B");
            var status = resolveTarget ? SemanticStatus.RESOLVED : SemanticStatus.UNRESOLVED;
            var relationship=SemanticRelationship.create(field.identity(),kind,target);
            var span=new SourceSpan(doc.identity(),2,11+9*i,2,12+9*i);
            var uncertainty = resolveTarget ? List.<Uncertainty>of() :
                    List.of(new Uncertainty("fixture.unresolved-target","No verified target",List.of("binding for B")));
            var occurrence=RelationshipOccurrence.create(relationship,span,0,status,derivation,uncertainty,List.of());
            if(!publishRelation)continue;
            uses.add(occurrence);observations.add(new ObservationRecord(doc.identity(),kind,Optional.of(span),
                    status,resolveTarget ? ObservationRecord.EvidenceState.VERIFIED : ObservationRecord.EvidenceState.MISSING,
                    ObservationRecord.EvidenceState.VERIFIED,
                    Optional.of(occurrence.identity()),"B",List.of()));
        }
        var coverage=FrontendRequest.CATEGORIES.stream().map(c -> {var k=new RelationshipKind("java."+c);
            long count=k.equals(kind)?uses.size():0;return new CategoryCoverage(k,CategoryCoverage.Support.IMPLEMENTED,count,count,0);
        }).toList();
        var source=occurrences<0?new SourceOutcome(doc.identity(),SourceOutcome.State.ERROR,List.of(new Diagnostic(
                DiagnosticSeverity.ERROR,"fixture.source-unavailable","Fixture source is unavailable",Optional.empty(),Map.of())))
                :new SourceOutcome(doc.identity(),SourceOutcome.State.PROCESSED,List.of());
        var shape=new TypeDeclarationRecord(declarations.getFirst().entity().identity(),TypeDeclarationRecord.Kind.CLASS,
                kindOfA.startsWith("abstract"),true);
        var frontend=new FrontendResult(manifest.identity(),VERSION,occurrences<0?FrontendResult.State.PARTIAL:FrontendResult.State.COMPLETED,
                declarations,uses,observations,List.of(source),coverage,List.of(),List.of(),List.of(),List.of(),List.of(shape),List.of());
        var builder=new CanonicalGraphBuilder();var main=builder.build(manifest,frontend);
        var root=builder.build(new CanonicalGraphInput(new com.evolution.analysis.ingestion.RepositoryInputs(snapshot,
                Map.of(PATH,new SourceInput(doc,java.getBytes(StandardCharsets.UTF_8)))),Optional.empty(),Optional.empty(),List.of(),List.of(),List.of()));
        var testOptions=new TreeMap<>(configuration.values());testOptions.put("java.source-set","TEST");
        testOptions.put("java.source-plan",CanonicalJson.sha256(List.of()).value());
        var test=AnalysisManifest.create(VERSION,snapshot,List.of(module),classpath,AnalysisConfiguration.create(VERSION,testOptions),component,policy,KnowledgeGraph.schemaComponent());
        var empty=FrontendRequest.CATEGORIES.stream().map(c->new CategoryCoverage(new RelationshipKind("java."+c),CategoryCoverage.Support.IMPLEMENTED,0,0,0)).toList();
        var testGraph=builder.build(test,new FrontendResult(test.identity(),VERSION,FrontendResult.State.COMPLETED,List.of(),List.of(),List.of(),List.of(),empty,List.of()));
        var contexts=new ArrayList<>(main.contexts());var nodes=new ArrayList<>(main.nodes());var edges=new ArrayList<>(main.edges());
        var evidence=new TreeMap<ContentDigest,GraphEvidence>();main.evidence().forEach(e->evidence.put(e.identity(),e));
        var contributions=new ArrayList<>(List.of(root,testGraph));
        if(duplicateMain){
            var opts=new TreeMap<>(configuration.values());opts.put("fixture.option","different");
            var extra=AnalysisManifest.create(VERSION,snapshot,List.of(module),classpath,AnalysisConfiguration.create(VERSION,opts),component,policy,KnowledgeGraph.schemaComponent());
            contributions.add(builder.build(extra,new FrontendResult(extra.identity(),VERSION,FrontendResult.State.COMPLETED,declarations,uses,observations,List.of(source),coverage,List.of())));
        }
        for(var g:contributions){contexts.addAll(g.contexts());nodes.addAll(g.nodes());edges.addAll(g.edges());g.evidence().forEach(e->evidence.put(e.identity(),e));}
        return new KnowledgeGraph(snapshot.identity(),contexts,nodes,edges,List.copyOf(evidence.values()));
    }
}
