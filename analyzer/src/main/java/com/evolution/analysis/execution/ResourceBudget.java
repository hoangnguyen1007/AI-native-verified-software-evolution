package com.evolution.analysis.execution;

import java.util.*;

/** Checked weighted admission. Reservations are rebuilt from a durable journal after restart. */
public final class ResourceBudget {
    public record Resources(long cpu,long memory,long disk,long processes,long log,long network) {
        public Resources {if(cpu<0||memory<0||disk<0||processes<0||log<0||network<0)throw new IllegalArgumentException("NEGATIVE_RESOURCE");}
        public Resources plus(Resources b){return new Resources(Math.addExact(cpu,b.cpu),Math.addExact(memory,b.memory),Math.addExact(disk,b.disk),Math.addExact(processes,b.processes),Math.addExact(log,b.log),Math.addExact(network,b.network));}
        public boolean fits(Resources b){return cpu<=b.cpu&&memory<=b.memory&&disk<=b.disk&&processes<=b.processes&&log<=b.log&&network<=b.network;}
    }
    private final Resources capacity;
    private final Map<String,Resources> reservations=new TreeMap<>();
    public ResourceBudget(Resources capacity){this.capacity=Objects.requireNonNull(capacity);}
    public synchronized boolean reserve(String key,Resources requested){
        ExecutionGraph.text(key);Objects.requireNonNull(requested);
        if(reservations.containsKey(key)){if(!reservations.get(key).equals(requested))throw new IllegalArgumentException("RESERVATION_CONFLICT");return true;}
        var total=new Resources(0,0,0,0,0,0);
        try{for(var value:reservations.values())total=total.plus(value);total=total.plus(requested);}catch(ArithmeticException overflow){return false;}
        if(!total.fits(capacity))return false;reservations.put(key,requested);return true;
    }
    public synchronized void release(String key){reservations.remove(key);}
    public synchronized Map<String,Resources> reservations(){return Map.copyOf(reservations);}
}
