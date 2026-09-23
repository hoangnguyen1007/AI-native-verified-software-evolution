package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import java.util.*;

/** Version-qualified metadata policy, not certification of every container behavior in a major release. */
public record FrameworkGeneration(ContentDigest evidence,Optional<String> frameworkVersion,Optional<String> bootVersion,
                                  Namespace namespace,OverridePolicy overridePolicy,MetadataPolicy metadataPolicy,boolean consistent) {
    public enum Namespace { JAVAX, JAKARTA, UNKNOWN }
    public enum OverridePolicy { ALLOW, DENY, UNKNOWN }
    public enum MetadataPolicy { FACTORIES, BOTH, IMPORTS, NONE, UNKNOWN }
    public static FrameworkGeneration from(SpringFrameworkEvidence framework,Optional<Boolean> explicitOverride) {
        var springs=framework.artifacts().stream().filter(a->a.groupId().equals("org.springframework")).map(SpringFrameworkEvidence.Artifact::version).distinct().sorted().toList();
        var boots=framework.artifacts().stream().filter(a->a.groupId().equals("org.springframework.boot")).map(SpringFrameworkEvidence.Artifact::version).distinct().sorted().toList();
        boolean consistent=framework.completeClasspath()&&springs.size()==1&&boots.size()<=1;
        String spring=springs.size()==1?springs.getFirst():"",boot=boots.size()==1?boots.getFirst():"";
        int[] s=version(spring),b=version(boot);
        Namespace ns=s[0]>=1&&s[0]<=5?Namespace.JAVAX:s[0]==6?Namespace.JAKARTA:Namespace.UNKNOWN;
        if(!boot.isEmpty())consistent&=b[0]==1&&s[0]>=3&&s[0]<=4||b[0]==2&&s[0]==5||b[0]==3&&s[0]==6;
        consistent&=ns!=Namespace.UNKNOWN;
        OverridePolicy override=OverridePolicy.UNKNOWN;MetadataPolicy metadata=MetadataPolicy.UNKNOWN;
        if(consistent) {
            override=explicitOverride.map(v->v?OverridePolicy.ALLOW:OverridePolicy.DENY).orElse(
                    b[0]<2||b[0]==2&&b[1]==0?OverridePolicy.ALLOW:OverridePolicy.DENY);
            metadata=boot.isEmpty()?MetadataPolicy.NONE:b[0]==1||b[0]==2&&b[1]<7?MetadataPolicy.FACTORIES:
                    b[0]==2?MetadataPolicy.BOTH:MetadataPolicy.IMPORTS;
        }
        return new FrameworkGeneration(IngestionEvidence.digest(List.of("spring.generation:m4u.2-v1",framework.identity(),explicitOverride)),
                spring.isEmpty()?Optional.empty():Optional.of(spring),boot.isEmpty()?Optional.empty():Optional.of(boot),
                consistent?ns:Namespace.UNKNOWN,override,metadata,consistent);
    }
    private static int[] version(String text) {
        var match=java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[.-][A-Za-z0-9.-]+)?$").matcher(text);
        if(!match.matches())return new int[]{-1,-1,-1};
        try{return new int[]{Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))};}
        catch(NumberFormatException invalid){return new int[]{-1,-1,-1};}
    }
    public ContentDigest identity(){return IngestionEvidence.digest(this);}
}
