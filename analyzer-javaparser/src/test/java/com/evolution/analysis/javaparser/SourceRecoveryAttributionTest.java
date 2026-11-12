package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceRecoveryAttributionTest {
    @Test void realPinnedSpringArtifactsLowerPassivePropertyAndProfileDespiteAnUnrelatedMissingType()throws Exception {
        var fixture=new SourceToSpringPlanTest().sourceFixture(Map.of("app/App.java","""
                package app;
                @org.springframework.stereotype.Component
                @org.springframework.context.annotation.Profile("dev")
                @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="enabled")
                class App { Missing unrelated; }
                """),true,true);
        var source=fixture.source();
        var inventory=com.evolution.analysis.spring.SpringMechanismScanner.scan(new com.evolution.analysis.spring.SpringMechanismScanRequest(source.frontend(),source.framework(),List.of(),List.of()));
        var lowered=com.evolution.analysis.spring.condition.ConditionEvidenceLowering.lower(fixture.build(),inventory,List.of(),com.evolution.analysis.spring.condition.ConditionEvidenceLowering.Limits.conservative());
        assertEquals(2,lowered.coverage().loweredRows());assertTrue(lowered.sourceCandidates().isEmpty());
        assertFalse(source.framework().completeClasspath());assertEquals(FrontendResult.State.PARTIAL,source.frontend().state());
    }
    @Test void evidencedComposedStereotypesAndInjectionAnnotationsRetainCandidatesButCyclesDoNot()throws Exception {
        String text="""
                package app;
                @org.springframework.stereotype.Service @interface LocalService {}
                @org.springframework.beans.factory.annotation.Autowired @interface Wire {}
                @LocalService class Store {}
                @LocalService class Client { @Wire Store store; }
                @CycleB @interface CycleA {} @CycleA @interface CycleB {}
                @CycleA class Unproved {}
                """;
        var request=LombokIngestionTest.recovery(ComponentIngestionTest.fixture(Map.of("fixture/App.java",text),false).request());
        var result=new com.evolution.analysis.spring.universal.SourceWiringCandidates().analyze(request,new JavaParserFrontend().analyze(request));
        assertEquals(2,result.beans().size());assertEquals(1,result.matches().size());assertEquals("FIELD",result.matches().getFirst().site().kind());assertEquals("NOT_SELECTED",result.matches().getFirst().selection());
        assertTrue(new JavaParserFrontend().estimatedWorkingBytes(request)>request.sources().stream().mapToLong(s->s.text().length()).sum());
    }
    @Test void exactExternalJarBytesCloseBoundaryCallsWhileOfflineAbsenceStaysUnknown()throws Exception {
        String text="class C { org.slf4j.Logger log; org.springframework.data.repository.CrudRepository<Object,Integer> repo; Object run(){ log.info(\"x\"); return repo.save(\"x\"); } }";
        var base=TestInputs.request(text);var missing=LombokIngestionTest.recovery(base);
        var unavailable=new JavaParserFrontend().analyze(missing);assertTrue(unavailable.observations().stream().filter(o->o.category().value().equals("java.calls")).noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
        var dependencies=new ArrayList<BinaryInput>();
        for(var item:Map.<String,Class<?>>of("org.slf4j:slf4j-api",org.slf4j.Logger.class,"org.springframework.data:spring-data-commons",org.springframework.data.repository.CrudRepository.class).entrySet()) {
            var jar=java.nio.file.Path.of(item.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());var parts=item.getKey().split(":");var properties=new java.util.Properties();
            try(var archive=new java.util.jar.JarFile(jar.toFile());var input=archive.getInputStream(archive.getJarEntry("META-INF/maven/"+parts[0]+"/"+parts[1]+"/pom.properties"))){properties.load(input);}
            String coordinate=item.getKey()+":"+properties.getProperty("version");
            dependencies.add(new BinaryInput(new com.evolution.analysis.contract.analysis.ClasspathEntry(com.evolution.analysis.contract.analysis.ClasspathEntryKind.DEPENDENCY,coordinate+"@jar",com.evolution.analysis.contract.common.ContentDigest.sha256(java.nio.file.Files.readAllBytes(jar))),jar));
        }
        var exact=LombokIngestionTest.recovery(TestInputs.request(base.sources(),base.platform(),dependencies));var acquired=new JavaParserFrontend().analyze(exact);
        var calls=acquired.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")&&o.status()==SemanticStatus.RESOLVED).toList();assertEquals(2,calls.size());
        var targets=calls.stream().map(o->((RelationshipTarget.Resolved)o.relationship().target()).target()).collect(java.util.stream.Collectors.toSet());
        assertEquals(2,acquired.declarations().stream().filter(d->targets.contains(d.entity().identity())&&d.entity().origin()==EntityOrigin.DEPENDENCY).count());
        assertEquals(FrontendResult.State.PARTIAL,acquired.state(),"A recovered build remains partial even when these two calls are proven");
    }
    @Test void repositoryInterfacesRetainCandidateEvidenceWithoutInventingProxyClasses()throws Exception {
        String text="""
                package app;
                class Pet {}
                interface Pets extends org.springframework.data.repository.CrudRepository<Pet,Integer> {}
                @org.springframework.data.repository.NoRepositoryBean interface Base extends org.springframework.data.repository.CrudRepository<Pet,Integer> {}
                @org.springframework.stereotype.Service class Client { Client(Pets pets){} }
                """;
        var base=ComponentIngestionTest.fixture(Map.of("fixture/App.java",text),false).request();var dependencies=new ArrayList<>(base.dependencies());
        var jar=java.nio.file.Path.of(org.springframework.data.repository.CrudRepository.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        dependencies.add(new BinaryInput(new com.evolution.analysis.contract.analysis.ClasspathEntry(com.evolution.analysis.contract.analysis.ClasspathEntryKind.DEPENDENCY,"org.springframework.data:spring-data-commons:3.4.0@jar",com.evolution.analysis.contract.common.ContentDigest.sha256(java.nio.file.Files.readAllBytes(jar))),jar));
        var request=LombokIngestionTest.recovery(TestInputs.request(Map.of("fixture/App.java",text),dependencies));var frontend=new JavaParserFrontend().analyze(request);
        var result=new com.evolution.analysis.spring.universal.SourceWiringCandidates().analyze(request,frontend);
        assertEquals(1,result.beans().stream().filter(b->b.kind().equals("SPRING_DATA_INTERFACE_CANDIDATE")).count());assertEquals(1,result.matches().size());
        assertEquals("SPRING_DATA_INTERFACE_CANDIDATE",result.matches().getFirst().bean().kind());assertEquals("NOT_SELECTED",result.matches().getFirst().selection());
    }
    @Test void openContainerRetainsOnlyEvidencedSourceTypeCandidates()throws Exception {
        String text="""
                package app;
                interface Store {}
                @org.springframework.stereotype.Service class Local implements Store {}
                @org.springframework.stereotype.Service class Other {}
                @org.springframework.stereotype.Service class Client { Client(Store store) {} }
                class Unrelated { Missing dependency; }
                """;
        var request=LombokIngestionTest.recovery(ComponentIngestionTest.fixture(Map.of("fixture/App.java",text),false).request());
        var frontend=new JavaParserFrontend().analyze(request);var result=new com.evolution.analysis.spring.universal.SourceWiringCandidates().analyze(request,frontend);
        assertEquals(3,result.beans().size());assertEquals(1,result.sites().size());assertEquals(1,result.matches().size());
        var match=result.matches().getFirst();assertEquals("UNKNOWN",match.activation());assertEquals("NOT_SELECTED",match.selection());
        var source=new com.evolution.analysis.spring.universal.SpringSourceEvidence(request,frontend,com.evolution.analysis.spring.SpringFrameworkEvidence.fromRequest(request));
        assertEquals("app.Local",source.typeName(match.bean().exposedType()));assertEquals("app.Client",source.typeName(match.site().owner()));assertEquals("app.Store",source.typeName(match.site().type().target().orElseThrow()));
        var fake=LombokIngestionTest.recovery(TestInputs.request("@interface Service {} @Service class Fake { Fake(Fake f){} }"));
        assertFalse(new com.evolution.analysis.spring.universal.SourceWiringCandidates().analyze(fake,new JavaParserFrontend().analyze(fake)).observed());
    }
    @Test void ownFixedZeroArgumentMethodsDoNotNeedUnknownAncestorOverloads() {
        for(String text:List.of("class C extends MissingBase { void helper() {} void run() { helper(); } }",
                "class C extends MissingBase { static void helper() {} void run() { C.helper(); } }")) {
            var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(text)));
            assertEquals(1,result.observations().stream().filter(o->o.category().value().equals("java.calls")&&o.attribution()==SemanticStatus.RESOLVED).count());
            assertEquals(FrontendResult.State.PARTIAL,result.state());
        }
    }
    @Test void incompatiblePlatformViewCannotAttributeJdkTargetsInRecovery() {
        var base=LombokIngestionTest.recovery(TestInputs.request("class C { String text() { return \"x\".trim(); } }"));
        var old=base.manifest();var options=new TreeMap<>(old.configuration().values());options.put(FrontendRequest.RECOVERY_PLATFORM_COMPATIBLE,"false");
        var manifest=com.evolution.analysis.contract.analysis.AnalysisManifest.create(old.manifestVersion(),old.snapshot(),old.modules(),old.classpath(),
                com.evolution.analysis.contract.analysis.AnalysisConfiguration.create(old.configuration().schema(),options),old.analyzer(),old.ruleSet(),old.graphSchema());
        var request=new FrontendRequest(manifest,base.module(),base.sourceSet(),base.plan(),base.sources(),base.platform(),base.dependencies());
        var result=new JavaParserFrontend().analyze(request);
        assertTrue(result.declarations().stream().anyMatch(d->d.entity().origin()==EntityOrigin.PROJECT&&d.entity().kind()==EntityKind.TYPE));
        assertTrue(result.declarations().stream().noneMatch(d->d.entity().origin()==EntityOrigin.JDK));
        assertTrue(result.observations().stream().filter(o->o.category().value().equals("java.calls")).noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
    }
    @Test void knownSpringDeclarationsAndConstructorSitesSurviveIncompleteBuildClosure()throws Exception {
        var fixture=ComponentIngestionTest.fixture(Map.of("fixture/App.java","""
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication class App {}
                @org.springframework.stereotype.Component class Store {}
                @org.springframework.stereotype.Component class Client { Client(Store store) {} }
                class Unrelated { Missing dependency; }
                """),false);
        var request=LombokIngestionTest.recovery(fixture.request());var frontend=new JavaParserFrontend().analyze(request);
        var framework=com.evolution.analysis.spring.SpringFrameworkEvidence.fromRequest(request);
        assertFalse(framework.completeClasspath());assertFalse(framework.acceptedConditionFragment(false));
        var components=new com.evolution.analysis.spring.condition.ComponentScanIngestion().scan(request,frontend,framework,100);
        assertEquals(3,components.rows().stream().filter(r->r.status()==com.evolution.analysis.spring.condition.ComponentScanIngestion.Status.INCLUDED).count());
        var constructors=new com.evolution.analysis.spring.condition.ConstructorInjectionIngestion().ingest(frontend,components,framework,100);
        assertTrue(constructors.rows().stream().anyMatch(r->r.status()==com.evolution.analysis.spring.condition.ConstructorInjectionIngestion.Status.SELECTED&&r.parameters().size()==1));
        assertFalse(components.gaps().isEmpty(),"Missing global closure is still reported");
    }
    @Test void missingUnrelatedLibraryDoesNotDisableSourceAndPlatformBindings() {
        var request=LombokIngestionTest.recovery(TestInputs.request(Map.of(
                "fixture/A.java","package fixture; @Marker class A extends B implements I { Missing unavailable; B field; B run(B b) { b.work(); return b; } String text() { return \"x\".trim(); } }",
                "fixture/B.java","package fixture; class B { void work() {} } interface I {} @interface Marker {}"),List.of()));
        var result=new JavaParserFrontend().analyze(request);
        assertEquals(FrontendResult.State.PARTIAL,result.state());
        for(String category:List.of("calls","extends","implements","field-type","parameter-type","returns","annotated-with"))
            assertTrue(result.observations().stream().anyMatch(o->o.category().value().equals("java."+category)&&o.attribution()==SemanticStatus.RESOLVED),category);
        var call=result.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")&&o.status()==SemanticStatus.RESOLVED).toList();
        assertEquals(2,call.size());
        var targets=call.stream().map(o->((RelationshipTarget.Resolved)o.relationship().target()).target()).toList();
        assertTrue(result.declarations().stream().anyMatch(d->targets.contains(d.entity().identity())&&d.entity().origin()==EntityOrigin.PROJECT&&d.entity().canonicalName().contains("\"work\"")));
        assertTrue(result.declarations().stream().anyMatch(d->targets.contains(d.entity().identity())&&d.entity().origin()==EntityOrigin.JDK&&d.entity().canonicalName().contains("\"trim\"")));
        assertTrue(result.observations().stream().anyMatch(o->o.reference().equals("Missing")&&o.attribution()==SemanticStatus.UNRESOLVED));
        assertEquals(result,new JavaParserFrontend().analyze(request));
    }
    @Test void missingReceiverAndUnknownOverloadsNeverBecomeResolvedCalls() {
        for(var fixture:Map.of(
                "class C extends MissingBase { void run(Missing value) { value.work(); pick(null); } void pick(String s) {} void pick(Missing s) {} }",2,
                "class C { void pick(Object value) {} void pick(Missing value) {} void run() { pick(\"x\"); } }",1,
                "class C extends MissingBase { void pick(Object value) {} void run() { pick(\"x\"); } }",1,
                "class C implements MissingInterface { void pick(Object value) {} void run() { pick(\"x\"); } }",1).entrySet()) {
            var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(fixture.getKey())));
            var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")).toList();
            assertEquals(fixture.getValue().intValue(),calls.size());
            assertTrue(calls.stream().noneMatch(o->o.attribution()==SemanticStatus.RESOLVED),fixture.getKey());
        }
    }
    @Test void uncertainInnerReceiverCannotProvideAnExactOuterCallBinding() {
        var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(
                "class A { void act() {} } class C extends MissingBase { A pick(Object value) { return null; } void run() { pick(\"x\").act(); } }")));
        var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")).toList();
        assertEquals(2,calls.size());assertTrue(calls.stream().noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
        var known=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(
                "class A { A next() { return this; } void act() {} } class C { void run(A a) { a.next().act(); } }")));
        assertEquals(2,known.observations().stream().filter(o->o.category().value().equals("java.calls")&&o.attribution()==SemanticStatus.RESOLVED).count());
    }
    @Test void unknownAncestorsCannotSupplyExactInheritedReferences() {
        var references=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(
                "class C extends MissingBase { void pick(Object value) {} void run() { java.util.function.Consumer<String> a=this::pick; java.util.function.BiConsumer<C,String> b=C::pick; } }")));
        var methods=references.observations().stream().filter(o->o.category().value().equals("java.method-references")).toList();
        assertEquals(2,methods.size());assertTrue(methods.stream().noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
        var fields=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(
                "class Base { static int VALUE; } class C extends Base implements MissingInterface { int run() { return VALUE; } }")));
        var reads=fields.observations().stream().filter(o->o.category().value().equals("java.reads-field")).toList();
        assertEquals(1,reads.size());assertTrue(reads.stream().noneMatch(o->o.attribution()==SemanticStatus.RESOLVED));
    }
    @Test void explicitImportsRetainExternalObligationsWithoutInventingMembersOrOrigins() {
        String source = """
                package app;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import com.external.sdk.ExternalClient;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class Service {
                    private final Logger log = LoggerFactory.getLogger(Service.class);
                    void execute(String host) {
                        ExternalClient client = new ExternalClient(host);
                        log.info("connecting");
                        client.send(host);
                        assertEquals("ok", host);
                    }
                }
                class Child extends ExternalClient {
                    void helper(Object value) {}
                    void run() {
                        helper("still-guarded");
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
        var calls = result.observations().stream().filter(o -> o.category().value().equals("java.calls")).toList();
        assertEquals(5, calls.size());
        assertEquals(0, calls.stream().filter(o -> o.attribution() == SemanticStatus.RESOLVED).count());
        assertEquals(1, calls.stream().filter(o -> o.attribution() == SemanticStatus.UNRESOLVED && o.reference().contains("helper")).count());
        var ctors = result.observations().stream().filter(o -> o.category().value().equals("java.constructor-calls")).toList();
        assertTrue(ctors.stream().anyMatch(o -> o.attribution() == SemanticStatus.UNRESOLVED && o.reference().contains("new ExternalClient")));
        assertTrue(result.declarations().stream().noneMatch(d ->
                d.entity().origin() == EntityOrigin.DEPENDENCY
                && d.derivation().method().id().equals("java.explicit-import-recovery")));
    }
    @Test void androidSdkAndGeneratedMembersRequireEvidenceRatherThanSyntheticDeclarations() {
        String source = """
                package com.example.app;
                import android.content.Intent;
                import android.os.Bundle;
                import android.os.IBinder;
                import android.widget.Toast;
                import androidx.appcompat.app.AppCompatActivity;
                import com.example.app.databinding.ActivityMainBinding;
                import com.example.ipc.IRemoteService;
                public class MainActivity extends AppCompatActivity {
                    private ActivityMainBinding binding;
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        binding = ActivityMainBinding.inflate(getLayoutInflater());
                        setContentView(binding.getRoot());
                        if (BuildConfig.DEBUG) {
                            int layoutId = R.layout.activity_main;
                            int btnId = R.id.submit_button;
                            binding.submitButton.setText(getString(R.string.app_name));
                            Toast.makeText(this, String.valueOf(layoutId + btnId), Toast.LENGTH_SHORT).show();
                        }
                    }
                    public String callRemote(IBinder binder) {
                        IRemoteService service = IRemoteService.Stub.asInterface(binder);
                        return service.fetchStatus();
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
        var unresolvedCalls = result.observations().stream()
                .filter(o -> o.category().value().equals("java.calls") && o.attribution() != SemanticStatus.RESOLVED)
                .toList();
        assertTrue(unresolvedCalls.stream().anyMatch(o -> o.reference().contains("setContentView")));
        assertTrue(unresolvedCalls.stream().anyMatch(o -> o.reference().contains("fetchStatus")));
        var unresolvedFields = result.observations().stream()
                .filter(o -> o.category().value().equals("java.reads-field") && o.attribution() != SemanticStatus.RESOLVED)
                .toList();
        assertTrue(unresolvedFields.stream().anyMatch(o -> o.reference().contains("BuildConfig.DEBUG")));
        assertTrue(unresolvedFields.stream().anyMatch(o -> o.reference().contains("R.layout.activity_main")));
        assertTrue(result.declarations().stream().noneMatch(d ->
                d.entity().origin() == EntityOrigin.PROJECT
                && d.derivation().method().id().equals("java.android-synthetic-recovery")));
    }

    @Test void externalWildcardAndOverloadedJdkCallsRetainTheirMissingTypeObligations() {
        String source = """
                package com.example.arch;
                import java.util.*;
                import com.custom.thirdparty.framework.*;
                import org.novel.annotations.stereotype.*;
                @NovelComponent
                public class OrderProcessor implements ExternalProcessor<String> {
                    public String format(ExternalPayload payload) {
                        return String.valueOf(payload);
                    }
                }
                """;
        var result = new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
        var structureCategories = Set.of(
                "java.extends", "java.implements", "java.field-type",
                "java.parameter-type", "java.returns", "java.annotated-with", "java.type-argument");
        var unresolvedStructure = result.observations().stream()
                .filter(o -> structureCategories.contains(o.category().value()) && o.attribution() != SemanticStatus.RESOLVED)
                .toList();
        assertEquals(Set.of("NovelComponent", "ExternalProcessor<String>", "ExternalPayload", "String"),
                unresolvedStructure.stream().map(o -> o.reference().replaceFirst("^@", "")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(unresolvedStructure.stream().filter(o -> o.reference().equals("String")).allMatch(o -> o.mappedOccurrence().isEmpty()),
                "The known return type cannot acquire a guessed callable owner identity");
        var unresolvedCalls = result.observations().stream()
                .filter(o -> o.category().value().equals("java.calls") && o.attribution() != SemanticStatus.RESOLVED)
                .toList();
        assertEquals(1, unresolvedCalls.size());
        assertEquals("String.valueOf(payload)", unresolvedCalls.getFirst().reference());
    }
}
