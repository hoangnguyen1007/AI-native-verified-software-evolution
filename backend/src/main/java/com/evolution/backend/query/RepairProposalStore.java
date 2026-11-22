package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded, content-addressed store for backend-produced captured repair proposals. */
public final class RepairProposalStore {
    private static final int MAX_MEMORY = 256;
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private final Path directory;
    private final Map<ContentDigest, CapturedRepair.Proposal> memory = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, CapturedRepair.Proposal> eldest) {
            return size() > MAX_MEMORY;
        }
    };

    public RepairProposalStore() { this(null); }

    public RepairProposalStore(Path directory) {
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    public synchronized void put(CapturedRepair.Proposal proposal) {
        if (!CapturedRepair.authentic(proposal)) throw new IllegalArgumentException("INVALID_REPAIR_PROPOSAL");
        memory.put(proposal.identity(), proposal);
        if (directory != null) {
            try {
                Files.createDirectories(directory);
                String hex = proposal.identity().value().substring(7);
                Path target = directory.resolve(hex + ".proposal.json");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
                byte[] bytes = CanonicalJson.write(proposal).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_FILE_BYTES) throw new IllegalStateException("REPAIR_PROPOSAL_SIZE_LIMIT");
                Path tmp = directory.resolve(hex + "." + UUID.randomUUID() + ".tmp");
                Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException unavailable) {
                throw new IllegalStateException("REPAIR_PROPOSAL_STORAGE_UNAVAILABLE", unavailable);
            }
        }
    }

    public synchronized Optional<CapturedRepair.Proposal> get(ContentDigest identity) {
        var cached = memory.get(Objects.requireNonNull(identity));
        if (cached != null && CapturedRepair.authentic(cached)) return Optional.of(cached);
        if (directory == null) return Optional.empty();
        Path file = directory.resolve(identity.value().substring(7) + ".proposal.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) return Optional.empty();
            String text = Files.readString(file, StandardCharsets.UTF_8);
            var parsed = CanonicalRecordReader.read(
                    GraphJson.read(text, new GraphJson.Limits(MAX_FILE_BYTES, 48, 250_000)),
                    CapturedRepair.Proposal.class);
            if (!parsed.identity().equals(identity) || !CapturedRepair.authentic(parsed)) return Optional.empty();
            memory.put(identity, parsed);
            return Optional.of(parsed);
        } catch (IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }
}
