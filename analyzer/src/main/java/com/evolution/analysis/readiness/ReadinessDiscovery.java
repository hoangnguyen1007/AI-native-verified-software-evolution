package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import com.evolution.analysis.readiness.ReadinessPlan.Module;
import java.util.*;

public interface ReadinessDiscovery {
    record Descriptor(String path, ContentHandle content, byte[] bytes) {
        public Descriptor {bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    record Projection(List<Root> roots, List<Module> modules, List<Context> contexts, List<Requirement> requirements, List<String> unknown) {
        public Projection {roots=List.copyOf(roots);modules=List.copyOf(modules);contexts=List.copyOf(contexts);requirements=List.copyOf(requirements);unknown=List.copyOf(unknown);}
    }
    Projection discover(Map<String,Descriptor> descriptors, com.evolution.analysis.acquisition.streaming.InventoryManifest inventory);
}
