package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.*;

public final class JavaParserFrontend implements ConcurrentSemanticFrontend {
    private static final java.util.concurrent.locks.ReentrantReadWriteLock RESOLVERS=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    public static final com.evolution.analysis.contract.common.VersionedIdentifier PROVIDER =
            new com.evolution.analysis.contract.common.VersionedIdentifier("frontend.javaparser", "3.28.2-m8.4-lambda-input-v11");
    @Override public long estimatedWorkingBytes(FrontendRequest request) {
        var paths=new java.util.HashSet<java.nio.file.Path>();request.platform().artifacts().forEach(a->paths.add(a.path().toAbsolutePath().normalize()));request.dependencies().forEach(b->paths.add(b.path().toAbsolutePath().normalize()));
        long symbols=0;
        try {
            for(var path:paths){long bytes;try{bytes=java.nio.file.Files.size(path);}catch(java.io.IOException|SecurityException unavailable){bytes=64L<<20;}symbols=Math.addExact(symbols,bytes);}
            return Math.addExact(ConcurrentSemanticFrontend.super.estimatedWorkingBytes(request),Math.multiplyExact(symbols,2));
        }catch(ArithmeticException enormous){return Long.MAX_VALUE;}
    }
    @Override public FrontendResult analyze(FrontendRequest request) {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        try{RESOLVERS.readLock().lockInterruptibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
        try{return new Extraction(request).run().validateFor(request);}
        finally {
            RESOLVERS.readLock().unlock();
            // JavaParser's process cache retains facades/solvers beyond analyze().
            // Never clear it under another active resolution, and never retain a
            // completed serial request merely because no scheduler owns the call.
            if(RESOLVERS.writeLock().tryLock())try{clearResolvers();}finally{RESOLVERS.writeLock().unlock();}
        }
    }
    @Override public void releaseCompletedContexts(){RESOLVERS.writeLock().lock();try{clearResolvers();}finally{RESOLVERS.writeLock().unlock();}}
    private static void clearResolvers(){synchronized(com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.class){com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade.clearInstances();}}
}
