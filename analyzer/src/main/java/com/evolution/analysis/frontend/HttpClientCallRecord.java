package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** A bound client API call with a literal URI, or an empty URI requiring configuration
 * evidence. This does not assert route selection, activation or a runtime request. */
public record HttpClientCallRecord(EntityIdentity caller,EntityIdentity clientMethod,SourceSpan span,String uri,List<String> methods)
        implements Comparable<HttpClientCallRecord> {
    public HttpClientCallRecord {
        Objects.requireNonNull(caller);Objects.requireNonNull(clientMethod);Objects.requireNonNull(span);Objects.requireNonNull(uri);
        methods=methods.stream().sorted().distinct().toList();
        if(!Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE").containsAll(methods))throw new IllegalArgumentException("Invalid HTTP method");
    }
    @Override public int compareTo(HttpClientCallRecord other){return CanonicalJson.write(this).compareTo(CanonicalJson.write(other));}
}
