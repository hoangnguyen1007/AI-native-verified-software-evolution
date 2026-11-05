package com.evolution.analysis.polyglot;

import java.net.URI;
import java.util.*;

/** Deliberately bounded URI candidate matching, not deployment/routing proof. */
public final class HttpUriPattern {
    public enum Match { MATCH, NO_MATCH, UNSUPPORTED }
    private HttpUriPattern() {}
    public static Match match(String request,String route) {
        var path=requestPath(request);
        if(path.isEmpty()||route==null||!route.startsWith("/")||unsafe(route))return Match.UNSUPPORTED;
        var actual=path.orElseThrow().split("/",-1);var expected=route.split("/",-1);
        // No target-supplied regular expressions or glob engine runs here.
        for(String part:expected)if(special(part)&&!variable(part))return Match.UNSUPPORTED;
        if(actual.length!=expected.length)return Match.NO_MATCH;
        for(int i=0;i<actual.length;i++) {
            if(variable(expected[i])){if(actual[i].isEmpty())return Match.NO_MATCH;}
            else if(!actual[i].equals(expected[i]))return Match.NO_MATCH;
        }
        return Match.MATCH;
    }
    public static Optional<String> requestPath(String request) {
        if(request==null||unsafe(request))return Optional.empty();
        try {
            var uri=URI.create(request);
            if(uri.isAbsolute()&&(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null))return Optional.empty();
            if(!uri.isAbsolute()&&uri.getRawAuthority()!=null)return Optional.empty();
            String path=uri.getRawPath();
            if(path==null||!path.startsWith("/")||unsafe(path)||Arrays.stream(path.split("/",-1)).anyMatch(HttpUriPattern::special))return Optional.empty();
            return Optional.of(path);
        }catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    private static boolean unsafe(String value) {
        String lower=value.toLowerCase(Locale.ROOT);
        return value.indexOf('\\')>=0||value.indexOf(';')>=0||lower.contains("%2f")||lower.contains("%5c")||lower.contains("%2e")
                ||Arrays.stream(value.split("/",-1)).anyMatch(p->p.equals(".")||p.equals(".."));
    }
    private static boolean variable(String part){return part.matches("\\{[A-Za-z_][A-Za-z0-9_]*}")||part.matches(":[A-Za-z_][A-Za-z0-9_]*");}
    private static boolean special(String part){return part.indexOf('{')>=0||part.indexOf('}')>=0||part.indexOf('*')>=0||part.startsWith(":");}
}
