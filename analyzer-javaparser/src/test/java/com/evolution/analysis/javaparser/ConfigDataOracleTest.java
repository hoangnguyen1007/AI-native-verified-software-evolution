package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.SourceInput;
import com.evolution.analysis.ingestion.RepositoryInputs;
import com.evolution.analysis.spring.condition.ConfigDataIngestion;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.*;
import org.springframework.core.io.ByteArrayResource;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDataOracleTest {
    @Test void passiveDocumentsMatchPinnedBootLoadersOnAuthoredControls()throws Exception {
        List<String> properties=List.of("a=one\\\n#---\nb=two\n", "a=1\n#---\nb=2\n",
                "# header\n#---\na=1\n", "a=1\n#---\n# comment\nb=2\n", "escaped=\\u00e9\nraw=é\n",
                "a=1\n!---\nb=2\n");
        for(String source:properties)check("properties",source,new PropertiesPropertySourceLoader());
        for(String source:List.of("server:\n  port: 8080\nlist: [a, b]\nempty: []\nnullValue: null\n",
                "a: 1\n---\na: 2\n", "map:\n  '[key]': value\n"))check("yml",source,new YamlPropertySourceLoader());
    }
    private void check(String extension,String source,PropertySourceLoader oracle)throws Exception {
        String path="src/main/resources/application."+extension;byte[] bytes=source.getBytes(StandardCharsets.UTF_8);
        var document=SourceDocument.create(TestInputs.REPO,TestInputs.MODULE,path,ContentDigest.sha256(bytes),SourceClassification.MAIN);
        var snapshot=RepositorySnapshot.create(TestInputs.REPO,Optional.empty(),false,List.of(SnapshotFile.from(document)),List.of(document));
        var inputs=new RepositoryInputs(snapshot,Map.of(path,new SourceInput(document,bytes)));
        var result=new ConfigDataIngestion().ingest(inputs,ConfigDataIngestion.Policy.defaults());
        var expected=oracle.load("control",new ByteArrayResource(bytes)).stream().map(p->{
            var values=new TreeMap<String,String>();
            ((Map<?,?>)p.getSource()).forEach((k,v)->values.put(k.toString(),v==null?"":v.toString()));return values;
        }).toList();
        assertEquals(expected,result.documents().stream().map(ConfigDataIngestion.Document::properties).toList());
        assertTrue(result.gaps().isEmpty(),result.issues().toString());
    }
}
