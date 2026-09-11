import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import java.nio.file.*;
import java.util.*;

public class GapExport {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path workspace = root.getParent().getParent();
        if (!output.startsWith(workspace)) throw new IllegalArgumentException("Output outside workspace");
        Files.createDirectory(output);
        List<SnapshotFile> files = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path rel = root.relativize(p);
                if (rel.startsWith(".cache") || rel.toString().contains("__pycache__")) continue;
                if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("Linked input");
                files.add(new SnapshotFile(rel.toString().replace('\\','/'), ContentDigest.sha256(Files.readAllBytes(p))));
            }
        }
        Path core = workspace.resolve("analyzer/src/main/java");
        try (var paths = Files.walk(core)) {
            for (Path p : paths.filter(Files::isRegularFile).sorted().toList()) {
                if (p.toString().endsWith(".java"))
                    files.add(new SnapshotFile("platform-contract-source/" + core.relativize(p).toString().replace('\\','/'),
                                               ContentDigest.sha256(Files.readAllBytes(p))));
            }
        }
        var snapshot = RepositorySnapshot.create(
                RepositoryIdentity.fromCanonicalCoordinate("urn:se121:authored-study:m4-r0"),
                Optional.empty(), false, files, List.of());
        var context = EvidenceContext.forSnapshot(snapshot.identity());
        var provider = new VersionedIdentifier("research.spring-r0-gap-export", "1");
        var catalog = new VersionedIdentifier("evidence.spring-r0-research-gaps", "1");
        Path observations = root.resolve("gap-observations.tsv");
        ContentDigest result = ContentDigest.sha256(Files.readAllBytes(observations));
        List<CapabilityGapRecord> gaps = new ArrayList<>();
        for (String line : Files.readAllLines(observations)) {
            String[] f = line.split("\\t", -1);
            if (f.length != 7) throw new IllegalArgumentException("Malformed research observation");
            var subject = new EvidenceSubject(EvidenceSubject.Kind.valueOf(f[3]), f[4]);
            var ref = ProviderObservationReference.create(provider, "spring.research.observation",
                                                          result, ContentDigest.sha256Utf8(line));
            var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.valueOf(f[2]),
                    "spring.research.resolve-evidence-gap", List.of(subject),
                    EvidenceRequirement.AuthorizationClass.PASSIVE, List.of(f[5]));
            List<EvidenceRequirement> requirements = List.of(requirement);
            Map<String,Object> stable = Map.of("schema", CapabilityGapRecord.SCHEMA, "catalog", catalog,
                    "context", context, "detectingProvider", provider, "mechanismCategory", f[0],
                    "reasonCode", f[1], "subject", subject, "sourceSpans", List.of(), "evidenceRequirements", requirements);
            var gapId = new CapabilityGapIdentity("gap:" + ContentDigest.sha256Utf8(
                    CanonicalJson.write(Map.of("kind","gap","stableInputs",stable))).value());
            gaps.add(new CapabilityGapRecord(CapabilityGapRecord.SCHEMA, catalog, gapId, context, provider,
                    f[0], f[1], subject, List.of(), List.of(ref), requirements, List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"spring.research.coverage")),
                    List.of(), List.of(), List.of(f[6])));
        }
        gaps.sort(Comparator.naturalOrder());
        if (new HashSet<>(gaps.stream().map(CapabilityGapRecord::gapIdentity).toList()).size()!=gaps.size())
            throw new IllegalArgumentException("Duplicate gap");
        String serialized = CanonicalJson.write(gaps);
        Files.writeString(output.resolve("capability-gaps.json"), serialized);
        Files.writeString(output.resolve("study-snapshot.json"), CanonicalJson.write(snapshot));
        String summary = CanonicalJson.write(Map.of("gaps", gaps.size(), "schema", CapabilityGapRecord.SCHEMA,
                "digest", ContentDigest.sha256Utf8(serialized), "snapshotIdentity", snapshot.identity()));
        Files.writeString(output.resolve("summary.json"), summary);
        System.out.println(summary);
    }
}
