package com.evolution.analysis.execution.javac;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Captured-data controls; no target compiler request executes on the host. */
class CapturedJavacReaderTest {
    @TempDir Path temp;
    StreamingContentStore store;
    ContentHandle source;
    static final String URI="file:///opt/evo-inputs/"+"a".repeat(32)+"/source/Input.java";
    static final List<String> OPTIONS=List.of("--release","21","-encoding","UTF-8","-proc:none","-classpath","sealed-empty","-Xlint:none");
    Map<String,Object> raw;
    Map<String,ContentHandle> bindings=Map.of();
    ContentHandle bytes(byte[] bytes)throws IOException{return store.put(new ByteArrayInputStream(bytes),()->false);}
    void setup()throws IOException {
        store=new StreamingContentStore(temp);bindings=Map.of();source=bytes("class Input {}\r\n".getBytes(StandardCharsets.UTF_8));
        raw=new TreeMap<>();raw.put("schema","java-native-selective-index:m8.4-v1");raw.put("provider","isolated.javac-index:m8.4-v1");
        raw.put("runtime","21.0.12.1");raw.put("options",OPTIONS);raw.put("sourceFiles",List.of(Map.of("uri",URI,"digest",source.digest(),"size",source.size())));
        raw.put("state","PARTIAL");raw.put("failure",null);raw.put("walkedSources",List.of(URI));raw.put("frontier",List.of());
        raw.put("diagnostics",List.of());raw.put("declarations",List.of());raw.put("observations",List.of());raw.put("artifacts",Map.of());
        raw.put("knownModules",List.of("java.base"));raw.put("qualified",false);raw.put("semanticTrust","PRODUCER_ASSERTION");
        var categories=new TreeMap<String,Object>();
        for(String category:CapturedJavacReader.CATEGORIES){
            var row=new TreeMap<String,Object>();row.put("state",CapturedJavacReader.OBSERVED.contains(category)?"OBSERVED_NOT_QUALIFIED_CENSUS":"UNAVAILABLE");
            row.put("observed",CapturedJavacReader.OBSERVED.contains(category)?0:null);categories.put(category,row);
        }raw.put("categories",categories);
    }
    CapturedJavacReader.Capture read()throws IOException {
        var doc=bytes(CanonicalJson.write(raw).getBytes(StandardCharsets.UTF_8));
        return new CapturedJavacReader(store).read(doc,new CapturedJavacReader.Context("21.0.12.1",OPTIONS,Map.of(URI,source),bindings),2_000_000,()->false);
    }
    @Test void closedSourceWalkAndKnownModulesCannotMintASemanticCensus()throws Exception {
        setup();var captured=read();assertEquals(18,captured.categories().size());assertTrue(captured.frontier().isEmpty());
        assertTrue(captured.current());assertFalse(captured.qualified());
        assertTrue(captured.limitations().contains("OCCURRENCE_CENSUS_UNQUALIFIED"));
        assertTrue(captured.limitations().contains("PLATFORM_SELECTION_UNESTABLISHED"));
    }
    @Test void exactSourceAndCompilerInputsAreRequired()throws Exception {
        setup();raw.put("runtime","21.0.foreign");assertThrows(IOException.class,this::read);
        setup();raw.put("sourceFiles",List.of(Map.of("uri",URI,"digest",source.digest(),"size",source.size()+1)));assertThrows(IOException.class,this::read);
        setup();raw.put("options",List.of("--release","21","-proc:full"));assertThrows(IOException.class,this::read);
    }
    @Test void missingAndDuplicateCategoryOrFrontierRowsAreRejected()throws Exception {
        setup();((Map<?,?>)raw.get("categories")).remove("java.calls");assertThrows(IOException.class,this::read);
        setup();raw.put("walkedSources",List.of(URI,URI));assertThrows(IOException.class,this::read);
        setup();raw.put("frontier",List.of(URI));assertThrows(IOException.class,this::read);
        setup();raw.put("qualified",true);assertThrows(IOException.class,this::read);
    }
    @Test void sourcePositionsMustAddressOriginalUtf16Bytes()throws Exception {
        setup();raw.put("declarations",List.of(Map.of("source",URI,"treeKind","CLASS","element",Map.of(),
                "span",Map.of("startOffset",0,"endOffset",15,"startLine",1,"startColumn",1,"endLine",1,"endColumn",16))));
        assertThrows(IOException.class,this::read); // offset 15 is between original CR and LF.
    }
    @Test void observedCountsAreCheckedWithoutTreatingMissingBindingsAsResolved()throws Exception {
        setup();var categories=(Map<String,Object>)raw.get("categories");categories.put("java.calls",Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",1));
        assertThrows(IOException.class,this::read);
        setup();raw.put("failure","INDEX_WORKER_CAPACITY_REQUIRED");raw.put("walkedSources",List.of());raw.put("frontier",List.of(URI));
        assertTrue(read().limitations().contains("INDEX_WORKER_CAPACITY_REQUIRED"));
    }
    @Test void observedArtifactBytesNeedTheirOwnBindingAndRemainUnqualified()throws Exception {
        setup();String uri="file:///work/"+"a".repeat(32)+"/classes/Api.class";var ref=bytes("unit class metadata only".getBytes(StandardCharsets.UTF_8));
        raw.put("artifacts",Map.of(uri,Map.of("uri",uri,"kind","CLASS","digest",ref.digest(),"size",ref.size(),"state","OBSERVED_COMPILER_INPUT")));
        var missing=read();assertFalse(missing.artifacts().get(uri).bytesBound());assertTrue(missing.limitations().contains("BINARY_INPUT_BINDING_UNAVAILABLE"));
        bindings=Map.of(uri,ref);var bound=read();assertTrue(bound.artifacts().get(uri).bytesBound());assertFalse(bound.qualified());
        bindings=Map.of(uri,source);assertThrows(IOException.class,this::read);
    }
    @Test void callerCannotMutateCapturedCoverageAndCasRevocationInvalidatesIt()throws Exception {
        setup();var capture=read();assertThrows(UnsupportedOperationException.class,()->capture.categories().clear());
        String hex=source.digest().value().substring(7);Path blob=temp.resolve("sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));
        java.nio.file.Files.write(blob,"CLASS Input {}\r\n".getBytes(StandardCharsets.UTF_8));assertFalse(capture.current());
    }
    @Test void unresolvedCoverageHasTypedContextQualifiedGaps()throws Exception {
        setup();var snapshot=com.evolution.analysis.contract.source.RepositorySnapshot.create(
                com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:captured-index-test"),Optional.empty(),false,
                List.of(new com.evolution.analysis.contract.source.SnapshotFile("Input.java",source.digest())),List.of());
        var context=com.evolution.analysis.evidence.EvidenceContext.forSnapshot(snapshot.identity());
        var gaps=read().gaps(context);assertEquals(2,gaps.size());assertTrue(gaps.stream().allMatch(g->g.context().equals(context)&&!g.evidenceRequirements().isEmpty()));
        assertEquals(Set.of("OCCURRENCE_CENSUS_UNQUALIFIED","PLATFORM_SELECTION_UNESTABLISHED"),gaps.stream().map(g->g.reasonCode()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void cancellationAndTooSmallWorkerBudgetCannotReturnAnAcceptedPrefix()throws Exception {
        setup();var doc=bytes(CanonicalJson.write(raw).getBytes(StandardCharsets.UTF_8));
        var context=new CapturedJavacReader.Context("21.0.12.1",OPTIONS,Map.of(URI,source),Map.of());
        assertThrows(IOException.class,()->new CapturedJavacReader(store).read(doc,context,1,()->false));
        var cancelled=assertThrows(IOException.class,()->new CapturedJavacReader(store).read(doc,context,2_000_000,()->true));
        assertTrue(cancelled.getMessage().contains("CANCELLED_WITH_FRONTIER"));
    }
    Map<String,Object> reference() {
        var element=Map.of("kind","CLASS","name","Input","owner","Input","module","","modelOrigin","EXPLICIT","descriptor","LInput;","artifact",URI);
        var row=new TreeMap<String,Object>();row.put("source",URI);row.put("sourceContent",source.digest());row.put("category","java.type-uses");row.put("treeKind","IDENTIFIER");
        row.put("span",CapturedJavacIndex.span("class Input {}\r\n",6,11));row.put("caller",element);row.put("target",element);row.put("enclosingScopes",List.of());row.put("binding","RESOLVED");row.put("type","Input");
        raw.put("artifacts",Map.of(URI,Map.of("uri",URI,"kind","SOURCE","digest",source.digest(),"size",source.size(),"state","CAPTURED_SOURCE")));
        ((Map<String,Object>)raw.get("categories")).put("java.type-uses",Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",1));raw.put("observations",List.of(row));return row;
    }
    @Test void compilerNoposInAnEnclosingScopeRetainsTheObservationAndItsMappingObligation()throws Exception {
        setup();var row=reference();row.put("enclosingScopes",List.of(Map.of("kind","LAMBDA_EXPRESSION","startOffset",-1,"endOffset",-1)));
        var captured=read();assertEquals(1,captured.observations().size());assertTrue(captured.limitations().contains("ENCLOSING_SCOPE_MAPPING_REQUIRED"));
        assertTrue(captured.limitations().contains("SOURCE_POSITION_UNAVAILABLE"));assertFalse(captured.qualified());
    }
    @Test void aNativeReferenceCannotInventAnElementKindOrBorrowAnUnlistedArtifact()throws Exception {
        setup();var row=reference();var target=new TreeMap<>((Map<String,Object>)row.get("target"));target.put("kind","INVENTED");row.put("target",target);assertThrows(IOException.class,this::read);
        setup();row=reference();target=new TreeMap<>((Map<String,Object>)row.get("target"));target.put("artifact","file:///unbound/Api.class");row.put("target",target);assertThrows(IOException.class,this::read);
        setup();row=reference();row.put("treeKind","INVENTED");assertThrows(IOException.class,this::read);
        setup();raw.put("failure","invalid failure/question");assertThrows(IOException.class,this::read);
    }
    @Test void versionTwoRetainsAllEighteenObservedCategoriesWithoutQualifyingThem()throws Exception {
        setup();raw.put("schema","java-native-selective-index:m8.4-v2");raw.put("provider","isolated.javac-index:m8.4-v2");
        var categories=new TreeMap<String,Object>();for(String category:CapturedJavacReader.CATEGORIES)categories.put(category,Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",0));raw.put("categories",categories);
        var captured=read();assertEquals(18,captured.observedCategories().size());assertFalse(captured.qualified());
        assertTrue(captured.limitations().contains("OCCURRENCE_CENSUS_UNQUALIFIED"));
    }
    @Test void versionTwoRetainsNativePositionsAndRestrictsSyntaxOnlyToUnboundScopes()throws Exception {
        setup();var row=reference();raw.put("schema","java-native-selective-index:m8.4-v2");raw.put("provider","isolated.javac-index:m8.4-v2");
        var categories=(Map<String,Object>)raw.get("categories");for(String category:CapturedJavacReader.CATEGORIES)categories.put(category,Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",category.equals("java.type-uses")?1:0));
        row.put("nativeSpan",row.get("span"));assertEquals(CanonicalJson.write(row.get("nativeSpan")),CanonicalJson.write(read().observations().getFirst().get("nativeSpan")));
        row.put("nativeSpan",CapturedJavacIndex.span("class Input {}\r\n",0,13));assertFalse(read().qualified());
        row.put("binding","SYNTAX_ONLY");assertThrows(IOException.class,this::read);
        categories.put("java.type-uses",Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",0));categories.put("java.declares",Map.of("state","OBSERVED_NOT_QUALIFIED_CENSUS","observed",1));
        row.put("category","java.declares");row.put("treeKind","LAMBDA_EXPRESSION");assertThrows(IOException.class,this::read); // A bound symbol cannot call itself syntax-only.
        row.put("target",null);assertTrue(read().limitations().contains("NATIVE_BINDING_SYNTAX_ONLY"));
        row.put("treeKind","METHOD");assertThrows(IOException.class,this::read);
        row.put("treeKind","BLOCK");row.put("nativeSpan",Map.of("startOffset",-1));assertThrows(IOException.class,this::read);
    }
}
