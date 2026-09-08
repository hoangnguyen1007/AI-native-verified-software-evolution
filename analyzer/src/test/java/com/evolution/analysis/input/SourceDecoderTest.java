package com.evolution.analysis.input;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.SourceInput;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceDecoderTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/decoding.git");
    private static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPOSITORY, ".", "root");
    private static final String PATH = "src/main/java/demo/Cafe.java";
    private static final BuildModelResult.PomEvidence POM = new BuildModelResult.PomEvidence(
            "workspace:pom.xml", ContentDigest.sha256Utf8("pom"));

    @Test
    void stripsUtf8BomAndPreservesRawDigestAndCrlfProvenance() {
        byte[] body = "class Cafe {}\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xef; bytes[1] = (byte) 0xbb; bytes[2] = (byte) 0xbf;
        System.arraycopy(body, 0, bytes, 3, body.length);

        SourceDecodingResult result = decode(bytes, declared("UTF-8"), SourceDecodingPolicy.withholdWhenAbsent());
        SourceInput input = result.outcomes().getFirst().input().orElseThrow();

        assertEquals("class Cafe {}\r\n", input.text());
        assertArrayEquals(bytes, input.bytes());
        assertEquals(ContentDigest.sha256(bytes), input.document().contentDigest());
        assertEquals(SourceInput.Bom.UTF8, input.decoding().bom());
        assertEquals(new SourceInput.LineEndings(SourceInput.LineEndingKind.CRLF, 0, 1, 0),
                input.decoding().lineEndings());
        assertEquals(SourceInput.EncodingOrigin.BUILD_DECLARATION, input.decoding().origin());
        assertFalse(result.hasGaps());
    }

    @Test
    void decodesDeclaredPortableCharsetWithoutGuessing() {
        byte[] bytes = "class Café {}\n".getBytes(StandardCharsets.ISO_8859_1);
        SourceInput input = decode(bytes, declared("ISO-8859-1"), SourceDecodingPolicy.assumeUtf8())
                .outcomes().getFirst().input().orElseThrow();
        assertEquals("class Café {}\n", input.text());
        assertEquals("ISO-8859-1", input.decoding().charset());
        assertEquals(SourceInput.EncodingOrigin.BUILD_DECLARATION, input.decoding().origin());

        byte[] utf16Body = "class Little {}\n".getBytes(StandardCharsets.UTF_16LE);
        byte[] utf16 = new byte[utf16Body.length + 2];
        utf16[0] = (byte) 0xff; utf16[1] = (byte) 0xfe;
        System.arraycopy(utf16Body, 0, utf16, 2, utf16Body.length);
        SourceInput genericUtf16 = decode(utf16, declared("UTF-16"), SourceDecodingPolicy.withholdWhenAbsent())
                .outcomes().getFirst().input().orElseThrow();
        assertEquals("class Little {}\n", genericUtf16.text());
        assertEquals(SourceInput.Bom.UTF16_LE, genericUtf16.decoding().bom());
    }

    @Test
    void invalidBytesAndBomConflictsStayTypedAndNeverBecomeDocuments() {
        SourceDecodingResult malformed = decode(new byte[] {(byte) 0xc3, 0x28}, declared("UTF-8"),
                SourceDecodingPolicy.assumeUtf8());
        assertEquals(SourceDecodingResult.Status.INVALID, malformed.outcomes().getFirst().status());
        assertEquals(SourceDecodingResult.Reason.MALFORMED_BYTE_SEQUENCE,
                malformed.outcomes().getFirst().problems().getFirst().reason());
        assertTrue(malformed.snapshot().documents().isEmpty());

        SourceDecodingResult mismatch = decode(new byte[] {(byte) 0xff, (byte) 0xfe, 65, 0},
                declared("UTF-8"), SourceDecodingPolicy.assumeUtf8());
        assertEquals(SourceDecodingResult.Reason.BOM_ENCODING_MISMATCH,
                mismatch.outcomes().getFirst().problems().getFirst().reason());
    }

    @Test
    void absentEncodingRequiresAnExplicitPolicyAndPolicyChangesIdentity() {
        byte[] bytes = "class Cafe {}".getBytes(StandardCharsets.UTF_8);
        SourceDecodingResult withheld = decode(bytes, absent(), SourceDecodingPolicy.withholdWhenAbsent());
        SourceDecodingResult assumed = decode(bytes, absent(), SourceDecodingPolicy.assumeUtf8());

        assertEquals(SourceDecodingResult.Status.WITHHELD, withheld.outcomes().getFirst().status());
        assertEquals(SourceDecodingResult.Reason.MISSING_ENCODING,
                withheld.outcomes().getFirst().problems().getFirst().reason());
        assertEquals(SourceInput.EncodingOrigin.ANALYSIS_POLICY,
                assumed.outcomes().getFirst().input().orElseThrow().decoding().origin());
        assertNotEquals(withheld.identity(), assumed.identity());
    }

    @Test
    void unsupportedOrInvalidBuildDeclarationsCannotFallThroughToPolicy() {
        byte[] bytes = "class Cafe {}".getBytes(StandardCharsets.UTF_8);
        for (SourcePlanModel.Status status : List.of(SourcePlanModel.Status.UNSUPPORTED,
                SourcePlanModel.Status.INVALID, SourcePlanModel.Status.UNRESOLVED)) {
            var setting = new SourcePlanModel.Setting("compiler.encoding", Optional.of("host-specific"),
                    Optional.empty(), status, SourcePlanModel.Origin.EFFECTIVE_MODEL, List.of(POM));
            SourceDecodingResult result = decode(bytes, setting, SourceDecodingPolicy.assumeUtf8());
            assertEquals(SourceDecodingResult.Status.WITHHELD, result.outcomes().getFirst().status());
        }
    }

    @Test
    void malformedDecodingNormalizesToAStableConfigurationGapWithoutLosingRawSubject() {
        SourceDecodingResult result = decode(new byte[] {(byte) 0xc3, 0x28}, declared("UTF-8"),
                SourceDecodingPolicy.withholdWhenAbsent());
        EvidenceContext context = new EvidenceContext(result.snapshot().identity(), Optional.empty());

        EvidenceAcquisitionLedger ledger = CapabilityGapNormalizer.normalize(context,
                EvidenceNormalizationInput.builder().sourceDecodings(List.of(result)).build());

        assertEquals(1, ledger.gaps().size());
        CapabilityGapRecord gap = ledger.gaps().getFirst();
        assertEquals("MALFORMED_BYTE_SEQUENCE", gap.reasonCode());
        assertEquals(new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY_PATH, PATH), gap.subject());
        assertEquals(EvidenceRequirement.Kind.REPOSITORY_CONTENT, gap.evidenceRequirements().getFirst().kind());
        assertTrue(gap.sourceSpans().isEmpty());
    }

    private static SourceDecodingResult decode(
            byte[] bytes, SourcePlanModel.Setting encoding, SourceDecodingPolicy policy) {
        AcquiredFile source = new AcquiredFile(PATH, bytes);
        AcquiredFile pom = new AcquiredFile("pom.xml", "pom".getBytes(StandardCharsets.UTF_8));
        var request = new RepositoryAcquisitionRequest(REPOSITORY, Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(10, 10, 10_000, 20_000, 10, List.of()));
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                List.of(new SnapshotFile(source.path(), source.contentDigest()),
                        new SnapshotFile(pom.path(), pom.contentDigest())), List.of());
        var acquisition = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA, request,
                new VersionedIdentifier("repository.test", "1"), RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(snapshot), List.of(source, pom), List.of(".", "src", "src/main", "src/main/java",
                        "src/main/java/demo"), List.of(), List.of(), List.of());

        var root = new SourcePlanModel.Setting("build.sourceDirectory", Optional.of("src/main/java"),
                Optional.of("src/main/java"), SourcePlanModel.Status.DECLARED,
                SourcePlanModel.Origin.EFFECTIVE_MODEL, List.of(POM));
        var sourcePlan = new SourcePlanModel(List.of(
                plan(SourcePlanModel.Kind.MAIN, root, encoding),
                plan(SourcePlanModel.Kind.TEST, root, absent())), List.of());
        var effective = new BuildModelResult.EffectivePom(new MavenCoordinate("demo", "root", "1"), "jar",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(POM), sourcePlan);
        var buildRequest = new BuildModelRequest(snapshot, "pom.xml", Map.of("pom.xml", new PomInput(pom.bytes())),
                Map.of(), new BuildModelPolicy(List.of(), List.of(), Map.of(), 10_000, 10, 10));
        var build = new BuildModelResult(BuildModelResult.SCHEMA, buildRequest.identity(),
                new VersionedIdentifier("build.test", "1"),
                List.of(new BuildModelResult.ModuleModel(MODULE, "pom.xml", Optional.empty(), Optional.of(effective))),
                List.of(), List.of(), List.of());
        var claim = new CandidateSourceOwnership.Claim(MODULE.identity(), SourcePlanModel.Kind.MAIN,
                "src/main/java", List.of(POM));
        var ownership = new CandidateSourceOwnership(CandidateSourceOwnership.SCHEMA, snapshot.identity(),
                build.identity(), CandidateSourceOwnership.PROVIDER,
                List.of(new CandidateSourceOwnership.Candidate(PATH, source.contentDigest(),
                        CandidateSourceOwnership.Status.OWNED, List.of(claim))), List.of(), List.of());
        return SourceDecoder.decode(acquisition, ownership, buildRequest, build, policy);
    }

    private static SourcePlanModel.SourceSetPlan plan(
            SourcePlanModel.Kind kind, SourcePlanModel.Setting root, SourcePlanModel.Setting encoding) {
        SourcePlanModel.Setting absent = absent();
        return new SourcePlanModel.SourceSetPlan(MODULE.identity(), kind, List.of(root), List.of(), absent,
                Map.of(), absent, absent, absent, encoding, List.of(), List.of());
    }

    private static SourcePlanModel.Setting declared(String value) {
        return new SourcePlanModel.Setting("compiler.encoding", Optional.of(value), Optional.of(value),
                SourcePlanModel.Status.DECLARED, SourcePlanModel.Origin.EFFECTIVE_MODEL, List.of(POM));
    }

    private static SourcePlanModel.Setting absent() {
        return new SourcePlanModel.Setting("compiler.encoding", Optional.empty(), Optional.empty(),
                SourcePlanModel.Status.UNSPECIFIED, SourcePlanModel.Origin.ABSENT, List.of());
    }
}
