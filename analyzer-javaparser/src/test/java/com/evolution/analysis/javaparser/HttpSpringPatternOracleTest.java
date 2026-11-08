package com.evolution.analysis.javaparser;

import com.evolution.analysis.polyglot.HttpUriPattern;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;
import static org.junit.jupiter.api.Assertions.*;

class HttpSpringPatternOracleTest {
    @Test void segmentCandidatesAgreeWithActualSpringAndKeepFrameworkBoundaries() {
        String route="/owners/*/pets/{petId}";
        var nativePattern=PathPatternParser.defaultInstance.parse(route);
        for(String path:List.of("/owners/17/pets/3","/owners/a/pets/x","/owners","/owners/a/b/pets/3","/owners/17/pets/3/","/vets/17/pets/3")) {
            var expected=nativePattern.matches(PathContainer.parsePath(path))?HttpUriPattern.Match.MATCH:HttpUriPattern.Match.NO_MATCH;
            assertEquals(expected,HttpUriPattern.springCandidateMatch(path,route),path);
        }
        assertEquals(HttpUriPattern.Match.MATCH,HttpUriPattern.springCandidateMatch("/owners/{ownerId}/pets/{petId}",route));
        assertEquals(HttpUriPattern.Match.UNSUPPORTED,HttpUriPattern.candidateMatch("/owners/17/pets/3",route));
        for(String opaque:List.of("/owners/**","/owners/{id:[0-9]+}","/owners/o?ner"))
            assertEquals(HttpUriPattern.Match.UNSUPPORTED,HttpUriPattern.springCandidateMatch("/owners/17",opaque));
        assertEquals(HttpUriPattern.Match.UNSUPPORTED,HttpUriPattern.springCandidateMatch("/owners/a%2Fb/pets/3",route));
    }
}
