package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Locally registered ancestry proofs. The HTTP request can only name registered proof digests. */
public final class EvolutionHistoryProofs {
    public record Registration(String schema, HistoryProof proof, String provenanceBase64) {}
    private static final String SCHEMA = "architecture.evolution.history-registration:v1";
    private static final int LIMIT = 1_048_576;
    private final Path directory;

    public EvolutionHistoryProofs(Path root) { directory = root.toAbsolutePath().normalize().resolve("history"); }

    public HistoryReader reader() {
        return (id, stop) -> {
            EvolutionSnapshot.check(stop);
            var path = directory.resolve(id.value().substring(7) + ".json");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            try {
                StreamingContentStore.existingDirectory(directory);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    var bytes = input.readNBytes(LIMIT + 1);
                    EvolutionSnapshot.check(stop);
                    if (bytes.length > LIMIT) throw new IllegalArgumentException();
                    var decoded = GraphJson.read(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(),
                            new GraphJson.Limits(LIMIT, 32, 8192));
                    var row = CanonicalRecordReader.read(decoded, Registration.class);
                    if (!SCHEMA.equals(row.schema()) || !row.proof().identity().equals(id) ||
                            !ContentDigest.sha256(Base64.getDecoder().decode(row.provenanceBase64())).equals(row.proof().provenance()) ||
                            !Arrays.equals(CanonicalJson.write(row).getBytes(StandardCharsets.UTF_8), bytes))
                        throw new IllegalArgumentException();
                    return Optional.of(row.proof());
                }
            } catch (NoSuchFileException missing) {
                return Optional.empty();
            } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException invalid) {
                throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);
            } catch (IOException unavailable) {
                throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNAVAILABLE,unavailable);
            }
        };
    }
}
