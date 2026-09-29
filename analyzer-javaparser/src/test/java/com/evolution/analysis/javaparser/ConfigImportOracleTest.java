package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.RepositoryInputs;
import com.evolution.analysis.spring.condition.ConfigDataIngestion;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only fixed authored fixtures are loaded by the trusted Boot oracle. The analyzer remains passive. */
class ConfigImportOracleTest {
    @TempDir Path directory;

    @Test void localImportPrecedenceAndProfileVariantsMatchBoot340() throws Exception {
        compare(Map.of("application.properties","spring.config.import=first.properties,second.yml\nvalue=base\n",
                "first.properties","spring.config.import=shared.properties\nvalue=first\n",
                "shared.properties","shared=yes\nvalue=shared\n", "second.yml","value: last\n"),List.of("value","shared"));
    }
    @Test void importedGroupsAndVariantsMatchBoot340() throws Exception {
        compare(Map.of("application.properties","spring.config.import=shared.properties\n",
                "shared.properties","spring.profiles.active=prod\nspring.profiles.group.prod=db\nvalue=base\n",
                "shared-db.properties","value=database\n"),List.of("value"));
    }
    @Test void repeatedImportsHaveOnePositionInThePrecedenceChain() throws Exception {
        compare(Map.of("application.properties","spring.config.import=first.properties,second.properties\n",
                "first.properties","spring.config.import=shared.properties\nvalue=first\n",
                "second.properties","spring.config.import=shared.properties\nvalue=second\n",
                "shared.properties","value=shared\n"),List.of("value"));
    }
    private void compare(Map<String,String> texts,List<String> keys) throws Exception {
        assertEquals("3.4.0",ConfigDataEnvironmentPostProcessor.class.getPackage().getImplementationVersion());
        var files=new TreeMap<String,SourceInput>();
        for(var row:texts.entrySet()) {
            Path path=directory.resolve(row.getKey());Files.createDirectories(path.getParent());Files.writeString(path,row.getValue());
            byte[] bytes=row.getValue().getBytes(StandardCharsets.UTF_8);
            files.put(row.getKey(),new SourceInput(SourceDocument.create(TestInputs.REPO,TestInputs.MODULE,row.getKey(),
                    ContentDigest.sha256(bytes),SourceClassification.MAIN),bytes));
        }
        var docs=files.values().stream().map(SourceInput::document).toList();
        var input=new RepositoryInputs(RepositorySnapshot.create(TestInputs.REPO,Optional.empty(),false,
                docs.stream().map(SnapshotFile::from).toList(),docs),files);
        var result=new ConfigDataIngestion().ingest(input,new ConfigDataIngestion.Policy(List.of("."),List.of(),Map.of(),64,10000,16,100));
        var environment=new StandardEnvironment();
        for(var source:List.copyOf(java.util.stream.StreamSupport.stream(environment.getPropertySources().spliterator(),false).toList()))
            environment.getPropertySources().remove(source.getName());
        environment.getPropertySources().addFirst(new MapPropertySource("authored-oracle",Map.of(
                "spring.config.location",directory.resolve("application.properties").toUri().toString())));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        assertTrue(result.assignment().isPresent(),result.issues().toString());
        for(String key:keys)assertEquals(environment.getProperty(key),result.properties().get(key),key);
    }
}
