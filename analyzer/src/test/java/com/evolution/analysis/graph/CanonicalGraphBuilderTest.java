package com.evolution.analysis.graph;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalGraphBuilderTest {
    static final VersionedIdentifier VERSION = new VersionedIdentifier("graph.fixture", "1");
    static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/graph.git");

    static AnalysisManifest manifest() {
        var a = ModuleDescriptor.create(REPO, "a", "a");
        var b = ModuleDescriptor.create(REPO, "b", "b");
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, List.of(), List.of());
        var component = new ManifestComponent(VERSION, ContentDigest.sha256Utf8("fixture"));
        return AnalysisManifest.create(VERSION, snapshot, List.of(a,b), List.of(),
                AnalysisConfiguration.create(VERSION, Map.of()), component, component, component);
    }

    static FrontendResult frontend(AnalysisManifest manifest) {
        var declarations = manifest.modules().stream().map(m -> new DeclarationRecord(
                Entity.create(EntityOrigin.PROJECT, EntityScope.project(m.identity()), EntityKind.TYPE,
                        JavaSymbolName.topLevelType("p", "Same").canonicalName(), Optional.empty()),
                "Same", SemanticStatus.RESOLVED, new Derivation(DerivationKind.DIRECT, VERSION, List.of()),
                List.of(), List.of())).toList();
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(
                new RelationshipKind("java."+c), CategoryCoverage.Support.IMPLEMENTED,0,0,0)).toList();
        return new FrontendResult(manifest.identity(), VERSION, FrontendResult.State.COMPLETED,
                declarations,List.of(),List.of(),List.of(),coverage,List.of());
    }

    @Test void preservesSameNameAcrossModules() {
        var manifest = manifest();
        var frontend = frontend(manifest);
        var graph = new CanonicalGraphBuilder().build(manifest, frontend);
        var types=graph.nodes().stream().filter(n->n.kind().equals("TYPE")).toList();
        assertEquals(2, types.size());
        assertEquals(2, types.stream().map(KnowledgeGraph.Node::identity).distinct().count());
        assertTrue(graph.entityIdentities().contains(frontend.declarations().getFirst().entity().identity().value()));
    }
    @Test void sameCanonicalNameKeepsProjectDependencyAndJdkScopesDistinct() {
        var manifest=manifest();var original=frontend(manifest);var declarations=new ArrayList<>(original.declarations());
        for(var origin:List.of(EntityOrigin.DEPENDENCY,EntityOrigin.JDK)) {
            var scope=EntityScope.external(origin,"authored-origin",ContentDigest.sha256Utf8(origin.name()));
            var entity=Entity.create(origin,scope,EntityKind.TYPE,JavaSymbolName.topLevelType("p","Same").canonicalName(),Optional.empty());
            declarations.add(new DeclarationRecord(entity,"Same",SemanticStatus.RESOLVED,new Derivation(DerivationKind.DIRECT,VERSION,List.of()),List.of(),List.of()));
        }
        var frontend=new FrontendResult(manifest.identity(),VERSION,FrontendResult.State.COMPLETED,declarations,List.of(),List.of(),List.of(),original.coverage(),List.of());
        var graph=new CanonicalGraphBuilder().build(manifest,frontend);
        assertEquals(4,graph.nodes().stream().filter(n->n.kind().equals("TYPE")).map(KnowledgeGraph.Node::identity).distinct().count());
    }
}
