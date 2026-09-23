package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.input.ConditionTestInputs;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.registration.*;
import com.evolution.analysis.spring.universal.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniversalFrameworkContextTest {
    static final ConditionEvidence E=ConditionTestInputs.EVIDENCE;
    static FrameworkGeneration generation(String spring,String boot) {
        var a=new ArrayList<SpringFrameworkEvidence.Artifact>();
        a.add(new SpringFrameworkEvidence.Artifact("org.springframework:spring-context:"+spring,ContentDigest.sha256Utf8("authored:"+spring)));
        if(!boot.isEmpty())a.add(new SpringFrameworkEvidence.Artifact("org.springframework.boot:spring-boot:"+boot,ContentDigest.sha256Utf8("authored:"+boot)));
        return FrameworkGeneration.from(new SpringFrameworkEvidence(true,Optional.of(ContentDigest.sha256Utf8("authored-manifest")),a),Optional.empty());
    }
    @Test void namespaceOverrideAndMetadataPoliciesCoverBootOneTwoAndThree() {
        var one=generation("4.3.25.RELEASE","1.5.22.RELEASE");
        assertEquals(FrameworkGeneration.Namespace.JAVAX,one.namespace());assertEquals(FrameworkGeneration.OverridePolicy.ALLOW,one.overridePolicy());assertEquals(FrameworkGeneration.MetadataPolicy.FACTORIES,one.metadataPolicy());
        assertEquals(FrameworkGeneration.OverridePolicy.ALLOW,generation("5.0.13.RELEASE","2.0.9.RELEASE").overridePolicy());
        assertEquals(FrameworkGeneration.OverridePolicy.DENY,generation("5.1.19.RELEASE","2.1.18.RELEASE").overridePolicy());
        assertEquals(FrameworkGeneration.MetadataPolicy.BOTH,generation("5.3.31","2.7.18").metadataPolicy());
        var three=generation("6.2.0","3.4.0");assertEquals(FrameworkGeneration.Namespace.JAKARTA,three.namespace());assertEquals(FrameworkGeneration.MetadataPolicy.IMPORTS,three.metadataPolicy());
        assertEquals(FrameworkGeneration.OverridePolicy.ALLOW,generation("5.3.31","").overridePolicy());
        assertFalse(generation("5.3.31","3.4.0").consistent());assertEquals(FrameworkGeneration.Namespace.UNKNOWN,generation("7.0.0","4.0.0").namespace());
    }
    static AutoConfigurationMetadata.Resource resource(String path,String text){return new AutoConfigurationMetadata.Resource(path,text.getBytes(StandardCharsets.UTF_8),UniversalSpringEvidence.derived(ContentDigest.sha256Utf8(text),"authored-resource"));}
    @Test void bothMetadataFormatsPreserveProvenanceVersionFilteringContinuationsAndDeduplication() {
        var resources=List.of(resource("META-INF/spring.factories","org.springframework.boot.autoconfigure.EnableAutoConfiguration=app.One,\\\n app.Two\nother.Key=app.Ignored\n"),
                resource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports","# comment\napp.Two\napp.Three # comment\n"));
        var both=AutoConfigurationMetadata.read(resources,generation("5.3.31","2.7.18"),ConditionTestInputs.context().snapshotIdentity(),10000,100);
        assertEquals(List.of("app.One","app.Two","app.Three"),both.candidates());assertEquals(2,both.rows().size());assertTrue(both.gaps().isEmpty());
        var modern=AutoConfigurationMetadata.read(resources,generation("6.2.0","3.4.0"),ConditionTestInputs.context().snapshotIdentity(),10000,100);
        assertEquals(List.of("app.Two","app.Three"),modern.candidates());assertEquals(AutoConfigurationMetadata.Status.IGNORED_VERSION,modern.rows().getFirst().status());
        var legacy=AutoConfigurationMetadata.read(resources,generation("4.3.25.RELEASE","1.5.22.RELEASE"),ConditionTestInputs.context().snapshotIdentity(),10000,100);
        assertEquals(List.of("app.One","app.Two"),legacy.candidates());
        assertEquals(both.identity(),AutoConfigurationMetadata.read(resources,generation("5.3.31","2.7.18"),ConditionTestInputs.context().snapshotIdentity(),10000,100).identity());
    }
    @Test void malformedAndOverBudgetMetadataHaveClosedRowsAndTypedGaps() {
        var resources=List.of(resource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports","app.Valid\n../../Bad\n"),resource("META-INF/spring.factories","x=y"));
        var result=AutoConfigurationMetadata.read(resources,generation("5.3.31","2.7.18"),ConditionTestInputs.context().snapshotIdentity(),10000,100);
        assertEquals(2,result.rows().size());assertEquals(AutoConfigurationMetadata.Status.INVALID,result.rows().getFirst().status());assertTrue(result.candidates().isEmpty());assertEquals(1,result.gaps().size());
        assertEquals(AutoConfigurationMetadata.Status.LIMITED,AutoConfigurationMetadata.read(resources,generation("5.3.31","2.7.18"),ConditionTestInputs.context().snapshotIdentity(),1,100).rows().getFirst().status());
    }
    static BeanDefinitionCandidate candidate(String name,String context){return new BeanDefinitionCandidate(new BeanProducer(ConditionTestInputs.context().identity(),context,E,BeanProducer.Kind.SUPPLIED_DEFINITION,name,"fixture"),name,BeanDefinitionCandidate.Names.exact(name),List.of());}
    static HierarchicalContexts.Context context(String name,String parent,Map<String,BeanDefinitionCandidate.Identity> definitions,Map<String,String> aliases,boolean complete) {
        var state=new BeanDefinitionState(new DiscoveryTransitions.ContextIdentity("spring-semantics-context:"+ContentDigest.sha256Utf8("fixture-context").value()),name,List.of(),definitions,aliases,complete,true);
        return new HierarchicalContexts.Context(name,Optional.ofNullable(parent),state,E);
    }
    @Test void childNamesAndAliasesShadowParentAndChildrenNeverLeakUpward() {
        var parent=candidate("service","parent");var child=candidate("local","child");var hidden=candidate("service","child");
        var contexts=List.of(context("parent",null,Map.of("service",parent.identity(),"onlyParent",candidate("onlyParent","parent").identity()),Map.of(),true),
                context("child","parent",Map.of("local",child.identity(),"service",hidden.identity()),Map.of("onlyParent","local"),true));
        var visible=HierarchicalContexts.visible(contexts,"child",ConditionExpression.Search.ALL,ConditionTestInputs.context().snapshotIdentity(),10);
        assertTrue(visible.complete());assertEquals(Set.of(child.identity(),hidden.identity()),visible.definitions().stream().map(HierarchicalContexts.Visible::candidate).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,HierarchicalContexts.visible(contexts,"parent",ConditionExpression.Search.ALL,ConditionTestInputs.context().snapshotIdentity(),10).definitions().size());
        assertTrue(HierarchicalContexts.visible(contexts,"parent",ConditionExpression.Search.ALL,ConditionTestInputs.context().snapshotIdentity(),10).definitions().stream().noneMatch(d->d.candidate().equals(child.identity())));
        assertEquals(2,HierarchicalContexts.visible(contexts,"child",ConditionExpression.Search.ANCESTORS,ConditionTestInputs.context().snapshotIdentity(),10).definitions().size());
    }
    @Test void unknownRegistryMissingParentCyclesAndDuplicateContextsWithholdClosure() {
        var registry=context("child","missing",Map.of(),Map.of(),true);
        for(var contexts:List.of(List.of(registry),List.of(context("child","child",Map.of(),Map.of(),true)),List.of(registry,registry),List.of(context("child",null,Map.of(),Map.of(),false)))) {
            var result=HierarchicalContexts.visible(contexts,"child",ConditionExpression.Search.ALL,ConditionTestInputs.context().snapshotIdentity(),10);
            assertFalse(result.complete());assertFalse(result.gaps().isEmpty());
        }
    }
    @Test void hierarchicalSelectionUsesLocalPrimaryAndRejectsUnknownAndConflictingMatches() {
        var local=candidate("local","child");var inherited=candidate("inherited","parent");
        var contexts=List.of(context("parent",null,Map.of("inherited",inherited.identity()),Map.of(),true),context("child","parent",Map.of("local",local.identity()),Map.of(),true));
        var snapshot=ConditionTestInputs.context().snapshotIdentity();var visible=HierarchicalContexts.visible(contexts,"child",ConditionExpression.Search.ALL,snapshot,10);
        var matches=List.of(new HierarchicalContexts.Match(local.identity(),LogicalValue.TRUE,LogicalValue.TRUE,E),new HierarchicalContexts.Match(inherited.identity(),LogicalValue.TRUE,LogicalValue.TRUE,E));
        assertEquals(Optional.of(local.identity()),HierarchicalContexts.select(visible,matches,Optional.empty(),snapshot).selected());
        assertEquals(Optional.of(inherited.identity()),HierarchicalContexts.select(visible,matches,Optional.of("inherited"),snapshot).selected());
        assertEquals(HierarchicalContexts.SelectionStatus.UNKNOWN,HierarchicalContexts.select(visible,List.of(matches.getFirst()),Optional.empty(),snapshot).status());
        assertEquals(HierarchicalContexts.SelectionStatus.UNKNOWN,HierarchicalContexts.select(visible,List.of(matches.getFirst(),matches.getFirst()),Optional.empty(),snapshot).status());
    }
    @Test void primariesInDifferentAncestorFactoriesStillConflictInTheChild() {
        var parent=candidate("parentService","parent");var grandparent=candidate("grandparentService","grandparent");
        var contexts=List.of(context("child","parent",Map.of(),Map.of(),true),
                context("parent","grandparent",Map.of("parentService",parent.identity()),Map.of(),true),
                context("grandparent",null,Map.of("grandparentService",grandparent.identity()),Map.of(),true));
        var snapshot=ConditionTestInputs.context().snapshotIdentity();
        var matches=List.of(new HierarchicalContexts.Match(parent.identity(),LogicalValue.TRUE,LogicalValue.TRUE,E),
                new HierarchicalContexts.Match(grandparent.identity(),LogicalValue.TRUE,LogicalValue.TRUE,E));
        var visibility=HierarchicalContexts.visible(contexts,"child",ConditionExpression.Search.ALL,snapshot,10);
        assertEquals(HierarchicalContexts.SelectionStatus.AMBIGUOUS,HierarchicalContexts.select(visibility,matches,Optional.empty(),snapshot).status());
    }
}
