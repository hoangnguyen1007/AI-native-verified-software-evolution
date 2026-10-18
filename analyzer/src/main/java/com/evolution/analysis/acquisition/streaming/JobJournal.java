package com.evolution.analysis.acquisition.streaming;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.util.*;

/** Durable compare-and-set transitions. Payloads refer to immutable CAS evidence, never executable closures. */
public interface JobJournal {
    enum State { PENDING, RUNNING, WAITING_FOR_RESOURCES, WAITING_FOR_INPUT, CANCELLED, COMPLETE, FAILED }
    record Record(String id, long revision, State state, String reason, Optional<ContentHandle> payload) {
        public Record { Objects.requireNonNull(id); Objects.requireNonNull(state); Objects.requireNonNull(reason); Objects.requireNonNull(payload); if (revision < 0) throw new IllegalArgumentException("Negative revision"); }
    }
    Record create(String id, ContentDigest request, Optional<ContentHandle> payload) throws IOException;
    Optional<Record> get(String id) throws IOException;
    Record transition(String id, long expectedRevision, State state, String reason, Optional<ContentHandle> payload) throws IOException;
    List<Record> history(long afterSequence, int size) throws IOException;
    record HistoryEntry(long sequence, Record record) {}
    record HistoryPage(List<HistoryEntry> entries,long total,Optional<Long> continuation,long highWaterSequence) {
        public HistoryPage { entries=List.copyOf(entries);Objects.requireNonNull(continuation); }
    }
    HistoryPage historyPage(long afterSequence,int size) throws IOException;
    HistoryPage historyPage(long afterSequence,int size,long highWaterSequence) throws IOException;
    Optional<Record> byRequest(ContentDigest request) throws IOException;
    List<Record> waiting(String reason,int size) throws IOException;
}
