package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Classifies every sealed inventory row, including inaccessible/unknown/raw
 * paths. No host materialization.
 */
public final class PolyglotInventory {
    public static final String INSTALLED_DEPENDENCY_DEFERRED = "INSTALLED_DEPENDENCY_SEMANTICS_DEFERRED";

    public record Report(ContentDigest inventory, SemanticTables.Table entries, long files,
            long unresolvedClassification, VersionedIdentifier classifier) {
        public Report {
            Objects.requireNonNull(inventory);
            Objects.requireNonNull(entries);
            Objects.requireNonNull(classifier);
            if (entries.kind() != SemanticTables.Kind.DOCUMENT || files < 0 || files > entries.rows()
                    || unresolvedClassification < 0 || unresolvedClassification > files)
                throw new IllegalArgumentException("POLYGLOT_INVENTORY_REPORT_COUNTS");
        }

        public Report(ContentDigest inventory, SemanticTables.Table entries, long files,
                long unresolvedClassification) {
            this(inventory, entries, files, unresolvedClassification,
                    new VersionedIdentifier("language.discovery", LanguageDiscovery.VERSION));
        }

        public ContentDigest identity() {
            return CanonicalJson.sha256(List.of("polyglot-inventory:m8.4-v2", classifier, inventory, entries, files,
                    unresolvedClassification));
        }
    }

    public Report scan(String revision, InventoryReader inventory, RepositoryContentStore content,
            SemanticTables tables, BooleanSupplier cancelled) throws IOException {
        return scan(revision, inventory, content, tables, cancelled, path -> false);
    }

