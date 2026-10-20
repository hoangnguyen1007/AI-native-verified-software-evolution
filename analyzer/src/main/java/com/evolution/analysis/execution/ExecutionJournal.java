package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.IOException;
import java.util.Optional;

/** A compare-and-set commits the verified payload before publishing its active pointer. */
public interface ExecutionJournal {
    ExecutionState create(ContentDigest execution,ContentDigest task)throws IOException;
    Optional<ExecutionState> get(ContentDigest execution,ContentDigest task)throws IOException;
    ExecutionState compareAndSet(ContentDigest execution,ExecutionState expected,ExecutionState next)throws IOException;
}
