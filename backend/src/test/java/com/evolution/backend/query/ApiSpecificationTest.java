package com.evolution.backend.query;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class ApiSpecificationTest {
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @Test void packagedOpenApiNamesEveryImplementedRouteAndOperation() throws Exception {
        try(var input=getClass().getResourceAsStream("/api/openapi.yaml")) {
            assertNotNull(input); var spec=map(new Yaml().load(input)); assertEquals("3.1.0",spec.get("openapi"));
            var paths=map(spec.get("paths")); assertEquals(5,paths.size());
            assertOperations(paths,"/api/v1/graphs/{graph}/queries/{operation}",ArchitectureQueryApi.OPERATIONS);
            assertOperations(paths,"/api/v1/graphs/{graph}/reports/{report}/{operation}",ArchitectureQueryApi.REPORT_OPERATIONS);
            checkReferences(spec,spec);
            var schemas=map(map(spec.get("components")).get("schemas"));
            var limits=map(map(schemas.get("Limits")).get("properties"));
            assertEquals(20,map(limits.get("maxDepth")).get("maximum")); assertEquals(100000,map(limits.get("maxSteps")).get("maximum"));
            assertEquals(50,map(limits.get("maxResults")).get("maximum"));
        }
    }
    static void assertOperations(Map<String,Object> paths,String path,List<String> expected) {
        var parameters=(List<?>)map(paths.get(path)).get("parameters");
        var operation=parameters.stream().map(ApiSpecificationTest::map).filter(p -> "operation".equals(p.get("name"))).findFirst().orElseThrow();
        assertEquals(expected,map(operation.get("schema")).get("enum"));
    }
    static void checkReferences(Object value,Map<String,Object> root) {
        if(value instanceof Map<?,?> row) {
            if(row.get("$ref") instanceof String ref) {
                assertTrue(ref.startsWith("#/")); Object target=root;
                for(String part:ref.substring(2).split("/")) target=map(target).get(part);
                assertNotNull(target,ref);
            }
            row.values().forEach(v -> checkReferences(v,root));
        } else if(value instanceof List<?> list) list.forEach(v -> checkReferences(v,root));
    }
}
