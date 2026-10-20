package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Out-of-band operator registry. A target's setup.json cannot approve its own host tools. */
public final class CoreMvpRegistrations {
    private CoreMvpRegistrations(){}
    public static void require(Path directory)throws IOException{
        var root=directory.toRealPath();var setup=CoreMvpBuildPipeline.read(root.resolve("setup.json"),65536);
        if(!"CORE_MVP".equals(setup.get("acceptanceScope"))||!"HUMAN".equals(setup.get("approvedBy")))throw new IOException("CORE_MVP_APPROVAL_REQUIRED");
        String attempt=UUID.fromString(CoreMvpBuildPipeline.text(setup,"attempt")).toString();
        var catalog=Path.of(System.getProperty("user.home"),".evo","core-mvp","registrations",attempt+".json");
        if(!Files.isRegularFile(catalog,LinkOption.NOFOLLOW_LINKS))throw new IOException("CORE_MVP_REGISTRATION_REQUIRED");
        var record=CoreMvpBuildPipeline.read(catalog,65536);
        if(!"core-mvp-registration:m8.4-v1".equals(record.get("schema"))||!root.equals(Path.of(CoreMvpBuildPipeline.text(record,"root")).toRealPath()))throw new IOException("CORE_MVP_REGISTRATION_SCOPE_MISMATCH");
        var pins=com.evolution.analysis.graph.GraphJson.object(record.get("files"));
        if(!pins.keySet().equals(Set.of("setup.json","provision-request.json","profile.json","controller-config.json","known_hosts")))throw new IOException("CORE_MVP_REGISTRATION_INVALID");
        for(var entry:pins.entrySet()){
            var path=root.resolve(entry.getKey());if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("CORE_MVP_REGISTRATION_CHANGED");
            java.security.MessageDigest digest;try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))!=-1)if(n>0)digest.update(bytes,0,n);}
            if(!new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest())).value().equals(entry.getValue()))throw new IOException("CORE_MVP_REGISTRATION_CHANGED");
        }
    }
}
