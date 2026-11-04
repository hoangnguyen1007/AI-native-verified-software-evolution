package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Only an operator-written, pinned catalog selects a native worker; repository data cannot. */
public final class NativeBuildRegistrations {
    public record Selected(String name,Path file,Map<String,Object> document,ContentDigest pin){}
    private static Path catalog(String name)throws IOException{if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))throw new IllegalArgumentException("INVALID_NATIVE_PROFILE_NAME");return NativeBuildFiles.safe(Path.of(System.getProperty("user.home"),".evo","native-build","registrations",name+".json").toString());}
    public static Optional<Selected> selected(String name,Path source)throws IOException{
        Path file=catalog(name);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Optional.empty();var registration=NativeBuildFiles.read(file,16384);
        if(!registration.keySet().equals(Set.of("schema","profile","pin"))||!"native-build-registration:m8.4-v1".equals(registration.get("schema")))throw new IOException("NATIVE_REGISTRATION_INVALID");
        var profile=NativeBuildFiles.safe(GraphJson.string(registration.get("profile")));if(source!=null&&profile.startsWith(source.toAbsolutePath().normalize()))throw new IOException("NATIVE_REGISTRATION_INSIDE_TARGET");
        var pin=new ContentDigest(GraphJson.string(registration.get("pin")));if(!NativeBuildFiles.hash(profile).digest().equals(pin))throw new IOException("NATIVE_REGISTRATION_CHANGED");
        return Optional.of(new Selected(name,profile,NativeBuildFiles.read(profile,1048576),pin));
    }
    public static void register(String name,Path profile)throws IOException{
        profile=NativeBuildFiles.safe(profile.toString());var document=NativeBuildFiles.read(profile,1048576);
        if(!NativeBuildProfile.SCHEMA.equals(document.get("schema")))throw new IOException("INVALID_NATIVE_PROFILE");
        Path file=catalog(name);Files.createDirectories(file.getParent());NativeBuildFiles.write(file,Map.of("schema","native-build-registration:m8.4-v1","profile",profile.toString(),"pin",NativeBuildFiles.hash(profile).digest()));
    }
    public static void unchanged(Selected selected)throws IOException{var current=selected(selected.name(),null);if(current.isEmpty()||!current.orElseThrow().file().equals(selected.file())||!current.orElseThrow().pin().equals(selected.pin()))throw new IOException("NATIVE_REGISTRATION_CHANGED");}
    private NativeBuildRegistrations(){}
}
