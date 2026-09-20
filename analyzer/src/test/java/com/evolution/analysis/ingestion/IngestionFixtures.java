package com.evolution.analysis.ingestion;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class IngestionFixtures {
    public static RepositoryInputs inputs(Map<String,String> text) {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/ingestion.git");
        var module = ModuleDescriptor.create(repo,".","test");
        Map<String,SourceInput> files = new TreeMap<>();
        text.forEach((path,value) -> {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            files.put(path,new SourceInput(SourceDocument.create(repo,module,path,ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));
        });
        var docs = files.values().stream().map(SourceInput::document).toList();
        return new RepositoryInputs(RepositorySnapshot.create(repo,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
    }
    private IngestionFixtures() {}
}
