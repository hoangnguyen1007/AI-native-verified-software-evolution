package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import java.io.*;
import java.util.*;
import static com.evolution.analysis.acquisition.streaming.InventoryEntry.*;

/** Logical containment and alias proof only; never dereferences a host symlink. */
final class InventoryLinkResolver {
    static void resolve(SqliteInventoryStore inventory,String revision)throws IOException{
        Optional<String> cursor=Optional.empty();
        do{var page=inventory.page(revision,256,cursor);for(var entry:page.entries()){
            if(entry.kind()!=Kind.SYMBOLIC_LINK||entry.disposition()==Disposition.EXCLUDED||entry.target().isEmpty())continue;
            var current=entry;var visited=new HashSet<LogicalPath>();boolean valid=true;String reason="LINK_CLOSURE_REQUIRED";
            while(current.kind()==Kind.SYMBOLIC_LINK){
                if(visited.size()>=4096){valid=false;reason="LINK_WORKER_REQUIRED";break;}
                if(!visited.add(current.path())||current.path().utf8().isEmpty()||current.target().isEmpty()){valid=false;break;}
                String target=current.target().orElseThrow();
                if(target.startsWith("/")||target.indexOf('\\')>=0||target.indexOf(':')>=0||target.indexOf('\0')>=0){valid=false;break;}
                String name=current.path().utf8().orElseThrow();var segments=new ArrayDeque<>(List.of(name.split("/")));segments.removeLast();
                for(String part:target.split("/")){if(part.equals("..")){if(segments.isEmpty()){valid=false;break;}segments.removeLast();}else if(!part.equals(".")&&!part.isEmpty())segments.add(part);}
                if(!valid||segments.isEmpty()){valid=false;break;}var next=inventory.observation(revision,LogicalPath.utf8(String.join("/",segments)));
                if(next.isEmpty()||next.orElseThrow().entry().disposition()==Disposition.EXCLUDED||next.orElseThrow().entry().kind()!=Kind.SYMBOLIC_LINK&&next.orElseThrow().entry().disposition()!=Disposition.ACQUIRED){valid=false;break;}current=next.orElseThrow().entry();
            }
            var resolved=new InventoryEntry(entry.path(),entry.kind(),entry.mode(),entry.population(),valid?Disposition.ACQUIRED:Disposition.GAP,entry.content(),entry.target(),entry.encoding(),entry.ownershipCandidates(),valid?Optional.empty():Optional.of(reason));
            if(!resolved.equals(entry))inventory.entries(revision,List.of(new SqliteInventoryStore.Observation(resolved,inventory.observation(revision,entry.path()).orElseThrow().stamp())));
        }cursor=page.continuation();}while(cursor.isPresent());
    }
    private InventoryLinkResolver(){}
}
