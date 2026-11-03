package com.evolution.analysis.polyglot;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiBridgeDescriptorsTest {
    final SemanticTablesTest.Memory content=new SemanticTablesTest.Memory();
    final OpenApiBridgeDescriptors reader=new OpenApiBridgeDescriptors(content);

    @Test void apiVersionIsDistinctFromDialectAndLocalParameterOverridesAreCaptured()throws Exception{
        var source=content.text("""
            {"openapi":"3.1.0","info":{"title":"Users","version":"2026.11"},"paths":{"/users/{id}":{
              "parameters":[{"$ref":"#/components/parameters/id"},{"name":"q","in":"query","schema":{"type":"string"}}],
              "get":{"parameters":[{"name":"q","in":"query","schema":{"type":"integer"}}],"responses":{"200":{"description":"ok"}}}}},
              "components":{"parameters":{"id":{"name":"id","in":"path","required":true,"schema":{"type":"string"}}}}}
            """);
        var result=reader.read(source,"GET","/users/{id}",65536,()->false);
        assertEquals("2026.11",result.operands().get("version"));assertEquals("3.1.0",result.operands().get("dialect"));assertTrue(result.diagnostics().isEmpty());assertTrue(result.parameters().isPresent());
        String text;try(var closure=content.open(result.parameters().orElseThrow())){text=new String(closure.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}assertTrue(text.contains("integer"));assertTrue(text.contains("path:id"));assertEquals(result,reader.read(source,"GET","/users/{id}",65536,()->false));
    }
    @Test void externalOrMissingReferencesAndInvalidInfoNeverBecomeAnEmptyClosure()throws Exception{
        for(String reference:List.of("https://example.invalid/parameters.json#/id","#/components/parameters/missing")){
            var source=content.text("{\"openapi\":\"3.1.0\",\"paths\":{\"/users\":{\"get\":{\"parameters\":[{\"$ref\":\""+reference+"\"}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");
            var result=reader.read(source,"GET","/users",65536,()->false);assertFalse(result.parameters().isPresent());assertFalse(result.operands().containsKey("version"));assertFalse(result.diagnostics().isEmpty());
        }
    }
    @Test void wrongOperationDuplicateParametersAndUnsupportedDialectStayExplicit()throws Exception{
        var source=content.text("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{\"/x\":{\"get\":{\"parameters\":[{\"name\":\"a\",\"in\":\"query\"},{\"name\":\"a\",\"in\":\"query\"}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");
        assertTrue(reader.read(source,"POST","/x",65536,()->false).diagnostics().contains("OPENAPI_OPERATION_NOT_CAPTURED"));assertTrue(reader.read(source,"GET","/x",65536,()->false).diagnostics().contains("OPENAPI_DUPLICATE_PARAMETER"));
        assertTrue(reader.read(content.text("{\"openapi\":\"3.2.0\"}"),"GET","/x",65536,()->false).diagnostics().contains("OPENAPI_DIALECT_UNSUPPORTED"));
    }
    @Test void effectiveOverridesDoNotAcquireReferencesFromDiscardedParameterSchemas()throws Exception{
        var source=content.text("""
            {"openapi":"3.1.0","info":{"title":"x","version":"1"},"paths":{"/x":{
              "parameters":[{"name":"q","in":"query","schema":{"$ref":"https://example.invalid/old.json"}}],
              "get":{"parameters":[{"name":"q","in":"query","schema":{"type":"string"},"example":{"$ref":"literal example data"}}]}}}}
            """);
        var result=reader.read(source,"GET","/x",65536,()->false);assertTrue(result.diagnostics().isEmpty());assertTrue(result.parameters().isPresent());
    }
    @Test void recursiveSchemaReferencesRemainFiniteAndUnsupportedScopesDoNotDisappear()throws Exception{
        var source=content.text("""
            {"openapi":"3.1.0","info":{"title":"x","version":"1"},"paths":{"/x":{"get":{"parameters":[{"name":"q","in":"query","schema":{"$ref":"#/components/schemas/Node"}}]}}},
             "components":{"schemas":{"Node":{"type":"object","properties":{"next":{"$ref":"#/components/schemas/Node"}}}}}}
            """);
        assertTrue(reader.read(source,"GET","/x",65536,()->false).parameters().isPresent());
        var scoped=content.text("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{\"/x\":{\"get\":{\"parameters\":[{\"name\":\"q\",\"in\":\"query\",\"schema\":{\"$dynamicRef\":\"#node\"}}]}}}}");
        var result=reader.read(scoped,"GET","/x",65536,()->false);assertFalse(result.parameters().isPresent());assertTrue(result.diagnostics().contains("OPENAPI_SCHEMA_SCOPE_UNSUPPORTED"));
    }
    @Test void malformedSchemaBudgetAndCancellationDoNotInventAValidClosure()throws Exception{
        var source=content.text("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{\"/x\":{\"get\":{\"parameters\":[{\"name\":\"q\",\"in\":\"query\",\"schema\":null}]}}}}");
        assertTrue(reader.read(source,"GET","/x",65536,()->false).diagnostics().contains("OPENAPI_SCHEMA_INVALID"));assertEquals(List.of("OPENAPI_SOURCE_WORKER_REQUIRED"),reader.read(source,"GET","/x",1,()->false).diagnostics());assertThrows(java.io.IOException.class,()->reader.read(source,"GET","/x",65536,()->true));
    }
}
