package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticQueryServiceTest {
    record Fixture(SemanticContextCoordinatorTest setup,SemanticContextCoordinator.Plan plan,SemanticContextCoordinator.Root result,AtomicReference<SemanticQueryService.Publication> publication,AtomicReference<ContentDigest> policy,SemanticQueryService query){}
    Fixture fixture()throws Exception{
        var setup=new SemanticContextCoordinatorTest();var first=setup.request("a");var second=setup.request("b");var plan=setup.plan(List.of(new SemanticContextCoordinator.Selection("a",first),new SemanticContextCoordinator.Selection("b",second)));var root=setup.coordinator.run(plan,r->r.module().equals("a")?Optional.of((request,cancel)->setup.output(request)):Optional.empty(),r->Map.of(),Map.of(),Set.of(),plan::policy,c->{},()->false);
        var publication=new AtomicReference<>(new SemanticQueryService.Publication(root.report(),plan.identity(),plan.policy(),root.report(),root.contexts(),1));var policy=new AtomicReference<>(plan.policy());var query=new SemanticQueryService((id,cancel)->id.equals(plan.identity())?Optional.of(publication.get()):Optional.empty(),setup.fixture.content,setup.fixture.tables,policy::get);return new Fixture(setup,plan,root,publication,policy,query);
    }
    @Test void pagesKeepMissingContextTypedGapsAndUnqualifiedRootDenominator()throws Exception{
        var f=fixture();var page=f.query().contexts(f.plan().identity(),1,Optional.empty(),()->false);assertEquals("2",page.summary().selected());assertEquals("1",page.summary().missingProviders());assertEquals("2",page.summary().incomplete());assertFalse(page.summary().complete());assertEquals("UNQUALIFIED",page.summary().qualification());assertEquals("ROOT_SOURCE_CONTEXT_CENSUS_METHOD_UNQUALIFIED",page.summary().rootGap());assertEquals("PARTIAL",page.items().getFirst().state());assertTrue(page.continuation().isPresent());
        var second=f.query().contexts(f.plan().identity(),1,page.continuation(),()->false);assertEquals("b",second.items().getFirst().key());assertEquals("MISSING_PROVIDER",second.items().getFirst().state());assertTrue(second.continuation().isEmpty());var gaps=f.query().gaps(f.plan().identity(),"b",50,Optional.empty(),()->false);assertEquals(1,gaps.items().size());assertEquals("SEMANTIC_PROVIDER_NOT_REGISTERED",gaps.items().getFirst().get("reasonCode"));
        assertThrows(IOException.class,()->f.query().gaps(f.plan().identity(),"absent",1,Optional.empty(),()->false));assertThrows(IllegalArgumentException.class,()->f.query().contexts(f.plan().identity(),51,Optional.empty(),()->false));
    }
    @Test void staleForgedCrossTableCursorsPolicyChangesAndCancellationAreRejected()throws Exception{
        var f=fixture();var cursor=f.query().contexts(f.plan().identity(),1,Optional.empty(),()->false).continuation();assertThrows(IOException.class,()->f.query().gaps(f.plan().identity(),"a",1,cursor,()->false));
        var old=f.publication().get();f.publication().set(new SemanticQueryService.Publication(f.setup().fixture.content.text("new publication"),old.plan(),old.policy(),old.report(),old.contexts(),2));assertThrows(IOException.class,()->f.query().contexts(f.plan().identity(),1,cursor,()->false));f.publication().set(old);
        var forged=Optional.of(new SemanticQueryService.Cursor(old.document().digest(),new SemanticTables.Cursor(old.contexts().identity(),1,Optional.of("wrong"))));assertThrows(IOException.class,()->f.query().contexts(f.plan().identity(),1,forged,()->false));f.policy().set(ContentDigest.sha256Utf8("revoked"));assertThrows(IOException.class,()->f.query().summary(f.plan().identity(),()->false));f.policy().set(old.policy());assertThrows(IOException.class,()->f.query().contexts(f.plan().identity(),1,Optional.empty(),()->true));
    }
    @Test void cursorCountsRemainExactAboveJavascriptIntegerPrecision(){
        var cursor=new SemanticQueryService.Cursor(ContentDigest.sha256Utf8("publication"),new SemanticTables.Cursor(ContentDigest.sha256Utf8("table"),9007199254740993L,Optional.of("last")));String json=com.evolution.analysis.contract.serialization.CanonicalJson.write(cursor);assertTrue(json.contains("\"rows\":\"9007199254740993\""));assertEquals(cursor,SemanticQueryService.cursor(com.evolution.analysis.graph.GraphJson.read(json,com.evolution.analysis.graph.GraphJson.Limits.defaults())));
        assertThrows(IllegalArgumentException.class,()->new SemanticQueryService.Position(cursor.table().table(),"01",Optional.of("last")));assertThrows(IllegalArgumentException.class,()->new SemanticQueryService.Position(cursor.table().table(),"9223372036854775808",Optional.of("last")));
    }
    @Test void legacyRootGapRemainsQueryableWithItsOwnCursorScope()throws Exception{
        var f=fixture();var root=f.query().rootGaps(f.plan().identity(),1,Optional.empty(),()->false);assertEquals(1,root.items().size());assertEquals("ROOT_SOURCE_CONTEXT_CENSUS_METHOD_UNQUALIFIED",root.items().getFirst().get("reasonCode"));assertTrue(root.continuation().isEmpty());assertFalse(root.summary().complete());
        var context=f.query().contexts(f.plan().identity(),1,Optional.empty(),()->false);assertThrows(IOException.class,()->f.query().rootGaps(f.plan().identity(),1,context.continuation(),()->false));
    }
    @Test void publishedQuestionHistoryAndConflictViewsHaveDistinctCursorScopes()throws Exception{
        var f=fixture();var context=f.query().contexts(f.plan().identity(),1,Optional.empty(),()->false).items().getFirst();assertEquals("CAPTURED_PRODUCER_VIEW",context.answers().get("availability"));assertEquals("1",context.answers().get("historyRevisions"));assertEquals(false,context.answers().get("complete"));var current=f.query().answers(f.plan().identity(),"a","CURRENT",1,Optional.empty(),()->false);assertEquals("NARROWED",com.evolution.analysis.graph.GraphJson.object(current.items().getFirst().get("fields")).get("state"));assertTrue(current.continuation().isPresent());var history=f.query().answers(f.plan().identity(),"a","HISTORY",50,Optional.empty(),()->false);assertEquals(1,history.items().size());assertTrue(f.query().answers(f.plan().identity(),"a","CONFLICTS",50,Optional.empty(),()->false).items().isEmpty());assertThrows(IOException.class,()->f.query().answers(f.plan().identity(),"a","HISTORY",1,current.continuation(),()->false));assertThrows(IOException.class,()->f.query().answers(f.plan().identity(),"b","CURRENT",1,Optional.empty(),()->false));
    }
}
