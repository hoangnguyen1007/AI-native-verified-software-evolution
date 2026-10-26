package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Trusted platform fault worker. It never executes target code or imports target classes. */
public final class JavaPublicationCrashFixture {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);String mode=args[1];var delegate=new StreamingContentStore(root.resolve("cas"));var key=ContentDigest.sha256Utf8("crash control");
        if(mode.equals("inspect")){
            var publication=new JavaEvidencePublicationStore(root.resolve("publications"),delegate).load(key).orElseThrow();
            System.out.print(publication.document().digest().value());return;
        }
        if(!Set.of("before-cas","before-pointer","after-pointer").contains(mode))throw new IllegalArgumentException("Unknown crash boundary");
        var next=delegate.put(new ByteArrayInputStream(new byte[]{3,4}),()->false);
        RepositoryContentStore interrupted=new RepositoryContentStore(){
            public ContentHandle put(InputStream input,BooleanSupplier cancelled)throws IOException{
                if(mode.equals("before-cas"))Runtime.getRuntime().halt(90);
                var handle=delegate.put(input,cancelled);
                if(mode.equals("before-pointer"))Runtime.getRuntime().halt(91);
                return handle;
            }
            public InputStream open(ContentHandle h)throws IOException{return delegate.open(h);}
            public InputStream range(ContentHandle h,long offset,long length)throws IOException{return delegate.range(h,offset,length);}
            public boolean verify(ContentHandle h)throws IOException{return delegate.verify(h);}
        };
        var store=new JavaEvidencePublicationStore(root.resolve("publications"),interrupted);var prior=store.load(key).orElseThrow();
        store.commit(key,Optional.of(prior.document().digest()),Map.of("graph",next));
        Runtime.getRuntime().halt(92);
    }
}