    public Report scan(String revision, InventoryReader inventory, RepositoryContentStore content,
            SemanticTables tables,
            BooleanSupplier cancelled, Predicate<String> installedDependency) throws IOException {
        var manifest = inventory.manifest(revision).orElseThrow(() -> new IOException("SEALED_INVENTORY_REQUIRED"));
        var iterator = new ClassifiedRows(revision, inventory, content, manifest.inventory(), cancelled,
                installedDependency);
        SemanticTables.Table table;
        try {
            table = tables.write(SemanticTables.Kind.DOCUMENT, iterator, cancelled);
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        if (table.rows() != manifest.entries() || !inventory.manifest(revision).filter(manifest::equals).isPresent())
            throw new IOException("POLYGLOT_INVENTORY_DENOMINATOR_CHANGED");
        return new Report(manifest.inventory(), table, iterator.files, iterator.unknown);
    }

    private static final class ClassifiedRows implements Iterator<SemanticTables.Row> {
        final String revision;
        final InventoryReader inventory;
        final RepositoryContentStore content;
        final ContentDigest scope;
        final BooleanSupplier cancelled;
        final Predicate<String> installedDependency;
        final byte[] readBuffer = new byte[16_384];
        final java.security.MessageDigest sha256 = newSha256();
        Iterator<InventoryEntry> page = Collections.emptyIterator();
        Optional<String> cursor = Optional.empty();
        boolean ended;
        long files, unknown;

        ClassifiedRows(String revision, InventoryReader inventory, RepositoryContentStore content, ContentDigest scope,
                BooleanSupplier cancelled, Predicate<String> installedDependency) {
            this.revision = revision;
            this.inventory = inventory;
            this.content = content;
            this.scope = scope;
            this.cancelled = cancelled;
            this.installedDependency = installedDependency;
        }

        private static java.security.MessageDigest newSha256() {
            try {
                return java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private byte[] verifyAndReadPrefix(ContentHandle handle, int prefixLen) throws IOException {
            sha256.reset();
            byte[] head = new byte[prefixLen];
            int headFilled = 0;
            long totalRead = 0;
            long expectedSize = handle.size();
            try (var stream = content.open(handle)) {
                int n;
                while ((n = stream.read(readBuffer)) >= 0) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new UncheckedIOException(new IOException("POLYGLOT_INVENTORY_CANCELLED"));
                    }
                    if (n == 0)
                        continue;
                    totalRead += n;
                    if (totalRead > expectedSize)
                        throw new IOException("CONTENT_UNAVAILABLE");
                    sha256.update(readBuffer, 0, n);
                    if (headFilled < prefixLen) {
                        int copy = Math.min(n, prefixLen - headFilled);
                        System.arraycopy(readBuffer, 0, head, headFilled, copy);
                        headFilled += copy;
                    }
                }
            }
            if (totalRead != expectedSize || headFilled != prefixLen) {
                throw new IOException("CONTENT_UNAVAILABLE");
            }
            String actualDigest = "sha256:" + HexFormat.of().formatHex(sha256.digest());
            if (!actualDigest.equals(handle.digest().value())) {
                throw new IOException("CONTENT_UNAVAILABLE");
            }
            return head;
        }

        @Override
        public boolean hasNext() {
            try {
                while (!page.hasNext() && !ended) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        throw new IOException("POLYGLOT_INVENTORY_CANCELLED");
                    var result = inventory.page(revision, 256, cursor);
                    if (result.entries().size() > 256)
                        throw new IOException("INVENTORY_PAGE_CONTRACT");
                    if (result.continuation().isPresent() && result.continuation().equals(cursor))
                        throw new IOException("INVENTORY_CURSOR_DID_NOT_ADVANCE");
                    page = result.entries().iterator();
                    cursor = result.continuation();
                    ended = cursor.isEmpty();
                }
                return page.hasNext();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public SemanticTables.Row next() {
            if (!hasNext())
                throw new NoSuchElementException();
            var entry = page.next();
            var fields = new TreeMap<String, Object>();
            fields.put("rawPath", entry.path().rawBase64());
            fields.put("kind", entry.kind().name());
            fields.put("population", entry.population().name());
            fields.put("disposition", entry.disposition().name());
            fields.put("content", entry.content().orElse(null));
            var detection = LanguageDiscovery.detect(entry.path().utf8().orElse("raw-path"), new byte[0],
                    Optional.empty());
            String reason = "INVENTORIED_NON_FILE";
            if (entry.kind() == InventoryEntry.Kind.FILE) {
                files = Math.addExact(files, 1);
                reason = "CLASSIFICATION_HINT_ONLY";
                if (entry.path().utf8().isEmpty())
                    reason = "RAW_PATH_CLASSIFIER_REQUIRED";
                else if (entry.disposition() != InventoryEntry.Disposition.ACQUIRED)
                    reason = "INPUT_UNAVAILABLE_OR_EXCLUDED";
                else if (installedDependency.test(entry.path().utf8().orElseThrow()))
                    reason = INSTALLED_DEPENDENCY_DEFERRED;
                else
                    try {
                        String utf8Path = entry.path().utf8().orElseThrow();
                        var handle = entry.content().orElseThrow();
                        boolean mustFullVerify = entry.population() == InventoryEntry.Population.SOURCE
                                || entry.population() == InventoryEntry.Population.UNKNOWN
                                || handle.size() <= 65_536
                                || !com.evolution.analysis.readiness.ReadinessPlanner
                                        .isNonSourceBinaryExtension(utf8Path);
                        int prefixLen = (int) Math.min(handle.size(), 8192L);
                        byte[] head;
                        if (mustFullVerify) {
                            head = verifyAndReadPrefix(handle, prefixLen);
                        } else {
                            try (var stream = content.range(handle, 0, prefixLen)) {
                                head = stream.readNBytes(prefixLen);
                                if (head.length != prefixLen)
                                    throw new IOException("CONTENT_UNAVAILABLE");
                            }
                        }
                        detection = LanguageDiscovery.detect(utf8Path, head, Optional.empty());
                    } catch (IOException failure) {
                        reason = "CLASSIFICATION_INPUT_UNAVAILABLE";
                    }
                if (detection.state() == LanguageDiscovery.State.UNKNOWN
                        || detection.state() == LanguageDiscovery.State.AMBIGUOUS
                        || detection.state() == LanguageDiscovery.State.CONFLICTED
                        || !reason.equals("CLASSIFICATION_HINT_ONLY"))
                    unknown = Math.addExact(unknown, 1);
            }
            fields.put("classification", detection.state().name());
            fields.put("languageCandidates", detection.candidates());
            fields.put("classificationEvidence", detection.evidence());
            fields.put("reason", reason);
            return new SemanticTables.Row(SemanticTables.Kind.DOCUMENT, entry.path().key(), scope, fields);
        }
    }
}
