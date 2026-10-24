package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.IOException;
import java.util.*;

/** Resolves redundant wire coordinates against the actual selected readiness plan. */
public final class JavaEvidenceScopes {
    public static Map<String,Object> scope(ReadinessPlan plan,ReadinessPlan.Context context,ModuleDescriptor module)throws IOException{
        if(plan.contexts().stream().filter(c->c.id().equals(context.id())).count()!=1||plan.contexts().stream().noneMatch(context::equals)||!plan.selection().selected().contains(context.id()))throw new IOException("JAVA_CONTEXT_NOT_SELECTED");
        var owners=plan.modules().stream().filter(m->m.id().equals(context.module())).toList();var roots=plan.roots().stream().filter(r->r.id().equals(context.root())).toList();
        if(owners.size()!=1||roots.size()!=1||!owners.getFirst().root().equals(context.root())||!owners.getFirst().directory().equals(module.path())||!plan.inventory().repository().equals(module.repository()))throw new IOException("JAVA_SCOPE_MODULE_ROOT_MISMATCH");
        var scope=new LinkedHashMap<String,Object>();scope.put("inventory",plan.inventory().inventory().value());scope.put("baseSnapshot",plan.inventory().compatibleV1Snapshot().map(com.evolution.analysis.contract.identity.SnapshotIdentity::value).orElse(null));scope.put("readiness",plan.identity().value());scope.put("selection",plan.selection().identity().value());scope.put("context",context.identity().value());
        scope.put("logicalScope",CanonicalJson.sha256(Map.of("schema","java-logical-scope:m8.4-v1","repository",module.repository(),"root",context.root(),"moduleKey",context.module(),"sourceSet",context.sourceSet(),"variant",context.variant())).value());scope.put("module",module.identity().value());scope.put("sourceSet",context.sourceSet());scope.put("variant",context.variant());return Collections.unmodifiableMap(scope);
    }
    public static void require(ReadinessPlan plan,ReadinessPlan.Context context,ModuleDescriptor module,Map<String,Object> supplied)throws IOException{if(!scope(plan,context,module).equals(supplied))throw new IOException("JAVA_EVIDENCE_READINESS_SCOPE_MISMATCH");}
    private JavaEvidenceScopes(){}
}
