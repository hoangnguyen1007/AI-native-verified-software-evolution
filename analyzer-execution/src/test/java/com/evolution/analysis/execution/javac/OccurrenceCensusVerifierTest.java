package com.evolution.analysis.execution.javac;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.contract.semantic.*;
import java.util.*;
import java.io.IOException;
import com.evolution.analysis.graph.GraphJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OccurrenceCensusVerifierTest {
    @TempDir Path temp;
    CapturedJavacReaderTest data;
    SourceDocumentIdentity id=SourceDocumentIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:evo:census-test"),"Input.java");
    List<SourceInput> sources(){var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:census-test");var module=ModuleDescriptor.create(repository,".","fixture");byte[] bytes="class Input {}\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);return List.of(new SourceInput(SourceDocument.create(repository,module,"Input.java",ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));}
    FrontendResult frontend(){
        return new FrontendResult(AnalysisIdentity.fromCanonicalManifestInputs("independent analysis"),new VersionedIdentifier("independent.test","1"),FrontendResult.State.COMPLETED,
                List.of(),List.of(),List.of(),List.of(new SourceOutcome(id,SourceOutcome.State.PROCESSED,List.of())),
                FrontendRequest.CATEGORIES.stream().map(c->new CategoryCoverage(new com.evolution.analysis.contract.semantic.RelationshipKind("java."+c),CategoryCoverage.Support.IMPLEMENTED,0,0,0)).toList(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
    @Test void twelveUnavailableCategoriesCannotBecomeAClosedZeroCensus()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();var captured=data.read();
        var result=new OccurrenceCensusVerifier().verify(captured,frontend(),Map.of(id,CapturedJavacReaderTest.URI),sources());
        assertFalse(result.censusClosed());assertEquals(12,result.report().get("unavailableNativeCategories"));
        assertEquals(18,GraphJson.object(result.report().get("categories")).size());assertEquals(false,result.report().get("semanticAttestation"));
    }
    @Test void emptySuccessfulIndependentProviderCannotHideANativeOccurrence()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();data.reference();
        var result=new OccurrenceCensusVerifier().verify(data.read(),frontend(),Map.of(id,CapturedJavacReaderTest.URI),sources());
        var category=GraphJson.object(GraphJson.object(result.report().get("categories")).get("java.type-uses"));
        assertEquals("LOCATION_DISAGREEMENT",category.get("disposition"));assertEquals(1,GraphJson.array(category.get("nativeOnly")).size());assertFalse(result.censusClosed());
    }
    @Test void selfCertificationAndForeignSourceDenominatorsAreRejected()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();var captured=data.read();var result=frontend();
        var self=new FrontendResult(result.analysis(),new VersionedIdentifier("isolated.javac-index","other-version"),result.state(),result.declarations(),result.occurrences(),result.observations(),result.sources(),result.coverage(),result.diagnostics(),result.types(),result.annotations(),result.derivedRelationships(),result.typeDeclarations(),result.memberDeclarations());
        assertThrows(IOException.class,()->new OccurrenceCensusVerifier().verify(captured,self,Map.of(id,CapturedJavacReaderTest.URI),sources()));
        assertThrows(IOException.class,()->new OccurrenceCensusVerifier().verify(captured,result,Map.of(id,"file:///foreign/Input.java"),sources()));
        assertThrows(IOException.class,()->new OccurrenceCensusVerifier().verify(captured,result,Map.of(id,CapturedJavacReaderTest.URI),List.of()));
    }
    @Test void matchingDocumentNamesCannotHideChangedSourceBytes()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();var original=sources().getFirst();byte[] changed="class Input { int injected; }\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var document=new SourceDocument(original.document().identity(),original.document().repository(),original.document().module(),original.document().path(),ContentDigest.sha256(changed),original.document().classification());
        assertThrows(IOException.class,()->new OccurrenceCensusVerifier().verify(data.read(),frontend(),Map.of(id,CapturedJavacReaderTest.URI),List.of(new SourceInput(document,changed))));
    }
    @Test void equalLocationsCannotCloseFailedCanceledOrIncompleteTraversals()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();data.raw.put("schema","java-native-selective-index:m8.4-v2");data.raw.put("provider","isolated.javac-index:m8.4-v2");
        var categories=new TreeMap<String,Object>();for(String category:CapturedJavacReader.CATEGORIES)categories.put(category,Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",0));data.raw.put("categories",categories);
        var verified=new OccurrenceCensusVerifier();var successful=frontend();var uri=Map.of(id,CapturedJavacReaderTest.URI);
        assertTrue(verified.verify(data.read(),successful,uri,sources()).censusClosed()); // Count agreement only; never a semantic attestation.
        for(var state:List.of(FrontendResult.State.FAILED,FrontendResult.State.CANCELED,FrontendResult.State.PARTIAL,FrontendResult.State.INVALID_INPUT)){
            var incomplete=new FrontendResult(successful.analysis(),successful.frontend(),state,successful.declarations(),successful.occurrences(),successful.observations(),successful.sources(),successful.coverage(),successful.diagnostics());
            assertFalse(verified.verify(data.read(),incomplete,uri,sources()).censusClosed(),state.name());
        }
        data.raw.put("failure","INDEX_WORKER_CAPACITY_REQUIRED");assertFalse(verified.verify(data.read(),successful,uri,sources()).censusClosed());
        data.raw.put("failure",null);data.raw.put("state","ERROR");assertFalse(verified.verify(data.read(),successful,uri,sources()).censusClosed());
    }

    FrontendResult singleTypeUse(String name,EntityOrigin origin){
        var source=sources().getFirst();var scope=origin==EntityOrigin.PROJECT?EntityScope.project(source.document().module())
                :EntityScope.external(origin,"foreign artifact",source.document().contentDigest());
        var entity=Entity.create(origin,scope,EntityKind.TYPE,JavaSymbolName.topLevelType("",name).canonicalName(),Optional.empty());
        return singleObservation("java.type-uses",entity,entity);
    }
    FrontendResult singleObservation(String category,Entity entity,Entity caller){
        var method=new VersionedIdentifier("independent.test","1");var derivation=new Derivation(DerivationKind.DIRECT,method,List.of());
        var span=new SourceSpan(id,1,7,1,12);var relation=SemanticRelationship.create(caller.identity(),new RelationshipKind(category),new RelationshipTarget.Resolved(entity.identity()));
        var occurrence=RelationshipOccurrence.create(relation,span,0,SemanticStatus.RESOLVED,derivation,List.of(),List.of());
        var observation=new ObservationRecord(id,relation.kind(),Optional.of(span),SemanticStatus.RESOLVED,ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(occurrence.identity()),"fixture reference",List.of());
        var declarations=new ArrayList<DeclarationRecord>();for(var item:new TreeSet<>(List.of(entity,caller)))declarations.add(new DeclarationRecord(item,"fixture",SemanticStatus.RESOLVED,derivation,List.of(),List.of()));
        var empty=frontend();return new FrontendResult(empty.analysis(),method,empty.state(),declarations,List.of(occurrence),List.of(observation),empty.sources(),
                empty.coverage().stream().map(c->new CategoryCoverage(c.category(),c.support(),c.category().equals(relation.kind())?1:0,c.category().equals(relation.kind())?1:0,0)).toList(),List.of());
    }
    @Test void identicalPositionsDoNotCertifyADifferentTargetOrOrigin()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();data.reference();
        var verifier=new OccurrenceCensusVerifier();var uris=Map.of(id,CapturedJavacReaderTest.URI);
        var changed=verifier.verify(data.read(),singleTypeUse("Other",EntityOrigin.PROJECT),uris,sources());
        var checks=GraphJson.object(changed.report().get("endpointComparison"));
        assertEquals(1L,checks.get("targetSignatureDisagreements"));assertEquals(1L,checks.get("callerSignatureDisagreements"));
        var changedOrigin=verifier.verify(data.read(),singleTypeUse("Input",EntityOrigin.DEPENDENCY),uris,sources());
        assertEquals(1L,GraphJson.object(changedOrigin.report().get("endpointComparison")).get("targetOriginDisagreements"));
        assertFalse((Boolean)changedOrigin.report().get("semanticAttestation"));
    }
    @Test void agreeingSourceEndpointsStillDoNotMintASemanticClaim()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();data.reference();
        var result=new OccurrenceCensusVerifier().verify(data.read(),singleTypeUse("Input",EntityOrigin.PROJECT),Map.of(id,CapturedJavacReaderTest.URI),sources());
        var checks=GraphJson.object(result.report().get("endpointComparison"));assertEquals(1L,checks.get("targetSignatureAgreements"));assertEquals(1L,checks.get("callerSignatureAgreements"));
        assertEquals(0L,checks.get("originProofRequired"));assertEquals(false,checks.get("semanticAttestation"));assertEquals(false,result.report().get("semanticAttestation"));
    }
    @Test void overloadParametersAndArrayRanksAreComparedWithoutGuessingReturnTypes()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();var row=data.reference();
        var nativeMethod=new TreeMap<>((Map<String,Object>)row.get("target"));nativeMethod.put("kind","METHOD");nativeMethod.put("name","accept");nativeMethod.put("descriptor","([[I)V");row.put("target",nativeMethod);
        var original=singleTypeUse("Input",EntityOrigin.PROJECT).declarations().getFirst().entity();var owner=JavaSymbolName.topLevelType("","Input");
        for(int rank:List.of(1,2)){
            var target=Entity.create(EntityOrigin.PROJECT,original.stableScope(),EntityKind.METHOD,JavaSymbolName.method(owner,"accept",List.of(ErasedType.array(ErasedType.primitive("int"),rank))).canonicalName(),Optional.empty());
            var result=new OccurrenceCensusVerifier().verify(data.read(),singleObservation("java.type-uses",target,original),Map.of(id,CapturedJavacReaderTest.URI),sources());
            var checks=GraphJson.object(result.report().get("endpointComparison"));assertEquals(rank==2?1L:0L,checks.get("targetSignatureAgreements"));assertEquals(rank==1?1L:0L,checks.get("targetSignatureDisagreements"));
        }
    }
    @Test void missingPackageArtifactsAndMalformedSignaturesStayExplicit()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();var row=data.reference();
        var pkg=new TreeMap<String,Object>();pkg.put("kind","PACKAGE");pkg.put("name","");pkg.put("owner","");pkg.put("module","");pkg.put("modelOrigin","EXPLICIT");pkg.put("artifact",null);pkg.put("descriptor",null);row.put("caller",pkg);
        var original=singleTypeUse("Input",EntityOrigin.PROJECT).declarations().getFirst().entity();
        var caller=Entity.create(EntityOrigin.PROJECT,original.stableScope(),EntityKind.PACKAGE,JavaSymbolName.packageName("").canonicalName(),Optional.empty());
        var result=new OccurrenceCensusVerifier().verify(data.read(),singleObservation("java.type-uses",original,caller),Map.of(id,CapturedJavacReaderTest.URI),sources());
        assertEquals(1L,GraphJson.object(result.report().get("endpointComparison")).get("originProofRequired"));
        var malformed=Entity.create(EntityOrigin.PROJECT,original.stableScope(),EntityKind.TYPE,"java:v1:[]",Optional.empty());
        result=new OccurrenceCensusVerifier().verify(data.read(),singleObservation("java.type-uses",malformed,caller),Map.of(id,CapturedJavacReaderTest.URI),sources());
        assertEquals(1L,GraphJson.object(result.report().get("endpointComparison")).get("targetSignatureUnavailable"));
    }
    @Test void compilerMandatedSourceConstructorsNeedCorrespondenceBeforeComparingOrigins()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();var row=data.reference();
        var nativeConstructor=new TreeMap<>((Map<String,Object>)row.get("target"));nativeConstructor.put("kind","CONSTRUCTOR");nativeConstructor.put("name","<init>");nativeConstructor.put("descriptor","()V");nativeConstructor.put("modelOrigin","MANDATED");row.put("target",nativeConstructor);
        var caller=singleTypeUse("Input",EntityOrigin.PROJECT).declarations().getFirst().entity();
        var target=Entity.create(EntityOrigin.PROJECT,caller.stableScope(),EntityKind.CONSTRUCTOR,JavaSymbolName.constructor(JavaSymbolName.topLevelType("","Input"),List.of()).canonicalName(),Optional.empty());
        var result=new OccurrenceCensusVerifier().verify(data.read(),singleObservation("java.type-uses",target,caller),Map.of(id,CapturedJavacReaderTest.URI),sources());
        var checks=GraphJson.object(result.report().get("endpointComparison"));assertEquals(0L,checks.get("targetSignatureAgreements"));assertEquals(1L,checks.get("targetSignatureUnavailable"));assertEquals(1L,checks.get("originProofRequired"));
    }
    @Test void duplicateCanonicalLocationsCannotReuseOneNativeEndpoint()throws Exception {
        data=new CapturedJavacReaderTest();data.temp=temp;data.setup();data.reference();
        var first=singleTypeUse("Input",EntityOrigin.PROJECT);var second=singleTypeUse("Other",EntityOrigin.PROJECT);
        var declarations=new ArrayList<>(first.declarations());declarations.addAll(second.declarations());var occurrences=new ArrayList<>(first.occurrences());occurrences.addAll(second.occurrences());var observations=new ArrayList<>(first.observations());observations.addAll(second.observations());
        var duplicate=new FrontendResult(first.analysis(),first.frontend(),first.state(),declarations,occurrences,observations,first.sources(),first.coverage().stream().map(c->new CategoryCoverage(c.category(),c.support(),c.attempted()*2,c.emitted()*2,0)).toList(),List.of());
        var result=new OccurrenceCensusVerifier().verify(data.read(),duplicate,Map.of(id,CapturedJavacReaderTest.URI),sources());
        assertFalse(result.censusClosed());assertEquals(0L,GraphJson.object(result.report().get("endpointComparison")).get("comparedSites"));assertEquals(2L,GraphJson.object(result.report().get("endpointComparison")).get("unmatchedOrDuplicateSites"));
    }
}
