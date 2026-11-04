package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.qualification.SandboxQualificationGate;
import java.util.*;

/** Operator data, outside the analyzed repository. Registration is not qualification. */
public record NativeBuildProfile(String schema,String workspace,String stateDirectory,FilePin storageReceipt,String storageOwner,
        FilePin python,FilePin powershell,String pythonModules,FilePin pythonModuleManifest,
        String baseRoot,FilePin disk,List<FilePin> images,Network network,Resources resources,
        Map<String,GuestTool> tools,List<Materials> materials,Map<String,List<String>> taskMappings,Validation validation) {
    public static final String SCHEMA="native-build-hyperv-profile:m8.4-v1";
    public record FilePin(String path,ContentHandle content){public FilePin{ExecutionGraph.text(path);Objects.requireNonNull(content);}}
    public record GuestTool(String executable,NativeBuildRecipes.Tool tool){public GuestTool{if(!executable.startsWith("/")||executable.contains("\0")||executable.contains(".."))throw new IllegalArgumentException("INVALID_GUEST_TOOL");Objects.requireNonNull(tool);}}
    public record Materials(String role,String directory,FilePin manifest){public Materials{if(!Set.of("maven","gradle").contains(role))throw new IllegalArgumentException("INVALID_MATERIAL_ROLE");ExecutionGraph.text(directory);Objects.requireNonNull(manifest);}}
    public record Network(String switchName,String mac,String guestCidr,String controller){
        public Network{ExecutionGraph.text(switchName);if(!mac.matches("[0-9A-F]{12}")||!guestCidr.matches("[0-9.]+/[0-9]{1,2}")||!controller.matches("[0-9.]+"))throw new IllegalArgumentException("INVALID_NATIVE_NETWORK");}
    }
    public record Resources(int processors,long memoryBytes,long buildMemoryBytes,int tasksMax,int cpuPercent,
            long workBytes,long overlayBytes,long inputBytes,long logBytes,long runtimeMillis,long controlMillis,
            int maxRowBytes,long maxObjectBytes,long maxCaptureBytes,long maxManifestBytes){
        public Resources{
            if(processors<1||memoryBytes<268435456||buildMemoryBytes<67108864||buildMemoryBytes>=memoryBytes||tasksMax<1||cpuPercent<1
                    ||workBytes<16777216||workBytes%4096!=0||overlayBytes<1||inputBytes<1||logBytes<1||runtimeMillis<1000||controlMillis<1000
                    ||maxRowBytes<1024||maxRowBytes>1048576||maxObjectBytes<0||maxCaptureBytes<0||maxManifestBytes<1024)throw new IllegalArgumentException("INVALID_NATIVE_RESOURCES");
            try{Math.multiplyExact(Math.addExact(runtimeMillis,Math.multiplyExact(controlMillis,8)),1_000_000L);}catch(ArithmeticException overflow){throw new IllegalArgumentException("NATIVE_TIME_BUDGET_OVERFLOW",overflow);}
        }
    }
    public record Validation(VersionedIdentifier controller,long maxAgeSeconds,Map<String,String> publicKeys,
            String revocations,Optional<SandboxQualificationGate.Certificate> certificate,Optional<EvidenceVerifier.SignatureEnvelope> signature){
        public Validation{Objects.requireNonNull(controller);if(maxAgeSeconds<1)throw new IllegalArgumentException("INVALID_VALIDATION_AGE");publicKeys=Map.copyOf(publicKeys);ExecutionGraph.text(revocations);Objects.requireNonNull(certificate);Objects.requireNonNull(signature);if(certificate.isPresent()!=signature.isPresent())throw new IllegalArgumentException("INCOMPLETE_NATIVE_CERTIFICATE");}
    }
    public NativeBuildProfile{
        if(!SCHEMA.equals(schema))throw new IllegalArgumentException("INVALID_NATIVE_PROFILE");
        for(String path:List.of(workspace,stateDirectory,pythonModules,baseRoot,storageOwner))ExecutionGraph.text(path);
        Objects.requireNonNull(storageReceipt);Objects.requireNonNull(python);Objects.requireNonNull(powershell);Objects.requireNonNull(pythonModuleManifest);Objects.requireNonNull(disk);
        images=List.copyOf(images);if(images.isEmpty()||!images.contains(disk))throw new IllegalArgumentException("IMAGE_CHAIN_REQUIRED");
        Objects.requireNonNull(network);Objects.requireNonNull(resources);Objects.requireNonNull(validation);tools=Map.copyOf(tools);materials=List.copyOf(materials);
        if(!Set.of("maven","gradle").containsAll(tools.keySet()))throw new IllegalArgumentException("INVALID_NATIVE_TOOLS");
        tools.forEach((role,tool)->{if(!role.equals(tool.tool().role())||!"linux".equals(tool.tool().os()))throw new IllegalArgumentException("NATIVE_TOOL_SCOPE_MISMATCH");});
        var mappings=new TreeMap<String,List<String>>();taskMappings.forEach((key,value)->mappings.put(key,List.copyOf(value)));taskMappings=Collections.unmodifiableMap(mappings);
        if(materials.stream().map(Materials::role).distinct().count()!=materials.size())throw new IllegalArgumentException("DUPLICATE_MATERIAL_ROLE");
    }
}
