package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.semantic.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontendContractTest {
    private static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/port.git");
    private static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPO, "fixture", "fixture");
    private static final PlatformInput PLATFORM = new PlatformInput(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE, "test-jdk", ContentDigest.sha256Utf8("platform")), Path.of("runtime"));
    private static SourceInput source(String path, byte[] bytes, SourceClassification role) {
        return new SourceInput(SourceDocument.create(REPO, MODULE, path, ContentDigest.sha256(bytes), role), bytes);
    }
    private static FrontendRequest request(List<SourceInput> all, List<SourceInput> selected) {
        var docs = all.stream().map(SourceInput::document).toList();
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, docs.stream().map(SnapshotFile::from).toList(), docs);
        var component = new ManifestComponent(new VersionedIdentifier("test.component", "1"), ContentDigest.sha256Utf8("component"));
        var config = AnalysisConfiguration.create(new VersionedIdentifier("frontend.configuration", "1"), FrontendRequest.options(MODULE.identity(), SourceClassification.MAIN, docs.stream().filter(d -> d.classification() == SourceClassification.MAIN).toList()));
        var manifest = AnalysisManifest.create(new VersionedIdentifier("analysis.manifest", "1"), snapshot, List.of(MODULE), List.of(PLATFORM.entry()), config, component, component, component);
        return new FrontendRequest(manifest, MODULE.identity(), SourceClassification.MAIN, selected, PLATFORM, List.of());
    }
    @Test void sourceSetMembershipMustChangeAnalysisIdentityEvenWithIdenticalFileInventory() {
        var a = source("fixture/A.java", new byte[]{65}, SourceClassification.MAIN);
        var mainB = source("fixture/B.java", new byte[]{66}, SourceClassification.MAIN);
        var testB = source("fixture/B.java", new byte[]{66}, SourceClassification.TEST);
        var first = request(List.of(a, mainB), List.of(a, mainB));
        var second = request(List.of(a, testB), List.of(a));
        assertNotEquals(first.manifest().identity(), second.manifest().identity());
    }
    @Test void sourceBytesAreDefensiveStrictAndDigestVerified() {
        byte[] bytes = {65}; var source = source("fixture/A.java", bytes, SourceClassification.MAIN);
        bytes[0] = 66; source.bytes()[0] = 67;
        assertEquals("A", source.text());
        assertThrows(FrontendInputException.class, () -> new SourceInput(source.document(), bytes));
        var invalid = assertThrows(FrontendInputException.class, () -> source("fixture/B.java", new byte[]{(byte)0xc3, 0x28}, SourceClassification.MAIN));
        assertEquals("frontend.encoding", invalid.diagnostic().code());
    }
    @Test void rejectsMissingDuplicateAndForeignSourceBindings() {
        var a = source("fixture/A.java", new byte[]{65}, SourceClassification.MAIN);
        assertThrows(FrontendInputException.class, () -> request(List.of(a), List.of()));
        assertThrows(FrontendInputException.class, () -> request(List.of(a), List.of(a,a)));
        var valid = request(List.of(a), List.of(a));
        assertThrows(UnsupportedOperationException.class, () -> valid.sources().clear());
    }
    @Test void completedEmptyOutputCannotOmitARequestedDocument() {
        var a = source("fixture/A.java", new byte[]{65}, SourceClassification.MAIN);
        var request = request(List.of(a),List.of(a));
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(new com.evolution.analysis.contract.semantic.RelationshipKind("java." + c),CategoryCoverage.Support.UNSUPPORTED,0,0,0)).toList();
        var result = new FrontendResult(request.manifest().identity(),new VersionedIdentifier("frontend.test","1"),FrontendResult.State.COMPLETED,List.of(),List.of(),List.of(),List.of(),coverage,List.of());
        assertThrows(IllegalArgumentException.class,() -> result.validateFor(request));
    }
    @Test void recursiveTypeDetailRequiresRegisteredTargetsAndHonestStatus() {
        var a = source("fixture/A.java", new byte[]{65}, SourceClassification.MAIN);
        var request = request(List.of(a),List.of(a));
        var target = EntityIdentity.from(EntityOrigin.PROJECT,EntityScope.project(MODULE.identity()),EntityKind.TYPE,"A");
        var type = new JavaType(JavaType.Kind.DECLARED,"A",Optional.of(target),List.of(),Optional.empty(),SemanticStatus.RESOLVED);
        var detail = new TypeUseRecord(Optional.empty(),new RelationshipKind("java.field-type"),new SourceSpan(a.document().identity(),1,1,1,2),type,false);
        var coverage = FrontendRequest.CATEGORIES.stream().map(c -> new CategoryCoverage(new RelationshipKind("java."+c),CategoryCoverage.Support.UNSUPPORTED,0,0,0)).toList();
        assertThrows(IllegalArgumentException.class,() -> new FrontendResult(request.manifest().identity(),new VersionedIdentifier("frontend.test","1"),
                FrontendResult.State.COMPLETED,List.of(),List.of(),List.of(),List.of(new SourceOutcome(a.document().identity(),SourceOutcome.State.PROCESSED,List.of())),coverage,List.of(),List.of(detail)));
        var missing = new JavaType(JavaType.Kind.UNKNOWN,"Missing",Optional.empty(),List.of(),Optional.empty(),SemanticStatus.UNRESOLVED);
        assertThrows(IllegalArgumentException.class,() -> new JavaType(JavaType.Kind.ARRAY,"Missing[]",Optional.empty(),List.of(missing),Optional.empty(),SemanticStatus.RESOLVED));
        assertThrows(UnsupportedOperationException.class,() -> type.components().add(missing));
    }
}
