package com.evolution.backend.analysis;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Explicit source selection. Every omitted path becomes a recorded acquisition exclusion. */
public final class ScanSelection {
    public record Plan(String policy,boolean includeTests,boolean allFiles,List<String> excluded) {}
    public static Plan plan(Path root,boolean includeTests,boolean allFiles,List<String> explicit)throws IOException{
        var excluded=new TreeSet<>(explicit);excluded.add(".git");excluded.add(".evo");
        if(!allFiles)visit(root,root,includeTests,excluded,new int[]{0},0);
        return new Plan("cli.source-selection:v1",includeTests,allFiles,List.copyOf(excluded));
    }
    private static void visit(Path root,Path directory,boolean tests,Set<String> excluded,int[] entries,int depth)throws IOException{
        if(depth>64)throw new IOException("SELECTION_DEPTH_LIMIT");
        var children=new ArrayList<Path>();
        try(var stream=Files.newDirectoryStream(directory)){for(Path p:stream){if(++entries[0]>50_000)throw new IOException("SELECTION_ENTRY_LIMIT");children.add(p);}}
        children.sort(Comparator.comparing(p->p.getFileName().toString()));
        boolean maven=Files.isRegularFile(directory.resolve("pom.xml"),NOFOLLOW_LINKS);
        boolean gradle=Files.isRegularFile(directory.resolve("build.gradle"),NOFOLLOW_LINKS)||Files.isRegularFile(directory.resolve("build.gradle.kts"),NOFOLLOW_LINKS);
        boolean node=Files.isRegularFile(directory.resolve("package.json"),NOFOLLOW_LINKS);
        for(Path path:children){
            String logical=root.relativize(path).toString().replace('\\','/'),name=path.getFileName().toString();
            if(excluded.stream().anyMatch(p->logical.equals(p)||logical.startsWith(p+"/")))continue;
            if(Files.isSymbolicLink(path))continue; // Acquisition retains its own explicit no-follow failure.
            if(Files.isDirectory(path,NOFOLLOW_LINKS)){
                if(name.equals(".git")||name.equals(".evo")||maven&&name.equals("target")||gradle&&Set.of("build",".gradle").contains(name)||node&&name.equals("node_modules")
                        ||!tests&&(logical.equals("src/test")||logical.endsWith("/src/test")||logical.endsWith("/src/integrationTest"))){excluded.add(logical);continue;}
                visit(root,path,tests,excluded,entries,depth+1);
            }else if(Files.isRegularFile(path,NOFOLLOW_LINKS)&&!input(logical))excluded.add(logical);
        }
    }
    private static boolean input(String path){
        String name=path.substring(path.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
        if(Set.of(".gitignore",".gitattributes",".classpath",".project","spring.factories","dockerfile","package.json").contains(name))return true;
        if(name.endsWith(".json"))return path.contains("/resources/")||name.startsWith("application")||name.startsWith("bootstrap");
        return List.of(".java",".xml",".properties",".yml",".yaml",".toml",".imports",".gradle",".kts",".kt",".scala",".groovy",".js",".ts",".cs",".c",".cpp",".h").stream().anyMatch(name::endsWith);
    }
    private ScanSelection(){}
}
