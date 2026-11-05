package com.evolution.analysis.concurrent;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Request-owned workers with explicit admission. Callers await in input order;
 * scheduling never selects domain winners. Close drains cancelled workers before
 * their caller can close scratch storage or publish a result. */
public final class BoundedTasks implements AutoCloseable {
    private final ExecutorService executor;
    private final int capacity;
    private final BooleanSupplier cancelled;
    private final Set<Future<?>> pending=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    public BoundedTasks(int workers,int capacity,String name,BooleanSupplier cancelled) {
        if(workers<1||capacity<workers)throw new IllegalArgumentException("Positive bounded worker admission required");
        this.capacity=capacity;this.cancelled=Objects.requireNonNull(cancelled);
        var sequence=new AtomicInteger();var factory=Executors.defaultThreadFactory();
        executor=workers==1?null:Executors.newFixedThreadPool(workers,job->{var thread=factory.newThread(job);thread.setName(name+"-"+sequence.incrementAndGet());return thread;});
    }
    public static int automaticWorkers() {
        var runtime=Runtime.getRuntime();long headroom=runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory();
        return (int)Math.max(1,Math.min(Math.min(4,runtime.availableProcessors()),Math.min(runtime.maxMemory()/(128L<<20),headroom/(32L<<20))));
    }
    private void check() {if(closed)throw new IllegalStateException("WORKERS_CLOSED");if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    public <T> Task<T> submit(Callable<T> job) {
        check();if(pending.size()>=capacity)throw new IllegalStateException("WORKER_ADMISSION_FULL");
        Callable<T> checked=()->{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();return job.call();};
        Future<T> future;
        if(executor==null){var direct=new FutureTask<>(checked);direct.run();future=direct;}else future=executor.submit(checked);
        pending.add(future);return new Task<>(future);
    }
    public final class Task<T> {
        private final Future<T> future;
        private Task(Future<T> future){this.future=future;}
        public T await() {
            check();
            try {T result=future.get();check();return result;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException("WORKER_WAIT_INTERRUPTED");}
            catch(ExecutionException failed){
                var cause=failed.getCause();if(cause instanceof RuntimeException runtime)throw runtime;if(cause instanceof Error error)throw error;
                if(cause instanceof IOException io)throw new UncheckedIOException(io);
                throw new IllegalStateException("WORKER_FAILED",cause);
            } finally {pending.remove(future);}
        }
    }
    @Override public void close() {
        if(closed)return;closed=true;pending.forEach(task->task.cancel(true));pending.clear();
        if(executor==null)return;executor.shutdownNow();boolean interrupted=Thread.interrupted();
        try {while(!executor.isTerminated())try{executor.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException again){interrupted=true;}}
        finally {if(interrupted)Thread.currentThread().interrupt();}
    }
}
