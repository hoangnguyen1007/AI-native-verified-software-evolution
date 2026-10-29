package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Evidence-qualified classification. Suffixes never prove native source membership. */
public final class LanguageDiscovery {
    public static final String VERSION="language-discovery:m8.4-v1";
    private static final Map<String,String> EXTENSIONS=Map.ofEntries(
        Map.entry("java","java"),Map.entry("kt","kotlin"),Map.entry("kts","kotlin"),Map.entry("scala","scala"),Map.entry("sc","scala"),
        Map.entry("groovy","groovy"),Map.entry("gvy","groovy"),Map.entry("py","python"),Map.entry("pyi","python"),Map.entry("pyw","python"),
        Map.entry("js","javascript"),Map.entry("jsx","javascript"),Map.entry("mjs","javascript"),Map.entry("cjs","javascript"),
        Map.entry("ts","typescript"),Map.entry("tsx","typescript"),Map.entry("mts","typescript"),Map.entry("cts","typescript"),
        Map.entry("rs","rust"),Map.entry("go","go"),Map.entry("cs","csharp"),Map.entry("vb","visualbasic"),
        Map.entry("c","c"),Map.entry("cpp","cpp"),Map.entry("cc","cpp"),Map.entry("cxx","cpp"),Map.entry("hpp","cpp"),Map.entry("hxx","cpp"),Map.entry("hh","cpp"),
        Map.entry("mm","objectivecpp"),Map.entry("rb","ruby"),Map.entry("rake","ruby"),Map.entry("php","php"),Map.entry("phtml","php"),
        Map.entry("swift","swift"),Map.entry("dart","dart"),Map.entry("vue","vue"),Map.entry("svelte","svelte"),Map.entry("astro","astro"),
        Map.entry("html","html"),Map.entry("htm","html"),Map.entry("css","css"),Map.entry("scss","scss"),Map.entry("sass","sass"),Map.entry("less","less"),
        Map.entry("sql","sql"),Map.entry("proto","protobuf"),Map.entry("graphql","graphql"),Map.entry("gql","graphql"),Map.entry("xml","xml"),
        Map.entry("json","json"),Map.entry("yaml","yaml"),Map.entry("yml","yaml"),Map.entry("toml","toml"),Map.entry("hcl","hcl"),Map.entry("tf","hcl"),
        Map.entry("jsp","jsp"),Map.entry("aj","aspectj"),Map.entry("ipynb","notebook"));
    public enum State { HINT, NATIVE_MEMBERSHIP, AMBIGUOUS, CONFLICTED, UNKNOWN }
    public record Detection(State state,List<String> candidates,ContentDigest evidence) {
        public Detection {Objects.requireNonNull(state);candidates=candidates.stream().sorted().distinct().toList();Objects.requireNonNull(evidence);
            if((state==State.UNKNOWN)!=candidates.isEmpty()||Set.of(State.HINT,State.NATIVE_MEMBERSHIP).contains(state)&&candidates.size()!=1||state==State.AMBIGUOUS&&candidates.size()<2)throw new IllegalArgumentException("Invalid classification candidates");}
        public Optional<String> language(){return candidates.size()==1&&state!=State.CONFLICTED?Optional.of(candidates.getFirst()):Optional.empty();}
    }
    public static List<String> hints(String path){
        String name=path.substring(path.lastIndexOf('/')+1);int dot=name.lastIndexOf('.');
        if(name.endsWith(".C"))return List.of("cpp");
        String ext=dot<0?"":name.substring(dot+1);
        if(ext.equals("h"))return List.of("c","cpp","objectivec","objectivecpp");
        if(ext.equals("m"))return List.of("matlab","objectivec");
        if(name.equals("Rakefile")||name.equals("Gemfile"))return List.of("ruby");
        if(name.equals("Jenkinsfile"))return List.of("groovy");
        return Optional.ofNullable(EXTENSIONS.get(ext)).map(List::of).orElse(List.of());
    }
    public static Detection detect(String path,byte[] prefix,Optional<String> nativeLanguage){
        Objects.requireNonNull(path);Objects.requireNonNull(prefix);Objects.requireNonNull(nativeLanguage);
        var candidates=new TreeSet<>(hints(path));
        String line=new String(prefix,StandardCharsets.ISO_8859_1).split("[\r\n]",2)[0];
        var shebang=new TreeSet<String>();
        if(line.startsWith("#!"))for(String token:interpreter(line.substring(2).trim())){
            String executable=token.substring(token.lastIndexOf('/')+1);
            if(executable.matches("python(?:[23](?:\\.[0-9]+)?)?"))shebang.add("python");
            if(executable.matches("node(?:js)?|bun|deno"))shebang.add("javascript");
            if(executable.matches("ruby(?:[0-9.]+)?"))shebang.add("ruby");
            if(executable.equals("php"))shebang.add("php");
        }
        boolean conflict=!candidates.isEmpty()&&!shebang.isEmpty()&&Collections.disjoint(candidates,shebang);
        candidates.addAll(shebang);
        State state=candidates.isEmpty()?State.UNKNOWN:candidates.size()==1?State.HINT:State.AMBIGUOUS;
        if(conflict)state=State.CONFLICTED;
        if(nativeLanguage.isPresent()){
            String language=nativeLanguage.orElseThrow();if(language.isBlank())throw new IllegalArgumentException("Empty native language");
            if(!conflict&&(candidates.isEmpty()||candidates.contains(language))){candidates.clear();candidates.add(language);state=State.NATIVE_MEMBERSHIP;}
            else{candidates.add(language);state=State.CONFLICTED;}
        }
        return new Detection(state,List.copyOf(candidates),CanonicalJson.sha256(List.of(VERSION,path,ContentDigest.sha256(prefix),nativeLanguage)));
    }
    private static List<String> interpreter(String command){
        String[] tokens=command.split("\\s+");if(tokens.length==0)return List.of();
        if(!tokens[0].substring(tokens[0].lastIndexOf('/')+1).equals("env"))return List.of(tokens[0]);
        for(int i=1;i<tokens.length;i++){
            String token=tokens[i];
            if(Set.of("-u","--unset","-C","--chdir").contains(token)){i++;continue;}
            if(token.equals("-S")||token.equals("--split-string")||token.equals("-i")||token.equals("--ignore-environment")||token.equals("--"))continue;
            if(token.matches("[A-Za-z_][A-Za-z0-9_]*=.*")||token.startsWith("--unset=")||token.startsWith("--chdir="))continue;
            if(token.startsWith("-")||token.indexOf('\'')>=0||token.indexOf('"')>=0)return List.of();
            return List.of(token);
        }
        return List.of();
    }
    public static boolean buildDescriptor(String name){return Set.of("go.work","Package.resolved","deno.json","deno.jsonc","import_map.json","bun.lock","bun.lockb","uv.lock","poetry.lock","tsconfig.json","jsconfig.json","Directory.Build.props","Directory.Build.targets","Directory.Packages.props").contains(name)||name.endsWith(".vbproj")||name.endsWith(".fsproj")||name.endsWith(".slnx");}
    private LanguageDiscovery(){}
}
