package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** A required pack is not a delivered or qualified implementation. */
public record LanguageProfile(String language,String engineFamily,List<String> categories,String qualification) {
    public static final String SCHEMA="language-profile:m8.4-v1";
    public LanguageProfile {
        language=ContractChecks.token(language,"language");engineFamily=ContractChecks.token(engineFamily,"engine family");
        categories=List.copyOf(categories);if(!categories.equals(requiredCategories()))throw new IllegalArgumentException("CATEGORY_CATALOG_INCOMPLETE");
        if(!Set.of("REQUIRED_UNQUALIFIED","LEGACY_JAVA_CONTRACT").contains(qualification))throw new IllegalArgumentException("PACK_QUALIFICATION_NOT_ESTABLISHED");
        if(qualification.equals("LEGACY_JAVA_CONTRACT")&&!language.equals("java"))throw new IllegalArgumentException("FOREIGN_LEGACY_CONTRACT");
    }
    public static List<String> requiredCategories(){return java.util.stream.IntStream.rangeClosed(1,12).mapToObj(i->String.format(Locale.ROOT,"A%02d",i)).toList();}
    public static List<LanguageProfile> baseline(){
        var engines=new TreeMap<>(Map.ofEntries(Map.entry("java","javaparser"),Map.entry("typescript","typescript-checker"),Map.entry("javascript","typescript-checker"),
            Map.entry("kotlin","kotlin-compiler"),Map.entry("scala","scala-compiler"),Map.entry("groovy","groovy-compiler"),Map.entry("python","pyright"),Map.entry("go","go-types"),
            Map.entry("rust","rust-analyzer"),Map.entry("c","clang"),Map.entry("cpp","clang"),Map.entry("csharp","roslyn"),Map.entry("visualbasic","roslyn-vb"),
            Map.entry("php","phpstan"),Map.entry("ruby","sorbet"),Map.entry("dart","dart-analyzer"),Map.entry("swift","swift-compiler"),Map.entry("objectivec","clang")));
        return engines.entrySet().stream().map(e->new LanguageProfile(e.getKey(),e.getValue(),requiredCategories(),e.getKey().equals("java")?"LEGACY_JAVA_CONTRACT":"REQUIRED_UNQUALIFIED")).toList();
    }
    /** Tool/provider versions intentionally do not occur in a logical declaration key. */
    public static ContentDigest symbol(String language,String repositoryScope,String packageScope,String owner,String signature){
        for(String value:List.of(language,repositoryScope,packageScope,owner,signature))if(value==null||value.isBlank())throw new IllegalArgumentException("SYMBOL_SCOPE_REQUIRED");
        return CanonicalJson.sha256(List.of("language-symbol:m8.4-v1",language,repositoryScope,packageScope,owner,signature));
    }
}
