package com.ishita.tripcoordinatorplatform.ai.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one extraction call per booking plus one shared call, with bounded parallelism, and merges the results.
 * <p>
 * Failures are split by cause. Unusable <em>content</em> ({@link InvalidDocumentExtractionException}) becomes an
 * unresolved booking in the merge. Anything else (provider error, timeout, rate limit, configuration) is an
 * <em>infrastructure</em> failure: remaining calls are cancelled and the original exception is rethrown unchanged, so
 * the worker's existing classification, retry, lease and delivery rules apply. The calls write no state, so job and
 * attempt fencing is untouched; only the final fenced finalization persists anything.
 * <p>
 * An in-flight synchronous HTTP call cannot be aborted, and no call may outlive the handler. After an infrastructure
 * failure, calls that have not started are cancelled and the in-flight ones are awaited, bounded by the extraction cap
 * and the invocation budget (each call's own timeout is no longer than that). Only if that bound is exceeded are the
 * workers interrupted as a last resort. An interrupted caller interrupts its workers at once instead.
 */
public final class SplitTourExtractionService {
    private static final Logger log = LoggerFactory.getLogger(SplitTourExtractionService.class);
    private static final AtomicInteger POOLS = new AtomicInteger();

    private final AttractionTourDocumentExtractor extractor;
    private final String model;
    private final int maxParallel;
    private final int maxBookings;
    private final long minUsableMillis;
    private final long joinSlackMillis;

    public SplitTourExtractionService(AttractionTourDocumentExtractor extractor, String model, int maxParallel,
            int maxBookings, int minUsableSeconds) {
        this(extractor, model, maxParallel, maxBookings, minUsableSeconds * 1000L, 3_000L);
    }

    SplitTourExtractionService(AttractionTourDocumentExtractor extractor, String model, int maxParallel, int maxBookings,
            long minUsableMillis, long joinSlackMillis) {
        if (extractor == null || model == null || model.isBlank() || maxParallel < 1 || maxBookings < 2 || minUsableMillis < 0)
            throw new IllegalArgumentException("Invalid split extraction settings");
        this.extractor = extractor;
        this.model = model;
        this.maxParallel = maxParallel;
        this.maxBookings = maxBookings;
        this.minUsableMillis = minUsableMillis;
        this.joinSlackMillis = joinSlackMillis;
    }

    /** False when the invocation no longer leaves enough time for provider work; the caller then uses the single call. */
    public boolean hasTimeToSplit() {
        return DocumentProcessingBudget.usableExtractionMillis() >= minUsableMillis;
    }

    /** Why this document should use the single call instead, or null when splitting is possible. For logs and the gate. */
    public String skipReason(BookingInventory inventory) {
        if (!extractor.supportsSplitExtraction()) return "unsupported-extractor";
        if (inventory == null || inventory.count() == 0) return "no-inventory";
        if (!inventory.usable(maxBookings)) return "inventory-not-usable";
        if (!hasTimeToSplit()) return "insufficient-time";
        return null;
    }

    public SplitTourMerger.Merged extract(DocumentAiInput document, BookingInventory inventory) {
        return extract(document, inventory, finished -> { });
    }

    /**
     * Same as the two-argument version, and also tells {@code onBookingFinished} how many per-booking calls have finished so far
     * (1, 2, 3...) each time one does. It is only a progress signal for the screen: it is called from the waiting thread, never
     * from the call threads, and it changes nothing about what is extracted or merged.
     */
    public SplitTourMerger.Merged extract(DocumentAiInput document, BookingInventory inventory, IntConsumer onBookingFinished) {
        if (inventory == null || !inventory.usable(maxBookings))
            throw new IllegalArgumentException("Split extraction needs a usable booking inventory");
        long started = System.nanoTime();
        var source = extractor.prepareSplitSource(document);
        var targets = targets(inventory);
        var pool = Executors.newFixedThreadPool(Math.min(maxParallel, targets.size()), threadFactory());
        var completion = new ExecutorCompletionService<SplitOutcome>(pool);
        var futures = new ArrayList<Future<SplitOutcome>>();
        var aborted = new AtomicBoolean();
        boolean interruptWorkers = false;
        try {
            for (var target : targets)
                futures.add(completion.submit(DocumentProcessingBudget.propagate(() -> call(source, target, aborted))));
            var outcomes = new SplitOutcome[targets.size()];
            int bookingsFinished = 0;
            for (int received = 0; received < targets.size(); received++) {
                var outcome = await(completion);
                outcomes[index(outcome.target(), inventory)] = outcome;
                if (outcome.target().kind() != SplitCallTarget.Kind.SHARED) onBookingFinished.accept(++bookingsFinished);
            }
            var merged = SplitTourMerger.merge(source, model, inventory,
                    List.of(outcomes).subList(0, inventory.count()), outcomes[inventory.count()]);
            log.info("Document split total calls={} unresolved={} wallMs={}", targets.size(), merged.unresolvedOrdinals(),
                    (System.nanoTime() - started) / 1_000_000);
            return merged;
        } catch (RuntimeException | Error failure) {
            interruptWorkers = failure instanceof CallerInterrupted;
            futures.forEach(future -> future.cancel(false));
            throw failure;
        } finally {
            stop(pool, interruptWorkers);
        }
    }

    /** Content problems are outcomes; every other exception travels through the future as an infrastructure failure. */
    private SplitOutcome call(SplitSource source, SplitCallTarget target, AtomicBoolean aborted) {
        // A queued call must not start once another call has failed; the flag closes the gap before the waiter cancels it.
        if (aborted.get()) throw new CancellationException("Split extraction call skipped after a failure");
        try {
            return SplitOutcome.succeeded(extractor.extractPartialReading(source, target));
        } catch (InvalidDocumentExtractionException content) {
            log.warn("Document split call unusable target={} ordinal={} reason={}", target.kind(), target.ordinal(),
                    content.getMessage());
            return SplitOutcome.failed(target, content.getMessage());
        } catch (RuntimeException | Error failure) {
            aborted.set(true);
            throw failure;
        }
    }

    private SplitOutcome await(ExecutorCompletionService<SplitOutcome> completion) {
        try {
            return completion.take().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CallerInterrupted(interrupted);
        } catch (CancellationException cancelled) {
            throw new IllegalStateException("Split extraction call was cancelled", cancelled);
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Split extraction call failed", cause);
        }
    }

    /** The thread waiting for the calls was interrupted; unlike a call failure, this stops the workers immediately. */
    private static final class CallerInterrupted extends IllegalStateException {
        CallerInterrupted(InterruptedException cause) { super("Split extraction was interrupted", cause); }
    }

    private static List<SplitCallTarget> targets(BookingInventory inventory) {
        var targets = new ArrayList<SplitCallTarget>();
        for (var entry : inventory.bookings()) targets.add(SplitCallTarget.booking(entry.ordinal(), inventory));
        targets.add(SplitCallTarget.shared(inventory));
        return targets;
    }

    private static int index(SplitCallTarget target, BookingInventory inventory) {
        return target.kind() == SplitCallTarget.Kind.SHARED ? inventory.count() : target.ordinal();
    }

    /** Queued calls never start (their futures are cancelled); running calls are awaited unless the caller was interrupted. */
    private void stop(ExecutorService pool, boolean interruptWorkers) {
        if (interruptWorkers) pool.shutdownNow(); else pool.shutdown();
        long bound = interruptWorkers ? joinSlackMillis : DocumentProcessingBudget.extractionJoinMillis() + joinSlackMillis;
        try {
            if (pool.awaitTermination(bound, TimeUnit.MILLISECONDS)) return;
            log.error("Document split calls outlived the join bound boundMs={}; interrupting them", bound);
            pool.shutdownNow();
            pool.awaitTermination(joinSlackMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static java.util.concurrent.ThreadFactory threadFactory() {
        int pool = POOLS.incrementAndGet();
        var threads = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, "split-extraction-" + pool + "-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
