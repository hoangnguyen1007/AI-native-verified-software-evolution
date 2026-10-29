package com.evolution.analysis.execution.polyglot;

import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent authored applicability labels. These checks do not sign claims
 * or treat a faithful unsupported result as an implemented binding capability. */
final class NativeCategoryControls {
    static void verify(String name,Object raw,SemanticTables.Table census,SemanticTables.Table output,SemanticTables tables)throws IOException {
        var controls=GraphJson.object(raw);assertEquals(new TreeSet<>(LanguageProfile.requiredCategories()),controls.keySet(),name+": closed A01-A12 label denominator");var emitted=new TreeMap<String,Long>();var written=new TreeMap<String,Long>();boolean exactSemantic=false;
        try(var expected=tables.open(census,()->false);var observed=tables.open(output,()->false)){
            SemanticTables.Row question;while((question=expected.next())!=null){var row=observed.next();assertNotNull(row,name+": omitted native occurrence");assertEquals(question.key(),row.key(),name);String category=GraphJson.string(question.fields().get("category")),status=GraphJson.string(row.fields().get("status"));emitted.merge(category,1L,Math::addExact);String role=Objects.toString(question.fields().get("role"),"");
                if(!Set.of("syntax-absent","absent-category","opaque-syntax","opaque-location","implicit-generation-census").contains(role)&&!Set.of("NOT_APPLICABLE","ERROR").contains(status))written.merge(category,1L,Math::addExact);
                if(Set.of("A02","A03","A04","A05","A06","A07","A08","A09").contains(category)&&status.equals("RESOLVED"))exactSemantic=true;
                assertFalse(status.equals("ERROR"),name+": unexpected diagnostic/recovery error in an architecture-positive control: "+category+" "+row.key());
            }assertNull(observed.next(),name+": extra native occurrence");
        }
        for(var category:controls.entrySet()){var control=GraphJson.object(category.getValue());assertEquals(Set.of("written"),control.keySet());assertTrue(control.get("written") instanceof Boolean);assertTrue(emitted.getOrDefault(category.getKey(),0L)>0,name+": missing category "+category.getKey());if(Boolean.TRUE.equals(control.get("written")))assertTrue(written.getOrDefault(category.getKey(),0L)>0,name+": written syntax classified as absent, omitted or errored in "+category.getKey());}
        assertTrue(exactSemantic,name+": an all-unknown pack cannot pass qualification controls");
    }
}
