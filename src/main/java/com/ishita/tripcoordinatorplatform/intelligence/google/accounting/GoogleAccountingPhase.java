package com.ishita.tripcoordinatorplatform.intelligence.google.accounting;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** Request-lived plan, not provider-content cache. Ready dispatches share short authorization transactions. */
public final class GoogleAccountingPhase implements AutoCloseable {
    private record Waiting(GoogleUsageLedger.Attempt attempt, CompletableFuture<GoogleUsageLedger.Authorization> future) {}
    private final GoogleUsageLedger ledger;
    private final BiConsumer<String,Long> timing;
    private final ScheduledExecutorService batcher;
    private final UUID requestId;
    private final String key;
    private final List<GoogleUsageLedger.Slot> plan;
    private GoogleUsageLedger.Phase phase;
    private final Map<GoogleSku,ArrayDeque<GoogleUsageLedger.Attempt>> available=new EnumMap<>(GoogleSku.class);
    private final List<Waiting> pending=new ArrayList<>();
    private final List<GoogleUsageLedger.Completion> completed=new ArrayList<>();
    private boolean scheduled,closed;
    private RuntimeException reservationFailure, authorizationFailure;

    public GoogleAccountingPhase(GoogleUsageLedger ledger,ScheduledExecutorService batcher,UUID requestId,String key,Map<GoogleSku,Integer> plan) {
        this(ledger,batcher,requestId,key,plan,(stage,nanos)->{});
    }
    public GoogleAccountingPhase(GoogleUsageLedger ledger,ScheduledExecutorService batcher,UUID requestId,String key,Map<GoogleSku,Integer> plan,BiConsumer<String,Long> timing) {
        this.ledger=ledger;this.timing=timing;this.batcher=batcher;this.requestId=requestId;this.key=key;
        List<GoogleUsageLedger.Slot> slots=new ArrayList<>();
        for(var sku:GoogleSku.values()) {
            int count=plan.getOrDefault(sku,0);
            if(count<0||count>128) throw new IllegalArgumentException("Invalid Google phase bound");
            for(int i=0;i<count;i++) slots.add(new GoogleUsageLedger.Slot(sku.name()+"_"+i,sku));
        }
        if(slots.isEmpty()||slots.size()>128) throw new IllegalArgumentException("Invalid Google phase size");
        this.plan=List.copyOf(slots);
    }
    public <T> T dispatch(GoogleSku sku,Supplier<T> network) {
        CompletableFuture<GoogleUsageLedger.Authorization> future=new CompletableFuture<>();
        synchronized(this) {
            if(closed) throw new GoogleAccountingException("PHASE_CLOSED","Google accounting phase is closed");
            if(reservationFailure!=null) throw reservationFailure;
            if(authorizationFailure!=null) throw authorizationFailure;
            if(phase==null) {
                // Lazy reservation: a DB-only branch does not reserve/pay for hypothetical calls.
                try { phase=measured("accountingReservation",()->ledger.reserve(requestId,key,plan)); }
                catch(RuntimeException failure) { reservationFailure=failure;throw failure; }
                for(var attempt:phase.attempts()) available.computeIfAbsent(attempt.sku(),ignored->new ArrayDeque<>()).addLast(attempt);
            }
            var slots=available.get(sku);
            if(slots==null||slots.isEmpty()) throw new GoogleAccountingException("PHASE_PLAN_EXHAUSTED","Google operation exceeds its reviewed phase plan; no request was authorized.");
            pending.add(new Waiting(slots.removeFirst(),future));
            if(!scheduled) {
                scheduled=true;
                try { batcher.schedule(this::authorizeReady,2,TimeUnit.MILLISECONDS); }
                catch(RejectedExecutionException stopped) {
                    scheduled=false;authorizationFailure=new GoogleAccountingException("ACCOUNTING_UNAVAILABLE","Google authorization coordinator is stopping; no new provider request was sent.");
                    pending.forEach(w->w.future.completeExceptionally(authorizationFailure));pending.clear();throw authorizationFailure;
                }
            }
        }
        final GoogleUsageLedger.Authorization auth;
        try { auth=future.get(45,TimeUnit.SECONDS); }
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new GoogleAccountingException("DISPATCH_CANCELLED","Authorization wait interrupted; no provider request sent. Any uncertain authorization retains its charge.");}
        catch(TimeoutException failure) {throw new GoogleAccountingException("DISPATCH_TIMEOUT","Authorization wait expired; no provider request sent. Any uncertain authorization retains its charge.");}
        catch(ExecutionException failure) {
            if(failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new GoogleAccountingException("ACCOUNTING_UNAVAILABLE","Google authorization was not acknowledged; no provider request was sent.");
        }
        boolean success=false;
        if(Thread.currentThread().isInterrupted()) throw new GoogleAccountingException("DISPATCH_CANCELLED","Dispatch was cancelled after authorization; conservative charge retained.");
        try { T result=network.get(); success=true; return result; }
        finally { synchronized(this) { completed.add(new GoogleUsageLedger.Completion(auth,success)); } }
    }
    private void authorizeReady() {
        List<Waiting> work;
        synchronized(this) { work=List.copyOf(pending);pending.clear();scheduled=false; }
        try {
            synchronized(this) { if(authorizationFailure!=null) throw authorizationFailure; }
            var auth=measured("accountingAuthorization",()->ledger.authorize(phase.id(),work.stream().map(w->w.attempt.id()).toList()));
            var byId=new HashMap<UUID,GoogleUsageLedger.Authorization>();auth.forEach(a->byId.put(a.operationId(),a));
            work.forEach(w->w.future.complete(byId.get(w.attempt.id())));
        } catch(RuntimeException failure) {
            synchronized(this) { authorizationFailure=failure; }
            work.forEach(w->w.future.completeExceptionally(failure));
        }
    }
    private <T> T measured(String stage,Supplier<T> work) {
        long start=System.nanoTime();
        try { return work.get(); } finally { timing.accept(stage,System.nanoTime()-start); }
    }
    @Override public void close() {
        GoogleUsageLedger.Phase current; List<GoogleUsageLedger.Completion> outcomes;
        synchronized(this) { if(closed) return; closed=true; current=phase;outcomes=List.copyOf(completed); }
        if(current!=null) measured("accountingSettlement",()->{ledger.settle(current.id(),outcomes);return null;});
    }
}
