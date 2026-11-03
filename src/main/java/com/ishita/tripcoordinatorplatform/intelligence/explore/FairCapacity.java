package com.ishita.tripcoordinatorplatform.intelligence.explore;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.*;

/** Single-instance, work-conserving round-robin among authenticated actors; no time-window allowance. */
public final class FairCapacity {
    private final int capacity, actorCapacity, queueCapacity;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final LinkedHashMap<Long,ArrayDeque<Object>> waiting = new LinkedHashMap<>();
    private final Map<Long,Integer> running = new HashMap<>();
    private int active, queued;
    public FairCapacity(int capacity,int actorCapacity,int queueCapacity) {
        if(capacity<1 || actorCapacity<1 || actorCapacity>capacity || queueCapacity<1) throw new IllegalArgumentException("Invalid provider capacity");
        this.capacity=capacity;this.actorCapacity=actorCapacity;this.queueCapacity=queueCapacity;
    }
    public Permit acquire(Long actor,Instant deadline) {
        if(actor==null || actor<=0) throw new IllegalArgumentException("Verified actor is required for capacity admission");
        Object ticket=new Object();lock.lock();
        try {
            if(queued>=queueCapacity || waiting.getOrDefault(actor,new ArrayDeque<>()).size()>=Math.max(1,queueCapacity/4)) throw unavailable("Capacity waiting queue is full; no paid work was dispatched.");
            waiting.computeIfAbsent(actor,id->new ArrayDeque<>()).addLast(ticket);queued++;
            while(true) {
                if(Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) throw unavailable("Capacity wait cancelled or deadline reached; no paid work was dispatched.");
                Long next=waiting.keySet().stream().filter(id->running.getOrDefault(id,0)<actorCapacity).findFirst().orElse(null);
                if(active<capacity && actor.equals(next) && waiting.get(actor).getFirst()==ticket) {
                    var queue=waiting.remove(actor);queue.removeFirst();queued--;
                    if(!queue.isEmpty()) waiting.put(actor,queue); // move actor to the back after each grant
                    active++;running.merge(actor,1,Integer::sum);changed.signalAll();return new Permit(actor);
                }
                try { changed.awaitNanos(Math.max(1,java.time.Duration.between(Instant.now(),deadline).toNanos())); }
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw unavailable("Capacity wait interrupted; no paid work was dispatched.");}
            }
        } finally {
            var queue=waiting.get(actor);
            if(queue!=null && queue.remove(ticket)) {queued--;if(queue.isEmpty()) waiting.remove(actor);changed.signalAll();}
            lock.unlock();
        }
    }
    private ProviderUnavailableException unavailable(String message) {return new ProviderUnavailableException(message);}
    public final class Permit implements AutoCloseable {
        private final Long actor;private boolean closed;
        private Permit(Long actor) {this.actor=actor;}
        public void close() {lock.lock();try {if(!closed) {closed=true;active--;running.compute(actor,(id,n)->n==1 ? null:n-1);changed.signalAll();}}finally {lock.unlock();}}
    }
}
