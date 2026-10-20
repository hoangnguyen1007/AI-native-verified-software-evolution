package com.evolution.analysis.execution.hyperv;

import com.evolution.analysis.execution.SandboxExecutor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HyperVProbeTest {
    @Test void presenceIsNotIsolationQualificationAndInputCannotInjectScript()throws Exception{
        var probe=new HyperVProbe(Path.of("powershell.exe"),(args,deadline)->{
            assertTrue(args.contains("-NoProfile"));assertTrue(args.contains("-NonInteractive"));assertEquals("-EncodedCommand",args.get(args.size()-2));
            return "{\"vmName\":\"evo-sandbox\",\"vmId\":\"1ffbdfff-ec60-40c8-abe9-24ec946be118\",\"state\":\"Saved\",\"processors\":4,\"memoryBytes\":2147483648,\"reason\":\"\"}";
        });
        assertEquals(SandboxExecutor.Availability.UNQUALIFIED,probe.readiness("evo-sandbox").availability());
        assertThrows(IllegalArgumentException.class,()->probe.inspect("'; Start-Process malicious; #"));
    }
    @Test void missingVMAndWrongProbeScopeRemainExplicit()throws Exception{
        var missing=new HyperVProbe(Path.of("powershell.exe"),(args,deadline)->"{\"vmName\":\"\",\"vmId\":\"\",\"state\":\"\",\"processors\":0,\"memoryBytes\":0,\"reason\":\"HYPERV_VM_UNAVAILABLE\"}");
        assertEquals(SandboxExecutor.Availability.UNAVAILABLE,missing.readiness("evo-sandbox").availability());
        var bad=new HyperVProbe(Path.of("powershell.exe"),(args,deadline)->"{\"arbitrary\":true}");assertThrows(java.io.IOException.class,()->bad.inspect("evo-sandbox"));
    }
    @Test void malformedProbePayloadIsAnIoFailureRatherThanUncontrolledRuntimeException(){
        String valid="{\"vmName\":\"evo-sandbox\",\"vmId\":\"1ffbdfff-ec60-40c8-abe9-24ec946be118\",\"state\":\"Saved\",\"processors\":4,\"memoryBytes\":2147483648,\"reason\":\"\"}";
        for(String raw:java.util.List.of("{", "[]",valid.replace("\"processors\":4","\"processors\":1.5"),valid.replace("2147483648","999999999999999999999999999"))){
            var probe=new HyperVProbe(Path.of("powershell.exe"),(args,deadline)->raw);
            assertThrows(java.io.IOException.class,()->probe.inspect("evo-sandbox"));
        }
    }

}
